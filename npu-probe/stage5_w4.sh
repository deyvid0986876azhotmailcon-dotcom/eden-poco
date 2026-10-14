#!/system/bin/sh
# Run in a phone shell after Claude has transferred the new binary and script.
set -eu
cd /data/local/tmp/llm
case "${1:-}" in
    qwen) model=qwen2.5_1.5b.bin; prefix=/data/local/tmp/llm/wf_qwen/q15; first=1 ;;
    llama) model=llama3_2_3b.bin; prefix=/data/local/tmp/llm/wf_l3/l3; first=0 ;;
    *) echo 'Usage: sh stage5_w4.sh qwen|llama hybrid-verify|island-verify|hybrid-speed|island-speed|w8-verify' >&2; exit 2 ;;
esac
calib=${NNAPI_W4_CALIB:-$prefix.w4.calib.r2.f$first.c8960}
bits=4
case "${2:-}" in
    hybrid-verify) mode=hybrid; verify=1 ;;
    island-verify) mode=island; verify=1 ;;
    hybrid-speed) mode=hybrid; verify=0 ;;
    island-speed)
        mode=island; verify=0
        if [ ! -f "$calib" ]; then echo "Run island-verify first: missing $calib" >&2; exit 2; fi ;;
    w8-verify) bits=8; mode=hybrid; verify=1 ;;
    *) echo 'Unknown test mode' >&2; exit 2 ;;
esac
env NNAPI_BACKEND=neuron NNAPI_DEVICE=mtk-mdla NNAPI_WEIGHT_BITS="$bits" \
    NNAPI_W4_MODE="$mode" NNAPI_W4_CALIB="$calib" NNAPI_W4_MARGIN=2 \
    NNAPI_W4_CALIB_TOKENS=8 NNAPI_NEURON_OPTS= NNAPI_VERIFY="$verify" \
    NNAPI_BLOCKS=4 NNAPI_BLOCK_FIRST="$first" NNAPI_BLOCK_LAYERS=999 \
    NNAPI_BLOCK_RESCALE=2 NNAPI_BLOCK_NORM=64 NNAPI_BLOCK_EPS=0 \
    NNAPI_BLOCK_T=256 NNAPI_BLOCK_PROBE=0 NNAPI_HEAD_CHUNK=8960 \
    NNAPI_CPU_THREADS=4 NNAPI_WEIGHTS_FILE="$prefix" \
    ./run_nnapi "$model" -z "$model.tokenizer" -t 0 -s 1 -n 96 \
    -m chat -y 'Responde en espanol de forma clara.' \
    -i 'Explica que es una NPU y como acelera un modelo de lenguaje.' < /dev/null
