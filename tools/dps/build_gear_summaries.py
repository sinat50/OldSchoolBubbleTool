"""Makes app/src/main/assets/dps/gear_summaries.bin: every wearable item's icon in brief, for the DPS Calculator's
Import my gear, which recognises your worn gear from these instead of item pictures (none are kept in the app for it).

Each icon is RuneLite's picture of the item as the game draws it (a 36 x 32 canvas, static.runelite.net), shrunk to
9 x 8 squares of 4 x 4 pixels: for each square, the average colour of the item's own pixels and how much of the square
the item covers. That's 288 bytes an item, a few blocks of colour, not a picture (the owner's limit, November 2026:
9 x 8 at the most). Icons are read from app/src/main/assets/items/canvas/ if it's there, otherwise downloaded (not saved).

File layout: "GSM1", the number of icons (4 bytes), then for each: its item ID (4 bytes; items that look exactly alike
share the icon of the lowest ID among them, see items/canvas.tsv), its equipment slot (1 byte, an index into SLOTS
below, which must match GearRecognizer.SLOT_ORDER), then 72 squares, row by row: red, green, blue, cover (1 byte each).

Usage (from the project folder; needs Pillow): python tools/dps/build_gear_summaries.py
"""
import io, os, struct, sys, urllib.request
from PIL import Image   # (pip install pillow)

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets')
SLOTS = ['head', 'cape', 'neck', 'ammo', 'weapon', 'body', 'shield', 'legs', 'hands', 'feet', 'ring']
ICON = 'https://static.runelite.net/cache/item/icon/{}.png'
AGENT = 'OSRSBubbleTool gear summary builder (github.com/sinat50/OSRSBubbleTool)'
W, H, GX, GY = 36, 32, 9, 8
BW, BH = W // GX, H // GY


def lines(name):
    with open(os.path.join(ASSETS, 'items', name), encoding='utf-8') as f:
        return [l.rstrip('\n').split('\t') for l in f if l.strip() and not l.startswith('#')]


shared = {int(a): int(b) for a, b in lines('canvas.tsv')}
slot_of = {}
for f in lines('items.tsv'):
    if len(f) > 2 and f[2] in SLOTS:
        icon = shared.get(int(f[0]), int(f[0]))
        slot_of.setdefault(icon, f[2])


def picture(i):
    local = os.path.join(ASSETS, 'items', 'canvas', f'{i}.png')
    if os.path.exists(local):
        return Image.open(local).convert('RGBA')
    try:
        req = urllib.request.Request(ICON.format(i), headers={'User-Agent': AGENT})
        with urllib.request.urlopen(req, timeout=30) as r:
            return Image.open(io.BytesIO(r.read())).convert('RGBA')
    except Exception as e:
        print(f'  no icon for {i}: {e}', file=sys.stderr)
        return None


def summary(img):
    px = img.load()
    out = bytearray()
    for cy in range(GY):
        for cx in range(GX):
            r = g = b = n = 0
            for y in range(cy * BH, (cy + 1) * BH):
                for x in range(cx * BW, (cx + 1) * BW):
                    pr, pg, pb, pa = px[x, y]
                    if pa > 128:
                        r += pr; g += pg; b += pb; n += 1
            if n:
                out += bytes((round(r / n), round(g / n), round(b / n), round(n * 255 / (BW * BH))))
            else:
                out += bytes(4)
    return out


entries = []
for icon in sorted(slot_of):
    img = picture(icon)
    if img is None or img.size != (W, H):
        continue
    entries.append(struct.pack('>iB', icon, SLOTS.index(slot_of[icon])) + summary(img))
out = os.path.join(ASSETS, 'dps', 'gear_summaries.bin')
with open(out, 'wb') as f:
    f.write(b'GSM1' + struct.pack('>i', len(entries)) + b''.join(entries))
print(f'{len(entries)} icons, {os.path.getsize(out) // 1024} KB: {out}')
