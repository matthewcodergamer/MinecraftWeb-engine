// Shader toolchain natives for renderpearl in the browser.
//  * shaderc  -> glslang (WASM): GLSL -> Vulkan SPIR-V, with shaderc's include callbacks, macro definitions and
//                auto-bound uniforms emulated in a small source preprocessor.
//  * spvc     -> reflection-only SPIRV-Cross API (what renderpearl's frontend uses), over the SPIR-V parser below.
//  * __mcShaders.toWGSL(words) for the WebGPU backend: combined image-samplers are split into texture + sampler
//    (WGSL has no combined samplers), then Tint (twgsl, WASM) converts SPIR-V -> WGSL.
'use strict';
(function () {
  const L = globalThis.__mcLayout, H = () => globalThis.__mcHeap, N = globalThis.__mcNative;
  const S = {
    glslang: null,
    twgsl: null,
    handles: [null],
    failedShaders: [],
  };
  globalThis.__mcShaders = S;

  function handle(obj) {
    S.handles.push(obj);
    return S.handles.length - 1;
  }

  function loadScript(src) {
    return new Promise((res, rej) => {
      const s = document.createElement('script');
      s.src = src;
      s.onload = res;
      s.onerror = () => rej(new Error('failed to load ' + src));
      document.head.appendChild(s);
    });
  }

  /** Both Emscripten loaders declare a global `Module`, so they are initialised one after the other. */
  S.init = async function (base) {
    await loadScript(base + 'vendor/twgsl.js');
    S.twgsl = await globalThis.twgsl(base + 'vendor/twgsl.wasm');
    // glslang's Emscripten runtime binds console.log/warn when it starts; route them through a capture hook
    // so compile errors end up in shaderc_result_get_error_message instead of only on the console.
    for (const k of ['log', 'warn', 'error']) {
      const orig = console[k].bind(console);
      console[k] = (...a) => { if (S.capture) S.capture.push(a.join(' ')); else orig(...a); };
    }
    await loadScript(base + 'vendor/glslang.js');
    S.glslang = await globalThis.glslang(base + 'vendor/glslang.wasm');
  };

  // =================================================================== SPIR-V parsing
  const Op = {
    Name: 5, MemberName: 6, EntryPoint: 15, TypeVoid: 19, TypeBool: 20, TypeInt: 21, TypeFloat: 22, TypeVector: 23,
    TypeMatrix: 24, TypeImage: 25, TypeSampler: 26, TypeSampledImage: 27, TypeArray: 28, TypeRuntimeArray: 29,
    TypeStruct: 30, TypePointer: 32, TypeFunction: 33, Constant: 43, Function: 54, FunctionParameter: 55,
    FunctionEnd: 56, FunctionCall: 57, Variable: 59, Load: 61, AccessChain: 65, Decorate: 71, MemberDecorate: 72,
    CompositeExtract: 81, CopyObject: 83, SampledImage: 86, ImageFetch: 95, Image: 100,
    ShiftRightLogical: 194, ShiftRightArithmetic: 195, ShiftLeftLogical: 196, BitwiseAnd: 199,
  };
  const Dec = { Block: 2, BufferBlock: 3, ArrayStride: 6, MatrixStride: 7, BuiltIn: 11, NonWritable: 24, Location: 30,
    Binding: 33, DescriptorSet: 34, Offset: 35 };
  const SC = { UniformConstant: 0, Input: 1, Uniform: 2, Output: 3, PushConstant: 9, StorageBuffer: 12 };

  function str(words, i, end) {
    let s = '';
    for (let w = i; w < end; w++) {
      const v = words[w];
      for (let b = 0; b < 4; b++) {
        const c = (v >>> (8 * b)) & 255;
        if (!c) return s;
        s += String.fromCharCode(c);
      }
    }
    return s;
  }

  function parse(words) {
    if (words[0] !== 0x07230203) throw new Error('not SPIR-V');
    const m = { words, names: {}, memberDecor: {}, decor: {}, types: {}, consts: {}, vars: [], entryInterface: [] };
    for (let i = 5; i < words.length;) {
      const wc = words[i] >>> 16, op = words[i] & 0xffff;
      if (!wc) throw new Error('bad SPIR-V word count at ' + i);
      const a = i + 1;
      switch (op) {
        case Op.Name: m.names[words[a]] = str(words, a + 1, i + wc); break;
        case Op.EntryPoint: {
          // interface ids follow the name string
          let j = a + 2;
          while (j < i + wc) { const w = words[j++]; if (((w >>> 24) & 255) === 0 || (w & 0xff000000) === 0) break; }
          for (; j < i + wc; j++) m.entryInterface.push(words[j]);
          break;
        }
        case Op.Decorate: {
          const d = m.decor[words[a]] || (m.decor[words[a]] = {});
          d[words[a + 1]] = { value: wc > 3 ? words[a + 2] : 1, offset: wc > 3 ? a + 2 : -1 };
          break;
        }
        case Op.MemberDecorate: {
          const d = m.memberDecor[words[a]] || (m.memberDecor[words[a]] = {});
          const mem = d[words[a + 1]] || (d[words[a + 1]] = {});
          mem[words[a + 2]] = wc > 4 ? words[a + 3] : 1;
          break;
        }
        case Op.TypeVoid: m.types[words[a]] = { kind: 'void' }; break;
        case Op.TypeBool: m.types[words[a]] = { kind: 'bool' }; break;
        case Op.TypeInt: m.types[words[a]] = { kind: 'int', width: words[a + 1], signed: words[a + 2] }; break;
        case Op.TypeFloat: m.types[words[a]] = { kind: 'float', width: words[a + 1] }; break;
        case Op.TypeVector: m.types[words[a]] = { kind: 'vector', elem: words[a + 1], count: words[a + 2] }; break;
        case Op.TypeMatrix: m.types[words[a]] = { kind: 'matrix', column: words[a + 1], count: words[a + 2] }; break;
        case Op.TypeImage: m.types[words[a]] = { kind: 'image', sampledType: words[a + 1], dim: words[a + 2], depth: words[a + 3],
          arrayed: words[a + 4], ms: words[a + 5], sampled: words[a + 6], format: words[a + 7] }; break;
        case Op.TypeSampler: m.types[words[a]] = { kind: 'sampler' }; break;
        case Op.TypeSampledImage: m.types[words[a]] = { kind: 'sampledImage', image: words[a + 1] }; break;
        case Op.TypeArray: m.types[words[a]] = { kind: 'array', elem: words[a + 1], lengthId: words[a + 2] }; break;
        case Op.TypeRuntimeArray: m.types[words[a]] = { kind: 'array', elem: words[a + 1], lengthId: 0 }; break;
        case Op.TypeStruct: m.types[words[a]] = { kind: 'struct', members: Array.from(words.subarray(a + 1, i + wc)) }; break;
        case Op.TypePointer: m.types[words[a]] = { kind: 'pointer', storage: words[a + 1], pointee: words[a + 2] }; break;
        case Op.TypeFunction: m.types[words[a]] = { kind: 'function' }; break;
        case Op.Constant: m.consts[words[a + 1]] = words[a + 2]; break;
        case Op.Variable: m.vars.push({ type: words[a], id: words[a + 1], storage: words[a + 2] }); break;
        default: break;
      }
      i += wc;
    }
    return m;
  }

  function isBuiltinVar(m, v) {
    if (m.decor[v.id] && m.decor[v.id][Dec.BuiltIn]) return true;
    const t = m.types[m.types[v.type].pointee];
    const md = t && t.kind === 'struct' && m.memberDecor[m.types[v.type].pointee];
    if (md) for (const k in md) if (md[k][Dec.BuiltIn] !== undefined) return true;
    return false;
  }

  function stripArrays(m, id) {
    let t = m.types[id];
    while (t && t.kind === 'array') { id = t.elem; t = m.types[id]; }
    return id;
  }

  /** SPIRV-Cross resource classification (spvc_resource_type) of every variable. */
  function classify(m) {
    const R = L.Spvc, out = {};
    const add = (type, v) => (out[type] || (out[type] = [])).push(v);
    const iface = new Set(m.entryInterface);
    for (const v of m.vars) {
      const ptr = m.types[v.type];
      if (!ptr || ptr.kind !== 'pointer') continue;
      const base = stripArrays(m, ptr.pointee), bt = m.types[base];
      const decor = m.decor[base] || {};
      if (v.storage === SC.Input && (iface.has(v.id) || !m.entryInterface.length) && !isBuiltinVar(m, v)) add(R.SPVC_RESOURCE_TYPE_STAGE_INPUT, v);
      else if (v.storage === SC.Output && (iface.has(v.id) || !m.entryInterface.length) && !isBuiltinVar(m, v)) add(R.SPVC_RESOURCE_TYPE_STAGE_OUTPUT, v);
      else if (v.storage === SC.Uniform && decor[Dec.Block]) add(R.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, v);
      else if ((v.storage === SC.Uniform && decor[Dec.BufferBlock]) || v.storage === SC.StorageBuffer) add(R.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, v);
      else if (v.storage === SC.PushConstant) add(R.SPVC_RESOURCE_TYPE_PUSH_CONSTANT, v);
      else if (v.storage === SC.UniformConstant && bt) {
        if (bt.kind === 'sampledImage') add(R.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, v);
        else if (bt.kind === 'image' && bt.dim === 5 && bt.sampled === 1) add(R.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, v);
        else if (bt.kind === 'image' && bt.sampled === 2) add(R.SPVC_RESOURCE_TYPE_STORAGE_IMAGE, v);
        else if (bt.kind === 'image') add(R.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, v);
        else if (bt.kind === 'sampler') add(R.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS, v);
      }
    }
    return out;
  }

  function resourceName(m, v) {
    const ptr = m.types[v.type];
    const base = ptr && ptr.kind === 'pointer' ? stripArrays(m, ptr.pointee) : -1;
    const decor = base >= 0 ? m.decor[base] || {} : {};
    if (decor[Dec.Block] || decor[Dec.BufferBlock]) {
      const block = m.names[base];
      if (block) return block;
    }
    return m.names[v.id] || m.names[base] || (ptr && m.names[ptr.pointee]) || '';
  }

  /** Size in bytes of a type in a buffer block (uses the Offset/ArrayStride/MatrixStride decorations). */
  function typeSize(m, id) {
    const t = m.types[id];
    switch (t.kind) {
      case 'bool': return 4;
      case 'int': case 'float': return t.width / 8;
      case 'vector': return typeSize(m, t.elem) * t.count;
      case 'matrix': {
        const stride = 16;
        return stride * t.count;
      }
      case 'array': {
        const stride = (m.decor[id] && m.decor[id][Dec.ArrayStride]) ? m.decor[id][Dec.ArrayStride].value : typeSize(m, t.elem);
        return stride * (t.lengthId ? m.consts[t.lengthId] : 0);
      }
      case 'struct': {
        let size = 0;
        t.members.forEach((mt, i) => {
          const md = (m.memberDecor[id] || {})[i] || {};
          const off = md[Dec.Offset] || 0;
          let s = typeSize(m, mt);
          if (m.types[mt].kind === 'matrix' && md[Dec.MatrixStride]) s = md[Dec.MatrixStride] * m.types[mt].count;
          size = Math.max(size, off + s);
        });
        return size;
      }
      default: return 0;
    }
  }

  const GLSL_TYPE_PATTERN = '(?:int|uint|float|double|bool|ivec[234]|uvec[234]|vec[234]|dvec[234]|bvec[234]|mat[234](?:x[234])?)';

  function normalizeClampCalls(source, macros) {
    const parsedType = (name) => {
      let match = /^(int|uint|float|double|bool)$/.exec(name);
      if (match) return { base: match[1], vec: 1, dims: 0 };
      match = /^([iudb]?)vec([234])$/.exec(name);
      if (match) {
        const base = { i: 'int', u: 'uint', d: 'double', b: 'bool', '': 'float' }[match[1]];
        return { base, vec: Number(match[2]), dims: 0 };
      }
      match = /^mat([234])(?:x([234]))?$/.exec(name);
      if (match) {
        const cols = Number(match[1]);
        return { base: 'float', vec: match[2] ? Number(match[2]) : cols, cols, kind: 'matrix', dims: 0 };
      }
      return null;
    };

    const sameType = (a, b) => !!a && !!b && a.base === b.base && a.vec === b.vec &&
      (a.dims || 0) === (b.dims || 0) && (a.kind || '') === (b.kind || '');

    const typeLabel = (type) => {
      if (!type || type.kind === 'matrix' || type.dims || type.base === 'bool') return null;
      if (type.vec < 1 || type.vec > 4) return null;
      if (type.vec === 1) return type.base;
      const prefix = { int: 'i', uint: 'u', float: '', double: 'd' }[type.base];
      return prefix === undefined ? null : prefix + 'vec' + type.vec;
    };

    const commonBase = (a, b) => {
      if (a === 'bool' || b === 'bool') return null;
      if (a === b) return a;
      if ((a === 'int' && b === 'uint') || (a === 'uint' && b === 'int')) return 'uint';
      if (a === 'double' || b === 'double') return 'double';
      if (a === 'float' || b === 'float') return 'float';
      return null;
    };

    const combine = (a, b) => {
      if (!a || !b || a.kind === 'matrix' || b.kind === 'matrix' || a.dims || b.dims) return null;
      const base = commonBase(a.base, b.base);
      if (!base) return null;
      if (a.vec !== 1 && b.vec !== 1 && a.vec !== b.vec) return null;
      return { base, vec: Math.max(a.vec, b.vec), dims: 0 };
    };

    const stripCommentsAndDirectives = (text) => {
      let out = '', i = 0;
      while (i < text.length) {
        if (text[i] === '/' && text[i + 1] === '/') {
          const end = text.indexOf('\n', i);
          const stop = end < 0 ? text.length : end;
          out += ' '.repeat(stop - i);
          i = stop;
        } else if (text[i] === '/' && text[i + 1] === '*') {
          const end = text.indexOf('*/', i + 2);
          const stop = end < 0 ? text.length : end + 2;
          out += text.slice(i, stop).replace(/[^\n]/g, ' ');
          i = stop;
        } else {
          out += text[i++];
        }
      }
      return out.split('\n').map((line) => /^\s*#/.test(line) ? ' '.repeat(line.length) : line).join('\n');
    };

    const vars = new Map(), funcs = new Map();
    const sanitized = stripCommentsAndDirectives(source);
    const declaration = new RegExp('\\b(' + GLSL_TYPE_PATTERN + ')\\b\\s+([A-Za-z_]\\w*)\\s*((?:\\[[^\\]]*\\]\\s*)*)', 'g');
    let dm;
    while ((dm = declaration.exec(sanitized))) {
      const type = parsedType(dm[1]);
      if (!type) continue;
      let after = dm.index + dm[0].length;
      while (/\s/.test(sanitized[after] || '')) after++;
      if (sanitized[after] === '(') {
        funcs.set(dm[2], type);
        continue;
      }
      const dims = (dm[3].match(/\[/g) || []).length;
      const variable = Object.assign({}, type, { dims });
      const previous = vars.get(dm[2]);
      vars.set(dm[2], previous === undefined || sameType(previous, variable) ? variable : null);
    }

    const builtinVars = {
      gl_FragCoord: { base: 'float', vec: 4, dims: 0 },
      gl_FragColor: { base: 'float', vec: 4, dims: 0 },
      gl_FragDepth: { base: 'float', vec: 1, dims: 0 },
      gl_FragData: { base: 'float', vec: 4, dims: 1 },
      gl_FrontFacing: { base: 'bool', vec: 1, dims: 0 },
      gl_PointCoord: { base: 'float', vec: 2, dims: 0 },
      gl_PointSize: { base: 'float', vec: 1, dims: 0 },
      gl_Position: { base: 'float', vec: 4, dims: 0 },
      gl_VertexID: { base: 'int', vec: 1, dims: 0 },
      gl_InstanceID: { base: 'int', vec: 1, dims: 0 },
    };

    const literalType = (raw) => {
      const text = raw.toLowerCase();
      if (text.endsWith('lf')) return { base: 'double', vec: 1, dims: 0 };
      const hex = text.startsWith('0x');
      if (!hex && text.endsWith('f')) return { base: 'float', vec: 1, dims: 0 };
      if (!hex && /[.e]/.test(text)) return { base: 'float', vec: 1, dims: 0 };
      if (text.endsWith('u')) return { base: 'uint', vec: 1, dims: 0 };
      return { base: 'int', vec: 1, dims: 0 };
    };

    const tokenize = (text) => {
      const tokens = [];
      let i = 0;
      while (i < text.length) {
        const c = text[i];
        if (/\s/.test(c)) { i++; continue; }
        if (c === '/' && text[i + 1] === '/') {
          const end = text.indexOf('\n', i);
          i = end < 0 ? text.length : end;
          continue;
        }
        if (c === '/' && text[i + 1] === '*') {
          const end = text.indexOf('*/', i + 2);
          i = end < 0 ? text.length : end + 2;
          continue;
        }
        if (/[A-Za-z_]/.test(c)) {
          let j = i + 1;
          while (j < text.length && /[A-Za-z0-9_]/.test(text[j])) j++;
          tokens.push({ k: 'id', v: text.slice(i, j) });
          i = j;
          continue;
        }
        if (/[0-9]/.test(c) || (c === '.' && /[0-9]/.test(text[i + 1] || ''))) {
          let j = i;
          if (c === '0' && /[xX]/.test(text[i + 1] || '')) {
            j = i + 2;
            while (/[0-9a-fA-F]/.test(text[j] || '')) j++;
            while (/[uU]/.test(text[j] || '')) j++;
          } else {
            while (/[0-9]/.test(text[j] || '')) j++;
            if (text[j] === '.') {
              j++;
              while (/[0-9]/.test(text[j] || '')) j++;
            }
            if (/[eE]/.test(text[j] || '')) {
              let k = j + 1;
              if (/[+-]/.test(text[k] || '')) k++;
              if (/[0-9]/.test(text[k] || '')) {
                j = k;
                while (/[0-9]/.test(text[j] || '')) j++;
              }
            }
            while (/[uUlLfF]/.test(text[j] || '')) j++;
          }
          tokens.push({ k: 'num', v: text.slice(i, j) });
          i = j;
          continue;
        }
        const two = text.slice(i, i + 2);
        if (['&&', '||', '<=', '>=', '==', '!=', '<<', '>>', '++', '--', '+=', '-=', '*=', '/=', '%=', '&=', '|=', '^='].includes(two)) {
          tokens.push({ k: 'op', v: two });
          i += 2;
          continue;
        }
        tokens.push({ k: 'op', v: c });
        i++;
      }
      return tokens;
    };

    const swizzleType = (type, swizzle) => {
      if (!type || type.kind === 'matrix' || type.dims || type.vec < 2 || !swizzle) return null;
      const groups = 'xyzwrgba stpq'.replace(' ', '');
      const first = swizzle[0];
      const group = 'xyzw'.includes(first) ? 'xyzw' : 'rgba'.includes(first) ? 'rgba' : 'stpq'.includes(first) ? 'stpq' : null;
      if (!group) return null;
      for (const ch of swizzle) if (!group.includes(ch)) return null;
      if (swizzle.length > 4 || swizzle.length > type.vec) return null;
      return { base: type.base, vec: swizzle.length, dims: 0 };
    };

    const indexType = (type, index) => {
      if (!type) return null;
      if (type.dims > 0) return Object.assign({}, type, { dims: type.dims - 1 });
      if (type.kind === 'matrix') return { base: type.base, vec: type.vec, dims: 0 };
      if (type.vec > 1) return { base: type.base, vec: 1, dims: 0 };
      return null;
    };

    const floatMath = new Set([
      'radians', 'degrees', 'sin', 'cos', 'tan', 'asin', 'acos', 'atan', 'sinh', 'cosh', 'tanh',
      'asinh', 'acosh', 'atanh', 'pow', 'exp', 'log', 'exp2', 'log2', 'sqrt', 'inversesqrt',
      'floor', 'trunc', 'round', 'roundEven', 'ceil', 'fract',
    ]);
    const preserveType = new Set(['abs', 'sign', 'mod', 'normalize', 'faceforward', 'reflect', 'refract']);
    const textureReturn = new Set([
      'texture', 'textureProj', 'textureLod', 'textureOffset', 'textureProjOffset', 'textureLodOffset',
      'textureProjLod', 'textureProjLodOffset', 'textureGrad', 'textureGradOffset', 'textureProjGrad',
      'textureProjGradOffset', 'texelFetch', 'texelFetchOffset', 'textureGather', 'textureGatherOffset',
      'imageLoad',
    ]);

    const inferExpression = (expression, macroDepth) => {
      if (!expression || macroDepth > 8) return null;
      const tokens = tokenize(expression);
      let pos = 0;
      const precedence = {
        '||': 1, '&&': 2, '|': 3, '^': 4, '&': 5, '==': 6, '!=': 6,
        '<': 7, '>': 7, '<=': 7, '>=': 7, '<<': 8, '>>': 8,
        '+': 9, '-': 9, '*': 10, '/': 10, '%': 10,
      };
      const isOp = (value) => { const t = tokens[pos]; return !!t && t.k === 'op' && t.v === value; };
      const eat = (value) => { if (isOp(value)) { pos++; return true; } return false; };

      const resolveMacro = (name) => {
        if (!macros || !macros.has(name) || macroDepth > 8) return null;
        const value = (macros.get(name) || '').trim();
        return value ? inferExpression(value, macroDepth + 1) : null;
      };

      const lookup = (name) => {
        const macro = resolveMacro(name);
        if (macro) return macro;
        if (vars.has(name)) return vars.get(name);
        if (builtinVars[name]) return builtinVars[name];
        if (name === 'true' || name === 'false') return { base: 'bool', vec: 1, dims: 0 };
        return null;
      };

      const inferCall = (name, args) => {
        const constructor = parsedType(name);
        if (constructor) return Object.assign({}, constructor, { dims: 0 });
        if (name === 'min' || name === 'max' || name === 'clamp') {
          let type = null;
          for (const arg of args) {
            type = type ? combine(type, arg) : arg;
            if (!type) return null;
          }
          return type;
        }
        if (name === 'mix') return args[0] ? Object.assign({}, args[0], { dims: 0 }) : null;
        if (name === 'step') return args[1] ? Object.assign({}, args[1], { dims: 0 }) : null;
        if (name === 'smoothstep') return args[2] ? Object.assign({}, args[2], { dims: 0 }) : null;
        if (floatMath.has(name)) {
          const arg = args[0];
          if (!arg) return null;
          return { base: arg.base === 'double' || args.some((a) => a && a.base === 'double') ? 'double' : 'float', vec: arg.vec, dims: 0 };
        }
        if (preserveType.has(name)) return args[0] ? Object.assign({}, args[0], { dims: 0 }) : null;
        if (name === 'length' || name === 'distance' || name === 'dot' || name === 'determinant') {
          return { base: args.some((a) => a && a.base === 'double') ? 'double' : 'float', vec: 1, dims: 0 };
        }
        if (name === 'cross') {
          const arg = args[0];
          return arg ? { base: arg.base === 'double' ? 'double' : 'float', vec: 3, dims: 0 } : null;
        }
        if (textureReturn.has(name)) return { base: 'float', vec: 4, dims: 0 };
        if (name === 'textureSize' || name === 'imageSize') return { base: 'int', vec: 2, dims: 0 };
        if (name === 'any' || name === 'all') return { base: 'bool', vec: 1, dims: 0 };
        if (name === 'not' || name === 'isnan' || name === 'isinf') {
          return { base: 'bool', vec: args[0] ? args[0].vec : 1, dims: 0 };
        }
        if (name === 'floatBitsToInt' || name === 'floatBitsToUint' ||
            name === 'intBitsToFloat' || name === 'uintBitsToFloat' ||
            name === 'bitCount' || name === 'findLSB' || name === 'findMSB' ||
            name === 'bitfieldReverse') {
          const arg = args[0];
          if (!arg) return null;
          const base = name === 'floatBitsToInt' || name === 'bitCount' || name === 'findLSB' ||
            name === 'findMSB' || name === 'bitfieldReverse' ? 'int'
            : name === 'floatBitsToUint' ? 'uint' : 'float';
          return { base, vec: name === 'findLSB' || name === 'findMSB' || name === 'bitCount' ? arg.vec : arg.vec, dims: 0 };
        }
        if (funcs.has(name)) return Object.assign({}, funcs.get(name), { dims: 0 });
        const macro = resolveMacro(name);
        return macro;
      };

      const parsePrimary = () => {
        const token = tokens[pos];
        if (!token) return null;
        if (token.k === 'num') { pos++; return literalType(token.v); }
        if (token.k === 'id') {
          pos++;
          if (eat('(')) {
            const args = [];
            if (!eat(')')) {
              while (true) {
                args.push(parseConditional());
                if (eat(',')) continue;
                if (eat(')')) break;
                return null;
              }
            }
            return inferCall(token.v, args);
          }
          return lookup(token.v);
        }
        if (eat('(')) {
          const inner = parseConditional();
          return eat(')') ? inner : null;
        }
        pos++;
        return null;
      };

      const parsePostfix = () => {
        let type = parsePrimary();
        while (type !== null && pos < tokens.length) {
          if (eat('[')) {
            const index = parseConditional();
            if (!eat(']')) return null;
            type = indexType(type, index);
          } else if (eat('.')) {
            const token = tokens[pos];
            if (!token || token.k !== 'id') return null;
            pos++;
            type = swizzleType(type, token.v);
          } else {
            break;
          }
        }
        return type;
      };

      const applyUnary = (op, type) => {
        if (op === '!' || op === 'not') return { base: 'bool', vec: 1, dims: 0 };
        if (op === '~') return type && (type.base === 'int' || type.base === 'uint') ? type : null;
        if (op === '+' || op === '-' || op === '++' || op === '--') return type;
        return null;
      };

      const parseUnary = () => {
        const token = tokens[pos];
        if (token && token.k === 'op' && ['+', '-', '!', '~', '++', '--'].includes(token.v)) {
          pos++;
          return applyUnary(token.v, parseUnary());
        }
        return parsePostfix();
      };

      const applyBinary = (op, left, right) => {
        if (['<', '>', '<=', '>=', '==', '!=', '&&', '||'].includes(op)) {
          return { base: 'bool', vec: 1, dims: 0 };
        }
        if (op === '<<' || op === '>>') {
          return left && (left.base === 'int' || left.base === 'uint') ? left : null;
        }
        if (['+', '-', '*', '/', '%', '&', '|', '^'].includes(op)) return combine(left, right);
        return null;
      };

      const parseBinary = (minPrecedence) => {
        let left = parseUnary();
        while (true) {
          const token = tokens[pos];
          if (!token || token.k !== 'op') break;
          const level = precedence[token.v];
          if (level === undefined || level < minPrecedence) break;
          pos++;
          left = applyBinary(token.v, left, parseBinary(level + 1));
        }
        return left;
      };

      const parseConditional = () => {
        const condition = parseBinary(1);
        if (!eat('?')) return condition;
        const whenTrue = parseConditional();
        if (!eat(':')) return null;
        const whenFalse = parseConditional();
        return combine(whenTrue, whenFalse) || whenTrue || whenFalse;
      };

      const result = parseConditional();
      return pos === tokens.length ? result : null;
    };

    const findMatching = (text, open) => {
      let depth = 0;
      for (let i = open; i < text.length; i++) {
        if (text[i] === '(') depth++;
        else if (text[i] === ')' && --depth === 0) return i + 1;
      }
      return -1;
    };

    const splitArguments = (body) => {
      const args = [];
      let start = 0, depth = 0;
      for (let i = 0; i < body.length; i++) {
        if (body[i] === '(') depth++;
        else if (body[i] === ')') depth--;
        else if (body[i] === ',' && depth === 0) {
          args.push(body.slice(start, i));
          start = i + 1;
        }
      }
      args.push(body.slice(start));
      return args;
    };

    const castArgument = (arg, type, first) => {
      const trimmed = arg.trim();
      const label = typeLabel(type);
      if (!label) return trimmed;
      const existing = new RegExp('^(' + GLSL_TYPE_PATTERN + ')\\s*\\(').exec(trimmed);
      if (existing && existing[1] === label) return trimmed;
      if (first && sameType(inferExpression(trimmed, 0), type)) return trimmed;
      return label + '(' + trimmed + ')';
    };

    const transform = (text) => {
      let out = '', cursor = 0;
      const re = /\bclamp\s*\(/g;
      let match;
      while ((match = re.exec(text))) {
        const open = text.indexOf('(', match.index);
        const end = findMatching(text, open);
        if (end < 0) break;
        const args = splitArguments(text.slice(open + 1, end - 1)).map((arg) => transform(arg).trim());
        if (args.length === 3) {
          const type = inferExpression(args[0].trim(), 0);
          const label = typeLabel(type);
          out += text.slice(cursor, match.index);
          out += label ? 'clamp(' + args.map((arg, index) => castArgument(arg, type, index === 0)).join(', ') + ')' : 'clamp(' + args.join(', ') + ')';
        } else {
          out += text.slice(cursor, end);
        }
        cursor = end;
        re.lastIndex = end;
      }
      return out + text.slice(cursor);
    };

    return transform(source);
  }

  // =================================================================== shaderc -> glslang
  /**
   * Source preprocessing that shaderc does natively but the glslang.js build does not expose:
   * include resolution (through Mojang's include callback), macro definitions and auto-bound uniforms.
   */
  function preprocess(opts, source, fileName) {
    const h = H();
    let depth = 0;
    function expand(src, requester) {
      return src.replace(/^[ \t]*#[ \t]*include[ \t]*([<"])([^>"]+)[>"].*$/gm, (line, kind, req) => {
        if (!opts.resolver) throw new Error('#include without include callbacks: ' + req);
        if (++depth > 64) throw new Error('#include nesting too deep at ' + req);
        const reqPtr = h.newCString(req), fromPtr = h.newCString(requester);
        const res = N.callback(opts.resolver, 'p', ['p', 'p', 'i', 'p', 'p'], [opts.userData, reqPtr, kind === '<' ? 1 : 0, fromPtr, depth]);
        h.free(reqPtr); h.free(fromPtr);
        const o = L.ShadercIncludeResult;
        const nameLen = h.getPtr(res + o.SOURCE_NAME_LENGTH);
        const contentPtr = h.getPtr(res + o.CONTENT), contentLen = h.getPtr(res + o.CONTENT_LENGTH);
        const content = h.utf8.decode(h.u8.subarray(contentPtr, contentPtr + contentLen));
        const resolvedName = nameLen ? h.utf8.decode(h.u8.subarray(h.getPtr(res + o.SOURCE_NAME), h.getPtr(res + o.SOURCE_NAME) + nameLen)) : '';
        if (opts.releaser) N.callback(opts.releaser, 'v', ['p', 'p'], [opts.userData, res]);
        if (!nameLen) throw new Error('Cannot find or open include file ' + req + ': ' + content);
        const r = '\n' + expand(content, resolvedName) + '\n';
        depth--;
        return r;
      });
    }
    let src = expand(source, fileName);
    // glslang can ambiguously resolve integer clamp overloads when one or both
    // bounds are macros or integer expressions. Parse call arguments with balanced
    // parentheses (rather than a shallow regex, which misses nested expressions),
    // then make the bounds explicitly int whenever the first argument is int(...).
    src = normalizeClampCalls(src, opts.macros);
    src = src.replace(/^[ \t]*#[ \t]*extension[ \t]+GL_GOOGLE_include_directive.*$/gm, '');
    // WebGPU rasterizes points at exactly 1px and Tint rejects other gl_PointSize values.
    src = src.replace(/\bgl_PointSize\s*=[^;]*;/g, '');

    // auto-bound uniforms (shaderc_compile_options_set_auto_bind_uniforms)
    let binding = 0;
    const qual = (layout) => {
      if (layout && /\bbinding\s*=/.test(layout)) return null;
      return (layout ? layout.replace(/\)\s*$/, ', ') : 'layout(') + 'set = 0, binding = ' + (binding++) + ') ';
    };
    src = src.replace(/(layout\s*\([^)]*\)\s*)?(uniform\s+\w+\s*\{)/g, (all, layout, rest) => {
      const q = qual(layout);
      return q === null ? all : q + rest;
    });
    src = src.replace(/(layout\s*\([^)]*\)\s*)?(uniform\s+(?:(?:highp|mediump|lowp)\s+)?[iu]?(?:sampler|image|texture)\w*\s+\w+\s*(?:\[[^\]]*\])?\s*;)/g, (all, layout, rest) => {
      const q = qual(layout);
      return q === null ? all : q + rest;
    });

    // macros and the extension needed for layout(binding) before GLSL 4.20, right after #version
    let header = '#extension GL_ARB_shading_language_420pack : enable\n';
    for (const [k, v] of opts.macros) header += '#define ' + k + (v ? ' ' + v : '') + '\n';
    const vm = /^[ \t]*#[ \t]*version[^\n]*\n/m.exec(src);
    if (vm) src = src.slice(0, vm.index + vm[0].length) + header + src.slice(vm.index + vm[0].length);
    else src = '#version 450\n' + header + src;
    return src;
  }

  function shaderErrorContext(source, error) {
    const lines = source.split('\n');
    const wanted = new Set();
    for (const match of String(error).matchAll(/ERROR:\s*\d+:(\d+):/g)) {
      const line = Number(match[1]) - 1;
      for (let i = Math.max(0, line - 2); i <= Math.min(lines.length - 1, line + 2); i++) wanted.add(i);
    }
    if (!wanted.size) return '';
    return '\n--- transformed shader context ---\n' +
      [...wanted].sort((a, b) => a - b).map((i) => String(i + 1).padStart(5) + ': ' + lines[i]).join('\n');
  }

  function compileGLSL(source, kind) {
    const stage = kind === 1 ? 'fragment' : kind === 2 ? 'compute' : 'vertex';
    const msgs = S.capture = [];
    try {
      return { words: S.glslang.compileGLSL(source, stage, false, '1.3'), error: null };
    } catch (e) {
      return { words: null, error: (msgs.filter((x) => x.trim() && !/^Parse failed|compilation terminated|No code generated/.test(x)).join('\n') || String(e)) };
    } finally {
      S.capture = null;
    }
  }

  N.defineAll({
    shaderc_compiler_initialize: () => handle({ kind: 'compiler' }),
    shaderc_compiler_release: () => {},
    shaderc_compile_options_initialize: () => handle({ macros: new Map(), resolver: 0, releaser: 0, userData: 0 }),
    shaderc_compile_options_clone: (o) => { const s = S.handles[o]; return handle({ ...s, macros: new Map(s.macros) }); },
    shaderc_compile_options_release: (o) => { S.handles[o] = null; },
    shaderc_compile_options_set_target_env: () => {},
    shaderc_compile_options_set_target_spirv: () => {},
    shaderc_compile_options_set_auto_bind_uniforms: () => {},
    shaderc_compile_options_set_preserve_bindings: () => {},
    shaderc_compile_options_set_generate_debug_info: () => {},
    shaderc_compile_options_set_optimization_level: () => {},
    shaderc_compile_options_set_source_language: () => {},
    shaderc_compile_options_add_macro_definition: (o, name, nameLen, value, valueLen) => {
      const h = H();
      const k = h.utf8.decode(h.u8.subarray(name, name + nameLen));
      const v = value ? h.utf8.decode(h.u8.subarray(value, value + valueLen)) : '';
      S.handles[o].macros.set(k, v);
    },
    shaderc_compile_options_set_include_callbacks: (o, resolver, releaser, userData) => {
      Object.assign(S.handles[o], { resolver, releaser, userData });
    },
    shaderc_compile_into_spv: (compiler, src, srcLen, kind, fileName, entry, o) => {
      const h = H();
      const opts = S.handles[o] || { macros: new Map() };
      const name = h.cstr(fileName) || 'shader';
      let result;
      try {
        const text = preprocess(opts, h.utf8.decode(h.u8.subarray(src, src + srcLen)), name);
        const r = compileGLSL(text, kind);
        if (r.words) {
          result = { status: 0, words: r.words, source: text };
        } else {
          result = { status: 3, error: name + ': ' + r.error + shaderErrorContext(text, r.error), source: text };
          S.failedShaders.push({ name, source: text });
          if (S.failedShaders.length > 8) S.failedShaders.shift();
        }
      } catch (e) {
        result = { status: 3, error: name + ': ' + (e && e.message || e) };
      }
      if (result.words) {
        result.ptr = h.malloc(result.words.byteLength, 16);
        h.u8.set(new Uint8Array(result.words.buffer, result.words.byteOffset, result.words.byteLength), result.ptr);
        // the WebGL2 backend compiles the GLSL source itself (GLSL ES 3.00); find it again from the SPIR-V
        S.glslBySpv.set(S.spvKey(result.words), { source: result.source, kind });
      }
      result.errPtr = h.newCString(result.error || '');
      return handle(result);
    },
    shaderc_result_get_compilation_status: (r) => S.handles[r].status,
    shaderc_result_get_error_message: (r) => S.handles[r].errPtr,
    shaderc_result_get_num_errors: (r) => S.handles[r].status ? 1 : 0,
    shaderc_result_get_num_warnings: () => 0,
    shaderc_result_get_bytes: (r) => S.handles[r].ptr || 0,
    shaderc_result_get_length: (r) => S.handles[r].words ? S.handles[r].words.byteLength : 0,
    shaderc_result_release: (r) => {
      const res = S.handles[r];
      if (!res) return;
      if (res.ptr) H().free(res.ptr);
      H().free(res.errPtr);
      S.handles[r] = null;
    },
  });

  // =================================================================== spvc (reflection)
  N.defineAll({
    spvc_context_create: (out) => { H().setPtr(out, handle({ kind: 'context', allocs: [] })); return 0; },
    spvc_context_destroy: (ctx) => {
      const c = S.handles[ctx];
      if (c) for (const p of c.allocs) H().free(p);
      S.handles[ctx] = null;
    },
    spvc_context_set_error_callback: () => {},
    spvc_context_get_last_error_string: () => N.address('spvc_no_error'),
    spvc_context_parse_spirv: (ctx, spirv, count, out) => {
      try {
        const words = new Uint32Array(H().buffer.slice(spirv, spirv + count * 4));
        H().setPtr(out, handle({ kind: 'ir', ctx, module: parse(words) }));
        return 0;
      } catch (e) {
        console.error('spvc_context_parse_spirv', e);
        return -1;
      }
    },
    spvc_context_create_compiler: (ctx, backend, ir, mode, out) => {
      H().setPtr(out, handle({ kind: 'compiler', ctx, m: S.handles[ir].module, typeHandles: new Map() }));
      return 0;
    },
    spvc_compiler_create_shader_resources: (comp, out) => {
      const c = S.handles[comp];
      H().setPtr(out, handle({ kind: 'resources', comp, classes: classify(c.m), lists: {} }));
      return 0;
    },
    spvc_resources_get_resource_list_for_type: (res, type, listOut, countOut) => {
      const r = S.handles[res], c = S.handles[r.comp], ctx = S.handles[c.ctx], h = H(), o = L.SpvcReflectedResource;
      let p = r.lists[type];
      const vars = r.classes[type] || [];
      if (p === undefined) {
        // LWJGL's SpvcReflectedResource.create(pointer, count) constructs a Pointer that
        // rejects address 0 when Checks are enabled, even for an empty list. SPIRV-Cross
        // callers only dereference entries below count, so hand back a valid dummy block.
        p = h.calloc(o.SIZEOF * Math.max(1, vars.length));
        ctx.allocs.push(p);
        vars.forEach((v, i) => {
          const e = p + i * o.SIZEOF;
          const ptrType = c.m.types[v.type];
          h.dv.setUint32(e + o.ID, v.id, true);
          h.dv.setUint32(e + o.BASE_TYPE_ID, stripArrays(c.m, ptrType.pointee), true);
          h.dv.setUint32(e + o.TYPE_ID, v.type, true);
          const name = h.newCString(resourceName(c.m, v));
          ctx.allocs.push(name);
          h.setPtr(e + o.NAME, name);
        });
        r.lists[type] = p;
      }
      h.setPtr(listOut, p);
      h.setPtr(countOut, vars.length);
      return 0;
    },
    spvc_compiler_get_decoration: (comp, id, decoration) => {
      const d = S.handles[comp].m.decor[id];
      return d && d[decoration] ? d[decoration].value : 0;
    },
    spvc_compiler_has_decoration: (comp, id, decoration) => {
      const d = S.handles[comp].m.decor[id];
      return !!(d && d[decoration]);
    },
    spvc_compiler_get_binary_offset_for_decoration: (comp, id, decoration, out) => {
      const d = S.handles[comp].m.decor[id];
      if (!d || !d[decoration] || d[decoration].offset < 0) return false;
      H().dv.setUint32(out, d[decoration].offset, true);
      return true;
    },
    spvc_compiler_get_name: (comp, id) => {
      const c = S.handles[comp], ctx = S.handles[c.ctx];
      const p = H().newCString(c.m.names[id] || '');
      ctx.allocs.push(p);
      return p;
    },
    spvc_compiler_set_name: (comp, id, name) => { S.handles[comp].m.names[id] = H().cstr(name); },
    spvc_compiler_get_type_handle: (comp, typeId) => {
      const c = S.handles[comp];
      let h = c.typeHandles.get(typeId);
      if (!h) {
        // SPIRV-Cross type handles of variables describe the pointee (pointers are transparent)
        let id = typeId, t = c.m.types[id];
        while (t && t.kind === 'pointer') { id = t.pointee; t = c.m.types[id]; }
        h = handle({ kind: 'type', m: c.m, id });
        c.typeHandles.set(typeId, h);
      }
      return h;
    },
    spvc_compiler_get_declared_struct_size: (comp, type, out) => {
      const t = S.handles[type];
      H().setPtr(out, typeSize(t.m, stripArrays(t.m, t.id)));
      return 0;
    },
    spvc_type_get_basetype: (type) => {
      const t = S.handles[type], B = L.Spvc;
      let id = stripArrays(t.m, t.id), ty = t.m.types[id];
      while (ty && (ty.kind === 'vector' || ty.kind === 'matrix')) { id = ty.kind === 'vector' ? ty.elem : ty.column; ty = t.m.types[id]; }
      switch (ty && ty.kind) {
        case 'void': return B.SPVC_BASETYPE_VOID;
        case 'bool': return B.SPVC_BASETYPE_BOOLEAN;
        case 'int': return ty.width === 32 ? (ty.signed ? B.SPVC_BASETYPE_INT32 : B.SPVC_BASETYPE_UINT32)
          : ty.width === 16 ? (ty.signed ? B.SPVC_BASETYPE_INT16 : B.SPVC_BASETYPE_UINT16)
            : ty.width === 64 ? (ty.signed ? B.SPVC_BASETYPE_INT64 : B.SPVC_BASETYPE_UINT64)
              : (ty.signed ? B.SPVC_BASETYPE_INT8 : B.SPVC_BASETYPE_UINT8);
        case 'float': return ty.width === 64 ? B.SPVC_BASETYPE_FP64 : ty.width === 16 ? B.SPVC_BASETYPE_FP16 : B.SPVC_BASETYPE_FP32;
        case 'struct': return B.SPVC_BASETYPE_STRUCT;
        case 'image': return B.SPVC_BASETYPE_IMAGE;
        case 'sampledImage': return B.SPVC_BASETYPE_SAMPLED_IMAGE;
        case 'sampler': return B.SPVC_BASETYPE_SAMPLER;
        default: return B.SPVC_BASETYPE_UNKNOWN;
      }
    },
    spvc_type_get_vector_size: (type) => {
      const t = S.handles[type];
      let ty = t.m.types[stripArrays(t.m, t.id)];
      if (ty.kind === 'matrix') ty = t.m.types[ty.column];
      return ty.kind === 'vector' ? ty.count : 1;
    },
    spvc_type_get_columns: (type) => {
      const t = S.handles[type], ty = t.m.types[stripArrays(t.m, t.id)];
      return ty.kind === 'matrix' ? ty.count : 1;
    },
    spvc_type_get_num_array_dimensions: (type) => {
      const t = S.handles[type];
      let n = 0, ty = t.m.types[t.id];
      while (ty && ty.kind === 'array') { n++; ty = t.m.types[ty.elem]; }
      return n;
    },
    spvc_type_get_array_dimension: (type, dim) => {
      const t = S.handles[type];
      let ty = t.m.types[t.id];
      for (let i = 0; i < dim; i++) ty = t.m.types[ty.elem];
      return ty.lengthId ? t.m.consts[ty.lengthId] : 0;
    },
    spvc_type_get_image_dimension: (type) => {
      const t = S.handles[type];
      let ty = t.m.types[stripArrays(t.m, t.id)];
      if (ty.kind === 'sampledImage') ty = t.m.types[ty.image];
      return ty.dim;
    },
  });
  N.define('spvc_no_error', () => 0);

  // =================================================================== SPIR-V -> WGSL (WebGPU backend)
  /**
   * Splits every combined image-sampler (OpTypeSampledImage) into an image and a sampler, which is what WGSL
   * requires. Texture keeps the original variable id, set and binding; the new sampler goes to
   * @group(samplerGroup) @binding(same binding). Function parameters of combined type get a companion sampler
   * parameter; loads become OpLoad image + OpLoad sampler + OpSampledImage.
   */
  function splitCombinedSamplers(words, samplerGroup) {
    const m = parse(words);
    let bound = words[3];
    const fresh = () => bound++;
    const insts = [];
    for (let i = 5; i < words.length;) {
      const wc = words[i] >>> 16;
      insts.push(Array.from(words.subarray(i, i + wc)));
      i += wc;
    }
    const opOf = (ins) => ins[0] & 0xffff;
    const mk = (op, args) => [((args.length + 1) << 16) | op, ...args];

    // combined pointer types: ptrId -> {imageType, sampledImageType, storage}
    const combinedPtr = new Map();
    for (const id in m.types) {
      const t = m.types[id];
      if (t.kind === 'pointer' && m.types[t.pointee] && m.types[t.pointee].kind === 'sampledImage') {
        combinedPtr.set(+id, { si: t.pointee, image: m.types[t.pointee].image, storage: t.storage });
      }
    }
    if (!combinedPtr.size) return { words, samplers: [] };

    let samplerType = 0;
    for (const id in m.types) if (m.types[id].kind === 'sampler') samplerType = +id;
    const newTypes = [];
    if (!samplerType) { samplerType = fresh(); newTypes.push(mk(Op.TypeSampler, [samplerType])); }
    const ptrImage = new Map(), ptrSampler = new Map();
    for (const [ptr, info] of combinedPtr) {
      // the original pointer id becomes "pointer to image"; a new pointer type is made for the sampler
      ptrImage.set(ptr, info.image);
      const ps = fresh();
      ptrSampler.set(ptr, ps);
      newTypes.push(mk(Op.TypePointer, [ps, info.storage, samplerType]));
    }

    // values whose type is a combined pointer: global variables and function parameters
    const companion = new Map(); // combined pointer value id -> sampler pointer value id
    const samplers = [];
    const newGlobals = [], newDecor = [], newNames = [];
    for (const v of m.vars) {
      if (!combinedPtr.has(v.type)) continue;
      const s = fresh();
      companion.set(v.id, s);
      newGlobals.push(mk(Op.Variable, [ptrSampler.get(v.type), s, v.storage]));
      const d = m.decor[v.id] || {};
      const binding = d[Dec.Binding] ? d[Dec.Binding].value : 0;
      newDecor.push(mk(Op.Decorate, [s, Dec.DescriptorSet, samplerGroup]));
      newDecor.push(mk(Op.Decorate, [s, Dec.Binding, binding]));
      const name = m.names[v.id] || ('sampler' + v.id);
      samplers.push({ name, binding, set: d[Dec.DescriptorSet] ? d[Dec.DescriptorSet].value : 0 });
    }

    // function types with combined parameters get an extra sampler parameter after each one
    const fnTypeRemap = new Map();
    const extraTypes = [];
    for (const ins of insts) {
      if (opOf(ins) !== Op.TypeFunction) continue;
      const params = ins.slice(3);
      if (!params.some((p) => combinedPtr.has(p))) continue;
      const id = fresh();
      const np = [];
      for (const p of params) { np.push(p); if (combinedPtr.has(p)) np.push(ptrSampler.get(p)); }
      extraTypes.push(mk(Op.TypeFunction, [id, ins[2], ...np]));
      fnTypeRemap.set(ins[1], id);
    }

    // rewrite instructions
    const out = [];
    let typesInserted = false;
    for (const ins of insts) {
      const op = opOf(ins);
      if (!typesInserted && (op === Op.Function)) {
        out.push(...newGlobals);
        typesInserted = true;
      }
      if (op === Op.TypePointer && combinedPtr.has(ins[1])) {
        out.push(mk(Op.TypePointer, [ins[1], ins[2], combinedPtr.get(ins[1]).image]));
        continue;
      }
      if (op === Op.Function) {
        if (fnTypeRemap.has(ins[4])) ins[4] = fnTypeRemap.get(ins[4]);
        out.push(ins);
        continue;
      }
      if (op === Op.FunctionParameter && combinedPtr.has(ins[1])) {
        const s = fresh();
        companion.set(ins[2], s);
        out.push(ins);
        out.push(mk(Op.FunctionParameter, [ptrSampler.get(ins[1]), s]));
        continue;
      }
      if (op === Op.FunctionCall) {
        const args = ins.slice(4), na = [];
        let changed = false;
        for (const a of args) { na.push(a); if (companion.has(a)) { na.push(companion.get(a)); changed = true; } }
        out.push(changed ? mk(Op.FunctionCall, [ins[1], ins[2], ins[3], ...na]) : ins);
        continue;
      }
      if (op === Op.Load && companion.has(ins[3])) {
        const resultType = ins[1], result = ins[2], ptr = ins[3];
        const si = m.types[resultType];
        const img = fresh(), smp = fresh();
        out.push(mk(Op.Load, [si.image, img, ptr]));
        out.push(mk(Op.Load, [samplerType, smp, companion.get(ptr)]));
        out.push(mk(Op.SampledImage, [resultType, result, img, smp]));
        continue;
      }
      out.push(ins);
    }

    // place new types/decorations: decorations after the last OpDecorate, types after the last type declaration
    let lastDecor = -1, lastType = -1;
    out.forEach((ins, i) => {
      const op = opOf(ins);
      if (op === Op.Decorate || op === Op.MemberDecorate) lastDecor = i;
      if ((op >= Op.TypeVoid && op <= 39) || (op >= 41 && op <= 52)) lastType = i;
    });
    // types must precede uses: insert new sampler/pointer/function types right after the last existing type
    // (function types with sampler params refer to the new pointer types, so they go after them).
    const typeBlock = [...newTypes, ...extraTypes];
    out.splice(lastType + 1, 0, ...typeBlock);
    if (lastDecor >= 0) out.splice(lastDecor + 1, 0, ...newDecor);
    // the new global variables were emitted before the first function; if there was none, append
    if (!typesInserted) out.push(...newGlobals);

    const flat = [words[0], words[1], words[2], bound, words[4]];
    for (const ins of out) for (const w of ins) flat.push(w >>> 0);
    return { words: new Uint32Array(flat), samplers };
  }

  /**
   * WGSL has no buffer textures (SPIR-V OpTypeImage Dim=Buffer). The vanilla cloud shader is the only user:
   * `uniform isamplerBuffer CloudFaces` is bound by Java as a TEXEL_BUFFER (one signed byte per element).
   * Lower the sampled buffer to a read-only storage buffer of i32 words and turn every texelFetch(...).r
   * into a byte extraction, so Tint can translate the module instead of failing with "invalid dimension: 5".
   */
  function lowerTexelBuffers(words) {
    const m = parse(words);
    const bufVars = [];
    for (const v of m.vars) {
      if (v.storage !== SC.UniformConstant) continue;
      const ptr = m.types[v.type];
      if (!ptr || ptr.kind !== 'pointer') continue;
      const pointee = m.types[ptr.pointee];
      const image = pointee && pointee.kind === 'sampledImage' ? m.types[pointee.image] : pointee;
      if (image && image.kind === 'image' && image.dim === 5) bufVars.push({ v, image });
    }
    if (!bufVars.length) return words;

    const insts = [];
    let bound = words[3];
    for (let i = 5; i < words.length;) {
      const wc = words[i] >>> 16;
      insts.push(Array.from(words.subarray(i, i + wc)));
      i += wc;
    }
    const fresh = () => bound++;
    const mk = (op, args) => [((args.length + 1) << 16) | op, ...args];
    const opOf = (ins) => ins[0] & 0xffff;

    let intType = 0, ptrInt = 0, zeroId = 0, twoId = 0, threeId = 0, mask255Id = 0, shift24Id = 0;
    for (const id in m.types) {
      const t = m.types[id];
      if (t.kind === 'int' && t.width === 32 && t.signed) intType = +id;
    }
    const newTypes = [], newDecor = [];
    if (!intType) { intType = fresh(); newTypes.push(mk(Op.TypeInt, [intType, 32, 1])); }
    const consts = new Map();
    for (const ins of insts) {
      if (opOf(ins) === Op.Constant && ins[1] === intType) consts.set(ins[3] >>> 0, ins[2]);
    }
    const constInt = (value) => {
      const key = value >>> 0;
      if (consts.has(key)) return consts.get(key);
      const id = fresh();
      newTypes.push(mk(Op.Constant, [intType, id, key]));
      consts.set(key, id);
      return id;
    };
    zeroId = constInt(0); twoId = constInt(2); threeId = constInt(3); mask255Id = constInt(255); shift24Id = constInt(24);
    ptrInt = fresh();
    newTypes.push(mk(Op.TypePointer, [ptrInt, SC.StorageBuffer, intType]));

    // One block struct with a runtime i32 array per buffer texture.
    const varInfo = new Map();
    for (const { v } of bufVars) {
      const ra = fresh(), block = fresh(), ptrBlock = fresh();
      newTypes.push(mk(Op.TypeRuntimeArray, [ra, intType]));
      newTypes.push(mk(Op.TypeStruct, [block, ra]));
      newTypes.push(mk(Op.TypePointer, [ptrBlock, SC.StorageBuffer, block]));
      newDecor.push(mk(Op.Decorate, [ra, Dec.ArrayStride, 4]));
      newDecor.push(mk(Op.Decorate, [block, Dec.Block]));
      newDecor.push(mk(Op.MemberDecorate, [block, 0, Dec.Offset, 0]));
      newDecor.push(mk(Op.MemberDecorate, [block, 0, Dec.NonWritable]));
      varInfo.set(v.id, { ptrBlock });
    }

    // Rewrite every fetch: load sampled image -> image -> OpImageFetch -> extract component 0.
    const loadInfo = new Map(), imageBase = new Map(), fetchValue = new Map(), remap = new Map();
    const out = [];
    let inFunction = false;
    for (const ins of insts) {
      const op = opOf(ins);
      if (op === Op.Function) inFunction = true;
      if (op === Op.FunctionEnd) inFunction = false;
      if (inFunction && op === Op.Load && varInfo.has(ins[3])) {
        loadInfo.set(ins[2], { ptrBlock: varInfo.get(ins[3]).ptrBlock });
        out.push(mk(Op.CopyObject, [varInfo.get(ins[3]).ptrBlock, ins[2], ins[3]]));
        continue;
      }
      if (inFunction && op === Op.Image && loadInfo.has(ins[3])) {
        imageBase.set(ins[2], ins[3]);
        continue;
      }
      const mapped = ins.map((w, i) => (inFunction && i > 0 && remap.has(w)) ? remap.get(w) : w);
      if (inFunction && op === Op.ImageFetch && imageBase.has(mapped[3])) {
        const base = imageBase.get(mapped[3]), coord = mapped[4];
        const wordIndex = fresh(), ptrWord = fresh(), word = fresh();
        const low = fresh(), shift = fresh(), shifted = fresh(), value = fresh(), wide = fresh();
        out.push(mk(Op.ShiftRightLogical, [intType, wordIndex, coord, twoId]));
        out.push(mk(Op.AccessChain, [ptrInt, ptrWord, base, zeroId, wordIndex]));
        out.push(mk(Op.Load, [intType, word, ptrWord]));
        out.push(mk(Op.BitwiseAnd, [intType, low, coord, threeId]));
        out.push(mk(Op.ShiftLeftLogical, [intType, shift, low, threeId]));
        out.push(mk(Op.ShiftRightLogical, [intType, shifted, word, shift]));
        out.push(mk(Op.BitwiseAnd, [intType, value, shifted, mask255Id]));
        // GLSL `isamplerBuffer` returns a *signed* byte. The vanilla cloud
        // mesh stores negative cell coordinates (cellX >> 1 cast to byte), so
        // masking without sign-extension turned -1 into 255 and shifted whole
        // cloud cells thousands of blocks away. Recover the sign by shifting
        // the low byte up and back down arithmetically.
        out.push(mk(Op.ShiftLeftLogical, [intType, wide, value, shift24Id]));
        out.push(mk(Op.ShiftRightArithmetic, [intType, mapped[2], wide, shift24Id]));
        fetchValue.set(mapped[2], true);
        continue;
      }
      if (inFunction && op === Op.CompositeExtract && fetchValue.has(mapped[3]) && mapped[4] === 0) {
        remap.set(ins[2], mapped[3]);
        continue;
      }
      out.push(mapped);
    }

    // Types must precede the first global variable; decorations go right after the last existing decoration.
    let firstVar = out.findIndex((ins) => opOf(ins) === Op.Variable);
    if (firstVar < 0) firstVar = out.findIndex((ins) => opOf(ins) === Op.Function);
    if (firstVar < 0) firstVar = out.length;
    let lastDecor = -1;
    out.forEach((ins, i) => {
      const op = opOf(ins);
      if (op === Op.Decorate || op === Op.MemberDecorate) lastDecor = i;
    });
    if (lastDecor >= 0) out.splice(lastDecor + 1, 0, ...newDecor);
    else newDecor.length = 0;
    out.splice(firstVar + newDecor.length, 0, ...newTypes);

    for (const ins of out) {
      if (opOf(ins) === Op.Variable && varInfo.has(ins[2])) {
        ins[1] = varInfo.get(ins[2]).ptrBlock;
        ins[3] = SC.StorageBuffer;
      }
    }

    const flat = [words[0], words[1], words[2], bound, words[4]];
    for (const ins of out) for (const w of ins) flat.push(w >>> 0);
    return new Uint32Array(flat);
  }

  /** SPIR-V words -> { code: WGSL, samplers: [{name, binding, set}] } for the WebGPU backend. */
  S.toWGSL = function (words, samplerGroup) {
    const split = splitCombinedSamplers(lowerTexelBuffers(words), samplerGroup);
    const msgs = S.capture = [];
    let code;
    try {
      code = S.twgsl.convertSpirV2WGSL(split.words);
    } finally {
      S.capture = null;
    }
    const errors = msgs.filter((x) => x.trim());
    if (errors.length) throw new Error('SPIR-V -> WGSL: ' + errors.join('; '));
    return { code, samplers: split.samplers };
  };
  S.compileGLSL = compileGLSL;
  /** Hash of a SPIR-V module ignoring decorations (renderpearl relocates bindings/locations in place). */
  S.glslBySpv = new Map();
  S.spvKey = function (words) {
    let h1 = 0x811c9dc5, h2 = words.length;
    for (let i = 5; i < words.length;) {
      const wc = words[i] >>> 16, op = words[i] & 0xffff;
      if (!wc) break;
      if (op !== Op.Decorate && op !== Op.MemberDecorate) {
        for (let j = i; j < i + wc; j++) {
          h1 = Math.imul(h1 ^ words[j], 0x01000193);
          h2 = Math.imul(h2 + words[j], 0x9e3779b1) ^ (h2 >>> 15);
        }
      }
      i += wc;
    }
    return (h1 >>> 0).toString(16) + ':' + (h2 >>> 0).toString(16);
  };
  S.parse = parse;
  S.classify = classify;
  S.normalizeClampCalls = normalizeClampCalls;
  S.preprocess = preprocess;
})();
