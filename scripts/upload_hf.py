#!/usr/bin/env python3
"""Upload the split clientmain bundle to a Hugging Face repo.

Usage:
    HF_TOKEN=... python3 scripts/upload_hf.py <src-dir> <repo-id> [path-in-repo]

Environment fallbacks: HF_REPO, HF_PATH (default "web"), HF_REPO_TYPE (default "space").
"""
import glob
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import time
import urllib.request

from huggingface_hub import HfApi

ARCHIVE_MAGIC = b"MCRS"


def archive_index(path: str) -> dict:
    """Read the small JSON index at the front of a packed resource archive."""
    with open(path, "rb") as archive:
        if archive.read(4) != ARCHIVE_MAGIC:
            raise ValueError(f"{path} is not a packed Minecraft resource archive")
        header = archive.read(4)
        if len(header) != 4:
            raise ValueError(f"{path} has a truncated archive header")
        length = struct.unpack("<I", header)[0]
        if length <= 0 or length > 64 * 1024 * 1024:
            raise ValueError(f"{path} has an invalid archive index length {length}")
        return json.loads(archive.read(length))


def choose_resource_archive(resource_dir: str) -> str:
    """Pick the newest complete archive instead of a stale 32 MB staging copy.

    The full archive contains ICU4J timezone data and the complete GUI sprite
    set. A jar-only smoke archive is missing those entries, which makes the
    live client show fallback icons. Prefer the explicit deployment archive,
    then the staged copy, then the ICU-merged archive produced by the build.
    """
    candidates = [
        os.environ.get("HF_RESOURCES_ARCHIVE"),
        os.path.join(resource_dir, "client-resources.bin"),
        "/tmp/client-resources-icu-merged.bin",
        os.path.join("build", "web", "client-resources.bin"),
    ]
    valid = []
    for candidate in candidates:
        if not candidate or not os.path.isfile(candidate):
            continue
        try:
            index = archive_index(candidate)
        except (OSError, ValueError, json.JSONDecodeError) as error:
            print(f"ignoring resource archive {candidate}: {error}")
            continue
        valid.append((os.path.getsize(candidate), candidate, index))
    if not valid:
        raise SystemExit("no valid client-resources.bin archive was found")
    size, path, index = max(valid, key=lambda item: item[0])
    has_icons = any(key.endswith("textures/gui/sprites/icon/language.png") for key in index)
    has_zoneinfo = any(key.endswith("zoneinfo64.res") for key in index)
    print(f"selected resource archive {path}: {size:,} bytes, {len(index):,} entries, "
          f"icons={has_icons}, icu-zoneinfo={has_zoneinfo}")
    if (size < 50_000_000 or not has_icons or not has_zoneinfo) and os.environ.get("HF_ALLOW_SMALL_RESOURCES") != "1":
        raise SystemExit(
            "refusing to deploy a stale/incomplete resource archive; "
            "set HF_RESOURCES_ARCHIVE to the full archive or HF_ALLOW_SMALL_RESOURCES=1"
        )
    return path


def space_url(repo: str) -> str:
    """Public static URL for a Hugging Face Space repo id."""
    owner, name = repo.split("/", 1)
    slug = re.sub(r"[^a-z0-9-]+", "-", f"{owner}-{name}".lower()).strip("-")
    return f"https://{slug}.static.hf.space/"


def wait_for_shell(url: str, timeout: int = 180) -> bool:
    """Wait until the Space serves the shell that reveals the Java UI."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                body = response.read()
            if b"java-ui" in body:
                print(f"live shell is serving the Java UI at {url}")
                return True
            print("live shell is still the previous build; waiting...")
        except Exception as error:
            print(f"live shell not ready yet: {error}")
        time.sleep(5)
    return False


def verify_live_title(url: str) -> bool:
    """Open the public Space and prove the real Java TitleScreen uses WASM."""
    chrome = shutil.which("google-chrome") or shutil.which("chromium")
    if not chrome:
        print("Chrome is not installed; skipping the live TitleScreen check")
        return True
    os.makedirs("build", exist_ok=True)
    command = [
        sys.executable, "scripts/cdp.py", url,
        "--timeout", "600",
        "--until-js",
        "window.__mc && window.__mc.stage === 'complete' && window.__mc.ui === 'java'",
        "--assert-js",
        "(() => { const f = document.getElementById('mc-brain');"
        " const m = f && f.contentWindow && f.contentWindow.__mc;"
        " const b = m && String(m['boot-state'] || '');"
        " return !!(b && b.indexOf('screen=net.minecraft.client.gui.screens.TitleScreen') >= 0"
        " && b.indexOf('loadFinished=true') >= 0); })()",
        "--assert-js",
        "(() => { const f = document.getElementById('mc-brain');"
        " return !!(f && f.contentWindow && f.contentWindow.__mcEngine === 'wasm'); })()",
        "--screenshot", "build/live-title.png",
    ]
    print("verifying the live Java TitleScreen: " + " ".join(command))
    result = subprocess.run(command, env=dict(os.environ, CHROME=chrome))
    if result.returncode != 0:
        print("live TitleScreen verification failed", file=sys.stderr)
        for name in ("build/browser_error.txt", "build/browser_console.log"):
            try:
                with open(name, encoding="utf-8", errors="replace") as log:
                    print(f"--- {name} (tail) ---", file=sys.stderr)
                    print(log.read()[-12000:], file=sys.stderr)
            except OSError:
                pass
        return False
    try:
        with open("build/live-title.png", "rb") as screenshot:
            data = screenshot.read()
        if data[:8] != b"\x89PNG\r\n\x1a\n" or len(data) < 24:
            raise ValueError("not a valid PNG")
        width, height = struct.unpack(">II", data[16:24])
        if width < 640 or height < 360:
            raise ValueError(f"screenshot too small: {width}x{height}")
        print(f"live screenshot: {width}x{height}, {len(data)} bytes")
    except Exception as error:
        print(f"live screenshot check failed: {error}", file=sys.stderr)
        return False
    print(f"live Java TitleScreen verified at {url}")
    return True


def main() -> int:
    src = sys.argv[1] if len(sys.argv) > 1 else "build/parts"
    repo = sys.argv[2] if len(sys.argv) > 2 else os.environ.get("HF_REPO", "")
    path_in_repo = sys.argv[3] if len(sys.argv) > 3 else os.environ.get("HF_PATH", "web")
    repo_type = os.environ.get("HF_REPO_TYPE", "space")
    token = os.environ.get("HF_TOKEN", "")

    if not token:
        print("HF_TOKEN is not set", file=sys.stderr)
        return 2
    if not repo:
        print("no Hugging Face repo id (pass it as an argument or set HF_REPO)", file=sys.stderr)
        return 2

    files = sorted(glob.glob(os.path.join(src, "*")))
    if not files:
        # WebAssembly-only deployments have no split JavaScript parts. Keep
        # going so the WASM module, resource archives and shell still publish.
        print(f"no split JavaScript parts staged in {src}; continuing with WASM/resources/shell")

    api = HfApi(token=token)
    for f in files:
        name = os.path.basename(f)
        print(f"uploading {name} ({os.path.getsize(f):,} bytes) -> {repo}:{path_in_repo}/{name}")
        api.upload_file(
            path_or_fileobj=f,
            path_in_repo=f"{path_in_repo}/{name}" if path_in_repo else name,
            repo_id=repo,
            repo_type=repo_type,
            commit_message=f"clientmain: {name}",
        )
    print(f"uploaded {len(files)} files to {repo}")

    # Older bundles were longer (five parts). The manifest is authoritative, but
    # leaving stale clientmain.part3/part4 behind confuses cache busting and
    # makes the Space look like it still serves the old build.
    manifest_path = os.path.join(src, "clientmain.parts.json")
    if os.path.exists(manifest_path):
        with open(manifest_path, encoding="utf-8") as f:
            manifest = json.load(f)
        keep = {p["name"] for p in manifest.get("parts", []) if isinstance(p, dict) and "name" in p}
        prefix = f"{path_in_repo}/" if path_in_repo else ""
        for repo_file in api.list_repo_files(repo_id=repo, repo_type=repo_type):
            if not repo_file.startswith(prefix + "clientmain.part"):
                continue
            name = repo_file[len(prefix):]
            if name not in keep:
                print(f"deleting stale {repo_file}")
                api.delete_file(
                    path_in_repo=repo_file,
                    repo_id=repo,
                    repo_type=repo_type,
                    commit_message=f"clientmain: remove stale {name}",
                )

    # Resource archives are packed from the user's own Minecraft installation
    # and are too large for git. When staged next to the parts, deploy them
    # before the shell so the Java client never starts against a stale archive
    # (the ICU4J timezone data lives here, not in the JavaScript bundle).
    resource_dir = os.path.join(os.path.dirname(src), "resources")
    if not os.path.isdir(resource_dir):
        # A clientmain-only deploy keeps the resource archives already on the
        # Space. This is important when the staged archive is a trimmed CI
        # pack: replacing the richer live archive would drop fonts and icons.
        print(f"no staged resource directory {resource_dir}; keeping the live archives")
    else:
        resource_archive = choose_resource_archive(resource_dir)
        resource_sources = {
            "client-resources.bin": resource_archive,
            "client-textures.bin": os.path.join(resource_dir, "client-textures.bin"),
        }
        for name, source_path in resource_sources.items():
            if not os.path.exists(source_path):
                print(f"skipping missing resource archive {source_path}")
                continue
            target = f"{path_in_repo}/{name}" if path_in_repo else name
            print(f"uploading {name} ({os.path.getsize(source_path):,} bytes) -> {repo}:{target}")
            api.upload_file(
                path_or_fileobj=source_path,
                path_in_repo=target,
                repo_id=repo,
                repo_type=repo_type,
                commit_message=f"resources: deploy {name}",
            )

    # WebAssembly is the primary client. Stage it next to the parts (the WASM
    # probe workflow publishes these files) and upload it before the shell, so
    # a new play.html never points at a module that is not live yet. When the
    # files are absent, the shell's JavaScript fallback still works.
    wasm_dir = os.path.join(os.path.dirname(src), "wasm")
    for name in ("clientmain.wasm-runtime.js", "clientmain.wasm"):
        source_path = os.path.join(wasm_dir, name)
        if not os.path.exists(source_path):
            print(f"skipping missing WASM file {source_path}")
            continue
        target = f"{path_in_repo}/{name}" if path_in_repo else name
        print(f"uploading {name} ({os.path.getsize(source_path):,} bytes) -> {repo}:{target}")
        api.upload_file(
            path_or_fileobj=source_path,
            path_in_repo=target,
            repo_id=repo,
            repo_type=repo_type,
            commit_message=f"wasm: deploy {name}",
        )

    # The Space also serves the browser shell. Deploy it with the bundle so the
    # live site cannot keep an older game.html that hides the real Java
    # TitleScreen behind the HTML launcher. index.html is the Space root, so it
    # goes last: the new shell only becomes live after its parts and scripts.
    web = os.path.join(os.getcwd(), "web")
    shell = [
        ("game.html", "game.html"),
        ("play.html", "play.html"),
        ("bridge.js", "bridge.js"),
        ("input.js", "input.js"),
        ("gpu/webgpu.js", "gpu/webgpu.js"),
        ("native/heap.js", "native/heap.js"),
        ("native/layout.js", "native/layout.js"),
        ("native/layout-shaders.js", "native/layout-shaders.js"),
        ("native/loading.js", "native/loading.js"),
        ("native/natives.js", "native/natives.js"),
        ("native/sdl.js", "native/sdl.js"),
        ("native/shaders.js", "native/shaders.js"),
        ("native/stb.js", "native/stb.js"),
        ("site.webmanifest", "site.webmanifest"),
        ("ui/grass_block_icon.svg", "ui/grass_block_icon.svg"),
        ("game.html", "index.html"),
    ]
    uploaded = 0
    for source, destination in shell:
        source_path = os.path.join(web, source)
        if not os.path.exists(source_path):
            print(f"skipping missing shell file {source_path}")
            continue
        target = f"{path_in_repo}/{destination}" if path_in_repo else destination
        print(f"uploading {source} -> {repo}:{target}")
        api.upload_file(
            path_or_fileobj=source_path,
            path_in_repo=target,
            repo_id=repo,
            repo_type=repo_type,
            commit_message=f"site: {destination}",
        )
        uploaded += 1
    print(f"uploaded {uploaded} browser shell files to {repo}")

    # Prove the public default URL, not just the uploaded bytes, shows the real
    # Java TitleScreen. Skip this when publishing to a subfolder, because the
    # Space root then still serves a different shell, and when the deploy host
    # has no WebGPU adapter (the files are already live at that point).
    if repo_type == "space" and not path_in_repo and os.environ.get("HF_SKIP_LIVE_VERIFY") != "1":
        url = space_url(repo)
        if not wait_for_shell(url):
            print(f"the live shell at {url} did not update in time", file=sys.stderr)
            return 1
        if not verify_live_title(url):
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
