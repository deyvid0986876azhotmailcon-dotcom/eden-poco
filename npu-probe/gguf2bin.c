/* Converts a Llama 2 GGUF quantized as Q8_0 (llama.cpp) into the Q8_0 checkpoint of llama2.c */
/* (version 2 of export.py), the one runq.c and run_nnapi.c read. Nothing is requantized: both */
/* formats hold int8 weights with one scaling factor per group, and the GGUF groups are of 32 */

#define _FILE_OFFSET_BITS 64
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <inttypes.h>
#include <string.h>
#include <errno.h>
#include <math.h>
#include <time.h>
#include <unistd.h>

#define die(...) do { fprintf(stderr, __VA_ARGS__); exit(EXIT_FAILURE); } while (0)

// ----------------------------------------------------------------------------
// Reading the GGUF

FILE* in;
char* in_path;
uint64_t in_size; // of the file, in bytes

void read_bytes(void* dst, size_t n) {
    if (fread(dst, 1, n, in) != n) { die("%s ends too soon: incomplete download?\n", in_path); }
}

uint32_t read_u32(void) { uint32_t v; read_bytes(&v, sizeof(v)); return v; }
uint64_t read_u64(void) { uint64_t v; read_bytes(&v, sizeof(v)); return v; }

void seek_to(uint64_t offset) {
    if (fseeko(in, (off_t)offset, SEEK_SET) != 0) { die("seek failed on %s\n", in_path); }
}

void skip_bytes(uint64_t n) {
    // short skips are read, so that the thousands of strings of the vocabulary don't cost a seek each
    char scratch[256];
    if (n <= sizeof(scratch)) { read_bytes(scratch, (size_t)n); return; }
    if (n > in_size || fseeko(in, (off_t)n, SEEK_CUR) != 0) { die("%s is not a valid GGUF\n", in_path); }
}

char* read_string(void) {
    // a string of GGUF: its length (uint64) and its bytes, with no terminator
    uint64_t len = read_u64();
    if (len > 65536) { die("%s is not a valid GGUF\n", in_path); }
    char* s = malloc(len + 1);
    if (!s) { die("malloc failed!\n"); }
    read_bytes(s, (size_t)len);
    s[len] = '\0';
    return s;
}

// value types of the metadata
enum { T_U8, T_I8, T_U16, T_I16, T_U32, T_I32, T_F32, T_BOOL, T_STRING, T_ARRAY, T_U64, T_I64, T_F64 };

size_t scalar_size(uint32_t type) {
    switch (type) {
        case T_U8: case T_I8: case T_BOOL: return 1;
        case T_U16: case T_I16: return 2;
        case T_U32: case T_I32: case T_F32: return 4;
        case T_U64: case T_I64: case T_F64: return 8;
        default: return 0; // a string, an array or something unknown
    }
}

double read_number(uint32_t type) {
    union { uint8_t u8; int8_t i8; uint16_t u16; int16_t i16; uint32_t u32; int32_t i32;
            uint64_t u64; int64_t i64; float f32; double f64; } v;
    read_bytes(&v, scalar_size(type));
    switch (type) {
        case T_U8: case T_BOOL: return v.u8;
        case T_I8: return v.i8;
        case T_U16: return v.u16;
        case T_I16: return v.i16;
        case T_U32: return v.u32;
        case T_I32: return v.i32;
        case T_F32: return v.f32;
        case T_U64: return (double)v.u64;
        case T_I64: return (double)v.i64;
        default: return v.f64;
    }
}

void skip_value(uint32_t type) {
    if (type == T_STRING) {
        skip_bytes(read_u64());
    } else if (type == T_ARRAY) {
        uint32_t item_type = read_u32();
        uint64_t n = read_u64();
        if (item_type == T_STRING || item_type == T_ARRAY) {
            for (uint64_t i = 0; i < n; i++) { skip_value(item_type); }
        } else {
            size_t size = scalar_size(item_type);
            if (size == 0 || n > in_size) { die("%s is not a valid GGUF\n", in_path); }
            skip_bytes(n * size);
        }
    } else {
        size_t size = scalar_size(type);
        if (size == 0) { die("%s has metadata of unknown type %u\n", in_path, type); }
        skip_bytes(size);
    }
}

// the metadata that is used here: the rest is skipped
enum { ARCHITECTURE, NAME, TOKENIZER, N_STRINGS };
const char* string_keys[N_STRINGS] = { "general.architecture", "general.name", "tokenizer.ggml.model" };
char* strings[N_STRINGS];

enum { ALIGNMENT, CONTEXT, DIM, LAYERS, HIDDEN_DIM, HEADS, KV_HEADS, ROPE_BASE, BOS, EOS, N_NUMBERS };
const char* number_keys[N_NUMBERS] = {
    "general.alignment", "llama.context_length", "llama.embedding_length", "llama.block_count",
    "llama.feed_forward_length", "llama.attention.head_count", "llama.attention.head_count_kv",
    "llama.rope.freq_base", "tokenizer.ggml.bos_token_id", "tokenizer.ggml.eos_token_id",
};
double numbers[N_NUMBERS];
int found[N_NUMBERS];

void read_metadata(uint64_t n_metadata) {
    for (uint64_t i = 0; i < n_metadata; i++) {
        char* key = read_string();
        uint32_t type = read_u32();
        int k;
        if (type == T_STRING) {
            for (k = 0; k < N_STRINGS && strcmp(key, string_keys[k]) != 0; k++) {}
            if (k < N_STRINGS) { strings[k] = read_string(); } else { skip_value(type); }
        } else if (scalar_size(type) > 0) {
            for (k = 0; k < N_NUMBERS && strcmp(key, number_keys[k]) != 0; k++) {}
            if (k < N_NUMBERS) { numbers[k] = read_number(type); found[k] = 1; } else { skip_value(type); }
        } else {
            skip_value(type);
        }
        free(key);
    }
}

// ggml types of the values of a tensor
#define GGML_F32 0
#define GGML_F16 1
#define GGML_Q8_0 8
#define QK 32 // values in a Q8_0 block of GGUF. it becomes the group size of the checkpoint

typedef struct {
    char* name;
    uint64_t ne[4];  // the shape, in the reverse order of PyTorch: ne[0] is the length of a row
    uint32_t type;
    uint64_t offset; // of its data, from the start of the file
} Tensor;

Tensor* tensors;
uint64_t n_tensors;

void read_tensors(uint64_t alignment) {
    tensors = calloc(n_tensors, sizeof(Tensor));
    if (!tensors) { die("malloc failed!\n"); }
    for (uint64_t i = 0; i < n_tensors; i++) {
        Tensor* t = &tensors[i];
        t->name = read_string();
        uint32_t n_dims = read_u32();
        if (n_dims > 4) { die("%s is not a valid GGUF\n", in_path); }
        for (uint32_t d = 0; d < 4; d++) { t->ne[d] = d < n_dims ? read_u64() : 1; }
        t->type = read_u32();
        t->offset = read_u64();
    }
    // the data starts at the next multiple of the alignment and the offsets are counted from there
    uint64_t data_start = ((uint64_t)ftello(in) + alignment - 1) / alignment * alignment;
    for (uint64_t i = 0; i < n_tensors; i++) { tensors[i].offset += data_start; }
}

Tensor* find_tensor(const char* name) {
    for (uint64_t i = 0; i < n_tensors; i++) {
        if (strcmp(tensors[i].name, name) == 0) { return &tensors[i]; }
    }
    return NULL;
}

// ----------------------------------------------------------------------------
// The tensors that go into the checkpoint, in its order

Tensor** plan;
int n_plan = 0;

void plan_tensor(const char* name, uint64_t row_length, uint64_t rows, int quantized) {
    // adds a tensor to the plan, after checking its shape, its type and that all its data is in the file
    Tensor* t = find_tensor(name);
    if (!t) { die("%s has no tensor %s\n", in_path, name); }
    if (t->ne[0] != row_length || t->ne[1] != rows || t->ne[2] != 1 || t->ne[3] != 1) {
        die("%s is %" PRIu64 "x%" PRIu64 ", expected %" PRIu64 "x%" PRIu64 "\n",
            name, t->ne[1], t->ne[0], rows, row_length);
    }
    uint64_t n = row_length * rows;
    uint64_t bytes;
    if (quantized) {
        if (t->type != GGML_Q8_0) {
            die("%s has ggml type %u and Q8_0 (8) is needed: get the Q8_0 file of the model\n", name, t->type);
        }
        bytes = n / QK * (2 + QK);
    } else {
        if (t->type != GGML_F32 && t->type != GGML_F16) {
            die("%s has ggml type %u, expected F32 (0) or F16 (1)\n", name, t->type);
        }
        bytes = n * (t->type == GGML_F32 ? 4 : 2);
    }
    if (t->offset > in_size || bytes > in_size - t->offset) {
        die("%s is past the end of %s: incomplete download?\n", name, in_path);
    }
    plan[n_plan++] = t;
}

void plan_layers(int n_layers, const char* part, uint64_t row_length, uint64_t rows, int quantized) {
    char name[64];
    for (int l = 0; l < n_layers; l++) {
        snprintf(name, sizeof(name), "blk.%d.%s.weight", l, part);
        plan_tensor(name, row_length, rows, quantized);
    }
}

// ----------------------------------------------------------------------------
// Writing the checkpoint

FILE* out;
char* tmp_path; // the checkpoint is written here and takes its name once it is complete
uint64_t written = 0;
float scale_min = INFINITY, scale_max = 0.0f;

void discard_output(void) {
    // runs at exit: a checkpoint that was left half written is deleted
    if (tmp_path) { remove(tmp_path); }
}

void write_bytes(const void* src, size_t n) {
    if (fwrite(src, 1, n, out) != n) { die("write failed on %s: %s\n", tmp_path, strerror(errno)); }
    written += n;
}

float half_to_float(uint16_t h) {
    // fp16 to fp32, which holds every fp16 value exactly
    uint32_t sign = (uint32_t)(h & 0x8000) << 16;
    uint32_t exponent = (h >> 10) & 0x1f;
    uint32_t mantissa = h & 0x3ff;
    uint32_t bits;
    if (exponent == 0x1f) { // inf or nan
        bits = 0x7f800000 | (mantissa << 13);
    } else if (exponent != 0) {
        bits = ((exponent + 112) << 23) | (mantissa << 13);
    } else if (mantissa != 0) { // subnormal: shift the mantissa up to its implicit 1
        exponent = 113;
        while (!(mantissa & 0x400)) { mantissa <<= 1; exponent--; }
        bits = (exponent << 23) | ((mantissa & 0x3ff) << 13);
    } else {
        bits = 0;
    }
    bits |= sign;
    float f;
    memcpy(&f, &bits, sizeof(f));
    return f;
}

void write_floats(Tensor* t) {
    // a 1D tensor (rmsnorm weights), that stays in fp32
    size_t n = t->ne[0];
    float* f = malloc(n * sizeof(float));
    if (!f) { die("malloc failed!\n"); }
    seek_to(t->offset);
    if (t->type == GGML_F32) {
        read_bytes(f, n * sizeof(float));
    } else {
        for (size_t i = 0; i < n; i++) {
            uint16_t h;
            read_bytes(&h, sizeof(h));
            f[i] = half_to_float(h);
        }
    }
    write_bytes(f, n * sizeof(float));
    free(f);
}

#define CHUNK 8192 // blocks read at a time

void write_q8(Tensor* t) {
    // a Q8_0 matrix. in the GGUF each block is its scaling factor (fp16) followed by its 32 int8
    // values. in the checkpoint all the int8 values go first and then all the factors, in fp32
    static uint8_t blocks[CHUNK * (2 + QK)];
    static int8_t q[CHUNK * QK];
    uint64_t n_blocks = t->ne[0] * t->ne[1] / QK;
    float* s = malloc(n_blocks * sizeof(float));
    if (!s) { die("malloc failed!\n"); }
    seek_to(t->offset);
    for (uint64_t done = 0; done < n_blocks; ) {
        size_t n = n_blocks - done < CHUNK ? (size_t)(n_blocks - done) : CHUNK;
        read_bytes(blocks, n * (2 + QK));
        for (size_t b = 0; b < n; b++) {
            const uint8_t* block = blocks + b * (2 + QK);
            uint16_t h;
            memcpy(&h, block, sizeof(h));
            float scale = half_to_float(h);
            if (!isfinite(scale) || scale < 0.0f) {
                die("%s has a bad scaling factor: damaged file?\n", t->name);
            }
            if (scale < scale_min) { scale_min = scale; }
            if (scale > scale_max) { scale_max = scale; }
            s[done + b] = scale;
            memcpy(q + b * QK, block + 2, QK);
        }
        write_bytes(q, n * QK);
        done += n;
    }
    write_bytes(s, n_blocks * sizeof(float));
    free(s);
}

// ----------------------------------------------------------------------------

int main(int argc, char* argv[]) {
    if (argc < 3 || argc > 4) {
        fprintf(stderr, "Usage:   gguf2bin <model.gguf> <checkpoint.bin> [seq_len]\n");
        fprintf(stderr, "Example: gguf2bin llama-2-7b-chat.Q8_0.gguf llama2_7b.bin\n");
        exit(EXIT_FAILURE);
    }
    in_path = argv[1];
    char* out_path = argv[2];
    if (strcmp(in_path, out_path) == 0) { die("The checkpoint can't be the GGUF itself\n"); }

    // the header of the GGUF: the metadata and the list of tensors
    in = fopen(in_path, "rb");
    if (!in) { die("Couldn't open file %s\n", in_path); }
    setvbuf(in, NULL, _IOFBF, 1 << 20);
    fseeko(in, 0, SEEK_END);
    in_size = (uint64_t)ftello(in);
    rewind(in);
    char magic[4];
    read_bytes(magic, sizeof(magic));
    if (memcmp(magic, "GGUF", 4) != 0) { die("%s is not a GGUF file\n", in_path); }
    uint32_t version = read_u32();
    if (version != 2 && version != 3) { die("Bad GGUF version %u, need 2 or 3\n", version); }
    n_tensors = read_u64();
    uint64_t n_metadata = read_u64();
    if (n_tensors > 100000 || n_metadata > 100000) { die("%s is not a valid GGUF\n", in_path); }
    numbers[ALIGNMENT] = 32; // the default, when the metadata doesn't say
    read_metadata(n_metadata);
    if (numbers[ALIGNMENT] < 1 || numbers[ALIGNMENT] > 1e6) { die("%s is not a valid GGUF\n", in_path); }
    read_tensors((uint64_t)numbers[ALIGNMENT]);

    // the Config of llama2.c
    if (!strings[ARCHITECTURE] || strcmp(strings[ARCHITECTURE], "llama") != 0) {
        die("The architecture is %s and llama2.c only runs llama\n",
            strings[ARCHITECTURE] ? strings[ARCHITECTURE] : "unknown");
    }
    for (int k = DIM; k <= HEADS; k++) {
        if (!found[k] || numbers[k] < 1 || numbers[k] > 1e6) { die("Bad or missing %s\n", number_keys[k]); }
    }
    int dim = (int)numbers[DIM];
    int hidden_dim = (int)numbers[HIDDEN_DIM];
    int n_layers = (int)numbers[LAYERS];
    int n_heads = (int)numbers[HEADS];
    if (found[KV_HEADS] && (numbers[KV_HEADS] < 1 || numbers[KV_HEADS] > n_heads)) {
        die("Bad %s\n", number_keys[KV_HEADS]);
    }
    int n_kv_heads = found[KV_HEADS] ? (int)numbers[KV_HEADS] : n_heads;
    if (dim % n_heads != 0 || dim % QK != 0 || hidden_dim % QK != 0) {
        die("dim %d, hidden_dim %d and n_heads %d don't fit a checkpoint with groups of %d\n",
            dim, hidden_dim, n_heads, QK);
    }
    uint64_t kv_dim = (uint64_t)(dim / n_heads) * n_kv_heads;
    Tensor* embedding = find_tensor("token_embd.weight");
    if (!embedding || embedding->ne[1] < 1 || embedding->ne[1] > 10000000) {
        die("%s has no usable token_embd.weight\n", in_path);
    }
    int vocab_size = (int)embedding->ne[1];
    // as export.py does with the checkpoints of Meta, seq_len is at most 2048 unless told: the key
    // and value caches of the run state are allocated for seq_len tokens
    int context = found[CONTEXT] && numbers[CONTEXT] >= 1 && numbers[CONTEXT] <= 1e9 ? (int)numbers[CONTEXT] : 0;
    int seq_len = argc == 4 ? atoi(argv[3]) : (context > 0 && context < 2048) ? context : 2048;
    if (seq_len < 1) { die("Bad seq_len %s\n", argv[3]); }

    fprintf(stderr, "%s: %s, GGUF v%u, %" PRIu64 " tensors\n", in_path,
            strings[NAME] ? strings[NAME] : "(no name)", version, n_tensors);
    fprintf(stderr, "dim %d, hidden_dim %d, n_layers %d, n_heads %d, n_kv_heads %d, vocab_size %d, seq_len %d",
            dim, hidden_dim, n_layers, n_heads, n_kv_heads, vocab_size, seq_len);
    if (context > 0 && context != seq_len) { fprintf(stderr, " (the GGUF says %d)", context); }
    fprintf(stderr, "\n");
    // what llama2.c has fixed in its code and a model can have different
    if (found[ROPE_BASE] && numbers[ROPE_BASE] != 10000) {
        fprintf(stderr, "WARNING: the RoPE base is %g and llama2.c uses 10000\n", numbers[ROPE_BASE]);
    }
    if (strings[TOKENIZER] && strcmp(strings[TOKENIZER], "llama") != 0) {
        fprintf(stderr, "WARNING: the tokenizer is %s, not the SentencePiece one of tokenizer.bin\n", strings[TOKENIZER]);
    }
    if ((found[BOS] && numbers[BOS] != 1) || (found[EOS] && numbers[EOS] != 2)) {
        fprintf(stderr, "WARNING: BOS and EOS are not the tokens 1 and 2 of llama2.c\n");
    }
    if (vocab_size != 32000) {
        fprintf(stderr, "WARNING: tokenizer.bin of llama2.c has 32000 tokens\n");
    }

    // first the rmsnorm weights, that stay in fp32...
    int n_norms = 2 * n_layers + 1;
    plan = malloc((9 * (size_t)n_layers + 3) * sizeof(Tensor*));
    if (!plan) { die("malloc failed!\n"); }
    plan_layers(n_layers, "attn_norm", dim, 1, 0);
    plan_layers(n_layers, "ffn_norm", dim, 1, 0);
    plan_tensor("output_norm.weight", dim, 1, 0);
    // ...and then the matrices
    plan_tensor("token_embd.weight", dim, vocab_size, 1);
    plan_layers(n_layers, "attn_q", dim, dim, 1);          // wq
    plan_layers(n_layers, "attn_k", dim, kv_dim, 1);       // wk
    plan_layers(n_layers, "attn_v", dim, kv_dim, 1);       // wv
    plan_layers(n_layers, "attn_output", dim, dim, 1);     // wo
    plan_layers(n_layers, "ffn_gate", dim, hidden_dim, 1); // w1
    plan_layers(n_layers, "ffn_down", hidden_dim, dim, 1); // w2
    plan_layers(n_layers, "ffn_up", dim, hidden_dim, 1);   // w3
    // a model whose classifier is the embedding table has no output.weight. llama 2 has it
    int shared_classifier = find_tensor("output.weight") == NULL;
    if (!shared_classifier) { plan_tensor("output.weight", dim, vocab_size, 1); }

    uint64_t expected = 256;
    for (int i = 0; i < n_plan; i++) {
        uint64_t n = plan[i]->ne[0] * plan[i]->ne[1];
        expected += i < n_norms ? n * sizeof(float) : n + n / QK * sizeof(float);
    }

    tmp_path = malloc(strlen(out_path) + 5);
    if (!tmp_path) { die("malloc failed!\n"); }
    sprintf(tmp_path, "%s.tmp", out_path);
    out = fopen(tmp_path, "wb");
    if (!out) { die("Couldn't create file %s: %s\n", tmp_path, strerror(errno)); }
    setvbuf(out, NULL, _IOFBF, 1 << 20);
    atexit(discard_output);

    // the header of version 2 of export.py: 256 bytes
    uint8_t header[256] = {0};
    uint32_t magic_number = 0x616b3432; // "ak42"
    int32_t version_and_config[8] = { 2, dim, hidden_dim, n_layers, n_heads, n_kv_heads, vocab_size, seq_len };
    int32_t group_size = QK;
    memcpy(header, &magic_number, 4);
    memcpy(header + 4, version_and_config, 32);
    header[36] = (uint8_t)shared_classifier;
    memcpy(header + 37, &group_size, 4);
    write_bytes(header, sizeof(header));

    time_t start = time(NULL);
    int shown = 0;
    for (int i = 0; i < n_plan; i++) {
        if (i < n_norms) { write_floats(plan[i]); } else { write_q8(plan[i]); }
        int percent = (int)(written * 100 / expected);
        if (percent >= shown + 5) {
            shown = percent - percent % 5;
            fprintf(stderr, "%3d%%  %ld s\n", shown, (long)(time(NULL) - start));
        }
    }
    if (written != expected) { die("wrote %" PRIu64 " bytes, expected %" PRIu64 "\n", written, expected); }
    if (fflush(out) != 0 || fsync(fileno(out)) != 0 || fclose(out) != 0 || rename(tmp_path, out_path) != 0) {
        die("Couldn't finish %s: %s\n", out_path, strerror(errno));
    }
    tmp_path = NULL; // nothing to discard anymore
    fprintf(stderr, "OK: %s, %" PRIu64 " bytes, group size %d, scaling factors from %g to %g\n",
            out_path, written, QK, scale_min, scale_max);
    return 0;
}
