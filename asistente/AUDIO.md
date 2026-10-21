# Audios por WhatsApp

Implementación retomada por Codex el 2026-10-20. No modifica la rama `llamada`.
MainActivity agrega únicamente la preferencia de voz; AssistantCore agrega
`transcribe`, sin cambiar las herramientas ni sus confirmaciones.

## Entrada

El filtro de identidad existente acepta `audioMessage`, con o sin `ptt`, también
dentro de `ephemeralMessage`. Desconocidos, grupos, mensajes propios e historial
siguen rechazados. El ledger se reclama antes de descargar; un fallo no reintenta
el mismo ID automáticamente. Hay como máximo dos descargas simultáneas, con plazo
de 20 segundos, streaming y tope real de 5.000.000 bytes. Se exige una duración
declarada positiva de hasta 180 segundos; no se confía solo en esa declaración.

`POST /audio` usa el servidor loopback y Bearer existentes. Cuerpo binario,
Content-Length obligatorio, `X-Message-Id` y `X-Audio-Seconds`; rechaza headers
duplicados, chunked, tipos desconocidos, cuerpos vacíos/truncados y excesos.
Tipos admitidos: audio/ogg, mpeg, mp3, mp4, aac, wav, x-wav, flac, webm. Android
MediaMetadataRetriever verifica la duración real antes de admitir el pedido. Un
tipo declarado permitido no garantiza que Android pueda leer el archivo.

Para medir, Android escribe un temporal en la caché privada y lo borra en finally.
Una muerte abrupta del proceso puede dejar ese temporal en caché; no se incluye
en el respaldo de la base. No se guardan binarios/base64 en historial ni bitácora.

La transcripción usa inlineData con el modelo de conversación configurado y su
fallback existente. No recibe historial, códigos pendientes ni herramientas.
Después el texto literal entra en `receive` con actor WHATSAPP_OWNER, ID estable
y generación de cancelación: mismas reglas, contexto, libreta, herramientas y
validación que el texto escrito. Esto puede consumir dos peticiones Gemini antes
del TTS. La transcripción puede equivocarse; no se afirma exactitud de reconocimiento.
Vacío, inaudible, herramienta inesperada, más de 4000 caracteres, falta de clave
o fallo de transcripción producen el mensaje fijo solicitado sin ejecutar órdenes.

PARAR escrito cancela durante la descarga, transcripción y generación de voz.
PARAR hablado se reconoce solo cuando llega su turno y termina la transcripción;
también se acepta la puntuación «Parar.»/«Parar!». Cancela los demás pedidos antes
de liberar su acuse y avisa al puente con `parar: true` para pausar proactivos.
No usar voz como vía de parada inmediata o sin conexión.

## Salida

Preferencia persistente `audio_reply_mode`: nunca / audio (por defecto) / siempre.
Se aplica solo a respuestas solicitadas por el puente, no al chat local ni a
recordatorios/resúmenes proactivos. Las respuestas conservan siempre el texto.

GeminiSpeech solicita AUDIO, voz Kore y lectura joven/cálida en español paraguayo.
Intenta en orden los modelos pedidos: gemini-3.8-flash-lite-tts,
gemini-3.8-flash-tts y gemini-3.1-flash-tts-preview. Cambia de modelo ante
404/429/503 o IOException; errores de autenticación no causan más intentos.
**No comprobado:** existencia/disponibilidad de estos modelos con la clave real,
voz resultante y compatibilidad efectiva del MIME de respuesta. No hubo red.

Solo acepta un candidato completo STOP y un bloque PCM de 24 kHz, mono, 16 bits,
hasta 60 segundos; respuestas de más de 2000 caracteres quedan solo escritas.
La respuesta HTTP añade opcionalmente
`audio: {format: "pcm_s16le", sampleRate: 24000, channels: 1, data: "BASE64"}`.
La generación de voz tiene hasta 15 segundos y comparte un presupuesto de 80 s
desde la entrada HTTP con el pedido principal; si no alcanza, entrega el texto.
El pool permite hasta dos solicitudes TTS; no crea una cola ilimitada.

El puente valida PCM y ejecuta ffmpeg sin shell ni temporales, por stdin/stdout:
PCM s16le/24000/mono → libopus/32k/voip → OGG, con timeout de 15 s y salida hasta
5 MB. Envía `ptt: true` al mismo chat autorizado (también LID). Si la conversión
falla, solo texto y aviso en el log. Un fallo de envío no reintenta automáticamente
para evitar duplicados; un envío ya iniciado no se puede retirar con PARAR.

## Verificación

Comprobado por Codex: suite Node sin red, 42 pruebas aprobadas, con descarga,
ffmpeg y WhatsApp simulados. Incluye bytes/duración declarada, stream vacío/grande,
timeout de descarga, PCM de 60 s, conversión fallida, envío ptt + texto, cancelación,
filtro de identidad y dedup a través del cableado real de index.cjs. También pasan
las pruebas anteriores de respaldo, proactivos y ciclo de conexión.

Preparado, **no ejecutado por prohibición de compilar en la notebook**:
AudioHttpTest (binario, límites y autenticación), AudioProtocolTest (inlineData,
modos, PCM, fallback TTS), AudioInboxTest (reglas/historial/dedup, contexto y
herramientas, confirmación ligada al actor, errores y PARAR).
El workflow existente `asistente-apk.yml` ejecuta todas las pruebas JVM.

Claude, después de revisar y unir la rama de llamada:

1. Ejecutar en CI `gradle --no-daemon test assembleDebug` desde `asistente_apk`.
2. Con teléfono libre, instalar ffmpeg en Termux (`pkg install ffmpeg`), actualizar
   puente/app y reiniciar el puente sin borrar configuración, ledger ni sesión.
3. Probar nota OGG/Opus y archivo de audio; comprobar gasto registrado una vez y
   transcripción en historial. Probar 180 s y más de 180 s, formato corrupto,
   límite de 5 MB, remitente ajeno y repetición del mismo ID.
4. Probar los tres modos, respuesta con monto/código escrito, falta de clave/cupo,
   modelo TTS no disponible y ffmpeg ausente. Escuchar y confirmar PCM/Opus real.
5. Probar PARAR escrito durante audio lento y TTS, PARAR hablado, confirmación
   por código y apagado del puente. Revisar que no aparezcan acciones tardías.
