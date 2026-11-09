#!/usr/bin/env python3
"""Split clientmain.js into GitHub/HF-safe parts plus a manifest.

GitHub Pages rejects files over 100 MB, and the TeaVM client bundle is ~266 MiB.
The bundle compresses ~12x with gzip, so we ship gzip parts and reassemble +
decompress in the browser with DecompressionStream. Usage:

    python3 scripts/split_clientmain.py build/web/clientmain.js ../old_mcweb
    python3 scripts/split_clientmain.py --no-gzip build/web/clientmain.js out
"""
import gzip
import hashlib
import json
import os
import sys

PART = 8 * 1024 * 1024


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    use_gzip = "--no-gzip" not in sys.argv
    src = args[0] if args else "build/web/clientmain.js"
    out = args[1] if len(args) > 1 else os.path.dirname(src)

    raw = open(src, "rb").read()
    data = gzip.compress(raw, 9) if use_gzip else raw
    os.makedirs(out, exist_ok=True)

    parts = []
    for index, start in enumerate(range(0, len(data), PART)):
        chunk = data[start:start + PART]
        name = "clientmain.part%d" % index
        with open(os.path.join(out, name), "wb") as f:
            f.write(chunk)
        parts.append({
            "name": name,
            "size": len(chunk),
            "sha256": hashlib.sha256(chunk).hexdigest(),
        })

    manifest = {
        "encoding": "gzip" if use_gzip else "identity",
        "total": len(raw),
        "downloadTotal": len(data),
        "partSize": PART,
        "parts": parts,
    }
    with open(os.path.join(out, "clientmain.parts.json"), "w") as f:
        json.dump(manifest, f, indent=2)

    print("split %s (raw %d, shipped %d bytes) -> %d parts in %s"
          % (src, len(raw), len(data), len(parts), out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
