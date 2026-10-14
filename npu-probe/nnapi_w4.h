// W4 files contain one signed value per byte; Neuron packs the compiled network.
typedef struct {
    float input, output;
    uint64_t hash;
    uint32_t samples;
} W4Range;

static struct {
    bool enabled, island, collecting, failed, initialized;
    int tokens, target, count;
    float margin;
    const char* path;
    W4Range* ranges;
} w4;

static bool w4_options(void) {
    const char* bits = getenv("NNAPI_WEIGHT_BITS");
    if (!bits || strcmp(bits, "8") == 0) { return true; }
    if (strcmp(bits, "4") != 0 || !nn.neuron) {
        fprintf(stderr, "nnapi: NNAPI_WEIGHT_BITS requires 8, or 4 with NNAPI_BACKEND=neuron; CPU fallback\n");
        weight_format = "CPU (invalid weight options)";
        return false;
    }
    w4.enabled = true;
    const char* mode = getenv("NNAPI_W4_MODE");
    w4.island = mode && strcmp(mode, "island") == 0;
    if (mode && strcmp(mode, "hybrid") != 0 && !w4.island) {
        fprintf(stderr, "nnapi: NNAPI_W4_MODE must be hybrid or island; CPU fallback\n");
        return false;
    }
    weight_format = w4.island ? "4-bit island" : "4-bit hybrid";
    w4.margin = 2.0f;
    const char* margin = getenv("NNAPI_W4_MARGIN");
    if (margin) {
        char* end;
        w4.margin = strtof(margin, &end);
        if (*end || !isfinite(w4.margin) || w4.margin <= 0.0f) {
            fprintf(stderr, "nnapi: invalid NNAPI_W4_MARGIN; CPU fallback\n");
            return false;
        }
    }
    w4.target = 8;
    const char* tokens = getenv("NNAPI_W4_CALIB_TOKENS");
    if (tokens) {
        char* end;
        long n = strtol(tokens, &end, 10);
        if (*end || n < 1 || n > 4096) {
            fprintf(stderr, "nnapi: invalid NNAPI_W4_CALIB_TOKENS (1..4096); CPU fallback\n");
            return false;
        }
        w4.target = (int)n;
    }
    w4.path = getenv("NNAPI_W4_CALIB");
    if (w4.island && (!w4.path || !*w4.path)) {
        fprintf(stderr, "nnapi: island requires NNAPI_W4_CALIB=<file>; CPU fallback\n");
        return false;
    }
    fprintf(stderr, "nnapi: weights=%s, one signed nibble per byte\n", weight_format);
    return true;
}

static uint64_t w4_hash(uint64_t hash, const void* bytes, size_t n) {
    const uint8_t* p = bytes;
    for (size_t i = 0; i < n; i++) { hash = (hash ^ p[i]) * 0x100000001b3ULL; }
    return hash;
}

static int w4_round(float value) {
    if (value >= 7.0f) { return 7; }
    if (value <= -7.0f) { return -7; }
    return (int)roundf(value);
}

typedef struct {
    char magic[8];
    int64_t checkpoint_size, checkpoint_mtime;
    int32_t group_size, id, n, d;
    uint64_t hash;
    double relative;
} W4WeightsHeader;

static int prepare_weights_4bit(MatmulGraph* g) {
    size_t bytes = (size_t)g->n * g->d, scales = (size_t)g->d * sizeof(float);
    W4WeightsHeader key = {0}, saved;
    memcpy(key.magic, "nnw4mse1", 8);
    struct stat st;
    if (!nn.checkpoint || stat(nn.checkpoint, &st)) { return -1; }
    key.checkpoint_size = st.st_size;
    key.checkpoint_mtime = st.st_mtime;
    key.group_size = GS; key.id = g->w4_id; key.n = g->n; key.d = g->d;
    char path[PATH_MAX + 64], temporary[PATH_MAX + 96];
    const char* prefix = getenv("NNAPI_WEIGHTS_FILE");
    bool cached = prefix && *prefix && strlen(prefix) < PATH_MAX;
    if (cached) { snprintf(path, sizeof path, "%s.w4.%d", prefix, g->w4_id); }
    g->w8 = malloc(bytes);
    g->row_scale = malloc(scales);
    if (!g->w8 || !g->row_scale) { return -1; }
    int fd = cached ? open(path, O_RDONLY) : -1;
    bool loaded = fd >= 0 && pread_all(fd, &saved, sizeof saved, 0)
        && memcmp(&saved, &key, offsetof(W4WeightsHeader, hash)) == 0
        && isfinite(saved.relative) && saved.relative >= 0.0
        && pread_all(fd, g->w8, bytes, sizeof saved)
        && pread_all(fd, g->row_scale, scales, sizeof saved + bytes);
    if (fd >= 0) { close(fd); }
    if (loaded) {
        g->hash = w4_hash(w4_hash(0xcbf29ce484222325ULL, g->w8, bytes), g->row_scale, scales);
        loaded = g->hash == saved.hash;
    }
    double error = 0.0, magnitude = 0.0;
    if (!loaded) {
        float* row = malloc((size_t)g->n * sizeof(float));
        if (!row) { return -1; }
        for (int i = 0; i < g->d; i++) {
            float maximum = 0.0f;
            for (int j = 0; j < g->n; j++) {
                size_t at = (size_t)i * g->n + j;
                float v = g->w->f ? g->w->f[at] : g->w->q[at] * g->w->s[at / GS];
                if (!isfinite(v)) { free(row); return -1; }
                row[j] = v;
                maximum = fmaxf(maximum, fabsf(v));
            }
            double best_error = INFINITY;
            float best = 0.0f;
            for (int candidate = 14; candidate <= 20; candidate++) {
                float step = (maximum / 7.0f) * (candidate / 20.0f);
                double mse = 0.0;
                for (int j = 0; j < g->n; j++) {
                    int q = step > 0.0f ? w4_round(row[j] / step) : 0;
                    double diff = (double)row[j] - q * step;
                    mse += diff * diff;
                }
                if (mse < best_error) { best_error = mse; best = step; }
            }
            // Preserve the shared graph builder's row_scale / 127 convention.
            g->row_scale[i] = best * 127.0f;
            for (int j = 0; j < g->n; j++) {
                int q = best > 0.0f ? w4_round(row[j] / best) : 0;
                ((int8_t*)g->w8)[(size_t)i * g->n + j] = (int8_t)q;
                error += fabs((double)row[j] - q * best);
                magnitude += fabs((double)row[j]);
            }
        }
        free(row);
        g->hash = w4_hash(w4_hash(0xcbf29ce484222325ULL, g->w8, bytes), g->row_scale, scales);
        key.hash = g->hash;
        key.relative = magnitude > 0.0 ? error / magnitude : 0.0;
        saved = key;
        if (cached) {
            snprintf(temporary, sizeof temporary, "%s.tmp.%ld", path, (long)getpid());
            fd = open(temporary, O_WRONLY | O_CREAT | O_TRUNC, 0600);
            bool ok = fd >= 0 && pwrite_all(fd, &key, sizeof key, 0)
                && pwrite_all(fd, g->w8, bytes, sizeof key)
                && pwrite_all(fd, g->row_scale, scales, sizeof key + bytes);
            if (fd >= 0) { if (close(fd)) { ok = false; } }
            if (!ok || rename(temporary, path)) {
                fprintf(stderr, "nnapi: W4 cache write failed: %s; keeping RAM weights\n", path);
                unlink(temporary);
            }
        }
    }
    fprintf(stderr, "nnapi: W4 matrix %d (%dx%d): sum|W-W4|/sum|W| = %.6f%% (%s)\n",
            g->w4_id, g->d, g->n, 100.0 * saved.relative, loaded ? "cache" : "checkpoint MSE");
    return 0;
}
