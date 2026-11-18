"""Downloads every puzzle box's solved picture from the OSRS Wiki, for the PC test PuzzleAllBoxesTest only.

The pictures go into tools/puzzle-box/picture_cache/, which Git ignores: they stay on this computer and are never
part of the app (the app ships only the colour summaries in puzzle_tiles.bin). The list of puzzles is read from
puzzle_tiles.bin itself, so a puzzle added to build_tile_fingerprints.py is picked up here too once that has run.

Usage (from the project folder): python tools/puzzle-box/get_test_pictures.py
"""
import os, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
TILES = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'assets', 'puzzle_tiles.bin')
CACHE = os.path.join(HERE, 'picture_cache')
WIKI = 'https://oldschool.runescape.wiki/images/'
AGENT = 'OldSchoolBubbleTool picture builder (github.com/sinat50/OldSchoolBubbleTool)'
TILE_BYTES = 25 * 8 * 8 * 3


def puzzle_names():
    data = open(TILES, 'rb').read()
    if data[:4] != b'PZL1':
        raise SystemExit('puzzle_tiles.bin is not a puzzle tiles file')
    names, at = [], 5
    for _ in range(data[4]):
        length = data[at]
        names.append(data[at + 1:at + 1 + length].decode('utf-8'))
        at += 1 + length + TILE_BYTES
    return names


def main():
    os.makedirs(CACHE, exist_ok=True)
    for name in puzzle_names():
        # The same naming as the wiki's pictures (and as the test expects): "Gnome child" -> Gnome_child_puzzle_solved.png
        file = name.replace(' ', '_') + '_puzzle_solved.png'
        request = urllib.request.Request(WIKI + file, headers={'User-Agent': AGENT})
        data = urllib.request.urlopen(request, timeout=30).read()
        if data[:8] != b'\x89PNG\r\n\x1a\n':
            raise SystemExit(f'{file}: the wiki did not send a picture')
        with open(os.path.join(CACHE, file), 'wb') as f:
            f.write(data)
        print(f'{file}: {len(data):,} bytes')


main()
