#!/usr/bin/env node
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

globalThis.__mcLayout = {};
globalThis.__mcHeap = () => ({});
globalThis.__mcNative = { defineAll() {}, define() {} };

const shaders = path.join(__dirname, '..', 'web', 'native', 'shaders.js');
vm.runInThisContext(fs.readFileSync(shaders, 'utf8'), { filename: shaders });
const normalize = globalThis.__mcShaders.normalizeClampCalls;
assert.strictEqual(typeof normalize, 'function', 'normalizer was not exported');

function norm(source, macros) {
  return normalize(source, macros || new Map());
}
function check(name, source, expected, macros) {
  const actual = norm(source, macros);
  assert.ok(actual.includes(expected), name + '\nexpected: ' + expected + '\nactual:   ' + actual);
}
function unchanged(name, source, macros) {
  assert.strictEqual(norm(source, macros), source, name);
}

check('logarithmic float expression',
  'float transmittance;\nfloat f() { return clamp(-log(max(transmittance, 0.0001)), 0, 4.0); }',
  'clamp(-log(max(transmittance, 0.0001)), float(0), float(4.0))');

check('signed integer overload',
  'float x;\nint f() { return clamp(int(floor(x)), 0, 7); }',
  'clamp(int(floor(x)), int(0), int(7))');

check('unsigned integer overload',
  'float x;\nuint f() { return clamp(uint(x), 0, 7u); }',
  'clamp(uint(x), uint(0), uint(7u))');

check('double overload',
  'double x;\ndouble f() { return clamp(log(x), 0, 4.0lf); }',
  'clamp(log(x), double(0), double(4.0lf))');

check('vector swizzle',
  'vec4 color;\nvec3 f() { return clamp(color.rgb, 0, 1.0); }',
  'clamp(color.rgb, vec3(0), vec3(1.0))');

check('scalar swizzle',
  'vec4 color;\nfloat f() { return clamp(color.a, 0, 1); }',
  'clamp(color.a, float(0), float(1))');

check('existing vector constructors are preserved',
  'vec4 color;\nvec4 f() { return clamp(color, vec4(0.0), vec4(1.0)); }',
  'clamp(color, vec4(0.0), vec4(1.0))');

check('integer vector constructor',
  'vec2 xy;\nivec2 f() { return clamp(ivec2(xy), 0, ivec2(8)); }',
  'clamp(ivec2(xy), ivec2(0), ivec2(8))');

check('nested clamp calls',
  'float x;\nfloat f() { return clamp(clamp(x, 0.0, 1.0), 0, 1); }',
  'clamp(clamp(x, float(0.0), float(1.0)), float(0), float(1))');

check('user function return type',
  'float tone(float x) { return x; }\nfloat x;\nfloat f() { return clamp(tone(x), 0, 4.0); }',
  'clamp(tone(x), float(0), float(4.0))');

check('nested comma arguments',
  'float a; float b;\nfloat f() { return clamp(mix(a, b, 0.5), 0, 1); }',
  'clamp(mix(a, b, 0.5), float(0), float(1))');

check('macro integer bound',
  'float x;\nint f() { return clamp(int(x), 0, OIT_COEFF_COUNT - 1); }',
  'clamp(int(x), int(0), int(OIT_COEFF_COUNT - 1))',
  new Map([['OIT_COEFF_COUNT', '8']]));

unchanged('unknown first argument is left untouched', 'float f() { return clamp(mystery, 0, 1); }');
unchanged('unknown nested expression is left untouched', 'float f() { return clamp(a + b, 0, 1); }');

const overloads = [
  ['int', '0', '1'], ['ivec2', '0', '1'], ['ivec3', '0', '1'], ['ivec4', '0', '1'],
  ['uint', '0u', '1u'], ['uvec2', '0u', '1u'], ['uvec3', '0u', '1u'], ['uvec4', '0u', '1u'],
  ['float', '0.0', '1.0'], ['vec2', '0.0', '1.0'], ['vec3', '0.0', '1.0'], ['vec4', '0.0', '1.0'],
  ['double', '0.0lf', '1.0lf'], ['dvec2', '0.0lf', '1.0lf'], ['dvec3', '0.0lf', '1.0lf'], ['dvec4', '0.0lf', '1.0lf'],
];
for (const [type, low, high] of overloads) {
  check('overload ' + type,
    type + ' value;\n' + type + ' f() { return clamp(value, ' + low + ', ' + high + '); }',
    'clamp(value, ' + type + '(' + low + '), ' + type + '(' + high + '))');
}

console.log('shader clamp normalizer: ' + (overloads.length + 16) + ' checks passed');
