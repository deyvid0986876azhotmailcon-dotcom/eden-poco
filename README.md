# eden-poco

Parches sobre el emulador [Eden](https://git.eden-emu.dev/eden-emu/eden) para probar DRAGON BALL: Sparking! ZERO en un Poco X6 Pro (Dimensity 8300, Mali-G615 MC6).

Este repositorio no contiene el código de Eden. GitHub Actions descarga el commit indicado en `EDEN_COMMIT` desde `eden-emulator/mirror`, aplica en orden los archivos de `patches/` y compila el APK `relWithDebInfo`.

## Uso

Cada ejecución genera un APK por variante, definida en la matriz de `.github/workflows/build.yml`. Ahora son `geom` (parches 0001, 0003, 0004 y 0005, el candidato actual), `all` (todos los parches) y `geom-opt` (los mismos parches que `geom`, compilados como la variante "optimized" de Eden, que se instala con otro nombre de paquete). Las variantes sirven para comparar y saber qué parche causa un comportamiento. Todas comparten identificador de paquete, así que en el teléfono solo cabe una a la vez.

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
| `0002-mali-fp16-workaround.patch` | En drivers ARM desactiva `shaderFloat16` para que los shaders usen fp32. Hipótesis sin confirmar; no forma parte de la variante `geom`. |
| `0003-mali-position-input-struct.patch` | En drivers ARM envuelve la entrada `Position` de los geometry shaders en una estructura, como ya preveía Eden para otros drivers. Evita el cierre del compilador de shaders de Mali. |
| `0004-sane-storage-buffer-size.patch` | No acepta como tamaño de un storage buffer un valor de 64 MiB o más leído junto a su dirección, y avisa en el log cuando se crea un búfer de 256 MiB o más. No bastó para evitar el cierre por un búfer de 2 GiB, pero su aviso mostró el tamaño pedido. |
| `0005-cap-buffer-binding-size.patch` | Limita a 64 MiB lo que puede pedir un enlace de búfer de vértices, índices, transform feedback o textura, y anota en el log cualquier petición de 64 MiB o más con su tipo (también las de DMA, uniformes, storage e indirectos, que no se limitan). Busca evitar el cierre por un búfer de 2 GiB. |

Eden se distribuye bajo GPL-3.0-or-later; los parches de este repositorio usan la misma licencia.
