'use strict';
const { spawn } = require('node:child_process');
const MAX_BYTES = 5000000, MAX_SECONDS = 180, MAX_PCM = 24000 * 2 * 60;
const LISTEN_FAILED = 'No pude escuchar el audio ahora; mandamelo escrito';
const TYPES = new Set(['audio/ogg', 'audio/mpeg', 'audio/mp3', 'audio/mp4', 'audio/aac', 'audio/wav', 'audio/x-wav', 'audio/flac', 'audio/webm']);
function mime(value) {
  const type = typeof value === 'string' ? value.split(';')[0].trim().toLowerCase() : '';
  if (!TYPES.has(type)) throw new Error('Tipo de audio no permitido');
  return type;
}
function metadata(audio) {
  const seconds = Number(audio.seconds?.toString()), size = Number(audio.fileLength?.toString());
  if (!Number.isFinite(seconds) || seconds <= 0 || seconds > MAX_SECONDS
      || !Number.isSafeInteger(size) || size <= 0 || size > MAX_BYTES) throw new Error('Audio fuera de límites');
  return { mime: mime(audio.mimetype), seconds };
}
/** Stream, not buffer: an untruthful fileLength must not allocate an unbounded download. */
async function downloadAudio(message, downloadMediaMessage, context, timeout = 20000) {
  const content = message.message?.ephemeralMessage?.message || message.message;
  const meta = metadata(content.audioMessage);
  let stream, timer, expired = false;
  const controller = new AbortController();
  const deadline = new Promise((_, reject) => {
    timer = setTimeout(() => {
      expired = true; controller.abort(); stream?.destroy(); reject(new Error('Tiempo agotado'));
    }, timeout);
  });
  const work = (async () => {
    stream = await downloadMediaMessage(message, 'stream', { signal: controller.signal }, context);
    if (expired) { stream.destroy(); throw new Error('Tiempo agotado'); }
    const chunks = []; let total = 0;
    try {
      for await (const chunk of stream) {
        if (expired) throw new Error('Tiempo agotado');
        total += chunk.length;
        if (total > MAX_BYTES) throw new Error('Audio demasiado grande');
        chunks.push(Buffer.from(chunk));
      }
      if (!total) throw new Error('Audio vacío');
      return { ...meta, bytes: Buffer.concat(chunks) };
    } finally { stream.destroy(); }
  })();
  try { return await Promise.race([work, deadline]); }
  finally { clearTimeout(timer); }
}
function pcmBytes(audio) {
  if (!audio || audio.format !== 'pcm_s16le' || audio.sampleRate !== 24000 || audio.channels !== 1
      || typeof audio.data !== 'string' || !audio.data.length || audio.data.length > Math.ceil(MAX_PCM / 3) * 4
      || audio.data.length % 4 || /[^A-Za-z0-9+/=]/.test(audio.data)) throw new Error('PCM inválido');
  const pcm = Buffer.from(audio.data, 'base64');
  if (!pcm.length || pcm.length > MAX_PCM || pcm.length % 2 || pcm.toString('base64') !== audio.data) throw new Error('PCM inválido');
  return pcm;
}
/** No shell and no files. Missing ffmpeg, timeout or malformed PCM falls back to text. */
function convert(audio, launch = spawn, timeout = 15000) {
  const pcm = pcmBytes(audio);
  return new Promise((resolve, reject) => {
    const child = launch('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-f', 's16le', '-ar', '24000', '-ac', '1',
      '-i', 'pipe:0', '-vn', '-c:a', 'libopus', '-b:a', '32k', '-application', 'voip', '-f', 'ogg', 'pipe:1'],
    { shell: false, windowsHide: true, stdio: ['pipe', 'pipe', 'ignore'] });
    let size = 0, finished = false; const chunks = [];
    const finish = (error) => {
      if (finished) return; finished = true; clearTimeout(timer);
      if (error) { child.kill(); reject(error); } else resolve(Buffer.concat(chunks));
    };
    const timer = setTimeout(() => finish(new Error('ffmpeg agotó el tiempo')), timeout);
    child.on('error', () => finish(new Error('ffmpeg no disponible')));
    child.stdin.on('error', () => finish(new Error('ffmpeg rechazó el audio')));
    child.stdout.on('error', () => finish(new Error('ffmpeg falló')));
    child.stdout.on('data', data => {
      size += data.length;
      if (size > MAX_BYTES) finish(new Error('OGG demasiado grande'));
      else chunks.push(data);
    });
    child.on('close', code => finish(code === 0 && size > 0 ? null : new Error('ffmpeg falló')));
    child.stdin.end(pcm);
  });
}
async function sendReply(reply, chat, send, current, log, convertAudio = convert) {
  // The app sets solo_audio when David sent a voice note and there is no code to copy and he did not ask
  // for text: then only her voice note goes. If the voice cannot be made, the text always goes.
  const { split } = require('./lib.cjs');
  const sendText = async () => {
    for (const text of split(reply.respuesta)) {
      if (!current()) return;
      await send(chat, { text });
    }
  };
  const voiceOnly = reply.solo_audio === true && reply.audio;
  if (!voiceOnly) await sendText(); // the written answer first, as before
  if (!reply.audio || !current()) return;
  let ogg;
  try { ogg = await convertAudio(reply.audio); }
  catch { log('Audio no disponible: revisar ffmpeg; respuesta enviada como texto'); if (voiceOnly) await sendText(); return; }
  if (current()) await send(chat, { audio: ogg, mimetype: 'audio/ogg; codecs=opus', ptt: true });
}
module.exports = { MAX_BYTES, MAX_SECONDS, MAX_PCM, LISTEN_FAILED, mime, metadata, downloadAudio, convert, sendReply };
