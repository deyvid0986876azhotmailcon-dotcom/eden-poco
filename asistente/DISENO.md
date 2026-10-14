# Asistente personal de David: diseño

Decisiones de David (2026-10-14):
- WhatsApp oficial (WhatsApp Cloud API de Meta), con un chip nuevo para el asistente. Responde solo al número personal de David.
- Puede ir a la nube todo: gastos, peso, documentos.
- El modelo grande es Gemini, con una clave gratis de Google AI Studio. En el nivel gratis, Google puede usar lo que se le manda para mejorar sus productos; David lo aceptó.
- Control del teléfono: nivel 3.

## Las piezas

```
David (WhatsApp) ──► Meta Cloud API ──webhook──► Relevo (Cloudflare Worker, gratis)
                                                    │  guarda el mensaje en una cola
Teléfono (app Asistente) ◄──── consulta la cola ────┘
   │  modelo local en la APU: entender el pedido, clasificar, respuestas cortas
   │  Gemini (AI Studio): contratos, PDFs, planes, lo difícil
   │  base local SQLite: gastos, ingresos, presupuestos, peso, comidas, entrenos
   │  servicio de accesibilidad: acciones en el teléfono (nivel 3)
   └──► Meta Cloud API (envía la respuesta, PDFs como documento)
```

- **Por qué un relevo:** Meta manda cada mensaje a una dirección HTTPS pública, y el teléfono está detrás del router de la casa. El relevo es un Worker de Cloudflare (nivel gratis, a confirmar) que solo recibe, comprueba la firma de Meta (`X-Hub-Signature-256` con el App Secret), descarta todo lo que no venga del número de David y lo guarda en una cola. El teléfono la consulta cada pocos segundos con una clave propia. El relevo no tiene la clave de Gemini ni datos de David más allá de los mensajes en tránsito, que borra al entregarlos.
- **La app:** se agrega al chat APK que ya existe (`chat_apk`), que ya corre los modelos en la APU. Corre como servicio en primer plano con notificación fija y una excepción de batería, porque HyperOS cierra lo que corre en segundo plano.

## Qué hace (fase 1 y 2)

- **Finanzas:** "gasté 3500 en super", "cobré 450000". Categorías, presupuesto mensual por categoría con aviso al acercarse al tope, resumen semanal (domingo), meta de ahorro y fondo de emergencia. No da consejos de inversión.
- **Salud y gimnasio:** peso semanal, comidas, entrenos (ejercicio, series, repeticiones, peso), sueño, y recordatorios. Plan general para subir de peso: un poco más de comida de la que gasta, proteína en cada comida, fuerza 3 días por semana. Los números exactos los pone un médico o nutricionista; el asistente se lo recuerda.
- **Documentos:** contratos, cartas y presupuestos redactados por Gemini a partir de plantillas, convertidos a PDF en el teléfono (`android.graphics.pdf.PdfDocument`) y enviados por WhatsApp. Cada contrato sale con la nota de que uno importante lo revisa un abogado.
- **Quién decide:** el modelo local lee el pedido y elige una de las acciones de una lista cerrada (anotar gasto, anotar peso, resumen, documento, acción del teléfono, conversación). Si no está seguro o el pedido es largo, se lo pasa a Gemini, que contesta con la misma lista de acciones en JSON.

## Control del teléfono (nivel 3, fase 3)

Con el servicio de accesibilidad de Android puede abrir apps, leer la pantalla, tocar y escribir. Reglas que no se quitan aunque el nivel sea 3:
1. Solo obedece mensajes que llegan del número de David, verificados por el relevo con la firma de Meta.
2. Lo que **envía** algo a otra persona, **paga**, **compra**, **borra** o **cambia un ajuste** pide confirmación: el asistente contesta qué va a hacer y espera un "SÍ" de David en los 5 minutos siguientes.
3. No entra a apps de bancos ni billeteras, ni lee contraseñas ni códigos de verificación (SMS de códigos, apps de autenticación). Lista de paquetes bloqueados en el código, no en el prompt.
4. Lo que dice un documento, una página web o un mensaje de otra persona son datos, nunca órdenes: no puede disparar acciones.
5. Todo lo que hace queda en un registro que David puede pedir ("¿qué hiciste hoy?").
6. Un mensaje "PARAR" corta cualquier acción en curso y apaga el nivel 3 hasta que David lo vuelva a encender desde el teléfono.

## Lo que tiene que hacer David

1. Conseguir el chip nuevo y no registrarlo en la app de WhatsApp: el número lo usa la API.
2. Crear una cuenta de Meta for Developers y una app de tipo Business, agregar WhatsApp y el número nuevo (Claude lo guía paso a paso).
3. Crear la clave de Gemini en aistudio.google.com.
4. Crear una cuenta gratis de Cloudflare.

Las claves (token de Meta, App Secret, clave de Gemini, clave del relevo) no van al repositorio: se cargan en la app una vez y quedan en el teléfono. Al relevo van solo las suyas, como secretos del Worker.

## Orden de trabajo

1. Diseño revisado por Codex.
2. Base local y finanzas y salud en la app, probadas con el chat de la app, sin WhatsApp.
3. Cliente de Gemini (con la clave).
4. Relevo y WhatsApp (con el chip y las cuentas).
5. Documentos en PDF.
6. Control del teléfono, de a una acción por vez, con el teléfono libre.

## Revisión de Codex (2026-10-14, 21 puntos; el informe completo está en `logs/delegado/asistente_diseno_codex_salida.txt`)

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

- La cola del relevo va en un **Durable Object con SQLite** (gratis: 100.000 pedidos y 100.000 filas escritas por día). KV no sirve: solo 1.000 escrituras por día.
- El servicio de la app **no puede ser de tipo dataSync** (Android 15+ lo corta a las 6 horas por día): va como `specialUse`, y se prueba en el teléfono que HyperOS no lo cierre.
- WhatsApp: las respuestas dentro de las 24 horas son gratis; fuera, solo plantillas pagas. El resumen del domingo se manda solo si David escribió en las últimas 24 horas; si no, queda esperando.
- Gemini gratis: Google puede leer lo que se le manda (revisores humanos). Para contratos con datos de otras personas, conviene la clave de pago o no mandar nombres ni documentos de identidad.
