// Build separately on the CI runner; no model or accelerator is needed.
#define TESTING
#include "run_nnapi.c"

static void reference(float* out, float* x, Matrix* w, int n, int d) {
    if (w->f) {
        for (int i = 0; i < d; i++) {
            float val = 0.0f;
            for (int j = 0; j < n; j++) { val += w->f[i * n + j] * x[j]; }
            out[i] = val;
        }
        return;
    }
    int8_t* xq = malloc(n);
    float* xs = malloc(n / GS * sizeof(float));
    if (!xq || !xs) { exit(EXIT_FAILURE); }
    quantize(xq, xs, x, n);
    for (int i = 0; i < d; i++) {
        float val = 0.0f;
        int32_t ival = 0;
        int in = i * n;
        for (int j = 0; j <= n - GS; j += GS) {
            for (int k = 0; k < GS; k++) {
                ival += ((int32_t)xq[j + k]) * ((int32_t)w->q[in + j + k]);
            }
            val += ((float)ival) * w->s[(in + j) / GS] * xs[j / GS];
            ival = 0;
        }
        out[i] = val;
    }
    free(xq);
    free(xs);
}

int main(void) {
    const int widths[] = {32, 96, 1536};
    const int rows[] = {1, 3, 7, 19, 257};
    GS = 32;
    int cases = 0;
    for (int ni = 0; ni < 3; ni++) {
        for (int di = 0; di < 5; di++) {
            int n = widths[ni], d = rows[di];
            float* f = malloc((size_t)n * d * sizeof(float));
            int8_t* q = malloc((size_t)n * d);
            float* s = malloc((size_t)n * d / GS * sizeof(float));
            float* x = malloc(n * sizeof(float));
            float* out = malloc((d + 2) * sizeof(float));
            float* ref = malloc(d * sizeof(float));
            if (!f || !q || !s || !x || !out || !ref) { exit(EXIT_FAILURE); }
            for (int j = 0; j < n * d; j++) {
                f[j] = ((j * 37 % 251) - 125) / 113.0f;
                q[j] = (int8_t)((j * 47 % 256) - 128);
            }
            for (int j = 0; j < n * d / GS; j++) { s[j] = (j % 13) / 997.0f; }
            for (int kind = 0; kind < 2; kind++) {
                Matrix w = {.f = kind ? NULL : f, .q = q, .s = s};
                for (int repeat = 0; repeat < 16; repeat++) {
                    for (int j = 0; j < n; j++) {
                        x[j] = repeat ? ((j * 23 + repeat * 17) % 127 - 63) / 71.0f : 0.0f;
                    }
                    // Changing shapes and inputs catches stale jobs and premature completion.
                    out[0] = out[d + 1] = 12345.0f;
                    for (int j = 1; j <= d; j++) { out[j] = NAN; }
                    reference(ref, x, &w, n, d);
                    token_begin();
                    matmul_cpu(out + 1, x, &w, n, d);
                    token_end(false);
                    if (memcmp(ref, out + 1, d * sizeof(float))
                        || out[0] != 12345.0f || out[d + 1] != 12345.0f) {
                        fprintf(stderr, "FAIL: %s %dx%d iteration %d\n", kind ? "Q8" : "fp32", d, n, repeat);
                        cpu_free();
                        return EXIT_FAILURE;
                    }
                    cases++;
                }
            }
            free(f); free(q); free(s); free(x); free(out); free(ref);
        }
    }
    cpu_free();
    report_speed();
    fprintf(stderr, "PASS: %d CPU matmuls bit-identical to scalar reference\n", cases);
    return 0;
}
