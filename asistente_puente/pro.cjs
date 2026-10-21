'use strict';
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const MODELS = Object.freeze(['gemini-3.8-flash-high', 'gemini-3.8-flash-medium', 'gemini-3.8-flash-low',
  'claude-sonnet-4-6', 'claude-opus-4-6-thinking', 'gpt-oss-120b-medium']);
const MAX_PROMPT = 24000, MAX_BODY = 32768, MAX_OUTPUT = 65536;
class Failure extends Error {
  constructor(code, status = 503) { super('Pro no disponible ahora.'); this.code = code; this.status = status; }
}
function validate(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || Object.keys(value).some(k => !['tipo', 'prompt', 'modelo'].includes(k))
      || !['charla', 'busqueda'].includes(value.tipo) || typeof value.prompt !== 'string'
      || !value.prompt.trim() || value.prompt.includes('\0') || Buffer.byteLength(value.prompt) > MAX_PROMPT)
    throw new Failure('PEDIDO_INVALIDO', 400);
  const modelo = value.modelo === undefined
    ? (value.tipo === 'charla' ? 'claude-opus-4-6-thinking' : 'gemini-3.8-flash-high') : value.modelo;
  if (!MODELS.includes(modelo)) throw new Failure('MODELO_INVALIDO', 400);
  return { tipo: value.tipo, prompt: value.prompt, modelo };
}
function killGroup(child) {
  // Termux/POSIX: detached creates a process group, including CLI terminal children.
  try { process.kill(-child.pid, 'SIGKILL'); }
  catch { try { child.kill('SIGKILL'); } catch { } }
}
class ProRunner {
  constructor({ home = os.homedir(), env = process.env, spawnProcess = spawn, kill = killGroup,
    log = () => {}, deadlines = { charla: 60000, busqueda: 35000 } } = {}) {
    this.home = home; this.spawn = spawnProcess; this.kill = kill; this.log = log; this.deadlines = deadlines;
    this.env = { HOME: home, PATH: path.join(home, '.local', 'bin') + path.delimiter + (env.PATH || '/system/bin'),
      TMPDIR: env.TMPDIR || path.join(home, '.cache'), LANG: env.LANG || 'C.UTF-8' };
    this.cwd = path.join(home, 'charla'); this.waiting = []; this.active = null; this.closed = false;
  }
  run(input, signal) {
    let request;
    try { request = validate(input); } catch (e) { return Promise.reject(e); }
    if (this.closed || signal?.aborted) return Promise.reject(new Failure('CANCELADO'));
    if (this.active && this.waiting.length >= 2) return Promise.reject(new Failure('OCUPADO', 429));
    return new Promise((resolve, reject) => {
      const job = { request, resolve, reject, signal, started: Date.now(), done: false, child: null };
      job.abort = () => this.fail(job, new Failure('CANCELADO'));
      signal?.addEventListener('abort', job.abort, { once: true });
      job.timer = setTimeout(() => this.fail(job, new Failure('TIEMPO_AGOTADO', 504)), this.deadlines[request.tipo]);
      this.waiting.push(job); this.next();
    });
  }
  settle(job, error, text) {
    if (job.done) return;
    job.done = true; clearTimeout(job.timer); job.signal?.removeEventListener('abort', job.abort);
    this.log(`Pro: ${error ? 'fallo' : 'éxito'} (${Date.now() - job.started} ms)`);
    if (error) job.reject(error); else job.resolve(text);
  }
  fail(job, error) {
    if (job.done) return;
    this.settle(job, error);
    if (job.child) this.kill(job.child);
    else { this.waiting = this.waiting.filter(j => j !== job); if (this.active === job) this.active = null; this.next(); }
    // An active child keeps the slot until 'close'; never overlap a killed but unreaped CLI.
  }
  next() {
    if (this.active || this.closed) return;
    const job = this.waiting.shift(); if (!job) return;
    this.active = job;
    try {
      fs.mkdirSync(this.cwd, { recursive: true, mode: 0o700 });
      if (fs.lstatSync(this.cwd).isSymbolicLink() || !fs.statSync(this.cwd).isDirectory()) throw new Error();
      fs.chmodSync(this.cwd, 0o700);
      const { tipo, prompt, modelo } = job.request;
      // -p is the documented, already tested interface; stdin prompt semantics are unknown.
      // This is a single argv element (not a shell command), never logged or written by us.
      const child = this.spawn(path.join(this.home, '.local', 'bin', 'agy'),
        ['--mode', 'plan', '--sandbox', '--disable-slash-commands', '--print-timeout',
          `${Math.ceil(this.deadlines[tipo] / 1000)}s`, '--model', modelo, '--output-format', 'text', '-p', prompt],
        { cwd: this.cwd, env: this.env, shell: false, detached: true, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
      job.child = child;
      let size = 0; const chunks = [];
      const output = (chunk, stdout) => {
        if (job.done) return;
        const bytes = Buffer.from(chunk); size += bytes.length;
        if (size > MAX_OUTPUT) { this.fail(job, new Failure('SALIDA_EXCESIVA')); return; }
        if (stdout) chunks.push(bytes); // stderr, including startup warnings, is never logged.
      };
      child.stdout.on('data', chunk => output(chunk, true));
      child.stderr.on('data', chunk => output(chunk, false));
      child.on('error', () => this.fail(job, new Failure('CLI_NO_DISPONIBLE')));
      child.on('close', code => {
        const text = Buffer.concat(chunks).toString('utf8').trim();
        this.settle(job, code === 0 && text ? null : new Failure('CLI_FALLO'), text);
        if (this.active === job) this.active = null;
        this.next();
      });
    } catch { this.fail(job, new Failure('CLI_NO_DISPONIBLE')); }
  }
  cancel() {
    const jobs = [...this.waiting]; if (this.active) jobs.push(this.active);
    // Remove waiting jobs before killing the active one (mock or OS may close immediately).
    this.waiting = [];
    for (const job of jobs) this.fail(job, new Failure('CANCELADO'));
  }
  close() { this.closed = true; this.cancel(); }
}
function authorized(header, token) {
  // Hashing makes the buffers equal length for timingSafeEqual, including malformed credentials.
  const digest = value => crypto.createHash('sha256').update(value).digest();
  return crypto.timingSafeEqual(digest(typeof header === 'string' ? header : ''), digest(`Bearer ${token}`));
}
function createProServer(config, runner) {
  if (typeof config.token !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(config.token)) throw new Failure('CONFIG_INVALIDA');
  const sockets = new Set(), headerTimers = new WeakMap();
  const server = http.createServer({ maxHeaderSize: 8192 }, (req, res) => {
    clearTimeout(headerTimers.get(req.socket));
    const controller = new AbortController(); let ended = false;
    const send = (status, body) => {
      if (ended || res.destroyed) return;
      ended = true;
      const bytes = Buffer.from(JSON.stringify(body));
      res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': bytes.length,
        Connection: 'close', 'Cache-Control': 'no-store' }); res.end(bytes);
    };
    const fail = e => send(e.status || 503, { codigo: e.code || 'NO_DISPONIBLE', mensaje: 'Pro no disponible ahora.' });
    const deadline = setTimeout(() => { controller.abort(); fail(new Failure('TIEMPO_AGOTADO', 504)); }, 65000);
    const bodyDeadline = setTimeout(() => { controller.abort(); fail(new Failure('CUERPO_LENTO', 408)); }, 5000);
    res.on('close', () => { clearTimeout(deadline); clearTimeout(bodyDeadline); controller.abort(); });
    req.on('aborted', () => controller.abort()); req.on('error', () => controller.abort());
    if (req.socket.remoteAddress !== '127.0.0.1') return fail(new Failure('ORIGEN_INVALIDO', 403));
    if (!authorized(req.headers.authorization, config.token)) return fail(new Failure('NO_AUTORIZADO', 401));
    if (req.method !== 'POST') return fail(new Failure('METODO_INVALIDO', 405));
    if (req.url !== '/pro') return fail(new Failure('RUTA_INVALIDA', 404));
    if (req.headers.origin || !/^application\/json(?:;\s*charset=utf-8)?$/i.test(req.headers['content-type'] || ''))
      return fail(new Failure('FORMATO_INVALIDO', 400));
    if (Number(req.headers['content-length']) > MAX_BODY) return fail(new Failure('CUERPO_EXCESIVO', 413));
    let size = 0; const chunks = [];
    req.on('data', chunk => {
      if (ended) return;
      size += chunk.length;
      if (size > MAX_BODY) { fail(new Failure('CUERPO_EXCESIVO', 413)); return; }
      chunks.push(chunk);
    });
    req.on('end', async () => {
      clearTimeout(bodyDeadline); if (ended) return;
      let body;
      try { body = validate(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
      catch (e) { fail(e instanceof Failure ? e : new Failure('PEDIDO_INVALIDO', 400)); return; }
      try { send(200, { texto: await runner.run(body, controller.signal) }); } catch (e) { fail(e); }
    });
  });
  server.maxConnections = 8; server.maxRequestsPerSocket = 1;
  server.headersTimeout = 5000; server.requestTimeout = 5000; server.keepAliveTimeout = 1;
  server.on('connection', socket => {
    sockets.add(socket); socket.on('close', () => sockets.delete(socket));
    const headerTimer = setTimeout(() => socket.destroy(), 5000); headerTimers.set(socket, headerTimer);
    // Also bounds clients that never send complete headers (Node's sweeper is coarse).
    const timer = setTimeout(() => socket.destroy(), 70000);
    socket.on('close', () => { clearTimeout(timer); clearTimeout(headerTimer); });
  });
  return { server,
    start() { return new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(config.pro_puerto || 8766, '127.0.0.1', () => { server.removeListener('error', reject); resolve(); });
    }); },
    close() { runner.close(); for (const socket of sockets) socket.destroy(); return new Promise(resolve => server.close(resolve)); }
  };
}
module.exports = { ProRunner, createProServer, validate, authorized, MODELS, MAX_PROMPT, MAX_BODY, MAX_OUTPUT };
