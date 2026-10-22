'use strict';
const test = require('node:test');
const assert = require('node:assert');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const sandbox = { module: { exports: {} } };
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '..', 'Code.gs'), 'utf8'), sandbox);
const { validar, hex, viejos } = sandbox.module.exports;

const archivo = Buffer.from('SQLite format 3\0datos de prueba');
const bueno = () => ({ nombre: 'asistente-2026-10-22.db', datos: archivo.toString('base64'),
  sha256: crypto.createHash('sha256').update(archivo).digest('hex') });

test('acepta solo el pedido esperado', () => {
  assert.strictEqual(validar(bueno()).nombre, 'asistente-2026-10-22.db');
  for (const roto of [null, [], 'texto', { ...bueno(), extra: 1 }, { ...bueno(), nombre: '../../x.db' },
    { ...bueno(), nombre: 'asistente-2026-10-22.db.exe' }, { ...bueno(), sha256: 'ABC' }, { ...bueno(), datos: 'no base64!' },
    { ...bueno(), datos: 'abc' }, { ...bueno(), datos: '' }])
    assert.throws(() => validar(roto));
});

test('el sha256 en hex coincide con el de Node, también con bytes negativos', () => {
  const firmados = [...crypto.createHash('sha256').update(archivo).digest()].map(b => (b > 127 ? b - 256 : b));
  assert.strictEqual(hex(firmados), bueno().sha256);
});

test('borra solo copias de más de 30 días y nunca otros archivos', () => {
  const hoy = new Date(Date.UTC(2026, 9, 22, 6));
  assert.deepStrictEqual(viejos(['asistente-2026-09-21.db', 'asistente-2026-09-22.db', 'asistente-2026-10-22.db',
    'notas.txt', 'asistente-2025-01-01.db.bak'], hoy), ['asistente-2026-09-21.db']);
});
