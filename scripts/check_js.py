#!/usr/bin/env python3
"""Finds classes the generated JS uses as superclass/interface but never defines (they break script load)."""
import re
import sys

s = open(sys.argv[1]).read()
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
