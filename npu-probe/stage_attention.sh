#!/system/bin/sh
# Claude runs this only after transferring the binaries and freeing the phone.
set -eu
cd /data/local/tmp/llm
case "${1:-}" in
    probe) exec env NNAPI_NEURON_OPTS= ./run_nnapi_attention_test --device ;;
    bmm-verify) attention=bmm; verify=1 ;;
    fc-verify) attention=fc; verify=1 ;;
    bmm-speed) attention=bmm; verify=0 ;;
    fc-speed) attention=fc; verify=0 ;;
    *) echo 'Usage: sh stage_attention.sh probe|bmm-verify|fc-verify|bmm-speed|fc-speed [32|256]' >&2; exit 2 ;;
esac
T=${2:-256}
case "$T" in 32) steps=32 ;; 256) steps=96 ;; *) echo 'T must be 32 or 256' >&2; exit 2 ;; esac
# Generation mode avoids chat EOS; check the execution counts in both logs
# since generate can still stop on its sequence delimiter (token 1).
# 32 steps stays below the CPU fallback boundary at pos > T.
exec env NNAPI_BACKEND=neuron NNAPI_DEVICE=mtk-mdla NNAPI_WEIGHT_BITS=8 \
    NNAPI_W8_ISLAND=0 NNAPI_BLOCK_ABLATE= NNAPI_NEURON_OPTS= \
    NNAPI_VERIFY="$verify" NNAPI_BLOCKS=4 NNAPI_BLOCK_FIRST=0 NNAPI_BLOCK_LAYERS=28 \
    NNAPI_BLOCK_RESCALE=3 NNAPI_BLOCK_NORM=64 NNAPI_BLOCK_EPS=0 \
    NNAPI_BLOCK_T="$T" NNAPI_BLOCK_ATTN="$attention" NNAPI_BLOCK_ATTN_DIAG=1 \
    NNAPI_BLOCK_PROBE=0 NNAPI_HEAD_CHUNK=0 NNAPI_CPU_THREADS=4 \
    NNAPI_FREE_AFTER_COMPILE=1 NNAPI_MEM_MARGIN_MB=1500 \
    NNAPI_WEIGHTS_FILE=/data/local/tmp/llm/wf_qwen/q15 \
    ./run_nnapi qwen2.5_1.5b.bin -z qwen2.5_1.5b.bin.tokenizer \
    -m generate -t 0 -s 1 -n "$steps" \
    -i 'Una NPU acelera un modelo de lenguaje porque' < /dev/null
