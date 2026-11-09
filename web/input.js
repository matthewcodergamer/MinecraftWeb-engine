// Desktop + mobile controls for the hybrid page.
//
// The visible canvas belongs to Rust, but movement/jumping must be resolved by
// the real Java client. Every input here is therefore forwarded twice:
//
//   * keys/buttons/wheel/analog -> `mcBridge.sendInput` -> SDL shim in Java
//   * look                      -> `mcBridge.look` (immediate Rust camera) *and*
//                                  `mcBridge.sendInput('look')` (Java yaw/pitch)
//
// Java reports its eye position and rotation every frame, so the Rust camera
// stays glued to the authoritative player even if a look delta is dropped.
//
// Touch layout is the Bedrock "Joystick & aim crosshair" scheme:
//   bottom-left  analog stick (tap the hub to toggle sneak)
//   bottom-right ATTACK (largest, corner), USE, JUMP, SNEAK
//   everywhere else: drag = look, tap = use/interact, hold = mine
'use strict';
(function () {
  const IGNORE_TAGS = /^(INPUT|TEXTAREA|SELECT)$/i;
  // The task's own detection rule, with the pointer-media fallback for iPads
  // that report no ontouchstart in desktop mode.
  const TOUCH = ('ontouchstart' in window) ||
    (navigator.maxTouchPoints || 0) > 0 ||
    matchMedia('(pointer: coarse)').matches ||
    /iPhone|iPad|iPod|Android/i.test(navigator.userAgent) ||
    new URLSearchParams(location.search).get('touch') === '1';

  const DEADZONE = 0.15;       // 15% of stick travel is noise
  const SENSITIVITY = 1.6;     // touch pixels -> degrees
  const TAP_MS = 260;          // shorter than this without movement = tap
  const TAP_SLOP = 9;          // px of movement that cancels the tap
  const HOLD_MS = 240;         // hold on the look area = start mining

  const isField = (el) => !!(el && IGNORE_TAGS.test(el.tagName || ''));

  class Input {
    constructor(canvas, options) {
      this.canvas = canvas;
      this.options = options || {};
      this.enabled = false;
      this.uiOpen = false;
      this.keys = new Set();
      this.stick = { x: 0, y: 0, pointer: null };
      this.look = { pointer: null, x: 0, y: 0 };
      this.sneaking = false;
      this.attackDown = false;
      this.lookStart = null;
      this.lookTimer = 0;
      this.pad = null;
      this._bind();
    }

    bridge() { return window.mcBridge || null; }

    setEnabled(on) {
      this.enabled = !!on;
      if (!this.enabled) this.releaseAll();
      if (this.pad) this.pad.classList.toggle('active', this.enabled);
      document.body.classList.toggle('playing', this.enabled);
    }

    /** Release every synthetic key so alt-tabbing does not leave W stuck down. */
    releaseAll() {
      for (const code of this.keys) this.sendKey(code, false);
      this.keys.clear();
      this.stick.x = this.stick.y = 0;
      this.stick.pointer = null;
      clearTimeout(this.lookTimer);
      this.look.pointer = null;
      this.lookStart = null;
      this.setAttack(false);
      this.setSneak(false);
      const bridge = this.bridge();
      if (bridge) bridge.sendInput('move', { x: 0, y: 0 });
      this.resetKnob();
    }

    sendKey(code, down) {
      const bridge = this.bridge();
      if (!bridge) return;
      bridge.sendInput('key', { code, down });
      if (down) this.keys.add(code); else this.keys.delete(code);
    }

    /** Left mouse button: Attack / Mine (hold). */
    setAttack(down) {
      if (this.attackDown === !!down) return;
      this.attackDown = !!down;
      const bridge = this.bridge();
      if (bridge) bridge.sendInput('button', { button: 0, down: !!down });
      this.flash('mc-attack', !!down);
    }

    /** Right mouse button: Use / Place / Interact (tap). */
    use() {
      const bridge = this.bridge();
      if (!bridge) return;
      bridge.sendInput('button', { button: 2, down: true });
      setTimeout(() => bridge.sendInput('button', { button: 2, down: false }), 60);
    }

    /** Sneak is a toggle on touch (Bedrock), a hold key on desktop. */
    setSneak(on) {
      const next = !!on;
      if (this.sneaking === next) return;
      this.sneaking = next;
      this.sendKey('ShiftLeft', next);
      this.flash('mc-sneak', next);
    }

    flash(id, on) {
      const el = document.getElementById(id);
      if (el) el.classList.toggle('on', !!on);
    }

    /** Inventory / chat is open: park movement and look but keep rendering. */
    setUiOpen(on) {
      this.uiOpen = !!on;
      if (this.uiOpen) {
        this.releaseAll();
        this.setAttack(false);
      }
    }

    sendLook(dx, dy, touch) {
      const bridge = this.bridge();
      if (!bridge) return;
      bridge.look(dx, dy, touch);
      bridge.sendInput('look', { dx, dy });
    }

    sendButton(button, down) {
      const bridge = this.bridge();
      if (bridge) bridge.sendInput('button', { button, down });
    }

    sendWheel(dx, dy) {
      const bridge = this.bridge();
      if (bridge) bridge.sendInput('wheel', { dx, dy });
    }

    resetKnob() {
      this.stick.x = this.stick.y = 0;
      if (this.pad) {
        this.pad.querySelectorAll('.mc-dir.on, .mc-action.on').forEach((el) => el.classList.remove('on'));
      }
      this.applyStick();
    }

    _bind() {
      // ---------------------------------------------------------- desktop
      this._onKeyDown = (event) => {
        if (!this.enabled) return;
        if (isField(event.target)) return;
        const shortcut = (event.ctrlKey || event.metaKey) &&
          (event.code === 'KeyR' || event.code === 'KeyW' || event.code === 'KeyT');
        if (!shortcut) event.preventDefault();
        if (event.repeat || this.keys.has(event.code)) return;
        // T is Java's chat key. The host owns the field; see index.html.
        if (event.code === 'KeyT' && !event.ctrlKey && !event.metaKey) {
          if (window.mcUi && window.mcUi.openChat) window.mcUi.openChat();
          return;
        }
        this.sendKey(event.code, true);
      };
      this._onKeyUp = (event) => {
        if (!this.enabled) return;
        this.keys.delete(event.code);
        this.sendKey(event.code, false);
      };
      this._onBlur = () => this.releaseAll();
      window.addEventListener('keydown', this._onKeyDown, { passive: false });
      window.addEventListener('keyup', this._onKeyUp);
      window.addEventListener('blur', this._onBlur);
      // iOS suspends pointer tracking when the tab goes away; a stuck stick
      // would keep the player walking on resume.
      document.addEventListener('visibilitychange', () => {
        if (document.hidden) this.releaseAll();
      });

      const canvas = this.canvas;
      canvas.addEventListener('mousedown', (event) => {
        if (!this.enabled || TOUCH) return;
        event.preventDefault();
        canvas.focus();
        if (document.pointerLockElement !== canvas) {
          try {
            const p = canvas.requestPointerLock({ unadjustedMovement: true });
            if (p && p.catch) p.catch(() => { try { canvas.requestPointerLock(); } catch (e) {} });
          } catch (e) { /* retried on the next click */ }
        }
        this.sendButton(event.button, true);
      });
      window.addEventListener('mouseup', (event) => {
        if (!this.enabled || TOUCH) return;
        this.sendButton(event.button, false);
      });
      window.addEventListener('mousemove', (event) => {
        if (!this.enabled || TOUCH) return;
        const locked = document.pointerLockElement === canvas;
        if (!locked && !this.attackDown) return;
        const dx = event.movementX || 0;
        const dy = event.movementY || 0;
        if (dx || dy) this.sendLook(dx, dy, false);
      });
      canvas.addEventListener('wheel', (event) => {
        if (!this.enabled) return;
        event.preventDefault();
        let dy = -event.deltaY, dx = event.deltaX;
        if (event.deltaMode === 0) { dy /= 100; dx /= 100; } else if (event.deltaMode === 2) { dy *= 3; dx *= 3; }
        this.sendWheel(dx, dy);
      }, { passive: false });
      canvas.addEventListener('contextmenu', (event) => event.preventDefault());
      document.addEventListener('pointerlockchange', () => {
        if (!this.enabled) return;
        if (document.pointerLockElement !== canvas) this.releaseAll();
      });

      if (!TOUCH) return;

      // ---------------------------------------------------------- touch
      const isControl = (target) => !!(target && target.closest &&
        target.closest('#mc-touch, #hotbar, #inventory, #mc-chat, #menu, button, input, textarea'));

      this._onPointerDown = (event) => {
        if (!this.enabled || this.uiOpen) return;
        if (event.pointerType === 'mouse') return;
        if (isField(document.activeElement)) return;
        if (isControl(event.target)) return;
        if (this.look.pointer !== null) return;
        // Android fires a compatibility mousedown too; kill scrolling/zoom.
        if (event.cancelable) event.preventDefault();
        this.look.pointer = event.pointerId;
        this.look.x = event.clientX;
        this.look.y = event.clientY;
        this.lookStart = { x: event.clientX, y: event.clientY, t: performance.now(), moved: false };
        clearTimeout(this.lookTimer);
        this.lookTimer = setTimeout(() => {
          if (this.lookStart && !this.lookStart.moved) this.setAttack(true);
        }, HOLD_MS);
      };
      this._onPointerMove = (event) => {
        if (!this.enabled || this.uiOpen) return;
        if (event.pointerId !== this.look.pointer) return;
        const dx = event.clientX - this.look.x;
        const dy = event.clientY - this.look.y;
        this.look.x = event.clientX;
        this.look.y = event.clientY;
        const start = this.lookStart;
        if (start && Math.hypot(event.clientX - start.x, event.clientY - start.y) > TAP_SLOP) {
          start.moved = true;
          clearTimeout(this.lookTimer);
          this.setAttack(false);
        }
        if (dx || dy) this.sendLook(dx * SENSITIVITY, dy * SENSITIVITY, true);
      };
      this._onPointerUp = (event) => {
        if (event.pointerId !== this.look.pointer) return;
        const start = this.lookStart;
        this.look.pointer = null;
        this.lookStart = null;
        clearTimeout(this.lookTimer);
        this.setAttack(false);
        // Short tap with no drag = interact (right click). This is what lets a
        // tap anywhere on the world place a block, as Bedrock does.
        if (start && !start.moved && performance.now() - start.t < TAP_MS) this.use();
      };
      document.addEventListener('pointerdown', this._onPointerDown, { passive: false });
      document.addEventListener('pointermove', this._onPointerMove, { passive: false });
      document.addEventListener('pointerup', this._onPointerUp, { passive: true });
      document.addEventListener('pointercancel', this._onPointerUp, { passive: true });
    }

    /** Bedrock-style 4-way D-pad plus jump/crouch/fly/attack/place. */
    installPad() {
      if (!TOUCH || this.pad) return;
      const pad = document.createElement('div');
      pad.id = 'mc-touch';
      pad.innerHTML =
        '<div id="mc-dpad" role="group" aria-label="Movement">' +
        '  <button id="mc-up" class="mc-dir" type="button" aria-label="Walk forward">' +
        '    <img class="touch-art" src="ui/touch/up.png" data-idle="ui/touch/up.png" data-pressed="ui/touch/up_pressed.png" alt="" draggable="false">' +
        '  </button>' +
        '  <button id="mc-down" class="mc-dir" type="button" aria-label="Walk backward">' +
        '    <img class="touch-art" src="ui/touch/down.png" data-idle="ui/touch/down.png" data-pressed="ui/touch/down_pressed.png" alt="" draggable="false">' +
        '  </button>' +
        '  <button id="mc-left" class="mc-dir" type="button" aria-label="Strafe left">' +
        '    <img class="touch-art" src="ui/touch/left.png" data-idle="ui/touch/left.png" data-pressed="ui/touch/left_pressed.png" alt="" draggable="false">' +
        '  </button>' +
        '  <button id="mc-right" class="mc-dir" type="button" aria-label="Strafe right">' +
        '    <img class="touch-art" src="ui/touch/right.png" data-idle="ui/touch/right.png" data-pressed="ui/touch/right_pressed.png" alt="" draggable="false">' +
        '  </button>' +
        '</div>' +
        '<div id="mc-actions">' +
        '  <button id="mc-jump" class="mc-action touch-square" type="button" aria-label="Jump">' +
        '    <img class="touch-art" src="ui/touch/jump.png" data-idle="ui/touch/jump.png" data-pressed="ui/touch/jump_pressed.png" alt="" draggable="false">' +
        '  </button>' +
        '  <button id="mc-sneak" class="mc-action touch-square" type="button" aria-label="Sneak">' +
        '    <img class="touch-art" src="ui/touch/sneak.png" data-idle="ui/touch/sneak.png" data-pressed="ui/touch/sneak_pressed.png" alt="" draggable="false">' +
        '  </button>' +
        '  <button id="mc-fly" class="mc-action touch-square" type="button" aria-label="Toggle flight" aria-pressed="false">' +
        '    <img class="touch-art" src="ui/touch/flyingascend.png" data-idle="ui/touch/flyingascend.png" data-pressed="ui/touch/flyingascend_pressed.png" alt="" draggable="false">' +
        '  </button>' +
        '  <button id="mc-attack" class="mc-action touch-wide" type="button" aria-label="Attack or mine">' +
        '    <img class="touch-bg" src="ui/touch/large_button.png" data-idle="ui/touch/large_button.png" data-pressed="ui/touch/large_button_pressed.png" alt="" draggable="false">' +
        '    <svg class="touch-glyph" viewBox="0 0 24 24" aria-hidden="true"><path fill="#fff" d="M5 19l2 2 6-6 2 2 3-3-2-2 3-3-4-4-3 3-2-2-3 3 2 2-6 6z"/></svg>' +
        '  </button>' +
        '  <button id="mc-place" class="mc-action touch-wide" type="button" aria-label="Use or place block">' +
        '    <img class="touch-bg" src="ui/touch/large_button.png" data-idle="ui/touch/large_button.png" data-pressed="ui/touch/large_button_pressed.png" alt="" draggable="false">' +
        '    <svg class="touch-glyph" viewBox="0 0 24 24" aria-hidden="true"><path fill="#fff" d="M12 3l8 4.5v9L12 21l-8-4.5v-9L12 3zm0 2.2L6.3 8.4 12 11.6l5.7-3.2L12 5.2zM6 10v5.6l5 2.8v-5.6L6 10zm7 8.4l5-2.8V10l-5 2.8v5.6z"/></svg>' +
        '  </button>' +
        '</div>';
      document.body.appendChild(pad);
      document.body.classList.add('touch-ui');
      this.pad = pad;
      pad.addEventListener('contextmenu', (event) => event.preventDefault());

      // The pressed variants are tiny; warm them so the first touch does not
      // flash the idle texture while the PNG is decoded.
      ['up', 'down', 'left', 'right', 'jump', 'sneak', 'flyingascend', 'flyingdescend',
        'large_button'].forEach((name) => {
        const pre = new Image();
        pre.src = 'ui/touch/' + name + '_pressed.png';
      });

      const dpad = pad.querySelector('#mc-dpad');
      const jump = pad.querySelector('#mc-jump');
      const sneak = pad.querySelector('#mc-sneak');
      const fly = pad.querySelector('#mc-fly');
      const attack = pad.querySelector('#mc-attack');
      const place = pad.querySelector('#mc-place');

      const setPressed = (button, on) => {
        const art = button.querySelector('img.touch-art, img.touch-bg');
        if (art) {
          const next = on ? art.dataset.pressed : art.dataset.idle;
          if (next && art.getAttribute('src') !== next) art.setAttribute('src', next);
        }
        button.classList.toggle('on', on);
      };

      // Multi-touch diagonals: keep a set of held directions and derive a
      // normalised vector from them. This feeds the same `move` message the
      // analog stick used, so Java still resolves physics from WASD.
      const dirs = new Set();
      const VECTORS = {
        'mc-up': [0, 1], 'mc-down': [0, -1], 'mc-left': [-1, 0], 'mc-right': [1, 0],
      };
      const updateDirs = () => {
        let x = 0, y = 0;
        dirs.forEach((id) => { const v = VECTORS[id]; x += v[0]; y += v[1]; });
        const len = Math.hypot(x, y);
        this.stick.x = len ? x / len : 0;
        this.stick.y = len ? y / len : 0;
        this.applyStick();
      };
      dpad.querySelectorAll('.mc-dir').forEach((button) => {
        const begin = (event) => {
          if (event.cancelable) event.preventDefault();
          try { button.setPointerCapture(event.pointerId); } catch (e) {}
          dirs.add(button.id);
          setPressed(button, true);
          updateDirs();
        };
        const end = () => {
          dirs.delete(button.id);
          setPressed(button, false);
          updateDirs();
        };
        button.addEventListener('pointerdown', begin);
        button.addEventListener('pointerup', end);
        button.addEventListener('pointercancel', end);
        button.addEventListener('lostpointercapture', end);
      });

      const hold = (button, code) => {
        button.addEventListener('pointerdown', (event) => {
          if (event.cancelable) event.preventDefault();
          try { button.setPointerCapture(event.pointerId); } catch (e) {}
          setPressed(button, true);
          this.sendKey(code, true);
        });
        const up = () => {
          setPressed(button, false);
          this.sendKey(code, false);
        };
        button.addEventListener('pointerup', up);
        button.addEventListener('pointercancel', up);
        button.addEventListener('lostpointercapture', up);
      };

      hold(jump, 'Space');

      sneak.addEventListener('pointerdown', (event) => {
        if (event.cancelable) event.preventDefault();
        this.setSneak(!this.sneaking);
        setPressed(sneak, this.sneaking);
      });

      // Rust physics reads KeyF as a toggle edge, matching double-tap-space.
      const applyFlyArt = (on) => {
        const art = fly.querySelector('img.touch-art');
        art.dataset.idle = on ? 'ui/touch/flyingdescend.png' : 'ui/touch/flyingascend.png';
        art.dataset.pressed = on ? 'ui/touch/flyingdescend_pressed.png' : 'ui/touch/flyingascend_pressed.png';
        art.setAttribute('src', art.dataset.idle);
        fly.classList.toggle('on', on);
        fly.setAttribute('aria-pressed', on ? 'true' : 'false');
      };
      fly.addEventListener('pointerdown', (event) => {
        if (event.cancelable) event.preventDefault();
        const on = !fly.classList.contains('on');
        applyFlyArt(on);
        this.sendKey('KeyF', true);
        setTimeout(() => this.sendKey('KeyF', false), 100);
      });

      attack.addEventListener('pointerdown', (event) => {
        if (event.cancelable) event.preventDefault();
        try { attack.setPointerCapture(event.pointerId); } catch (e) {}
        setPressed(attack, true);
        this.setAttack(true);
      });
      const attackUp = () => {
        setPressed(attack, false);
        this.setAttack(false);
      };
      attack.addEventListener('pointerup', attackUp);
      attack.addEventListener('pointercancel', attackUp);
      attack.addEventListener('lostpointercapture', attackUp);

      place.addEventListener('pointerdown', (event) => {
        if (event.cancelable) event.preventDefault();
        setPressed(place, true);
        this.use();
      });
      const placeUp = () => setPressed(place, false);
      place.addEventListener('pointerup', placeUp);
      place.addEventListener('pointercancel', placeUp);
      place.addEventListener('lostpointercapture', placeUp);

      this.pad.classList.toggle('active', this.enabled);
      // QA: ?touch=1 shows the pad even before the world starts.
      if (new URLSearchParams(location.search).get('touch') === '1') {
        this.pad.classList.add('active');
        document.body.classList.add('touch-ui');
      }
    }

    /** Analog stick -> Java. The SDL shim turns the vector into the WASD key
     *  state Minecraft polls, so the real physics path runs unchanged. */
    applyStick() {
      const bridge = this.bridge();
      if (bridge) bridge.sendInput('move', { x: this.stick.x, y: this.stick.y });
    }
  }

  window.MCInput = {
    install(canvas, options) {
      if (window.__mcInput) window.__mcInput.setEnabled(false);
      const input = new Input(canvas, options);
      window.__mcInput = input;
      input.installPad();
      return input;
    },
    isTouch: TOUCH,
  };
})();
