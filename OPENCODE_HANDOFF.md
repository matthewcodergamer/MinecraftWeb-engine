# OpenCode Handoff: Minecraft Web Client — Reach the Main Menu

**Purpose:** Continue the existing browser-boot work from the current branch, fix the next verified blocker, and keep iterating until the actual Minecraft 26.3 title/main menu is visibly rendered in the browser.

## Current target

- Repository: `matthewcodergamer/MinecraftWeb-engine`
- Working branch: `feat/client-headless-webgpu-boot`
- Latest known branch commit: `46b00c7516cdeae53530d143c97780969777404a`
- Latest known commit message: `Replace PathPackResources.listPath with walkFileTree (TeaVM Files.find NPE)`

**Do not switch to `main` or discard the work on this branch.** Start by fetching the latest branch state and checking the current GitHub Actions runs. The branch may have advanced since this handoff was written.

## Goal and definition of done

The goal is not merely a successful compile or a created SDL window. The goal is to boot Minecraft 26.3 in the browser using the existing TeaVM/WASM and WebGPU path and reach the real title screen.

Count the task as complete only when:
1. The latest browser verification workflow passes.
2. Runtime logs show Minecraft completes client/resource/font initialization and constructs/initializes the title screen without a fatal exception.
3. There is direct browser evidence (screenshot or equivalent test assertion) that the actual Minecraft main menu is rendered.
4. The result is reproducible from the committed branch.

Do not report the menu as reached based only on a title-screen patch trace, an SDL window handle, or successful compilation.

## Verified progress

The latest observed browser smoke-test logs from workflow run [#44024053545](https://github.com/matthewcodergamer/MinecraftWeb-engine/actions/runs/44024053545) contained these messages:

```text
[MinecraftWeb WebGPU] adapter info:
{"name":"google swiftshader","vendor":"google", ... "fallback":true,...}

WebGPU device: google swiftshader (google)

Using graphics backend WebGPU, using drivers: WebGPU (HeadlessChrome/157...)

[MinecraftWeb WebGPU] Creating SDL window: Minecraft* 26.3 854x480

[MinecraftWeb WebGPU] SDL_CreateWindow returned handle=1
```

This confirms that, in that run, the browser obtained a WebGPU adapter/device, selected the WebGPU backend, and SDL window creation returned handle 1. It does **not** prove that Minecraft rendered the main menu.

The same observed log stream reported:

```text
java.lang.NoClassDefFoundError:
Class not found: java.nio.channels.Channels
```

The stack was in resource-pack discovery/loading, including:
- `FolderRepositorySource$FolderPackDetector.createZipPack`
- `PackDetector.detectPackResources`
- `FolderRepositorySource.discoverPacks`
- `BuiltInPackSource.populatePackList`
- `ClientPackSource.populatePackList`
- `PackRepository.reload`

The logs also contained a `PATCH TRACE net.minecraft.client.gui.screens.TitleScreen.<init>()V x1` line. Treat that as evidence that the trace/patch was registered or matched, **not** evidence that the title-screen constructor actually ran.

## Latest workflow state known when this handoff was prepared

- Browser verification run [#44024053545](https://github.com/matthewcodergamer/MinecraftWeb-engine/actions/runs/44024053545), commit `46b00c7`: was still **in progress** when last checked. The Channels error above was present in the log stream inspected at that time; fetch the final job result/logs before treating it as the final outcome.
- TeaVM probe run [#44024053409](https://github.com/matthewcodergamer/MinecraftWeb-engine/actions/runs/44024053409), same commit: **failed**. Inspect its job logs first; do not assume the failure is unrelated.
- Re-check both workflows and all newer runs before making changes. Workflow status can change after this document was written.

## Recent fixes already present in branch history

Preserve and understand these changes before editing them:

- `3cbbf33`: added a `BODY_STATIC` bypass for `com.mojang.blaze3d.platform.DebugMemoryUntracker.<clinit>()V`, using `mcport.rt.patch.LibraryPatches.noop`. This addressed a TeaVM reflection/static-initializer crash during font initialization.
- `364d3a6`: added a TeaVM compatibility implementation for `java.util.concurrent.CopyOnWriteArraySet` under `src/compat/java/org/teavm/classlib/java/util/concurrent/TCopyOnWriteArraySet.java`. The prior browser crash for that missing class disappeared after this change.
- `493e254` and `b4d07e7`: added/fixed a bridge in `BrowserLinkFSAccess` to adapt TeaVM's default VFS input streams into a read-only `SeekableByteChannel`. The path conversion was corrected to use `TDefaultFileSystem.INSTANCE.getPath(path.toString())`.
- `dd7cebf`: added JDK API compatibility shims for `TimeUnit.convert(Duration)`, `Reader.transferTo`, and `ByteBuffer.slice` (and related thread spin-hint compatibility noted in the commit).
- `0290b38`: added a browser no-op narrator and title-screen stage diagnostics/checkpoints.
- `46b00c7`: replaced the resource-pack path traversal that used `Files.find()` with `Files.walkFileTree()` after a TeaVM directory traversal failure.

Use the actual source and diffs in the repository as the authority; this list is a navigation aid, not a substitute for inspecting the code.

## Immediate next task: investigate and fix `java.nio.channels.Channels`

1. Fetch the latest branch and inspect the latest browser workflow's final status and full logs. Confirm whether `Channels` is still the first fatal exception after commit `46b00c7`.
2. Search the repository and TeaVM 0.16 class-library sources/dependencies for `java.nio.channels.Channels`, `TChannels`, and existing channel/stream adapters. Determine whether the class is absent, incompletely linked, or being called through an API TeaVM does not implement.
3. Inspect the exact Minecraft call site and required overloads/method semantics from the stack trace/bytecode/source. Do not guess a compatibility class name, package, signature, or behavior.
4. Implement the smallest correct compatibility fix in the established `src/compat` / TeaVM patch architecture. Prefer reusing the existing `BrowserLinkFSAccess` bridge where appropriate; avoid duplicating incompatible filesystem behavior.
5. Ensure read-only browser VFS behavior remains correct. Do not silently claim support for writes, seek semantics, file attributes, or unsupported options unless implemented.
6. Add/adjust a focused test or probe if practical.
7. Commit the fix to `feat/client-headless-webgpu-boot`, let the workflows run, and inspect their final logs.
8. Repeat on the **next first fatal runtime exception**, rather than attempting a broad speculative rewrite.

## Debugging rules

- Fix the first fatal exception in the latest browser run, then rerun. Later stack entries may be cascading failures.
- Distinguish warnings/optional unsupported operations from the exception that actually terminates startup.
- Keep the title-screen diagnostics enabled until there is reliable proof they are no longer needed.
- Preserve working WebGPU, SDL, OpenAL, font, and filesystem progress. Do not regress earlier milestones to silence a later error.
- Avoid broad stubs that return fake success for required functionality. A stub is acceptable only when the API is genuinely optional for the browser target and the behavior is safely defined.
- Inspect the actual TeaVM version and class library in this repository before adding compatibility overlays.
- After every code change, run the relevant compile/probe and browser verification workflows and read the logs. A commit existing is not proof that its fix works.
- Do not estimate a fixed number of remaining cycles or promise a completion time. Report only measured workflow results and observed runtime milestones.

## Required reporting after each iteration

Give a concise update with:
- Commit SHA and what changed.
- TeaVM/WASM build result.
- Browser workflow result.
- Last successful startup milestone.
- First fatal exception (exact class/method/message and relevant stack area), or confirmation that no fatal exception occurred.
- Whether title-screen construction actually executed.
- Whether a screenshot/assertion confirms the main menu is visible.
- Next concrete fix.

Be explicit about **good news**, **bad news**, and **what is not yet proven**. Never say the main menu is reached until the browser evidence confirms it.

## Useful links

- [Working branch](https://github.com/matthewcodergamer/MinecraftWeb-engine/tree/feat/client-headless-webgpu-boot)
- [Latest known commit: 46b00c7](https://github.com/matthewcodergamer/MinecraftWeb-engine/commit/46b00c7516cdeae53530d143c97780969777404a)
- [Browser verification run #44024053545](https://github.com/matthewcodergamer/MinecraftWeb-engine/actions/runs/44024053545)
- [TeaVM probe run #44024053409](https://github.com/matthewcodergamer/MinecraftWeb-engine/actions/runs/44024053409)

---

**Instruction to OpenCode:** Take ownership of the next iteration now. Start by refreshing the branch and workflow state, then investigate `java.nio.channels.Channels` against the actual TeaVM APIs and current code. Implement, commit, build, inspect logs, and continue toward the visible Minecraft 26.3 main menu. Do not stop after writing a plan.
