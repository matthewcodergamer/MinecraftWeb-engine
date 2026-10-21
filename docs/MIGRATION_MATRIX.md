# Migration matrix — Minecraft 26.3 client subsystems

Status legend: ✅ runs in browser (verified) · 🟡 compiles / partially working · ⛔ not started · ➖ not needed in browser

| Subsystem (original) | Original dependencies | Browser implementation | Status | Evidence / test |
|---|---|---|---|---|
| Core math & types (`Mth`, `BlockPos`, `AABB`, `Vec3`, `Identifier`) | JDK | Unmodified bytecode | ✅ | `scripts/test.sh m1main`: identical to JVM |
| `Util` static init (executors, time sources, zip FS lookup) | ForkJoinPool, Executors, FileSystemProvider | `java.util.concurrent` compat (green threads); `jar` provider registered | ✅ | M1/M2 run through it |
| Logging (`LogUtils`, SLF4J) | log4j-core, log4j-slf4j2-impl | `BrowserLogger` → console + page log | ✅ | Minecraft's own WARN messages appear in browser console |
| Version detection (`SharedConstants.tryDetectVersion`) | classpath `version.json` | `ClasspathResources` (resource archive from local jar) | ✅ | reports `26.3 protocol=777` |
| Bootstrap & built-in registries (blocks, states, items, entities, …) | Guava, DFU, JOML, fastutil | Unmodified; JOML→NIO MemUtil; Guava→AtomicReferenceFieldUpdater helper | ✅ | M2: 1286 / 1658 / 161 / 35723 = JVM |
| Crash/system reports (`SystemReport`) | JMX MemoryMXBean, OSHI | `SystemReportBrowser` (navigator / performance.memory) | 🟡 | compiles; not yet exercised |
| DataFixerUpper | CompletableFuture | `TCompletableFuture` | 🟡 | compiles; schema build not yet exercised |
| SNBT `\N{name}` escapes | `Character.codePointOf` (Unicode name table) | only `U+XXXX` names | 🟡 | known gap |
| Zip/jar file systems (resource packs, `.mcassetsroot`) | `jdk.nio.zipfs` | `JarFileSystemProvider` registered; mounting not implemented (throws) | ⛔ | next: read-only NIO FS over the resource archive |
| Resource manager / vanilla pack / language | NIO Paths over the client jar | needs the NIO FS above | ⛔ | |
| Data packs, worldgen registries, integrated server | NIO, threads, DFU | | ⛔ | |
| Native memory (`MemoryUtil`, `MemoryStack`, `NativeImage`) | LWJGL system | emulated heap (ArrayBuffer) | ⛔ | 65 functions / 618 call sites |
| Window & input | LWJGL SDL3 | canvas + DOM events + pointer lock | ⛔ | 84 functions |
| Renderer | renderpearl → OpenGL/Vulkan backend | WebGL2 renderpearl backend | ⛔ | backend API inventoried |
| Shader compilation & reflection | shaderc, SPIRV-Cross (native) | WASM builds behind LWJGL facades | ⛔ | needs Emscripten (disk) |
| Fonts | FreeType | WASM FreeType or browser rasterizer | ⛔ | 14 functions |
| Images | STB | browser decode / WASM | ⛔ | 5 functions |
| Audio | OpenAL, jorbis | WebAudio (+ jorbis is pure Java) | ⛔ | 40 functions |
| Networking / multiplayer | Netty (NIO sockets, epoll/kqueue) | WebSocket transport (proxy needed for TCP servers) | ⛔ | |
| Authentication | authlib, MSAL | offline profile first; MSAL not portable | ⛔ | |
| Profiler (jtracy) | JNI | unmodified (reports unavailable) | ✅ | |

## Bytecode adaptations in effect

All are listed in `patches/rules.txt`; the build prints `PATCH …` lines with how often each applied.
JDK compatibility classes live in `src/compat/java/org/teavm/classlib/**`; two are corrected copies
of TeaVM 0.16.0 classes (Apache-2.0):

* `TConcurrentMap.computeIfAbsent` returned `null` after inserting (JDK returns the new value) —
  this made Minecraft's interned `ResourceKey`s null.
* `TDecimalFormatParser` rejected a negative subpattern without digits (`" 0.000E0;-"`, used by JOML).
