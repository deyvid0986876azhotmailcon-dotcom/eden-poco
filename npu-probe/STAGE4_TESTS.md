# Stage 4: dynamic scaling (for Claude)

Implemented by Codex, without compiling, network or phone access. `neuron_api.h`
needs no change: scalar tensor outputs and all operations already exist in the ABI.

## Timing

The checked-in `report_speed()` already printed the breakdown unconditionally,
immediately after throughput, with the prefix `nnapi: time/token:`. Source inspection
cannot establish why the phone log omitted it. A filter retaining `nnapi: speed:`
would discard it. Both lines now use `nnapi: speed:` and stderr is explicitly flushed.
Check the **unfiltered stderr of the new binary** if it still seems absent.
Definitions of the timers are unchanged; loading/compilation are excluded and
VERIFY includes the extra CPU reference work.

## Representation and bounds

Only `NNAPI_BLOCK_RESCALE=1` selects the new path. Default 0 retains the old graph,
weight format, epsilon setting and NNAPI/Neuron behavior. RESCALE uses norm 64
regardless of `NNAPI_BLOCK_NORM` and implies scaled model epsilon.

Each pair `(z,a)` represents `z/a` in model units, with `||z||2 <=64`, `0<a<=1`.
At entry the CPU chooses `a=min(1,64/||x||)`; at exit an extra FLOAT32 tensor of
shape `[1]` carries the final inverse scale. Output indices for K/V are unchanged.
Both normal inference and VERIFY divide by that output scale, not the input scale.
An invalid scale, non-finite real residual or invalid K/V disables that group and
uses the existing CPU fallback. FIRST=1 keeps layer 0 on CPU through the same path
as FIRST=3, with stable matrix numbering and cache updates.

For residual pairs `(x,a)` and `(y,c)`, use `u=c/(a+c)`, `v=a/(a+c)`, then
`z=u*x+v*y`, `b=a*u`. This is a convex combination **before** the addition,
so its norm is at most 64 even when one real branch is 90 or 270 times larger.
Normalize it using `r=64/sqrt(dim)/sqrt(mean(z*z)+1/4096)`, then cap its inverse
scale with `k=1/(1+b*r)`. The new pair is `(z*r*k,b*r*k)`; both factors cancel
when decoded, so the reduction floor is not an extra model RMSNorm epsilon.
RMSNorm itself receives `NORM_EPS*a*a`, evaluated as `(sqrt(NORM_EPS)*a)^2`.
All reciprocal operations use RSQRT followed by MUL; no DIV, SQRT, MAX, MIN or
new operation code is introduced. MEAN now also uses the existing keep_dims=0 form.

The supplied norms 0.43 -> 38 -> 4632 -> 10208 -> 10600 therefore never become
the old `64*10208/0.43 = 1,519,330` inside a group. The stored residual stays <=64;
the sum of its squares stays <=4096. Ignoring the small reduction floor, the
inverse scale after normalization is `64/(real_norm+64)`: about 0.627, 0.0136,
0.00623 and 0.00600. Real growth changes the scalar, not the range of the vector.

FC protection uses Frobenius norms computed from the actual quantized bytes,
with signed bytes on Neuron and unsigned bytes (including mapped files) on NNAPI.
If `R` bounds the uncorrected matrix and `B` the input norm, a common gain
`min(1,32/(R*max(B,1)))` enters the weight tensor scale BEFORE FC. The per-row
correction compensates it afterwards. Paired outputs separately scale the true
matrix bound to <=32, then normalize. Thus both the raw FC and corrected paired
output fit, including w2 before row correction. Unsafe constants/bounds reject
that graph explicitly; conservative bounds may reject a usable graph.

Gate and up vectors each have norm <=64. Their product is taken as
`(gate/8)*sigmoid(real_gate)*(up/8)`, of norm <=64, rather than forming the real
SwiGLU product first. The corresponding pair represents **real SwiGLU / 64**.
Carry that factor through the linear w2 and divide its final inverse scale by 64
before the residual addition. Check this compensation particularly carefully.
The real gate required by LOGISTIC has a separate per-row bound <=32000.

Real Q/K/V outputs are bounded; attention logits are attenuated before BMM and
the mask by the same amount, with inverse attenuation supplied as SOFTMAX beta.
This keeps score products <=8192 and masked scores <=18192. V caches remain in
real units. The mixed-V bound accounts for different tokens selected by different
heads, including GQA. The compilation cache uses a distinct token for RESCALE.

These are arithmetic bounds on graph operands, not a proof about proprietary
kernel accumulators, compiler fusion, or fp16 denormal handling. In particular,
very small inverse-scale products/epsilon can underflow; finite but inaccurate
results still require VERIFY. Actual Qwen weight bounds, MDLA support for these
graph shapes/weight scales/SOFTMAX beta, layer-0 accuracy, and speed are UNTESTED.

## Checks and commands

`stage4_math_test.js` is a dependency-free numerical specification, not an execution
of the C or an emulator of the MDLA. Codex ran it in the JavaScript tool runtime:
40 checks passed, maximum relative error 0.1197%, including half rounding after
operations. It covers concentrated/distributed versions of the supplied growth,
zero/cancellation, scaled epsilon, an overflowing real SwiGLU product, delayed
w2 compensation and FC overflow before row correction. Reproduce with:

```sh
node stage4_math_test.js
```

On the CI runner, from npu-probe (do not compile on the notebook):

```sh
aarch64-linux-android29-clang -O3 -Wall run_nnapi.c -o run_nnapi -lm -ldl -llog
```

After Claude reviews the code and transfers the binary, in a phone shell from
`/data/local/tmp/llm`, define the following helper. Paths are the existing stage-3
paths; no weight-file prefix is needed. Capture complete stderr.

```sh
stage4_run() {
    env NNAPI_BACKEND=neuron NNAPI_DEVICE=mtk-mdla_shim \
        NNAPI_BLOCK_RESCALE=1 NNAPI_BLOCK_FIRST=0 NNAPI_BLOCK_LAYERS=28 \
        NNAPI_BLOCKS=4 NNAPI_BLOCK_T=256 NNAPI_BLOCK_NORM=64 NNAPI_BLOCK_EPS=0 \
        NNAPI_CPU_THREADS=4 NNAPI_HEAD_CHUNK=8960 NNAPI_VERIFY=0 \
        NNAPI_WEIGHTS_FILE= NNAPI_NEURON_OPTS= "$@" \
        ./run_nnapi qwen2.5_1.5b.bin -z qwen2.5_1.5b.bin.tokenizer \
        -m chat -y 'Responde en espanol.' -i 'Explica por que el cielo es azul.' \
        -t 0 -s 1 -n 96
}
# One layer per graph; isolate layers 0, 1 and 2, including position 0.
stage4_run NNAPI_BLOCKS=1 NNAPI_BLOCK_LAYERS=3 NNAPI_VERIFY=1 >stage4_single.txt 2>stage4_single.log
# All 28 layers in groups of four, same CPU trajectory for verification.
stage4_run NNAPI_VERIFY=1 >stage4_blocks.txt 2>stage4_blocks.log
# Actual throughput; includes the chunked classifier, excludes CPU verification.
stage4_run >stage4_speed.txt 2>stage4_speed.log
# If layer 0 still fails: verify, then measure with only that layer on CPU.
stage4_run NNAPI_BLOCK_FIRST=1 NNAPI_VERIFY=1 >stage4_first1_verify.txt 2>stage4_first1_verify.log
stage4_run NNAPI_BLOCK_FIRST=1 >stage4_first1_speed.txt 2>stage4_first1_speed.log
# Legacy control and original backend, with the same model/prompt.
stage4_run NNAPI_BLOCK_RESCALE=0 NNAPI_BLOCK_FIRST=3 >stage4_legacy.txt 2>stage4_legacy.log
stage4_run NNAPI_BACKEND=nnapi NNAPI_BLOCK_RESCALE=0 NNAPI_BLOCK_FIRST=3 NNAPI_HEAD_CHUNK=0 >stage4_nnapi.txt 2>stage4_nnapi.log
```

Review graph operation support/CPU fallbacks before attributing throughput to APU.
Compare layer-0 and group 0-3 error at position 0, later positions, classifier
argmax, readable text, and both `nnapi: speed:` lines. Check that FIRST=1 has seven
graphs covering layers 1..27, that no scale output is zero/non-finite, and whether
the extra reductions cost more than the CPU layers they replace. Also validate
RESCALE on NNAPI (including NNAPI_WEIGHTS_FILE), since it was inspected but not run.
