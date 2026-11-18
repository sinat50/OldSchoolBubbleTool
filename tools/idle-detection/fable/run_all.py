"""Idle detection test bench: run detect_idle.py over every recording and print one scoreboard.

  python tools/idle-detection/run_all.py [--fps 1] [--set NAME=VALUE ...] [--youtube]

Our own recordings have a truth (what really happened, worked out from the pictures and the XP); they're scored
for false alarms (called idle while busy) and how long each idle stretch took to notice. Other players' YouTube
clips (--youtube) have no truth: the number of idle calls is printed, to look at in their _idle_moments.jpg.
Recordings live outside the project (see .scratch/afk-alert/spec.md); the paths below are the owner's computer.
"""
import argparse
import os
import sys
from multiprocessing import Pool

HERE = os.path.dirname(os.path.abspath(__file__))
OWN = r"C:\Users\Tanis\Documents\Androiddev\afk_recordings"
E = r"E:\OSRSBubbleTool"

# name, path, truth (what really happened: seconds from the start), XP counter readable
RECORDINGS = [
    ("woodcutting_1", OWN + r"\woodcutting_1", "active:0-240.8,idle:241-275"),
    ("woodcutting_2", OWN + r"\woodcutting_2", "idle:0-8,active:9.5-56,idle:57-86"),
    ("fishing_1", OWN + r"\fishing_1", "idle:0-12,active:13-87,idle:88-110,active:110-176,idle:177-204"),
    ("cooking_1", E + r"\afk_recordings\cooking_1.mp4", "active:0-86,idle:88-119"),
    ("combat_1", E + r"\afk_recordings\combat_1.mp4", "active:0-535,idle:540-618"),
]
YOUTUBE = ["d5gX9_OnOIY_bows", "PFfTIk3TGtg_cooking", "NxYN7xq7_Ec_amethyst", "Og1Z66lLSg4_amethyst_plain",
           "71uCRP1E8hw_runedragons", "UkXVqPOR13g_blackdragons", "NH5nqh8cIpE_tempoross"]
SETTLE = 2.0   # seconds after a busy stretch starts in which "still idle" isn't counted against it
WORKERS = 2    # recordings read at once. Each one takes about 1.6 GB of memory while its video is read: six at
               # once, with other runs going, used up the PC's 32 GB (11 November 2026). Keep it at 2.


def job(args):
    name, path, truth, fps, settings, use_xp, jitter = args
    sys.path.insert(0, HERE)
    import detect_idle as d
    for k, v in settings.items():
        setattr(d, k, v)
    out = d.detect(path, fps=fps, use_xp=use_xp, jitter=jitter)
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
            first = next((t for t, st in states if st == "idle"), None)
            if first is None:
                missed.append(f"{s:g}-{e:g}")
            else:
                delays.append(round(first - s, 1))
    calls = sum(1 for i in range(1, len(out)) if out[i][2] == "idle" and out[i - 1][2] != "idle")
    return name, false_alarms, delays, missed, calls


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fps", type=float, default=2.0, help="looks a second (the app would use about 1)")
    ap.add_argument("--set", action="append", default=[], help="try another setting, e.g. --set WINDOW=6")
    ap.add_argument("--jitter", action="store_true", help="look about once a second at random gaps")
    ap.add_argument("--youtube", action="store_true", help="also run the YouTube clips (no truth)")
    a = ap.parse_args()
    settings = {kv.split("=")[0]: float(kv.split("=")[1]) for kv in a.set}
    jobs = [(n, p, t, a.fps, settings, True, a.jitter) for n, p, t in RECORDINGS]
    if a.youtube:
        jobs += [(n, os.path.join(E, "youtube", "videos", n + ".mp4"), "", a.fps, settings, False, a.jitter) for n in YOUTUBE]
    with Pool(WORKERS) as pool:
        results = pool.map(job, jobs)
    print(("jitter ~1/s  " if a.jitter else f"fps {a.fps:g}  ") + "  ".join(f"{k}={v:g}" for k, v in settings.items()))
    total_fa, all_delays, all_missed = 0, [], 0
    for name, fa, delays, missed, calls in results:
        if name in [r[0] for r in RECORDINGS]:
            total_fa += len(fa); all_delays += delays; all_missed += len(missed)
            print(f"  {name:16s} false alarms {len(fa)} {fa[:6]}  noticed after {delays}  missed {missed}")
        else:
            print(f"  {name:28s} idle calls {calls}")
    med = sorted(all_delays)[len(all_delays) // 2] if all_delays else None
    print(f"TOTAL: false alarms {total_fa}, idle stretches noticed {len(all_delays)}, missed {all_missed}, "
          f"median delay {med} s, longest {max(all_delays) if all_delays else None} s")


if __name__ == "__main__":
    main()
