#!/usr/bin/env python3
"""Download every vanilla Minecraft 26.3 texture from Mojang's official asset index.

Writes PNGs under ui/assets/full/textures/... and a machine-readable
ui/assets/full/texture-index.json used by asset-atlas.html.
"""
from __future__ import annotations

import concurrent.futures
import hashlib
import json
import os
import pathlib
import sys
import urllib.request

VERSION = os.environ.get("MC_VERSION", "26.3")
ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "web" / "ui" / "assets" / "full"
MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
OBJECT_BASE = "https://resources.download.minecraft.net"

def get_json(url: str) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": "MinecraftWeb-AssetExtractor/1.0"})
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)

def download_one(item: tuple[str, dict]) -> tuple[str, int]:
    logical, spec = item
    digest = spec["hash"]
    relative = logical.removeprefix("minecraft/")
    target = OUT / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_file() and hashlib.sha1(target.read_bytes()).hexdigest() == digest:
        return logical, target.stat().st_size

    url = f"{OBJECT_BASE}/{digest[:2]}/{digest}"
    req = urllib.request.Request(url, headers={"User-Agent": "MinecraftWeb-AssetExtractor/1.0"})
    with urllib.request.urlopen(req, timeout=90) as response:
        data = response.read()
    actual = hashlib.sha1(data).hexdigest()
    if actual != digest:
        raise RuntimeError(f"SHA-1 mismatch for {logical}: expected {digest}, got {actual}")
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError(f"Object for {logical} is not a PNG")
    target.write_bytes(data)
    return logical, len(data)

def main() -> int:
    print(f"Resolving official Minecraft {VERSION} metadata from Mojang...")
    manifest = get_json(MANIFEST_URL)
    version = next((v for v in manifest["versions"] if v["id"] == VERSION), None)
    if not version:
        raise SystemExit(f"Minecraft version {VERSION!r} was not found in Mojang's manifest")
    metadata = get_json(version["url"])
    index_info = metadata["assetIndex"]
    asset_index = get_json(index_info["url"])
    objects = asset_index.get("objects", {})
    textures = {
        name: spec for name, spec in objects.items()
        if name.startswith("minecraft/textures/") and name.lower().endswith(".png")
    }
    if not textures:
        raise SystemExit("No minecraft/textures/*.png entries found in official asset index")
    print(f"Official index {index_info['id']}: {len(textures)} PNG textures")
    OUT.mkdir(parents=True, exist_ok=True)
    failures = []
    completed = 0
    total_bytes = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        futures = {pool.submit(download_one, item): item[0] for item in textures.items()}
        for future in concurrent.futures.as_completed(futures):
            try:
                _, size = future.result()
                completed += 1
                total_bytes += size
                if completed % 100 == 0 or completed == len(textures):
                    print(f"Downloaded/verified {completed}/{len(textures)} textures")
            except Exception as exc:
                failures.append((futures[future], str(exc)))
                print(f"FAILED {futures[future]}: {exc}", file=sys.stderr)

    index = {
        "version": VERSION,
        "asset_index": index_info["id"],
        "source": index_info["url"],
        "texture_count": len(textures),
        "downloaded_count": completed,
        "total_bytes": total_bytes,
        "textures": [
            {
                "path": name.removeprefix("minecraft/"),
                "sha1": spec["hash"],
                "size": spec.get("size", 0),
                "category": name.split("/")[2] if len(name.split("/")) > 2 else "other",
            }
            for name, spec in sorted(textures.items())
        ],
    }
    (OUT / "texture-index.json").write_text(
        json.dumps(index, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Verified {completed}/{len(textures)} textures; {total_bytes / 1048576:.1f} MiB")
    if failures:
        print(f"{len(failures)} download(s) failed; refusing to report a complete extraction", file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
