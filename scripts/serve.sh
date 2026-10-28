#!/bin/bash
# Serve the browser port on http://localhost:${PORT:-8000}/play.html (local use only).
# Needs a build (scripts/build.sh mcport.ClientMain js) and your own Minecraft 26.3 installation.
set -euo pipefail
cd "$(dirname "$0")/.."
MC_JAR="${MC_JAR:-$HOME/Library/Application Support/minecraft/versions/26.3/26.3.jar}"
mkdir -p build/web
cp -R web/. build/web/
[ -f build/web/client-resources.bin ] || python3 scripts/pack_resources.py "$MC_JAR" build/web/client-resources.bin
PORT="${PORT:-8000}"
echo "Open http://localhost:$PORT/play.html    (Java client)"
[ -f build/web/body.html ] && echo "     http://localhost:$PORT/body.html    (Rust/WebGPU body)"
echo "     ?gpu=webgl2 forces the fallback, ?scale=1 sets render scale"
exec python3 -m http.server "$PORT" --bind 127.0.0.1 --directory build/web
