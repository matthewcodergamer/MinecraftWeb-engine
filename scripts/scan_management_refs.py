#!/usr/bin/env python3
"""Exhaustively find java.lang.management constant-pool references in a JAR and dump owners."""
import sys
import zipfile
import struct
import subprocess
from pathlib import Path

jar_path = Path(sys.argv[1])
output_path = Path(sys.argv[2])

def management_refs(data):
    if data[:4] != b"\xca\xfe\xba\xbe":
        return False
    pos = 8
    count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    found = False
    index = 1
    while index < count:
        tag = data[pos]
        pos += 1
        if tag == 1:  # CONSTANT_Utf8
            size = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            value = data[pos:pos + size].decode("utf-8", "replace")
            pos += size
            if "java/lang/management" in value or "java.lang.management" in value:
                found = True
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            raise ValueError(f"Unknown constant-pool tag {tag}")
        index += 1
    return found

with zipfile.ZipFile(jar_path) as archive:
    classes = []
    for name in archive.namelist():
        if not name.endswith(".class") or name.startswith("META-INF/versions/"):
            continue
        if management_refs(archive.read(name)):
            classes.append(name[:-6].replace("/", "."))

with output_path.open("w", encoding="utf-8") as out:
    out.write(f"JAR: {jar_path}\n")
    out.write(f"CLASS_COUNT_WITH_MANAGEMENT_REFERENCE: {len(classes)}\n")
    for class_name in classes:
        out.write(f"\n===== MANAGEMENT_REFERENCE_OWNER {class_name} =====\n")
        result = subprocess.run(
            ["javap", "-classpath", str(jar_path), "-p", "-s", "-c", "-v", class_name],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False
        )
        out.write(result.stdout)
        if result.returncode:
            out.write(f"\nJAVAP_EXIT_CODE: {result.returncode}\n")

print(f"MANAGEMENT_REFERENCE_CLASS_COUNT={len(classes)}")
for class_name in classes:
    print(f"MANAGEMENT_REFERENCE_OWNER {class_name}")
print(f"Wrote {output_path}")
