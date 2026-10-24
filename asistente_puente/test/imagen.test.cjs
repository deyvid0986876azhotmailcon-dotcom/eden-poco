'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { Readable } = require('node:stream');
const { candidate, post } = require('../lib.cjs');
const { MAX_BYTES, SEE_FAILED, metadata, downloadImage } = require('../imagen.cjs');
const owner = '1111111111@s.whatsapp.net';
const config = { puerto: 8765, token: 'a'.repeat(43) };
const message = { key: { id: 'PHOTO_1', fromMe: false, remoteJid: owner }, messageTimestamp: 100,
  message: { imageMessage: { mimetype: 'image/jpeg', fileLength: 4, caption: 'cuánto gasté acá?' } } };
test('images use the same owner, group and freshness filters as text', () => {
  const decision = candidate(message, 'notify', owner, 100, 100000);
  assert.equal(decision.media, message); assert.equal(decision.kind, 'imagen');
  for (const key of [{ remoteJid: '2222222222@s.whatsapp.net' }, { fromMe: true }, { remoteJid: '1@g.us' }, { participant: owner }])
    assert.ok(candidate({ ...message, key: { ...message.key, ...key } }, 'notify', owner, 100, 100000).reason);
  assert.ok(candidate(message, 'append', owner, 100, 100000).reason);
  const big = { ...message, message: { imageMessage: { mimetype: 'image/jpeg', fileLength: MAX_BYTES + 1 } } };
  assert.ok(candidate(big, 'notify', owner, 100, 100000).reason);
});
test('metadata accepts only JPG, PNG or WEBP with a bounded caption', () => {
  assert.equal(metadata({ mimetype: 'image/png', fileLength: 10 }).caption, '');
  for (const patch of [{ mimetype: 'image/gif' }, { mimetype: 'video/mp4' }, { fileLength: 0 }, { fileLength: undefined },
    { fileLength: MAX_BYTES + 1 }, { caption: 'x'.repeat(1001) }, { caption: 'a\u0000b' }])
    assert.throws(() => metadata({ mimetype: 'image/jpeg', fileLength: 10, ...patch }));
});
test('download is bounded by the bytes actually received', async () => {
  const fake = (chunks) => async () => Readable.from(chunks);
  const ok = await downloadImage(message, fake([Buffer.from('ab'), Buffer.from('cd')]), {});
  assert.equal(ok.bytes.toString(), 'abcd'); assert.equal(ok.mime, 'image/jpeg');
  await assert.rejects(() => downloadImage(message, fake([Buffer.alloc(MAX_BYTES), Buffer.alloc(1)]), {}));
  await assert.rejects(() => downloadImage(message, fake([]), {}));
});
test('post sends the bytes to /imagen with the caption as base64url', async () => {
  let seen;
  const fetcher = async (url, options) => { seen = { url, options }; return new Response(JSON.stringify({ respuesta: 'Listo' })); };
  const image = { bytes: Buffer.from('abcd'), mime: 'image/jpeg', caption: 'cuánto gasté acá?' };
  const reply = await post(config, { id: 'PHOTO_1', image }, fetcher, 1000, true);
  assert.equal(reply.respuesta, 'Listo');
  assert.equal(seen.url, 'http://127.0.0.1:8765/imagen');
  assert.equal(seen.options.headers['Content-Type'], 'image/jpeg');
  assert.equal(Buffer.from(seen.options.headers['X-Caption'], 'base64url').toString('utf8'), 'cuánto gasté acá?');
  const failed = await post(config, { id: 'PHOTO_1', image }, async () => { throw new Error('down'); }, 1000, true);
  assert.equal(failed.respuesta, SEE_FAILED);
});
