'use strict';
const fs = require('node:fs');
const crypto = require('node:crypto');

const FALLBACK = 'No pude procesar tu mensaje, proba de nuevo';
function configuration(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || Object.keys(value).some(k => !['puerto', 'remitente', 'token', 'respaldo_hora', 'respaldo_script', 'pro_puerto'].includes(k))
      || (value.pro_puerto !== undefined && (!Number.isInteger(value.pro_puerto) || value.pro_puerto < 1024 || value.pro_puerto > 65535 || value.pro_puerto === value.puerto))
      || value.puerto === (value.pro_puerto === undefined ? 8766 : value.pro_puerto)
      || (value.respaldo_hora !== undefined && (typeof value.respaldo_hora !== 'string' || !/^([01][0-9]|2[0-3]):[0-5][0-9]$/.test(value.respaldo_hora)))
      || !Number.isInteger(value.puerto) || value.puerto < 1024 || value.puerto > 65535
      || typeof value.token !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(value.token)
      || typeof value.remitente !== 'string' || !/^[1-9][0-9]{6,14}@s\.whatsapp\.net$/.test(value.remitente)) {
    throw new Error('Configuración inválida: revisá puerto, token y remitente en config.json');
  }
  return Object.freeze({ ...value });
}
function split(text, limit = 1800) {
  if (!Number.isInteger(limit) || limit < 2) throw new Error('Límite inválido');
  const parts = [];
  for (let i = 0; i < text.length;) {
    let end = Math.min(text.length, i + limit);
    // At most limit UTF-16 units AND code points, without breaking surrogate pairs.
    if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1]) && /[\uDC00-\uDFFF]/.test(text[end])) end--;
    parts.push(text.slice(i, end)); i = end;
  }
  return parts;
}
function isStop(text) {
  return typeof text === 'string' && text.normalize('NFD').replace(/[\u0300-\u036f]/g, '').trim().toLowerCase() === 'parar';
}
function candidate(message, type, owner, connectedAt, now = Date.now()) {
  const key = message?.key;
  // WhatsApp now names most private chats by a LID (…@lid) instead of the phone number. Such a
  // message is the owner's only when the phone number WhatsApp's server sends along with it
  // (remoteJidAlt, or senderPn in older builds) is the owner's; Baileys 6.7.22+ stopped letting
  // a sender forge that mapping (the spoofing fixed after 6.7.18).
  const lid = typeof key?.remoteJid === 'string' && /^[0-9]+@lid$/.test(key.remoteJid);
  const fromOwner = key?.remoteJid === owner || (lid && (key.remoteJidAlt === owner || key.senderPn === owner));
  if (!key || key.fromMe !== false || key.participant || !fromOwner) {
    // the log says which kind of id came, never the text or the number
    const kind = typeof key?.remoteJid !== 'string' || !key.remoteJid ? 'sin id' : key.remoteJid.endsWith('@g.us') ? 'grupo'
      : key.remoteJid.endsWith('@broadcast') ? 'difusion/estado' : lid ? `lid sin numero del dueno (alt=${key.remoteJidAlt ? 'otro' : 'falta'})`
      : key.remoteJid.endsWith('@s.whatsapp.net') ? 'otro numero' : 'otro tipo';
    return { reason: `origen no autorizado (${kind})` };
  }
  if (typeof key.id !== 'string' || !/^[A-Za-z0-9_-]{1,120}$/.test(key.id)) return { reason: 'id inválido' };
  let content = message.message;
  // Ephemeral TEXT is allowed; media captions, protocol edits, reactions and quoted text are not orders.
  if (content?.ephemeralMessage) content = content.ephemeralMessage.message;
  const text = content?.conversation ?? content?.extendedTextMessage?.text;
  const audio = content?.audioMessage;
  if (!audio && (typeof text !== 'string' || !text.trim() || text.length > 4000)) return { reason: 'no es texto válido' };
  const timestamp = Number(message.messageTimestamp?.toString());
  if (type !== 'notify' || !connectedAt || !Number.isSafeInteger(timestamp)
      || timestamp < connectedAt || timestamp > Math.floor(now / 1000) + 30) return { reason: 'mensaje antiguo o de historial', id: key.id };
  // the reply goes to the same chat (the LID one when that is how WhatsApp named it): sending to the
  // phone number of a LID chat arrives as "waiting for this message", it cannot be decrypted
  if (audio) {
    try { require('./audio.cjs').metadata(audio); }
    catch { return { reason: 'audio inválido (máximo 5 MB y 3 minutos)', id: key.id }; }
    return { id: key.id, media: message, chat: key.remoteJid };
  }
  return { id: key.id, text, chat: key.remoteJid };
}
class Ledger {
  constructor(filename) {
    this.filename = filename;
    if (!fs.existsSync(filename)) fs.writeFileSync(filename, '', { mode: 0o600, flag: 'wx' });
    fs.chmodSync(filename, 0o600);
    const data = fs.readFileSync(filename, 'utf8');
    // A partial append after a power loss fails closed, rather than forgetting an executed ID.
    if (data && (!data.endsWith('\n') || !/^(?:[a-f0-9]{64}\n)+$/.test(data))) throw new Error('Ledger dañado: requiere revisión manual; no se procesa nada');
    this.seen = new Set(data.split('\n').filter(Boolean));
  }
  claim(owner, id) {
    const hash = crypto.createHash('sha256').update(owner + '\0' + id).digest('hex');
    if (this.seen.has(hash)) return false;
    const fd = fs.openSync(this.filename, 'a', 0o600);
    try { fs.writeSync(fd, hash + '\n'); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
    this.seen.add(hash); return true;
  }
}
async function post(config, message, fetcher = fetch, timeout = 90000, detailed = false) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const audio = message.audio;
    const headers = { Authorization: `Bearer ${config.token}`, 'Content-Type': 'application/json' };
    if (audio) {
      const policy = require('./audio.cjs');
      if (!Buffer.isBuffer(audio.bytes) || !audio.bytes.length || audio.bytes.length > policy.MAX_BYTES
          || !Number.isFinite(audio.seconds) || audio.seconds <= 0 || audio.seconds > policy.MAX_SECONDS
          || !/^[A-Za-z0-9_-]{1,120}$/.test(message.id)) throw new Error('Audio inválido');
      headers['Content-Type'] = policy.mime(audio.mime);
      headers['X-Message-Id'] = message.id; headers['X-Audio-Seconds'] = String(audio.seconds);
    }
    const response = await fetcher(`http://127.0.0.1:${config.puerto}/${audio ? 'audio' : 'mensaje'}`, {
      method: 'POST', redirect: 'error', signal: controller.signal,
      headers,
      body: audio ? audio.bytes : JSON.stringify({ id: message.id, texto: message.text })
    });
    if (!response.ok) throw new Error('Respuesta HTTP fallida');
    // The deadline also covers the body. Bound memory even if a different local process owns the port.
    const reader = response.body.getReader();
    let size = 0; const chunks = [];
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > (detailed ? 4 * 1024 * 1024 : 256 * 1024)) { await reader.cancel(); throw new Error('Respuesta demasiado grande'); }
      chunks.push(Buffer.from(value));
    }
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (typeof body.respuesta !== 'string' || !body.respuesta.trim()) throw new Error('Respuesta inválida');
    return detailed ? body : body.respuesta;
  } catch { return detailed ? { respuesta: message.audio ? require('./audio.cjs').LISTEN_FAILED : FALLBACK } : FALLBACK; }
  finally { clearTimeout(timer); }
}
/** Dispatch immediately to the APP's queue: a local PARAR must also cancel waiting WhatsApp work. */
class DeliveryQueue {
  constructor(process, onFailure) {
    this.process = process; this.onFailure = onFailure; this.tail = Promise.resolve(); this.epoch = 0; this.waiting = 0;
  }
  add(message) {
    const stop = isStop(message.text);
    if (!stop && this.waiting >= 64) return false;
    if (stop) { this.epoch++; this.tail = Promise.resolve(); }
    const epoch = this.epoch; this.waiting++;
    const task = Promise.resolve().then(async () => {
      if (epoch === this.epoch) await this.process(message, () => epoch === this.epoch);
    }).catch(() => this.onFailure()).finally(() => { this.waiting--; });
    this.tail = Promise.all([this.tail, task]).then(() => {});
    return true;
  }
  cancel() { this.epoch++; this.tail = Promise.resolve(); }
}
function privateDirectory(directory) {
  fs.mkdirSync(directory, { recursive: true, mode: 0o700 }); fs.chmodSync(directory, 0o700);
}
module.exports = { FALLBACK, configuration, split, isStop, candidate, Ledger, post, DeliveryQueue, privateDirectory };
