# eden-poco

Parches sobre el emulador [Eden](https://git.eden-emu.dev/eden-emu/eden) para probar DRAGON BALL: Sparking! ZERO en un Poco X6 Pro (Dimensity 8300, Mali-G615 MC6).

Este repositorio no contiene el código de Eden. GitHub Actions descarga el commit indicado en `EDEN_COMMIT` desde `eden-emulator/mirror`, aplica en orden los archivos de `patches/` y compila el APK `relWithDebInfo`.

## Uso

1. Añadir o modificar un archivo `patches/NNNN-descripcion.patch` (formato `git diff`, rutas relativas a la raíz de Eden).
2. Hacer `git push` a `main`. La compilación arranca sola; también se puede lanzar a mano en la pestaña **Actions**.
3. Descargar el APK desde los artefactos de la ejecución e instalarlo:

   ```powershell
   adb install -r eden-poco.apk
   ```

## Parches

| Archivo | Qué cambia |
|---|---|
| `0001-geometry-streams-fallback.patch` | Si el driver no soporta geometry streams, el shader emite al stream 0 y descarta los demás en vez de abortar la creación del pipeline. |

Eden se distribuye bajo GPL-3.0-or-later; los parches de este repositorio usan la misma licencia.
