#!/usr/bin/env python3
"""Upload the split clientmain bundle to a Hugging Face repo.

Usage:
    HF_TOKEN=... python3 scripts/upload_hf.py <src-dir> <repo-id> [path-in-repo]

Environment fallbacks: HF_REPO, HF_PATH (default "web"), HF_REPO_TYPE (default "space").
"""
import glob
import json
import os
import sys

from huggingface_hub import HfApi


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
        print(f"nothing to upload in {src}", file=sys.stderr)
        return 2

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
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
