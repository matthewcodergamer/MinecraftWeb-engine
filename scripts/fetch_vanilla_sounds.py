#!/usr/bin/env python3
"""Download the vanilla sound effects and build a range-addressable archive.

The browser client decodes OGG through WebAudio, which requires CORS. Mojang's
asset CDN does not send CORS headers, so the deployable sound bank lives on the
Hugging Face model repository and is read with HTTP Range requests. This script
turns the local Minecraft asset index (the same index the launcher used to
install 26.3) into:

    sounds.bin          concatenated OGG objects
    sounds-index.json   {"minecraft/sounds/foo.ogg": [offset, size], ...}

Music is deliberately excluded: the client already streams the menu tracks and
music is large enough that bundling it would dwarf the SFX bank.

Usage:
    python3 scripts/fetch_vanilla_sounds.py \
        --index /root/mcenv/assets/indexes/34.json \
        --out /root/mc-sounds/sounds.bin \
        --index-out web/sounds-index.json

The cache is keyed by Mojang object hash, so re-running after an interrupted
download only fetches the missing objects.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import struct
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

CDN = "https://resources.download.minecraft.net"
SOUNDS_PREFIX = "minecraft/sounds/"
MUSIC_PREFIX = "minecraft/sounds/music/"


def human(n: float) -> str:
    return f"{n / 1048576:.1f} MiB"


def fetch_one(args):
    key, entry, cache = args
    digest = entry["hash"]
    size = entry["size"]
    path = Path(cache) / digest[:2] / digest
    if path.is_file() and path.stat().st_size == size:
        return key, path, size, True
    path.parent.mkdir(parents=True, exist_ok=True)
    url = f"{CDN}/{digest[:2]}/{digest}"
    last = None
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "mc-browser-port/26.3"})
            with urllib.request.urlopen(req, timeout=60) as response:
                data = response.read()
            if len(data) != size:
                raise IOError(f"short read {len(data)} != {size}")
            tmp = path.with_suffix(".part")
            tmp.write_bytes(data)
            os.replace(tmp, path)
            return key, path, size, False
        except Exception as exc:  # noqa: BLE001 - retry all network failures
            last = exc
            time.sleep(0.4 * (attempt + 1))
    raise RuntimeError(f"{key}: {last}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--index", default=os.environ.get("MC_ASSET_INDEX", "/root/mcenv/assets/indexes/34.json"))
    ap.add_argument("--cache", default="/root/mc-sounds-cache")
    ap.add_argument("--out", default="/root/mc-sounds/sounds.bin")
    ap.add_argument("--index-out", default="web/sounds-index.json")
    ap.add_argument("--workers", type=int, default=24)
    args = ap.parse_args()

    source = json.loads(Path(args.index).read_text())["objects"]
    selected = {
        key: entry
        for key, entry in source.items()
        if key.startswith(SOUNDS_PREFIX)
        and not key.startswith(MUSIC_PREFIX)
        and key.endswith(".ogg")
    }
    if not selected:
        print("no sounds selected", file=sys.stderr)
        return 1

    total = sum(entry["size"] for entry in selected.values())
    print(f"selected {len(selected)} OGG objects, {human(total)}")

    ordered = sorted(selected.items())
    started = time.time()
    paths: dict[str, Path] = {}
    downloaded = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(fetch_one, (key, entry, args.cache)) for key, entry in ordered]
        for i, future in enumerate(concurrent.futures.as_completed(futures), 1):
            key, path, size, cached = future.result()
            paths[key] = path
            if not cached:
                downloaded += size
            if i % 250 == 0 or i == len(futures):
                elapsed = max(0.001, time.time() - started)
                print(f"  {i}/{len(futures)}  fetched {human(downloaded)}  ({human(downloaded / elapsed)}/s)")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    index = {}
    cursor = 0
    with out.open("wb") as sink:
        for key, _entry in ordered:
            data = paths[key].read_bytes()
            index[key] = [cursor, len(data)]
            sink.write(data)
            cursor += len(data)

    index_path = Path(args.index_out)
    index_path.parent.mkdir(parents=True, exist_ok=True)
    index_path.write_text(json.dumps(index, separators=(",", ":")))
    print(f"wrote {out} ({human(cursor)}) and {index_path} ({index_path.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
