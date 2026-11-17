"""Makes app/src/main/assets/mixology/potions.tsv: each Mastering Mixology potion's inventory icon in brief, for the
Mastering Mixology tool's bag tags, which recognise the potions in your inventory from these instead of item pictures
(none are kept in the app).

Each icon is RuneLite's picture of the item as the game draws it (a 36 x 32 canvas, static.runelite.net), shrunk to
9 x 8 squares of 4 x 4 pixels: for each square, the average colour of the item's own pixels (its black outline
included, the soft shadow beside it left out) and how much of the square the item covers. That's a few blocks of
colour, not a picture (the owner's limit: 9 x 8 at the most; agreed for these potions on 16 November 2026). Icons are
read from tools/items/icon_cache/ (on this computer only, in .gitignore) if they're there, otherwise downloaded (not
saved). No pictures go into the app.

Which items: the ten potions as they come out of the mixing vessel ("unfinished", no cork) and after a station has
worked on them ("finished", with a cork), with the item IDs the Mastering Mixology RuneLite plugin by Hexagon tags
(its PotionType.java, read at commit 10f4ced; the numbers are RuneLite's gameval/ItemID.java MM_POTION_...).

File layout (tab-separated, lines starting with # are notes): item ID, recipe, then 288 bytes as hex: the 72
squares row by row, each red, green, blue, cover.

The script then checks the summaries: every kind's top 5 rows of squares (all the tool looks at, as the tag is written
over the bottom of the icon) must differ clearly from every other kind's, and it stops with an error if not.

Usage (from the project folder; needs Pillow): python tools/mixology/build_potion_summaries.py
A new potion needs its line in POTIONS here and its recipe in MixologyData.kt.
"""
import io, os, sys, urllib.request
from PIL import Image   # (pip install pillow)

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'mixology', 'potions.tsv')
ICON = 'https://static.runelite.net/cache/item/icon/{}.png'
AGENT = 'OldSchoolBubbleTool potion summary builder (github.com/sinat50/OldSchoolBubbleTool)'
W, H, GX, GY = 36, 32, 9, 8
BW, BH = W // GX, H // GY
SHADOW = (48, 32, 32)   # the soft shadow the game draws below-right of every item: not part of the item
USED_ROWS = 5           # must match MixologyBag.USED_ROWS
MIN_APART = 12          # the least two kinds' colours may differ, a square, over the squares the tool compares

# (unfinished ID, finished ID, recipe): the recipe must match MixologyData.kt
POTIONS = [
    (30011, 30021, 'MMM'),   # Mammoth-might mix
    (30012, 30022, 'MMA'),   # Mystic mana amalgam
    (30013, 30023, 'MML'),   # Marley's moonlight
    (30014, 30024, 'AAA'),   # Alco-augmentator
    (30015, 30025, 'ALA'),   # Aqualux amalgam
    (30016, 30026, 'AAM'),   # Azure aura mix
    (30017, 30027, 'LLL'),   # Liplack liquor
    (30018, 30028, 'ALL'),   # Anti-leech lotion
    (30019, 30029, 'MLL'),   # Megalite liquid
    (30020, 30030, 'MAL'),   # Mixalot
]


def picture(i):
    local = os.path.join(ROOT, 'tools', 'items', 'icon_cache', f'{i}.png')
    if os.path.exists(local):
        return Image.open(local).convert('RGBA')
    req = urllib.request.Request(ICON.format(i), headers={'User-Agent': AGENT})
    with urllib.request.urlopen(req, timeout=30) as r:
        return Image.open(io.BytesIO(r.read())).convert('RGBA')


def summary(img):
    px = img.load()
    out = bytearray()
    for cy in range(GY):
        for cx in range(GX):
            r = g = b = n = 0
            for y in range(cy * BH, (cy + 1) * BH):
                for x in range(cx * BW, (cx + 1) * BW):
                    pr, pg, pb, pa = px[x, y]
                    if pa > 128 and (pr, pg, pb) != SHADOW:
                        r += pr; g += pg; b += pb; n += 1
            if n:
                out += bytes((round(r / n), round(g / n), round(b / n), round(n * 255 / (BW * BH))))
            else:
                out += bytes(4)
    return bytes(out)


def apart(a, b):
    """How much two summaries' colours differ, a square, over the squares both mostly cover in the rows compared"""
    total = n = 0
    for sq in range(GX * USED_ROWS):
        if a[sq * 4 + 3] >= 128 and b[sq * 4 + 3] >= 128:
            total += sum(abs(a[sq * 4 + c] - b[sq * 4 + c]) for c in range(3))
            n += 1
    return total / max(n, 1)


rows = []
for unfinished, finished, recipe in POTIONS:
    for i in (unfinished, finished):
        img = picture(i)
        if img.size != (W, H):
            sys.exit(f'icon {i} is {img.size}, not {W} x {H}')
        rows.append((i, recipe, summary(img)))

closest = min((apart(a[2], b[2]), a[1], b[1]) for a in rows for b in rows if a[1] != b[1])
print(f'closest two kinds: {closest[1]} and {closest[2]}, {closest[0]:.1f} apart a square')
if closest[0] < MIN_APART:
    sys.exit('two kinds look too alike in the squares: not written')

with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
    f.write('# Made by tools/mixology/build_potion_summaries.py: item ID, recipe, 72 squares (red, green, blue, cover)\n')
    for i, recipe, s in rows:
        f.write(f'{i}\t{recipe}\t{s.hex()}\n')
print(f'{len(rows)} icons, {os.path.getsize(OUT)} bytes: {OUT}')
