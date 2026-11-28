# AI handoff — MinecraftWeb-engine

**Canonical project record:** [README.md](README.md)  
**Live browser build:** https://matthewcodergamer-minecraft-browser.static.hf.space/  
**Live Pages mirror:** https://matthewcodergamer.github.io/  
**Browser branch:** `feat/client-headless-webgpu-boot`  
**Native branch:** `feat/native-vulkan`  
**Last updated:** 2026-11-28

This file is the copy-paste handoff for another AI. It is self-contained; the README has the full
details, commit log, performance numbers, native APK state, and deploy runbook.

## Copy-paste prompt

```text
You are taking over https://github.com/matthewcodergamer/MinecraftWeb-engine.

READ FIRST
- README.md is the source of truth; this prompt is the short version.
- Browser branch: feat/client-headless-webgpu-boot. Code HEAD 7068c91; the README/handoff
  commit is the tip after it. Run `git log --oneline -5` before doing anything.
- Native branch: feat/native-vulkan, based on browser HEAD 4aed992; native commit 4d7bfce.
  The APK workflow is being fixed; no APK is published yet.
- Live browser build: 26.3-20261127c on both hosts (details below).

USER GOAL
1. Make the real Minecraft Java 26.3 browser client fast enough to play, verify it in CI, and
   deploy it to the live Hugging Face Space AND the GitHub Pages mirror.
2. Ship the genuinely native Vulkan Android APK as a separate download. It is not the Play
   button page; the Play button stays the real Java Edition browser client.
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
- Native APK: no published APK yet. Workflow 46152280757 failed at android-actions/setup-android
  (it tried to install the removed `tools` package). The workflow now locates the runner's
  preinstalled SDK. The native app is Vulkan/wgpu NativeActivity, no WebView, arm64, with vanilla
  textures and a generated overworld. It is not full Java Edition parity and must not replace the
  Play button page.

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
- CI 46146270033 on 7068c91 succeeded (51m51s): full build, Rust smoke, Java title/world, and
  screenshot assertions. Title soak mean 9.4 fps on SwiftShader (23 samples), workAvg ~101 ms,
  gpuMs ~305 ms, heap 1.73-1.75 GB, title_screen.png 76,895 bytes. Earlier 46090386545 reported
  13.3-13.6 fps; treat the difference as runner variance until a controlled repeat. Any code push
  cancels the running verify job; README-only commits are safe.

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
1. CI 46146270033 already passed on 7068c91. Before changing performance code, reproduce the
   title soak under the same conditions; do not deploy an unverified change.
2. Test https://matthewcodergamer.github.io/ and the HF Space on a real phone. Record FPS,
   __mcScaleBias, and canvas backing size. If it is still GPU-bound, lower the 1.6 MP mobile
   budget or the initial bias; do not lower gameplay correctness.
3. Add CI assertions that title_screen.png is non-blank and that __mcScaleBias=0.6 actually
   shrinks the canvas, plus Language/Singleplayer/death/loading screen coverage.
4. Native: the branch is merged with browser HEAD and the scaffold + Android shell are committed
   in 4d7bfce. Fix the APK workflow by removing android-actions/setup-android (its `tools`
   install fails on the current runner) and using the preinstalled SDK; then get a green build
   and publish minecraft-native-vulkan-latest.apk to the native-vulkan-latest release. Assert the
   APK contains libmc_native.so, links libvulkan.so, uses android.app.NativeActivity, and has no
   WebView/Tauri payload.
5. Keep README.md updated with commits, hashes, and measured numbers after every deploy.

GOTCHAS
- Local host is ARM64 Linux/Android; Chrome 158 has no usable WebGPU adapter (SwiftShader and
  lavapipe both return "no adapter"). CI is x86_64 with SwiftShader, so its FPS is not a
  real-GPU metric. Local javac is 21 and cannot compile class-file 69; CI uses Java 25.
- HF static hosting injects window.huggingface and OG meta tags into HTML, so live HTML hashes
  differ from repo files. Wasm/runtime hashes match exactly.
- stash@{0} contains the native scaffold (not the Tauri shell) and has been recovered into
  native/. The scrapped Tauri/WebView shell is only in old commit 8eb6624; do not revive it.
- Do not remove or shorten the five-second sponsor ad. Keep the Play button page as the browser
  Java Edition client; do not replace it with the native APK. Do not claim the native
  platform-layer build is full Java Edition parity.
```

## Quick links

- [Project goal and non-negotiables](README.md#project-goal-read-this-first)
- [Status snapshot](README.md#status-snapshot-2026-11-28)
- [Mobile behavior](README.md#mobile-behavior-verified-2026-11-28)
- [Native Vulkan APK](README.md#native-vulkan-apk-building)
- [Deploy runbook](README.md#deploy-runbook)
- [Known issues](README.md#known-issues-and-open-questions)
