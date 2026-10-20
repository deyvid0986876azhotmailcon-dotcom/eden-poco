'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const lib = require('../lib.cjs');
const tick = () => new Promise(resolve => setImmediate(resolve));

// Execute the real entry point with a simulated Baileys/QR/clock. No npm modules or network.
async function harness(prelocked = false, manual = false) {
  const home = fs.mkdtempSync(path.join(__dirname, '.tmp-'));
  const root = path.join(home, '.asistente_puente'), auth = path.join(root, 'auth');
  const png = path.join(home, 'storage', 'downloads', 'asistente_qr.png');
  const config = { puerto: 8765, token: 'a'.repeat(43), remitente: '1'.repeat(10) + '@s.whatsapp.net' };
  fs.mkdirSync(root); fs.mkdirSync(path.dirname(png), { recursive: true });
  fs.writeFileSync(path.join(root, 'config.json'), JSON.stringify(config));
  if (prelocked) { fs.writeFileSync(path.join(root, 'running.lock'), 'other'); fs.writeFileSync(png, 'other QR'); }
  const sockets = [], logs = [], posts = [], timers = new Set(), exits = [], backupCalls = [];
  const fakeProcess = new EventEmitter();
  fakeProcess.argv = ['node', 'index.cjs', ...(manual ? ['--respaldo-ahora'] : [])];
  fakeProcess.umask = () => {}; fakeProcess.pid = 123; fakeProcess.exit = code => exits.push(code);
  const socketFactory = options => {
    assert.equal(options.syncFullHistory, false); assert.equal(options.shouldSyncHistoryMessage(), false);
    const socket = { ev: new EventEmitter(), auth: options.auth, sent: [], end() {}, async sendMessage(jid, body) { this.sent.push({ jid, body }); } };
    sockets.push(socket); return socket;
  };
  const sandbox = {
    process: fakeProcess, console: { log: message => logs.push(message) },
    setTimeout: (fn, delay) => { const timer = { fn, delay }; timers.add(timer); return timer; },
    clearTimeout: timer => timers.delete(timer),
    require: name => {
      if (name === './respaldo.cjs') return {
        Backups: class { start() { backupCalls.push('start'); } stop() {} request() { backupCalls.push('request'); } },
        signalBackup: directory => backupCalls.push(directory)
      };
      if (name === './lib.cjs') return { ...lib, post: async (_, message) => { posts.push(message); return 'Hecho'; } };
      if (name === 'node:os') return { homedir: () => home };
      if (name === '@whiskeysockets/baileys') return {
        default: socketFactory, DisconnectReason: { loggedOut: 401 },
        useMultiFileAuthState: async directory => ({
          state: { keys: { set: async () => fs.writeFileSync(path.join(directory, 'signal.json'), '{}') } },
          saveCreds: async () => fs.writeFileSync(path.join(directory, 'creds.json'), '{}')
        })
      };
      if (name === 'pino') return () => ({});
      if (name === 'qrcode') return { toString: async () => 'TERMINAL_QR', toFile: async file => fs.writeFileSync(file, 'PNG_QR') };
      return require(name);
    }
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '..', 'index.cjs'), 'utf8')
    + '\nglobalThis.hooks = { shutdown, session: () => connection, queue: () => queue.tail };', sandbox);
  await tick();
  return { home, root, auth, png, config, sockets, logs, posts, timers, exits, backupCalls, fakeProcess, hooks: sandbox.hooks,
    async close() {
      sandbox.hooks.shutdown(); await tick();
      assert.equal(path.dirname(path.resolve(home)), path.resolve(__dirname)); fs.rmSync(home, { recursive: true });
    } };
}
test('manual CLI bypasses ownership and Baileys; primary handles SIGUSR1', async () => {
  const manual = await harness(true, true);
  try {
    assert.deepEqual(manual.backupCalls, [manual.root]);
    assert.equal(manual.sockets.length, 0);
    assert.equal(fs.readFileSync(path.join(manual.root, 'running.lock'), 'utf8'), 'other');
  } finally { await manual.close(); }
  const primary = await harness();
  try {
    primary.fakeProcess.emit('SIGUSR1');
    assert.deepEqual(primary.backupCalls, ['start', 'request']);
  } finally { await primary.close(); }
});
test('QR terminal/PNG, authenticated delivery and persistent dedup use the real event wiring', async () => {
  const h = await harness();
  try {
    const socket = h.sockets[0];
    socket.ev.emit('connection.update', { qr: 'private QR' }); await h.hooks.session().events;
    assert.ok(h.logs.includes('TERMINAL_QR')); assert.equal(fs.readFileSync(h.png, 'utf8'), 'PNG_QR');
    socket.ev.emit('creds.update', {}); await h.hooks.session().writes;
    assert.ok(fs.existsSync(path.join(h.auth, 'creds.json')));
    socket.ev.emit('connection.update', { connection: 'open' }); await h.hooks.session().events;
    assert.equal(fs.existsSync(h.png), false);
    const message = { key: { fromMe: false, id: 'PRIVATE_ID', remoteJid: h.config.remitente },
      messageTimestamp: Math.floor(Date.now() / 1000), message: { conversation: 'PRIVATE_TEXT' } };
    socket.ev.emit('messages.upsert', { type: 'notify', messages: [message, message,
      { ...message, key: { ...message.key, remoteJid: 'status@broadcast' } }] });
    await h.hooks.queue();
    assert.equal(h.posts.length, 1); assert.equal(socket.sent.length, 1);
    assert.equal(socket.sent[0].jid, h.config.remitente); assert.equal(socket.sent[0].body.text, 'Hecho');
    assert.equal(new lib.Ledger(path.join(h.root, 'seen.ids')).claim(h.config.remitente, 'PRIVATE_ID'), false);
    for (const privateValue of ['PRIVATE_TEXT', 'PRIVATE_ID', h.config.remitente, h.config.token]) assert.ok(!h.logs.join('\n').includes(privateValue));
  } finally { await h.close(); }
});
test('owner LID replies stay in that chat; unrelated chats and payload aliases are rejected', async () => {
  const h = await harness();
  try {
    const socket = h.sockets[0];
    socket.ev.emit('connection.update', { connection: 'open' }); await h.hooks.session().events;
    const message = { key: { fromMe: false, id: 'LID_1', remoteJid: '123@lid', remoteJidAlt: h.config.remitente },
      messageTimestamp: Math.floor(Date.now() / 1000), message: { conversation: 'Hola' } };
    socket.ev.emit('messages.upsert', { type: 'notify', messages: [message,
      { ...message, key: { ...message.key, id: 'LID_2', remoteJidAlt: '2222222222@s.whatsapp.net' } },
      { ...message, key: { ...message.key, id: 'GROUP', remoteJid: '123@g.us' } },
      { ...message, key: { fromMe: false, id: 'PAYLOAD', remoteJid: '456@lid' },
        message: { extendedTextMessage: { text: 'Hola', contextInfo: { remoteJidAlt: h.config.remitente, senderPn: h.config.remitente } } } }
    ] });
    await h.hooks.queue();
    assert.equal(h.posts.length, 1); assert.equal(socket.sent.length, 1);
    assert.equal(socket.sent[0].jid, '123@lid'); assert.deepEqual(h.exits, []);
  } finally { await h.close(); }
});

test('loggedOut removes only auth and reconnects with increasing delays until a new QR', async () => {
  const h = await harness();
  try {
    const socket = h.sockets[0];
    socket.ev.emit('creds.update', {}); await h.hooks.session().writes;
    await socket.auth.keys.set({}); assert.ok(fs.existsSync(path.join(h.auth, 'signal.json')));
    const before = fs.readFileSync(path.join(h.root, 'config.json'), 'utf8');
    const closing = h.hooks.session();
    socket.ev.emit('connection.update', { connection: 'close', lastDisconnect: { error: { output: { statusCode: 401 } } } });
    await closing.events;
    await socket.auth.keys.set({}); // A stale socket must not recreate deleted Signal keys.
    assert.deepEqual(fs.readdirSync(h.auth), []); assert.equal(fs.readFileSync(path.join(h.root, 'config.json'), 'utf8'), before);
    assert.ok(fs.existsSync(path.join(h.root, 'seen.ids'))); assert.ok(fs.existsSync(path.join(h.root, 'running.lock')));
    let timer = [...h.timers][0]; assert.equal(timer.delay, 1000); h.timers.delete(timer); timer.fn(); await tick();
    assert.equal(h.sockets.length, 2);
    const again = h.hooks.session();
    h.sockets[1].ev.emit('connection.update', { connection: 'close', lastDisconnect: { error: { output: { statusCode: 408 } } } });
    await again.events;
    timer = [...h.timers][0]; assert.equal(timer.delay, 2000); h.timers.delete(timer); timer.fn(); await tick();
    h.sockets[2].ev.emit('connection.update', { qr: 'new private QR' }); await h.hooks.session().events;
    assert.ok(fs.existsSync(h.png));
  } finally { await h.close(); }
});
test('a second process refuses to run and does not remove the first process QR or lock', async () => {
  const h = await harness(true);
  try {
    assert.deepEqual(h.exits, [1]); assert.equal(h.sockets.length, 0);
    assert.equal(fs.readFileSync(h.png, 'utf8'), 'other QR');
    assert.equal(fs.readFileSync(path.join(h.root, 'running.lock'), 'utf8'), 'other');
  } finally { await h.close(); }
});
