/**
 * Finanzas del asistente: lee SOLO los correos de Banco Familiar y devuelve tipo, monto, fecha y detalle.
 * Corre en la cuenta de Google de David con permiso de solo lectura (gmail.readonly, ver appsscript.json).
 * Nada de IA: reglas fijas, así un correo trucho no puede dar órdenes. Cuentas, nombres, referencias,
 * números de operación y códigos nunca salen de acá. Correos de seguridad (códigos, accesos) se ignoran.
 */
const REMITENTE = 'operbancaweb@familiar.com.py';
const TOKEN = '__TOKEN__'; // lo reemplaza Claude al preparar la copia que pega David; nunca va al repositorio

function doGet(e) {
  const p = (e && e.parameter) || {};
  if (typeof p.t !== 'string' || p.t !== TOKEN || TOKEN.indexOf('__') === 0) return json_({ error: 'no autorizado' });
  const dias = Math.min(30, Math.max(1, parseInt(p.d, 10) || 7));
  return json_({ version: 1, movimientos: leer_(dias) });
}

/** Ejecutalo una vez desde el editor: pide la autorización y muestra solo cuántos movimientos encontró. */
function probar() {
  const movimientos = leer_(30);
  Logger.log('Movimientos encontrados en 30 días: ' + movimientos.length + ' (' +
      movimientos.filter(m => m.tipo === 'gasto').length + ' gastos, ' +
      movimientos.filter(m => m.tipo === 'ingreso').length + ' ingresos)');
  // Count unknown mail without exposing its original subject.
  const cuenta = {};
  movimientos.filter(m => m.tipo === 'desconocido').forEach(m => { cuenta[m.asunto] = (cuenta[m.asunto] || 0) + 1; });
  Object.keys(cuenta).sort((a, b) => cuenta[b] - cuenta[a]).forEach(a => Logger.log('Sin regla: ' + cuenta[a] + ' × «' + a + '»'));
  // Unknown subjects are deliberately generic; do not search the inbox for those placeholders.
}

/** Para armar reglas nuevas: SOLO los nombres de los campos («Monto:», «Comercio:»), nunca sus valores. */
function etiquetas_(asuntos) {
  // un correo por asunto: Gmail corta a ~200 lecturas por minuto
  asuntos.forEach(function (asunto) {
    const lista = Gmail.Users.Messages.list('me', { q: 'from:' + REMITENTE + ' subject:"' + asunto.replace(/"/g, '') + '" newer_than:30d', maxResults: 1 });
    const ref = (lista.messages || [])[0];
    if (!ref) return;
    const msg = Gmail.Users.Messages.get('me', ref.id, { format: 'full' });
    const nombres = [];
    textoPlano(cuerpo_(msg.payload)).split(/[\n\t]/).forEach(function (pedazo) {
      const m = pedazo.match(/^\s*([A-Za-zÁÉÍÓÚáéíóúÑñº°.\s]{2,40}):/);
      if (m && nombres.indexOf(m[1].trim()) < 0) nombres.push(m[1].trim());
    });
    Logger.log('Campos de «' + asunto + '»: ' + nombres.join(' | '));
  });
}

function leer_(dias) {
  const salida = [];
  var pagina;
  do {
    const opciones = { q: 'from:' + REMITENTE + ' newer_than:' + dias + 'd', maxResults: 100 };
    if (pagina) opciones.pageToken = pagina;
    const lista = Gmail.Users.Messages.list('me', opciones);
    (lista.messages || []).forEach(function (ref) {
      try {
        const msg = Gmail.Users.Messages.get('me', ref.id, { format: 'full' });
        const cabeceras = (msg.payload && msg.payload.headers) || [];
        const de = valorCabecera_(cabeceras, 'From').toLowerCase();
        if (de.indexOf('<' + REMITENTE + '>') < 0 && de.trim() !== REMITENTE) return; // el filtro de búsqueda no alcanza
        const movimiento = interpretar(valorCabecera_(cabeceras, 'Subject'), cuerpo_(msg.payload));
        if (movimiento) { movimiento.id = ref.id; salida.push(movimiento); }
      } catch (e) {
        // un correo raro no frena a los demás; se marca para que el asistente pregunte
        salida.push({ id: ref.id, tipo: 'desconocido', asunto: 'Correo del banco que no pude leer' });
      }
    });
    pagina = lista.nextPageToken;
  } while (pagina);
  return salida;
}

function valorCabecera_(cabeceras, nombre) {
  for (var i = 0; i < cabeceras.length; i++) if (cabeceras[i].name.toLowerCase() === nombre.toLowerCase()) return cabeceras[i].value || '';
  return '';
}

function cuerpo_(parte) {
  if (!parte) return '';
  if (parte.parts) {
    var html = '', plano = '';
    parte.parts.forEach(function (p) { var t = cuerpo_(p); if (/html/i.test(p.mimeType || '')) html = html || t; else plano = plano || t; });
    return html || plano;
  }
  if (!parte.body || !parte.body.data) return '';
  var tipo = valorCabecera_(parte.headers || [], 'Content-Type');
  var juego = (tipo.match(/charset="?([\w-]+)/i) || [])[1] || 'UTF-8';
  // El servicio avanzado de Gmail a veces entrega los bytes ya decodificados y a veces base64url sin relleno.
  var datos = parte.body.data, bytes;
  if (Array.isArray(datos)) bytes = datos;
  else {
    var s = String(datos).replace(/-/g, '+').replace(/_/g, '/').replace(/=+$/, '');
    while (s.length % 4) s += '=';
    bytes = Utilities.base64Decode(s);
  }
  return Utilities.newBlob(bytes).getDataAsString(juego);
}

function json_(valor) {
  return ContentService.createTextOutput(JSON.stringify(valor)).setMimeType(ContentService.MimeType.JSON);
}

// ---- Reglas (sin dependencias de Google: las prueba asistente_finanzas/test con Node) ----

function normalizar(s) {
  return String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/\s+/g, ' ').trim();
}

function textoPlano(html) {
  return String(html || '')
      .replace(/<(style|script)[\s\S]*?<\/\1>/gi, ' ')
      .replace(/<(br|\/p|\/div|\/tr|\/li|\/h\d)[^>]*>/gi, '\n')
      .replace(/<\/t[dh]>/gi, '\t')
      .replace(/<[^>]+>/g, '')
      .replace(/&nbsp;/gi, ' ').replace(/&amp;/gi, '&').replace(/&lt;/gi, '<').replace(/&gt;/gi, '>')
      .replace(/&#(\d+);/g, function (_, n) { return String.fromCharCode(parseInt(n, 10)); })
      .replace(/&([aeiou])acute;/gi, function (_, v) { return { a: 'á', e: 'é', i: 'í', o: 'ó', u: 'ú', A: 'Á', E: 'É', I: 'Í', O: 'Ó', U: 'Ú' }[v]; })
      .replace(/&ntilde;/gi, 'ñ')
      .replace(/[  ]+/g, ' ');
}

/** El valor que sigue a «Etiqueta:», en la misma celda o en la siguiente. */
function campo(texto, etiquetas) {
  for (var i = 0; i < etiquetas.length; i++) {
    var escapada = etiquetas[i].replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/\s+/g, '\\s+');
    var m = texto.match(new RegExp('(?:^|[\\n\\t])\\s*' + escapada + '\\s*:?[\\s\\t]*([^\\t\\n]+)', 'i'));
    if (m && m[1].trim()) return m[1].trim();
  }
  return null;
}

/** «Gs 10.000», «PYG 875.000», «₲ 1.250.000» -> número en guaraníes (sin decimales). */
function monto(valor) {
  var m = String(valor || '').match(/(?:gs\.?|pyg|₲)\s*([\d.]+)(?:,\d{1,2})?/i);
  if (!m) return null;
  var n = parseInt(m[1].replace(/\./g, ''), 10);
  return n > 0 && n < 10000000000 ? n : null;
}

/** «20/10/2026 18:40» o «18/10/2026 01:03:36» -> «2026-10-20T18:40». */
function fecha(valor) {
  var m = String(valor || '').match(/(\d{2})\/(\d{2})\/(\d{4})(?:\s+(\d{2}):(\d{2}))?/);
  return m ? m[3] + '-' + m[2] + '-' + m[1] + 'T' + (m[4] || '00') + ':' + (m[5] || '00') : null;
}

var SEGURIDAD = /codigo|clave|token|contrasena|\bpin\b|acceso|inicio de sesion|ingreso a|verificacion|seguridad|bloqueo|otp/;

function interpretar(asunto, html) {
  var a = normalizar(asunto);
  if (SEGURIDAD.test(a)) return null; // nunca se leen ni se devuelven
  var texto = textoPlano(html);
  var cuerpo = normalizar(texto);
  if (SEGURIDAD.test(cuerpo) && !/pago de servicios|transferencia/.test(a)) return null;
  var resultado = null;
  // mover plata entre cuentas propias no es gasto ni ingreso
  if (a.indexOf('entre cuentas propias') >= 0) return null;
  if (a.indexOf('transferencia a otros bancos') >= 0 || a.indexOf('transferencia a otra cuenta') >= 0) {
    // Mandarse plata a uno mismo en otro banco tampoco es gasto. Los nombres se comparan acá y nunca salen.
    var pagador = normalizar(campo(texto, ['Cliente Pagador', 'Remitente']));
    var beneficiario = normalizar(campo(texto, ['Cliente Beneficiario', 'Beneficiario']));
    if (pagador && beneficiario && pagador === beneficiario) return null;
    var destino = limpiar_(campo(texto, ['Entidad Beneficiaria']));
    resultado = { tipo: 'gasto', monto: monto(campo(texto, ['Moneda y Monto', 'Monto a enviar', 'Monto'])),
      fecha: fecha(campo(texto, ['Fecha y hora de Operación', 'Fecha y hora de Operacion', 'Fecha'])),
      detalle: destino ? 'Transferencia a ' + destino : 'Transferencia a otra cuenta' };
  } else if (a.indexOf('pago a traves de qr') >= 0) {
    resultado = { tipo: 'gasto', monto: monto(campo(texto, ['Monto'])), fecha: fecha(campo(texto, ['Fecha'])),
      detalle: limpiar_(campo(texto, ['Comercio'])) || 'Pago con QR' };
  } else if (a.indexOf('pago de servicios') >= 0) {
    resultado = { tipo: 'gasto', monto: monto(campo(texto, ['Monto'])), fecha: fecha(campo(texto, ['Fecha'])),
      detalle: limpiar_(campo(texto, ['Servicio'])) || 'Pago de servicios' };
  } else if (a.indexOf('transferencia de otros bancos') >= 0 || cuerpo.indexOf('transferencia de otras entidades recibida') >= 0) {
    var origen = limpiar_(campo(texto, ['Entidad Pagadora']));
    resultado = { tipo: 'ingreso', monto: monto(campo(texto, ['Moneda y Monto', 'Monto'])),
      fecha: fecha(campo(texto, ['Fecha y hora de Operación', 'Fecha y hora de Operacion', 'Fecha'])),
      detalle: origen ? 'Transferencia de ' + origen : 'Transferencia recibida' };
  }
  if (resultado && resultado.monto && resultado.fecha) { resultado.moneda = 'PYG'; return resultado; }
  // Del banco pero sin entender: el asistente le pregunta a David antes de anotar nada.
  // An unrecognized subject is free text: it can contain a person's name, account or reference.
  // Removing long digit runs does not make it safe (e.g. «Cuenta 123-456 de Ana Pérez»).
  return { tipo: 'desconocido', asunto: 'Correo del banco sin regla' };
}

/** Sin números largos (cuentas, referencias) y con largo acotado. */
function limpiar_(s) {
  if (!s) return null;
  var t = String(s).replace(/\*+\d+/g, '').replace(/\d{4,}/g, '').replace(/\s+/g, ' ').trim();
  return t ? t.slice(0, 80) : null;
}

if (typeof module !== 'undefined') module.exports = { interpretar, textoPlano, campo, monto, fecha, normalizar };
