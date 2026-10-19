#!/data/data/com.termux/files/usr/bin/bash
# Copia de ~/.termux/boot/asistente.sh: Termux la ejecuta en segundo plano al prender el telefono.
#   mkdir -p ~/.termux/boot && cp ~/asistente_puente/arranque_boot.sh ~/.termux/boot/asistente.sh
# La salida va a ~/.asistente_puente/puente.log (sin textos de mensajes: el puente no los registra).
umask 077
log="$HOME/.asistente_puente/puente.log"
mkdir -p "$HOME/.asistente_puente" || exit 1
# Despues de un reinicio queda el candado del proceso anterior, que ya no existe: sin el, el puente no arranca.
pgrep -f 'node index.cjs' >/dev/null
case $? in
    0) exit 0 ;; # No rotar el registro de un puente que sigue escribiendo.
    1) rm -f "$HOME/.asistente_puente/running.lock" || exit 1 ;;
    *) echo 'No se pudo comprobar si el puente sigue activo; se conserva el candado.' >&2; exit 1 ;;
esac
# En cada arranque se guarda solo la ultima parte; no limita el crecimiento durante la sesion.
if [ -f "$log" ] && [ "$(wc -c < "$log")" -gt 1000000 ]; then tail -c 200000 "$log" > "$log.tmp" && mv "$log.tmp" "$log"; fi
echo "=== arranque $(date '+%F %T')" >> "$log"
exec bash "$HOME/asistente_puente/iniciar.sh" >> "$log" 2>&1
