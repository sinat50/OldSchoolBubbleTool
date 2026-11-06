"""Builds app/src/main/assets/dps/equipment.json, the DPS Calculator's item list (which items Import my gear can load).

Source: the OSRS Wiki DPS calculator's repository (github.com/weirdgloop/osrs-dps-calc, GPL-3.0) at the
commit pinned in CalculatorItems.kt (COMMIT). Clone it and check out that commit first:
    git clone https://github.com/weirdgloop/osrs-dps-calc && cd osrs-dps-calc && git checkout <COMMIT>

Each item keeps id, name, version, slot, and "icon" (the name of its picture in cdn/equipment/: only the name, which
the DPS tool matches items by; no pictures go into the app). Items whose picture is missing from the repository
are left out.

If you move to a newer commit: update COMMIT in CalculatorItems.kt, then rebuild this file.

Usage: python3 build_equipment_json.py path/to/osrs-dps-calc path/to/equipment.json
"""
import json, os, sys

repo, out = sys.argv[1], sys.argv[2]
items = json.load(open(os.path.join(repo, 'cdn', 'json', 'equipment.json'), encoding='utf-8'))
rows = []
for o in items:
    if not os.path.exists(os.path.join(repo, 'cdn', 'equipment', o['image'])):
        continue
    e = {'id': o['id'], 'name': o['name'], 'version': o.get('version', ''), 'slot': o['slot'], 'icon': o['image']}
    rows.append(e)
json.dump(rows, open(out, 'w', encoding='utf-8'), separators=(',', ':'), ensure_ascii=False)
print(len(rows), 'items')
