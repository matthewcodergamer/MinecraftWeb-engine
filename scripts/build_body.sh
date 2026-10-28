#!/bin/bash
# Build the Rust/WebGPU Body and stage it into build/web (next to the client jar's
# resource archive) so `scripts/serve.sh` can serve it at /body.html.
set -euo pipefail
cd "$(dirname "$0")/.."

cd renderer
cargo build --release --target wasm32-unknown-unknown
TARGET_DIR="${CARGO_TARGET_DIR:-target}"
WASM="$TARGET_DIR/wasm32-unknown-unknown/release/mc_body.wasm"
[ -f "$WASM" ] || WASM="target/wasm32-unknown-unknown/release/mc_body.wasm"
wasm-bindgen --target web --out-dir pkg --out-name mc_body "$WASM"

cd ..
mkdir -p build/web/pkg
cp renderer/pkg/mc_body.js renderer/pkg/mc_body_bg.wasm renderer/pkg/mc_body.d.ts build/web/pkg/
cp renderer/index.html build/web/body.html
ls -l build/web/pkg/mc_body_bg.wasm | awk '{print "body wasm:", $5, "bytes"}'
echo "open http://localhost:${PORT:-8000}/body.html"
