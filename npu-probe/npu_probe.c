// Probes MediaTek's public Neuron Adapter API on the device: version, devices, on-device
// compilation, execution and compiled network (DLA) store/restore.
// Built twice from this file: as a shell executable (-DPROBE_EXE) and as a NativeActivity library.

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

#define TAG "NPUPROBE"
#define MAX_DEVS 16

typedef struct NeuronModel NeuronModel;
typedef struct NeuronCompilation NeuronCompilation;
typedef struct NeuronExecution NeuronExecution;
typedef struct NeuronDevice NeuronDevice;

typedef struct {
    uint8_t major;
    uint8_t minor;
    uint8_t patch;
} NeuronRuntimeVersion;

typedef struct {
    int32_t type;
    uint32_t dimensionCount;
    const uint32_t* dimensions;
    float scale;
    int32_t zeroPoint;
} NeuronOperandType;

// Same numbering as NNAPI
enum { NEURON_FLOAT32 = 0, NEURON_INT32 = 1, NEURON_TENSOR_FLOAT32 = 3 };
enum { NEURON_CONV_2D = 3, NEURON_FULLY_CONNECTED = 9, NEURON_SOFTMAX = 25 };

static int (*Neuron_getVersion)(NeuronRuntimeVersion*);
static int (*Neuron_getL1MemorySizeKb)(uint32_t*);
static int (*Neuron_getDeviceCount)(uint32_t*);
static int (*Neuron_getDevice)(uint32_t, NeuronDevice**);
static int (*NeuronDevice_getName)(const NeuronDevice*, const char**);
static int (*NeuronModel_create)(NeuronModel**);
static void (*NeuronModel_free)(NeuronModel*);
static int (*NeuronModel_addOperand)(NeuronModel*, const NeuronOperandType*);
static int (*NeuronModel_setOperandValue)(NeuronModel*, int32_t, const void*, size_t);
static int (*NeuronModel_addOperation)(NeuronModel*, int32_t, uint32_t, const uint32_t*, uint32_t,
                                       const uint32_t*);
static int (*NeuronModel_identifyInputsAndOutputs)(NeuronModel*, uint32_t, const uint32_t*,
                                                   uint32_t, const uint32_t*);
static int (*NeuronModel_relaxComputationFloat32toFloat16)(NeuronModel*, bool);
static int (*NeuronModel_finish)(NeuronModel*);
static int (*NeuronModel_getSupportedOperationsForDevices)(const NeuronModel*,
                                                           const NeuronDevice* const*, uint32_t,
                                                           bool*);
static int (*NeuronModel_restoreFromCompiledNetwork)(NeuronModel**, NeuronCompilation**,
                                                     const void*, size_t);
static int (*NeuronCompilation_create)(NeuronModel*, NeuronCompilation**);
static int (*NeuronCompilation_createForDevices)(NeuronModel*, const NeuronDevice* const*,
                                                 uint32_t, NeuronCompilation**);
static void (*NeuronCompilation_free)(NeuronCompilation*);
static int (*NeuronCompilation_finish)(NeuronCompilation*);
static int (*NeuronCompilation_getCompiledNetworkSize)(NeuronCompilation*, size_t*);
static int (*NeuronCompilation_storeCompiledNetwork)(NeuronCompilation*, void*, size_t);
static int (*NeuronExecution_create)(NeuronCompilation*, NeuronExecution**);
static void (*NeuronExecution_free)(NeuronExecution*);
static int (*NeuronExecution_setInput)(NeuronExecution*, int32_t, const NeuronOperandType*,
                                       const void*, size_t);
static int (*NeuronExecution_setOutput)(NeuronExecution*, int32_t, const NeuronOperandType*,
                                        void*, size_t);
static int (*NeuronExecution_compute)(NeuronExecution*);

static void* g_lib;
static FILE* g_file;

// Every line is flushed so a crash inside the vendor library still shows the last step reached
static void out(const char* fmt, ...) {
    char line[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof line, fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", line);
    printf("%s\n", line);
    fflush(stdout);
    if (g_file) {
        fprintf(g_file, "%s\n", line);
        fflush(g_file);
    }
}

static int load_symbols(void) {
    int missing = 0;
#define LOAD(name)                                                                                 \
    if (!(name = dlsym(g_lib, #name))) {                                                           \
        out("falta el simbolo %s", #name);                                                         \
        missing++;                                                                                 \
    }
    LOAD(Neuron_getVersion)
    LOAD(Neuron_getL1MemorySizeKb)
    LOAD(Neuron_getDeviceCount)
    LOAD(Neuron_getDevice)
    LOAD(NeuronDevice_getName)
    LOAD(NeuronModel_create)
    LOAD(NeuronModel_free)
    LOAD(NeuronModel_addOperand)
    LOAD(NeuronModel_setOperandValue)
    LOAD(NeuronModel_addOperation)
    LOAD(NeuronModel_identifyInputsAndOutputs)
    LOAD(NeuronModel_relaxComputationFloat32toFloat16)
    LOAD(NeuronModel_finish)
    LOAD(NeuronModel_getSupportedOperationsForDevices)
    LOAD(NeuronModel_restoreFromCompiledNetwork)
    LOAD(NeuronCompilation_create)
    LOAD(NeuronCompilation_createForDevices)
    LOAD(NeuronCompilation_free)
    LOAD(NeuronCompilation_finish)
    LOAD(NeuronCompilation_getCompiledNetworkSize)
    LOAD(NeuronCompilation_storeCompiledNetwork)
    LOAD(NeuronExecution_create)
    LOAD(NeuronExecution_free)
    LOAD(NeuronExecution_setInput)
    LOAD(NeuronExecution_setOutput)
    LOAD(NeuronExecution_compute)
#undef LOAD
    return missing;
}

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

#define CONV_C 16
#define CONV_HW 8
#define CONV_N (CONV_HW * CONV_HW * CONV_C)
#define FC_N 64

// Constant operands are referenced, not copied, so they must outlive the models
static float g_conv_filter[CONV_C * CONV_C];
static float g_conv_bias[CONV_C];
static float g_fc_weights[FC_N * FC_N];
static float g_fc_bias[FC_N];
static const int32_t g_one = 1;
static const int32_t g_zero = 0;
static const float g_beta = 1.0f;

static int add_tensor(NeuronModel* m, const uint32_t* dims, uint32_t rank) {
    NeuronOperandType t = {NEURON_TENSOR_FLOAT32, rank, dims, 0.0f, 0};
    return NeuronModel_addOperand(m, &t);
}

static int add_scalar(NeuronModel* m, int32_t type) {
    NeuronOperandType t = {type, 0, NULL, 0.0f, 0};
    return NeuronModel_addOperand(m, &t);
}

static NeuronModel* finish_model(NeuronModel* m, int e) {
    if (!e) {
        e = NeuronModel_relaxComputationFloat32toFloat16(m, true);
    }
    if (!e) {
        e = NeuronModel_finish(m);
    }
    if (e) {
        out("  construccion del modelo FALLO (%d)", e);
        NeuronModel_free(m);
        return NULL;
    }
    return m;
}

// 1x1 convolution with identity weights: output must equal input
static NeuronModel* build_conv(void) {
    static const uint32_t in_dims[] = {1, CONV_HW, CONV_HW, CONV_C};
    static const uint32_t filter_dims[] = {CONV_C, 1, 1, CONV_C};
    static const uint32_t bias_dims[] = {CONV_C};
    static const uint32_t op_in[] = {0, 1, 2, 3, 4, 5, 6};
    static const uint32_t op_out[] = {7};
    static const uint32_t model_in[] = {0};
    NeuronModel* m = NULL;
    int e = NeuronModel_create(&m);
    if (e || !m) {
        out("  NeuronModel_create FALLO (%d)", e);
        return NULL;
    }
    e |= add_tensor(m, in_dims, 4);
    e |= add_tensor(m, filter_dims, 4);
    e |= add_tensor(m, bias_dims, 1);
    e |= add_scalar(m, NEURON_INT32); // padding scheme
    e |= add_scalar(m, NEURON_INT32); // stride width
    e |= add_scalar(m, NEURON_INT32); // stride height
    e |= add_scalar(m, NEURON_INT32); // fused activation
    e |= add_tensor(m, in_dims, 4);
    e |= NeuronModel_setOperandValue(m, 1, g_conv_filter, sizeof g_conv_filter);
    e |= NeuronModel_setOperandValue(m, 2, g_conv_bias, sizeof g_conv_bias);
    e |= NeuronModel_setOperandValue(m, 3, &g_one, sizeof g_one); // SAME
    e |= NeuronModel_setOperandValue(m, 4, &g_one, sizeof g_one);
    e |= NeuronModel_setOperandValue(m, 5, &g_one, sizeof g_one);
    e |= NeuronModel_setOperandValue(m, 6, &g_zero, sizeof g_zero);
    e |= NeuronModel_addOperation(m, NEURON_CONV_2D, 7, op_in, 1, op_out);
    e |= NeuronModel_identifyInputsAndOutputs(m, 1, model_in, 1, op_out);
    return finish_model(m, e);
}

// Fully connected with identity weights followed by softmax: output must equal softmax(input)
static NeuronModel* build_fc_softmax(void) {
    static const uint32_t io_dims[] = {1, FC_N};
    static const uint32_t weight_dims[] = {FC_N, FC_N};
    static const uint32_t bias_dims[] = {FC_N};
    static const uint32_t fc_in[] = {0, 1, 2, 3};
    static const uint32_t fc_out[] = {4};
    static const uint32_t sm_in[] = {4, 5};
    static const uint32_t sm_out[] = {6};
    static const uint32_t model_in[] = {0};
    NeuronModel* m = NULL;
    int e = NeuronModel_create(&m);
    if (e || !m) {
        out("  NeuronModel_create FALLO (%d)", e);
        return NULL;
    }
    e |= add_tensor(m, io_dims, 2);
    e |= add_tensor(m, weight_dims, 2);
    e |= add_tensor(m, bias_dims, 1);
    e |= add_scalar(m, NEURON_INT32); // fused activation
    e |= add_tensor(m, io_dims, 2);
    e |= add_scalar(m, NEURON_FLOAT32); // beta
    e |= add_tensor(m, io_dims, 2);
    e |= NeuronModel_setOperandValue(m, 1, g_fc_weights, sizeof g_fc_weights);
    e |= NeuronModel_setOperandValue(m, 2, g_fc_bias, sizeof g_fc_bias);
    e |= NeuronModel_setOperandValue(m, 3, &g_zero, sizeof g_zero);
    e |= NeuronModel_setOperandValue(m, 5, &g_beta, sizeof g_beta);
    e |= NeuronModel_addOperation(m, NEURON_FULLY_CONNECTED, 4, fc_in, 1, fc_out);
    e |= NeuronModel_addOperation(m, NEURON_SOFTMAX, 2, sm_in, 1, sm_out);
    e |= NeuronModel_identifyInputsAndOutputs(m, 1, model_in, 1, sm_out);
    return finish_model(m, e);
}

static int execute(NeuronCompilation* c, const float* in, float* got, size_t n) {
    NeuronExecution* x = NULL;
    int e = NeuronExecution_create(c, &x);
    if (e || !x) {
        return e ? e : -1;
    }
    e = NeuronExecution_setInput(x, 0, NULL, in, n * sizeof(float));
    if (!e) {
        e = NeuronExecution_setOutput(x, 0, NULL, got, n * sizeof(float));
    }
    if (!e) {
        e = NeuronExecution_compute(x);
    }
    NeuronExecution_free(x);
    return e;
}

static void bench(const char* label, NeuronCompilation* c, const float* in, const float* expect,
                  size_t n) {
    enum { RUNS = 20 };
    float* got = calloc(n, sizeof(float));
    int e = execute(c, in, got, n);
    if (e) {
        out("  %s: ejecucion FALLO (%d)", label, e);
        free(got);
        return;
    }
    // Counted with a negated comparison so NaN outputs are reported as wrong
    size_t bad = 0;
    float max_err = 0.0f;
    for (size_t i = 0; i < n; i++) {
        const float d = fabsf(got[i] - expect[i]);
        if (!(d < 2e-3f)) {
            bad++;
        }
        if (d > max_err) {
            max_err = d;
        }
    }
    const double t0 = now_ms();
    for (int i = 0; i < RUNS && !e; i++) {
        e = execute(c, in, got, n);
    }
    const double avg = (now_ms() - t0) / RUNS;
    out("  %s: ejecucion OK, valores incorrectos %zu/%zu, error max %.5f, %.3f ms por ejecucion",
        label, bad, n, max_err, avg);
    free(got);
}

static void store_restore(const char* name, NeuronCompilation* c, const float* in,
                          const float* expect, size_t n, const char* dir) {
    size_t size = 0;
    int e = NeuronCompilation_getCompiledNetworkSize(c, &size);
    if (e || !size) {
        out("  guardar DLA: getCompiledNetworkSize FALLO (%d, %zu bytes)", e, size);
        return;
    }
    void* buf = malloc(size);
    e = NeuronCompilation_storeCompiledNetwork(c, buf, size);
    if (e) {
        out("  guardar DLA: storeCompiledNetwork FALLO (%d)", e);
        free(buf);
        return;
    }
    char path[600];
    snprintf(path, sizeof path, "%s/%s.dla", dir, name);
    FILE* f = fopen(path, "wb");
    if (f) {
        fwrite(buf, 1, size, f);
        fclose(f);
    }
    out("  DLA guardado: %zu bytes en %s", size, f ? path : "(no se pudo escribir el archivo)");

    out("  restaurando DLA...");
    NeuronModel* m2 = NULL;
    NeuronCompilation* c2 = NULL;
    e = NeuronModel_restoreFromCompiledNetwork(&m2, &c2, buf, size);
    if (e || !c2) {
        out("  restaurar DLA FALLO (%d)", e);
    } else {
        bench("DLA restaurado", c2, in, expect, n);
    }
    if (c2) {
        NeuronCompilation_free(c2);
    }
    if (m2) {
        NeuronModel_free(m2);
    }
    free(buf);
}

static void test_model(const char* name, NeuronModel* m, uint32_t op_count, const float* in,
                       const float* expect, size_t n, const NeuronDevice** devs,
                       const char** dev_names, uint32_t dev_count, const char* dir) {
    out("[%s] compilando con la seleccion por defecto...", name);
    NeuronCompilation* c = NULL;
    double t0 = now_ms();
    int e = NeuronCompilation_create(m, &c);
    if (!e) {
        e = NeuronCompilation_finish(c);
    }
    if (e) {
        out("  compilacion por defecto FALLO (%d)", e);
    } else {
        out("  compilacion por defecto OK en %.0f ms", now_ms() - t0);
        bench("por defecto", c, in, expect, n);
        store_restore(name, c, in, expect, n, dir);
    }
    if (c) {
        NeuronCompilation_free(c);
    }

    // Restricting the compilation to one device shows which backend really accepts the model
    for (uint32_t i = 0; i < dev_count; i++) {
        if (!devs[i]) {
            continue;
        }
        const NeuronDevice* const one[1] = {devs[i]};
        bool supported[8] = {false};
        out("[%s] dispositivo %s...", name, dev_names[i]);
        e = NeuronModel_getSupportedOperationsForDevices(m, one, 1, supported);
        if (e) {
            out("  getSupportedOperationsForDevices FALLO (%d)", e);
        } else {
            for (uint32_t op = 0; op < op_count; op++) {
                out("  operacion %u soportada: %s", op, supported[op] ? "si" : "no");
            }
        }
        c = NULL;
        t0 = now_ms();
        e = NeuronCompilation_createForDevices(m, one, 1, &c);
        if (!e) {
            e = NeuronCompilation_finish(c);
        }
        if (e) {
            out("  compilacion FALLO (%d)", e);
        } else {
            out("  compilacion OK en %.0f ms", now_ms() - t0);
            bench(dev_names[i], c, in, expect, n);
        }
        if (c) {
            NeuronCompilation_free(c);
        }
    }
}

static void run(const char* dir) {
    g_lib = dlopen("libneuronusdk_adapter.mtk.so", RTLD_NOW);
    if (!g_lib) {
        out("dlopen por nombre FALLO: %s", dlerror());
        g_lib = dlopen("/system_ext/lib64/libneuronusdk_adapter.mtk.so", RTLD_NOW);
        if (!g_lib) {
            out("dlopen por ruta FALLO: %s", dlerror());
            return;
        }
    }
    out("dlopen OK");
    if (load_symbols()) {
        return;
    }

    NeuronRuntimeVersion version = {0, 0, 0};
    int e = Neuron_getVersion(&version);
    out("Neuron_getVersion: %d -> %u.%u.%u", e, version.major, version.minor, version.patch);
    uint32_t l1_kb = 0;
    e = Neuron_getL1MemorySizeKb(&l1_kb);
    out("Neuron_getL1MemorySizeKb: %d -> %u KB", e, l1_kb);

    const NeuronDevice* devs[MAX_DEVS] = {NULL};
    const char* dev_names[MAX_DEVS] = {NULL};
    uint32_t dev_count = 0;
    e = Neuron_getDeviceCount(&dev_count);
    out("Neuron_getDeviceCount: %d -> %u", e, dev_count);
    if (e) {
        dev_count = 0;
    }
    if (dev_count > MAX_DEVS) {
        dev_count = MAX_DEVS;
    }
    for (uint32_t i = 0; i < dev_count; i++) {
        NeuronDevice* dev = NULL;
        dev_names[i] = "?";
        e = Neuron_getDevice(i, &dev);
        if (!e && dev) {
            NeuronDevice_getName(dev, &dev_names[i]);
        }
        devs[i] = dev;
        out("  dispositivo %u: %s (%d)", i, dev_names[i], e);
    }

    static float in_conv[CONV_N];
    static float in_fc[FC_N];
    static float expect_fc[FC_N];
    for (int i = 0; i < CONV_C; i++) {
        g_conv_filter[i * CONV_C + i] = 1.0f;
    }
    for (int i = 0; i < FC_N; i++) {
        g_fc_weights[i * FC_N + i] = 1.0f;
    }
    for (int i = 0; i < CONV_N; i++) {
        in_conv[i] = sinf(i * 0.37f);
    }
    float sum = 0.0f;
    for (int i = 0; i < FC_N; i++) {
        in_fc[i] = sinf(i * 0.37f);
        expect_fc[i] = expf(in_fc[i]);
        sum += expect_fc[i];
    }
    for (int i = 0; i < FC_N; i++) {
        expect_fc[i] /= sum;
    }

    out("[conv1x1] construyendo modelo...");
    NeuronModel* m = build_conv();
    if (m) {
        test_model("conv1x1", m, 1, in_conv, in_conv, CONV_N, devs, dev_names, dev_count, dir);
        NeuronModel_free(m);
    }
    out("[fc_softmax] construyendo modelo...");
    m = build_fc_softmax();
    if (m) {
        test_model("fc_softmax", m, 2, in_fc, expect_fc, FC_N, devs, dev_names, dev_count, dir);
        NeuronModel_free(m);
    }
}

static void probe(const char* dir) {
    char path[600];
    snprintf(path, sizeof path, "%s/npu_probe.txt", dir);
    g_file = fopen(path, "w");
    out("npu_probe: resultados en %s", g_file ? path : "(solo logcat)");
    run(dir);
    out("FIN");
    if (g_file) {
        fclose(g_file);
        g_file = NULL;
    }
}

#ifdef PROBE_EXE

int main(int argc, char** argv) {
    probe(argc > 1 ? argv[1] : "/data/local/tmp");
    return 0;
}

#else

#include <android/native_activity.h>
#include <pthread.h>

static ANativeActivity* g_activity;
static char g_dir[512];

static void* probe_thread(void* arg) {
    (void)arg;
    probe(g_dir);
    ANativeActivity_finish(g_activity);
    return NULL;
}

__attribute__((visibility("default")))
void ANativeActivity_onCreate(ANativeActivity* activity, void* saved_state, size_t saved_size) {
    (void)saved_state;
    (void)saved_size;
    const char* dir =
        activity->externalDataPath ? activity->externalDataPath : activity->internalDataPath;
    g_activity = activity;
    snprintf(g_dir, sizeof g_dir, "%s", dir);
    pthread_t thread;
    pthread_create(&thread, NULL, probe_thread, NULL);
    pthread_detach(thread);
}

#endif
