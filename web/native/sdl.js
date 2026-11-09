// SDL3 for the browser: the subset of the SDL3 C API that Minecraft 26.3 calls through LWJGL
// (window, display, events, keyboard, mouse, clipboard, cursors), implemented over a <canvas> and DOM events.
// Struct offsets and constants come from web/native/layout.js (dumped from LWJGL itself).
'use strict';
(function () {
  const L = globalThis.__mcLayout, H = () => globalThis.__mcHeap, N = globalThis.__mcNative;
  const EV = L.SDLEvents, KC = L.SDLKeycode, SC = L.SDLScancode, VID = L.SDLVideo, MOUSE = L.SDLMouse;
  const params = new URLSearchParams(location.search);

  const state = {
    canvas: null,
    windowId: 1,
    title: 'Minecraft',
    flags: 0,
    queue: [],
    pendingFree: [],
    keys: null,            // SDL_GetKeyboardState array (bool per scancode)
    mod: 0,
    mouseX: 0, mouseY: 0, buttons: 0,
    relX: 0, relY: 0,
    relative: false,
    textInput: false,
    clipboard: '',
    error: 0,
    errorText: '',
    cursor: 'default',
    scale: 1,
    cssW: 0, cssH: 0,
    pixelW: 0, pixelH: 0,
    focused: true,
  };
  globalThis.__mcSDL = state;

  function setError(msg) {
    state.errorText = msg;
    if (state.error) H().free(state.error);
    state.error = H().newCString(msg);
    return false;
  }

  function now() {
    return Math.round(performance.now() * 1e6);
  }

  function push(ev) {
    ev.timestamp = now();
    state.queue.push(ev);
  }

  // ---------------------------------------------------------------- canvas & sizing
  /** Render scale: device pixels per CSS pixel. Capped on phones so an iPhone 11 can hold 30 fps. */
  function computeScale() {
    const dpr = window.devicePixelRatio || 1;
    const forced = parseFloat(params.get('scale'));
    if (forced > 0) return forced;
    const mobile = /iPhone|iPad|Android/i.test(navigator.userAgent);
    return mobile ? Math.min(dpr, 1.0) : Math.min(dpr, 2);
  }

  function resize(initial) {
    const c = state.canvas;
    if (!c) return;
    state.scale = computeScale();
    const w = Math.max(1, window.innerWidth), h = Math.max(1, window.innerHeight);
    const pw = Math.max(1, Math.round(w * state.scale)), ph = Math.max(1, Math.round(h * state.scale));
    const changed = w !== state.cssW || h !== state.cssH || pw !== state.pixelW || ph !== state.pixelH;
    state.cssW = w; state.cssH = h; state.pixelW = pw; state.pixelH = ph;
    c.style.width = w + 'px';
    c.style.height = h + 'px';
    c.width = pw;
    c.height = ph;
    if (changed && !initial) {
      push({ type: EV.SDL_EVENT_WINDOW_RESIZED, data1: w, data2: h });
      push({ type: EV.SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED, data1: pw, data2: ph });
    }
  }

  function createCanvas() {
    let c = document.getElementById('mc-canvas');
    if (!c) {
      c = document.createElement('canvas');
      c.id = 'mc-canvas';
      document.body.appendChild(c);
    }
    c.tabIndex = 0;
    Object.assign(c.style, { position: 'fixed', left: '0', top: '0', display: 'block', outline: 'none',
      touchAction: 'none', imageRendering: 'pixelated' });
    state.canvas = c;
    resize(true);
    installInput(c);
    window.addEventListener('resize', () => resize(false));
    return c;
  }

  // ---------------------------------------------------------------- keyboard
  const CODE_TO_SCANCODE = {};
  (function buildKeyMap() {
    const named = {
      Enter: 'RETURN', Escape: 'ESCAPE', Backspace: 'BACKSPACE', Tab: 'TAB', Space: 'SPACE', Minus: 'MINUS',
      Equal: 'EQUALS', BracketLeft: 'LEFTBRACKET', BracketRight: 'RIGHTBRACKET', Backslash: 'BACKSLASH',
      Semicolon: 'SEMICOLON', Quote: 'APOSTROPHE', Backquote: 'GRAVE', Comma: 'COMMA', Period: 'PERIOD',
      Slash: 'SLASH', CapsLock: 'CAPSLOCK', PrintScreen: 'PRINTSCREEN', ScrollLock: 'SCROLLLOCK', Pause: 'PAUSE',
      Insert: 'INSERT', Home: 'HOME', PageUp: 'PAGEUP', Delete: 'DELETE', End: 'END', PageDown: 'PAGEDOWN',
      ArrowRight: 'RIGHT', ArrowLeft: 'LEFT', ArrowDown: 'DOWN', ArrowUp: 'UP', NumLock: 'NUMLOCKCLEAR',
      NumpadDivide: 'KP_DIVIDE', NumpadMultiply: 'KP_MULTIPLY', NumpadSubtract: 'KP_MINUS', NumpadAdd: 'KP_PLUS',
      NumpadEnter: 'KP_ENTER', NumpadDecimal: 'KP_PERIOD', NumpadEqual: 'KP_EQUALS', IntlBackslash: 'NONUSBACKSLASH',
      ContextMenu: 'APPLICATION', ControlLeft: 'LCTRL', ShiftLeft: 'LSHIFT', AltLeft: 'LALT', MetaLeft: 'LGUI',
      ControlRight: 'RCTRL', ShiftRight: 'RSHIFT', AltRight: 'RALT', MetaRight: 'RGUI',
    };
    for (const k in named) CODE_TO_SCANCODE[k] = SC['SDL_SCANCODE_' + named[k]];
    for (let i = 0; i < 26; i++) {
      const ch = String.fromCharCode(65 + i);
      CODE_TO_SCANCODE['Key' + ch] = SC['SDL_SCANCODE_' + ch];
    }
    for (let i = 0; i < 10; i++) {
      CODE_TO_SCANCODE['Digit' + i] = SC['SDL_SCANCODE_' + i];
      CODE_TO_SCANCODE['Numpad' + i] = SC['SDL_SCANCODE_KP_' + i];
    }
    for (let i = 1; i <= 24; i++) CODE_TO_SCANCODE['F' + i] = SC['SDL_SCANCODE_F' + i];
  })();

  const SCANCODE_TO_KEY = {};
  (function buildKeycodes() {
    for (const name in SC) {
      if (!name.startsWith('SDL_SCANCODE_')) continue;
      const sc = SC[name], suffix = name.substring('SDL_SCANCODE_'.length);
      const k = KC['SDLK_' + suffix];
      SCANCODE_TO_KEY[sc] = k !== undefined ? k : (sc | KC.SDLK_SCANCODE_MASK);
    }
  })();

  const MOD_BITS = {
    ShiftLeft: KC.SDL_KMOD_LSHIFT, ShiftRight: KC.SDL_KMOD_RSHIFT, ControlLeft: KC.SDL_KMOD_LCTRL,
    ControlRight: KC.SDL_KMOD_RCTRL, AltLeft: KC.SDL_KMOD_LALT, AltRight: KC.SDL_KMOD_RALT,
    MetaLeft: KC.SDL_KMOD_LGUI, MetaRight: KC.SDL_KMOD_RGUI,
  };

  function keyboardState() {
    if (!state.keys) state.keys = H().calloc(SC.SDL_SCANCODE_COUNT || 512);
    return state.keys;
  }

  function onKey(e, down) {
    const sc = CODE_TO_SCANCODE[e.code];
    const browserShortcut = (e.ctrlKey || e.metaKey) && (e.code === 'KeyR' || e.code === 'KeyW' || e.code === 'KeyT');
    if (!browserShortcut) e.preventDefault();
    if (sc === undefined) return;
    const bit = MOD_BITS[e.code];
    if (bit) state.mod = down ? (state.mod | bit) : (state.mod & ~bit);
    state.mod = e.getModifierState && e.getModifierState('CapsLock') ? (state.mod | KC.SDL_KMOD_CAPS) : (state.mod & ~KC.SDL_KMOD_CAPS);
    H().u8[keyboardState() + sc] = down ? 1 : 0;
    push({ type: down ? EV.SDL_EVENT_KEY_DOWN : EV.SDL_EVENT_KEY_UP, scancode: sc, key: SCANCODE_TO_KEY[sc] || 0,
      mod: state.mod, down, repeat: !!e.repeat });
    if (down && state.textInput && e.key && e.key.length === 1 && !e.ctrlKey && !e.metaKey) {
      push({ type: EV.SDL_EVENT_TEXT_INPUT, text: e.key });
    }
  }

  // ---------------------------------------------------------------- mouse
  const DOM_TO_SDL_BUTTON = [MOUSE.SDL_BUTTON_LEFT, MOUSE.SDL_BUTTON_MIDDLE, MOUSE.SDL_BUTTON_RIGHT, MOUSE.SDL_BUTTON_X1, MOUSE.SDL_BUTTON_X2];

  function requestLock() {
    const c = state.canvas;
    if (!c || document.pointerLockElement === c) return;
    try {
      const p = c.requestPointerLock({ unadjustedMovement: true });
      if (p && p.catch) p.catch(() => { try { c.requestPointerLock(); } catch (e) { /* needs a user gesture */ } });
    } catch (e) { /* retried on the next click */ }
  }

  function installInput(c) {
    window.addEventListener('keydown', (e) => onKey(e, true));
    window.addEventListener('keyup', (e) => onKey(e, false));
    c.addEventListener('contextmenu', (e) => e.preventDefault());
    c.addEventListener('mousedown', (e) => {
      c.focus();
      if (state.relative) requestLock();
      const b = DOM_TO_SDL_BUTTON[e.button] || 1;
      state.buttons |= 1 << (b - 1);
      push({ type: EV.SDL_EVENT_MOUSE_BUTTON_DOWN, button: b, down: true, clicks: e.detail || 1, x: state.mouseX, y: state.mouseY });
      e.preventDefault();
    });
    window.addEventListener('mouseup', (e) => {
      const b = DOM_TO_SDL_BUTTON[e.button] || 1;
      if (!(state.buttons & (1 << (b - 1)))) return;
      state.buttons &= ~(1 << (b - 1));
      push({ type: EV.SDL_EVENT_MOUSE_BUTTON_UP, button: b, down: false, clicks: e.detail || 1, x: state.mouseX, y: state.mouseY });
    });
    window.addEventListener('mousemove', (e) => {
      const locked = document.pointerLockElement === c;
      let xrel = e.movementX || 0, yrel = e.movementY || 0;
      state.relX += xrel;
      state.relY += yrel;
      if (!locked) {
        const r = c.getBoundingClientRect();
        state.mouseX = e.clientX - r.left;
        state.mouseY = e.clientY - r.top;
      }
      push({ type: EV.SDL_EVENT_MOUSE_MOTION, state: state.buttons, x: state.mouseX, y: state.mouseY, xrel, yrel });
    });
    c.addEventListener('wheel', (e) => {
      e.preventDefault();
      let dy = -e.deltaY, dx = e.deltaX;
      if (e.deltaMode === 0) { dy /= 100; dx /= 100; } else if (e.deltaMode === 2) { dy *= 3; dx *= 3; }
      push({ type: EV.SDL_EVENT_MOUSE_WHEEL, x: dx, y: dy, direction: MOUSE.SDL_MOUSEWHEEL_NORMAL,
        mouse_x: state.mouseX, mouse_y: state.mouseY });
    }, { passive: false });
    document.addEventListener('pointerlockchange', () => {
      if (document.pointerLockElement !== c && state.relative) {
        // the user pressed Esc (the browser consumes it): tell the game, like losing focus
        push({ type: EV.SDL_EVENT_WINDOW_FOCUS_LOST });
        push({ type: EV.SDL_EVENT_WINDOW_FOCUS_GAINED });
      }
    });
    window.addEventListener('blur', () => { state.focused = false; push({ type: EV.SDL_EVENT_WINDOW_FOCUS_LOST }); });
    window.addEventListener('focus', () => { state.focused = true; push({ type: EV.SDL_EVENT_WINDOW_FOCUS_GAINED }); });
    document.addEventListener('paste', (e) => { state.clipboard = e.clipboardData.getData('text') || state.clipboard; });
    document.addEventListener('visibilitychange', () => {
      push({ type: document.hidden ? EV.SDL_EVENT_WINDOW_OCCLUDED : EV.SDL_EVENT_WINDOW_EXPOSED });
    });
  }

  // ---------------------------------------------------------------- injected input
  // The hybrid page owns the visible canvas; the Java client runs in an
  // off-screen iframe. Parent input is forwarded here and turned into the exact
  // same SDL events a real key/mouse would produce, so Minecraft's own input,
  // rotation and physics path runs unchanged.
  function injectKey(code, down) {
    const sc = CODE_TO_SCANCODE[code];
    if (sc === undefined) return false;
    const keys = keyboardState();
    const bit = MOD_BITS[code];
    if (bit) state.mod = down ? (state.mod | bit) : (state.mod & ~bit);
    H().u8[keys + sc] = down ? 1 : 0;
    push({ type: down ? EV.SDL_EVENT_KEY_DOWN : EV.SDL_EVENT_KEY_UP, scancode: sc,
      key: SCANCODE_TO_KEY[sc] || 0, mod: state.mod, down, repeat: false });
    return true;
  }

  function injectMouse(dx, dy, x, y) {
    if (!state.canvas) return false;
    if (typeof x === 'number') state.mouseX = x;
    if (typeof y === 'number') state.mouseY = y;
    state.mouseX += dx || 0;
    state.mouseY += dy || 0;
    state.relX += dx || 0;
    state.relY += dy || 0;
    push({ type: EV.SDL_EVENT_MOUSE_MOTION, state: state.buttons,
      x: state.mouseX, y: state.mouseY, xrel: dx || 0, yrel: dy || 0 });
    return true;
  }

  function injectButton(button, down) {
    if (!state.canvas) return false;
    const domButton = button === 1 || button === 2 ? button : 0;
    const b = DOM_TO_SDL_BUTTON[domButton];
    if (down) state.buttons |= 1 << (b - 1);
    else state.buttons &= ~(1 << (b - 1));
    push({ type: down ? EV.SDL_EVENT_MOUSE_BUTTON_DOWN : EV.SDL_EVENT_MOUSE_BUTTON_UP,
      button: b, down, clicks: 1, x: state.mouseX, y: state.mouseY });
    return true;
  }

  function injectWheel(dx, dy) {
    if (!state.canvas) return false;
    push({ type: EV.SDL_EVENT_MOUSE_WHEEL, x: dx || 0, y: dy || 0,
      direction: MOUSE.SDL_MOUSEWHEEL_NORMAL, mouse_x: state.mouseX, mouse_y: state.mouseY });
    return true;
  }

  // Analog stick -> the four movement keys Java actually polls. Minecraft's
  // input is digital, so a Bedrock-style stick is translated with hysteresis:
  // press at 0.35, release at 0.22. Without the gap a stick parked on the
  // threshold would chatter W on/off every pointermove.
  const MOVE_PRESS = 0.35, MOVE_RELEASE = 0.22;
  const moveHeld = { KeyW: false, KeyS: false, KeyA: false, KeyD: false };
  function injectMove(x, y) {
    const axis = (value) => {
      const codePos = value > 0, codeNeg = value < 0;
      return { pos: codePos, neg: codeNeg, mag: Math.abs(value) };
    };
    const v = axis(y || 0), h = axis(x || 0);
    const want = {
      KeyW: v.pos && v.mag > (moveHeld.KeyW ? MOVE_RELEASE : MOVE_PRESS),
      KeyS: v.neg && v.mag > (moveHeld.KeyS ? MOVE_RELEASE : MOVE_PRESS),
      KeyD: h.pos && h.mag > (moveHeld.KeyD ? MOVE_RELEASE : MOVE_PRESS),
      KeyA: h.neg && h.mag > (moveHeld.KeyA ? MOVE_RELEASE : MOVE_PRESS),
    };
    // Analog magnitude is not lost: it clamps the walk speed through the
    // standard sprint/sneak modifiers only when the stick is pushed past the
    // "full" ring. Anything inside it is a normal Java walk.
    for (const code of Object.keys(want)) {
      if (want[code] === moveHeld[code]) continue;
      moveHeld[code] = want[code];
      injectKey(code, want[code]);
    }
    return true;
  }

  // Hotbar selection is Digit1..Digit9 in Java. Press-and-release in one go so
  // the client sees the same edge a keyboard would produce.
  function injectSlot(index) {
    const n = Number(index) | 0;
    if (n < 0 || n > 8) return false;
    const code = 'Digit' + (n + 1);
    if (CODE_TO_SCANCODE[code] === undefined) return false;
    injectKey(code, true);
    injectKey(code, false);
    return true;
  }

  state.injectKey = injectKey;
  state.injectMouse = injectMouse;
  state.injectButton = injectButton;
  state.injectWheel = injectWheel;
  state.injectMove = injectMove;
  state.injectSlot = injectSlot;
  state.injectText = (text) => {
    if (state.textInput && text) push({ type: EV.SDL_EVENT_TEXT_INPUT, text: String(text) });
  };

  // ---------------------------------------------------------------- event structs
  function writeEvent(p, ev) {
    const h = H(), dv = h.dv;
    h.u8.fill(0, p, p + L.SDL_Event.SIZEOF);
    dv.setUint32(p, ev.type, true);
    h.setI64(p + 8, ev.timestamp);
    const t = ev.type;
    if (t === EV.SDL_EVENT_KEY_DOWN || t === EV.SDL_EVENT_KEY_UP) {
      const o = L.SDL_KeyboardEvent;
      dv.setUint32(p + o.WINDOWID, state.windowId, true);
      dv.setUint32(p + o.SCANCODE, ev.scancode, true);
      dv.setUint32(p + o.KEY, ev.key, true);
      dv.setUint16(p + o.MOD, ev.mod, true);
      dv.setUint8(p + o.DOWN, ev.down ? 1 : 0);
      dv.setUint8(p + o.REPEAT, ev.repeat ? 1 : 0);
    } else if (t === EV.SDL_EVENT_TEXT_INPUT) {
      const s = h.newCString(ev.text);
      state.pendingFree.push(s);
      dv.setUint32(p + L.SDL_TextInputEvent.WINDOWID, state.windowId, true);
      h.setPtr(p + L.SDL_TextInputEvent.TEXT, s);
    } else if (t === EV.SDL_EVENT_MOUSE_MOTION) {
      const o = L.SDL_MouseMotionEvent;
      dv.setUint32(p + o.WINDOWID, state.windowId, true);
      dv.setUint32(p + o.STATE, ev.state, true);
      dv.setFloat32(p + o.X, ev.x, true);
      dv.setFloat32(p + o.Y, ev.y, true);
      dv.setFloat32(p + o.XREL, ev.xrel, true);
      dv.setFloat32(p + o.YREL, ev.yrel, true);
    } else if (t === EV.SDL_EVENT_MOUSE_BUTTON_DOWN || t === EV.SDL_EVENT_MOUSE_BUTTON_UP) {
      const o = L.SDL_MouseButtonEvent;
      dv.setUint32(p + o.WINDOWID, state.windowId, true);
      dv.setUint8(p + o.BUTTON, ev.button);
      dv.setUint8(p + o.DOWN, ev.down ? 1 : 0);
      dv.setUint8(p + o.CLICKS, ev.clicks);
      dv.setFloat32(p + o.X, ev.x, true);
      dv.setFloat32(p + o.Y, ev.y, true);
    } else if (t === EV.SDL_EVENT_MOUSE_WHEEL) {
      const o = L.SDL_MouseWheelEvent;
      dv.setUint32(p + o.WINDOWID, state.windowId, true);
      dv.setFloat32(p + o.X, ev.x, true);
      dv.setFloat32(p + o.Y, ev.y, true);
      dv.setUint32(p + o.DIRECTION, ev.direction, true);
      dv.setFloat32(p + o.MOUSE_X, ev.mouse_x, true);
      dv.setFloat32(p + o.MOUSE_Y, ev.mouse_y, true);
      dv.setInt32(p + o.INTEGER_X, Math.trunc(ev.x), true);
      dv.setInt32(p + o.INTEGER_Y, Math.trunc(ev.y), true);
    } else if (t >= EV.SDL_EVENT_WINDOW_FIRST && t <= EV.SDL_EVENT_WINDOW_LAST) {
      const o = L.SDL_WindowEvent;
      dv.setUint32(p + o.WINDOWID, state.windowId, true);
      dv.setInt32(p + o.DATA1, ev.data1 || 0, true);
      dv.setInt32(p + o.DATA2, ev.data2 || 0, true);
    }
  }

  function writeDisplayMode(p) {
    const h = H(), dv = h.dv, o = L.SDL_DisplayMode;
    h.u8.fill(0, p, p + o.SIZEOF);
    const dpr = window.devicePixelRatio || 1;
    dv.setUint32(p + o.DISPLAYID, 1, true);
    dv.setUint32(p + o.FORMAT, L.SDLPixels.SDL_PIXELFORMAT_XRGB8888 || 0, true);
    dv.setInt32(p + o.W, Math.round(screen.width), true);
    dv.setInt32(p + o.H, Math.round(screen.height), true);
    dv.setFloat32(p + o.PIXEL_DENSITY, dpr, true);
    dv.setFloat32(p + o.REFRESH_RATE, 60, true);
    dv.setInt32(p + o.REFRESH_RATE_NUMERATOR, 60, true);
    dv.setInt32(p + o.REFRESH_RATE_DENOMINATOR, 1, true);
    return p;
  }

  let staticDisplayMode = 0, staticPixelFormat = 0;
  function displayMode() {
    if (!staticDisplayMode) staticDisplayMode = H().malloc(L.SDL_DisplayMode.SIZEOF, 16);
    return writeDisplayMode(staticDisplayMode);
  }

  const CURSORS = ['default', 'text', 'wait', 'crosshair', 'progress', 'nwse-resize', 'nesw-resize', 'ew-resize',
    'ns-resize', 'move', 'not-allowed', 'pointer', 'nw-resize', 'n-resize', 'ne-resize', 'e-resize', 'se-resize',
    's-resize', 'sw-resize', 'w-resize'];
  const cursorHandles = [];

  function str(s) {
    return H().newCString(s);
  }
  const cache = {};
  function cstr(key, s) {
    return cache[key] || (cache[key] = str(s));
  }

  N.defineAll({
    // init / hints / logging
    SDL_Init: () => true,
    SDL_InitSubSystem: () => true,
    SDL_Quit: () => {},
    SDL_QuitSubSystem: () => {},
    SDL_WasInit: (f) => f,
    SDL_SetAppMetadataProperty: () => true,
    SDL_SetHint: () => true,
    SDL_SetHintWithPriority: () => true,
    SDL_SetLogOutputFunction: () => {},
    SDL_SetLogPriorities: () => {},
    SDL_GetError: () => state.error || cstr('noerr', ''),
    SDL_ClearError: () => { state.errorText = ''; return true; },
    SDL_GetPlatform: () => cstr('platform', 'Emscripten'),
    SDL_GetVersion: () => 3002000,
    SDL_GetRevision: () => cstr('rev', 'browser'),
    SDL_free: (p) => H().free(p),
    SDL_malloc: (n) => H().malloc(n, 16),
    SDL_GetTicksNS: () => now(),
    SDL_GetTicks: () => Math.round(performance.now()),
    SDL_GetPerformanceCounter: () => now(),
    SDL_GetPerformanceFrequency: () => 1e9,
    SDL_OpenURL: (p) => { window.open(H().cstr(p), '_blank', 'noopener'); return true; },

    // displays
    SDL_GetPrimaryDisplay: () => 1,
    SDL_GetDisplays: (countPtr) => {
      const h = H(), p = h.malloc(8, 16);
      h.dv.setUint32(p, 1, true);
      h.dv.setUint32(p + 4, 0, true);
      if (countPtr) h.dv.setInt32(countPtr, 1, true);
      return p;
    },
    SDL_GetDisplayName: () => cstr('display', 'Browser display'),
    SDL_GetDisplayBounds: (id, rect) => {
      const dv = H().dv, o = L.SDL_Rect;
      dv.setInt32(rect + o.X, 0, true); dv.setInt32(rect + o.Y, 0, true);
      dv.setInt32(rect + o.W, screen.width, true); dv.setInt32(rect + o.H, screen.height, true);
      return true;
    },
    SDL_GetDisplayUsableBounds: (id, rect) => N.impls.SDL_GetDisplayBounds(id, rect),
    SDL_GetDisplayForWindow: () => 1,
    SDL_GetDesktopDisplayMode: () => displayMode(),
    SDL_GetCurrentDisplayMode: () => displayMode(),
    SDL_GetFullscreenDisplayModes: (id, countPtr) => {
      const h = H(), list = h.malloc(16, 16), mode = h.malloc(L.SDL_DisplayMode.SIZEOF, 16);
      writeDisplayMode(mode);
      h.setPtr(list, mode);
      h.setPtr(list + 8, 0);
      if (countPtr) h.dv.setInt32(countPtr, 1, true);
      return list;
    },
    SDL_GetClosestFullscreenDisplayMode: (id, w, hh, rate, hd, out) => { writeDisplayMode(out); return true; },
    SDL_GetDisplayContentScale: () => window.devicePixelRatio || 1,
    SDL_GetCurrentVideoDriver: () => cstr('driver', 'browser'),

    // windows
    SDL_CreateWindow: (titlePtr, w, h, flags) => {
      state.title = H().cstr(titlePtr) || 'Minecraft';
      document.title = state.title;
      state.flags = flags;
      createCanvas();
      return state.windowId;
    },
    SDL_DestroyWindow: () => {},
    SDL_GetWindowID: () => state.windowId,
    SDL_GetWindowFromID: () => state.windowId,
    SDL_GetWindowFromEvent: () => state.windowId,
    SDL_SetWindowTitle: (w, p) => { state.title = H().cstr(p); document.title = state.title; return true; },
    SDL_SetWindowIcon: () => true,
    SDL_SetWindowMinimumSize: () => true,
    SDL_SetWindowMaximumSize: () => true,
    SDL_SetWindowPosition: () => true,
    SDL_SetWindowSize: () => true,
    SDL_SetWindowBordered: () => true,
    SDL_SetWindowResizable: () => true,
    SDL_SyncWindow: () => true,
    SDL_ShowWindow: () => true,
    SDL_RaiseWindow: () => true,
    SDL_RestoreWindow: () => true,
    SDL_MaximizeWindow: () => true,
    SDL_MinimizeWindow: () => true,
    SDL_GetWindowPosition: (w, xp, yp) => {
      const dv = H().dv;
      if (xp) dv.setInt32(xp, 0, true);
      if (yp) dv.setInt32(yp, 0, true);
      return true;
    },
    SDL_GetWindowSize: (w, wp, hp) => {
      const dv = H().dv;
      if (wp) dv.setInt32(wp, state.cssW, true);
      if (hp) dv.setInt32(hp, state.cssH, true);
      return true;
    },
    SDL_GetWindowSizeInPixels: (w, wp, hp) => {
      const dv = H().dv;
      if (wp) dv.setInt32(wp, state.pixelW, true);
      if (hp) dv.setInt32(hp, state.pixelH, true);
      return true;
    },
    SDL_GetWindowPixelDensity: () => state.scale,
    SDL_GetWindowDisplayScale: () => state.scale,
    SDL_GetWindowFlags: () => {
      let f = VID.SDL_WINDOW_RESIZABLE | VID.SDL_WINDOW_HIGH_PIXEL_DENSITY;
      if (state.focused) f |= VID.SDL_WINDOW_INPUT_FOCUS | VID.SDL_WINDOW_MOUSE_FOCUS;
      if (document.fullscreenElement) f |= VID.SDL_WINDOW_FULLSCREEN;
      if (state.relative) f |= VID.SDL_WINDOW_MOUSE_RELATIVE_MODE;
      if (document.hidden) f |= VID.SDL_WINDOW_OCCLUDED;
      return f;
    },
    SDL_GetWindowFullscreenMode: () => 0,
    SDL_SetWindowFullscreenMode: () => true,
    SDL_SetWindowFullscreen: (w, on) => {
      try {
        if (on && !document.fullscreenElement) document.documentElement.requestFullscreen().catch(() => {});
        else if (!on && document.fullscreenElement) document.exitFullscreen().catch(() => {});
      } catch (e) { /* needs a user gesture */ }
      return true;
    },
    SDL_SetWindowMouseGrab: () => true,
    SDL_SetWindowKeyboardGrab: () => true,

    // events
    SDL_PumpEvents: () => {},
    SDL_FlushEvents: (min, max) => { state.queue = state.queue.filter((e) => e.type < min || e.type > max); },
    SDL_FlushEvent: (t) => { state.queue = state.queue.filter((e) => e.type !== t); },
    SDL_PollEvent: (p) => {
      if (state.pendingFree.length) {
        for (const q of state.pendingFree) H().free(q);
        state.pendingFree.length = 0;
      }
      if (!state.queue.length) return false;
      const ev = state.queue.shift();
      if (p) writeEvent(p, ev);
      return true;
    },
    SDL_HasEvents: () => state.queue.length > 0,

    // keyboard
    SDL_GetKeyboardState: (countPtr) => {
      if (countPtr) H().dv.setInt32(countPtr, SC.SDL_SCANCODE_COUNT || 512, true);
      return keyboardState();
    },
    SDL_GetModState: () => state.mod,
    SDL_GetKeyFromScancode: (sc) => SCANCODE_TO_KEY[sc] || 0,
    SDL_GetScancodeFromKey: (key) => {
      for (const sc in SCANCODE_TO_KEY) if (SCANCODE_TO_KEY[sc] === key) return +sc;
      return 0;
    },
    SDL_GetKeyName: (key) => {
      let name = '';
      if (key >= 32 && key < 127) name = String.fromCharCode(key).toUpperCase();
      else for (const k in KC) if (KC[k] === key && k.startsWith('SDLK_')) { name = k.substring(5); break; }
      return cstr('key' + key, name);
    },
    SDL_StartTextInput: () => { state.textInput = true; return true; },
    SDL_StopTextInput: () => { state.textInput = false; return true; },
    SDL_TextInputActive: () => state.textInput,
    SDL_SetTextInputArea: () => true,
    SDL_ClearComposition: () => true,

    // mouse
    SDL_GetMouseState: (xp, yp) => {
      const dv = H().dv;
      if (xp) dv.setFloat32(xp, state.mouseX, true);
      if (yp) dv.setFloat32(yp, state.mouseY, true);
      return state.buttons;
    },
    SDL_GetGlobalMouseState: (xp, yp) => N.impls.SDL_GetMouseState(xp, yp),
    SDL_GetRelativeMouseState: (xp, yp) => {
      const dv = H().dv;
      if (xp) dv.setFloat32(xp, state.relX, true);
      if (yp) dv.setFloat32(yp, state.relY, true);
      state.relX = 0;
      state.relY = 0;
      return state.buttons;
    },
    SDL_WarpMouseInWindow: (w, x, y) => { state.mouseX = x; state.mouseY = y; },
    SDL_SetWindowRelativeMouseMode: (w, on) => {
      state.relative = !!on;
      if (on) requestLock();
      else if (document.pointerLockElement) document.exitPointerLock();
      return true;
    },
    SDL_GetWindowRelativeMouseMode: () => state.relative,
    SDL_CreateSystemCursor: (id) => { cursorHandles.push(CURSORS[id] || 'default'); return cursorHandles.length; },
    SDL_GetDefaultCursor: () => { cursorHandles.push('default'); return cursorHandles.length; },
    SDL_SetCursor: (c) => { if (state.canvas) state.canvas.style.cursor = cursorHandles[c - 1] || 'default'; return true; },
    SDL_DestroyCursor: () => {},
    SDL_ShowCursor: () => true,
    SDL_HideCursor: () => true,

    // clipboard / dialogs
    SDL_GetClipboardText: () => str(state.clipboard),
    SDL_SetClipboardText: (p) => {
      state.clipboard = H().cstr(p) || '';
      if (navigator.clipboard) navigator.clipboard.writeText(state.clipboard).catch(() => {});
      return true;
    },
    SDL_HasClipboardText: () => state.clipboard.length > 0,
    SDL_ShowSimpleMessageBox: (flags, title, msg) => { console.warn(H().cstr(title) + ': ' + H().cstr(msg)); return true; },
    SDL_ShowMessageBox: (data, buttonIdPtr) => {
      const h = H(), o = L.SDL_MessageBoxData;
      console.warn('[SDL message box] ' + h.cstr(h.getPtr(data + o.TITLE)) + ': ' + h.cstr(h.getPtr(data + o.MESSAGE)));
      if (buttonIdPtr) h.dv.setInt32(buttonIdPtr, -1, true);
      return true;
    },

    // surfaces (window icon)
    SDL_CreateSurfaceFrom: (w, hh, format, pixels, pitch) => {
      const h = H(), o = L.SDL_Surface, s = h.calloc(o.SIZEOF);
      h.dv.setUint32(s + o.FORMAT, format, true);
      h.dv.setInt32(s + o.W, w, true);
      h.dv.setInt32(s + o.H, hh, true);
      h.dv.setInt32(s + o.PITCH, pitch, true);
      h.setPtr(s + o.PIXELS, pixels);
      h.dv.setInt32(s + o.REFCOUNT, 1, true);
      return s;
    },
    SDL_AddSurfaceAlternateImage: () => true,
    SDL_DestroySurface: (s) => { if (s) H().free(s); },
    SDL_GetPixelFormatDetails: (format) => {
      if (!staticPixelFormat) staticPixelFormat = H().calloc(L.SDL_PixelFormatDetails.SIZEOF);
      const dv = H().dv, o = L.SDL_PixelFormatDetails;
      dv.setUint32(staticPixelFormat + o.FORMAT, format, true);
      dv.setUint8(staticPixelFormat + o.BITS_PER_PIXEL, 32);
      dv.setUint8(staticPixelFormat + o.BYTES_PER_PIXEL, 4);
      return staticPixelFormat;
    },
  });

  // OpenGL / Vulkan window integration is never used: rendering goes through the WebGPU/WebGL2 renderpearl backends.
  N.defineAll({
    SDL_GL_LoadLibrary: () => setError('OpenGL is not available in the browser (WebGPU/WebGL2 backends are used)'),
    SDL_Vulkan_LoadLibrary: () => setError('Vulkan is not available in the browser'),
    SDL_GL_UnloadLibrary: () => {},
    SDL_Vulkan_UnloadLibrary: () => {},
  });
})();
