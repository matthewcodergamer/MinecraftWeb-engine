# mc-browser-port

Running the **real Minecraft Java Edition 26.3 client** in a web browser by compiling Mojang's
unmodified client bytecode with [TeaVM](https://github.com/konsoletyper/teavm), and building a
**native Vulkan Android client** from the same Java code. See `docs/ARCHITECTURE.md` and
`docs/MIGRATION_MATRIX.md` for the older design notes.

> **Current priority:** browser performance must be fixed, verified in CI, and deployed to the live
> Space **before** native Vulkan work resumes. The native APK is not finished and no APK is shipped
> from this branch yet.

## Live build

| Item | Value |
|---|---|
| Playable demo | <https://matthewcodergamer-minecraft-browser.static.hf.space/> |
| Visible build | `26.3-20261127a` |
| Space commit | `ee1cd576bb87` |
| Model repo | `matthewcodergamer/minecraft-web-client` (commit `86b7b521bddd`) |
| Wasm module (deployed) | 111,924,360 bytes today; next deploy strips the name section to ~60,452,000 bytes |
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
| Native client | `native/` (parked in a stash) | winit + wgpu NativeActivity, no WebView |

The TeaVM module imports **97 JavaScript/DOM/WebGPU functions**. There is **no direct
WASM→Vulkan translator** and no plan to add one: the native client needs a real platform layer
(window, input, GPU, audio), not a binary translation step.

## Current performance state

The last fully verified browser run was **45980931507** (`20261127a`). Its title/world soak ran on
GitHub's x86_64 runner with **SwiftShader** at 1280×577:

| Metric | Value | Meaning |
|---|---|---|
| Presented FPS | ~12–13 | Software WebGPU; not a real-GPU number |
| `workAvg` | ~74 ms | Java/WASM frame work from RAF resume to frame stats |
| `presentAvg` | ~0.00–0.07 ms | `WG.present()` only; GPU rasterization is asynchronous |
| JS heap | ~2.3–2.4 GB | Includes the 768 MB native heap and the 112 MB Wasm module |
| Live textures / views | 711 / 727 | Stable over the soak |
| Live buffers | ~106 | Stable |
| Pipelines | 199 | Created during load, not per frame |
| Draws | ~250/frame | |
| Render passes | ~11/frame | |
| Transient ring | 32 MB (8 × 4 MB) | Capacity, not necessarily per-frame upload |
| Bind groups | ~17/frame | Mostly plan-cache misses |

The **smoke test is a different path**: `?smoke-world=1` keeps the HTML/Rust Body UI and does not
wait for Java's canvas takeover. It samples 0.2–1.2 fps with `workAvg` 2.8–4.5 s on a normal
world. Those numbers describe Java worldgen/tick plus the Rust Body on a 2-vCPU SwiftShader
runner, **not** the Java WebGPU renderer. Do not use them as the browser-renderer score.

### Instrumentation added in `52f1534`

- `web/gpu/webgpu.js`: counters for buffer/texture writes and bytes, transient uploads, plan
  misses/evictions, bind/pipeline/vertex/index/scissor skips, pipeline/shader creation time, and
  begin-pass cache hits. It also skips redundant WebGPU state calls and fixes plan-cache
  accounting when a pipeline is destroyed.
- `src/compat/java/mcport/rt/FrameProfiler.java`: per-frame `submit`, `hooks`, and `readback`
  phase timings, published to `window.__mcFrames.phases`.
- `scripts/cdp.py`: `--cpu-profile`, `--cpu-profile-seconds`, `--cpu-profile-interval`; captures a
  Chrome CPU profile after the soak and prints self-time plus inclusive-time summaries.
- `.github/workflows/verify-client-browser.yml`: uploads `title_profile.json` and
  `smoke_profile.json` with the diagnostics artifact.

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

### What the profile must answer

1. **Is the main thread CPU-bound or starved?** Compare the profile's total sampled time with the
   soak wall time and with `Performance.getMetrics` (`TaskDuration`, `ScriptDuration`). If the main
   thread only sampled a few seconds out of 25 s, SwiftShader/GPU contention is inflating
   `workAvg`; the FPS number is not representative of a real GPU.
2. **Is GC dominant?** Look for `(garbage collector)` in self-time. The heap is close to typical
   Chrome limits, and the 768 MB native heap is a large fixed reservation.
3. **Which Java frames dominate?** The Wasm module carries a large name section (about 51 MB in
   the local build), so leaves should appear as real Java methods (`LevelRenderer`,
   `GameRenderer`, `Minecraft.tick`, `SectionRenderDispatcher`, etc.), not
   `wasm-function[...]`.
4. **Which bridge calls dominate?** Look for `bindAndValidate`, `setUniformBuffer`,
   `drawIndexed`, `beginPass`, `JSON.parse`, `createBindGroup`, `createRenderPipeline`.

### Working hypotheses (ranked)

1. **CI is GPU/CPU-bound on SwiftShader.** Eleven full-screen passes at 1280×577 plus ~250 draws
   saturate a 2-vCPU runner. The renderer main thread is descheduled, so `workAvg` includes
   waiting. Real-GPU FPS is probably much higher, but this has not been measured on a real GPU.
2. **Heap pressure.** `performance.memory.usedJSHeapSize` includes the 768 MB native heap
   `ArrayBuffer`. At ~2.3 GB used, a ~2.4–4 GB V8 limit leaves little headroom. If the profile
   shows heavy GC, reduce `?heapMB=` (512 or 384) after checking `nativeHeapPeakMB`, or release
   the asset/sound buffers after use.
3. **Java renderer CPU.** ~250 draws and ~11 passes per frame with per-uniform JS bridge calls
   may be the real cost on a fast GPU. Batch uniform bindings or cache more state if the profile
   points there.
4. **Plan-cache counter leak (fixed, uncommitted at the time of writing).** `destroyPipeline` did
   not subtract `planCount` from `planTotal`, so resource reloads eventually forced a full
   plan-cache flush and a bind-group rebuild wave.
5. **Bridge overhead.** Every uniform and draw crosses Wasm→JS. It is cheap per call but adds up;
   measure before redesigning.

### Next steps

1. Wait for CI run **45994386852** and download `browser-console-diagnostics`.
2. Read the `SOAKGPU` lines and `title_profile.json`; confirm hypothesis 1 vs 2/3.
3. Apply the highest-impact fix; re-run the same soak and compare `fps`, `workAvg`, `bindGroups`,
   `heapMB`, and `nativeHeapPeakMB`.
4. Deploy the optimized build manually (the current PAT cannot dispatch workflows or read
   repository secrets: HTTP 403). Use `scripts/upload_hf.py` and upload the Wasm to the model repo.
5. Only after the browser build is verified and deployed, resume native Vulkan.

## Native Vulkan APK (paused)

The WebView/Tauri APK was scrapped. **Only a real native Vulkan APK is acceptable.**

| Item | State |
|---|---|
| Branch | `feat/native-vulkan`, HEAD `ac90861` |
| Base | `c0014ec` (two commits behind browser HEAD `88119c0`/`52f1534`) |
| WebView/Tauri shell | Removed in `ac90861`; old shell still in `8eb6624` for reference |
| Scaffold | `stash@{0}` `native-vulkan-scaffold-wip` |
| Scaffold files | `native/src/{textures,arena,world,input,camera,renderer,lib,main}.rs` |
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
| `native/` (stash) | Native Vulkan scaffold |

## Handoff prompt (copy this to another AI)

```text
You are taking over https://github.com/matthewcodergamer/MinecraftWeb-engine.

USER GOAL
1. Fix browser performance for the real Minecraft Java 26.3 client, verify it in CI, and deploy it
   to the live Hugging Face Space.
2. Only after that, resume the genuinely native Vulkan Android APK.
3. Preserve Minecraft rendering/gameplay correctness and the guaranteed five-second sponsor ad.
4. Only a native Vulkan APK is acceptable. The WebView/Tauri APK is scrapped and must not be
   revived.
5. Do not claim a direct WASM→Vulkan translator. The TeaVM module imports 97 JS/DOM/WebGPU
   functions; native needs a new client/platform layer.
6. No AdMob for sideloaded Android. Use Mojang/bedrock-samples and GeyserMC mappings for controls.

REPO STATE
- Browser work: branch feat/client-headless-webgpu-boot. Last commits: 0ea1e34 (strip the Wasm
  name section for deploy, PERF window deltas, planTotal fix), 2415b86 (this handoff README),
  52f1534 (profiling and hot-path counters), 88119c0 (TeaVM runtime kept on the Space), c0014ec
  (GUI/sound fixes, build 20261127a).
- Live: https://matthewcodergamer-minecraft-browser.static.hf.space/ (build 26.3-20261127a).
  Space commit ee1cd576bb87; model repo matthewcodergamer/minecraft-web-client commit
  86b7b521bddd. Wasm 111,924,360 bytes (next deploy ~60.4 MB stripped); runtime JS 17,338 bytes;
  asset archive 64,689,340 bytes / 25,671 entries; sounds.bin 117,739,281 bytes.
- Native: branch feat/native-vulkan, HEAD ac90861, based on c0014ec and not pushed. WebView shell
  removed in ac90861 (old shell in 8eb6624). Scaffold is in stash@{0}
  "native-vulkan-scaffold-wip" (native/src/{textures,arena,world,input,camera,renderer,lib,main}.rs).
  No APK yet. No local Android SDK/NDK; build in CI with cargo-ndk + Gradle. Signing key
  /root/ci-release.jks. Target wgpu 30 APIs: CurrentSurfaceTexture, immediate_size, queue.present.
- Local host: ARM64 Linux/Android container. Chrome 158 has no usable WebGPU adapter (SwiftShader
  and lavapipe both return "no adapter"), so local live testing is blocked. CI is x86_64 with
  SwiftShader; its FPS is not a real-GPU metric.

CURRENT PERFORMANCE WORK
- Run 45994386852 is the profiled build (may be finished by the time you read this). Download the
  browser-console-diagnostics artifact and inspect build/title_profile.json plus the SOAKGPU log
  lines.
- Last verified title/world soak (run 45980931507): ~13 fps, workAvg ~74 ms, presentAvg ~0.00 ms,
  heap ~2.3 GB, 711 live textures, 106 buffers, 199 pipelines, ~250 draws/frame, ~11 passes/frame,
  32 MB transient capacity.
- The smoke test (?smoke-world=1) is the Rust Body path and runs at 0.2–1.2 fps with workAvg
  2.8–4.5 s; do not treat it as the Java renderer score.
- Instrumentation: web/gpu/webgpu.js counters and state-call skipping; FrameProfiler.java for
  submit/hooks/readback phases; scripts/cdp.py --cpu-profile; workflow uploads title_profile.json
  and smoke_profile.json.
- Key questions: is the main thread CPU-bound or starved by SwiftShader? Is GC dominant? Which
  Java frames (LevelRenderer/GameRenderer/Minecraft.tick/SectionRenderDispatcher) or JS bridge
  calls dominate? Does nativeHeapPeakMB justify reducing ?heapMB=768?
- Applied in `0ea1e34`: `destroyPipeline` now subtracts `planCount` from `planTotal`, so resource
  reloads cannot force a full plan-cache flush and a bind-group rebuild wave.
- `0ea1e34` also adds `scripts/strip_wasm_names.py`. It removes TeaVM's ~51 MB `name` section and
  takes the module from ~112 MB to ~60.4 MB; the deploy workflow now strips before publishing.
  Verified locally: the stripped module compiles in Chrome in ~5.2 s and still exposes the same
  exports. Profiles still come from the unstripped CI artifact.

NEXT MOVES
1. Run: gh run list --branch feat/client-headless-webgpu-boot; gh run download <id> -n
   browser-console-diagnostics -D diag.
2. Analyze the profile with scripts/cdp.py's summarize_cpu_profile (self + inclusive).
3. Apply the highest-impact fix, re-run the same title/world soak, and compare fps/workAvg/
   bindGroups/heapMB/nativeHeapPeakMB.
4. Verify the real Java title screen, Create World, Superflat world, screenshots, and audio.
5. Deploy manually with scripts/upload_hf.py; the PAT cannot dispatch workflows or read secrets
   (HTTP 403). Strip the Wasm with scripts/strip_wasm_names.py before uploading it to the model
   repo (the deploy workflow already does this), then bump the ?v= build query.
6. Then resume native: pop stash@{0} on feat/native-vulkan, rebase onto browser HEAD, implement
   winit + wgpu NativeActivity, build with cargo-ndk + Gradle in CI, assert no WebView.
7. Add browser CI coverage for Language/Singleplayer/death/loading screens and fix the blank
   title_screen.png timing issue.

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
