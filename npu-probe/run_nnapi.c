/* Inference for Llama-2 Transformer model in pure C, with the matmuls run through NNAPI (Android) */
/* Reads the fp32 checkpoints of run.c and the Q8_0 (int8) ones of runq.c */

#include <stdio.h>
#include <stdlib.h>
#include <ctype.h>
#include <time.h>
#include <math.h>
#include <string.h>
#include <fcntl.h>
#include <errno.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#if defined _WIN32
    #include "win.h"
#else
    #include <unistd.h>
    #include <dlfcn.h>
    #include <sys/mman.h>
    #include <sys/stat.h>
#endif
// ----------------------------------------------------------------------------
// Globals
int GS = 0; // group size global for quantization of the weights. 0 = the checkpoint is fp32

// ----------------------------------------------------------------------------
// Transformer model

typedef struct {
    int dim; // transformer dimension
    int hidden_dim; // for ffn layers
    int n_layers; // number of layers
    int n_heads; // number of query heads
    int n_kv_heads; // number of key/value heads (can be < query heads because of multiquery)
    int vocab_size; // vocabulary size, usually 256 (byte-level)
    int seq_len; // max sequence length
} Config;

typedef struct {
    // one weight matrix, in the format of the checkpoint it comes from
    float* f;  // fp32 values. NULL in a Q8_0 checkpoint, which has the two below instead
    int8_t* q; // quantized values
    float* s;  // scaling factors, one per group of GS values
} Matrix;

typedef struct {
    // token embedding table
    Matrix* token_embedding_table;    // (vocab_size, dim)
    // weights for rmsnorms
    float* rms_att_weight; // (layer, dim) rmsnorm weights
    float* rms_ffn_weight; // (layer, dim)
    // weights for matmuls, one Matrix per layer. note dim == n_heads * head_size
    Matrix* wq; // (layer, dim, n_heads * head_size)
    Matrix* wk; // (layer, dim, n_kv_heads * head_size)
    Matrix* wv; // (layer, dim, n_kv_heads * head_size)
    Matrix* wo; // (layer, n_heads * head_size, dim)
    // weights for ffn
    Matrix* w1; // (layer, hidden_dim, dim)
    Matrix* w2; // (layer, dim, hidden_dim)
    Matrix* w3; // (layer, hidden_dim, dim)
    // final rmsnorm
    float* rms_final_weight; // (dim,)
    // (optional) classifier weights for the logits, on the last layer
    Matrix* wcls;
} TransformerWeights;

typedef struct {
    // current wave of activations
    float *x; // activation at current time stamp (dim,)
    float *xb; // same, but inside a residual branch (dim,)
    float *xb2; // an additional buffer just for convenience (dim,)
    float *hb; // buffer for hidden dimension in the ffn (hidden_dim,)
    float *hb2; // buffer for hidden dimension in the ffn (hidden_dim,)
    float *q; // query (dim,)
    float *k; // key (dim,)
    float *v; // value (dim,)
    float *att; // buffer for scores/attention values (n_heads, seq_len)
    float *logits; // output logits
    // kv cache
    float* key_cache;   // (layer, seq_len, dim)
    float* value_cache; // (layer, seq_len, dim)
} RunState;

typedef struct {
    Config config; // the hyperparameters of the architecture (the blueprint)
    TransformerWeights weights; // the weights of the model
    RunState state; // buffers for the "wave" of activations in the forward pass
    // some more state needed to properly clean up the memory mapping (sigh)
    int fd; // file descriptor for memory mapping
    void* data; // memory mapped data pointer
    ssize_t file_size; // size of the checkpoint file in bytes
} Transformer;

void malloc_run_state(RunState* s, Config* p) {
    // we calloc instead of malloc to keep valgrind happy
    int kv_dim = (p->dim * p->n_kv_heads) / p->n_heads;
    s->x = calloc(p->dim, sizeof(float));
    s->xb = calloc(p->dim, sizeof(float));
    s->xb2 = calloc(p->dim, sizeof(float));
    s->hb = calloc(p->hidden_dim, sizeof(float));
    s->hb2 = calloc(p->hidden_dim, sizeof(float));
    s->q = calloc(p->dim, sizeof(float));
    s->key_cache = calloc(p->n_layers * p->seq_len * kv_dim, sizeof(float));
    s->value_cache = calloc(p->n_layers * p->seq_len * kv_dim, sizeof(float));
    s->att = calloc(p->n_heads * p->seq_len, sizeof(float));
    s->logits = calloc(p->vocab_size, sizeof(float));
    // ensure all mallocs went fine
    if (!s->x || !s->xb || !s->xb2 || !s->hb || !s->hb2 || !s->q
     || !s->key_cache || !s->value_cache || !s->att || !s->logits) {
        fprintf(stderr, "malloc failed!\n");
        exit(EXIT_FAILURE);
    }
}

void free_run_state(RunState* s) {
    free(s->x);
    free(s->xb);
    free(s->xb2);
    free(s->hb);
    free(s->hb2);
    free(s->q);
    free(s->att);
    free(s->logits);
    free(s->key_cache);
    free(s->value_cache);
}

float* map_floats(void** ptr, size_t count) {
    // `count` fp32 values starting from the memory pointed at by *ptr
    float* res = *ptr;
    *ptr = res + count;
    return res;
}

Matrix* map_matrices(void** ptr, int n, size_t size_each) {
    // initialize `n` matrices (with `size_each` elements), starting from the memory pointed at by *ptr
    char* p = *ptr;
    Matrix* res = calloc(n, sizeof(Matrix));
    if (!res) { fprintf(stderr, "malloc failed!\n"); exit(EXIT_FAILURE); }
    for (int i = 0; i < n; i++) {
        if (GS == 0) {
            res[i].f = (float*)p;
            p += size_each * sizeof(float);
        } else {
            // map quantized int8 values
            res[i].q = (int8_t*)p;
            p += size_each;
            // map scale factors
            res[i].s = (float*)p;
            p += size_each / GS * sizeof(float);
        }
    }
    *ptr = p; // advance ptr to current position
    return res;
}

void memory_map_weights(TransformerWeights *w, Config* p, void* ptr, int shared_weights) {
    int head_size = p->dim / p->n_heads;
    // make sure the multiplications below are done in 64bit to fit the parameter counts of 13B+ models
    size_t dim = p->dim;
    size_t hidden_dim = p->hidden_dim;
    size_t n_layers = p->n_layers;
    size_t q_dim = p->n_heads * head_size;
    size_t kv_dim = p->n_kv_heads * head_size;
    if (GS == 0) {
        // fp32 checkpoint: everything is fp32, in this order
        w->token_embedding_table = map_matrices(&ptr, 1, p->vocab_size * dim);
        w->rms_att_weight = map_floats(&ptr, n_layers * dim);
        w->wq = map_matrices(&ptr, p->n_layers, dim * q_dim);
        w->wk = map_matrices(&ptr, p->n_layers, dim * kv_dim);
        w->wv = map_matrices(&ptr, p->n_layers, dim * kv_dim);
        w->wo = map_matrices(&ptr, p->n_layers, q_dim * dim);
        w->rms_ffn_weight = map_floats(&ptr, n_layers * dim);
        w->w1 = map_matrices(&ptr, p->n_layers, dim * hidden_dim);
        w->w2 = map_matrices(&ptr, p->n_layers, hidden_dim * dim);
        w->w3 = map_matrices(&ptr, p->n_layers, dim * hidden_dim);
        w->rms_final_weight = map_floats(&ptr, dim);
        map_floats(&ptr, p->seq_len * head_size / 2); // skip what used to be freq_cis_real (for RoPE)
        map_floats(&ptr, p->seq_len * head_size / 2); // skip what used to be freq_cis_imag (for RoPE)
    } else {
        // Q8_0 checkpoint: first are the parameters that are kept in fp32 (the rmsnorm (1D) weights)
        w->rms_att_weight = map_floats(&ptr, n_layers * dim);
        w->rms_ffn_weight = map_floats(&ptr, n_layers * dim);
        w->rms_final_weight = map_floats(&ptr, dim);
        // now read all the quantized weights
        w->token_embedding_table = map_matrices(&ptr, 1, p->vocab_size * dim);
        w->wq = map_matrices(&ptr, p->n_layers, dim * q_dim);
        w->wk = map_matrices(&ptr, p->n_layers, dim * kv_dim);
        w->wv = map_matrices(&ptr, p->n_layers, dim * kv_dim);
        w->wo = map_matrices(&ptr, p->n_layers, q_dim * dim);
        w->w1 = map_matrices(&ptr, p->n_layers, dim * hidden_dim);
        w->w2 = map_matrices(&ptr, p->n_layers, hidden_dim * dim);
        w->w3 = map_matrices(&ptr, p->n_layers, dim * hidden_dim);
    }
    w->wcls = shared_weights ? w->token_embedding_table : map_matrices(&ptr, 1, p->vocab_size * dim);
}

void read_checkpoint(char* checkpoint, Config* config, TransformerWeights* weights,
                     int* fd, void** data, ssize_t* file_size) {
    FILE *file = fopen(checkpoint, "rb");
    if (!file) { fprintf(stderr, "Couldn't open file %s\n", checkpoint); exit(EXIT_FAILURE); }
    // a Q8_0 checkpoint (version 2 of export.py, the one runq.c reads) starts with a magic number
    // (uint32), 0x616b3432, i.e. "ak42" in ASCII. an fp32 one starts with the Config itself
    uint32_t magic_number;
    if (fread(&magic_number, sizeof(uint32_t), 1, file) != 1) { exit(EXIT_FAILURE); }
    size_t header_size = sizeof(Config);
    int shared_weights;
    if (magic_number == 0x616b3432) {
        // read in the version number (uint32), has to be 2
        int version;
        if (fread(&version, sizeof(int), 1, file) != 1) { exit(EXIT_FAILURE); }
        if (version != 2) { fprintf(stderr, "Bad version %d, need version 2\n", version); exit(EXIT_FAILURE); }
        header_size = 256; // the header size for version 2 in bytes
        // read in the Config
        if (fread(config, sizeof(Config), 1, file) != 1) { exit(EXIT_FAILURE); }
        // read in flags
        uint8_t shared_classifier; // a byte to indicate if the classifier is shared
        if (fread(&shared_classifier, sizeof(uint8_t), 1, file) != 1) { exit(EXIT_FAILURE); }
        int group_size; // the group size used in quantization
        if (fread(&group_size, sizeof(int), 1, file) != 1) { exit(EXIT_FAILURE); }
        // the groups have to fit in the rows of every matrix
        if (group_size <= 0 || config->dim % group_size != 0 || config->hidden_dim % group_size != 0) {
            fprintf(stderr, "Bad group size %d\n", group_size);
            exit(EXIT_FAILURE);
        }
        GS = group_size; // set as global, as it will be used in many places
        shared_weights = shared_classifier;
    } else {
        // read in the config header
        rewind(file);
        if (fread(config, sizeof(Config), 1, file) != 1) { exit(EXIT_FAILURE); }
        // negative vocab size is hacky way of signaling unshared weights. bit yikes.
        shared_weights = config->vocab_size > 0 ? 1 : 0;
        config->vocab_size = abs(config->vocab_size);
    }
    // figure out the file size
    fseek(file, 0, SEEK_END); // move file pointer to end of file
    *file_size = ftell(file); // get the file size, in bytes
    fclose(file);
    // memory map the Transformer weights into the data pointer
    *fd = open(checkpoint, O_RDONLY); // open in read only mode
    if (*fd == -1) { fprintf(stderr, "open failed!\n"); exit(EXIT_FAILURE); }
    *data = mmap(NULL, *file_size, PROT_READ, MAP_PRIVATE, *fd, 0);
    if (*data == MAP_FAILED) { fprintf(stderr, "mmap failed!\n"); exit(EXIT_FAILURE); }
    void* weights_ptr = (char*)*data + header_size; // skip header bytes. char is 1 byte
    memory_map_weights(weights, config, weights_ptr, shared_weights);
}

void build_transformer(Transformer *t, char* checkpoint_path) {
    // read in the Config and the Weights from the checkpoint
    read_checkpoint(checkpoint_path, &t->config, &t->weights, &t->fd, &t->data, &t->file_size);
    // allocate the RunState buffers
    malloc_run_state(&t->state, &t->config);
}

void free_transformer(Transformer* t) {
    // free the Matrix structs: what they point at is in the memory mapping
    TransformerWeights* w = &t->weights;
    if (w->wcls != w->token_embedding_table) { free(w->wcls); }
    free(w->token_embedding_table);
    free(w->wq);
    free(w->wk);
    free(w->wv);
    free(w->wo);
    free(w->w1);
    free(w->w2);
    free(w->w3);
    // close the memory mapping
    if (t->data != MAP_FAILED) { munmap(t->data, t->file_size); }
    if (t->fd != -1) { close(t->fd); }
    // free the RunState buffers
    free_run_state(&t->state);
}

// ----------------------------------------------------------------------------
// Quantization functions

void token_embedding(float* x, Matrix* table, int token, int dim) {
    // copy the embedding of a token into x. a Q8_0 table is dequantized one row at a time
    size_t start = (size_t)token * dim;
    if (table->f) {
        memcpy(x, table->f + start, dim * sizeof(*x));
        return;
    }
    for (int i = 0; i < dim; i++) {
        x[i] = table->q[start + i] * table->s[(start + i) / GS];
    }
}

void quantize(int8_t* q, float* s, float* x, int n) {
    // Q8_0 of an activation vector, as in runq.c: q values and one scaling factor per group
    int num_groups = n / GS;
    float Q_MAX = 127.0f;

    for (int group = 0; group < num_groups; group++) {

        // find the max absolute value in the current group
        float wmax = 0.0;
        for (int i = 0; i < GS; i++) {
            float val = fabsf(x[group * GS + i]);
            if (val > wmax) {
                wmax = val;
            }
        }

        // calculate and write the scaling factor
        float scale = wmax / Q_MAX;
        s[group] = scale;

        // calculate and write the quantized values
        for (int i = 0; i < GS; i++) {
            float quant_value = scale > 0.0f ? x[group * GS + i] / scale : 0.0f; // scale
            int8_t quantized = (int8_t) roundf(quant_value); // round and clamp
            q[group * GS + i] = quantized;
        }
    }
}

// ----------------------------------------------------------------------------
// neural net blocks; the dynamics of the Transformer

void rmsnorm(float* o, float* x, float* weight, int size) {
    // calculate sum of squares
    float ss = 0.0f;
    for (int j = 0; j < size; j++) {
        ss += x[j] * x[j];
    }
    ss /= size;
    ss += 1e-5f;
    ss = 1.0f / sqrtf(ss);
    // normalize and scale
    for (int j = 0; j < size; j++) {
        o[j] = weight[j] * (ss * x[j]);
    }
}

void softmax(float* x, int size) {
    // find max value (for numerical stability)
    float max_val = x[0];
    for (int i = 1; i < size; i++) {
        if (x[i] > max_val) {
            max_val = x[i];
        }
    }
    // exp and sum
    float sum = 0.0f;
    for (int i = 0; i < size; i++) {
        x[i] = expf(x[i] - max_val);
        sum += x[i];
    }
    // normalize
    for (int i = 0; i < size; i++) {
        x[i] /= sum;
    }
}

void matmul_cpu(float* xout, float* x, Matrix* w, int n, int d) {
    // W (d,n) @ x (n,) -> xout (d,)
    // the original matmuls: used when NNAPI or the accelerator cannot take a matrix
    int i;
    if (w->f) {
        // fp32 weights, as in run.c
        #pragma omp parallel for private(i)
        for (i = 0; i < d; i++) {
            float val = 0.0f;
            for (int j = 0; j < n; j++) {
                val += w->f[i * n + j] * x[j];
            }
            xout[i] = val;
        }
        return;
    }

    // Q8_0 weights, as in runq.c: x is quantized the same way and both inputs are int8
    int8_t* xq = malloc(n * sizeof(int8_t));
    float* xs = malloc(n / GS * sizeof(float));
    if (!xq || !xs) { fprintf(stderr, "malloc failed!\n"); exit(EXIT_FAILURE); }
    quantize(xq, xs, x, n);
    #pragma omp parallel for private(i)
    for (i = 0; i < d; i++) {

        float val = 0.0f;
        int32_t ival = 0;
        int in = i * n;

        // do the matmul in groups of GS
        int j;
        for (j = 0; j <= n - GS; j += GS) {
            for (int k = 0; k < GS; k++) {
                ival += ((int32_t) xq[j + k]) * ((int32_t) w->q[in + j + k]);
            }
            val += ((float) ival) * w->s[(in + j) / GS] * xs[j / GS];
            ival = 0;
        }

        xout[i] = val;
    }
    free(xq);
    free(xs);
}

// ----------------------------------------------------------------------------
// NNAPI backend for matmul
// Each weight matrix W becomes a one-operation model (FULLY_CONNECTED with W as its constant
// weights) that is compiled for one accelerator the first time matmul sees it and kept for
// every later call. libneuralnetworks.so is loaded at run time and only what is used is
// declared here, as in eden-poco/npu-probe/npu_probe.c.
//
// The weights of an fp32 checkpoint go in as TENSOR_FLOAT32 and are computed in half precision.
// Those of a Q8_0 checkpoint go in with 8 bits each, as a TENSOR_QUANT8_ASYMM constant with the
// zero at 128 that a DEQUANTIZE inside the graph turns into the fp32 weights of the
// FULLY_CONNECTED: input and output stay fp32. NNAPI_Q8=1 does the same with an fp32 checkpoint.
// NNAPI_Q8=2 is the all 8-bit graph npu_probe ran on the APU (its case fc2048_x64_q8): input and
// output are TENSOR_QUANT8_ASYMM too and the bias is TENSOR_INT32.

typedef struct ANeuralNetworksMemory ANeuralNetworksMemory;
typedef struct ANeuralNetworksModel ANeuralNetworksModel;
typedef struct ANeuralNetworksCompilation ANeuralNetworksCompilation;
typedef struct ANeuralNetworksExecution ANeuralNetworksExecution;
typedef struct ANeuralNetworksDevice ANeuralNetworksDevice;

typedef struct {
    int32_t type;
    uint32_t dimensionCount;
    const uint32_t* dimensions;
    float scale;
    int32_t zeroPoint;
} ANeuralNetworksOperandType;

enum {
    ANEURALNETWORKS_INT32 = 1,
    ANEURALNETWORKS_TENSOR_FLOAT32 = 3,
    ANEURALNETWORKS_TENSOR_INT32 = 4,
    ANEURALNETWORKS_TENSOR_QUANT8_ASYMM = 5,
};
enum { ANEURALNETWORKS_DEQUANTIZE = 6, ANEURALNETWORKS_FULLY_CONNECTED = 9 };
// what nn.q8 can be: fp32 weights, 8-bit weights dequantized in the graph, or 8-bit input and output too
enum { Q8_OFF, Q8_DEQUANT, Q8_IO };
#define CACHE_TOKEN_SIZE 32
#define Q8_ZERO 128           // zero point of every 8-bit tensor
#define Q8_SCALE (1.0f / 127) // scale of input and weights: [-1,1] is [-127,127] around the zero
#define Q8_PEAK 96            // where the largest output of a matmul is aimed at, out of 127
#define Q8_MAX_TRIES 8        // executions of one matmul before it is given to the CPU
#define WEIGHTS_DONE 0x38716e6eu

static int (*ANeuralNetworks_getDeviceCount)(uint32_t*);
static int (*ANeuralNetworks_getDevice)(uint32_t, ANeuralNetworksDevice**);
static int (*ANeuralNetworksDevice_getName)(const ANeuralNetworksDevice*, const char**);
static int (*ANeuralNetworksMemory_createFromFd)(size_t, int, int, size_t, ANeuralNetworksMemory**);
static void (*ANeuralNetworksMemory_free)(ANeuralNetworksMemory*);
static int (*ANeuralNetworksModel_create)(ANeuralNetworksModel**);
static void (*ANeuralNetworksModel_free)(ANeuralNetworksModel*);
static int (*ANeuralNetworksModel_addOperand)(ANeuralNetworksModel*, const ANeuralNetworksOperandType*);
static int (*ANeuralNetworksModel_setOperandValue)(ANeuralNetworksModel*, int32_t, const void*, size_t);
static int (*ANeuralNetworksModel_setOperandValueFromMemory)(ANeuralNetworksModel*, int32_t,
                                                             const ANeuralNetworksMemory*, size_t, size_t);
static int (*ANeuralNetworksModel_addOperation)(ANeuralNetworksModel*, int32_t, uint32_t, const uint32_t*,
                                                uint32_t, const uint32_t*);
static int (*ANeuralNetworksModel_identifyInputsAndOutputs)(ANeuralNetworksModel*, uint32_t, const uint32_t*,
                                                            uint32_t, const uint32_t*);
static int (*ANeuralNetworksModel_relaxComputationFloat32toFloat16)(ANeuralNetworksModel*, bool);
static int (*ANeuralNetworksModel_finish)(ANeuralNetworksModel*);
static int (*ANeuralNetworksModel_getSupportedOperationsForDevices)(const ANeuralNetworksModel*,
                                                                    const ANeuralNetworksDevice* const*,
                                                                    uint32_t, bool*);
static int (*ANeuralNetworksCompilation_createForDevices)(ANeuralNetworksModel*,
                                                          const ANeuralNetworksDevice* const*, uint32_t,
                                                          ANeuralNetworksCompilation**);
static void (*ANeuralNetworksCompilation_free)(ANeuralNetworksCompilation*);
static int (*ANeuralNetworksCompilation_setCaching)(ANeuralNetworksCompilation*, const char*, const uint8_t*);
static int (*ANeuralNetworksCompilation_finish)(ANeuralNetworksCompilation*);
static int (*ANeuralNetworksExecution_create)(ANeuralNetworksCompilation*, ANeuralNetworksExecution**);
static void (*ANeuralNetworksExecution_free)(ANeuralNetworksExecution*);
static int (*ANeuralNetworksExecution_setInput)(ANeuralNetworksExecution*, int32_t,
                                                const ANeuralNetworksOperandType*, const void*, size_t);
static int (*ANeuralNetworksExecution_setOutput)(ANeuralNetworksExecution*, int32_t,
                                                 const ANeuralNetworksOperandType*, void*, size_t);
static int (*ANeuralNetworksExecution_compute)(ANeuralNetworksExecution*);

typedef struct {
    Matrix* w; // the weight matrix this graph was built for, of shape (d,n)
    int n;
    int d;
    void* bias; // FULLY_CONNECTED requires a bias: all zeros
    ANeuralNetworksModel* model; // has to outlive the compilation
    ANeuralNetworksCompilation* compilation; // NULL if the device rejected it: this matrix stays on the CPU
    // the rest is only for a graph with 8-bit weights
    uint8_t* w8; // W as the graph takes it, when it is kept in RAM
    ANeuralNetworksMemory* memory; // W as the graph takes it, when it is mapped from its weights file
    uint64_t hash; // of those weights, for the cache token
    float* row_scale; // (d,) what one unit of each output is worth, for an input that peaks at 1.0
    float* x1; // (n,) the input of an execution, scaled to [-1,1]
    // and this only for the all 8-bit graph
    float out_scale; // scale of the output tensor
    float att; // how much the next input is attenuated so that the outputs fit in their 8 bits
    uint8_t* in; // (n,) the quantized input of an execution
    uint8_t* out; // (d,) its quantized output
} MatmulGraph;

// which checkpoint the weights files were made from.
// the magic has to change if make_weights_8bit or the order of the matmuls in forward do
typedef struct {
    char magic[8];
    int64_t checkpoint_size;
    int64_t checkpoint_mtime;
    int64_t group_size;
} WeightsHeader;

// what follows the weights and the row scales of a matrix in its weights file.
// it is written last and marks them as complete
typedef struct {
    WeightsHeader made_from;
    uint64_t hash;
    float out_scale;
    int32_t n;
    int32_t d;
    uint32_t done;
} WeightsTail;

typedef struct {
    int state; // 0 = not set up yet, 1 = ready, -1 = unavailable
    const ANeuralNetworksDevice* device;
    const char* device_name;
    char cache_dir[PATH_MAX]; // "" = no compilation caching
    bool verify; // also compute every matmul on the CPU and compare
    const char* checkpoint; // path of the checkpoint, set by main
    int q8; // Q8_OFF, or how the graphs take 8-bit weights: Q8_DEQUANT or Q8_IO
    char weights_path[PATH_MAX]; // prefix of the files the graphs map their 8-bit weights from. "" = kept in RAM
    WeightsHeader weights_from; // what the tail of each of those files has to say
    MatmulGraph* graphs; // one per weight matrix seen so far
    int n_graphs;
    // counters for report_matmul
    long nnapi_runs;
    long cpu_runs;
    long retries; // executions repeated because the outputs did not fit in 8 bits
    double weights_ms;
    double compile_ms;
    double run_ms;
    float max_err;
    float max_ref;
    double rel_sum; // of the relative error of each verified matmul
    double rel_max;
    long verified;
    long not_finite;
} MatmulState;

static MatmulState nn;

static double now_ms(void) {
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return time.tv_sec * 1000.0 + time.tv_nsec / 1e6;
}

static bool nnapi_load(void) {
    void* lib = dlopen("libneuralnetworks.so", RTLD_NOW);
    if (!lib) { lib = dlopen("/apex/com.android.neuralnetworks/lib64/libneuralnetworks.so", RTLD_NOW); }
    if (!lib) { fprintf(stderr, "nnapi: dlopen failed: %s\n", dlerror()); return false; }
    int missing = 0;
#define LOAD(name) \
    if (!(name = dlsym(lib, #name))) { fprintf(stderr, "nnapi: missing symbol %s\n", #name); missing++; }
    LOAD(ANeuralNetworks_getDeviceCount)
    LOAD(ANeuralNetworks_getDevice)
    LOAD(ANeuralNetworksDevice_getName)
    LOAD(ANeuralNetworksMemory_createFromFd)
    LOAD(ANeuralNetworksMemory_free)
    LOAD(ANeuralNetworksModel_create)
    LOAD(ANeuralNetworksModel_free)
    LOAD(ANeuralNetworksModel_addOperand)
    LOAD(ANeuralNetworksModel_setOperandValue)
    LOAD(ANeuralNetworksModel_setOperandValueFromMemory)
    LOAD(ANeuralNetworksModel_addOperation)
    LOAD(ANeuralNetworksModel_identifyInputsAndOutputs)
    LOAD(ANeuralNetworksModel_relaxComputationFloat32toFloat16)
    LOAD(ANeuralNetworksModel_finish)
    LOAD(ANeuralNetworksModel_getSupportedOperationsForDevices)
    LOAD(ANeuralNetworksCompilation_createForDevices)
    LOAD(ANeuralNetworksCompilation_free)
    LOAD(ANeuralNetworksCompilation_setCaching)
    LOAD(ANeuralNetworksCompilation_finish)
    LOAD(ANeuralNetworksExecution_create)
    LOAD(ANeuralNetworksExecution_free)
    LOAD(ANeuralNetworksExecution_setInput)
    LOAD(ANeuralNetworksExecution_setOutput)
    LOAD(ANeuralNetworksExecution_compute)
#undef LOAD
    return missing == 0;
}

static bool pread_all(int fd, void* buffer, size_t size, off_t offset) {
    char* p = buffer;
    while (size > 0) {
        ssize_t done = pread(fd, p, size, offset);
        if (done <= 0) { return false; }
        p += done;
        size -= done;
        offset += done;
    }
    return true;
}

static bool pwrite_all(int fd, const void* buffer, size_t size, off_t offset) {
    const char* p = buffer;
    while (size > 0) {
        ssize_t done = pwrite(fd, p, size, offset);
        if (done <= 0) { return false; }
        p += done;
        size -= done;
        offset += done;
    }
    return true;
}

static void weights_files_setup(void) {
    // The 8-bit weights the graphs take are not the bytes of the checkpoint. By default they
    // are made in RAM at every run. With NNAPI_WEIGHTS_FILE they are written once to files
    // named after it, one per matrix (<path>.0, <path>.1, ...), and NNAPI maps each matrix from
    // its file: this process then holds no copy of them (given a pointer, NNAPI makes a second
    // copy in shared memory that lives as long as the model) and later runs find them already
    // made. A model of several GB needs that.
    // It is a file per matrix and not one for all because the weights have to be at the first
    // byte: the MediaTek driver maps the fd from its start whatever the offset the memory was
    // created with (bytes of the file ahead of that offset changed the outputs)
    const char* path = getenv("NNAPI_WEIGHTS_FILE");
    struct stat st;
    if (!path || path[0] == '\0') { return; }
    if (!nn.checkpoint || stat(nn.checkpoint, &st) != 0 || strlen(path) + 12 > sizeof nn.weights_path) {
        fprintf(stderr, "nnapi: cannot use weights file %s, the 8-bit weights stay in RAM\n", path);
        return;
    }
    memcpy(nn.weights_from.magic, "nnapi8-c", 8);
    nn.weights_from.checkpoint_size = st.st_size;
    nn.weights_from.checkpoint_mtime = st.st_mtime;
    nn.weights_from.group_size = GS;
    strcpy(nn.weights_path, path);
    fprintf(stderr, "nnapi: 8-bit weights in %s.<matrix>\n", path);
}

static void nnapi_setup(void) {
    // the accelerators NNAPI lists on the Dimensity 8300; the MDLA (the APU itself) goes first
    static const char* const mtk_devices[] = {"mtk-mdla_shim", "mtk-neuron_shim"};
    const char* want = getenv("NNAPI_DEVICE");
    const char* const* names = want ? &want : mtk_devices;
    int best = want ? 1 : 2; // index in names of the best device found so far
    nn.state = -1;
    if (want && strcmp(want, "off") == 0) { return; }
    if (!nnapi_load()) { return; }

    uint32_t count = 0;
    if (ANeuralNetworks_getDeviceCount(&count) != 0) { count = 0; }
    for (uint32_t i = 0; i < count; i++) {
        ANeuralNetworksDevice* device = NULL;
        const char* name = NULL;
        if (ANeuralNetworks_getDevice(i, &device) != 0 || !device) { continue; }
        if (ANeuralNetworksDevice_getName(device, &name) != 0 || !name) { continue; }
        fprintf(stderr, "nnapi: device %u: %s\n", i, name);
        for (int r = 0; r < best; r++) {
            if (strcmp(name, names[r]) == 0) { best = r; nn.device = device; nn.device_name = name; }
        }
    }
    if (!nn.device) {
        fprintf(stderr, "nnapi: no %s device, matmul runs on the CPU\n", want ? want : "MediaTek");
        return;
    }

    // compilation caching: the driver stores its compiled blobs in this directory and later runs
    // of this program load them instead of compiling again. NNAPI opens the files from this
    // process, so it has to be a directory the process can write to
    const char* dir = getenv("NNAPI_CACHE_DIR");
    if (!dir) { dir = "nnapi_cache"; }
    if (dir[0] != '\0') {
        mkdir(dir, 0700);
        if (!realpath(dir, nn.cache_dir)) {
            fprintf(stderr, "nnapi: cannot use cache directory %s, compiling without cache\n", dir);
            nn.cache_dir[0] = '\0';
        }
    }

    // 8-bit weights: always with a Q8_0 checkpoint, on request with an fp32 one
    const char* q8 = getenv("NNAPI_Q8");
    int mode = q8 ? atoi(q8) : 0;
    nn.q8 = mode == 2 ? Q8_IO : (GS != 0 || mode == 1) ? Q8_DEQUANT : Q8_OFF;
    if (nn.q8) { weights_files_setup(); }

    const char* verify = getenv("NNAPI_VERIFY");
    nn.verify = verify && atoi(verify) != 0;
    nn.state = 1;
    fprintf(stderr, "nnapi: matmul runs on %s with %s\n", nn.device_name,
            nn.q8 == Q8_DEQUANT ? "8-bit weights dequantized in the graph, fp32 input and output"
            : nn.q8 == Q8_IO ? "8-bit weights, input and output" : "fp32 weights in half precision");
}

static void make_weights_8bit(MatmulGraph* g, uint8_t* w8) {
    // W as the 8-bit weights of the graph. Row i is written as m[i] * q, with q in [-127,127]
    // stored as q + 128, so every row uses the whole range whatever the size of its weights;
    // m[i] is left in row_scale and applied to the output afterwards, on the CPU. Q8_0 has a
    // scaling factor per group of GS weights and the tensor has a single one: the groups of a
    // row are brought to the largest factor in that row
    int n = g->n;
    int d = g->d;
    double norm = 0.0; // sum of q * q over the whole matrix
    uint64_t hash = 0xcbf29ce484222325ULL; // FNV-1a, one weight at a time
    for (int i = 0; i < d; i++) {
        uint8_t* row = w8 + (size_t)i * n;
        float m = 0.0f;
        int64_t sum = 0;
        if (g->w->f) {
            const float* f = g->w->f + (size_t)i * n;
            for (int j = 0; j < n; j++) {
                if (fabsf(f[j]) > m) { m = fabsf(f[j]); }
            }
            m /= 127.0f;
            float inv = m > 0.0f ? 1.0f / m : 0.0f;
            for (int j = 0; j < n; j++) {
                int32_t q = (int32_t)lrintf(f[j] * inv);
                sum += q * q;
                row[j] = (uint8_t)(q + Q8_ZERO);
            }
        } else {
            const int8_t* q8 = g->w->q + (size_t)i * n;
            const float* s = g->w->s + (size_t)i * n / GS;
            for (int k = 0; k < n / GS; k++) {
                if (s[k] > m) { m = s[k]; }
            }
            for (int k = 0; k < n / GS; k++) {
                float ratio = m > 0.0f ? s[k] / m : 0.0f;
                for (int j = k * GS; j < (k + 1) * GS; j++) {
                    int32_t q = (int32_t)lrintf(q8[j] * ratio);
                    sum += q * q;
                    row[j] = (uint8_t)(q + Q8_ZERO);
                }
            }
        }
        for (int j = 0; j < n; j++) {
            hash = (hash ^ row[j]) * 0x100000001b3ULL;
        }
        g->row_scale[i] = m;
        norm += sum;
    }

    // The scale of the output tensor of the all 8-bit graph. With an input that peaks at 1.0,
    // one step of the 8-bit input moves an output by the norm of its row of weights (omega is
    // the RMS of that norm over the rows). One step of the output is made as large, so neither
    // side wastes its 8 bits. Outputs that do not fit are dealt with at run time, by
    // attenuating the input
    float omega = sqrtf((float)(norm / d)) * Q8_SCALE;
    if (omega < 1.0f) { omega = 1.0f; }
    g->out_scale = omega * Q8_SCALE;
    g->hash = hash;
}

static void scale_rows(MatmulGraph* g) {
    // row_scale comes with the m[i] of make_weights_8bit, which is also how the weights file
    // keeps it. The weights of the graph are q / 127, so one unit of an fp32 output is worth
    // 127 * m[i], and one step of an 8-bit output is worth out_scale times that
    float unit = nn.q8 == Q8_IO ? 127.0f * g->out_scale : 127.0f;
    for (int i = 0; i < g->d; i++) {
        g->row_scale[i] *= unit;
    }
}

static int prepare_weights_8bit(MatmulGraph* g) {
    // everything a graph with 8-bit weights needs besides its model. with weights files, the
    // weights are taken from the file of this matrix if an earlier run left them there
    size_t size = (size_t)g->n * g->d;
    size_t scales_size = g->d * sizeof(float);
    g->row_scale = malloc(scales_size);
    g->x1 = malloc(g->n * sizeof(float));
    g->in = malloc(g->n);
    g->out = malloc(g->d);
    if (!g->row_scale || !g->x1 || !g->in || !g->out) { fprintf(stderr, "malloc failed!\n"); exit(EXIT_FAILURE); }
    g->att = 1.0f;

    if (nn.weights_path[0] == '\0') {
        g->w8 = malloc(size);
        if (!g->w8) { fprintf(stderr, "malloc failed!\n"); exit(EXIT_FAILURE); }
        make_weights_8bit(g, g->w8);
        scale_rows(g);
        return 0;
    }

    // in the file of this matrix: the weights, the row scales and the tail. the matrices are
    // always first used in the same order, so each one finds the file an earlier run left it
    char path[PATH_MAX];
    off_t tail_offset = size + scales_size;
    WeightsTail tail;
    snprintf(path, sizeof path, "%s.%d", nn.weights_path, (int)(g - nn.graphs));
    int fd = open(path, O_RDONLY);
    if (fd != -1 && pread_all(fd, &tail, sizeof tail, tail_offset) && tail.done == WEIGHTS_DONE
        && memcmp(&tail.made_from, &nn.weights_from, sizeof tail.made_from) == 0
        && tail.n == g->n && tail.d == g->d && tail.out_scale > 0.0f
        && pread_all(fd, g->row_scale, scales_size, size)) {
        g->hash = tail.hash;
        g->out_scale = tail.out_scale;
    } else {
        // no file yet, or one made from another checkpoint: start it over
        if (fd != -1) { close(fd); }
        uint8_t* w8 = malloc(size);
        if (!w8) { fprintf(stderr, "malloc failed!\n"); exit(EXIT_FAILURE); }
        make_weights_8bit(g, w8);
        memset(&tail, 0, sizeof tail);
        tail.made_from = nn.weights_from;
        tail.hash = g->hash;
        tail.out_scale = g->out_scale;
        tail.n = g->n;
        tail.d = g->d;
        tail.done = WEIGHTS_DONE;
        fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        bool written = fd != -1 && pwrite_all(fd, w8, size, 0)
                    && pwrite_all(fd, g->row_scale, scales_size, size)
                    && pwrite_all(fd, &tail, sizeof tail, tail_offset);
        free(w8);
        if (fd != -1) { close(fd); }
        // the driver only has to read it
        fd = written ? open(path, O_RDONLY) : -1;
        if (fd == -1) {
            fprintf(stderr, "nnapi: writing weights file %s failed: %s\n", path, strerror(errno));
            return -1;
        }
    }
    scale_rows(g);
    // the memory starts at the first byte of the file. NNAPI duplicates the fd
    int e = ANeuralNetworksMemory_createFromFd(size, PROT_READ, fd, 0, &g->memory);
    close(fd);
    return e;
}

static int add_operand(ANeuralNetworksModel* m, int32_t type, uint32_t rank, const uint32_t* dims,
                       float scale, int32_t zero) {
    ANeuralNetworksOperandType t = {type, rank, dims, scale, zero};
    return ANeuralNetworksModel_addOperand(m, &t);
}

static int build_matmul_model(MatmulGraph* g) {
    // x (1,n) -> FULLY_CONNECTED with weights W (d,n) and a zero bias -> xout (1,d)
    // W is stored exactly as FULLY_CONNECTED wants it: one row of n values per output
    // 8-bit weights with fp32 tensors go through a DEQUANTIZE first: W8 (d,n) -> W (d,n)
    const uint32_t x_dims[] = {1, (uint32_t)g->n};
    const uint32_t w_dims[] = {(uint32_t)g->d, (uint32_t)g->n};
    const uint32_t bias_dims[] = {(uint32_t)g->d};
    const uint32_t out_dims[] = {1, (uint32_t)g->d};
    const uint32_t w8_op[] = {1};
    const uint32_t w32_op[] = {5};
    const uint32_t fc_in[] = {0, (uint32_t)(nn.q8 == Q8_DEQUANT ? 5 : 1), 2, 3};
    const uint32_t fc_out[] = {4};
    const uint32_t model_in[] = {0};
    const int32_t no_activation = 0;
    // 8-bit tensors hold (value - zero) * scale. in the all 8-bit graph the bias is int32 and
    // its scale has to be the product of the scales of input and weights
    const bool io8 = nn.q8 == Q8_IO;
    const int32_t fp32 = ANEURALNETWORKS_TENSOR_FLOAT32;
    const int32_t io_type = io8 ? ANEURALNETWORKS_TENSOR_QUANT8_ASYMM : fp32;
    const int32_t io_zero = io8 ? Q8_ZERO : 0;
    const float io_scale = io8 ? Q8_SCALE : 0.0f;
    const size_t w_size = (size_t)g->d * g->n * (nn.q8 ? sizeof(uint8_t) : sizeof(float));
    int e = ANeuralNetworksModel_create(&g->model);
    if (e || !g->model) { return e ? e : -1; }
    ANeuralNetworksModel* m = g->model;
    e |= add_operand(m, io_type, 2, x_dims, io_scale, io_zero);
    if (nn.q8) { e |= add_operand(m, ANEURALNETWORKS_TENSOR_QUANT8_ASYMM, 2, w_dims, Q8_SCALE, Q8_ZERO); }
    else { e |= add_operand(m, fp32, 2, w_dims, 0.0f, 0); }
    e |= add_operand(m, io8 ? ANEURALNETWORKS_TENSOR_INT32 : fp32, 1, bias_dims, io_scale * io_scale, 0);
    e |= add_operand(m, ANEURALNETWORKS_INT32, 0, NULL, 0.0f, 0); // fused activation
    e |= add_operand(m, io_type, 2, out_dims, io8 ? g->out_scale : 0.0f, io_zero);
    if (nn.q8 == Q8_DEQUANT) {
        // W in fp32: a temporary that DEQUANTIZE fills from the 8-bit constant
        e |= add_operand(m, fp32, 2, w_dims, 0.0f, 0);
    }
    // values over 128 bytes are referenced, not copied: W and the bias must outlive the model
    if (g->memory) {
        e |= ANeuralNetworksModel_setOperandValueFromMemory(m, 1, g->memory, 0, w_size);
    } else {
        e |= ANeuralNetworksModel_setOperandValue(m, 1, nn.q8 ? (void*)g->w8 : (void*)g->w->f, w_size);
    }
    e |= ANeuralNetworksModel_setOperandValue(m, 2, g->bias, g->d * sizeof(float));
    e |= ANeuralNetworksModel_setOperandValue(m, 3, &no_activation, sizeof no_activation);
    if (nn.q8 == Q8_DEQUANT) {
        e |= ANeuralNetworksModel_addOperation(m, ANEURALNETWORKS_DEQUANTIZE, 1, w8_op, 1, w32_op);
    }
    e |= ANeuralNetworksModel_addOperation(m, ANEURALNETWORKS_FULLY_CONNECTED, 4, fc_in, 1, fc_out);
    e |= ANeuralNetworksModel_identifyInputsAndOutputs(m, 1, model_in, 1, fc_out);
    // with fp32 tensors the accelerator computes in half precision
    if (!e && !io8) { e = ANeuralNetworksModel_relaxComputationFloat32toFloat16(m, true); }
    if (!e) { e = ANeuralNetworksModel_finish(m); }
    return e;
}

static void matmul_cache_token(MatmulGraph* g, uint8_t* token) {
    // the token has to identify the model, so it is made of a hash of the weights and the shape
    uint64_t hash = g->hash; // of the 8-bit weights: it was made along with them
    if (!nn.q8) {
        // FNV-1a, one float at a time
        hash = 0xcbf29ce484222325ULL;
        size_t count = (size_t)g->n * g->d;
        for (size_t i = 0; i < count; i++) {
            uint32_t bits;
            memcpy(&bits, &g->w->f[i], sizeof bits);
            hash = (hash ^ bits) * 0x100000001b3ULL;
        }
    }
    memset(token, 0, CACHE_TOKEN_SIZE);
    // the names of the 8-bit graphs are not the ones of the versions that gave the memory an
    // fd offset: what those compiled from the wrong bytes of the file may still be in a cache
    memcpy(token, nn.q8 == Q8_DEQUANT ? "matmul-fc-q8e" : nn.q8 ? "matmul-fc-q8c" : "matmul-fc-f16", 13);
    memcpy(token + 16, &hash, sizeof hash);
    memcpy(token + 24, &g->n, sizeof g->n);
    memcpy(token + 28, &g->d, sizeof g->d);
}

static int compile_matmul_graph(MatmulGraph* g, const uint8_t* token) {
    // restricted to one device NNAPI has no CPU to fall back to silently:
    // a model the accelerator does not take fails here
    const ANeuralNetworksDevice* const one[1] = {nn.device};
    int e = ANeuralNetworksCompilation_createForDevices(g->model, one, 1, &g->compilation);
    if (!e && token) { e = ANeuralNetworksCompilation_setCaching(g->compilation, nn.cache_dir, token); }
    if (!e) { e = ANeuralNetworksCompilation_finish(g->compilation); }
    if (e && g->compilation) {
        ANeuralNetworksCompilation_free(g->compilation);
        g->compilation = NULL;
    }
    return e;
}

static void explain_rejection(MatmulGraph* g) {
    // which operations of a rejected graph the device takes. said once: it is the same for all
    static bool said;
    const ANeuralNetworksDevice* const one[1] = {nn.device};
    bool takes[2] = {false, false};
    if (said || !g->model) { return; }
    if (ANeuralNetworksModel_getSupportedOperationsForDevices(g->model, one, 1, takes) != 0) { return; }
    said = true;
    if (nn.q8 == Q8_DEQUANT) {
        fprintf(stderr, "nnapi: %s takes DEQUANTIZE: %s, the FULLY_CONNECTED it feeds: %s\n",
                nn.device_name, takes[0] ? "yes" : "no", takes[1] ? "yes" : "no");
    } else {
        fprintf(stderr, "nnapi: %s takes FULLY_CONNECTED: %s\n", nn.device_name, takes[0] ? "yes" : "no");
    }
}

static MatmulGraph* matmul_graph(Matrix* w, int n, int d) {
    // the graph of an already seen matrix is reused as is
    for (int i = 0; i < nn.n_graphs; i++) {
        MatmulGraph* g = &nn.graphs[i];
        if (g->w == w && g->n == n && g->d == d) { return g; }
    }

    // first use of this matrix: build its model and compile it, once
    nn.graphs = realloc(nn.graphs, (nn.n_graphs + 1) * sizeof(MatmulGraph));
    void* bias = calloc(d, sizeof(float)); // zeros, be it as fp32 or as the int32 of an 8-bit graph
    if (!nn.graphs || !bias) { fprintf(stderr, "malloc failed!\n"); exit(EXIT_FAILURE); }
    MatmulGraph* g = &nn.graphs[nn.n_graphs++];
    memset(g, 0, sizeof(MatmulGraph));
    g->w = w;
    g->n = n;
    g->d = d;
    g->bias = bias;

    double start = now_ms();
    int e = nn.q8 ? prepare_weights_8bit(g) : 0;
    nn.weights_ms += now_ms() - start;
    start = now_ms();
    if (!e) { e = build_matmul_model(g); }
    if (!e) {
        uint8_t token[CACHE_TOKEN_SIZE];
        bool cached = nn.cache_dir[0] != '\0';
        if (cached) { matmul_cache_token(g, token); }
        e = compile_matmul_graph(g, cached ? token : NULL);
        if (e && cached) {
            // a driver that cannot use the cache files fails the whole compilation
            e = compile_matmul_graph(g, NULL);
            if (!e) {
                fprintf(stderr, "nnapi: compiling works only without cache, cache disabled\n");
                nn.cache_dir[0] = '\0';
            }
        }
    }
    nn.compile_ms += now_ms() - start;
    if (e) {
        fprintf(stderr, "nnapi: %s rejected matrix %dx%d (error %d), it stays on the CPU\n",
                nn.device_name, d, n, e);
        explain_rejection(g);
    }
    return g;
}

static void verify_matmul(float* xout, float* x, Matrix* w, int n, int d) {
    // compare a result of the accelerator with the CPU one. half precision alone gives small
    // errors. 8-bit tensors give larger ones: the relative error (RMS of the error over RMS of
    // the values) tells how large
    float* ref = malloc(d * sizeof(float));
    if (!ref) { fprintf(stderr, "malloc failed!\n"); exit(EXIT_FAILURE); }
    double err2 = 0.0;
    double ref2 = 0.0;
    matmul_cpu(ref, x, w, n, d);
    for (int i = 0; i < d; i++) {
        if (!isfinite(xout[i])) { nn.not_finite++; continue; }
        float err = fabsf(xout[i] - ref[i]);
        if (err > nn.max_err) { nn.max_err = err; }
        if (fabsf(ref[i]) > nn.max_ref) { nn.max_ref = fabsf(ref[i]); }
        err2 += (double)err * err;
        ref2 += (double)ref[i] * ref[i];
    }
    if (ref2 > 0.0) {
        double rel = sqrt(err2 / ref2);
        if (rel > nn.rel_max) { nn.rel_max = rel; }
        nn.rel_sum += rel;
        nn.verified++;
    }
    free(ref);
}

static int run_matmul_graph(MatmulGraph* g, const void* in, size_t in_size, void* out, size_t out_size) {
    // an execution is single use: what is kept between calls is the compilation
    ANeuralNetworksExecution* execution = NULL;
    int e = ANeuralNetworksExecution_create(g->compilation, &execution);
    if (!e) { e = ANeuralNetworksExecution_setInput(execution, 0, NULL, in, in_size); }
    if (!e) { e = ANeuralNetworksExecution_setOutput(execution, 0, NULL, out, out_size); }
    if (!e) { e = ANeuralNetworksExecution_compute(execution); }
    if (execution) { ANeuralNetworksExecution_free(execution); }
    return e;
}

static int run_matmul_8bit(MatmulGraph* g, float* xout, float* x) {
    // The 8-bit input holds x / (xmax * att): x over its whole range, attenuated by att >= 1
    // when the outputs would not fit in their 8 bits otherwise. Whether they fit is only known
    // afterwards: an output at either end of the range means they did not, and the matmul is
    // run again with twice the attenuation.
    // Returns 0, the error of NNAPI (positive), or -1 if the outputs never fit
    int n = g->n;
    int d = g->d;
    float xmax = 0.0f;
    for (int j = 0; j < n; j++) {
        if (fabsf(x[j]) > xmax) { xmax = fabsf(x[j]); }
    }
    if (!(xmax > 0.0f)) {
        memset(xout, 0, d * sizeof(float));
        return 0;
    }

    float att = g->att;
    int peak = 0;
    for (int tries = 1; ; tries++) {
        float k = 127.0f / (xmax * att);
        for (int j = 0; j < n; j++) {
            g->in[j] = (uint8_t)(lrintf(x[j] * k) + Q8_ZERO);
        }
        int e = run_matmul_graph(g, g->in, n, g->out, d);
        if (e) { return e; }
        peak = 0;
        for (int i = 0; i < d; i++) {
            int v = abs(g->out[i] - Q8_ZERO);
            if (v > peak) { peak = v; }
        }
        if (peak < 127) { break; }
        if (tries == Q8_MAX_TRIES) { g->att = att; return -1; }
        att *= 2.0f;
        nn.retries++;
    }

    float unit = xmax * att; // the input value that 127 stood for
    for (int i = 0; i < d; i++) {
        xout[i] = g->row_scale[i] * unit * (g->out[i] - Q8_ZERO);
    }
    // the next input of this matrix is attenuated so that a peak like this one lands at Q8_PEAK
    att *= (float)peak / Q8_PEAK;
    g->att = att > 1.0f ? att : 1.0f;
    return 0;
}

static int run_matmul_dequant(MatmulGraph* g, float* xout, float* x) {
    // 8-bit weights, fp32 input and output. The graph has every row of W scaled to [-1,1] and
    // is given x scaled the same way, so nothing in it can be larger than n whatever the sizes
    // in the model: the accelerator computes in half precision, which ends at 65504. Both
    // scales are put back here
    int n = g->n;
    int d = g->d;
    float xmax = 0.0f;
    for (int j = 0; j < n; j++) {
        if (fabsf(x[j]) > xmax) { xmax = fabsf(x[j]); }
    }
    if (!(xmax > 0.0f)) {
        memset(xout, 0, d * sizeof(float));
        return 0;
    }
    for (int j = 0; j < n; j++) {
        g->x1[j] = x[j] / xmax;
    }
    int e = run_matmul_graph(g, g->x1, n * sizeof(float), xout, d * sizeof(float));
    if (e) { return e; }
    for (int i = 0; i < d; i++) {
        xout[i] *= g->row_scale[i] * xmax;
    }
    return 0;
}

void matmul(float* xout, float* x, Matrix* w, int n, int d) {
    // W (d,n) @ x (n,) -> xout (d,)
    // by far the most amount of time is spent inside this little function
    if (nn.state == 0) { nnapi_setup(); }
    MatmulGraph* g = nn.state > 0 ? matmul_graph(w, n, d) : NULL;
    if (g && g->compilation) {
        double start = now_ms();
        int e = nn.q8 == Q8_IO ? run_matmul_8bit(g, xout, x)
              : nn.q8 == Q8_DEQUANT ? run_matmul_dequant(g, xout, x)
              : run_matmul_graph(g, x, n * sizeof(float), xout, d * sizeof(float));
        if (!e) {
            nn.run_ms += now_ms() - start;
            nn.nnapi_runs++;
            if (nn.verify) { verify_matmul(xout, x, w, n, d); }
            return;
        }
        if (e > 0) {
            fprintf(stderr, "nnapi: execution of matrix %dx%d failed (error %d), it stays on the CPU\n", d, n, e);
            ANeuralNetworksCompilation_free(g->compilation);
            g->compilation = NULL;
        }
    }
    matmul_cpu(xout, x, w, n, d);
    nn.cpu_runs++;
}

void report_matmul() {
    // where the matmuls actually ran, to stderr like the tok/s
    if (nn.state <= 0) {
        fprintf(stderr, "nnapi: not used, %ld matmuls on the CPU\n", nn.cpu_runs);
        return;
    }
    int compiled = 0;
    for (int i = 0; i < nn.n_graphs; i++) {
        if (nn.graphs[i].compilation) { compiled++; }
    }
    if (nn.q8) {
        fprintf(stderr, "nnapi: 8-bit weights made ready in %.0f ms, kept in %s\n",
                nn.weights_ms, nn.weights_path[0] != '\0' ? "the weights files" : "RAM");
    }
    fprintf(stderr, "nnapi: %d of %d matmul graphs compiled for %s in %.0f ms\n",
            compiled, nn.n_graphs, nn.device_name, nn.compile_ms);
    fprintf(stderr, "nnapi: %ld matmuls on %s at %.3f ms each, %ld on the CPU\n",
            nn.nnapi_runs, nn.device_name, nn.nnapi_runs ? nn.run_ms / nn.nnapi_runs : 0.0, nn.cpu_runs);
    if (nn.q8 == Q8_IO) {
        fprintf(stderr, "nnapi: %ld executions repeated because the outputs did not fit in 8 bits\n", nn.retries);
    }
    if (nn.verify) {
        fprintf(stderr, "nnapi: verify: max error %g against CPU values up to %g, %ld non-finite outputs\n",
                nn.max_err, nn.max_ref, nn.not_finite);
        fprintf(stderr, "nnapi: verify: relative error %.2f%% on average, %.2f%% at worst\n",
                nn.verified ? 100.0 * nn.rel_sum / nn.verified : 0.0, 100.0 * nn.rel_max);
    }
}

void free_matmul() {
    // call this before the checkpoint is unmapped: the models point at the weights
    for (int i = 0; i < nn.n_graphs; i++) {
        MatmulGraph* g = &nn.graphs[i];
        if (g->compilation) { ANeuralNetworksCompilation_free(g->compilation); }
        if (g->model) { ANeuralNetworksModel_free(g->model); }
        if (g->memory) { ANeuralNetworksMemory_free(g->memory); }
        free(g->bias);
        free(g->w8);
        free(g->row_scale);
        free(g->x1);
        free(g->in);
        free(g->out);
    }
    free(nn.graphs);
}

float* forward(Transformer* transformer, int token, int pos) {

    // a few convenience variables
    Config* p = &transformer->config;
    TransformerWeights* w = &transformer->weights;
    RunState* s = &transformer->state;
    float *x = s->x;
    int dim = p->dim;
    int kv_dim = (p->dim * p->n_kv_heads) / p->n_heads;
    int kv_mul = p->n_heads / p->n_kv_heads; // integer multiplier of the kv sharing in multiquery
    int hidden_dim =  p->hidden_dim;
    int head_size = dim / p->n_heads;

    // copy the token embedding into x
    token_embedding(x, w->token_embedding_table, token, dim);

    // forward all the layers
    for(unsigned long long l = 0; l < p->n_layers; l++) {

        // attention rmsnorm
        rmsnorm(s->xb, x, w->rms_att_weight + l*dim, dim);

        // key and value point to the kv cache
        int loff = l * p->seq_len * kv_dim; // kv cache layer offset for convenience
        s->k = s->key_cache + loff + pos * kv_dim;
        s->v = s->value_cache + loff + pos * kv_dim;

        // qkv matmuls for this position
        matmul(s->q, s->xb, w->wq + l, dim, dim);
        matmul(s->k, s->xb, w->wk + l, dim, kv_dim);
        matmul(s->v, s->xb, w->wv + l, dim, kv_dim);

        // RoPE relative positional encoding: complex-valued rotate q and k in each head
        for (int i = 0; i < dim; i+=2) {
            int head_dim = i % head_size;
            float freq = 1.0f / powf(10000.0f, head_dim / (float)head_size);
            float val = pos * freq;
            float fcr = cosf(val);
            float fci = sinf(val);
            int rotn = i < kv_dim ? 2 : 1; // how many vectors? 2 = q & k, 1 = q only
            for (int v = 0; v < rotn; v++) {
                float* vec = v == 0 ? s->q : s->k; // the vector to rotate (query or key)
                float v0 = vec[i];
                float v1 = vec[i+1];
                vec[i]   = v0 * fcr - v1 * fci;
                vec[i+1] = v0 * fci + v1 * fcr;
            }
        }

        // multihead attention. iterate over all heads
        int h;
        #pragma omp parallel for private(h)
        for (h = 0; h < p->n_heads; h++) {
            // get the query vector for this head
            float* q = s->q + h * head_size;
            // attention scores for this head
            float* att = s->att + h * p->seq_len;
            // iterate over all timesteps, including the current one
            for (int t = 0; t <= pos; t++) {
                // get the key vector for this head and at this timestep
                float* k = s->key_cache + loff + t * kv_dim + (h / kv_mul) * head_size;
                // calculate the attention score as the dot product of q and k
                float score = 0.0f;
                for (int i = 0; i < head_size; i++) {
                    score += q[i] * k[i];
                }
                score /= sqrtf(head_size);
                // save the score to the attention buffer
                att[t] = score;
            }

            // softmax the scores to get attention weights, from 0..pos inclusively
            softmax(att, pos + 1);

            // weighted sum of the values, store back into xb
            float* xb = s->xb + h * head_size;
            memset(xb, 0, head_size * sizeof(float));
            for (int t = 0; t <= pos; t++) {
                // get the value vector for this head and at this timestep
                float* v = s->value_cache + loff + t * kv_dim + (h / kv_mul) * head_size;
                // get the attention weight for this timestep
                float a = att[t];
                // accumulate the weighted value into xb
                for (int i = 0; i < head_size; i++) {
                    xb[i] += a * v[i];
                }
            }
        }

        // final matmul to get the output of the attention
        matmul(s->xb2, s->xb, w->wo + l, dim, dim);

        // residual connection back into x
        for (int i = 0; i < dim; i++) {
            x[i] += s->xb2[i];
        }

        // ffn rmsnorm
        rmsnorm(s->xb, x, w->rms_ffn_weight + l*dim, dim);

        // Now for FFN in PyTorch we have: self.w2(F.silu(self.w1(x)) * self.w3(x))
        // first calculate self.w1(x) and self.w3(x)
        matmul(s->hb, s->xb, w->w1 + l, dim, hidden_dim);
        matmul(s->hb2, s->xb, w->w3 + l, dim, hidden_dim);

        // SwiGLU non-linearity
        for (int i = 0; i < hidden_dim; i++) {
            float val = s->hb[i];
            // silu(x)=x*σ(x), where σ(x) is the logistic sigmoid
            val *= (1.0f / (1.0f + expf(-val)));
            // elementwise multiply with w3(x)
            val *= s->hb2[i];
            s->hb[i] = val;
        }

        // final matmul to get the output of the ffn
        matmul(s->xb, s->hb, w->w2 + l, hidden_dim, dim);

        // residual connection
        for (int i = 0; i < dim; i++) {
            x[i] += s->xb[i];
        }
    }

    // final rmsnorm
    rmsnorm(x, x, w->rms_final_weight, dim);

    // classifier into logits
    matmul(s->logits, x, w->wcls, p->dim, p->vocab_size);
    return s->logits;
}

// ----------------------------------------------------------------------------
// The Byte Pair Encoding (BPE) Tokenizer that translates strings <-> tokens

typedef struct {
    char *str;
    int id;
} TokenIndex;

typedef struct {
    char** vocab;
    float* vocab_scores;
    TokenIndex *sorted_vocab;
    int vocab_size;
    unsigned int max_token_length;
    unsigned char byte_pieces[512]; // stores all single-byte strings
} Tokenizer;

int compare_tokens(const void *a, const void *b) {
    return strcmp(((TokenIndex*)a)->str, ((TokenIndex*)b)->str);
}

void build_tokenizer(Tokenizer* t, char* tokenizer_path, int vocab_size) {
    // i should have written the vocab_size into the tokenizer file... sigh
    t->vocab_size = vocab_size;
    // malloc space to hold the scores and the strings
    t->vocab = (char**)malloc(vocab_size * sizeof(char*));
    t->vocab_scores = (float*)malloc(vocab_size * sizeof(float));
    t->sorted_vocab = NULL; // initialized lazily
    for (int i = 0; i < 256; i++) {
        t->byte_pieces[i * 2] = (unsigned char)i;
        t->byte_pieces[i * 2 + 1] = '\0';
    }
    // read in the file
    FILE *file = fopen(tokenizer_path, "rb");
    if (!file) { fprintf(stderr, "couldn't load %s\n", tokenizer_path); exit(EXIT_FAILURE); }
    if (fread(&t->max_token_length, sizeof(int), 1, file) != 1) { fprintf(stderr, "failed read\n"); exit(EXIT_FAILURE); }
    int len;
    for (int i = 0; i < vocab_size; i++) {
        if (fread(t->vocab_scores + i, sizeof(float), 1, file) != 1) { fprintf(stderr, "failed read\n"); exit(EXIT_FAILURE);}
        if (fread(&len, sizeof(int), 1, file) != 1) { fprintf(stderr, "failed read\n"); exit(EXIT_FAILURE); }
        t->vocab[i] = (char *)malloc(len + 1);
        if (fread(t->vocab[i], len, 1, file) != 1) { fprintf(stderr, "failed read\n"); exit(EXIT_FAILURE); }
        t->vocab[i][len] = '\0'; // add the string terminating token
    }
    fclose(file);
}

void free_tokenizer(Tokenizer* t) {
    for (int i = 0; i < t->vocab_size; i++) { free(t->vocab[i]); }
    free(t->vocab);
    free(t->vocab_scores);
    free(t->sorted_vocab);
}

char* decode(Tokenizer* t, int prev_token, int token) {
    char *piece = t->vocab[token];
    // following BOS (1) token, sentencepiece decoder strips any leading whitespace (see PR #89)
    if (prev_token == 1 && piece[0] == ' ') { piece++; }
    // careful, some tokens designate raw bytes, and look like e.g. '<0x01>'
    // parse this and convert and return the actual byte
    unsigned char byte_val;
    if (sscanf(piece, "<0x%02hhX>", &byte_val) == 1) {
        piece = (char*)t->byte_pieces + byte_val * 2;
    }
    return piece;
}

void safe_printf(char *piece) {
    // piece might be a raw byte token, and we only want to print printable chars or whitespace
    // because some of the other bytes can be various control codes, backspace, etc.
    if (piece == NULL) { return; }
    if (piece[0] == '\0') { return; }
    if (piece[1] == '\0') {
        unsigned char byte_val = piece[0];
        if (!(isprint(byte_val) || isspace(byte_val))) {
            return; // bad byte, don't print it
        }
    }
    printf("%s", piece);
}

int str_lookup(char *str, TokenIndex *sorted_vocab, int vocab_size) {
    // efficiently find the perfect match for str in vocab, return its index or -1 if not found
    TokenIndex tok = { .str = str }; // acts as the key to search for
    TokenIndex *res = bsearch(&tok, sorted_vocab, vocab_size, sizeof(TokenIndex), compare_tokens);
    return res != NULL ? res->id : -1;
}

void encode(Tokenizer* t, char *text, int8_t bos, int8_t eos, int *tokens, int *n_tokens) {
    // encode the string text (input) into an upper-bound preallocated tokens[] array
    // bos != 0 means prepend the BOS token (=1), eos != 0 means append the EOS token (=2)
    if (text == NULL) { fprintf(stderr, "cannot encode NULL text\n"); exit(EXIT_FAILURE); }

    if (t->sorted_vocab == NULL) {
        // lazily malloc and sort the vocabulary
        t->sorted_vocab = malloc(t->vocab_size * sizeof(TokenIndex));
        for (int i = 0; i < t->vocab_size; i++) {
            t->sorted_vocab[i].str = t->vocab[i];
            t->sorted_vocab[i].id = i;
        }
        qsort(t->sorted_vocab, t->vocab_size, sizeof(TokenIndex), compare_tokens);
    }

    // create a temporary buffer that will store merge candidates of always two consecutive tokens
    // *2 for concat, +1 for null terminator +2 for UTF8 (in case max_token_length is 1)
    char* str_buffer = malloc((t->max_token_length*2 +1 +2) * sizeof(char));
    size_t str_len = 0;

    // start at 0 tokens
    *n_tokens = 0;

    // add optional BOS (=1) token, if desired
    if (bos) tokens[(*n_tokens)++] = 1;

    // add_dummy_prefix is true by default
    // so prepend a dummy prefix token to the input string, but only if text != ""
    // TODO: pretty sure this isn't correct in the general case but I don't have the
    // energy to read more of the sentencepiece code to figure out what it's doing
    if (text[0] != '\0') {
        int dummy_prefix = str_lookup(" ", t->sorted_vocab, t->vocab_size);
        tokens[(*n_tokens)++] = dummy_prefix;
    }

    // Okay UTF-8 time. This will get messy. Here is the reference from Wikipedia:
    // Code point ↔ UTF-8 conversion
    // First code point	Last code point	Byte 1	Byte 2	Byte 3	Byte 4
    // U+0000	U+007F	    0xxxxxxx
    // U+0080	U+07FF	    110xxxxx	10xxxxxx
    // U+0800	U+FFFF	    1110xxxx	10xxxxxx	10xxxxxx
    // U+10000	U+10FFFF    11110xxx	10xxxxxx	10xxxxxx	10xxxxxx

    // process the raw (UTF-8) byte sequence of the input string
    for (char *c = text; *c != '\0'; c++) {

        // reset buffer if the current byte is ASCII or a leading byte
        // 0xC0 is 11000000, so (*c & 0xC0) keeps the first 2 bits and zeros the rest
        // 0x80 is 10000000
        // in UTF-8, all continuation bytes start with "10" in first two bits
        // so in English this is: "if this byte is not a continuation byte"
        if ((*c & 0xC0) != 0x80) {
            // this byte must be either a leading byte (11...) or an ASCII char (0x...)
            // => reset our location, as we're starting a new UTF-8 codepoint
            str_len = 0;
        }

        // append the current byte to the buffer
        str_buffer[str_len++] = *c; // ++ is post-increment, incremented after this line
        str_buffer[str_len] = '\0';

        // while the next character is a continuation byte, continue appending
        // but if there are too many of them, just stop to avoid overruning str_buffer size.
        if ((*(c+1) & 0xC0) == 0x80 && str_len < 4) {
            continue;
        }

        // ok c+1 is not a continuation byte, so we've read in a full codepoint
        int id = str_lookup(str_buffer, t->sorted_vocab, t->vocab_size);

        if (id != -1) {
            // we found this codepoint in vocab, add it as a token
            tokens[(*n_tokens)++] = id;
        } else {
            // byte_fallback encoding: just encode each byte as a token
            // +3 is here because the first 3 vocab elements are <unk>, <s>, </s>
            // so the individual bytes only start at index 3
            for (int i=0; i < str_len; i++) {
                tokens[(*n_tokens)++] = (unsigned char)str_buffer[i] + 3;
            }
        }
        str_len = 0; // protect against a sequence of stray UTF8 continuation bytes
    }

    // merge the best consecutive pair each iteration, according the scores in vocab_scores
    while (1) {
        float best_score = -1e10;
        int best_id = -1;
        int best_idx = -1;

        for (int i=0; i < (*n_tokens-1); i++) {
            // check if we can merge the pair (tokens[i], tokens[i+1])
            sprintf(str_buffer, "%s%s", t->vocab[tokens[i]], t->vocab[tokens[i+1]]);
            int id = str_lookup(str_buffer, t->sorted_vocab, t->vocab_size);
            if (id != -1 && t->vocab_scores[id] > best_score) {
                // this merge pair exists in vocab! record its score and position
                best_score = t->vocab_scores[id];
                best_id = id;
                best_idx = i;
            }
        }

        if (best_idx == -1) {
            break; // we couldn't find any more pairs to merge, so we're done
        }

        // merge the consecutive pair (best_idx, best_idx+1) into new token best_id
        tokens[best_idx] = best_id;
        // delete token at position best_idx+1, shift the entire sequence back 1
        for (int i = best_idx+1; i < (*n_tokens-1); i++) {
            tokens[i] = tokens[i+1];
        }
        (*n_tokens)--; // token length decreased
    }

    // add optional EOS (=2) token, if desired
    if (eos) tokens[(*n_tokens)++] = 2;

    free(str_buffer);
}

// ----------------------------------------------------------------------------
// The Sampler, which takes logits and returns a sampled token
// sampling can be done in a few ways: greedy argmax, sampling, top-p sampling

typedef struct {
    float prob;
    int index;
} ProbIndex; // struct used when sorting probabilities during top-p sampling

typedef struct {
    int vocab_size;
    ProbIndex* probindex; // buffer used in top-p sampling
    float temperature;
    float topp;
    unsigned long long rng_state;
} Sampler;

int sample_argmax(float* probabilities, int n) {
    // return the index that has the highest probability
    int max_i = 0;
    float max_p = probabilities[0];
    for (int i = 1; i < n; i++) {
        if (probabilities[i] > max_p) {
            max_i = i;
            max_p = probabilities[i];
        }
    }
    return max_i;
}

int sample_mult(float* probabilities, int n, float coin) {
    // sample index from probabilities (they must sum to 1!)
    // coin is a random number in [0, 1), usually from random_f32()
    float cdf = 0.0f;
    for (int i = 0; i < n; i++) {
        cdf += probabilities[i];
        if (coin < cdf) {
            return i;
        }
    }
    return n - 1; // in case of rounding errors
}

int compare(const void* a, const void* b) {
    ProbIndex* a_ = (ProbIndex*) a;
    ProbIndex* b_ = (ProbIndex*) b;
    if (a_->prob > b_->prob) return -1;
    if (a_->prob < b_->prob) return 1;
    return 0;
}

int sample_topp(float* probabilities, int n, float topp, ProbIndex* probindex, float coin) {
    // top-p sampling (or "nucleus sampling") samples from the smallest set of
    // tokens that exceed probability topp. This way we never sample tokens that
    // have very low probabilities and are less likely to go "off the rails".
    // coin is a random number in [0, 1), usually from random_f32()

    int n0 = 0;
    // quicksort indices in descending order of probabilities
    // values smaller than (1 - topp) / (n - 1) cannot be part of the result
    // so for efficiency we crop these out as candidates before sorting
    const float cutoff = (1.0f - topp) / (n - 1);
    for (int i = 0; i < n; i++) {
        if (probabilities[i] >= cutoff) {
            probindex[n0].index = i;
            probindex[n0].prob = probabilities[i];
            n0++;
        }
    }
    qsort(probindex, n0, sizeof(ProbIndex), compare);

    // truncate the list where cumulative probability exceeds topp
    float cumulative_prob = 0.0f;
    int last_idx = n0 - 1; // in case of rounding errors consider all elements
    for (int i = 0; i < n0; i++) {
        cumulative_prob += probindex[i].prob;
        if (cumulative_prob > topp) {
            last_idx = i;
            break; // we've exceeded topp by including last_idx
        }
    }

    // sample from the truncated list
    float r = coin * cumulative_prob;
    float cdf = 0.0f;
    for (int i = 0; i <= last_idx; i++) {
        cdf += probindex[i].prob;
        if (r < cdf) {
            return probindex[i].index;
        }
    }
    return probindex[last_idx].index; // in case of rounding errors
}

void build_sampler(Sampler* sampler, int vocab_size, float temperature, float topp, unsigned long long rng_seed) {
    sampler->vocab_size = vocab_size;
    sampler->temperature = temperature;
    sampler->topp = topp;
    sampler->rng_state = rng_seed;
    // buffer only used with nucleus sampling; may not need but it's ~small
    sampler->probindex = malloc(sampler->vocab_size * sizeof(ProbIndex));
}

void free_sampler(Sampler* sampler) {
    free(sampler->probindex);
}

unsigned int random_u32(unsigned long long *state) {
    // xorshift rng: https://en.wikipedia.org/wiki/Xorshift#xorshift.2A
    *state ^= *state >> 12;
    *state ^= *state << 25;
    *state ^= *state >> 27;
    return (*state * 0x2545F4914F6CDD1Dull) >> 32;
}
float random_f32(unsigned long long *state) { // random float32 in [0,1)
    return (random_u32(state) >> 8) / 16777216.0f;
}

int sample(Sampler* sampler, float* logits) {
    // sample the token given the logits and some hyperparameters
    int next;
    if (sampler->temperature == 0.0f) {
        // greedy argmax sampling: take the token with the highest probability
        next = sample_argmax(logits, sampler->vocab_size);
    } else {
        // apply the temperature to the logits
        for (int q=0; q<sampler->vocab_size; q++) { logits[q] /= sampler->temperature; }
        // apply softmax to the logits to get the probabilities for next token
        softmax(logits, sampler->vocab_size);
        // flip a (float) coin (this is our source of entropy for sampling)
        float coin = random_f32(&sampler->rng_state);
        // we sample from this distribution to get the next token
        if (sampler->topp <= 0 || sampler->topp >= 1) {
            // simply sample from the predicted probability distribution
            next = sample_mult(logits, sampler->vocab_size, coin);
        } else {
            // top-p (nucleus) sampling, clamping the least likely tokens to zero
            next = sample_topp(logits, sampler->vocab_size, sampler->topp, sampler->probindex, coin);
        }
    }
    return next;
}

// ----------------------------------------------------------------------------
// utilities: time

long time_in_ms() {
    // return time in milliseconds, for benchmarking the model speed
    struct timespec time;
    clock_gettime(CLOCK_REALTIME, &time);
    return time.tv_sec * 1000 + time.tv_nsec / 1000000;
}

// ----------------------------------------------------------------------------
// generation loop

void generate(Transformer *transformer, Tokenizer *tokenizer, Sampler *sampler, char *prompt, int steps) {
    char *empty_prompt = "";
    if (prompt == NULL) { prompt = empty_prompt; }

    // encode the (string) prompt into tokens sequence
    int num_prompt_tokens = 0;
    int* prompt_tokens = (int*)malloc((strlen(prompt)+3) * sizeof(int)); // +3 for '\0', ?BOS, ?EOS
    encode(tokenizer, prompt, 1, 0, prompt_tokens, &num_prompt_tokens);
    if (num_prompt_tokens < 1) {
        fprintf(stderr, "something is wrong, expected at least 1 prompt token\n");
        exit(EXIT_FAILURE);
    }

    // start the main loop
    long start = 0;  // used to time our code, only initialized after first iteration
    int next;        // will store the next token in the sequence
    int token = prompt_tokens[0]; // kick off with the first token in the prompt
    int pos = 0;     // position in the sequence
    while (pos < steps) {

        // forward the transformer to get logits for the next token
        float* logits = forward(transformer, token, pos);

        // advance the state machine
        if (pos < num_prompt_tokens - 1) {
            // if we are still processing the input prompt, force the next prompt token
            next = prompt_tokens[pos + 1];
        } else {
            // otherwise sample the next token from the logits
            next = sample(sampler, logits);
        }
        pos++;

        // data-dependent terminating condition: the BOS (=1) token delimits sequences
        if (next == 1) { break; }

        // print the token as string, decode it with the Tokenizer object
        char* piece = decode(tokenizer, token, next);
        safe_printf(piece); // same as printf("%s", piece), but skips "unsafe" bytes
        fflush(stdout);
        token = next;

        // init the timer here because the first iteration can be slower
        if (start == 0) { start = time_in_ms(); }
    }
    printf("\n");

    // report achieved tok/s (pos-1 because the timer starts after first iteration)
    if (pos > 1) {
        long end = time_in_ms();
        fprintf(stderr, "achieved tok/s: %f\n", (pos-1) / (double)(end-start)*1000);
    }

    free(prompt_tokens);
}

void read_stdin(const char* guide, char* buffer, size_t bufsize) {
    // read a line from stdin, up to but not including \n
    printf("%s", guide);
    if (fgets(buffer, bufsize, stdin) != NULL) {
        size_t len = strlen(buffer);
        if (len > 0 && buffer[len - 1] == '\n') {
            buffer[len - 1] = '\0'; // strip newline
        }
    }
}

// ----------------------------------------------------------------------------
// chat loop
// I manually inspected the tokens for a few chat conversations compared to
// python reference and that seemed ok, but this was not thoroughly tested and
// is not safely implemented, it's more a proof of concept atm.

void chat(Transformer *transformer, Tokenizer *tokenizer, Sampler *sampler,
          char *cli_user_prompt, char *cli_system_prompt, int steps) {

    // buffers for reading the system prompt and user prompt from stdin
    // you'll notice they are soomewhat haphazardly and unsafely set atm
    char system_prompt[512];
    char user_prompt[512];
    char rendered_prompt[1152];
    int num_prompt_tokens = 0;
    int* prompt_tokens = (int*)malloc(1152 * sizeof(int));
    int user_idx;

    // start the main loop
    int8_t user_turn = 1; // user starts
    int next;        // will store the next token in the sequence
    int token;       // stores the current token to feed into the transformer
    int prev_token;
    int pos = 0;     // position in the sequence
    while (pos < steps) {

        // when it is the user's turn to contribute tokens to the dialog...
        if (user_turn) {
            // get the (optional) system prompt at position 0
            if (pos == 0) {
                // at position 0, the user can also contribute a system prompt
                if (cli_system_prompt == NULL) {
                    // system prompt was not passed in, attempt to get it from stdin
                    read_stdin("Enter system prompt (optional): ", system_prompt, sizeof(system_prompt));
                } else {
                    // system prompt was passed in, use it
                    strcpy(system_prompt, cli_system_prompt);
                }
            }
            // get the user prompt
            if (pos == 0 && cli_user_prompt != NULL) {
                // user prompt for position 0 was passed in, use it
                strcpy(user_prompt, cli_user_prompt);
            } else {
                // otherwise get user prompt from stdin
                read_stdin("User: ", user_prompt, sizeof(user_prompt));
            }
            // render user/system prompts into the Llama 2 Chat schema
            if (pos == 0 && system_prompt[0] != '\0') {
                char system_template[] = "[INST] <<SYS>>\n%s\n<</SYS>>\n\n%s [/INST]";
                sprintf(rendered_prompt, system_template, system_prompt, user_prompt);
            } else {
                char user_template[] = "[INST] %s [/INST]";
                sprintf(rendered_prompt, user_template, user_prompt);
            }
            // encode the rendered prompt into tokens
            encode(tokenizer, rendered_prompt, 1, 0, prompt_tokens, &num_prompt_tokens);
            user_idx = 0; // reset the user index
            user_turn = 0;
            printf("Assistant: ");
        }

        // determine the token to pass into the transformer next
        if (user_idx < num_prompt_tokens) {
            // if we are still processing the input prompt, force the next prompt token
            token = prompt_tokens[user_idx++];
        } else {
            // otherwise use the next token sampled from previous turn
            token = next;
        }
        // EOS (=2) token ends the Assistant turn
        if (token == 2) { user_turn = 1; }

        // forward the transformer to get logits for the next token
        float* logits = forward(transformer, token, pos);
        next = sample(sampler, logits);
        pos++;

        if (user_idx >= num_prompt_tokens && next != 2) {
            // the Assistant is responding, so print its output
            char* piece = decode(tokenizer, token, next);
            safe_printf(piece); // same as printf("%s", piece), but skips "unsafe" bytes
            fflush(stdout);
        }
        if (next == 2) { printf("\n"); }
    }
    printf("\n");
    free(prompt_tokens);
}


// ----------------------------------------------------------------------------
// CLI, include only if not testing
#ifndef TESTING

void error_usage() {
    fprintf(stderr, "Usage:   run_nnapi <checkpoint> [options]\n");
    fprintf(stderr, "Example: run_nnapi model.bin -n 256 -i \"Once upon a time\"\n");
    fprintf(stderr, "Options:\n");
    fprintf(stderr, "  -t <float>  temperature in [0,inf], default 1.0\n");
    fprintf(stderr, "  -p <float>  p value in top-p (nucleus) sampling in [0,1] default 0.9\n");
    fprintf(stderr, "  -s <int>    random seed, default time(NULL)\n");
    fprintf(stderr, "  -n <int>    number of steps to run for, default 256. 0 = max_seq_len\n");
    fprintf(stderr, "  -i <string> input prompt\n");
    fprintf(stderr, "  -z <string> optional path to custom tokenizer\n");
    fprintf(stderr, "  -m <string> mode: generate|chat, default: generate\n");
    fprintf(stderr, "  -y <string> (optional) system prompt in chat mode\n");
    fprintf(stderr, "Environment:\n");
    fprintf(stderr, "  NNAPI_DEVICE     NNAPI device for the matmuls, default mtk-mdla_shim, then mtk-neuron_shim.\n");
    fprintf(stderr, "                   off = no NNAPI, the plain C matmul\n");
    fprintf(stderr, "  NNAPI_CACHE_DIR  compilation cache directory, default ./nnapi_cache. empty = no cache\n");
    fprintf(stderr, "  NNAPI_VERIFY     1 = also run every matmul on the CPU and report the largest difference\n");
    fprintf(stderr, "  NNAPI_Q8         1 = 8-bit weights in the NNAPI graphs for an fp32 checkpoint too, turned\n");
    fprintf(stderr, "                   into fp32 inside the graph. a Q8_0 checkpoint always has them.\n");
    fprintf(stderr, "                   2 = input and output of the graphs in 8 bits as well\n");
    fprintf(stderr, "  NNAPI_WEIGHTS_FILE  name of the files to keep those 8-bit weights in and map them from,\n");
    fprintf(stderr, "                   one per matrix: model.bin.nnapi8 gives model.bin.nnapi8.0, .1, ...\n");
    fprintf(stderr, "                   default: they are made in RAM at every run\n");
    exit(EXIT_FAILURE);
}

int main(int argc, char *argv[]) {

    // default parameters
    char *checkpoint_path = NULL;  // e.g. out/model.bin
    char *tokenizer_path = "tokenizer.bin";
    float temperature = 1.0f;   // 0.0 = greedy deterministic. 1.0 = original. don't set higher
    float topp = 0.9f;          // top-p in nucleus sampling. 1.0 = off. 0.9 works well, but slower
    int steps = 256;            // number of steps to run for
    char *prompt = NULL;        // prompt string
    unsigned long long rng_seed = 0; // seed rng with time by default
    char *mode = "generate";    // generate|chat
    char *system_prompt = NULL; // the (optional) system prompt to use in chat mode

    // poor man's C argparse so we can override the defaults above from the command line
    if (argc >= 2) { checkpoint_path = argv[1]; } else { error_usage(); }
    for (int i = 2; i < argc; i+=2) {
        // do some basic validation
        if (i + 1 >= argc) { error_usage(); } // must have arg after flag
        if (argv[i][0] != '-') { error_usage(); } // must start with dash
        if (strlen(argv[i]) != 2) { error_usage(); } // must be -x (one dash, one letter)
        // read in the args
        if (argv[i][1] == 't') { temperature = atof(argv[i + 1]); }
        else if (argv[i][1] == 'p') { topp = atof(argv[i + 1]); }
        else if (argv[i][1] == 's') { rng_seed = atoi(argv[i + 1]); }
        else if (argv[i][1] == 'n') { steps = atoi(argv[i + 1]); }
        else if (argv[i][1] == 'i') { prompt = argv[i + 1]; }
        else if (argv[i][1] == 'z') { tokenizer_path = argv[i + 1]; }
        else if (argv[i][1] == 'm') { mode = argv[i + 1]; }
        else if (argv[i][1] == 'y') { system_prompt = argv[i + 1]; }
        else { error_usage(); }
    }

    // parameter validation/overrides
    if (rng_seed <= 0) rng_seed = (unsigned int)time(NULL);
    if (temperature < 0.0) temperature = 0.0;
    if (topp < 0.0 || 1.0 < topp) topp = 0.9;
    if (steps < 0) steps = 0;

    // build the Transformer via the model .bin file
    Transformer transformer;
    build_transformer(&transformer, checkpoint_path);
    nn.checkpoint = checkpoint_path; // a file of 8-bit weights is checked against it
    if (steps == 0 || steps > transformer.config.seq_len) steps = transformer.config.seq_len; // override to ~max length

    // build the Tokenizer via the tokenizer .bin file
    Tokenizer tokenizer;
    build_tokenizer(&tokenizer, tokenizer_path, transformer.config.vocab_size);

    // build the Sampler
    Sampler sampler;
    build_sampler(&sampler, transformer.config.vocab_size, temperature, topp, rng_seed);

    // run!
    if (strcmp(mode, "generate") == 0) {
        generate(&transformer, &tokenizer, &sampler, prompt, steps);
    } else if (strcmp(mode, "chat") == 0) {
        chat(&transformer, &tokenizer, &sampler, prompt, system_prompt, steps);
    } else {
        fprintf(stderr, "unknown mode: %s\n", mode);
        error_usage();
    }
    report_matmul();

    // memory and file handles cleanup
    free_matmul(); // before free_transformer: the NNAPI models point at the mapped weights
    free_sampler(&sampler);
    free_tokenizer(&tokenizer);
    free_transformer(&transformer);
    return 0;
}
#endif
