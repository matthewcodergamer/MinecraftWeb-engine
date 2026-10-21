# Architecture: running the real Minecraft 26.3 client in a browser

## Decision

**Compile Mojang's unmodified client bytecode with TeaVM (JavaScript backend), and port the
platform underneath it** — the JDK gaps, the native libraries (LWJGL), and the graphics backend —
instead of decompiling, rewriting, or translating game code.

```
26.3.jar (unobfuscated, Java 25 bytecode)  +  Mojang's declared libraries (Guava, DFU, Netty-common, JOML, fastutil, …)
        │   no decompilation: TeaVM reads .class files directly
        ▼
PortTransformer (patches/rules.txt)  — small, listed bytecode adaptations (REDIRECT / BODY / STUB)
        │
src/compat  — JDK classes TeaVM lacks (java.util.concurrent, ScopedValue, HexFormat, …),
        │     TeaVM classlib bug fixes, SLF4J/LogUtils browser backend, resources, (next) LWJGL facades
        ▼
TeaVM 0.16.0 → JavaScript (green threads for Java threads/monitors)
        ▼
web/run.html — loads the client's resources archive, starts main(), exposes state for tests
```

## Evidence behind the decision

| Question | Evidence (this machine, this repo) |
|---|---|
| Is the target client available and vanilla? | `~/Library/Application Support/minecraft/versions/26.3/26.3.jar`, SHA-1 `e877b6a0…` = Mojang's official 26.3 `client.jar` (piston-meta manifest). 26.3 is the current release; no 26.1.x install exists locally. |
| Do we need decompilation/mappings? | No. Since 26.1 Mojang ships **unobfuscated** jars (`net/minecraft/util/Mth.class`, …). TeaVM consumes bytecode; Quilt mappings are unnecessary. |
| Can TeaVM read Java 25 class files? | Yes: TeaVM 0.16.0 bundles ASM 9.10.1; M1/M2 compile class-file version 69 cleanly. |
| Does real client code behave identically? | M1 and M2 outputs match the real JVM exactly (BlockPos packing, AABB math, 1286 blocks / 1658 items / 161 entity types / 35723 block states). |
| Existing ports for this version? | `NT9712/eaglercraft-262-workspace`: scaffold with a `DemoGame`, no Minecraft code. `brebathe/Eaglercraft-26-1-1-src`: decompiled 26.1.1 sources (and Mojang jars) that do not compile yet. `FigureT13/EaglerCraft-1.21-Client`: blocked (HTTP 451). `ScorchedIV/26.2-client-with-mods`: 404. `EaglerPorts/1.13.2`: real TeaVM port, but of 1.13.2 (pre-renderpearl, pre-SDL), useful only as reference for browser adapters. None provides a usable 26.x base. |
| Why not decompile + patch sources (Eaglercraft model)? | Decompiling 11k classes yields non-compiling source (see brebathe repo) and must be redone per version. Bytecode-level porting keeps Mojang's code byte-for-byte except for the listed rules. |
| Why not Rust/other translation? | It would replace Mojang's game logic with a re-implementation — the opposite of the objective. |
| WASM-GC? | Kept as a later experiment: TeaVM's WASM-GC backend exists, but the client depends on threads/monitors (executors, futures, DFU), which TeaVM supports in the JS backend through green threads. JS is the proven path first. |

## Graphics: renderpearl

26.3 renders through **renderpearl** (`com.mojang.renderpearl`): an API layer, a frontend, and a
backend API (`GpuDeviceBackend`, `CommandEncoderBackend`, `RenderPassBackend`) with Mojang's
OpenGL and Vulkan backends. The plan is a **WebGL2 backend at that level**, so the whole renderer
above it (world/chunks/entities/GUI) stays Mojang's code.

Shaders: the frontend compiles GLSL to SPIR-V with **shaderc** and reflects with **SPIRV-Cross**
(LWJGL natives). Browser equivalent: WebAssembly builds of glslang/shaderc and SPIRV-Cross behind
LWJGL-compatible facades; SPIRV-Cross also emits GLSL ES 3.00 for WebGL2.

## Native-library surface still to port (from `docs/lwjgl-usage-26.3.txt`)

| LWJGL module | API functions used | Browser implementation plan |
|---|---|---|
| `org.lwjgl.system` (MemoryUtil, MemoryStack, …) | 65 (618 call sites) | Emulated native heap: one ArrayBuffer; addresses are offsets; direct ByteBuffers alias it |
| `org.lwjgl.sdl` (SDL3 window/input) | 84 | DOM canvas, keyboard/mouse events, pointer lock, fullscreen |
| `org.lwjgl.opengl` | 148 | Replaced as a whole by the WebGL2 renderpearl backend |
| `org.lwjgl.util.shaderc` / `spvc` | 56 | glslang/shaderc + SPIRV-Cross compiled to WASM |
| `org.lwjgl.openal` | 40 | WebAudio |
| `org.lwjgl.util.freetype` | 14 | FreeType compiled to WASM (or browser font rasterization) |
| `org.lwjgl.stb` | 5 | Browser image decode / WASM stb |
| `org.lwjgl.vulkan`, `util.vma` | 618 | Not needed (Vulkan backend not compiled in) |

## Constraints of this machine

2018 MacBook Pro, i5-7360U (2 cores/4 threads), 8 GB RAM, macOS 15.7, **disk ~99% full
(1–3 GB free)**. No Node, Gradle, Maven or Emscripten. Builds therefore use only the JDK bundled with
Minecraft (Java 25), Python 3.9 and Chrome; the bootstrap build needs a 4 GB heap and ~5 minutes.
Installing Emscripten (needed to build the WASM shader/font toolchains) requires freeing disk space.
