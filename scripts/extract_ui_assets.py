#!/usr/bin/env python3
"""Extract the real vanilla UI textures from the local 26.3 client jar.

The browser port never ships the game jar; this script only reads the copy the
player already owns and copies the GUI/font/dirt textures into web/ui/vanilla so
the HTML screens can reference the exact pixels the Java client draws.

usage: scripts/extract_ui_assets.py [--jar PATH] [--out DIR]
"""
import argparse
import os
import zipfile

DEFAULT_JAR = os.path.expanduser(
    '~/Library/Application Support/minecraft/versions/26.3/26.3.jar')

# Every vanilla texture the browser UI can draw. The GUI tree covers every
# sprite (widget/, world_list/, tooltip/, container/, ...) plus the legacy
# menu_background.png files; the block/font entries supply the dirt tiling and
# the bitmap font.
PREFIXES = (
    'assets/minecraft/textures/gui/',
    'assets/minecraft/textures/font/',
)
FILES = (
    'assets/minecraft/textures/block/dirt.png',
    'assets/minecraft/textures/block/dirt_path_top.png',
    'assets/minecraft/textures/block/grass_block_top.png',
    'assets/minecraft/textures/block/stone.png',
    'assets/minecraft/textures/block/sand.png',
    'assets/minecraft/textures/block/water_still.png',
    'assets/minecraft/textures/block/oak_log.png',
    'assets/minecraft/textures/block/oak_leaves.png',
)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--jar', default=DEFAULT_JAR)
    ap.add_argument('--out', default='web/ui/vanilla')
    args = ap.parse_args()
    if not os.path.isfile(args.jar):
        raise SystemExit('missing client jar: ' + args.jar)

    written = 0
    with zipfile.ZipFile(args.jar) as jar:
        names = [n for n in jar.namelist() if n.endswith('/') is False]
        for name in names:
            if not (name.startswith(PREFIXES) or name in FILES):
                continue
            relative = name[len('assets/minecraft/textures/'):]
            dst = os.path.join(args.out, relative)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, 'wb') as f:
                f.write(jar.read(name))
            written += 1

    print('extracted %d textures from %s -> %s' % (written, args.jar, args.out))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
