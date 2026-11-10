#!/usr/bin/env python3
"""Fetch and verify Mojang's dedicated-server bundle for a Minecraft release.

The Mojang server download is a bundler JAR, not the direct game runtime JAR.
This script verifies the outer download, extracts the versioned server runtime
from META-INF/versions.list, and extracts the bundled Java libraries listed in
META-INF/libraries.list. It never executes the downloaded artifact.

Usage:
    python3 scripts/fetch_server.py --version 26.3 --dest "$MC_DIR"

Output:
    <dest>/versions/<version>/server-bundled.jar
    <dest>/versions/<version>/server.jar
    <dest>/libraries/<maven path from libraries.list>
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.request
import zipfile

MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"


def get_json(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for chunk in iter(lambda: source.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url, destination, expected_sha1, expected_size):
    os.makedirs(os.path.dirname(destination), exist_ok=True)
    if os.path.isfile(destination):
        valid_size = expected_size is None or os.path.getsize(destination) == expected_size
        if valid_size and expected_sha1:
            digest = hashlib.sha1()
            with open(destination, "rb") as source:
                for chunk in iter(lambda: source.read(1 << 20), b""):
                    digest.update(chunk)
            valid_size = digest.hexdigest() == expected_sha1
        if valid_size:
            return "cached"

    temporary = destination + ".part"
    try:
        with urllib.request.urlopen(url, timeout=180) as response, open(temporary, "wb") as output:
            while True:
                chunk = response.read(1 << 20)
                if not chunk:
                    break
                output.write(chunk)
        if expected_size is not None and os.path.getsize(temporary) != expected_size:
            raise RuntimeError(f"size mismatch for {url}: expected {expected_size}, got {os.path.getsize(temporary)}")
        if expected_sha1:
            digest = hashlib.sha1()
            with open(temporary, "rb") as source:
                for chunk in iter(lambda: source.read(1 << 20), b""):
                    digest.update(chunk)
            if digest.hexdigest() != expected_sha1:
                raise RuntimeError(f"SHA-1 mismatch for {url}")
        os.replace(temporary, destination)
        return "downloaded"
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def safe_output(root, relative):
    normalized = relative.replace("\\", "/")
    parts = normalized.split("/")
    if not normalized or normalized.startswith("/") or any(part in ("", ".", "..") for part in parts):
        raise RuntimeError(f"unsafe path in Mojang bundle metadata: {relative!r}")
    root = os.path.realpath(root)
    output = os.path.realpath(os.path.join(root, *parts))
    if os.path.commonpath((root, output)) != root:
        raise RuntimeError(f"path escapes destination: {relative!r}")
    return output


def parse_list(data, fields, label):
    rows = []
    for line_number, raw in enumerate(data.decode("utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        columns = raw.split("\t")
        if len(columns) != fields:
            raise RuntimeError(f"invalid {label} row {line_number}: expected {fields} tab-separated fields")
        rows.append(columns)
    return rows


def verify_server_runtime(runtime_path):
    """Fail closed unless this is the dedicated server runtime, not the client jar."""
    with zipfile.ZipFile(runtime_path) as runtime:
        names = set(runtime.namelist())
    required = {
        "net/minecraft/server/Main.class",
        "net/minecraft/server/level/ServerLevel.class",
    }
    missing = sorted(required - names)
    if missing:
        raise RuntimeError("dedicated server runtime is missing required classes: " + ", ".join(missing))
    if "net/minecraft/client/Minecraft.class" in names:
        raise RuntimeError("extracted runtime unexpectedly contains the client Minecraft entry point")


def extract_bundle(bundle_path, version, version_dir, libraries_dir):
    os.makedirs(version_dir, exist_ok=True)
    os.makedirs(libraries_dir, exist_ok=True)
    with zipfile.ZipFile(bundle_path) as bundle:
        version_rows = parse_list(bundle.read("META-INF/versions.list"), 3, "versions.list")
        matching = [row for row in version_rows if row[1] == version]
        if len(matching) != 1:
            raise RuntimeError(f"expected one {version!r} entry in META-INF/versions.list, found {len(matching)}")
        expected_hash, _, inner_path = matching[0]
        if len(expected_hash) != 64:
            raise RuntimeError("unexpected server runtime hash format (expected SHA-256)")
        member = "META-INF/versions/" + inner_path
        runtime = safe_output(version_dir, f"server.jar")
        temporary = runtime + ".part"
        try:
            with bundle.open(member) as source, open(temporary, "wb") as output:
                while True:
                    chunk = source.read(1 << 20)
                    if not chunk:
                        break
                    output.write(chunk)
            actual_hash = sha256_file(temporary)
            if actual_hash.lower() != expected_hash.lower():
                raise RuntimeError(f"SHA-256 mismatch for bundled server runtime: expected {expected_hash}, got {actual_hash}")
            os.replace(temporary, runtime)
            verify_server_runtime(runtime)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

        library_rows = parse_list(bundle.read("META-INF/libraries.list"), 2, "libraries.list")
        for expected_hash, library_path in library_rows:
            if len(expected_hash) != 64:
                raise RuntimeError(f"unexpected library hash format for {library_path}")
            destination = safe_output(libraries_dir, library_path)
            os.makedirs(os.path.dirname(destination), exist_ok=True)
            member = "META-INF/libraries/" + library_path
            temporary = destination + ".part"
            try:
                with bundle.open(member) as source, open(temporary, "wb") as output:
                    while True:
                        chunk = source.read(1 << 20)
                        if not chunk:
                            break
                        output.write(chunk)
                actual_hash = sha256_file(temporary)
                if actual_hash.lower() != expected_hash.lower():
                    raise RuntimeError(f"SHA-256 mismatch for bundled library {library_path}")
                os.replace(temporary, destination)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
    return runtime, len(library_rows)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", default=os.environ.get("MC_VERSION", "26.3"))
    parser.add_argument("--dest", default=os.environ.get("MC_DIR", os.path.expanduser("~/.minecraft")))
    args = parser.parse_args()

    manifest = get_json(MANIFEST)
    entry = next((item for item in manifest["versions"] if item["id"] == args.version), None)
    if entry is None:
        print(f"version {args.version} not found in Mojang's version manifest", file=sys.stderr)
        return 1

    version_json = get_json(entry["url"])
    server = version_json.get("downloads", {}).get("server")
    if not server:
        print(f"Mojang does not publish a dedicated-server artifact for {args.version}", file=sys.stderr)
        return 1

    version_dir = os.path.join(args.dest, "versions", args.version)
    bundle_path = os.path.join(version_dir, "server-bundled.jar")
    status = download(server["url"], bundle_path, server.get("sha1"), server.get("size"))
    runtime, library_count = extract_bundle(
        bundle_path, args.version, version_dir, os.path.join(args.dest, "libraries")
    )
    print(f"{args.version}: server bundle {status}; verified runtime -> {runtime}; "
          f"verified {library_count} bundled libraries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
