#!/usr/bin/env python3
"""Pack the non-class files of the local Minecraft client jar into build/web/client-resources.bin.

Format: b"MCRS" | u32 LE index length | UTF-8 JSON index {path: [offset, length]} | raw bytes.
The output contains Mojang assets copied from the user's own client jar: local use only,
never commit or publish it (build/ is git-ignored).
"""
import json
import os
import struct
import sys
import zipfile

jar, out = sys.argv[1], sys.argv[2]
index, blobs, offset = {}, [], 0
with zipfile.ZipFile(jar) as z:
    for info in z.infolist():
        name = info.filename
        if info.is_dir() or name.endswith(".class") or name.startswith("META-INF/"):
            continue
        data = z.read(info)
        index[name] = [offset, len(data)]
        blobs.append(data)
        offset += len(data)
head = json.dumps(index, separators=(",", ":")).encode()
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "wb") as f:
    f.write(b"MCRS")
    f.write(struct.pack("<I", len(head)))
    f.write(head)
    for b in blobs:
        f.write(b)
print(f"packed {len(index)} resources, {offset / 1048576:.1f} MiB -> {out}")
