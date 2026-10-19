'use strict';
const fs = require('node:fs');
const crypto = require('node:crypto');

const FALLBACK = 'No pude procesar tu mensaje, proba de nuevo';
function configuration(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || Object.keys(value).sort().join(',') !== 'puerto,remitente,token'
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
  return text.normalize('NFD').replace(/[\u0300-\u036f]/g, '').trim().toLowerCase() === 'parar';
}
function candidate(message, type, owner, connectedAt, now = Date.now()) {
  const key = message?.key;
  if (!key || key.fromMe !== false || key.participant || key.remoteJid !== owner) return { reason: 'origen no autorizado' };
  if (typeof key.id !== 'string' || !/^[A-Za-z0-9_-]{1,120}$/.test(key.id)) return { reason: 'id inválido' };
  let content = message.message;
  // Ephemeral TEXT is allowed; media captions, protocol edits, reactions and quoted text are not orders.
  if (content?.ephemeralMessage) content = content.ephemeralMessage.message;
  const text = content?.conversation ?? content?.extendedTextMessage?.text;
  if (typeof text !== 'string' || !text.trim() || text.length > 4000) return { reason: 'no es texto válido' };
  const timestamp = Number(message.messageTimestamp?.toString());
  if (type !== 'notify' || !connectedAt || !Number.isSafeInteger(timestamp)
      || timestamp < connectedAt || timestamp > Math.floor(now / 1000) + 30) return { reason: 'mensaje antiguo o de historial', id: key.id };
  return { id: key.id, text };
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
async function post(config, message, fetcher = fetch, timeout = 90000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetcher(`http://127.0.0.1:${config.puerto}/mensaje`, {
      method: 'POST', redirect: 'error', signal: controller.signal,
      headers: { Authorization: `Bearer ${config.token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: message.id, texto: message.text })
    });
    if (!response.ok) throw new Error('Respuesta HTTP fallida');
    // The deadline also covers the body. Bound memory even if a different local process owns the port.
    const reader = response.body.getReader();
    let size = 0; const chunks = [];
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > 256 * 1024) { await reader.cancel(); throw new Error('Respuesta demasiado grande'); }
      chunks.push(Buffer.from(value));
    }
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (typeof body.respuesta !== 'string' || !body.respuesta.trim()) throw new Error('Respuesta inválida');
    return body.respuesta;
  } catch { return FALLBACK; }
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
