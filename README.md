# mc-browser-port

Running the **real Minecraft Java Edition 26.3 client** in a web browser by compiling Mojang's
unmodified client bytecode with [TeaVM](https://github.com/konsoletyper/teavm), and building a
**native Vulkan Android client** from the same Java code. See `docs/ARCHITECTURE.md` and
`docs/MIGRATION_MATRIX.md` for the older design notes.

> **Current priority:** browser performance must be fixed, verified in CI, and deployed to the live
> Space **before** native Vulkan work resumes. The native APK is not finished and no APK is shipped
> from this branch yet.

## Status snapshot (2026-11-27)

| Item | State |
|---|---|
| Browser branch | `feat/client-headless-webgpu-boot` |
| Browser HEAD | monitor/plan-cache fixes on top of `0ea1e34` (this commit); earlier code commit `0ea1e34` |
| Browser CI | `45996284596` completed successfully; next run validates the monitor/plan fixes |
| Live Space | build `26.3-20261127a`; still serves the unstripped ~112 MB module |
| Native branch | `feat/native-vulkan`, HEAD `ac90861`, not pushed, no APK |
| Next gate | confirm the heap drop and plan-eviction drop, then deploy |

> **CI caveat:** the verify workflow uses `concurrency: cancel-in-progress`, so any push touching
> `scripts/**`, `web/**`, `src/**`, `renderer/**`, `patches/**`, `tools/src/**`, or the workflow
> file **cancels the running verify job**. Batch changes and wait for the profile artifact before
> pushing. A README-only commit does not trigger the workflow.

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

The profiled build ran as verify **45996284596** (commit `0f652a8`, `20261127a` runtime) on GitHub's
x86_64 runner with **SwiftShader** at 1280×577. The title/Create World test drives the real Java
renderer; the smoke test is the separate Rust Body path.

| Metric | Title/Create World | Meaning |
|---|---|---|
| Presented FPS | 12.6 | Software WebGPU; not a real-GPU number |
| `workAvg` | 77 ms | Frame wall time measured by the Java frame loop |
| `presentAvg` | 0.00 ms | `WG.present()` only; GPU work is asynchronous |
| Main-thread busy | 49% (`TaskDuration` 12.26 s / 25 s) | Half the window is waiting |
| Main-thread script | 37% (`ScriptDuration` 9.33 s / 25 s) | WASM/JS work, including ticks |
| CPU profile idle | 67.8% of samples | Direct evidence of GPU/compositor waiting |
| JS heap | 2.37–2.42 GB | Before the monitor fix; limit 4.19 GB |
| Native heap | 194 MB peak | The 768 MB default is mostly unused |
| Live textures / views | 711 / 727 | Stable |
| Live buffers | 103 | Stable |
| Pipelines | 199 | Created during load |
| Draws | ~262/frame | 537,044 over 2,050 frames |
| Render passes | ~11/frame | 22,725 over 2,050 frames |
| Bind groups | ~17/frame | 35,142 over 2,050 frames |
| Plan misses / evictions | 35,557 / 34,816 | Cache thrash, fixed below |
| Shader creation | 2.4 s | One-time during load |

### CPU profile findings

The title profile sampled 27.4 s (20,359 samples). Self-time:

- **67.8% idle** — the main thread is mostly waiting for the software rasterizer/compositor.
- 2.1% native program code, 0.5% garbage collector.
- 1.4% `mcport.rt.MonitorRegistry::describe`, 1.0% `LevelChunkSection::getNoiseBiome`, 0.9%
  `wasm-to-js`, 0.9% `ChunkAccess::getNoiseBiome`, 0.8% `EnvironmentAttributeSystem::getValue`.

Inclusive time: 58.3% idle, 20.1% frame loop (`FrameLoopAsync` → `Minecraft.run` → `runTick`),
16.4% `Minecraft.tick`, 13.9% `ClientLevel.animateTick`/`doAnimateTick`, 9.2%
`EnvironmentAttributeReader.getValue_1`, 8.8% `EnvironmentAttributeSystem.getValue`, 7.8% the
integrated server fiber, 4.7% `BiomeManager.getNoiseBiomeAtQuart`.

Conclusion: on SwiftShader the browser is **GPU-bound first and CPU-bound second**. The 12.6 fps
number is not a real-GPU score, but the profile exposed two real CPU/memory bugs:

1. **Monitor registry leak (13,341,935 entries).** `TObject.createMonitor` registered every
   synchronized object in a static array that is never pruned. `[REPORT] monitors` showed
   `locks=13341935` after 222 s, and the boot watchdog walked that array every 5 s. At roughly
   48 bytes per entry this was hundreds of megabytes of the JS heap and a large GC root set.
   Fixed by registering only contended locks (lazily, already supported by the contended path)
   and adding a hard 131,072-entry cap.
2. **Bind-group plan cache thrash.** `animate_sprite_blit` needs ~500 plans (one per sprite view).
   The per-pipeline cap was 512, so it cleared the entire cache every time it filled: 34,816
   evictions over 2,050 frames (68 full flushes, exactly 512 each). Fixed by raising the cap to
   1,024 and evicting only the oldest bucket instead of the whole cache.

The monitor leak is expected to remove roughly 0.6–0.7 GB from the JS heap; the next verify run
should show it in `heapMB`/`heapTotalMB`. Neither fix changes rendering output.

The **smoke test** (`?smoke-world=1`, Rust Body) sampled 0.2 fps with `busyPct` 70.9% and
`scriptPct` 9.4%; its profile is 46.8% idle, 45.8% native program code, 3.3% `getCurrentTexture`.
That path is GPU-bound on the Rust Body and is not the Java renderer score.

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

### What the profile answered

1. **Main thread CPU-bound or starved?** Neither extreme: 49% busy, 37% script, 67.8% of samples
   idle. The browser is waiting on SwiftShader for about half the frame and spending the other
   half in WASM/JS. A real GPU will remove much of the wait, but the CPU half still needs work.
2. **Is GC dominant?** No: 0.5% of self-time. The 13.3M-entry monitor registry was a much larger
   problem than the 768 MB native heap; it inflated the live set and every GC scan.
3. **Which Java frames dominate?** `Minecraft.tick` (16.4%), `ClientLevel.animateTick` (13.9%),
   environment-attribute lookups (9.2% + 8.8%), biome lookups (4.7%), and the integrated server
   fiber (7.8%). The renderer itself is not the top CPU consumer on this path.
4. **Which bridge calls dominate?** None individually. `wasm-to-js` is 0.9% self, `submit` 0.3%,
   and the per-draw `bindAndValidate`/`drawIndexed` path is spread thin. The bind-group churn was
   the measurable bridge cost (17 `createBindGroup` calls/frame), now fixed.

### Next steps

1. Verify the monitor and plan-cache fixes in CI: the next title soak should show `heapMB`/
   `heapTotalMB` drop by roughly 0.6–0.7 GB, `planEvictions` near zero after warm-up, and no
   rendering regression.
2. Compare `fps`, `workAvg`, `bindGroups`, `heapMB`, `heapLimitMB`, and `nativeHeapPeakMB` against
   the table above. On SwiftShader, expect a small FPS change; the memory drop is the main signal.
3. If CPU still matters on a fast GPU, target `animateTick`/environment attributes next. Those are
   vanilla client frames, so any change must be a targeted bytecode rule, not a gameplay rewrite.
4. Deploy the verified build manually (the current PAT cannot dispatch workflows or read repository
   secrets: HTTP 403). Use `scripts/upload_hf.py`, strip the Wasm with
   `scripts/strip_wasm_names.py`, and upload it to the model repo.
5. Only after the browser build is verified and deployed, resume native Vulkan.

## Native Vulkan APK (paused)

The WebView/Tauri APK was scrapped. **Only a real native Vulkan APK is acceptable.**

| Item | State |
|---|---|
| Branch | `feat/native-vulkan`, HEAD `ac90861` |
| Base | `c0014ec`; browser HEAD has since advanced to `0f652a8` (rebase before resuming) |
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
  adapter (Chrome 158 returns "no adapter" for SwiftShader and lavapipe). The 12.6 fps title soak is
  a software-rasterizer number, not a user-facing score. `PERF.busyPct` (49%) and the 67.8% idle
  profile show that roughly half the frame is GPU/compositor wait.
- **`title_screen.png` was blank gray** while `title_soak.png` shows the world. The capture ran
  immediately after `java-ui` appeared, before Java had presented a frame. The workflow now waits
  for `window.__mc.frame > 10` before the shot; confirm it in the next run.
- **Heap headroom.** The 2.37–2.42 GB measurement included the 13.3M-entry monitor registry leak,
  now fixed. Re-measure before changing `?heapMB=`: `nativeHeapPeakMB` was only 194 MB against a
  768 MB reservation, so a 512 MB desktop default is a reasonable follow-up once the leak fix is
  verified.
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

PLAIN ENGLISH
The user wants the browser version to stop being slow, to be proven fixed in CI, and to be live on
the Space. After that, they want a real Android app that renders with Vulkan natively — not a
WebView wrapper and not a Tauri shell. The five-second sponsor ad is a hard requirement. Do not
declare victory early, do not swap the Java client for a demo renderer, and do not ship a fake APK.

REPO STATE
- Browser work: branch feat/client-headless-webgpu-boot, latest code commit 0ea1e34 (README commits
  after it are docs-only). Code commits: 0ea1e34 (strip the Wasm name section for deploy, PERF
  window deltas, planTotal fix), 52f1534 (profiling and hot-path counters), 88119c0 (TeaVM runtime
  kept on the Space), c0014ec (GUI/sound fixes, build 20261127a).
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
- Verify run 45996284596 completed successfully. title_profile.json (1.16 MB) and
  smoke_profile.json (281 KB) are in the browser-console-diagnostics artifact.
- Title/Create World result: 12.6 fps, workAvg 77 ms, presentAvg 0.00 ms, heap 2.37-2.42 GB
  (limit 4.19 GB), nativeHeapPeakMB 194, 711 textures, 727 views, 103 buffers, 199 pipelines,
  ~262 draws/frame, ~11 passes/frame, ~17 bind groups/frame.
- PERF window: TaskDuration 12.26s/25s (busyPct 49.0), ScriptDuration 9.33s/25s (scriptPct 37.3).
- CPU profile: 20,359 samples over 27.4s; 67.8% idle, 2.1% program, 0.5% GC. Inclusive: 58.3%
  idle, 20.1% frame loop, 16.4% Minecraft.tick, 13.9% ClientLevel.animateTick, 9.2% + 8.8%
  environment-attribute lookups, 7.8% integrated server, 4.7% biome lookups. The renderer is not
  the top CPU consumer; SwiftShader waiting is about half the frame.
- Smoke profile (Rust Body): 0.2 fps, busyPct 70.9, scriptPct 9.4; 46.8% idle, 45.8% program,
  3.3% getCurrentTexture. Not the Java renderer score.
- FIXED in this commit: MonitorRegistry leak. [REPORT] showed locks=13,341,935 after 222s because
  TObject.createMonitor registered every synchronized object forever. Registration is now lazy
  (contended path only) with a 131,072 hard cap. Expect ~0.6-0.7 GB less heap.
- FIXED in this commit: bind-group plan thrash. animate_sprite_blit needs ~500 plans; the 512 cap
  cleared the whole cache, causing 34,816 evictions / 68 flushes in 2,050 frames. Cap is now 1,024
  and eviction drops only the oldest bucket.
- Also: scripts/strip_wasm_names.py cuts the deployed module from ~112 MB to ~60.4 MB; the deploy
  workflow strips before publishing. cdp.py now prints PERF deltas (busyPct/scriptPct) and
  inclusive profile time. The title screenshot action waits for frame > 10 to avoid the blank
  title_screen.png.

NEXT MOVES
1. Wait for the new verify run (triggered by this commit), download
   browser-console-diagnostics, and confirm heapMB/heapTotalMB dropped by ~0.6-0.7 GB and
   planEvictions is near zero after warm-up.
2. Check title_screen.png is non-blank and the flow still reaches world-level-and-player-ready;
   compare fps/workAvg/bindGroups/heapMB/heapLimitMB/nativeHeapPeakMB with the numbers above.
3. Deploy manually with scripts/upload_hf.py; the PAT cannot dispatch workflows or read secrets
   (HTTP 403). Strip the Wasm with scripts/strip_wasm_names.py before uploading it to the model
   repo (the deploy workflow already does this), then bump the ?v= build query.
4. Then resume native: pop stash@{0} on feat/native-vulkan, rebase onto browser HEAD, implement
   winit + wgpu NativeActivity, build with cargo-ndk + Gradle in CI, assert no WebView.
5. Add browser CI coverage for Language/Singleplayer/death/loading screens.
6. Remember: pushes to scripts/web/src cancel the running verify job. Batch work; README-only
   commits are safe.

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
