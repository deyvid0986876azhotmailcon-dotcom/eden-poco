'use strict';
const { spawn } = require('node:child_process');
const DOWN = 'Che, la app del asistente se cerró y no pude abrirla. Abrila un momento así sigo funcionando.';
const BACK = 'Ya volví, perdón la demora.';

function reopen(spawner = spawn) {
  try {
    const child = spawner('am', ['start', '--user', '0', '-n', 'dev.poco.asistente/.MainActivity'],
      { shell: false, stdio: 'ignore', timeout: 10000 });
    child.on('error', () => {});
  } catch { /* Missing am or denied execution must not stop the bridge. */ }
}
function unavailable(error) {
  return ['AbortError', 'TimeoutError'].includes(error?.name)
    || ['ECONNREFUSED', 'ETIMEDOUT', 'UND_ERR_CONNECT_TIMEOUT', 'UND_ERR_HEADERS_TIMEOUT',
      'UND_ERR_BODY_TIMEOUT'].includes(error?.code)
    || (error?.cause && unavailable(error.cause)) === true;
}

class Guardian {
  constructor(config, chat, session, log, { fetcher = fetch, now = () => performance.now(), open = reopen } = {}) {
    Object.assign(this, { config, chat, session, log, fetcher, now, open });
    this.since = null; this.reopened = null; this.attempted = false; this.notified = false;
    this.stopped = false; this.running = null;
  }
  start() {
    if (this.timer) return;
    this.stopped = false;
    this.timer = setInterval(() => { void this.poll(); }, 60000);
  }
  stop() {
    this.stopped = true; clearInterval(this.timer); this.timer = null;
    this.controller?.abort(); return this.running;
  }
  poll() {
    if (this.stopped || this.running) return this.running || Promise.resolve();
    this.running = this.run().catch(() => this.log('Guardian: chequeo fallido'))
      .finally(() => { this.running = null; });
    return this.running;
  }
  async send(text) {
    const session = this.session();
    if (this.stopped || !session?.opened) return false;
    await session.socket.sendMessage(this.chat.chat, { text });
    return true;
  }
  async run() {
    // Health checks remain independent of pending delivery, PARAR and WhatsApp connectivity.
    const controller = new AbortController(); this.controller = controller;
    const timer = setTimeout(() => controller.abort(), 10000);
    let alive;
    try {
      const response = await this.fetcher(`http://127.0.0.1:${this.config.puerto}/pendientes`, {
        method: 'GET', redirect: 'error', signal: controller.signal,
        headers: { Authorization: `Bearer ${this.config.token}` }
      });
      // Any HTTP response proves the server is reachable; HTTP errors are not app closures.
      await response.body?.cancel(); alive = true;
    } catch (error) {
      alive = controller.signal.aborted || unavailable(error) ? false : null;
    } finally { clearTimeout(timer); this.controller = null; }
    if (this.stopped) return;
    const now = this.now();
    if (alive === null) {
      // An unrelated error cannot establish two continuous minutes of refused/timed-out requests.
      this.since = null;
      return;
    }
    if (alive) {
      if (this.since !== null) this.log('Guardian: reabierta');
      this.since = null; this.reopened = null; this.attempted = false;
      if (this.notified && this.session()?.opened) {
        // Claim before sending: an ambiguous WhatsApp error must not cause repeated messages.
        this.notified = false;
        if (await this.send(BACK)) this.log('Guardian: regreso enviado');
      }
      return;
    }
    if (this.since === null) this.since = now;
    if (now - this.since < 120000) return;
    if (this.reopened === null) {
      this.reopened = now; this.log('Guardian: app caida'); this.open(); return;
    }
    if (now - this.reopened < 60000 || this.attempted || !this.session()?.opened) return;
    // One attempt per outage, with no six-hour reminders. Delivery failures are ambiguous.
    this.attempted = true;
    if (await this.send(DOWN)) { this.notified = true; this.log('Guardian: aviso enviado'); }
  }
}
module.exports = { Guardian, reopen, DOWN, BACK };
