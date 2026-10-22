/**
 * Copias nocturnas del asistente: el puente de Termux manda la base de datos y este script la guarda en el Drive
 * de David. Reemplaza a rclone, cuyo client_id compartido de Google se retira en 2026. Corre en la cuenta de David
 * con solo drive.file (ver appsscript.json): ve únicamente los archivos que él mismo crea, nada más del Drive.
 * Carpeta propia «Asistente - respaldos» (la de rclone no la puede ver con este permiso); guarda 30 días.
 */
const TOKEN = '__TOKEN__'; // lo reemplaza Claude al preparar la copia que pega David; nunca va al repositorio
const CARPETA = 'Asistente - respaldos';
const DIAS = 30;
const MAX_BYTES = 20 * 1024 * 1024;

function doPost(e) {
  const p = (e && e.parameter) || {};
  if (typeof p.t !== 'string' || p.t !== TOKEN || TOKEN.indexOf('__') === 0) return json_({ error: 'no autorizado' });
  try {
    const pedido = validar(JSON.parse((e.postData && e.postData.contents) || ''));
    const bytes = Utilities.base64Decode(pedido.datos);
    if (bytes.length === 0 || bytes.length > MAX_BYTES) return json_({ error: 'tamaño inválido' });
    const hash = hex(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, bytes));
    if (hash !== pedido.sha256) return json_({ error: 'la copia llegó dañada' });
    const carpeta = carpeta_();
    // la copia del mismo día reemplaza a la anterior (por ejemplo un respaldo manual)
    const previas = carpeta.getFilesByName(pedido.nombre);
    while (previas.hasNext()) previas.next().setTrashed(true);
    carpeta.createFile(Utilities.newBlob(bytes, 'application/vnd.sqlite3', pedido.nombre));
    limpiar_(carpeta, new Date());
    return json_({ ok: true, bytes: bytes.length, sha256: hash });
  } catch (err) {
    return json_({ error: 'no pude guardar la copia' }); // sin detalles: pueden traer rutas o datos
  }
}

/** Ejecutalo una vez desde el editor: pide el permiso y muestra cuántas copias hay. */
function probar() {
  const carpeta = carpeta_();
  var n = 0; const it = carpeta.getFiles();
  while (it.hasNext()) { it.next(); n++; }
  Logger.log('Carpeta «' + CARPETA + '» lista; copias guardadas: ' + n);
}

function carpeta_() {
  const encontradas = DriveApp.getRootFolder().getFoldersByName(CARPETA);
  return encontradas.hasNext() ? encontradas.next() : DriveApp.getRootFolder().createFolder(CARPETA);
}

function limpiar_(carpeta, hoy) {
  const nombres = [], archivos = {};
  const it = carpeta.getFiles();
  while (it.hasNext()) { const f = it.next(); nombres.push(f.getName()); archivos[f.getName()] = f; }
  viejos(nombres, hoy).forEach(function (n) { archivos[n].setTrashed(true); });
}

function json_(valor) {
  return ContentService.createTextOutput(JSON.stringify(valor)).setMimeType(ContentService.MimeType.JSON);
}

// ---- Reglas (sin dependencias de Google: las prueba asistente_respaldos/test con Node) ----

/** Solo lo esperado, nada más: nombre con fecha, base64 limpio y sha256 en hex. */
function validar(cuerpo) {
  if (!cuerpo || typeof cuerpo !== 'object' || Array.isArray(cuerpo)) throw new Error('pedido inválido');
  const claves = Object.keys(cuerpo).sort().join(',');
  if (claves !== 'datos,nombre,sha256') throw new Error('pedido inválido');
  if (typeof cuerpo.nombre !== 'string' || !/^asistente-\d{4}-\d{2}-\d{2}\.db$/.test(cuerpo.nombre)) throw new Error('nombre inválido');
  if (typeof cuerpo.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(cuerpo.sha256)) throw new Error('sha256 inválido');
  if (typeof cuerpo.datos !== 'string' || !cuerpo.datos.length || cuerpo.datos.length > Math.ceil(MAX_BYTES / 3) * 4
      || cuerpo.datos.length % 4 || /[^A-Za-z0-9+/=]/.test(cuerpo.datos)) throw new Error('datos inválidos');
  return { nombre: cuerpo.nombre, datos: cuerpo.datos, sha256: cuerpo.sha256 };
}

/** computeDigest devuelve bytes con signo (-128..127). */
function hex(bytes) {
  return bytes.map(function (b) { return ('0' + (b & 0xff).toString(16)).slice(-2); }).join('');
}

/** Copias con más de DIAS días según la fecha del nombre; lo que no tenga ese formato no se toca. */
function viejos(nombres, hoy) {
  const limite = Date.UTC(hoy.getUTCFullYear(), hoy.getUTCMonth(), hoy.getUTCDate()) - DIAS * 86400000;
  return nombres.filter(function (n) {
    const m = /^asistente-(\d{4})-(\d{2})-(\d{2})\.db$/.exec(n);
    return m && Date.UTC(+m[1], +m[2] - 1, +m[3]) < limite;
  });
}

if (typeof module !== 'undefined') module.exports = { validar, hex, viejos };
