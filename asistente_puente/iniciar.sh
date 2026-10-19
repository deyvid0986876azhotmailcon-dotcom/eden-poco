#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
command -v termux-wake-lock >/dev/null || { echo 'Ejecutá este guion dentro de Termux.' >&2; exit 1; }
termux-wake-lock
trap 'termux-wake-unlock' EXIT
node index.cjs
