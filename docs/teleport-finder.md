# Teleport Finder: updating its data

How to rebuild the Teleport Finder's data after the update checker reports `teleports` (Shortest Path),
`dungeons` (RuneLite) or `obstacles` (Golems Don't Die). Last done 7 November 2026 (Shortest Path a4aa30a,
7 November), in about 15 minutes all told. The scripts are described in `tools/README.md`; this is the whole
routine, in order.

## The files

| File | Made by | From |
| --- | --- | --- |
| `assets/teleports.tsv` | `build_teleports_and_walks.py` | Shortest Path's `transports/*.tsv` teleport files, plus `FIX` (corrected landing spots), `HOW` (our notes on teleports it has) and `ADD` (teleports Shortest Path doesn't have) in the script |
| `assets/walks.txt` | `build_teleports_and_walks.py` | Shortest Path's `transports.tsv` and friends (doors, ladders, boats...), plus `CAVES`, `CAVE_BARRIERS`, `GUESSED_WAYS` in the script and the joins in `extra_walks.txt` |
| `assets/collision.bin` | `build_collision.py` | Shortest Path's `collision-map.zip` |
| `assets/dungeons.txt` | `build_dungeons.py` | RuneLite's `DungeonLocation.java` (only when `dungeons` is reported) |
| `tools/teleport-finder/extra_walks.txt` | `find_closed_off_areas.py` | The three files above, plus Golems Don't Die's `obstacles.gz` |

The app reads these with `TeleportData.kt` and `WalkMap.kt`, which have no Android parts (so the route check below
runs them on the PC).

## The routine

Work in the scratchpad (call it `W`), never in the project, and copy results in at the end. Run Python with `-I`.

1. **Download**: `git clone --depth 1` each of github.com/Skretzo/shortest-path and
   github.com/Varzeki/golems-dont-die (and github.com/runelite/runelite only if `dungeons` changed).
   Keep a copy of the current `teleports.tsv`, `walks.txt`, `collision.bin`, `dungeons.txt` in `W/old/`.
2. **Collision map**: `python -I tools/teleport-finder/build_collision.py W/shortest-path/src/main/resources/collision-map.zip W/new/collision.bin`.
   First check `FLAG_COUNT` in Shortest Path's `pathfinder/SplitFlagMap.java` still matches `FLAGS_IN` in the
   script (4 since 9 October 2026).
3. **Build without joins**: `python -I tools/teleport-finder/build_teleports_and_walks.py W/shortest-path W/empty.txt W/nojoin`
   (`empty.txt` = an empty file; `W/nojoin` must exist), then copy `collision.bin` and `dungeons.txt` into `W/nojoin/`.
   Give this script **absolute** paths: it moves into the output folder before reading, so relative ones aren't found.
4. **Look at the teleport changes**: `diff W/old/teleports.tsv W/nojoin/teleports.tsv`. Things to act on:
   - an `ADD` entry that Shortest Path now has itself: it appears twice in the diff (once moved). Remove it from
     `ADD` (the script skips names already there, but the entry is then dead weight), and move its note (the last
     column) to `HOW` if it's still true. If Shortest Path's landing spot differs from ours, look at which is right.
   - a `FIX` entry that Shortest Path now has right itself (compare its line): remove it.
   - a `FIX` entry for a teleport that was renamed: the fix silently stops applying. Rename it in `FIX`.
   - renamed teleports: `grep` the app's code and `tools/teleport-finder/test/` for the old name.
   - a new kind of menu key in front of network names (`D: Civitas illa Fortis`): widen `MENU_KEY` in the script.
   - also check the column headers of the files the script reads (`grep -m1 "^# \?\(Origin\|Destination\)"`): it
     reads columns by name, so a renamed column silently empties it.
5. **Joins** (20 to 75 seconds, needs numpy and scipy):
   `python -I tools/teleport-finder/find_closed_off_areas.py W/nojoin/collision.bin W/nojoin/walks.txt W/nojoin/teleports.tsv W/golems-dont-die/src/main/resources/obstacles.gz W/new_extra_walks.txt`
6. **Final build**: step 3 again with `W/new_extra_walks.txt` instead of the empty file, into `W/final/`
   (it should print `joins added` with the number from step 5).
7. **Check routes** (below). Fewer guesses and no lost results is what you want; read every changed top result.
8. **Copy in**: `teleports.tsv`, `walks.txt`, `collision.bin` (and `dungeons.txt`) to `app/src/main/assets/`,
   `new_extra_walks.txt` to `tools/teleport-finder/extra_walks.txt`; compare them with `cmp` after a few seconds.
   `walks.txt` always shows a diff of thousands of lines (its numbering shifts): that's normal. Its names are
   the in-game actions ("Attract Light creature"), not places, so check a new transport is in by its
   coordinates: `grep "^x y plane x2 y2 plane2" walks.txt` with the first two columns of Shortest Path's line.
9. Build and run the PC tests, then `--checked teleports` (and `obstacles` / `dungeons`) in the update checker.

## Checking routes (`tools/teleport-finder/test/CompareRoutes.kt`)

There's no `kotlinc` on the owner's computer, but Gradle's cache has the compiler. Use the JDK in
`%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-25...\bin\java` (the `java` on the PATH is Java 8, too old).
From `~/.gradle/caches/modules-2/files-2.1/`, the class path is the jars of `kotlin-compiler-embeddable`,
`kotlin-stdlib`, `kotlin-script-runtime`, `kotlin-reflect`, `kotlin-daemon-embeddable` (all one recent
version that has all five: 2.2.10 in November 2026, as `kotlin-reflect` lags behind), `trove4j` 1.0.20200330
(if it's not in the cache, it's in Gradle's own `~/.gradle/wrapper/dists/gradle-*/.../lib/`),
`kotlinx-coroutines-core-jvm` and `annotations` 13.0. Use org.json **20240303 or newer** (20260814 works; the 2009
and 2018 ones fail on `keys()`). Separate paths with `;` and give Windows paths (`cygpath -w`). Don't run Gradle for
this: the compiler jars run straight from the cache.

```
java -cp <compiler jars> org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "<json>;<stdlib>" \
    tools/teleport-finder/test/CompareRoutes.kt app/src/main/java/com/sinat/osrsbubbletool/TeleportData.kt \
    app/src/main/java/com/sinat/osrsbubbletool/WalkMap.kt -d W/compare
java -cp "W/compare;<json>;<stdlib>" CompareRoutesKt tools/teleport-finder/test/targets.json \
    W/final/teleports.tsv W/final/collision.bin W/final/dungeons.txt W/old/walks.txt W/final/walks.txt
```

It prints, for each `walks.txt`, how many of the 153 test spots have no result or a guessed top result, then every
spot whose top result changed. (It uses the new `teleports.tsv` and `collision.bin` for both, so it compares the
walking data only.) October 2026: 25 → 23 guesses; Wise Old Man and Giant Mole got real routes, Corporeal Beast's
top answer became the Games necklace.

## History worth knowing

- 6 Oct 2026: Shortest Path data refreshed, extra_walks rerun (commit 7366986).
- 10 Oct 2026: Shortest Path's collision map went to 4 bits per tile; `build_collision.py` converts it.
- 24 Oct 2026: new transports (Chasm of Tears, Ancient Guthixian Temple), Aldarin house portal, clearer cape
  teleport names; the hand-added Wyrmscraig necklace of passage removed from `ADD` (Shortest Path has it now).
- 26 Oct 2026: Prifddinas city platforms and staircases, Iorwerth Dungeon shortcuts, Trahaearn Mine and Zalcano
  links (walks only: teleports and collision map unchanged); 14 more joins. Iorwerth Dungeon routes much shorter
  (Dark beasts 239 → 116, Greater Nechryael 198 → 75); still 23 guesses.
- 6 Nov 2026: 89 upstream commits (Motherlode Mine rockfalls, Twilight Temple and Tonali Cavern lifts, Digsite,
  Castle Drakan and the Vampyrium, Karamja jungle rows redone). Teleports 508 → 512 (Guthixian temple teleport,
  Archivist's folio, Master scroll book versions); five `ADD` scrolls and medallion spots and all three `FIX`
  spots are in Shortest Path now, so removed (their notes kept in `HOW`). The Chasm teleport scroll moved to the
  bottom of the Chasm of Fire, and Castle Drakan to the castle Shortest Path maps near the Vampyrium portal. Both checked in the game by the owner (6 Nov 2026).
  Charter ships gained letter keys ("D: ..."), now stripped like the numbers (that also tidied "Spirit tree: A:
  Hosidius" and the minecarts). The diary cape's Lumbridge spot is now Mikasi (game change, upstream #929; no
  format change). Joins 1588 → 1867, nearly all inside the Vampyrium, newly reachable. Guesses 23 → 22
  (Thermonuclear smoke devil got a real route); Chasm of Fire black demons now best by the Chasm scroll.
- 7 Nov 2026: 14 upstream commits. Teleports 512 → 545: Ring of returning (one line per respawn point, noted
  "Takes you to your respawn point only", since Shortest Path lists them all), the Perfected quetzal whistle(i),
  the Camelot tablet's Seers' Village spot, the Kourend Castle and Civitas illa Fortis tablets, the Mokhaiotl
  waystone, and the Bounty Hunter, Mastering Mixology and Sorceress's Garden minigame teleports. The Revenant cave
  teleport now has its three entrances. Shortest Path now has the Wyrmscraig Cavern slayer ring and the calcified
  moth, so both were removed from `ADD`. The moth lands at 1439 9550 (our guess was 1447 9566), and Shortest Path
  checks the quest stage itself, so it lists no quest: the "needs Perilous Moons" note moved to `HOW`. Collision map
  unchanged; joins 1867 → 1889; still 22 guesses, no top result changed.
