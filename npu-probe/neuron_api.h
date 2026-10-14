// Block and classifier graphs share the NNAPI ABI; declarations come from neuron_quant_probe.c.
// Handles stay opaque. A process uses only one backend for these graphs.
#ifndef NEURON_API_H
#define NEURON_API_H

enum { NEURON_TENSOR_QUANT16_SYMM = 7, NEURON_TENSOR_QUANT8_SYMM = 13,
       NEURON_EXT_TENSOR_QUANT4_SYMM = 9005, NEURON_QUANTIZE = 72, NEURON_SLICE = 86 };

#define BLOCK_API_FUNCTIONS(X) \
    X(Model_create) \
    X(Model_free) \
    X(Model_addOperand) \
    X(Model_setOperandValue) \
    X(Model_addOperation) \
    X(Model_identifyInputsAndOutputs) \
    X(Model_relaxComputationFloat32toFloat16) \
    X(Model_finish) \
    X(Model_getSupportedOperationsForDevices) \
    X(Compilation_createForDevices) \
    X(Compilation_free) \
    X(Compilation_finish) \
    X(Execution_create) \
    X(Execution_free) \
    X(Execution_setInput) \
    X(Execution_setOutput) \
    X(Execution_compute)

typedef struct {
#define BLOCK_API_FIELD(name) __typeof__(ANeuralNetworks##name) name;
    BLOCK_API_FUNCTIONS(BLOCK_API_FIELD)
#undef BLOCK_API_FIELD
    int (*Compilation_setOptimizationString)(ANeuralNetworksCompilation*, const char*);
    int (*Compilation_getCompiledNetworkSize)(ANeuralNetworksCompilation*, size_t*);
} BlockApi;

static BlockApi block_api;
static void* neuron_lib; // kept until all graphs have been freed

static void blocks_api_nnapi(void) {
#define BLOCK_API_ASSIGN(name) block_api.name = ANeuralNetworks##name;
    BLOCK_API_FUNCTIONS(BLOCK_API_ASSIGN)
#undef BLOCK_API_ASSIGN
}

static bool blocks_api_neuron(void) {
    neuron_lib = dlopen("libneuronusdk_adapter.mtk.so", RTLD_NOW);
    if (!neuron_lib) {
        neuron_lib = dlopen("/system_ext/lib64/libneuronusdk_adapter.mtk.so", RTLD_NOW);
    }
    if (!neuron_lib) {
        fprintf(stderr, "nnapi: blocks: neuron dlopen failed: %s\n", dlerror());
        return false;
    }
    int missing = 0;
#define BLOCK_API_LOAD(name) \
    if (!(block_api.name = dlsym(neuron_lib, "Neuron" #name))) { \
        fprintf(stderr, "nnapi: blocks: missing symbol Neuron%s\n", #name); missing++; \
    }
    BLOCK_API_FUNCTIONS(BLOCK_API_LOAD)
    BLOCK_API_LOAD(Compilation_setOptimizationString)
#undef BLOCK_API_LOAD
    block_api.Compilation_getCompiledNetworkSize = dlsym(neuron_lib, "NeuronCompilation_getCompiledNetworkSize");
    __typeof__(ANeuralNetworks_getDeviceCount) get_count = dlsym(neuron_lib, "Neuron_getDeviceCount");
    __typeof__(ANeuralNetworks_getDevice) get_device = dlsym(neuron_lib, "Neuron_getDevice");
    __typeof__(ANeuralNetworksDevice_getName) get_name = dlsym(neuron_lib, "NeuronDevice_getName");
    if (!get_count || !get_device || !get_name) {
        fprintf(stderr, "nnapi: blocks: missing Neuron device enumeration symbols\n");
        missing++;
    }
    if (missing) { return false; }
    uint32_t count = 0;
    int e = get_count(&count);
    if (e) {
        fprintf(stderr, "nnapi: blocks: neuron device enumeration failed (error %d)\n", e);
        return false;
    }
    for (uint32_t i = 0; i < count; i++) {
        ANeuralNetworksDevice* device = NULL;
        const char* name = NULL;
        if (get_device(i, &device) || !device || get_name(device, &name) || !name) { continue; }
        fprintf(stderr, "nnapi: blocks: neuron device %u: %s\n", i, name);
        if (strcmp(name, "mtk-mdla") == 0) { nn.device = device; nn.device_name = name; }
    }
    if (!nn.device) { fprintf(stderr, "nnapi: blocks: neuron has no mtk-mdla device, using CPU\n"); }
    return nn.device != NULL;
}

#undef BLOCK_API_FUNCTIONS
#endif
