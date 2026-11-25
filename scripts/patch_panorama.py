#!/usr/bin/env python3
"""Replace the six title-screen panorama entries in a packed MCRS archive.

Minecraft 26.3's client jar ships 1x1 grey placeholders for these textures.
The real 1024x1024 faces are Mojang asset objects, so a CI deployment that
preserves the live archive (for sounds, languages and tutorial toasts) would
otherwise keep the grey title screen. This rewrites only the six panorama
entries from the PNGs committed under web/ui/panorama/.
"""
from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path

MAGIC = b"MCRS"
KEYS = tuple(
    f"assets/minecraft/textures/gui/title/background/panorama_{i}.png"
    for i in range(6)
)
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


def read_archive(path: Path) -> tuple[dict[str, list[int]], bytes]:
    with path.open("rb") as archive:
        if archive.read(4) != MAGIC:
            raise SystemExit(f"{path} is not a packed Minecraft resource archive")
        header = archive.read(4)
        if len(header) != 4:
            raise SystemExit(f"{path} has a truncated archive header")
        length = struct.unpack("<I", header)[0]
        if length <= 0 or length > 64 * 1024 * 1024:
            raise SystemExit(f"{path} has an invalid archive index length {length}")
        index = json.loads(archive.read(length))
        data = archive.read()
    return index, data


def png_size(blob: bytes) -> tuple[int, int]:
    if len(blob) < 24 or blob[:8] != PNG_MAGIC or blob[12:16] != b"IHDR":
        raise ValueError("not a PNG with an IHDR chunk")
    return struct.unpack(">II", blob[16:24])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", help="packed minecraft-java-edition-v26.3-assets.bin")
    parser.add_argument(
        "panorama_dir",
        nargs="?",
        default="web/ui/panorama",
        help="directory containing panorama_0.png .. panorama_5.png",
    )
    args = parser.parse_args()

    archive = Path(args.archive)
    panorama_dir = Path(args.panorama_dir)
    index, data = read_archive(archive)

    missing = [key for key in KEYS if key not in index]
    if missing:
        raise SystemExit("archive is missing panorama entries:\n  " + "\n  ".join(missing))

    replacements: dict[str, bytes] = {}
    for i, key in enumerate(KEYS):
        path = panorama_dir / f"panorama_{i}.png"
        try:
            blob = path.read_bytes()
        except OSError as error:
            raise SystemExit(f"cannot read {path}: {error}") from error
        width, height = png_size(blob)
        if width < 512 or height < 512:
            raise SystemExit(f"{path} is only {width}x{height}; refusing to deploy a placeholder")
        replacements[key] = blob

    out = bytearray()
    new_index: dict[str, list[int]] = {}
    for key, value in index.items():
        offset, size = int(value[0]), int(value[1])
        blob = replacements.get(key)
        if blob is None:
            blob = data[offset:offset + size]
        new_index[key] = [len(out), len(blob)]
        out.extend(blob)

    head = json.dumps(new_index, separators=(",", ":"), sort_keys=True).encode("utf-8")
    temp = archive.with_name(archive.name + ".panorama-tmp")
    with temp.open("wb") as output:
        output.write(MAGIC)
        output.write(struct.pack("<I", len(head)))
        output.write(head)
        output.write(out)
    temp.replace(archive)

    for key in KEYS:
        width, height = png_size(replacements[key])
        print(f"panorama {key.rsplit('/', 1)[-1]}: {width}x{height}, {len(replacements[key]):,} bytes")
    print(f"patched {archive}: {archive.stat().st_size:,} bytes, {len(new_index):,} entries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
