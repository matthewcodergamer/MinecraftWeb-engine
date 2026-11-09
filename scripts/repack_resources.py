#!/usr/bin/env python3
"""Repack a client-resources.bin (MCRS) archive for web deployment.

The full Java resource archive is ~477 MB: 247 MB of music, 87 MB of
translations, 112 MB of SFX and ~29 MB of everything the client actually
needs to boot. GitHub rejects files over 100 MB and Pages caps a site at
1 GB, so the deployable archive keeps the engine data and the English
language file and drops the rest.

Format (see index.html parseArchive):
    0   "MCRS"
    4   uint32 LE  index length
    8   JSON index  { "path": [offset, size], ... }  (offset from payload start)
    ..  payload
"""
import argparse
import json
import os
import struct
import sys

MAGIC = b"MCRS"
SOUNDS_PREFIX = "assets/minecraft/sounds/"
MUSIC_PREFIX = "assets/minecraft/sounds/music/"
LANG_PREFIX = "assets/minecraft/lang/"


def read_index(path):
    with open(path, "rb") as f:
        magic = f.read(4)
        if magic != MAGIC:
            raise SystemExit(f"{path}: not an MCRS archive (magic {magic!r})")
        length = struct.unpack("<I", f.read(4))[0]
        index = json.loads(f.read(length))
        payload_start = 8 + length
        f.seek(0, os.SEEK_END)
        payload_end = f.tell()
    return index, payload_start, payload_end


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--keep-sfx", action="store_true",
                    help="keep sound effects (112 MB, still over the 100 MB limit)")
    ap.add_argument("--keep-music", action="store_true",
                    help="keep assets/minecraft/sounds/music (247 MB); implies --keep-sfx")
    ap.add_argument("--lang", action="append", default=None,
                    help="language file to keep (default: en_us.json)")
    args = ap.parse_args()

    keep_langs = set(args.lang or ["en_us.json"])

    index, payload_start, payload_end = read_index(args.src)
    expected = max(off + size for off, size in index.values())
    if expected != payload_end - payload_start:
        raise SystemExit(
            f"index does not cover payload: {expected} vs {payload_end - payload_start}")

    kept = {}
    dropped_music = dropped_sfx = dropped_lang = 0
    keep_sfx = args.keep_sfx or args.keep_music
    for key, (off, size) in index.items():
        # sounds.json is the registry, not audio: it lives beside the folder.
        if not keep_sfx and key.startswith(SOUNDS_PREFIX):
            if key.startswith(MUSIC_PREFIX):
                dropped_music += size
            else:
                dropped_sfx += size
            continue
        if not args.keep_music and key.startswith(MUSIC_PREFIX):
            dropped_music += size
            continue
        if key.startswith(LANG_PREFIX) and os.path.basename(key) not in keep_langs:
            dropped_lang += size
            continue
        kept[key] = (off, size)

    if "assets/minecraft/lang/en_us.json" not in kept:
        raise SystemExit("en_us.json missing from source archive")

    # Recompute offsets against the new, smaller index.
    new_index = {}
    cursor = 0
    for key, (off, size) in kept.items():
        new_index[key] = [cursor, size]
        cursor += size

    blob = json.dumps(new_index, separators=(",", ":")).encode("utf-8")
    tmp = args.dst + ".tmp"
    with open(args.src, "rb") as fin, open(tmp, "wb") as fout:
        fout.write(MAGIC)
        fout.write(struct.pack("<I", len(blob)))
        fout.write(blob)
        for key, (off, size) in kept.items():
            fin.seek(payload_start + off)
            chunk = fin.read(size)
            if len(chunk) != size:
                raise SystemExit(f"short read for {key}")
            fout.write(chunk)
    os.replace(tmp, args.dst)

    src_mb = os.path.getsize(args.src) / 1048576
    dst_mb = os.path.getsize(args.dst) / 1048576
    print(f"entries {len(index)} -> {len(kept)}")
    print(f"dropped music {dropped_music / 1048576:.1f} MB, "
          f"sfx {dropped_sfx / 1048576:.1f} MB, "
          f"translations {dropped_lang / 1048576:.1f} MB")
    print(f"{args.src}: {src_mb:.1f} MB -> {args.dst}: {dst_mb:.1f} MB")

    # Round-trip check with the same logic index.html uses.
    with open(args.dst, "rb") as f:
        assert f.read(4) == MAGIC
        length = struct.unpack("<I", f.read(4))[0]
        check = json.loads(f.read(length))
    base = 8 + length
    for key, (off, size) in check.items():
        if off < 0 or off + size > os.path.getsize(args.dst) - base:
            raise SystemExit(f"bad offset after repack: {key}")
    print(f"verified {len(check)} entries")


if __name__ == "__main__":
    main()
