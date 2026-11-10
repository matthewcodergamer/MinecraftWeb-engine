// WebGPU side of the renderpearl WebGPU backend (Java: mcport.gpu.webgpu.*).
// Java holds integer handles into G.obj; every renderpearl backend operation maps to one function here.
//
// Conventions (match Mojang's OpenGL/Vulkan backends so render targets have the same memory layout):
//  * vertex shaders negate gl_Position.y and pipelines use frontFace 'cw' (as the Vulkan backend does);
//    the surface blit flips back, so texture rows, gl_FragCoord, scissor rectangles and readbacks agree with GL.
//  * depth is zero-to-one (RENDERPEARL_DEPTH_IS_ZERO_TO_ONE); renderpearl uses reversed-Z compare ops.
//  * uniform i of a pipeline is @group(0) @binding(i); the sampler split off a combined sampler i is
//    @group(1) @binding(i) (see web/native/shaders.js).
'use strict';
(function () {
  const H = () => globalThis.__mcHeap;
  const GU = globalThis.GPUBufferUsage, TU = globalThis.GPUTextureUsage, SS = globalThis.GPUShaderStage;

  const G = {
    adapter: null, device: null, queue: null, context: null, canvasFormat: null,
    obj: [null], free: [],
    encoder: null,               // current GPUCommandEncoder (lazily created)
    pass: null,                  // current GPURenderPassEncoder
    passState: null,
    transient: [],               // [{buf, shadow, size, used}] uploaded at submit
    pendingReadbacks: [],        // completed async readbacks to report to Java: callback ids
    readbacksInFlight: 0,
    frame: 0,
    errors: [],
    stats: { draws: 0, passes: 0, submits: 0, bindGroups: 0, pipelines: 0 },
  };
  globalThis.__mcWebGPU = G;

  function put(o) {
    if (G.free.length) { const i = G.free.pop(); G.obj[i] = o; return i; }
    G.obj.push(o);
    return G.obj.length - 1;
  }
  function release(i) {
    if (i > 0 && G.obj[i]) { G.obj[i] = null; G.free.push(i); }
  }

  /** Rejects if `promise` does not settle in `ms`; the timer is always cleared. */
  function withTimeout(promise, ms, label) {
    let timer = 0;
    const guard = new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(label + ' timed out after ' + Math.round(ms / 1000) + 's')), ms);
    });
    return Promise.race([promise, guard]).finally(() => clearTimeout(timer));
  }

  /** Called by the host page before Java starts; resolves to false when WebGPU is unavailable. */
  G.init = async function () {
    if (!navigator.gpu) return false;
    const params = new URLSearchParams(location.search);
    if (params.get('gpu') === 'webgl2') return false;
    try {
      // Two WebGPU contexts on one page (the Rust body plus this Java brain) can
      // leave requestAdapter pending forever on some drivers. Never let that
      // stall the boot screen: hang up and fall back to WebGL2 instead. 30s is
      // the ceiling: hardware GPUs answer in well under a second, while
      // software rasterizers (SwiftShader) can take ~20s on a busy machine.
      G.adapter = await withTimeout(navigator.gpu.requestAdapter({ powerPreference: 'high-performance' }),
        30000, 'WebGPU adapter request');
      if (!G.adapter) return false;
      const want = ['depth32float-stencil8', 'float32-filterable', 'rg11b10ufloat-renderable', 'indirect-first-instance'];
      const requiredFeatures = want.filter((f) => G.adapter.features.has(f));
      const L = G.adapter.limits;
      G.device = await withTimeout(G.adapter.requestDevice({
        requiredFeatures,
        requiredLimits: {
          maxTextureDimension2D: L.maxTextureDimension2D,
          maxBufferSize: L.maxBufferSize,
          maxStorageBufferBindingSize: L.maxStorageBufferBindingSize,
          maxUniformBufferBindingSize: L.maxUniformBufferBindingSize,
          maxBindGroups: Math.min(L.maxBindGroups, 4),
          maxSampledTexturesPerShaderStage: L.maxSampledTexturesPerShaderStage,
          maxSamplersPerShaderStage: L.maxSamplersPerShaderStage,
          maxDynamicUniformBuffersPerPipelineLayout: L.maxDynamicUniformBuffersPerPipelineLayout,
          maxUniformBuffersPerShaderStage: L.maxUniformBuffersPerShaderStage,
          maxVertexAttributes: L.maxVertexAttributes,
          maxVertexBuffers: L.maxVertexBuffers,
          maxColorAttachments: L.maxColorAttachments,
        },
      }, 20000, 'WebGPU device request'));
      G.features = new Set(requiredFeatures);
      G.queue = G.device.queue;
      G.device.lost.then((info) => { G.lost = info; console.error('WebGPU device lost: ' + info.message); });
      G.device.addEventListener('uncapturederror', (e) => {
        if (G.errors.length < 50) G.errors.push(e.error.message);
        if (G.errors.length <= 20) console.error('WebGPU: ' + e.error.message);
      });
      const info = G.adapter.info || {};
      G.adapterName = [info.vendor, info.architecture, info.device, info.description].filter(Boolean).join(' ') || 'WebGPU adapter';
      G.vendor = info.vendor || 'unknown';
      G.isFallback = !!(G.adapter.isFallbackAdapter || (info && info.isFallbackAdapter));
      G.ready = true;
      G.api = 'WebGPU';
      globalThis.__mcGPU = G;
      return true;
    } catch (e) {
      console.warn('WebGPU unavailable: ' + e);
      return false;
    }
  };

  // ------------------------------------------------------------------ formats
  // GpuFormat ordinal order (com.mojang.renderpearl.api.GpuFormat)
  const FORMATS = ['R8_UNORM', 'R8_SNORM', 'RG8_UNORM', 'RG8_SNORM', 'RGB8_UNORM', 'RGB8_SNORM', 'RGBA8_UNORM', 'RGBA8_SNORM',
    'R16_UNORM', 'R16_SNORM', 'RG16_UNORM', 'RG16_SNORM', 'RGB16_UNORM', 'RGB16_SNORM', 'RGBA16_UNORM', 'RGBA16_SNORM',
    'R8_UINT', 'R8_SINT', 'RG8_UINT', 'RG8_SINT', 'RGB8_UINT', 'RGB8_SINT', 'RGBA8_UINT', 'RGBA8_SINT',
    'R16_UINT', 'R16_SINT', 'RG16_UINT', 'RG16_SINT', 'RGB16_UINT', 'RGB16_SINT', 'RGBA16_UINT', 'RGBA16_SINT',
    'R32_UINT', 'R32_SINT', 'RG32_UINT', 'RG32_SINT', 'RGB32_UINT', 'RGB32_SINT', 'RGBA32_UINT', 'RGBA32_SINT',
    'R16_FLOAT', 'RG16_FLOAT', 'RGB16_FLOAT', 'RGBA16_FLOAT', 'R32_FLOAT', 'RG32_FLOAT', 'RGB32_FLOAT', 'RGBA32_FLOAT',
    'RGB10A2_UNORM', 'RGB10A2_UINT', 'RG11B10_FLOAT', 'D32_FLOAT', 'D32_FLOAT_S8_UINT', 'D24_UNORM_S8_UINT', 'D16_UNORM', 'S8_UINT'];
  const TEX_FORMAT = {
    R8_UNORM: 'r8unorm', R8_SNORM: 'r8snorm', RG8_UNORM: 'rg8unorm', RG8_SNORM: 'rg8snorm', RGBA8_UNORM: 'rgba8unorm',
    RGBA8_SNORM: 'rgba8snorm', R8_UINT: 'r8uint', R8_SINT: 'r8sint', RG8_UINT: 'rg8uint', RG8_SINT: 'rg8sint',
    RGBA8_UINT: 'rgba8uint', RGBA8_SINT: 'rgba8sint', R16_UINT: 'r16uint', R16_SINT: 'r16sint', RG16_UINT: 'rg16uint',
    RG16_SINT: 'rg16sint', RGBA16_UINT: 'rgba16uint', RGBA16_SINT: 'rgba16sint', R32_UINT: 'r32uint', R32_SINT: 'r32sint',
    RG32_UINT: 'rg32uint', RG32_SINT: 'rg32sint', RGBA32_UINT: 'rgba32uint', RGBA32_SINT: 'rgba32sint',
    R16_FLOAT: 'r16float', RG16_FLOAT: 'rg16float', RGBA16_FLOAT: 'rgba16float', R32_FLOAT: 'r32float',
    RG32_FLOAT: 'rg32float', RGBA32_FLOAT: 'rgba32float', RGB10A2_UNORM: 'rgb10a2unorm', RGB10A2_UINT: 'rgb10a2uint',
    RG11B10_FLOAT: 'rg11b10ufloat', D32_FLOAT: 'depth32float', D32_FLOAT_S8_UINT: 'depth32float-stencil8',
    D24_UNORM_S8_UINT: 'depth24plus-stencil8', D16_UNORM: 'depth16unorm', S8_UINT: 'stencil8',
    // 16-bit normalized and 3-component formats have no WebGPU texture equivalent: nearest wider format
    R16_UNORM: 'r16float', RG16_UNORM: 'rg16float', RGBA16_UNORM: 'rgba16float', RGB8_UNORM: 'rgba8unorm',
    RGB16_FLOAT: 'rgba16float', RGB32_FLOAT: 'rgba32float',
  };
  const VERTEX_FORMAT = {
    R8_UNORM: 'unorm8', R8_SNORM: 'snorm8', RG8_UNORM: 'unorm8x2', RG8_SNORM: 'snorm8x2', RGB8_UNORM: 'unorm8x4',
    RGB8_SNORM: 'snorm8x4', RGBA8_UNORM: 'unorm8x4', RGBA8_SNORM: 'snorm8x4', R16_UNORM: 'unorm16', R16_SNORM: 'snorm16',
    RG16_UNORM: 'unorm16x2', RG16_SNORM: 'snorm16x2', RGB16_UNORM: 'unorm16x4', RGB16_SNORM: 'snorm16x4',
    RGBA16_UNORM: 'unorm16x4', RGBA16_SNORM: 'snorm16x4', R8_UINT: 'uint8', R8_SINT: 'sint8', RG8_UINT: 'uint8x2',
    RG8_SINT: 'sint8x2', RGB8_UINT: 'uint8x4', RGB8_SINT: 'sint8x4', RGBA8_UINT: 'uint8x4', RGBA8_SINT: 'sint8x4',
    R16_UINT: 'uint16', R16_SINT: 'sint16', RG16_UINT: 'uint16x2', RG16_SINT: 'sint16x2', RGB16_UINT: 'uint16x4',
    RGB16_SINT: 'sint16x4', RGBA16_UINT: 'uint16x4', RGBA16_SINT: 'sint16x4', R32_UINT: 'uint32', R32_SINT: 'sint32',
    RG32_UINT: 'uint32x2', RG32_SINT: 'sint32x2', RGB32_UINT: 'uint32x3', RGB32_SINT: 'sint32x3', RGBA32_UINT: 'uint32x4',
    RGBA32_SINT: 'sint32x4', R16_FLOAT: 'float16', RG16_FLOAT: 'float16x2', RGB16_FLOAT: 'float16x4',
    RGBA16_FLOAT: 'float16x4', R32_FLOAT: 'float32', RG32_FLOAT: 'float32x2', RGB32_FLOAT: 'float32x3',
    RGBA32_FLOAT: 'float32x4', RGB10A2_UNORM: 'unorm10-10-10-2',
  };
  const BYTES_PER_TEXEL = {
    r8unorm: 1, r8snorm: 1, r8uint: 1, r8sint: 1, rg8unorm: 2, rg8snorm: 2, rg8uint: 2, rg8sint: 2, rgba8unorm: 4,
    rgba8snorm: 4, rgba8uint: 4, rgba8sint: 4, bgra8unorm: 4, r16uint: 2, r16sint: 2, r16float: 2, rg16uint: 4,
    rg16sint: 4, rg16float: 4, rgba16uint: 8, rgba16sint: 8, rgba16float: 8, r32uint: 4, r32sint: 4, r32float: 4,
    rg32uint: 8, rg32sint: 8, rg32float: 8, rgba32uint: 16, rgba32sint: 16, rgba32float: 16, rgb10a2unorm: 4,
    rgb10a2uint: 4, rg11b10ufloat: 4, depth32float: 4, depth16unorm: 2, stencil8: 1,
    'depth32float-stencil8': 4, 'depth24plus-stencil8': 4,
  };
  const isDepth = (f) => f.startsWith('depth') || f === 'stencil8';
  const hasStencil = (f) => f.includes('stencil');
  function texFormat(ordinal) {
    const name = FORMATS[ordinal];
    let f = TEX_FORMAT[name];
    if (f === 'depth32float-stencil8' && !G.features.has('depth32float-stencil8')) f = 'depth24plus-stencil8';
    if (!f) throw new Error('unsupported texture format ' + name);
    return f;
  }

  // ------------------------------------------------------------------ device info
  G.info = function () {
    const L = G.device.limits;
    return JSON.stringify({
      name: G.adapterName, vendor: G.vendor, maxTextureSize: L.maxTextureDimension2D,
      minUniformOffsetAlignment: L.minUniformBufferOffsetAlignment, maxBufferSize: L.maxBufferSize,
      maxColorAttachments: L.maxColorAttachments, fallback: G.isFallback,
      features: Array.from(G.features),
    });
  };

  // ------------------------------------------------------------------ buffers
  // renderpearl GpuBuffer usage bits
  const B_MAP_READ = 1, B_MAP_WRITE = 2, B_COPY_DST = 8, B_COPY_SRC = 16, B_VERTEX = 32, B_INDEX = 64, B_UNIFORM = 128,
    B_TEXEL = 256, B_INDIRECT = 512;

  G.createBuffer = function (size, usage, label) {
    let u = GU.COPY_DST | GU.COPY_SRC;
    if (usage & B_VERTEX) u |= GU.VERTEX;
    if (usage & B_INDEX) u |= GU.INDEX;
    if (usage & B_UNIFORM) u |= GU.UNIFORM;
    if (usage & B_TEXEL) u |= GU.STORAGE;
    if (usage & B_INDIRECT) u |= GU.INDIRECT;
    const padded = Math.max(4, Math.ceil(size / 4) * 4);
    const buf = G.device.createBuffer({ size: padded, usage: u, label: label || undefined });
    return put({ kind: 'buffer', buf, size, usage, shadow: 0 });
  };

  // GPU objects closed mid-frame may still be referenced by recorded, unsubmitted commands: destroy after submit.
  G.deferred = [];
  G.destroyBuffer = function (id) {
    const b = G.obj[id];
    if (!b) return;
    release(id);
    G.deferred.push(() => { if (b.shadow) H().free(b.shadow); b.buf.destroy(); });
  };

  /** CPU-side copy for map(): readable buffers keep it current through readbacks; writes are flushed on unmap. */
  G.mapBuffer = function (id) {
    const b = G.obj[id];
    if (!b.shadow) b.shadow = H().calloc(Math.max(4, b.size));
    return b.shadow;
  };

  G.unmapBuffer = function (id, offset, length, written) {
    const b = G.obj[id];
    if (written && length > 0) G.writeBufferStaged(id, offset, b.shadow + offset, length);
  };

  // ------------------------------------------------------------------ transient memory (ring of upload chunks)
  const CHUNK = 4 << 20;
  G.transientAlloc = function (size, alignment) {
    alignment = Math.max(4, alignment | 0);
    size = Math.max(4, Math.ceil(size / 4) * 4);
    let chunk = null;
    for (const c of G.transient) {
      const off = Math.ceil(c.used / alignment) * alignment;
      if (off + size <= c.size) { chunk = c; break; }
    }
    if (!chunk) {
      const csize = Math.max(CHUNK, Math.ceil(size / CHUNK) * CHUNK);
      const buf = G.device.createBuffer({ size: csize, label: 'transient',
        usage: GU.COPY_DST | GU.COPY_SRC | GU.VERTEX | GU.INDEX | GU.UNIFORM | GU.INDIRECT | GU.STORAGE });
      chunk = { buf, size: csize, used: 0, dirtyFrom: 0, shadow: H().malloc(csize, 256) };
      chunk.id = put({ kind: 'buffer', buf, size: csize, usage: -1, shadow: chunk.shadow, transient: true });
      G.transient.push(chunk);
    }
    const off = Math.ceil(chunk.used / alignment) * alignment;
    chunk.used = off + size;
    G.lastTransient = chunk;
    return off;   // with G.lastTransient: Java reads id and address next
  };
  G.lastTransientId = () => G.lastTransient.id;
  G.lastTransientAddress = () => G.lastTransient.shadow;

  function flushTransient() {
    for (const c of G.transient) {
      if (c.used > c.dirtyFrom) {
        const n = c.used - c.dirtyFrom;
        G.queue.writeBuffer(c.buf, c.dirtyFrom, H().buffer, c.shadow + c.dirtyFrom, n);
      }
      c.used = 0;
      c.dirtyFrom = 0;
    }
  }

  /** Buffer write ordered with recorded commands: data goes through a transient chunk + copyBufferToBuffer. */
  G.writeBufferStaged = function (dst, dstOffset, srcAddr, size) {
    if (size <= 0) return;
    const b = G.obj[dst];
    if (dstOffset % 4) {
      // unaligned destination: flush everything recorded so far and write directly through the queue
      G.submit();
      const start = dstOffset & ~3, pre = dstOffset - start;
      const n = Math.ceil((pre + size) / 4) * 4;
      const tmp = new Uint8Array(n);
      if (b.shadow) tmp.set(H().u8.subarray(b.shadow + start, b.shadow + start + n));
      tmp.set(H().u8.subarray(srcAddr, srcAddr + size), pre);
      G.queue.writeBuffer(b.buf, start, tmp);
      return;
    }
    const n = Math.ceil(size / 4) * 4;
    const off = G.transientAlloc(n, 4);
    const chunk = G.lastTransient;
    H().u8.copyWithin(chunk.shadow + off, srcAddr, srcAddr + size);
    if (n > size) H().u8.fill(0, chunk.shadow + off + size, chunk.shadow + off + n);
    if (b.shadow && b.shadow !== srcAddr + 0 && !(srcAddr >= b.shadow && srcAddr < b.shadow + b.size)) {
      H().u8.copyWithin(b.shadow + dstOffset, srcAddr, srcAddr + size);
    }
    endPass();
    const limit = b.buf.size - dstOffset;
    encoder().copyBufferToBuffer(chunk.buf, off, b.buf, dstOffset, Math.min(n, limit));
  };

  G.copyBuffer = function (src, srcOff, dst, dstOff, size) {
    endPass();
    encoder().copyBufferToBuffer(G.obj[src].buf, srcOff, G.obj[dst].buf, dstOff, Math.ceil(size / 4) * 4);
  };

  // ------------------------------------------------------------------ textures
  // renderpearl GpuTexture usage bits
  const T_COPY_DST = 1, T_COPY_SRC = 2, T_BINDING = 4, T_ATTACHMENT = 8, T_CUBE = 16;

  G.createTexture = function (label, usage, formatOrdinal, w, h, layers, mips) {
    const format = texFormat(formatOrdinal);
    let u = TU.COPY_DST | TU.COPY_SRC | TU.TEXTURE_BINDING;
    if (usage & T_ATTACHMENT) u |= TU.RENDER_ATTACHMENT;
    const tex = G.device.createTexture({
      label: label || undefined, format, size: { width: Math.max(1, w), height: Math.max(1, h), depthOrArrayLayers: Math.max(1, layers) },
      mipLevelCount: Math.max(1, mips), usage: u, dimension: '2d',
      textureBindingViewDimension: (usage & T_CUBE) ? 'cube' : undefined,
    });
    return put({ kind: 'texture', tex, format, w, h, layers: Math.max(1, layers), mips, cube: !!(usage & T_CUBE),
      sourceFormat: FORMATS[formatOrdinal] });
  };

  G.destroyTexture = function (id) {
    const t = G.obj[id];
    if (!t) return;
    release(id);
    G.deferred.push(() => t.tex.destroy());
  };

  G.createView = function (texId, baseMip, mipCount) {
    const t = G.obj[texId];
    const dim = t.cube ? 'cube' : '2d';
    const sampleView = t.tex.createView({ dimension: dim, baseMipLevel: baseMip, mipLevelCount: mipCount,
      aspect: isDepth(t.format) && hasStencil(t.format) ? 'depth-only' : 'all' });
    return put({ kind: 'view', tex: t, texId, baseMip, mipCount, view: sampleView, attach: [] });
  };

  /** Render-attachment view of one mip level / layer. */
  function attachmentView(v, layer) {
    const key = layer | 0;
    let a = v.attach[key];
    if (!a) a = v.attach[key] = v.tex.tex.createView({ dimension: '2d', baseMipLevel: v.baseMip, mipLevelCount: 1, baseArrayLayer: key, arrayLayerCount: 1 });
    return a;
  }

  G.destroyView = function (id) { release(id); };

  const ADDRESS = ['repeat', 'clamp-to-edge'];
  const FILTER = ['nearest', 'linear'];
  G.createSampler = function (u, v, min, mag, aniso, maxLod) {
    const desc = { addressModeU: ADDRESS[u], addressModeV: ADDRESS[v], addressModeW: ADDRESS[v],
      minFilter: FILTER[min], magFilter: FILTER[mag], mipmapFilter: FILTER[min] };
    if (maxLod >= 0) desc.lodMaxClamp = maxLod;
    if (aniso > 1 && min === 1 && mag === 1) desc.maxAnisotropy = Math.min(16, aniso);
    const s = G.device.createSampler(desc);
    const nonFiltering = G.device.createSampler({ ...desc, minFilter: 'nearest', magFilter: 'nearest', mipmapFilter: 'nearest', maxAnisotropy: 1 });
    return put({ kind: 'sampler', s, nonFiltering, filtering: min === 1 || mag === 1 });
  };
  G.destroySampler = function (id) { release(id); };

  function rowLayout(t, w) {
    const bpt = BYTES_PER_TEXEL[t.format] || 4;
    return { bpt, tight: w * bpt, aligned: Math.ceil(w * bpt / 256) * 256 };
  }

  /** Source rows are tightly packed RGBA etc. in the native heap. RGB8 sources are expanded to RGBA. */
  function sourceToTexelBytes(t, srcAddr, w, h) {
    const heap = H();
    if (t.sourceFormat === 'RGB8_UNORM') {
      const out = new Uint8Array(w * h * 4);
      for (let i = 0, j = srcAddr; i < w * h; i++, j += 3) {
        out[i * 4] = heap.u8[j]; out[i * 4 + 1] = heap.u8[j + 1]; out[i * 4 + 2] = heap.u8[j + 2]; out[i * 4 + 3] = 255;
      }
      return out;
    }
    return null;
  }

  /**
   * Texture upload ordered with the recorded commands: rows are repacked to 256-byte alignment into a transient
   * chunk (uploaded at submit) and copied with copyBufferToTexture.
   */
  G.writeTexture = function (texId, srcAddr, srcBytes, mip, x, y, w, h, layer, srcStride) {
    const t = G.obj[texId];
    if (w <= 0 || h <= 0) return;
    const conv = srcStride ? null : sourceToTexelBytes(t, srcAddr, w, h);
    const { tight, aligned } = rowLayout(t, w);
    const stride = srcStride || tight;
    const off = G.transientAlloc(aligned * h, 256);
    const chunk = G.lastTransient, heap = H().u8, dst = chunk.shadow + off;
    if (conv) {
      for (let r = 0; r < h; r++) heap.set(conv.subarray(r * tight, r * tight + tight), dst + r * aligned);
    } else if (aligned === tight && stride === tight) {
      heap.copyWithin(dst, srcAddr, srcAddr + tight * h);
    } else {
      for (let r = 0; r < h; r++) heap.copyWithin(dst + r * aligned, srcAddr + r * stride, srcAddr + r * stride + tight);
    }
    endPass();
    encoder().copyBufferToTexture({ buffer: chunk.buf, offset: off, bytesPerRow: aligned, rowsPerImage: h },
      { texture: t.tex, mipLevel: mip, origin: { x, y, z: layer | 0 } }, { width: w, height: h, depthOrArrayLayers: 1 });
  };

  G.copyBufferToTexture = function (bufId, srcOffset, srcRowLength, srcRows, texId, mip, x, y, w, h, layer) {
    const t = G.obj[texId], b = G.obj[bufId];
    const { bpt } = rowLayout(t, w);
    const rowLen = (srcRowLength || w) * bpt;
    endPass();
    if (rowLen % 256 === 0 && srcOffset % bpt === 0) {
      encoder().copyBufferToTexture({ buffer: b.buf, offset: srcOffset, bytesPerRow: rowLen, rowsPerImage: srcRows || h },
        { texture: t.tex, mipLevel: mip, origin: { x, y, z: layer | 0 } }, { width: w, height: h, depthOrArrayLayers: 1 });
      return;
    }
    // unaligned rows: repack from the CPU copy of the buffer (transient chunks always have one)
    if (!b.shadow) throw new Error('copyBufferToTexture with unaligned rows needs a CPU-visible buffer');
    G.writeTexture(texId, b.shadow + srcOffset, rowLen * h, mip, x, y, w, h, layer, rowLen);
  };

  G.copyTextureToTexture = function (src, dst, mip, dx, dy, sx, sy, w, h) {
    endPass();
    encoder().copyTextureToTexture({ texture: G.obj[src].tex, mipLevel: mip, origin: { x: sx, y: sy } },
      { texture: G.obj[dst].tex, mipLevel: mip, origin: { x: dx, y: dy } }, { width: w, height: h });
  };

  /**
   * Texture -> GpuBuffer readback. The GPU copy uses 256-byte aligned rows into a temporary buffer; when it has
   * been mapped the rows are packed into the destination buffer's CPU copy and callbackId is reported to Java
   * (G.pollReadbacks) so the Runnable runs on the render thread.
   */
  G.copyTextureToBuffer = function (texId, bufId, dstOffset, mip, x, y, w, h, callbackId) {
    const t = G.obj[texId], b = G.obj[bufId];
    const { bpt, tight, aligned } = rowLayout(t, w);
    const tmp = G.device.createBuffer({ size: aligned * h, usage: GU.COPY_DST | GU.MAP_READ });
    endPass();
    const aspect = isDepth(t.format) ? 'depth-only' : 'all';
    encoder().copyTextureToBuffer({ texture: t.tex, mipLevel: mip, origin: { x, y }, aspect },
      { buffer: tmp, bytesPerRow: aligned, rowsPerImage: h }, { width: w, height: h });
    const shadow = G.mapBuffer(bufId);
    G.readbacksInFlight++;
    G.afterSubmit.push(() => tmp.mapAsync(GPUMapMode.READ).then(() => {
      const src = new Uint8Array(tmp.getMappedRange());
      const heap = H().u8;
      for (let r = 0; r < h; r++) heap.set(src.subarray(r * aligned, r * aligned + tight), shadow + dstOffset + r * tight);
      tmp.unmap();
      tmp.destroy();
      G.readbacksInFlight--;
      G.pendingReadbacks.push(callbackId);
      void bpt;
    }, (e) => { G.readbacksInFlight--; G.pendingReadbacks.push(callbackId); console.error('readback failed', e); }));
  };
  G.afterSubmit = [];

  /** Next completed readback callback id, or 0. */
  G.pollReadback = function () {
    return G.pendingReadbacks.length ? G.pendingReadbacks.shift() : 0;
  };

  // ------------------------------------------------------------------ clears
  G.clearColor = function (texId, r, g, b, a) {
    const t = G.obj[texId];
    endPass();
    for (let layer = 0; layer < t.layers; layer++) {
      const view = t.tex.createView({ dimension: '2d', baseMipLevel: 0, mipLevelCount: 1, baseArrayLayer: layer, arrayLayerCount: 1 });
      encoder().beginRenderPass({ colorAttachments: [{ view, loadOp: 'clear', storeOp: 'store', clearValue: { r, g, b, a } }] }).end();
    }
  };

  G.clearDepth = function (texId, depth) {
    const t = G.obj[texId];
    endPass();
    const view = t.tex.createView({ dimension: '2d', baseMipLevel: 0, mipLevelCount: 1 });
    const att = { view, depthLoadOp: 'clear', depthStoreOp: 'store', depthClearValue: depth };
    if (hasStencil(t.format)) Object.assign(att, { stencilLoadOp: 'clear', stencilStoreOp: 'store', stencilClearValue: 0 });
    encoder().beginRenderPass({ colorAttachments: [], depthStencilAttachment: att }).end();
  };

  G.clearColorDepthRegion = function (colorId, r, g, b, a, depthId, depth, x, y, w, h) {
    // partial clear: a render pass that loads, with a scissored full-screen clear draw
    const c = G.obj[colorId], d = G.obj[depthId];
    endPass();
    const pass = encoder().beginRenderPass({
      colorAttachments: [{ view: c.tex.createView({ dimension: '2d', mipLevelCount: 1, arrayLayerCount: 1 }), loadOp: 'load', storeOp: 'store' }],
      depthStencilAttachment: Object.assign({ view: d.tex.createView({ dimension: '2d', mipLevelCount: 1 }), depthLoadOp: 'load', depthStoreOp: 'store' },
        hasStencil(d.format) ? { stencilLoadOp: 'load', stencilStoreOp: 'store' } : {}),
    });
    pass.setScissorRect(x, y, w, h);
    pass.setPipeline(clearPipeline(c.format, d.format));
    const ub = new Float32Array([r, g, b, a, depth, 0, 0, 0]);
    const u = G.device.createBuffer({ size: 32, usage: GU.UNIFORM | GU.COPY_DST });
    G.queue.writeBuffer(u, 0, ub);
    pass.setBindGroup(0, G.device.createBindGroup({ layout: pass.__layout || clearPipeline(c.format, d.format).getBindGroupLayout(0), entries: [{ binding: 0, resource: { buffer: u } }] }));
    pass.draw(3);
    pass.end();
  };

  const clearPipelines = new Map();
  function clearPipeline(colorFormat, depthFormat) {
    const key = colorFormat + '|' + depthFormat;
    let p = clearPipelines.get(key);
    if (p) return p;
    const module = G.device.createShaderModule({ code: `
      struct C { color: vec4<f32>, depth: vec4<f32> };
      @group(0) @binding(0) var<uniform> c: C;
      @vertex fn vs(@builtin(vertex_index) i: u32) -> @builtin(position) vec4<f32> {
        let p = vec2<f32>(f32((i << 1u) & 2u), f32(i & 2u)) * 2.0 - 1.0;
        return vec4<f32>(p, c.depth.x, 1.0);
      }
      @fragment fn fs() -> @location(0) vec4<f32> { return c.color; }` });
    p = G.device.createRenderPipeline({ layout: 'auto', vertex: { module, entryPoint: 'vs' },
      fragment: { module, entryPoint: 'fs', targets: [{ format: colorFormat }] },
      depthStencil: { format: depthFormat, depthWriteEnabled: true, depthCompare: 'always' }, primitive: { topology: 'triangle-list' } });
    clearPipelines.set(key, p);
    return p;
  }

  // ------------------------------------------------------------------ pipelines
  const COMPARE = ['always', 'less', 'less-equal', 'equal', 'not-equal', 'greater-equal', 'greater', 'never'];
  const BLEND_FACTOR = { CONSTANT_ALPHA: 'constant', CONSTANT_COLOR: 'constant', DST_ALPHA: 'dst-alpha', DST_COLOR: 'dst',
    ONE: 'one', ONE_MINUS_CONSTANT_ALPHA: 'one-minus-constant', ONE_MINUS_CONSTANT_COLOR: 'one-minus-constant',
    ONE_MINUS_DST_ALPHA: 'one-minus-dst-alpha', ONE_MINUS_DST_COLOR: 'one-minus-dst', ONE_MINUS_SRC_ALPHA: 'one-minus-src-alpha',
    ONE_MINUS_SRC_COLOR: 'one-minus-src', SRC_ALPHA: 'src-alpha', SRC_ALPHA_SATURATE: 'src-alpha-saturated', SRC_COLOR: 'src', ZERO: 'zero' };
  const BLEND_OP = { ADD: 'add', SUBTRACT: 'subtract', REVERSE_SUBTRACT: 'reverse-subtract', MIN: 'min', MAX: 'max' };
  const TOPOLOGY = { LINES: 'triangle-list', DEBUG_LINES: 'line-list', DEBUG_LINE_STRIP: 'line-strip', POINTS: 'point-list',
    TRIANGLES: 'triangle-list', TRIANGLE_STRIP: 'triangle-strip', TRIANGLE_FAN: 'triangle-list', QUADS: 'triangle-list' };

  function blendComponent(eq) {
    const op = BLEND_OP[eq.op];
    // min/max ignore factors and WebGPU requires them to be 'one'
    if (op === 'min' || op === 'max') return { operation: op, srcFactor: 'one', dstFactor: 'one' };
    return { operation: op, srcFactor: BLEND_FACTOR[eq.src], dstFactor: BLEND_FACTOR[eq.dst] };
  }

  /** Vertex shader WGSL from Tint: flip Y (Vulkan-style) so render targets have GL's memory layout. */
  function patchVertexWGSL(code) {
    return code.replace(/(\n[ \t]*)return main_out\(/, '$1gl_Position.y = -gl_Position.y;$1return main_out(');
  }

  /**
   * desc (JSON from Java): {name, shaders:[{stage, spv:[addr,bytes]}], vertexBuffers:[{slot,stride,step}],
   *  attribs:[{slot,location,offset,format}], uniforms:[{name,type,format}], depth:{compare,write,biasScale,biasConst}|null,
   *  cull, wireframe, targets:[{format, blend:{color:{src,dst,op},alpha:{...}}|null, mask}], topology}
   */
  G.createPipeline = function (json) {
    const desc = JSON.parse(json);
    const p = { kind: 'pipeline', desc, variants: new Map(), modules: {}, error: null, samplerBindings: new Set() };
    try {
      for (const sh of desc.shaders) {
        const words = new Uint32Array(H().buffer.slice(sh.spv[0], sh.spv[0] + sh.spv[1]));
        const r = globalThis.__mcShaders.toWGSL(words, 1);
        let code = r.code;
        if (sh.stage === 'vertex') code = patchVertexWGSL(code);
        for (const s of r.samplers) p.samplerBindings.add(s.binding);
        const module = G.device.createShaderModule({ code, label: desc.name + ':' + sh.stage });
        p.modules[sh.stage] = { module, code };
      }
    } catch (e) {
      p.error = String(e && e.message || e);
      console.warn('pipeline ' + desc.name + ' unavailable: ' + p.error);
    }
    G.stats.pipelines++;
    return put(p);
  };
  G.pipelineError = (id) => G.obj[id].error || '';
  G.destroyPipeline = (id) => release(id);

  /** Pipeline variant for the bound texture sample types (depth / unfilterable textures need other layouts). */
  function pipelineVariant(p, sampleKinds, indexFormat) {
    const key = sampleKinds.join(',') + '|' + (indexFormat || '');
    let v = p.variants.get(key);
    if (v) return v;
    const d = p.desc;
    const g0 = [], g1 = [];
    d.uniforms.forEach((u, i) => {
      if (u.type === 'UNIFORM_BUFFER') {
        g0.push({ binding: i, visibility: SS.VERTEX | SS.FRAGMENT, buffer: { type: 'uniform', hasDynamicOffset: true } });
      } else if (u.type === 'COMBINED_IMAGE_SAMPLER') {
        const kind = sampleKinds[i] || 'float';
        g0.push({ binding: i, visibility: SS.VERTEX | SS.FRAGMENT, texture: { sampleType: kind, viewDimension: u.cube ? 'cube' : '2d' } });
        if (p.samplerBindings.has(i)) {
          g1.push({ binding: i, visibility: SS.VERTEX | SS.FRAGMENT,
            sampler: { type: kind === 'float' ? 'filtering' : 'non-filtering' } });
        }
      } else if (u.type === 'TEXEL_BUFFER') {
        g0.push({ binding: i, visibility: SS.VERTEX | SS.FRAGMENT, buffer: { type: 'read-only-storage', hasDynamicOffset: true } });
      }
    });
    const layouts = [G.device.createBindGroupLayout({ entries: g0 }), G.device.createBindGroupLayout({ entries: g1 })];
    const buffers = [];
    for (const vb of d.vertexBuffers) {
      buffers[vb.slot] = { arrayStride: vb.stride, stepMode: vb.step ? 'instance' : 'vertex', attributes: [] };
    }
    for (const a of d.attribs) {
      const fmt = VERTEX_FORMAT[a.format];
      if (!fmt) throw new Error('unsupported vertex format ' + a.format);
      (buffers[a.slot] || (buffers[a.slot] = { arrayStride: 0, stepMode: 'vertex', attributes: [] }))
        .attributes.push({ shaderLocation: a.location, offset: a.offset, format: fmt });
    }
    for (let i = 0; i < buffers.length; i++) if (!buffers[i]) buffers[i] = null;
    const topology = TOPOLOGY[d.topology] || 'triangle-list';
    const targets = d.targets.map((t) => t && {
      format: texFormat(t.format),
      blend: t.blend ? { color: blendComponent(t.blend.color), alpha: blendComponent(t.blend.alpha) } : undefined,
      writeMask: t.mask,
    });
    const desc = {
      label: d.name,
      layout: G.device.createPipelineLayout({ bindGroupLayouts: layouts }),
      vertex: { module: p.modules.vertex.module, entryPoint: 'main', buffers },
      primitive: { topology, cullMode: d.cull ? 'back' : 'none', frontFace: 'cw',
        stripIndexFormat: topology.endsWith('strip') ? (indexFormat || undefined) : undefined },
    };
    if (p.modules.fragment) desc.fragment = { module: p.modules.fragment.module, entryPoint: 'main', targets };
    if (d.depth) {
      desc.depthStencil = {
        format: texFormat(d.depthFormat), depthWriteEnabled: !!d.depth.write, depthCompare: COMPARE[d.depth.compare],
        depthBias: Math.round(d.depth.biasConst), depthBiasSlopeScale: d.depth.biasScale,
      };
    } else if (d.depthFormat >= 0) {
      desc.depthStencil = { format: texFormat(d.depthFormat), depthWriteEnabled: false, depthCompare: 'always' };
    }
    v = { pipeline: G.device.createRenderPipeline(desc), layouts };
    p.variants.set(key, v);
    return v;
  }

  // ------------------------------------------------------------------ command encoding
  function encoder() {
    if (!G.encoder) G.encoder = G.device.createCommandEncoder();
    return G.encoder;
  }
  function endPass() {
    if (G.pass) {
      G.pass.end();
      G.pass = null;
      G.passState = null;
    }
  }

  /**
   * colors: [[viewId, layer, clear?, r,g,b,a] | null], depth: [viewId, clear?, value] | null, area [x,y,w,h]
   */
  G.beginPass = function (json) {
    const d = JSON.parse(json);
    endPass();
    const colorAttachments = d.colors.map((c) => {
      if (!c) return null;
      const v = G.obj[c[0]];
      return { view: attachmentView(v, c[1]), loadOp: c[2] ? 'clear' : 'load', storeOp: 'store',
        clearValue: c[2] ? { r: c[3], g: c[4], b: c[5], a: c[6] } : undefined };
    });
    let depthStencilAttachment;
    let depthFormat = null;
    if (d.depth) {
      const v = G.obj[d.depth[0]];
      depthFormat = v.tex.format;
      depthStencilAttachment = { view: attachmentView(v, 0), depthLoadOp: d.depth[1] ? 'clear' : 'load', depthStoreOp: 'store',
        depthClearValue: d.depth[1] ? d.depth[2] : undefined };
      if (hasStencil(depthFormat)) Object.assign(depthStencilAttachment, { stencilLoadOp: 'load', stencilStoreOp: 'store' });
    }
    G.pass = encoder().beginRenderPass({ colorAttachments, depthStencilAttachment });
    const first = d.colors.find((c) => c);
    const tex = first ? G.obj[first[0]].tex : G.obj[d.depth[0]].tex;
    const mip = first ? G.obj[first[0]].baseMip : 0;
    const tw = Math.max(1, tex.w >> mip), th = Math.max(1, tex.h >> mip);
    G.passState = { width: tw, height: th, pipeline: null, variant: null, vertex: [], index: null, indexFormat: 'uint32',
      area: d.area, depthFormat };
    G.pass.setViewport(0, 0, tw, th, 0, 1);
    const [x, y, w, h] = clampRect(d.area, tw, th);
    G.pass.setScissorRect(x, y, w, h);
    G.stats.passes++;
  };

  function clampRect(r, tw, th) {
    const x = Math.max(0, Math.min(r[0], tw)), y = Math.max(0, Math.min(r[1], th));
    return [x, y, Math.max(0, Math.min(r[2], tw - x)), Math.max(0, Math.min(r[3], th - y))];
  }

  G.endPass = function () { endPass(); };

  G.setPipeline = function (id) {
    const s = G.passState;
    s.pipeline = G.obj[id];
    s.variant = null;
  };

  G.setVertexBuffer = function (slot, bufId, offset) {
    G.passState.vertex[slot] = bufId ? [G.obj[bufId].buf, offset] : null;
    if (bufId) G.pass.setVertexBuffer(slot, G.obj[bufId].buf, offset);
  };

  G.setIndexBuffer = function (bufId, is32) {
    const s = G.passState;
    s.indexFormat = is32 ? 'uint32' : 'uint16';
    s.index = bufId ? G.obj[bufId].buf : null;
    if (bufId) G.pass.setIndexBuffer(s.index, s.indexFormat);
  };

  G.setScissor = function (enabled, x, y, w, h) {
    const s = G.passState;
    const r = enabled ? clampRect([x, y, w, h], s.width, s.height) : clampRect(s.area, s.width, s.height);
    G.pass.setScissorRect(r[0], r[1], r[2], r[3]);
  };

  // Uniform values for the next draw: per binding [kind, a, b, c]: 1 = buffer(id, offset, size), 2 = view+sampler(viewId, samplerId)
  const uniformState = [];
  G.setUniformBuffer = function (index, bufId, offset, size) { uniformState[index] = [1, bufId, offset, size]; };
  G.setUniformTexture = function (index, viewId, samplerId) { uniformState[index] = [2, viewId, samplerId]; };
  G.clearUniforms = function (count) { uniformState.length = count; for (let i = 0; i < count; i++) uniformState[i] = null; };

  const bindGroupCache = new Map();
  function sampleKindOf(view) {
    const f = view.tex.format;
    if (isDepth(f)) return 'depth';
    if (/uint/.test(f)) return 'uint';
    if (/sint/.test(f)) return 'sint';
    if (/32float/.test(f) && !G.features.has('float32-filterable')) return 'unfilterable-float';
    return 'float';
  }

  function bindAndValidate() {
    const s = G.passState, p = s.pipeline;
    if (!p || p.error || !p.modules.vertex) return false;
    const d = p.desc;
    const kinds = d.uniforms.map((u, i) => {
      if (u.type !== 'COMBINED_IMAGE_SAMPLER') return '';
      const val = uniformState[i];
      return val && G.obj[val[1]] ? sampleKindOf(G.obj[val[1]]) : 'float';
    });
    const indexed = s.drawIndexed ? s.indexFormat : '';
    let v;
    try {
      v = pipelineVariant(p, kinds, indexed);
    } catch (e) {
      p.error = String(e.message || e);
      console.warn('pipeline ' + d.name + ': ' + p.error);
      return false;
    }
    if (s.variant !== v) {
      G.pass.setPipeline(v.pipeline);
      s.variant = v;
    }
    // group 0: buffers (dynamic offsets) and textures; group 1: samplers
    const e0 = [], e1 = [], offsets = [];
    let key0 = '', key1 = '';
    for (let i = 0; i < d.uniforms.length; i++) {
      const u = d.uniforms[i], val = uniformState[i];
      if (!val) { return false; }
      if (u.type === 'UNIFORM_BUFFER' || u.type === 'TEXEL_BUFFER') {
        const b = G.obj[val[1]];
        // WGSL struct sizes are rounded up to 16 bytes; std140 data may end earlier (e.g. a trailing vec3)
        const size = Math.min(Math.ceil(val[3] / 16) * 16, b.buf.size - val[2]);
        e0.push({ binding: i, resource: { buffer: b.buf, offset: 0, size } });
        offsets.push(val[2]);
        key0 += 'b' + val[1] + ':' + size + ',';
      } else {
        const view = G.obj[val[1]], smp = G.obj[val[2]];
        e0.push({ binding: i, resource: view.view });
        key0 += 't' + val[1] + ',';
        if (p.samplerBindings.has(i)) {
          e1.push({ binding: i, resource: kinds[i] === 'float' ? smp.s : smp.nonFiltering });
          key1 += val[2] + (kinds[i] === 'float' ? 'f' : 'n') + ',';
        }
      }
    }
    G.pass.setBindGroup(0, cachedBindGroup(v.layouts[0], key0, e0), offsets);
    G.pass.setBindGroup(1, cachedBindGroup(v.layouts[1], key1, e1));
    return true;
  }

  function cachedBindGroup(layout, key, entries) {
    let m = bindGroupCache.get(layout);
    if (!m) { m = new Map(); bindGroupCache.set(layout, m); }
    let bg = m.get(key);
    if (!bg) {
      bg = G.device.createBindGroup({ layout, entries });
      m.set(key, bg);
      G.stats.bindGroups++;
      if (m.size > 4096) m.clear();
    }
    return bg;
  }

  G.draw = function (vertexCount, instanceCount, firstVertex, firstInstance) {
    const s = G.passState;
    s.drawIndexed = false;
    if (!bindAndValidate()) return;
    G.pass.draw(vertexCount, instanceCount, firstVertex, firstInstance);
    G.stats.draws++;
  };

  G.drawIndexed = function (indexCount, instanceCount, firstIndex, baseVertex, firstInstance) {
    const s = G.passState;
    s.drawIndexed = true;
    if (!s.index || !bindAndValidate()) return;
    G.pass.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
    G.stats.draws++;
  };

  /** multiDraw: drawParams in the heap as [firstIndex/firstVertex, count, baseVertex] triples (see Java). */
  G.multiDrawIndexed = function (addr, drawCount) {
    const s = G.passState;
    s.drawIndexed = true;
    if (!s.index || !bindAndValidate()) return;
    const dv = H().dv;
    for (let i = 0; i < drawCount; i++) {
      const p = addr + i * 12;
      G.pass.drawIndexed(dv.getInt32(p + 4, true), 1, dv.getInt32(p, true), dv.getInt32(p + 8, true), 0);
    }
    G.stats.draws += drawCount;
  };

  G.drawIndexedIndirect = function (bufId, offset, drawCount) {
    const s = G.passState;
    s.drawIndexed = true;
    if (!s.index || !bindAndValidate()) return;
    for (let i = 0; i < drawCount; i++) G.pass.drawIndexedIndirect(G.obj[bufId].buf, offset + i * 20);
    G.stats.draws += drawCount;
  };

  G.drawIndirect = function (bufId, offset, drawCount) {
    const s = G.passState;
    s.drawIndexed = false;
    if (!bindAndValidate()) return;
    for (let i = 0; i < drawCount; i++) G.pass.drawIndirect(G.obj[bufId].buf, offset + i * 16);
    G.stats.draws += drawCount;
  };

  G.submit = function () {
    endPass();
    flushTransient();
    if (G.encoder) {
      G.queue.submit([G.encoder.finish()]);
      G.encoder = null;
      G.stats.submits++;
    }
    if (G.deferred.length) {
      const list = G.deferred;
      G.deferred = [];
      for (const f of list) f();
    }
    if (G.afterSubmit.length) {
      const list = G.afterSubmit;
      G.afterSubmit = [];
      for (const f of list) f();
    }
  };

  // fences: resolved when all work submitted so far has completed
  G.createFence = function () {
    G.submit();
    const f = { done: false };
    G.queue.onSubmittedWorkDone().then(() => { f.done = true; });
    return put(f);
  };
  G.fenceDone = (id) => !G.obj[id] || G.obj[id].done;
  G.fencePromise = function (id) {
    const f = G.obj[id];
    if (!f || f.done) return Promise.resolve();
    return G.queue.onSubmittedWorkDone();
  };
  G.destroyFence = (id) => release(id);

  // ------------------------------------------------------------------ surface (canvas)
  G.configureSurface = function (w, h, vsync) {
    const canvas = globalThis.__mcSDL.canvas;
    if (!G.context) {
      G.context = canvas.getContext('webgpu');
      G.canvasFormat = navigator.gpu.getPreferredCanvasFormat();
    }
    G.context.configure({ device: G.device, format: G.canvasFormat, alphaMode: 'opaque' });
    G.vsync = vsync;
    return true;
  };

  let blitPipeline = null, blitSampler = null;
  /** Draws the game's final color texture to the canvas, flipping Y back (see the conventions above). */
  G.blitToSurface = function (viewId) {
    const v = G.obj[viewId];
    if (!G.context) return;
    endPass();
    if (!blitPipeline) {
      const module = G.device.createShaderModule({ code: `
        struct O { @builtin(position) pos: vec4<f32>, @location(0) uv: vec2<f32> };
        @vertex fn vs(@builtin(vertex_index) i: u32) -> O {
          let p = vec2<f32>(f32((i << 1u) & 2u), f32(i & 2u));
          var o: O;
          o.pos = vec4<f32>(p * 2.0 - 1.0, 0.0, 1.0);
          o.uv = vec2<f32>(p.x, p.y);
          return o;
        }
        @group(0) @binding(0) var t: texture_2d<f32>;
        @group(0) @binding(1) var s: sampler;
        @fragment fn fs(i: O) -> @location(0) vec4<f32> { return vec4<f32>(textureSample(t, s, i.uv).rgb, 1.0); }` });
      blitPipeline = G.device.createRenderPipeline({ layout: 'auto', vertex: { module, entryPoint: 'vs' },
        fragment: { module, entryPoint: 'fs', targets: [{ format: G.canvasFormat }] }, primitive: { topology: 'triangle-list' } });
      blitSampler = G.device.createSampler({ minFilter: 'linear', magFilter: 'linear' });
    }
    const target = G.context.getCurrentTexture();
    const pass = encoder().beginRenderPass({ colorAttachments: [{ view: target.createView(), loadOp: 'clear', storeOp: 'store', clearValue: { r: 0, g: 0, b: 0, a: 1 } }] });
    pass.setPipeline(blitPipeline);
    pass.setBindGroup(0, G.device.createBindGroup({ layout: blitPipeline.getBindGroupLayout(0),
      entries: [{ binding: 0, resource: v.tex.tex.createView({ dimension: '2d', baseMipLevel: v.baseMip, mipLevelCount: 1, baseArrayLayer: 0, arrayLayerCount: 1 }) },
        { binding: 1, resource: blitSampler }] }));
    pass.draw(3);
    pass.end();
  };

  G.present = function () {
    G.submit();
    G.frame++;
  };
})();
