#!/usr/bin/env python3
"""Copy the vanilla menu music and core UI/gameplay sounds out of the local assets index."""
import json, os, shutil, sys
home = os.path.expanduser('~/Library/Application Support/minecraft')
index = json.load(open(os.path.join(home, 'assets/indexes/34.json')))['objects']
objects = os.path.join(home, 'assets/objects')
out = 'web/assets/minecraft/sounds'
names = [
    'music/game/ebb', 'music/game/home', 'music/game/memories',
    'music/game/nightly', 'music/game/shores',
    'music/menu/beginning_2', 'music/menu/floating_trees',
    'music/menu/moog_city_2', 'music/menu/mutation',
    'random/click', 'random/click_stereo',
    'dig/stone1', 'dig/stone2', 'dig/stone3', 'dig/stone4',
    'damage/hit1', 'damage/hit2', 'damage/hit3',
    'step/stone1', 'step/stone2', 'step/stone3', 'step/stone4', 'step/stone5', 'step/stone6',
    'liquid/water', 'ambient/weather/rain1',
]
for name in names:
    key = 'minecraft/sounds/' + name + '.ogg'
    entry = index.get(key)
    if not entry:
        print('missing', key, file=sys.stderr)
        continue
    src = os.path.join(objects, entry['hash'][:2], entry['hash'])
    dst = os.path.join(out, name + '.ogg')
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(src, dst)
    print(dst, entry['size'])
