// ----------------------------------------------------------------------------
// NNAPI backend for whole transformer layers
// Included by run_nnapi.c after its backend for single matmuls, which this one builds on.
//
// One graph per matmul is 7 graphs per layer. With Qwen 2.5 1.5B that is 197, and the driver of
// the Dimensity 8300 dies while it holds them (error 14, DEAD_OBJECT). Here a group of layers is
// a single graph: rmsnorm, wq wk wv with their biases, RoPE, attention over the keys and values
// of the positions before, wo, residual, rmsnorm, SwiGLU, w2, residual. npu_block_probe.c showed
// that a graph of this shape compiles and runs on mtk-mdla_shim. What it did not have is real
// weights, a real RoPE or the keys and values going from one token to the next.
// THIS FILE HAS NOT RUN ON THE PHONE YET: NNAPI_VERIFY=1 says how far it is from the plain C path.
//
// - Weights: the same 8-bit ones of the single matmuls (prepare_weights_8bit: one file per matrix
//   with NNAPI_WEIGHTS_FILE), turned into fp32 inside the graph with DEQUANTIZE. Every row keeps
//   its own scale, put back after the FULLY_CONNECTED.
// - Keys and values: a graph has no memory. Every execution gets the ones of T positions as
//   inputs and gives back the ones of this position, which are kept here. The position being
//   computed goes in one more slot at the end, so the graph is the same for every token, and a
//   mask leaves out the slots still empty. That makes the context T + 1 tokens: past it, these
//   layers go on in plain C, slowly.
// - RoPE: it is a rotation, so a product by a matrix. The one of each position is an input.
// - Half precision: the accelerator computes in fp16, where 256 squared is already infinite and
//   rmsnorm adds up squares. The residual goes in scaled to a length (L2 norm) of
//   NNAPI_BLOCK_NORM: the sum of its squares is then 4096 whatever the model does, and their
//   mean is not small either. The scale is an input too, to weigh what each layer adds to the
//   residual. rmsnorm itself doesn't depend on it. Inside a graph of several layers nobody
//   rescales: if the residual grows a lot from one layer to the next, use fewer per graph.
//
// NNAPI_BLOCKS=<n>        layers per graph, 0 = off. default: 4 with a checkpoint of gguf2bin.c
// NNAPI_BLOCK_T=<n>       positions kept, default 256
// NNAPI_BLOCK_LAYERS=<n>  only the first n layers go in graphs
// NNAPI_BLOCK_NORM=<f>    length of the residual at the input of a graph, default 64
// NNAPI_VERIFY=1          the CPU computes every group too and the difference is reported. The
//                         text is then the one of the CPU

enum { ANEURALNETWORKS_FLOAT32 = 0, ANEURALNETWORKS_BOOL = 6 };
enum {
    ANEURALNETWORKS_ADD = 0, ANEURALNETWORKS_CONCATENATION = 2, ANEURALNETWORKS_LOGISTIC = 14,
    ANEURALNETWORKS_MUL = 18, ANEURALNETWORKS_RESHAPE = 22, ANEURALNETWORKS_SOFTMAX = 25,
    ANEURALNETWORKS_MEAN = 31, ANEURALNETWORKS_RSQRT = 83, ANEURALNETWORKS_BATCH_MATMUL = 102,
};
#define BLOCK_MAX_LAYERS 8     // in one graph
#define BLOCK_MAX_OPS 1024
#define BLOCK_MATRICES 7       // of a layer: wq, wk, wv, wo, w1, w3, w2, in the order forward uses them
#define BLOCK_MASKED -10000.0f // added to the score of an empty slot: its weight after the softmax is 0
#define BLOCK_EPS 1e-5f        // of the rmsnorms: next to the squares of a residual scaled like that it is nothing

typedef struct {
    int first; // layer
    int count; // layers
    int state; // 0 = not built yet, 1 = ready, -1 = the device didn't take it: these layers run on the CPU
    ANeuralNetworksModel* model; // has to outlive the compilation
    ANeuralNetworksCompilation* compilation;
    float** owned; // constants made for this graph: they have to outlive the model
    int n_owned;
    long runs;
    // NNAPI_VERIFY
    double rel_sum; // of the relative error of each execution
    double rel_max;
    long verified;
} BlockGraph;

typedef struct {
    int state; // 0 = not set up yet, 1 = on, -1 = off
    int per_graph;
    int layers; // how many, from the first one, go in graphs
    int T;
    float norm; // length the residual is scaled to at the input of a graph
    BlockGraph* groups;
    int n_groups;
    int first_matrix; // index in nn.graphs of wq of layer 0. BLOCK_MATRICES per layer follow
    float* kc; // (layer, n_kv_heads, T, head_size) keys of the positions before
    float* vc; // and values
    float* zeros; // the bias FULLY_CONNECTED asks for
    // inputs and outputs of an execution
    float* x; // (dim,)
    float* y; // (dim,)
    float* k; // (per_graph, kv_dim) keys of this position, one row per layer of the graph
    float* v;
    float* rot; // (n_kv_heads, head_size, head_size) the rotation of RoPE for position rot_pos
    int rot_pos;
    float* mask; // (T + 1,)
    // NNAPI_VERIFY: what the graph gave for the group the CPU is computing again
    float* expect; // (dim,)
    BlockGraph* pending;
    double run_ms;
    long not_finite;
} BlockState;

static BlockState blk;

// ---- building a model, one operand and one operation at a time

typedef struct {
    ANeuralNetworksModel* model;
    uint32_t next; // index the next operand gets
    int e; // the first error of NNAPI, 0 = none so far
    uint32_t n_ops;
    uint8_t ops[BLOCK_MAX_OPS]; // which operation each one is, to name the ones a device rejects
} BlockBuilder;

static uint32_t bb_operand(BlockBuilder* b, int32_t type, uint32_t rank, const uint32_t* dims, float scale, int32_t zero) {
    ANeuralNetworksOperandType t = {type, rank, dims, scale, zero};
    int e = ANeuralNetworksModel_addOperand(b->model, &t);
    if (!b->e) { b->e = e; }
    return b->next++;
}

static uint32_t bb_tensor(BlockBuilder* b, uint32_t rank, const uint32_t* dims) {
    return bb_operand(b, ANEURALNETWORKS_TENSOR_FLOAT32, rank, dims, 0.0f, 0);
}

static uint32_t bb_value(BlockBuilder* b, uint32_t operand, const void* data, size_t size) {
    // up to 128 bytes are copied. more than that is referenced: data has to outlive the model
    int e = ANeuralNetworksModel_setOperandValue(b->model, (int32_t)operand, data, size);
    if (!b->e) { b->e = e; }
    return operand;
}

static uint32_t bb_int(BlockBuilder* b, int32_t value) {
    return bb_value(b, bb_operand(b, ANEURALNETWORKS_INT32, 0, NULL, 0.0f, 0), &value, sizeof value);
}

static uint32_t bb_float(BlockBuilder* b, float value) {
    return bb_value(b, bb_operand(b, ANEURALNETWORKS_FLOAT32, 0, NULL, 0.0f, 0), &value, sizeof value);
}

static uint32_t bb_bool(BlockBuilder* b, bool value) {
    uint8_t byte = value;
    return bb_value(b, bb_operand(b, ANEURALNETWORKS_BOOL, 0, NULL, 0.0f, 0), &byte, sizeof byte);
}

static uint32_t bb_ints(BlockBuilder* b, const int32_t* values, uint32_t n) {
    return bb_value(b, bb_operand(b, ANEURALNETWORKS_TENSOR_INT32, 1, &n, 0.0f, 0), values, n * sizeof *values);
}

static uint32_t bb_const(BlockBuilder* b, uint32_t rank, const uint32_t* dims, const float* data, size_t count) {
    return bb_value(b, bb_tensor(b, rank, dims), data, count * sizeof *data);
}

static uint32_t bb_op(BlockBuilder* b, int32_t type, uint32_t n_in, const uint32_t* in, uint32_t out) {
    int e = ANeuralNetworksModel_addOperation(b->model, type, n_in, in, 1, &out);
    if (!b->e) { b->e = e; }
    if (b->n_ops < BLOCK_MAX_OPS) { b->ops[b->n_ops] = (uint8_t)type; }
    b->n_ops++;
    return out;
}

static uint32_t bb_mul(BlockBuilder* b, uint32_t x, uint32_t y, uint32_t rank, const uint32_t* dims) {
    uint32_t in[] = {x, y, bb_int(b, 0)}; // the third input is the fused activation: none
    return bb_op(b, ANEURALNETWORKS_MUL, 3, in, bb_tensor(b, rank, dims));
}

static uint32_t bb_add(BlockBuilder* b, uint32_t x, uint32_t y, uint32_t rank, const uint32_t* dims) {
    uint32_t in[] = {x, y, bb_int(b, 0)};
    return bb_op(b, ANEURALNETWORKS_ADD, 3, in, bb_tensor(b, rank, dims));
}

static uint32_t bb_reshape(BlockBuilder* b, uint32_t x, uint32_t rank, const uint32_t* dims) {
    int32_t shape[4];
    for (uint32_t i = 0; i < rank; i++) { shape[i] = (int32_t)dims[i]; }
    uint32_t in[] = {x, bb_ints(b, shape, rank)};
    return bb_op(b, ANEURALNETWORKS_RESHAPE, 2, in, bb_tensor(b, rank, dims));
}

static uint32_t bb_matmul(BlockBuilder* b, uint32_t x, uint32_t y, bool transpose_y, const uint32_t* dims) {
    // batches of matrices of rank 3: x @ y, or x @ y^T
    uint32_t in[] = {x, y, bb_bool(b, false), bb_bool(b, transpose_y)};
    return bb_op(b, ANEURALNETWORKS_BATCH_MATMUL, 4, in, bb_tensor(b, 3, dims));
}

static const char* block_op_name(int op) {
    switch (op) {
        case ANEURALNETWORKS_ADD: return "ADD";
        case ANEURALNETWORKS_CONCATENATION: return "CONCATENATION";
        case ANEURALNETWORKS_DEQUANTIZE: return "DEQUANTIZE";
        case ANEURALNETWORKS_FULLY_CONNECTED: return "FULLY_CONNECTED";
        case ANEURALNETWORKS_LOGISTIC: return "LOGISTIC";
        case ANEURALNETWORKS_MUL: return "MUL";
        case ANEURALNETWORKS_RESHAPE: return "RESHAPE";
        case ANEURALNETWORKS_SOFTMAX: return "SOFTMAX";
        case ANEURALNETWORKS_MEAN: return "MEAN";
        case ANEURALNETWORKS_RSQRT: return "RSQRT";
        case ANEURALNETWORKS_BATCH_MATMUL: return "BATCH_MATMUL";
        default: return "?";
    }
}

static float* block_keep(BlockGraph* bg, size_t count) {
    // room for a constant of this graph, freed with it
    float* data = malloc(count * sizeof(float));
    bg->owned = realloc(bg->owned, (bg->n_owned + 1) * sizeof(float*));
    if (!data || !bg->owned) { fprintf(stderr, "malloc failed!\n"); exit(EXIT_FAILURE); }
    bg->owned[bg->n_owned++] = data;
    return data;
}

// ---- the pieces of a layer

static uint32_t bb_rmsnorm(BlockBuilder* b, uint32_t x, const float* weight, uint32_t dim) {
    // weight * x * rsqrt(mean(x * x) + eps), as rmsnorm() does
    uint32_t xd[] = {1, dim}, one[] = {1, 1}, single = 1;
    int32_t last_axis = -1;
    float eps = BLOCK_EPS;
    uint32_t squares = bb_mul(b, x, x, 2, xd);
    uint32_t mean_in[] = {squares, bb_ints(b, &last_axis, 1), bb_int(b, 1)}; // 1 = keep the dimension
    uint32_t mean = bb_op(b, ANEURALNETWORKS_MEAN, 3, mean_in, bb_tensor(b, 2, one));
    // ADD and MUL take tensors only: a number goes in as a tensor of one element and broadcasts
    uint32_t sum = bb_add(b, mean, bb_const(b, 1, &single, &eps, 1), 2, one);
    uint32_t inverse = bb_op(b, ANEURALNETWORKS_RSQRT, 1, &sum, bb_tensor(b, 2, one));
    uint32_t normalized = bb_mul(b, x, inverse, 2, xd);
    return bb_mul(b, normalized, bb_const(b, 1, &dim, weight, dim), 2, xd);
}

static uint32_t bb_linear(BlockBuilder* b, BlockGraph* bg, uint32_t x, MatmulGraph* g, float scale, const float* bias) {
    // (W @ x + bias) * scale, with x of shape (1,n) and the result (1,d).
    // W comes as the 8-bit weights of prepare_weights_8bit: row i is m[i] * q, with q in
    // [-127,127] stored as q + 128, and g->row_scale[i] is 127 * m[i]. The tensor takes the
    // largest m as its scale, so no output of the FULLY_CONNECTED is smaller than the real one
    // (small numbers are what fp16 loses), and what each row is short of is put back after it
    uint32_t d = (uint32_t)g->d, n = (uint32_t)g->n;
    uint32_t wd[] = {d, n}, bd[] = {d}, od[] = {1, d};
    float largest = 0.0f;
    for (uint32_t i = 0; i < d; i++) {
        if (g->row_scale[i] > largest) { largest = g->row_scale[i]; }
    }
    float tensor_scale = largest > 0.0f ? largest / 127.0f : 1.0f;
    float* rows = block_keep(bg, d);
    for (uint32_t i = 0; i < d; i++) {
        rows[i] = g->row_scale[i] / 127.0f / tensor_scale * scale;
    }

    uint32_t w8 = bb_operand(b, ANEURALNETWORKS_TENSOR_QUANT8_ASYMM, 2, wd, tensor_scale, Q8_ZERO);
    int e = g->memory ? ANeuralNetworksModel_setOperandValueFromMemory(b->model, (int32_t)w8, g->memory, 0, (size_t)d * n)
                      : ANeuralNetworksModel_setOperandValue(b->model, (int32_t)w8, g->w8, (size_t)d * n);
    if (!b->e) { b->e = e; }
    uint32_t w = bb_op(b, ANEURALNETWORKS_DEQUANTIZE, 1, &w8, bb_tensor(b, 2, wd));
    uint32_t fc_in[] = {x, w, bb_const(b, 1, bd, blk.zeros, d), bb_int(b, 0)};
    uint32_t out = bb_op(b, ANEURALNETWORKS_FULLY_CONNECTED, 4, fc_in, bb_tensor(b, 2, od));
    out = bb_mul(b, out, bb_const(b, 2, od, rows, d), 2, od);
    if (bias) {
        float* scaled = block_keep(bg, d);
        for (uint32_t i = 0; i < d; i++) { scaled[i] = bias[i] * scale; }
        out = bb_add(b, out, bb_const(b, 2, od, scaled, d), 2, od);
    }
    return out;
}

static uint32_t bb_layer(BlockBuilder* b, BlockGraph* bg, Transformer* t, int l, uint32_t x, uint32_t alpha,
                         uint32_t rot, uint32_t mask, uint32_t kc, uint32_t vc, uint32_t* k_out, uint32_t* v_out) {
    // layer l, from the residual x to the residual after it. the same steps as forward()
    Config* p = &t->config;
    TransformerWeights* w = &t->weights;
    uint32_t D = (uint32_t)p->dim, F = (uint32_t)p->hidden_dim, T = (uint32_t)blk.T;
    uint32_t KVH = (uint32_t)p->n_kv_heads, HD = D / (uint32_t)p->n_heads, G = (uint32_t)p->n_heads / KVH;
    size_t KD = (size_t)KVH * HD;
    uint32_t xd[] = {1, D}, fd[] = {1, F};
    uint32_t qd[] = {KVH, G, HD};      // the queries, grouped by the key head they share
    uint32_t kd[] = {KVH, 1, HD};      // the key (or value) of this position
    uint32_t alld[] = {KVH, T + 1, HD}; // and with the ones before
    uint32_t attd[] = {KVH, G, T + 1};
    MatmulGraph* m = &nn.graphs[blk.first_matrix + BLOCK_MATRICES * l]; // wq, wk, wv, wo, w1, w3, w2
    // the scores are divided by sqrt(head_size): done on the queries, that is the same and keeps the products smaller
    float score_scale = 1.0f / sqrtf((float)HD);

    // attention rmsnorm and the qkv matmuls for this position
    uint32_t xn = bb_rmsnorm(b, x, w->rms_att_weight + (size_t)l * D, D);
    uint32_t q = bb_linear(b, bg, xn, &m[0], score_scale, w->bq ? w->bq + (size_t)l * D : NULL);
    uint32_t k = bb_linear(b, bg, xn, &m[1], 1.0f, w->bk ? w->bk + (size_t)l * KD : NULL);
    uint32_t v = bb_linear(b, bg, xn, &m[2], 1.0f, w->bv ? w->bv + (size_t)l * KD : NULL);
    q = bb_reshape(b, q, 3, qd);
    k = bb_reshape(b, k, 3, kd);
    v = bb_reshape(b, v, 3, kd);

    // RoPE: rotate q and k in each head
    q = bb_matmul(b, q, rot, false, qd);
    k = bb_matmul(b, k, rot, false, kd);

    // multihead attention over the positions before and this one, that goes last
    uint32_t keys_in[] = {kc, k, bb_int(b, 1)}; // along axis 1
    uint32_t keys = bb_op(b, ANEURALNETWORKS_CONCATENATION, 3, keys_in, bb_tensor(b, 3, alld));
    uint32_t values_in[] = {vc, v, bb_int(b, 1)};
    uint32_t values = bb_op(b, ANEURALNETWORKS_CONCATENATION, 3, values_in, bb_tensor(b, 3, alld));
    uint32_t scores = bb_matmul(b, q, keys, true, attd);
    scores = bb_add(b, scores, mask, 3, attd);
    uint32_t softmax_in[] = {scores, bb_float(b, 1.0f)}; // beta
    uint32_t weights = bb_op(b, ANEURALNETWORKS_SOFTMAX, 2, softmax_in, bb_tensor(b, 3, attd));
    uint32_t mixed = bb_matmul(b, weights, values, false, qd);
    mixed = bb_reshape(b, mixed, 2, xd);

    // final matmul to get the output of the attention, and the residual connection
    uint32_t attended = bb_linear(b, bg, mixed, &m[3], 1.0f, NULL);
    uint32_t h = bb_add(b, x, bb_mul(b, attended, alpha, 2, xd), 2, xd);

    // ffn rmsnorm and self.w2(F.silu(self.w1(x)) * self.w3(x))
    uint32_t hn = bb_rmsnorm(b, h, w->rms_ffn_weight + (size_t)l * D, D);
    uint32_t gate = bb_linear(b, bg, hn, &m[4], 1.0f, NULL);
    uint32_t up = bb_linear(b, bg, hn, &m[5], 1.0f, NULL);
    uint32_t sigmoid = bb_op(b, ANEURALNETWORKS_LOGISTIC, 1, &gate, bb_tensor(b, 2, fd));
    uint32_t silu = bb_mul(b, gate, sigmoid, 2, fd);
    uint32_t hidden = bb_mul(b, silu, up, 2, fd);
    uint32_t down = bb_linear(b, bg, hidden, &m[6], 1.0f, NULL);

    *k_out = k;
    *v_out = v;
    return bb_add(b, h, bb_mul(b, down, alpha, 2, xd), 2, xd);
}

// ---- a graph: building it, compiling it, running it

static int blocks_build(Transformer* t, BlockGraph* bg, BlockBuilder* b) {
    // inputs: residual (1,dim), its scale (1,), rotation of RoPE, mask and, per layer, keys and values before.
    // outputs: residual and, per layer, key and value of this position
    Config* p = &t->config;
    uint32_t D = (uint32_t)p->dim, T = (uint32_t)blk.T;
    uint32_t KVH = (uint32_t)p->n_kv_heads, HD = D / (uint32_t)p->n_heads;
    uint32_t xd[] = {1, D}, single = 1, rotd[] = {KVH, HD, HD}, maskd[] = {1, 1, T + 1}, cached[] = {KVH, T, HD};
    uint32_t in[4 + 2 * BLOCK_MAX_LAYERS], out[1 + 2 * BLOCK_MAX_LAYERS];
    uint32_t kc[BLOCK_MAX_LAYERS], vc[BLOCK_MAX_LAYERS], k[BLOCK_MAX_LAYERS], v[BLOCK_MAX_LAYERS];
    uint32_t n_in = 0, n_out = 0;

    memset(b, 0, sizeof *b);
    int e = ANeuralNetworksModel_create(&b->model);
    if (e || !b->model) { return e ? e : -1; }
    uint32_t x = bb_tensor(b, 2, xd);
    uint32_t alpha = bb_tensor(b, 1, &single);
    uint32_t rot = bb_tensor(b, 3, rotd);
    uint32_t mask = bb_tensor(b, 3, maskd);
    in[n_in++] = x;
    in[n_in++] = alpha;
    in[n_in++] = rot;
    in[n_in++] = mask;
    for (int i = 0; i < bg->count; i++) {
        kc[i] = bb_tensor(b, 3, cached);
        vc[i] = bb_tensor(b, 3, cached);
        in[n_in++] = kc[i];
        in[n_in++] = vc[i];
    }
    for (int i = 0; i < bg->count; i++) {
        x = bb_layer(b, bg, t, bg->first + i, x, alpha, rot, mask, kc[i], vc[i], &k[i], &v[i]);
    }
    out[n_out++] = x;
    for (int i = 0; i < bg->count; i++) {
        out[n_out++] = k[i];
        out[n_out++] = v[i];
    }
    e = b->e;
    if (!e) { e = ANeuralNetworksModel_identifyInputsAndOutputs(b->model, n_in, in, n_out, out); }
    if (!e) { e = ANeuralNetworksModel_relaxComputationFloat32toFloat16(b->model, true); }
    if (!e) { e = ANeuralNetworksModel_finish(b->model); }
    if (e) {
        ANeuralNetworksModel_free(b->model);
        return e;
    }
    bg->model = b->model;
    return 0;
}

static int blocks_finish(BlockGraph* bg, const uint8_t* token) {
    const ANeuralNetworksDevice* const one[1] = {nn.device};
    int e = ANeuralNetworksCompilation_createForDevices(bg->model, one, 1, &bg->compilation);
    if (!e && token) { e = ANeuralNetworksCompilation_setCaching(bg->compilation, nn.cache_dir, token); }
    if (!e) { e = ANeuralNetworksCompilation_finish(bg->compilation); }
    if (e && bg->compilation) {
        ANeuralNetworksCompilation_free(bg->compilation);
        bg->compilation = NULL;
    }
    return e;
}

static void blocks_compile(Transformer* t, BlockGraph* bg) {
    static bool takes[BLOCK_MAX_OPS];
    BlockBuilder b;
    double start = now_ms();
    bg->state = -1;
    int e = blocks_build(t, bg, &b);
    if (!e && b.n_ops <= BLOCK_MAX_OPS) {
        // restricted to one device NNAPI has no CPU to fall back to: one operation the
        // accelerator doesn't take and the whole graph fails. this says which
        const ANeuralNetworksDevice* const one[1] = {nn.device};
        int rejected = 0;
        if (ANeuralNetworksModel_getSupportedOperationsForDevices(bg->model, one, 1, takes) == 0) {
            for (uint32_t i = 0; i < b.n_ops; i++) {
                if (takes[i]) { continue; }
                if (rejected++ < 8) {
                    fprintf(stderr, "nnapi: blocks: %s does not take operation %u, a %s\n",
                            nn.device_name, i, block_op_name(b.ops[i]));
                }
            }
        }
        if (rejected) { e = -1; }
    }
    if (!e) {
        // the token of the compilation cache: the weights in the graph, the layers and the positions kept
        uint8_t token[CACHE_TOKEN_SIZE];
        bool cached = nn.cache_dir[0] != '\0';
        uint64_t hash = 0xcbf29ce484222325ULL;
        for (int i = 0; i < BLOCK_MATRICES * bg->count; i++) {
            hash = (hash ^ nn.graphs[blk.first_matrix + BLOCK_MATRICES * bg->first + i].hash) * 0x100000001b3ULL;
        }
        memset(token, 0, CACHE_TOKEN_SIZE);
        memcpy(token, "blocks-1", 8);
        memcpy(token + 12, &blk.T, sizeof blk.T);
        memcpy(token + 16, &hash, sizeof hash);
        memcpy(token + 24, &bg->first, sizeof bg->first);
        memcpy(token + 28, &bg->count, sizeof bg->count);
        e = blocks_finish(bg, cached ? token : NULL);
        if (e && cached) {
            // a driver that cannot use the cache files fails the whole compilation
            e = blocks_finish(bg, NULL);
        }
    }
    nn.compile_ms += now_ms() - start;
    if (e) {
        fprintf(stderr, "nnapi: blocks: layers %d to %d did not compile for %s (error %d), they stay on the CPU\n",
                bg->first, bg->first + bg->count - 1, nn.device_name, e);
        return;
    }
    bg->state = 1;
    fprintf(stderr, "nnapi: blocks: layers %d to %d compiled for %s, %u operations, %.0f ms\n",
            bg->first, bg->first + bg->count - 1, nn.device_name, b.n_ops, now_ms() - start);
}

static void blocks_setup(Transformer* t) {
    Config* p = &t->config;
    TransformerWeights* w = &t->weights;
    blk.state = -1;
    // by default, on for the checkpoints of gguf2bin.c: those are the models with more matrices than
    // the driver holds as single graphs
    const char* want = getenv("NNAPI_BLOCKS");
    int per_graph = want ? atoi(want) : (t->extras.magic == EXTRAS_MAGIC ? 4 : 0);
    if (per_graph <= 0) { return; }
    if (nn.state == 0) { nnapi_setup(); }
    if (nn.state < 0) { return; }
    if (p->dim % p->n_heads != 0 || p->n_heads % p->n_kv_heads != 0 || (p->dim / p->n_heads) % 2 != 0) {
        fprintf(stderr, "nnapi: blocks: not for a model with these heads, off\n");
        return;
    }
    int dim = p->dim, hidden = p->hidden_dim, head_size = dim / p->n_heads;
    int kv_dim = head_size * p->n_kv_heads;
    const char* positions = getenv("NNAPI_BLOCK_T");
    const char* some_layers = getenv("NNAPI_BLOCK_LAYERS");
    const char* norm = getenv("NNAPI_BLOCK_NORM");
    blk.per_graph = per_graph < BLOCK_MAX_LAYERS ? per_graph : BLOCK_MAX_LAYERS;
    blk.T = positions ? atoi(positions) : 256;
    if (blk.T < 1) { blk.T = 1; }
    if (blk.T > p->seq_len - 1) { blk.T = p->seq_len - 1; }
    blk.layers = some_layers ? atoi(some_layers) : p->n_layers;
    if (blk.layers > p->n_layers) { blk.layers = p->n_layers; }
    if (blk.layers < 1 || blk.T < 1) { return; }
    blk.norm = norm ? (float)atof(norm) : 64.0f;
    if (!(blk.norm > 0.0f)) { blk.norm = 64.0f; }
    blk.rot_pos = -1;

    // the graphs take 8-bit weights and turn them into fp32 themselves, and so does from now on
    // the one of any matmul left out of them (the classifier)
    if (nn.q8 == Q8_OFF) {
        nn.q8 = Q8_DEQUANT;
        weights_files_setup();
    }
    nn.q8 = Q8_DEQUANT;
    if (nn.weights_path[0] == '\0') {
        fprintf(stderr, "nnapi: blocks: without NNAPI_WEIGHTS_FILE the 8-bit weights of every layer stay in RAM\n");
    }

    // the 8-bit weights of every matrix that goes in a graph. they get their place among the
    // ones of the single matmuls, in the order forward would have asked for them: so the files
    // of weights are the same ones, and matmul() finds these matrices without a graph of
    // their own and computes them on the CPU when it has to
    double start = now_ms();
    nn.graphs = realloc(nn.graphs, (nn.n_graphs + BLOCK_MATRICES * blk.layers) * sizeof(MatmulGraph));
    if (!nn.graphs) { fprintf(stderr, "malloc failed!\n"); exit(EXIT_FAILURE); }
    blk.first_matrix = nn.n_graphs;
    for (int l = 0; l < blk.layers; l++) {
        Matrix* matrices[BLOCK_MATRICES] = {w->wq + l, w->wk + l, w->wv + l, w->wo + l, w->w1 + l, w->w3 + l, w->w2 + l};
        int ns[BLOCK_MATRICES] = {dim, dim, dim, dim, dim, dim, hidden};
        int ds[BLOCK_MATRICES] = {dim, kv_dim, kv_dim, dim, hidden, hidden, dim};
        for (int i = 0; i < BLOCK_MATRICES; i++) {
            MatmulGraph* g = &nn.graphs[nn.n_graphs++];
            memset(g, 0, sizeof(MatmulGraph));
            g->w = matrices[i];
            g->n = ns[i];
            g->d = ds[i];
            if (prepare_weights_8bit(g) != 0) {
                fprintf(stderr, "nnapi: blocks: no 8-bit weights for a matrix of layer %d, off\n", l);
                return;
            }
        }
    }
    nn.weights_ms += now_ms() - start;

    size_t cache = (size_t)blk.layers * p->n_kv_heads * blk.T * head_size;
    blk.kc = calloc(cache, sizeof(float));
    blk.vc = calloc(cache, sizeof(float));
    blk.zeros = calloc(dim > hidden ? dim : hidden, sizeof(float));
    blk.x = malloc(dim * sizeof(float));
    blk.y = malloc(dim * sizeof(float));
    blk.expect = malloc(dim * sizeof(float));
    blk.k = malloc((size_t)blk.per_graph * kv_dim * sizeof(float));
    blk.v = malloc((size_t)blk.per_graph * kv_dim * sizeof(float));
    blk.rot = malloc((size_t)p->n_kv_heads * head_size * head_size * sizeof(float));
    blk.mask = malloc(((size_t)blk.T + 1) * sizeof(float));
    blk.n_groups = (blk.layers + blk.per_graph - 1) / blk.per_graph;
    blk.groups = calloc(blk.n_groups, sizeof(BlockGraph));
    if (!blk.kc || !blk.vc || !blk.zeros || !blk.x || !blk.y || !blk.expect || !blk.k || !blk.v
        || !blk.rot || !blk.mask || !blk.groups) {
        fprintf(stderr, "malloc failed!\n");
        exit(EXIT_FAILURE);
    }
    for (int i = 0; i < blk.n_groups; i++) {
        blk.groups[i].first = i * blk.per_graph;
        blk.groups[i].count = blk.layers - blk.groups[i].first < blk.per_graph ? blk.layers - blk.groups[i].first : blk.per_graph;
    }
    blk.state = 1;
    fprintf(stderr, "nnapi: blocks: %d layers in %d graphs on %s, %d positions kept\n",
            blk.layers, blk.n_groups, nn.device_name, blk.T);
}

static void blocks_position(Transformer* t, int pos) {
    // what every graph gets for this position, besides the residual
    Config* p = &t->config;
    int head_size = p->dim / p->n_heads;
    int half = head_size / 2;
    float base = t->extras.rope_base > 1.0f ? t->extras.rope_base : 10000.0f;
    // RoPE rotates the pair (a, b) of every head: a' = a cos - b sin, b' = a sin + b cos.
    // as a matrix that multiplies a row vector from the right, q' = q @ m
    float* m = blk.rot;
    memset(m, 0, (size_t)head_size * head_size * sizeof(float));
    for (int i = 0; i < half; i++) {
        float freq = 1.0f / powf(base, (2.0f * i) / (float)head_size);
        int a = 2 * i, b = 2 * i + 1; // neighbours, as in Llama
        if (t->extras.rope_halves) {
            a = i; // Qwen: the first half of the head with the second
            b = i + half;
        } else if (t->weights.rope_factors) {
            freq /= t->weights.rope_factors[i];
        }
        float val = pos * freq;
        float fcr = cosf(val);
        float fci = sinf(val);
        m[a * head_size + a] = fcr;
        m[b * head_size + a] = -fci;
        m[a * head_size + b] = fci;
        m[b * head_size + b] = fcr;
    }
    for (int h = 1; h < p->n_kv_heads; h++) {
        memcpy(m + (size_t)h * head_size * head_size, m, (size_t)head_size * head_size * sizeof(float));
    }
    // the slots of the positions before are full up to pos. the last one is this position
    for (int i = 0; i < blk.T; i++) {
        blk.mask[i] = i < pos ? 0.0f : BLOCK_MASKED;
    }
    blk.mask[blk.T] = 0.0f;
    blk.rot_pos = pos;
}

static int blocks_run(Transformer* t, BlockGraph* bg, float alpha) {
    // an execution is single use: what is kept between calls is the compilation
    Config* p = &t->config;
    size_t dim = p->dim, kvh = p->n_kv_heads, head_size = dim / p->n_heads;
    size_t kv_dim = kvh * head_size;
    size_t cache = kvh * blk.T * head_size; // of one layer
    ANeuralNetworksExecution* execution = NULL;
    int e = ANeuralNetworksExecution_create(bg->compilation, &execution);
    if (!e) { e = ANeuralNetworksExecution_setInput(execution, 0, NULL, blk.x, dim * sizeof(float)); }
    if (!e) { e = ANeuralNetworksExecution_setInput(execution, 1, NULL, &alpha, sizeof alpha); }
    if (!e) { e = ANeuralNetworksExecution_setInput(execution, 2, NULL, blk.rot, kvh * head_size * head_size * sizeof(float)); }
    if (!e) { e = ANeuralNetworksExecution_setInput(execution, 3, NULL, blk.mask, ((size_t)blk.T + 1) * sizeof(float)); }
    for (int i = 0; i < bg->count && !e; i++) {
        size_t at = (size_t)(bg->first + i) * cache;
        e = ANeuralNetworksExecution_setInput(execution, 4 + 2 * i, NULL, blk.kc + at, cache * sizeof(float));
        if (!e) { e = ANeuralNetworksExecution_setInput(execution, 5 + 2 * i, NULL, blk.vc + at, cache * sizeof(float)); }
    }
    if (!e) { e = ANeuralNetworksExecution_setOutput(execution, 0, NULL, blk.y, dim * sizeof(float)); }
    for (int i = 0; i < bg->count && !e; i++) {
        e = ANeuralNetworksExecution_setOutput(execution, 1 + 2 * i, NULL, blk.k + i * kv_dim, kv_dim * sizeof(float));
        if (!e) { e = ANeuralNetworksExecution_setOutput(execution, 2 + 2 * i, NULL, blk.v + i * kv_dim, kv_dim * sizeof(float)); }
    }
    if (!e) { e = ANeuralNetworksExecution_compute(execution); }
    if (execution) { ANeuralNetworksExecution_free(execution); }
    return e;
}

static void blocks_check(Transformer* t, int l) {
    // NNAPI_VERIFY: the CPU has just computed, up to layer l, the group whose result is in expect
    BlockGraph* bg = blk.pending;
    if (!bg || l != bg->first + bg->count) { return; }
    blk.pending = NULL;
    float* x = t->state.x;
    double err2 = 0.0;
    double ref2 = 0.0;
    for (int i = 0; i < t->config.dim; i++) {
        double err = (double)blk.expect[i] - x[i];
        err2 += err * err;
        ref2 += (double)x[i] * x[i];
    }
    if (ref2 > 0.0) {
        double rel = sqrt(err2 / ref2);
        if (rel > bg->rel_max) { bg->rel_max = rel; }
        bg->rel_sum += rel;
        bg->verified++;
    }
}

static int blocks_forward(Transformer* t, unsigned long long* layer, int pos) {
    // forward() calls it before every layer. if a graph starts at this layer, it runs here, the
    // residual in t->state.x is the one after its last layer, *layer is that one and the
    // result is 1. if it is 0 the layer is forward's to compute
    if (blk.state == 0) { blocks_setup(t); }
    int l = (int)*layer;
    blocks_check(t, l);
    if (blk.state < 0 || l >= blk.layers || l % blk.per_graph != 0 || pos > blk.T) { return 0; }
    BlockGraph* bg = &blk.groups[l / blk.per_graph];
    if (bg->state == 0) { blocks_compile(t, bg); }
    if (bg->state < 0) { return 0; }

    Config* p = &t->config;
    RunState* s = &t->state;
    int dim = p->dim, kvh = p->n_kv_heads, head_size = dim / p->n_heads;
    int kv_dim = kvh * head_size;
    if (blk.rot_pos != pos) { blocks_position(t, pos); }
    // the residual, scaled so that fp16 holds the sum of its squares
    double squares = 0.0;
    for (int i = 0; i < dim; i++) {
        squares += (double)s->x[i] * s->x[i];
    }
    float length = (float)sqrt(squares);
    float alpha = length > 0.0f ? blk.norm / length : 1.0f;
    for (int i = 0; i < dim; i++) {
        blk.x[i] = s->x[i] * alpha;
    }

    double start = now_ms();
    int e = blocks_run(t, bg, alpha);
    bool finite = true;
    for (int i = 0; i < dim && !e; i++) {
        if (!isfinite(blk.y[i])) { finite = false; }
    }
    for (int i = 0; i < bg->count * kv_dim && !e; i++) {
        if (!isfinite(blk.k[i]) || !isfinite(blk.v[i])) { finite = false; }
    }
    if (e || !finite) {
        // these layers are the CPU's from now on. it has the keys and values of the positions before
        if (e) {
            fprintf(stderr, "nnapi: blocks: execution of layers %d to %d failed (error %d), they stay on the CPU\n",
                    bg->first, bg->first + bg->count - 1, e);
        } else {
            fprintf(stderr, "nnapi: blocks: layers %d to %d gave values that are not finite at position %d "
                    "(residual of length %g): they stay on the CPU. fp16 overflow? try NNAPI_BLOCKS=1\n",
                    bg->first, bg->first + bg->count - 1, pos, length);
            blk.not_finite++;
        }
        ANeuralNetworksCompilation_free(bg->compilation);
        bg->compilation = NULL;
        bg->state = -1;
        return 0;
    }
    double elapsed = now_ms() - start;
    blk.run_ms += elapsed;
    bg->runs++;

    for (int i = 0; i < bg->count; i++) {
        float* k = blk.k + (size_t)i * kv_dim;
        float* v = blk.v + (size_t)i * kv_dim;
        if (pos < blk.T) {
            // the key and value of this position, for the positions to come
            size_t at = (size_t)(l + i) * kvh * blk.T * head_size;
            for (int h = 0; h < kvh; h++) {
                memcpy(blk.kc + at + ((size_t)h * blk.T + pos) * head_size, k + h * head_size, head_size * sizeof(float));
                memcpy(blk.vc + at + ((size_t)h * blk.T + pos) * head_size, v + h * head_size, head_size * sizeof(float));
            }
        }
        if (!nn.verify) {
            // and where the plain C path keeps them, for when it has to take these layers over
            size_t at = ((size_t)(l + i) * p->seq_len + pos) * kv_dim;
            memcpy(s->key_cache + at, k, kv_dim * sizeof(float));
            memcpy(s->value_cache + at, v, kv_dim * sizeof(float));
        }
    }
    if (nn.verify) {
        // the CPU computes these layers too, from the same residual, and blocks_check compares
        for (int i = 0; i < dim; i++) {
            blk.expect[i] = blk.y[i] / alpha;
        }
        blk.pending = bg;
        return 0;
    }
    for (int i = 0; i < dim; i++) {
        s->x[i] = blk.y[i] / alpha;
    }
    // counted as the matmuls they stand for, so that the report says where the model ran
    nn.nnapi_runs += BLOCK_MATRICES * bg->count;
    nn.run_ms += elapsed;
    *layer = (unsigned long long)(l + bg->count - 1);
    return 1;
}

static int blocks_matrices(void) {
    // how many matrices are inside a graph that compiled
    int n = 0;
    for (int i = 0; i < blk.n_groups; i++) {
        if (blk.groups[i].state == 1) { n += BLOCK_MATRICES * blk.groups[i].count; }
    }
    return n;
}

static void blocks_report(void) {
    if (blk.state <= 0) { return; }
    int ready = 0;
    long runs = 0;
    for (int i = 0; i < blk.n_groups; i++) {
        if (blk.groups[i].state == 1) { ready++; }
        runs += blk.groups[i].runs;
    }
    fprintf(stderr, "nnapi: blocks: %d of %d graphs of %d layers on %s, %ld executions at %.3f ms each\n",
            ready, blk.n_groups, blk.per_graph, nn.device_name, runs, runs ? blk.run_ms / runs : 0.0);
    if (blk.not_finite) {
        fprintf(stderr, "nnapi: blocks: %ld graphs dropped because their outputs were not finite\n", blk.not_finite);
    }
    for (int i = 0; i < blk.n_groups && nn.verify; i++) {
        BlockGraph* bg = &blk.groups[i];
        fprintf(stderr, "nnapi: blocks: verify: layers %d to %d: relative error %.2f%% on average, %.2f%% at worst, %ld executions\n",
                bg->first, bg->first + bg->count - 1,
                bg->verified ? 100.0 * bg->rel_sum / bg->verified : 0.0, 100.0 * bg->rel_max, bg->verified);
    }
}

static void blocks_free(void) {
    // before free_matmul: the models point at the weights it frees
    for (int i = 0; i < blk.n_groups; i++) {
        BlockGraph* bg = &blk.groups[i];
        if (bg->compilation) { ANeuralNetworksCompilation_free(bg->compilation); }
        if (bg->model) { ANeuralNetworksModel_free(bg->model); }
        for (int j = 0; j < bg->n_owned; j++) { free(bg->owned[j]); }
        free(bg->owned);
    }
    free(blk.groups);
    free(blk.kc);
    free(blk.vc);
    free(blk.zeros);
    free(blk.x);
    free(blk.y);
    free(blk.expect);
    free(blk.k);
    free(blk.v);
    free(blk.rot);
    free(blk.mask);
}
