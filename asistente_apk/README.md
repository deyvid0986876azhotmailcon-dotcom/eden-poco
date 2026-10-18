# Asistente — fase 1

App independiente, `dev.poco.asistente`, Java, Android 8/API 26 o posterior,
target/compile SDK 35. No utiliza modelos locales, `chat_apk`, motores NPU,
WhatsApp ni accesibilidad. Interfaz nativa: Chat, Resumen y Ajustes.

## Compilación y pruebas

**No compilar en la notebook del proyecto.** El workflow
`.github/workflows/asistente-apk.yml` usa JDK 17, Gradle 8.7 y AGP 8.6.1:

```sh
# Solo en CI o en otra máquina autorizada, dentro de asistente_apk:
gradle --no-daemon test assembleDebug
```

Los tests JVM usan JUnit y una implementación real de `org.json` exclusivamente
en `testImplementation`; producción usa las clases JSON de Android y
`HttpURLConnection`, sin dependencias externas de red. Las pruebas cubren reglas,
validación/CRUD, códigos de borrado, cancelación, idempotencia, resumen, armado y
lectura del JSON de Gemini y fallback HTTP con transporte simulado. No usan red.
No prueban SQLite Android, Keystore ni la Activity: requieren prueba en teléfono.

Artefactos: `asistente-debug-<run_number>` y `asistente-tests-<run_number>`.
El flujo se dispara por push a main, PR que toque las rutas de esta app o ejecución
manual. No requiere descargar motores ni compilar código nativo.

## Uso sin clave

- `Gasté 3.500,25 en super` / `Cobré 450000 de sueldo`.
- `Peso 58,4` / `Hice sentadilla 3x10 con 40`.
- `Comí arroz con pollo` (sin estimaciones automáticas).
- `Presupuesto 80000 en super 2026-10` / `Meta 500000 para emergencia`.
- `Resumen`, `Ver UUID`, `¿Qué hiciste hoy?`, `Ayuda`.
- `corregir UUID {"centavos":350000}`; admite campos del tipo existente,
  por ejemplo `{"fecha":"2026-10-16","kg":58.4}` para un peso.
- `Borrar UUID`, seguido de `SÍ CÓDIGO` exactamente como indica el ejecutor.
- `PARAR` cancela confirmaciones y descarta la respuesta de una petición en curso.

Los UUID completos se muestran al registrar, en Resumen (últimos 30 registros)
y en el historial de operaciones. Las aclaraciones requieren repetir el pedido
completo. No se persiste el chat; sus últimos 30.000 caracteres quedan en memoria
del proceso. Los registros y los recibos de operaciones sí se persisten.

Las reglas financieras usan coma decimal y punto de miles. No redondean ni
adivinan una categoría ausente. Sin fecha se registra hoy. Cuando una frase
no coincide completamente con las reglas se consulta a Gemini; sin clave se
pide reformular. Las respuestas del modelo no son recibos: solo el ejecutor
local produce la confirmación de un cambio en la base.

## Arquitectura y decisiones

- `AssistantCore`: recibe texto, valida origen, resuelve comandos locales,
  reglas y Gemini. No depende de Activity, Context ni Service. `Source.UNTRUSTED_DATA`
  nunca llega al modelo como orden ni al ejecutor. Solo existe el adaptador local;
  fase 2 deberá agregar el origen remoto después de autenticarlo.
- `ToolExecutor`: lista cerrada de nueve herramientas, tipos estrictos,
  sin coerción de strings a números, campos desconocidos rechazados y validación
  completa también al corregir. Montos: 1 a 99.999.999.999 centavos; monedas
  ARS/PYG/USD/EUR/BRL. Peso corporal: 30–250 kg. Carga de ejercicio: 0–1000 kg;
  series 1–100, repeticiones 1–1000. Fechas 2000–2100, sin registros realizados
  en el futuro; una meta sí puede tener fecha futura. Nutrientes opcionales,
  identificados como estimaciones.
- `AssistantDb`: SQLiteOpenHelper, tabla común `registros` con UUID, tipo,
  versión y payload JSON validado. Los importes se serializan como enteros,
  nunca como valores monetarios de coma flotante. Índice único de presupuesto
  por mes, moneda y categoría; un presupuesto existente se corrige por ID.
  `operaciones` guarda recibos e historial. Mutación + recibo en una transacción,
  actualización/borrado con versión esperada. Repetir el mismo requestId no
  duplica una mutación; escribir el mismo texto como mensaje nuevo sí la repite.
- Borrado: código aleatorio de 64 bits, ligado al actor, UUID y versión,
  vencimiento de cinco minutos con reloj monotónico (`elapsedRealtime` en Android,
  incluye suspensión del teléfono), consumo único. Una propuesta
  nueva sustituye la anterior. Cambiar el registro exige proponer de nuevo;
  reiniciar el proceso o PARAR descarta las propuestas pendientes. La confirmación
  es un comando directo del usuario y no existe como herramienta del modelo.
- `Summary`: calcula saldo mensual, gasto por categoría y presupuesto restante
  por moneda, aviso al 80 %, peso y diferencia respecto del día anterior con
  medición, y ejercicios/series de lunes a hoy. Varias mediciones en un día usan
  la última ingresada. Las metas son objetivos declarados, no un saldo reservado.
- `GeminiProtocol` y `GeminiClient`: REST `v1beta`, function calling, máximo una
  acción por respuesta. Respuestas truncadas, bloqueadas, con varias acciones o
  JSON inválido no se ejecutan. Se devuelve directamente el recibo local, sin una
  segunda ronda al modelo que pueda volver a ejecutar la acción. El contexto es
  un resumen calculado, de hasta 6000 caracteres, no el historial completo.
- Modelo por defecto `gemini-3.5-flash-lite`; fallback:
  `gemini-3.1-flash-lite`, `gemini-3.8-flash`, `gemini-3.5-flash`.
  Si se selecciona otro, se intenta primero y luego los demás en ese orden,
  sin repeticiones. Solo reintenta red/429/503, máximo cuatro intentos.
  Informa los intentos fallidos en el resultado. No hay retry de una mutación.
  PARAR impide pedidos encolados, nuevos intentos y escrituras; la conexión ya enviada puede esperar
  su timeout (conexión 15 s, lectura 30 s). No se usan respuestas HTTP de error
  como texto para el usuario ni se registra la clave en logs.
- `AssistantApplication`: dueño del núcleo y del worker único. Rotar la Activity
  no repite un envío. Una caída del proceso antes de guardar pierde la petición
  pendiente, no deja escrituras parciales. La entrega durable de mensajes remotos
  es trabajo de fase 2.
- `KeyVault`: AES-256-GCM con clave no exportable de Android Keystore, IV nuevo
  y AAD fijo por cifrado. SharedPreferences contiene solo ciphertext e IV.
  Clave Gemini en cabecera HTTPS, nunca en URL ni SQLite. Backups y transferencia
  de datos deshabilitados; pantallas protegidas con FLAG_SECURE. Si Keystore
  deja de poder abrirla, las reglas siguen funcionando y se pide cargarla de nuevo.

## Firma y pendientes

Por instrucción del 17/10 se copia la **clave debug pública** de `chat_apk`.
Esto sustituye, solo para esta fase, el orden anterior de DISENO.md que exigía
firma privada antes de guardar credenciales. Keystore no evita una actualización
maliciosa firmada con esa misma clave pública. Antes de fase 2 se necesita firma
privada y un plan de migración que conserve los datos (no desinstalar a ciegas).

Fase 2: servicio en primer plano `specialUse`, relevo WhatsApp con autenticación
de Meta y número de David, cola durable con ACK/lease, persistencia del mensaje
entrante y requestId estable, reanudación y deduplicación de entregas, verificación
de HyperOS y ventana de 24 horas. No convertir datos remotos en `LOCAL_USER`.

Fase 3: accesibilidad habilitada desde el teléfono con autenticación local,
lista explícita de apps/acciones permitidas, exclusión de bancos/credenciales,
filtrado de pantalla, confirmaciones para acciones sensibles y PARAR por camino
independiente con botón en la notificación.

Validación pendiente de Claude: ejecutar CI tras revisar; comprobar en teléfono
teclado/insets/rotación, persistencia SQLite tras cerrar, guardado/borrado de la
clave, confirmaciones vencidas y modificadas, y una llamada real a Gemini. Los
IDs de modelos y su cuota proceden de la tarea: no se consultó red ni se probó
su disponibilidad desde este entorno.
