// Build/run on Linux CI or Android, never on the project notebook.
#define TESTING
#include "run_nnapi.c"
#include <assert.h>

static int finishes, models, compilations, fail_finish;
static const void* constants[BLOCK_MAX_OPS * 8];
static size_t lengths[BLOCK_MAX_OPS * 8];
static uint64_t hashes[BLOCK_MAX_OPS * 8];
static int nconstants, nops;

static int model_create(ANeuralNetworksModel** model) {
    *model = (ANeuralNetworksModel*)malloc(1);
    models++; nconstants = nops = 0;
    return *model ? 0 : -1;
}
static void model_free(ANeuralNetworksModel* model) { free(model); models--; }
static int operand(ANeuralNetworksModel* model, const ANeuralNetworksOperandType* type) {
    (void)model; (void)type; return 0;
}
static int value(ANeuralNetworksModel* model, int32_t at, const void* data, size_t size) {
    (void)model; (void)at;
    assert(nconstants < BLOCK_MAX_OPS * 8 && data);
    constants[nconstants] = data; lengths[nconstants] = size;
    hashes[nconstants++] = w4_hash(0xcbf29ce484222325ULL, data, size);
    return 0;
}
static int operation(ANeuralNetworksModel* model, int32_t op, uint32_t ni, const uint32_t* in,
                     uint32_t no, const uint32_t* out) {
    (void)model; (void)op; (void)ni; (void)in; (void)no; (void)out; nops++; return 0;
}
static int identify(ANeuralNetworksModel* model, uint32_t ni, const uint32_t* in,
                    uint32_t no, const uint32_t* out) {
    (void)model; (void)ni; (void)in; (void)no; (void)out; return 0;
}
static int relax(ANeuralNetworksModel* model, bool yes) { (void)model; (void)yes; return 0; }
static int model_finish(ANeuralNetworksModel* model) { (void)model; return 0; }
static int supported(const ANeuralNetworksModel* model, const ANeuralNetworksDevice* const* devices,
                     uint32_t n, bool* takes) {
    (void)model; (void)devices; (void)n;
    for (int i = 0; i < nops; i++) { takes[i] = true; }
    return 0;
}
static int compilation_create(ANeuralNetworksModel* model, const ANeuralNetworksDevice* const* devices,
                              uint32_t n, ANeuralNetworksCompilation** compilation) {
    (void)model; (void)devices; (void)n;
    *compilation = (ANeuralNetworksCompilation*)malloc(1); compilations++;
    return *compilation ? 0 : -1;
}
static void compilation_free(ANeuralNetworksCompilation* compilation) { free(compilation); compilations--; }
static int compilation_finish(ANeuralNetworksCompilation* compilation) {
    (void)compilation;
    // Under ASan, catches weights or small stack constants released before finish.
    for (int i = 0; i < nconstants; i++) {
        assert(hashes[i] == w4_hash(0xcbf29ce484222325ULL, constants[i], lengths[i]));
    }
    finishes++;
    return fail_finish ? 6 : 0;
}

static void released(BlockGraph* bg) {
    assert(!bg->owned && !bg->n_owned);
    for (int i = 0; i < BLOCK_MATRICES * bg->count; i++) {
        MatmulGraph* g = &nn.graphs[blk.first_matrix + bg->first * BLOCK_MATRICES + i];
        assert(!g->w8 && !g->row_scale && !g->in && !g->out && !g->x1);
        assert(g->w && g->n && g->d);
    }
}

int main(void) {
    assert(neuron_mem_fits(100, 100, 20, 20, 10, 50)); // equality allowed
    assert(!neuron_mem_fits(100, 100, 21, 20, 10, 50)); // budget ledger
    assert(!neuron_mem_fits(79, 100, 0, 20, 10, 50)); // live memory drop
    assert(!neuron_mem_fits(-1, 100, 0, 0, 0, 0)); // fail closed
    nn.neuron = false;
    assert(neuron_mem_admit("legacy NNAPI bypass", 1e100, true));

    setenv("NNAPI_BLOCKS", "1", 1);
    setenv("NNAPI_BLOCK_FIRST", "0", 1);
    setenv("NNAPI_BLOCK_LAYERS", "2", 1);
    setenv("NNAPI_BLOCK_T", "4", 1);
    setenv("NNAPI_BLOCK_RESCALE", "0", 1);
    setenv("NNAPI_MEM_MARGIN_MB", "0", 1);
    setenv("NNAPI_FREE_AFTER_COMPILE", "1", 1);
    unsetenv("NNAPI_NEURON_OPTS"); unsetenv("NNAPI_WEIGHTS_FILE");
    nn.neuron = true; nn.state = 1; nn.q8 = Q8_DEQUANT; nn.device_name = "mock";
    block_api = (BlockApi){.Model_create = model_create, .Model_free = model_free,
        .Model_addOperand = operand, .Model_setOperandValue = value, .Model_addOperation = operation,
        .Model_identifyInputsAndOutputs = identify, .Model_relaxComputationFloat32toFloat16 = relax,
        .Model_finish = model_finish, .Model_getSupportedOperationsForDevices = supported,
        .Compilation_createForDevices = compilation_create, .Compilation_free = compilation_free,
        .Compilation_finish = compilation_finish};
    float matrix[64 * 64], norms[2 * 64];
    for (int i = 0; i < 64 * 64; i++) { matrix[i] = (i % 19 - 9) * 0.01f; }
    for (int i = 0; i < 2 * 64; i++) { norms[i] = 1.0f; }
    Matrix matrices[2] = {{.f = matrix}, {.f = matrix}};
    Transformer t = {.config = {.dim = 64, .hidden_dim = 64, .n_layers = 2,
        .n_heads = 2, .n_kv_heads = 1, .vocab_size = 64, .seq_len = 8},
        .weights = {.wq = matrices, .wk = matrices, .wv = matrices, .wo = matrices,
            .w1 = matrices, .w3 = matrices, .w2 = matrices, .wcls = matrices,
            .rms_att_weight = norms, .rms_ffn_weight = norms}};
    // Deterministic admission budget; live /proc/meminfo must still be readable.
    neuron_mem.initialized = neuron_mem.release = true;
    neuron_mem.baseline = 1e15; neuron_mem.graph_ratio = 1.0;
    blocks_setup_impl(&t);
    assert(blk.state == 1 && blk.n_groups == 2);
    for (int i = 0; i < nn.n_graphs; i++) { assert(!nn.graphs[i].w8); }
    blocks_compile(&t, &blk.groups[0]);
    assert(blk.groups[0].state == 1 && finishes == 1);
    released(&blk.groups[0]);
    for (int i = 7; i < nn.n_graphs; i++) { assert(!nn.graphs[i].w8); }
    // Quantized CPU diagnostics can reconstruct precisely the released data.
    uint64_t hash = nn.graphs[0].hash;
    assert(neuron_group_prepare(&blk.groups[0], false));
    assert(nn.graphs[0].hash == hash);
    neuron_group_free(&blk.groups[0]);
    neuron_mem.margin = 1e30;
    blocks_compile(&t, &blk.groups[1]);
    assert(blk.groups[1].state == -1 && finishes == 1 && neuron_mem.stopped);
    released(&blk.groups[1]);
    neuron_mem.margin = 0.0; neuron_mem.stopped = false;
    // W4 is also loaded/released a group at a time, retaining identical hashes.
    char checkpoint[PATH_MAX];
    const char* temporary = getenv("TMPDIR");
#ifdef __ANDROID__
    if (!temporary) { temporary = "/data/local/tmp"; }
#else
    if (!temporary) { temporary = "/tmp"; }
#endif
    snprintf(checkpoint, sizeof checkpoint, "%s/nnapi-memory-XXXXXX", temporary);
    int fd = mkstemp(checkpoint);
    assert(fd >= 0); close(fd);
    nn.checkpoint = checkpoint;
    w4.enabled = true;
    assert(neuron_group_prepare(&blk.groups[1], false));
    hash = nn.graphs[7].hash;
    neuron_group_free(&blk.groups[1]);
    assert(neuron_group_prepare(&blk.groups[1], false));
    assert(nn.graphs[7].hash == hash);
    neuron_group_free(&blk.groups[1]);
    fail_finish = 1;
    blocks_compile(&t, &blk.groups[1]);
    assert(blk.groups[1].state == -1 && finishes == 2 && !blk.groups[1].model);
    released(&blk.groups[1]);
    fail_finish = 0;
    w4.enabled = false;
    setenv("NNAPI_HEAD_CHUNK", "32", 1);
    head_setup(&t);
    assert(head.state == 1 && finishes == 3);
    assert(!head.weights.w8 && !head.weights.row_scale && !head.graph.owned);
    assert(head.weights.w == matrices); // VERIFY/fallback checkpoint still valid
    head_free();
    // Explicit retention switch preserves buffers, still subject to admission.
    neuron_mem.release = false;
    blocks_compile(&t, &blk.groups[1]);
    assert(blk.groups[1].state == 1 && nn.graphs[7].w8 && blk.groups[1].owned);
    // A refused head must never enter the compiler or allocate weight buffers.
    neuron_mem.margin = 1e30;
    head_setup(&t);
    assert(head.state == -1 && !head.weights.w8 && finishes == 4 && neuron_mem.stopped);
    free_matmul();
    assert(models == 0 && compilations == 0);
    unlink(checkpoint);
    puts("memory admission and constant lifetime tests passed");
    return 0;
}
