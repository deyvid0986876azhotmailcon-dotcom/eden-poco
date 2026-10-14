# Revision de la noche, Codex, 2026-10-14

Base revisada: `3f1ec5c`. Las lineas de esta seccion son las de esa base,
antes de agregar el experimento de atencion. Revision estatica, sin red,
compilacion ni telefono. Los hallazgos siguientes NO se corrigen en esta tarea:
la parte de programacion se limita al experimento de atencion.

## Hallazgos, por gravedad

1. **P1 COMPROBADO — clasificador W4 convertido en W8 al construirlo.**
   `nnapi_blocks.h:1674-1676` y `1743-1745`: las estructuras `slice` no llevan
   `.w4`. `prepare_weights_4bit` tampoco establece ese campo; solo lo hace
   `prepare_weights_block` (`nnapi_memory.h:160`). Desde `06eb028`,
   `bb_linear_gain` decide tipo e isla por `g->w4` (`387`, `396`). El
   clasificador sigue perdiendo calidad al cuantizar sus valores a 4 bits,
   pero se declara QUANT8 y, sin W8_ISLAND, omite la isla. No invalida las
   medidas anteriores a ese commit; si invalida llamarlo W4 real despues.
   Corregir tanto la preparacion como la propagacion a los dos `slice`.

2. **P1 COMPROBADO — colision de cache NNAPI entre grafos diferentes.**
   `nnapi_blocks.h:867`: RESCALE=2 y RESCALE=3 comparten `blocks-4` y pesos;
   ABLATE tampoco entra en el token. NNAPI puede reutilizar el grafo sin
   safe_norm, o uno con partes omitidas al quitar ABLATE. Afecta solo al
   backend NNAPI con cache; Neuron recompila y las medidas indicadas usan
   Neuron. Separar version/opciones del grafo en el token.

3. **P2 COMPROBADO — EPS=1 y RESCALE=3 no conservan RMSNorm.**
   `nnapi_blocks.h:271-281`, `1290`: x se divide por 64 pero el epsilon
   externo no se divide por 4096. El epsilon efectivo queda 4096 veces mayor.
   Ademas, EPS=1 con renorm arrastra el alpha inicial entre capas aunque
   cambie alpha (problema heredado de RESCALE=2). La app usa EPS=0; su caso
   x/64 y BLOCK_EPS/4096 si es algebraicamente equivalente. El test existente
   `run_nnapi_w4_test.c:166` suministra epsilon ya dividido, al contrario del
   llamador real, por lo que no detecta este error.

4. **P2 COMPROBADO — W8_ISLAND no se activa con WEIGHT_BITS=8.**
   `nnapi_w4.h:25`: retorno antes de leer W8_ISLAND (`46`). Solo funciona con
   WEIGHT_BITS=4, MODE=island y una seleccion MATS que deje matrices en W8.
   `nnapi_w4_calib.h:28` tambien exige w4.enabled. Para interpretar una
   medida de W8_ISLAND hace falta conservar el entorno exacto utilizado.

5. **P2 COMPROBADO — estimacion de memoria baja en modo mixto W4/W8.**
   `nnapi_memory.h:28`: graph_ratio inicial es 0.5 para cualquier seleccion
   MATS, incluso si todas las matrices del bloque quedan en W8. El primer
   grupo se admite con una estimacion de red menor que la real. La lectura
   del tamano compilado corrige la cuenta despues, no el pico de ese grupo.
   No prueba un reinicio; usar 1.0 conservador si hay mezcla.

6. **P2 COMPROBADO — el test W4 quedo desactualizado.**
   `run_nnapi_w4_test.c:81-88`: varia w4.enabled pero nunca g->w4; las
   aserciones de QUANT4 fallan desde 06eb028. No ejecutar ese test como prueba
   de la nueva seleccion sin adaptarlo. El experimento trae su propio test.

## Grupos W4, ablation y app

- **COMPROBADO:** la cuenta de `nnapi_blocks.h:370` no tiene un factor 7/127
  perdido. Antes de corregir cada fila, la salida del FC es
  `group_largest * sum(q*x)`. Si `abs(x)<=in_range`, la cota usada es valida.
  Luego `group_scale/group_largest` recupera la escala de esa fila y se
  suman los grupos; el bias se agrega una sola vez.
- **SOSPECHA:** esa cota de peor caso reduce la resolucion de la salida.
  Su paso por grupo es `in_range*group_largest*worst/32767`, frente al rango
  observado en el modo por fila. No basta para explicar por si sola el
  22-78%: no conocemos la precision interna de requantizacion del driver.
  Con redondeo ideal sin saturacion, el error absoluto adicional por fila
  queda acotado por `in_range/(2*32767) * sum(group_scale*worst)`.
- **COMPROBADO:** cada grupo agrega SLICE, QUANTIZE, FC, DEQUANTIZE, MUL y
  casi siempre ADD. La menor calidad y velocidad medidas justifican retirar
  NNAPI_W4_GROUP del camino recomendado. Propongo quitarlo en otro cambio,
  conservando los logs y la evidencia; no lo elimine en esta revision.
- `ABLATE=a` deja operaciones desconectadas en el modelo; su eliminacion
  depende del compilador. Con `m`, los scores con mascara -10000 se usan
  como pesos sin normalizar: la salida puede desbordarse y algunas redes
  fallan. Es una medicion diagnostica, no un costo aislado de SOFTMAX.
- No encontre errores nuevos en seleccion de modelos/neuronFirst/
  neuronRescale/neuronBlocks ni en su traslado al ProcessBuilder. Revisados
  `383a34d`, `ef6ed50` y el contexto de lanzamiento; no toda la app.
- Revisados los diffs de `2ad4533`, `15d94ad`, `383a34d`, `74bb43f`,
  `3963204`, `06eb028`, `27f5cb2`, `ef6ed50`, `f6d2fc0`, `3f1ec5c`, y los
  seis archivos del motor pedidos. El control de memoria previo no es una
  prueba de limites del driver; no revise componentes externos a ese alcance.

## Que se puede afirmar del costo de atencion

`neuron_api.h:82` selecciona mtk-mdla y `blocks_finish` pasa un unico
dispositivo a Compilation_createForDevices. No hay seleccion de otra CPU
por nuestro programa dentro de un grafo; un bloque rechazado entero si va
a CPU. Todos los tensores de atencion se declaran FLOAT32 y el modelo pide
relajacion a FP16: eso permite reducir precision, no informa la precision
efectiva ni el reparto interno del driver. No se puede confirmar desde el
codigo que cada operacion corra en FP16 o en una unidad fisica determinada.

Para Qwen, los BATCH_MATMUL son [2,6,128] x [2,T+1,128]^T y
[2,6,T+1] x [2,T+1,128]. SOSPECHA: conversiones/distribucion del lote pequeno
y filtros dinamicos dominan sobre el producto. La independencia respecto a
T entre 32 y 256 es compatible con ello, pero no demuestra la causa.
El experimento FC por cabeza KV conserva el producto y permite medir otra
implementacion del driver. Los filtros siguen siendo dinamicos; el MDLA
puede rechazarlos o ejecutarlos mas despacio. No se anuncia ninguna ganancia
antes de medir en el telefono.
