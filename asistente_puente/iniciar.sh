#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
command -v termux-wake-lock >/dev/null || { echo 'Ejecutá este guion dentro de Termux.' >&2; exit 1; }
# One loop only. Opening Termux can start this twice at once (seen on 2026-10-23). mkdir is atomic; a lock
# whose owner died (Termux killed) is taken over, and after writing its pid each starter waits a moment and
# keeps going only if the pid file still says itself, so two simultaneous takeovers leave one winner.
loop="$HOME/.asistente_puente/iniciar.lock.d"
if ! mkdir "$loop" 2>/dev/null; then
    sleep 1 # an owner that just created the lock may not have written its pid yet
    owner=$(cat "$loop/pid" 2>/dev/null || true)
    if [ -n "$owner" ] && kill -0 "$owner" 2>/dev/null; then exit 0; fi
    rm -rf "$loop"; mkdir "$loop" 2>/dev/null || exit 0
fi
echo $$ > "$loop/pid"
sleep 2
[ "$(cat "$loop/pid" 2>/dev/null || true)" = "$$" ] || exit 0
# Winner: a bridge started some other way keeps running; otherwise a lock left by a killed one is stale.
if pgrep -f 'node index.cjs' >/dev/null; then exit 0; fi
rm -f "$HOME/.asistente_puente/running.lock"
termux-wake-lock
trap 'termux-wake-unlock; rm -rf "$loop"' EXIT
# If node dies on its own (error, out of memory), start it again after 10 s. A clean stop (PARAR, pkill:
# SIGTERM ends in a normal exit 0) ends the loop. The bridge lock is cleared only when no bridge is running.
while true; do
    code=0; node index.cjs || code=$?
    [ "$code" -eq 0 ] && break
    echo "=== node salió con código $code; vuelvo a arrancar en 10 s ($(date '+%F %T'))"
    sleep 10
    pgrep -f 'node index.cjs' >/dev/null || rm -f "$HOME/.asistente_puente/running.lock"
done
