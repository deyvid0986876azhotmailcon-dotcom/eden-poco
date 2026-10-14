# Atencion por FC: entrega a Claude

Revision previa en `REVIEW_R9.md`. No se corrigieron esos hallazgos ni se
cambio la configuracion de la app. El camino predeterminado sigue siendo
BATCH_MATMUL, RESCALE=3/FIRST=0/W8 no cambia sus operaciones matematicas.
No se compilo ni uso el telefono desde Codex. La aceptacion y la mejora
de velocidad son pendientes, no resultados.

## Cambio

`NNAPI_BLOCK_ATTN=fc`, solo Neuron, separa las cabezas KV. Para cada una:

```
Q [G,HD], K [T+1,HD], V [T+1,HD]
scores  = FULLY_CONNECTED(Q, K, zero[T+1])
weights = SOFTMAX(scores + mask, beta)
mixed   = FULLY_CONNECTED(weights, TRANSPOSE(V), zero[HD])
```

Las cabezas se concatenan en el mismo orden KVH,G,HD. Los dos CONCAT de
cache+token actual se conservan. No duplica las claves G veces. Q ya trae
el factor 1/sqrt(HD); beta conserva el ajuste de RESCALE=1. No cambia
entrada/salida de K/V, historial, renormalizacion ni verificacion.
Todos estos FC usan filtros FLOAT32 dinamicos, no pesos W4/W8 del modelo.

Es una hipotesis medible: evitar la implementacion del BATCH_MATMUL con lote
2 y G=6 puede compensar los SLICE/TRANSPOSE/CONCAT adicionales. Tambien
puede ser mas lento o no compilar. Si falla, el bloque entero queda en CPU
como antes; no presentar esa corrida como una mejora de la APU. Para volver
al original, quitar la variable o usar `NNAPI_BLOCK_ATTN=bmm`.

`NNAPI_BLOCK_ATTN_DIAG=1` imprime formas, dispositivo unico, resultado de la
consulta de soporte y cantidad de operaciones por tipo en cada grafo. No
es un perfilador de precision o dispositivo interno del driver. Un 100%
de soporte indica aceptacion del modelo, no precision efectiva FP16.

## CI y sonda

El workflow `npu-probe.yml` compila/ejecuta el test host en Actions y deja
`attention_host_test.txt`, `run_nnapi_attention_test` Android y
`stage_attention.sh` junto a los binarios del artefacto.

El test ejecuta el constructor real de ambas variantes por CPU replay y lo
compara contra una referencia independiente en double: 40 casos por dos
variantes, MHA/MQA/GQA, KVH=1/2/3, G=1/4/6, HD=8/16/128, T=1/5/7/32/256,
sin historial, historial parcial/lleno y beta=1 o 8. No simula fp16 ni prueba
el ABI del driver. `--device` ademas carga Neuron y compila las dos variantes
aisladas con formas de Qwen: T=32 y 256, 8 calentamientos y 40 ejecuciones,
en orden alternado. Informa minimo, mediana y error frente a double. Cualquier
rechazo o error relativo sintetico >1% da codigo de salida 1. La sonda no
mantiene varias redes cargadas ni requiere un checkpoint. El modo --device
tiene un limite de 180 segundos para no esperar indefinidamente al driver.

Comandos alternativos SOLO en runner Linux, desde `npu-probe/`:

```sh
cc -O2 -Wall run_nnapi_attention_test.c -o run_nnapi_attention_test_host -ldl -lm -pthread
./run_nnapi_attention_test_host
```

## Telefono

Claude debe revisar el diff, compilar en Actions y transferir los tres
archivos nuevos del artefacto a `/data/local/tmp/llm/`: `run_nnapi`,
`run_nnapi_attention_test`, `stage_attention.sh`. Con el telefono libre,
estos comandos son para su shell, no para PowerShell:

```sh
cd /data/local/tmp/llm
chmod 755 run_nnapi run_nnapi_attention_test
sh stage_attention.sh probe > attention_probe.log 2>&1
sh stage_attention.sh bmm-verify 256 > attention_bmm_verify.log 2>&1
sh stage_attention.sh fc-verify 256 > attention_fc_verify.log 2>&1
sh stage_attention.sh bmm-verify 32 > attention_bmm_t32_verify.log 2>&1
sh stage_attention.sh fc-verify 32 > attention_fc_t32_verify.log 2>&1
```

El script fija Qwen `qwen2.5_1.5b.bin` y pesos
`/data/local/tmp/llm/wf_qwen/q15`, EPS=0, RESCALE=3, FIRST=0, 4 capas,
28 capas, 8 bits, sin ablation ni clasificador APU. La sonda sintetica
es un filtro rapido: aunque no mejore aislada, el grafo completo puede
fusionar operaciones de otra manera. Si la sonda rechaza FC, comprobar el
log antes de gastar tiempo en compilar el modelo entero.

Exigir **7/7 grafos**, ausencia de fallback/no finitos y error por grupo
comparable con la base del mismo prompt: alrededor de 1,1-1,7% en los grupos
intermedios segun la medida de Claude; comparar por separado el primero
(~12%) y el ultimo (~4%). El texto de VERIFY es CPU y por si solo NO prueba
calidad de la variante. Repetir sin VERIFY y revisar el texto tambien.

Solo si pasa calidad, alternar a temperatura comparable (verificar la
temperatura por el procedimiento de Claude entre comandos):

```sh
sh stage_attention.sh bmm-speed 256 > attention_bmm_speed_1.log 2>&1
sh stage_attention.sh fc-speed 256 > attention_fc_speed_1.log 2>&1
sh stage_attention.sh fc-speed 256 > attention_fc_speed_2.log 2>&1
sh stage_attention.sh bmm-speed 256 > attention_bmm_speed_2.log 2>&1
```

Comparar los tiempos de cada grupo y el promedio, con igual numero de
ejecuciones y 7/7 grafos. Compilar excluye tiempo de generacion pero calienta
el dispositivo; por eso una sola corrida no basta. No comparar tokens/s
con VERIFY activado. T=32 usa 32 pasos para no cruzar a CPU despues de pos=T;
T=256 usa 96. El generador puede acabar antes si emite su token de parada:
si ocurre, comparar solo corridas con el mismo numero de ejecuciones.

Pendiente: rendimiento real, precision MDLA, una causa interna demostrada
del costo fijo, y RoPE elemental (baja prioridad). Sin mejora repetible,
conservar bmm; no activar fc en la app.

## Comprobaciones realizadas en la notebook

`git diff --check` y `bash -n npu-probe/stage_attention.sh`: sin errores.
Revision estatica de formas, orden de cabezas, tipos de FC/bias, vida de
constantes, mascara y beta. Un calculo numerico independiente reprodujo
el factor 4096 del hallazgo EPS=1. No se ejecutaron los tests C ni la sonda;
no se usaron compilador, red, adb o git push. Los logs de compilacion del
test se guardan en el artefacto de Actions incluso si falla el paso.
