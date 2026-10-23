#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
command -v termux-wake-lock >/dev/null || { echo 'Ejecutá este guion dentro de Termux.' >&2; exit 1; }
termux-wake-lock
trap 'termux-wake-unlock' EXIT
# If node dies on its own (error, out of memory), start it again after 10 s. A clean stop (PARAR, pkill:
# SIGTERM ends in a normal exit 0) ends the loop. Only this loop runs node, so a lock left behind by the
# dead process is stale and can go.
while true; do
    code=0; node index.cjs || code=$?
    [ "$code" -eq 0 ] && break
    echo "=== node salió con código $code; vuelvo a arrancar en 10 s ($(date '+%F %T'))"
    sleep 10
    rm -f "$HOME/.asistente_puente/running.lock"
done
