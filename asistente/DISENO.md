# Asistente personal de David: diseño

Decisiones de David (2026-10-14):
- Decisión original: WhatsApp Cloud API de Meta, con un chip nuevo. **Sustituida el 2026-10-17:** Meta restringe la publicidad de la cuenta de David y no permite crear el portfolio comercial (dato del encargo de Claude). David eligió una integración no oficial con un número de repuesto, aceptando el riesgo. La API oficial queda para cuando Meta levante la restricción.
- Puede ir a la nube todo: gastos, peso, documentos.
- El modelo grande es Gemini, con una clave gratis de Google AI Studio. En el nivel gratis, Google puede usar lo que se le manda para mejorar sus productos; David lo aceptó.
- Control del teléfono: nivel 3.

## Las piezas

```
David (WhatsApp personal, com.whatsapp)
   │ mensaje al número de repuesto
   ▼
WhatsApp Business (com.whatsapp.w4b), en el mismo Poco
   │ aviso de mensaje nuevo
   ▼
Asistente (APK independiente, dev.poco.asistente)
   │ NotificationListenerService → filtro de remitente → SQLite → cola única
   │ AssistantCore: reglas locales; Gemini cuando las reglas no alcanzan
   │ SQLite: registros, deduplicación, respuestas pendientes y bitácora
   └──► RemoteInput + PendingIntent «Responder» del aviso → WhatsApp Business
```

- **Sin servidor ni cliente que imite WhatsApp Web:** no se usa Cloudflare, sesiones web ni tokens de Meta. El único envío lo realiza la acción de respuesta proporcionada por WhatsApp Business. Esta fase responde texto; no manda PDFs por RemoteInput ni inicia conversaciones sin un aviso.
- **La app:** la fase 1 ya es una app independiente (`asistente_apk`), sin APU ni accesibilidad. La fase 2 agrega un `NotificationListenerService` enlazado por Android y protegido con `BIND_NOTIFICATION_LISTENER_SERVICE`; núcleo y cola pertenecen a `Application`, sobreviven a la rotación de pantalla. No se agrega un servicio `specialUse`: la continuidad depende del listener y del sistema. HyperOS, batería y desconexiones requieren prueba real.

## Mensajería de fase 2 (implementación de Codex, 2026-10-17; pendiente de CI y teléfono)

- **Activación explícita:** «Responder por WhatsApp» empieza apagado. Se guarda el nombre EXACTO del contacto personal de David en WhatsApp Business y, opcionalmente, el teléfono personal E.164. El botón de Ajustes abre `ACTION_NOTIFICATION_LISTENER_SETTINGS`. En Android 13+ una instalación fuera de Play puede requerir Información de la app → menú → «Permitir ajustes restringidos». El permiso del sistema es general; el código descarta cualquier paquete distinto de `com.whatsapp.w4b` antes de extraer contenido.
- **Identidad:** se toma el `Person` del último mensaje de `MessagingStyle` (nombre, clave y URI). Se exige el nombre exacto, sin normalizar mayúsculas, acentos ni espacios. Si `uri` contiene `tel:`, se compara su número con el E.164 configurado; una discrepancia o un `tel:` mal formado rechaza el aviso. Una `Person.key` que ya sea E.164 también se compara; una clave opaca nunca autoriza. Un conflicto entre ambos identificadores rechaza. Si no hay número verificable o no se configuró, se acepta solo el nombre y queda anotado «nombre exacto» en Registro.
- **Límite del nombre guardado:** el nombre de agenda lo elige David en su propio teléfono; un desconocido no puede cambiar esa entrada de contactos a distancia. La hipótesis de este camino es que WhatsApp muestra ese nombre local para contactos guardados y el número para no guardados. **Codex no lo comprobó en esta versión de WhatsApp Business y no es una garantía de Android.** Un nombre de perfil de un desconocido, cambios de formato, sincronización de agenda, contactos duplicados, otro contacto renombrado igual o una cuenta tomada pueden romper esa hipótesis. El listener no recibe una prueba de que el nombre salió de la agenda. No se afirma que un nombre de `Person` por sí solo sea imposible de falsificar: comprobar con un número no guardado cuyo perfil se llame «Yo David» antes de habilitar uso real. El E.164 verificable es preferible, pero su ausencia permite el modo por nombre solicitado.
- **Grupos y mensajes propios:** se descartan `FLAG_GROUP_SUMMARY`, conversaciones marcadas como grupo, títulos de conversación y listas con varios remitentes. Si no se puede determinar que es un chat individual, se rechaza. Si el último mensaje tiene remitente nulo (respuesta propia), no se vuelve a tomar un mensaje recibido anterior. Sin mensajes de `MessagingStyle`, se usa `EXTRA_TEXT`, `EXTRA_TITLE` y `Notification.when` solo con indicador explícito de conversación individual y sin historial de RemoteInput; faltas de metadatos se rechazan. Este criterio puede descartar chats legítimos; se ajustará con evidencia del teléfono, no relajando el filtro a ciegas.
- **Deduplicación persistente:** SQLite v2 agrega `wa_messages` y `wa_log`, conservando tablas y registros v1. El identificador es SHA-256 de clave del aviso + hora del mensaje + SHA-256 del texto, con delimitación inequívoca. Se inserta con clave primaria antes de encolar. Un mismo texto enviado en otro instante es un pedido distinto. Los IDs no se podan; la bitácora conserva 500 eventos, muestra los últimos 200 y nunca guarda texto de rechazados. No se persiste el texto entrante; solo remitente aceptado, estado y respuesta pendiente. Registro muestra hasta 50 respuestas pendientes, con hora y estado.
- **Orden y PARAR:** una sola cola ejecuta los pedidos locales y de WhatsApp en orden de admisión. La recepción y SQLite usan un ejecutor separado para que PARAR no espere a Gemini. PARAR solo se acepta tras filtro y deduplicación; cancela confirmaciones, invalida el trabajo activo y vacía lo encolado. El botón de Chat o Registro hace lo mismo. Cambiar remitente, apagar el interruptor o desconectar el listener cancela la cola. Una conexión HTTP ya enviada puede esperar su timeout; su resultado no ejecutará acciones ni responderá tras la cancelación. Las acciones ya terminadas no se deshacen. Los avisos recibidos antes de la cancelación pero aún sin admitir se descartan.
- **Respuesta:** se elige una acción semántica de respuesta o etiquetada Responder/Reply, con RemoteInput de texto libre y PendingIntent creado por `com.whatsapp.w4b`. La respuesta se guarda antes de enviar y se parte en trozos de hasta 1800 puntos de código Unicode, conservando todo el texto. Tras cada invocación correcta se guarda solo el resto pendiente. Sin acción o ante fallo queda visible en Registro, para copiar manualmente. No se reintenta por cuenta propia: «enviada a Responder» significa que Android aceptó el PendingIntent, no que WhatsApp entregó el mensaje. Algunos PendingIntent podrían ser de un solo uso; las partes restantes quedarían pendientes.
- **Interrupciones:** al reiniciar, los estados en cola/procesando/enviando pasan a interrumpidos y se pide revisar Resumen. No se recuperan órdenes ni se reenvían respuestas automáticamente. Puede perderse un pedido si el proceso muere después de deduplicarlo; también puede haber una respuesta enviada pero sin recibo local de envío. Los avisos solo exponen el último mensaje y no son una cola fiable: una ráfaga puede ocultar mensajes anteriores. No se garantiza entrega ni ejecución exactamente una vez. La operación del núcleo usa un ID estable y un actor `WHATSAPP_OWNER` separado de `LOCAL_USER`, incluidas sus confirmaciones de borrado.
- **Riesgos aceptados:** integración no oficial, posible bloqueo del número de repuesto y cambios de WhatsApp que rompan avisos o acciones. Chats abiertos, notificaciones desactivadas/silenciadas, contenido oculto, falta de red y cierres del sistema pueden impedir recibir o contestar. No se releen notificaciones antiguas al conectar. El permiso es sensible y se puede revocar desde Android. La firma debug pública sigue en el proyecto: la firma privada y la migración de datos requieren una tarea de Claude antes de distribución/uso estable.
- **API oficial aplazada:** cuando Meta levante la restricción, reconsiderar Cloud API con verificación de firma y número, cola durable y reglas oficiales de plantillas/ventana. Esas garantías y restricciones no se trasladan automáticamente al listener actual.

## Qué hace (fase 1 y 2)

- **Finanzas:** "gasté 3500 en super", "cobré 450000". Categorías, presupuesto mensual por categoría con aviso al acercarse al tope, resumen semanal (domingo), meta de ahorro y fondo de emergencia. No da consejos de inversión.
- **Salud y gimnasio:** peso semanal, comidas, entrenos (ejercicio, series, repeticiones, peso), sueño, y recordatorios. Plan general para subir de peso: un poco más de comida de la que gasta, proteína en cada comida, fuerza 3 días por semana. Los números exactos los pone un médico o nutricionista; el asistente se lo recuerda.
- **Documentos:** contratos, cartas y presupuestos redactados por Gemini a partir de plantillas, convertidos a PDF en el teléfono (`android.graphics.pdf.PdfDocument`) y enviados por WhatsApp. Cada contrato sale con la nota de que uno importante lo revisa un abogado.
- **Quién decide:** el modelo local lee el pedido y elige una de las acciones de una lista cerrada (anotar gasto, anotar peso, resumen, documento, acción del teléfono, conversación). Si no está seguro o el pedido es largo, se lo pasa a Gemini, que contesta con la misma lista de acciones en JSON.

## Control del teléfono (nivel 3, fase 3)

Con el servicio de accesibilidad de Android puede abrir apps, leer la pantalla, tocar y escribir. Reglas que no se quitan aunque el nivel sea 3:
1. Solo obedece al remitente autorizado mediante el filtro de fase 2. Antes de habilitar accesibilidad/nivel 3 hay que revisar si la identificación disponible es suficiente: el modo por nombre no equivale a la verificación de número y firma de Meta del diseño original.
2. Lo que **envía** algo a otra persona, **paga**, **compra**, **borra** o **cambia un ajuste** pide confirmación: el asistente contesta qué va a hacer y espera un "SÍ" de David en los 5 minutos siguientes.
3. No entra a apps de bancos ni billeteras, ni lee contraseñas ni códigos de verificación (SMS de códigos, apps de autenticación). Lista de paquetes bloqueados en el código, no en el prompt.
4. Lo que dice un documento, una página web o un mensaje de otra persona son datos, nunca órdenes: no puede disparar acciones.
5. Todo lo que hace queda en un registro que David puede pedir ("¿qué hiciste hoy?").
6. Un mensaje "PARAR" corta cualquier acción en curso y apaga el nivel 3 hasta que David lo vuelva a encender desde el teléfono.

## Lo que tiene que hacer David

1. Registrar el número de repuesto en WhatsApp Business en el Poco; mantener el número personal en WhatsApp normal.
2. Guardar el número personal en la agenda con un nombre único y configurar ese nombre exacto y su E.164 en Asistente.
3. Dar acceso a notificaciones y activar «Responder por WhatsApp». Escribir desde el WhatsApp personal al número de Business; revisar Registro.
4. Crear/cargar la clave de Gemini en AI Studio si se quieren consultas que las reglas locales no resuelven.

La clave de Gemini no va al repositorio; se carga en la app y queda cifrada con Android Keystore. Este camino no requiere claves de Meta ni de un relevo.

## Orden de trabajo

1. Diseño revisado por Codex.
2. Base local y finanzas y salud en la app, probadas con el chat de la app, sin WhatsApp.
3. Cliente de Gemini (con la clave).
4. WhatsApp Business mediante avisos y RemoteInput; revisar el código, ejecutar JVM/compilación en Actions y probar filtros, respuesta y PARAR en el teléfono.
5. Documentos en PDF.
6. Control del teléfono, de a una acción por vez, con el teléfono libre.

## Revisión de Codex (2026-10-14, 21 puntos; el informe completo está en `logs/delegado/asistente_diseno_codex_salida.txt`)

**Registro histórico del diseño con Cloud API.** Lo relativo al relevo, firma de Meta, ACK/lease, servicio en primer plano y ventana de 24 horas queda aplazado y no describe la fase 2 actual. Siguen las restricciones del ejecutor y las decisiones de seguridad no sustituidas arriba.

Cambios que se adoptan:
- **Firma de la app:** hoy se firma con una clave de depuración pública. Antes de guardar claves en el teléfono, la app pasa a una clave de firma privada (secreto de GitHub Actions y copia local fuera del repositorio).
- **Un ejecutor que no es el modelo:** el modelo (local o Gemini) solo propone una acción en JSON. Un código aparte valida el esquema, los argumentos, los permisos y de dónde salió el pedido. Lo que provenga de un documento, una página o la pantalla nunca llega al ejecutor como orden.
- **Confirmaciones atadas a una operación:** cada "SÍ" va con un código de la operación (destinatario, contenido o importe, vencimiento a los 5 minutos, un solo uso). Si cambia algo, hay que confirmar de nuevo.
- **Lista de lo permitido, no de lo prohibido:** en nivel 3 se habilitan apps y acciones de a una. La pantalla se filtra antes de mandarla al modelo: códigos, contraseñas y bancos (también en el navegador) no salen.
- **Relevo con dos claves:** una para que Meta deje mensajes (verificada con la firma HMAC-SHA256 del App Secret sobre el cuerpo original, el `phone_number_id` esperado y `messages[].from` igual al número de David) y otra para que el teléfono lea y confirme. La del teléfono no puede escribir mensajes. Las dos se pueden cambiar sin tocar la otra.
- **Cola sin pérdidas:** el relevo entrega con plazo; el mensaje se borra cuando el teléfono confirma que lo guardó. Cada mensaje tiene su ID y no se procesa dos veces (no se duplican gastos ni envíos).
- **PARAR por su propio camino:** se revisa antes de cada paso de una acción y también hay un botón en la notificación del teléfono.
- **Robo:** el nivel 3 se enciende solo desde el teléfono con huella o PIN; las claves van en el Android Keystore; desde la web de Meta y de Cloudflare se pueden revocar.
- **Servicio separado de la pantalla:** el motor, la cola y la base corren en un servicio en primer plano, no en la Activity.
- **Ventana de 24 horas de WhatsApp:** fuera de las 24 horas desde el último mensaje de David, el asistente solo puede escribir con plantillas aprobadas por Meta, que pueden tener costo. El resumen del domingo y los recordatorios usan plantillas, o esperan a que David escriba.
- **Finanzas con código, no con el modelo:** "gasté 3500 en super" se interpreta con reglas fijas primero; el modelo solo ayuda si las reglas no alcanzan, y si algo es ambiguo, pregunta.

A comprobar antes de programar esas partes (pedido a Gemini, `logs/delegado/verificacion_asistente.md`): alta y tokens de Meta, ventana de 24 horas y costos, envío de documentos; cuotas gratis de Cloudflare (KV, Durable Objects, D1); servicio en primer plano y accesibilidad para una app instalada fuera de Play en este Android.

## Verificado en la documentación oficial (2026-10-14, `logs/delegado/verificacion_asistente.md`)

Datos históricos aportados por el proyecto, no vueltos a verificar por Codex en esta tarea sin red. Cloudflare y los límites de la API oficial no se usan en el camino actual.

- La cola del relevo va en un **Durable Object con SQLite** (gratis: 100.000 pedidos y 100.000 filas escritas por día). KV no sirve: solo 1.000 escrituras por día.
- El servicio de la app **no puede ser de tipo dataSync** (Android 15+ lo corta a las 6 horas por día): va como `specialUse`, y se prueba en el teléfono que HyperOS no lo cierre.
- WhatsApp: las respuestas dentro de las 24 horas son gratis; fuera, solo plantillas pagas. El resumen del domingo se manda solo si David escribió en las últimas 24 horas; si no, queda esperando.
- Gemini gratis: Google puede leer lo que se le manda (revisores humanos). Para contratos con datos de otras personas, conviene la clave de pago o no mandar nombres ni documentos de identidad.

## Memoria y mensajes proactivos (Codex, 2026-10-20; pendiente de CI y teléfono)

- SQLite v3 migra v1/v2 conservando registros: conversación (últimos diez pares, solo texto de David y respuesta final), libreta (100 entradas de hasta 200 caracteres), recordatorios (50 activos), salientes y calendario del aviso de WhatsApp. Cada pedido a Gemini incluye historial `user/model` recortado a 4000 caracteres y la libreta como JSON citado, DATOS y nunca órdenes. `olvidá la conversación` vacía ese historial; Resumen permite vaciarlo y borrar entradas de la libreta con confirmación.
- `recordar` valida categoría, tamaño, secretos numéricos/tarjetas/credenciales e instrucciones; origen y fecha los fija el ejecutor. El filtro es conservador y no demuestra ausencia de cualquier secreto o instrucción posible. El prompt limita la extracción a datos estables de David del mensaje actual y excluye datos de terceros. `qué sabés de mí` y `olvidá ID` son locales; `olvidar` y `cancelar_recordatorio` requieren el código existente, ligado al actor y con vencimiento. No se admite corregir estas tablas por la herramienta genérica.
- Horarios en `America/Asuncion`, resueltos con las reglas de zona del sistema: fecha única `AAAA-MM-DDTHH:mm`, o `diaria`/días ISO `1,3,5` (lunes=1) con `HH:mm`. Un sondeo tardío prepara una sola ocurrencia vencida y adelanta la próxima; no reproduce cada día perdido. Una fecha única sigue activa hasta confirmar su entrega o cancelarla. El aviso del Segundo espacio se inicia diez días después de crear/migrar la base y se repite diez días después de cada preparación.
- Resúmenes diario y dominical optativos, inicialmente apagados, a las 21:30. Incluyen cuentas calculadas localmente, presupuestos, peso y entrenos; Gemini aporta solo la frase final, sin herramientas, y ante falta de clave/cupo/red se usa una frase fija. Se recupera la última fecha diaria vencida y el domingo hasta la siguiente hora programada del lunes; no se reconstruyen semanas atrasadas. Silencio 23:00–07:00 tanto al preparar como al ofrecer, salvo recordatorios pedidos a una hora nocturna. Resúmenes largos se recortan a 12000 caracteres indicando que el detalle está en la app.
- Sin alarmas Android: Termux consulta cada 60 s `GET /pendientes`; mismo Bearer, loopback y análisis HTTP estricto. Devuelve `[{id,texto}]`, hasta veinte mensajes y 192 KiB; `POST /pendientes/confirmar {ids:[...]}` confirma solo IDs ofrecidos. Inserción y avance del calendario son atómicos; las ofertas sin ACK reaparecen tras diez minutos. El texto saliente se quita al confirmar/cancelar; Registro muestra tipo, ID y estado sin contenido. Los IDs entregados se conservan para deduplicar.
- El puente conserva el último chat del dueño validado por el filtro actual (teléfono o LID) en `owner-chat.json`, y estados de envío en `outgoing.ids`, ambos dentro de `~/.asistente_puente`, modo 600. Sin chat previo usa el teléfono de config. Un ACK perdido se repite sin volver a enviar. **Límite deliberado:** una caída/timeout entre registrar intención y confirmar el envío a WhatsApp queda «entrega incierta» en el log del puente, no se reenvía ni se marca entregada automáticamente. Se prioriza evitar duplicados; no se garantiza entrega exactamente una vez. Requiere revisión de Claude, sin borrar el ledger.
- PARAR invalida resultados de Gemini, cancela la cola saliente preparada y pausa la generación en el proceso; un nuevo pedido aceptado la habilita. Si PARAR llegó por WhatsApp, el sondeo del puente espera otro mensaje autorizado por WhatsApp. Los recordatorios futuros se conservan. Un envío ya entregado a Baileys no se puede retirar. Apagar el servicio también cancela la cola preparada.
- Comprobaciones de Codex: pruebas Node sin red para sondeo, identidad persistida, pérdida de ACK, reinicio, fallo ambiguo y PARAR; pruebas JVM agregadas para validación, historial/prompt, calendario/zona/silencio, resúmenes y endpoints. Compilación/JVM y uso real quedan para CI/Claude; no se compiló ni se usó adb en esta notebook.
