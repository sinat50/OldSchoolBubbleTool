"""Makes app/src/main/assets/mixology/names.tsv: the shape of each Mastering Mixology potion name, for the Mastering
Mixology tool, which reads the three potion orders from the game's Potion Orders box by comparing each line of text
with these shapes.

Each name is written in RuneLite's copy of the game's plain font (runescape.ttf, BSD 2-Clause, downloaded from
RuneLite's GitHub at the commit below and never saved), at 16 px, which is the game's own size: one font pixel is one
game pixel. Its shape is then: its width (in game pixels), and how much ink each column has, squeezed to BINS
columns. That's a row of numbers per name, not a picture. No font file goes into the app.

The script then checks the shapes: each name, written bigger and blurred the way a phone scales the game, must still
fit its own shape best, and clearly (by MARGIN). It stops with an error if any doesn't.

File layout (tab-separated, lines starting with # are notes): name, width, then BINS numbers (ink per column, in
hundredths of a pixel).

Usage (from the project folder; needs Pillow and numpy): python tools/mixology/build_name_shapes.py
A new potion only needs its name in NAMES here and its recipe in MixologyData.kt.
"""
import io, os, sys, urllib.request
import numpy as np                              # (pip install numpy)
from PIL import Image, ImageDraw, ImageFont     # (pip install pillow)

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'mixology', 'names.tsv')
COMMIT = 'bb0fa42b3d9329ffe31115f1f20042c455def2e3'   # RuneLite, 14 November 2026
FONT = ('https://raw.githubusercontent.com/runelite/runelite/' + COMMIT +
        '/runelite-client/src/main/resources/net/runelite/client/ui/runescape.ttf')
AGENT = 'OldSchoolBubbleTool name shape builder (github.com/sinat50/OldSchoolBubbleTool)'

# Exactly as the game writes them in the Potion Orders box (checked in game: "Aqualux amalgam" is one word)
NAMES = ['Mammoth-might mix', 'Mystic mana amalgam', "Marley's moonlight", 'Alco-augmentator', 'Azure aura mix',
         'Aqualux amalgam', 'Liplack liquor', 'Megalite liquid', 'Anti-leech lotion', 'Mixalot']
BINS = 48          # must match MixologyReader.BINS
MARGIN = 0.3       # how much better than the runner-up each name must fit in the check
WIDTH_WEIGHT = 3   # must match MixologyReader.WIDTH_WEIGHT


def ink(name, font):
    """The name written in white on black: which pixels are ink, cut to its own width"""
    im = Image.new('L', (420, 40))
    ImageDraw.Draw(im).text((5, 5), name, font=font, fill=255)
    return np.array(im)


def columns(mask):
    """Ink per column, from the first inked column to the last"""
    cols = np.where(mask.any(0))[0]
    return mask[:, cols.min():cols.max() + 1].sum(0).astype(float)


def squeeze(profile, bins=BINS):
    """Squeezes (or stretches) a row of numbers to `bins` numbers, each the average over its share (same as
    MixologyReader.squeeze)"""
    n = len(profile)
    total = np.concatenate([[0.0], np.cumsum(profile)])

    def upto(x):   # the sum of the profile from 0 to x, x in columns (may be fractional)
        i = min(int(x), n - 1)
        return total[i] + (x - i) * profile[i]

    edges = np.linspace(0, n, bins + 1)
    return np.array([(upto(edges[k + 1]) - upto(edges[k])) / (edges[k + 1] - edges[k]) for k in range(bins)])


def fit(profile, width, shape_profile, shape_width):
    """How well a line fits a name's shape (same as MixologyReader.fit): how alike the two ink rows go up and down
    (1 = exactly), less a penalty for a different width"""
    a = profile - profile.mean()
    b = shape_profile - shape_profile.mean()
    alike = float((a * b).sum() / np.sqrt((a * a).sum() * (b * b).sum()))
    return alike - WIDTH_WEIGHT * abs(width - shape_width) / shape_width


def main():
    request = urllib.request.Request(FONT, headers={'User-Agent': AGENT})
    font = ImageFont.truetype(io.BytesIO(urllib.request.urlopen(request).read()), 16)

    shapes = {}
    for name in NAMES:
        profile = columns(ink(name, font) > 127)
        shapes[name] = (len(profile), squeeze(profile))

    # The check: each name written 1.4, 2.1 and 3.2 times bigger with smoothing (like phones of different sizes),
    # its width measured 2% off either way, must still fit its own shape best by MARGIN
    worst = None
    for scale in (1.4, 2.1, 3.2):
        for name in NAMES:
            im = Image.fromarray(ink(name, font))
            big = np.array(im.resize((int(im.width * scale), int(im.height * scale)), Image.BILINEAR)) > 110
            profile = columns(big)
            for off in (0.98, 1.02):
                width = len(profile) / scale * off
                fits = sorted(((fit(squeeze(profile), width, p, w), n) for n, (w, p) in shapes.items()), reverse=True)
                margin = fits[0][0] - fits[1][0]
                if fits[0][1] != name or margin < MARGIN:
                    sys.exit(f'"{name}" at {scale}x reads as "{fits[0][1]}" (margin {margin:.2f}): the shapes '
                             f'are too alike. Try more BINS.')
                worst = margin if worst is None else min(worst, margin)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# Mastering Mixology potion names: name, width in game pixels, then ink per column in hundredths\n')
        f.write(f'# ({BINS} columns). Made by tools/mixology/build_name_shapes.py from RuneLite\'s runescape.ttf '
                f'(BSD 2-Clause, commit {COMMIT[:10]}).\n')
        for name in NAMES:
            width, profile = shapes[name]
            f.write('\t'.join([name, str(width)] + [str(int(round(v * 100))) for v in profile]) + '\n')
    print(f'Wrote {len(NAMES)} names to {os.path.relpath(OUT, ROOT)}; closest call in the check: margin {worst:.2f}')


if __name__ == '__main__':
    main()
