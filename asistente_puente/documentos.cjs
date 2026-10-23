'use strict';
const MAX_DOCUMENT = 15 * 1024 * 1024;
const MAX_RESPONSE = MAX_DOCUMENT * 4 / 3 + 64 * 1024;
function documentBody(item) {
  if (!item || Object.keys(item).sort().join(',') !== 'documento,id') throw new Error('Documento inválido');
  const d = item.documento;
  if (!d || Object.keys(d).sort().join(',') !== 'base64,mime,nombre') throw new Error('Documento inválido');
  const extensions = { 'application/pdf': ['.pdf'], 'image/jpeg': ['.jpg', '.jpeg'], 'image/png': ['.png'], 'text/plain': ['.txt'] };
  if (typeof d.mime !== 'string' || !Object.hasOwn(extensions, d.mime)
      || typeof d.nombre !== 'string' || d.nombre.length > 120 || !/^[\p{L}\p{N} _().-]+$/u.test(d.nombre)
      || d.nombre.startsWith('.') || d.nombre.includes('..') || /[. ]$/.test(d.nombre)
      || !extensions[d.mime].some(ext => d.nombre.toLowerCase().endsWith(ext))) throw new Error('Tipo o nombre inválido');
  if (typeof d.base64 !== 'string' || !d.base64.length || d.base64.length > 4 * Math.ceil(MAX_DOCUMENT / 3)
      || d.base64.length % 4 || !/^[A-Za-z0-9+/]*={0,2}$/.test(d.base64)) throw new Error('Base64 inválido');
  const bytes = Buffer.from(d.base64, 'base64');
  if (!bytes.length || bytes.length > MAX_DOCUMENT || bytes.toString('base64') !== d.base64) throw new Error('Tamaño o base64 inválido');
  return { document: bytes, mimetype: d.mime, fileName: d.nombre };
}
module.exports = { documentBody, MAX_DOCUMENT, MAX_RESPONSE };
