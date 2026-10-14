# Plan W4 por fila sin grupos en tiempo de ejecucion

Propuesta de Codex, no implementada ni validada en el telefono. Ningun
metodo garantiza recuperar la calidad de W8 en Qwen 1.5B. El criterio de
exito es medido, no el nombre GPTQ/AWQ. Primero corregir el flag W4 del
clasificador descrito en REVIEW_R9.md; mantener W8 como referencia.

## Primera version: GPTQ simetrico, una escala por fila

1. Workflow manual de GitHub Actions con Python, NumPy/PyTorch CPU,
   versiones fijadas, semilla y dataset de calibracion con hash. Procesar
   una capa y una matriz a la vez; no materializar el modelo entero FP32.
   Leer el MISMO checkpoint `.bin` que el telefono; comprobar SHA-256,
   `Config`, `ModelExtras`, `GS`, orden de wq,wk,wv,wo,w1,w3,w2 y bias/RoPE.
   La entrada ya es Q8_0: esta primera version optimiza respecto a esos
   pesos, no recupera los originales anteriores a Q8.
2. Recoger entradas de cada matriz en una trayectoria CPU de referencia,
   con frases variadas en espanol y otros dominios. Separar calibracion y
   evaluacion, sin elegir hiperparametros con el conjunto de evaluacion.
   Guardar H=X^T X/N acumulado por lotes; evitar guardar todas las activaciones.
   Empezar con 32 secuencias de hasta 128 tokens y ampliar solo si mejora.
3. Por cada matriz W[d,n], minimizar ||X(W-Wq)^T||^2 con GPTQ: regularizar
   H con lambda*mean(diag(H)), Cholesky, cuantizar columnas secuencialmente
   y propagar el error a las restantes. Buscar la escala positiva de cada
   fila con clipping ponderado por H, usando enteros en [-7,7]. Mantener
   UNA escala por fila durante la pasada; no cambiarla silenciosamente al
   actualizar errores. Registrar regularizacion y filas/columnas degeneradas.
4. `block_size=128` puede dividir el trabajo de GPTQ para memoria, pero NO
   es un grupo de cuantizacion: no genera escalas por 128 columnas. Si se
   usa orden de activacion, deshacer la permutacion al exportar y comprobar
   Wq contra el orden original. No dejar reordenaciones en el grafo.
5. Empezar con w1/w3, despues ampliar por ablacion de matrices cuantizadas,
   conservando las sensibles en W8. El clasificador se evalua por separado.
   Reportar error de salida sobre activaciones, perplexity y concordancia
   de logits/top-1, ademas del error de pesos. Este ultimo no basta.

Memoria: H de w2 con n=8960 tiene ~306 MiB en FP32 (mas factorizacion y
copias); la matriz grande es ~52,5 MiB FP32 para Qwen. El costo adicional
del modelo/activaciones puede dominar. Medir RSS y tiempo por matriz en el
runner, fijar limites y guardar progreso por matriz como artefactos. Usar
trabajos reanudables; no prometer que todo termine en un unico job gratuito.

## Formato que YA lee prepare_weights_4bit

Un archivo `<prefix>.w4.<id>` por matriz, sin NNAPI_W4_GROUP. No requiere
modificar el consumidor si el encabezado coincide con el checkpoint local.
La estructura actual de Android arm64 tiene 56 bytes; el exportador debe
empaquetar explicitamente little endian con `struct.pack('<8sqqiiiiQd', ...)`.
Confirmar sizeof/offsetof en CI antes de producir artefactos.

| Offset | Tipo | Contenido |
|---:|---|---|
| 0 | 8 bytes | `nnw4mse1` (version actual del lector; ver advertencia abajo) |
| 8 | int64 LE | tamano en bytes del checkpoint del telefono |
| 16 | int64 LE | st_mtime del checkpoint del telefono, segundos |
| 24 | int32 LE | GS del checkpoint fuente |
| 28 | int32 LE | id = capa*7+matriz; clasificador = n_layers*7 |
| 32 | int32 LE | n, columnas |
| 36 | int32 LE | d, filas |
| 40 | uint64 LE | FNV-1a del payload completo descrito abajo |
| 48 | float64 LE | sum(abs(W-Wq))/sum(abs(W)); 0 si toda W es cero |
| 56 | int8[d*n] | enteros [-7,7], orden fila/columna, UN entero por byte |
| 56+d*n | float32 LE[d] | row_scale[i] = 127 * step[i] |

FNV-1a usa inicio `0xcbf29ce484222325`, XOR por byte y multiplicacion por
`0x100000001b3` modulo 2^64. Primero todos los q, despues los bytes exactos
de row_scale. No empaquetar dos nibbles por byte: Neuron empaqueta al
compilar. Cero por fila: q=0, step=0, row_scale=0; el constructor resuelve
la escala positiva del tensor. No usar -8 en esta primera version.

Para preservar exactamente el paso, definir `step_effective` a partir de
los float32 exportados (`row_scale/127`), y evaluar Wq con ese valor. Comprobar
que el redondeo de float32 no introduce no finitos o escalas negativas.

La cabecera se llama `mse1` y el lector imprime 'checkpoint MSE' aunque el
payload venga de GPTQ. El nombre no altera el calculo, pero la procedencia
seria ambigua sin un manifiesto. Usar un prefix NUEVO, por ejemplo
`wf_qwen_gptq_v1/q15`, y un manifiesto JSON con metodo/version/semilla,
SHA-256 del checkpoint, hashes del dataset y archivos, id/n/d/GS,
hiperparametros, metricas, y `checkpoint_size/mtime` del telefono.
No sobrescribir los archivos W8 ni mezclar dos exportaciones en un prefix.

**Compatibilidad imprescindible:** el lector invalida el archivo si size,
mtime o GS no coinciden y vuelve a cuantizar en el telefono. Claude debe
obtener size/mtime del checkpoint ya instalado y pasarlos como datos al job;
el hash SHA-256 del manifiesto debe confirmar que es el mismo contenido.
Alternativa futura: importador que compruebe SHA-256 y reescriba SOLO los
campos size/mtime para el destino. No usar un mtime inventado ni confiar
solo en el tamano. Registrar que todos los archivos entraron por `cache`.

La calibracion W4 de activaciones es OTRO formato (`NNAPI_W4_CALIB`). No
exportarla como si fuera portatil: contiene estructuras C y rangos del
grafo/rescale concreto. Crear una ruta nueva y calibrar en el telefono
con RESCALE=3/FIRST=0 y los nuevos pesos; el hash de pesos invalida los
rangos anteriores. El clasificador usa un archivo W4 completo y rangos
por trozo; los ids de sus trozos no son archivos de pesos independientes.

## AWQ como segunda variante, sin costo extra de operaciones

Si GPTQ por fila no alcanza, buscar escalas positivas por columna s con
activaciones de calibracion, usando W'=W*diag(s), x'=diag(1/s)*x.
Para wq/wk/wv, usar una misma s y plegar 1/s en rms_att_weight; para w1/w3,
una misma s plegada en rms_ffn_weight. Bias de Q/K/V no cambia. Ese plegado
modifica los pesos de normalizacion: NO basta con exportar `.w4` de matrices.
Producir un checkpoint derivado con esos RMSNorm modificados y un manifiesto
de transformaciones, junto a los W4 generados desde el modelo transformado.
Recalibrar y mantener separado el checkpoint original para evaluar.

No plegar arbitrariamente a traves de SiLU, softmax o RoPE: no conmutan
con escalas de columnas generales. Para wo/w2 empezar con GPTQ sin AWQ,
o estudiar un plegado compatible y verificar la identidad en FP32 antes
de cuantizar. Si no existe plegado sin operaciones extra, conservar W8.

## Puertas de aceptacion

- Python contra reconstruccion C del payload: mismo orden, escalas y hashes;
  error GPTQ frente a redondeo actual usando activaciones de evaluacion.
- Identidad de cualquier plegado AWQ antes de cuantizar. Evaluacion frente
  al checkpoint ORIGINAL, no solamente el transformado que lee VERIFY.
- En el telefono: carga de cache comprobada, NNAPI_VERIFY=1 por grupo,
  top-1 del clasificador si se activa, texto sin VERIFY y conjunto de
  evaluacion reservado. Objetivo inicial: acercarse a la referencia W8
  (1,1-1,7% en grupos intermedios); si no, conservar mas matrices W8.
- Mismo numero de grafos en APU, tamano compilado y velocidad alternada con
  W8 a temperatura comparable. Ni GPTQ ni AWQ solucionan por si mismos el
  costo fijo de atencion, ni garantizan 25 tok/s o encajar Llama 3B.
