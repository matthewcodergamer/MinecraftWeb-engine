#!/usr/bin/env python3
"""Build a pixel font from the vanilla ascii.png atlas (the real Java font)."""
import json, struct, sys, os
from PIL import Image
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

ARCHIVE = os.environ.get('MC_ARCHIVE', 'build/web/client-resources.bin')
OUT = 'web/ui/font/minecraft.woff'
GLYPH = 8            # pixels per cell
PIXEL = 16           # font units per source pixel
EM = GLYPH * PIXEL

with open(ARCHIVE, 'rb') as f:
    magic, = struct.unpack('>I', f.read(4)); length, = struct.unpack('<I', f.read(4))
    assert magic == 0x4D435253
    index = json.loads(f.read(length))
    base = 8 + length
    f.seek(base + index['assets/minecraft/textures/font/ascii.png'][0])
    png = f.read(index['assets/minecraft/textures/font/ascii.png'][1])
    f.seek(base + index['assets/minecraft/font/include/default.json'][0])
    default = f.read(index['assets/minecraft/font/include/default.json'][1])
with open('/tmp/ascii.png', 'wb') as out:
    out.write(png)
with open('/tmp/font-default.json', 'wb') as out:
    out.write(default)
providers = json.loads(default)['providers']
rows = next(p for p in providers if p.get('file') == 'minecraft:font/ascii.png')['chars']

im = Image.open('/tmp/ascii.png').convert('RGBA')
assert im.size == (128, 128)

glyphs = {'.notdef': TTGlyphPen(None).glyph()}
metrics = {'.notdef': (EM, 0)}
cmap = {}
order = ['.notdef']

for row, line in enumerate(rows):
    for col, ch in enumerate(line):
        cp = ord(ch)
        if cp == 0:
            continue
        if cp in cmap:
            continue
        x0, y0 = col * GLYPH, row * GLYPH
        px = im.crop((x0, y0, x0 + GLYPH, y0 + GLYPH)).load()
        runs = []
        by_row = {}
        right = -1
        for y in range(GLYPH):
            x = 0
            row_runs = []
            while x < GLYPH:
                if px[x, y][3] and any(px[x, y][i] for i in range(3)):
                    start = x
                    while x < GLYPH and px[x, y][3] and any(px[x, y][i] for i in range(3)):
                        x += 1
                    runs.append((start, x - 1, y))
                    row_runs.append((start, x - 1))
                    right = max(right, x - 1)
                else:
                    x += 1
            by_row[y] = row_runs
        if not runs and cp != 32:
            continue
        name = 'uni%04X' % cp
        pen = TTGlyphPen(None)
        # Merge identical runs on consecutive scanlines into one tall rectangle.
        # Emitting one rectangle per scanline leaves hairline gaps at
        # fractional GUI scales, which look like horizontal stripes through
        # the glyphs. One rectangle per contiguous block has no interior edge.
        merged = []
        open_runs = {}
        for y in range(GLYPH):
            keys = set(by_row[y])
            for key in list(open_runs):
                if key not in keys:
                    l, r, start = open_runs.pop(key)
                    merged.append((l, r, start, y - 1))
            for (l, r) in by_row[y]:
                if (l, r) not in open_runs:
                    open_runs[(l, r)] = (l, r, y)
        for (l, r, start) in open_runs.values():
            merged.append((l, r, start, GLYPH - 1))
        for left, end, top_row, bottom_row in merged:
            # TTF y grows upward; baseline sits below the 8th row.
            top = (GLYPH - top_row) * PIXEL
            bottom = (GLYPH - bottom_row - 1) * PIXEL
            l, r = left * PIXEL, (end + 1) * PIXEL
            pen.moveTo((l, bottom))
            pen.lineTo((l, top))
            pen.lineTo((r, top))
            pen.lineTo((r, bottom))
            pen.closePath()
        glyphs[name] = pen.glyph()
        advance = (right + 2) * PIXEL if runs else 4 * PIXEL
        metrics[name] = (max(advance, PIXEL), 0)
        cmap[cp] = name
        order.append(name)

fb = FontBuilder(EM, isTTF=True)
fb.setupGlyphOrder(order)
fb.setupCharacterMap(cmap)
fb.setupGlyf(glyphs)
fb.setupHorizontalMetrics(metrics)
fb.setupHorizontalHeader(ascent=7 * PIXEL, descent=-PIXEL, lineGap=0)
fb.setupOS2(sTypoAscender=7 * PIXEL, sTypoDescender=-PIXEL, sTypoLineGap=0,
            usWinAscent=7 * PIXEL, usWinDescent=PIXEL, sxHeight=5 * PIXEL, sCapHeight=7 * PIXEL)
fb.setupNameTable({'familyName': 'Minecraft', 'styleName': 'Regular',
                   'uniqueFontIdentifier': 'Minecraft-Regular',
                   'fullName': 'Minecraft', 'psName': 'Minecraft-Regular',
                   'version': 'Version 1.0'})
fb.setupPost()
fb.setupMaxp()
os.makedirs(os.path.dirname(OUT), exist_ok=True)
fb.font.flavor = 'woff'
fb.save(OUT)
print('wrote', OUT, len(cmap), 'glyphs')
