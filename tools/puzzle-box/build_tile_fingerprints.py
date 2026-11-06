"""Makes assets/puzzle_tiles.bin: a short colour summary of every tile of every puzzle box, for the Puzzle Box Solver.

The app comes with these summaries instead of the solved pictures. This script reads each solved picture from the
OSRS Wiki while it runs and never saves it. Each picture is cut into its 25 tiles, and the middle 70% of each tile
is shrunk to 8 x 8 average colours (exactly as TileMatcher.kt does to the tiles on your screen).

The file: "PZL1", the number of puzzles (1 byte), then for each puzzle its name's length (1 byte), its name, and
25 tiles x 8 x 8 cells x red, green, blue (1 byte each).

A new puzzle box: add it to PUZZLES below and run this again. No change to the app's code is needed.

Needs Pillow and numpy. Usage (from the project folder): python tools/puzzle-box/build_tile_fingerprints.py
"""
import io, os, urllib.request
import numpy as np
from PIL import Image

# Puzzle name as the app shows it, and its picture on the wiki
PUZZLES = [
    ('Castle', 'Castle_puzzle_solved.png'),
    ('Tree', 'Tree_puzzle_solved.png'),
    ('Troll', 'Troll_puzzle_solved.png'),
    ('Zulrah', 'Zulrah_puzzle_solved.png'),
    ('Cerberus', 'Cerberus_puzzle_solved.png'),
    ('Gnome child', 'Gnome_child_puzzle_solved.png'),
    ('Theatre of Blood', 'Theatre_of_Blood_puzzle_solved.png'),
]
WIKI = 'https://oldschool.runescape.wiki/images/'
AGENT = 'OSRSBubbleTool picture builder (github.com/sinat50/OSRSBubbleTool)'
GRID, SAMPLE, INNER = 5, 8, 0.7
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'assets', 'puzzle_tiles.bin')


def download(name):
    request = urllib.request.Request(WIKI + name, headers={'User-Agent': AGENT})
    data = urllib.request.urlopen(request, timeout=30).read()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise SystemExit(f'{name}: the wiki did not send a picture')
    return data


def tile_area(rgba):
    # The wiki pictures include the puzzle window's brown frame: finds the frame's inner edge on each side (the
    # strongest straight edge near that side) and keeps what's inside.
    rgb = rgba[:, :, :3].astype(np.int64) * (rgba[:, :, 3:] > 0)     # see-through corners count as black
    lum = (rgb[:, :, 0] * 3 + rgb[:, :, 1] * 6 + rgb[:, :, 2]) // 10
    h, w = lum.shape
    columns = lambda x: np.abs(lum[:, x] - lum[:, x - 1]).sum()
    rows = lambda y: np.abs(lum[y, :] - lum[y - 1, :]).sum()
    strongest = lambda a, b, edge: max(range(a, b + 1), key=edge)    # the first of any equal ones
    left = strongest(max(1, int(w * 0.02)), int(w * 0.15), columns)
    right = strongest(int(w * 0.85), int(w * 0.98), columns)
    top = strongest(max(1, int(h * 0.02)), int(h * 0.15), rows)
    bottom = strongest(int(h * 0.85), int(h * 0.98), rows)
    if right - left < w / 2 or bottom - top < h / 2:
        raise SystemExit('could not find the puzzle inside its frame')
    return rgb[top:bottom, left:right]


def fingerprint(rgb):
    h, w = rgb.shape[:2]
    tile_w, tile_h = w / GRID, h / GRID
    out = bytearray()
    for tile in range(GRID * GRID):
        x0 = (tile % GRID) * tile_w + tile_w * (1 - INNER) / 2
        y0 = (tile // GRID) * tile_h + tile_h * (1 - INNER) / 2
        inner_w, inner_h = tile_w * INNER, tile_h * INNER
        for cy in range(SAMPLE):
            sy0 = min(max(int(y0 + inner_h * cy / SAMPLE), 0), h - 1)
            sy1 = min(max(sy0 + 1, int(y0 + inner_h * (cy + 1) / SAMPLE)), h)
            for cx in range(SAMPLE):
                sx0 = min(max(int(x0 + inner_w * cx / SAMPLE), 0), w - 1)
                sx1 = min(max(sx0 + 1, int(x0 + inner_w * (cx + 1) / SAMPLE)), w)
                average = rgb[sy0:sy1, sx0:sx1].reshape(-1, 3).mean(0)
                out += bytes(int(round(c)) for c in average)
    return bytes(out)


def main():
    out = bytearray(b'PZL1') + bytes([len(PUZZLES)])
    for name, picture in PUZZLES:
        rgba = np.asarray(Image.open(io.BytesIO(download(picture))).convert('RGBA'))
        encoded = name.encode('utf-8')
        out += bytes([len(encoded)]) + encoded + fingerprint(tile_area(rgba))
    with open(OUT, 'wb') as f:
        f.write(out)
    print(f'{len(PUZZLES)} puzzles, {len(out):,} bytes: {os.path.normpath(OUT)}')


main()
