/*
 * Browser audio backend for the Java client.
 *
 * Java's SoundEngine is patched to call window.mcAudio.playJava(...). Sounds
 * are decoded with WebAudio so they can be pitched, attenuated and panned like
 * vanilla. The OGG bank is hosted on the Hugging Face model repository because
 * Mojang's asset CDN does not send CORS headers; each sound is fetched with an
 * HTTP Range request and decoded once per session.
 *
 * The page also loads sounds.json (the vanilla event registry) and
 * sounds-index.json (path -> [offset, size] inside sounds.bin). Both are small
 * and are cached with the client build query string.
 */
(function () {
  'use strict';

  const MODEL_BASE = 'https://huggingface.co/matthewcodergamer/minecraft-web-client/resolve/main/';
  const LOCAL_SOUNDS = './assets/minecraft/sounds/';
  const script = document.currentScript;
  const build = (function () {
    try {
      return new URL(script.src, location.href).searchParams.get('v') || 'dev';
    } catch (error) {
      return 'dev';
    }
  })();
  const REGISTRY_URL = './sounds.json?v=' + encodeURIComponent(build);
  const INDEX_URL = './sounds-index.json?v=' + encodeURIComponent(build);
  const MUSIC_INDEX_URL = './music-index.json?v=' + encodeURIComponent(build);
  const ARCHIVE_URL = MODEL_BASE + 'sounds.bin?v=' + encodeURIComponent(build);
  const MOJANG_CDN = 'https://resources.download.minecraft.net/';

  // Only the first variant of each event is warmed. They cover the sounds a
  // player hears within the first seconds of a world: UI, mining, footsteps,
  // hurt and item pickup. The rest are fetched on demand.
  const PRELOAD_EVENTS = [
    'ui.button.click',
    'block.stone.break',
    'block.stone.place',
    'block.stone.step',
    'block.grass.break',
    'block.grass.place',
    'block.grass.step',
    'block.dirt.break',
    'block.dirt.step',
    'block.wood.break',
    'block.wood.place',
    'block.wood.step',
    'block.gravel.break',
    'block.gravel.step',
    'block.sand.break',
    'block.sand.step',
    'block.glass.break',
    'block.wool.break',
    'block.metal.break',
    'block.deepslate.break',
    'entity.player.hurt',
    'entity.player.attack.strong',
    'entity.player.attack.weak',
    'entity.player.big_fall',
    'entity.player.small_fall',
    'entity.item.pickup',
    'entity.experience_orb.pickup',
    'block.chest.open',
    'block.chest.close'
  ];

  function stripNamespace(name) {
    const text = String(name == null ? '' : name);
    const colon = text.indexOf(':');
    return colon >= 0 ? text.slice(colon + 1) : text;
  }

  function namespaceOf(name) {
    const text = String(name == null ? '' : name);
    const colon = text.indexOf(':');
    return colon > 0 ? text.slice(0, colon) : 'minecraft';
  }

  function finite(value, fallback) {
    const number = Number(value);
    return Number.isFinite(number) ? number : fallback;
  }

  function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  // sounds.json values are either a number or a SampledFloat object such as
  // {"type":"uniform","value":{"min":0.8,"max":1.2}}.
  function sampled(value, fallback) {
    if (typeof value === 'number' && Number.isFinite(value)) return value;
    if (!value || typeof value !== 'object') return fallback;
    const inner = value.value;
    if (typeof inner === 'number') return inner;
    if (inner && typeof inner === 'object') {
      const min = finite(inner.min, NaN);
      const max = finite(inner.max, NaN);
      if (Number.isFinite(min) && Number.isFinite(max)) {
        return min + Math.random() * Math.max(0, max - min);
      }
      if (Array.isArray(inner) && inner.length >= 2) {
        return finite(inner[0], fallback) + Math.random() * Math.max(0, finite(inner[1], 0) - finite(inner[0], 0));
      }
    }
    return fallback;
  }

  function weightOf(value) {
    if (typeof value === 'number') return Math.max(1, value);
    if (value && typeof value === 'object') return Math.max(1, finite(value.weight, 1));
    return 1;
  }

  function pickWeighted(list) {
    let total = 0;
    for (const item of list) total += weightOf(item);
    if (total <= 0) return list[0];
    let roll = Math.random() * total;
    for (const item of list) {
      roll -= weightOf(item);
      if (roll <= 0) return item;
    }
    return list[list.length - 1];
  }

  function eventName(item) {
    return typeof item === 'string' ? item : String((item && item.name) || '');
  }

  class BrowserAudio {
    constructor() {
      this.ctx = null;
      this.master = null;
      this.registry = null;
      this.index = null;
      this.musicIndex = null;
      this.buffers = new Map();
      this.loading = new Map();
      this.active = new Map();
      this.categories = new Map();
      this.preloaded = false;
      this.stats = {
        requested: 0, started: 0, failed: 0, blocked: 0, decoded: 0,
        bytes: 0, lastError: ''
      };
      this.ready = this.loadManifests();
      this.installUnlock();
      this.ready.then(() => this.report('manifests ready')).catch(() => {});
    }

    async loadManifests() {
      const [registry, index, musicIndex] = await Promise.all([
        fetch(REGISTRY_URL, { cache: 'force-cache' })
          .then((response) => response.ok ? response.json() : {})
          .catch(() => ({})),
        fetch(INDEX_URL, { cache: 'force-cache' })
          .then((response) => response.ok ? response.json() : {})
          .catch(() => ({})),
        fetch(MUSIC_INDEX_URL, { cache: 'force-cache' })
          .then((response) => response.ok ? response.json() : {})
          .catch(() => ({}))
      ]);
      this.registry = registry || {};
      this.index = index || {};
      this.musicIndex = musicIndex || {};
      if (!Object.keys(this.index).length) {
        console.warn('[audio-java] sounds-index.json missing; only bundled sounds will play');
      }
      return this;
    }

    installUnlock() {
      const unlock = () => this.unlock();
      for (const type of ['pointerdown', 'touchstart', 'keydown']) {
        addEventListener(type, unlock, { passive: true });
      }
    }

    unlock() {
      if (!this.ctx) {
        const Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx) return null;
        try {
          this.ctx = new Ctx();
        } catch (error) {
          this.stats.lastError = 'AudioContext: ' + error;
          return null;
        }
        this.master = this.ctx.createGain();
        this.master.gain.value = 1.0;
        this.master.connect(this.ctx.destination);
        this.ready.then(() => this.preload()).catch(() => {});
        this.report('unlocked');
      }
      if (this.ctx.state === 'suspended') {
        this.ctx.resume().catch(() => {});
      }
      return this.ctx;
    }

    report(reason) {
      if (window.__mc) {
        window.__mc['audio-backend'] = 'webaudio';
        window.__mc['audio-java'] = [
          this.stats.started + ' started',
          this.stats.failed + ' failed',
          this.stats.blocked + ' blocked'
        ].join(', ') + (reason ? ' (' + reason + ')' : '');
      }
    }

    async preload() {
      if (this.preloaded || !this.ctx) return;
      this.preloaded = true;
      // Warm a handful of buffers without blocking gameplay. Failures are
      // expected for events whose first variant is absent from the index.
      for (const event of PRELOAD_EVENTS) {
        try {
          const def = this.resolve(event);
          if (def) await this.bufferFor(def);
        } catch (error) {
          // On-demand playback retries and reports the real error later.
        }
      }
    }

    resolve(name, depth) {
      if (depth == null) depth = 0;
      if (depth > 6) return null;
      const raw = String(name == null ? '' : name);
      const bare = stripNamespace(raw);
      if (bare === 'intentionally_empty') {
        return { silent: true, fileKey: '', path: '', volume: 1, pitch: 1, attenuation: 16, stream: false };
      }
      const entry = this.registry && this.registry[bare];
      if (entry && Array.isArray(entry.sounds) && !entry.sounds.length) {
        // A handful of vanilla events deliberately have no variants.
        return { silent: true, fileKey: '', path: '', volume: 1, pitch: 1, attenuation: 16, stream: false };
      }
      if (entry && Array.isArray(entry.sounds) && entry.sounds.length) {
        const picked = pickWeighted(entry.sounds);
        const soundName = eventName(picked);
        if (!soundName) return null;
        const soundBare = stripNamespace(soundName);
        const ns = namespaceOf(soundName);
        const fileKey = ns + '/sounds/' + soundBare + '.ogg';
        if (this.index && this.index[fileKey]) {
          return {
            fileKey: fileKey,
            path: soundBare,
            volume: sampled(picked && picked.volume, 1),
            pitch: sampled(picked && picked.pitch, 1),
            attenuation: finite(picked && picked.attenuation_distance, 16),
            stream: !!(picked && picked.stream) || soundBare.startsWith('music/')
          };
        }
        // sounds.json events can alias another event (e.g. parrot imitations).
        if (this.registry[soundBare]) return this.resolve(soundName, depth + 1);
        return {
          fileKey: fileKey,
          path: soundBare,
          volume: sampled(picked && picked.volume, 1),
          pitch: sampled(picked && picked.pitch, 1),
          attenuation: finite(picked && picked.attenuation_distance, 16),
          stream: !!(picked && picked.stream) || soundBare.startsWith('music/')
        };
      }
      const ns = namespaceOf(raw);
      const path = stripNamespace(raw);
      return {
        fileKey: ns + '/sounds/' + path + '.ogg',
        path: path,
        volume: 1,
        pitch: 1,
        attenuation: 16,
        stream: path.startsWith('music/')
      };
    }

    bufferFor(def) {
      const key = def.fileKey;
      if (this.buffers.has(key)) return Promise.resolve(this.buffers.get(key));
      if (this.loading.has(key)) return this.loading.get(key);
      const pending = this.loadBuffer(def)
        .then((buffer) => {
          this.buffers.set(key, buffer);
          this.loading.delete(key);
          return buffer;
        })
        .catch((error) => {
          this.loading.delete(key);
          throw error;
        });
      this.loading.set(key, pending);
      return pending;
    }

    async loadLocal(def) {
      const response = await fetch(LOCAL_SOUNDS + encodeURI(def.path) + '.ogg', { cache: 'force-cache' });
      if (!response.ok) throw new Error('missing local sound ' + def.fileKey);
      return response.arrayBuffer();
    }

    async loadBuffer(def) {
      let data;
      const entry = this.index && this.index[def.fileKey];
      if (Array.isArray(entry) && entry.length === 2) {
        const offset = Number(entry[0]);
        const size = Number(entry[1]);
        if (!(offset >= 0) || !(size > 0)) throw new Error('bad range for ' + def.fileKey);
        try {
          const response = await fetch(ARCHIVE_URL, {
            headers: { Range: 'bytes=' + offset + '-' + (offset + size - 1) },
            cache: 'force-cache'
          });
          if (!response.ok && response.status !== 206) {
            throw new Error('sound bank HTTP ' + response.status + ' for ' + def.fileKey);
          }
          let buffer = await response.arrayBuffer();
          if (buffer.byteLength > size) buffer = buffer.slice(0, size);
          if (buffer.byteLength !== size) {
            throw new Error('short sound range for ' + def.fileKey + ' (' + buffer.byteLength + '/' + size + ')');
          }
          data = buffer;
        } catch (bankError) {
          // The Space still carries a small set of vanilla OGGs; use them when
          // the range bank is unavailable (offline dev, CDN hiccup).
          try {
            data = await this.loadLocal(def);
          } catch (localError) {
            throw bankError;
          }
        }
      } else {
        // Offline / local development fallback: the Space still carries a
        // small set of vanilla OGGs next to the client.
        data = await this.loadLocal(def);
      }
      this.stats.bytes += data.byteLength;
      const decoded = await new Promise((resolve, reject) => {
        try {
          this.ctx.decodeAudioData(data.slice(0), resolve, reject);
        } catch (error) {
          reject(error);
        }
      });
      this.stats.decoded++;
      return decoded;
    }

    listener() {
      const raw = window.__mc && window.__mc['player-pos'];
      if (!raw) return null;
      const parts = String(raw).split(',');
      if (parts.length < 5) return null;
      const values = parts.map(Number);
      if (!values.every(Number.isFinite)) return null;
      return { x: values[0], y: values[1], z: values[2], yaw: values[3] };
    }

    categoryGain(source) {
      if (!source) return 1;
      return this.categories.has(source) ? this.categories.get(source) : 1;
    }

    setCategoryVolume(source, volume) {
      if (!source) return;
      const gain = clamp(finite(volume, 1), 0, 1);
      this.categories.set(source, gain);
      if (!this.ctx) return;
      for (const entry of this.active.values()) {
        if (!entry || entry.pending || entry.source !== source) continue;
        const target = entry.baseGain * gain;
        if (entry.audio) {
          entry.audio.volume = clamp(target, 0, 1);
          continue;
        }
        try {
          entry.gain.gain.setTargetAtTime(target, this.ctx.currentTime, 0.03);
        } catch (error) {
          entry.gain.gain.value = target;
        }
      }
    }

    playJava(event, volume, pitch, x, y, z, relative, source, looping, delay, id) {
      this.stats.requested++;
      const ctx = this.unlock();
      if (!ctx) {
        this.stats.blocked++;
        return false;
      }
      const key = String(id == null ? event : id);
      this.stopJava(key);
      const start = () => {
        this.start(event, volume, pitch, x, y, z, relative, source, looping, key)
          .catch((error) => this.failed(event, error));
      };
      const begin = () => {
        if (!this.ctx || this.ctx.state !== 'running') {
          this.stats.blocked++;
          return;
        }
        const ticks = Math.max(0, finite(delay, 0));
        if (ticks > 0) {
          const timer = setTimeout(() => {
            const entry = this.active.get(key);
            if (entry && entry.pending) this.active.delete(key);
            start();
          }, ticks * 50);
          this.active.set(key, { pending: true, timer: timer, source: String(source || '') });
        } else {
          start();
        }
      };
      if (ctx.state === 'running') {
        begin();
      } else {
        // The first gesture creates the context; resume() settles on the next
        // microtask. Queue the sound instead of dropping the first click.
        ctx.resume().then(begin).catch((error) => this.failed(event, error));
      }
      return true;
    }

    async start(event, volume, pitch, x, y, z, relative, source, looping, key) {
      await this.ready;
      const def = this.resolve(event);
      if (!def) throw new Error('unresolved sound ' + event);
      if (def.silent) {
        this.stats.started++;
        return;
      }
      let gainValue = clamp(finite(volume, 1), 0, 4) * clamp(def.volume, 0, 4)
        * this.categoryGain(source);
      let pan = 0;
      if (!relative) {
        const listener = this.listener();
        if (listener) {
          const dx = finite(x, listener.x) - listener.x;
          const dy = finite(y, listener.y) - listener.y;
          const dz = finite(z, listener.z) - listener.z;
          const distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
          const attenuation = Math.max(1, def.attenuation || 16);
          gainValue *= Math.max(0, 1 - distance / attenuation);
          if (distance > 0.001) {
            const bearing = Math.atan2(-dx, dz);
            const yaw = listener.yaw * Math.PI / 180;
            let relativeAngle = bearing - yaw;
            while (relativeAngle > Math.PI) relativeAngle -= Math.PI * 2;
            while (relativeAngle < -Math.PI) relativeAngle += Math.PI * 2;
            pan = clamp(-Math.sin(relativeAngle), -1, 1) * 0.75;
          }
        }
      }
      if (gainValue <= 0.0001 && !looping) {
        this.stats.started++;
        this.report('silent');
        return;
      }
      // Music and other streamed sounds use a media element. Mojang's CDN does
      // not send CORS headers, but media elements do not need CORS to play; the
      // SFX bank still uses WebAudio for pitch/pan/attenuation.
      if (def.stream) {
        this.startStream(def, event, source, looping, key, gainValue, pitch);
        return;
      }
      const buffer = await this.bufferFor(def);
      const ctx = this.ctx;
      if (!ctx || ctx.state !== 'running') {
        this.stats.blocked++;
        return;
      }
      const src = ctx.createBufferSource();
      src.buffer = buffer;
      src.loop = !!looping;
      src.playbackRate.value = clamp(finite(pitch, 1) * def.pitch, 0.25, 4);
      const gain = ctx.createGain();
      gain.gain.value = gainValue;
      let tail = gain;
      if (!relative && pan !== 0 && ctx.createStereoPanner) {
        const panner = ctx.createStereoPanner();
        panner.pan.value = pan;
        gain.connect(panner);
        tail = panner;
      }
      src.connect(gain);
      tail.connect(this.master);
      const entry = {
        src: src,
        gain: gain,
        baseGain: gainValue,
        source: String(source || ''),
        event: String(event),
        startedAt: performance.now()
      };
      this.active.set(key, entry);
      src.onended = () => {
        if (this.active.get(key) === entry) this.active.delete(key);
      };
      src.start(0);
      this.stats.started++;
      if (this.stats.started === 1) this.report('first sound');
    }

    startStream(def, event, source, looping, key, gainValue, pitch) {
      const hash = this.musicIndex && this.musicIndex[def.fileKey];
      const url = hash
        ? MOJANG_CDN + hash.slice(0, 2) + '/' + hash
        : LOCAL_SOUNDS + encodeURI(def.path) + '.ogg';
      const audio = new Audio();
      audio.preload = 'auto';
      audio.loop = !!looping;
      audio.volume = clamp(gainValue, 0, 1);
      audio.playbackRate = clamp(finite(pitch, 1) * def.pitch, 0.25, 4);
      audio.src = url;
      const entry = {
        audio: audio,
        source: String(source || ''),
        event: String(event),
        baseGain: gainValue,
        stream: true
      };
      this.active.set(key, entry);
      audio.onended = () => {
        if (this.active.get(key) === entry) this.active.delete(key);
      };
      audio.onerror = () => {
        if (this.active.get(key) === entry) this.active.delete(key);
        this.failed(event, new Error('media error ' + url));
      };
      const started = audio.play();
      if (started && started.then) {
        started.then(() => {
          this.stats.started++;
          if (this.stats.started === 1) this.report('first sound');
        }).catch((error) => {
          if (this.active.get(key) === entry) this.active.delete(key);
          this.failed(event, error);
        });
      } else {
        this.stats.started++;
      }
    }

    failed(event, error) {
      this.stats.failed++;
      this.stats.lastError = String((error && error.message) || error);
      if (this.stats.failed <= 3 || this.stats.failed % 50 === 0) {
        console.warn('[audio-java] ' + event + ': ' + this.stats.lastError);
      }
      this.report('failed');
    }

    isJavaActive(id) {
      return this.active.has(String(id));
    }

    stopJava(id) {
      const key = String(id);
      const entry = this.active.get(key);
      if (!entry) return false;
      if (entry.timer) clearTimeout(entry.timer);
      if (entry.src) {
        try {
          entry.src.stop();
        } catch (error) {
          // Already stopped.
        }
      }
      if (entry.audio) {
        try {
          entry.audio.pause();
          entry.audio.removeAttribute('src');
          entry.audio.load();
        } catch (error) {
          // Media element already torn down.
        }
      }
      this.active.delete(key);
      return true;
    }

    stopAllJava() {
      for (const key of Array.from(this.active.keys())) this.stopJava(key);
    }

    play(event, options) {
      const opts = options || {};
      return this.playJava(
        event,
        opts.volume == null ? 1 : opts.volume,
        opts.pitch == null ? 1 : opts.pitch,
        0, 0, 0, true, opts.source || 'master', false, 0,
        'ui:' + event + ':' + Math.random()
      );
    }
  }

  window.mcAudio = new BrowserAudio();
})();
