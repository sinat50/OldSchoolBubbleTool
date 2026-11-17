"""Idle detection: play a recording through the planned detector and check it against what really happened.
Research for the idle notifier (plan: .scratch/afk-alert/spec.md); nothing here goes into the app yet.

THE MAIN WAY: THE ZOOM GUIDE (the owner's idea, 11 November 2026; GUIDE = 1)

Before going AFK the player zooms until the character is as tall as a box the app shows (an upright box,
three quarters as wide as tall, reaching from a little below the screen's middle upwards; about 15% of the
screen's height, fine from about 10% to 22%). From then on the detector KNOWS how big the character is and
where it is (the camera always aims just above its feet, at the middle), so nothing has to be searched for.
Once per look (about one a second):

1. Watch one box at the middle, a bit bigger than the guide's (0.9 x the character's height wide, 1.25 x
   tall: it holds the character seen from the side or from above, with the swing of its tool). Compare it
   with how it looked about 1 second earlier, at full size if the character is small enough (up to about
   175 pixels on a 1080-tall screen), at half size if it's bigger. A pixel "changed" if its colour moved
   enough, and only SOLID change counts (a changed pixel whose neighbours changed too): standing still,
   the character's outline shimmers (the slow standing sway) but its body doesn't; any work moves the body.
   Because the box is the character's size at any zoom, the numbers mean the same everywhere: standing sway
   changes 0-4% of the box, stringing bows (only the hands move) 5-35%, chopping or mining more.
2. A thin camera check: every 8th pixel each way of the area around the box. If nearly a third of those
   changed at once, the camera turned, zoomed or followed you walking: you're holding the phone, so you're
   active, and the looks so far are forgotten (an alert that hasn't gone off has to wait again).
   The box stays where it is afterwards. A turn or a tilt doesn't change the character's size much (the
   detector is fine with a size 0.7 to 1.4 times the true one). A ZOOM does, and the detector can't measure
   it from a few pixels, so that's the app's job: after a camera move it should show the guide box again
   for a moment, so the player sees whether the character still fits.
3. A look is quiet when under GUIDE_QUIET % of the box changed. You're IDLE once at least half the looks
   over the last 8 seconds were quiet (people walking past make the other half busy).
4. One ALERT (the buzz) for each idle stretch: it goes off the first time you're idle, and can't go off
   again until the box was busy in three quarters of the looks over 8 seconds (you worked again). A camera
   move alone doesn't count. The XP counter isn't needed (the owner's rule: don't rely on XP); where it's
   read, an XP drop holds idle back for a few seconds and counts as having worked.

Our recordings weren't made with the guide, so run_all.py gives each one the character's true height, as the
guide would have set it (measured by eye, stretch by stretch where the zoom changes).

THE OLD WAY: SEARCHING FOR THE CHARACTER (GUIDE = 0, or no height given; kept to compare)

What the detector does, once per picture (about one look a second is what the app would do):

1. Compare the middle of the screen with how it looked about 1 second earlier (pictures shrunk to half size,
   as the app would, to save battery). A pixel "changed" if its colour moved enough.
2. Keep only SOLID change: a changed pixel counts only if its neighbours changed too. Standing still, your
   character's outline shimmers (the slow standing sway) but its body doesn't; any activity moves the body.
3. If most of the area changed at once, the camera turned, zoomed or followed you walking: you're holding the
   phone, so you're active. The character's size may have changed, so forget where it was and find it again.
4. Finding your character: for a few seconds, add up where solid change happens. The patch at (or nearest)
   the screen's middle is your character; a pet or a passer-by beside you is a different patch. Its box,
   a little enlarged, is what gets watched from then on. While it's busy, the detector also learns how much
   that box usually changes ("busy level").
   A far-away character (zoomed out, 40 pixels tall) is only 20 pixels in the shrunk picture, and step 2
   then throws away nearly all of its movement. So the screen's very middle is also read at full size (the
   "close-up", 320 x 320 pixels), and a small character is found and watched there instead.
5. Each look: how much of the box changed (solid change only). Quiet = under a quarter of the busy level
   (and under QUIET_MAX). You're IDLE once at least half the looks over the last 8 seconds were quiet (people
   walking past you make the other half busy) and no XP came in recently.

Changed on 11 November 2026 (tried first in the fable/ copy, which is kept for now), each from a wrong idle
call in the unedited mining video:
- "The same picture as before" (SKIP_SAME) meant a small AVERAGE change, which a tiny character mining in a
  still cave also gives: a third of its looks were thrown away. Now it means almost no pixel changed, and
  two such looks in a row count as a still screen.
- The close-up (above), and no box smaller than a character can be (SMALLEST): boxes of 8 x 10 shrunk pixels
  read 0% or 100% by chance.
- Once the character is found, the looks it was found from are judged again by its box (REJUDGE): the
  standard boxes had called a far-away, busy character quiet.

   The box must cover the whole character (late on 11 November 2026). It used to land on a small piece:
   - of a big character standing still, whose swaying outline is thin lines and specks at full size, and one
     speck near the middle was taken for a tiny far-away character (a 30 x 50 box on a 300-pixel body);
   - of a far-away character at work, whose movement comes in pieces a few pixels apart (head and pickaxe,
     cape), and only the piece nearest the middle was boxed.
   So in the close-up, pieces close together are joined into one patch first (CLOSE_JOIN), a patch must be
   big enough (CLOSE_LEAST) and filled in, not a few lines (CLOSE_FILL), and anywhere a thin line is never a
   character (THINNEST). A patch shorter than any character in the shrunk picture isn't watched either. When
   nothing passes, the detector keeps watching the standard boxes at the middle and keeps looking.

6. One ALERT for each idle stretch. "Idle" in step 5 is judged afresh at every look, and a character standing
   still zoomed in sways enough to read busy about half the time, so the answer flips idle, not idle, idle
   every second or two (4 times in 8 s in a YouTube clip). The alert (the buzz) is therefore separate: it
   goes off the first time you're idle, and can't go off again until you've clearly been active (see REARM).
   Turning or zooming the camera is not "active" for this: it shows you're holding the phone, so it puts off
   an alert that hasn't gone off yet (step 3), but after an alert it doesn't earn you a second buzz for the
   same stretch of standing. Only your character working again does.
   None of this needs the XP counter (the owner's rule, 11 November 2026: don't rely on XP, since some skills
   go a long time between drops and players hide the counter; where it shows, an XP drop is a bonus sign of
   work). Working again is seen two ways: your character's own box busy in three quarters of the looks over
   8 seconds, or, while the character hasn't been found (fletching and cooking move only the hands, and short
   of that the standard middle boxes are all there is), the middle boxes STRONGLY busy in most of the looks
   over 8 seconds (REARM_MIDDLE).

Truths (what really happened in each recording): see run_all.py.

Run it:  python tools/idle-detection/detect_idle.py <recording folder> --height 150 --truth "active:0-87,idle:87-110,..."
--height is the character's height in pixels on a 1080-tall screen, as the zoom guide would have set it; or
"0:150,28:-,39:200" for a zoom that changes (from 0 s 150, from 28 s not covered, from 39 s 200). Without it
the old search is used. --truth lists what really happened (seconds from the start); times not listed
aren't scored.
Writes <folder name>_detector.png (timeline), _boxes.png (where it found the character) and _idle_moments.jpg
(the screen at each alert) next to the recording, or into --out <folder>.
Needs Python 3 with numpy, scipy, Pillow and matplotlib.
"""

import argparse
import os
import sys
from collections import deque

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

# Measured on the owner's phone (2340 x 1080 sideways). The camera aims just above your feet, at the game
# view's middle, and the game scales its picture by the view's HEIGHT, so other screens are handled by
# scaling these by (game height / 1080) around the game view's middle (see set_screen).
MIDDLE = (1170, 540)
SEARCH = (570, 90, 1770, 990)   # left, top, right, bottom: the area searched for your character
SHRINK = 2                      # pictures are shrunk this many times each way before comparing
CLOSE = (1010, 380, 1330, 700)  # the screen's middle, read at full size: a far-away character is too small in
                                # the shrunk picture (a 40-pixel character becomes 20, and loses its solid change)
CLOSE_UP = 1.0                  # 1 = use it
CLOSE_TALLEST = 160             # a patch taller than this (pixels at 1080 tall) is judged in the shrunk picture
                                # (was 100, when a patch was often only a piece of the character; whole, with
                                # its pickaxe and the box's margin, a far-away character is 85 to 150 tall)
SMALLEST = (30, 50)             # width, height (pixels at 1080 tall) of the smallest box worth watching: about
                                # a character fully zoomed out
CLOSE_LEAST = 150               # the smallest moving patch (pixels at 1080 tall) taken for a character in the
                                # close-up. A far-away character at work gives 1,000 or more; a big character
                                # standing still gives specks of 12 to 110 where its outline sways, and such a
                                # speck used to be taken for a tiny character (a 30 x 50 box on a big body)
CLOSE_JOIN = 16                 # pieces of movement this close together (pixels at 1080 tall) are one patch
CLOSE_FILL = 0.1                # ...and this share of the patch's box must have moved
THINNEST = 0.2                  # a moving patch narrower than this share of its length is a line, not a character
REJUDGE = 1.0                   # 1 = once the character is found, judge the looks it was found from by its box
XP_BOX = (1660, 40, 1790, 80)   # the XP counter's numbers (owner's phone only; --no-xp for other videos)
USE_XP = True
BRIGHTEN = 1.0          # 1 = undo screen darkening: the owner's phone darkens the whole PICTURE to about a
                        # tenth after a while without touches (combat_1, 2:12-7:42), so changes shrink too
SKIP_SAME = 1.0         # 1 = ignore a look identical to the one before (repeated video frames, the game not
                        # redrawing): it says nothing about whether you moved
SAME_SHARE = 0.0001     # under this share of the search area changed = identical to the look before
NORMAL_BRIGHTNESS = 70  # average brightness (0-255) of an ordinary game picture

PIXEL_CHANGED = 40      # red+green+blue difference that counts as a change
COMPARE_WITH = 1.0      # seconds between the two pictures compared
COMPARE_ALSO = 0.0      # ...and also with the picture this many seconds back (0 = don't). Looking once a
                        # second, an animation that repeats about every second (a pickaxe swing is 1.1 s)
                        # looks almost the same each time, like a wagon wheel in a film; 2 s back it doesn't
CAMERA_MOVED = 0.30     # this share of the area around the box changed at once = camera turned or zoomed
LEARN_FOR = 4.0         # seconds spent finding the character after starting or after the camera moves
HOT = 0.5               # a pixel belongs to a moving patch if it changed in this share of the looks
NEAR_MIDDLE = 30        # pixels (at 1080 tall): how far from the screen's middle the character's patch may be
WIDEST = 0.8            # a character box is at most this many times as wide as it is tall (others beside
                        # you can join your patch; a person is taller than wide, tool swing included)
QUIET_SHARE = 0.25      # a look is quiet when the box changed less than this share of the busy level...
QUIET_MAX = 3.0         # ...and less than this many %
WINDOW = 8.0            # seconds of looks considered for idle
QUIET_MOST = 0.5        # idle when this share of the looks in WINDOW were quiet
REARM = 0.75            # after an alert, the next one waits until you've clearly been active again: this
                        # share of the looks in WINDOW were busy in your character's own box (or see
                        # REARM_MIDDLE while it hasn't been found; or XP came in, where the counter shows). Standing sway is busy
                        # about half the time and real work nearly all the time; one busy look (someone
                        # walking past) isn't enough. A camera move alone doesn't count: you'd get a second
                        # buzz 8 s after nudging the camera while still standing there
REARM_STRONG = 20.0     # % of a standard middle box changed = strongly busy. Measured in the YouTube clips:
                        # standing sway reaches 14% zoomed right in (usually under 10%), stringing bows gives
                        # 30 to 85% in most looks
REARM_AROUND = 0.10     # ...only counted while less than this share of the picture around changed
REARM_MIDDLE = 0.6      # ...and this share of the looks in WINDOW strongly busy = working again, when the
                        # character hasn't been found. Stringing bows has a slack look in every three or four,
                        # so three quarters would be too strict; sway never gets one strong look
GUIDE = 1.0             # 1 = the zoom guide is the main way: when the character's height is given, watch one
                        # box that size at the middle and search for nothing. 0 = always search (the old way)
GUIDE_TARGET = 0.15     # the guide box's height as a share of the screen's height (fine from 0.10 to 0.22)
GUIDE_SCALE = 1.0       # for tests: every height given is multiplied by this (a player who zoomed too far
                        # in or out; 0.7 and 1.4 were tried)
GUIDE_QUIET = 3.0       # a look is quiet when under this % of the character's box changed (standing sway
                        # is 0-4%, mostly under 2.5; the slackest work measured, stringing bows, 5-35%)
GUIDE_FULL = 175        # up to this height (pixels at 1080 tall) the box is compared at full size, above
                        # it at half size: about 30,000 pixels a look at most either way
THIN = 8                # the camera check reads every THIN-th pixel each way of the half-size picture
GUESS = 1.0             # 1 = until the character is found, watch a standard box at the middle (quiet there
                        # counts too: if you're already idle after a camera move, nothing moves to be found)
XP_RECENT = 6.0         # XP within this many seconds = active
XP_GAIN_PIXELS = 60
NUMBER_WHITE = 600


def set_screen(width, height, area=None):
    """Fit the regions to a picture of this size. area = (left, top, right, bottom) of the game view inside
    it (black bars left out); the whole picture if None."""
    global MIDDLE, SEARCH, SHRINK, CLOSE
    l, t, r, b = area or (0, 0, width, height)
    k = (b - t) / 1080.0
    cx, cy = (l + r) / 2.0, (t + b) / 2.0
    MIDDLE = (int(cx), int(cy))
    SEARCH = (int(max(l, cx - 600 * k)), int(max(t, cy - 450 * k)),
              int(min(r, cx + 600 * k)), int(min(b, cy + 450 * k)))
    SHRINK = 2 if height >= 900 else 1
    CLOSE = (int(cx - 160 * k), int(cy - 160 * k), int(cx + 160 * k), int(cy + 160 * k))


def video_size(path):
    import subprocess
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                          "stream=width,height", "-of", "csv=p=0", path], capture_output=True, text=True).stdout
    w, h = out.strip().split(chr(10))[0].split(",")[:2]
    return int(w), int(h)


def video_frames(path, fps):
    """Yield (seconds, name, picture) from a video, fps pictures a second, without saving them."""
    import subprocess
    w, h = video_size(path)
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-i", path, "-vf", f"fps={fps}", "-f", "rawvideo",
                          "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    n = 0
    while True:
        buf = p.stdout.read(w * h * 3)
        if len(buf) < w * h * 3:
            break
        yield n / fps, f"{n / fps:.1f}s", Image.frombuffer("RGB", (w, h), buf)
        n += 1
    p.wait()


def game_area(pictures):
    """The game view inside the picture: columns and rows that are near-black in every picture are bars."""
    most = None
    for im in pictures:
        a = np.asarray(im.convert("L"))
        most = a if most is None else np.maximum(most, a)
    cols = np.nonzero(most.max(axis=0) > 20)[0]
    rows = np.nonzero(most.max(axis=1) > 20)[0]
    return int(cols[0]), int(rows[0]), int(cols[-1]) + 1, int(rows[-1]) + 1


def folder_frames(folder, fps=None):
    """Pictures from a folder; with fps, at most that many a second (as the app would look)."""
    times, names = load_times(folder)
    last = -1e9
    for t, n in zip(times, names):
        if fps and t - last < 1.0 / fps - 0.05:
            continue
        last = t
        yield t, n, Image.open(os.path.join(folder, n)).convert("RGB")


def load_times(folder):
    names = sorted(n for n in os.listdir(folder) if n.startswith("f_") and n.endswith(".png"))
    if not names:
        sys.exit(f"No f_<milliseconds>.png pictures in {folder}")
    ms = [int(n[2:-4]) for n in names]
    return [(m - ms[0]) / 1000.0 for m in ms], names


def read(im):
    """One look: the search area shrunk, the XP counter's number pixels, and the screen's middle at full size
    (darkened pictures brightened back)."""
    if isinstance(im, tuple):   # already read (the test bench's cache)
        return im
    l, t, r, b = SEARCH
    gain = 1.0
    if BRIGHTEN:
        small = np.asarray(im.resize((im.width // 16, im.height // 16)), dtype=np.float32)
        gain = min(12.0, max(1.0, NORMAL_BRIGHTNESS / max(1.0, small.mean())))
    area = im.crop(SEARCH).resize(((r - l) // SHRINK, (b - t) // SHRINK), Image.BOX)
    xp = None
    if USE_XP:
        numbers = np.asarray(im.crop(XP_BOX), dtype=np.float32)
        if gain > 1.5:
            numbers = numbers * gain
        xp = numbers.sum(axis=2) > NUMBER_WHITE
    area = np.asarray(area, dtype=np.float32)
    close = np.asarray(im.crop(CLOSE), dtype=np.float32)
    if gain > 1.5:  # clearly darkened: brighten it back (ordinary dark scenes stay as they are)
        area = area * gain
        close = close * gain
    return area.astype(np.int16), xp, close.astype(np.int16)


def solid(changed):
    """Changed pixels whose 3 x 3 neighbourhood all changed (drops outlines a pixel or two wide)."""
    return ndimage.binary_erosion(changed, structure=np.ones((3, 3)))


def find_character(heat, looks, mx, my, near, inside=False, least=12, join=0, fill=0.0):
    """The moving patch at or nearest the middle (mx, my), as a box in heat's pixels, or None. near: how far
    from the middle the patch may be. inside: the patch must not touch heat's edge (it would be only a part
    of something bigger). least: patches of fewer pixels than this are specks, not looked at. join: pieces
    this many pixels apart or closer count as one patch (0 = only pieces that touch). fill: the share of the
    patch's box that must have moved (a body at work fills its box; a swaying outline is a few thin lines)."""
    hot = heat >= max(1, HOT * looks)
    if join:
        # Each piece is grown by half the joining distance, so pieces that close run together; the patch is
        # then measured by its own pixels, not the grown ones.
        grow = max(1, int(join) // 2)
        labels, n = ndimage.label(ndimage.binary_dilation(hot, structure=np.ones((2 * grow + 1, 2 * grow + 1))))
    else:
        hot = ndimage.binary_closing(hot, structure=np.ones((3, 3)))
        labels, n = ndimage.label(hot)
    if n == 0:
        return None
    best = None
    for i, sl in enumerate(ndimage.find_objects(labels), start=1):
        own = hot[sl] & (labels[sl] == i)        # this patch's own pixels
        size = int(own.sum())
        if size < least:
            continue
        rows, cols = np.nonzero(own.any(axis=1))[0], np.nonzero(own.any(axis=0))[0]
        ys = slice(sl[0].start + int(rows[0]), sl[0].start + int(rows[-1]) + 1)
        xs = slice(sl[1].start + int(cols[0]), sl[1].start + int(cols[-1]) + 1)
        if size < fill * (ys.stop - ys.start) * (xs.stop - xs.start):
            continue
        # A line (under a fifth as wide as it's long) is the edge of a body swaying as it stands, not a body
        if min(ys.stop - ys.start, xs.stop - xs.start) < THINNEST * max(ys.stop - ys.start, xs.stop - xs.start):
            continue
        sl = (ys, xs)
        # distance from the middle to the patch's box (0 if the middle is inside it)
        dx = max(xs.start - mx, 0, mx - (xs.stop - 1))
        dy = max(ys.start - my, 0, my - (ys.stop - 1))
        d = (dx * dx + dy * dy) ** 0.5
        if best is None or d < best[0] or (d == best[0] and size > best[1]):
            best = (d, size, sl)
    # Your character always covers the screen's middle (the camera aims just above your feet), so the patch
    # must reach it. Something moving beside you (a pet, a fire) while you stand still is not you.
    if best is None or best[0] > near:
        return None
    ys, xs = best[2]
    h, w = heat.shape
    if inside and (xs.start == 0 or ys.start == 0 or xs.stop == w or ys.stop == h):
        return None
    # Too wide: someone beside you joined the patch. Keep a slice around the middle, as wide as a person.
    most = max(4, int(WIDEST * (ys.stop - ys.start)))
    if xs.stop - xs.start > most:
        left = int(min(max(xs.start, mx - most // 2), xs.stop - most))
        xs = slice(left, left + most)
    pad_y = max(2, (ys.stop - ys.start) // 6)
    pad_x = max(2, (xs.stop - xs.start) // 6)
    return (max(0, xs.start - pad_x), max(0, ys.start - pad_y), min(w, xs.stop + pad_x), min(h, ys.stop + pad_y))


def guess_box(shape, size=1.0):
    """A standard box where a character stands at the middle zoom (about 0.25 x 0.12 of the view's height,
    feet just below the middle), in shrunk pixels of the search area. size scales it about the middle."""
    k = (SEARCH[3] - SEARCH[1]) / 900.0          # the search area is 900 px tall at 1080
    mx = (MIDDLE[0] - SEARCH[0]) / SHRINK
    my = (MIDDLE[1] - SEARCH[1]) / SHRINK
    k *= size
    half_w = 65 * k / SHRINK
    h, w = shape
    return (int(max(0, mx - half_w)), int(max(0, my - 210 * k / SHRINK)),
            int(min(w, mx + half_w)), int(min(h, my + 60 * k / SHRINK)))


GUESS_SIZES = (0.3, 1.0, 2.0)   # nested boxes: a far-away character, the middle zoom, a close-up


def run(frames):
    """frames: (seconds, name, picture) one after another. Returns the per-look results, where the character
    was found (with a picture of it) and small pictures of the moments the detector first called you idle."""
    history = deque()            # (time, shrunk picture, close-up) for the last ~COMPARE_WITH seconds
    box = None                   # where the character is, once found: shrunk pixels of the search area, or...
    box_close = False            # ...full-size pixels of the close-up, for a small (far-away) character
    heat = None                  # learning: how often each pixel had solid change
    heat_close = None            # the same in the close-up
    learned = []                 # (time, solid change, the same in the close-up) of the looks learnt from
    learn_start = 0.0
    learn_looks = 0
    busy = None                  # how much the box changed while learning (the character is busy then)
    looks = deque()              # (time, quiet?) within WINDOW
    last_xp = -1e9
    prev_xp = None
    out = []                     # per picture: time, reading, state, event
    boxes_found = []             # (time, box in whole-picture pixels, picture of the search area for the
                                 # first 8) whenever the character was found
    idle_moments = []            # the alerts: (time, small picture) each time the buzz would go off
    was_idle = False
    armed = True                 # may the alert go off? False from an alert until you've clearly been active
    strong = deque()             # (time, strongly busy?) for the looks in WINDOW judged by the middle boxes
    skipped_last = False         # the look before this one was left out as identical
    im = None

    def on_screen(bx, close):
        """A box in whole-picture pixels."""
        left, top = (CLOSE if close else SEARCH)[:2]
        k = 1 if close else SHRINK
        return (left + bx[0] * k, top + bx[1] * k, left + bx[2] * k, top + bx[3] * k)

    def snapshot(im, t, bx=None):
        """A small picture of the moment, with the watched box drawn in."""
        if isinstance(im, tuple):
            return None
        small = im.resize((im.width * 300 // im.height, 300))
        d = ImageDraw.Draw(small)
        if bx:
            sx = small.width / im.width
            d.rectangle([v * sx for v in bx], outline=(255, 0, 255), width=2)
        d.rectangle([0, 0, 70, 14], fill=(0, 0, 0))
        d.text((3, 1), f"{int(t) // 60}:{int(t) % 60:02d} idle", fill=(255, 255, 0))
        return small

    for i, (t, n, im) in enumerate(frames):
        pic, xp, close = read(im)
        if i % 100 == 0 and not isinstance(im, tuple):
            print(f"  {t:.0f} s", end="\r")
        if xp is not None and prev_xp is not None and int((xp != prev_xp).sum()) >= XP_GAIN_PIXELS:
            last_xp = t
            armed = True         # XP came in: you were working
        prev_xp = xp
        # A look identical to the last one (a repeated video frame, the game not redrawing) says nothing, so
        # it's left out. "Identical" means almost no pixel changed anywhere, not a small average: a far-away
        # character mining in a still cave is a few hundred pixels in a quarter of a million. Two identical
        # looks in a row do say something: the screen really is still.
        same = SKIP_SAME and history and (np.abs(pic - history[-1][1]).sum(axis=2) > PIXEL_CHANGED).mean() < SAME_SHARE
        if same and not skipped_last:
            skipped_last = True
            out.append((t, np.nan, "idle" if was_idle else "active", "same")); continue
        skipped_last = False
        history.append((t, pic, close))
        keep = max(COMPARE_WITH, COMPARE_ALSO)
        while len(history) > 1 and history[1][0] <= t - keep:
            history.popleft()

        def back(seconds):
            """The latest look at least (about) this many seconds old, or None."""
            found = None
            for h in history:
                if h[0] <= t - seconds * 0.8:
                    found = h
            return found

        older = back(COMPARE_WITH)
        if older is None:
            out.append((t, np.nan, "active", "")); continue

        changed = np.abs(pic - older[1]).sum(axis=2) > PIXEL_CHANGED
        changed_close = np.abs(close - older[2]).sum(axis=2) > PIXEL_CHANGED
        # the character reading also counts changes since 2 s back; the camera check below uses 1 s only
        both, both_close = changed, changed_close
        older2 = back(COMPARE_ALSO) if COMPARE_ALSO else None
        if older2 is not None and older2 is not older:
            both = changed | (np.abs(pic - older2[1]).sum(axis=2) > PIXEL_CHANGED)
            both_close = changed_close | (np.abs(close - older2[2]).sum(axis=2) > PIXEL_CHANGED)
        firm = solid(both)
        firm_close = solid(both_close)

        # 3. Did the camera move? Look at the area outside the character's box.
        outside = np.ones(changed.shape, bool)
        if box:
            x0, y0, x1, y1 = box
            if box_close:   # the same box in the search area's shrunk pixels
                sx0, sy0, sx1, sy1 = on_screen(box, True)
                x0, y0 = (sx0 - SEARCH[0]) // SHRINK, (sy0 - SEARCH[1]) // SHRINK
                x1, y1 = (sx1 - SEARCH[0]) // SHRINK + 1, (sy1 - SEARCH[1]) // SHRINK + 1
            outside[y0:y1, x0:x1] = False
        around = changed[outside].mean()
        if around > CAMERA_MOVED:
            box, heat, heat_close, learn_looks, busy = None, None, None, 0, None
            looks.clear()         # you're here: an alert that hasn't gone off yet has to wait again
            out.append((t, np.nan, "active", "camera")); continue

        # 4. Finding the character
        if box is None:
            if GUESS:
                # the busiest of the nested boxes: quiet only if your character is still at any zoom
                g = 0.0
                for size in GUESS_SIZES:
                    gx0, gy0, gx1, gy1 = guess_box(changed.shape, size)
                    g = max(g, 100.0 * firm[gy0:gy1, gx0:gx1].mean())
                looks.append((t, g < QUIET_MAX))
                while looks and looks[0][0] < t - WINDOW:
                    looks.popleft()
                # strongly busy at the middle while the rest of the picture is nearly still: a slow camera
                # drift changes the middle boxes too, and that isn't work
                strong.append((t, g >= REARM_STRONG and around < REARM_AROUND))
                while strong and strong[0][0] < t - WINDOW:
                    strong.popleft()
            if heat is None:
                heat, learn_start, learn_looks = np.zeros(changed.shape, np.int32), t, 0
                heat_close = np.zeros(changed_close.shape, np.int32)
                learned = []
            heat += firm
            heat_close += firm_close
            learned.append((t, firm, firm_close))
            learn_looks += 1
            if t - learn_start >= LEARN_FOR:
                # First in the close-up: a far-away character is only a few shrunk pixels, too few to tell
                # solid change from outlines. A patch reaching the close-up's edge is part of something
                # bigger (a character zoomed in on), which the shrunk search area shows whole.
                k = (SEARCH[3] - SEARCH[1]) / 900.0
                if CLOSE_UP:
                    box = find_character(heat_close, learn_looks, MIDDLE[0] - CLOSE[0], MIDDLE[1] - CLOSE[1],
                                         NEAR_MIDDLE * k, inside=True, least=max(12, CLOSE_LEAST * k * k),
                                         join=CLOSE_JOIN * k, fill=CLOSE_FILL)
                    # ...and a patch that's big enough to judge in the shrunk picture is judged there: at
                    # full size, outlines are thick enough to count as solid change
                    if box and box[3] - box[1] > CLOSE_TALLEST * k:
                        box = None
                box_close = box is not None
                if box is None:
                    box = find_character(heat, learn_looks, (MIDDLE[0] - SEARCH[0]) // SHRINK,
                                         (MIDDLE[1] - SEARCH[1]) // SHRINK, NEAR_MIDDLE * k / SHRINK)
                    # A patch shorter than any character, in the shrunk picture, is a speck (a few shrunk
                    # pixels read 0% or 100% by chance): better to keep looking than to watch it
                    if box and (box[3] - box[1]) * SHRINK < SMALLEST[1] * k:
                        box = None
                if box and box_close:
                    # no character is smaller than this (fully zoomed out, seen from above): a smaller patch
                    # is only a part of one, so widen the box around it
                    x0, y0, x1, y1 = box
                    gw, gh = max(0, SMALLEST[0] * k - (x1 - x0)) / 2, max(0, SMALLEST[1] * k - (y1 - y0)) / 2
                    hh, ww = heat_close.shape
                    box = (int(max(0, x0 - gw)), int(max(0, y0 - gh)), int(min(ww, x1 + gw)), int(min(hh, y1 + gh)))
                if box:
                    x0, y0, x1, y1 = box
                    # the busy level: how much the box changed while learning
                    busy = max(1.0, float(np.mean([100.0 * (fc if box_close else f)[y0:y1, x0:x1].mean()
                                                   for _, f, fc in learned])))
                    if REJUDGE:
                        # Now the box is known, judge the looks it was found from again, by the box. (They
                        # were judged by the standard boxes, which a far-away character hardly shows in.)
                        again = {lt: 100.0 * (fc if box_close else f)[y0:y1, x0:x1].mean() < min(QUIET_SHARE * busy, QUIET_MAX)
                                 for lt, f, fc in learned}
                        looks = deque((lt, again.get(lt, q)) for lt, q in looks)
                    boxes_found.append((t, on_screen(box, box_close),
                                        None if isinstance(im, tuple) or len(boxes_found) >= 8 else im.crop(SEARCH)))
                heat, heat_close, learn_looks, learned = None, None, 0, []
            idle = False
            if GUESS and looks:
                enough = looks[-1][0] - looks[0][0] >= WINDOW * 0.8
                share_quiet = sum(q for _, q in looks) / len(looks)
                idle = enough and share_quiet >= QUIET_MOST and t - last_xp >= XP_RECENT
            # Working again, seen in the middle boxes: they must be strongly busy most of the time, which a
            # standing character's sway never is and a pet or passer-by rarely keeps up for 8 seconds.
            # (Looks during a camera move aren't counted at all, so turning the camera neither helps nor
            # wipes out the work seen before it.)
            if strong and strong[-1][0] - strong[0][0] >= WINDOW * 0.8 \
                    and sum(b for _, b in strong) >= REARM_MIDDLE * len(strong):
                armed = True
            if idle and armed:
                idle_moments.append((t, snapshot(im, t)))
                armed = False
            was_idle = idle
            out.append((t, np.nan, "idle" if idle else "active", "learning")); continue

        # 5. Watch the box
        x0, y0, x1, y1 = box
        reading = 100.0 * (firm_close if box_close else firm)[y0:y1, x0:x1].mean()
        quiet = reading < min(QUIET_SHARE * busy, QUIET_MAX)
        looks.append((t, quiet))
        while looks and looks[0][0] < t - WINDOW:
            looks.popleft()
        enough = looks[-1][0] - looks[0][0] >= WINDOW * 0.8
        share_quiet = sum(q for _, q in looks) / len(looks)
        idle = enough and share_quiet >= QUIET_MOST and t - last_xp >= XP_RECENT
        if enough and 1 - share_quiet >= REARM:
            armed = True             # busy nearly all of the last few seconds: working again
        out.append((t, reading, "idle" if idle else "active", "close" if box_close else ""))
        if idle and armed:
            idle_moments.append((t, snapshot(im, t, on_screen(box, box_close))))
            armed = False
        was_idle = idle
    if im is not None and not isinstance(im, tuple):
        print()
    return out, boxes_found, idle_moments


def guide_height(guide, t):
    """The character's height (pixels at 1080 tall) the zoom guide would have set at time t, or None when
    the guide doesn't cover that moment (a menu over the game, a stopwatch shot in someone's video).
    guide: one number, or a list of (from this second on, height or None)."""
    if isinstance(guide, (int, float)):
        return guide * GUIDE_SCALE
    height = None
    for start, h in guide:
        if t >= start:
            height = h
    return None if height is None else height * GUIDE_SCALE


def guide_box(height):
    """The box watched for a character this tall (pixels at 1080 tall): (box, close). close = True: the box
    is in the close-up's full-size pixels, False: in the search area's half-size pixels. It reaches from 0.9
    x the height above the screen's middle to 0.35 x below (the camera aims just above the feet) and is 0.9 x
    the height wide."""
    k = (SEARCH[3] - SEARCH[1]) / 900.0
    tall = height * k
    x0, x1 = MIDDLE[0] - 0.45 * tall, MIDDLE[0] + 0.45 * tall
    y0, y1 = MIDDLE[1] - 0.9 * tall, MIDDLE[1] + 0.35 * tall
    close = height <= GUIDE_FULL and x0 >= CLOSE[0] and y0 >= CLOSE[1] and x1 <= CLOSE[2] and y1 <= CLOSE[3]
    left, top, right, bottom = CLOSE if close else SEARCH
    unit = 1 if close else SHRINK
    ww, hh = (right - left) // unit, (bottom - top) // unit
    return (int(max(0, (x0 - left) / unit)), int(max(0, (y0 - top) / unit)),
            int(min(ww, (x1 - left) / unit)), int(min(hh, (y1 - top) / unit))), close


def run_guide(frames, guide):
    """The zoom guide's way (see the top of this file). frames: (seconds, name, picture) one after another;
    guide: the character's height, see guide_height. Returns the same three things as run()."""
    history = deque()            # (time, half-size search area, full-size close-up) for the last second or so
    looks = deque()              # (time, quiet?) within WINDOW, since the last camera move
    work = deque()               # (time, quiet?) within WINDOW, camera moves or not: for "worked again"
    last_xp, prev_xp = -1e9, None
    out, boxes_found, idle_moments = [], [], []
    armed = True                 # may the alert go off? False from an alert until you've worked again
    was_idle = False
    skipped_last = False
    height = None                # the height the box was made for
    box, close_up = None, False
    im = None

    def on_screen(bx, close):
        left, top = (CLOSE if close else SEARCH)[:2]
        unit = 1 if close else SHRINK
        return (left + bx[0] * unit, top + bx[1] * unit, left + bx[2] * unit, top + bx[3] * unit)

    def part(look):
        """The box's pixels from one look, with a pixel of margin for the solid-change test."""
        x0, y0, x1, y1 = box
        return (look[2] if close_up else look[1])[max(0, y0 - 1):y1 + 1, max(0, x0 - 1):x1 + 1]

    def around(look):
        """The thin sample for the camera check: every THIN-th pixel of the half-size search area."""
        return look[1][::THIN, ::THIN]

    for i, (t, n, im) in enumerate(frames):
        pic, xp, close = read(im)
        if i % 100 == 0 and not isinstance(im, tuple):
            print(f"  {t:.0f} s", end="\r")
        if xp is not None and prev_xp is not None and int((xp != prev_xp).sum()) >= XP_GAIN_PIXELS:
            last_xp = t
            armed = True         # XP came in: you were working (a bonus, where the counter shows)
        prev_xp = xp
        now = guide_height(guide, t)
        if now is None:
            # the guide doesn't cover this moment: nothing is watched, and everything starts afresh after it
            history.clear(); looks.clear(); work.clear()
            height, box, was_idle = None, None, False
            out.append((t, np.nan, "active", "off")); continue
        if now != height:
            # the guide was set (again): a new box, and the looks so far were of another one
            height = now
            box, close_up = guide_box(height)
            history.clear(); looks.clear()
            if len(boxes_found) < 40:
                boxes_found.append((t, on_screen(box, close_up),
                                    None if isinstance(im, tuple) or len(boxes_found) >= 8 else im.crop(SEARCH)))
        look = (t, pic, close)
        # A look identical to the last one (a repeated video frame, the game not redrawing) says nothing, so
        # it's left out; two in a row do say something: the screen really is still.
        if SKIP_SAME and history:
            same = not (np.abs(around(look) - around(history[-1])).sum(axis=2) > PIXEL_CHANGED).any() \
                and int((np.abs(part(look) - part(history[-1])).sum(axis=2) > PIXEL_CHANGED).sum()) <= 2
            if same and not skipped_last:
                skipped_last = True
                out.append((t, np.nan, "idle" if was_idle else "active", "same")); continue
        skipped_last = False
        history.append(look)
        while len(history) > 1 and history[1][0] <= t - COMPARE_WITH:
            history.popleft()
        older = None                 # the latest look at least (about) COMPARE_WITH seconds old
        for h in history:
            if h[0] <= t - COMPARE_WITH * 0.8:
                older = h
        if older is None:
            out.append((t, np.nan, "active", "")); continue

        # 2. The thin camera check, on the area around the box
        thin = np.abs(around(look) - around(older)).sum(axis=2) > PIXEL_CHANGED
        outside = np.ones(thin.shape, bool)
        sx0, sy0, sx1, sy1 = on_screen(box, close_up)
        outside[int((sy0 - SEARCH[1]) / SHRINK / THIN):int((sy1 - SEARCH[1]) / SHRINK / THIN) + 1,
                int((sx0 - SEARCH[0]) / SHRINK / THIN):int((sx1 - SEARCH[0]) / SHRINK / THIN) + 1] = False
        if thin[outside].mean() > CAMERA_MOVED:
            looks.clear()         # you're here: an alert that hasn't gone off yet has to wait again
            out.append((t, np.nan, "active", "camera")); continue

        # 1. and 3. How much of the character's box changed (solid change only)
        # (Also comparing with 2 seconds back was tried, in case an animation repeating every 1.2 s hid from
        # a 1-second comparison: it lifted standing sway as much as it lifted zoomed-in harpoon fishing.)
        moved = np.abs(part(look) - part(older)).sum(axis=2) > PIXEL_CHANGED
        firm = solid(moved)[1:-1, 1:-1]
        reading = 100.0 * firm.mean()
        looks.append((t, reading < GUIDE_QUIET))
        work.append((t, reading < GUIDE_QUIET))
        for kept in (looks, work):
            while kept[0][0] < t - WINDOW:
                kept.popleft()
        enough = looks[-1][0] - looks[0][0] >= WINDOW * 0.8
        share_quiet = sum(q for _, q in looks) / len(looks)
        idle = enough and share_quiet >= QUIET_MOST and t - last_xp >= XP_RECENT

        # 4. One alert for each idle stretch. "Worked again" = the box busy in three quarters of the looks
        # over the last 8 seconds. Looks during a camera move aren't counted, but they don't wipe out the
        # work seen before them either (a player turning the camera every few seconds while fishing is
        # still fishing), where "idle" above starts afresh after every camera move.
        if work[-1][0] - work[0][0] >= WINDOW * 0.8 and len(work) >= 5                 and sum(not q for _, q in work) >= REARM * len(work):
            armed = True
        out.append((t, reading, "idle" if idle else "active", "close" if close_up else ""))
        if idle and armed:
            small = None
            if not isinstance(im, tuple):
                small = im.resize((im.width * 300 // im.height, 300))
                d = ImageDraw.Draw(small)
                d.rectangle([v * small.width / im.width for v in on_screen(box, close_up)], outline=(255, 0, 255), width=2)
                d.rectangle([0, 0, 70, 14], fill=(0, 0, 0))
                d.text((3, 1), f"{int(t) // 60}:{int(t) % 60:02d} idle", fill=(255, 255, 0))
            idle_moments.append((t, small))
            armed = False
        was_idle = idle
    if im is not None and not isinstance(im, tuple):
        print()
    return out, boxes_found, idle_moments


def parse_guide(text):
    """--height's text as a guide: "150", or "0:150,28:-,39:200" (from 28 s not covered)."""
    if not text:
        return None
    if ":" not in text:
        return float(text)
    pairs = []
    for piece in text.split(","):
        start, h = piece.split(":")
        pairs.append((float(start), None if h.strip() in ("-", "") else float(h)))
    return pairs


def jittered(frames, seed=1):
    """Thin pictures to about one a second at slightly random gaps (0.6-1.4 s), as the app would look:
    an animation that repeats at a steady rate then can't keep landing on the same moment."""
    import random
    rnd = random.Random(seed)
    next_at = 0.0
    for t, n, im in frames:
        if t + 1e-6 >= next_at:
            next_at = t + rnd.uniform(0.6, 1.4)
            yield t, n, im


def detect(path, fps=2.0, use_xp=True, jitter=False, guide=None):
    """Run the detector over a recording (folder or video). Returns the per-look results and the times of the
    alerts. jitter: look about once a second at random gaps instead of fps (reads the recording at 5 a second
    and thins it). guide: the character's height as the zoom guide would have set it (see guide_height);
    with it (and GUIDE on) the zoom guide's way is used, without it the old search."""
    global USE_XP
    USE_XP = use_xp
    rate = 5.0 if jitter else fps
    if os.path.isdir(path):
        set_screen(2340, 1080)          # the owner's phone
        frames = folder_frames(path, None if jitter else fps)
    else:
        w, h = video_size(path)
        set_screen(w, h, game_area(im for _, _, im in video_frames(path, 0.2)))
        frames = video_frames(path, rate)
    if jitter:
        frames = jittered(frames)
    out, _, alerts = run_guide(frames, guide) if GUIDE and guide is not None else run(frames)
    return out, [t for t, _ in alerts]


def parse_truth(s):
    spans = []
    for part in (s or "").split(","):
        if not part.strip():
            continue
        what, span = part.split(":")
        a, b = span.split("-")
        spans.append((what.strip(), float(a), float(b)))
    return spans


def truth_at(spans, t):
    for what, a, b in spans:
        if a <= t < b:
            return what
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", help="a folder of f_<ms>.png pictures, or a video file")
    ap.add_argument("--fps", type=float, default=2.0, help="pictures a second taken from a video")
    ap.add_argument("--start", type=float, default=0.0, help="video time (s) shown as 0, e.g. where a clip was cut")
    ap.add_argument("--no-xp", action="store_true", help="don't read the XP counter (other people's videos)")
    ap.add_argument("--out", help="folder for the pictures written (default: next to the recording)")
    ap.add_argument("--height", help="the character's height the zoom guide would have set (pixels at 1080 tall), "
                    'or "0:150,28:-,39:200" where the zoom changes; without it the old search is used')
    ap.add_argument("--truth", help='what really happened, e.g. "active:0-87,idle:87-110"')
    ap.add_argument("--set", action="append", default=[], help="try another setting, e.g. --set HOT=0.4")
    a = ap.parse_args()
    for kv in a.set:
        k, v = kv.split("=")
        globals()[k] = float(v)
    global USE_XP
    USE_XP = not a.no_xp
    folder = os.path.abspath(a.folder)
    name = os.path.splitext(os.path.basename(folder))[0]
    out_dir = os.path.abspath(a.out) if a.out else os.path.dirname(folder)
    os.makedirs(out_dir, exist_ok=True)
    print(f"Reading {folder}")
    if os.path.isdir(folder):
        frames = folder_frames(folder)
    else:
        w, h = video_size(folder)
        # find black bars from a picture every 5 seconds, then fit the regions to the game view
        area = game_area(im for _, _, im in video_frames(folder, 0.2))
        set_screen(w, h, area)
        print(f"  {w} x {h}, game view {area}, middle {MIDDLE}, search {SEARCH}")
        frames = video_frames(folder, a.fps)
    guide = parse_guide(a.height)
    out, boxes, idle_moments = run_guide(frames, guide) if GUIDE and guide is not None else run(frames)
    spans = parse_truth(a.truth)

    # Score it against the truth
    print(f"{name}: character found {len(boxes)} time(s); camera moves: {sum(1 for o in out if o[3] == 'camera')} looks")
    for t, box, _ in boxes:
        x0, y0, x1, y1 = box
        print(f"  {t:6.1f} s: box x {x0}-{x1}, y {y0}-{y1}")
    for what, s, e in spans:
        states = [(t, st) for t, _, st, _ in out if s <= t < e]
        if what == "active":
            wrong = [t for t, st in states if st == "idle"]
            print(f"  busy {s:g}-{e:g} s: " + ("never called idle" if not wrong else
                  f"WRONGLY called idle for {len(wrong)} looks, first at {wrong[0]:.1f} s"))
        else:
            rang = [t for t, _ in idle_moments if s <= t < e]
            share = sum(st == "idle" for _, st in states) / max(1, len(states))
            print(f"  idle {s:g}-{e:g} s: " + ("NO alert" if not rang else
                  f"alert after {rang[0] - s:.1f} s, {len(rang)} alert(s) (judged idle for {100 * share:.0f}% of the stretch)"))

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    ts = [o[0] for o in out]
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(16, 7), sharex=True, gridspec_kw={"height_ratios": [3, 1]})
    ax1.plot(ts, [o[1] for o in out], lw=1, label="character box: solid change (%)")
    for t, _, _, ev in out:
        if ev == "camera":
            ax1.axvline(t, color="grey", alpha=0.15, lw=2)
    ax1.set_yscale("symlog", linthresh=1)
    ax1.set_ylabel("% of the box changed")
    ax1.legend(loc="upper right")
    ax2.fill_between(ts, [1 if o[2] == "idle" else 0 for o in out], step="post", color="orange", label="detector: idle")
    for what, s, e in spans:
        ax2.axvspan(s, e, ymin=0, ymax=0.15, color="red" if what == "idle" else "green", alpha=0.6)
    for t, _ in idle_moments:
        ax2.axvline(t, color="black", lw=1.5)
    ax2.set_yticks([0, 1], ["active", "idle"])
    ax2.set_xlabel("seconds (bottom strip: what really happened, green busy, red idle; black lines: alerts; grey: camera moved)")
    ax1.set_title(f"{name}: second detector")
    fig.tight_layout()
    fig.savefig(os.path.join(out_dir, f"{name}_detector.png"), dpi=80)

    # Where it found the character (up to 8 times)
    if boxes:
        cells = []
        for t, box, im in boxes[:8]:
            d = ImageDraw.Draw(im)
            x0, y0, x1, y1 = box
            d.rectangle([x0 - SEARCH[0], y0 - SEARCH[1], x1 - SEARCH[0], y1 - SEARCH[1]], outline=(255, 0, 255), width=4)
            mx, my = MIDDLE[0] - SEARCH[0], MIDDLE[1] - SEARCH[1]
            d.line([(mx - 15, my), (mx + 15, my)], fill=(255, 0, 0), width=2)
            d.line([(mx, my - 15), (mx, my + 15)], fill=(255, 0, 0), width=2)
            d.text((8, 8), f"{t:.1f} s", fill=(255, 255, 0))
            cells.append(im.resize((400, 300)))
        sheet = Image.new("RGB", (400 * 4, 300 * ((len(cells) + 3) // 4)))
        for k, c in enumerate(cells):
            sheet.paste(c, ((k % 4) * 400, (k // 4) * 300))
        sheet.save(os.path.join(out_dir, f"{name}_boxes.png"))

    # The alerts, to check by eye (up to 24)
    print(f"  alerts: {len(idle_moments)}: " + ", ".join(
        f"{int(t + a.start) // 60}:{int(t + a.start) % 60:02d}" for t, _ in idle_moments[:40]))
    if idle_moments:
        w = idle_moments[0][1].width
        sheet = Image.new("RGB", (w * 4, 300 * ((min(24, len(idle_moments)) + 3) // 4)))
        for k, (t, sm) in enumerate(idle_moments[:24]):
            sheet.paste(sm, ((k % 4) * w, (k // 4) * 300))
        sheet.save(os.path.join(out_dir, f"{name}_idle_moments.jpg"), quality=80)


if __name__ == "__main__":
    main()
