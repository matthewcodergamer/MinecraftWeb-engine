#!/bin/bash
# Serve build/web over HTTP and run a page in headless Chrome; print what the ported code reported.
# usage: scripts/test.sh <js-name> [budget-ms]     e.g. scripts/test.sh m2main 120000
set -euo pipefail
cd "$(dirname "$0")/.."
JS="${1:?js name}"
BUDGET="${2:-30000}"
PORT="${PORT:-8765}"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
cp web/*.html build/web/
MC_JAR="${MC_JAR:-$HOME/Library/Application Support/minecraft/versions/26.3/26.3.jar}"
[ -f build/web/client-resources.bin ] || python3 scripts/pack_resources.py "$MC_JAR" build/web/client-resources.bin
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory build/web >/dev/null 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null' EXIT
sleep 1
PROFILE="$(mktemp -d)"
"$CHROME" --headless=new --disable-gpu --no-first-run --user-data-dir="$PROFILE" \
  --virtual-time-budget="$BUDGET" --dump-dom "http://127.0.0.1:$PORT/run.html?js=$JS" 2>/dev/null \
  | python3 -c "
import sys, html, re
d = sys.stdin.read()
for pid in ('log', 'err'):
    m = re.search(r'<pre id=\"%s\"[^>]*>(.*?)</pre>' % pid, d, re.S)
    if m and m.group(1).strip():
        print('--- #' + pid); print(html.unescape(m.group(1)).rstrip())
"
rm -rf "$PROFILE"
