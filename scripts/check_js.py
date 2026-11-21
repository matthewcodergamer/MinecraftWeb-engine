#!/usr/bin/env python3
"""Finds classes the generated JS uses as superclass/interface but never defines (they break script load)."""
import os
import re
import sys

# The WebAssembly build has no clientmain.js; this scan only applies to the
# JavaScript backend. Default to the generated bundle and skip cleanly when the
# workflow is checking a Wasm build.
path = sys.argv[1] if len(sys.argv) > 1 else "build/web/clientmain.js"
if not os.path.isfile(path):
    print(f"no JavaScript client bundle at {path}; skipping supertype scan")
    sys.exit(0)

s = open(path).read()
declared = set(re.findall(r'(?:^|[\s,;(])([A-Za-z_$][\w$]*)\s*=\s*', s, re.M)) | set(re.findall(r'function\s+([A-Za-z_$][\w$]*)\s*\(', s))
missing = set()
for m in re.finditer(r'^\s*([\w$]+), "[^"]*", -?\d+, ([\w$]+), \[([^\]]*)\]', s, re.M):
    for n in [m.group(2)] + [x.strip() for x in m.group(3).split(',') if x.strip()]:
        if n not in declared and n != '0' and not n.startswith('$rt_java.'):
            missing.add(n)
for pat in [r'\b([\w$]+)\.call\(a\)', r'\$rt_classWith(?:out)?Fields\(([\w$]+)\)']:
    for n in re.findall(pat, s):
        if n not in declared and not n.isdigit():
            missing.add(n)
print('undefined supertypes:', sorted(missing) or 'none')
sys.exit(1 if missing else 0)
