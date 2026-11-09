#!/usr/bin/env python3
"""Pack the *whole* vanilla game resource set into build/web/client-resources.bin.

What goes in
------------
1. Every non-class file in the version jar (blocks, items, GUI, models, blockstates,
   fonts, shaders, data packs, version.json, ...).
2. Every object named by the version's asset index (sounds, music, languages,
   sounds.json, icons, ...), stored under the logical path Minecraft asks for
   (`assets/minecraft/sounds/...`, `assets/minecraft/lang/...`).
3. The asset index itself (`assets/indexes/34.json`) plus a version alias
   (`assets/indexes/26.3.json`) so both the launcher-style and human-style names work.

Format (unchanged, so the Java and Rust readers keep working)
--------------------------------------------------------------
    b"MCRS" | u32 LE index length | UTF-8 JSON {logical_path: [offset, length]} | raw bytes

The file is streamed: it is never held in Python memory as one 500 MB blob.
Mojang assets are copied from the user's own installation. The output lives in
build/ (git-ignored) and must never be committed or published.

Usage
-----
    # defaults: ~/Library/Application Support/minecraft, version 26.3,
    #           output build/web/client-resources.bin
    python3 scripts/pack_resources.py

    python3 scripts/pack_resources.py --mc-dir "$HOME/Library/Application Support/minecraft" \\
        --version 26.3 --out build/web/client-resources.bin

    # old two-argument form still works
    python3 scripts/pack_resources.py <version.jar> <output.bin>

Options
-------
    --jar-only       pack only the version jar (fast smoke test; no sounds/langs)
    --verify         SHA-1 check every external asset object while packing
    --list           print the logical path of every packed file
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import struct
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

MAGIC = b"MCRS"
DEFAULT_MC_DIR = Path.home() / "Library" / "Application Support" / "minecraft"
DEFAULT_VERSION = "26.3"
DEFAULT_OUT = Path("build/web/client-resources.bin")


@dataclass(frozen=True)
class Entry:
    """One logical file in the archive and where its bytes come from."""

    name: str
    size: int
    source: str          # "jar" or "file"
    location: str        # zip member name, or absolute filesystem path


def natural_version_key(name: str) -> tuple:
    parts = []
    for chunk in name.replace("-", ".").replace("_", ".").split("."):
        parts.append((0, int(chunk)) if chunk.isdigit() else (1, chunk))
    return tuple(parts)


def find_installation(mc_dir: Path, version: str | None) -> tuple[Path, Path, dict]:
    versions = mc_dir / "versions"
    if not versions.is_dir():
        raise SystemExit(f"not a Minecraft installation (missing {versions})")
    if version:
        candidates = [versions / version]
    else:
        candidates = sorted(
            (p for p in versions.iterdir() if (p / f"{p.name}.json").is_file()),
            key=lambda p: natural_version_key(p.name),
            reverse=True,
        )
    for folder in candidates:
        jar = folder / f"{folder.name}.jar"
        meta = folder / f"{folder.name}.json"
        if jar.is_file() and meta.is_file():
            with meta.open("r", encoding="utf-8") as fh:
                return jar, meta, json.load(fh)
    raise SystemExit(f"no usable version under {versions} (looked for {version or 'any version'})")


def jar_entries(jar: Path) -> list[Entry]:
    out: list[Entry] = []
    with zipfile.ZipFile(jar) as zf:
        for info in zf.infolist():
            name = info.filename
            if info.is_dir() or name.endswith(".class") or name.startswith("META-INF/"):
                continue
            if name.endswith(".DS_Store") or name == ".DS_Store":
                continue
            out.append(Entry(name, info.file_size, "jar", name))
    return out


def object_entries(mc_dir: Path, meta: dict, already: set[str], verify: bool) -> list[Entry]:
    """Resolve every asset-index object to its logical `assets/...` path."""
    index_info = meta.get("assetIndex") or {}
    index_id = index_info.get("id")
    if not index_id:
        print("warning: version metadata has no assetIndex; external assets skipped", file=sys.stderr)
        return []

    index_path = mc_dir / "assets" / "indexes" / f"{index_id}.json"
    if not index_path.is_file():
        # Fall back to the index the launcher actually downloaded, if the named one is absent.
        indexes = sorted((mc_dir / "assets" / "indexes").glob("*.json"))
        if not indexes:
            print(f"warning: no asset index in {mc_dir / 'assets' / 'indexes'}", file=sys.stderr)
            return []
        index_path = indexes[-1]
        print(f"warning: {index_id}.json missing, using {index_path.name}", file=sys.stderr)

    with index_path.open("r", encoding="utf-8") as fh:
        objects = json.load(fh).get("objects", {})

    out: list[Entry] = []
    missing = 0
    for logical_name, spec in sorted(objects.items()):
        logical = f"assets/{logical_name}"
        if logical in already:
            continue  # the jar's copy is identical and already packed
        digest = spec.get("hash", "")
        size = int(spec.get("size", 0))
        if len(digest) < 2:
            continue
        path = mc_dir / "assets" / "objects" / digest[:2] / digest
        if not path.is_file():
            missing += 1
            continue
        if verify:
            h = hashlib.sha1()
            with path.open("rb") as fh:
                for block in iter(lambda: fh.read(1 << 20), b""):
                    h.update(block)
            if h.hexdigest() != digest:
                print(f"warning: SHA-1 mismatch for {logical_name}, skipping", file=sys.stderr)
                missing += 1
                continue
        actual_size = path.stat().st_size
        if size and actual_size != size:
            print(f"warning: {logical_name}: index size {size} != file {actual_size}", file=sys.stderr)
        out.append(Entry(logical, actual_size, "file", str(path)))
    if missing:
        print(f"warning: {missing} asset object(s) missing from {mc_dir / 'assets' / 'objects'}",
              file=sys.stderr)
    return out


def index_file_entries(mc_dir: Path, meta: dict, version: str) -> list[Entry]:
    """Pack the asset index under its canonical id and a version-named alias."""
    index_id = (meta.get("assetIndex") or {}).get("id")
    if not index_id:
        return []
    path = mc_dir / "assets" / "indexes" / f"{index_id}.json"
    if not path.is_file():
        return []
    size = path.stat().st_size
    out = [Entry(f"assets/indexes/{index_id}.json", size, "file", str(path))]
    # The user/launcher-facing name requested by the port: assets/indexes/26.3.json.
    alias = f"assets/indexes/{version}.json"
    if alias != f"assets/indexes/{index_id}.json":
        out.append(Entry(alias, size, "file", str(path)))
    return out


def dedupe(entries: Iterable[Entry]) -> list[Entry]:
    """First writer wins (the jar is authoritative), and names are sorted for reproducible output."""
    seen: set[str] = set()
    kept: list[Entry] = []
    for entry in entries:
        if entry.name in seen:
            continue
        seen.add(entry.name)
        kept.append(entry)
    kept.sort(key=lambda e: e.name)
    return kept


def read_entry(entry: Entry, zf: zipfile.ZipFile | None) -> bytes:
    if entry.source == "jar":
        if zf is None:
            raise RuntimeError("jar closed before reading")
        return zf.read(entry.location)
    with open(entry.location, "rb") as fh:
        return fh.read()


def category(name: str) -> str:
    if "/textures/block/" in name:
        return "block textures"
    if "/textures/item/" in name:
        return "item textures"
    if "/textures/gui/" in name:
        return "GUI textures"
    if "/textures/" in name:
        return "other textures"
    if "/sounds/" in name or name.endswith(".ogg"):
        return "sounds"
    if "/lang/" in name:
        return "languages"
    if "/models/" in name:
        return "models"
    if "/blockstates/" in name:
        return "blockstates"
    if "/shaders/" in name or name.endswith((".vsh", ".fsh", ".glsl")):
        return "shaders"
    if name.startswith("data/"):
        return "data packs"
    if name.startswith("assets/indexes/"):
        return "asset indexes"
    return "other"


def write_archive(path: Path, entries: list[Entry], zf: zipfile.ZipFile) -> None:
    """Write the MCRS archive for `entries` (already sorted)."""
    index: dict[str, list[int]] = {}
    offset = 0
    for entry in entries:
        index[entry.name] = [offset, entry.size]
        offset += entry.size
    head = json.dumps(index, separators=(",", ":"), sort_keys=True).encode("utf-8")
    if len(head) > 0xFFFFFFFF:
        raise SystemExit("resource index is larger than the u32 length field")
    path.parent.mkdir(parents=True, exist_ok=True)
    written = 0
    with path.open("wb") as fh:
        fh.write(MAGIC)
        fh.write(struct.pack("<I", len(head)))
        fh.write(head)
        for entry in entries:
            data = read_entry(entry, zf)
            if len(data) != entry.size:
                raise SystemExit(f"{entry.name}: expected {entry.size} bytes, read {len(data)}")
            fh.write(data)
            written += len(data)
    print(f"  wrote {len(entries)} files, {written / 1048576:.1f} MiB -> {path}")


def extract_ui(entries: list[Entry], zf: zipfile.ZipFile, ui_dir: Path) -> None:
    """Copy the two assets the HTML loading screen needs out of the jar.

    The loading overlay must paint before the 500 MB archive has downloaded, so
    dirt and the Mojang wordmark are served as small standalone PNGs.
    """
    wanted = {
        "assets/minecraft/textures/block/dirt.png": "dirt.png",
        "assets/minecraft/textures/gui/title/mojangstudios.png": "mojangstudios.png",
        # Vanilla HUD sprites: the hotbar/selection/crosshair are drawn at 2x by
        # the HTML overlay so the page keeps the exact Java pixel layout.
        "assets/minecraft/textures/gui/sprites/hud/hotbar.png": "gui/hotbar.png",
        "assets/minecraft/textures/gui/sprites/hud/hotbar_selection.png": "gui/hotbar_selection.png",
        "assets/minecraft/textures/gui/sprites/hud/crosshair.png": "gui/crosshair.png",
        "assets/minecraft/textures/gui/sprites/widget/button.png": "gui/button.png",
        "assets/minecraft/textures/gui/sprites/widget/button_highlighted.png": "gui/button_highlighted.png",
        "assets/minecraft/textures/gui/sprites/hud/heart/container.png": "gui/heart_container.png",
        "assets/minecraft/textures/gui/sprites/hud/heart/full.png": "gui/heart_full.png",
        "assets/minecraft/textures/gui/sprites/hud/heart/half.png": "gui/heart_half.png",
        "assets/minecraft/textures/gui/sprites/hud/food_empty.png": "gui/food_empty.png",
        "assets/minecraft/textures/gui/sprites/hud/food_full.png": "gui/food_full.png",
        "assets/minecraft/textures/gui/sprites/hud/food_half.png": "gui/food_half.png",
        # Default creative hotbar icons (the renderer-only page has no Java
        # inventory yet; these are the vanilla 16x16 block textures).
        "assets/minecraft/textures/block/grass_block_side.png": "items/grass_block.png",
        "assets/minecraft/textures/block/dirt.png": "items/dirt.png",
        "assets/minecraft/textures/block/stone.png": "items/stone.png",
        "assets/minecraft/textures/block/oak_log.png": "items/oak_log.png",
        "assets/minecraft/textures/block/oak_leaves.png": "items/oak_leaves.png",
        "assets/minecraft/textures/block/oak_planks.png": "items/oak_planks.png",
        "assets/minecraft/textures/block/cobblestone.png": "items/cobblestone.png",
        "assets/minecraft/textures/block/sand.png": "items/sand.png",
        "assets/minecraft/textures/block/glass.png": "items/glass.png",
    }
    by_name = {entry.name: entry for entry in entries if entry.source == "jar"}
    ui_dir.mkdir(parents=True, exist_ok=True)
    for logical, filename in wanted.items():
        entry = by_name.get(logical)
        if entry is None:
            print(f"warning: {logical} not in jar; UI falls back", file=sys.stderr)
            continue
        data = read_entry(entry, zf)
        target = ui_dir / filename
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        print(f"  ui: {filename} ({len(data)} bytes)")


def write_texture_archive(out: Path, entries: list[Entry], zf: zipfile.ZipFile) -> None:
    """Small archive with exactly the PNGs the Rust texture array asks for."""
    wanted = [
        entry for entry in entries
        if entry.name.endswith(".png") and (
            "/textures/block/" in entry.name
            or entry.name.endswith("textures/gui/title/mojangstudios.png")
        )
    ]
    write_archive(out, wanted, zf)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("jar", nargs="?", help="version jar (old two-argument form)")
    parser.add_argument("out_pos", nargs="?", help="output archive (old two-argument form)")
    parser.add_argument("--mc-dir", default=os.environ.get("MC_DIR"), help="Minecraft installation directory")
    parser.add_argument("--version", default=os.environ.get("MC_VERSION"), help="version id, e.g. 26.3")
    parser.add_argument("--out", default=None, help="output archive")
    parser.add_argument("--jar-only", action="store_true", help="skip external asset objects (sounds/langs)")
    parser.add_argument("--verify", action="store_true", help="verify SHA-1 of external asset objects")
    parser.add_argument("--list", action="store_true", help="print every packed logical path")
    parser.add_argument("--ui-dir", default=None,
                        help="write dirt.png/mojangstudios.png for the HTML loading screen "
                             "(default: <out dir>/ui)")
    parser.add_argument("--textures-out", default=None,
                        help="write a small archive of block PNGs for the Rust body "
                             "(default: <out dir>/client-textures.bin)")
    parser.add_argument("--no-extra", action="store_true",
                        help="skip the loading-screen UI PNGs and the Rust texture subset")
    args = parser.parse_args(argv)

    # Old form: pack_resources.py <jar> <out>
    if args.jar and not args.out and not args.out_pos:
        jar = Path(args.jar).expanduser().resolve()
        mc_dir = jar.parent.parent.parent  # .../versions/<v>/<v>.jar -> .../minecraft
        meta_path = jar.with_suffix(".json")
        if not meta_path.is_file():
            raise SystemExit(f"missing version metadata: {meta_path}")
        with meta_path.open("r", encoding="utf-8") as fh:
            meta = json.load(fh)
        version = jar.stem
        out = DEFAULT_OUT
    elif args.jar and args.out_pos and not args.out:
        jar = Path(args.jar).expanduser().resolve()
        mc_dir = jar.parent.parent.parent
        meta_path = jar.with_suffix(".json")
        if not meta_path.is_file():
            raise SystemExit(f"missing version metadata: {meta_path}")
        with meta_path.open("r", encoding="utf-8") as fh:
            meta = json.load(fh)
        version = jar.stem
        out = Path(args.out_pos)
    else:
        mc_dir = Path(args.mc_dir or DEFAULT_MC_DIR).expanduser().resolve()
        version = args.version or DEFAULT_VERSION
        jar, meta_path, meta = find_installation(mc_dir, version)
        version = jar.stem
        out = Path(args.out or DEFAULT_OUT)

    if not jar.is_file():
        raise SystemExit(f"missing client jar: {jar}")

    print(f"minecraft: {mc_dir}")
    print(f"version:   {version}  ({jar.name}, {jar.stat().st_size / 1048576:.1f} MiB)")
    print(f"output:    {out}")

    jar_list = jar_entries(jar)
    names = {e.name for e in jar_list}
    entries = list(jar_list)

    if not args.jar_only:
        external = object_entries(mc_dir, meta, names, args.verify)
        entries.extend(external)
        names.update(e.name for e in external)

    # Asset index files: canonical id + version-named alias.
    indexes = [e for e in index_file_entries(mc_dir, meta, version) if e.name not in names]
    entries.extend(indexes)
    names.update(e.name for e in indexes)

    entries = dedupe(entries)

    total = sum(e.size for e in entries)
    counts: dict[str, int] = {}
    bytes_by: dict[str, int] = {}
    for entry in entries:
        c = category(entry.name)
        counts[c] = counts.get(c, 0) + 1
        bytes_by[c] = bytes_by.get(c, 0) + entry.size

    # Lay out the index (offset, length) exactly as the readers expect.
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(jar) as zf:
        write_archive(out, entries, zf)

        if not args.no_extra:
            ui_dir = Path(args.ui_dir).expanduser() if args.ui_dir else out.parent / "ui"
            extract_ui(entries, zf, ui_dir)
            textures_out = (Path(args.textures_out).expanduser()
                            if args.textures_out else out.parent / "client-textures.bin")
            write_texture_archive(textures_out, entries, zf)

    print(f"packed {len(entries)} files, {total / 1048576:.1f} MiB payload "
          f"-> {out} ({out.stat().st_size / 1048576:.1f} MiB)")
    for name in sorted(counts):
        print(f"  {name:16s} {counts[name]:5d} files  {bytes_by[name] / 1048576:7.1f} MiB")
    if args.list:
        for entry in entries:
            print(entry.name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
