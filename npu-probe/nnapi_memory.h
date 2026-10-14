// Neuron-only admission control. Estimates are not a driver allocation limit.
#define NN_MIB (1024.0 * 1024.0)
static struct {
    bool initialized, stopped, release;
    double margin, baseline, charged, reserve, graph_ratio;
} neuron_mem;

static double neuron_mem_available(void) {
    FILE* f = fopen("/proc/meminfo", "r");
    if (!f) { return -1.0; }
    char line[256];
    double kb, result = -1.0;
    while (fgets(line, sizeof line, f)) {
        if (sscanf(line, "MemAvailable: %lf kB", &kb) == 1) {
            if (isfinite(kb) && kb >= 0.0) { result = kb * 1024.0; }
            break;
        }
    }
    fclose(f);
    return result;
}

static void neuron_mem_setup(Transformer* t) {
    if (!nn.neuron || neuron_mem.initialized) { return; }
    neuron_mem.initialized = true;
    neuron_mem.release = true;
    neuron_mem.margin = 1500.0 * NN_MIB;
    neuron_mem.graph_ratio = w4.enabled ? 0.5 : 1.0;
    // Accuracy options can promote W4 compiled storage to W8 (observed in probes).
    const char* opts = getenv("NNAPI_NEURON_OPTS");
    if (opts && *opts) { neuron_mem.graph_ratio = 2.0; }
    const char* release = getenv("NNAPI_FREE_AFTER_COMPILE");
    if (release) {
        if (strcmp(release, "0") == 0) { neuron_mem.release = false; }
        else if (strcmp(release, "1") != 0) { neuron_mem.stopped = true; }
    }
    const char* margin = getenv("NNAPI_MEM_MARGIN_MB");
    if (margin) {
        char* end;
        errno = 0;
        double mb = strtod(margin, &end);
        if (errno || end == margin || *end || !isfinite(mb) || mb < 0.0 || mb > 1048576.0) {
            neuron_mem.stopped = true;
        } else { neuron_mem.margin = mb * NN_MIB; }
    }
    Config* p = &t->config;
    double kv_dim = (double)(p->dim / p->n_heads) * p->n_kv_heads;
    // The checkpoint is a clean read-only MAP_PRIVATE file, not anonymous RAM.
    // MemAvailable includes reclaimable file cache: charging its entire mapping
    // as pinned memory would reject models that fit. Reserve a source working
    // set of one group or classifier (whichever is larger), including Q8 scales.
    // Also cover lazy calloc K/V pages and token/activation buffers. Previously
    // visited checkpoint pages may remain resident but the kernel can evict them.
    double layer = 2.0 * p->dim * p->dim + 2.0 * p->dim * kv_dim
                 + 3.0 * p->dim * p->hidden_dim;
    double source = fmax(layer * blk.per_graph, (double)p->dim * p->vocab_size)
                  * (GS > 0 ? 1.0 + (double)sizeof(float) / GS : sizeof(float));
    source = fmin(source, (double)t->file_size);
    neuron_mem.reserve = source
        + 8.0 * p->n_layers * p->seq_len * kv_dim
        + 8.0 * blk.layers * blk.T * kv_dim + 64.0 * NN_MIB;
    neuron_mem.baseline = neuron_mem_available();
    if (neuron_mem.baseline < 0.0) { neuron_mem.stopped = true; }
    fprintf(stderr, "nnapi: memory: MemAvailable %.1f MiB, margin %.1f MiB, checkpoint mapping %.1f MiB "
            "(reclaimable file pages), source working set %.1f MiB, source+runtime reserve %.1f MiB, "
            "FREE_AFTER_COMPILE=%d%s\n",
            neuron_mem.baseline / NN_MIB, neuron_mem.margin / NN_MIB, (double)t->file_size / NN_MIB,
            source / NN_MIB, neuron_mem.reserve / NN_MIB, neuron_mem.release,
            neuron_mem.stopped ? "; invalid options or unreadable MemAvailable: further graphs stay on CPU" : "");
}

static double neuron_mem_graph(double weights) {
    return weights * neuron_mem.graph_ratio + 16.0 * NN_MIB;
}

static bool neuron_mem_fits(double available, double baseline, double charged,
                            double reserve, double margin, double temporary) {
    return available >= 0.0 && temporary + reserve + margin <= fmin(available, baseline - charged);
}

static bool neuron_mem_admit(const char* name, double weights, bool compile) {
    if (!nn.neuron) { return true; }
    double available = neuron_mem_available();
    double graph = compile ? neuron_mem_graph(weights) : 0.0;
    // One input byte per weight even for W4, plus a full input-sized compiler
    // scratch allowance, graph storage and 64 MiB for constants/replay scratch.
    double temporary = 2.0 * weights + graph + 64.0 * NN_MIB;
    bool ok = !neuron_mem.stopped && neuron_mem_fits(available, neuron_mem.baseline,
        neuron_mem.charged, neuron_mem.reserve, neuron_mem.margin, temporary);
    fprintf(stderr, "nnapi: memory: %s: RAM weights %.1f MiB, expected graph %.1f MiB, "
            "temporary peak %.1f MiB + reserve %.1f MiB; MemAvailable %.1f MiB, "
            "budget remaining %.1f MiB, margin %.1f MiB: %s\n", name, weights / NN_MIB,
            graph / NN_MIB, temporary / NN_MIB, neuron_mem.reserve / NN_MIB,
            available / NN_MIB, (neuron_mem.baseline - neuron_mem.charged) / NN_MIB,
            neuron_mem.margin / NN_MIB, ok ? "admitted" : "STOP; this and further uncompiled graphs stay on CPU");
    if (!ok) { neuron_mem.stopped = true; }
    return ok;
}

static bool neuron_mem_before_finish(double weights) {
    if (!nn.neuron) { return true; }
    double available = neuron_mem_available();
    // Weights are already allocated; add them back only to the live reading,
    // not the baseline ledger. Recheck after potentially slow quantization/I/O.
    double temporary = 2.0 * weights + neuron_mem_graph(weights) + 64.0 * NN_MIB;
    bool ok = !neuron_mem.stopped && available >= 0.0
        && neuron_mem_fits(available + weights, neuron_mem.baseline, neuron_mem.charged,
                          neuron_mem.reserve, neuron_mem.margin, temporary);
    fprintf(stderr, "nnapi: memory: before finish: MemAvailable %.1f MiB: %s\n",
            available / NN_MIB, ok ? "admitted" : "STOP; further uncompiled graphs stay on CPU");
    if (!ok) { neuron_mem.stopped = true; }
    return ok;
}

static void neuron_mem_charge(ANeuralNetworksCompilation* compilation, double weights) {
    double graph = neuron_mem_graph(weights);
    size_t actual = 0;
    if (block_api.Compilation_getCompiledNetworkSize
        && !block_api.Compilation_getCompiledNetworkSize(compilation, &actual)) {
        graph = fmax(graph, (double)actual + 16.0 * NN_MIB);
        if (weights > 0.0) { neuron_mem.graph_ratio = fmax(neuron_mem.graph_ratio, actual / weights); }
    }
    neuron_mem.charged += graph + (neuron_mem.release ? 0.0 : weights + 16.0 * NN_MIB);
}

static void neuron_weights_free(MatmulGraph* g) {
    free(g->w8); g->w8 = NULL;
    free(g->row_scale); g->row_scale = NULL;
    free(g->x1); g->x1 = NULL;
    free(g->in); g->in = NULL;
    free(g->out); g->out = NULL;
    // Keep shape, checkpoint Matrix*, id and hash for CPU fallback/VERIFY.
}

static void neuron_constants_free(BlockGraph* bg) {
    for (int i = 0; i < bg->n_owned; i++) { free(bg->owned[i]); }
    free(bg->owned); bg->owned = NULL; bg->n_owned = 0;
}

static double neuron_group_bytes(BlockGraph* bg) {
    double bytes = 0.0;
    for (int i = 0; i < BLOCK_MATRICES * bg->count; i++) {
        MatmulGraph* g = &nn.graphs[blk.first_matrix + BLOCK_MATRICES * bg->first + i];
        bytes += (double)g->n * g->d;
    }
    return bytes;
}

static void neuron_group_free(BlockGraph* bg) {
    for (int i = 0; i < BLOCK_MATRICES * bg->count; i++) {
        neuron_weights_free(&nn.graphs[blk.first_matrix + BLOCK_MATRICES * bg->first + i]);
    }
}

static bool neuron_group_prepare(BlockGraph* bg, bool compile) {
    char name[80];
    snprintf(name, sizeof name, "layers %d..%d%s", bg->first, bg->first + bg->count - 1,
             compile ? "" : " CPU replay");
    if (!neuron_mem_admit(name, neuron_group_bytes(bg), compile)) { return false; }
    double start = now_ms();
    bool ok = true;
    for (int i = 0; i < BLOCK_MATRICES * bg->count; i++) {
        MatmulGraph* g = &nn.graphs[blk.first_matrix + BLOCK_MATRICES * bg->first + i];
        if (g->w8) { continue; }
        if ((w4.enabled ? prepare_weights_4bit(g) : prepare_weights_8bit(g)) != 0) { ok = false; break; }
        if (!w4.enabled) {
            // Preserve unsigned cache files and exactly the previous signed conversion.
            for (size_t j = 0; j < (size_t)g->n * g->d; j++) {
                ((int8_t*)g->w8)[j] = (int8_t)((int)g->w8[j] - Q8_ZERO);
            }
        }
    }
    nn.weights_ms += now_ms() - start;
    if (!ok) {
        fprintf(stderr, "nnapi: memory: %s: weights unavailable; CPU fallback\n", name);
        neuron_group_free(bg);
    }
    return ok;
}
