# mc-browser-port

Running the **real Minecraft Java Edition 26.3 client** in a web browser by compiling Mojang's
unmodified client bytecode with [TeaVM](https://github.com/konsoletyper/teavm) and porting the
platform underneath it. See `docs/ARCHITECTURE.md` and `docs/MIGRATION_MATRIX.md`.

**Current state:** two halves move independently.

- **Java Brain.** The client's bootstrap (all built-in registries) runs in Chrome with results
  identical to the JVM; the port now gets past jopt-simple and into window/GPU creation.
  There is still no integrated server, world or gameplay in the browser build.
- **Rust Body** (`renderer/`). M2b renders a synthetic 8×8-chunk world with the real 26.3 block
  textures, vanilla face shading and fog, at 60 fps in an iPhone-sized viewport. It is a
  renderer test bed, not Minecraft: the world, camera and physics are still fake and will be
  replaced by Java state in M3.

This is not yet a playable client.

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

scripts/test.sh m1main 60                    # headless Chrome via DevTools protocol
scripts/test.sh m2main 180 --eval 'JSON.stringify(window.__mc)'
```

`scripts/build.sh` prints every remaining TeaVM error (missing JDK/native APIs) — that list is the
porting worklist. `PORT_JAVA_OPTS=-Dport.why=<class prefix>` prints the call chain that pulls a
class into the build.

## Layout

| Path | Purpose |
|---|---|
| `tools/src/PortBuilder.java` | TeaVM driver |
| `tools/src/PortTransformer.java` + `patches/rules.txt` | compile-time bytecode adaptations |
| `src/compat/java` | JDK gaps, TeaVM fixes, browser runtime (`mcport.rt`), library shims |
| `src/entry/java/mcport` | milestone entry points (`M1Main`, `M2Main`, `Probe`) |
| `web/run.html` | host page: loads resources, runs an entry, exposes `window.__mc` |
| `scripts/` | dependency fetcher, build, resource packer, CDP test runner |
