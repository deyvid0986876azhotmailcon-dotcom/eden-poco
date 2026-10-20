# Migrar Asistente de firma debug a privada

Guion para Claude; no ejecutado por Codex. Requiere el teléfono libre y el sí
del usuario antes de desinstalar. Solo afecta a `dev.poco.asistente`, nunca a Eden.
Los comandos de abajo son para **Git Bash**, con `adb` en PATH; no usar las
redirecciones binarias de Windows PowerShell 5.1 para los archivos tar.

## Preparar y comprobar la firma

Claude crea y conserva fuera del repositorio el PKCS12, su contraseña y una copia
segura. Alias: `asistente`; contraseña de clave y almacén iguales. Cargar los
secretos de Actions `ASISTENTE_KEYSTORE_B64` (Base64 del PKCS12) y
`ASISTENTE_KEYSTORE_PASS`. No publicar esos valores, el almacén ni los respaldos.
El workflow guarda el PKCS12 temporal con modo 600 y lo elimina al terminar.
Gradle firma el APK intermedio con la debug del repositorio; no necesita una
signingConfig privada ni recibe la contrase?a privada. Tras assembleDebug,
apksigner de build-tools 35.0.0 crea la lineage debug -> privada y vuelve a firmar
con ambos firmantes y `--rotation-min-sdk-version 28`. Android 28+ usa la clave
privada con prueba de rotaci?n v3; Android 26-27 conserva la firma vieja v1/v2.
La capacidad installed-data del certificado anterior permite conservar los datos.

El flujo exige secretos v?lidos y firma v?lida antes de publicar
`asistente-debug-<n>`. Ejecuta `apksigner verify -v --print-certs` y exige v3.
Como esa salida no enumera necesariamente los ancestros, tambi?n lee la prueba
incrustada en el APK ya verificado y exige exactamente los certificados debug y
privado, en ese orden, compar?ndolos con los exportados de los almacenes.
Imprime sus huellas SHA-256; Claude debe comparar la privada con su copia segura.
Las contrase?as se pasan mediante variables de entorno, nunca como valores en
argumentos ni logs. No compilar en esta notebook.

Conservar el PKCS12, la contrase?a y la debug original para las pr?ximas
compilaciones: deben mantener esta misma cadena, no generar otra clave privada.
El APK intermedio de Gradle no es el artefacto distribuible. Conservar tambi?n
el APK anterior; no asumir que se podr? volver a instalar despu?s de la rotaci?n.
La actualizaci?n y la conservaci?n efectiva de credenciales siguen pendientes
de comprobar en el tel?fono Android 16; Codex no las ha ejecutado.

## Respaldar ambos espacios antes de actualizar (recomendado)

Desbloquear ambos espacios. Detener primero el puente en Termux siguiendo su
procedimiento habitual, para que no entren mensajes durante la migración.
Elegir una carpeta privada **fuera del repositorio** y un APK privado ya verificado:

```bash
set -euo pipefail
export MSYS_NO_PATHCONV=1
respaldo="$HOME/asistente-migracion-$(date +%Y%m%d-%H%M%S)"
apk='/c/ruta/fuera/del/repositorio/asistente-privada.apk'
umask 077
mkdir -p "$respaldo"
for usuario in 0 10; do
  adb shell am force-stop --user "$usuario" dev.poco.asistente
  adb shell run-as dev.poco.asistente --user "$usuario" id
  adb exec-out run-as dev.poco.asistente --user "$usuario" \
    tar -cf - databases shared_prefs > "$respaldo/usuario-$usuario.tar"
  tar -tf "$respaldo/usuario-$usuario.tar"
  mkdir "$respaldo/restaurar-$usuario"
  tar -xf "$respaldo/usuario-$usuario.tar" -C "$respaldo/restaurar-$usuario"
  rm -f "$respaldo/restaurar-$usuario/shared_prefs/vault.xml" \
        "$respaldo/restaurar-$usuario/shared_prefs/vault.xml.bak" \
        "$respaldo/restaurar-$usuario/shared_prefs/bridge_vault.xml" \
        "$respaldo/restaurar-$usuario/shared_prefs/bridge_vault.xml.bak"
  tar -cf "$respaldo/restaurar-$usuario.tar" \
    -C "$respaldo/restaurar-$usuario" databases shared_prefs
  tar -tf "$respaldo/restaurar-$usuario.tar"
done
```

No continuar si falla un comando, un espacio no permite `run-as`, o falta una
carpeta: comprobar el estado real con Claude. No sustituir el respaldo del usuario
10 por el del 0. Confirmar que ambos archivos se extraen correctamente, contienen
las bases y preferencias esperadas y que los tar de restauración no incluyen
ningún vault. Copiar toda `databases/`, incluidos archivos `-wal` y `-shm` si los
hay; detener la app antes evita cambios durante la copia. Guardar los tar originales.
Esta copia cubre bases y preferencias, no permisos del sistema ni Android Keystore.

## Camino principal: instalar como actualizaci?n

Con el tel?fono libre, el respaldo comprobado y el APK rotado verificado:

```bash
adb install -r "$apk"
```

Exigir `Success`. Android comparte el APK entre espacios; la actualizaci?n debe
conservar los datos, UID y claves de Android Keystore de cada instalaci?n.
No restaurar los tar ni borrar los vaults: los archivos originales siguen en
la app; las copias sin vault del respaldo son exclusivamente para el plan B.
Abrir Asistente en cada espacio donde est? instalada, comprobar historial,
gastos y ajustes; probar Gemini sin volver a cargar la clave y reiniciar el
puente con su token actual para probar WhatsApp. No arrancar dos servidores en
el mismo puerto. Conservar el respaldo hasta completar las comprobaciones.

Si falla la instalaci?n, detenerse y guardar el error exacto. Revisar certificado
instalado, lineage y versionCode antes de considerar el plan B. Si se instala
pero falla una credencial, detenerse y diagnosticar sin desinstalar.

## Plan B: desinstalar y restaurar, solo con autorizaci?n expl?cita

Este camino pierde las claves de Android Keystore y exige configurar nuevamente
Gemini y el puente. No es un paso autom?tico tras fallar `adb install -r`.

**Punto destructivo: solo después del sí del usuario y de verificar las dos copias.**
Android comparte el APK entre los espacios: hay que quitar la firma vieja de ambos.
No usar `-k` para conservar datos al cambiar de firma.

```bash
adb uninstall dev.poco.asistente
adb install --user 0 "$apk"
adb shell cmd package install-existing --user 10 dev.poco.asistente
```

Comprobar `Success` en cada operación; detenerse ante cualquier error. Si HyperOS
no permite habilitarla así en Segundo espacio, resolverlo desde ese espacio antes
de restaurar. No abrir la app ni arrancar el puente todavía.

```bash
for usuario in 0 10; do
  adb shell am force-stop --user "$usuario" dev.poco.asistente
  adb shell run-as dev.poco.asistente --user "$usuario" id
  adb shell -T run-as dev.poco.asistente --user "$usuario" \
    tar -xf - < "$respaldo/restaurar-$usuario.tar"
  adb shell run-as dev.poco.asistente --user "$usuario" ls -l databases shared_prefs
done
```

`run-as` extrae como el UID de la app de cada usuario, sin copiar propietarios
del espacio anterior. Comprobar la restauración antes de abrir la app. Si falla,
conservar los respaldos y parar; no repetir desinstalaciones a ciegas.

## Solo tras el plan B: recuperar credenciales y comprobar datos

La clave de Gemini y el token del puente están cifrados con **Android Keystore**.
Sus claves se pierden al desinstalar y no están en este respaldo. `vault.xml`
copiado no sirve y **no debe restaurarse**; lo mismo ocurre con
`bridge_vault.xml` y sus `.bak`. La clave privada que firma el APK no recupera
esas claves de cifrado.

En cada espacio que use Asistente, abrir la app y comprobar historial, gastos y
ajustes. Pegar otra vez la clave de Gemini. Al solicitar el token del puente,
`AppSettings.bridgeToken()` genera uno nuevo si no existe `bridge_vault.xml`.
Copiar la configuración del puente desde la app y actualizar `puerto` y `token`
en `~/.asistente_puente/config.json` en Termux, conservando `remitente` (ver el
README del puente). Mantener permisos 600 y reiniciar el puente. No imprimir el
token ni pegarlo en logs. Usar el token del espacio que realmente atiende al puente;
no arrancar dos servidores a la vez en el mismo puerto 8765.

Revisar permisos, acceso a notificaciones y excepciones de batería/arranque en los
dos espacios: la desinstalación puede revocarlos. Probar una consulta de Gemini y
un mensaje de WhatsApp, verificar que los datos anteriores siguen presentes y que
el puente autentica. Conservar copias hasta completar esas comprobaciones. Una
actualización posterior firmada con el mismo PKCS12 debe instalarse con
`adb install -r "$apk"` sin repetir esta migración ni perder credenciales.
