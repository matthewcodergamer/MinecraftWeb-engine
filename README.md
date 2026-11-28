# mc-browser-port

Running the **real Minecraft Java Edition 26.3 client** in a web browser by compiling Mojang's
unmodified client bytecode with [TeaVM](https://github.com/konsoletyper/teavm), and building a
**native Vulkan Android client** from the same Java code. See `docs/ARCHITECTURE.md` and
`docs/MIGRATION_MATRIX.md` for the older design notes.

> **Current priority:** build `26.3-20261127c` is deployed and CI-verified; the remaining browser
> gate is a real-phone FPS measurement. Native Vulkan resumes only after that. The native APK is
> not finished and no APK is shipped from this branch yet.

## Project goal (read this first)

**Primary goal:** run the real, unmodified Minecraft Java Edition 26.3 client in a browser at a
playable frame rate, verify it in CI, and deploy the exact verified artifact to both live hosts
(the Hugging Face Space and the GitHub Pages mirror). The browser must stay the real Java client:
same world generation, rendering, gameplay, GUI, and the guaranteed five-second sponsor ad.

**Second goal (only after the browser is deployed and measured on a phone):** ship a genuinely
native Vulkan Android APK built from the same Java client code, with `winit` + `wgpu` and **no
WebView**. The old WebView/Tauri APK is scrapped and must not be revived. The native client is a
platform-layer port, not a WASM→Vulkan translation: the TeaVM module imports 97 JS/DOM/WebGPU
functions.

**Non-negotiables:**

- Do not replace the Java client with a demo renderer or ship a fake APK.
- Do not remove or shorten the five-second sponsor ad.
- Do not use AdMob for the sideloaded Android build.
- Keep Minecraft rendering/gameplay correctness; performance work must not change game behavior.
- Only claim performance is fixed after CI **and** a real phone both pass.

> **AI handoff:** this file is self-contained. Read the Project goal, Status snapshot, Mobile
> behavior, Deploy runbook, and the copy-paste **Handoff prompt** at the bottom. It records the live
> URLs, commits, hashes, measured numbers, rejected approaches, and exact next moves so no prior
> conversation is needed.

## Status snapshot (2026-11-28)

| Item | State |
|---|---|
| Browser branch | `feat/client-headless-webgpu-boot` |
| Browser HEAD | code `7068c91` (touch starts at render distance 4); this README/handoff commit is the branch tip |
| Browser CI | `46146270033` **in progress** on `7068c91` (started 2026-11-28T12:45:55Z); latest completed `46090386545` on `6f6e281` succeeded: 13.3–13.6 fps, `workAvg` ~74 ms, title screenshot non-blank; baseline `46008416951` was 9.4 fps |
| Live Space | build `26.3-20261127c`; Space commit `931269de2b36`, model commit `e738a3b20293`; wasm 60,461,887 bytes, SHA-256 `bdac79c39a3c26d72104e214918fa9ec57e34d4d35d9a10bb1af0eddf6af1285` |
| Pages mirror | <https://matthewcodergamer.github.io/>; site commits `4e71d1f` (scroll lock), `4f600a8` (direct game shell), `0db7489` (docs/cache), `dde70f8` (overflow clip), `07f9977` (site docs), `cc1a184` (root lock), `d1bad5e` (iOS launcher scroll), `3026e00` (site docs); loads `/game.html?v=26.3-20261127c&host=pages2`; Pages build `built` |
| Mobile render distance | touch shell passes `rd=4` (was 6); simulation distance 4; adaptive floor 4 |
| Mobile scroll | launcher scrolls normally on iOS (verified 414×896, `scrollY` reaches 1500); during play `html`/`body` use `overflow:clip`/`touch-action:none`, `body` is `position:fixed`, and all scroll offsets stay 0 |
| Native branch | `feat/native-vulkan`, HEAD `ac90861`, not pushed, no APK |
| Next gate | finish `46146270033`, then real-phone test of the Pages/HF build; if still slow, tune initial scale/bias, not gameplay |

Run `46027524065` was not a client regression. `scripts/cdp.py` split the new title wait on the
semicolons inside its JavaScript function body, so the title step exited with
`unknown --actions step: return ...` before it launched Chrome. `b3970a1` adds a quote/bracket-aware
action splitter, moves the wait into a single function expression, stores the learned scale bias in
`localStorage`, and makes Java read that stored value back. `6f6e281` bumps every shell script and
the iframe query to `20261127c`, so the deploy gets a fresh Cache Storage bucket. Verify
`46090386545` then succeeded: FPS rose from 9.4 to 13.3–13.6 on the same SwiftShader runner,
`workAvg` fell from ~105 ms to ~74 ms, the ambient cache removed most of the `animateTick` cost,
and `title_screen.png` is now a 1,110-color image instead of a flat gray frame. The build audit
matches the `EnvironmentAttributeReader` patch line in the full build log.

> **CI caveat:** the verify workflow uses `concurrency: cancel-in-progress`, so any push touching
> `scripts/**`, `web/**`, `src/**`, `renderer/**`, `patches/**`, `tools/src/**`, or the workflow
> file **cancels the running verify job**. Batch changes and wait for the profile artifact before
> pushing. A README-only commit does not trigger the workflow.

### Recent commit log

| Commit | What changed |
|---|---|
| `9673255` | This complete README/handoff (goal, live state, mobile fix, deploy runbook, prompt) |
| `7068c91` | Touch shell passes `rd=4`; README mirror/perf notes |
| `d3aec32` | Record Pages mirror build `c` and mobile scroll lock |
| `72de903` | Record live wasm SHA-256 for build `c` |
| `6f6e281` | Bump every shell script/iframe query to build `20261127c` (fresh Cache Storage) |
| `b3970a1` | CDP `split_actions`, learned scale bias persistence, patch audit |
| `5b786ba` | Ambient cache, adaptive render scale, mobile fill budget, `gpuAvg` |
| `abd846e` | Wait for the iframe's WebGPU frame before the title screenshot |
| `6307f3a` | MonitorRegistry leak fix and bind-group plan cap |
| `0ea1e34` | Strip the Wasm name section for deploy, PERF deltas, `planTotal` fix |
| `52f1534` | Profiling and hot-path counters |
| `88119c0` | Keep the TeaVM runtime on the Space |
| `c0014ec` | GUI/sound fixes, build `20261127a` |

Pages-site commits: `4e71d1f` (scroll lock), `4f600a8` (direct game shell), `0db7489` (docs/cache
v23), `dde70f8` (`overflow:clip`), `07f9977` (site docs), `cc1a184` (lock `html` too), `d1bad5e`
(restore normal iOS launcher scrolling), `3026e00` (site docs).

## Live build

| Item | Value |
|---|---|
| Playable demo | <https://matthewcodergamer-minecraft-browser.static.hf.space/> (root `index.html` is the same shell as `game.html`) |
| GitHub Pages mirror | <https://matthewcodergamer.github.io/>; site repo `matthewcodergamer/matthewcodergamer.github.io`, tip `3026e00`; launcher loads `https://matthewcodergamer-minecraft-browser.static.hf.space/game.html?v=26.3-20261127c&host=pages2` in the fullscreen iframe |
| Visible build | `26.3-20261127c` |
| Space commit | `931269de2b361f6f8c56ebf08fce8444e6fedc47` |
| Model repo | `matthewcodergamer/minecraft-web-client` (commit `e738a3b202933a3eafa01c92693c91bb87f610ec`) |
| Wasm module (deployed) | 60,461,887 bytes (TeaVM name section stripped) |
| Wasm SHA-256 | `bdac79c39a3c26d72104e214918fa9ec57e34d4d35d9a10bb1af0eddf6af1285` |
| Wasm runtime JS | 17,338 bytes |
| Asset archive | 64,689,340 bytes, 25,671 entries (jar-only by design) |
| Audio bank | `sounds.bin`, 117,739,281 bytes, served with HTTP Range from the model repo |

The live Space root (`index.html`) is the same shell as `web/game.html` (`upload_hf.py` copies it),
and that shell embeds `web/play.html` in an iframe. The iframe runs the Java client; the parent owns
the loading screen, the sponsor slot, and the mobile controls.

## What works

- **Real Java client.** Mojang's 26.3 client bytecode bootstraps under TeaVM Wasm GC: title
  screen, Create World, Superflat world, integrated server, player, chat, language selection.
- **Java's own WebGPU renderer.** `mcport.gpu.webgpu` implements the renderpearl backend directly
  on WebGPU. This is the primary renderer on `feat/client-headless-webgpu-boot`; the Rust Body is
  no longer required for the normal browser path.
- **Rust Body fallback.** `renderer/` is a WebGPU greedy-mesher that consumes Java's world state.
  It remains useful as a boot backdrop, as a renderer-only diagnostic (`?java=0`), and for the
  Java→Rust smoke test. It never generates or mutates world state.
- **Audio.** The vanilla sound registry and OGG bank are served from Hugging Face with Range
  requests; the browser backend decodes with WebAudio. Range requests were verified end-to-end
  (`206 Partial Content`, `OggS` payloads).
- **GUI correctness.** Dynamic uniform offsets, sound resolution, and the Java boot overlay were
  fixed in `c0014ec`; the live build includes those fixes.
- **Five-second sponsor ad.** The shell keeps the guaranteed sponsor slot; do not remove it.
- **GitHub Pages mirror.** <https://matthewcodergamer.github.io/> embeds build
  `26.3-20261127c`. While the game iframe is fullscreen the body is `position:fixed` with
  `overflow:clip` and `touch-action:none`, so mobile Safari/Chrome cannot rubber-band or scroll the
  page behind the canvas.

## Mobile behavior (verified 2026-11-28)

The GitHub Pages launcher and the Hugging Face game shell are separate documents, so each needs its
own scrolling rules:

- **Pages launcher** (`matthewcodergamer.github.io`). The document itself must scroll on phones:
  `html{height:100%}` and `html,body{min-height:100%}`, with horizontal clipping moved to `body`
  (`overflow-x:hidden; overflow-x:clip`). The old `height:100%; overflow-x:hidden` on both made
  `body` the scroll container, so iOS Safari clipped the long guide instead of scrolling it.
  Verified live at 414×896 DPR 2: `documentElement.scrollHeight=17,692` and
  `window.scrollTo(0,1500)` reaches `scrollY=1500`.
- **Game lock.** When Play opens the game, both `html.game-on` and `body.game-on` get
  `overflow:hidden; overflow:clip; overscroll-behavior:none; touch-action:none`; `body` also
  becomes `position:fixed; inset:0; width:100%; height:100%`, and `#game-frame` fills the viewport
  at `100dvh`. `overflow:clip` (not only `hidden`) is required because `hidden` still permits
  programmatic `scrollTop` changes. Verified live: `scrollY=0`, `htmlTop=0`, and `bodyTop=0` even
  after assigning 1500.
- **HF game shell** (`/game.html`, also copied to `/index.html`). `html,body` are `height:100%`
  with `overflow:hidden`, and the Java canvas is a fixed full-viewport layer. The earlier test
  confirmed `body`/`html` scrollTop stay 0 while the game runs.
- **Touch performance defaults.** The shell passes `rd=4` for touch devices (it used to pass 6,
  which overrode Java's mobile fallback). Java then applies render distance 4 and simulation
  distance 4, fast graphics, and an adaptive scale that walks down to 0.6 when FPS stays below 28.
  The learned scale is persisted in `localStorage` as `mc_scale_bias_v1`.
- **Mobile framebuffer budget.** `web/native/sdl.js` caps mobile at the smaller of DPR 1.0 and a
  1.6 MP budget (`sqrt(1_600_000/(w*h))`), then applies the adaptive bias. This avoids spending
  the first ~20 s rendering a 3.7 MP 1440p framebuffer while the bias walks down.
- **Stale shell protection.** The Pages service worker (`/root/site/sw.js`, `mw-site-v22`) is
  network-first for navigations, so a normal reload gets the new launcher. The HF shell and module
  are versioned by `MC_BUILD`/`?v=` so Cache Storage cannot mix builds.

## Architecture

| Layer | Path | Role |
|---|---|---|
| Java client | Minecraft 26.3 jar + `src/compat/java` | Unmodified game logic, compiled to Wasm GC |
| Browser runtime | `src/compat/java/mcport/rt` | Fibers, frame loop, diagnostics, frame profiler |
| WebGPU backend | `src/compat/java/mcport/gpu/webgpu`, `web/gpu/webgpu.js` | Renderpearl → WebGPU, bind-group/plan caches, transient upload ring |
| Java↔Rust bridge | `src/compat/java/mcport/bridge`, `web/bridge.js` | World sections, palette, player state, input |
| Rust Body | `renderer/` | Optional WebGPU fallback renderer and boot backdrop |
| Production shell | `web/index.html`, `web/play.html`, `web/game.html` | Loading UI, iframe, QA/test entry points |
| Native client | not started | winit + wgpu NativeActivity, no WebView; build the platform layer |

The TeaVM module imports **97 JavaScript/DOM/WebGPU functions**. There is **no direct
WASM→Vulkan translator** and no plan to add one: the native client needs a real platform layer
(window, input, GPU, audio), not a binary translation step.

## Current performance state

The latest profile is verify **46090386545** (commit `6f6e281`, build `20261127c`) on GitHub's
x86_64 runner with **SwiftShader** at 1280×577. It includes the ambient cache, adaptive scale,
`gpuAvg`, and the corrected title wait. The previous profile **46008416951** (`abd846e`) is the
baseline. The title/Create World test drives the real Java renderer; the smoke test is the
separate Rust Body path.

| Metric | Latest (build c) | Previous (build b) | Meaning |
|---|---|---|---|
| Presented FPS | 13.3–13.6 | 9.4 | Software WebGPU; not a real-GPU number |
| `workAvg` | ~74 ms | 105 ms | Java frame wall time, including GPU fence waits |
| `gpuMs` | ~220 ms | n/a | Queue latency; above the frame interval, so SwiftShader is the limiter |
| `presentAvg` | 0.00 ms | 0.00 ms | `WG.present()` only; GPU work is asynchronous |
| JS heap | 1.73–1.75 GB | 1.72–1.74 GB | Stable; limit 4.19 GB |
| Live textures / views | 711 / 727 | 711 / 727 | Stable |
| Live buffers | 103 | 103 | Stable |
| Pipelines | 199 | 199 | Created during load |
| Draws | ~250/frame | ~260/frame | 478,116 over 1,910 frames |
| Render passes | ~10.5/frame | ~11/frame | 20,153 over 1,910 frames |
| Plans | 1,273 steady | 1,272 steady | 9,391 misses / 8,118 evictions during startup only |

### What the latest run proved

1. **The ambient cache works.** Inclusive `ClientLevel.animateTick` fell from 14.5% to 5.7%;
   `EnvironmentAttributeReader.getValue` fell from 9.7% to below 3.4%; the 4.9%
   `BiomeManager.getNoiseBiomeAtQuart` path fell with it. `Minecraft.tick` inclusive fell from
   16.7% to 7.9%. FPS rose 41% and `workAvg` fell 30% on the same software GPU.
2. **The title screenshot is finally real.** `title_screen.png` is 77,713 bytes with 1,110 unique
   colors (was a 16,751-byte flat gray frame). The parser fix plus the `TitleScreen` +
   `loadFinished=true` + `frame > 10` + 5 s wait fixed the capture.
3. **The monitor leak and plan churn stay fixed.** Heap is flat at 1.73–1.75 GB; `locks=0`;
   `planEvictions` stays at 8,118 and `planTotal` at 1,273 after startup.
4. **SwiftShader is now the wall.** `gpuMs` is ~220 ms against a ~74 ms frame interval, so the
   software rasterizer is the remaining CI bottleneck. The CPU is 73.4% idle self-time; the real
   phone test is still the missing measurement.

### CPU profile findings (20,726 samples over 26.7 s, build c)

- 73.4% idle, 5.3% garbage collector, 2.5% native program code.
- 5.7% `ClientLevel.animateTick` / `doAnimateTick`; `EnvironmentAttributeReader.getValue` is no
  longer in the top inclusive list (below 3.4%, down from 9.7%).
- `Minecraft.tick` inclusive 7.9% (down from 16.7%); integrated server 4.0% (down from 5.4%).
- `gpuMs` ~220 ms and `workAvg` ~74 ms show the queue is the limiter on SwiftShader.

The ambient-particle cache is the optimization that moved the CPU numbers. The remaining work is
GPU/fill-rate, which the adaptive scale and mobile budget address on real devices.

### Verified optimization batch (`6f6e281`, build `20261127c`)

1. **Per-quart ambient-particle cache** (`EnvironmentAttributePatches`). `AMBIENT_PARTICLES` is
   cached per quart cell for about one second; every other attribute and reader falls through to
   the original three-argument implementation. The one-second clock check runs once per 256
   lookups so it does not add a JS boundary crossing to every sample. It cut the profile's
   `animateTick` inclusive time from 14.5% to 5.7% without changing gameplay or particle rates.
2. **Adaptive render scale.** Once render distance is already at the floor of 4, a device below
   28 FPS steps the canvas backing store down by 10% every 5 s (floor 0.6), and steps it back up
   after 30 s above 52 FPS. CSS keeps the canvas full-screen. This is the direct lever for a phone
   GPU that cannot fill 1080p; it is exposed as `__mcScaleBias` and triggered through the existing
   SDL resize path.
3. **Mobile defaults and fill budget.** The shell now passes render distance **4** on touch (it
   used to pass 6, so Java's mobile fallback of 4 never applied), simulation distance is 4, and
   the mobile canvas is capped at CSS resolution (scale ≤ 1.0) and a 1.6 MP framebuffer budget
   instead of full DPR. A 1440p phone would otherwise render 3.7 MP for the first ~20 s while the
   adaptive bias walked down; this makes the first frame playable. The learned bias is stored in
   `localStorage` (`mc_scale_bias_v1`), and both `computeScale` and Java's `currentScaleBias()`
   read it back, so a returning player starts at the scale that worked last time instead of
   re-learning it.
4. **GPU queue timing.** `gpuAvg` measures `queue.onSubmittedWorkDone()` once per frame (one probe
   in flight), and the snapshot now reports the adapter name and fallback flag. `cdp.py` prints
   `gpuMs` on every SOAK line, so the next run distinguishes a GPU-bound frame from a CPU-bound
   one instead of inferring it from idle samples.
5. **CDP action parsing and patch audit.** `split_actions` keeps semicolons inside quoted strings
   and bracket depth, so a multi-statement JS wait no longer becomes bogus `--actions` steps. The
   build audit now matches `PATCH_RULE_MATCH net.minecraft.world.attribute.EnvironmentAttributeReader`
   in the full build log; the profile above independently confirms the cache is active. All five
   items are live in build `20261127c`.

Run a profiled soak locally (Chrome must expose a WebGPU adapter):

```sh
CHROME=/usr/bin/google-chrome python3 scripts/cdp.py \
  "http://127.0.0.1:8766/game.html?wasm=1&diag=1&rd=6&adaptive=0&mobs=0&gfx=fast&clouds=fast" \
  --timeout 1800 --soak-seconds 120 --soak-screenshot build/title_soak.png \
  --cpu-profile build/title_profile.json --cpu-profile-seconds 25
```

Summarize a profile downloaded from CI:

```sh
python3 - <<'PY'
import importlib.util, json
spec = importlib.util.spec_from_file_location("cdp", "scripts/cdp.py")
cdp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cdp)
cdp.summarize_cpu_profile(json.load(open("build/title_profile.json")))
PY
```

### Next steps

1. The build-c verification is complete: heap stayed at 1.73–1.75 GB, `planEvictions` stayed at
   8,118, `gpuMs` is ~220 ms (SwiftShader is the wall), and `title_screen.png` is non-blank.
2. The profile comparison is done: `animateTick` inclusive fell from 14.5% to 5.7%,
   `EnvironmentAttributeReader.getValue` fell below 3.4%, and `Minecraft.tick` fell from 16.7% to
   7.9%. FPS rose from 9.4 to 13.3–13.6 on the same software GPU.
3. Build `c` is deployed to both hosts: stripped module 60,461,887 bytes, Space commit
   `931269de2b36`, model commit `e738a3b20293`, live `play.html`/`game.html` contain `20261127c`,
   and the wasm magic is `0061736d01000000`.
4. Test the live Pages/HF build on a real phone. If it is still GPU-bound, reduce the 1.6 MP
   mobile budget or start the mobile bias lower (a `?scale=` override already exists); do not lower
   gameplay correctness.
5. Add the CI assertions in the handoff prompt (non-blank title, scale-bias shrink, extra screens).
6. Only after the browser build is verified and deployed, resume native Vulkan.

## Native Vulkan APK (paused)

The WebView/Tauri APK was scrapped. **Only a real native Vulkan APK is acceptable.**

| Item | State |
|---|---|
| Branch | `feat/native-vulkan`, HEAD `ac90861` |
| Base | `c0014ec`; browser HEAD has since advanced to `6f6e281` (rebase before resuming) |
| WebView/Tauri shell | Removed in `ac90861`; old shell still in `8eb6624` for reference |
| Native scaffold | None. `stash@{0}` is only the scrapped Tauri/WebView shell; do not restore it |
| Reuse | Renderer math/meshing/material/shader code and `client-textures.bin` (443,717 bytes, 1,301 PNGs) |
| APK | None yet |
| Local toolchain | No Android SDK/NDK; Rust 1.75; native APK must build in CI |
| Signing key | `/root/ci-release.jks` |
| Target API | wgpu 30: `CurrentSurfaceTexture`, `immediate_size`, `queue.present` |

Native requirements:

- `winit` + `wgpu` NativeActivity, **no WebView**.
- Use `cargo-ndk` + Gradle in CI; assert the APK has no WebView dependency.
- Preserve Minecraft rendering/gameplay and the guaranteed five-second sponsor ad.
- No AdMob for sideloaded Android.
- Use `Mojang/bedrock-samples` / GeyserMC mappings for controls.
- Do not describe the native port as a WASM→Vulkan translation; build the platform layer.

## Build and test

CI is authoritative because the local host has no usable WebGPU adapter (ARM64 Chrome 158
returns “no adapter” for SwiftShader and lavapipe attempts).

```sh
# Dependencies
python3 scripts/fetch_deps.py org.teavm:teavm-tooling:0.16.0 org.teavm:teavm-classlib:0.16.0 \
  org.teavm:teavm-jso-apis:0.16.0 org.teavm:teavm-jso-impl:0.16.0 org.teavm:teavm-platform:0.16.0

# Build the real client (Java 25 + Minecraft 26.3 installation)
PORT_HEAP=12g PORT_STACK=256m PORT_OPT=ADVANCED PORT_LENIENT=true \
  bash scripts/build.sh mcport.ClientMain wasm

# Patch the TeaVM runtime, stage the page, pack resources
python3 scripts/patch_teavm_wasm_runtime.py build/web/minecraft-java-edition-v26.3.wasm-runtime.js
cp -r web/* build/web/
python3 scripts/pack_resources.py --mc-dir "$MC_DIR" --version 26.3 --jar-only \
  --out build/web/minecraft-java-edition-v26.3-assets.bin
bash scripts/build_body.sh

# Serve
python3 -m http.server 8766 --directory build/web
```

Useful test URLs:

- `http://127.0.0.1:8766/index.html` — production shell.
- `http://127.0.0.1:8766/play.html?embedded=1` — Java client only.
- `http://127.0.0.1:8766/game.html?wasm=1&diag=1&rd=6&adaptive=0&mobs=0&gfx=fast&clouds=fast`
  — CI title/world test.
- `http://127.0.0.1:8766/game.html?smoke-world=1&wasm=1&diag=1&rd=6&adaptive=0` — Java→Rust
  smoke test (not the Java renderer path).

### Deploy runbook

Manual deploy (the workflow cannot be dispatched with the current PAT; direct HF uploads work):

```sh
# One command: rebuild staging from the verified build, strip names, upload the wasm to the
# model repo, upload the shell/runtime to the Space, then verify the live URLs.
HF_TOKEN=... /root/deploy-now.sh

# Equivalent manual steps:
python3 scripts/strip_wasm_names.py \
  build/web/minecraft-java-edition-v26.3.wasm build/web/minecraft-java-edition-v26.3.wasm
# Upload that module to matthewcodergamer/minecraft-web-client (model repo).
HF_TOKEN=... python3 scripts/upload_hf.py <parts-dir> matthewcodergamer/minecraft-browser ""
```

The stripped module is **60,461,887 bytes** with SHA-256
`bdac79c39a3c26d72104e214918fa9ec57e34d4d35d9a10bb1af0eddf6af1285`; the live copy must match
both. Bump `MC_BUILD` in `web/play.html` and the iframe query in `web/game.html` before uploading so
Cache Storage cannot mix builds. `upload_hf.py` also copies `game.html` to the Space root as
`index.html`, so both URLs serve the same shell.

### GitHub Pages mirror deploy

The Pages site is a **separate repository**, not a Pages deployment of `MinecraftWeb-engine`
(`MinecraftWeb-engine` has no Pages site; the API returns 404). It is a launcher that embeds the HF
game shell.

```sh
cd /root/site   # clone of matthewcodergamer/matthewcodergamer.github.io
# 1. Point the launcher at the current build:
#    index.html: HF_PLAY = HF_ORIGIN + '/game.html?v=26.3-20261127c&host=pages2'
#    play.html / game.html: redirect to ?v=26.3-20261127c
# 2. Keep the mobile lock in index.html:
#    body.game-on{position:fixed;inset:0;width:100%;height:100%;overflow:hidden;overflow:clip;
#                 overscroll-behavior:none;touch-action:none}
# 3. Push; Pages rebuilds automatically.
git add index.html play.html game.html && git commit -m "Point site at build ..." && git push origin main

# 4. Wait for the Pages build, then verify the live HTML:
gh api repos/matthewcodergamer/matthewcodergamer.github.io/pages/builds/latest \
  --jq '{status,commit}'
curl -s 'https://matthewcodergamer.github.io/?cb=deploy' | grep -o "HF_PLAY = HF_ORIGIN + '[^']*'"
```

`sw.js` is network-first for navigations, so a normal reload picks up a new launcher. Bump its
`CACHE` constant (`mw-site-v23`) when changing the shell asset list; otherwise old cached assets
can survive an offline load.

### Live verification checklist

```sh
BASE=https://matthewcodergamer-minecraft-browser.static.hf.space
# 1. The shell must advertise the build that was just deployed.
curl -sL "$BASE/index.html" | grep -oE "v=2026[0-9a-z]+" | head
curl -sL "$BASE/game.html" | grep -o "MC_BUILD = '[^']*'"
# 2. The model repo must serve the module; after the strip it is ~60.4 MB.
curl -sIL https://huggingface.co/matthewcodergamer/minecraft-web-client/resolve/main/\
minecraft-java-edition-v26.3.wasm | grep -iE 'HTTP/|x-linked-size|x-linked-etag'
# 3. Audio metadata and a range request must still answer.
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/sounds-index.json"
curl -s -r 0-3 https://huggingface.co/matthewcodergamer/minecraft-web-client/resolve/main/\
sounds.bin | head -c 4   # expect OggS
# 4. Pages must serve the same build and the scroll lock.
curl -s 'https://matthewcodergamer.github.io/?cb=verify' | grep -o "HF_PLAY = HF_ORIGIN + '[^']*'"
curl -s 'https://matthewcodergamer.github.io/?cb=verify' | grep -o 'body.game-on{[^}]*}'
```

Mobile scroll/layout check (headless Chrome; no WebGPU needed for the launcher page):

```sh
CHROME=/usr/bin/google-chrome python3 scripts/cdp.py \
  'https://matthewcodergamer.github.io/?cb=verify' --viewport 390x844 --dpr 3 \
  --timeout 90 --quiet --until-js "document.readyState === 'complete'" --assert-js "true" \
  --eval "(function(){const b=document.body,d=document.documentElement;b.classList.add('game-on');window.scrollTo(0,120);b.scrollTop=120;d.scrollTop=120;return JSON.stringify({inner:[innerWidth,innerHeight],doc:[d.clientWidth,d.clientHeight,d.scrollHeight],scrollY,bodyTop:b.scrollTop,docTop:d.scrollTop});})()"
```

Expect `inner` and `doc` height 844, `scrollY=0`, `bodyTop=0`, `docTop=0`.

A live deploy is not "verified" until `MC_BUILD` matches the new version, the module size/hash
matches the build log, the Pages launcher points at that build, and the title
screen/Create World/Superflat flow plus audio pass in CI.

## Decisions and rejected approaches

- **Native must be a real platform port.** The WebView/Tauri APK is scrapped (`ac90861` removed
  the shell; the old shell is only in `8eb6624` and `stash@{0}` for reference). The acceptable
  deliverable is a `winit` + `wgpu` NativeActivity APK with no WebView.
- **No WASM→Vulkan translator.** The TeaVM module imports 97 JS/DOM/WebGPU functions. Native work
  must implement the platform layer, not translate the module.
- **No standalone Binaryen shortcut.** Probe `46090386540` produced the same 111,929,134-byte
  unoptimized module, and no newer accepted wasm came out of it. Deploy optimization is the name
  section strip (60,461,887 bytes), not a second optimizer.
- **Local real-GPU profiling is not possible on this host.** ARM64 Chrome 158 returns no WebGPU
  adapter for SwiftShader or lavapipe. CI SwiftShader numbers are comparative, not user-facing.
- **Workflow dispatch is blocked for the current PAT** (HTTP 403), so deploys are direct uploads
  with the HF token. The PAT can still push commits and read public APIs.
- **Pages and the HF Space are separate shells.** Fixing one does not fix the other; the Pages
  launcher embeds the HF `game.html`, and both need their own viewport/scroll rules.
- **Do not "fix" the title screenshot by loosening the wait.** The real fix was waiting for
  `TitleScreen` + `loadFinished=true` + `frame > 10` + 5 s; the current capture is a real
  1,110-color image.

## Known issues and open questions

- **No real-GPU FPS number.** CI uses SwiftShader; the local ARM64 host cannot expose a WebGPU
  adapter (Chrome 158 returns "no adapter" for SwiftShader and lavapipe). The 13.3–13.6 fps title
  soak is a software-rasterizer number, not a user-facing score. The next meaningful measurement
  is the live Pages/HF build on a real phone with `__mcScaleBias` visible.
- **Real-phone verification is still pending.** The mobile scroll lock and `rd=4` shell change are
  verified with headless Chrome at 390×844, but no physical phone has reported FPS yet. If the
  phone is still GPU-bound, lower the mobile 1.6 MP budget or the initial bias; do not lower
  gameplay correctness.
- **Heap headroom.** The monitor fix brought the live set to 1.72–1.75 GB. `nativeHeapPeakMB` is
  only 194 MB against a 768 MB reservation, so a 512 MB desktop default is a reasonable follow-up;
  re-measure after the pending CPU batch before changing it.
- **Title test final screen.** The run reached `world-level-and-player-ready` with `level=true` and
  `player=true`, but its last `MW_STATE` still reported `CreateWorldScreen`. Add an explicit
  in-world HUD assertion so the test cannot pass on a stale screen state.
- **Deploy credentials.** The current PAT can push and read public APIs but cannot dispatch
  workflows or read repository secrets (HTTP 403). Deployment is manual until that changes.
- **No native APK.** The native scaffold is parked in `stash@{0}`; only a winit + wgpu
  NativeActivity build counts. The WebView/Tauri shell is scrapped.
- **TeaVM import surface.** 97 JS/DOM/WebGPU imports. Native is a platform-layer port, not a
  WASM→Vulkan translation.

## Layout

| Path | Purpose |
|---|---|
| `tools/src/PortBuilder.java` | TeaVM driver |
| `tools/src/PortTransformer.java`, `patches/rules.txt` | Compile-time bytecode adaptations |
| `src/compat/java` | JDK gaps, TeaVM fixes, browser runtime (`mcport.rt`), library shims |
| `src/compat/java/mcport/gpu/webgpu` | Java WebGPU backend (renderpearl) |
| `web/gpu/webgpu.js` | WebGPU binding hot path, caches, transient uploads |
| `src/entry/java/mcport` | Entry points (`M1Main`, `M2Main`, `Probe`, `ClientMain`) |
| `src/compat/java/mcport/bridge` | Java→Rust chunk/palette/player bridge |
| `web/index.html` | Production shell: loading screen, sponsor, iframe |
| `web/play.html` | Java client host page |
| `web/game.html` | QA/test page (`smoke-world`, `screen`, diagnostics) |
| `web/bridge.js` | Java ↔ Rust/iframe messages |
| `web/input.js` | Desktop + mobile controls forwarded into Java SDL |
| `scripts/cdp.py` | Headless Chrome driver, soak, CPU profiling, PERF window deltas |
| `scripts/strip_wasm_names.py` | Removes the ~51 MB Wasm name section for deployment |
| `renderer/` | Rust WebGPU fallback renderer |
| `native/` | Native platform layer to be built; no scaffold exists |

## Handoff prompt (copy this to another AI)

```text
You are taking over https://github.com/matthewcodergamer/MinecraftWeb-engine.

READ FIRST
- README.md is the source of truth; this prompt is the short version.
- Browser branch: feat/client-headless-webgpu-boot. Code HEAD 7068c91; the README/handoff
  commit is the tip after it. Run `git log --oneline -5` before doing anything.
- Native branch: feat/native-vulkan, HEAD ac90861, parked. There is no APK yet.
- Live browser build: 26.3-20261127c on both hosts (details below).

USER GOAL
1. Make the real Minecraft Java 26.3 browser client fast enough to play, verify it in CI, and
   deploy it to the live Hugging Face Space AND the GitHub Pages mirror.
2. Only after that, resume the genuinely native Vulkan Android APK.
3. Preserve Minecraft rendering/gameplay correctness and the guaranteed five-second sponsor ad.
4. Only a native Vulkan APK is acceptable. The WebView/Tauri APK is scrapped; do not revive it.
5. Do not claim a direct WASM->Vulkan translator. The TeaVM module imports 97 JS/DOM/WebGPU
   functions; native needs a real platform layer.
6. No AdMob for sideloaded Android. Use Mojang/bedrock-samples and GeyserMC mappings for controls.

WHAT IS LIVE NOW (2026-11-28)
- HF Space: https://matthewcodergamer-minecraft-browser.static.hf.space/
  The root index.html is the same shell as game.html (upload_hf.py copies it).
- Build 26.3-20261127c. Space commit 931269de2b361f6f8c56ebf08fce8444e6fedc47.
  Model repo matthewcodergamer/minecraft-web-client commit
  e738a3b202933a3eafa01c92693c91bb87f610ec.
- Wasm 60,461,887 bytes, SHA-256
  bdac79c39a3c26d72104e214918fa9ec57e34d4d35d9a10bb1af0eddf6af1285. Runtime JS 17,338 bytes.
  Asset archive 64,689,340 bytes / 25,671 entries. sounds.bin 117,739,281 bytes with Range.
- GitHub Pages: https://matthewcodergamer.github.io/ . Separate repo
  matthewcodergamer/matthewcodergamer.github.io, tip 3026e00, branch main.
  The launcher loads https://matthewcodergamer-minecraft-browser.static.hf.space/game.html?v=26.3-20261127c&host=pages2
  in its fullscreen iframe. play.html and game.html redirect to build c.
- Mobile scroll: the launcher document scrolls normally on iOS (`html{height:100%}`,
  `html,body{min-height:100%}`, body `overflow-x:clip`). Verified live at 414x896 DPR 2:
  documentElement.scrollHeight 17,692 and window.scrollTo(0,1500) reaches 1500. When the game
  starts, html.game-on and body.game-on get overflow:clip + touch-action:none, body becomes
  `position:fixed` at 100%, #game-frame is 100dvh; scrollY, htmlTop, and bodyTop stay 0 even when
  assigned 1500. The HF shell already locks html/body.
- Touch performance: the shell passes rd=4 (it used to pass 6, which overrode Java's mobile
  fallback). Java uses render distance 4, simulation distance 4, fast graphics, adaptive scale
  floor 0.6, persisted in localStorage `mc_scale_bias_v1`. Mobile framebuffer is capped at the
  smaller of DPR 1.0 and a 1.6 MP budget.

WHAT WAS FIXED AND MEASURED
- Verify 46090386545 on 6f6e281 (build c): 13.3-13.6 fps on SwiftShader vs 9.4 baseline;
  workAvg ~74 ms vs 105 ms; gpuMs ~220 ms; heap 1.73-1.75 GB; planEvictions 8,118;
  title_screen.png 77,713 bytes / 1,110 colors (was flat gray).
- Ambient-particle cache (EnvironmentAttributePatches): animateTick inclusive 14.5% -> 5.7%,
  Minecraft.tick 16.7% -> 7.9%, EnvironmentAttributeReader.getValue below 3.4%.
- MonitorRegistry leak fix (heap down ~0.65 GB) and bind-group plan cap 1,024.
- CDP split_actions fix; title wait is TitleScreen + loadFinished=true + frame > 10 + 5 s.
- Pages scroll root cause: `height:100%; overflow-x:hidden` on html+body made body the scroll
  container, which clipped the long launcher on iOS; and `body.game-on{overflow:hidden}` still
  allowed body.scrollTop changes. Final fix: `html{height:100%}`, `html,body{min-height:100%}`,
  body `overflow-x:clip`, and `overflow:clip` + `position:fixed` on html/body only during play.
- Mobile rd root cause: the shell forced rd=6 on touch, so Java never used its mobile 4.
- CI 46146270033 on 7068c91 was in progress at handoff. Check it. Any code push cancels the
  running verify job; README-only commits are safe.

DEPLOY RUNBOOK
- One command: `HF_TOKEN=... /root/deploy-now.sh` (strips the name section, uploads the wasm to
  the model repo, uploads shell/runtime to the Space, verifies live URLs). The token is in
  /root/.hf-token; never print it.
- Manual: `python3 scripts/strip_wasm_names.py <in> <out>` then upload that module to
  matthewcodergamer/minecraft-web-client, then
  `HF_TOKEN=... python3 scripts/upload_hf.py <parts-dir> matthewcodergamer/minecraft-browser ""`.
  Bump MC_BUILD in web/play.html and the iframe query in web/game.html first. The stripped size
  must be 60,461,887 bytes or the deploy script aborts.
- Pages is a separate repo at /root/site. Edit index.html `HF_PLAY` and the play.html/game.html
  redirects, push main, wait for
  `gh api repos/matthewcodergamer/matthewcodergamer.github.io/pages/builds/latest --jq .status`
  to say built, then curl the live HTML. sw.js is network-first for navigations; bump its CACHE
  constant only when changing the shell asset list.
- The GitHub PAT can push and read public APIs but cannot dispatch workflows or read secrets
  (HTTP 403). Direct HF uploads work with the valid token.

NEXT MOVES
1. Read the result of CI 46146270033. If it failed, fix the failure and rerun; do not deploy an
   unverified change.
2. Test https://matthewcodergamer.github.io/ and the HF Space on a real phone. Record FPS,
   __mcScaleBias, and canvas backing size. If it is still GPU-bound, lower the 1.6 MP mobile
   budget or the initial bias; do not lower gameplay correctness.
3. Add CI assertions that title_screen.png is non-blank and that __mcScaleBias=0.6 actually
   shrinks the canvas, plus Language/Singleplayer/death/loading screen coverage.
4. Then resume native: rebase feat/native-vulkan onto browser HEAD, implement winit + wgpu
   NativeActivity, build with cargo-ndk + Gradle in CI, and assert the APK has no WebView.
   Signing key /root/ci-release.jks. Target wgpu 30 APIs (CurrentSurfaceTexture, immediate_size,
   queue.present).
5. Keep README.md updated with commits, hashes, and measured numbers after every deploy.

GOTCHAS
- Local host is ARM64 Linux/Android; Chrome 158 has no usable WebGPU adapter (SwiftShader and
  lavapipe both return "no adapter"). CI is x86_64 with SwiftShader, so its FPS is not a
  real-GPU metric. Local javac is 21 and cannot compile class-file 69; CI uses Java 25.
- HF static hosting injects window.huggingface and OG meta tags into HTML, so live HTML hashes
  differ from repo files. Wasm/runtime hashes match exactly.
- Do not restore stash@{0}; it is the scrapped Tauri/WebView shell. The reusable renderer/ crate
  and web/native/ helpers are on feat/native-vulkan, but no Android activity exists.
- Do not remove or shorten the five-second sponsor ad. Do not replace the Java client with a
  demo renderer. Do not ship a fake APK.
```

## Legal

This is a personal legal port. It requires your own Minecraft installation and does not distribute
Mojang's client jar. The public demo is provided for testing the port; do not redistribute
Minecraft assets. Minecraft is a trademark of Mojang Studios. This project is not affiliated with
or endorsed by Mojang or Microsoft.
