// One or more complete decoder transformer blocks as one NNAPI graph.
#include <android/log.h>
#include <dlfcn.h>
#include <math.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define TAG "NPUBLOCK"
#define MAX_DEVS 16
#define MAX_OPS 256
#define MAX_IO 16

typedef struct ANeuralNetworksModel ANeuralNetworksModel;
typedef struct ANeuralNetworksCompilation ANeuralNetworksCompilation;
typedef struct ANeuralNetworksExecution ANeuralNetworksExecution;
typedef struct ANeuralNetworksDevice ANeuralNetworksDevice;
typedef struct { int32_t type; uint32_t dimensionCount; const uint32_t* dimensions;
                 float scale; int32_t zeroPoint; } ANeuralNetworksOperandType;
enum { ANEURALNETWORKS_FLOAT32=0, ANEURALNETWORKS_INT32=1, ANEURALNETWORKS_TENSOR_FLOAT32=3,
       ANEURALNETWORKS_TENSOR_INT32=4, ANEURALNETWORKS_BOOL=6 };
enum { ANEURALNETWORKS_ADD=0, ANEURALNETWORKS_CONCATENATION=2,
       ANEURALNETWORKS_FULLY_CONNECTED=9, ANEURALNETWORKS_LOGISTIC=14,
       ANEURALNETWORKS_MUL=18, ANEURALNETWORKS_RESHAPE=22, ANEURALNETWORKS_SOFTMAX=25,
       ANEURALNETWORKS_MEAN=31, ANEURALNETWORKS_RSQRT=83, ANEURALNETWORKS_BATCH_MATMUL=102 };
enum { ANEURALNETWORKS_DURATION_ON_HARDWARE=0, ANEURALNETWORKS_DURATION_IN_DRIVER=1 };

static int (*ANeuralNetworks_getDeviceCount)(uint32_t*);
static int (*ANeuralNetworks_getDevice)(uint32_t, ANeuralNetworksDevice**);
static int (*ANeuralNetworksDevice_getName)(const ANeuralNetworksDevice*, const char**);
static int (*ANeuralNetworksModel_create)(ANeuralNetworksModel**);
static void (*ANeuralNetworksModel_free)(ANeuralNetworksModel*);
static int (*ANeuralNetworksModel_addOperand)(ANeuralNetworksModel*, const ANeuralNetworksOperandType*);
static int (*ANeuralNetworksModel_setOperandValue)(ANeuralNetworksModel*, int32_t, const void*, size_t);
static int (*ANeuralNetworksModel_addOperation)(ANeuralNetworksModel*, int32_t, uint32_t, const uint32_t*, uint32_t, const uint32_t*);
static int (*ANeuralNetworksModel_identifyInputsAndOutputs)(ANeuralNetworksModel*, uint32_t, const uint32_t*, uint32_t, const uint32_t*);
static int (*ANeuralNetworksModel_relaxComputationFloat32toFloat16)(ANeuralNetworksModel*, bool);
static int (*ANeuralNetworksModel_finish)(ANeuralNetworksModel*);
static int (*ANeuralNetworksModel_getSupportedOperationsForDevices)(const ANeuralNetworksModel*, const ANeuralNetworksDevice* const*, uint32_t, bool*);
static int (*ANeuralNetworksCompilation_createForDevices)(ANeuralNetworksModel*, const ANeuralNetworksDevice* const*, uint32_t, ANeuralNetworksCompilation**);
static void (*ANeuralNetworksCompilation_free)(ANeuralNetworksCompilation*);
static int (*ANeuralNetworksCompilation_finish)(ANeuralNetworksCompilation*);
static int (*ANeuralNetworksExecution_create)(ANeuralNetworksCompilation*, ANeuralNetworksExecution**);
static void (*ANeuralNetworksExecution_free)(ANeuralNetworksExecution*);
static int (*ANeuralNetworksExecution_setInput)(ANeuralNetworksExecution*, int32_t, const ANeuralNetworksOperandType*, const void*, size_t);
static int (*ANeuralNetworksExecution_setOutput)(ANeuralNetworksExecution*, int32_t, const ANeuralNetworksOperandType*, void*, size_t);
static int (*ANeuralNetworksExecution_setMeasureTiming)(ANeuralNetworksExecution*, bool);
static int (*ANeuralNetworksExecution_getDuration)(const ANeuralNetworksExecution*, int32_t, uint64_t*);
static int (*ANeuralNetworksExecution_compute)(ANeuralNetworksExecution*);
static void* g_lib;

static void out(const char* fmt, ...) { char s[2048]; va_list a; va_start(a,fmt); vsnprintf(s,sizeof s,fmt,a); va_end(a); __android_log_print(ANDROID_LOG_INFO,TAG,"%s",s); puts(s); fflush(stdout); }
static double now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return t.tv_sec*1000.0+t.tv_nsec/1e6; }
static uint32_t g_seed=1;
static float rnd(void) { g_seed=g_seed*1664525u+1013904223u; return (float)(g_seed>>8)/8388608.0f-1.0f; }
static void fill(float* p,size_t n,float s) { for(size_t i=0;i<n;i++) p[i]=rnd()*s; }
static void* alloc(size_t n) { void* p=malloc(n); if(!p) { out("sin memoria para %zu bytes",n); exit(1); } return p; }

static int load_symbols(void) { int bad=0;
#define LOAD(x) do { if (!(x=dlsym(g_lib,#x))) { out("falta el simbolo %s",#x); bad++; } } while(0)
 LOAD(ANeuralNetworks_getDeviceCount); LOAD(ANeuralNetworks_getDevice); LOAD(ANeuralNetworksDevice_getName);
 LOAD(ANeuralNetworksModel_create); LOAD(ANeuralNetworksModel_free); LOAD(ANeuralNetworksModel_addOperand); LOAD(ANeuralNetworksModel_setOperandValue); LOAD(ANeuralNetworksModel_addOperation); LOAD(ANeuralNetworksModel_identifyInputsAndOutputs); LOAD(ANeuralNetworksModel_relaxComputationFloat32toFloat16); LOAD(ANeuralNetworksModel_finish); LOAD(ANeuralNetworksModel_getSupportedOperationsForDevices); LOAD(ANeuralNetworksCompilation_createForDevices); LOAD(ANeuralNetworksCompilation_free); LOAD(ANeuralNetworksCompilation_finish); LOAD(ANeuralNetworksExecution_create); LOAD(ANeuralNetworksExecution_free); LOAD(ANeuralNetworksExecution_setInput); LOAD(ANeuralNetworksExecution_setOutput); LOAD(ANeuralNetworksExecution_setMeasureTiming); LOAD(ANeuralNetworksExecution_getDuration); LOAD(ANeuralNetworksExecution_compute);
#undef LOAD
 return bad; }

typedef struct { ANeuralNetworksModel* m; uint32_t next, nops, ops[MAX_OPS]; int e; } Builder;
static bool model_begin(Builder* b) { memset(b,0,sizeof *b); b->e=ANeuralNetworksModel_create(&b->m); return !b->e && b->m; }
static uint32_t operand(Builder* b,int32_t t,uint32_t r,const uint32_t* d) { ANeuralNetworksOperandType x={t,r,d,0,0}; b->e|=ANeuralNetworksModel_addOperand(b->m,&x); return b->next++; }
static uint32_t tensor(Builder* b,uint32_t r,const uint32_t* d) { return operand(b,ANEURALNETWORKS_TENSOR_FLOAT32,r,d); }
static uint32_t value(Builder* b,uint32_t i,const void* p,size_t n) { b->e|=ANeuralNetworksModel_setOperandValue(b->m,(int32_t)i,p,n); return i; }
static uint32_t iscalar(Builder* b,int32_t v) { return value(b,operand(b,ANEURALNETWORKS_INT32,0,NULL),&v,sizeof v); }
static uint32_t fscalar(Builder* b,float v) { return value(b,operand(b,ANEURALNETWORKS_FLOAT32,0,NULL),&v,sizeof v); }
// ADD and MUL take tensors only: a constant goes in as a one-element tensor and broadcasts.
static uint32_t fone(Builder* b,float v) { uint32_t one=1; return value(b,operand(b,ANEURALNETWORKS_TENSOR_FLOAT32,1,&one),&v,sizeof v); }
static uint32_t bscalar(Builder* b,bool v) { uint8_t x=v; return value(b,operand(b,ANEURALNETWORKS_BOOL,0,NULL),&x,sizeof x); }
static uint32_t ivec(Builder* b,const int32_t* p,uint32_t n) { return value(b,operand(b,ANEURALNETWORKS_TENSOR_INT32,1,&n),p,n*sizeof *p); }
static uint32_t op(Builder* b,int32_t t,uint32_t n,const uint32_t* in,uint32_t result) { b->e|=ANeuralNetworksModel_addOperation(b->m,t,n,in,1,&result); if(b->nops<MAX_OPS)b->ops[b->nops++]=t; return result; }
static ANeuralNetworksModel* model_end(Builder* b,uint32_t ni,const uint32_t* in,uint32_t no,const uint32_t* outs) { b->e|=ANeuralNetworksModel_identifyInputsAndOutputs(b->m,ni,in,no,outs); if(!b->e)b->e|=ANeuralNetworksModel_relaxComputationFloat32toFloat16(b->m,true); if(!b->e)b->e|=ANeuralNetworksModel_finish(b->m); if(b->e){out("construccion FALLO (%d)",b->e); ANeuralNetworksModel_free(b->m); return NULL;} return b->m; }

typedef struct { uint32_t D,H,KVH,HD,G,F,T; float *wq,*wk,*wv,*wo,*w1,*w3,*w2,*zero,*gain,*c1,*c2; } Block;
static void block_data(Block* z,uint32_t d,uint32_t h,uint32_t kvh,uint32_t f,uint32_t t) { memset(z,0,sizeof *z); z->D=d;z->H=h;z->KVH=kvh;z->HD=d/h;z->G=h/kvh;z->F=f;z->T=t; size_t q=(size_t)d*d, kv=(size_t)kvh*z->HD*d, ff=(size_t)f*d, out=(size_t)d*f, hd=(size_t)kvh*z->G*z->HD, kh=(size_t)kvh*z->HD; z->wq=alloc(q*4);z->wk=alloc(kv*4);z->wv=alloc(kv*4);z->wo=alloc(q*4);z->w1=alloc(ff*4);z->w3=alloc(ff*4);z->w2=alloc(out*4);z->zero=calloc(d>f?d:f,4);z->gain=alloc(d*4);z->c1=alloc(hd*4);z->c2=alloc(hd*4); if(!z->zero)exit(1); fill(z->wq,q,.02f);fill(z->wk,kv,.02f);fill(z->wv,kv,.02f);fill(z->wo,q,.02f);fill(z->w1,ff,.02f);fill(z->w3,ff,.02f);fill(z->w2,out,.02f);for(uint32_t i=0;i<d;i++)z->gain[i]=1;fill(z->c1,hd,.02f);fill(z->c2,hd,.02f); (void)kh; }
static void free_block(Block* z){free(z->wq);free(z->wk);free(z->wv);free(z->wo);free(z->w1);free(z->w3);free(z->w2);free(z->zero);free(z->gain);free(z->c1);free(z->c2);}
static uint32_t cnst(Builder*b,const uint32_t*d,uint32_t r,const float*p,size_t n){return value(b,tensor(b,r,d),p,n*sizeof *p);}
static uint32_t rms(Builder*b,Block*z,uint32_t x){uint32_t xd[]={1,z->D},rd[]={1,1};int32_t axis=-1;uint32_t a[]={x,x,iscalar(b,0)},sq=op(b,ANEURALNETWORKS_MUL,3,a,tensor(b,2,xd));uint32_t mi[]={sq,ivec(b,&axis,1),iscalar(b,1)},mean=op(b,ANEURALNETWORKS_MEAN,3,mi,tensor(b,2,rd));uint32_t ad[]={mean,fone(b,1e-5f),iscalar(b,0)},sum=op(b,ANEURALNETWORKS_ADD,3,ad,tensor(b,2,rd));uint32_t inv=op(b,ANEURALNETWORKS_RSQRT,1,&sum,tensor(b,2,rd));uint32_t sc[]={x,inv,iscalar(b,0)},norm=op(b,ANEURALNETWORKS_MUL,3,sc,tensor(b,2,xd));uint32_t gain=cnst(b,&z->D,1,z->gain,z->D),ga[]={norm,gain,iscalar(b,0)};return op(b,ANEURALNETWORKS_MUL,3,ga,tensor(b,2,xd));}
static uint32_t fc(Builder*b,Block*z,uint32_t x,const float*w,uint32_t rows,uint32_t cols){uint32_t wd[]={rows,cols},bd[]={rows},od[]={1,rows};uint32_t a[]={x,cnst(b,wd,2,w,(size_t)rows*cols),cnst(b,bd,1,z->zero,rows),iscalar(b,0)};return op(b,ANEURALNETWORKS_FULLY_CONNECTED,4,a,tensor(b,2,od));}
typedef struct { uint32_t y,k,v; } BlockOut;
static BlockOut add_block(Builder*b,Block*z,uint32_t x,uint32_t kc,uint32_t vc,uint32_t mask){uint32_t qd[]={z->KVH,z->G,z->HD},kd[]={z->KVH,1,z->HD},fulld[]={z->KVH,z->T+1,z->HD},attd[]={z->KVH,z->G,z->T+1},xd[]={1,z->D},fd[]={1,z->F};int32_t qs[]={ (int32_t)z->KVH,(int32_t)z->G,(int32_t)z->HD},ks[]={ (int32_t)z->KVH,1,(int32_t)z->HD},xs[]={1,(int32_t)z->D};uint32_t xn=rms(b,z,x),q0=fc(b,z,xn,z->wq,z->D,z->D),k0=fc(b,z,xn,z->wk,z->KVH*z->HD,z->D),v=fc(b,z,xn,z->wv,z->KVH*z->HD,z->D);uint32_t qa[]={q0,ivec(b,qs,3)},q=op(b,ANEURALNETWORKS_RESHAPE,2,qa,tensor(b,3,qd));uint32_t ka[]={k0,ivec(b,ks,3)},k=op(b,ANEURALNETWORKS_RESHAPE,2,ka,tensor(b,3,kd));uint32_t vr[]={v,ivec(b,ks,3)};v=op(b,ANEURALNETWORKS_RESHAPE,2,vr,tensor(b,3,kd));uint32_t cqd[]={z->KVH,z->G,z->HD},ckd[]={z->KVH,1,z->HD};uint32_t r1[]={q,cnst(b,cqd,3,z->c1,(size_t)z->KVH*z->G*z->HD),iscalar(b,0)},r2[]={q,cnst(b,cqd,3,z->c2,(size_t)z->KVH*z->G*z->HD),iscalar(b,0)},qr[]={op(b,ANEURALNETWORKS_MUL,3,r1,tensor(b,3,qd)),op(b,ANEURALNETWORKS_MUL,3,r2,tensor(b,3,qd)),iscalar(b,0)};q=op(b,ANEURALNETWORKS_ADD,3,qr,tensor(b,3,qd));uint32_t t1[]={k,cnst(b,ckd,3,z->c1,(size_t)z->KVH*z->HD),iscalar(b,0)},t2[]={k,cnst(b,ckd,3,z->c2,(size_t)z->KVH*z->HD),iscalar(b,0)},kr[]={op(b,ANEURALNETWORKS_MUL,3,t1,tensor(b,3,kd)),op(b,ANEURALNETWORKS_MUL,3,t2,tensor(b,3,kd)),iscalar(b,0)};k=op(b,ANEURALNETWORKS_ADD,3,kr,tensor(b,3,kd));uint32_t ca[]={kc,k,iscalar(b,1)},kf=op(b,ANEURALNETWORKS_CONCATENATION,3,ca,tensor(b,3,fulld));uint32_t va[]={vc,v,iscalar(b,1)},vf=op(b,ANEURALNETWORKS_CONCATENATION,3,va,tensor(b,3,fulld));uint32_t ma[]={q,kf,bscalar(b,false),bscalar(b,true)},att=op(b,ANEURALNETWORKS_BATCH_MATMUL,4,ma,tensor(b,3,attd));uint32_t sa[]={att,fone(b,1.0f/sqrtf((float)z->HD)),iscalar(b,0)};att=op(b,ANEURALNETWORKS_MUL,3,sa,tensor(b,3,attd));uint32_t aa[]={att,mask,iscalar(b,0)};att=op(b,ANEURALNETWORKS_ADD,3,aa,tensor(b,3,attd));uint32_t sm[]={att,fscalar(b,1)},p=op(b,ANEURALNETWORKS_SOFTMAX,2,sm,tensor(b,3,attd));uint32_t oa[]={p,vf,bscalar(b,false),bscalar(b,false)},o=op(b,ANEURALNETWORKS_BATCH_MATMUL,4,oa,tensor(b,3,qd));uint32_t ra[]={o,ivec(b,xs,2)},flat=op(b,ANEURALNETWORKS_RESHAPE,2,ra,tensor(b,2,xd));uint32_t proj=fc(b,z,flat,z->wo,z->D,z->D),ha[]={x,proj,iscalar(b,0)},h=op(b,ANEURALNETWORKS_ADD,3,ha,tensor(b,2,xd));uint32_t hn=rms(b,z,h),a=fc(b,z,hn,z->w1,z->F,z->D),bb=fc(b,z,hn,z->w3,z->F,z->D),la=op(b,ANEURALNETWORKS_LOGISTIC,1,&a,tensor(b,2,fd));uint32_t si[]={a,la,iscalar(b,0)},s=op(b,ANEURALNETWORKS_MUL,3,si,tensor(b,2,fd));uint32_t mm[]={s,bb,iscalar(b,0)},m=op(b,ANEURALNETWORKS_MUL,3,mm,tensor(b,2,fd));uint32_t y0=fc(b,z,m,z->w2,z->D,z->F),ya[]={h,y0,iscalar(b,0)};BlockOut r={op(b,ANEURALNETWORKS_ADD,3,ya,tensor(b,2,xd)),k,v};return r;}

typedef struct { const char* name; uint32_t D,H,KVH,F; } Spec;
static const char* opname(uint32_t x){switch(x){case 0:return"ADD";case 2:return"CONCAT";case 9:return"FC";case 14:return"LOGISTIC";case 18:return"MUL";case 22:return"RESHAPE";case 25:return"SOFTMAX";case 31:return"MEAN";case 83:return"RSQRT";case 102:return"BATCH_MATMUL";default:return"?";}}
static void run_one(const Spec*s,uint32_t T,uint32_t nb,const ANeuralNetworksDevice*dev,const char*dn){
 Block*z=calloc(nb,sizeof*z); Builder b; uint32_t in[MAX_IO],outs[MAX_IO],ni=0,no=0; BlockOut bo[4];
 for(uint32_t i=0;i<nb;i++)block_data(&z[i],s->D,s->H,s->KVH,s->F,T);
 if(!model_begin(&b)){out("%s D=%u T=%u NB=%u ops=0 supported=0 dev=%s compile=FALLO",s->name,s->D,T,nb,dn);goto done;}
 uint32_t xd[]={1,s->D},cd[]={s->KVH,T,s->D/s->H},md[]={1,1,T+1}; uint32_t x=tensor(&b,2,xd); in[ni++]=x; uint32_t kc[4],vc[4];
 for(uint32_t i=0;i<nb;i++){kc[i]=tensor(&b,3,cd);vc[i]=tensor(&b,3,cd);in[ni++]=kc[i];in[ni++]=vc[i];}
 uint32_t mask=tensor(&b,3,md);in[ni++]=mask; for(uint32_t i=0;i<nb;i++)bo[i]=add_block(&b,&z[i],i?bo[i-1].y:x,kc[i],vc[i],mask);
 outs[no++]=bo[nb-1].y; for(uint32_t i=0;i<nb;i++){outs[no++]=bo[i].k;outs[no++]=bo[i].v;}
 ANeuralNetworksModel*m=model_end(&b,ni,in,no,outs); if(!m){out("%s D=%u T=%u NB=%u ops=%u supported=? dev=%s compile=FALLO",s->name,s->D,T,nb,b.nops,dn);goto done;}
 bool ok[MAX_OPS]; const ANeuralNetworksDevice*one[]={dev}; int e=ANeuralNetworksModel_getSupportedOperationsForDevices(m,one,1,ok);uint32_t yes=0;char missing[1024]={0};size_t at=0;
 if(!e)for(uint32_t i=0;i<b.nops;i++){if(ok[i])yes++;else at+=(size_t)snprintf(missing+at,sizeof missing-at,"%s%u:%s",at?",":"",i,opname(b.ops[i]));}
 if(e||yes!=b.nops){out("%s D=%u T=%u NB=%u ops=%u supported=%u rejected=%s dev=%s compile=NO",s->name,s->D,T,nb,b.nops,yes,e?"consulta-fallo":missing,dn);ANeuralNetworksModel_free(m);goto done;}
 ANeuralNetworksCompilation*c=NULL;double t0=now_ms();e=ANeuralNetworksCompilation_createForDevices(m,one,1,&c);if(!e)e=ANeuralNetworksCompilation_finish(c);double ct=now_ms()-t0;
 if(e||!c){out("%s D=%u T=%u NB=%u ops=%u supported=%u dev=%s compile=%d compile_ms=%.3f",s->name,s->D,T,nb,b.nops,yes,dn,e,ct);if(c)ANeuralNetworksCompilation_free(c);ANeuralNetworksModel_free(m);goto done;}
 float** ip=calloc(ni,sizeof*ip);size_t* ib=calloc(ni,sizeof*ib);float** optr=calloc(no,sizeof*optr);size_t* ob=calloc(no,sizeof*ob);for(uint32_t i=0;i<ni;i++){uint32_t n=i==0?s->D:(i==ni-1?T+1:(uint32_t)((size_t)s->KVH*T*(s->D/s->H)));ip[i]=calloc(n,sizeof(float));ib[i]=(size_t)n*4;if(i!=ni-1)fill(ip[i],n,0.5f);}for(uint32_t i=0;i<no;i++){uint32_t n=i==0?s->D:s->KVH*(s->D/s->H);optr[i]=calloc(n,sizeof(float));ob[i]=(size_t)n*4;}
 double sum=0,hw=0;int hws=0;int runerr=0;for(int r=0;r<23&&!runerr;r++){ANeuralNetworksExecution*xn=NULL;uint64_t ns=UINT64_MAX;runerr=ANeuralNetworksExecution_create(c,&xn);for(uint32_t i=0;i<ni&&!runerr;i++)runerr=ANeuralNetworksExecution_setInput(xn,i,NULL,ip[i],ib[i]);for(uint32_t i=0;i<no&&!runerr;i++)runerr=ANeuralNetworksExecution_setOutput(xn,i,NULL,optr[i],ob[i]);if(!runerr)runerr=ANeuralNetworksExecution_setMeasureTiming(xn,true);double a=now_ms();if(!runerr)runerr=ANeuralNetworksExecution_compute(xn);double elapsed=now_ms()-a;if(!runerr)ANeuralNetworksExecution_getDuration(xn,ANEURALNETWORKS_DURATION_ON_HARDWARE,&ns);if(r>=3)sum+=elapsed;if(r>=3&&ns!=UINT64_MAX){hw+=ns/1e6;hws++;}if(xn)ANeuralNetworksExecution_free(xn);}
 bool finite=true,nonzero=false;for(uint32_t i=0;i<no;i++)for(size_t j=0;j<ob[i]/4;j++){if(!isfinite(optr[i][j]))finite=false;if(optr[i][j]!=0)nonzero=true;}out("%s D=%u T=%u NB=%u ops=%u supported=%u dev=%s compile=0 compile_ms=%.3f exec_ms=%s%.3f hw_ms=%s%.3f outputs=%s",s->name,s->D,T,nb,b.nops,yes,dn,ct,runerr?"ERR ":"",runerr?0:sum/20, hws?"":"NA ",hws?hw/hws:0,finite&&nonzero?"finite-nonzero":"BAD");
 for(uint32_t i=0;i<ni;i++)free(ip[i]);for(uint32_t i=0;i<no;i++)free(optr[i]);free(ip);free(ib);free(optr);free(ob);ANeuralNetworksCompilation_free(c);ANeuralNetworksModel_free(m);
done:for(uint32_t i=0;i<nb;i++)free_block(&z[i]);free(z);}
static bool wanted(const char*a,const char*n){return !a||!strcmp(a,n);}
int main(int argc,char**argv){const char*only=argc>1?argv[1]:NULL;uint32_t onlyt=argc>2?(uint32_t)strtoul(argv[2],NULL,10):0;bool mdla=false,neuron=false;g_lib=dlopen("libneuralnetworks.so",RTLD_NOW);if(!g_lib)g_lib=dlopen("/apex/com.android.neuralnetworks/lib64/libneuralnetworks.so",RTLD_NOW);if(!g_lib||load_symbols()){out("FIN");return 1;}uint32_t n=0;ANeuralNetworks_getDeviceCount(&n);const Spec sp[]={{"qwen15",1536,12,2,8960},{"llama1b",2048,32,8,8192},{"llama3b",3072,24,8,8192}};for(uint32_t d=0;d<n;d++){ANeuralNetworksDevice*dv=NULL;const char*dn="?";ANeuralNetworks_getDevice(d,&dv);ANeuralNetworksDevice_getName(dv,&dn);if(!strncmp(dn,"mtk-mdla_shim",13))mdla=true;else if(!strncmp(dn,"mtk-neuron_shim",15))neuron=true;else continue;for(uint32_t i=0;i<3;i++)if(wanted(only,sp[i].name)){uint32_t ts[]={256,1024};for(uint32_t j=0;j<2;j++)if(!onlyt||onlyt==ts[j])run_one(&sp[i],ts[j],1,dv,dn);if(!strcmp(sp[i].name,"qwen15")&&(!onlyt||onlyt==256)){run_one(&sp[i],256,2,dv,dn);run_one(&sp[i],256,4,dv,dn);}}}if(!mdla)out("dispositivo mtk-mdla_shim no existe");if(!neuron)out("dispositivo mtk-neuron_shim no existe");out("FIN");return 0;}
