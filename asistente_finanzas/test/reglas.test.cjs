'use strict';
// Correos con la misma forma que los de Banco Familiar (2026-10), con datos inventados.
const test = require('node:test');
const assert = require('node:assert');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

const sandbox = { module: { exports: {} } };
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '..', 'Code.gs'), 'utf8'), sandbox);
const { interpretar, monto, fecha } = sandbox.module.exports;

const fila = (etiqueta, valor) => `<tr><td class="l">${etiqueta}:</td><td class="v">${valor}</td></tr>`;
const correo = (titulo, filas) => `<html><head><style>td{font:12px Arial}</style></head><body><table>
  <tr><td><b>${titulo}</b></td></tr><tr><td>Hemos registrado la siguiente operaci&oacute;n</td></tr>${filas.join('')}</table></body></html>`;

test('pago de servicios -> gasto con monto, fecha y servicio, sin cuenta ni referencias', () => {
  const html = correo('PAGO de SERVICIOS', [fila('N&ordm; de Cuenta D&eacute;bito', '******1111'), fila('Servicio', 'TIGO Compra de saldo'),
    fila('Monto', 'Gs 10.000'), fila('Fecha', '20/10/2026 18:40'), fila('Referencia de Pago', '12345678'),
    fila('N&uacute;mero de Transacci&oacute;n', '987654321'), fila('Ticket', '5555555555'), fila('CRC', '1f68abcdef0123456789')]);
  const m = interpretar('PAGO de SERVICIOS', html);
  assert.deepStrictEqual({ ...m }, { tipo: 'gasto', monto: 10000, fecha: '2026-10-20T18:40', detalle: 'TIGO Compra de saldo', moneda: 'PYG' });
  assert.ok(!JSON.stringify(m).match(/1111|12345678|987654321|5555555555|1f68/));
});

test('transferencia recibida -> ingreso con el banco de origen, sin nombres ni cuentas', () => {
  const html = correo('TRANSFERENCIA DE OTRAS ENTIDADES RECIBIDA', [fila('Nro. de Operaci&oacute;n', '700000001'),
    fila('Referencia', 'AMAMPYPAARES00000000000000000'), fila('Fecha y hora de Operaci&oacute;n', '18/10/2026 01:03:36'),
    fila('Entidad Pagadora', 'BANCO BASA S.A.'), fila('Cliente Pagador', 'PERSONA DE PRUEBA'), fila('Nro. de Cuenta del Pagador', '*****2222'),
    fila('Moneda y Monto', 'PYG 875.000'), fila('Cliente Beneficiario', 'OTRA PERSONA'), fila('Nro. de Cuenta del Beneficiario', '33333333')]);
  const m = interpretar('Transferencia de otros bancos', html);
  assert.deepStrictEqual({ ...m }, { tipo: 'ingreso', monto: 875000, fecha: '2026-10-18T01:03', detalle: 'Transferencia de BANCO BASA S.A.', moneda: 'PYG' });
  assert.ok(!JSON.stringify(m).match(/PERSONA|2222|33333333|700000001|AMAM/));
});

test('correos de seguridad del banco nunca se devuelven', () => {
  for (const asunto of ['Código de verificación', 'Tu clave de acceso', 'Nuevo inicio de sesión en Familiar A Toda Hora', 'Token de seguridad', 'PIN generado'])
    assert.strictEqual(interpretar(asunto, correo(asunto, [fila('Código', '123456')])), null);
});

test('un correo del banco que no se entiende se marca para preguntar, sin números largos', () => {
  const m = interpretar('Débito automático 4455667788', correo('DEBITO', [fila('Monto', 'Gs 50.000')]));
  assert.deepStrictEqual({ ...m }, { tipo: 'desconocido', asunto: 'Correo del banco sin regla' });
  const incompleto = interpretar('PAGO de SERVICIOS', correo('PAGO', [fila('Servicio', 'ANDE')]));
  assert.strictEqual(incompleto.tipo, 'desconocido');
});

test('un asunto desconocido no filtra nombres, cuentas separadas ni referencias alfanuméricas', () => {
  const m = interpretar('Cuenta 123-456 de Ana Pérez ref. ABXY-ZZZZ', 'Sin regla');
  assert.deepStrictEqual({ ...m }, { tipo: 'desconocido', asunto: 'Correo del banco sin regla' });
});

test('lee todas las páginas aunque la primera sean solo correos de seguridad', () => {
  const pages = [];
  const env = { Gmail: { Users: { Messages: {
    list: (_, options) => {
      pages.push(options.pageToken || 'primera');
      return options.pageToken ? { messages: [{ id: 'movimiento2' }] }
        : { messages: [{ id: 'seguridad1' }], nextPageToken: 'segunda' };
    },
    get: (_, id) => ({ payload: { headers: [
      { name: 'From', value: 'operbancaweb@familiar.com.py' },
      { name: 'Subject', value: id === 'seguridad1' ? 'Código de acceso' : 'Aviso sin regla' }
    ] } })
  } } } };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '..', 'Code.gs'), 'utf8'), env);
  const items = env.leer_(3);
  assert.deepStrictEqual(pages, ['primera', 'segunda']);
  assert.strictEqual(items.length, 1); assert.strictEqual(items[0].id, 'movimiento2');
});

test('montos y fechas', () => {
  assert.strictEqual(monto('Gs 1.250.000'), 1250000);
  assert.strictEqual(monto('₲ 25.000'), 25000);
  assert.strictEqual(monto('PYG 875.000,00'), 875000);
  assert.strictEqual(monto('USD 10'), null);
  assert.strictEqual(fecha('20/10/2026 18:40'), '2026-10-20T18:40');
  assert.strictEqual(fecha('sin fecha'), null);
});

test('pago con QR -> gasto con el comercio; entre cuentas propias se ignora', () => {
  const qr = interpretar('Pago a través de QR', correo('PAGO QR', [fila('Debitado de la Cuenta', '******1111'), fila('Comercio', 'SUPERMERCADO EJEMPLO'),
    fila('Monto', 'Gs 85.500'), fila('Fecha', '19/10/2026 12:15'), fila('N&uacute;mero de Operaci&oacute;n', '44444444')]));
  assert.deepStrictEqual({ ...qr }, { tipo: 'gasto', monto: 85500, fecha: '2026-10-19T12:15', detalle: 'SUPERMERCADO EJEMPLO', moneda: 'PYG' });
  assert.strictEqual(interpretar('Transferencia entre cuentas propias', correo('PROPIAS', [fila('Monto a enviar', 'Gs 100.000')])), null);
});

test('transferencias que salen -> gasto con el banco de destino, sin la persona; a uno mismo se ignora', () => {
  const otros = interpretar('Transferencia a otros bancos', correo('TRANSFERENCIA A OTRAS ENTIDADES', [fila('Nro. de Operaci&oacute;n', '700000002'),
    fila('Fecha y hora de Operaci&oacute;n', '17/10/2026 09:30:00'), fila('Entidad Pagadora', 'BANCO FAMILIAR S.A.E.C.A.'), fila('Cliente Pagador', 'TITULAR PRUEBA'),
    fila('Moneda y Monto', 'PYG 150.000'), fila('Entidad Beneficiaria', 'BANCO ITAU'), fila('Cliente Beneficiario', 'AMIGO PRUEBA')]));
  assert.deepStrictEqual({ ...otros }, { tipo: 'gasto', monto: 150000, fecha: '2026-10-17T09:30', detalle: 'Transferencia a BANCO ITAU', moneda: 'PYG' });
  assert.ok(!JSON.stringify(otros).match(/AMIGO|TITULAR|700000002/));
  const otra = interpretar('Transferencia a otra cuenta', correo('TRANSFERENCIA', [fila('Fecha', '16/10/2026'), fila('Cuenta D&eacute;bito', '******1111'),
    fila('Remitente', 'TITULAR PRUEBA'), fila('Monto a enviar', 'Gs 40.000'), fila('Cuenta Cr&eacute;dito', '******2222'), fila('Beneficiario', 'AMIGO PRUEBA')]));
  assert.deepStrictEqual({ ...otra }, { tipo: 'gasto', monto: 40000, fecha: '2026-10-16T00:00', detalle: 'Transferencia a otra cuenta', moneda: 'PYG' });
  const propia = interpretar('Transferencia a otros bancos', correo('T', [fila('Cliente Pagador', 'Titular Prueba'), fila('Cliente Beneficiario', 'TITULAR  PRUEBA'),
    fila('Moneda y Monto', 'PYG 1.000.000'), fila('Fecha y hora de Operaci&oacute;n', '15/10/2026 10:00:00')]));
  assert.strictEqual(propia, null);
});
