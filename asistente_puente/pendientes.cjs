'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { candidate } = require('./lib.cjs');
const { documentBody, MAX_RESPONSE } = require('./documentos.cjs');
const ID = /^[A-Za-z0-9_-]{1,120}$/;

function durableAppend(filename, line) {
  const fd = fs.openSync(filename, 'a', 0o600);
  try { fs.writeFileSync(fd, line + '\n'); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
}
class OwnerChat {
  constructor(root, owner) {
    this.filename = path.join(root, 'owner-chat.json'); this.owner = owner; this.chat = owner;
    if (fs.existsSync(this.filename)) {
      fs.chmodSync(this.filename, 0o600);
      const saved = JSON.parse(fs.readFileSync(this.filename, 'utf8'));
      if (saved.owner === owner && (saved.chat === owner || /^[0-9]+@lid$/.test(saved.chat))) this.chat = saved.chat;
    }
  }
  // Re-run the existing authorization policy. An arbitrary JID can never become the destination.
  accept(message, type, connectedAt, now = Date.now()) {
    const accepted = candidate(message, type, this.owner, connectedAt, now);
    if (accepted.reason || !accepted.chat) return false;
    const temp = this.filename + '.tmp';
    const fd = fs.openSync(temp, 'w', 0o600);
    try {
      fs.fchmodSync(fd, 0o600);
      fs.writeFileSync(fd, JSON.stringify({ owner: this.owner, chat: accepted.chat })); fs.fsyncSync(fd);
    } finally { fs.closeSync(fd); }
    fs.renameSync(temp, this.filename); fs.chmodSync(this.filename, 0o600);
    this.chat = accepted.chat; return true;
  }
}
class OutgoingLedger {
  constructor(root, owner) {
    this.filename = path.join(root, 'outgoing.ids'); this.owner = owner; this.states = new Map();
    if (!fs.existsSync(this.filename)) fs.writeFileSync(this.filename, '', { flag: 'wx', mode: 0o600 });
    fs.chmodSync(this.filename, 0o600);
    const data = fs.readFileSync(this.filename, 'utf8');
    if (data && !data.endsWith('\n')) throw new Error('Registro de salientes incompleto');
    for (const line of data.split('\n').filter(Boolean)) {
      const entry = JSON.parse(line);
      if (!ID.test(entry.id) || !['sending', 'sent'].includes(entry.state) || typeof entry.owner !== 'string') throw new Error('Registro de salientes inválido');
      if (entry.owner === owner) this.states.set(entry.id, entry.state);
    }
  }
  state(id) { return this.states.get(id); }
  record(id, state) {
    durableAppend(this.filename, JSON.stringify({ owner: this.owner, id, state }));
    this.states.set(id, state);
  }
}
async function request(config, endpoint, body, fetcher = fetch, whatsapp) {
  const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), 90000);
  try {
    const response = await fetcher(`http://127.0.0.1:${config.puerto}${endpoint}`, {
      method: body === undefined ? 'GET' : 'POST', redirect: 'error', signal: controller.signal,
      headers: { Authorization: `Bearer ${config.token}`, 'Content-Type': 'application/json',
        ...(endpoint === '/pendientes' && whatsapp ? { 'X-WhatsApp': whatsapp } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) })
    });
    if (!response.ok) throw new Error('HTTP fallido');
    const reader = response.body.getReader(); const chunks = []; let size = 0;
    while (true) {
      const { value, done } = await reader.read(); if (done) break;
      size += value.length;
      if (size > (endpoint === '/pendientes' ? MAX_RESPONSE : 256 * 1024)) { await reader.cancel(); throw new Error('Respuesta demasiado grande'); }
      chunks.push(Buffer.from(value));
    }
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } finally { clearTimeout(timer); }
}
class PendingPoller {
  constructor(root, config, chat, session, log, fetcher = fetch, whatsapp = () => session()?.opened ? 'conectado' : 'desconectado') {
    this.whatsapp = whatsapp; this.stopped = false;
    this.config = config; this.chat = chat; this.session = session; this.log = log; this.fetcher = fetcher;
    this.ledger = new OutgoingLedger(root, config.remitente); this.busy = false; this.epoch = 0; this.paused = false;
  }
  pause() { this.epoch++; this.paused = true; }
  resume() { this.paused = false; }
  start() { if (!this.timer) this.timer = setInterval(() => { void this.poll(); }, 60000); }
  stop() { this.stopped = true; this.pause(); clearInterval(this.timer); this.timer = null; return this.running; }
  poll() {
    if (this.busy || this.stopped) return Promise.resolve();
    this.busy = true;
    this.running = this.run().catch(() => this.log('Salientes: fallo de sondeo o entrega; sin texto registrado'))
      .finally(() => { this.busy = false; });
    return this.running;
  }
  async run() {
    const session = this.session(), epoch = this.epoch;
    const current = () => !this.paused && epoch === this.epoch && this.session() === session && session?.opened;
    const status = this.whatsapp();
    if (!['conectado', 'desconectado', 'desvinculado'].includes(status)) throw new Error('Estado inválido');
    const items = await request(this.config, '/pendientes', undefined, this.fetcher, status);
    if (!current()) return;
    if (!Array.isArray(items) || items.length > 20 || items.some(item => !item || typeof item.id !== 'string' || !ID.test(item.id))
        || new Set(items.map(item => item.id)).size !== items.length) throw new Error('Salientes invalidos');
    const bodies = items.map(item => {
      if (Object.hasOwn(item, 'documento')) {
        if (items.length !== 1) throw new Error('Un documento por lote');
        return documentBody(item);
      }
      if (Object.keys(item).sort().join(',') !== 'id,texto' || typeof item.texto !== 'string' || !item.texto.trim() || item.texto.length > 60000)
        throw new Error('Saliente invalido');
      return { text: item.texto };
    });
    const sent = [];
    for (const item of items) {
      if (!current()) return;
      const state = this.ledger.state(item.id);
      if (state === 'sending') {
        // Crash/timeout after handing data to WhatsApp is ambiguous. Never resend or falsely acknowledge.
        this.log(`Salientes: entrega incierta, revisar ${item.id}`); continue;
      }
      if (state !== 'sent') {
        this.ledger.record(item.id, 'sending');
        try {
          await session.socket.sendMessage(this.chat.chat, bodies[items.indexOf(item)]);
          this.ledger.record(item.id, 'sent');
          this.log(`Salientes: enviado ${item.id}`);
        } catch {
          this.log(`Salientes: entrega incierta, revisar ${item.id}`); continue;
        }
      }
      sent.push(item.id);
    }
    if (sent.length && current()) {
      await request(this.config, '/pendientes/confirmar', { ids: sent }, this.fetcher);
      this.log(`Salientes: confirmados ${sent.length}`);
    }
  }
}
module.exports = { OwnerChat, OutgoingLedger, PendingPoller, request };
