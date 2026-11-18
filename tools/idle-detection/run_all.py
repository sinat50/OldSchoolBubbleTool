"""Idle detection test bench: run detect_idle.py over every recording and print one scoreboard.

  python tools/idle-detection/run_all.py [--fps 1] [--youtube] [--no-xp] [--search] [--scale 0.7] [--set NAME=VALUE ...]

Our own recordings have a truth (what really happened, worked out from the pictures and the XP). The numbers:
- false alarms: stretches of looks judged idle while you were busy, whether or not the alert went off (the
  stricter count: every wrong alert is one of these, not every one of these is an alert);
- wrong buzzes: alerts that went off while you were busy (what the player would notice);
- alerted after: seconds from when you really stopped to the alert (the buzz); missed = no alert in that stretch;
- alerts: how many times the buzz went off in the whole recording (one for each idle stretch is right);
- idle flips: how many times the look-by-look answer went from busy to idle. This was the old "idle calls";
  it's higher than the alerts when a standing character flickers idle, not idle, idle.
Other players' YouTube clips (--youtube) have no truth: their alerts and idle flips are printed, to look at in
their _idle_moments.jpg (one picture for each alert).
The detector works the zoom guide's way (the character's size is known, see detect_idle.py). The recordings
weren't made with the guide, so GUIDE below gives each one the character's true height as the guide would
have set it. --search runs the old way (searching for the character) to compare; --scale 0.7 or 1.4 gives
every height wrong by that much (a player who zoomed too far out or in).
Recordings live outside the project (see .scratch/afk-alert/spec.md); the paths below are the owner's computer.
"""
import argparse
import os
import sys
from multiprocessing import Pool

HERE = os.path.dirname(os.path.abspath(__file__))
OWN = r"C:\Users\Tanis\Documents\Androiddev\afk_recordings"
E = r"E:\OSRSBubbleTool"

# name, path, truth (what really happened: seconds from the start)
RECORDINGS = [
    ("woodcutting_1", OWN + r"\woodcutting_1", "active:0-240.8,idle:241-275"),
    ("woodcutting_2", OWN + r"\woodcutting_2", "idle:0-8,active:9.5-56,idle:57-86"),
    ("fishing_1", OWN + r"\fishing_1", "idle:0-12,active:13-87,idle:88-110,active:110-176,idle:177-204"),
    ("cooking_1", E + r"\afk_recordings\cooking_1.mp4", "active:0-86,idle:88-119"),
    ("combat_1", E + r"\afk_recordings\combat_1.mp4", "active:0-535,idle:540-618"),
    # 17 November 2026: someone lit a fire under the owner; chopping (camera turned at 5-9 s) until 17 s, then
    # standing in the fire's smoke, with players walking through the box until 25 s (notes.md there)
    ("fire_1", OWN + r"\fire_1", "active:0-17,idle:17.5-37"),
    # the same day, with the app running: alone at the guide's size on a fire. Chopping 7-12.5 s, then tending the
    # fire (a log at 13.3 s and one at 19.3 s, standing still between them: not scored either way), standing from
    # about 22 s (notes.md there)
    ("fire_2", OWN + r"\fire_2", "active:6-13,idle:22-55.5"),
    # recorded with the Idle Notifier running on the phone (watching from 37 s and 19.5 s; notes.md in each folder)
    ("forestry_1", OWN + r"\forestry_1\forestry_1.mp4", "active:37-63.5,idle:64-71,active:72-115.5,idle:117-131"),
    ("potions_1", OWN + r"\potions_1\potions_1.mp4", "idle:73-90,idle:132.5-154"),
]
YOUTUBE = ["d5gX9_OnOIY_bows", "PFfTIk3TGtg_cooking", "NxYN7xq7_Ec_amethyst", "Og1Z66lLSg4_amethyst_plain",
           "71uCRP1E8hw_runedragons", "UkXVqPOR13g_blackdragons", "NH5nqh8cIpE_tempoross"]

# The character's height in each recording, as the zoom guide would have set it: pixels on a 1080-tall
# screen, MEASURED BY EYE (11 November 2026) against a ruler drawn on one picture from each stretch between
# camera moves, so good to about a fifth either way. One number = the zoom never changes. A list = (from this
# second on, height); the owner changed the zoom on purpose in most of his recordings, as if he had set the
# guide again each time (each change is put where the camera was moving, about 2 s before it settled). None = the guide wouldn't cover it (a menu over the game, a stopwatch shot, a
# keyboard), and nothing is watched there. Heights far outside the guide's range (about 110 to 240 pixels)
# are kept as they are: they show what happens when a player ignores the guide.
GUIDE = {
    "woodcutting_1": [(0, 180), (19, 150), (64, 90), (87, 100), (179, 90)],
    "woodcutting_2": [(0, 95), (12, 110)],
    # 28-39 s: zoomed right out (35 pixels, far smaller than the guide allows)
    "fishing_1": [(0, 150), (26, 35), (37, 200), (42, 130), (48, 110), (75, 220), (122, 130), (128, 200),
                  (143, 230), (169, 90), (196, 210)],
    "cooking_1": [(0, 300), (9, 280), (25, 300), (44, 60), (51, 250), (63, 130), (74, 80), (100, 230)],
    "combat_1": 60,                               # zoomed out the whole way, seen from above
    # These three are given the guide's own 162 px, which is what the app watched, whatever the character's
    # size really was: fire_1's character is about 210 px tall (head 405, feet 615), forestry_1's about 100,
    # potions_1's about 270
    "fire_1": 162,
    "fire_2": [(0, None), (5, 162)],              # Start tapped at about 5 s (the guide box goes)
    "forestry_1": [(0, None), (37, 162)],
    "potions_1": [(0, None), (19.5, 162)],
    # The YouTube clips are cut every 10-40 s; the stretches with a menu or a stopwatch over the game are None
    "d5gX9_OnOIY_bows": [(0, 300), (27, None), (57, 290), (63, 170), (71, None), (82, 520), (104, None),
                         (136, 560), (144, 250), (160, None), (173, 300), (197, None), (243, 260), (264, None),
                         (287, 260), (302, None), (315, 450), (334, None), (372, 480), (387, None), (413, 150),
                         (422, None), (436, 480), (458, None)],
    "PFfTIk3TGtg_cooking": [(0, None), (12, 330), (26, None), (100, 250), (123, None), (157, 110), (170, None),
                            (194, 350), (212, None), (245, 450), (264, None), (325, 450), (339, None)],
    "NxYN7xq7_Ec_amethyst": [(0, 220), (16, 180), (52, 200), (71, 190), (123, None), (138, 200), (156, 230),
                             (170, 200), (206, None)],
    "Og1Z66lLSg4_amethyst_plain": 70,             # unedited, zoomed out, seen from almost straight above
    "71uCRP1E8hw_runedragons": [(0, None), (7, 160), (28, 110)],
    "UkXVqPOR13g_blackdragons": [(0, None), (7, 110)],
    "NH5nqh8cIpE_tempoross": [(0, 50), (82, 80), (108, 50), (148, 60), (187, None), (197, 60), (228, 50)],
}
SETTLE = 2.0   # seconds after a busy stretch starts in which "still idle" isn't counted against it
WORKERS = 8    # recordings read at once. Each one takes about 1.6 GB of memory while its video is read: twenty
               # at once, with other runs going, used up the PC's 32 GB (11 November 2026). Never more than 8
               # (the owner's limit). Check free memory before a big run: 8 need about 13 GB, so use 8 only
               # with about 16 GB free, 4 with 10 to 16 GB free, and 2 under 10 GB


def job(args):
    name, path, truth, fps, settings, use_xp, jitter, guide = args
    sys.path.insert(0, HERE)
    import detect_idle as d
    for k, v in settings.items():
        setattr(d, k, v)
    out, alerts = d.detect(path, fps=fps, use_xp=use_xp, jitter=jitter, guide=guide)
    spans = d.parse_truth(truth)
    false_alarms, delays, missed = [], [], []
    for what, s, e in spans:
        states = [(t, st) for t, _, st, _ in out if s <= t < e]
        if what == "active":
            # count each separate wrong idle stretch once
            prev = False
            for t, st in states:
                bad = st == "idle" and t >= s + SETTLE
                if bad and not prev:
                    false_alarms.append(round(t, 1))
                prev = bad
        else:
            first = next((t for t in alerts if s <= t < e), None)
            if first is None:
                missed.append(f"{s:g}-{e:g}")
            else:
                delays.append(round(first - s, 1))
    flips = sum(1 for i in range(1, len(out)) if out[i][2] == "idle" and out[i - 1][2] != "idle")
    # buzzes at a wrong moment: alerts that went off while you were busy
    wrong = [round(t, 1) for t in alerts if any(w == "active" and s + SETTLE <= t < e for w, s, e in spans)]
    return name, false_alarms, delays, missed, len(alerts), flips, wrong


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fps", type=float, default=2.0, help="looks a second (the app would use about 1)")
    ap.add_argument("--set", action="append", default=[], help="try another setting, e.g. --set WINDOW=6")
    ap.add_argument("--jitter", action="store_true", help="look about once a second at random gaps")
    ap.add_argument("--youtube", action="store_true", help="also run the YouTube clips (no truth)")
    ap.add_argument("--workers", type=int, default=WORKERS, help="recordings read at once (see WORKERS; never over 8)")
    ap.add_argument("--no-xp", action="store_true", help="don't read the XP counter in our own recordings either "
                    "(the detector must work with the counter hidden)")
    ap.add_argument("--search", action="store_true", help="the old way: search for the character (no zoom guide)")
    ap.add_argument("--scale", type=float, default=1.0, help="give every guide height wrong by this much (0.7, 1.4)")
    a = ap.parse_args()
    settings = {kv.split("=")[0]: float(kv.split("=")[1]) for kv in a.set}
    if a.scale != 1.0:
        settings["GUIDE_SCALE"] = a.scale
    guide = (lambda n: None) if a.search else GUIDE.get
    jobs = [(n, p, t, a.fps, settings, not a.no_xp, a.jitter, guide(n)) for n, p, t in RECORDINGS]
    if a.youtube:
        jobs += [(n, os.path.join(E, "youtube", "videos", n + ".mp4"), "", a.fps, settings, False, a.jitter, guide(n)) for n in YOUTUBE]
    with Pool(max(1, min(8, a.workers))) as pool:
        results = pool.map(job, jobs)
    print(("OLD SEARCH  " if a.search else "ZOOM GUIDE  ") + ("XP not read  " if a.no_xp else "") + ("jitter ~1/s  " if a.jitter else f"fps {a.fps:g}  ") + "  ".join(f"{k}={v:g}" for k, v in settings.items()))
    total_fa, all_delays, all_missed = 0, [], 0
    total_wrong = 0
    for name, fa, delays, missed, alerts, flips, wrong in results:
        if name in [r[0] for r in RECORDINGS]:
            total_fa += len(fa); all_delays += delays; all_missed += len(missed); total_wrong += len(wrong)
            print(f"  {name:16s} false alarms {len(fa)} {fa[:6]}  wrong buzzes {len(wrong)} {wrong[:6]}  "
                  f"alerted after {delays}  missed {missed}  "
                  f"alerts {alerts}  idle flips {flips}")
        else:
            print(f"  {name:28s} alerts {alerts}  idle flips {flips}")
    med = sorted(all_delays)[len(all_delays) // 2] if all_delays else None
    print(f"TOTAL: false alarms {total_fa}, wrong buzzes {total_wrong}, idle stretches alerted {len(all_delays)}, missed {all_missed}, "
          f"median delay {med} s, longest {max(all_delays) if all_delays else None} s")


if __name__ == "__main__":
    main()
