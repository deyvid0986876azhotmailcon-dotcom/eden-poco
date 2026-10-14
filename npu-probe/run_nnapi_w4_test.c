// Run on a Linux CI runner or Android; no accelerator or real model is needed.
#define TESTING
#include "run_nnapi.c"
#include <assert.h>

static ANeuralNetworksOperandType types[64];
static uint32_t shapes[64][4];
static int operands, operations, codes[16];
static uint32_t fc_input[4], fc_output;

static int mock_operand(ANeuralNetworksModel* model, const ANeuralNetworksOperandType* type) {
    (void)model;
    assert(operands < 64 && type->dimensionCount <= 4);
    types[operands] = *type;
    if (type->dimensionCount) { memcpy(shapes[operands], type->dimensions, type->dimensionCount * sizeof(uint32_t)); }
    types[operands].dimensions = shapes[operands];
    operands++;
    return 0;
}

static int mock_value(ANeuralNetworksModel* model, int32_t at, const void* data, size_t size) {
    (void)model;
    if (types[at].type == NEURON_EXT_TENSOR_QUANT4_SYMM) {
        assert(size == (size_t)shapes[at][0] * shapes[at][1]);
        for (size_t i = 0; i < size; i++) { assert(((const int8_t*)data)[i] >= -7 && ((const int8_t*)data)[i] <= 7); }
    }
    return 0;
}

static int mock_op(ANeuralNetworksModel* model, int32_t op, uint32_t ni, const uint32_t* in,
                   uint32_t no, const uint32_t* out) {
    (void)model;
    assert(no == 1 && operations < 16);
    codes[operations++] = op;
    if (op == ANEURALNETWORKS_FULLY_CONNECTED) {
        assert(ni == 4);
        memcpy(fc_input, in, sizeof fc_input);
        fc_output = *out;
    }
    return 0;
}

static void clear_owner(BlockGraph* bg) {
    for (int i = 0; i < bg->n_owned; i++) { free(bg->owned[i]); }
    free(bg->owned);
    memset(bg, 0, sizeof *bg);
}

static void check_linear(MatmulGraph* g) {
    float x[32], bias[3] = {0.1f, -0.2f, 0.3f}, zeros[3] = {0};
    for (int i = 0; i < 32; i++) { x[i] = (i - 13) * 0.11f; }
    BlockGraph bg = {0};
    W4Replay replay = {.operands = calloc(BLOCK_MAX_OPS * 8, sizeof(W4Operand))};
    assert(replay.operands);
    BlockBuilder b = {.owner = &bg, .replay = &replay};
    uint32_t dims[] = {1, 32};
    uint32_t input = bb_tensor(&b, 2, dims);
    replay.operands[input].data = x;
    uint32_t output = bb_linear_gain(&b, &bg, input, g, 0.125f, bias, zeros, 0.5f);
    assert(!b.e);
    const float* actual = replay.operands[output].data;
    float largest = 0, expected_peak = 0;
    for (int i = 0; i < 3; i++) { largest = fmaxf(largest, g->row_scale[i]); }
    for (int i = 0; i < 3; i++) {
        double dot = 0;
        for (int j = 0; j < 32; j++) { dot += ((int8_t*)g->w8)[i * 32 + j] * (double)x[j]; }
        double expected = (dot * g->row_scale[i] / 127.0 + bias[i]) * 0.125;
        assert(fabs(actual[i] - expected) < 1e-5 * (1 + fabs(expected)));
        expected_peak = fmaxf(expected_peak, fabsf((float)(dot * (largest / 127.0f) * 0.5f)));
    }
    assert(fabsf(w4.ranges[0].output - expected_peak) < 1e-6f);
    assert(fabsf(w4.ranges[0].input - 1.98f) < 1e-6f);
    clear_owner(&bg);
    free(replay.operands);

    block_api.Model_addOperand = mock_operand;
    block_api.Model_setOperandValue = mock_value;
    block_api.Model_addOperation = mock_op;
    for (int mode = 0; mode < 3; mode++) {
        w4.enabled = mode != 0; w4.island = mode == 2;
        operands = operations = 0;
        b = (BlockBuilder){.owner = &bg};
        input = bb_tensor(&b, 2, dims);
        bb_linear_gain(&b, &bg, input, g, 0.125f, NULL, zeros, 0.5f);
        assert(!b.e);
        assert(types[fc_input[1]].type == (mode ? NEURON_EXT_TENSOR_QUANT4_SYMM : NEURON_TENSOR_QUANT8_SYMM));
        if (mode == 2) {
            assert(operations == 4 && codes[0] == NEURON_QUANTIZE && codes[1] == ANEURALNETWORKS_FULLY_CONNECTED);
            assert(codes[2] == ANEURALNETWORKS_DEQUANTIZE && codes[3] == ANEURALNETWORKS_MUL);
            assert(types[fc_input[0]].type == NEURON_TENSOR_QUANT16_SYMM);
            assert(types[fc_input[2]].type == ANEURALNETWORKS_TENSOR_INT32);
            assert(types[fc_input[2]].scale == types[fc_input[0]].scale * types[fc_input[1]].scale);
            assert(types[fc_output].scale == w4_activation_scale(w4.ranges[0].output));
        } else {
            assert(operations == 2 && codes[0] == ANEURALNETWORKS_FULLY_CONNECTED && codes[1] == ANEURALNETWORKS_MUL);
            assert(types[fc_input[0]].type == ANEURALNETWORKS_TENSOR_FLOAT32);
        }
        clear_owner(&bg);
    }
}

static void check_calibration(const char* checkpoint) {
    free(w4.ranges);
    memset(&w4, 0, sizeof w4);
    memset(&w4_key, 0, sizeof w4_key);
    Transformer t = {.config = {.dim = 32, .hidden_dim = 32, .n_layers = 1,
                                .n_heads = 2, .n_kv_heads = 1, .vocab_size = 10, .seq_len = 8}};
    char path[128];
    snprintf(path, sizeof path, "%s.calib", checkpoint);
    nn.state = 1;
    blk.state = 1; blk.first = 0; blk.layers = 1; blk.per_graph = 1; blk.T = 7; blk.norm = 64;
    blk.renorm = true;
    w4.enabled = w4.island = true; w4.path = path; w4.target = 1; w4.margin = 2;
    unsetenv("NNAPI_HEAD_CHUNK");
    w4_calib_setup(&t);
    assert(w4.collecting && !w4.failed && w4.count == 7);
    for (int i = 0; i < 7; i++) {
        w4.ranges[i] = (W4Range){.input = i + 1, .output = i + 2, .hash = (uint64_t)i, .samples = 1};
    }
    w4_calib_token();
    assert(!w4.collecting && !w4.failed);
    free(w4.ranges); w4.ranges = NULL; w4.initialized = false;
    w4.margin = 3; // Raw ranges allow changing the margin without recalibration.
    w4_calib_setup(&t);
    assert(!w4.collecting && !w4.failed && w4.ranges[6].output == 8);
    free(w4.ranges); w4.ranges = NULL; w4.initialized = false;
    blk.safe_norm = true;
    w4_calib_setup(&t);
    assert(w4.failed); // RESCALE=3 cannot silently reuse RESCALE=2 ranges.
    free(w4.ranges); w4.ranges = NULL;
    unlink(path);
}

static void check_replay_ops(void) {
    BlockGraph bg = {0};
    W4Replay replay = {.operands = calloc(BLOCK_MAX_OPS * 8, sizeof(W4Operand))};
    assert(replay.operands);
    BlockBuilder b = {.owner = &bg, .replay = &replay};
    uint32_t qd[] = {2, 1, 2}, kd[] = {2, 2, 2};
    float q[] = {1, 2, 3, 4}, k[] = {1, 0, 0, 2, 2, 1, 1, 0};
    float v[] = {1, 2, 3, 4, 5, 6, 7, 8};
    uint32_t query = bb_const(&b, 3, qd, q, 4), keys = bb_const(&b, 3, kd, k, 8);
    uint32_t score = bb_matmul(&b, query, keys, true, qd);
    const float expected_score[] = {1, 4, 10, 3};
    assert(!memcmp(replay.operands[score].data, expected_score, sizeof expected_score));
    uint32_t values = bb_const(&b, 3, kd, v, 8);
    uint32_t mixed = bb_matmul(&b, score, values, false, qd);
    const float expected_mixed[] = {13, 18, 71, 84};
    assert(!memcmp(replay.operands[mixed].data, expected_mixed, sizeof expected_mixed));
    uint32_t maskd[] = {1, 1, 2};
    float mask[] = {2, -3};
    uint32_t masked = bb_add(&b, score, bb_const(&b, 3, maskd, mask, 2), 3, qd);
    const float expected_masked[] = {3, 1, 12, 0};
    assert(!memcmp(replay.operands[masked].data, expected_masked, sizeof expected_masked));
    uint32_t catd[] = {2, 3, 2}, cat_in[] = {keys, query, bb_int(&b, 1)};
    uint32_t cat = bb_op(&b, ANEURALNETWORKS_CONCATENATION, 3, cat_in, bb_tensor(&b, 3, catd));
    const float expected_cat[] = {1, 0, 0, 2, 1, 2, 2, 1, 1, 0, 3, 4};
    assert(!memcmp(replay.operands[cat].data, expected_cat, sizeof expected_cat));
    float weights[] = {0.5f, 2.0f, 1.5f, 1.0f};
    uint32_t flatd[] = {1, 4};
    uint32_t flat = bb_reshape(&b, query, 2, flatd);
    blk.scaled_eps = true;
    for (int safe = 0; safe <= 1; safe++) {
        blk.safe_norm = safe != 0;
        // Match the epsilon supplied by blocks_run for the selected input units.
        uint32_t epsilon = bb_number(&b, safe ? 0.25f / 4096.0f : 0.25f);
        uint32_t normalized = bb_rmsnorm(&b, flat, weights, 4, epsilon);
        const float* data = replay.operands[normalized].data;
        for (int i = 0; i < 4; i++) {
            float expected = q[i] * weights[i] / sqrtf(7.5f + 0.25f);
            assert(fabsf(data[i] - expected) < 1e-6f);
        }
    }
    assert(!b.e);
    blk.safe_norm = blk.scaled_eps = false;
    clear_owner(&bg);
    free(replay.operands);
}

int main(void) {
    char checkpoint[] = "w4-test-XXXXXX";
    int fd = mkstemp(checkpoint);
    assert(fd >= 0 && write(fd, "checkpoint", 10) == 10);
    close(fd);
    nn.checkpoint = checkpoint;
    nn.neuron = true;
    w4.enabled = true; w4.margin = 2; w4.count = 1;
    w4.ranges = calloc(1, sizeof(W4Range));
    assert(w4.ranges);
    setenv("NNAPI_WEIGHTS_FILE", checkpoint, 1);
    char path[128], original[128];
    snprintf(path, sizeof path, "%s.w4.0", checkpoint);
    snprintf(original, sizeof original, "%s.0", checkpoint);
    fd = open(original, O_CREAT | O_WRONLY, 0600);
    assert(fd >= 0 && write(fd, "untouched", 9) == 9);
    close(fd);
    float values[96];
    for (int i = 0; i < 96; i++) { values[i] = i < 32 ? 0 : ((i * 17 % 37) - 18) * 0.003f; }
    values[95] = 0.7f; // outlier favors clipping in the candidate search
    Matrix matrix = {.f = values};
    MatmulGraph g = {.w = &matrix, .n = 32, .d = 3};
    assert(!prepare_weights_4bit(&g));
    for (int i = 0; i < 3; i++) {
        float maximum = 0;
        for (int j = 0; j < 32; j++) { maximum = fmaxf(maximum, fabsf(values[i * 32 + j])); }
        double mse = 0, baseline = 0;
        for (int j = 0; j < 32; j++) {
            float v = values[i * 32 + j];
            float quantized = ((int8_t*)g.w8)[i * 32 + j] * g.row_scale[i] / 127.0f;
            float max_only = maximum ? roundf(v * 7 / maximum) * maximum / 7 : 0;
            mse += (v - quantized) * (double)(v - quantized);
            baseline += (v - max_only) * (double)(v - max_only);
        }
        assert(mse <= baseline + 1e-9);
    }
    check_linear(&g);
    float first = values[0];
    values[0] = NAN; // A cache hit must not read the checkpoint matrix again.
    MatmulGraph cached = {.w = &matrix, .n = 32, .d = 3};
    assert(!prepare_weights_4bit(&cached));
    values[0] = first;
    assert(g.hash == cached.hash && !memcmp(g.w8, cached.w8, 96));
    assert(!memcmp(g.row_scale, cached.row_scale, 3 * sizeof(float)));
    fd = open(original, O_RDONLY);
    char sentinel[9];
    assert(fd >= 0 && read(fd, sentinel, 9) == 9 && !memcmp(sentinel, "untouched", 9));
    close(fd);
    int8_t q8[96];
    float scales[12];
    GS = 8;
    for (int i = 0; i < 96; i++) { q8[i] = (int8_t)((i * 19 % 255) - 127); }
    for (int i = 0; i < 12; i++) { scales[i] = i / 997.0f; }
    Matrix quantized = {.q = q8, .s = scales};
    MatmulGraph from_q8 = {.w = &quantized, .n = 32, .d = 3};
    assert(!prepare_weights_4bit(&from_q8)); // GS mismatch invalidates the previous file.
    for (int i = 0; i < 96; i++) { values[i] = q8[i] * scales[i / GS]; }
    unsetenv("NNAPI_WEIGHTS_FILE");
    MatmulGraph from_float = {.w = &matrix, .n = 32, .d = 3};
    assert(!prepare_weights_4bit(&from_float));
    assert(from_q8.hash == from_float.hash);
    free(from_q8.w8); free(from_q8.row_scale); free(from_float.w8); free(from_float.row_scale);
    free(g.w8); free(g.row_scale); free(cached.w8); free(cached.row_scale);
    check_replay_ops();
    check_calibration(checkpoint);
    unlink(path); unlink(original); unlink(checkpoint);
    puts("PASS: W4 MSE, signed bytes, cache, W8 isolation, raw ranges, hybrid/island graph ABI and row correction");
    report_speed();
    return 0;
}
