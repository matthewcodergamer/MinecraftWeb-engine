#!/usr/bin/env python3
"""Add a release signing config to Tauri's generated Android Gradle project.

Tauri regenerates ``src-tauri/gen/android`` on ``tauri android init`` but does
not expose Android keystore settings in ``tauri.conf.json``. This patch runs
after ``init`` and wires the checked-in sideload keystore into the ``release``
build type.

Usage:
    python3 android/patch_signing.py android/src-tauri/gen/android/app/build.gradle.kts

The keystore path defaults to ``android/ci-release.jks`` in the repository and
can be overridden with ``ANDROID_KEYSTORE_PATH``. Passwords and the key alias
come from ``ANDROID_KEYSTORE_PASSWORD``, ``ANDROID_KEY_ALIAS`` and
``ANDROID_KEY_PASSWORD``.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

SIGNING_CONFIGS = """
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
                ?: rootProject.file("../../../ci-release.jks").absolutePath
            storeFile = rootProject.file(keystorePath)
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }
"""

RELEASE_SIGNING = '\n            signingConfig = signingConfigs.getByName("release")'


def patch(text: str) -> str:
    if 'signingConfigs {' in text:
        raise SystemExit("build.gradle.kts already has a signingConfigs block")

    android = re.search(r"^android\s*\{", text, re.MULTILINE)
    if not android:
        raise SystemExit("could not find the android { } block")
    text = text[: android.end()] + SIGNING_CONFIGS + text[android.end() :]

    release = re.search(r'getByName\("release"\)\s*\{', text)
    if not release:
        raise SystemExit('could not find the release build type')
    text = text[: release.end()] + RELEASE_SIGNING + text[release.end() :]
    return text


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    path = Path(argv[1])
    if not path.is_file():
        raise SystemExit(f"missing {path}")
    original = path.read_text(encoding="utf-8")
    patched = patch(original)
    path.write_text(patched, encoding="utf-8")
    print(f"patched release signing into {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
