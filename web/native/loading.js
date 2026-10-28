// Mojang loading screen — a faithful reimplementation of
// net.minecraft.client.gui.screens.LoadingOverlay (Minecraft 26.3).
//
// Constants below were read out of the client's own bytecode:
//   LOGO_BACKGROUND_COLOR  = ARGB(255, 239, 50, 61)  -> #EF323D
//   logo box               = min(guiWidth * 0.75, guiHeight) wide, 4:1
//   logo texture           = textures/gui/title/mojangstudios.png, whose top half is
//                            the left half of the wordmark and whose bottom half is the
//                            right half (two 120x60 source regions drawn side by side)
//   progress bar           = centred at y = int(guiHeight * 0.8325), 10 px tall, the
//                            same width as the logo, 1 px white outline, 2 px inset fill
//   fade in 0.5 s (0.15 -> 1), fade out 2 s
//
// The whole screen is drawn on a 2D canvas in *GUI units* (vanilla guiScale), so the
// layout is identical at every window size and DPR.
'use strict';
(function () {
  const RED = [0xEF, 0x32, 0x3D];
  const LOGO_SOURCE = 'mojangstudios.png';

  class MojangLoadingScreen {
    constructor() {
      this.canvas = document.createElement('canvas');
      this.canvas.id = 'mc-loading';
      this.ctx = this.canvas.getContext('2d', { alpha: false });
      this.actual = 0;
      this.current = 0;
      this.fadeInStart = -1;
      this.fadeOutStart = -1;
      this.done = false;
      this.removed = false;
      this.logo = null;
      this.logoReady = false;
      this.running = false;
      this.dpr = 1;
      this.guiScale = 1;
      this.guiW = 1;
      this.guiH = 1;
      Object.assign(this.canvas.style, {
        position: 'fixed', inset: '0', width: '100%', height: '100%',
        zIndex: '2147483647', display: 'block', background: '#000',
        imageRendering: 'auto', pointerEvents: 'auto',
      });
      document.body.appendChild(this.canvas);
      this._resize();
      window.addEventListener('resize', () => this._resize());
      this._frame = this._frame.bind(this);
    }

    /** Loads and reassembles the real logo from the client resource archive. */
    async load(resources) {
      let bytes = null;
      if (resources && resources.data && resources.index) {
        const entry = resources.index['assets/minecraft/textures/gui/title/mojangstudios.png'];
        if (entry) bytes = new Uint8Array(resources.data, entry[0], entry[1]);
      }
      if (!bytes) {
        try {
          const res = await fetch(LOGO_SOURCE);
          if (res.ok) bytes = new Uint8Array(await res.arrayBuffer());
        } catch (e) { /* logo falls back to the text mark */ }
      }
      if (bytes) {
        try {
          const bmp = await createImageBitmap(new Blob([bytes], { type: 'image/png' }));
          // The 512x512 file stores the wordmark halves stacked: top half = left,
          // bottom half = right. Reassemble the 1024x256 wordmark once.
          const c = document.createElement('canvas');
          c.width = 1024; c.height = 256;
          const g = c.getContext('2d');
          g.imageSmoothingEnabled = true;
          g.imageSmoothingQuality = 'high';
          g.drawImage(bmp, 0, 0, 512, 256, 0, 0, 512, 256);
          g.drawImage(bmp, 0, 256, 512, 256, 512, 0, 512, 256);
          this.logo = c;
          this.logoReady = true;
        } catch (e) { /* keep the fallback */ }
      }
    }

    _resize() {
      this.dpr = Math.max(1, window.devicePixelRatio || 1);
      const w = Math.max(1, window.innerWidth), h = Math.max(1, window.innerHeight);
      this.canvas.width = Math.round(w * this.dpr);
      this.canvas.height = Math.round(h * this.dpr);
      // Vanilla auto GUI scale: the largest integer that keeps at least 320x240 GUI
      // units on screen (the option caps auto at 4).
      this.guiScale = Math.max(1, Math.min(4, Math.floor(Math.min(w / 320, h / 240))));
      this.guiW = Math.max(1, Math.floor(w / this.guiScale));
      this.guiH = Math.max(1, Math.floor(h / this.guiScale));
    }

    /** progress in 0..1, used exactly like ReloadInstance.getActualProgress(). */
    setProgress(p) {
      this.actual = Math.max(0, Math.min(1, p || 0));
    }

    setStatus(text) { this.status = text; }
    setError(text) { this.error = String(text || ''); }

    start() {
      if (this.running) return;
      this.running = true;
      this.fadeInStart = performance.now();
      requestAnimationFrame(this._frame);
    }

    /** Begins the vanilla 2 s fade-out; the overlay removes itself afterwards. */
    finish() {
      if (this.fadeOutStart < 0) this.fadeOutStart = performance.now();
    }

    destroy() {
      if (this.canvas.parentNode) this.canvas.parentNode.removeChild(this.canvas);
      this.removed = true;
      this.running = false;
    }

    _frame(now) {
      if (this.removed) return;
      if (this.fadeInStart < 0) this.fadeInStart = now;
      // Vanilla: currentProgress = clamp(currentProgress * 0.95 + actual * 0.05)
      this.current = Math.max(0, Math.min(1, this.current * 0.95 + this.actual * 0.05));
      this._draw(now);
      if (this.fadeOutStart >= 0 && (now - this.fadeOutStart) / 1000 >= 2) {
        this.destroy();
        return;
      }
      requestAnimationFrame(this._frame);
    }

    _draw(now) {
      const ctx = this.ctx;
      const s = this.guiScale, dpr = this.dpr;
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.fillStyle = '#000';
      ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);
      ctx.setTransform(dpr * s, 0, 0, dpr * s, 0, 0);
      ctx.imageSmoothingEnabled = true;

      const fadeOut = this.fadeOutStart >= 0 ? (now - this.fadeOutStart) / 1000 : -1;
      const fadeIn = this.fadeInStart >= 0 ? (now - this.fadeInStart) / 500 : -1;

      let bgAlpha, overlayAlpha;
      if (fadeOut >= 1) {
        bgAlpha = 1 - Math.max(0, Math.min(1, fadeOut - 1));
        overlayAlpha = 1 - Math.max(0, Math.min(1, fadeOut));
      } else if (fadeIn >= 0 && fadeIn < 1) {
        bgAlpha = Math.max(0.15, Math.min(1, fadeIn));
        overlayAlpha = Math.max(0, Math.min(1, fadeIn));
      } else {
        bgAlpha = 1;
        overlayAlpha = 1;
      }

      ctx.globalAlpha = bgAlpha;
      ctx.fillStyle = 'rgb(' + RED[0] + ',' + RED[1] + ',' + RED[2] + ')';
      ctx.fillRect(0, 0, this.guiW, this.guiH);
      ctx.globalAlpha = 1;

      // ---- logo: two source halves side by side, exactly as GuiGraphicsExtractor.blit
      const guiW = this.guiW, guiH = this.guiH;
      const d14 = Math.min(guiW * 0.75, guiH) * 0.25;   // LOGO_SCALE block
      const i16 = Math.floor(d14 * 0.5);
      const i19 = Math.floor(d14 * 4.0 * 0.5);
      const x = Math.floor(guiW / 2);
      const y = Math.floor(guiH / 2);
      const logoH = Math.floor(d14);
      ctx.globalAlpha = overlayAlpha;
      if (this.logoReady && logoH > 0) {
        // left half (source top half) and right half (source bottom half)
        ctx.drawImage(this.logo, 0, 0, 512, 256, x - i19, y - i16, i19, logoH);
        ctx.drawImage(this.logo, 512, 0, 512, 256, x, y - i16, i19, logoH);
      } else {
        // Pre-asset fallback: the wordmark drawn with the loading screen's own font.
        ctx.fillStyle = 'rgba(255,255,255,' + overlayAlpha + ')';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.font = '600 ' + Math.max(10, Math.floor(logoH * 0.52)) + 'px system-ui, sans-serif';
        ctx.fillText('MOJANG STUDIOS', x, y);
      }

      // ---- progress bar (hidden once fading out, as in vanilla: fadeOut < 1)
      if (fadeOut < 1) {
        const barY = Math.floor(guiH * 0.8325);
        const x1 = x - i19, x2 = x + i19, y1 = barY - 5, y2 = barY + 5;
        const fillW = Math.ceil((x2 - x1 - 2) * this.current);
        const white = 'rgba(255,255,255,' + overlayAlpha + ')';
        ctx.fillStyle = white;
        ctx.fillRect(x1 + 2, y1 + 2, x1 + fillW - (x1 + 2), (y2 - 2) - (y1 + 2)); // fill
        ctx.fillRect(x1 + 1, y1, (x2 - 1) - (x1 + 1), 1);                        // top
        ctx.fillRect(x1 + 1, y2 - 1, (x2 - 1) - (x1 + 1), 1);                    // bottom
        ctx.fillRect(x1, y1, 1, y2 - y1);                                        // left
        ctx.fillRect(x2 - 1, y1, 1, y2 - y1);                                    // right
      }
      ctx.globalAlpha = 1;
    }
  }

  globalThis.MojangLoadingScreen = MojangLoadingScreen;
})();
