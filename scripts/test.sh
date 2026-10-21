#!/bin/bash
# Run a built page in headless Chrome and print what the ported code reported.
# usage: scripts/test.sh <page.html> [budget-ms]
set -euo pipefail
cd "$(dirname "$0")/.."
PAGE="${1:?page}"
BUDGET="${2:-20000}"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
cp web/*.html build/web/
PROFILE="$(mktemp -d)"
"$CHROME" --headless=new --disable-gpu --no-first-run --user-data-dir="$PROFILE" \
  --allow-file-access-from-files --virtual-time-budget="$BUDGET" --run-all-compositor-stages-before-draw \
  --dump-dom "file://$PWD/build/web/$PAGE" 2>/dev/null \
  | python3 -c "
import sys, html, re
d = sys.stdin.read()
for pid in ('log', 'err'):
    m = re.search(r'<pre id=\"%s\"[^>]*>(.*?)</pre>' % pid, d, re.S)
    if m and m.group(1).strip():
        print('--- #' + pid); print(html.unescape(m.group(1)).rstrip())
"
rm -rf "$PROFILE"
