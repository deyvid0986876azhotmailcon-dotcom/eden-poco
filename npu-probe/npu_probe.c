// Probes NNAPI (libneuralnetworks.so) on the device: runtime feature level, devices, the MediaTek
// accelerators (mtk-neuron_shim, mtk-mdla_shim), compilation, execution and compilation caching.
// Two tiny models come first, then operations at the sizes a language model uses.
// Built twice from this file: as a shell executable (-DPROBE_EXE) and as a NativeActivity library.

#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <math.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>

#define TAG "NPUPROBE"
#define MAX_DEVS 16
#define MAX_OPS 8
#define CACHE_TOKEN_SIZE 32

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
    ANEURALNETWORKS_FLOAT32 = 0,
    ANEURALNETWORKS_INT32 = 1,
    ANEURALNETWORKS_TENSOR_FLOAT32 = 3,
    ANEURALNETWORKS_TENSOR_INT32 = 4,
    ANEURALNETWORKS_TENSOR_QUANT8_ASYMM = 5,
    ANEURALNETWORKS_BOOL = 6
};
enum {
    ANEURALNETWORKS_ADD = 0,
    ANEURALNETWORKS_CONCATENATION = 2,
    ANEURALNETWORKS_CONV_2D = 3,
    ANEURALNETWORKS_FULLY_CONNECTED = 9,
    ANEURALNETWORKS_L2_NORMALIZATION = 11,
    ANEURALNETWORKS_LOGISTIC = 14,
    ANEURALNETWORKS_MUL = 18,
    ANEURALNETWORKS_RESHAPE = 22,
    ANEURALNETWORKS_SOFTMAX = 25,
    ANEURALNETWORKS_TANH = 28,
    ANEURALNETWORKS_DIV = 30,
    ANEURALNETWORKS_MEAN = 31,
    ANEURALNETWORKS_SUB = 36,
    ANEURALNETWORKS_TRANSPOSE = 37,
    ANEURALNETWORKS_GATHER = 51,
    ANEURALNETWORKS_POW = 70,
    ANEURALNETWORKS_RSQRT = 83,
    ANEURALNETWORKS_SQRT = 88,
    ANEURALNETWORKS_BATCH_MATMUL = 102
};
enum { ANEURALNETWORKS_DEVICE_CPU = 2 };
enum { ANEURALNETWORKS_DURATION_ON_HARDWARE = 0, ANEURALNETWORKS_DURATION_IN_DRIVER = 1 };

static int (*ANeuralNetworks_getDeviceCount)(uint32_t*);
static int (*ANeuralNetworks_getDevice)(uint32_t, ANeuralNetworksDevice**);
static int (*ANeuralNetworksDevice_getName)(const ANeuralNetworksDevice*, const char**);
static int (*ANeuralNetworksDevice_getType)(const ANeuralNetworksDevice*, int32_t*);
static int (*ANeuralNetworksDevice_getVersion)(const ANeuralNetworksDevice*, const char**);
static int (*ANeuralNetworksDevice_getFeatureLevel)(const ANeuralNetworksDevice*, int64_t*);
static int (*ANeuralNetworksModel_create)(ANeuralNetworksModel**);
static void (*ANeuralNetworksModel_free)(ANeuralNetworksModel*);
static int (*ANeuralNetworksModel_addOperand)(ANeuralNetworksModel*,
                                              const ANeuralNetworksOperandType*);
static int (*ANeuralNetworksModel_setOperandValue)(ANeuralNetworksModel*, int32_t, const void*,
                                                   size_t);
static int (*ANeuralNetworksModel_addOperation)(ANeuralNetworksModel*, int32_t, uint32_t,
                                                const uint32_t*, uint32_t, const uint32_t*);
static int (*ANeuralNetworksModel_identifyInputsAndOutputs)(ANeuralNetworksModel*, uint32_t,
                                                            const uint32_t*, uint32_t,
                                                            const uint32_t*);
static int (*ANeuralNetworksModel_relaxComputationFloat32toFloat16)(ANeuralNetworksModel*, bool);
static int (*ANeuralNetworksModel_finish)(ANeuralNetworksModel*);
static int (*ANeuralNetworksModel_getSupportedOperationsForDevices)(
    const ANeuralNetworksModel*, const ANeuralNetworksDevice* const*, uint32_t, bool*);
static int (*ANeuralNetworksCompilation_create)(ANeuralNetworksModel*,
                                                ANeuralNetworksCompilation**);
static int (*ANeuralNetworksCompilation_createForDevices)(ANeuralNetworksModel*,
                                                          const ANeuralNetworksDevice* const*,
                                                          uint32_t, ANeuralNetworksCompilation**);
static void (*ANeuralNetworksCompilation_free)(ANeuralNetworksCompilation*);
static int (*ANeuralNetworksCompilation_setCaching)(ANeuralNetworksCompilation*, const char*,
                                                    const uint8_t*);
static int (*ANeuralNetworksCompilation_finish)(ANeuralNetworksCompilation*);
static int (*ANeuralNetworksExecution_create)(ANeuralNetworksCompilation*,
                                              ANeuralNetworksExecution**);
static void (*ANeuralNetworksExecution_free)(ANeuralNetworksExecution*);
static int (*ANeuralNetworksExecution_setInput)(ANeuralNetworksExecution*, int32_t,
                                                const ANeuralNetworksOperandType*, const void*,
                                                size_t);
static int (*ANeuralNetworksExecution_setOutput)(ANeuralNetworksExecution*, int32_t,
                                                 const ANeuralNetworksOperandType*, void*, size_t);
static int (*ANeuralNetworksExecution_setMeasureTiming)(ANeuralNetworksExecution*, bool);
static int (*ANeuralNetworksExecution_getDuration)(const ANeuralNetworksExecution*, int32_t,
                                                   uint64_t*);
static int (*ANeuralNetworksExecution_compute)(ANeuralNetworksExecution*);

static void* g_lib;
static FILE* g_file;
static const ANeuralNetworksDevice* g_devs[MAX_DEVS];
static const char* g_dev_names[MAX_DEVS];
static int32_t g_dev_types[MAX_DEVS];
static uint32_t g_dev_count;
static const char* g_cache_dir;

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
    LOAD(ANeuralNetworks_getDeviceCount)
    LOAD(ANeuralNetworks_getDevice)
    LOAD(ANeuralNetworksDevice_getName)
    LOAD(ANeuralNetworksDevice_getType)
    LOAD(ANeuralNetworksDevice_getVersion)
    LOAD(ANeuralNetworksDevice_getFeatureLevel)
    LOAD(ANeuralNetworksModel_create)
    LOAD(ANeuralNetworksModel_free)
    LOAD(ANeuralNetworksModel_addOperand)
    LOAD(ANeuralNetworksModel_setOperandValue)
    LOAD(ANeuralNetworksModel_addOperation)
    LOAD(ANeuralNetworksModel_identifyInputsAndOutputs)
    LOAD(ANeuralNetworksModel_relaxComputationFloat32toFloat16)
    LOAD(ANeuralNetworksModel_finish)
    LOAD(ANeuralNetworksModel_getSupportedOperationsForDevices)
    LOAD(ANeuralNetworksCompilation_create)
    LOAD(ANeuralNetworksCompilation_createForDevices)
    LOAD(ANeuralNetworksCompilation_free)
    LOAD(ANeuralNetworksCompilation_setCaching)
    LOAD(ANeuralNetworksCompilation_finish)
    LOAD(ANeuralNetworksExecution_create)
    LOAD(ANeuralNetworksExecution_free)
    LOAD(ANeuralNetworksExecution_setInput)
    LOAD(ANeuralNetworksExecution_setOutput)
    LOAD(ANeuralNetworksExecution_setMeasureTiming)
    LOAD(ANeuralNetworksExecution_getDuration)
    LOAD(ANeuralNetworksExecution_compute)
#undef LOAD
    return missing;
}

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

static const char* type_name(int32_t type) {
    static const char* const names[] = {"desconocido", "otro", "cpu", "gpu", "acelerador"};
    return type >= 0 && type < 5 ? names[type] : "?";
}

static bool is_mtk_target(const char* name) {
    return !strncmp(name, "mtk-neuron", 10) || !strncmp(name, "mtk-mdla", 8);
}

static void dir_usage(const char* dir, unsigned* files, unsigned long long* bytes) {
    *files = 0;
    *bytes = 0;
    DIR* d = opendir(dir);
    if (!d) {
        return;
    }
    struct dirent* entry;
    while ((entry = readdir(d))) {
        char path[900];
        struct stat st;
        snprintf(path, sizeof path, "%s/%s", dir, entry->d_name);
        if (!stat(path, &st) && S_ISREG(st.st_mode)) {
            (*files)++;
            *bytes += (unsigned long long)st.st_size;
        }
    }
    closedir(d);
}

#define CONV_C 16
#define CONV_HW 8
#define CONV_N (CONV_HW * CONV_HW * CONV_C)
#define FC_N 64

// Constant operands this large are referenced, not copied, so they must outlive the models
static float g_conv_filter[CONV_C * CONV_C];
static float g_conv_bias[CONV_C];
static float g_fc_weights[FC_N * FC_N];
static float g_fc_bias[FC_N];
static const int32_t g_one = 1;
static const int32_t g_zero = 0;
static const float g_beta = 1.0f;

static int add_tensor(ANeuralNetworksModel* m, const uint32_t* dims, uint32_t rank) {
    ANeuralNetworksOperandType t = {ANEURALNETWORKS_TENSOR_FLOAT32, rank, dims, 0.0f, 0};
    return ANeuralNetworksModel_addOperand(m, &t);
}

static int add_scalar(ANeuralNetworksModel* m, int32_t type) {
    ANeuralNetworksOperandType t = {type, 0, NULL, 0.0f, 0};
    return ANeuralNetworksModel_addOperand(m, &t);
}

static ANeuralNetworksModel* finish_model(ANeuralNetworksModel* m, int e) {
    if (!e) {
        e = ANeuralNetworksModel_relaxComputationFloat32toFloat16(m, true);
    }
    if (!e) {
        e = ANeuralNetworksModel_finish(m);
    }
    if (e) {
        out("  construccion del modelo FALLO (%d)", e);
        ANeuralNetworksModel_free(m);
        return NULL;
    }
    return m;
}

// 1x1 convolution with identity weights: output must equal input
static ANeuralNetworksModel* build_conv(void) {
    static const uint32_t in_dims[] = {1, CONV_HW, CONV_HW, CONV_C};
    static const uint32_t filter_dims[] = {CONV_C, 1, 1, CONV_C};
    static const uint32_t bias_dims[] = {CONV_C};
    static const uint32_t op_in[] = {0, 1, 2, 3, 4, 5, 6};
    static const uint32_t op_out[] = {7};
    static const uint32_t model_in[] = {0};
    ANeuralNetworksModel* m = NULL;
    int e = ANeuralNetworksModel_create(&m);
    if (e || !m) {
        out("  ANeuralNetworksModel_create FALLO (%d)", e);
        return NULL;
    }
    e |= add_tensor(m, in_dims, 4);
    e |= add_tensor(m, filter_dims, 4);
    e |= add_tensor(m, bias_dims, 1);
    e |= add_scalar(m, ANEURALNETWORKS_INT32); // padding scheme
    e |= add_scalar(m, ANEURALNETWORKS_INT32); // stride width
    e |= add_scalar(m, ANEURALNETWORKS_INT32); // stride height
    e |= add_scalar(m, ANEURALNETWORKS_INT32); // fused activation
    e |= add_tensor(m, in_dims, 4);
    e |= ANeuralNetworksModel_setOperandValue(m, 1, g_conv_filter, sizeof g_conv_filter);
    e |= ANeuralNetworksModel_setOperandValue(m, 2, g_conv_bias, sizeof g_conv_bias);
    e |= ANeuralNetworksModel_setOperandValue(m, 3, &g_one, sizeof g_one); // SAME
    e |= ANeuralNetworksModel_setOperandValue(m, 4, &g_one, sizeof g_one);
    e |= ANeuralNetworksModel_setOperandValue(m, 5, &g_one, sizeof g_one);
    e |= ANeuralNetworksModel_setOperandValue(m, 6, &g_zero, sizeof g_zero);
    e |= ANeuralNetworksModel_addOperation(m, ANEURALNETWORKS_CONV_2D, 7, op_in, 1, op_out);
    e |= ANeuralNetworksModel_identifyInputsAndOutputs(m, 1, model_in, 1, op_out);
    return finish_model(m, e);
}

// Fully connected with identity weights followed by softmax: output must equal softmax(input)
static ANeuralNetworksModel* build_fc_softmax(void) {
    static const uint32_t io_dims[] = {1, FC_N};
    static const uint32_t weight_dims[] = {FC_N, FC_N};
    static const uint32_t bias_dims[] = {FC_N};
    static const uint32_t fc_in[] = {0, 1, 2, 3};
    static const uint32_t fc_out[] = {4};
    static const uint32_t sm_in[] = {4, 5};
    static const uint32_t sm_out[] = {6};
    static const uint32_t model_in[] = {0};
    ANeuralNetworksModel* m = NULL;
    int e = ANeuralNetworksModel_create(&m);
    if (e || !m) {
        out("  ANeuralNetworksModel_create FALLO (%d)", e);
        return NULL;
    }
    e |= add_tensor(m, io_dims, 2);
    e |= add_tensor(m, weight_dims, 2);
    e |= add_tensor(m, bias_dims, 1);
    e |= add_scalar(m, ANEURALNETWORKS_INT32); // fused activation
    e |= add_tensor(m, io_dims, 2);
    e |= add_scalar(m, ANEURALNETWORKS_FLOAT32); // beta
    e |= add_tensor(m, io_dims, 2);
    e |= ANeuralNetworksModel_setOperandValue(m, 1, g_fc_weights, sizeof g_fc_weights);
    e |= ANeuralNetworksModel_setOperandValue(m, 2, g_fc_bias, sizeof g_fc_bias);
    e |= ANeuralNetworksModel_setOperandValue(m, 3, &g_zero, sizeof g_zero);
    e |= ANeuralNetworksModel_setOperandValue(m, 5, &g_beta, sizeof g_beta);
    e |= ANeuralNetworksModel_addOperation(m, ANEURALNETWORKS_FULLY_CONNECTED, 4, fc_in, 1, fc_out);
    e |= ANeuralNetworksModel_addOperation(m, ANEURALNETWORKS_SOFTMAX, 2, sm_in, 1, sm_out);
    e |= ANeuralNetworksModel_identifyInputsAndOutputs(m, 1, model_in, 1, sm_out);
    return finish_model(m, e);
}

// With dev == NULL NNAPI picks the devices itself and may fall back to its CPU path silently
static int compile(ANeuralNetworksModel* m, const ANeuralNetworksDevice* dev,
                   const char* cache_dir, const uint8_t* token, ANeuralNetworksCompilation** c) {
    const ANeuralNetworksDevice* const one[1] = {dev};
    int e = dev ? ANeuralNetworksCompilation_createForDevices(m, one, 1, c)
                : ANeuralNetworksCompilation_create(m, c);
    if (!e && token) {
        e = ANeuralNetworksCompilation_setCaching(*c, cache_dir, token);
    }
    if (!e) {
        e = ANeuralNetworksCompilation_finish(*c);
    }
    return e;
}

typedef struct {
    const char* name;
    ANeuralNetworksModel* model;
    uint32_t op_count;
    const char* const* op_names;
    uint32_t in_count;
    const void* in[2];
    size_t in_bytes[2];
    size_t out_count;
    // A quantized output holds uint8 values; out_scale stays 0 for a float output
    float out_scale;
    int32_t out_zero;
    // Real values the output must match. Shorter than the output when the expected rows repeat
    const float* expect;
    size_t expect_count;
    float tol;
    bool with_default;
    bool with_cache;
    bool skip_cpu;
} Case;

static size_t out_bytes(const Case* tc) {
    return tc->out_count * (tc->out_scale != 0.0f ? 1 : sizeof(float));
}

// driver_ns, when given, receives the time on hardware and in the driver as the driver reports them
static int execute(ANeuralNetworksCompilation* c, const Case* tc, void* got, uint64_t* driver_ns) {
    ANeuralNetworksExecution* x = NULL;
    int e = ANeuralNetworksExecution_create(c, &x);
    if (e || !x) {
        return e ? e : -1;
    }
    for (uint32_t i = 0; i < tc->in_count && !e; i++) {
        e = ANeuralNetworksExecution_setInput(x, (int32_t)i, NULL, tc->in[i], tc->in_bytes[i]);
    }
    if (!e) {
        e = ANeuralNetworksExecution_setOutput(x, 0, NULL, got, out_bytes(tc));
    }
    if (!e && driver_ns) {
        e = ANeuralNetworksExecution_setMeasureTiming(x, true);
    }
    if (!e) {
        e = ANeuralNetworksExecution_compute(x);
    }
    if (!e && driver_ns) {
        driver_ns[0] = UINT64_MAX;
        driver_ns[1] = UINT64_MAX;
        ANeuralNetworksExecution_getDuration(x, ANEURALNETWORKS_DURATION_ON_HARDWARE,
                                             &driver_ns[0]);
        ANeuralNetworksExecution_getDuration(x, ANEURALNETWORKS_DURATION_IN_DRIVER, &driver_ns[1]);
    }
    ANeuralNetworksExecution_free(x);
    return e;
}

// A driver that does not report its timing leaves UINT64_MAX
static void add_driver_ms(const uint64_t* ns, double* sum_ms, int* count) {
    if (ns[0] != UINT64_MAX && ns[1] != UINT64_MAX) {
        sum_ms[0] += ns[0] / 1e6;
        sum_ms[1] += ns[1] / 1e6;
        (*count)++;
    }
}

// NNAPI only lets a compilation made for a single device ask the driver for its timing
static void bench(const char* label, ANeuralNetworksCompilation* c, const Case* tc,
                  bool driver_time) {
    enum { RUNS = 20, BUDGET_MS = 2000 };
    uint64_t ns[2] = {UINT64_MAX, UINT64_MAX};
    double driver_ms[2] = {0.0, 0.0};
    int driver_runs = 0;
    void* got = calloc(1, out_bytes(tc));
    double t0 = now_ms();
    int e = execute(c, tc, got, driver_time ? ns : NULL);
    const double first = now_ms() - t0;
    if (e) {
        out("  %s: ejecucion FALLO (%d)", label, e);
        free(got);
        return;
    }
    // Counted with a negated comparison so NaN outputs are reported as wrong
    size_t bad = 0;
    float max_err = 0.0f;
    for (size_t i = 0; i < tc->out_count; i++) {
        const float v = tc->out_scale != 0.0f
                            ? (float)(((const uint8_t*)got)[i] - tc->out_zero) * tc->out_scale
                            : ((const float*)got)[i];
        const float d = fabsf(v - tc->expect[i % tc->expect_count]);
        if (!(d < tc->tol)) {
            bad++;
        }
        if (d > max_err) {
            max_err = d;
        }
    }
    // A case that takes seconds is not repeated, so the whole probe stays within a few minutes
    char timing[96];
    if (first < BUDGET_MS) {
        int runs = 0;
        t0 = now_ms();
        while (runs < RUNS && !e && now_ms() - t0 < BUDGET_MS) {
            e = execute(c, tc, got, driver_time ? ns : NULL);
            runs++;
            if (!e && driver_time) {
                add_driver_ms(ns, driver_ms, &driver_runs);
            }
        }
        snprintf(timing, sizeof timing, "primera %.3f ms, media %.3f ms en %d repeticiones", first,
                 (now_ms() - t0) / runs, runs);
    } else {
        snprintf(timing, sizeof timing, "una sola ejecucion de %.0f ms", first);
        if (driver_time) {
            add_driver_ms(ns, driver_ms, &driver_runs);
        }
    }
    if (e) {
        out("  %s: ejecucion repetida FALLO (%d)", label, e);
    } else {
        out("  %s: ejecucion OK, valores incorrectos %zu/%zu, error max %.5f, %s", label, bad,
            tc->out_count, max_err, timing);
        if (driver_runs) {
            out("  %s: tiempo segun el driver: en hardware %.3f ms, en driver %.3f ms", label,
                driver_ms[0] / driver_runs, driver_ms[1] / driver_runs);
        } else if (driver_time) {
            out("  %s: tiempo segun el driver: sin dato", label);
        }
    }
    free(got);
}

// NNAPI's counterpart of storing and restoring a DLA: with caching on, the driver may save its
// compiled blob in the cache directory and a second compilation with the same token can load it
static void test_caching(const Case* tc, const ANeuralNetworksDevice* dev) {
    // The clock keeps the token new on every launch, so the first pass never finds an old entry
    uint8_t token[CACHE_TOKEN_SIZE] = {0};
    const int64_t stamp = (int64_t)(now_ms() * 1000.0);
    memcpy(token, &stamp, sizeof stamp);
    snprintf((char*)token + sizeof stamp, sizeof token - sizeof stamp, "%s", tc->name);

    unsigned files_before = 0;
    unsigned files_after = 0;
    unsigned long long bytes_before = 0;
    unsigned long long bytes_after = 0;
    dir_usage(g_cache_dir, &files_before, &bytes_before);
    for (int pass = 1; pass <= 2; pass++) {
        ANeuralNetworksCompilation* c = NULL;
        const double t0 = now_ms();
        const int e = compile(tc->model, dev, g_cache_dir, token, &c);
        const double took = now_ms() - t0;
        if (e) {
            out("  con cache, compilacion %d FALLO (%d)", pass, e);
        } else if (pass == 1) {
            dir_usage(g_cache_dir, &files_after, &bytes_after);
            out("  con cache, compilacion 1 OK en %.0f ms, archivos nuevos: %u (%llu bytes)", took,
                files_after - files_before, bytes_after - bytes_before);
        } else {
            out("  con cache, compilacion 2 OK en %.0f ms", took);
            bench("desde cache", c, tc, true);
        }
        if (c) {
            ANeuralNetworksCompilation_free(c);
        }
        if (e) {
            return;
        }
    }
}

static void test_case(const Case* tc) {
    ANeuralNetworksCompilation* c = NULL;
    double t0;
    int e;
    if (tc->with_default) {
        out("[%s] compilando con la seleccion por defecto...", tc->name);
        t0 = now_ms();
        e = compile(tc->model, NULL, NULL, NULL, &c);
        if (e) {
            out("  compilacion por defecto FALLO (%d)", e);
        } else {
            out("  compilacion por defecto OK en %.0f ms", now_ms() - t0);
            bench("por defecto", c, tc, false);
        }
        if (c) {
            ANeuralNetworksCompilation_free(c);
        }
    }

    // Restricting the compilation to one device shows which backend really accepts the model
    for (uint32_t i = 0; i < g_dev_count; i++) {
        if (!g_devs[i]) {
            continue;
        }
        const bool cpu = g_dev_types[i] == ANEURALNETWORKS_DEVICE_CPU;
        if (cpu && tc->skip_cpu) {
            out("[%s] dispositivo %s: omitido, la CPU de referencia tardaria minutos", tc->name,
                g_dev_names[i]);
            continue;
        }
        const ANeuralNetworksDevice* const one[1] = {g_devs[i]};
        bool supported[MAX_OPS] = {false};
        out("[%s] dispositivo %s...", tc->name, g_dev_names[i]);
        e = ANeuralNetworksModel_getSupportedOperationsForDevices(tc->model, one, 1, supported);
        if (e) {
            out("  getSupportedOperationsForDevices FALLO (%d)", e);
        } else {
            for (uint32_t op = 0; op < tc->op_count; op++) {
                out("  operacion %u (%s) soportada: %s", op, tc->op_names[op],
                    supported[op] ? "si" : "no");
            }
        }
        c = NULL;
        t0 = now_ms();
        e = compile(tc->model, g_devs[i], NULL, NULL, &c);
        if (e) {
            out("  compilacion FALLO (%d)", e);
        } else {
            out("  compilacion OK en %.0f ms", now_ms() - t0);
            bench(g_dev_names[i], c, tc, !cpu);
        }
        if (c) {
            ANeuralNetworksCompilation_free(c);
        }
        if (!e && tc->with_cache && is_mtk_target(g_dev_names[i])) {
            test_caching(tc, g_devs[i]);
        }
    }
}

// From here on, operations at the sizes of a language model: a hidden size of LLM_D and LLM_T
// tokens at once. Inputs repeat every PERIOD rows, so a whole output is checked against PERIOD
// expected rows computed here.
#define LLM_D 2048
#define LLM_T 64
#define PERIOD 4

typedef struct {
    ANeuralNetworksModel* m;
    uint32_t next;
    int e;
} Builder;

static bool model_begin(Builder* b) {
    b->m = NULL;
    b->next = 0;
    b->e = ANeuralNetworksModel_create(&b->m);
    if (b->e || !b->m) {
        out("  ANeuralNetworksModel_create FALLO (%d)", b->e);
        return false;
    }
    return true;
}

static uint32_t operand(Builder* b, int32_t type, uint32_t rank, const uint32_t* dims,
                        float scale, int32_t zero) {
    ANeuralNetworksOperandType t = {type, rank, dims, scale, zero};
    b->e |= ANeuralNetworksModel_addOperand(b->m, &t);
    return b->next++;
}

static uint32_t tensor(Builder* b, uint32_t rank, const uint32_t* dims) {
    return operand(b, ANEURALNETWORKS_TENSOR_FLOAT32, rank, dims, 0.0f, 0);
}

// NNAPI copies values of up to 128 bytes; larger ones must outlive the model
static uint32_t with_value(Builder* b, uint32_t index, const void* data, size_t bytes) {
    b->e |= ANeuralNetworksModel_setOperandValue(b->m, (int32_t)index, data, bytes);
    return index;
}

static uint32_t int_scalar(Builder* b, int32_t value) {
    return with_value(b, operand(b, ANEURALNETWORKS_INT32, 0, NULL, 0.0f, 0), &value,
                      sizeof value);
}

static uint32_t float_scalar(Builder* b, float value) {
    return with_value(b, operand(b, ANEURALNETWORKS_FLOAT32, 0, NULL, 0.0f, 0), &value,
                      sizeof value);
}

static uint32_t bool_scalar(Builder* b, bool value) {
    const uint8_t byte = value;
    return with_value(b, operand(b, ANEURALNETWORKS_BOOL, 0, NULL, 0.0f, 0), &byte, sizeof byte);
}

static uint32_t int_vector(Builder* b, const int32_t* values, uint32_t count) {
    return with_value(b, operand(b, ANEURALNETWORKS_TENSOR_INT32, 1, &count, 0.0f, 0), values,
                      count * sizeof(int32_t));
}

static uint32_t operation(Builder* b, int32_t type, uint32_t in_count, const uint32_t* in,
                          uint32_t result) {
    const int e = ANeuralNetworksModel_addOperation(b->m, type, in_count, in, 1, &result);
    if (e) {
        out("  addOperation %d FALLO (%d)", type, e);
    }
    b->e |= e;
    return result;
}

static ANeuralNetworksModel* model_end(Builder* b, uint32_t in_count, const uint32_t* in,
                                       uint32_t result) {
    b->e |= ANeuralNetworksModel_identifyInputsAndOutputs(b->m, in_count, in, 1, &result);
    return finish_model(b->m, b->e);
}

static uint32_t g_seed = 1;

// 24 pseudo-random bits, the same sequence on every run so that runs can be compared
static uint32_t rnd_bits(void) {
    g_seed = g_seed * 1664525u + 1013904223u;
    return g_seed >> 8;
}

// Uniform in [-1, 1)
static float rnd(void) {
    return rnd_bits() / 8388608.0f - 1.0f;
}

static void fill(float* p, size_t count, float scale) {
    for (size_t i = 0; i < count; i++) {
        p[i] = rnd() * scale;
    }
}

static void* alloc(size_t bytes) {
    void* p = malloc(bytes);
    if (!p) {
        out("sin memoria para %zu bytes", bytes);
        exit(1);
    }
    return p;
}

// One linear layer of LLM_D inputs and outputs applied to several tokens, either as a fully
// connected layer over a batch or as the 1x1 convolution NPU toolchains usually turn it into.
// scales (input, weights, output) selects 8-bit quantized tensors; NULL means float.
static ANeuralNetworksModel* build_linear(uint32_t tokens, bool as_conv, const void* w,
                                          const void* bias, const float* scales) {
    const uint32_t fc_io[] = {tokens, LLM_D};
    const uint32_t fc_w[] = {LLM_D, LLM_D};
    const uint32_t conv_io[] = {1, 1, tokens, LLM_D};
    const uint32_t conv_w[] = {LLM_D, 1, 1, LLM_D};
    const uint32_t bias_dims[] = {LLM_D};
    const uint32_t rank = as_conv ? 4 : 2;
    const uint32_t* io_dims = as_conv ? conv_io : fc_io;
    const int32_t type =
        scales ? ANEURALNETWORKS_TENSOR_QUANT8_ASYMM : ANEURALNETWORKS_TENSOR_FLOAT32;
    const int32_t bias_type = scales ? ANEURALNETWORKS_TENSOR_INT32 : type;
    const int32_t zero = scales ? 128 : 0;
    const float in_scale = scales ? scales[0] : 0.0f;
    const float w_scale = scales ? scales[1] : 0.0f;
    const float out_scale = scales ? scales[2] : 0.0f;
    const size_t w_bytes = (size_t)LLM_D * LLM_D * (scales ? 1 : sizeof(float));
    Builder b;
    if (!model_begin(&b)) {
        return NULL;
    }
    uint32_t args[7];
    uint32_t arg_count = 0;
    const uint32_t x = operand(&b, type, rank, io_dims, in_scale, zero);
    args[arg_count++] = x;
    args[arg_count++] = with_value(
        &b, operand(&b, type, rank, as_conv ? conv_w : fc_w, w_scale, zero), w, w_bytes);
    args[arg_count++] = with_value(
        &b, operand(&b, bias_type, 1, bias_dims, in_scale * w_scale, 0), bias, LLM_D * 4);
    if (as_conv) {
        args[arg_count++] = int_scalar(&b, 1); // SAME padding
        args[arg_count++] = int_scalar(&b, 1); // stride width
        args[arg_count++] = int_scalar(&b, 1); // stride height
    }
    args[arg_count++] = int_scalar(&b, 0); // fused activation
    const uint32_t y = operation(
        &b, as_conv ? ANEURALNETWORKS_CONV_2D : ANEURALNETWORKS_FULLY_CONNECTED, arg_count, args,
        operand(&b, type, rank, io_dims, out_scale, zero));
    return model_end(&b, 1, &x, y);
}

typedef struct {
    float* w;
    float* bias;
    float* in;
    float* expect;
} LinearData;

static LinearData linear_data(void) {
    const size_t row = LLM_D * sizeof(float);
    LinearData d;
    d.w = alloc(LLM_D * row);
    d.bias = alloc(row);
    d.in = alloc(LLM_D * row);
    d.expect = alloc(PERIOD * row);
    fill(d.w, (size_t)LLM_D * LLM_D, 1.0f / sqrtf(LLM_D));
    fill(d.bias, LLM_D, 0.1f);
    fill(d.in, PERIOD * LLM_D, 1.0f);
    for (size_t r = PERIOD; r < LLM_D; r++) {
        memcpy(d.in + r * LLM_D, d.in + (r % PERIOD) * LLM_D, row);
    }
    for (size_t r = 0; r < PERIOD; r++) {
        for (size_t u = 0; u < LLM_D; u++) {
            double acc = d.bias[u];
            for (size_t i = 0; i < LLM_D; i++) {
                acc += (double)d.in[r * LLM_D + i] * d.w[u * LLM_D + i];
            }
            d.expect[r * LLM_D + u] = (float)acc;
        }
    }
    return d;
}

static void test_linear(const LinearData* d, const char* name, uint32_t tokens, bool as_conv,
                        bool with_cache) {
    static const char* const fc_ops[] = {"FULLY_CONNECTED"};
    static const char* const conv_ops[] = {"CONV_2D"};
    out("[%s] construyendo modelo...", name);
    ANeuralNetworksModel* m = build_linear(tokens, as_conv, d->w, d->bias, NULL);
    if (!m) {
        return;
    }
    const Case tc = {
        .name = name,
        .model = m,
        .op_count = 1,
        .op_names = as_conv ? conv_ops : fc_ops,
        .in_count = 1,
        .in = {d->in},
        .in_bytes = {(size_t)tokens * LLM_D * sizeof(float)},
        .out_count = (size_t)tokens * LLM_D,
        .expect = d->expect,
        .expect_count = PERIOD * LLM_D,
        .tol = 0.05f,
        .with_cache = with_cache,
    };
    test_case(&tc);
    ANeuralNetworksModel_free(m);
}

// The same layer for LLM_T tokens with 8-bit tensors, the form NPUs are built for
static void test_linear_quant(void) {
    static const char* const ops[] = {"FULLY_CONNECTED"};
    // Inputs in [-1, 1), weights within 1/sqrt(LLM_D) of zero, outputs in [-2, 2)
    static const float scales[] = {1.0f / 128, 1.0f / (128 * 45.25f), 1.0f / 64};
    const double bias_scale = scales[0] * scales[1];
    uint8_t* w = alloc((size_t)LLM_D * LLM_D);
    int32_t* bias = alloc(LLM_D * sizeof(int32_t));
    uint8_t* in = alloc(LLM_T * LLM_D);
    float* expect = alloc(PERIOD * LLM_D * sizeof(float));
    for (size_t i = 0; i < (size_t)LLM_D * LLM_D; i++) {
        w[i] = (uint8_t)(rnd_bits() >> 16);
    }
    for (size_t i = 0; i < LLM_D; i++) {
        bias[i] = (int32_t)(rnd() * 20000.0f);
    }
    for (size_t i = 0; i < PERIOD * LLM_D; i++) {
        in[i] = (uint8_t)(rnd_bits() >> 16);
    }
    for (size_t r = PERIOD; r < LLM_T; r++) {
        memcpy(in + r * LLM_D, in + (r % PERIOD) * LLM_D, LLM_D);
    }
    for (size_t r = 0; r < PERIOD; r++) {
        for (size_t u = 0; u < LLM_D; u++) {
            int32_t acc = bias[u];
            for (size_t i = 0; i < LLM_D; i++) {
                acc += (in[r * LLM_D + i] - 128) * (w[u * LLM_D + i] - 128);
            }
            long q = lround(acc * bias_scale / scales[2]) + 128;
            q = q < 0 ? 0 : q > 255 ? 255 : q;
            expect[r * LLM_D + u] = (float)(q - 128) * scales[2];
        }
    }

    out("[fc2048_x64_q8] construyendo modelo...");
    ANeuralNetworksModel* m = build_linear(LLM_T, false, w, bias, scales);
    if (m) {
        // The drivers may round the last bit differently, so one step of difference is accepted
        const Case tc = {
            .name = "fc2048_x64_q8",
            .model = m,
            .op_count = 1,
            .op_names = ops,
            .in_count = 1,
            .in = {in},
            .in_bytes = {LLM_T * LLM_D},
            .out_count = LLM_T * LLM_D,
            .out_scale = scales[2],
            .out_zero = 128,
            .expect = expect,
            .expect_count = PERIOD * LLM_D,
            .tol = 1.5f * scales[2],
        };
        test_case(&tc);
        ANeuralNetworksModel_free(m);
    }
    free(expect);
    free(in);
    free(bias);
    free(w);
}

// Product of two tensors that are both inputs, as attention needs: [batch, rows, inner] times
// [batch, inner, cols]. rows must be a multiple of PERIOD.
static void test_matmul(const char* name, uint32_t batch, uint32_t rows, uint32_t inner,
                        uint32_t cols, bool skip_cpu) {
    static const char* const ops[] = {"BATCH_MATMUL"};
    const uint32_t a_dims[] = {batch, rows, inner};
    const uint32_t b_dims[] = {batch, inner, cols};
    const uint32_t c_dims[] = {batch, rows, cols};
    const size_t a_count = (size_t)batch * rows * inner;
    const size_t b_count = (size_t)batch * inner * cols;
    float* a = alloc(a_count * sizeof(float));
    float* bm = alloc(b_count * sizeof(float));
    float* expect = alloc(PERIOD * cols * sizeof(float));
    double* acc = alloc(cols * sizeof(double));
    fill(a, PERIOD * inner, 1.0f);
    fill(bm, (size_t)inner * cols, 1.0f / sqrtf(inner));
    // Every batch gets the same right-hand matrix, so the output repeats every PERIOD rows too
    for (size_t r = PERIOD; r < (size_t)batch * rows; r++) {
        memcpy(a + r * inner, a + (r % PERIOD) * inner, inner * sizeof(float));
    }
    for (size_t i = 1; i < batch; i++) {
        memcpy(bm + i * inner * cols, bm, (size_t)inner * cols * sizeof(float));
    }
    for (size_t r = 0; r < PERIOD; r++) {
        memset(acc, 0, cols * sizeof(double));
        for (size_t k = 0; k < inner; k++) {
            const double av = a[r * inner + k];
            for (size_t j = 0; j < cols; j++) {
                acc[j] += av * bm[k * cols + j];
            }
        }
        for (size_t j = 0; j < cols; j++) {
            expect[r * cols + j] = (float)acc[j];
        }
    }

    out("[%s] construyendo modelo...", name);
    Builder b;
    ANeuralNetworksModel* m = NULL;
    if (model_begin(&b)) {
        uint32_t args[4];
        args[0] = tensor(&b, 3, a_dims);
        args[1] = tensor(&b, 3, b_dims);
        args[2] = bool_scalar(&b, false); // adjoint of the first tensor
        args[3] = bool_scalar(&b, false); // adjoint of the second tensor
        const uint32_t y =
            operation(&b, ANEURALNETWORKS_BATCH_MATMUL, 4, args, tensor(&b, 3, c_dims));
        m = model_end(&b, 2, args, y);
    }
    if (m) {
        const Case tc = {
            .name = name,
            .model = m,
            .op_count = 1,
            .op_names = ops,
            .in_count = 2,
            .in = {a, bm},
            .in_bytes = {a_count * sizeof(float), b_count * sizeof(float)},
            .out_count = (size_t)batch * rows * cols,
            .expect = expect,
            .expect_count = PERIOD * cols,
            .tol = 0.05f,
            .skip_cpu = skip_cpu,
        };
        test_case(&tc);
        ANeuralNetworksModel_free(m);
    }
    free(acc);
    free(expect);
    free(bm);
    free(a);
}

// RMSNorm spelled out with basic operations: x * rsqrt(mean(x^2) + eps) * gain
static ANeuralNetworksModel* build_rmsnorm(const uint32_t* dims, const float* gain) {
    const uint32_t row_dims[] = {dims[0], 1};
    const uint32_t gain_dims[] = {dims[1]};
    const uint32_t one_dims[] = {1};
    const int32_t axis = 1;
    const float eps = 1e-5f;
    Builder b;
    if (!model_begin(&b)) {
        return NULL;
    }
    const uint32_t x = tensor(&b, 2, dims);
    const uint32_t act = int_scalar(&b, 0);
    const uint32_t axes = int_vector(&b, &axis, 1);
    const uint32_t keep_dims = int_scalar(&b, 1);
    const uint32_t epsilon = with_value(&b, tensor(&b, 1, one_dims), &eps, sizeof eps);
    const uint32_t g = with_value(&b, tensor(&b, 1, gain_dims), gain, dims[1] * sizeof(float));
    const uint32_t square_args[] = {x, x, act};
    const uint32_t square =
        operation(&b, ANEURALNETWORKS_MUL, 3, square_args, tensor(&b, 2, dims));
    const uint32_t mean_args[] = {square, axes, keep_dims};
    const uint32_t mean =
        operation(&b, ANEURALNETWORKS_MEAN, 3, mean_args, tensor(&b, 2, row_dims));
    const uint32_t add_args[] = {mean, epsilon, act};
    const uint32_t sum = operation(&b, ANEURALNETWORKS_ADD, 3, add_args, tensor(&b, 2, row_dims));
    const uint32_t inv = operation(&b, ANEURALNETWORKS_RSQRT, 1, &sum, tensor(&b, 2, row_dims));
    const uint32_t scale_args[] = {x, inv, act};
    const uint32_t scaled = operation(&b, ANEURALNETWORKS_MUL, 3, scale_args, tensor(&b, 2, dims));
    const uint32_t gain_args[] = {scaled, g, act};
    const uint32_t y = operation(&b, ANEURALNETWORKS_MUL, 3, gain_args, tensor(&b, 2, dims));
    return model_end(&b, 1, &x, y);
}

static void expect_rmsnorm(const float* in, float* expect, size_t cols, const float* gain) {
    double sum = 0.0;
    for (size_t i = 0; i < cols; i++) {
        sum += (double)in[i] * in[i];
    }
    const double inv = 1.0 / sqrt(sum / cols + 1e-5);
    for (size_t i = 0; i < cols; i++) {
        expect[i] = (float)(in[i] * inv * gain[i]);
    }
}

// The built-in normalization closest to RMSNorm: it differs only by a constant factor
static ANeuralNetworksModel* build_l2norm(const uint32_t* dims, const float* gain) {
    (void)gain;
    Builder b;
    if (!model_begin(&b)) {
        return NULL;
    }
    const uint32_t x = tensor(&b, 2, dims);
    const uint32_t y = operation(&b, ANEURALNETWORKS_L2_NORMALIZATION, 1, &x, tensor(&b, 2, dims));
    return model_end(&b, 1, &x, y);
}

static void expect_l2norm(const float* in, float* expect, size_t cols, const float* gain) {
    (void)gain;
    double sum = 0.0;
    for (size_t i = 0; i < cols; i++) {
        sum += (double)in[i] * in[i];
    }
    const double inv = 1.0 / sqrt(sum);
    for (size_t i = 0; i < cols; i++) {
        expect[i] = (float)(in[i] * inv);
    }
}

// SiLU, the activation of most current models: x * sigmoid(x)
static ANeuralNetworksModel* build_silu(const uint32_t* dims, const float* gain) {
    (void)gain;
    Builder b;
    if (!model_begin(&b)) {
        return NULL;
    }
    const uint32_t x = tensor(&b, 2, dims);
    const uint32_t sigmoid = operation(&b, ANEURALNETWORKS_LOGISTIC, 1, &x, tensor(&b, 2, dims));
    const uint32_t args[] = {x, sigmoid, int_scalar(&b, 0)};
    const uint32_t y = operation(&b, ANEURALNETWORKS_MUL, 3, args, tensor(&b, 2, dims));
    return model_end(&b, 1, &x, y);
}

static void expect_silu(const float* in, float* expect, size_t cols, const float* gain) {
    (void)gain;
    for (size_t i = 0; i < cols; i++) {
        expect[i] = (float)(in[i] / (1.0 + exp(-in[i])));
    }
}

static ANeuralNetworksModel* build_softmax(const uint32_t* dims, const float* gain) {
    (void)gain;
    Builder b;
    if (!model_begin(&b)) {
        return NULL;
    }
    const uint32_t x = tensor(&b, 2, dims);
    const uint32_t args[] = {x, float_scalar(&b, 1.0f)};
    const uint32_t y = operation(&b, ANEURALNETWORKS_SOFTMAX, 2, args, tensor(&b, 2, dims));
    return model_end(&b, 1, &x, y);
}

static void expect_softmax(const float* in, float* expect, size_t cols, const float* gain) {
    (void)gain;
    double sum = 0.0;
    for (size_t i = 0; i < cols; i++) {
        sum += exp(in[i]);
    }
    for (size_t i = 0; i < cols; i++) {
        expect[i] = (float)(exp(in[i]) / sum);
    }
}

typedef ANeuralNetworksModel* (*RowBuild)(const uint32_t* dims, const float* gain);
typedef void (*RowExpect)(const float* in, float* expect, size_t cols, const float* gain);

// A model with one [rows, cols] input whose output rows depend only on the same input row
static void test_rowwise(const char* name, uint32_t rows, uint32_t cols, float in_scale,
                         float tol, uint32_t op_count, const char* const* op_names,
                         RowBuild build, RowExpect expect_row) {
    const uint32_t dims[] = {rows, cols};
    const size_t count = (size_t)rows * cols;
    float* in = alloc(count * sizeof(float));
    float* expect = alloc(count * sizeof(float));
    float* gain = alloc(cols * sizeof(float));
    fill(in, count, in_scale);
    for (size_t i = 0; i < cols; i++) {
        gain[i] = 1.0f + 0.25f * rnd();
    }
    for (size_t r = 0; r < rows; r++) {
        expect_row(in + r * cols, expect + r * cols, cols, gain);
    }
    out("[%s] construyendo modelo...", name);
    ANeuralNetworksModel* m = build(dims, gain);
    if (m) {
        const Case tc = {
            .name = name,
            .model = m,
            .op_count = op_count,
            .op_names = op_names,
            .in_count = 1,
            .in = {in},
            .in_bytes = {count * sizeof(float)},
            .out_count = count,
            .expect = expect,
            .expect_count = count,
            .tol = tol,
        };
        test_case(&tc);
        ANeuralNetworksModel_free(m);
    }
    free(gain);
    free(expect);
    free(in);
}

// One operation alone on LLM-sized tensors, only to ask each device whether it takes it
static ANeuralNetworksModel* build_single(int32_t op) {
    const uint32_t dims[] = {LLM_T, LLM_D};
    const uint32_t head_dims[] = {LLM_T, 16, LLM_D / 16};
    uint32_t ins[2] = {0, 0};
    uint32_t in_count = 1;
    uint32_t y = 0;
    Builder b;
    if (!model_begin(&b)) {
        return NULL;
    }
    switch (op) {
    case ANEURALNETWORKS_SUB:
    case ANEURALNETWORKS_DIV: {
        ins[0] = tensor(&b, 2, dims);
        ins[1] = tensor(&b, 2, dims);
        in_count = 2;
        const uint32_t args[] = {ins[0], ins[1], int_scalar(&b, 0)};
        y = operation(&b, op, 3, args, tensor(&b, 2, dims));
        break;
    }
    case ANEURALNETWORKS_TANH:
    case ANEURALNETWORKS_SQRT:
        ins[0] = tensor(&b, 2, dims);
        y = operation(&b, op, 1, ins, tensor(&b, 2, dims));
        break;
    case ANEURALNETWORKS_POW: {
        const uint32_t one_dims[] = {1};
        const float two = 2.0f;
        ins[0] = tensor(&b, 2, dims);
        const uint32_t args[] = {ins[0],
                                 with_value(&b, tensor(&b, 1, one_dims), &two, sizeof two)};
        y = operation(&b, op, 2, args, tensor(&b, 2, dims));
        break;
    }
    case ANEURALNETWORKS_RESHAPE: {
        // The hidden dimension split into 16 attention heads
        const int32_t shape[] = {LLM_T, 16, LLM_D / 16};
        ins[0] = tensor(&b, 2, dims);
        const uint32_t args[] = {ins[0], int_vector(&b, shape, 3)};
        y = operation(&b, op, 2, args, tensor(&b, 3, head_dims));
        break;
    }
    case ANEURALNETWORKS_TRANSPOSE: {
        // [tokens, heads, head size] to [heads, tokens, head size]
        const uint32_t out_dims[] = {16, LLM_T, LLM_D / 16};
        const int32_t perm[] = {1, 0, 2};
        ins[0] = tensor(&b, 3, head_dims);
        const uint32_t args[] = {ins[0], int_vector(&b, perm, 3)};
        y = operation(&b, op, 2, args, tensor(&b, 3, out_dims));
        break;
    }
    case ANEURALNETWORKS_CONCATENATION: {
        // One new token appended to the previous ones, as a key-value cache does
        const uint32_t old_dims[] = {LLM_T - 1, LLM_D};
        const uint32_t new_dims[] = {1, LLM_D};
        ins[0] = tensor(&b, 2, old_dims);
        ins[1] = tensor(&b, 2, new_dims);
        in_count = 2;
        const uint32_t args[] = {ins[0], ins[1], int_scalar(&b, 0)};
        y = operation(&b, op, 3, args, tensor(&b, 2, dims));
        break;
    }
    case ANEURALNETWORKS_GATHER: {
        // Embedding lookup: LLM_T rows picked from a table
        const uint32_t table_dims[] = {4096, LLM_D};
        const uint32_t index_dims[] = {LLM_T};
        ins[0] = tensor(&b, 2, table_dims);
        ins[1] = operand(&b, ANEURALNETWORKS_TENSOR_INT32, 1, index_dims, 0.0f, 0);
        in_count = 2;
        const uint32_t args[] = {ins[0], int_scalar(&b, 0), ins[1]};
        y = operation(&b, op, 3, args, tensor(&b, 2, dims));
        break;
    }
    default:
        break;
    }
    return model_end(&b, in_count, ins, y);
}

static void survey(void) {
    static const struct {
        int32_t op;
        const char* name;
    } list[] = {
        {ANEURALNETWORKS_SUB, "SUB"},
        {ANEURALNETWORKS_DIV, "DIV"},
        {ANEURALNETWORKS_TANH, "TANH"},
        {ANEURALNETWORKS_SQRT, "SQRT"},
        {ANEURALNETWORKS_POW, "POW"},
        {ANEURALNETWORKS_RESHAPE, "RESHAPE"},
        {ANEURALNETWORKS_TRANSPOSE, "TRANSPOSE"},
        {ANEURALNETWORKS_CONCATENATION, "CONCATENATION"},
        {ANEURALNETWORKS_GATHER, "GATHER"},
    };
    out("[sondeo] una operacion por modelo, sin ejecutar; si = declarada soportada y compila");
    for (size_t k = 0; k < sizeof list / sizeof list[0]; k++) {
        ANeuralNetworksModel* m = build_single(list[k].op);
        if (!m) {
            out("  %s: NNAPI no admite el modelo", list[k].name);
            continue;
        }
        char line[512];
        size_t len = (size_t)snprintf(line, sizeof line, "  %s:", list[k].name);
        for (uint32_t i = 0; i < g_dev_count && len < sizeof line; i++) {
            if (!g_devs[i]) {
                continue;
            }
            const ANeuralNetworksDevice* const one[1] = {g_devs[i]};
            bool supported = false;
            char verdict[32] = "no";
            int e = ANeuralNetworksModel_getSupportedOperationsForDevices(m, one, 1, &supported);
            if (e) {
                snprintf(verdict, sizeof verdict, "FALLO (%d)", e);
            } else if (supported) {
                ANeuralNetworksCompilation* c = NULL;
                e = compile(m, g_devs[i], NULL, NULL, &c);
                if (c) {
                    ANeuralNetworksCompilation_free(c);
                }
                if (e) {
                    snprintf(verdict, sizeof verdict, "no compila (%d)", e);
                } else {
                    snprintf(verdict, sizeof verdict, "si");
                }
            }
            len += (size_t)snprintf(line + len, sizeof line - len, " %s %s;", g_dev_names[i],
                                    verdict);
        }
        out("%s", line);
        ANeuralNetworksModel_free(m);
    }
}

static void run_llm(void) {
    static const char* const rmsnorm_ops[] = {"MUL", "MEAN", "ADD", "RSQRT", "MUL", "MUL"};
    static const char* const l2norm_ops[] = {"L2_NORMALIZATION"};
    static const char* const silu_ops[] = {"LOGISTIC", "MUL"};
    static const char* const softmax_ops[] = {"SOFTMAX"};
    out("== operaciones al tamano de un modelo de lenguaje: dimension %d, %d tokens ==", LLM_D,
        LLM_T);
    survey();

    LinearData d = linear_data();
    test_linear(&d, "fc2048_x1", 1, false, false);
    test_linear(&d, "fc2048_x64", LLM_T, false, true);
    test_linear(&d, "conv2048_x64", LLM_T, true, false);
    test_linear_quant();
    test_rowwise("rmsnorm_64x2048", LLM_T, LLM_D, 1.0f, 0.02f, 6, rmsnorm_ops, build_rmsnorm,
                 expect_rmsnorm);
    test_rowwise("l2norm_64x2048", LLM_T, LLM_D, 1.0f, 5e-4f, 1, l2norm_ops, build_l2norm,
                 expect_l2norm);
    test_rowwise("silu_64x2048", LLM_T, LLM_D, 4.0f, 0.02f, 2, silu_ops, build_silu,
                 expect_silu);
    // The attention scores of 16 heads over 256 tokens
    test_rowwise("softmax_4096x256", 4096, 256, 4.0f, 1e-3f, 1, softmax_ops, build_softmax,
                 expect_softmax);
    test_matmul("matmul_16x256x128x256", 16, 256, 128, 256, false);

    // The heaviest cases go last, so a hang or a kill loses as little as possible: these three
    // multiply two 2048x2048 matrices
    test_linear(&d, "fc2048_x2048", LLM_D, false, false);
    test_linear(&d, "conv2048_x2048", LLM_D, true, false);
    free(d.expect);
    free(d.in);
    free(d.bias);
    free(d.w);
    test_matmul("matmul_2048x2048", 1, LLM_D, LLM_D, LLM_D, true);
}

static void run(void) {
    g_lib = dlopen("libneuralnetworks.so", RTLD_NOW);
    if (!g_lib) {
        out("dlopen por nombre FALLO: %s", dlerror());
        g_lib = dlopen("/apex/com.android.neuralnetworks/lib64/libneuralnetworks.so", RTLD_NOW);
        if (!g_lib) {
            out("dlopen por ruta FALLO: %s", dlerror());
            return;
        }
    }
    out("dlopen OK");
    if (load_symbols()) {
        return;
    }

    // Added in Android 12, so it is looked up apart from the required symbols
    int64_t (*get_runtime_level)(void) = dlsym(g_lib, "ANeuralNetworks_getRuntimeFeatureLevel");
    if (get_runtime_level) {
        out("nivel de funciones del runtime NNAPI: %lld", (long long)get_runtime_level());
    }

    uint32_t mtk_count = 0;
    int e = ANeuralNetworks_getDeviceCount(&g_dev_count);
    out("ANeuralNetworks_getDeviceCount: %d -> %u", e, g_dev_count);
    if (e) {
        g_dev_count = 0;
    }
    if (g_dev_count > MAX_DEVS) {
        g_dev_count = MAX_DEVS;
    }
    for (uint32_t i = 0; i < g_dev_count; i++) {
        ANeuralNetworksDevice* dev = NULL;
        const char* version = NULL;
        int64_t level = 0;
        g_dev_names[i] = NULL;
        g_dev_types[i] = 0;
        e = ANeuralNetworks_getDevice(i, &dev);
        if (!e && dev) {
            ANeuralNetworksDevice_getName(dev, &g_dev_names[i]);
            ANeuralNetworksDevice_getType(dev, &g_dev_types[i]);
            ANeuralNetworksDevice_getVersion(dev, &version);
            ANeuralNetworksDevice_getFeatureLevel(dev, &level);
        }
        if (!g_dev_names[i]) {
            g_dev_names[i] = "?";
        }
        g_devs[i] = dev;
        out("  dispositivo %u: %s (%d), tipo %s, version %s, nivel %lld", i, g_dev_names[i], e,
            type_name(g_dev_types[i]), version ? version : "?", (long long)level);
        if (dev && is_mtk_target(g_dev_names[i])) {
            mtk_count++;
        }
    }
    out("aceleradores MediaTek (mtk-neuron_shim, mtk-mdla_shim) encontrados: %u", mtk_count);

    static const char* const conv_ops[] = {"CONV_2D"};
    static const char* const fc_ops[] = {"FULLY_CONNECTED", "SOFTMAX"};
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
    ANeuralNetworksModel* m = build_conv();
    if (m) {
        const Case tc = {
            .name = "conv1x1",
            .model = m,
            .op_count = 1,
            .op_names = conv_ops,
            .in_count = 1,
            .in = {in_conv},
            .in_bytes = {sizeof in_conv},
            .out_count = CONV_N,
            .expect = in_conv,
            .expect_count = CONV_N,
            .tol = 2e-3f,
            .with_default = true,
            .with_cache = true,
        };
        test_case(&tc);
        ANeuralNetworksModel_free(m);
    }
    out("[fc_softmax] construyendo modelo...");
    m = build_fc_softmax();
    if (m) {
        const Case tc = {
            .name = "fc_softmax",
            .model = m,
            .op_count = 2,
            .op_names = fc_ops,
            .in_count = 1,
            .in = {in_fc},
            .in_bytes = {sizeof in_fc},
            .out_count = FC_N,
            .expect = expect_fc,
            .expect_count = FC_N,
            .tol = 2e-3f,
            .with_default = true,
            .with_cache = true,
        };
        test_case(&tc);
        ANeuralNetworksModel_free(m);
    }

    run_llm();
}

// cache_parent must be storage the process owns: NNAPI opens the cache files from this process
static void probe(const char* dir, const char* cache_parent) {
    char path[600];
    snprintf(path, sizeof path, "%s/npu_probe.txt", dir);
    g_file = fopen(path, "w");
    out("npu_probe: resultados en %s", g_file ? path : "(solo logcat)");
    snprintf(path, sizeof path, "%s/nnapi_cache", cache_parent);
    mkdir(path, 0700);
    g_cache_dir = path;
    run();
    out("FIN");
    if (g_file) {
        fclose(g_file);
        g_file = NULL;
    }
}

#ifdef PROBE_EXE

int main(int argc, char** argv) {
    const char* dir = argc > 1 ? argv[1] : "/data/local/tmp";
    probe(dir, dir);
    return 0;
}

#else

#include <android/input.h>
#include <android/looper.h>
#include <android/native_activity.h>
#include <android/window.h>
#include <pthread.h>

static ANativeActivity* g_activity;
static char g_dir[512];
static char g_private_dir[512];

static void* probe_thread(void* arg) {
    (void)arg;
    probe(g_dir, g_private_dir);
    ANativeActivity_finish(g_activity);
    return NULL;
}

// The run takes minutes: a touch left unanswered that long makes Android report the app as hung
static int drain_input(int fd, int events, void* data) {
    (void)fd;
    (void)events;
    AInputQueue* queue = data;
    AInputEvent* event = NULL;
    while (AInputQueue_getEvent(queue, &event) >= 0) {
        if (!AInputQueue_preDispatchEvent(queue, event)) {
            AInputQueue_finishEvent(queue, event, 0);
        }
    }
    return 1;
}

static void on_input_created(ANativeActivity* activity, AInputQueue* queue) {
    (void)activity;
    AInputQueue_attachLooper(queue, ALooper_forThread(), ALOOPER_POLL_CALLBACK, drain_input, queue);
}

static void on_input_destroyed(ANativeActivity* activity, AInputQueue* queue) {
    (void)activity;
    AInputQueue_detachLooper(queue);
}

__attribute__((visibility("default")))
void ANativeActivity_onCreate(ANativeActivity* activity, void* saved_state, size_t saved_size) {
    (void)saved_state;
    (void)saved_size;
    const char* dir =
        activity->externalDataPath ? activity->externalDataPath : activity->internalDataPath;
    g_activity = activity;
    snprintf(g_dir, sizeof g_dir, "%s", dir);
    snprintf(g_private_dir, sizeof g_private_dir, "%s",
             activity->internalDataPath ? activity->internalDataPath : dir);
    activity->callbacks->onInputQueueCreated = on_input_created;
    activity->callbacks->onInputQueueDestroyed = on_input_destroyed;
    ANativeActivity_setWindowFlags(activity, AWINDOW_FLAG_KEEP_SCREEN_ON, 0);
    pthread_t thread;
    pthread_create(&thread, NULL, probe_thread, NULL);
    pthread_detach(thread);
}

#endif
