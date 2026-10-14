# Stage 3: validation for Claude

Written by Codex; not compiled or run locally. Compile on the CI runner, then
transfer the binaries through the existing workflow. Phone commands require
the user's authorization that the phone is free.

Build from `npu-probe/` on the runner (the engine command is unchanged):

```sh
aarch64-linux-android29-clang -O3 -Wall run_nnapi.c -o run_nnapi -lm -ldl -llog
aarch64-linux-android29-clang -O3 -Wall run_nnapi_cpu_test.c -o run_nnapi_cpu_test -lm -ldl -llog
```

In a phone shell, in the directory containing the new binaries and existing model:

```sh
cd /data/local/tmp/llm
for n in 1 2 3 4 8; do NNAPI_CPU_THREADS=$n ./run_nnapi_cpu_test || break; done
```

Each test process must print `PASS: 480 CPU matmuls bit-identical to scalar reference`.
This checks FP32/Q8, zero inputs, changing inputs and dimensions, uneven row
partitions, fewer rows than threads, and guards on the output buffer.

Define this shell function once. It fixes settings for comparison and uses RAM
for graph weights so it does not depend on existing weight files. Each argument
overrides an environment setting. The paths below come from `ESTADO.md`; confirm
they still describe the phone before running.

```sh
stage3_run() {
    env NNAPI_BACKEND=neuron NNAPI_DEVICE=mtk-mdla_shim \
        NNAPI_BLOCKS=4 NNAPI_BLOCK_FIRST=3 NNAPI_BLOCK_LAYERS=28 \
        NNAPI_BLOCK_T=256 NNAPI_BLOCK_NORM=64 NNAPI_BLOCK_EPS=0 \
        NNAPI_CPU_THREADS=4 NNAPI_HEAD_CHUNK=0 NNAPI_VERIFY=0 \
        NNAPI_WEIGHTS_FILE= NNAPI_NEURON_OPTS= "$@" \
        ./run_nnapi qwen2.5_1.5b.bin -z qwen2.5_1.5b.bin.tokenizer \
        -m chat -y 'Responde en espanol.' -i 'Explica por que el cielo es azul.' \
        -t 0 -s 1 -n 96
}
```

Run one at a time, under comparable temperatures:

```sh
# Timing baseline; existing blocks, one CPU thread, classifier on CPU.
stage3_run NNAPI_CPU_THREADS=1 >stage3_one.txt 2>stage3_one.log
# CPU threading only. Text should be identical to the baseline.
stage3_run >stage3_four.txt 2>stage3_four.log
cmp stage3_one.txt stage3_four.txt
# Classifier only as the new change; same one-thread CPU baseline.
stage3_run NNAPI_CPU_THREADS=1 NNAPI_HEAD_CHUNK=8960 >stage3_head.txt 2>stage3_head.log
# Both changes, with verification (also computes blocks and classifier on CPU).
stage3_run NNAPI_HEAD_CHUNK=8960 NNAPI_VERIFY=1 >stage3_verify.txt 2>stage3_verify.log
# Both changes, without verification, for actual speed.
stage3_run NNAPI_HEAD_CHUNK=8960 >stage3_all.txt 2>stage3_all.log
# Isolate head verification from the block graphs.
stage3_run NNAPI_BLOCKS=0 NNAPI_HEAD_CHUNK=8960 NNAPI_VERIFY=1 >stage3_head_only.txt 2>stage3_head_only.log
# Known unsupported full head: must log CPU fallback and finish normally.
stage3_run NNAPI_HEAD_CHUNK=151936 >stage3_fallback.txt 2>stage3_fallback.log
cmp stage3_four.txt stage3_fallback.txt
# Original NNAPI backend, classifier chunking off.
stage3_run NNAPI_BACKEND=nnapi >stage3_nnapi.txt 2>stage3_nnapi.log
# Entirely CPU, one and four threads, for an independent trajectory comparison.
stage3_run NNAPI_DEVICE=off NNAPI_CPU_THREADS=1 >stage3_cpu1.txt 2>stage3_cpu1.log
stage3_run NNAPI_DEVICE=off NNAPI_CPU_THREADS=4 >stage3_cpu4.txt 2>stage3_cpu4.log
cmp stage3_cpu1.txt stage3_cpu4.txt
```

Read `nnapi: speed:` and `nnapi: time/token:`. A token is one evaluated input,
including the final prompt token in the prompt count. Generation therefore
counts evaluated generated inputs, not strings printed. EOS/step-limit exits
count their last completed forward call. A run with no generation has zero
generation throughput. Timers exclude loading, tokenization, user input, and
nested lazy setup/compilation; they include sampling and output. APU time is
wall time around execution creation, bindings, compute and free, including
failed attempts. CPU matmul time includes input quantization and worker waits.
The remainder includes attention, norms, copies, sampling and output.
Verification work is deliberately included and identified in the log.

The head uses the existing row quantizer in RAM (about 233 MB of weight bytes
for this model), outside the token timer. It has 17 chunks at 8960 rows, the
last with 8576 rows. Each chunk has FC plus row-scale MUL; its output buffer
is the corresponding contiguous slice of logits. The normalized input is
shared, without another RMSNorm. Check relative logit error and argmax match;
verification samples CPU logits. Compilation/execution failure disables the
head for the rest of the process and releases its graph and weights.

Review the pool's single-caller contract: workers receive immutable job data
under the mutex, write disjoint rows, and signal completion before the caller
frees Q8 inputs. They never call NNAPI or access `nn`/`blk`. The requested count
includes the caller, is limited to 1..8, and defaults to 4. Four threads pin to
cores 4,5,6,7; with one thread the caller can run on any of 4..7. Affinity failures
are logged. No manual NEON or changed reduction order was introduced.

Per-layer residual rescaling remains unimplemented. A future experiment must
track a dynamic scale for each attention/FFN residual addition, scale epsilon
consistently, and avoid overflow before the rescaling reduction itself. Merely
normalizing at the next layer cannot recover an already overflowing residual.
