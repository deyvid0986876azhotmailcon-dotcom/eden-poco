'use strict';
process.umask(0o077);
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { ProRunner, createProServer } = require('./pro.cjs');
const { OwnerChat, PendingPoller } = require('./pendientes.cjs');
const { Guardian } = require('./guardian.cjs');
const { isStop } = require('./lib.cjs');
const { downloadAudio, sendReply, LISTEN_FAILED } = require('./audio.cjs');
const { downloadImage, SEE_FAILED } = require('./imagen.cjs');
const { Backups, signalBackup } = require('./respaldo.cjs');
const { configuration, split, candidate, Ledger, post, DeliveryQueue, privateDirectory, FALLBACK } = require('./lib.cjs');
// Do not print library errors, message objects or auth material to logs.
function log(event) { console.log(`${new Date().toISOString()} ${event}`); }
const root = path.join(os.homedir(), '.asistente_puente');
const auth = path.join(root, 'auth');
const qrFile = path.join(os.homedir(), 'storage', 'downloads', 'asistente_qr.png');
let lockOwned = false, exiting = false, connection = null, reconnect = null, attempts = 0;
let config, ledger, makeWASocket, useMultiFileAuthState, DisconnectReason, downloadMediaMessage, logger, QRCode;
let downloading = 0;
let whatsappState = 'desconectado';
let backups, ownerChat, pending, guardian, pro, proServer;
const queue = new DeliveryQueue(async (message, current) => {
  let response;
  if (message.media) {
    const isImage = message.kind === 'imagen', failed = isImage ? SEE_FAILED : LISTEN_FAILED;
    if (downloading >= 2) response = { respuesta: failed };
    else {
      downloading++;
      const source = connection;
      try {
        const context = { logger, reuploadRequest: source.socket.updateMediaMessage };
        if (isImage) message.image = await downloadImage(message.media, downloadMediaMessage, context);
        else message.audio = await downloadAudio(message.media, downloadMediaMessage, context);
      } catch { response = { respuesta: failed }; log(isImage ? 'Imagen rechazada o descarga fallida' : 'Audio rechazado o descarga fallida'); }
      finally { downloading--; delete message.media; }
      if (!current() || source !== connection || !source?.opened) return;
    }
  }
  if (!response) response = await post(config, message, fetch, 90000, true);
  delete message.audio; delete message.image;
  if (!current()) return;
  if (response.parar === true) { queue.cancel(); pending.pause(); }
  log(response.respuesta === FALLBACK ? 'Fallido: app no disponible o tiempo agotado' : 'Aceptado: respuesta de la app');
  const session = connection;
  if (!session?.opened) { log('Fallido: sin conexión para entregar; no se reintenta'); return; }
  // A voice PARAR cancels the generation, but its acknowledgement may still be delivered.
  const epoch = queue.epoch;
  await sendReply(response, message.chat || config.remitente, session.socket.sendMessage.bind(session.socket),
    () => queue.epoch === epoch && connection === session && session.opened, log);
  log('Aceptado: respuesta enviada a WhatsApp');
}, () => log('Fallido: envío interrumpido; revisar Resumen antes de repetir'));

function clearQr() {
  try { fs.unlinkSync(qrFile); } catch (e) { if (e.code !== 'ENOENT') log('Fallido: no se pudo quitar el PNG del QR'); }
}
function scheduleReconnect() {
  if (exiting || reconnect) return;
  const delay = Math.min(60000, 1000 * 2 ** Math.min(attempts++, 6));
  log(`Reconexión en ${delay / 1000} s`);
  reconnect = setTimeout(() => { reconnect = null; connect().catch(fatal); }, delay);
}
async function connect() {
  if (exiting) return;
  privateDirectory(auth);
  // umask protects new Baileys files; also repair modes from an earlier installation.
  for (const entry of fs.readdirSync(auth, { withFileTypes: true })) if (entry.isFile()) fs.chmodSync(path.join(auth, entry.name), 0o600);
  const { state, saveCreds } = await useMultiFileAuthState(auth);
  if (exiting) return;
  const session = { active: true, opened: false, connectedAt: 0, writes: Promise.resolve(), events: Promise.resolve(), stable: null };
  connection = session;
  // Track Signal key writes too, not just creds.update, before deleting auth on loggedOut.
  const setKeys = state.keys.set.bind(state.keys);
  state.keys.set = data => {
    session.writes = session.writes.then(() => session.active && setKeys(data)).catch(fatal);
    return session.writes;
  };
  try {
    session.socket = makeWASocket({
      auth: state, logger, printQRInTerminal: false, markOnlineOnConnect: false,
      syncFullHistory: false, shouldSyncHistoryMessage: () => false,
      connectTimeoutMs: 30000, defaultQueryTimeoutMs: 30000,
      getMessage: async () => undefined
    });
  } catch { connection = null; scheduleReconnect(); return; }
  session.socket.ev.on('creds.update', () => {
    session.writes = session.writes.then(() => session.active && saveCreds()).catch(fatal);
  });
  session.socket.ev.on('connection.update', update => {
    session.events = session.events.then(async () => {
      if (!session.active || exiting) return;
      if (update.qr) {
        console.log(await QRCode.toString(update.qr, { type: 'terminal', small: true }));
        if (fs.existsSync(path.dirname(qrFile))) {
          try {
            await QRCode.toFile(qrFile, update.qr, { type: 'png', width: 768, margin: 4 });
            log('QR actualizado en ~/storage/downloads/asistente_qr.png');
          } catch { log('Fallido: no se pudo guardar el PNG; usá el QR de la terminal'); }
        }
      }
      if (update.connection === 'open') {
        whatsappState = 'conectado';
        session.opened = true; session.connectedAt = Math.floor(Date.now() / 1000);
        session.stable = setTimeout(() => { attempts = 0; }, 60000);
        clearQr(); log('WhatsApp vinculado; se aceptan únicamente mensajes nuevos del remitente configurado');
      }
      if (update.connection === 'close') {
        whatsappState = update.lastDisconnect?.error?.output?.statusCode === DisconnectReason.loggedOut ? 'desvinculado'
          : whatsappState === 'desvinculado' ? 'desvinculado' : 'desconectado';
        session.active = false; session.opened = false;
        clearTimeout(session.stable); clearQr();
        await session.writes;
        if (connection === session) connection = null;
        const code = update.lastDisconnect?.error?.output?.statusCode;
        if (code === DisconnectReason.loggedOut) {
          queue.cancel();
          // Only the auth directory is disposable; config, dedup IDs and the process lock survive.
          if (path.resolve(auth) !== path.resolve(root, 'auth') || path.dirname(path.resolve(auth)) !== path.resolve(root)) throw new Error('Ruta inválida');
          fs.rmSync(auth, { recursive: true, force: true }); privateDirectory(auth);
          log('Sesión cerrada por WhatsApp; se solicitará un QR nuevo');
        }
        scheduleReconnect();
      }
    }).catch(fatal);
  });
  session.socket.ev.on('messages.upsert', event => {
    if (!session.active || !session.opened || exiting) return;
    for (const message of event.messages || []) {
      try {
        const decision = candidate(message, event.type, config.remitente, session.connectedAt);
        if (decision.id && !ledger.claim(config.remitente, decision.id)) { log('Ignorado: id repetido'); continue; }
        if (decision.reason) { log(`Ignorado: ${decision.reason}`); continue; }
        ownerChat.accept(message, event.type, session.connectedAt);
        if (isStop(decision.text)) { pending.pause(); pro?.cancel(); } else pending.resume();
        log(decision.kind === 'imagen' ? 'Aceptado: imagen privada del remitente autorizado'
          : decision.media ? 'Aceptado: audio privado del remitente autorizado' : 'Aceptado: texto privado del remitente autorizado');
        if (!queue.add(decision)) log('Fallido: cola llena; no se ejecuta ni se reintenta');
      } catch { fatal(); return; }
    }
  });
}
function shutdown(code = 0) {
  if (exiting) return;
  exiting = true; queue.cancel(); pending?.stop(); guardian?.stop(); clearTimeout(reconnect);
  if (connection) {
    connection.active = false; clearTimeout(connection.stable);
    try { connection.socket?.end(new Error('Cierre local')); } catch { }
  }
  if (lockOwned) clearQr();
  // Wait for auth writes before releasing ownership; SIGKILL leaves a lock for manual inspection.
  Promise.allSettled([connection?.writes, backups?.stop(), pending?.running, guardian?.running, proServer?.close()]).finally(() => {
    if (lockOwned) {
      try { fs.unlinkSync(path.join(root, 'running.lock')); } catch { }
    }
    process.exit(code);
  });
}
// require() loads ES modules from Node 22.12 on; import() covers an older Node or a module with top-level await.
async function loadBaileys() {
  try { return require('@whiskeysockets/baileys'); }
  catch (e) { if (e.code !== 'ERR_REQUIRE_ESM' && e.code !== 'ERR_REQUIRE_ASYNC_MODULE') throw e; return import('@whiskeysockets/baileys'); }
}
function fatal() { log('Fallido: error local; revisar configuración, dependencias y permisos. No se continúa para evitar duplicados'); shutdown(1); }
async function main() {
  if (process.argv.includes('--respaldo-ahora')) {
    try { signalBackup(root); log('Respaldo solicitado; consultar el log del puente'); }
    catch { log('Respaldo fallido: no se pudo avisar al puente activo'); process.exitCode = 1; }
    return;
  }
  privateDirectory(root);
  // O_EXCL prevents two processes from sharing Baileys keys or racing the dedup ledger.
  fs.writeFileSync(path.join(root, 'running.lock'), String(process.pid) + '\n', { flag: 'wx', mode: 0o600 }); lockOwned = true;
  const filename = path.join(root, 'config.json'); fs.chmodSync(filename, 0o600);
  config = configuration(JSON.parse(fs.readFileSync(filename, 'utf8')));
  pro = new ProRunner({ log }); proServer = createProServer(config, pro);
  await proServer.start();
  ledger = new Ledger(path.join(root, 'seen.ids'));
  backups = new Backups(root, config, log);
  ownerChat = new OwnerChat(root, config.remitente);
  pending = new PendingPoller(root, config, ownerChat, () => connection, log, fetch, () => whatsappState);
  pending.start();
  guardian = new Guardian(config, ownerChat, () => connection, log);
  guardian.start();
  process.on('SIGUSR1', () => { void backups.request(); });
  process.on('SIGINT', () => shutdown()); process.on('SIGTERM', () => shutdown());
  backups.start();
  // Baileys 6.7.22+ is an ES module (6.7.18 and older let messages be spoofed: do not go back)
  ({ default: makeWASocket, useMultiFileAuthState, DisconnectReason, downloadMediaMessage } = await loadBaileys());
  logger = require('pino')({ level: 'silent' }); QRCode = require('qrcode');
  await connect();
}
main().catch(fatal);
