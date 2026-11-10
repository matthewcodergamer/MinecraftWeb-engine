# Dedicated-server pivot: feasibility and staged implementation

## What is established

Minecraft 26.3 has an official Mojang dedicated-server download. It is a bundled JAR:
the outer archive contains a versioned server runtime and separately bundled Java
libraries. The runtime must be extracted before TeaVM can use it as a classpath JAR.

`scripts/fetch_server.py` performs that acquisition without executing the artifact:

1. Resolves the requested release through Mojang's version manifest.
2. Downloads the official `downloads.server` artifact and verifies Mojang's SHA-1 and size.
3. Reads `META-INF/versions.list` and extracts the matching versioned runtime.
4. Verifies the extracted runtime against the bundle's SHA-256.
5. Reads `META-INF/libraries.list`, extracts each bundled library, and verifies its SHA-256.
6. Fails unless `net.minecraft.server.Main` and `net.minecraft.server.level.ServerLevel`
   exist, and the client entry point `net.minecraft.client.Minecraft` is absent.

Run it with:

```sh
python3 scripts/fetch_server.py --version 26.3 --dest "$MC_DIR"
python3 -m unittest discover -s scripts -p 'test_fetch_server.py' -v
```

The expected output includes:

- `$MC_DIR/versions/26.3/server-bundled.jar` — original Mojang bundle
- `$MC_DIR/versions/26.3/server.jar` — extracted, hash-verified dedicated-server runtime
- `$MC_DIR/libraries/` — extracted, hash-verified bundled Java libraries

The `Verify dedicated server artifact` GitHub Actions workflow runs the offline
extractor tests and fetches/verifies the official 26.3 artifact.

## What is not established yet

This artifact milestone does **not** claim that the server runs under TeaVM or that
world generation works in the browser. The existing `scripts/build.sh` still targets
the client JAR and client entry points. The existing `ChunkBridge` still imports
`Minecraft`, `TitleScreen`, and `FrameLoop`.

The migration matrix also records these prerequisites as unfinished:

- read-only NIO/JAR filesystem mounting
- resource manager and vanilla data-pack loading
- data-pack and world-generation registry loading
- server-compatible thread/executor behavior
- a server-specific bridge that reads `ServerLevel` chunks and sends the existing
  block-state palette and section format to the JavaScript/Rust receiver

Do not replace these dependencies with no-op resource packs or fake successful
graphics APIs. The server should not load client graphics in the first place, and
world generation must receive real vanilla data and registries.

## Next implementation gates

1. Add a separate server build mode that places the extracted server runtime and
   its bundled libraries ahead of the client classpath, without changing the
   existing client build.
2. Add a minimal server entry point and use TeaVM diagnostics to identify the
   actual server-side linkage/native gaps.
3. Implement the read-only resource/data-pack filesystem required by the server.
4. Start a real server level and wait for a generated chunk.
5. Replace the client bridge with a server bridge that emits the same section
   payload contract consumed by Rust.
6. Verify a real generated section reaches Rust/WebGPU in a browser run.

Each gate needs its own evidence. A successful artifact download, TeaVM compile,
or CI upload is not evidence of real chunk generation.
