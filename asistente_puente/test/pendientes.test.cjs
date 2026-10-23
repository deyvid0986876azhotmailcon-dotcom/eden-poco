'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { OwnerChat, PendingPoller, OutgoingLedger, request } = require('../pendientes.cjs');
const config = { puerto: 8765, token: 'x'.repeat(43), remitente: '595981111111@s.whatsapp.net' };
function fixture(t) {
  const root = fs.mkdtempSync(path.join(__dirname, '.pending-'));
  t.after(() => {
    assert.equal(path.dirname(path.resolve(root)), path.resolve(__dirname)); fs.rmSync(root, { recursive: true });
  });
  return root;
}
const response = value => new Response(JSON.stringify(value), { status: 200 });
test('accepted owner phone/LID persists privately; forged LIDs cannot change destination', t => {
  const root = fixture(t), chats = new OwnerChat(root, config.remitente), time = Math.floor(Date.now() / 1000);
  assert.equal(chats.chat, config.remitente);
  const message = { key: { id: 'one', fromMe: false, remoteJid: '1234@lid', remoteJidAlt: config.remitente },
    messageTimestamp: time, message: { conversation: 'hola' } };
  assert.equal(chats.accept(message, 'notify', time), true);
  assert.equal(new OwnerChat(root, config.remitente).chat, '1234@lid');
  assert.equal(chats.accept({ ...message, key: { ...message.key, remoteJid: '999@lid', remoteJidAlt: 'other@s.whatsapp.net' } }, 'notify', time), false);
  assert.equal(chats.chat, '1234@lid');
  assert.equal(new OwnerChat(root, '595982222222@s.whatsapp.net').chat, '595982222222@s.whatsapp.net');
  if (process.platform !== 'win32') assert.equal(fs.statSync(path.join(root, 'owner-chat.json')).mode & 0o777, 0o600);
});
test('GET polls, sends to saved owner chat, POST confirms; restart and lost ACK do not duplicate', async t => {
  const root = fixture(t), calls = [], sends = [];
  const session = { opened: true, socket: { async sendMessage(jid, body) { sends.push({ jid, body }); } } };
  let failAck = true;
  const fetcher = async (url, options) => {
    calls.push({ url, options });
    assert.equal(options.headers.Authorization, `Bearer ${config.token}`); assert.equal(options.redirect, 'error');
    if (options.method === 'GET') return response([{ id: 'r_one_123', texto: 'Mensaje privado' }]);
    assert.deepEqual(JSON.parse(options.body), { ids: ['r_one_123'] });
    if (failAck) throw new Error('lost ack');
    return response({});
  };
  const log = [];
  await new PendingPoller(root, config, { chat: '123@lid' }, () => session, e => log.push(e), fetcher).poll();
  failAck = false;
  await new PendingPoller(root, config, { chat: '123@lid' }, () => session, e => log.push(e), fetcher).poll();
  assert.equal(sends.length, 1); assert.equal(sends[0].jid, '123@lid');
  assert.equal(calls.filter(c => c.url.endsWith('/pendientes/confirmar')).length, 2);
  assert.ok(!log.join('\n').includes('Mensaje privado'));
  assert.ok(!fs.readFileSync(path.join(root, 'outgoing.ids'), 'utf8').includes('Mensaje privado'));
});
test('ambiguous send is never automatically sent twice or falsely confirmed, including after restart', async t => {
  const root = fixture(t); let attempts = 0, ack = 0;
  const session = { opened: true, socket: { async sendMessage() { attempts++; throw new Error('uncertain'); } } };
  const fetcher = async (_, options) => {
    if (options.method === 'POST') { ack++; return response({}); }
    return response([{ id: 'id1', texto: 'text' }]);
  };
  for (let i = 0; i < 2; i++) await new PendingPoller(root, config, { chat: config.remitente }, () => session, () => {}, fetcher).poll();
  assert.equal(attempts, 1); assert.equal(ack, 0);
});
test('concurrent polls coalesce; PARAR and disconnected session suppress late sends', async t => {
  const root = fixture(t); let release, requests = 0, sends = 0;
  const session = { opened: true, socket: { async sendMessage() { sends++; } } };
  const fetcher = () => { requests++; return new Promise(resolve => { release = () => resolve(response([{ id: 'one', texto: 'text' }])); }); };
  const poller = new PendingPoller(root, config, { chat: config.remitente }, () => session, () => {}, fetcher);
  const running = poller.poll(); await poller.poll(); assert.equal(requests, 1);
  poller.pause(); release(); await running; assert.equal(sends, 0);
  const paused = poller.poll(); release(); await paused; assert.equal(requests, 2); assert.equal(sends, 0);
  poller.resume(); session.opened = false;
  const disconnected = poller.poll(); release(); await disconnected; assert.equal(requests, 3); assert.equal(sends, 0);
});
test('health header continues while disconnected, logged out or paused, without delivery or ACK', async t => {
  const root = fixture(t), headers = []; let status = 'desconectado', sends = 0;
  const session = { opened: false, socket: { async sendMessage() { sends++; } } };
  const poller = new PendingPoller(root, config, {}, () => session, () => {}, async (url, options) => {
    assert.equal(options.method, 'GET'); assert.ok(url.endsWith('/pendientes'));
    headers.push(options.headers['X-WhatsApp']);
    return response([{ id: 'notice', texto: 'text' }]);
  }, () => status);
  await poller.poll(); status = 'desvinculado'; await poller.poll();
  poller.pause(); status = 'conectado'; session.opened = true; await poller.poll();
  assert.deepEqual(headers, ['desconectado', 'desvinculado', 'conectado']); assert.equal(sends, 0);
  poller.stop(); await poller.poll(); assert.equal(headers.length, 3);
});
test('invalid batches fail closed and pending endpoint is bounded/authenticated', async t => {
  const root = fixture(t); let sends = 0;
  const session = { opened: true, socket: { async sendMessage() { sends++; } } };
  for (const items of [[{ id: '../x', texto: 'text' }], [{ id: 'one', texto: 'text', destino: 'other' }],
    [{ id: 'one', texto: 'a' }, { id: 'one', texto: 'b' }], [{ id: 'one', texto: 4 }]]) {
    await new PendingPoller(root, config, { chat: config.remitente }, () => session, () => {}, async () => response(items)).poll();
  }
  assert.equal(sends, 0);
  await assert.rejects(() => request(config, '/pendientes', undefined, async () => new Response('x'.repeat(require('../documentos.cjs').MAX_RESPONSE + 1))));
  await assert.rejects(() => request(config, '/pendientes', undefined, async () => new Response('{}', { status: 401 })));
});
test('journal rejects torn writes instead of silently losing deduplication', t => {
  const root = fixture(t); new OutgoingLedger(root, config.remitente).record('x', 'sending');
  fs.appendFileSync(path.join(root, 'outgoing.ids'), '{');
  assert.throws(() => new OutgoingLedger(root, config.remitente));
});
test('polling interval is 60 seconds and start/stop does not leave duplicate timers', t => {
  const root = fixture(t); let scheduled = 0, cleared = 0;
  t.mock.method(global, 'setInterval', (fn, delay) => { assert.equal(delay, 60000); scheduled++; return 123; });
  t.mock.method(global, 'clearInterval', timer => { assert.equal(timer, 123); cleared++; });
  const poller = new PendingPoller(root, config, { chat: config.remitente }, () => null, () => {}, async () => response([]));
  poller.start(); poller.start(); assert.equal(scheduled, 1); poller.stop(); assert.equal(cleared, 1);
});
