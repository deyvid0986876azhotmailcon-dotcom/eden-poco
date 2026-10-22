'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { Backups, signalBackup, download } = require('../respaldo.cjs');
const { configuration } = require('../lib.cjs');
const config = { puerto: 8765, token: 'a'.repeat(43), remitente: '123456789@s.whatsapp.net' };
const body = Buffer.alloc(4096); body.write('SQLite format 3\0');
const { createHash } = require('node:crypto');
const { validScript, uploadScript } = require('../respaldo.cjs');
const script = { url: 'https://script.google.com/macros/s/test-id/exec', token: 'secret_'.repeat(5) };
const digest = createHash('sha256').update(body).digest('hex');
function response(bytes = body, status = 200, size = bytes.length) {
  return new Response(bytes, { status, headers: { 'Content-Type': 'application/vnd.sqlite3', 'Content-Length': String(size) } });
}
function harness(t, options = {}) {
  const root = fs.mkdtempSync(path.join(__dirname, '.backup-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const calls = [], logs = [];
  const clock = { date: new Date(2026, 9, 19, 3, 0) };
  const deps = { now: () => clock.date, fetcher: async (url, request) => {
    assert.equal(url, 'http://127.0.0.1:8765/respaldo');
    assert.equal(request.method, 'GET'); assert.equal(request.body, undefined);
    assert.equal(request.headers.Authorization, `Bearer ${config.token}`);
    assert.equal(request.redirect, 'error'); return response();
  }, run: async args => calls.push(args), ...options };
  const backups = new Backups(root, config, line => logs.push(line), deps);
  return { root, calls, logs, clock, backups, deps };
}
test('config accepts optional strict local HH:mm and retains legacy configuration', () => {
  assert.equal(configuration(config).puerto, 8765);
  assert.equal(configuration({ ...config, respaldo_hora: '23:59' }).respaldo_hora, '23:59');
  for (const hour of ['24:00', '3:00', '12:60', null, 300]) assert.throws(() => configuration({ ...config, respaldo_hora: hour }));
});
test('snapshot upload, retention, private file and persisted success; restart avoids duplicate', async t => {
  const h = harness(t);
  fs.mkdirSync(h.backups.directory);
  for (let day = 1; day <= 9; day++) fs.writeFileSync(path.join(h.backups.directory, `asistente-2026-10-0${day}.db`), body);
  fs.writeFileSync(path.join(h.backups.directory, 'unrelated'), 'keep');
  assert.equal(await h.backups.tick(true), true);
  const file = path.join(h.backups.directory, 'asistente-2026-10-19.db');
  assert.deepEqual(h.calls, [['copy', file, 'drive:Asistente/respaldos'], ['delete', '--min-age', '30d', 'drive:Asistente/respaldos']]);
  assert.deepEqual(fs.readFileSync(file), body);
  if (process.platform !== 'win32') assert.equal(fs.statSync(file).mode & 0o777, 0o600);
  assert.equal(fs.readdirSync(h.backups.directory).filter(name => name.endsWith('.db')).length, 7);
  assert.ok(fs.existsSync(path.join(h.backups.directory, 'unrelated')));
  assert.deepEqual(h.logs, ['Respaldo exitoso (4 KB)']);
  const restarted = new Backups(h.root, config, line => h.logs.push(line), h.deps);
  assert.equal(await restarted.tick(true), false);
  h.clock.date = new Date(2026, 9, 20, 2, 59); assert.equal(await restarted.tick(), false);
  h.clock.date = new Date(2026, 9, 20, 3, 0); assert.equal(await restarted.tick(), true);
});
test('missed day catches up on startup before scheduled hour; failed attempts do not stamp success', async t => {
  const h = harness(t);
  await h.backups.request();
  h.clock.date = new Date(2026, 9, 21, 1, 0);
  const restarted = new Backups(h.root, config, line => h.logs.push(line), h.deps);
  assert.equal(await restarted.tick(true), true);
  assert.equal(await restarted.tick(), false);
});
test('configured local hour is honored and a failed automatic attempt is not repeated each minute', async t => {
  const h = harness(t, { fetcher: async () => { throw new Error('unavailable'); } });
  const scheduled = new Backups(h.root, { ...config, respaldo_hora: '21:15' }, line => h.logs.push(line), h.deps);
  h.clock.date = new Date(2026, 9, 19, 21, 14);
  assert.equal(await scheduled.tick(), false); assert.equal(h.logs.length, 0);
  h.clock.date = new Date(2026, 9, 19, 21, 15);
  assert.equal(await scheduled.tick(), false); assert.equal(h.logs.length, 1);
  h.clock.date = new Date(2026, 9, 19, 21, 16);
  await scheduled.tick(); assert.equal(h.logs.length, 1);
  assert.equal(fs.existsSync(scheduled.state), false);
  h.clock.date = new Date(2026, 9, 20, 21, 15);
  await scheduled.tick(); assert.equal(h.logs.length, 3);
  assert.equal(h.logs.at(-1), 'Respaldo: no se pudo subir');
});
test('invalid or truncated SQLite and HTTP failures never reach rclone or leave partial files', async t => {
  for (const getResponse of [() => response(Buffer.alloc(4096)), () => response(body, 503), () => response(body, 200, 8192)]) {
    const h = harness(t, { fetcher: async () => getResponse() });
    assert.equal(await h.backups.request(), false);
    assert.equal(h.calls.length, 0); assert.equal(fs.existsSync(h.backups.state), false);
    assert.deepEqual(fs.readdirSync(h.backups.directory), []);
    assert.deepEqual(h.logs, ['Respaldo fallido: app no disponible o base invalida']);
  }
});
test('rclone missing/failure is contained and private error details are never logged', async t => {
  for (const failureAt of ['copy', 'delete']) {
    const h = harness(t, { run: async args => { if (args[0] === failureAt) throw new Error('ENOENT private personal path'); } });
    assert.equal(await h.backups.request(), false);
    assert.equal(fs.existsSync(h.backups.state), false);
    assert.deepEqual(h.logs, [`Respaldo fallido: rclone ${failureAt}`]);
  }
});
test('manual and scheduled requests share one in-flight backup; shutdown waits for it', async t => {
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const h = harness(t, { fetcher: async () => { await gate; return response(); } });
  const first = h.backups.request();
  assert.equal(h.backups.request(), first);
  assert.equal(h.backups.stop(), first);
  release(); assert.equal(await first, true); assert.equal(h.calls.length, 2);
  assert.equal(await h.backups.request(), false);
});
test('manual signal reads existing lock without replacing or acquiring it', t => {
  const h = harness(t), signals = [];
  const lock = path.join(h.root, 'running.lock');
  fs.writeFileSync(lock, '123\n');
  signalBackup(h.root, { kill: (...args) => signals.push(args) });
  assert.deepEqual(signals, [[123, 'SIGUSR1']]); assert.equal(fs.readFileSync(lock, 'utf8'), '123\n');
  fs.writeFileSync(lock, '-1'); assert.throws(() => signalBackup(h.root));
});
test('download timeout covers the response body', async t => {
  const h = harness(t);
  const fetcher = async (_, request) => ({ status: 200, headers: new Headers({ 'content-type': 'application/vnd.sqlite3', 'content-length': '4096' }),
    body: { async *[Symbol.asyncIterator]() { await new Promise((_, reject) => request.signal.addEventListener('abort', () => reject(new Error('timeout')), { once: true })); } } });
  await assert.rejects(download(config, path.join(h.root, 'timeout.part'), fetcher, 10));
});

test('script config validation and configuration acceptance', () => {
  assert.deepEqual(configuration({ ...config, respaldo_script: script }).respaldo_script, script);
  assert.equal(validScript(script), true);
  for (const url of [script.url + '?t=x', script.url + '/', 'http://script.google.com/macros/s/id/exec',
    'https://script.google.com.evil/macros/s/id/exec', 'https://script.google.com/macros/s/../exec']) {
    assert.equal(validScript({ ...script, url }), false);
  }
  for (const token of ['', 'a'.repeat(19), 'a'.repeat(65), 'x&' + 'a'.repeat(20)]) assert.equal(validScript({ ...script, token }), false);
});

test('script exact POST, allowed GET redirect and success without rclone', async t => {
  const calls = [];
  const h = harness(t, { fetcher: async (url, request) => {
    if (url.startsWith('http:')) return response();
    calls.push({ url, request });
    if (calls.length === 1) return new Response(null, { status: 302, headers: { location: 'https://script.googleusercontent.com/macros/echo?id=test' } });
    return Response.json({ ok: true, bytes: body.length, sha256: digest });
  } });
  h.backups.config = { ...config, respaldo_script: script };
  assert.equal(await h.backups.request(), true);
  assert.equal(h.calls.length, 0);
  assert.equal(calls[0].url, script.url + '?t=' + script.token);
  assert.equal(calls[0].request.method, 'POST');
  assert.equal(calls[0].request.redirect, 'manual');
  assert.deepEqual(calls[0].request.headers, { 'Content-Type': 'text/plain;charset=utf-8' });
  assert.deepEqual(JSON.parse(calls[0].request.body), { nombre: 'asistente-2026-10-19.db', datos: body.toString('base64'), sha256: digest });
  assert.equal(calls[1].request.method, 'GET');
  assert.equal(calls[1].request.redirect, 'error');
  assert.equal(calls[1].request.body, undefined);
  assert.equal(calls[1].request.headers, undefined);
  assert.equal(calls[0].request.signal, calls[1].request.signal);
});

test('untrusted redirects, hash mismatch and private failures fail without leaking or rclone fallback', async t => {
  for (const bad of ['https://evil.test/', 'https://script.googleusercontent.com.evil/',
    'http://script.googleusercontent.com/', 'https://user@script.googleusercontent.com/',
    'https://script.googleusercontent.com:444/', '/relative', 'hash', 'error']) {
    let calls = 0;
    const h = harness(t, { fetcher: async (url) => {
      if (url.startsWith('http:')) return response();
      calls++;
      if (bad === 'error') throw new Error(script.url + '?t=' + script.token);
      if (bad === 'hash') return Response.json({ ok: true, sha256: 'wrong' });
      return new Response(null, { status: 302, headers: { location: bad } });
    } });
    h.backups.config = { ...config, respaldo_script: script };
    assert.equal(await h.backups.request(), false);
    assert.equal(calls, 1); assert.equal(h.calls.length, 0);
    assert.equal(fs.existsSync(h.backups.state), false);
    assert.deepEqual(h.logs, ['Respaldo fallido: subida por script']);
  }
});

test('one retry after ten minutes, no third attempt; manual success cancels retry', async t => {
  let fail = true;
  const h = harness(t, { run: async () => { if (fail) throw new Error('offline'); } });
  assert.equal(await h.backups.request(), false);
  h.clock.date = new Date(2026, 9, 19, 3, 9); await h.backups.tick(); assert.equal(h.logs.length, 1);
  h.clock.date = new Date(2026, 9, 19, 3, 10); await h.backups.tick(); assert.equal(h.logs.length, 3);
  assert.equal(h.logs.at(-1), 'Respaldo: no se pudo subir');
  h.clock.date = new Date(2026, 9, 19, 3, 20); await h.backups.tick(); assert.equal(h.logs.length, 3);
  await h.backups.request(); assert.ok(h.backups.retryAt);
  fail = false; assert.equal(await h.backups.request(), true); assert.equal(h.backups.retryAt, 0);
});

test('invalid script config retains rclone; stop cancels pending retry', async t => {
  const h = harness(t);
  h.backups.config = { ...config, respaldo_script: { ...script, token: 'invalid' } };
  assert.equal(await h.backups.request(), true); assert.equal(h.calls.length, 2);
  h.backups.run = async () => { throw new Error('offline'); };
  await h.backups.request(); assert.ok(h.backups.retryAt);
  h.backups.stop(); assert.equal(h.backups.retryAt, 0);
});

test('script size limit and total timeout cover redirected response body', async t => {
  const h = harness(t), file = path.join(h.root, 'asistente-2026-10-19.db');
  fs.writeFileSync(file, body);
  let calls = 0;
  const fetcher = async (_, request) => {
    if (++calls === 1) return new Response(null, { status: 302, headers: { location: 'https://script.googleusercontent.com/result' } });
    return { status: 200, body: { async *[Symbol.asyncIterator]() {
      await new Promise((_, reject) => request.signal.addEventListener('abort', () => reject(new Error('timeout')), { once: true }));
    } } };
  };
  await assert.rejects(uploadScript(script, file, fetcher, 20));
  fs.truncateSync(file, 20 * 1024 * 1024 + 1);
  await assert.rejects(uploadScript(script, file, async () => { assert.fail('must not upload'); }));
});
