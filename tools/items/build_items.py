"""Builds the item list, into app/src/main/assets/items/: text only, for the DPS Calculator's Import my gear and the
item lists of older Gear Loadouts. No pictures are downloaded or kept (the app comes with no game pictures).

Source: the OSRS Wiki's item tables (oldschool.runescape.wiki, text under CC BY-NC-SA 3.0), read through the
wiki's API: "infobox_item" (every item version: ID, name, picture name) and "infobox_bonuses" (which equipment slot
it goes in).

Makes:
  items/items.tsv  one line per item ID: ID, name, equipment slot (empty if it isn't worn), picture name(s),
                   and the wiki page if it isn't the item's name (minigame and quest copies have their own page).
                   Items whose picture changes with the stack size (coins, arrows...) list several pictures,
                   separated by "|"; the number at the end of each name ("Coins 250.png") is the smallest stack
                   it's for.
                   (Characters Windows can't have in a file name are "_": "Guam in a box?.png" is written
                   "Guam in a box_.png". The DPS tool matches items to the calculator's by these names.)
  items/noted.tsv  each item that can be noted, and its noted version's ID.

Run it again after a game update to pick up new items, then tools/items/build_icons.py.

Usage: python3 build_items.py path/to/app/src/main/assets
"""
import html, json, os, re, sys, urllib.parse, urllib.request

assets = sys.argv[1]
AGENT = 'OldSchoolBubbleTool item list builder (github.com/sinat50/OldSchoolBubbleTool)'
API = 'https://oldschool.runescape.wiki/api.php'
# The slot names the app uses (the same as the DPS tool's), from the wiki's names
SLOTS = {'head': 'head', 'cape': 'cape', 'neck': 'neck', 'ammo': 'ammo', 'weapon': 'weapon', '2h': 'weapon',
         'body': 'body', 'shield': 'shield', 'legs': 'legs', 'hands': 'hands', 'feet': 'feet', 'ring': 'ring'}

def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': AGENT})
    return urllib.request.urlopen(req, timeout=60).read()

def table(name, fields):
    """Every row of one of the wiki's tables, 5,000 at a time"""
    rows, offset = [], 0
    while True:
        query = f"bucket('{name}').select({','.join(repr(f) for f in fields)}).limit(5000).offset({offset}).run()"
        part = json.loads(fetch(API + '?' + urllib.parse.urlencode({'action': 'bucket', 'format': 'json', 'query': query})))
        if 'bucket' not in part:
            sys.exit(f'The wiki refused the {name} query: {part}')
        rows += part['bucket']
        offset += len(part['bucket'])
        if len(part['bucket']) < 5000:
            return rows

def clean(text):
    # Web page bits some wiki names carry ("&#39;" for an apostrophe, a "[sic]" note) come out, and tabs and
    # line breaks would break the file's columns
    text = re.sub(r'<sup.*?</sup>', '', str(text))
    text = html.unescape(re.sub(r'<[^>]*>', '', text))
    return ' '.join(text.split())

items = table('infobox_item', ['page_name', 'page_name_sub', 'item_name', 'item_id', 'image'])
bonuses = table('infobox_bonuses', ['page_name', 'page_name_sub', 'equipment_slot'])

# Slot by page and version ("Page#Version"); or by page alone when the page has only one slot for all versions
slot_by_sub, slots_by_page = {}, {}
for b in bonuses:
    slot = SLOTS.get(str(b.get('equipment_slot', '')).strip().lower())
    if not slot:
        continue
    slot_by_sub[b.get('page_name_sub', '')] = slot
    slots_by_page.setdefault(b.get('page_name', ''), set()).add(slot)

# The name each picture is saved under: characters Windows can't have in a file name become "_", and names
# that differ only in capitals ("Unfinished batta (worm)" and "(Worm)", the same picture) share one file,
# since Windows sees them as one
saved_as = {}

def file_name(picture):
    safe = re.sub(r'[\\/:*?"<>|]', '_', picture)
    return saved_as.setdefault(safe.lower(), safe)

lines = {}
for r in items:
    page = r.get('page_name', '')
    name = clean(r.get('item_name') or page)
    slot = slot_by_sub.get(r.get('page_name_sub', ''))
    if slot is None and len(slots_by_page.get(page, ())) == 1:
        slot = next(iter(slots_by_page[page]))
    files = [clean(p[len('File:'):]) for p in r.get('image', []) if p.startswith('File:')]
    for raw_id in r.get('item_id', []):
        try:
            item_id = int(str(raw_id).strip())
        except ValueError:
            continue   # a few pages hold notes instead of an ID ("beta", "N/A")
        if item_id not in lines:   # the first row wins if two pages claim one ID
            stored = [file_name(f) for f in files]
            # the wiki page, when it isn't the item's name: copies of an item from a minigame or quest are on
            # their own page ("Rune pouch (Last Man Standing)") but have the same name in the game
            page_note = clean(page) if clean(page) != name else ''
            lines[item_id] = '\t'.join([str(item_id), name, slot or '', '|'.join(stored), page_note])

folder = os.path.join(assets, 'items')
os.makedirs(folder, exist_ok=True)
with open(os.path.join(folder, 'items.tsv'), 'w', encoding='utf-8', newline='\n') as out:
    out.write('# OSRS item IDs, names, equipment slots and picture names, from the OSRS Wiki (CC BY-NC-SA 3.0). '
              'Built by tools/items/build_items.py\n')
    for item_id in sorted(lines):
        out.write(lines[item_id] + '\n')
print(len(lines), 'item IDs,', sum(1 for l in lines.values() if l.split('\t')[2]), 'worn items')

# Noted items: each item that can be noted, and its noted version's ID (a noted item is its own item in the game,
# drawn on a bank note). From RuneLite's list of the game's item IDs (github.com/runelite/runelite,
# net/runelite/api/gameval/ItemID.java, BSD 2-Clause): its "Cert" list holds the noted IDs, under the same names
# as the items themselves. items/noted.tsv: item ID, noted ID.
RUNELITE_ITEMS = 'https://raw.githubusercontent.com/runelite/runelite/master/runelite-api/src/main/java/net/runelite/api/gameval/ItemID.java'
source = fetch(RUNELITE_ITEMS).decode('utf-8', 'replace')
cert_at = source.find('class Cert')
if cert_at < 0:
    sys.exit('RuneLite item list: no Cert (noted) section found')
constant = re.compile(r'public static final int (\w+)\s*=\s*(\d+)\s*;')
plain_ids = {n: int(v) for n, v in constant.findall(source[:cert_at])}
cert_part = source[cert_at:].split('public static final class', 1)[0]   # up to the next list
noted_ids = {n: int(v) for n, v in constant.findall(cert_part)}
noted_pairs = sorted((plain_ids[n], v) for n, v in noted_ids.items() if n in plain_ids and plain_ids[n] in lines)
with open(os.path.join(folder, 'noted.tsv'), 'w', encoding='utf-8', newline='\n') as out:
    out.write('# Item ID and its noted version\'s ID, from RuneLite\'s item list (BSD 2-Clause). Built by tools/items/build_items.py\n')
    for plain, noted in noted_pairs:
        out.write(f'{plain}\t{noted}\n')
print('items/noted.tsv:', len(noted_pairs), 'notable items')
