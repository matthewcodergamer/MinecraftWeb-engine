#!/bin/bash
# Build Mojang's real 26.3 client entry point (never the dedicated server).
# usage: MC_DIR=... JDK=... scripts/build_client_probe.sh
set -euo pipefail
cd "$(dirname "$0")/.."
MC_DIR="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
MC_VERSION="${MC_VERSION:-26.3}"
JDK="${JDK:-${JAVA_HOME:-}}"
[ -n "$JDK" ] || { echo "set JDK or JAVA_HOME to a Java 25 JDK" >&2; exit 1; }
export MC_DIR MC_VERSION JDK
[ -f "$MC_DIR/versions/$MC_VERSION/$MC_VERSION.jar" ] || {
  echo "missing client jar: $MC_DIR/versions/$MC_VERSION/$MC_VERSION.jar (run scripts/fetch_minecraft.py first)" >&2
  exit 1
}
exec bash scripts/build.sh mcport.ClientMain js
