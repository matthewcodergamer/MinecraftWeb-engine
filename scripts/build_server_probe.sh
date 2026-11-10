#!/bin/bash
# Compile the real built-in Minecraft bootstrap with the dedicated server runtime first.
# This is a classpath/TeaVM feasibility probe, not yet a full server or world-generation launch.
set -euo pipefail
cd "$(dirname "$0")/.."
MC_VERSION="${MC_VERSION:-26.3}"
MC_DIR="${MC_DIR:-$HOME/.minecraft}"
export MC_SERVER_JAR="${MC_SERVER_JAR:-$MC_DIR/versions/$MC_VERSION/server.jar}"
export MC_SERVER_LIB_LIST="${MC_SERVER_LIB_LIST:-$MC_DIR/versions/$MC_VERSION/server-libraries.list}"
[ -f "$MC_SERVER_JAR" ] || { echo "missing server runtime: $MC_SERVER_JAR (run scripts/fetch_server.py first)" >&2; exit 1; }
[ -f "$MC_SERVER_LIB_LIST" ] || { echo "missing server library list: $MC_SERVER_LIB_LIST (run scripts/fetch_server.py first)" >&2; exit 1; }
exec bash scripts/build.sh mcport.M2Main js
