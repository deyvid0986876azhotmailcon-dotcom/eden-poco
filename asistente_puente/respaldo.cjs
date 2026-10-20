'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { privateDirectory } = require('./lib.cjs');
const DAY = 86400000;
const HEADER = Buffer.from('SQLite format 3\0');
const dateKey = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;

function rclone(args) {
  return new Promise((resolve, reject) => {
    // No shell and no stdout/stderr: remote errors can contain personal paths.
    const child = spawn('rclone', args, { stdio: 'ignore', windowsHide: true });
    const timer = setTimeout(() => child.kill('SIGKILL'), 10 * 60 * 1000);
    child.once('error', () => { clearTimeout(timer); reject(new Error('rclone no disponible')); });
    child.once('close', code => {
      clearTimeout(timer);
      if (code === 0) resolve(); else reject(new Error('rclone fallo o tiempo agotado'));
    });
  });
}

async function download(config, filename, fetcher = fetch, timeout = 95000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  let fd;
  try {
    const response = await fetcher(`http://127.0.0.1:${config.puerto}/respaldo`, {
      method: 'GET', redirect: 'error', signal: controller.signal,
      headers: { Authorization: `Bearer ${config.token}` }
    });
    const length = response.headers.get('content-length');
    if (response.status !== 200 || response.headers.get('content-type') !== 'application/vnd.sqlite3'
        || !/^[0-9]+$/.test(length || '')) throw new Error('respuesta invalida');
    const expected = Number(length);
    if (!Number.isSafeInteger(expected) || expected < 100 || expected > 1024 ** 3) throw new Error('tamano invalido');
    fd = fs.openSync(filename, 'wx', 0o600);
    let size = 0, prefix = Buffer.alloc(0);
    for await (const chunk of response.body) {
      const bytes = Buffer.from(chunk);
      size += bytes.length;
      if (size > expected) throw new Error('respuesta demasiado grande');
      if (prefix.length < HEADER.length) prefix = Buffer.concat([prefix, bytes.subarray(0, HEADER.length - prefix.length)]);
      let offset = 0;
      while (offset < bytes.length) offset += fs.writeSync(fd, bytes, offset, bytes.length - offset);
    }
    if (size !== expected || !prefix.equals(HEADER)) throw new Error('base invalida o incompleta');
    fs.fsyncSync(fd);
    return size;
  } finally {
    controller.abort(); clearTimeout(timer);
    if (fd !== undefined) fs.closeSync(fd);
  }
}

class Backups {
  constructor(root, config, log, options = {}) {
    this.root = root; this.config = config; this.log = log;
    this.now = options.now || (() => new Date());
    this.fetcher = options.fetcher || fetch; this.run = options.run || rclone;
    this.directory = path.join(root, 'respaldos');
    this.state = path.join(root, 'respaldo-exito.json');
    this.active = null; this.timer = null; this.stopped = false; this.attemptDay = null;
    this.last = 0;
    try {
      const state = JSON.parse(fs.readFileSync(this.state, 'utf8'));
      if (Number.isSafeInteger(state.timestamp) && state.timestamp > 0) this.last = state.timestamp;
    } catch { /* Missing/corrupt state means a fresh backup is required. */ }
  }
  request() {
    if (this.stopped) return Promise.resolve(false);
    if (this.active) return this.active;
    this.attemptDay = dateKey(this.now());
    this.active = this.perform().finally(() => { this.active = null; });
    return this.active;
  }
  async perform() {
    let stage = 'archivo local', temporary;
    try {
      privateDirectory(this.directory);
      const file = path.join(this.directory, `asistente-${dateKey(this.now())}.db`);
      temporary = file + '.part';
      // Only this process owns backups; recover a partial download after a crash.
      fs.rmSync(temporary, { force: true });
      stage = 'app no disponible o base invalida';
      const size = await download(this.config, temporary, this.fetcher);
      stage = 'archivo local';
      fs.renameSync(temporary, file); temporary = null;
      fs.chmodSync(file, 0o600);
      stage = 'rclone copy';
      await this.run(['copy', file, 'drive:Asistente/respaldos']);
      stage = 'rclone delete';
      await this.run(['delete', '--min-age', '30d', 'drive:Asistente/respaldos']);
      stage = 'retencion local';
      const files = fs.readdirSync(this.directory, { withFileTypes: true })
        .filter(entry => entry.isFile() && /^asistente-\d{4}-\d{2}-\d{2}\.db$/.test(entry.name))
        .map(entry => entry.name).sort().reverse();
      for (const name of files.slice(7)) fs.unlinkSync(path.join(this.directory, name));
      stage = 'guardar fecha de exito';
      const timestamp = this.now().getTime();
      const stateTemp = this.state + '.tmp';
      fs.writeFileSync(stateTemp, JSON.stringify({ timestamp }) + '\n', { mode: 0o600 });
      fs.renameSync(stateTemp, this.state);
      this.last = timestamp;
      this.log(`Respaldo exitoso (${Math.ceil(size / 1024)} KB)`);
      return true;
    } catch {
      this.log(`Respaldo fallido: ${stage}`);
      return false;
    } finally {
      if (temporary) try { fs.unlinkSync(temporary); } catch { }
    }
  }
  tick(startup = false) {
    const now = this.now(), day = dateKey(now);
    const [hour, minute] = (this.config.respaldo_hora || '03:00').split(':').map(Number);
    const scheduled = new Date(now); scheduled.setHours(hour, minute, 0, 0);
    const overdue = !this.last || now.getTime() - this.last > DAY;
    const alreadyToday = this.last && dateKey(new Date(this.last)) === day;
    if (this.attemptDay !== day && ((startup && overdue) || (!alreadyToday && now >= scheduled))) return this.request();
    return Promise.resolve(false);
  }
  start() {
    void this.tick(true);
    // Re-evaluate local wall time each minute (timezone changes, sleep and DST included).
    this.timer = setInterval(() => { void this.tick(); }, 60000);
  }
  stop() { this.stopped = true; clearInterval(this.timer); return this.active; }
}

function signalBackup(root, processApi = process) {
  const pid = fs.readFileSync(path.join(root, 'running.lock'), 'utf8').trim();
  if (!/^[1-9][0-9]*$/.test(pid) || !Number.isSafeInteger(Number(pid))) throw new Error('candado invalido');
  processApi.kill(Number(pid), 'SIGUSR1');
}
module.exports = { Backups, download, signalBackup, dateKey, rclone };
