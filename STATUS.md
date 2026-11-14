# Project Status

_Last updated: 2026-11-14 · Branch: `feat/client-headless-webgpu-boot`_

## Current State: Browser Boot

The project is bringing the real Minecraft Java Edition 26.3 client into a browser runtime. The Java client is being compiled with TeaVM to WebAssembly GC and connected to a custom Rust/WebGPU renderer.

### Verified progress

- **Offline identity path:** the browser log has reached `Setting user: Player`. The current client boot uses an offline `Player` identity; this is not Mojang/Microsoft account authentication.
- **Crash-report handling:** a null guard in the browser crash-report path allows crash reports to be generated and saved, preserving the original startup exception instead of masking it with a secondary null dereference.
- **Bytecode diagnostics:** CI has completed the exhaustive Minecraft 26.3 JAR scan for `java.lang.management` references. The scan found eight classes, including the client-side `Minecraft` uptime lambda.
- **Uptime crash patch:** commit `7f192e2` replaces `Minecraft.fillUptime(CrashReportCategory)` with a browser-safe helper. The replacement avoids the vanilla invokedynamic uptime suppliers and does not call `ManagementFactory`. The transformer has a required-match assertion for BODY rules.

### Current CI status

The checks for commit `7f192e2904e8ca79d217224cd97e431ed34478c7` are **in progress**, not yet green:

- [TeaVM WebAssembly GC client build — run #43578283316](https://github.com/matthewcodergamer/MinecraftWeb-engine/actions/runs/43578283316): environment/toolchain setup passed; compilation of the actual Minecraft client was still running at the last check.
- [Browser client verification — run #43578283343](https://github.com/matthewcodergamer/MinecraftWeb-engine/actions/runs/43578283343): Java/Rust setup, dependency retrieval, and bytecode/JMX diagnostic scans passed; building the actual client entry point was still running.

**The ManagementFactory fix is a targeted patch under verification, not yet a confirmed resolution.** The next success criterion is a browser run that passes the former constructor/crash-report failure and continues through initialization. Reaching the main menu and rendering a real in-game frame remain outstanding milestones.

## Architecture Overview

- **Java brain:** Mojang's Minecraft 26.3 client bytecode, compiled to WebAssembly GC using [TeaVM](https://github.com/konsoletyper/teavm).
- **Graphics body:** custom Rust renderer using WebGPU, with Java-to-Rust world, block palette, and player-state bridging.
- **Bytecode adaptation:** `tools/src/PortTransformer.java` applies compile-time redirects and method-body replacements declared in `patches/rules.txt`.
- **Browser host:** the web page coordinates the Java client, Rust renderer, loading UI, and browser input bridge.

## Browser Compatibility Work and Limitations

The browser runtime does not provide the full desktop JVM or all native services expected by the original client. Compatibility work is therefore explicit and narrowly scoped.

- **Authlib / Gson path:** the offline identity/service path is redirected through `mcport.rt.patch.AuthPatches`. The earlier Authlib `TypeToken`/Gson startup failure was worked around in the offline boot path; this should not be described as a general implementation of Authlib or a full JVM `TypeToken` class-library replacement.
- **JVM uptime:** vanilla `Minecraft.fillUptime` uses `java.lang.management.ManagementFactory`. The current patch replaces the containing method and supplies browser-safe text instead. It is intended to remove the unsupported management API from this startup path; CI and browser verification are still pending.
- **Crash reporting:** browser-safe guards and substitutions keep early crash reporting from hiding the original exception.
- **Memory:** browser tabs are subject to browser and device memory ceilings. Mobile Safari and Chrome may impose tighter practical limits than desktop browsers, particularly when loading the full client, assets, and WebAssembly memory together. Actual limits vary by device and browser; performance and memory profiling remain necessary.

## Roadmap

1. **Finalize browser boot.** Clear the current TeaVM and browser verification runs, pass Minecraft construction, reach the main menu, and render the first real game frame.
2. **Publish a web build.** Package the browser build for [Hugging Face Spaces](https://huggingface.co/spaces) as a static web app, then connect the deployment to [matthewcodergamer.github.io](https://matthewcodergamer.github.io/).
3. **Explore an Android APK.** Evaluate a Tauri v2 Android shell with the Rust/WASM runtime and native integration. A native wrapper may change resource constraints, but does not automatically eliminate WebAssembly or device-memory limits.
4. **Implement Bedrock-style touch controls.** Use the public [Mojang/bedrock-samples](https://github.com/Mojang/bedrock-samples) UI resources as a reference for mapping touch layouts and interaction behavior into this project.
5. **Evaluate monetization.** Investigate Unity LevelPlay / Unity Ads for a sideloaded Android distribution, subject to platform support, policy compliance, consent/privacy requirements, and eligibility.

## Scope of This Status

This document records verified repository and CI progress as of the timestamp above. A successful compile is not the same as a successful browser boot; a successful browser boot is not the same as a playable, fully compatible game. Roadmap items are plans, not completed features.
