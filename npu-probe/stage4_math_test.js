// Numerical specification, not a C/driver test. Run with node stage4_math_test.js.
// No dependencies, model, compiler, files, network or accelerator required.
// Compare the represented real vectors against an independent unscaled reference.
'use strict';
function assert(ok, message) { if (!ok) throw new Error(message); }
function half(x) {
    if (!Number.isFinite(x) || x === 0) return x;
    const v = Math.abs(x), e = Math.floor(Math.log2(v));
    const step = Math.pow(2, Math.max(-24, e - 10));
    const units = v / step, lo = Math.floor(units), f = units - lo;
    const nearest = f > 0.5 || (f === 0.5 && lo % 2) ? lo + 1 : lo;
    const y = nearest * step;
    return Math.sign(x) * (y > 65504 ? Infinity : y);
}
const norm = x => Math.sqrt(x.reduce((s, v) => s + v * v, 0));
let checks = 0, maxError = 0;
for (const fp16 of [false, true]) {
    function op(x) {
        assert(Number.isFinite(x) && Math.abs(x) <= 65504, 'intermediate overflow');
        return fp16 ? half(x) : x;
    }
    const reciprocal = x => { const r = op(1 / Math.sqrt(x)); return op(r * r); };
    function normalize(z, a) {
        const squares = z.map(v => op(v * v));
        assert(squares.reduce((s, v) => s + v, 0) <= 4200, 'unsafe reduction');
        const mean = op(squares.reduce((s, v) => s + v, 0) / z.length);
        const r = op(op(1 / Math.sqrt(op(mean + 1 / 4096))) * op(64 / Math.sqrt(z.length)));
        z = z.map(v => op(v * r));
        a = op(a * r);
        const cap = reciprocal(op(1 + a));
        return {z: z.map(v => op(v * cap)), a: op(a * cap)};
    }
    function pair(real) {
        const a = op(Math.min(1, 32 / (norm(real) || 1)));
        return normalize(real.map(v => op(v * a)), a);
    }
    function add(x, y) {
        const inv = reciprocal(op(x.a + y.a));
        const u = op(y.a * inv), v = op(x.a * inv);
        return normalize(x.z.map((z, i) => op(op(z * u) + op(y.z[i] * v))), op(x.a * u));
    }
    function compare(p, real, label) {
        assert(p.a > 0 && p.a <= 1.01 && norm(p.z) <= 65, label + ' pair invariant');
        const error = norm(real.map((v, i) => p.z[i] / p.a - v)) / (norm(real) || 1);
        assert(error < (fp16 ? 0.025 : 1e-11), label + ' relative error ' + error);
        maxError = Math.max(error, maxError);
        checks++;
    }
    for (const distributed of [false, true]) {
        const dim = 1536;
        function vector(length) {
            return Array.from({length: dim}, (_, i) => distributed ? length / Math.sqrt(dim) : (i ? 0 : length));
        }
        let real = vector(0.43), p = pair(real), previous = 0.43;
        for (const length of [38, 4632, 10208, 10600, 10600]) {
            const delta = vector(length - previous);
            p = add(p, pair(delta));
            real = real.map((v, i) => v + delta[i]);
            compare(p, real, 'growth ' + length);
            previous = length;
        }
        compare(add(pair(vector(38)), pair(vector(-38))), vector(0), 'cancellation');
        compare(add(pair(vector(0)), pair(vector(0))), vector(0), 'zero');
        // RMSNorm epsilon must track the square of the inverse scale.
        const small = vector(0.43), s = pair(small), eps = 1e-5;
        const realMean = small.reduce((sum, v) => sum + v * v, 0) / dim;
        const scaledMean = s.z.reduce((sum, v) => sum + v * v, 0) / dim;
        const rmsError = norm(s.z.map((v, i) => v / Math.sqrt(scaledMean + eps * s.a * s.a)
            - small[i] / Math.sqrt(realMean + eps))) / Math.sqrt(dim);
        assert(rmsError < (fp16 ? 0.005 : 1e-11), 'epsilon in real units');
        checks++;
    }
    // Old SwiGLU overflows at 200 * 1000; the new product stays <=64 in norm.
    const dim = 8960;
    const gate = Array.from({length: dim}, (_, i) => i ? 0 : 200);
    const up = Array.from({length: dim}, (_, i) => i ? 0 : 1000);
    const g = pair(gate), u = pair(up);
    const hidden = g.z.map((v, i) => {
        const realGate = op(v * reciprocal(g.a));
        const sigmoid = op(1 / (1 + Math.exp(-realGate)));
        return op(op(op(v * 0.125) * sigmoid) * op(u.z[i] * 0.125));
    });
    const h = normalize(hidden, op(g.a * u.a));
    const realHidden = gate.map((v, i) => v / (1 + Math.exp(-v)) * up[i]);
    compare(h, realHidden.map(v => v / 64), 'SwiGLU / 64');
    // A linear down projection commutes with the deferred factor of 64.
    const down = normalize(h.z.map(v => op(v * 0.02)), op(1));
    down.a = op(op(down.a * h.a) * (1 / 64));
    compare(down, realHidden.map(v => v * 0.02), 'down projection compensation');
    // One zero weight row sets a very large tensor scale. The remaining rows
    // have a correction of 0.0001. Legacy FC produces 512000 BEFORE correction.
    const rawBound = 1000 * Math.sqrt(63 * 64) * 64;
    const correctedBound = 0.1 * Math.sqrt(63 * 64) * 64;
    const gain = 32 / rawBound, outputGain = 32 / correctedBound;
    const rawFC = op(512000 * gain);
    const corrected = op(rawFC * op(0.0001 * outputGain / gain));
    const fc = normalize(Array.from({length: 64}, (_, i) => i ? corrected : 0), op(outputGain));
    compare(fc, Array.from({length: 64}, (_, i) => i ? 51.2 : 0), 'FC row correction');
    assert(!Number.isFinite(half(200000)) && !Number.isFinite(half(512000)), 'legacy overflow controls');
    checks++;
}
const result = 'PASS: ' + checks + ' mathematical checks; maximum relative error ' + maxError;
if (typeof console !== 'undefined') console.log(result);
result;
