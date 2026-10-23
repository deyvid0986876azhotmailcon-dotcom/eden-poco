# Copia de $PREFIX/etc/profile.d/asistente.sh: cada consola nueva de Termux arranca el puente si no está
# corriendo. Así la app (o David) lo levanta con solo abrir Termux; la versión de Google Play no trae RUN_COMMAND.
#   cp ~/asistente_puente/profile_asistente.sh $PREFIX/etc/profile.d/asistente.sh
# The profile can be read twice by the same session: the variable keeps it to one start per session.
if [ -z "${ASISTENTE_ARRANQUE:-}" ] && ! pgrep -f 'node index.cjs' >/dev/null 2>&1 && [ -f "$HOME/.termux/boot/asistente.sh" ]; then
    export ASISTENTE_ARRANQUE=1
    (setsid nohup bash "$HOME/.termux/boot/asistente.sh" >/dev/null 2>&1 </dev/null &)
fi
