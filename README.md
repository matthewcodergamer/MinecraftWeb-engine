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
| Browser HEAD | `fbcf93b` (README; code HEAD `6f6e281`, build `20261127c`) |
| Browser CI | verify `46090386545` **succeeded** on `6f6e281`: 13.3–13.6 fps, title screenshot non-blank; `46027524065` failed before Chrome launched; `46008416951` succeeded |
| Live Space | **build `26.3-20261127c` deployed** (60,461,887-byte stripped module); Space commit `cfbdef30a776`, model commit `e738a3b20293` |
| Native branch | `feat/native-vulkan`, HEAD `ac90861`, not pushed, no APK |
| Next gate | real-phone test of build `c`; if still GPU-bound, tighten the mobile fill budget or initial scale |

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

## Live build

| Item | Value |
|---|---|
| Playable demo | <https://matthewcodergamer-minecraft-browser.static.hf.space/> |
| Visible build | `26.3-20261127c` |
| Space commit | `cfbdef30a7763dd1565ee78928d62b31e1eb0e41` |
| Model repo | `matthewcodergamer/minecraft-web-client` (commit `e738a3b202933a3eafa01c92693c91bb87f610ec`) |
| Wasm module (deployed) | 60,461,887 bytes (TeaVM name section stripped) |
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

1. Verify `46090386545` succeeded: heap stayed at 1.73–1.75 GB, `planEvictions` stayed at 8,118,
   `gpuMs` is ~220 ms (SwiftShader is the wall), and `title_screen.png` is non-blank.
2. The profile comparison is done: `animateTick` inclusive fell from 14.5% to 5.7%,
   `EnvironmentAttributeReader.getValue` fell below 3.4%, and `Minecraft.tick` fell from 16.7% to
   7.9%. FPS rose from 9.4 to 13.3–13.6 on the same software GPU.
3. Build `c` is deployed: stripped module 60,461,887 bytes, Space commit `cfbdef30a776`, model
   commit `e738a3b20293`, live `play.html`/`game.html` contain `20261127c`, and the wasm magic is
   `0061736d01000000`.
4. Test the live build on a real phone. If it is still GPU-bound, reduce the mobile fill budget
   further or expose a `?scale=` hint; do not lower gameplay correctness.
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
The user says the live build's flicker is fixed but the game still crawls. Build
26.3-20261127c is now live: it is verified at 13.3–13.6 fps on SwiftShader versus 9.4 before, with
the ambient cache cutting the animateTick hot path and a real title screenshot. The deploy is
confirmed (60,461,887-byte stripped module, Space commit cfbdef30a776, model commit e738a3b20293).
The next measurement is a real phone; if it is still GPU-bound, tighten the mobile fill budget or
initial scale. After that, build the real Android app that renders with Vulkan natively — not a
WebView wrapper and not a Tauri shell. The five-second sponsor ad is a hard requirement. Do not
declare victory early, do not swap the Java client for a demo renderer, and do not ship a fake
APK.

REPO STATE
- Browser work: branch feat/client-headless-webgpu-boot, HEAD fbcf93b (README). Code HEAD 6f6e281.
  Code commits: 6f6e281
  (bump every shell script and iframe query to build 20261127c), b3970a1 (CDP action-parser fix,
  learned-scale persistence, patch-match audit), 5b786ba
  (EnvironmentAttributePatches ambient cache, adaptive render scale, mobile fill budget, gpuAvg),
  abd846e (wait for the iframe's WebGPU frame before the title screenshot), 6307f3a (monitor leak
  + bind-group plan-cache fixes), 0ea1e34 (strip the Wasm name section for deploy, PERF window
  deltas, planTotal fix), 52f1534 (profiling and hot-path counters), 88119c0 (TeaVM runtime kept
  on the Space), c0014ec (GUI/sound fixes, build 20261127a).
- Build c deployed: Space commit cfbdef30a7763dd1565ee78928d62b31e1eb0e41, model commit
  e738a3b202933a3eafa01c92693c91bb87f610ec. Live wasm 60,461,887 bytes, runtime 17,338 bytes,
  play.html and game.html both contain 20261127c.
- Batch verified in 46090386545 on 6f6e281: EnvironmentAttributePatches (per-quart ambient-particle
  cache), adaptive render scale (__mcScaleBias, 0.6–1.0, persisted in localStorage
  mc_scale_bias_v1 and read back by Java), mobile render/simulation distance 4, gpuAvg queue
  timing, SOAK gpuMs, split_actions, build 20261127c. It is live now.
- Run 46027524065 (5b786ba) failed only because cdp.py split the JS wait on semicolons; the title
  step exited before launching Chrome. That is fixed in b3970a1 and included in 6f6e281; the next
  run, 46090386545, succeeded and captured a real title screen.
- Live: https://matthewcodergamer-minecraft-browser.static.hf.space/ (build 26.3-20261127c).
  Space commit cfbdef30a776; model repo matthewcodergamer/minecraft-web-client commit
  e738a3b20293. Wasm 60,461,887 bytes (name section stripped); runtime JS 17,338 bytes; asset
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
- Verified run 46090386545 (6f6e281, build c): 13.3–13.6 fps on SwiftShader (was 9.4), workAvg
  ~74 ms (was 105), gpuMs ~220 ms (software GPU queue), heap 1.73–1.75 GB, 711 textures / 727
  views / 103 buffers / 199 pipelines, 1,273 plans steady, planEvictions 8,118. title_screen.png
  is 77,713 bytes / 1,110 colors (was a flat gray frame).
- New CPU profile (20,726 samples / 26.7 s): 73.4% idle, 5.3% GC, 2.5% program. Inclusive: 7.9%
  Minecraft.tick, 5.7% ClientLevel.animateTick, EnvironmentAttributeReader.getValue below 3.4%,
  4.0% integrated server. The ambient cache cut the 9.7% attribute path and the FPS rose 41%;
  the remaining wall is SwiftShader/GPU queue time.
- FIXED and verified: MonitorRegistry leak (lazy contended-only registration, 131,072 cap; heap
  down ~0.65 GB), bind-group plan cap 1,024 with oldest-bucket eviction, and the title capture
  wait (TitleScreen + loadFinished=true + frame > 10 + 5 s). The cdp.py split_actions fix lets
  that wait run; 46090386545 produced a real 1,110-color title screenshot.
- Deploy mechanics: scripts/strip_wasm_names.py strips the name section; upload the stripped
  module to the model repo, then run scripts/upload_hf.py for the Space shell/runtime. Bump
  MC_BUILD in web/play.html and web/game.html so Cache Storage does not serve the old module.

NEXT MOVES
1. Build c is deployed and verified: wasm 60,461,887 bytes, live play.html/game.html contain
   20261127c, runtime 17,338 bytes, wasm magic 0061736d01000000.
2. Test https://matthewcodergamer-minecraft-browser.static.hf.space/ on a real phone. Watch the
   on-screen FPS, and confirm the canvas shrinks as __mcScaleBias adapts (floor 0.6). If it is
   still GPU-bound, lower the mobile fill budget (1.6 MP) or start mobile at a lower initial bias.
3. Add browser CI coverage for Language/Singleplayer/death/loading screens, plus assertions that
   title_screen.png is non-blank and that __mcScaleBias=0.6 actually shrinks the canvas.
4. Then resume native: rebase feat/native-vulkan onto browser HEAD 6f6e281, implement winit + wgpu
   NativeActivity, build with cargo-ndk + Gradle in CI, assert no WebView.
5. Remember: README-only commits are safe; code pushes cancel the running verify job.

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
