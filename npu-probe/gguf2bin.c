/* Converts a GGUF quantized as Q8_0 (llama.cpp) into the Q8_0 checkpoint of llama2.c (version 2 */
/* of export.py), the one runq.c and run_nnapi.c read. Nothing is requantized: both formats hold */
/* int8 weights with one scaling factor per group, and the GGUF groups are of 32. */
/* Llama 2 gives the checkpoint of export.py. Qwen 2 and Llama 3 need what llama2.c lacks: it */
/* goes in the unused part of the header (ModelExtras), in more fp32 tensors after the rmsnorm */
/* weights, and in a tokenizer file written next to the checkpoint. run_nnapi.c reads all three */

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

// the architectures this converter knows. the keys of a model carry the name of its
// architecture: llama.block_count, qwen2.block_count...
const char* ARCHITECTURES[] = { "llama", "qwen2" };

// the metadata that is used here: the rest is skipped
enum { ARCHITECTURE, NAME, TOKENIZER, PRE_TOKENIZER, CHAT_TEMPLATE, N_STRINGS };
const char* string_keys[N_STRINGS] = {
    "general.architecture", "general.name", "tokenizer.ggml.model", "tokenizer.ggml.pre", "tokenizer.chat_template",
};
char* strings[N_STRINGS];

enum { ALIGNMENT, CONTEXT, DIM, LAYERS, HIDDEN_DIM, HEADS, KV_HEADS, ROPE_BASE, NORM_EPS, BOS, EOS, ADD_BOS, N_NUMBERS };
// a key that starts with a dot goes after the name of the architecture
const char* number_keys[N_NUMBERS] = {
    "general.alignment", ".context_length", ".embedding_length", ".block_count",
    ".feed_forward_length", ".attention.head_count", ".attention.head_count_kv",
    ".rope.freq_base", ".attention.layer_norm_rms_epsilon",
    "tokenizer.ggml.bos_token_id", "tokenizer.ggml.eos_token_id", "tokenizer.ggml.add_bos_token",
};
double numbers[N_NUMBERS];
int found[N_NUMBERS];

char** vocab;         // the tokens as the GGUF has them
uint64_t n_vocab = 0;
int32_t* token_types; // of each token: 1 = normal, 3 = control... NULL if the GGUF doesn't say
uint64_t n_token_types = 0;

int is_key(const char* key, const char* wanted) {
    if (wanted[0] != '.') { return strcmp(key, wanted) == 0; }
    for (size_t a = 0; a < sizeof(ARCHITECTURES) / sizeof(ARCHITECTURES[0]); a++) {
        size_t n = strlen(ARCHITECTURES[a]);
        if (strncmp(key, ARCHITECTURES[a], n) == 0 && strcmp(key + n, wanted) == 0) { return 1; }
    }
    return 0;
}

void read_vocab(void) {
    uint32_t item_type = read_u32();
    n_vocab = read_u64();
    if (item_type != T_STRING || n_vocab > 10000000) { die("%s is not a valid GGUF\n", in_path); }
    vocab = malloc((n_vocab + 1) * sizeof(char*));
    if (!vocab) { die("malloc failed!\n"); }
    for (uint64_t i = 0; i < n_vocab; i++) { vocab[i] = read_string(); }
}

void read_token_types(void) {
    uint32_t item_type = read_u32();
    n_token_types = read_u64();
    if (scalar_size(item_type) == 0 || n_token_types > 10000000) { die("%s is not a valid GGUF\n", in_path); }
    token_types = malloc((n_token_types + 1) * sizeof(int32_t));
    if (!token_types) { die("malloc failed!\n"); }
    for (uint64_t i = 0; i < n_token_types; i++) { token_types[i] = (int32_t)read_number(item_type); }
}

void read_metadata(uint64_t n_metadata) {
    for (uint64_t i = 0; i < n_metadata; i++) {
        char* key = read_string();
        uint32_t type = read_u32();
        int k;
        if (type == T_STRING) {
            for (k = 0; k < N_STRINGS && strcmp(key, string_keys[k]) != 0; k++) {}
            if (k < N_STRINGS) { strings[k] = read_string(); } else { skip_value(type); }
        } else if (scalar_size(type) > 0) {
            for (k = 0; k < N_NUMBERS && !is_key(key, number_keys[k]); k++) {}
            if (k < N_NUMBERS) { numbers[k] = read_number(type); found[k] = 1; } else { skip_value(type); }
        } else if (type == T_ARRAY && strcmp(key, "tokenizer.ggml.tokens") == 0) {
            read_vocab();
        } else if (type == T_ARRAY && strcmp(key, "tokenizer.ggml.token_type") == 0) {
            read_token_types();
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
    int used;        // it has a place in the checkpoint
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
    t->used = 1;
    plan[n_plan++] = t;
}

void plan_layers(int n_layers, const char* part, uint64_t row_length, uint64_t rows, int quantized) {
    char name[64];
    for (int l = 0; l < n_layers; l++) {
        snprintf(name, sizeof(name), "blk.%d.%s", l, part);
        plan_tensor(name, row_length, rows, quantized);
    }
}

// ----------------------------------------------------------------------------
// What llama2.c has fixed in its code and other models change

// It goes in the header of export.py from byte 64 on, which that leaves as zeros: a checkpoint
// without the magic is a Llama 2 one. run_nnapi.c has the same struct
typedef struct {
    uint32_t magic;       // EXTRAS_MAGIC
    float rope_base;      // 10000 in Llama 2
    float norm_eps;       // of the rmsnorms, 1e-5 in Llama 2
    int32_t rope_halves;  // RoPE pairs element i of a head with i + head_size/2 (Qwen), not with i + 1
    int32_t qkv_bias;     // wq, wk and wv have a bias: fp32, after the rmsnorm weights (Qwen)
    int32_t rope_factors; // the frequencies of RoPE are divided by a table: fp32, after those (Llama 3.1 on)
    int32_t tokenizer;    // TOKENIZER_*
    int32_t chat;         // CHAT_*
    int32_t bos;          // token every sequence starts with, -1 = none
    int32_t eos;          // token that ends a turn
} ModelExtras;
#define EXTRAS_MAGIC 0x31747865 // "ext1"
#define EXTRAS_OFFSET 64
// sentencepiece with scores (tokenizer.bin of llama2.c), or the byte-level BPE of Qwen or of Llama 3
enum { TOKENIZER_SPM, TOKENIZER_BPE_QWEN, TOKENIZER_BPE_LLAMA3 };
// [INST] of Llama 2, ChatML (<|im_start|>, Qwen) or the headers of Llama 3
enum { CHAT_LLAMA2, CHAT_CHATML, CHAT_LLAMA3 };

size_t token_bytes(const char* token, unsigned char* bytes) {
    // A byte-level BPE vocabulary writes its tokens with one character per byte: the printable
    // bytes as themselves and the others (0 to 32, 127 to 160 and 173) as U+0100 to U+0143.
    // This gives the bytes back
    size_t n = 0;
    for (const unsigned char* c = (const unsigned char*)token; *c != '\0'; ) {
        uint32_t cp = 0xFFFFFFFF; // of the characters above U+07FF, that alphabet has none: copied as they are
        int len = 1;
        if (*c < 0x80) {
            cp = *c;
        } else if ((*c & 0xE0) == 0xC0 && (c[1] & 0xC0) == 0x80) {
            cp = (uint32_t)(*c & 0x1F) << 6 | (c[1] & 0x3F);
            len = 2;
        }
        if (cp < 0x100) { bytes[n++] = (unsigned char)cp; }
        else if (cp <= 0x120) { bytes[n++] = (unsigned char)(cp - 0x100); }
        else if (cp <= 0x142) { bytes[n++] = (unsigned char)(cp - 0x121 + 127); }
        else if (cp == 0x143) { bytes[n++] = 173; }
        else { for (int k = 0; k < len; k++) { bytes[n++] = c[k]; } }
        c += len;
    }
    return n;
}

size_t token_text(uint64_t id, unsigned char* bytes) {
    // the bytes that token stands for, as run_nnapi.c will have them: a C string
    size_t n;
    if (token_types == NULL || id >= n_token_types || token_types[id] == 1) {
        n = token_bytes(vocab[id], bytes);
    } else {
        // control and added tokens (<|im_start|>...) are written as plain text
        n = strlen(vocab[id]);
        memcpy(bytes, vocab[id], n);
    }
    if (n == 0 || memchr(bytes, 0, n) != NULL) {
        // a zero byte can't be in a C string. no text has one: this token gets a name no text has either
        n = (size_t)sprintf((char*)bytes, "\xff\xff%" PRIu64, id);
    }
    return n;
}

void write_tokenizer(const char* path) {
    // The format of tokenizer.bin of llama2.c: the length of the longest token and then score,
    // length and bytes of each one. The encoder of llama2.c merges the two neighbours whose
    // union is the token with the highest score. These vocabularies come from tiktoken, which
    // merges the pair whose union has the lowest id: the score is minus the id
    size_t longest = 0;
    for (uint64_t i = 0; i < n_vocab; i++) {
        if (strlen(vocab[i]) > longest) { longest = strlen(vocab[i]); }
    }
    unsigned char* bytes = malloc(longest + 32);
    FILE* file = fopen(path, "wb");
    if (!bytes || !file) { die("Couldn't create file %s: %s\n", path, strerror(errno)); }
    int32_t max_token_length = 0;
    for (uint64_t i = 0; i < n_vocab; i++) {
        int32_t len = (int32_t)token_text(i, bytes);
        if (len > max_token_length) { max_token_length = len; }
    }
    int failed = fwrite(&max_token_length, sizeof(int32_t), 1, file) != 1;
    for (uint64_t i = 0; i < n_vocab && !failed; i++) {
        float score = -(float)i;
        int32_t len = (int32_t)token_text(i, bytes);
        failed = fwrite(&score, sizeof(float), 1, file) != 1 || fwrite(&len, sizeof(int32_t), 1, file) != 1
              || fwrite(bytes, 1, len, file) != (size_t)len;
    }
    if (fclose(file) != 0 || failed) { die("write failed on %s: %s\n", path, strerror(errno)); }
    free(bytes);
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
    // a 1D tensor (rmsnorm weights, biases, the table of RoPE), that stays in fp32
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
    const char* architecture = strings[ARCHITECTURE] ? strings[ARCHITECTURE] : "unknown";
    int qwen = strcmp(architecture, "qwen2") == 0;
    if (!qwen && strcmp(architecture, "llama") != 0) {
        die("The architecture is %s: this converter knows llama and qwen2\n", architecture);
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

    // what is not as in Llama 2
    ModelExtras extras;
    memset(&extras, 0, sizeof(extras));
    extras.magic = EXTRAS_MAGIC;
    extras.rope_base = found[ROPE_BASE] && numbers[ROPE_BASE] > 1 ? (float)numbers[ROPE_BASE] : 10000.0f;
    extras.norm_eps = found[NORM_EPS] && numbers[NORM_EPS] > 0 ? (float)numbers[NORM_EPS] : 1e-5f;
    extras.rope_halves = qwen; // as llama.cpp does: its "neox" RoPE for qwen2, the "norm" one for llama
    extras.qkv_bias = find_tensor("blk.0.attn_q.bias") != NULL;
    extras.rope_factors = find_tensor("rope_freqs.weight") != NULL;
    const char* tokenizer_model = strings[TOKENIZER] ? strings[TOKENIZER] : "llama";
    const char* pre_tokenizer = strings[PRE_TOKENIZER] ? strings[PRE_TOKENIZER] : "";
    if (strcmp(tokenizer_model, "gpt2") == 0) {
        if (strcmp(pre_tokenizer, "qwen2") == 0) {
            extras.tokenizer = TOKENIZER_BPE_QWEN;
        } else if (strcmp(pre_tokenizer, "llama-bpe") == 0 || strcmp(pre_tokenizer, "llama3") == 0
                   || strcmp(pre_tokenizer, "llama-v3") == 0) {
            extras.tokenizer = TOKENIZER_BPE_LLAMA3;
        } else {
            die("The BPE tokenizer is '%s': this converter knows those of Qwen 2 and Llama 3\n", pre_tokenizer);
        }
        if (n_vocab != (uint64_t)vocab_size) {
            die("%s has %" PRIu64 " tokens for %d embeddings\n", in_path, n_vocab, vocab_size);
        }
    } else if (strcmp(tokenizer_model, "llama") != 0) {
        die("The tokenizer is %s: this converter knows llama (sentencepiece) and gpt2 (BPE)\n", tokenizer_model);
    }
    const char* chat_template = strings[CHAT_TEMPLATE] ? strings[CHAT_TEMPLATE] : "";
    extras.chat = strstr(chat_template, "<|im_start|>") ? CHAT_CHATML
                : strstr(chat_template, "<|start_header_id|>") ? CHAT_LLAMA3 : CHAT_LLAMA2;
    if ((found[BOS] && (numbers[BOS] < 0 || numbers[BOS] >= vocab_size))
        || (found[EOS] && (numbers[EOS] < 0 || numbers[EOS] >= vocab_size))) {
        die("Bad BOS or EOS token\n");
    }
    extras.bos = found[BOS] ? (int32_t)numbers[BOS] : 1;
    extras.eos = found[EOS] ? (int32_t)numbers[EOS] : 2;
    if (extras.tokenizer == TOKENIZER_SPM) {
        if (extras.bos != 1 || extras.eos != 2 || vocab_size != 32000) {
            fprintf(stderr, "WARNING: not the vocabulary of tokenizer.bin of llama2.c (32000 tokens, BOS 1, EOS 2)\n");
        }
    } else if (!(found[ADD_BOS] ? numbers[ADD_BOS] != 0 : extras.tokenizer == TOKENIZER_BPE_LLAMA3)) {
        // as llama.cpp does: a BPE tokenizer adds no BOS unless the GGUF asks for it, except the one of Llama 3
        extras.bos = -1;
    }

    fprintf(stderr, "%s: %s (%s), GGUF v%u, %" PRIu64 " tensors\n", in_path,
            strings[NAME] ? strings[NAME] : "no name", architecture, version, n_tensors);
    fprintf(stderr, "dim %d, hidden_dim %d, n_layers %d, n_heads %d, n_kv_heads %d, vocab_size %d, seq_len %d",
            dim, hidden_dim, n_layers, n_heads, n_kv_heads, vocab_size, seq_len);
    if (context > 0 && context != seq_len) { fprintf(stderr, " (the GGUF says %d)", context); }
    fprintf(stderr, "\n");
    fprintf(stderr, "RoPE base %g on %s%s, rmsnorm epsilon %g, %s on wq wk wv, %s, %s chat, BOS %d, EOS %d\n",
            extras.rope_base, extras.rope_halves ? "the halves of a head" : "neighbours",
            extras.rope_factors ? " with a table of factors" : "", extras.norm_eps,
            extras.qkv_bias ? "bias" : "no bias",
            extras.tokenizer == TOKENIZER_SPM ? "sentencepiece tokenizer"
            : extras.tokenizer == TOKENIZER_BPE_QWEN ? "BPE tokenizer of Qwen" : "BPE tokenizer of Llama 3",
            extras.chat == CHAT_CHATML ? "ChatML" : extras.chat == CHAT_LLAMA3 ? "Llama 3" : "Llama 2",
            extras.bos, extras.eos);

    // first what stays in fp32: the rmsnorm weights and, in the models that have them, the
    // biases of wq, wk and wv and the table of RoPE...
    plan = malloc((12 * (size_t)n_layers + 4) * sizeof(Tensor*));
    if (!plan) { die("malloc failed!\n"); }
    plan_layers(n_layers, "attn_norm.weight", dim, 1, 0);
    plan_layers(n_layers, "ffn_norm.weight", dim, 1, 0);
    plan_tensor("output_norm.weight", dim, 1, 0);
    if (extras.qkv_bias) {
        plan_layers(n_layers, "attn_q.bias", dim, 1, 0);
        plan_layers(n_layers, "attn_k.bias", kv_dim, 1, 0);
        plan_layers(n_layers, "attn_v.bias", kv_dim, 1, 0);
    }
    if (extras.rope_factors) { plan_tensor("rope_freqs.weight", dim / n_heads / 2, 1, 0); }
    int n_floats = n_plan;
    // ...and then the matrices
    plan_tensor("token_embd.weight", dim, vocab_size, 1);
    plan_layers(n_layers, "attn_q.weight", dim, dim, 1);          // wq
    plan_layers(n_layers, "attn_k.weight", dim, kv_dim, 1);       // wk
    plan_layers(n_layers, "attn_v.weight", dim, kv_dim, 1);       // wv
    plan_layers(n_layers, "attn_output.weight", dim, dim, 1);     // wo
    plan_layers(n_layers, "ffn_gate.weight", dim, hidden_dim, 1); // w1
    plan_layers(n_layers, "ffn_down.weight", hidden_dim, dim, 1); // w2
    plan_layers(n_layers, "ffn_up.weight", dim, hidden_dim, 1);   // w3
    // a model whose classifier is the embedding table has no output.weight. llama 2 has it
    int shared_classifier = find_tensor("output.weight") == NULL;
    if (!shared_classifier) { plan_tensor("output.weight", dim, vocab_size, 1); }
    // a tensor left out would be a part of the model that run_nnapi.c doesn't compute
    for (uint64_t i = 0; i < n_tensors; i++) {
        if (!tensors[i].used) { die("%s has a tensor with no place in the checkpoint: %s\n", in_path, tensors[i].name); }
    }

    uint64_t expected = 256;
    for (int i = 0; i < n_plan; i++) {
        uint64_t n = plan[i]->ne[0] * plan[i]->ne[1];
        expected += i < n_floats ? n * sizeof(float) : n + n / QK * sizeof(float);
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
    memcpy(header + EXTRAS_OFFSET, &extras, sizeof(extras));
    write_bytes(header, sizeof(header));

    time_t start = time(NULL);
    int shown = 0;
    for (int i = 0; i < n_plan; i++) {
        if (i < n_floats) { write_floats(plan[i]); } else { write_q8(plan[i]); }
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
    if (extras.tokenizer != TOKENIZER_SPM) {
        // tokenizer.bin of llama2.c is the vocabulary of Llama 2: these models bring their own
        char* tokenizer_path = malloc(strlen(out_path) + 11);
        if (!tokenizer_path) { die("malloc failed!\n"); }
        sprintf(tokenizer_path, "%s.tokenizer", out_path);
        write_tokenizer(tokenizer_path);
        fprintf(stderr, "OK: %s, its tokenizer (run_nnapi -z)\n", tokenizer_path);
    }
    return 0;
}
