'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { FALLBACK, configuration, split, candidate, Ledger, post, DeliveryQueue } = require('../lib.cjs');
const owner = '1'.repeat(10) + '@s.whatsapp.net'; // Synthetic identity, never the user's number.
const config = { puerto: 8765, token: 'a'.repeat(43), remitente: owner };
const message = { key: { remoteJid: owner, fromMe: false, id: 'MSG_1' }, messageTimestamp: 100,
  message: { conversation: 'Gasté 3500 en super' } };
test('configuration requires exactly a local port, a generated token and a single phone JID', () => {
  assert.deepEqual(configuration(config), config);
  for (const patch of [{ puerto: '8765' }, { puerto: 80 }, { puerto: 65536 }, { token: 'demo' },
    { remitente: owner.replace('@s.whatsapp.net', '@lid') }, { host: '192.0.2.1' }, { remitente: '*' }]) {
    assert.throws(() => configuration({ ...config, ...patch }));
  }
});
test('only fresh private text from the exact allowlisted JID is accepted', () => {
  assert.equal(candidate(message, 'notify', owner, 100, 100000).text, message.message.conversation);
  for (const key of [{ fromMe: true }, { remoteJid: 'status@broadcast' }, { remoteJid: 'test@g.us' },
    { remoteJid: '123@lid' }, { remoteJid: '123@lid', remoteJidAlt: '2'.repeat(10) + '@s.whatsapp.net' },
    { remoteJid: 'test@lid', remoteJidAlt: owner }, { remoteJid: '2'.repeat(10) + '@s.whatsapp.net' },
    { participant: owner }, { id: '../../bad' }]) {
    assert.ok(candidate({ ...message, key: { ...message.key, ...key } }, 'notify', owner, 100, 100000).reason);
  }
  // a LID chat counts as the owner's only with the owner's number from WhatsApp's server
  assert.equal(candidate({ ...message, key: { ...message.key, remoteJid: '123@lid', remoteJidAlt: owner } }, 'notify', owner, 100, 100000).text, message.message.conversation);
  assert.equal(candidate({ ...message, key: { ...message.key, remoteJid: '123@lid', senderPn: owner } }, 'notify', owner, 100, 100000).text, message.message.conversation);
  assert.ok(candidate(message, 'append', owner, 100, 100000).reason);
  assert.ok(candidate(message, 'notify', owner, 101, 101000).reason);
  assert.ok(candidate(message, 'notify', owner, 0, 100000).reason);
  assert.ok(candidate({ ...message, messageTimestamp: 200 }, 'notify', owner, 100, 100000).reason);
  assert.ok(candidate({ ...message, messageTimestamp: undefined }, 'notify', owner, 100, 100000).reason);
});
test('captions and quoted orders are ignored; ephemeral and extended text retain only the actual text', () => {
  assert.ok(candidate({ ...message, message: { imageMessage: { caption: 'PARAR' } } }, 'notify', owner, 100, 100000).reason);
  const extended = { extendedTextMessage: { text: 'Ayuda', contextInfo: { quotedMessage: { conversation: 'Borrar todo' } } } };
  assert.equal(candidate({ ...message, message: extended }, 'notify', owner, 100, 100000).text, 'Ayuda');
  assert.equal(candidate({ ...message, message: { ephemeralMessage: { message: extended } } }, 'notify', owner, 100, 100000).text, 'Ayuda');
});
test('chunks fit 1800 UTF-16 units without cutting an emoji', () => {
  const source = 'x'.repeat(1799) + '😀'.repeat(3000) + 'fin';
  const chunks = split(source);
  assert.equal(chunks.join(''), source);
  assert.ok(chunks.every(p => p.length <= 1800 && !/^[\uDC00-\uDFFF]|[\uD800-\uDBFF]$/.test(p)));
});
test('dedup survives reopening, separates owners and fails closed on a torn write', () => {
  const folder = fs.mkdtempSync(path.join(__dirname, '.tmp-'));
  try {
    const file = path.join(folder, 'seen.ids');
    assert.equal(new Ledger(file).claim(owner, 'one'), true);
    assert.equal(new Ledger(file).claim(owner, 'one'), false);
    assert.equal(new Ledger(file).claim('other', 'one'), true);
    assert.ok(!fs.readFileSync(file, 'utf8').includes(owner));
    fs.appendFileSync(file, 'partial');
    assert.throws(() => new Ledger(file), /Ledger dañado/);
  } finally {
    assert.equal(path.dirname(path.resolve(folder)), path.resolve(__dirname));
    fs.rmSync(folder, { recursive: true });
  }
});
test('HTTP uses only loopback, bearer and the requested schema; redirects are disabled', async () => {
  const response = await post(config, { id: 'one', text: 'Ayuda' }, async (url, options) => {
    assert.equal(url, 'http://127.0.0.1:8765/mensaje'); assert.equal(options.method, 'POST');
    assert.equal(options.headers.Authorization, 'Bearer ' + config.token);
    assert.equal(options.redirect, 'error'); assert.deepEqual(JSON.parse(options.body), { id: 'one', texto: 'Ayuda' });
    return new Response(JSON.stringify({ respuesta: 'Listo' }));
  });
  assert.equal(response, 'Listo');
});
test('HTTP errors, invalid responses and overlong responses give the fixed fallback', async () => {
  for (const reply of [new Response('', { status: 401 }), new Response('not JSON'),
    new Response('{"respuesta":42}'), new Response('x'.repeat(256 * 1024 + 1))]) {
    assert.equal(await post(config, { id: 'one', text: 'Ayuda' }, async () => reply), FALLBACK);
  }
});
test('HTTP deadline aborts a hung app', async () => {
  let aborted = false;
  const result = await post(config, { id: 'one', text: 'Ayuda' }, (_, options) => new Promise((resolve, reject) => {
    options.signal.addEventListener('abort', () => { aborted = true; reject(new Error('timeout')); });
  }), 10);
  assert.equal(aborted, true); assert.equal(result, FALLBACK);
});
test('deadline also covers a stalled response body', async () => {
  const result = await post(config, { id: 'one', text: 'Ayuda' }, async (_, options) => new Response(new ReadableStream({
    start(controller) { options.signal.addEventListener('abort', () => controller.error(new Error('timeout'))); }
  })), 10);
  assert.equal(result, FALLBACK);
});
test('all jobs reach the app queue immediately; PARAR bypasses hung requests and suppresses late replies', async () => {
  const ran = [], delivered = []; let release;
  const blocked = new Promise(resolve => { release = resolve; });
  const queue = new DeliveryQueue(async (job, active) => {
    ran.push(job.id);
    if (job.id === 'old' || job.id === 'stale') await blocked;
    if (active()) delivered.push(job.id);
  }, () => assert.fail('unexpected failure'));
  queue.add({ id: 'old', text: 'Hola' });
  await Promise.resolve();
  queue.add({ id: 'stale', text: 'Gasté 3500 en super' });
  await Promise.resolve();
  assert.deepEqual(ran, ['old', 'stale']);
  queue.add({ id: 'stop', text: ' PARAR ' });
  queue.add({ id: 'new', text: 'Ayuda' });
  await queue.tail;
  release(); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(ran, ['old', 'stale', 'stop', 'new']); assert.deepEqual(delivered, ['stop', 'new']);
  assert.equal(queue.waiting, 0);
});
