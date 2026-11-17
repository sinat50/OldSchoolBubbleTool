"""Idle detection: measure a screen recording and draw a timeline.

Research for the idle notifier (plan: .scratch/afk-alert/spec.md). It doesn't build anything for the app.
It reads a folder of screen pictures named f_<milliseconds>.png (as recorded from the phone, 2340 x 1080,
sideways) and works out, for every picture:

  - movement: how much of the area around your character changed compared with the picture about one
    second earlier (the camera keeps your character in the middle of the screen). Comparing with a second
    ago, rather than the picture just before, catches small animations that repeat every second or two.
  - xp: how many of the near-white number pixels in the XP counter (top right) changed since the picture
    just before. A real XP gain changes hundreds of pixels; noise changes a handful.

Then it tries the planned rule:
  you count as ACTIVE while your character moved in the last few seconds OR XP came in recently,
  and as IDLE once both have stopped.

Run it:  python tools/idle-detection/analyse_recording.py <recording folder> [--idle-from SECONDS]
It writes <folder name>_timeline.png and <folder name>_signals.csv next to the folder.
--idle-from marks when you really went idle (seconds from the start), so the chart can show how quickly the
rule noticed and whether it ever called you idle while you were still busy.

Needs Python 3 with numpy, Pillow and matplotlib.
"""

import argparse
import csv
import os
import sys

import numpy as np
from PIL import Image

# Where things are on the owner's phone (2340 x 1080, sideways). Other screens are scaled to match.
SCREEN_W, SCREEN_H = 2340, 1080
CHARACTER_BOX = (1050, 280, 1420, 760)  # left, top, right, bottom: around your character
XP_BOX = (1660, 40, 1790, 80)           # the XP counter's numbers

PIXEL_CHANGED = 40       # a pixel counts as changed when its red+green+blue differ by more than this
NUMBER_WHITE = 600       # XP counter pixels brighter than this (red+green+blue) are the number itself
XP_GAIN_PIXELS = 60      # this many changed number pixels = an XP gain (noise stays under about 20)

COMPARE_WITH = 1.0       # seconds: compare each picture with the one about this long before
MOVING = 1.0             # % of the character area changed: above this, your character is moving
STILL_FOR = 4.0          # seconds of no movement before movement says "idle"
XP_GAP_TIMES = 3.0       # XP says "idle" after this many times the usual gap between XP gains...
XP_GAP_MIN = 6.0         # ...but never sooner than this many seconds


def scaled(box, w, h):
    l, t, r, b = box
    return (l * w // SCREEN_W, t * h // SCREEN_H, r * w // SCREEN_W, b * h // SCREEN_H)


def load(folder):
    """Every picture's time (seconds from the first) and file name, oldest first."""
    names = sorted(n for n in os.listdir(folder) if n.startswith("f_") and n.endswith(".png"))
    if not names:
        sys.exit(f"No f_<milliseconds>.png pictures in {folder}")
    ms = [int(n[2:-4]) for n in names]
    return [(m - ms[0]) / 1000.0 for m in ms], names


def crop(path, box):
    im = Image.open(path).convert("RGB")
    return np.asarray(im.crop(scaled(box, *im.size)), dtype=np.int16)


def measure(folder):
    times, names = load(folder)
    chars, xps = [], []
    for i, n in enumerate(names):
        p = os.path.join(folder, n)
        chars.append(crop(p, CHARACTER_BOX))
        xps.append(crop(p, XP_BOX))
        if i % 50 == 0:
            print(f"  read {i}/{len(names)}", end="\r")
    print()

    movement, movement_next, xp_change = [], [], []
    for i, t in enumerate(times):
        # the latest picture taken at least COMPARE_WITH seconds before this one
        j = i - 1
        while j >= 0 and times[j] > t - COMPARE_WITH:
            j -= 1
        if j < 0:
            movement.append(np.nan)
        else:
            d = np.abs(chars[i] - chars[j]).sum(axis=2)
            movement.append(100.0 * (d > PIXEL_CHANGED).mean())
        # the picture just before, for comparison with the older measurement
        if i == 0:
            movement_next.append(np.nan)
            xp_change.append(0)
        else:
            d = np.abs(chars[i] - chars[i - 1]).sum(axis=2)
            movement_next.append(100.0 * (d > PIXEL_CHANGED).mean())
            white_now = xps[i].sum(axis=2) > NUMBER_WHITE
            white_before = xps[i - 1].sum(axis=2) > NUMBER_WHITE
            xp_change.append(int((white_now != white_before).sum()))
    return times, names, movement, movement_next, xp_change


def apply_rule(times, movement, xp_change):
    """Play the planned rule through the recording. Returns, for every picture, 1 for idle and 0 for active,
    plus the moments XP came in."""
    last_moving = 0.0          # counts as moving at the start: you switch it on while busy
    xp_times = []
    states = []
    for t, m, x in zip(times, movement, xp_change):
        if not np.isnan(m) and m > MOVING:
            last_moving = t
        if x >= XP_GAIN_PIXELS:
            xp_times.append(t)
        still = t - last_moving >= STILL_FOR
        if len(xp_times) >= 2:
            gaps = np.diff(xp_times)
            usual = float(np.median(gaps))
            wait = max(XP_GAP_MIN, XP_GAP_TIMES * usual)
        else:
            wait = XP_GAP_MIN
        last_xp = xp_times[-1] if xp_times else 0.0
        no_xp = t - last_xp >= wait
        states.append(1 if (still and no_xp) else 0)
    return states, xp_times


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder")
    ap.add_argument("--idle-from", type=float, help="seconds from the start when you really went idle")
    ap.add_argument("--box", help="character area to try instead: left,top,right,bottom (2340 x 1080 screen)")
    ap.add_argument("--tag", default="", help="added to the output file names, to keep several tries")
    a = ap.parse_args()
    if a.box:
        global CHARACTER_BOX
        CHARACTER_BOX = tuple(int(v) for v in a.box.split(","))

    folder = os.path.abspath(a.folder)
    name = os.path.basename(folder) + a.tag
    out = os.path.dirname(folder)
    print(f"Reading {folder}")
    times, names, movement, movement_next, xp_change = measure(folder)
    states, xp_times = apply_rule(times, movement, xp_change)

    with open(os.path.join(out, f"{name}_signals.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["seconds", "picture", "movement_1s_%", "movement_prev_%", "xp_pixels", "rule_idle"])
        for row in zip(times, names, movement, movement_next, xp_change, states):
            w.writerow([f"{row[0]:.3f}", row[1], f"{row[2]:.3f}", f"{row[3]:.3f}", row[4], row[5]])

    # Summary
    mv = np.array(movement)
    print(f"{len(times)} pictures over {times[-1]:.1f} s; XP gains seen at: "
          + ", ".join(f"{t:.1f}" for t in xp_times))
    if len(xp_times) >= 2:
        g = np.diff(xp_times)
        print(f"  gaps between XP gains: shortest {g.min():.1f} s, usual {np.median(g):.1f} s, longest {g.max():.1f} s")
    first_idle = next((t for t, s in zip(times, states) if s), None)
    if a.idle_from is not None:
        busy = [m for t, m in zip(times, mv) if t < a.idle_from and not np.isnan(m)]
        idle = [m for t, m in zip(times, mv) if t >= a.idle_from + 1.5 and not np.isnan(m)]
        if busy:
            print(f"  movement while busy: lowest {min(busy):.2f}%, median {np.median(busy):.2f}%")
            # the longest stretch while busy with no movement above MOVING
            longest, start = 0.0, None
            for t, m in zip(times, mv):
                if t >= a.idle_from:
                    break
                if np.isnan(m):
                    continue
                if m <= MOVING:
                    start = t if start is None else start
                    longest = max(longest, t - start)
                else:
                    start = None
            print(f"  longest still stretch while busy: {longest:.1f} s (movement alone would alarm after {STILL_FOR} s)")
        if idle:
            print(f"  movement once idle: highest {max(idle):.2f}%, median {np.median(idle):.2f}%")
        false_alarms = [t for t, s in zip(times, states) if s and t < a.idle_from]
        print(f"  rule called you idle while busy: {'never' if not false_alarms else ', '.join(f'{t:.1f}' for t in false_alarms[:10])}")
        noticed = next((t for t, s in zip(times, states) if s and t >= a.idle_from), None)
        print(f"  rule noticed you were idle: {'never' if noticed is None else f'{noticed - a.idle_from:.1f} s after you stopped'}")
    else:
        print(f"  rule first said idle at: {'never' if first_idle is None else f'{first_idle:.1f} s'}")

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, (ax1, ax2, ax3) = plt.subplots(3, 1, figsize=(16, 9), sharex=True,
                                        gridspec_kw={"height_ratios": [3, 2, 1]})
    ax1.plot(times, movement, lw=1, label="vs ~1 s before")
    ax1.plot(times, movement_next, lw=0.6, alpha=0.5, label="vs picture just before")
    ax1.axhline(MOVING, color="red", ls="--", lw=0.8, label=f"moving above {MOVING}%")
    ax1.set_ylabel("character area changed (%)")
    ax1.set_yscale("symlog", linthresh=1)
    ax1.legend(loc="upper right")
    ax2.bar(times, xp_change, width=0.3, color="green")
    ax2.axhline(XP_GAIN_PIXELS, color="red", ls="--", lw=0.8)
    ax2.set_ylabel("XP counter pixels changed")
    ax3.fill_between(times, states, step="post", color="orange")
    ax3.set_yticks([0, 1], ["active", "idle"])
    ax3.set_xlabel("seconds")
    for ax in (ax1, ax2, ax3):
        if a.idle_from is not None:
            ax.axvline(a.idle_from, color="black", lw=1)
    ax1.set_title(f"{name}: movement, XP and the rule's answer"
                  + ("" if a.idle_from is None else f" (black line: really idle from {a.idle_from} s)"))
    fig.tight_layout()
    chart = os.path.join(out, f"{name}_timeline.png")
    fig.savefig(chart, dpi=80)
    print(f"Chart: {chart}")


if __name__ == "__main__":
    main()
