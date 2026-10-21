'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { EventEmitter } = require('node:events');
const { PassThrough } = require('node:stream');
const { ProRunner, createProServer, validate, authorized, MAX_PROMPT, MAX_OUTPUT, MAX_BODY } = require('../pro.cjs');
const { configuration } = require('../lib.cjs');
const config = { puerto: 8765, token: 'a'.repeat(43), remitente: '123456789@s.whatsapp.net' };
const input = { tipo: 'charla', prompt: 'Una charla privada; $(touch bad) `cmd` --mode auto' };
const tick = () => new Promise(resolve => setImmediate(resolve));
function harness(t, extra = {}) {
  const home = fs.mkdtempSync(path.join(__dirname, '.pro-')), children = [], calls = [], logs = [], kills = [];
  const runner = new ProRunner({ home, env: { PATH: '/system/bin', TMPDIR: '/tmp', LANG: 'es_PY.UTF-8', SECRET: 'never' },
    spawnProcess: (file, args, options) => {
      calls.push({ file, args, options });
      const child = new EventEmitter(); child.pid = 123; child.stdout = new PassThrough(); child.stderr = new PassThrough();
      children.push(child); return child;
    }, kill: child => { kills.push(child); setImmediate(() => child.emit('close', null)); },
    log: line => logs.push(line), ...extra });
  t.after(() => { runner.close(); assert.equal(path.dirname(path.resolve(home)), path.resolve(__dirname)); fs.rmSync(home, { recursive: true, force: true }); });
  return { home, children, calls, logs, kills, runner };
}
function complete(child, text = 'Hola, che') { child.stdout.write(text); child.emit('close', 0); }
test('exact argv, no shell, minimal environment, private cwd; stderr never becomes text or log', async t => {
  const h = harness(t); const result = h.runner.run(input);
  assert.equal(h.calls[0].file, path.join(h.home, '.local', 'bin', 'agy'));
  assert.deepEqual(h.calls[0].args, ['--mode', 'plan', '--sandbox', '--disable-slash-commands', '--print-timeout',
    '60s', '--model', 'claude-opus-4-6-thinking', '--output-format', 'text', '-p', input.prompt]);
  assert.deepEqual(h.calls[0].options, { cwd: path.join(h.home, 'charla'),
    env: { HOME: h.home, PATH: path.join(h.home, '.local', 'bin') + path.delimiter + '/system/bin', TMPDIR: '/tmp', LANG: 'es_PY.UTF-8' },
    shell: false, detached: true, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  if (process.platform !== 'win32') assert.equal(fs.statSync(path.join(h.home, 'charla')).mode & 0o777, 0o700);
  h.children[0].stderr.write('ERROR: logging before google.Init: private'); complete(h.children[0]);
  assert.equal(await result, 'Hola, che'); assert.match(h.logs[0], /éxito \(\d+ ms\)/);
  assert.ok(!h.logs.join().includes('private')); assert.ok(!h.logs.join().includes(input.prompt));
});
test('timeouts kill active child and release slot only after close', async t => {
  const h = harness(t, { deadlines: { charla: 15, busqueda: 15 } });
  await assert.rejects(h.runner.run(input), e => e.code === 'TIEMPO_AGOTADO');
  assert.equal(h.kills.length, 1); await tick(); assert.equal(h.runner.active, null);
});
test('stdout and stderr share a bounded budget; failed/empty/missing CLI output is generic', async t => {
  const h = harness(t);
  for (const stream of ['stdout', 'stderr']) {
    const p = assert.rejects(h.runner.run(input), e => e.code === 'SALIDA_EXCESIVA');
    h.children.at(-1)[stream].write(Buffer.alloc(MAX_OUTPUT + 1)); await p; await tick();
  }
  for (const mode of ['empty', 'error', 'nonzero']) {
    const p = assert.rejects(h.runner.run(input), e => !e.message.includes('PRIVATE'));
    const child = h.children.at(-1);
    if (mode === 'error') child.emit('error', new Error('PRIVATE'));
    else child.emit('close', mode === 'empty' ? 0 : 1);
    await p; await tick();
  }
});
test('one active and two waiting, FIFO; full queue rejects, cancellation removes waiting work', async t => {
  const h = harness(t), cancel = new AbortController();
  const a = h.runner.run(input), b = h.runner.run({ tipo: 'busqueda', prompt: 'lunas' }, cancel.signal), c = h.runner.run(input);
  const rejectedB = assert.rejects(b, e => e.code === 'CANCELADO');
  await assert.rejects(h.runner.run(input), e => e.code === 'OCUPADO'); assert.equal(h.children.length, 1);
  cancel.abort(); await rejectedB; complete(h.children[0]); await a;
  assert.equal(h.children.length, 2); complete(h.children[1]); await c; assert.equal(h.runner.active, null);
});
test('disconnect and PARAR kill active child, reject waiting jobs and suppress late text', async t => {
  const h = harness(t), signal = new AbortController();
  const a = assert.rejects(h.runner.run(input, signal.signal), e => e.code === 'CANCELADO');
  signal.abort(); complete(h.children[0], 'late'); await a;
  const b = assert.rejects(h.runner.run(input), e => e.code === 'CANCELADO');
  const c = assert.rejects(h.runner.run(input), e => e.code === 'CANCELADO');
  h.runner.cancel(); await Promise.all([b, c]); assert.equal(h.children.length, 2);
  await tick(); h.runner.close(); await assert.rejects(h.runner.run(input));
});
test('strict request, allowlisted models, UTF8 byte limit and configurable port', () => {
  assert.equal(validate({ tipo: 'busqueda', prompt: 'lunas' }).modelo, 'gemini-3.8-flash-high');
  for (const patch of [{ modelo: '--bad' }, { modelo: null }, { tipo: 'exec' }, { prompt: '\0x' }, { prompt: '' },
    { prompt: '€'.repeat(MAX_PROMPT / 3 + 1) }, { shell: true }]) assert.throws(() => validate({ ...input, ...patch }));
  assert.equal(configuration({ ...config, pro_puerto: 9876 }).pro_puerto, 9876);
  for (const pro_puerto of [8765, 80, '8766', 65536]) assert.throws(() => configuration({ ...config, pro_puerto }));
  assert.throws(() => configuration({ ...config, puerto: 8766 }));
  assert.ok(authorized('Bearer ' + config.token, config.token));
  for (const h of [null, '', 'Bearer short', 'Bearer ' + 'b'.repeat(43)]) assert.equal(authorized(h, config.token), false);
});
async function serverHarness(t, runner) {
  const wrapper = createProServer(config, runner);
  await new Promise(resolve => wrapper.server.listen(0, '127.0.0.1', resolve));
  t.after(() => wrapper.close());
  assert.equal(wrapper.server.address().address, '127.0.0.1');
  return { wrapper, port: wrapper.server.address().port };
}
function request(port, { method = 'POST', url = '/pro', token = config.token, body = JSON.stringify(input), headers = {} } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ hostname: '127.0.0.1', port, path: url, method, agent: false,
      headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body), ...headers } }, res => {
      const chunks = []; res.on('data', c => chunks.push(c)); res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: JSON.parse(Buffer.concat(chunks)) }));
    });
    req.on('error', reject); req.end(body);
  });
}
test('HTTP rejects token/method/path/JSON/model/large body/origin before starting CLI', async t => {
  let calls = 0;
  const { port } = await serverHarness(t, { async run() { calls++; return 'Respuesta'; }, close() {} });
  for (const [options, status] of [[{ token: 'wrong' }, 401], [{ method: 'GET' }, 405], [{ method: 'OPTIONS' }, 405],
    [{ url: '/pro?x=1' }, 404], [{ body: '{' }, 400], [{ body: JSON.stringify({ ...input, modelo: 'bad' }) }, 400],
    [{ body: 'x'.repeat(MAX_BODY + 1) }, 413], [{ headers: { Origin: 'https://example.org' } }, 400]]) {
    const response = await request(port, options); assert.equal(response.status, status); assert.equal(response.body.mensaje, 'Pro no disponible ahora.');
  }
  assert.equal(calls, 0);
  const response = await request(port); assert.equal(response.status, 200); assert.deepEqual(response.body, { texto: 'Respuesta' });
  assert.equal(response.headers.connection, 'close'); assert.equal(response.headers['access-control-allow-origin'], undefined); assert.equal(calls, 1);
});
test('HTTP client disconnect aborts the runner; no late response or child survives shutdown', async t => {
  const h = harness(t); const { port, wrapper } = await serverHarness(t, h.runner);
  let entered; const ready = new Promise(resolve => { entered = resolve; });
  const original = h.runner.run.bind(h.runner); h.runner.run = (...args) => { entered(); return original(...args); };
  const req = http.request({ hostname: '127.0.0.1', port, path: '/pro', method: 'POST',
    headers: { Authorization: 'Bearer ' + config.token, 'Content-Type': 'application/json' } });
  req.on('error', () => {}); req.end(JSON.stringify(input)); await ready;
  req.destroy();
  for (let i = 0; i < 100 && !h.kills.length; i++) await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(h.kills.length, 1); await wrapper.close(); assert.equal(h.runner.closed, true);
});

test('queued jobs have their own deadline and cannot run after timeout', async t => {
  const h = harness(t, { deadlines: { charla: 1000, busqueda: 15 } });
  const active = h.runner.run(input);
  await assert.rejects(h.runner.run({ tipo: 'busqueda', prompt: 'lunas' }), e => e.code === 'TIEMPO_AGOTADO');
  assert.equal(h.children.length, 1); assert.equal(h.kills.length, 0);
  complete(h.children[0]); await active; assert.equal(h.children.length, 1);
});

test('chunked bodies are bounded without trusting Content-Length', async t => {
  let calls = 0;
  const { port } = await serverHarness(t, { async run() { calls++; return 'text'; }, close() {} });
  const status = await new Promise((resolve, reject) => {
    const req = http.request({ hostname: '127.0.0.1', port, path: '/pro', method: 'POST',
      headers: { Authorization: 'Bearer ' + config.token, 'Content-Type': 'application/json' } }, res => {
      res.resume(); res.on('end', () => resolve(res.statusCode));
    });
    req.on('error', reject); req.write('x'.repeat(MAX_BODY)); req.end('x');
  });
  assert.equal(status, 413); assert.equal(calls, 0);
});
