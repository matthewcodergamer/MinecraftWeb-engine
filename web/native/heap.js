// Emulated native heap for LWJGL (mcport.lwjgl.BrowserMemoryBackend): one ArrayBuffer; a native address
// is a byte offset into it, 0 is NULL. Allocator: 16-byte aligned blocks with a 16-byte header;
// segregated free lists for small sizes, address-ordered coalescing free list for large ones.
'use strict';
(function () {
  const HEADER = 16, MAGIC = 0x4d43484d; // 'MCHM'
  const SMALL_MAX = 4096;                 // payload sizes <= this use size classes of 16 bytes
  const PAGE = 4096;

  function create(size) {
    const buffer = new ArrayBuffer(size);
    const heap = {
      buffer,
      size,
      u8: new Uint8Array(buffer),
      i32: new Int32Array(buffer),
      f32: new Float32Array(buffer),
      dv: new DataView(buffer),
      utf8: new TextDecoder('utf-8'),
      utf8e: new TextEncoder(),
      top: 64,            // bump pointer (first 64 bytes reserved so NULL is never handed out)
      small: [],          // small[classIndex] = array of free payload addresses
      large: [],          // sorted [{addr, size}] of free large blocks (addr = block start incl. header)
      inUse: 0,
      peak: 0,
    };

    function header(block, size, cls, payload) {
      const dv = heap.dv;
      dv.setUint32(block, size, true);
      dv.setUint32(block + 4, MAGIC, true);
      dv.setUint32(block + 8, payload, true);
      dv.setUint32(block + 12, cls, true);
    }

    function bump(size) {
      const block = heap.top;
      if (block + size > heap.size) {
        throw new Error('native heap exhausted: requested ' + size + ' bytes, in use ' + heap.inUse +
          ' of ' + heap.size + ' (raise ?heapMB=)');
      }
      heap.top += size;
      return block;
    }

    function takeLarge(size) {
      const list = heap.large;
      let best = -1;
      for (let i = 0; i < list.length; i++) {
        const b = list[i];
        if (b.size >= size && (best < 0 || b.size < list[best].size)) {
          best = i;
          if (b.size === size) break;
        }
      }
      if (best < 0) return bump(size);
      const b = list[best];
      if (b.size - size >= PAGE) {
        const block = b.addr;
        b.addr += size;
        b.size -= size;
        return block;
      }
      list.splice(best, 1);
      return b.addr | 0;
    }

    function freeLarge(addr, size) {
      const list = heap.large;
      let lo = 0, hi = list.length;
      while (lo < hi) { const m = (lo + hi) >> 1; if (list[m].addr < addr) lo = m + 1; else hi = m; }
      let entry = { addr, size };
      list.splice(lo, 0, entry);
      // merge with next
      if (lo + 1 < list.length && entry.addr + entry.size === list[lo + 1].addr) {
        entry.size += list[lo + 1].size;
        list.splice(lo + 1, 1);
      }
      // merge with previous
      if (lo > 0 && list[lo - 1].addr + list[lo - 1].size === entry.addr) {
        list[lo - 1].size += entry.size;
        list.splice(lo, 1);
        entry = list[lo - 1];
        lo--;
      }
      // give the tail back to the bump allocator
      if (entry.addr + entry.size === heap.top) {
        heap.top = entry.addr;
        list.splice(lo, 1);
      }
    }

    heap.malloc = function (n, align) {
      n = Math.max(1, Math.ceil(n));
      align = align > 16 ? align : 16;
      const need = n + (align > 16 ? align : 0);
      let block, size, cls;
      if (need <= SMALL_MAX) {
        cls = ((need + 15) >> 4);         // 1..256
        size = HEADER + (cls << 4);
        const free = heap.small[cls];
        block = (free && free.length) ? free.pop() - HEADER : bump(size);
      } else {
        cls = 0;
        size = (HEADER + need + PAGE - 1) & ~(PAGE - 1);
        block = takeLarge(size);
      }
      let payload = block + HEADER;
      if (align > 16) payload = (payload + align - 1) & ~(align - 1);
      header(block, size, cls, block + HEADER);
      if (payload !== block + HEADER) {
        // aligned payload: a second header right before it points back to the real block
        header(payload - HEADER, 0, 0xFFFFFFFF, block + HEADER);
      }
      heap.inUse += size;
      if (heap.inUse > heap.peak) heap.peak = heap.inUse;
      return payload;
    };

    heap.calloc = function (n) {
      const p = heap.malloc(n, 16);
      heap.u8.fill(0, p, p + Math.ceil(n));
      return p;
    };

    function blockOf(p) {
      const dv = heap.dv;
      let h = p - HEADER;
      if (dv.getUint32(h + 4, true) !== MAGIC) throw new Error('free/realloc of invalid pointer ' + p);
      if (dv.getUint32(h + 12, true) === 0xFFFFFFFF) h = dv.getUint32(h + 8, true) - HEADER;
      return h;
    }

    heap.free = function (p) {
      if (!p) return;
      const h = blockOf(p);
      const dv = heap.dv;
      const size = dv.getUint32(h, true), cls = dv.getUint32(h + 12, true);
      dv.setUint32(h + 4, 0, true); // catch double free
      heap.inUse -= size;
      if (cls) (heap.small[cls] || (heap.small[cls] = [])).push(h + HEADER);
      else freeLarge(h, size);
    };

    heap.usable = function (p) {
      const h = blockOf(p);
      return heap.dv.getUint32(h, true) - HEADER - (p - (h + HEADER));
    };

    heap.realloc = function (p, n) {
      if (!p) return heap.malloc(n, 16);
      if (!n) { heap.free(p); return 0; }
      const old = heap.usable(p);
      if (n <= old) return p;
      const q = heap.malloc(n, 16);
      heap.u8.copyWithin(q, p, p + old);
      heap.free(p);
      return q;
    };

    // C string helpers used by the JS natives
    heap.cstr = function (p) {
      if (!p) return null;
      let e = p; const u = heap.u8;
      while (u[e] !== 0) e++;
      return heap.utf8.decode(u.subarray(p, e));
    };
    heap.newCString = function (s) {
      const bytes = heap.utf8e.encode(s);
      const p = heap.malloc(bytes.length + 1, 16);
      heap.u8.set(bytes, p);
      heap.u8[p + bytes.length] = 0;
      return p;
    };
    heap.getPtr = function (a) { return heap.dv.getUint32(a, true) + heap.dv.getUint32(a + 4, true) * 4294967296; };
    heap.setPtr = function (a, v) {
      heap.dv.setUint32(a, v >>> 0, true);
      heap.dv.setUint32(a + 4, Math.floor(v / 4294967296) >>> 0, true);
    };
    heap.getI64 = function (a) { return Number(heap.dv.getBigInt64(a, true)); };
    heap.setI64 = function (a, v) { heap.dv.setBigInt64(a, BigInt(Math.trunc(v)), true); };
    return heap;
  }

  const params = new URLSearchParams(location.search);
  const mobile = /iPhone|iPad|Android/i.test(navigator.userAgent);
  const mb = +(params.get('heapMB') || (mobile ? 384 : 768));
  globalThis.__mcHeap = create(mb * 1048576);
})();
