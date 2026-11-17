# Updating each tool's data

Each source the update checker (`tools/update-check/check_updates.py`) reports, and where the routine for
bringing it in is written down. Follow the linked notes rather than working it out from the code. If a tool has
no notes yet, write them (in `docs/<tool>.md`) the first time you update it, and link them here.

| Checker name | Tool | How to update |
| --- | --- | --- |
| `teleports`, `dungeons`, `obstacles` | Teleport Finder | `docs/teleport-finder.md` |
| `quest-helper`, `new-quests` | Quest Helper | `docs/quest-helper.md` (new quests only arrive once the Quest Helper plugin has them) |
| `quest-helper`, `new-quests` | Bubble Sync | After any quest or diary change, run `tools/quest-helper/sync_tables.py` (`docs/quest-helper.md`, "After adding a quest or changing a diary"). A new skill or a new diary area also needs a new code format in the Bubble Tool Sync plugin (`C:\Users\Tanis\IdeaProjects\BubbleToolSync`, its `CLAUDE.md`) and in `AccountSync.kt`, both at once |
| `dps-items` | DPS Calculator | `docs/gear-items.md` (DPS section) |
| `puzzle-pictures` | Puzzle Box Solver | Run `python tools/puzzle-box/build_tile_fingerprints.py` (a new puzzle box: add it to the list at the top of that script first) |
| `new-gear` | DPS Calculator item lists | `docs/gear-items.md` |
| `toa` | ToA Puzzle Helper | `docs/toa-helper.md` (answers in `ToaPuzzles.kt`) |
| `hunter-rumours` | Hunter Rumours | `docs/hunter-rumours.md` |
| `zulrah` | Zulrah Helper | No notes yet. Rotations are hand-written in `ZulrahTool.kt` |
| `farming` | Timers | No notes yet. Crops and growth times are hand-written in `FarmingTimers.kt` (tested by the farming PC tests) |
| (not watched) | Mastering Mixology | A new or renamed potion: add it to `POTIONS` in `MixologyData.kt` and to the list in `tools/mixology/build_name_shapes.py`, run the script (`tools/README.md`), and check its own "told apart" report; `MixologyReaderTest` checks both lists match. For the bag tags, also add its two item IDs (without and with a cork) to `POTIONS` in `tools/mixology/build_potion_summaries.py` and run it: it makes `mixology/potions.tsv` (each potion's icon as 9 x 8 colour squares, from RuneLite's item icons; no pictures are kept) and stops if two kinds look too alike; rerun it too if the game redraws a potion (`MixologyBagTest` checks the file against `MixologyData.kt` and the owner's recording). The recipes and item IDs come from the Mastering Mixology plugin (github.com/hex-agon/mastering-mixology; last read at commit `10f4ced`, see `.scratch/mixology/plugin-features.md`) |
| (not watched: DroidFish rarely changes) | Chess (Game Room) | `docs/chess.md`, "Updating the engine" |
| `game-updates` | Everything | Read the posts; anything a tool needs goes through that tool's row above |

For any wiki page, `python tools/wiki_diff.py "Page title" OLDID` shows an edit as plain text.

After an update: build, run the PC tests, mark the source with `--checked NAME`, and update the tool's notes if
anything in the routine changed (new step, renamed file, a gotcha you hit).
