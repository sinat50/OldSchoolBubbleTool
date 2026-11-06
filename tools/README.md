# Tools

Scripts that build the app's data files. The app doesn't need them to run or build: they're only for
updating the data (for example after a game update) and for showing how each bundled file was made.
Run them on a computer with Python 3, then copy the results into `app/src/main/assets/`.

`wiki_diff.py` shows what changed on an OSRS Wiki page since an older revision, as plain text (for the update
checker's wiki edits): `python tools/wiki_diff.py "Page title" OLD_REVISION_NUMBER`.

## Teleport Finder (`teleport-finder/`)

The full update routine, step by step, with the route check: `docs/teleport-finder.md`.

Sources to download first:

```
git clone https://github.com/Skretzo/shortest-path          # teleports, transports, collision map (BSD 2-Clause)
git clone --depth 1 https://github.com/runelite/runelite    # DungeonLocation.java (BSD 2-Clause)
git clone https://github.com/Varzeki/golems-dont-die        # obstacles.gz (BSD 2-Clause)
```

| Script | Makes | Notes |
| --- | --- | --- |
| `build_collision.py` | `collision.bin` | `python3 build_collision.py shortest-path/src/main/resources/collision-map.zip collision.bin` |
| `build_dungeons.py` | `dungeons.txt` | `python3 build_dungeons.py runelite/runelite-client/src/main/java/net/runelite/client/plugins/worldmap/DungeonLocation.java dungeons.txt` |
| `build_teleports_and_walks.py` | `teleports.tsv`, `walks.txt` | `python3 build_teleports_and_walks.py shortest-path extra_walks.txt app/src/main/assets`. Its FIX, ADD, CAVES and GUESSED_WAYS lists hold the hand-checked corrections and additions: add new teleports and cave entrances there. |
| `find_closed_off_areas.py` | `extra_walks.txt` | Joins areas no teleport reaches. Needs numpy and scipy and several GB of memory; about 20 seconds. See the notes at the top of the script for the order to run things in. |

`extra_walks.txt` is kept here so the build script works without rerunning the slow analysis.

### Checking a change (`teleport-finder/test/`)

`targets.json` lists 95 places (153 map spots) used to check the Teleport Finder. `CompareRoutes.kt`
runs them against one or more versions of `walks.txt` and prints how many results are guesses and every
place whose top result changed. Build and run instructions are at the top of the file.

## Item list (`items/`)

Text only, for the DPS Calculator's Import my gear (and the item lists of loadouts saved before Gear Loadouts kept
screenshots). No pictures go into the app. No downloads needed first: the scripts read the OSRS Wiki's item tables
through the wiki's API, and RuneLite's item icons.

| Script | Makes | Notes |
| --- | --- | --- |
| `build_items.py` | `items/items.tsv`, `items/noted.tsv` | `python3 build_items.py app/src/main/assets` (from the project folder). Every item's ID, name, equipment slot and picture name from the OSRS Wiki, and noted IDs from RuneLite's item list. A minute or two. |
| `build_icons.py` | `items/canvas.tsv` | `python3 build_icons.py app/src/main/assets`, **right after** `build_items.py` (needs Pillow). Which items look exactly alike in the game, from RuneLite's item icons, which it keeps in `tools/items/icon_cache/` on this computer only (left out of Git, never in the app) so later runs only fetch new ones. About 15 minutes with an empty cache (21,000 icons), seconds after. |

## Quest Helper (`quest-helper/`)

`quest_files.py` shows, adds and replaces quests in `QuestData.kt` and `QuestGuides.kt` (the app's code folder),
from one JSON file per quest kept in `quest-helper/quests/`. Each file is written by hand from the Quest Helper
plugin's Java, following `docs/quest-helper.md`. `python tools/quest-helper/quest_files.py` (from the project
folder) lists its commands.

`sync_tables.py` makes `SyncTables.kt` for Bubble Sync: each quest's RuneLite ID and each diary task's place in the
Bubble Tool Sync plugin's code. Run it after adding a quest or changing a diary: `python
tools/quest-helper/sync_tables.py QUEST_HELPER_FOLDER QUEST_JAVA` (a clone of the Quest Helper plugin and RuneLite's
`Quest.java`; details at the top of the script and in `docs/quest-helper.md`).

## Puzzle Box Solver (`puzzle-box/`)

`python tools/puzzle-box/build_tile_fingerprints.py` (from the project folder; needs Pillow and numpy) makes
`puzzle_tiles.bin`: each puzzle's tiles as 8 x 8 average colours, read from the OSRS Wiki's solved pictures, which
are not saved. A new puzzle box only needs a new line in the script's `PUZZLES` list. `PuzzleTilesTest` checks the file.

## DPS Calculator gear import (`dps/`)

```
git clone https://github.com/weirdgloop/osrs-dps-calc       # GPL-3.0
cd osrs-dps-calc && git checkout <CalculatorItems.COMMIT>
```

| Script | Makes | Notes |
| --- | --- | --- |
| `build_equipment_json.py` | `dps/equipment.json` | `python3 build_equipment_json.py osrs-dps-calc equipment.json` |
| `build_gear_summaries.py` | `dps/gear_summaries.bin` | `python tools/dps/build_gear_summaries.py` (from the project folder). Every wearable item's icon (RuneLite's, from `tools/items/icon_cache/` or downloaded) as 9 x 8 colour squares, for Import my gear, which keeps no item pictures. The owner's limit is 9 x 8 at the most. Run it after the item lists change (`tools/items/`). `GearImportTest` checks it on the owner's screenshots. |

If you move to a newer version of the repository, update `COMMIT` in `CalculatorItems.kt`, then rebuild `equipment.json`.
