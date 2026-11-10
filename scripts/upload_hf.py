#!/usr/bin/env python3
"""Upload the split clientmain bundle to a Hugging Face repo.

Usage:
    HF_TOKEN=... python3 scripts/upload_hf.py <src-dir> <repo-id> [path-in-repo]

Environment fallbacks: HF_REPO, HF_PATH (default "web"), HF_REPO_TYPE (default "space").
"""
import glob
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
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
