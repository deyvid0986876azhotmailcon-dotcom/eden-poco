'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { Readable, PassThrough } = require('node:stream');
const { EventEmitter } = require('node:events');
const { candidate, post, DeliveryQueue } = require('../lib.cjs');
const { MAX_BYTES, MAX_PCM, LISTEN_FAILED, metadata, downloadAudio, convert, sendReply } = require('../audio.cjs');
const owner = '1111111111@s.whatsapp.net';
const config = { puerto: 8765, token: 'a'.repeat(43) };
const message = { key: { id: 'VOICE_1', fromMe: false, remoteJid: owner }, messageTimestamp: 100,
  message: { audioMessage: { mimetype: 'audio/ogg; codecs=opus', seconds: 2, fileLength: 4, ptt: true } } };
const pcm = (size = 4) => ({ format: 'pcm_s16le', channels: 1, sampleRate: 24000, data: Buffer.alloc(size).toString('base64') });
test('audio uses the same owner, group, freshness and LID filters as text', () => {
  assert.equal(candidate(message, 'notify', owner, 100, 100000).media, message);
  for (const key of [{ remoteJid: '2222222222@s.whatsapp.net' }, { fromMe: true },
    { remoteJid: '1@g.us' }, { participant: owner }, { remoteJid: '123@lid' }])
    assert.ok(candidate({ ...message, key: { ...message.key, ...key } }, 'notify', owner, 100, 100000).reason);
  assert.ok(candidate(message, 'append', owner, 100, 100000).reason);
  const lid = { ...message, key: { ...message.key, remoteJid: '123@lid', remoteJidAlt: owner } };
  assert.equal(candidate(lid, 'notify', owner, 100, 100000).chat, '123@lid');
  const ephemeral = { ...message, message: { ephemeralMessage: { message: message.message } } };
  assert.equal(candidate(ephemeral, 'notify', owner, 100, 100000).media, ephemeral);
});
test('metadata rejects unknown formats, missing sizes and out of range durations', () => {
  for (const patch of [{ seconds: 181 }, { seconds: 0 }, { seconds: NaN }, { seconds: undefined },
    { fileLength: MAX_BYTES + 1 }, { fileLength: 0 }, { fileLength: undefined }, { mimetype: 'text/plain' }])
    assert.throws(() => metadata({ ...message.message.audioMessage, ...patch }));
  assert.equal(metadata({ ...message.message.audioMessage, seconds: 180, fileLength: MAX_BYTES }).seconds, 180);
});
test('download is streamed, bounded by actual bytes and destroys failed streams', async () => {
  let stream;
  const audio = await downloadAudio(message, async (m, type, options) => {
    assert.equal(m, message); assert.equal(type, 'stream'); assert.ok(options.signal);
    return Readable.from([Buffer.from('ab'), Buffer.from('cd')]);
  }, {});
  assert.equal(audio.bytes.toString(), 'abcd'); assert.equal(audio.mime, 'audio/ogg');
  await assert.rejects(downloadAudio(message, async () => (stream = Readable.from([Buffer.alloc(MAX_BYTES), Buffer.alloc(1)])), {}));
  assert.ok(stream.destroyed);
  await assert.rejects(downloadAudio(message, async () => Readable.from([]), {}));
});
test('download timeout aborts both initial fetch and stalled stream', async () => {
  let signal;
  await assert.rejects(downloadAudio(message, async (m, type, options) => {
    signal = options.signal; return new Promise(() => {});
  }, {}, 10));
  assert.ok(signal.aborted);
  const stream = new PassThrough();
  await assert.rejects(downloadAudio(message, async () => stream, {}, 10));
  assert.ok(stream.destroyed);
});
test('binary HTTP preserves bytes, token and ID; unavailable app gives audio fallback', async () => {
  const audio = { bytes: Buffer.from([0, 255, 1]), mime: 'audio/ogg', seconds: 2 };
  const result = await post(config, { id: 'VOICE_1', audio }, async (url, options) => {
    assert.equal(url, 'http://127.0.0.1:8765/audio'); assert.equal(options.body, audio.bytes);
    assert.equal(options.headers.Authorization, 'Bearer ' + config.token);
    assert.equal(options.headers['X-Message-Id'], 'VOICE_1'); assert.equal(options.headers['X-Audio-Seconds'], '2');
    assert.equal(options.headers['Content-Type'], 'audio/ogg'); assert.equal(options.redirect, 'error');
    return new Response(JSON.stringify({ respuesta: 'Hecho', audio: pcm() }));
  }, 1000, true);
  assert.deepEqual(result.audio, pcm());
  assert.equal((await post(config, { id: 'VOICE_1', audio }, async () => new Response('', { status: 503 }), 1000, true)).respuesta, LISTEN_FAILED);
});
function child() {
  const c = new EventEmitter(); c.stdin = new PassThrough(); c.stdout = new PassThrough();
  c.kill = () => { c.killed = true; }; return c;
}
test('ffmpeg receives bounded PCM through pipes without shell; full minute works', async () => {
  const result = await convert(pcm(MAX_PCM), (command, args, options) => {
    assert.equal(command, 'ffmpeg'); assert.ok(args.includes('libopus')); assert.ok(args.includes('24000'));
    assert.equal(options.shell, false); const c = child();
    c.stdin.resume(); c.stdin.on('finish', () => { c.stdout.write(Buffer.from('OggS')); c.emit('close', 0); });
    return c;
  });
  assert.equal(result.toString(), 'OggS');
  for (const bad of [pcm(3), pcm(MAX_PCM + 2), { ...pcm(), data: 'AA=A' }, { ...pcm(), sampleRate: 48000 }])
    assert.throws(() => convert(bad, () => assert.fail('must not launch')));
});
test('ffmpeg missing, nonzero exit, oversized output and timeout fail safely', async () => {
  for (const failure of ['missing', 'exit', 'size', 'timeout']) {
    let c;
    await assert.rejects(convert(pcm(), () => {
      c = child(); process.nextTick(() => {
        if (failure === 'missing') c.emit('error', new Error('ENOENT'));
        if (failure === 'exit') c.emit('close', 1);
        if (failure === 'size') c.stdout.write(Buffer.alloc(MAX_BYTES + 1));
      }); return c;
    }, 10));
    assert.ok(c.killed);
  }
});
test('voice always keeps amounts and confirmation codes written; conversion failure logs fallback', async () => {
  const sent = [], logs = []; const reply = { respuesta: 'Monto 25000 PYG. Confirmá SÍ ABC123', audio: pcm() };
  await sendReply(reply, owner, async (chat, body) => sent.push({ chat, body }), () => true, s => logs.push(s), async () => Buffer.from('OggS'));
  assert.equal(sent[0].body.text, reply.respuesta); assert.equal(sent[1].body.ptt, true);
  assert.equal(sent[1].body.mimetype, 'audio/ogg; codecs=opus'); assert.equal(sent[1].chat, owner);
  sent.length = 0;
  await sendReply(reply, owner, async (_, body) => sent.push(body), () => true, s => logs.push(s), async () => { throw Error(); });
  assert.deepEqual(sent, [{ text: reply.respuesta }]); assert.equal(logs.length, 1);
});
test('PARAR during conversion suppresses the pending voice send', async () => {
  let active = true; const sent = [];
  await sendReply({ respuesta: 'Hecho', audio: pcm() }, owner, async (_, body) => sent.push(body), () => active, () => {},
    async () => { active = false; return Buffer.from('OggS'); });
  assert.deepEqual(sent, [{ text: 'Hecho' }]);
});
test('PARAR while audio is downloading prevents forwarding it to app', async () => {
  let release; const download = new Promise(resolve => { release = resolve; }); const forwarded = [];
  const queue = new DeliveryQueue(async (job, current) => {
    if (job.media) await download;
    if (current()) forwarded.push(job.id);
  }, () => assert.fail());
  queue.add({ id: 'audio', media: message }); await Promise.resolve();
  queue.add({ id: 'stop', text: 'PARAR' }); await queue.tail; release();
  await new Promise(resolve => setImmediate(resolve)); assert.deepEqual(forwarded, ['stop']);
});
test('a voice note gets only a voice note unless the app asks for text', async () => {
  const sent = [];
  await sendReply({ respuesta: 'Anoté', audio: pcm(), solo_audio: true }, owner, async (_, body) => sent.push(body), () => true, () => {},
    async () => Buffer.from('OggS'));
  assert.equal(sent.length, 1); assert.equal(sent[0].ptt, true);
  const fallback = [];
  await sendReply({ respuesta: 'Anoté', audio: pcm(), solo_audio: true }, owner, async (_, body) => fallback.push(body), () => true, () => {},
    async () => { throw new Error('sin ffmpeg'); });
  assert.deepEqual(fallback, [{ text: 'Anoté' }]);
});
