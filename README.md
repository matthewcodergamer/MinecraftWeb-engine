# mc-browser-port

Running the **real Minecraft Java Edition 26.3 client** in a web browser by compiling Mojang's
unmodified client bytecode with [TeaVM](https://github.com/konsoletyper/teavm) and porting the
platform underneath it. See `docs/ARCHITECTURE.md` and `docs/MIGRATION_MATRIX.md`.

**Current state:** the client's bootstrap (all built-in registries) runs in Chrome with results identical to
the JVM. There is no rendering, world or gameplay yet — this is not a playable client.

## Legal

Minecraft is proprietary software of Mojang/Microsoft. This repository contains **no Minecraft
code or assets**. The build reads the client jar from your own local installation, and everything
derived from it (`build/`, including `client-resources.bin` and the compiled JavaScript) must stay
local: do not publish or distribute it. Compatibility classes adapted from TeaVM keep their
Apache-2.0 headers.

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
