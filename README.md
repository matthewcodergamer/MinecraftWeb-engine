# mc-browser-port

Running the **real Minecraft Java Edition 26.3 client** in a web browser by compiling Mojang's
unmodified client bytecode with [TeaVM](https://github.com/konsoletyper/teavm), and building a
**native Vulkan Android client** from the same Java code. See `docs/ARCHITECTURE.md` and
`docs/MIGRATION_MATRIX.md` for the older design notes.

> **Current priority:** browser performance must be fixed, verified in CI, and deployed to the live
> Space **before** native Vulkan work resumes. The native APK is not finished and no APK is shipped
> from this branch yet.

## Status snapshot (2026-11-28)

| Item | State |
|---|---|
| Browser branch | `feat/client-headless-webgpu-boot` |
| Browser HEAD | `6f6e281` (build `20261127c`; CDP action-parser fix, learned-scale persistence, patch audit) |
| Browser CI | verify `46090386545` in progress on `6f6e281`; `46027524065` failed before Chrome launched; `46008416951` succeeded |
| Live Space | build `26.3-20261127b`; stripped 60,459,240-byte module |
| Native branch | `feat/native-vulkan`, HEAD `ac90861`, not pushed, no APK |
| Next gate | verify `46090386545`, check patch match/`gpuMs`/heap/plans and the title screenshot, then deploy build `c` |

Run `46027524065` was not a client regression. `scripts/cdp.py` split the new title wait on the
semicolons inside its JavaScript function body, so the title step exited with
`unknown --actions step: return ...` before it launched Chrome. `b3970a1` adds a quote/bracket-aware
action splitter, moves the wait into a single function expression, stores the learned scale bias in
`localStorage`, and makes Java read that stored value back. `6f6e281` bumps every shell script and
the iframe query to `20261127c`, so the deploy gets a fresh Cache Storage bucket. The build audit
now prints the `EnvironmentAttributeReader` patch-match line.

> **CI caveat:** the verify workflow uses `concurrency: cancel-in-progress`, so any push touching
> `scripts/**`, `web/**`, `src/**`, `renderer/**`, `patches/**`, `tools/src/**`, or the workflow
> file **cancels the running verify job**. Batch changes and wait for the profile artifact before
> pushing. A README-only commit does not trigger the workflow.

## Live build

| Item | Value |
|---|---|
| Playable demo | <https://matthewcodergamer-minecraft-browser.static.hf.space/> |
| Visible build | `26.3-20261127b` |
| Space commit | `c91bc519e3fd` |
| Model repo | `matthewcodergamer/minecraft-web-client` (commit `2a26281ac451`) |
| Wasm module (deployed) | 60,459,240 bytes (TeaVM name section stripped) |
| Wasm runtime JS | 17,338 bytes |
| Asset archive | 64,689,340 bytes, 25,671 entries (jar-only by design) |
| Audio bank | `sounds.bin`, 117,739,281 bytes, served with HTTP Range from the model repo |

The live Space serves the production shell (`web/index.html`), which embeds `web/play.html` in an
iframe. The iframe runs the Java client; the parent owns the loading screen, the sponsor slot, and
the mobile controls.

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

The latest completed profile is verify **46008416951** (commit `abd846e`) on GitHub's x86_64
runner with **SwiftShader** at 1280×577. It includes the monitor-registry fix and the plan-cache
fix. The title/Create World test drives the real Java renderer; the smoke test is the separate
Rust Body path. Verify **46090386545** (`6f6e281`, build `20261127c`) is the next profile: it adds
the ambient cache, adaptive scale, `gpuAvg`, and the corrected title wait. The earlier verify
`46089986283` was canceled by the build-`c` bump before it reached the browser.

| Metric | Title/Create World | Meaning |
|---|---|---|
| Presented FPS | 9.4 | Software WebGPU; not a real-GPU number |
| `workAvg` | 105 ms | Java frame wall time, including GPU fence waits |
| `presentAvg` | 0.00 ms | `WG.present()` only; GPU work is asynchronous |
| JS heap | 1.72–1.74 GB | Down from 2.37–2.42 GB before the monitor fix; limit 4.19 GB |
| Native heap | 194 MB peak | The 768 MB default is mostly unused |
| Live textures / views | 711 / 727 | Stable |
| Live buffers | 103 | Stable |
| Pipelines | 199 | Created during load |
| Draws | ~260/frame | 361,107 over 1,391 frames |
| Render passes | ~11/frame | 15,460 over 1,391 frames |
| Plans | 1,272 steady | 9,390 misses / 8,118 evictions during startup only |
| Shader creation | 3.0 s | One-time during load |

### What the latest run proved

1. **The monitor leak is gone.** `[REPORT] monitors` now stays at `locks=0` with only the lazy
   contended path; the heap fell by about 0.65 GB and stayed flat (1.72–1.74 GB over 200 s)
   instead of climbing toward the 4.19 GB limit.
2. **The plan cache no longer thrashes every frame.** `planEvictions` is 8,118, all during the
   first half-second of startup; after warm-up `planTotal` holds at 1,272 and grows by only a few
   plans over 100 s. The old build evicted 34,816 plans across 2,050 frames.
3. **The title flow still reaches `world-level-and-player-ready`, but `title_screen.png` was still
   a flat gray frame in `46008416951`.** The old wait only required `__mcWebGPU.frame > 2`, so it
   captured before the Java title screen had painted or composited. `b3970a1` waits for the
   iframe's `boot-state` to report `TitleScreen` + `loadFinished=true` and `frame > 10`, then holds
   for 5 s. Verify `46090386545` will confirm whether the capture is non-blank.
4. **The frame is still GPU-bound on SwiftShader.** The CPU profile is 61.6% idle self-time
   (55.2% of inclusive time), with 5.5% GC and 2.6% native program code. The game ticks at 20 TPS;
   the 9.4 FPS is the software rasterizer and the three-frame fence ring, not a tick stall.

### CPU profile findings (20,946 samples over 27.1 s)

- 61.6% idle, 5.5% garbage collector, 2.6% native program code.
- 14.5% `ClientLevel.animateTick` / `doAnimateTick`; 9.7% of all samples are
  `EnvironmentAttributeReader.getValue(attribute, BlockPos)` and 9.2% is the
  `EnvironmentAttributeSystem.getValue` underneath it.
- 4.9% `BiomeManager.getNoiseBiomeAtQuart`, mostly called from those attribute lookups.
- 16.7% inclusive `Minecraft.tick`, 9.7% inclusive TeaVM `EventQueue.run`, 5.4% integrated
  server, 2.2% Java rendering.

The dominant CPU cost is `animateTick`: every client tick samples 1,334 random block positions and
asks for `AMBIENT_PARTICLES` at each one. The vanilla two-argument attribute overload re-resolves
the biome and walks every attribute layer for every sample, even though the value is constant
inside a 4×4×4-block biome cell. That is the next targeted fix.

### Optimization batch under verification (`6f6e281`, build `20261127c`)

1. **Per-quart ambient-particle cache** (`EnvironmentAttributePatches`). `AMBIENT_PARTICLES` is
   cached per quart cell for about one second; every other attribute and reader falls through to
   the original three-argument implementation. The one-second clock check runs once per 256
   lookups so it does not add a JS boundary crossing to every sample. This targets the 9.7% +
   4.9% biome/attribute cost without changing gameplay or particle rates.
2. **Adaptive render scale.** Once render distance is already at the floor of 4, a device below
   28 FPS steps the canvas backing store down by 10% every 5 s (floor 0.6), and steps it back up
   after 30 s above 52 FPS. CSS keeps the canvas full-screen. This is the direct lever for a phone
   GPU that cannot fill 1080p; it is exposed as `__mcScaleBias` and triggered through the existing
   SDL resize path.
3. **Mobile defaults and fill budget.** Render distance starts at 4 (was 5), simulation distance
   at 4, and the canvas starts near a 1.6 MP framebuffer (about 850p wide) instead of full
   resolution. A 1440p phone would otherwise render 3.7 MP for the first ~20 s while the
   adaptive bias walked down; this makes the first frame playable and still lets the bias raise
   sharpness when the GPU keeps up. The learned bias is stored in `localStorage`
   (`mc_scale_bias_v1`), and both `computeScale` and Java's `currentScaleBias()` read it back, so a
   returning player starts at the scale that worked last time instead of re-learning it.
4. **GPU queue timing.** `gpuAvg` measures `queue.onSubmittedWorkDone()` once per frame (one probe
   in flight), and the snapshot now reports the adapter name and fallback flag. `cdp.py` prints
   `gpuMs` on every SOAK line, so the next run distinguishes a GPU-bound frame from a CPU-bound
   one instead of inferring it from idle samples.
5. **CDP action parsing and patch audit.** `split_actions` keeps semicolons inside quoted strings
   and bracket depth, so a multi-statement JS wait no longer becomes bogus `--actions` steps. The
   build audit prints `PATCH_RULE_MATCH net.minecraft.world.attribute.EnvironmentAttributeReader`,
   which proves the ambient cache was actually wired into the client.

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

1. Wait for verify `46090386545`; confirm `PATCH_RULE_MATCH ... matched=true`, `heapMB` /
   `heapTotalMB` stay near 1.7 GB, `planEvictions` stays at the startup value, `gpuMs` tracks
   `intervalAvg`, and the title screenshot is non-blank.
2. Compare the CPU profile with the numbers above: `animateTick` and
   `EnvironmentAttributeSystem.getValue` should fall sharply; GC should fall with the removed
   `Vec3.atCenterOf` allocations.
3. Deploy with `scripts/strip_wasm_names.py` and `scripts/upload_hf.py` (the PAT cannot dispatch
   workflows or read repository secrets: HTTP 403). Bump `MC_BUILD` so returning players do not
   keep the old module in Cache Storage.
4. If a real phone is still GPU-bound after the scale adaptation, reduce the mobile fill budget
   further or add a `?scale=` recommendation to the shell; do not lower gameplay correctness.
5. Only after the browser build is verified and deployed, resume native Vulkan.

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

Manual deploy (the workflow cannot be dispatched with the current PAT):

```sh
HF_TOKEN=... python3 scripts/upload_hf.py deploy/parts matthewcodergamer/minecraft-browser ""
# Strip the module, upload it to matthewcodergamer/minecraft-web-client, and bump
# the ?v= build query in the shell:
python3 scripts/strip_wasm_names.py \
  build/web/minecraft-java-edition-v26.3.wasm build/web/minecraft-java-edition-v26.3.wasm
```

### Live verification checklist

```sh
BASE=https://matthewcodergamer-minecraft-browser.static.hf.space
# 1. The shell must advertise the build that was just deployed.
curl -sL "$BASE/index.html" | grep -oE "v=2026[0-9a-z]+" | head
# 2. The model repo must serve the module; after the strip it is ~60.4 MB.
curl -sIL https://huggingface.co/matthewcodergamer/minecraft-web-client/resolve/main/\
minecraft-java-edition-v26.3.wasm | grep -iE 'HTTP/|content-length'
# 3. Audio metadata and a range request must still answer.
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/sounds-index.json"
curl -s -r 0-3 https://huggingface.co/matthewcodergamer/minecraft-web-client/resolve/main/\
sounds.bin | head -c 4   # expect OggS
```

A live deploy is not "verified" until `MC_BUILD` matches the new version, the module size matches
the build log, and the title screen/Create World/Superflat flow plus audio pass in CI.

## Known issues and open questions

- **No real-GPU FPS number.** CI uses SwiftShader; the local ARM64 host cannot expose a WebGPU
  adapter (Chrome 158 returns "no adapter" for SwiftShader and lavapipe). The 9.4 fps title soak is
  a software-rasterizer number, not a user-facing score. The new `gpuAvg` probe will show how much
  of each frame is queue latency on the next run.
- **`title_screen.png` is still blank gray** while `title_soak.png` shows the world. Waiting for
  the iframe's `__mcWebGPU.frame > 2` was not enough: the Java title screen had not painted yet.
  The workflow now waits for the iframe's `boot-state` to report `TitleScreen` and
  `loadFinished=true`, then holds 5 s before the shot. Confirm it in the next run.
- **Heap headroom.** The monitor fix brought the live set to 1.72–1.74 GB. `nativeHeapPeakMB` is
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

USER GOAL
1. Make the real Minecraft Java 26.3 browser client fast enough to play, verify it in CI, and
   deploy it to the live Hugging Face Space.
2. Only after that, resume the genuinely native Vulkan Android APK.
3. Preserve Minecraft rendering/gameplay correctness and the guaranteed five-second sponsor ad.
4. Only a native Vulkan APK is acceptable. The WebView/Tauri APK is scrapped and must not be
   revived.
5. Do not claim a direct WASM→Vulkan translator. The TeaVM module imports 97 JS/DOM/WebGPU
   functions; native needs a new client/platform layer.
6. No AdMob for sideloaded Android. Use Mojang/bedrock-samples and GeyserMC mappings for controls.

PLAIN ENGLISH
The user says the live build's flicker is fixed but the game still crawls. The live Space now
serves build 26.3-20261127b with the monitor-leak fix and a 60.5 MB stripped module; the next job
is to verify and deploy the environment-attribute and adaptive-scale batch. After the browser is
proven, build the real Android app that renders with Vulkan natively — not a WebView wrapper and
not a Tauri shell. The five-second sponsor ad is a hard requirement. Do not declare victory early,
do not swap the Java client for a demo renderer, and do not ship a fake APK.

REPO STATE
- Browser work: branch feat/client-headless-webgpu-boot, HEAD 6f6e281. Code commits: 6f6e281
  (bump every shell script and iframe query to build 20261127c), b3970a1 (CDP action-parser fix,
  learned-scale persistence, patch-match audit), 5b786ba
  (EnvironmentAttributePatches ambient cache, adaptive render scale, mobile fill budget, gpuAvg),
  abd846e (wait for the iframe's WebGPU frame before the title screenshot), 6307f3a (monitor leak
  + bind-group plan-cache fixes), 0ea1e34 (strip the Wasm name section for deploy, PERF window
  deltas, planTotal fix), 52f1534 (profiling and hot-path counters), 88119c0 (TeaVM runtime kept
  on the Space), c0014ec (GUI/sound fixes, build 20261127a).
- Batch under verification on 6f6e281: EnvironmentAttributePatches (per-quart ambient-particle
  cache), adaptive render scale (__mcScaleBias, 0.6–1.0, persisted in localStorage
  mc_scale_bias_v1 and read back by Java), mobile render/simulation distance 4, gpuAvg queue
  timing, SOAK gpuMs, split_actions, build 20261127c. Verify 46090386545 is in progress.
- Run 46027524065 (5b786ba) failed only because cdp.py split the JS wait on semicolons; the title
  step exited before launching Chrome. That is fixed in b3970a1 and included in 6f6e281, not a
  rendering regression.
- Live: https://matthewcodergamer-minecraft-browser.static.hf.space/ (build 26.3-20261127b).
  Space commit c91bc519e3fd; model repo matthewcodergamer/minecraft-web-client commit
  2a26281ac451. Wasm 60,459,240 bytes (name section stripped); runtime JS 17,338 bytes; asset
  archive 64,689,340 bytes / 25,671 entries; sounds.bin 117,739,281 bytes.
- Native: branch feat/native-vulkan, HEAD ac90861, based on c0014ec and not pushed. WebView shell
  removed in ac90861 (old shell in 8eb6624). There is no native scaffold: stash@{0} is only the
  scrapped Tauri/WebView shell; do not restore it. The reusable renderer/ crate (wgpu shaders,
  math, meshing, materials, textures) and web/native/ JS helpers are on the branch, but there is
  no Android activity yet. No APK. No local Android SDK/NDK; build in CI with cargo-ndk + Gradle.
  Signing key /root/ci-release.jks. Target wgpu 30 APIs: CurrentSurfaceTexture, immediate_size,
  queue.present.
- Local host: ARM64 Linux/Android container. Chrome 158 has no usable WebGPU adapter (SwiftShader
  and lavapipe both return "no adapter"), so local live testing is blocked. CI is x86_64 with
  SwiftShader; its FPS is not a real-GPU metric.

CURRENT PERFORMANCE WORK
- Verified run 46008416951 on abd846e succeeded. Title/Create World soak: 9.4 fps on SwiftShader,
  workAvg ~105 ms, heap 1.72-1.74 GB (down from 2.37-2.42 GB; limit 4.19 GB), nativeHeapPeakMB
  194, 711 textures / 727 views / 103 buffers / 199 pipelines, ~260 draws and ~11 passes per
  frame, 1,272 plans steady. planEvictions 8,118 all happened in the first half-second; the old
  build evicted 34,816 over 2,050 frames.
- Run 46027524065 on 5b786ba completed the Rust Body smoke step but never ran the title test:
  cdp.py split the multi-statement JS wait on semicolons and exited with "unknown --actions step".
  b3970a1 adds split_actions and rewrites the wait as one function expression. Verify 46089986283
  was canceled by the 20261127c bump before it reached the browser; 46090386545 is the first run
  that will exercise the new title capture.
- CPU profile (20,946 samples / 27.1 s): 61.6% idle, 5.5% GC, 2.6% program. Inclusive: 16.7%
  Minecraft.tick, 14.5% ClientLevel.animateTick, 9.7% EnvironmentAttributeReader.getValue_1,
  9.2% EnvironmentAttributeSystem.getValue, 4.9% BiomeManager.getNoiseBiomeAtQuart, 5.4%
  integrated server. The tick runs at 20 TPS; the low FPS is GPU/SwiftShader waiting.
- Batch under verification (6f6e281, build 20261127c): cache AMBIENT_PARTICLES per quart for one second (targets
  the 9.7% + 4.9% hot path), adaptive canvas scale after render distance reaches the floor of 4,
  mobile defaults rd/simulation 4, gpuAvg timing, learned-scale persistence. Expected: lower CPU
  per frame, same rendering output; the scale lever is the direct fix for a weak phone GPU.
- FIXED and deployed: MonitorRegistry leak (lazy contended-only registration, 131,072 cap; heap
  down ~0.65 GB), bind-group plan cap 1,024 with oldest-bucket eviction, and the stripped 60.5 MB
  module. The old title_screen.png wait (iframe __mcWebGPU.frame > 2) was too early; b3970a1 waits
  for TitleScreen + loadFinished=true + frame > 10 and a 5 s settle, and fixes the parser that
  previously prevented that wait from running. The non-blank capture is not confirmed yet.
- Deploy mechanics: scripts/strip_wasm_names.py strips the name section; upload the stripped
  module to the model repo, then run scripts/upload_hf.py for the Space shell/runtime. Bump
  MC_BUILD in web/play.html and web/game.html so Cache Storage does not serve the old module.

NEXT MOVES
1. Wait for verify 46090386545 on 6f6e281 (do not push code while it runs). Download the
   diagnostics artifact and read title_console.log / title_profile.json.
2. Confirm PATCH_RULE_MATCH EnvironmentAttributeReader matched=true, heap stays ~1.7 GB,
   planEvictions stays at the startup value, gpuMs tracks intervalAvg, title_screen.png is
   non-blank, and world-level-and-player-ready is reached. Compare the profile to the numbers
   above; animateTick and environment-attribute frames should fall sharply.
3. Deploy the verified batch with scripts/strip_wasm_names.py + scripts/upload_hf.py. MC_BUILD is
   already 20261127c; check the live wasm size and play.html build string.
4. If a real phone is still GPU-bound after scale adaptation, lower the mobile fill budget or
   expose a scale hint; do not lower gameplay correctness.
5. Then resume native: rebase feat/native-vulkan onto browser HEAD 6f6e281, implement winit + wgpu
   NativeActivity, build with cargo-ndk + Gradle in CI, assert no WebView.
6. Add browser CI coverage for Language/Singleplayer/death/loading screens.
7. Remember: README-only commits are safe; code pushes cancel the running verify job.

GUARDRAILS
- Do not regress the five-second sponsor ad.
- Do not remove the Java client or replace it with a demo renderer.
- Do not claim browser performance is fixed until CI and the live Space both pass.
- Keep the Rust Body as a fallback/diagnostic; the primary browser renderer is the Java WebGPU
  backend on feat/client-headless-webgpu-boot.
- Do not use AdMob or a WebView for the native deliverable.
```

## Legal

This is a personal legal port. It requires your own Minecraft installation and does not distribute
Mojang's client jar. The public demo is provided for testing the port; do not redistribute
Minecraft assets. Minecraft is a trademark of Mojang Studios. This project is not affiliated with
or endorsed by Mojang or Microsoft.
