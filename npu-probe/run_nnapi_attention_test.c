// Linux CI: exercises the actual graph builder with CPU replay against a
// separate double reference. Android --device: also compiles/times both layouts.
// No checkpoint, SDK, network, phone UI or model weights are needed.
#define TESTING
#include "run_nnapi.c"
#include <assert.h>

typedef struct {
    uint32_t kvh, group, hd, T;
    size_t counts[6];
    float* inputs[6]; // q, k, v, cached k, cached v, mask
    float* expected;
    float beta;
} AttentionCase;

static void make_case(AttentionCase* c, uint32_t kvh, uint32_t group, uint32_t hd,
                       uint32_t T, uint32_t position, float gain) {
    *c = (AttentionCase){.kvh = kvh, .group = group, .hd = hd, .T = T, .beta = 1.0f / gain,
        .counts = {(size_t)kvh * group * hd, (size_t)kvh * hd, (size_t)kvh * hd,
                   (size_t)kvh * T * hd, (size_t)kvh * T * hd, T + 1}};
    for (int j = 0; j < 6; j++) {
        c->inputs[j] = malloc(c->counts[j] * sizeof(float));
        assert(c->inputs[j]);
        for (size_t i = 0; i < c->counts[j]; i++) {
            // Different heads, caches and current token; nonzero masked slots.
            c->inputs[j][i] = sinf((float)(i * 13 + j * 19 + 1) * 0.17f) * (j + 1) * 0.31f;
        }
    }
    for (size_t i = 0; i < c->counts[0]; i++) { c->inputs[0][i] *= gain; }
    for (uint32_t i = 0; i <= T; i++) {
        c->inputs[5][i] = i < position || i == T ? 0.0f : BLOCK_MASKED * gain;
    }
    c->expected = calloc(c->counts[0], sizeof(float));
    double* scores = malloc((T + 1) * sizeof(double));
    assert(c->expected && scores);
    for (uint32_t head = 0; head < kvh * group; head++) {
        uint32_t kv = head / group;
        double largest = -INFINITY, total = 0;
        for (uint32_t token = 0; token <= T; token++) {
            const float* key = token == T ? c->inputs[1] + kv * hd
                : c->inputs[3] + ((size_t)kv * T + token) * hd;
            double score = c->inputs[5][token];
            for (uint32_t d = 0; d < hd; d++) { score += (double)c->inputs[0][head * hd + d] * key[d]; }
            scores[token] = score * c->beta;
            largest = fmax(largest, scores[token]);
        }
        for (uint32_t token = 0; token <= T; token++) { scores[token] = exp(scores[token] - largest); total += scores[token]; }
        for (uint32_t d = 0; d < hd; d++) {
            double result = 0;
            for (uint32_t token = 0; token <= T; token++) {
                float value = token == T ? c->inputs[2][kv * hd + d]
                    : c->inputs[4][((size_t)kv * T + token) * hd + d];
                result += scores[token] * value / total;
            }
            c->expected[head * hd + d] = (float)result;
        }
    }
    free(scores);
}

static uint32_t build_case(BlockBuilder* b, AttentionCase* c, uint32_t inputs[6]) {
    uint32_t dims[6][3] = {{c->kvh, c->group, c->hd}, {c->kvh, 1, c->hd}, {c->kvh, 1, c->hd},
        {c->kvh, c->T, c->hd}, {c->kvh, c->T, c->hd}, {1, 1, c->T + 1}};
    for (int i = 0; i < 6; i++) {
        inputs[i] = bb_tensor(b, 3, dims[i]);
        if (b->replay) { b->replay->operands[inputs[i]].data = c->inputs[i]; }
    }
    uint32_t all[] = {c->kvh, c->T + 1, c->hd};
    uint32_t ki[] = {inputs[3], inputs[1], bb_int(b, 1)};
    uint32_t vi[] = {inputs[4], inputs[2], bb_int(b, 1)};
    uint32_t keys = bb_op(b, ANEURALNETWORKS_CONCATENATION, 3, ki, bb_tensor(b, 3, all));
    uint32_t values = bb_op(b, ANEURALNETWORKS_CONCATENATION, 3, vi, bb_tensor(b, 3, all));
    return bb_attention_mix(b, inputs[0], keys, values, inputs[5], c->kvh, c->group, c->hd, c->T + 1, c->beta);
}

static double relative_error(AttentionCase* c, const float* actual, bool strict) {
    double error = 0, reference = 0;
    for (size_t i = 0; i < c->counts[0]; i++) {
        assert(isfinite(actual[i]));
        double delta = (double)actual[i] - c->expected[i];
        if (strict) { assert(fabs(delta) < 2e-5 * (1 + fabs(c->expected[i]))); }
        error += delta * delta;
        reference += (double)c->expected[i] * c->expected[i];
    }
    return sqrt(error / fmax(reference, 1e-30));
}

static void free_case(AttentionCase* c) {
    for (int i = 0; i < 6; i++) { free(c->inputs[i]); }
    free(c->expected);
}

static void check_replay(AttentionCase* c) {
    for (int mode = 0; mode < 2; mode++) {
        blk.attention_fc = mode != 0;
        BlockGraph bg = {0};
        W4Replay replay = {.operands = calloc(BLOCK_MAX_OPS * 8, sizeof(W4Operand))};
        assert(replay.operands);
        BlockBuilder b = {.owner = &bg, .replay = &replay};
        uint32_t inputs[6], output = build_case(&b, c, inputs);
        assert(!b.e);
        relative_error(c, replay.operands[output].data, true);
        neuron_constants_free(&bg);
        free(replay.operands);
    }
}

static int compare_double(const void* a, const void* b) {
    double x = *(const double*)a, y = *(const double*)b;
    return (x > y) - (x < y);
}

static bool check_device(AttentionCase* c, bool fc) {
    blk.attention_fc = fc;
    BlockGraph bg = {0};
    BlockBuilder b = {.owner = &bg};
    uint32_t inputs[6];
    int e = block_api.Model_create(&bg.model);
    if (!e && !bg.model) { e = -1; }
    b.model = bg.model;
    uint32_t output = 0;
    if (!e) { output = build_case(&b, c, inputs); e = b.e; }
    if (!e) { e = block_api.Model_identifyInputsAndOutputs(bg.model, 6, inputs, 1, &output); }
    if (!e) { e = block_api.Model_relaxComputationFloat32toFloat16(bg.model, true); }
    if (!e) { e = block_api.Model_finish(bg.model); }
    const ANeuralNetworksDevice* const device[] = {nn.device};
    bool takes[BLOCK_MAX_OPS];
    unsigned supported = 0;
    if (!e && b.n_ops > BLOCK_MAX_OPS) { e = -1; }
    if (!e) {
        e = block_api.Model_getSupportedOperationsForDevices(bg.model, device, 1, takes);
        if (!e) {
            for (uint32_t i = 0; i < b.n_ops; i++) {
                supported += takes[i];
                if (!takes[i]) { printf("REJECT %s op %u %s\n", fc ? "fc" : "bmm", i, block_op_name(b.ops[i])); }
            }
            if (supported != b.n_ops) { e = -1; }
        }
    }
    double start = now_ms();
    if (!e) { e = blocks_finish(&bg, NULL); }
    printf("ATTENTION %s T=%u KVH=%u G=%u HD=%u device=%s support=%u/%u compile_error=%d compile_ms=%.3f\n",
           fc ? "fc" : "bmm", c->T, c->kvh, c->group, c->hd, nn.device_name, supported, b.n_ops, e, now_ms() - start);
    float* result = calloc(c->counts[0], sizeof(float));
    assert(result);
    double times[40];
    // New execution each call, exactly like blocks_run. Include input/output
    // binding and copies; compile time and 8 warmups are excluded.
    for (int run = -8; run < 40 && !e; run++) {
        ANeuralNetworksExecution* execution = NULL;
        start = now_ms();
        e = block_api.Execution_create(bg.compilation, &execution);
        for (int i = 0; i < 6 && !e; i++) { e = block_api.Execution_setInput(execution, i, NULL, c->inputs[i], c->counts[i] * sizeof(float)); }
        if (!e) { e = block_api.Execution_setOutput(execution, 0, NULL, result, c->counts[0] * sizeof(float)); }
        if (!e) { e = block_api.Execution_compute(execution); }
        if (execution) { block_api.Execution_free(execution); }
        if (run >= 0) { times[run] = now_ms() - start; }
    }
    if (!e) {
        double relative = relative_error(c, result, false);
        qsort(times, 40, sizeof(double), compare_double);
        printf("ATTENTION %s T=%u min_ms=%.3f median_ms=%.3f relative_error=%.9g\n",
               fc ? "fc" : "bmm", c->T, times[0], (times[19] + times[20]) * 0.5, relative);
        if (relative > 0.01) { e = -1; } // Synthetic fixture; full-model VERIFY is still required.
    } else { printf("ATTENTION %s execution_error=%d\n", fc ? "fc" : "bmm", e); }
    free(result);
    if (bg.compilation) { block_api.Compilation_free(bg.compilation); }
    if (bg.model) { block_api.Model_free(bg.model); }
    neuron_constants_free(&bg);
    return e == 0;
}

int main(int argc, char** argv) {
    bool device = argc == 2 && strcmp(argv[1], "--device") == 0;
    if (argc > 1 && !device) { fprintf(stderr, "Usage: %s [--device]\n", argv[0]); return 2; }
    nn.neuron = true; // Own the constants in CPU replay too.
    const uint32_t configs[][4] = {{1, 1, 8, 1}, {1, 4, 16, 7}, {3, 1, 8, 5}, {2, 6, 128, 32}, {2, 6, 128, 256}};
    unsigned cases = 0;
    for (size_t i = 0; i < sizeof configs / sizeof configs[0]; i++) {
        uint32_t T = configs[i][3], positions[] = {0, 1, T / 2, T};
        for (int p = 0; p < 4; p++) {
            for (int scaled = 0; scaled < 2; scaled++) {
                AttentionCase c;
                make_case(&c, configs[i][0], configs[i][1], configs[i][2], T, positions[p], scaled ? 0.125f : 1.0f);
                check_replay(&c);
                free_case(&c);
                cases++;
            }
        }
    }
    printf("PASS: %u fixtures x 2 actual attention builders vs independent double reference (MHA/MQA/GQA, masks, current token, beta)\n", cases);
    if (!device) { return 0; }
    alarm(180); // Bound a stalled vendor compilation/execution, including dlopen.
    if (!blocks_api_neuron()) { return 1; }
    bool ok = true;
    // Alternate ordering across repetitions to reduce, not eliminate, thermal bias.
    for (int repetition = 0; repetition < 2; repetition++) {
        for (int context = 0; context < 2; context++) {
            uint32_t T = context ? 256 : 32;
            AttentionCase c;
            make_case(&c, 2, 6, 128, T, T / 2, 1.0f);
            for (int mode = 0; mode < 2; mode++) { if (!check_device(&c, (mode ^ repetition) != 0)) { ok = false; } }
            free_case(&c);
        }
    }
    dlclose(neuron_lib);
    alarm(0);
    return ok ? 0 : 1;
}
