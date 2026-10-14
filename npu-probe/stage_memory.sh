#!/system/bin/sh
# Claude: run with the phone free, after transferring this and stage5_w4.sh.
# Usage: sh stage_memory.sh llama w8-verify [log-prefix]
set -eu
cd /data/local/tmp/llm
model=${1:-llama}
mode=${2:-w8-verify}
prefix=${3:-memory_${model}_${mode}}
export NNAPI_FREE_AFTER_COMPILE=${NNAPI_FREE_AFTER_COMPILE:-1}
export NNAPI_MEM_MARGIN_MB=${NNAPI_MEM_MARGIN_MB:-1500}
monitor=
cleanup() {
    if [ -n "$monitor" ]; then
        kill "$monitor" 2>/dev/null || :
        wait "$monitor" 2>/dev/null || :
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
(
    while :; do
        date '+%s'
        awk '/^(MemAvailable|MemFree|Cached|SReclaimable|SwapFree):/ {print}' /proc/meminfo
        sleep 2
    done
) > "$prefix.mem.log" &
monitor=$!
result=0
sh stage5_w4.sh "$model" "$mode" > "$prefix.run.log" 2>&1 || result=$?
printf 'exit=%s; inference=%s.run.log; memory=%s.mem.log\n' "$result" "$prefix" "$prefix"
exit "$result"
