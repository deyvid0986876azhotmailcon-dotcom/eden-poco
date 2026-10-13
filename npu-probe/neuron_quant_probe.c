// Asks the Neuron compiler of the phone (libneuronusdk_adapter.mtk.so, the API below NNAPI) which
// formats it takes for LLM-sized fully connected layers: float, 8 bits everywhere, and the ones
// NNAPI cannot express, 16-bit activations with 8-bit or 4-bit weights. MediaTek's own LLM flow
// uses the last one (A16W4), and reading fewer bytes of weights per token is what makes it fast.
//
// For every format: is the graph accepted, does it compile for the MDLA, how many bytes per weight
// the compiled network keeps, how long an execution takes and how far the result is from the same
// sum done here in C. One line per format, the last line is FIN.
//
// Run from adb shell:
//   ./neuron_quant_probe            every format, each attempt in its own process
//   ./neuron_quant_probe a16w4      only that one; add "sin" or "con" for one pass (without or with
//                                   compiler options)
// Environment: NQ_ROWS and NQ_COLS (default 8960 x 1536, the up projection of Qwen 2.5 1.5B),
// NQ_LAYERS (default 4 layers side by side in one graph), NQ_OPTS (compiler options for the second
// pass, "none" to skip it).
//
// Type codes and prototypes are the ones of NeuronAdapter.h, NeuroPilot 8.
#include <android/log.h>
#include <dlfcn.h>
#include <math.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define TAG "NeuronQuant"

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

enum {
    NEURON_INT32 = 1,
    NEURON_TENSOR_FLOAT32 = 3,
    NEURON_TENSOR_INT32 = 4,
    NEURON_TENSOR_QUANT8_ASYMM = 5,
    NEURON_TENSOR_QUANT16_SYMM = 7,
    NEURON_TENSOR_QUANT8_SYMM = 13,
    NEURON_TENSOR_QUANT8_ASYMM_SIGNED = 14,
    NEURON_EXT_TENSOR_QUANT4_SYMM = 9005,
};
enum { NEURON_FULLY_CONNECTED = 9 };

static int (*Neuron_getVersion)(NeuronRuntimeVersion*);
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
static int (*NeuronCompilation_create)(NeuronModel*, NeuronCompilation**);
static int (*NeuronCompilation_createForDevices)(NeuronModel*, const NeuronDevice* const*,
                                                 uint32_t, NeuronCompilation**);
static void (*NeuronCompilation_free)(NeuronCompilation*);
static int (*NeuronCompilation_finish)(NeuronCompilation*);
static int (*NeuronCompilation_getCompiledNetworkSize)(NeuronCompilation*, size_t*);
static int (*NeuronCompilation_setOptimizationString)(NeuronCompilation*, const char*);
static int (*NeuronExecution_create)(NeuronCompilation*, NeuronExecution**);
static void (*NeuronExecution_free)(NeuronExecution*);
static int (*NeuronExecution_setInput)(NeuronExecution*, int32_t, const NeuronOperandType*,
                                       const void*, size_t);
static int (*NeuronExecution_setOutput)(NeuronExecution*, int32_t, const NeuronOperandType*, void*,
                                        size_t);
static int (*NeuronExecution_compute)(NeuronExecution*);

static void* g_lib;

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
}

static int load_symbols(void) {
    int missing = 0;
#define LOAD(name)                                                                                 \
    if (!(name = dlsym(g_lib, #name))) {                                                           \
        out("falta el simbolo %s", #name);                                                         \
        missing++;                                                                                 \
    }
    LOAD(Neuron_getVersion)
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
    LOAD(NeuronCompilation_create)
    LOAD(NeuronCompilation_createForDevices)
    LOAD(NeuronCompilation_free)
    LOAD(NeuronCompilation_finish)
    LOAD(NeuronCompilation_getCompiledNetworkSize)
    LOAD(NeuronCompilation_setOptimizationString)
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

static uint32_t g_seed = 12345;
static uint32_t rnd(void) {
    g_seed = g_seed * 1664525u + 1013904223u;
    return g_seed >> 8;
}

// ---- the formats

typedef struct {
    const char* name;
    const char* what;
    int32_t act_type; // inputs and outputs
    int act_bits;     // 0 = float
    int act_zero;     // zero point of the activations
    int32_t wt_type;
    int wt_bits;      // 0 = float
    int wt_zero;      // zero point of the weights
    bool wt_packed;   // 4-bit weights, two in a byte
} Format;

static const Format FORMATS[] = {
    {"fp16", "pesos y activaciones en coma flotante, calculo en media precision",
     NEURON_TENSOR_FLOAT32, 0, 0, NEURON_TENSOR_FLOAT32, 0, 0, false},
    {"a8w8n", "8 bits sin signo en todo, como por NNAPI",
     NEURON_TENSOR_QUANT8_ASYMM, 8, 128, NEURON_TENSOR_QUANT8_ASYMM, 8, 128, false},
    {"a8w8", "activaciones de 8 bits con signo, pesos de 8 bits simetricos",
     NEURON_TENSOR_QUANT8_ASYMM_SIGNED, 8, 0, NEURON_TENSOR_QUANT8_SYMM, 8, 0, false},
    {"a16w8", "activaciones de 16 bits, pesos de 8 bits",
     NEURON_TENSOR_QUANT16_SYMM, 16, 0, NEURON_TENSOR_QUANT8_SYMM, 8, 0, false},
    {"a16w4", "activaciones de 16 bits, pesos de 4 bits (un valor por byte)",
     NEURON_TENSOR_QUANT16_SYMM, 16, 0, NEURON_EXT_TENSOR_QUANT4_SYMM, 4, 0, false},
    {"a16w4p", "activaciones de 16 bits, pesos de 4 bits (dos valores por byte)",
     NEURON_TENSOR_QUANT16_SYMM, 16, 0, NEURON_EXT_TENSOR_QUANT4_SYMM, 4, 0, true},
    {"a8w4", "activaciones de 8 bits simetricas, pesos de 4 bits (un valor por byte)",
     NEURON_TENSOR_QUANT8_SYMM, 8, 0, NEURON_EXT_TENSOR_QUANT4_SYMM, 4, 0, false},
    {"a8w4p", "activaciones de 8 bits simetricas, pesos de 4 bits (dos valores por byte)",
     NEURON_TENSOR_QUANT8_SYMM, 8, 0, NEURON_EXT_TENSOR_QUANT4_SYMM, 4, 0, true},
};
#define FORMAT_COUNT (sizeof FORMATS / sizeof FORMATS[0])

// The options the library itself lists for LLMs, without the ones that only print or debug
static const char* const LLM_OPTIONS =
    "--opt=3 --opt-footprint --opt-aggressive --stable-linearize --split-16a4w-conv-oc "
    "--gno=LTS,Inception --gno-exp --gno-non-4d-tiling --fc-to-conv --mdla-conv-exp "
    "--intval-color-legacy --mdla-int16-lut --mlo";

// Real values of the tensors: inputs in [-1, 1], weights with a deviation near 0.02, outputs
// within [-OUT_MAX, OUT_MAX]
#define OUT_MAX 4.0f

static uint32_t g_rows = 8960, g_cols = 1536, g_layers = 4;

static size_t act_bytes(const Format* f) {
    return f->act_bits == 16 ? 2 : f->act_bits == 8 ? 1 : 4;
}

static float act_scale(const Format* f, float max_real) {
    return f->act_bits == 16 ? max_real / 32767.0f : max_real / 127.0f;
}

static float wt_scale(const Format* f) {
    // uniform integers in [-m, m] have a deviation of about m / 1.73
    return f->wt_bits == 4 ? 0.02f * 1.73f / 7.0f : 0.02f * 1.73f / 127.0f;
}

// Weights as small integers, the same for every format with that many bits
static int8_t* make_weights(const Format* f, size_t count) {
    int8_t* q = malloc(count);
    if (!q) {
        return NULL;
    }
    const int m = f->wt_bits == 4 ? 7 : 127;
    g_seed = 777;
    for (size_t i = 0; i < count; i++) {
        q[i] = (int8_t)((int)(rnd() % (uint32_t)(2 * m + 1)) - m);
    }
    return q;
}

// The buffer that goes to the model, in the layout of the weight type. *size gets its bytes
static void* pack_weights(const Format* f, const int8_t* q, size_t count, size_t* size) {
    if (f->wt_bits == 0) {
        float* w = malloc(count * sizeof(float));
        const float s = wt_scale(f);
        for (size_t i = 0; w && i < count; i++) {
            w[i] = q[i] * s;
        }
        *size = count * sizeof(float);
        return w;
    }
    if (f->wt_packed) {
        uint8_t* w = calloc((count + 1) / 2, 1);
        for (size_t i = 0; w && i < count; i++) {
            w[i / 2] |= (uint8_t)((q[i] & 0x0f) << (4 * (i % 2)));
        }
        *size = (count + 1) / 2;
        return w;
    }
    uint8_t* w = malloc(count);
    for (size_t i = 0; w && i < count; i++) {
        w[i] = (uint8_t)(q[i] + f->wt_zero);
    }
    *size = count;
    return w;
}

static int run_format(const Format* f, const NeuronDevice* dev, const char* options) {
    const uint32_t R = g_rows, C = g_cols, L = g_layers;
    const size_t per_layer = (size_t)R * C;
    const char* pass = options ? "con opciones" : "sin opciones";
    char head[160];
    snprintf(head, sizeof head, "%s %s", f->name, pass);

    const float in_scale = f->act_bits ? act_scale(f, 1.0f) : 0.0f;
    const float out_scale = f->act_bits ? act_scale(f, OUT_MAX) : 0.0f;
    const float w_scale = f->wt_bits ? wt_scale(f) : 0.0f;

    // Constant operands are referenced, not copied: they live until the compilation is finished
    int8_t* q = make_weights(f, per_layer * L);
    size_t w_size = 0;
    void* w = q ? pack_weights(f, q, per_layer * L, &w_size) : NULL;
    void* bias = calloc(R, 4);
    if (!q || !w || !bias) {
        out("%s | sin memoria para %zu pesos", head, per_layer * L);
        return 1;
    }
    const size_t w_layer = w_size / L;

    NeuronModel* m = NULL;
    int e = NeuronModel_create(&m);
    if (e || !m) {
        out("%s | NeuronModel_create FALLO (%d)", head, e);
        return 1;
    }
    const uint32_t in_dims[] = {1, C}, out_dims[] = {1, R}, w_dims[] = {R, C}, b_dims[] = {R};
    const int32_t no_activation = 0;
    uint32_t next = 0;
    const char* step = "";
#define ADD(type_, rank_, dims_, scale_, zero_)                                                    \
    do {                                                                                           \
        NeuronOperandType t = {type_, rank_, dims_, scale_, zero_};                                \
        if (!e) {                                                                                  \
            e = NeuronModel_addOperand(m, &t);                                                     \
            if (e) {                                                                               \
                step = "addOperand " #type_;                                                       \
            }                                                                                      \
        }                                                                                          \
        next++;                                                                                    \
    } while (0)

    ADD(f->act_type, 2, in_dims, in_scale, f->act_zero); // operand 0: the input
    uint32_t outs[16];
    for (uint32_t l = 0; l < L; l++) {
        const uint32_t wi = next;
        ADD(f->wt_type, 2, w_dims, w_scale, f->wt_zero);
        if (!e) {
            e = NeuronModel_setOperandValue(m, (int32_t)wi, (const uint8_t*)w + l * w_layer, w_layer);
            if (e) {
                step = "setOperandValue de los pesos";
            }
        }
        const uint32_t bi = next;
        if (f->wt_bits) {
            ADD(NEURON_TENSOR_INT32, 1, b_dims, in_scale * w_scale, 0);
        } else {
            ADD(NEURON_TENSOR_FLOAT32, 1, b_dims, 0.0f, 0);
        }
        if (!e) {
            e = NeuronModel_setOperandValue(m, (int32_t)bi, bias, (size_t)R * 4);
            if (e) {
                step = "setOperandValue del sesgo";
            }
        }
        const uint32_t ai = next;
        ADD(NEURON_INT32, 0, NULL, 0.0f, 0);
        if (!e) {
            e = NeuronModel_setOperandValue(m, (int32_t)ai, &no_activation, sizeof no_activation);
        }
        const uint32_t oi = next;
        ADD(f->act_type, 2, out_dims, out_scale, f->act_zero);
        outs[l] = oi;
        const uint32_t op_in[] = {0, wi, bi, ai};
        if (!e) {
            e = NeuronModel_addOperation(m, NEURON_FULLY_CONNECTED, 4, op_in, 1, &oi);
            if (e) {
                step = "addOperation FULLY_CONNECTED";
            }
        }
    }
#undef ADD
    const uint32_t input0 = 0;
    if (!e) {
        e = NeuronModel_identifyInputsAndOutputs(m, 1, &input0, L, outs);
        if (e) {
            step = "identifyInputsAndOutputs";
        }
    }
    if (!e && f->act_bits == 0) {
        e = NeuronModel_relaxComputationFloat32toFloat16(m, true);
    }
    if (!e) {
        e = NeuronModel_finish(m);
        if (e) {
            step = "NeuronModel_finish";
        }
    }
    if (e) {
        out("%s | el modelo NO se acepta: %s devuelve %d", head, step, e);
        NeuronModel_free(m);
        return 1;
    }

    bool supported[16] = {false};
    uint32_t yes = 0;
    const NeuronDevice* const devs[1] = {dev};
    if (dev) {
        const int se = NeuronModel_getSupportedOperationsForDevices(m, devs, 1, supported);
        for (uint32_t l = 0; !se && l < L; l++) {
            yes += supported[l];
        }
    }

    NeuronCompilation* c = NULL;
    double t0 = now_ms();
    e = dev ? NeuronCompilation_createForDevices(m, devs, 1, &c) : NeuronCompilation_create(m, &c);
    step = "crear la compilacion";
    if (!e && options) {
        e = NeuronCompilation_setOptimizationString(c, options);
        step = "setOptimizationString";
    }
    if (!e) {
        e = NeuronCompilation_finish(c);
        step = "NeuronCompilation_finish";
    }
    const double compile_ms = now_ms() - t0;
    if (e || !c) {
        out("%s | modelo aceptado, MDLA soporta %u/%u | NO compila: %s devuelve %d tras %.0f ms",
            head, yes, L, step, e, compile_ms);
        if (c) {
            NeuronCompilation_free(c);
        }
        NeuronModel_free(m);
        return 1;
    }
    size_t dla = 0;
    const int de = NeuronCompilation_getCompiledNetworkSize(c, &dla);

    // Inputs and outputs in the layout of the activation type
    const size_t ab = act_bytes(f);
    void* in = calloc(C, ab);
    void* got = calloc((size_t)R * L, ab);
    float* x_real = calloc(C, sizeof(float));
    g_seed = 4242;
    for (uint32_t i = 0; i < C; i++) {
        const float x = (float)(rnd() % 20001) / 10000.0f - 1.0f;
        if (f->act_bits == 16) {
            const int16_t v = (int16_t)lrintf(x / in_scale);
            ((int16_t*)in)[i] = v;
            x_real[i] = v * in_scale;
        } else if (f->act_bits == 8) {
            const int v = (int)lrintf(x / in_scale);
            ((uint8_t*)in)[i] = (uint8_t)(v + f->act_zero);
            x_real[i] = v * in_scale;
        } else {
            ((float*)in)[i] = x;
            x_real[i] = x;
        }
    }

    double sum_ms = 0.0;
    int runs = 0;
    for (int r = 0; r < 23 && !e; r++) {
        NeuronExecution* x = NULL;
        e = NeuronExecution_create(c, &x);
        if (!e) {
            e = NeuronExecution_setInput(x, 0, NULL, in, (size_t)C * ab);
        }
        for (uint32_t l = 0; !e && l < L; l++) {
            e = NeuronExecution_setOutput(x, (int32_t)l, NULL, (uint8_t*)got + (size_t)l * R * ab,
                                          (size_t)R * ab);
        }
        const double a = now_ms();
        if (!e) {
            e = NeuronExecution_compute(x);
        }
        if (!e && r >= 3) {
            sum_ms += now_ms() - a;
            runs++;
        }
        if (x) {
            NeuronExecution_free(x);
        }
    }

    // The same sum in C over the first layer, with the values the integers stand for
    double err2 = 0.0, ref2 = 0.0;
    const float ws = f->wt_bits ? w_scale : wt_scale(f);
    for (uint32_t r = 0; !e && r < R; r++) {
        double acc = 0.0;
        const int8_t* row = q + (size_t)r * C;
        for (uint32_t i = 0; i < C; i++) {
            acc += (double)row[i] * x_real[i];
        }
        const double ref = acc * ws;
        double y;
        if (f->act_bits == 16) {
            y = ((int16_t*)got)[r] * (double)out_scale;
        } else if (f->act_bits == 8) {
            y = ((int)((uint8_t*)got)[r] - f->act_zero) * (double)out_scale;
            if (f->act_type != NEURON_TENSOR_QUANT8_ASYMM) {
                y = ((int8_t*)got)[r] * (double)out_scale;
            }
        } else {
            y = ((float*)got)[r];
        }
        err2 += (y - ref) * (y - ref);
        ref2 += ref * ref;
    }
    const double total = (double)per_layer * L;
    if (e) {
        out("%s | MDLA soporta %u/%u | compila en %.0f ms | la ejecucion FALLA (%d)", head, yes, L,
            compile_ms, e);
    } else {
        out("%s | MDLA soporta %u/%u | compila en %.0f ms | red compilada %.1f MB = %.2f bytes por peso "
            "(codigo %d) | %.3f ms por ejecucion de %.0f M de pesos | error relativo %.4f",
            head, yes, L, compile_ms, dla / 1048576.0, dla / total, de, runs ? sum_ms / runs : 0.0,
            total / 1e6, ref2 > 0.0 ? sqrt(err2 / ref2) : -1.0);
    }
    free(in);
    free(got);
    free(x_real);
    NeuronCompilation_free(c);
    NeuronModel_free(m);
    free(w);
    free(q);
    free(bias);
    return e ? 1 : 0;
}

static uint32_t env_u32(const char* name, uint32_t fallback) {
    const char* v = getenv(name);
    return v && *v ? (uint32_t)strtoul(v, NULL, 10) : fallback;
}

int main(int argc, char** argv) {
    g_rows = env_u32("NQ_ROWS", g_rows);
    g_cols = env_u32("NQ_COLS", g_cols);
    g_layers = env_u32("NQ_LAYERS", g_layers);
    if (g_layers < 1 || g_layers > 16) {
        g_layers = 4;
    }
    const char* opts = getenv("NQ_OPTS");
    if (!opts || !*opts) {
        opts = LLM_OPTIONS;
    }
    const bool second_pass = strcmp(opts, "none") != 0;
    const char* only = argc > 1 ? argv[1] : NULL;

    g_lib = dlopen("libneuronusdk_adapter.mtk.so", RTLD_NOW);
    if (!g_lib) {
        g_lib = dlopen("/system_ext/lib64/libneuronusdk_adapter.mtk.so", RTLD_NOW);
    }
    if (!g_lib) {
        out("dlopen FALLO: %s", dlerror());
        out("FIN");
        return 1;
    }
    if (load_symbols()) {
        out("FIN");
        return 1;
    }
    NeuronRuntimeVersion version = {0, 0, 0};
    Neuron_getVersion(&version);
    uint32_t count = 0;
    Neuron_getDeviceCount(&count);
    NeuronDevice* mdla = NULL;
    char names[256] = "";
    for (uint32_t i = 0; i < count; i++) {
        NeuronDevice* d = NULL;
        const char* name = "?";
        if (Neuron_getDevice(i, &d) || !d) {
            continue;
        }
        NeuronDevice_getName(d, &name);
        strncat(names, name, sizeof names - strlen(names) - 2);
        strncat(names, " ", sizeof names - strlen(names) - 1);
        if (strstr(name, "mdla")) {
            mdla = d;
        }
    }
    if (!only) {
        out("Neuron %u.%u.%u | dispositivos: %s| capa %u x %u, %u por grafo | MDLA %s", version.major,
            version.minor, version.patch, names, g_rows, g_cols, g_layers,
            mdla ? "encontrado" : "NO encontrado: se compila sin forzar dispositivo");
        if (second_pass) {
            out("opciones del segundo pase: %s", opts);
        }
    }

    // With a format name this process tries it, without options, with them, or both
    const char* which = argc > 2 ? argv[2] : NULL;
    if (only) {
        for (size_t i = 0; i < FORMAT_COUNT; i++) {
            const Format* f = &FORMATS[i];
            if (strcmp(only, f->name) != 0) {
                continue;
            }
            if (!which || !strcmp(which, "sin")) {
                run_format(f, mdla, NULL);
            }
            if (second_pass && (!which || !strcmp(which, "con"))) {
                run_format(f, mdla, opts);
            }
        }
        if (!getenv("NQ_CHILD")) {
            out("FIN");
        }
        return 0;
    }

    // Without one, every attempt runs in a process of its own started from scratch: a crash
    // inside the vendor compiler must not end the survey, and the library is not fork-safe
    setenv("NQ_CHILD", "1", 1);
    for (size_t i = 0; i < FORMAT_COUNT; i++) {
        const Format* f = &FORMATS[i];
        out("--- %s: %s", f->name, f->what);
        for (int pass = 0; pass < (second_pass ? 2 : 1); pass++) {
            fflush(stdout);
            const pid_t child = fork();
            if (child == 0) {
                execl("/proc/self/exe", "neuron_quant_probe", f->name, pass ? "con" : "sin",
                      (char*)NULL);
                _exit(127);
            }
            int status = 0;
            if (child < 0 || waitpid(child, &status, 0) < 0) {
                out("%s | no se pudo lanzar el proceso de prueba", f->name);
            } else if (WIFSIGNALED(status)) {
                out("%s %s | el proceso MURIO con la senal %d", f->name,
                    pass ? "con opciones" : "sin opciones", WTERMSIG(status));
            } else if (WEXITSTATUS(status) == 127) {
                out("%s | no se pudo volver a ejecutar el programa", f->name);
            }
        }
    }
    out("FIN");
    return 0;
}
