"""Works out which items look exactly alike in the game, into app/src/main/assets/items/canvas.tsv, for the DPS
Calculator's Import my gear (it can't tell those apart, so it picks one). No pictures go into the app.

Source: RuneLite's item icons (static.runelite.net/cache/item/icon/<id>.png), rendered from the game's own files by
RuneLite (github.com/runelite/runelite); the pictures are Jagex's. They're downloaded into tools/items/icon_cache/ on
this computer only (left out of Git, never part of the app), so a later run only fetches new items' icons, and
tools/dps/build_gear_summaries.py reads them from there too. Two items look exactly alike when their icons are the
same pixel for pixel.

Run it after build_items.py (it needs items/items.tsv and items/noted.tsv). Icons already there are skipped.

Makes:
  items/canvas.tsv   item ID, then the ID of the item it looks just like (the lowest ID among them; only for items
                     that look like another)

Usage: python3 build_icons.py path/to/app/src/main/assets
"""
import hashlib, os, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor
from PIL import Image   # (pip install pillow)

assets = sys.argv[1]
AGENT = 'OldSchoolBubbleTool item icon builder (github.com/sinat50/OldSchoolBubbleTool)'
ICON = 'https://static.runelite.net/cache/item/icon/{}.png'

folder = os.path.join(assets, 'items')
# the icons, on this computer only (see above)
canvas = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'icon_cache')
os.makedirs(canvas, exist_ok=True)

ids = set()
for name in ('items.tsv', 'noted.tsv'):
    for line in open(os.path.join(folder, name), encoding='utf-8'):
        if line.startswith('#') or not line.strip():
            continue
        parts = line.rstrip('\n').split('\t')
        for p in (parts[:1] if name == 'items.tsv' else parts[:2]):
            ids.add(int(p))
print(len(ids), 'item IDs (with noted versions)')

# Already shared with another icon in an earlier run: not downloaded again
shared = {}
tsv = os.path.join(folder, 'canvas.tsv')
if os.path.exists(tsv):
    for line in open(tsv, encoding='utf-8'):
        if not line.startswith('#') and line.strip():
            a, b = line.split('\t')
            shared[int(a)] = int(b)

def fetch(item_id):
    path = os.path.join(canvas, f'{item_id}.png')
    if os.path.exists(path) or item_id in shared:
        return True
    try:
        req = urllib.request.Request(ICON.format(item_id), headers={'User-Agent': AGENT})
        data = urllib.request.urlopen(req, timeout=60).read()
    except Exception:
        return False
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        return False
    open(path, 'wb').write(data)
    return True

with ThreadPoolExecutor(6) as pool:   # gentle on RuneLite's server
    ok = sum(pool.map(fetch, sorted(ids)))
print(f'{ok} of {len(ids)} icons there')

# Items that look exactly alike share one icon (the lowest ID's)
first_with = {}
for item_id in sorted(ids):
    path = os.path.join(canvas, f'{item_id}.png')
    if item_id in shared or not os.path.exists(path):
        continue
    im = Image.open(path).convert('RGBA')
    key = hashlib.sha1(im.tobytes()).hexdigest()
    if key in first_with:
        shared[item_id] = first_with[key]
        os.remove(path)
    else:
        first_with[key] = item_id
with open(tsv, 'w', encoding='utf-8', newline='\n') as out:
    out.write('# Item ID, then the ID of the item it looks exactly like in the game (from RuneLite icons). Built by tools/items/build_icons.py\n')
    for a in sorted(shared):
        out.write(f'{a}\t{shared[a]}\n')
print(f'items/canvas.tsv: {len(shared)} items look exactly like another ({len(first_with)} different icons)')
