'use strict';
// Photos David sends to the bot: same owner filters as text and audio; the app describes them and
// what is written inside an image is data, never an order.
const MAX_BYTES = 5000000, MAX_CAPTION = 1000;
const SEE_FAILED = 'No pude ver la imagen ahora; contame qué es o mandala de nuevo';
const TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);
function mime(value) {
  const type = typeof value === 'string' ? value.split(';')[0].trim().toLowerCase() : '';
  if (!TYPES.has(type)) throw new Error('Tipo de imagen no permitido');
  return type;
}
function caption(value) {
  if (value === undefined || value === null || value === '') return '';
  if (typeof value !== 'string' || value.length > MAX_CAPTION || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(value))
    throw new Error('Texto de la imagen inválido');
  return value.trim();
}
function metadata(image) {
  const size = Number(image?.fileLength?.toString());
  if (!Number.isSafeInteger(size) || size <= 0 || size > MAX_BYTES) throw new Error('Imagen fuera de límites');
  return { mime: mime(image.mimetype), caption: caption(image.caption) };
}
/** Stream, not buffer: an untruthful fileLength must not allocate an unbounded download. */
async function downloadImage(message, downloadMediaMessage, context, timeout = 20000) {
  const content = message.message?.ephemeralMessage?.message || message.message;
  const meta = metadata(content.imageMessage);
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
        if (total > MAX_BYTES) throw new Error('Imagen demasiado grande');
        chunks.push(Buffer.from(chunk));
      }
      if (!total) throw new Error('Imagen vacía');
      return { ...meta, bytes: Buffer.concat(chunks) };
    } finally { stream.destroy(); }
  })();
  try { return await Promise.race([work, deadline]); }
  finally { clearTimeout(timer); }
}
module.exports = { MAX_BYTES, MAX_CAPTION, SEE_FAILED, mime, caption, metadata, downloadImage };
