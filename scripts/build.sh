#!/bin/bash
# Build a browser artifact from the real Minecraft client bytecode.
# usage: scripts/build.sh <EntryClass> [js|wasm]
#   e.g. scripts/build.sh mcport.M1Main js
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

MC_DIR="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
MC_VERSION="${MC_VERSION:-26.3}"
MC_JAR="$MC_DIR/versions/$MC_VERSION/$MC_VERSION.jar"
MC_SERVER_JAR="${MC_SERVER_JAR:-}"
MC_SERVER_LIB_LIST="${MC_SERVER_LIB_LIST:-$MC_DIR/versions/$MC_VERSION/server-libraries.list}"
MC_JSON="$MC_DIR/versions/$MC_VERSION/$MC_VERSION.json"
JDK="${JDK:-$MC_DIR/runtime/java-runtime-epsilon/osx/java-runtime-epsilon/jre.bundle/Contents/Home}"
ENTRY="${1:?entry class}"
TARGET="${2:-js}"
OUT="$ROOT/build/web"

[ -f "$MC_JAR" ] || { echo "missing client jar: $MC_JAR" >&2; exit 1; }
SERVER_LIBS=()
if [ -n "$MC_SERVER_JAR" ]; then
  [ -f "$MC_SERVER_JAR" ] || { echo "missing dedicated server jar: $MC_SERVER_JAR" >&2; exit 1; }
  [ -f "$MC_SERVER_LIB_LIST" ] || { echo "missing server library manifest: $MC_SERVER_LIB_LIST (run scripts/fetch_server.py first)" >&2; exit 1; }
  while IFS= read -r relative; do
    [ -n "$relative" ] && SERVER_LIBS+=("$MC_DIR/libraries/$relative")
  done < "$MC_SERVER_LIB_LIST"
  for library in "${SERVER_LIBS[@]}"; do
    [ -f "$library" ] || { echo "missing server library: $library" >&2; exit 1; }
  done
fi
"$JDK/bin/java" -version 2>&1 | head -1

# Minecraft's declared libraries (Java parts only; native jars are irrelevant in a browser).
MC_LIBS=$(python3 - "$MC_JSON" "$MC_DIR/libraries" <<'EOF'
import json, os, sys
d = json.load(open(sys.argv[1]))
out = []
for l in d["libraries"]:
    g, a, v, *cls = l["name"].split(":")
    if cls:
        continue
    # Libraries that cannot run in a browser; their call sites are ported to browser APIs instead.
    if a in ("log4j-core", "log4j-slf4j2-impl", "log4j-api", "jna", "jna-platform", "oshi-core", "msal4j", "azure-json",
             "java-objc-bridge", "text2speech", "netty-transport-classes-epoll",
             "netty-transport-classes-kqueue", "netty-transport-native-unix-common"):
        continue
    p = os.path.join(sys.argv[2], *g.split("."), a, v, f"{a}-{v}.jar")
    if os.path.exists(p):
        out.append(p)
print(":".join(out))
EOF
)

TEAVM_CP=$(ls "$ROOT"/tools/lib/*.jar | tr '\n' ':')
mkdir -p build/tools build/classes "$OUT"

"$JDK/bin/javac" -d build/tools -cp "$TEAVM_CP" $(find tools/src -name '*.java')

SRCS=$(find src/entry/java src/compat/java -name '*.java' 2>/dev/null || true)
# Compile-only signatures of TeaVM classlib extensions that live under java.* names (never given to TeaVM).
rm -rf build/stubs && mkdir -p build/stubs
"$JDK/bin/javac" --patch-module java.base=src/compat/java-stubs -d build/stubs $(find src/compat/java-stubs -name '*.java')
"$JDK/bin/javac" --patch-module java.base=build/stubs -d build/classes \
  -cp "$MC_JAR:$MC_LIBS:$ROOT/tools/lib/teavm-jso-0.16.0.jar:$ROOT/tools/lib/teavm-interop-0.16.0.jar:$ROOT/tools/lib/teavm-classlib-0.16.0.jar:$ROOT/tools/lib/teavm-jso-apis-0.16.0.jar" $SRCS

# Types that live in JDK packages TeaVM does not rename (javax.*) are compiled into those packages.
OVERLAY=$(find src/compat/java-base-overlay -name '*.java' 2>/dev/null || true)
[ -n "$OVERLAY" ] && "$JDK/bin/javac" --release 25 --patch-module java.base=src/compat/java-base-overlay -d build/classes $OVERLAY
cp -R src/compat/resources/. build/classes/ 2>/dev/null || true

# Compat classes come first so they shadow the original library classes.
IFS=: read -ra LIBARR <<< "$MC_LIBS"
MC_TEA_CP=()
if [ -n "$MC_SERVER_JAR" ]; then
  MC_TEA_CP+=("$MC_SERVER_JAR" "${SERVER_LIBS[@]}")
fi
MC_TEA_CP+=("$MC_JAR" "${LIBARR[@]}")
# TeaVM's Java class library + JS interop runtime (what replaces the JDK in the browser).
TEAVM_RT=()
for a in core classlib jso jso-apis jso-impl interop platform metaprogramming-api metaprogramming-impl extension-apis; do
  TEAVM_RT+=("$ROOT/tools/lib/teavm-$a-0.16.0.jar")
done
# TeaVM's java.util.zip is implemented on top of jzlib.
TEAVM_RT+=("$ROOT/tools/lib/jzlib-1.1.3.jar")
NAME="$(echo "${ENTRY##*.}" | tr 'A-Z' 'a-z').${TARGET/wasm/wasm}"
[ "$TARGET" = js ] && NAME="${NAME%.js}.js"
time "$JDK/bin/java" -Xss1g -Xmx${PORT_HEAP:-3g} -Dport.rules="$ROOT/patches/rules.txt" -Dport.lenientLinkage=${PORT_LENIENT:-true} ${PORT_JAVA_OPTS:-} -cp "build/tools:$TEAVM_CP" PortBuilder "$TARGET" "$ENTRY" "$OUT" "$NAME" \
  "$ROOT/build/classes" "${MC_TEA_CP[@]}" "${TEAVM_RT[@]}" 2>&1 | tee "build/$ENTRY.$TARGET.log" | grep -vE "^\s*at " | tail -${PORT_LOG_LINES:-60}
exit "${PIPESTATUS[0]}"
