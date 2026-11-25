// The Java <-> Rust bridge.
//
// Two deployment shapes are supported by the same file:
//
//  1. Same page (play.html): Java calls mcBridge.receiveJavaSection(...) and the
//     Rust module, if attached, is called directly.
//  2. Hybrid page (index.html): the Java client runs in an off-screen iframe.
//     Chunks/palette/player messages are forwarded with postMessage to the parent,
//     which owns the visible Rust WebGPU canvas. Input travels the other way and
//     is injected into the SDL shim so the real Java input/physics path runs.
'use strict';
(function () {
  // A host page (game.html) has its own #mc-brain iframe. It may itself be
  // embedded in the website's launcher, but it is still the host for that
  // iframe and must route input to Java, not post it to the outer page.
  const hostPage = window.__mcBridgeHost === true || !!document.getElementById('mc-brain');
  const embedded = !hostPage && window.parent && window.parent !== window;
  const params = new URLSearchParams(location.search);
  const rust = { module: null };
  const pending = [];
  const state = window.__mcBridgeState = {
    embedded,
    // Java owns the visible canvas in the normal browser flow. The Rust body is
    // only a boot backdrop behind an opaque status screen, so streaming every
    // chunk/player frame into it is wasted CPU and allocation churn.
    javaPrimary: params.get('java-primary') === '1',
    javaReady: false,
    javaOwnsCanvas: false,
    bodyReady: false,
    sections: 0,
    lastPlayer: null,
    lastRotation: null,
    lastPalette: new Map(),
    progress: 0,
    status: 'Building terrain...',
    error: null,
  };

  function asI32(ids) {
    if (ids instanceof Int32Array) return ids;
    if (ArrayBuffer.isView(ids)) return new Int32Array(ids.buffer, ids.byteOffset, ids.byteLength / 4);
    return Int32Array.from(ids || []);
  }

  function postToParent(type, payload) {
    if (!embedded) return false;
    try {
      window.parent.postMessage(Object.assign({ source: 'mc-brain', type }, payload || {}), '*');
      return true;
    } catch (error) {
      console.warn('mcBridge: postMessage failed', error);
      return false;
    }
  }

  /** World/geometry traffic is only useful while the Rust body owns the canvas.
   *  Once Java's own renderer is revealed, stop cloning chunks and player state
   *  across the iframe every frame. Progress/error/ready messages still flow. */
  function postWorldToParent(type, payload) {
    if (!embedded || state.javaPrimary || state.javaOwnsCanvas || window.__mcJavaOwnsCanvas) return false;
    return postToParent(type, payload);
  }

  function callRust(name, args) {
    const fn = rust.module && rust.module[name];
    if (typeof fn !== 'function') return false;
    try {
      fn.apply(rust.module, args);
      return true;
    } catch (error) {
      console.error('mcBridge: Rust ' + name + ' failed', error);
      return false;
    }
  }

  function attachRust(module) {
    rust.module = module;
    state.module = module;
    state.bodyReady = true;
    // Flush anything Java sent before the wasm module finished loading.
    for (const item of pending.splice(0)) {
      callRust(item.name, item.args);
    }
    if (state.lastPlayer) callRust('set_java_player', state.lastPlayer);
    if (state.lastRotation) callRust('set_java_rotation', state.lastRotation);
    for (const [id, name] of state.lastPalette) callRust('set_block_name', [id, name]);
  }

  function receiveJavaSection(cx, cy, cz, ids) {
    const array = asI32(ids);
    if (array.length !== 4096) {
      console.warn('mcBridge: section ' + cx + ',' + cy + ',' + cz + ' has ' + array.length + ' IDs (expected 4096)');
      return false;
    }
    state.sections++;
    if (callRust('receive_java_section', [cx, cy, cz, array])) return true;
    if (embedded) {
      postWorldToParent('section', { cx, cy, cz, ids: array });
      return true;
    }
    pending.push({ name: 'receive_java_section', args: [cx, cy, cz, array] });
    return false;
  }

  function receiveJavaChunk(cx, cz, ids) {
    return receiveJavaSection(cx, 0, cz, ids);
  }

  /** Java sent one section's baked skylight/blocklight (`sky << 4 | block`). */
  /** Java's `level.getDayTime() % 24000 / 24000`: 0 sunrise, .25 noon. */
  function setTimeOfDay(fraction) {
    if (callRust('set_time_of_day', [fraction])) return true;
    if (embedded) {
      postWorldToParent('time', { fraction });
      return true;
    }
    pending.push({ name: 'set_time_of_day', args: [fraction] });
    return false;
  }

  function receiveJavaLight(cx, cy, cz, packed) {
    const bytes = packed instanceof Uint8Array
      ? packed
      : new Uint8Array(packed.buffer || packed, packed.byteOffset || 0, packed.byteLength || packed.length);
    if (bytes.length !== 4096) {
      console.warn('mcBridge: light ' + cx + ',' + cy + ',' + cz + ' has ' + bytes.length + ' bytes (expected 4096)');
      return false;
    }
    if (callRust('receive_java_light', [cx, cy, cz, bytes])) return true;
    if (embedded) {
      postWorldToParent('light', { cx, cy, cz, packed: bytes });
      return true;
    }
    pending.push({ name: 'receive_java_light', args: [cx, cy, cz, bytes] });
    return false;
  }

  /** Java changed level (new world, respawn after a dimension change, title
   *  screen): drop every section the Body still has for the previous world. */
  function resetWorld() {
    state.sections = 0;
    if (callRust('reset_world', [])) return true;
    if (embedded) {
      postWorldToParent('reset', {});
      return true;
    }
    pending.push({ name: 'reset_world', args: [] });
    return false;
  }

  function setBlockName(id, name) {
    state.lastPalette.set(id, name);
    if (callRust('set_block_name', [id, name])) return;
    if (embedded) postWorldToParent('block-name', { id, name });
    else pending.push({ name: 'set_block_name', args: [id, name] });
  }

  function setBlockMaterial(id, top, bottom, side, cutout) {
    if (callRust('set_block_material', [id, top, bottom, side, !!cutout])) return;
    if (embedded) postWorldToParent('block-material', { id, top, bottom, side, cutout: !!cutout });
  }

  function setPlayerPos(x, y, z, yaw, pitch) {
    state.lastPlayer = [x, y, z];
    if (typeof yaw === 'number' && typeof pitch === 'number') {
      state.lastRotation = [yaw, pitch];
    }
    if (callRust('set_java_player', [x, y, z])) {
      if (state.lastRotation) callRust('set_java_rotation', state.lastRotation);
      return;
    }
    if (embedded) postWorldToParent('player', { x, y, z, yaw, pitch });
  }

  /** Mouse/touch look from the visible page. Rust applies it immediately; the
   *  same delta is separately injected into Java by input.js so Java physics
   *  turns with the camera. */
  function look(dx, dy, touch) {
    state.lookDx = (state.lookDx || 0) + (dx || 0);
    state.lookDy = (state.lookDy || 0) + (dy || 0);
    callRust('apply_look', [dx || 0, dy || 0, !!touch]);
  }

  function setProgress(value, status) {
    state.progress = Math.max(0, Math.min(1, value || 0));
    if (status) state.status = status;
    if (embedded) postToParent('progress', { value: state.progress, status: state.status });
    if (typeof state.onProgress === 'function') state.onProgress(state.progress, state.status);
  }

  function javaError(text) {
    state.error = String(text || 'Java client failed');
    if (embedded) postToParent('error', { text: state.error });
    if (typeof state.onError === 'function') state.onError(state.error);
  }

  /** Parent -> Java input. In the iframe this is a direct SDL injection. */
  function injectInput(kind, payload) {
    const sdl = window.__mcSDL;
    if (kind === 'key') {
      if (sdl && sdl.injectKey) sdl.injectKey(payload.code, !!payload.down);
    } else if (kind === 'look') {
      if (sdl && sdl.injectMouse) sdl.injectMouse(payload.dx || 0, payload.dy || 0);
    } else if (kind === 'button') {
      if (sdl && sdl.injectButton) sdl.injectButton(payload.button || 0, !!payload.down);
    } else if (kind === 'wheel') {
      if (sdl && sdl.injectWheel) sdl.injectWheel(payload.dx || 0, payload.dy || 0);
    } else if (kind === 'move') {
      // Analog stick, -1..1 on both axes. The SDL shim maps it onto the same
      // WASD key state Java reads, so the real physics path runs unchanged.
      if (sdl && sdl.injectMove) sdl.injectMove(payload.x || 0, payload.y || 0);
    } else if (kind === 'slot') {
      // Java has no analog hotbar; selecting slot n is exactly pressing Digit n.
      if (sdl && sdl.injectSlot) sdl.injectSlot(payload.index || 0);
    } else if (kind === 'text') {
      // Chat / sign / anvil fields: SDL_EVENT_TEXT_INPUT is what Minecraft reads.
      if (sdl && sdl.injectText) sdl.injectText(String(payload.text || ''));
    }
  }

  /** Renderer-only fallback: the deployed old_mcweb page has no SDL shim and
   *  its Java iframe is about:blank, so keys/stick go straight into Rust's
   *  local walking controller instead of disappearing. */
  function injectRendererInput(kind, payload) {
    const module = rust.module;
    if (!module) return false;
    if (kind === 'key') {
      if (typeof module.set_virtual_key === 'function') {
        module.set_virtual_key(payload.code, !!payload.down);
        return true;
      }
    } else if (kind === 'move') {
      if (typeof module.set_virtual_stick === 'function') {
        module.set_virtual_stick(payload.x || 0, payload.y || 0);
        return true;
      }
    }
    return false;
  }

  function sendInput(kind, payload) {
    if (embedded) {
      postToParent('input', { kind, payload });
      return;
    }
    // Same-document Java page.
    if (window.__mcSDL) {
      injectInput(kind, payload);
      return;
    }
    // Hybrid parent: forward to the Java brain iframe. `javaReady` is emitted
    // when the client finishes loading, so title-screen clicks and touch
    // controls reach Java even in Java-primary mode, where no Rust sections
    // are streamed. If Java never becomes ready, the Rust fallback still runs.
    const iframe = document.getElementById('mc-brain');
    const javaAlive = !!(iframe && iframe.contentWindow && state.javaReady);
    if (javaAlive) {
      iframe.contentWindow.postMessage({ source: 'mc-host', type: 'input', kind, payload }, '*');
      return;
    }
    injectRendererInput(kind, payload);
  }

  function javaReady() {
    state.javaReady = true;
    if (embedded) {
      postToParent('ready', {});
      return;
    }
    window.__mcJavaReady = true;
    if (typeof state.onJavaReady === 'function') state.onJavaReady();
  }

  function bodyReady() {
    state.bodyReady = true;
    if (typeof state.onBodyReady === 'function') state.onBodyReady();
  }

  /* ------------------------------------------------------------------ assets
     Range-chunked downloader for client-textures.bin / minecraft-java-edition-v26.3-assets.bin.

     iOS Safari is the reason this exists. A single 32 MB `arrayBuffer()` is
     exactly the shape of request that gets a backgrounded phone tab killed,
     and the old code preallocated from `content-length` — which overflows when
     the CDN rewrites the body. GitHub Pages answers `Range` with 206 and an
     accurate `Content-Range`, so we pull the archive in bounded chunks, retry
     each chunk on its own, and only allocate the final buffer once the exact
     total size is known. If a proxy ignores `Range` we transparently fall
     back to a streaming body.

     Errors carry `status`, `url` and `kind` ('http' | 'network' | 'truncated')
     so the loading screen can say 404 vs 403 vs offline instead of a bare
     "fetchArchive failed". */
  const ASSET_CHUNK = 1024 * 1024;   // 1 MiB: small enough to survive a suspend
  const ASSET_TIMEOUT = 30000;

  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  function httpError(href, response) {
    const hint = response.status === 404 ? ' (file missing from the deploy)'
      : response.status === 403 ? ' (blocked — CORS / access rule)'
      : response.status === 416 ? ' (range rejected)' : '';
    const error = new Error(href + ': HTTP ' + response.status + ' ' + response.statusText + hint);
    error.kind = 'http';
    error.status = response.status;
    error.url = href;
    return error;
  }

  async function timedFetch(url, options, ms) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), ms || ASSET_TIMEOUT);
    try {
      return await fetch(url, Object.assign({}, options, { signal: controller.signal }));
    } catch (error) {
      const wrapped = new Error(
        (error.name === 'AbortError' ? 'timed out after ' + Math.round((ms || ASSET_TIMEOUT) / 1000) + 's'
          : error.name + ': ' + error.message) + (navigator.onLine === false ? ' (offline)' : ''));
      wrapped.kind = 'network';
      wrapped.url = url;
      wrapped.cause = error;
      throw wrapped;
    } finally {
      clearTimeout(timer);
    }
  }

  /** Reads a whole Response body without ever trusting content-length. */
  async function streamBody(response, href, onProgress) {
    const total = +response.headers.get('content-length') || 0;
    if (!response.body) {
      const buffer = await response.arrayBuffer();
      onProgress && onProgress(1, buffer.byteLength, buffer.byteLength);
      return buffer;
    }
    const reader = response.body.getReader();
    const chunks = [];
    let got = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      got += value.length;
      onProgress && onProgress(total ? Math.min(1, got / total) : 0, got, total);
    }
    if (total && got < total) {
      const error = new Error(href + ': truncated (' + got + ' of ' + total + ' bytes)');
      error.kind = 'truncated';
      error.url = href;
      throw error;
    }
    const out = new Uint8Array(got);
    let offset = 0;
    for (const chunk of chunks) { out.set(chunk, offset); offset += chunk.length; }
    onProgress && onProgress(1, got, got);
    return out.buffer;
  }

  /** No readable Content-Range: read chunks until the server returns a short
   *  one. That short read is the only trustworthy end-of-file marker on a CDN
   *  that may or may not compress the same URL. */
  async function blindRanges(href, firstBody, onProgress, size) {
    const chunks = [firstBody];
    let got = firstBody.length;
    onProgress && onProgress(0, got, 0);
    while (firstBody.length >= size) {
      const start = got;
      const end = start + size - 1;
      const response = await timedFetch(href, {
        credentials: 'same-origin',
        headers: { Range: 'bytes=' + start + '-' + end },
      });
      if (response.status === 416) break;          // asked past EOF
      if (response.status !== 206) throw httpError(href, response);
      const bytes = new Uint8Array(await response.arrayBuffer());
      if (!bytes.length) break;
      chunks.push(bytes);
      got += bytes.length;
      onProgress && onProgress(0, got, 0);
      if (bytes.length < size) break;
    }
    const out = new Uint8Array(got);
    let offset = 0;
    for (const chunk of chunks) { out.set(chunk, offset); offset += chunk.length; }
    onProgress && onProgress(1, got, got);
    return out.buffer;
  }

  async function rangeDownload(href, onProgress, chunkSize) {
    const size = chunkSize || ASSET_CHUNK;
    const first = await timedFetch(href, {
      credentials: 'same-origin',
      headers: { Range: 'bytes=0-' + (size - 1) },
    });
    if (!first.ok && first.status !== 206) throw httpError(href, first);
    // 200 means the Range header was ignored (a stripping proxy, or file://).
    if (first.status !== 206) return streamBody(first, href, onProgress);

    const match = /\/(\d+)\s*$/.exec(first.headers.get('content-range') || '');
    const total = match ? +match[1] : 0;
    if (!total) return blindRanges(href, new Uint8Array(await first.arrayBuffer()), onProgress, size);

    const out = new Uint8Array(total);
    let got = 0;
    const lead = new Uint8Array(await first.arrayBuffer());
    if (lead.length > total) throw new Error(href + ': range returned more bytes than the file');
    out.set(lead, 0);
    got = lead.length;
    onProgress && onProgress(got / total, got, total);

    while (got < total) {
      const start = got;
      const end = Math.min(total, start + size) - 1;
      let response = null;
      let lastError = null;
      for (let attempt = 0; attempt < 3 && !response; attempt++) {
        try {
          const candidate = await timedFetch(href, {
            credentials: 'same-origin',
            headers: { Range: 'bytes=' + start + '-' + end },
          });
          if (candidate.status === 206) { response = candidate; break; }
          lastError = httpError(href, candidate);
          // 404/403/416 will not fix themselves.
          if (candidate.status === 404 || candidate.status === 403 || candidate.status === 416) break;
        } catch (error) {
          lastError = error;
        }
        if (attempt < 2) await sleep(250 * (attempt + 1));
      }
      if (!response) throw lastError || new Error(href + ': range ' + start + '-' + end + ' failed');

      const bytes = new Uint8Array(await response.arrayBuffer());
      const want = end - start + 1;
      if (bytes.length > want) {
        const error = new Error(href + ': range ' + start + '-' + end + ' returned too many bytes');
        error.kind = 'truncated';
        error.url = href;
        throw error;
      }
      out.set(bytes, start);
      got += bytes.length;
      onProgress && onProgress(got / total, got, total);
      // A short read is the server's real end-of-file. Content-Range can be
      // stale on a cache node; the bytes win.
      if (bytes.length < want) return out.buffer.slice(0, got);
    }
    return out.buffer;
  }

  /* ---------------------------------------------------------- artifact cache
   * The WebAssembly client is ~112 MiB and the resource archive ~62 MiB. The
   * Space redirects every request to a signed CDN URL, so the normal HTTP
   * cache cannot reuse the bytes between visits. Cache Storage can: store the
   * finished artifacts under a build-specific cache name and serve them
   * instantly next time. A failed or over-quota cache write is never fatal.
   */
  const ASSET_CACHE_PREFIX = 'mc-client-';
  let assetCacheName = null;
  let assetCachePruned = false;

  async function cacheAvailable() {
    return typeof caches !== 'undefined' && typeof caches.open === 'function';
  }

  function configureAssetCache(name) {
    if (!name) return;
    const next = ASSET_CACHE_PREFIX + String(name).replace(/[^a-zA-Z0-9._-]/g, '');
    if (next === assetCacheName) return;
    assetCacheName = next;
    if (navigator.storage && navigator.storage.persist) {
      navigator.storage.persist().catch(() => { /* best effort */ });
    }
    if (assetCachePruned) return;
    assetCachePruned = true;
    (async () => {
      if (!(await cacheAvailable())) return;
      try {
        const keys = await caches.keys();
        await Promise.all(keys
          .filter((key) => key.indexOf(ASSET_CACHE_PREFIX) === 0 && key !== assetCacheName)
          .map((key) => caches.delete(key)));
      } catch (error) {
        console.warn('asset cache prune skipped', error);
      }
    })();
  }

  async function cacheGet(href) {
    if (!assetCacheName || !(await cacheAvailable())) return null;
    try {
      const cache = await caches.open(assetCacheName);
      const hit = await cache.match(href);
      if (!hit) return null;
      return await hit.arrayBuffer();
    } catch (error) {
      console.warn('asset cache read skipped', error);
      return null;
    }
  }

  async function cachePut(href, buffer, contentType) {
    if (!assetCacheName || !(await cacheAvailable())) return false;
    try {
      if (navigator.storage && navigator.storage.estimate) {
        const estimate = await navigator.storage.estimate();
        const free = (estimate.quota || 0) - (estimate.usage || 0);
        // Leave headroom for the running client, the GPU driver and the
        // browser's own storage. A full disk is worse than a second download.
        if (free > 0 && free < buffer.byteLength + 128 * 1024 * 1024) return false;
      }
      const cache = await caches.open(assetCacheName);
      await cache.put(href, new Response(buffer, {
        headers: {
          'content-type': contentType || 'application/octet-stream',
          'content-length': String(buffer.byteLength),
        },
      }));
      return true;
    } catch (error) {
      console.warn('asset cache write skipped', error);
      return false;
    }
  }

  /**
   * Fetch a binary asset relative to the page (`./name.bin`), with resume,
   * retries and progress. `onProgress(fraction, loaded, total)`.
   *
   * Pass `cacheName` to keep the finished bytes in Cache Storage. A cache hit
   * reports 100% immediately and calls `onCacheHit(bytes)`.
   */
  async function fetchAsset(url, options) {
    const opts = options || {};
    const href = new URL(url, document.baseURI).href;
    const attempts = opts.retries == null ? 2 : opts.retries;
    const useCache = !!opts.cacheName;
    if (useCache) configureAssetCache(opts.cacheName);
    if (useCache) {
      const cached = await cacheGet(href);
      if (cached) {
        console.info('fetchAsset cache hit', { url: href, bytes: cached.byteLength });
        if (opts.onCacheHit) opts.onCacheHit(cached.byteLength);
        if (opts.onProgress) opts.onProgress(1, cached.byteLength, cached.byteLength);
        return cached;
      }
    }
    let lastError = null;
    for (let attempt = 0; attempt <= attempts; attempt++) {
      try {
        const buffer = await rangeDownload(href, opts.onProgress, opts.chunkSize);
        console.info('fetchAsset ok', { url: href, bytes: buffer.byteLength });
        if (useCache) {
          const stored = await cachePut(href, buffer, opts.contentType);
          if (stored && opts.onCached) opts.onCached(buffer.byteLength);
        }
        return buffer;
      } catch (error) {
        lastError = error;
        // A missing or forbidden file is a deploy problem, not a flaky radio.
        if (error && (error.status === 404 || error.status === 403)) break;
        console.warn('fetchAsset attempt ' + (attempt + 1) + '/' + (attempts + 1) + ' failed',
          { url: href, kind: error && error.kind, message: error && error.message });
        if (attempt < attempts) await sleep(400 * (attempt + 1));
      }
    }
    console.error('fetchAsset failed', {
      url: href,
      online: navigator.onLine !== false,
      status: lastError && lastError.status,
      kind: lastError && lastError.kind,
      message: lastError && lastError.message,
    });
    throw lastError || new Error('fetch failed: ' + href);
  }

  window.addEventListener('message', (event) => {
    if (!event.data || typeof event.data !== 'object') return;
    const data = event.data;
    if (data.source === 'mc-brain' && !embedded) {
      // Parent side: messages from the Java iframe. Once Java owns the visible
      // canvas the Rust body is paused; ignore its old world stream instead of
      // uploading sections and player state into a renderer nobody can see.
      if (window.__mcJavaOwnsCanvas
          && (data.type === 'section' || data.type === 'light' || data.type === 'time'
            || data.type === 'reset' || data.type === 'block-name'
            || data.type === 'block-material' || data.type === 'player')) {
        return;
      }
      if (data.type === 'section') {
        receiveJavaSection(data.cx, data.cy, data.cz, data.ids);
      } else if (data.type === 'light') {
        receiveJavaLight(data.cx, data.cy, data.cz, data.packed);
      } else if (data.type === 'time') {
        setTimeOfDay(data.fraction);
      } else if (data.type === 'reset') {
        resetWorld();
      } else if (data.type === 'block-name') {
        setBlockName(data.id, data.name);
      } else if (data.type === 'block-material') {
        setBlockMaterial(data.id, data.top, data.bottom, data.side, data.cutout);
      } else if (data.type === 'player') {
        setPlayerPos(data.x, data.y, data.z, data.yaw, data.pitch);
      } else if (data.type === 'progress') {
        setProgress(data.value, data.status);
      } else if (data.type === 'error') {
        javaError(data.text);
      } else if (data.type === 'ready') {
        state.javaReady = true;
        window.__mcJavaReady = true;
        if (typeof state.onJavaReady === 'function') state.onJavaReady();
      }
    } else if (data.source === 'mc-host' && embedded) {
      // Iframe side: input from the visible page.
      if (data.type === 'java-ui') {
        state.javaOwnsCanvas = true;
        return;
      }
      if (data.type === 'input') injectInput(data.kind, data.payload || {});
    }
  });

  window.mcBridge = {
    attachRust,
    receiveJavaChunk,
    receiveJavaSection,
    receiveJavaLight,
    setTimeOfDay,
    resetWorld,
    setBlockName,
    setBlockMaterial,
    setPlayerPos,
    sendInput,
    look,
    setProgress,
    javaReady,
    javaError,
    bodyReady,
    fetchAsset,
    configureAssetCache,
    state,
  };
  window.__mcBridge = window.mcBridge;
})();
