'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { documentBody, MAX_DOCUMENT } = require('../documentos.cjs');
const { PendingPoller } = require('../pendientes.cjs');
const doc = () => ({ id: 'doc_1', documento: { nombre: 'Gastos.pdf', mime: 'application/pdf', base64: Buffer.from('%PDF-1.4\n').toString('base64') } });
test('strict document metadata, canonical base64 and size validation', () => {
  assert.equal(documentBody(doc()).document.toString(), '%PDF-1.4\n');
  for (const nombre of ['../x.pdf', 'a/b.pdf', 'a\\b.pdf', '.pdf', 'a..pdf', 'a.pdf\n', 'a.pdf ', 'a.exe']) {
    const item = doc(); item.documento.nombre = nombre; assert.throws(() => documentBody(item));
  }
  for (const mime of ['text/html', 'application/octet-stream', '__proto__', null]) {
    const item = doc(); item.documento.mime = mime; assert.throws(() => documentBody(item));
  }
  for (const base64 of ['', 'Zg', 'Zg==\n', 'Zh==', '!!!!', 'A===', 'A'.repeat(4 * Math.ceil(MAX_DOCUMENT / 3) + 4)]) {
    const item = doc(); item.documento.base64 = base64; assert.throws(() => documentBody(item));
  }
  const item = doc(); item.destino = 'other'; assert.throws(() => documentBody(item));
});
test('15 MB boundary and every allowed type', () => {
  const item = doc(); item.documento.base64 = Buffer.alloc(MAX_DOCUMENT).toString('base64');
  assert.equal(documentBody(item).document.length, MAX_DOCUMENT);
  for (const [mime, nombre] of [['image/png', 'a.png'], ['image/jpeg', 'a.jpeg'], ['text/plain', 'a.txt']]) {
    const item = doc(); Object.assign(item.documento, { mime, nombre }); assert.equal(documentBody(item).mimetype, mime);
  }
});
test('document goes only to owner, ACK retry does not resend, malformed batch sends nothing', async t => {
  const root = fs.mkdtempSync(path.join(__dirname, '.docs-'));
  t.after(() => { assert.equal(path.dirname(path.resolve(root)), path.resolve(__dirname)); fs.rmSync(root, { recursive: true }); });
  const config = { puerto: 8765, token: 'x', remitente: '595111@s.whatsapp.net' };
  const sent = [], acks = [];
  const session = { opened: true, socket: { async sendMessage(jid, body) { sent.push({ jid, body }); } } };
  let items = [doc()];
  const fetcher = async (_, options) => {
    if (options.method === 'POST') { acks.push(JSON.parse(options.body)); return new Response('{}'); }
    return new Response(JSON.stringify(items));
  };
  const poll = () => new PendingPoller(root, config, { chat: config.remitente }, () => session, () => {}, fetcher).poll();
  await poll(); await poll();
  assert.equal(sent.length, 1); assert.equal(sent[0].jid, config.remitente);
  assert.equal(sent[0].body.fileName, 'Gastos.pdf'); assert.ok(Buffer.isBuffer(sent[0].body.document));
  assert.equal(acks.length, 2);
  items = [{ id: 'text1', texto: 'safe' }, { ...doc(), id: 'doc_2' }];
  await poll(); assert.equal(sent.length, 1);
  items = [{ ...doc(), id: 'doc_3', destino: 'other' }];
  await poll(); assert.equal(sent.length, 1);
});
