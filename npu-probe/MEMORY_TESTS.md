# Neuron group memory changes (for Claude)

Implemented without compiling locally or using the phone. `git diff --check`
and `bash -n stage_memory.sh` passed. The C test and hardware results below
are **pending**, not measurements. No quantization, graph math, cache format,
or default NNAPI path was changed.

## Ownership and admission

- Neuron registers matrix descriptors first, then loads only the next group.
  Existing W8/W4 readers load their per-matrix files, or quantize the checkpoint
  on a cache miss. Matrix IDs and unsigned W8 cache bytes are unchanged.
- Referenced Neuron constants must remain valid through `Compilation_finish`.
  After successful finish, execution needs the compilation, input and output
  buffers. `NNAPI_FREE_AFTER_COMPILE=1` (Neuron default) frees W8/W4 arrays,
  scales, auxiliary matrix buffers and builder-owned constants. Model and
  compilation handles retain their original lifetime. `0` retains constants,
  with the retained weights charged against the memory budget.
- Verified by reading the code: normal CPU fallback and `NNAPI_VERIFY` use
  `Matrix*` pointing at the checkpoint, not the released weights. W4 calibration
  reloads/releases each group and head for every calibration token. The probe
  reloads its group before replay. Hashes remain intact. CPU checkpoints and
  both K/V histories are kept. Quantization and numeric operations are unchanged.
- Compilation/build failures destroy the failed model before freeing its
  references. Successful groups are freed before the next group's preparation.
  This relies on the API's constant lifetime contract supplied in the task;
  actual vendor behavior after freeing buffers still needs hardware validation.
- Neuron checks `/proc/meminfo` before setup/weight allocation and again just
  before compilation. Admission uses the smaller of live `MemAvailable` and an
  initial budget minus accumulated graph estimates. Invalid options, unreadable
  memory information or insufficient space stop **all further** compilations;
  already compiled groups continue, the rest use CPU. W4 calibration refusal
  invalidates calibration instead of saving incomplete ranges.
- Margin is `NNAPI_MEM_MARGIN_MB`, default **1500 MiB**. The admission estimate
  includes a source working set, full CPU/block K/V reservations (including lazy
  calloc pages), graph storage, one input-sized compiler scratch allowance and
  fixed allowances. It is an estimate, not an allocation cap inside the driver.
  Other processes can allocate after the check; a driver can exceed the estimate.
- W4 files and preparation arrays still use **one byte per weight**. Expected
  compiled graphs use 0.5 byte/weight (W8: 1), plus 16 MiB per graph. With any
  explicit `NNAPI_NEURON_OPTS`, admission instead reserves 2 bytes/weight because
  accuracy options can expand the graph. Measured compiled sizes can only raise
  the estimated storage and the ratio used for subsequent groups.
- The read-only checkpoint mapping is clean/reclaimable file cache, so its full
  virtual size is logged separately, not counted as pinned RAM. Source reserve
  is the larger of a group and the head, including Q8 group scales. Keeping the
  entire checkpoint resident gives the larger estimates below.

## Estimated peak, not phone measurements

Decimal GB/MB below; logs use MiB. Assumptions: `BLOCKS=4`, `BLOCK_T=256`,
`seq_len=2048`, `HEAD_CHUNK=8960`, no compiler options, Q8 checkpoint with GS=32,
Qwen FIRST=1 and Llama FIRST=0. If the real checkpoint differs, the printed
runtime estimate uses its actual configuration.

Per-layer matrix elements: `P = 2*dim^2 + 2*dim*kv_dim + 3*dim*hidden`.
Head elements: `H = vocab*dim`; active elements `W = active_layers*P + H`.

| Quantity | Qwen 1.5B | Llama 3B |
|---|---:|---:|
| dim / hidden / kv_dim | 1536 / 8960 / 256 | 3072 / 8192 / 1024 |
| P | 46,792,704 | 100,663,296 |
| RAM for four-layer weights (W8 **and** W4) | 187.17 MB | 402.65 MB |
| H, one byte/weight in RAM | 233.37 MB | 394.00 MB |
| W, all active compiled matrices | 1.49678 billion | 3.21257 billion |
| Expected graph payload W8 / W4 | 1.497 / 0.748 GB | 3.213 / 1.606 GB |
| Source working set `max(4P,H)*1.125` | 0.263 GB | 0.453 GB |
| CPU + block K/V `8*28*kv_dim*(2048+256)` | 0.132 GB | 0.528 GB |
| Estimated pressure peak W8 / W4 | **2.63 / 1.88 GB** | **5.25 / 3.64 GB** |
| Including 1500 MiB admission margin | **4.20 / 3.45 GB** | **6.82 / 5.22 GB** |
| Peak if entire checkpoint stays resident, W8 / W4 | 4.36 / 3.61 GB | 8.41 / 6.81 GB |

Peak formula (head compilation is the largest in these examples):
`graph_payload + 2*H + source_working_set + KV + 268,435,456`.
`2*H` is RAM weights plus compiler scratch; the last term is eight graph
allowances of 16 MiB, 64 MiB runtime and 64 MiB temporary allowance. The full
resident calculation substitutes the approximately 2.00/3.61 GB checkpoint
for the source working set. Thus a phone with 5–7 GB available may stop some
Llama W8 groups; W4 has more room. This is deliberately conditional, not a
promise that all graphs fit or that the W4 text is accurate.

## CI checks (not this notebook)

From `npu-probe/` on a Linux runner:

```sh
cc -O1 -g -Wall -Wextra -fsanitize=address,undefined run_nnapi_memory_test.c -o run_nnapi_memory_test -lm -ldl -pthread
./run_nnapi_memory_test
cc -O1 -g -Wall -Wextra -fsanitize=address,undefined run_nnapi_w4_test.c -o run_nnapi_w4_test -lm -ldl -pthread
./run_nnapi_w4_test
```

The new test uses referenced-constant mocks through real group construction:
no eager loading, W8/W4 reload hashes, constants alive at finish, success/failure
cleanup, head release, retention switch and refusal before weight allocation.
It also tests budget/live-memory boundaries and the NNAPI bypass. Mocks cannot
prove that MediaTek's compiler copies constants or bounds its scratch memory.
Build `run_nnapi.c` on the existing Android CI with the new `nnapi_memory.h`
present. Include the existing W4 regression test: quantization/calibration
formats and numbers must stay unchanged.

## Phone checks (Claude, after the user says it is free)

Transfer the new `run_nnapi`, `stage5_w4.sh` and `stage_memory.sh` to
`/data/local/tmp/llm/` using the usual process. In the phone shell:

```sh
cd /data/local/tmp/llm
sh stage_memory.sh llama w8-verify llama_mem_w8
sh stage_memory.sh llama hybrid-verify llama_mem_w4
NNAPI_MEM_MARGIN_MB=1048576 sh stage_memory.sh llama w8-verify llama_mem_refusal
sh stage_memory.sh llama island-verify llama_mem_island
sh stage_memory.sh qwen w8-verify qwen_mem_w8
sh stage_memory.sh qwen hybrid-verify qwen_mem_w4
```

Each writes separate `.run.log` and `.mem.log` files; the background monitor
records MemAvailable and cache/swap information every two seconds, then exits.
The huge-margin case must report CPU fallback without a single compilation.
Use a new `NNAPI_W4_CALIB` path if an island calibration must be collected.
Check `constants released after finish`, memory refusals, verify errors and
classifier argmax agreement; compare identical settings against the old binary.
For retention comparison, use Qwen with `NNAPI_FREE_AFTER_COMPILE=0`; the guard
still applies. After verification, use `hybrid-speed`/`island-speed` to measure
real device execution. VERIFY forces the CPU output trajectory and its text
alone cannot establish W4 quality. Check the reported graph count and generate
unverified text as well. None of these phone checks have been run by Codex.
