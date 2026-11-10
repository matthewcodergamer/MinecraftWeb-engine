#!/usr/bin/env python3
"""Download the Minecraft client jar and its Java libraries from Mojang's official CDN.

A clean CI runner has no Minecraft installation, and this repo is forbidden from
carrying Mojang artifacts (.gitignore: "Never commit Minecraft artifacts"). This
fetches exactly what scripts/build.sh reads:

  <dest>/versions/<ver>/<ver>.json   the version manifest (library list)
  <dest>/versions/<ver>/<ver>.jar    the client jar
  <dest>/libraries/<maven path>      every Java library artifact

Layout matches the launcher's, so scripts/build.sh finds everything unmodified.

Usage:
    python3 scripts/fetch_minecraft.py --version 26.3 --dest "$MC_DIR"
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import sys
import urllib.request

MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"


def get_json(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.load(r)


def download(url, dest, sha1=None, size=None):
    """Fetch url to dest unless a matching file is already present."""
    if os.path.exists(dest):
        if size is not None and os.path.getsize(dest) == size:
            return "cached"
        if size is None:
            return "cached"
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    if sha1:
        h = hashlib.sha1()
        with open(tmp, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        if h.hexdigest() != sha1:
            os.unlink(tmp)
            raise RuntimeError(f"sha1 mismatch for {url}")
    os.replace(tmp, dest)
    return "fetched"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default=os.environ.get("MC_VERSION", "26.3"))
    ap.add_argument("--dest", default=os.environ.get("MC_DIR", os.path.expanduser("~/.minecraft")))
    ap.add_argument("--jobs", type=int, default=8)
    args = ap.parse_args()

    manifest = get_json(MANIFEST)
    entry = next((v for v in manifest["versions"] if v["id"] == args.version), None)
    if entry is None:
        print(f"version {args.version} not found on Mojang's manifest", file=sys.stderr)
        return 1

    vdir = os.path.join(args.dest, "versions", args.version)
    os.makedirs(vdir, exist_ok=True)
    vjson_path = os.path.join(vdir, f"{args.version}.json")
    if not os.path.exists(vjson_path):
        with urllib.request.urlopen(entry["url"], timeout=60) as r, open(vjson_path, "wb") as f:
            f.write(r.read())
    version = json.load(open(vjson_path))

    jobs = []
    client = version["downloads"]["client"]
    jobs.append((client["url"], os.path.join(vdir, f"{args.version}.jar"),
                 client.get("sha1"), client.get("size")))

    skipped = 0
    for lib in version.get("libraries", []):
        art = lib.get("downloads", {}).get("artifact")
        if not art or not art.get("url"):
            continue  # natives / classifier-only entries are irrelevant in the browser
        jobs.append((art["url"], os.path.join(args.dest, "libraries", art["path"]),
                     art.get("sha1"), art.get("size")))

    ok = fail = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(download, u, d, s, z): d for u, d, s, z in jobs}
        for fut in concurrent.futures.as_completed(futures):
            try:
                fut.result()
                ok += 1
            except Exception as e:  # noqa: BLE001 - report every failure, keep going
                fail += 1
                print(f"FAILED {futures[fut]}: {e}", file=sys.stderr)

    print(f"{args.version}: {ok} artifacts ready, {fail} failed, {skipped} skipped -> {args.dest}")
    return 1 if fail else 0


if __name__ == "__main__":
    raise SystemExit(main())
