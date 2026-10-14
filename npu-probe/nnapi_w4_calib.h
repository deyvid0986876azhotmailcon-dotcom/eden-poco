// CPU replay uses the builder's shapes and operations, including residual scales.
// It estimates ranges before device rounding; normal forward still supplies CPU text.
typedef struct {
    int type;
    uint32_t rank, dims[4];
    size_t count;
    const void* data;
} W4Operand;

typedef struct {
    W4Operand* operands;
    float alpha, epsilon;
} W4Replay;

typedef struct {
    char magic[8];
    int64_t size, mtime;
    Config config;
    ModelExtras extras;
    int32_t group_size, first, layers, per_graph, T, rescale, renorm, safe_norm, scaled_eps, chunk, count;
    float norm, epsilon;
} W4CalibHeader;

static W4CalibHeader w4_key;

static void w4_calib_setup(Transformer* t) {
    if (!w4.enabled || !w4.island || w4.initialized || nn.state < 0) { return; }
    w4.initialized = true;
    struct stat st;
    if (!nn.checkpoint || stat(nn.checkpoint, &st)) { w4.failed = true; return; }
    memcpy(w4_key.magic, "nnw4cal1", 8);
    w4_key.size = st.st_size; w4_key.mtime = st.st_mtime;
    w4_key.config = t->config; w4_key.extras = t->extras;
    w4_key.group_size = GS;
    w4_key.first = blk.first; w4_key.layers = blk.state > 0 ? blk.layers : blk.first;
    w4_key.per_graph = blk.per_graph; w4_key.T = blk.T;
    w4_key.rescale = blk.rescale; w4_key.renorm = blk.renorm; w4_key.safe_norm = blk.safe_norm;
    w4_key.scaled_eps = blk.scaled_eps; w4_key.norm = blk.norm; w4_key.epsilon = NORM_EPS;
    const char* chunk = getenv("NNAPI_HEAD_CHUNK");
    char* end = NULL;
    long value = chunk ? strtol(chunk, &end, 10) : 0;
    if (chunk && (*end || value < 0 || value > INT_MAX)) { value = 0; }
    w4_key.chunk = value > t->config.vocab_size ? t->config.vocab_size : (int)value;
    int chunks = w4_key.chunk ? 1 + (t->config.vocab_size - 1) / w4_key.chunk : 0;
    w4.count = t->config.n_layers * BLOCK_MATRICES + chunks;
    w4_key.count = w4.count;
    w4.ranges = calloc(w4.count, sizeof(W4Range));
    if (!w4.ranges) { w4.failed = true; return; }
    int fd = open(w4.path, O_RDONLY);
    if (fd < 0 && errno == ENOENT) {
        w4.collecting = true;
        fprintf(stderr, "nnapi: W4 calibrating first %d tokens on CPU, margin %g, file %s\n",
                w4.target, w4.margin, w4.path);
        return;
    }
    W4CalibHeader saved;
    size_t bytes = (size_t)w4.count * sizeof(W4Range);
    uint64_t checksum = 0;
    bool ok = fd >= 0 && pread_all(fd, &saved, sizeof saved, 0)
        && memcmp(&saved, &w4_key, sizeof saved) == 0
        && pread_all(fd, w4.ranges, bytes, sizeof saved)
        && pread_all(fd, &checksum, sizeof checksum, sizeof saved + bytes)
        && checksum == w4_hash(0xcbf29ce484222325ULL, w4.ranges, bytes);
    if (fd >= 0) { close(fd); }
    for (int i = 0; i < w4.count && ok; i++) {
        W4Range* r = &w4.ranges[i];
        bool required = (i >= w4_key.first * BLOCK_MATRICES && i < w4_key.layers * BLOCK_MATRICES)
                     || i >= t->config.n_layers * BLOCK_MATRICES;
        if (required && (!r->samples || !isfinite(r->input) || !isfinite(r->output)
                         || r->input < 0.0f || r->output < 0.0f)) { ok = false; }
    }
    w4.failed = !ok;
    fprintf(stderr, "nnapi: W4 calibration %s: %s%s\n", ok ? "loaded" : "invalid/incompatible", w4.path,
            ok ? "" : "; CPU fallback, use a new calibration path");
}

static void w4_calib_save(void) {
    if (!w4.collecting || w4.failed || !w4.tokens) { return; }
    int head_start = w4_key.config.n_layers * BLOCK_MATRICES;
    for (int i = 0; i < w4.count; i++) {
        bool required = (i >= w4_key.first * BLOCK_MATRICES && i < w4_key.layers * BLOCK_MATRICES) || i >= head_start;
        if (required && !w4.ranges[i].samples) {
            fprintf(stderr, "nnapi: W4 calibration missing matrix %d; CPU fallback, file not saved\n", i);
            w4.failed = true;
            return;
        }
        if (required) {
            fprintf(stderr, "nnapi: W4 range matrix %d: input max %g, raw FC output max %g, samples %u\n",
                    i, w4.ranges[i].input, w4.ranges[i].output, w4.ranges[i].samples);
        }
    }
    size_t length = strlen(w4.path) + 40;
    char* temporary = malloc(length);
    if (!temporary) { w4.failed = true; return; }
    snprintf(temporary, length, "%s.tmp.%ld", w4.path, (long)getpid());
    int fd = open(temporary, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    size_t bytes = (size_t)w4.count * sizeof(W4Range);
    uint64_t checksum = w4_hash(0xcbf29ce484222325ULL, w4.ranges, bytes);
    bool ok = fd >= 0 && pwrite_all(fd, &w4_key, sizeof w4_key, 0)
        && pwrite_all(fd, w4.ranges, bytes, sizeof w4_key)
        && pwrite_all(fd, &checksum, sizeof checksum, sizeof w4_key + bytes);
    if (fd >= 0) { if (close(fd)) { ok = false; } }
    if (ok && rename(temporary, w4.path)) { ok = false; }
    if (!ok) { unlink(temporary); }
    free(temporary);
    w4.failed = !ok;
    w4.collecting = false;
    fprintf(stderr, "nnapi: W4 calibration %s after %d CPU tokens: %s%s\n", ok ? "saved" : "write failed",
            w4.tokens, w4.path, ok ? "; graphs start on next token" : "; CPU fallback");
}

static void w4_calib_token(void) {
    if (w4.collecting && !w4.failed && ++w4.tokens >= w4.target) { w4_calib_save(); }
}

static float w4_activation_scale(float maximum) {
    return fmaxf(maximum * w4.margin / 32767.0f, 1e-12f);
}

static int w4_observe(MatmulGraph* g, const float* x, const float* y) {
    if (g->w4_id < 0 || g->w4_id >= w4.count) { return -1; }
    W4Range* r = &w4.ranges[g->w4_id];
    for (int i = 0; i < g->n; i++) {
        if (!isfinite(x[i])) { return -1; }
        r->input = fmaxf(r->input, fabsf(x[i]));
    }
    for (int i = 0; i < g->d; i++) {
        if (!isfinite(y[i])) { return -1; }
        r->output = fmaxf(r->output, fabsf(y[i]));
    }
    r->hash = g->hash;
    r->samples++;
    return 0;
}

static size_t w4_broadcast(const W4Operand* input, const W4Operand* output, size_t at) {
    size_t result = 0, stride = 1;
    for (int axis = (int)output->rank - 1, src = (int)input->rank - 1; axis >= 0; axis--, src--) {
        size_t coordinate = at % output->dims[axis];
        at /= output->dims[axis];
        if (src >= 0) {
            if (input->dims[src] != 1) { result += coordinate * stride; }
            stride *= input->dims[src];
        }
    }
    return result;
}

static int w4_replay_op(W4Replay* replay, int op, const uint32_t* in, uint32_t out, float* data) {
    W4Operand* o = replay->operands;
    W4Operand *a = &o[in[0]], *z = &o[out];
    const float* x = a->data;
    if (!x) { return -1; }
    z->data = data;
    if (op == ANEURALNETWORKS_RESHAPE) { z->data = x; return 0; }
    if (op == ANEURALNETWORKS_ADD || op == ANEURALNETWORKS_MUL) {
        W4Operand* b = &o[in[1]];
        const float* y = b->data;
        if (!y) { return -1; }
        for (size_t i = 0; i < z->count; i++) {
            float u = x[w4_broadcast(a, z, i)], v = y[w4_broadcast(b, z, i)];
            data[i] = op == ANEURALNETWORKS_ADD ? u + v : u * v;
        }
    } else if (op == ANEURALNETWORKS_RSQRT || op == ANEURALNETWORKS_LOGISTIC) {
        for (size_t i = 0; i < z->count; i++) {
            data[i] = op == ANEURALNETWORKS_RSQRT ? 1.0f / sqrtf(x[i]) : 1.0f / (1.0f + expf(-x[i]));
        }
    } else if (op == ANEURALNETWORKS_MEAN) {
        if (*(const int32_t*)o[in[1]].data != -1) { return -1; }
        size_t width = a->dims[a->rank - 1];
        for (size_t i = 0; i < z->count; i++) {
            double sum = 0.0;
            for (size_t j = 0; j < width; j++) { sum += x[i * width + j]; }
            data[i] = (float)(sum / width);
        }
    } else if (op == ANEURALNETWORKS_SOFTMAX) {
        float beta = *(const float*)o[in[1]].data;
        size_t width = a->dims[a->rank - 1];
        for (size_t i = 0; i < z->count; i++) { data[i] = x[i] * beta; }
        for (size_t i = 0; i < z->count; i += width) { softmax(data + i, (int)width); }
    } else if (op == ANEURALNETWORKS_CONCATENATION) {
        W4Operand* b = &o[in[1]];
        if (a->rank != 3 || *(const int32_t*)o[in[2]].data != 1) { return -1; }
        size_t ac = (size_t)a->dims[1] * a->dims[2], bc = (size_t)b->dims[1] * b->dims[2];
        for (uint32_t batch = 0; batch < a->dims[0]; batch++) {
            memcpy(data + batch * (ac + bc), x + batch * ac, ac * sizeof(float));
            memcpy(data + batch * (ac + bc) + ac, (const float*)b->data + batch * bc, bc * sizeof(float));
        }
    } else if (op == ANEURALNETWORKS_BATCH_MATMUL) {
        W4Operand* b = &o[in[1]];
        const float* y = b->data;
        bool ta = *(const uint8_t*)o[in[2]].data, tb = *(const uint8_t*)o[in[3]].data;
        if (ta || a->rank != 3 || b->rank != 3 || !y) { return -1; }
        size_t m = a->dims[1], k = a->dims[2], n = tb ? b->dims[1] : b->dims[2];
        for (uint32_t batch = 0; batch < z->dims[0]; batch++) {
            for (size_t i = 0; i < m; i++) {
                for (size_t j = 0; j < n; j++) {
                    double sum = 0.0;
                    for (size_t c = 0; c < k; c++) {
                        sum += (double)x[batch * m * k + i * k + c]
                             * y[batch * k * n + (tb ? j * k + c : c * n + j)];
                    }
                    data[batch * m * n + i * n + j] = (float)sum;
                }
            }
        }
    } else { return -1; }
    return 0;
}
