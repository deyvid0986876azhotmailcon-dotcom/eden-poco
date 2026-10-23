#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
command -v termux-wake-lock >/dev/null || { echo 'Ejecutá este guion dentro de Termux.' >&2; exit 1; }
# One loop only: opening Termux can start this twice at once (seen on 2026-10-23). flock is atomic and the
# kernel releases it when the holder dies (Termux killed), so there is no stale lock to guess about; node
# inherits the descriptor, so the lock lasts while the bridge runs. Without flock (pkg install util-linux),
# an mkdir lock is the fallback: weaker under exact races (Codex's review), but the bridge still starts.
if command -v flock >/dev/null; then
    exec 9>"$HOME/.asistente_puente/iniciar.lock"
    flock -n 9 || exit 0
else
    loop="$HOME/.asistente_puente/iniciar.lock.d"
    if ! mkdir "$loop" 2>/dev/null; then
        sleep 1
        owner=$(cat "$loop/pid" 2>/dev/null || true)
        if [ -n "$owner" ] && kill -0 "$owner" 2>/dev/null; then exit 0; fi
        rm -rf "$loop"; mkdir "$loop" 2>/dev/null || exit 0
    fi
    echo $$ > "$loop/pid"; sleep 2
    [ "$(cat "$loop/pid" 2>/dev/null || true)" = "$$" ] || exit 0
    trap 'rm -rf "$loop"' EXIT
fi
# The bridge lock (written by index.cjs with its pid) is stale only if that pid is not a running bridge.
stale() {
    local pid; pid=$(cat "$HOME/.asistente_puente/running.lock" 2>/dev/null || true)
    [ -z "$pid" ] || ! ps -o args= -p "$pid" 2>/dev/null | grep -q 'node index.cjs'
}
stale || exit 0 # a bridge started some other way is already running
rm -f "$HOME/.asistente_puente/running.lock"
termux-wake-lock
trap 'termux-wake-unlock; [ -n "${loop:-}" ] && rm -rf "$loop"' EXIT
# If node dies on its own (error, out of memory, SIGKILL), start it again after 10 s. A stop on purpose ends
# the loop: pkill/SIGTERM (exit 0 after the shutdown handlers, or 143 if it arrives before node set them up)
# and Ctrl+C (130).
while true; do
    code=0; node index.cjs || code=$?
    case "$code" in 0|130|143) break ;; esac
    echo "=== node salió con código $code; vuelvo a arrancar en 10 s ($(date '+%F %T'))"
    sleep 10
    if stale; then rm -f "$HOME/.asistente_puente/running.lock"; else break; fi
done
