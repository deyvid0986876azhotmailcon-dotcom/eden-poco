# Puente de WhatsApp vinculado para Asistente

Corre en Termux, en el **espacio principal**, junto con Asistente. El WhatsApp
del bot sigue en el Segundo espacio. El puente se vincula como otro dispositivo;
no depende de los avisos del Segundo espacio. Es una integración no oficial,
aceptada por David: WhatsApp puede cambiar el protocolo o bloquear el número del bot.

## Estado de esta entrega

**No instalar la dependencia actual: requiere actualización de seguridad.**
Claude agregó `package-lock.json`, con dependencias Git por HTTPS. En su entrada
de Baileys 6.7.18, el campo `deprecated` advierte de falsificación de mensajes y
exige 6.7.22 o posterior, o 7.0.0-rc12 o posterior (aviso
[GHSA-qvv5-jq5g-4cgg](https://github.com/WhiskeySockets/Baileys/security/advisories/GHSA-qvv5-jq5g-4cgg)).
Codex comprobó ese campo localmente el 2026-10-19; no verificó el aviso por red
ni reprodujo la vulnerabilidad. El filtro de remitente no sustituye el arreglo
de autenticación de la biblioteca.

Claude debe elegir y fijar una versión corregida exacta, regenerar y revisar el
lockfile con red y validar la API y la conexión real. Después, dentro de esta carpeta:

```sh
npm install --package-lock-only --ignore-scripts --no-audit --no-fund
npm ci
npm test
```

Revisar el lockfile generado y sus dependencias Git antes de distribuirlo. No hacer
estos pasos de instalación en la notebook del proyecto: algunas dependencias
pueden intentar compilar. La prueba final de `npm ci` corresponde a Termux arm64.
Las dependencias directas están fijadas: Baileys **6.7.18**, pino **9.6.0**,
qrcode **1.5.4**. Se eligió una versión publicada de la línea 6.7, con API CommonJS
y autenticación multifichero conocidas, sin adoptar una versión candidata de 7.
La versión elegida inicialmente tiene el aviso de seguridad anterior. No se
verificó su compatibilidad con el WhatsApp actual; las pruebas simuladas no
demuestran que pueda vincularse ni recibir mensajes reales.

## Instalación en Termux (después de actualizar y validar Baileys)

1. Instalar/abrir Termux **en el espacio principal**. En su terminal:

   ```sh
   pkg update
   pkg install nodejs-lts git tmux nano
   termux-setup-storage
   ```

   Conceder el acceso a archivos permite guardar el QR en Descargas. Se requiere
   Node 20 o posterior (`node --version`). El QR siempre aparece también en terminal.

2. Copiar esta carpeta completa, incluido `package-lock.json`, a
   `~/asistente_puente` (almacenamiento privado de Termux, no dentro de Descargas).
   Alternativamente, obtenerla del repositorio después de que Claude la publique:

   ```sh
   git clone --depth 1 https://github.com/deyvid0986876azhotmailcon-dotcom/eden-poco.git ~/eden-poco
   cp -R ~/eden-poco/asistente_puente ~/asistente_puente
   cd ~/asistente_puente
   npm ci
   npm test
   ```

   No sustituir `npm ci` por una actualización automática de dependencias. Si
   una dependencia falla en Android arm64, guardar el error de instalación para
   Claude; no se ha validado aún la instalación real en Termux.

3. En Asistente → Ajustes, pulsar **Copiar configuración del puente**.
   Activar **Puente de WhatsApp** y conceder el permiso de notificaciones para
   ver el estado y el botón de apagar. El lector anterior de avisos queda apagado.
   En Termux:

   ```sh
   umask 077
   mkdir -p ~/.asistente_puente/auth
   chmod 700 ~/.asistente_puente ~/.asistente_puente/auth
   cp -n ~/asistente_puente/config.example.json ~/.asistente_puente/config.json
   nano ~/.asistente_puente/config.json
   chmod 600 ~/.asistente_puente/config.json
   ```

   Reemplazar `puerto` y `token` por los valores copiados de la app, conservando
   el campo `remitente`. En ese campo escribir **el número personal autorizado**,
   con código de país, sin `+`, espacios ni guiones, seguido de `@s.whatsapp.net`.
   El número es el del dueño que dará órdenes, no el del bot. El archivo final
   debe tener exactamente `puerto`, `token` y `remitente`. No publicar ni subir
   ese archivo, la sesión ni capturas del QR. El token no se imprime en logs.

4. Arrancar en una sesión persistente:

   ```sh
   cd ~/asistente_puente
   tmux new -s asistente
   bash iniciar.sh
   ```

   En el WhatsApp del bot del **Segundo espacio**: **tres puntos → Dispositivos
   vinculados → Vincular un dispositivo**. Escanear el QR que muestra Termux.
   Como ambos espacios están en el mismo teléfono, hay que mostrar el QR en
   otra pantalla para apuntarle con la cámara: transferir privadamente el PNG
   `~/storage/downloads/asistente_qr.png` o mostrar una captura de la terminal
   en la notebook. Un QR vence: usar el más reciente. No se presupone que los
   espacios compartan Descargas ni que WhatsApp permita importarlo de la galería.
   Al vincularse se elimina el PNG; borrar también cualquier copia externa.

5. Para dejarlo corriendo: en tmux, **Ctrl+B**, luego **D**. Volver con
   `tmux attach -t asistente`. `iniciar.sh` mantiene `termux-wake-lock` y lo libera
   al terminar. En HyperOS permitir actividad en segundo plano y quitar restricciones
   de batería para Termux y Asistente. Esto no garantiza que Android conserve los
   procesos; comprobar con pantalla bloqueada. Tras reiniciar el teléfono, abrir
   Asistente y volver a ejecutar `bash iniciar.sh` en tmux; la sesión guardada evita
   otro QR salvo que WhatsApp la haya cerrado.

Para parar: **Ctrl+C** en la terminal del puente y apagar el interruptor en
Asistente, o usar **Apagar y PARAR** en su notificación. Apagar Asistente cancela
la cola y confirmaciones; no termina por sí solo el proceso de Termux.

## Comportamiento y límites

- HTTP únicamente a `127.0.0.1:8765/mensaje`; puerto copiado de la app. Bearer
  aleatorio de 32 bytes, cifrado con Android Keystore en la app; copia privada
  en el config de Termux. No hay escucha en Wi-Fi, reenvío de puertos ni CORS.
- Solo texto privado entrante del JID exacto configurado. Se ignoran mensajes
  propios, grupos, estados, medios, reacciones, ediciones y terceros; el log no
  incluye texto, número ni id. No se acepta `@lid` ni se confía en un JID alternativo
  para ampliar la lista autorizada. Si WhatsApp entrega solo LID, esta versión
  lo rechazará: Claude debe comprobarlo en el teléfono antes de dar el flujo por útil.
- Solo eventos `notify` con fecha desde la apertura de la conexión actual.
  El historial y mensajes recibidos mientras estaba desconectado se descartan;
  los relojes deben estar correctos. La fecha de WhatsApp tiene resolución de
  segundos. `seen.ids` guarda hashes de IDs, con `fsync` **antes** del POST,
  sin poda; la app también reclama el ID persistentemente antes de encolarlo.
- No hay reintento automático de un pedido o de una respuesta. Una caída entre
  reclamar y ejecutar puede perder ese pedido; una caída después de ejecutar
  puede perder la respuesta. Consultar **Resumen** antes de repetir un gasto.
  El puente admite 64 peticiones en vuelo; el exceso queda marcado y no se ejecuta.
  Envía el POST inmediatamente: los pedidos esperan en la cola de la app, de modo
  que PARAR desde el chat también cancela WhatsApp. La app admite seis pedidos
  del puente pendientes; ante exceso contesta fallo y reserva capacidad para PARAR.
  La app procesa en orden de admisión HTTP; peticiones simultáneas pueden llegar
  en distinto orden. Esperar el código antes de enviar una confirmación.
- La app usa la misma cola y núcleo que el chat. Las confirmaciones con código
  pertenecen al actor WhatsApp; el chat local no puede confirmarlas. `PARAR`
  adelanta la cola, cancela pedidos pendientes y suprime respuestas tardías.
- El POST vence a los 90 s, incluyendo lectura del cuerpo. La app vence su
  espera a los 85 s y ejecuta PARAR sobre **la cola compartida**, también local,
  para impedir mutaciones tardías de Gemini. Un cambio que ya terminó no se
  deshace. Ante fallo/timeout se envía exactamente:
  `No pude procesar tu mensaje, proba de nuevo`.
- Las respuestas se dividen a un máximo de 1800 unidades UTF-16, sin partir
  pares de emojis. Un fallo entre partes no reenvía las partes ya enviadas.
- Reconexión con esperas de 1, 2, 4… hasta 60 s. Tras 60 s conectado se reinicia
  la espera. `loggedOut` borra **solo** `~/.asistente_puente/auth` y solicita un
  QR nuevo; conserva config y deduplicación. La carpeta de sesión es 700 y los
  archivos privados 600. No usar esa cuenta simultáneamente con otro puente.
- `running.lock` impide dos procesos a la vez. Un cierre forzado puede dejarlo:
  comprobar `pgrep -af 'node.*index.cjs'`; **solo si no queda ningún puente vivo**,
  ejecutar `rm -- ~/.asistente_puente/running.lock` y arrancar de nuevo. No borrar
  `seen.ids` para solucionar fallos: permitiría repetir pedidos. Un ledger truncado
  detiene el puente y requiere revisión manual.
- Registro en la app muestra hora y aceptado/fallido, sin texto del pedido ni
  respuesta. Los registros de gastos, etc. siguen guardándose normalmente.
  El lector de avisos anterior conserva sus propios registros y sigue disponible,
  apagado por defecto; no debe habilitarse a la vez.

## Validación pendiente para Claude

`npm test` prueba filtros, deduplicación tras reiniciar, división Unicode, POST,
timeouts y PARAR sin Baileys ni red. También ejecuta el punto de entrada con
Baileys/QR simulados: QR terminal/PNG, eventos reales del adaptador, reconexión,
loggedOut, privacidad del log y exclusión de un segundo proceso. Actions debe ejecutar los tests JVM y construir
el APK; no compilarlo en esta notebook. En el teléfono comprobar instalación arm64,
QR/PNG, persistencia y cierre remoto de sesión, mensaje real del dueño/tercero/grupo,
reconexión sin reproducir pedidos antiguos, confirmación con código, PARAR durante
Gemini, timeout y uso con pantalla bloqueada. No dar esos puntos por verificados
con los tests simulados.

## Arranque automático al prender el teléfono

La versión de Termux de Google Play ejecuta en segundo plano los scripts de `~/.termux/boot/` al
recibir BOOT_COMPLETED (TermuxBootReceiver, en termux-play-store/termux-apps). En Termux:

```sh
mkdir -p ~/.termux/boot
cp ~/asistente_puente/arranque_boot.sh ~/.termux/boot/asistente.sh
chmod 700 ~/.termux/boot/asistente.sh
```

La app Asistente vuelve a levantar su servicio del puente al prender el teléfono si estaba encendido.
En Xiaomi/HyperOS hay que permitir "Inicio automático" a Termux y a Asistente, y dejar su batería
"Sin restricciones". El registro queda en `~/.asistente_puente/puente.log`.
