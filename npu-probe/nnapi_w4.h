// W4 files contain one signed value per byte; Neuron packs the compiled network.
typedef struct {
    float input, output;
    uint64_t hash;
    uint32_t samples;
} W4Range;

static struct {
    bool enabled, island, collecting, failed, initialized;
    int group; // NNAPI_W4_GROUP: columns that share a scale in each row, 0 = the whole row
    // NNAPI_W4_MATS: which matrices of a layer get 4 bits, by their place in it (0 wq, 1 wk, 2 wv,
    // 3 wo, 4 w1, 5 w3, 6 w2); NULL = all of them. The rest keep 8 bits
    const char* mats;
    // NNAPI_W8_ISLAND=1: the 8-bit matrices also go in a 16-bit island, not as float activations with
    // int8 weights. On the phone the all-island 4-bit graph ran 1.5 times faster than the float one
    bool w8_island;
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
    const char* group = getenv("NNAPI_W4_GROUP");
    w4.group = group ? atoi(group) : 0;
    if (w4.group < 0 || (w4.group > 0 && !w4.island)) {
        fprintf(stderr, "nnapi: NNAPI_W4_GROUP needs NNAPI_W4_MODE=island and a positive size; CPU fallback\n");
        return false;
    }
    if (w4.group > 0) { weight_format = "4-bit island, grouped"; }
    const char* w8_island = getenv("NNAPI_W8_ISLAND");
    w4.w8_island = w8_island && atoi(w8_island) != 0 && w4.island;
    w4.mats = getenv("NNAPI_W4_MATS");
    if (w4.mats && *w4.mats) { weight_format = "4-bit island for some matrices, 8-bit the rest"; }
    else { w4.mats = NULL; }
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

// The columns of a group for a matrix of n columns: the fewest groups of at most w4.group columns
// that split n evenly
static int w4_group_cols(int n) {
    if (w4.group <= 0 || w4.group >= n) { return n; }
    for (int groups = (n + w4.group - 1) / w4.group; groups <= n; groups++) {
        if (n % groups == 0) { return n / groups; }
    }
    return n;
}

// 4-bit weights with a scale per row and per group of columns. One scale per row of 1536 or 8960
// weights lost 12 to 47 % of every matrix of Qwen 2.5 1.5B on the phone; the MDLA takes no block
// scales, so the graph does each group as a FULLY_CONNECTED of its own (bb_linear_gain). Not
// cached in files: it takes seconds and the cache format has no room for the group scales.
// row_scale keeps the largest scale of the row, so the code that reads it still sees the right size
static int prepare_weights_4bit_grouped(MatmulGraph* g) {
    int n = g->n, d = g->d, cols = w4_group_cols(n), groups = n / cols;
    g->group_cols = cols;
    g->w8 = malloc((size_t)n * d);
    g->row_scale = malloc((size_t)d * sizeof(float));
    g->group_scale = malloc((size_t)d * groups * sizeof(float));
    float* part = malloc((size_t)cols * sizeof(float));
    if (!g->w8 || !g->row_scale || !g->group_scale || !part) { free(part); return -1; }
    double error = 0.0, magnitude = 0.0;
    for (int i = 0; i < d; i++) {
        float row_largest = 0.0f;
        for (int k = 0; k < groups; k++) {
            float maximum = 0.0f;
            for (int j = 0; j < cols; j++) {
                size_t at = (size_t)i * n + (size_t)k * cols + j;
                float v = g->w->f ? g->w->f[at] : g->w->q[at] * g->w->s[at / GS];
                if (!isfinite(v)) { free(part); return -1; }
                part[j] = v;
                maximum = fmaxf(maximum, fabsf(v));
            }
            double best_error = INFINITY;
            float best = 0.0f;
            for (int candidate = 14; candidate <= 20; candidate++) {
                float step = (maximum / 7.0f) * (candidate / 20.0f);
                double mse = 0.0;
                for (int j = 0; j < cols; j++) {
                    int q = step > 0.0f ? w4_round(part[j] / step) : 0;
                    double diff = (double)part[j] - q * step;
                    mse += diff * diff;
                }
                if (mse < best_error) { best_error = mse; best = step; }
            }
            g->group_scale[(size_t)i * groups + k] = best;
            row_largest = fmaxf(row_largest, best);
            for (int j = 0; j < cols; j++) {
                int q = best > 0.0f ? w4_round(part[j] / best) : 0;
                ((int8_t*)g->w8)[(size_t)i * n + (size_t)k * cols + j] = (int8_t)q;
                error += fabs((double)part[j] - q * best);
                magnitude += fabs((double)part[j]);
            }
        }
        // the graph builder reads row_scale / 127 as one unit of the row
        g->row_scale[i] = row_largest * 127.0f;
    }
    free(part);
    g->hash = w4_hash(w4_hash(w4_hash(0xcbf29ce484222325ULL, g->w8, (size_t)n * d), g->row_scale,
                              (size_t)d * sizeof(float)),
                      g->group_scale, (size_t)d * groups * sizeof(float));
    fprintf(stderr, "nnapi: W4 matrix %d (%dx%d): %d groups of %d columns, sum|W-W4|/sum|W| = %.6f%% (grouped)\n",
            g->w4_id, d, n, groups, cols, 100.0 * (magnitude > 0.0 ? error / magnitude : 0.0));
    return 0;
}

static int prepare_weights_4bit(MatmulGraph* g) {
    if (w4.group > 0) { return prepare_weights_4bit_grouped(g); }
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
