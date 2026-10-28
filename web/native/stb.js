// stb_image / stb_image_write / stb_image_resize for the browser (the functions NativeImage uses).
// Decoding must be synchronous (NativeImage.read returns pixels immediately), so PNG is decoded here with a
// small inflate implementation; output follows stb semantics (8 bits per channel, desired channel count).
'use strict';
(function () {
  const H = () => globalThis.__mcHeap, N = globalThis.__mcNative;
  let failure = 'no error';
  let failurePtr = 0;

  // ------------------------------------------------------------------ inflate (RFC 1951)
  const LBASE = [3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258];
  const LEXT = [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0];
  const DBASE = [1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073,
    4097, 6145, 8193, 12289, 16385, 24577];
  const DEXT = [0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13];
  const CLORDER = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15];

  /** Canonical Huffman table: counts per length + symbols sorted by code. */
  function buildTree(lengths, n) {
    const counts = new Uint16Array(16), offs = new Uint16Array(16), symbols = new Uint16Array(n);
    for (let i = 0; i < n; i++) counts[lengths[i]]++;
    counts[0] = 0;
    for (let i = 1; i < 16; i++) offs[i] = offs[i - 1] + counts[i - 1];
    for (let i = 0; i < n; i++) if (lengths[i]) symbols[offs[lengths[i]]++] = i;
    return { counts, symbols };
  }

  const FIXED_L = (() => { const l = new Uint8Array(288); l.fill(8, 0, 144); l.fill(9, 144, 256); l.fill(7, 256, 280); l.fill(8, 280, 288); return buildTree(l, 288); })();
  const FIXED_D = (() => { const l = new Uint8Array(30); l.fill(5); return buildTree(l, 30); })();

  function inflate(src, expected) {
    let out = new Uint8Array(expected || src.length * 4 + 1024), op = 0;
    let pos = 0, bitbuf = 0, bitcnt = 0;
    const need = (n) => { if (op + n > out.length) { const o = new Uint8Array(Math.max(out.length * 2, op + n)); o.set(out); out = o; } };
    const bits = (n) => {
      while (bitcnt < n) { if (pos >= src.length) throw new Error('inflate: unexpected end'); bitbuf |= src[pos++] << bitcnt; bitcnt += 8; }
      const v = bitbuf & ((1 << n) - 1);
      bitbuf >>>= n; bitcnt -= n;
      return v;
    };
    const decode = (t) => {
      let code = 0, first = 0, index = 0;
      for (let len = 1; len < 16; len++) {
        code |= bits(1);
        const count = t.counts[len];
        if (code - count < first) return t.symbols[index + (code - first)];
        index += count; first += count; first <<= 1; code <<= 1;
      }
      throw new Error('inflate: bad code');
    };
    let last;
    do {
      last = bits(1);
      const type = bits(2);
      if (type === 0) {
        bitbuf = 0; bitcnt = 0;
        const len = src[pos] | (src[pos + 1] << 8);
        pos += 4;
        need(len);
        out.set(src.subarray(pos, pos + len), op);
        pos += len; op += len;
        continue;
      }
      let lt, dt;
      if (type === 1) { lt = FIXED_L; dt = FIXED_D; } else if (type === 2) {
        const hlit = bits(5) + 257, hdist = bits(5) + 1, hclen = bits(4) + 4;
        const cl = new Uint8Array(19);
        for (let i = 0; i < hclen; i++) cl[CLORDER[i]] = bits(3);
        const ct = buildTree(cl, 19);
        const lens = new Uint8Array(hlit + hdist);
        for (let i = 0; i < hlit + hdist;) {
          const sym = decode(ct);
          if (sym < 16) lens[i++] = sym;
          else if (sym === 16) { const p = lens[i - 1]; for (let r = bits(2) + 3; r > 0; r--) lens[i++] = p; } else if (sym === 17) { i += bits(3) + 3; } else { i += bits(7) + 11; }
        }
        lt = buildTree(lens.subarray(0, hlit), hlit);
        dt = buildTree(lens.subarray(hlit), hdist);
      } else throw new Error('inflate: bad block type');
      for (;;) {
        const sym = decode(lt);
        if (sym < 256) { need(1); out[op++] = sym; } else if (sym === 256) break; else {
          const s = sym - 257;
          const len = LBASE[s] + bits(LEXT[s]);
          const ds = decode(dt);
          const dist = DBASE[ds] + bits(DEXT[ds]);
          need(len);
          for (let i = 0; i < len; i++, op++) out[op] = out[op - dist];
        }
      }
    } while (!last);
    return out.subarray(0, op);
  }
  globalThis.__mcInflate = inflate;

  // ------------------------------------------------------------------ PNG
  function paeth(a, b, c) {
    const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
    return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
  }

  function decodePNG(u8) {
    const sig = [137, 80, 78, 71, 13, 10, 26, 10];
    for (let i = 0; i < 8; i++) if (u8[i] !== sig[i]) throw new Error('not a PNG');
    const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
    let p = 8, w = 0, h = 0, depth = 0, ctype = 0, interlace = 0, palette = null, trns = null;
    const idat = [];
    let idatLen = 0;
    while (p < u8.length) {
      const len = dv.getUint32(p), type = String.fromCharCode(u8[p + 4], u8[p + 5], u8[p + 6], u8[p + 7]);
      const data = u8.subarray(p + 8, p + 8 + len);
      if (type === 'IHDR') { w = dv.getUint32(p + 8); h = dv.getUint32(p + 12); depth = data[8]; ctype = data[9]; interlace = data[12]; } else if (type === 'PLTE') palette = data; else if (type === 'tRNS') trns = data; else if (type === 'IDAT') { idat.push(data); idatLen += len; } else if (type === 'IEND') break;
      p += 12 + len;
    }
    const z = new Uint8Array(idatLen);
    let zo = 0;
    for (const d of idat) { z.set(d, zo); zo += d.length; }
    const channels = [1, 0, 3, 1, 2, 0, 4][ctype];
    const bpp = Math.max(1, (channels * depth) >> 3);           // bytes per complete pixel for filtering
    const rowBytes = (pw) => Math.ceil(pw * channels * depth / 8);
    const passes = interlace ? [[0, 0, 8, 8], [4, 0, 8, 8], [0, 4, 4, 8], [2, 0, 4, 4], [0, 2, 2, 4], [1, 0, 2, 2], [0, 1, 1, 2]] : [[0, 0, 1, 1]];
    let expected = 0;
    for (const [x0, y0, dx, dy] of passes) {
      const pw = Math.ceil((w - x0) / dx), ph = Math.ceil((h - y0) / dy);
      if (pw > 0 && ph > 0) expected += ph * (rowBytes(pw) + 1);
    }
    const raw = inflate(z.subarray(2), expected);   // skip the zlib header
    const rgba = new Uint8Array(w * h * 4);
    let rp = 0;
    for (const [x0, y0, dx, dy] of passes) {
      const pw = Math.ceil((w - x0) / dx), ph = Math.ceil((h - y0) / dy);
      if (pw <= 0 || ph <= 0) continue;
      const rb = rowBytes(pw);
      let prev = new Uint8Array(rb), cur = new Uint8Array(rb);
      for (let y = 0; y < ph; y++) {
        const f = raw[rp++];
        cur.set(raw.subarray(rp, rp + rb));
        rp += rb;
        for (let i = 0; i < rb; i++) {
          const a = i >= bpp ? cur[i - bpp] : 0, b = prev[i], c = i >= bpp ? prev[i - bpp] : 0;
          switch (f) {
            case 1: cur[i] = (cur[i] + a) & 255; break;
            case 2: cur[i] = (cur[i] + b) & 255; break;
            case 3: cur[i] = (cur[i] + ((a + b) >> 1)) & 255; break;
            case 4: cur[i] = (cur[i] + paeth(a, b, c)) & 255; break;
            default: break;
          }
        }
        const oy = y0 + y * dy;
        for (let x = 0; x < pw; x++) {
          const o = ((oy * w) + x0 + x * dx) * 4;
          let r, g, b, al = 255;
          const sample = (ch) => {
            if (depth === 8) return cur[x * channels + ch];
            if (depth === 16) return cur[(x * channels + ch) * 2];
            const bitPos = (x * channels + ch) * depth, v = (cur[bitPos >> 3] >> (8 - depth - (bitPos & 7))) & ((1 << depth) - 1);
            return ctype === 3 ? v : Math.round(v * 255 / ((1 << depth) - 1));
          };
          const sample16 = (ch) => depth === 16 ? (cur[(x * channels + ch) * 2] << 8) | cur[(x * channels + ch) * 2 + 1] : -1;
          switch (ctype) {
            case 0: r = g = b = sample(0); if (trns && sample16(0) === ((trns[0] << 8) | trns[1])) al = 0; else if (trns && depth <= 8 && r === trns[1] * (depth < 8 ? 255 / ((1 << depth) - 1) : 1)) al = 0; break;
            case 2: r = sample(0); g = sample(1); b = sample(2); if (trns && depth === 8 && r === trns[1] && g === trns[3] && b === trns[5]) al = 0; break;
            case 3: { const i = sample(0); r = palette[i * 3]; g = palette[i * 3 + 1]; b = palette[i * 3 + 2]; al = trns && i < trns.length ? trns[i] : 255; break; }
            case 4: r = g = b = sample(0); al = sample(1); break;
            case 6: r = sample(0); g = sample(1); b = sample(2); al = sample(3); break;
            default: throw new Error('bad PNG color type ' + ctype);
          }
          rgba[o] = r; rgba[o + 1] = g; rgba[o + 2] = b; rgba[o + 3] = al;
        }
        const t = prev; prev = cur; cur = t;
      }
    }
    // stb reports the file's channel count: grey=1, grey+alpha=2, rgb=3, rgba=4 (palette with tRNS = 4)
    const fileComp = ctype === 0 ? (trns ? 2 : 1) : ctype === 4 ? 2 : ctype === 2 ? (trns ? 4 : 3) : ctype === 3 ? (trns ? 4 : 3) : 4;
    return { w, h, rgba, fileComp };
  }

  function convert(rgba, n, comp) {
    if (comp === 4) return rgba;
    const out = new Uint8Array(n * comp);
    for (let i = 0; i < n; i++) {
      const r = rgba[i * 4], g = rgba[i * 4 + 1], b = rgba[i * 4 + 2], a = rgba[i * 4 + 3];
      if (comp === 3) { out[i * 3] = r; out[i * 3 + 1] = g; out[i * 3 + 2] = b; } else {
        const y = ((r * 77 + g * 150 + b * 29) >> 8);   // stbi__compute_y
        if (comp === 1) out[i] = y; else { out[i * 2] = y; out[i * 2 + 1] = a; }
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ PNG encoding (screenshots)
  const CRC = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
  function crc32(b) { let c = 0xffffffff; for (let i = 0; i < b.length; i++) c = CRC[(c ^ b[i]) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }
  function adler32(b) { let a = 1, s = 0; for (let i = 0; i < b.length; i++) { a = (a + b[i]) % 65521; s = (s + a) % 65521; } return ((s << 16) | a) >>> 0; }

  function encodePNG(px, w, h, comp, stride) {
    const ctype = [0, 0, 4, 2, 6][comp];
    const raw = new Uint8Array(h * (w * comp + 1));
    for (let y = 0; y < h; y++) {
      raw[y * (w * comp + 1)] = 0;
      raw.set(px.subarray(y * stride, y * stride + w * comp), y * (w * comp + 1) + 1);
    }
    // zlib with stored blocks (no compression): simple and fast
    const blocks = Math.ceil(raw.length / 65535) || 1;
    const z = new Uint8Array(2 + raw.length + blocks * 5 + 4);
    z[0] = 0x78; z[1] = 0x01;
    let zp = 2;
    for (let i = 0; i < blocks; i++) {
      const chunk = raw.subarray(i * 65535, Math.min(raw.length, (i + 1) * 65535));
      z[zp++] = i === blocks - 1 ? 1 : 0;
      z[zp++] = chunk.length & 255; z[zp++] = chunk.length >> 8;
      z[zp++] = ~chunk.length & 255; z[zp++] = (~chunk.length >> 8) & 255;
      z.set(chunk, zp); zp += chunk.length;
    }
    const ad = adler32(raw);
    z[zp++] = ad >>> 24; z[zp++] = (ad >>> 16) & 255; z[zp++] = (ad >>> 8) & 255; z[zp++] = ad & 255;
    const chunks = [];
    const chunk = (type, data) => {
      const c = new Uint8Array(12 + data.length), d = new DataView(c.buffer);
      d.setUint32(0, data.length);
      for (let i = 0; i < 4; i++) c[4 + i] = type.charCodeAt(i);
      c.set(data, 8);
      d.setUint32(8 + data.length, crc32(c.subarray(4, 8 + data.length)));
      chunks.push(c);
    };
    const ihdr = new Uint8Array(13), id = new DataView(ihdr.buffer);
    id.setUint32(0, w); id.setUint32(4, h); ihdr[8] = 8; ihdr[9] = ctype;
    chunk('IHDR', ihdr);
    chunk('IDAT', z.subarray(0, zp));
    chunk('IEND', new Uint8Array(0));
    const total = 8 + chunks.reduce((s, c) => s + c.length, 0);
    const out = new Uint8Array(total);
    out.set([137, 80, 78, 71, 13, 10, 26, 10]);
    let o = 8;
    for (const c of chunks) { out.set(c, o); o += c.length; }
    return out;
  }

  N.defineAll({
    stbi_load_from_memory: (buf, len, xp, yp, compp, req) => {
      const h = H();
      try {
        const img = decodePNG(new Uint8Array(h.buffer, buf, len));
        const comp = req || img.fileComp;
        const px = convert(img.rgba, img.w * img.h, comp);
        const p = h.malloc(px.length, 16);
        h.u8.set(px, p);
        h.dv.setInt32(xp, img.w, true);
        h.dv.setInt32(yp, img.h, true);
        if (compp) h.dv.setInt32(compp, img.fileComp, true);
        return p;
      } catch (e) {
        failure = String(e.message || e);
        return 0;
      }
    },
    stbi_info_from_memory: (buf, len, xp, yp, compp) => {
      const h = H(), u = new Uint8Array(h.buffer, buf, len);
      if (u[0] !== 137 || u[1] !== 80) { failure = 'not a PNG'; return false; }
      const dv = new DataView(h.buffer, buf, len);
      h.dv.setInt32(xp, dv.getUint32(16), true);
      h.dv.setInt32(yp, dv.getUint32(20), true);
      if (compp) h.dv.setInt32(compp, [1, 0, 3, 3, 2, 0, 4][u[25]] || 4, true);
      return true;
    },
    stbi_image_free: (p) => H().free(p),
    stbi_failure_reason: () => {
      if (failurePtr) H().free(failurePtr);
      failurePtr = H().newCString(failure);
      return failurePtr;
    },
    stbi_set_flip_vertically_on_load: () => {},
    stbi_write_png_to_func: (fn, ctx, w, hgt, comp, data, stride) => {
      const h = H();
      const png = encodePNG(new Uint8Array(h.buffer, data, (stride || w * comp) * hgt), w, hgt, comp, stride || w * comp);
      const p = h.malloc(png.length, 16);
      h.u8.set(png, p);
      N.callback(fn, 'v', ['p', 'p', 'i'], [ctx, p, png.length]);
      h.free(p);
      return 1;
    },
    stbir_resize_uint8_linear: (input, iw, ih, istride, output, ow, oh, ostride, layout) => {
      // bilinear resampling; channel count from stbir_pixel_layout (1..4 = STBIR_1CHANNEL..STBIR_4CHANNEL/RGBA)
      const h = H(), u = h.u8;
      const comp = layout >= 4 ? 4 : layout === 3 ? 3 : layout === 2 ? 2 : 1;
      istride = istride || iw * comp;
      ostride = ostride || ow * comp;
      for (let y = 0; y < oh; y++) {
        const sy = Math.min(ih - 1, Math.max(0, (y + 0.5) * ih / oh - 0.5)), y0 = Math.floor(sy), y1 = Math.min(ih - 1, y0 + 1), fy = sy - y0;
        for (let x = 0; x < ow; x++) {
          const sx = Math.min(iw - 1, Math.max(0, (x + 0.5) * iw / ow - 0.5)), x0 = Math.floor(sx), x1 = Math.min(iw - 1, x0 + 1), fx = sx - x0;
          for (let c = 0; c < comp; c++) {
            const a = u[input + y0 * istride + x0 * comp + c], b = u[input + y0 * istride + x1 * comp + c];
            const cc = u[input + y1 * istride + x0 * comp + c], d = u[input + y1 * istride + x1 * comp + c];
            u[output + y * ostride + x * comp + c] = Math.round((a * (1 - fx) + b * fx) * (1 - fy) + (cc * (1 - fx) + d * fx) * fy);
          }
        }
      }
      return output;
    },
  });
})();
