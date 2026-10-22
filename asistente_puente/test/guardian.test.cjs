'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { Guardian, reopen, DOWN, BACK } = require('../guardian.cjs');

function fixture() {
  let time = 0, failure = 'ECONNREFUSED';
  const sends = [], events = [], opens = [], requests = [];
  const session = { opened: true, socket: { async sendMessage(chat, body) { sends.push({ chat, ...body }); } } };
  const guardian = new Guardian({ puerto: 8765, token: 'secret' }, { chat: '123@lid' }, () => session,
    event => events.push(event), { now: () => time, open: () => opens.push(time), fetcher: async (url, options) => {
      requests.push({ url, options });
      if (failure) throw new TypeError('private error', { cause: Object.assign(new Error(), { code: failure }) });
      return new Response('[]');
    } });
  return { guardian, sends, events, opens, requests, session,
    async tick(ms, error = 'ECONNREFUSED') { time = ms; failure = error; await guardian.poll(); } };
}
test('isolated failure resets the continuous two-minute window', async () => {
  const f = fixture();
  await f.tick(0); await f.tick(60000, null); await f.tick(120000); await f.tick(180000);
  assert.deepEqual(f.opens, []); assert.deepEqual(f.sends, []);
  await f.tick(240000); assert.deepEqual(f.opens, [240000]);
});
test('two minutes trigger reopening; sixty more seconds trigger exactly one owner-chat warning', async () => {
  const f = fixture();
  await f.tick(0); await f.tick(60000); await f.tick(119999);
  assert.equal(f.opens.length, 0);
  await f.tick(120000); await f.tick(179999); assert.equal(f.sends.length, 0);
  await f.tick(180000); await f.tick(240000); await f.tick(7 * 3600000);
  assert.deepEqual(f.opens, [120000]); assert.deepEqual(f.sends, [{ chat: '123@lid', text: DOWN }]);
  assert.equal(f.requests[0].url, 'http://127.0.0.1:8765/pendientes');
  assert.equal(f.requests[0].options.headers.Authorization, 'Bearer secret');
  assert.equal(f.requests[0].options.redirect, 'error');
  assert.ok(f.events.every(event => event.startsWith('Guardian: ')));
  assert.ok(!f.events.join().includes(DOWN)); assert.ok(!f.events.join().includes('private error'));
});
test('recovery after a warning sends one return message and rearms for a new outage', async () => {
  const f = fixture();
  for (const t of [0, 60000, 120000, 180000]) await f.tick(t);
  await f.tick(240000, null); await f.tick(300000, null);
  assert.deepEqual(f.sends.map(s => s.text), [DOWN, BACK]);
  for (const t of [360000, 420000, 480000, 540000]) await f.tick(t);
  assert.deepEqual(f.sends.map(s => s.text), [DOWN, BACK, DOWN]);
});
test('reopening followed by recovery before the warning stays silent', async () => {
  const f = fixture();
  for (const t of [0, 60000, 120000]) await f.tick(t);
  await f.tick(180000, null); await f.tick(240000, null);
  assert.equal(f.opens.length, 1); assert.equal(f.sends.length, 0);
});
test('reopen uses exact am arguments without shell; missing executable and throws are ignored', () => {
  const child = new EventEmitter();
  reopen((command, args, options) => {
    assert.equal(command, 'am');
    assert.deepEqual(args, ['start', '--user', '0', '-n', 'dev.poco.asistente/.MainActivity']);
    assert.equal(options.shell, false); assert.equal(options.stdio, 'ignore'); assert.equal(options.timeout, 10000);
    return child;
  });
  child.emit('error', new Error('ENOENT'));
  assert.doesNotThrow(() => reopen(() => { throw new Error('denied'); }));
});
test('disconnected WhatsApp does not prevent reopening; pending warning waits for reconnection', async () => {
  const f = fixture(); f.session.opened = false;
  for (const t of [0, 60000, 120000, 180000]) await f.tick(t);
  assert.equal(f.opens.length, 1); assert.equal(f.sends.length, 0);
  f.session.opened = true; await f.tick(240000);
  f.session.opened = false; await f.tick(300000, null);
  assert.equal(f.sends.length, 1);
  f.session.opened = true; await f.tick(360000, null); await f.tick(420000, null);
  assert.deepEqual(f.sends.map(s => s.text), [DOWN, BACK]);
});
test('ambiguous WhatsApp failure is not retried every minute', async () => {
  const f = fixture(); let sends = 0;
  f.session.socket.sendMessage = async () => { sends++; throw new Error('private delivery failure'); };
  for (const t of [0, 60000, 120000, 180000, 240000, 300000]) await f.tick(t);
  assert.equal(sends, 1); assert.ok(!f.events.join().includes('private'));
});
test('unrelated network errors break the continuous failure window; HTTP errors prove reachability', async () => {
  const f = fixture();
  await f.tick(0); await f.tick(60000, 'OTHER'); await f.tick(120000); await f.tick(180000);
  assert.equal(f.opens.length, 0);
  f.guardian.fetcher = async () => new Response('', { status: 401 });
  await f.guardian.poll(); assert.equal(f.guardian.since, null);
});
test('timeout is a qualifying failure; polls serialize and stop cancels the outstanding check', async t => {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] });
  const f = fixture(); let calls = 0;
  f.guardian.fetcher = async (_, { signal }) => {
    calls++;
    return new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }));
  };
  const first = f.guardian.poll(); assert.equal(f.guardian.poll(), first);
  t.mock.timers.tick(10000); await first; assert.equal(f.guardian.since, 0);
  f.guardian.start(); f.guardian.start(); t.mock.timers.tick(60000);
  assert.equal(calls, 2); await f.guardian.stop();
  t.mock.timers.tick(180000); await f.guardian.poll();
  assert.equal(calls, 2); assert.equal(f.opens.length, 0); assert.equal(f.sends.length, 0);
});
