# Stage 5: W4 validation for Claude

Codex implemented this without a compiler, network or phone. Compilation,
the C tests below, MDLA acceptance, text quality and speed remain unverified.
All changes are confined to `npu-probe/`.

Build on the existing CI runner, not this notebook:

```sh
aarch64-linux-android29-clang -O3 -Wall run_nnapi.c -o run_nnapi -lm -ldl -llog
aarch64-linux-android29-clang -O3 -Wall run_nnapi_w4_test.c -o run_nnapi_w4_test -lm -ldl -llog
```

Alternatively the unit test runs on a Linux runner without an accelerator:

```sh
cc -O3 -Wall run_nnapi_w4_test.c -o run_nnapi_w4_test -lm -ldl -pthread
./run_nnapi_w4_test
```

The test covers FP32/Q8 checkpoint conversion, zero rows, MSE versus max-only
rounding, signed unpacked bytes, cache reuse and GS invalidation, preservation
of W8 files, raw FC calibration ranges including the weight scale/gain,
row correction/bias, W8/hybrid/island operand types and operation ordering,
INT32 bias scale, calibration persistence and rejection after RESCALE changes.
It also checks replay broadcasting, concatenated history, both batched matrix
products and ordinary/safe RMSNorm with known numerical fixtures.
It uses mock graph calls; it does not prove driver support or fp16 accuracy.

Transfer `run_nnapi`, `run_nnapi_w4_test`, and `stage5_w4.sh` to
`/data/local/tmp/llm/` using Claude's normal process. With the phone free,
run these exact commands in its shell (redirect each run to a separate log):

```sh
cd /data/local/tmp/llm
./run_nnapi_w4_test
sh stage5_w4.sh qwen hybrid-verify > qwen_w4_hybrid_verify.log 2>&1
sh stage5_w4.sh qwen island-verify > qwen_w4_island_verify.log 2>&1
sh stage5_w4.sh qwen hybrid-speed > qwen_w4_hybrid_speed.log 2>&1
sh stage5_w4.sh qwen island-speed > qwen_w4_island_speed.log 2>&1
sh stage5_w4.sh llama hybrid-verify > llama_w4_hybrid_verify.log 2>&1
sh stage5_w4.sh llama island-verify > llama_w4_island_verify.log 2>&1
sh stage5_w4.sh llama hybrid-speed > llama_w4_hybrid_speed.log 2>&1
sh stage5_w4.sh llama island-speed > llama_w4_island_speed.log 2>&1
sh stage5_w4.sh qwen w8-verify > qwen_w8_verify.log 2>&1
sh stage5_w4.sh llama w8-verify > llama_w8_verify.log 2>&1
```

The script uses the exact model and weight paths in the task, FIRST=1 for Qwen,
FIRST=0 for Llama, RESCALE=2, four layers per graph, HEAD_CHUNK=8960 and four CPU
threads. Llama settings are test choices, not measurements. An explicit
`NNAPI_W4_CALIB=/new/path` overrides the script's per-model calibration path.
Use a new path to recalibrate; an existing incompatible file is never overwritten.

For a missing calibration file, `island-verify` runs eight tokens with CPU
outputs, and replays the same graph builder on CPU to collect W4 ranges. It
saves the ranges and starts compiling device graphs at the following token.
A shorter complete run saves its available samples on exit. A second run with
the same file loads the ranges immediately. Check for `calibration saved`,
then `calibration loaded`, block/head compilation and verification lines.
For timing, require successful device executions; a printed W4 format alone
does not prove that the device accepted the graphs. A failed graph stays on CPU.
Do not compare the first calibration run's throughput with steady-state runs.

Decisions to inspect closely:

- Quantization starts at checkpoint values (including Q8 group scales), tries
  max/7 times 0.70..1.00 in increments of 0.05, and minimizes squared error per
  row. The printed relative error is `sum(abs(W-W4))/sum(abs(W))` per matrix;
  a zero matrix reports zero. It is not a mean of per-element ratios near zero.
- `<NNAPI_WEIGHTS_FILE>.w4.<id>` stores signed bytes and row scales, with a
  version, checkpoint size/mtime, shape, GS and content hash. W8 files are only
  used when W8 is selected. IDs are `layer*7 + {wq,wk,wv,wo,w1,w3,w2}`; the head
  weight file has ID `n_layers*7`. Calibration has a separate record per head chunk.
- The common row_scale/127 convention is retained. This divisor is an internal
  scale convention even for W4, not the quantizer's range. The matrix operand
  gets max(row_step)*gain; row correction comes after DEQUANTIZE in island mode.
- Calibration executes the existing builder's float operations, not a second
  implementation of residual scaling. It uses W4 weights, includes RESCALE=1/2/3,
  FIRST, epsilon, gain and chunk boundaries, and measures raw FC outputs before
  row correction, bias and query scaling. It does not simulate device fp16 or
  activation quantization. CPU forward determines the generated calibration text.
- CPU K/V history is imported during calibration and when device execution
  begins. VERIFY continues comparing the corrected residual/logits in real units
  against the original checkpoint CPU path; quantization error is included.
- Range files validate the checkpoint metadata, configuration, graph settings,
  matrix hashes and payload checksum. Margin defaults to two; zero ranges use
  a 1e-12 scale floor. More representative calibration can be requested with
  NNAPI_W4_CALIB_TOKENS outside the fixed test script. Saturation/quality outside
  calibrated prompts must be checked on hardware, especially on small models.

Also compare W8 with the prior binary and exercise FIRST=0/1, RESCALE=2/3,
HEAD_CHUNK=0/8960 and multiple tokens of history. Use separate calibration files
when changing graph settings. These hardware regressions are still pending.
