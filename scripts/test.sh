#!/bin/bash
# Serve build/web over HTTP and run an entry in headless Chrome via the DevTools Protocol.
# usage: scripts/test.sh <js-name> [timeout-sec] [extra cdp.py args...]
#   e.g. scripts/test.sh m2main 180 --eval 'JSON.stringify(window.__mc)'
set -euo pipefail
cd "$(dirname "$0")/.."
JS="${1:?js name}"
TIMEOUT="${2:-120}"
shift $(( $# >= 2 ? 2 : 1 ))
PORT="${PORT:-8765}"
cp web/*.html build/web/
MC_JAR="${MC_JAR:-$HOME/Library/Application Support/minecraft/versions/26.3/26.3.jar}"
[ -f build/web/client-resources.bin ] || python3 scripts/pack_resources.py "$MC_JAR" build/web/client-resources.bin
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory build/web >/dev/null 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null' EXIT
sleep 1
python3 scripts/cdp.py "http://127.0.0.1:$PORT/run.html?js=$JS" --timeout "$TIMEOUT" "$@"
