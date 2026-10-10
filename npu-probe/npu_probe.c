// Probes NNAPI (libneuralnetworks.so) on the device: runtime feature level, devices, the MediaTek
// accelerators (mtk-neuron_shim, mtk-mdla_shim), compilation, execution and compilation caching.
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

enum { ANEURALNETWORKS_FLOAT32 = 0, ANEURALNETWORKS_INT32 = 1, ANEURALNETWORKS_TENSOR_FLOAT32 = 3 };
enum {
    ANEURALNETWORKS_CONV_2D = 3,
    ANEURALNETWORKS_FULLY_CONNECTED = 9,
    ANEURALNETWORKS_SOFTMAX = 25
};
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

// driver_ns, when given, receives the time on hardware and in the driver as the driver reports them
static int execute(ANeuralNetworksCompilation* c, const float* in, float* got, size_t n,
                   uint64_t* driver_ns) {
    ANeuralNetworksExecution* x = NULL;
    int e = ANeuralNetworksExecution_create(c, &x);
    if (e || !x) {
        return e ? e : -1;
    }
    e = ANeuralNetworksExecution_setInput(x, 0, NULL, in, n * sizeof(float));
    if (!e) {
        e = ANeuralNetworksExecution_setOutput(x, 0, NULL, got, n * sizeof(float));
    }
    if (!e && driver_ns) {
        e = ANeuralNetworksExecution_setMeasureTiming(x, true);
    }
    if (!e) {
        e = ANeuralNetworksExecution_compute(x);
    }
    if (!e && driver_ns) {
        ANeuralNetworksExecution_getDuration(x, ANEURALNETWORKS_DURATION_ON_HARDWARE,
                                             &driver_ns[0]);
        ANeuralNetworksExecution_getDuration(x, ANEURALNETWORKS_DURATION_IN_DRIVER, &driver_ns[1]);
    }
    ANeuralNetworksExecution_free(x);
    return e;
}

static const char* ns_text(char* buf, size_t size, uint64_t ns) {
    if (ns == UINT64_MAX) {
        return "sin dato";
    }
    snprintf(buf, size, "%.3f ms", ns / 1e6);
    return buf;
}

static void bench(const char* label, ANeuralNetworksCompilation* c, const float* in,
                  const float* expect, size_t n, bool one_device) {
    enum { RUNS = 20 };
    float* got = calloc(n, sizeof(float));
    int e = execute(c, in, got, n, NULL);
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
        e = execute(c, in, got, n, NULL);
    }
    const double avg = (now_ms() - t0) / RUNS;
    out("  %s: ejecucion OK, valores incorrectos %zu/%zu, error max %.5f, %.3f ms por ejecucion",
        label, bad, n, max_err, avg);

    // NNAPI only lets a compilation made for a single device ask the driver for its timing
    if (one_device) {
        uint64_t ns[2] = {UINT64_MAX, UINT64_MAX};
        char hw[32];
        char drv[32];
        e = execute(c, in, got, n, ns);
        if (e) {
            out("  %s: ejecucion con medicion FALLO (%d)", label, e);
        } else {
            out("  %s: tiempo segun el driver: en hardware %s, en driver %s", label,
                ns_text(hw, sizeof hw, ns[0]), ns_text(drv, sizeof drv, ns[1]));
        }
    }
    free(got);
}

// NNAPI's counterpart of storing and restoring a DLA: with caching on, the driver may save its
// compiled blob in cache_dir and a second compilation with the same token can load it back
static void test_caching(const char* name, ANeuralNetworksModel* m,
                         const ANeuralNetworksDevice* dev, const float* in, const float* expect,
                         size_t n, const char* cache_dir) {
    // The clock keeps the token new on every launch, so the first pass never finds an old entry
    uint8_t token[CACHE_TOKEN_SIZE] = {0};
    const int64_t stamp = (int64_t)(now_ms() * 1000.0);
    memcpy(token, &stamp, sizeof stamp);
    snprintf((char*)token + sizeof stamp, sizeof token - sizeof stamp, "%s", name);

    unsigned files_before = 0;
    unsigned files_after = 0;
    unsigned long long bytes_before = 0;
    unsigned long long bytes_after = 0;
    dir_usage(cache_dir, &files_before, &bytes_before);
    for (int pass = 1; pass <= 2; pass++) {
        ANeuralNetworksCompilation* c = NULL;
        const double t0 = now_ms();
        const int e = compile(m, dev, cache_dir, token, &c);
        const double took = now_ms() - t0;
        if (e) {
            out("  con cache, compilacion %d FALLO (%d)", pass, e);
        } else if (pass == 1) {
            dir_usage(cache_dir, &files_after, &bytes_after);
            out("  con cache, compilacion 1 OK en %.0f ms, archivos nuevos: %u (%llu bytes)", took,
                files_after - files_before, bytes_after - bytes_before);
        } else {
            out("  con cache, compilacion 2 OK en %.0f ms", took);
            bench("desde cache", c, in, expect, n, true);
        }
        if (c) {
            ANeuralNetworksCompilation_free(c);
        }
        if (e) {
            return;
        }
    }
}

static void test_model(const char* name, ANeuralNetworksModel* m, uint32_t op_count,
                       const float* in, const float* expect, size_t n,
                       const ANeuralNetworksDevice** devs, const char** dev_names,
                       uint32_t dev_count, const char* cache_dir) {
    out("[%s] compilando con la seleccion por defecto...", name);
    ANeuralNetworksCompilation* c = NULL;
    double t0 = now_ms();
    int e = compile(m, NULL, NULL, NULL, &c);
    if (e) {
        out("  compilacion por defecto FALLO (%d)", e);
    } else {
        out("  compilacion por defecto OK en %.0f ms", now_ms() - t0);
        bench("por defecto", c, in, expect, n, false);
    }
    if (c) {
        ANeuralNetworksCompilation_free(c);
    }

    // Restricting the compilation to one device shows which backend really accepts the model
    for (uint32_t i = 0; i < dev_count; i++) {
        if (!devs[i]) {
            continue;
        }
        const ANeuralNetworksDevice* const one[1] = {devs[i]};
        bool supported[8] = {false};
        out("[%s] dispositivo %s...", name, dev_names[i]);
        e = ANeuralNetworksModel_getSupportedOperationsForDevices(m, one, 1, supported);
        if (e) {
            out("  getSupportedOperationsForDevices FALLO (%d)", e);
        } else {
            for (uint32_t op = 0; op < op_count; op++) {
                out("  operacion %u soportada: %s", op, supported[op] ? "si" : "no");
            }
        }
        c = NULL;
        t0 = now_ms();
        e = compile(m, devs[i], NULL, NULL, &c);
        if (e) {
            out("  compilacion FALLO (%d)", e);
        } else {
            out("  compilacion OK en %.0f ms", now_ms() - t0);
            bench(dev_names[i], c, in, expect, n, true);
        }
        if (c) {
            ANeuralNetworksCompilation_free(c);
        }
        if (!e && is_mtk_target(dev_names[i])) {
            test_caching(name, m, devs[i], in, expect, n, cache_dir);
        }
    }
}

static void run(const char* cache_dir) {
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

    const ANeuralNetworksDevice* devs[MAX_DEVS] = {NULL};
    const char* dev_names[MAX_DEVS] = {NULL};
    uint32_t dev_count = 0;
    uint32_t mtk_count = 0;
    int e = ANeuralNetworks_getDeviceCount(&dev_count);
    out("ANeuralNetworks_getDeviceCount: %d -> %u", e, dev_count);
    if (e) {
        dev_count = 0;
    }
    if (dev_count > MAX_DEVS) {
        dev_count = MAX_DEVS;
    }
    for (uint32_t i = 0; i < dev_count; i++) {
        ANeuralNetworksDevice* dev = NULL;
        const char* version = NULL;
        int32_t type = 0;
        int64_t level = 0;
        e = ANeuralNetworks_getDevice(i, &dev);
        if (!e && dev) {
            ANeuralNetworksDevice_getName(dev, &dev_names[i]);
            ANeuralNetworksDevice_getType(dev, &type);
            ANeuralNetworksDevice_getVersion(dev, &version);
            ANeuralNetworksDevice_getFeatureLevel(dev, &level);
        }
        if (!dev_names[i]) {
            dev_names[i] = "?";
        }
        devs[i] = dev;
        out("  dispositivo %u: %s (%d), tipo %s, version %s, nivel %lld", i, dev_names[i], e,
            type_name(type), version ? version : "?", (long long)level);
        if (dev && is_mtk_target(dev_names[i])) {
            mtk_count++;
        }
    }
    out("aceleradores MediaTek (mtk-neuron_shim, mtk-mdla_shim) encontrados: %u", mtk_count);

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
        test_model("conv1x1", m, 1, in_conv, in_conv, CONV_N, devs, dev_names, dev_count,
                   cache_dir);
        ANeuralNetworksModel_free(m);
    }
    out("[fc_softmax] construyendo modelo...");
    m = build_fc_softmax();
    if (m) {
        test_model("fc_softmax", m, 2, in_fc, expect_fc, FC_N, devs, dev_names, dev_count,
                   cache_dir);
        ANeuralNetworksModel_free(m);
    }
}

// cache_parent must be storage the process owns: NNAPI opens the cache files from this process
static void probe(const char* dir, const char* cache_parent) {
    char path[600];
    snprintf(path, sizeof path, "%s/npu_probe.txt", dir);
    g_file = fopen(path, "w");
    out("npu_probe: resultados en %s", g_file ? path : "(solo logcat)");
    snprintf(path, sizeof path, "%s/nnapi_cache", cache_parent);
    mkdir(path, 0700);
    run(path);
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

#include <android/native_activity.h>
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
    pthread_t thread;
    pthread_create(&thread, NULL, probe_thread, NULL);
    pthread_detach(thread);
}

#endif
