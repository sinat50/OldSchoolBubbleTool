# DPS Calculator: updating the item lists

Import my gear uses two item lists from separate sources. Gear Loadouts keeps screenshots and needs no item list
(only loadouts saved before November 2026 use `items.tsv`, for their items' names). No game pictures go into the app.
Last done 6 November 2026 (Pete Kayer's Challenges copies, Vampyre costume, Amulet of sight).

| List | Used for | From | Built by |
| --- | --- | --- | --- |
| `assets/items/items.tsv`, `noted.tsv` | Every item's ID, name, slot and wiki picture name: naming what Import my gear read, and which items the summaries are made for | OSRS Wiki item tables (API "bucket" queries); noted IDs from RuneLite | `tools/items/build_items.py` |
| `assets/items/canvas.tsv` | Which items look exactly alike (Import my gear picks one) | RuneLite's item icons (static.runelite.net), kept in `tools/items/icon_cache/` on the PC only | `tools/items/build_icons.py` |
| `assets/dps/gear_summaries.bin` | Every wearable item's icon as 9 x 8 colour squares (no pictures) | RuneLite's item icons | `tools/dps/build_gear_summaries.py`, after `build_items.py` and `build_icons.py` (new items need a summary too) |
| `assets/dps/equipment.json` | The calculator's own item list: which items it can take | The wiki DPS calculator repository (weirdgloop/osrs-dps-calc) at `CalculatorItems.COMMIT` | `tools/dps/build_equipment_json.py` |

## Is anything new?

The update checker's `new-gear` source lists equipment on the wiki that either list lacks, grouped by release
date (the same check as `python tools/items/check_new_gear.py`, read-only, about a minute). Items missing from the
item list always show until they're added. The DPS Calculator's can't be added until the wiki's calculator has them,
so once seen, `--checked new-gear` hides those released before that day (newer gear still shows). The `dps-items`
source says when the calculator's own item list changes.

## The item list (any time the check lists items)

1. `python -I tools/items/build_items.py app/src/main/assets` (a minute or two: text only).
2. **Right after**, `python -I tools/items/build_icons.py app/src/main/assets` (needs `pip install pillow`; only new
   items' icons are downloaded, into `tools/items/icon_cache/`).
3. `python tools/dps/build_gear_summaries.py`, so Import my gear can recognise the new items.
4. Run `check_new_gear.py` again: the item list's part should be empty. RuneLite's icons for brand-new items can
   lag a few days; a later rerun picks them up. A new copy of an item that looks exactly like it (a minigame's copy,
   say) gets no summary of its own: `canvas.tsv` points it at the original's. `gear_summaries.bin` can get smaller
   after a run: items the wiki no longer lists as worn (no slot) drop out.
5. Build and run the PC tests (`GearImportTest` uses the owner's screenshots, skipped without them).
6. Git shows "LF will be replaced by CRLF" for the `.tsv` files: harmless.

## DPS Calculator (only when the calculator itself has the items)

The DPS tool loads your gear into the wiki's calculator by item ID, so an item the calculator doesn't know can't
be used there, whatever the app knows. Until the calculator adds new gear, Import my gear leaves that slot empty and
says the calculator doesn't have it: that's correct, nothing to fix.

When the update checker reports `dps-items` (the repository's `cdn/json/equipment.json` changed after
`CalculatorItems.COMMIT`): move `COMMIT` in `CalculatorItems.kt` to the newest commit, then run
`build_equipment_json.py` as in `tools/README.md`. Commits that only change the calculator's code (most of them) need
nothing: the app shows the live calculator. The app keeps only each item's id, name, version, slot and picture name,
so new fields or `category` values in the calculator's file (a weapon type such as "Powered Book") need no change in
the app. Items are matched by ID first, so the wiki renaming an item (as "Rune sword" to "Rune shortsword") is
harmless too.

## History

- 24 Oct 2026: 30 new items (Ascension crossbows and bolts, Obligator, Rondache, Zorya's tome, Living record
  tiers 1-5, and Fractured Archive quest items). The DPS calculator's item list was last changed 2 September
  2026, so it has none of the October gear (nor the 7 October Imbued ancient capes) yet.
- 26 Oct 2026: DPS calculator moved to 741f2f2 (24 October list): 25 new entries (Ascension crossbows and bolts,
  Obligator, Rondache, Zorya's tome, Living record, Imbued ancient capes, locked Dizana's quiver), and 43 item
  names and pictures recapitalised (Moon armour, "of Ruin", Scythe of Vitur...). `wiki_icons.txt` unchanged.
- 5 Nov 2026: no more game pictures. Gear Loadouts keeps screenshots, so `items/canvas/`, `items/icons/`,
  `items/spellbooks/`, `canvas.bin`, `shapes.bin` and `dps/icons/` were removed (68 MB of assets down to 4 MB),
  with `fetch_pictures.py`, `wiki_icons.txt` and `item_shapes.py`. RuneLite's icons now stay in
  `tools/items/icon_cache/` on the PC, for `canvas.tsv` and the gear summaries.
- 6 Nov 2026: item list: 32 new IDs, 12 of them gear: the 7 October copies of Karil's leatherskirt, Bandos
  chestplate, Fighter torso, Black d'hide shield, Torva full helm, Abyssal tentacle and Dragon boots used in Pete
  Kayer's Challenges (they look exactly like the originals, so share their summaries), the Vampyre monocle and
  costume top, bottom and cape (28 October) and the Amulet of sight (3 November). The wiki renamed the swords to
  shortswords (Bronze to Dragon, White, Wooden, Training; Training bow to Training shortbow) and stopped listing the
  440 Sailing cargo crates as worn, so `gear_summaries.bin` went from 4,253 to 4,228 icons (1,246,137 to 1,238,812
  bytes). DPS calculator moved to 3bedf51 (31 October list): the 7 Pete Kayer's copies and 4 Vampyre items added,
  376 cargo crates removed (5,460 to 5,095 items), Zorya's tome now a "Powered Book", Imbued ancient capes no longer
  "Unarmed". It doesn't have the Amulet of sight yet. `dps/SOURCE.txt` brought up to date (it still named the
  removed pictures).
