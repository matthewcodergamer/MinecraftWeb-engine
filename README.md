# mc-browser-port

Running the **real Minecraft Java Edition 26.3 client** in a web browser by compiling Mojang's
unmodified client bytecode with [TeaVM](https://github.com/konsoletyper/teavm) and porting the
platform underneath it. See `docs/ARCHITECTURE.md` and `docs/MIGRATION_MATRIX.md`.

**Current state:** the Java Brain and the Rust Body are wired together.

- **Java Brain.** The real client bootstraps under TeaVM. `mcport.bridge.ChunkBridge`
  scans the loaded world around the player every 500 ms, packs each 16×16×16
  section as `Int32Array(4096)`, registers the real block-state palette, and
  sends the player's eye position + yaw/pitch every frame.
- **Rust Body** (`renderer/`). A WebGPU renderer with a greedy mesher. It
  uploads 99 real 26.3 block textures from `client-textures.bin`, consumes the
  Java sections, follows Java's eye/rotation, and never generates or mutates
  world state.
- **Hybrid page** (`web/index.html`). Owns the visible WebGPU canvas, shows the
  Mojang loading screen over the exact dirt texture, and runs the Java client in
  an off-screen iframe. The overlay stays up until the Body has meshed the first
  real 16³ section (or, in `?java=0` mode, the synthetic demo arena). If Java
  boots but never produces a world, the overlay offers an explicit
  "Continue with renderer only" button instead of pretending the world loaded.

`web/play.html` still runs the Java client alone (for debugging), and
`body.html` runs the Rust renderer alone.

# LEGAL

this is a is a fully support legal port and is only localhost on the my end in
this browswer with everthing staying true to the minecrfat java port being one
to one 

## Requirements

* A local Minecraft 26.3 installation (`~/Library/Application Support/minecraft/versions/26.3/`)
  including its downloaded libraries and the `java-runtime-epsilon` runtime (a full JDK 25).
* Python 3, Google Chrome. No Gradle/Maven/Node needed.

## Build and test

```sh
python3 scripts/fetch_deps.py org.teavm:teavm-tooling:0.16.0 org.teavm:teavm-classlib:0.16.0 \
  org.teavm:teavm-jso-apis:0.16.0 org.teavm:teavm-jso-impl:0.16.0 org.teavm:teavm-platform:0.16.0

scripts/build.sh mcport.M1Main js            # math/types smoke test (~30 s)
PORT_HEAP=4g scripts/build.sh mcport.M2Main js   # full Bootstrap (~5 min; add
                                             # PORT_JAVA_OPTS=-Dport.cache=build/teavm-cache for incremental)

# Pack every vanilla asset (blocks, items, GUI, sounds, music, languages,
# asset indexes). This reads your own installation and writes only into build/.
python3 scripts/pack_resources.py            # -> build/web/client-resources.bin (~477 MiB)
                                             #    build/web/client-textures.bin (~0.4 MiB)
                                             #    build/web/ui/{dirt,mojangstudios}.png

scripts/build_body.sh                        # Rust/WebGPU -> build/web/pkg
scripts/serve.sh                             # http://localhost:8000/index.html
```

The hybrid page needs exactly one download of the large archive: the Java iframe
loads `client-resources.bin`, while the Rust body loads the tiny
`client-textures.bin`. `index.html?java=0` skips the Java iframe for renderer-only
debugging; `body.html` and `play.html` remain standalone.

`scripts/build.sh` prints every remaining TeaVM error (missing JDK/native APIs) — that list is the
porting worklist. `PORT_JAVA_OPTS=-Dport.why=<class prefix>` prints the call chain that pulls a
class into the build.

## Dedicated-server feasibility

The dedicated-server feasibility path is separate from the existing client build.
`scripts/fetch_server.py` downloads and hash-verifies Mojang's dedicated-server
bundle, extracts its runtime and bundled libraries, and confirms the server
entry-point classes are present without the client entry point.
`scripts/build_server_probe.sh` then compiles the existing built-in registry
bootstrap with the server runtime first on the TeaVM classpath, and CI executes
that bootstrap in headless Chrome. See
[docs/HEADLESS_SERVER_PIVOT.md](docs/HEADLESS_SERVER_PIVOT.md) for the remaining
server startup, data-pack, world-generation, and bridge gates.

This does not yet start a headless server or generate browser chunks.

## Layout

| Path | Purpose |
|---|---|
| `tools/src/PortBuilder.java` | TeaVM driver |
| `tools/src/PortTransformer.java` + `patches/rules.txt` | compile-time bytecode adaptations |
| `src/compat/java` | JDK gaps, TeaVM fixes, browser runtime (`mcport.rt`), library shims |
| `src/entry/java/mcport` | milestone entry points (`M1Main`, `M2Main`, `Probe`, `ClientMain`) |
| `src/compat/java/mcport/bridge` | Java→Rust chunk/palette/player bridge |
| `web/index.html` | hybrid page: Mojang loading screen + Rust canvas + Java iframe |
| `web/play.html` | Java client host page (supports `?embedded=1`) |
| `web/bridge.js` | Java ↔ Rust/iframe messages |
| `web/input.js` | desktop + mobile controls forwarded into Java SDL |
| `web/run.html` | host page: loads resources, runs an entry, exposes `window.__mc` |
| `scripts/` | dependency fetcher, build, resource packer, CDP test runner |
