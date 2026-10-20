#!/usr/bin/env python3
"""
Library smoothness on a device, without touching the user's notes.

Only taps and scrolls: collapses and expands the sidebar, switches sections and
grid/list, scrolls the grid, opens the first notebook and comes back. It never
creates, edits or deletes anything, so it is safe on the user's own tablet.
Frame stats come from `dumpsys gfxinfo` (reset before the run).

Usage: scripts/perf_library.py -s SERIAL [--rounds 3] [--label before]
"""
import argparse, os, re, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from e2e_milestone import ADB, PKG, Device  # noqa: E402


def gfx(d, *extra):
    return d.sh("shell", "dumpsys", "gfxinfo", PKG, *extra)


def summary(text):
    out = {}
    for key, pat in [
        ("frames", r"Total frames rendered: (\d+)"),
        ("janky", r"Janky frames: (\d+) \(([\d.]+)%\)"),
        ("p50", r"50th percentile: (\d+)ms"),
        ("p90", r"90th percentile: (\d+)ms"),
        ("p95", r"95th percentile: (\d+)ms"),
        ("p99", r"99th percentile: (\d+)ms"),
    ]:
        m = re.search(pat, text)
        if m:
            out[key] = m.group(2) + "%" if key == "janky" else m.group(1)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--serial", required=True)
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--label", default="")
    ap.add_argument("--out", default="build/perf-library")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    d = Device(a.serial, a.out)

    d.sh("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    d.launch()
    # Folio reopens the last notebook; back out to the library (closing any open sheet first).
    for _ in range(4):
        if 'text="Documents"' in d.ui():
            break
        d.sh("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1)
    d.find("Documents")
    if 'content-desc="Expand sidebar"' in d.ui():
        d.tap("Expand sidebar")
    w, h = map(int, re.search(r"(\d+)x(\d+)", d.sh("shell", "wm", "size")).groups())
    if w < h:
        w, h = h, w  # landscape
    gfx(d, "reset")

    for _ in range(a.rounds):
        for label in ("Collapse sidebar", "Expand sidebar"):
            d.tap(label)
            time.sleep(0.8)
        for label in ("Favorites", "Trash", "Documents"):
            d.tap(label)
            time.sleep(0.5)
        d.tap("Switch to list view")
        time.sleep(0.6)
        d.tap("Switch to grid view")
        time.sleep(0.6)
        cx = int(w * 0.62)
        for y1, y2 in ((0.8, 0.3), (0.3, 0.8)):
            d.sh("shell", "input", "swipe", str(cx), str(int(h * y1)), str(cx), str(int(h * y2)), "250")
            time.sleep(0.6)

    lib = summary(gfx(d))
    d.shot(f"library{('-' + a.label) if a.label else ''}")

    # Open the first notebook tile and come back: editor start-up and return to the library.
    xml = d.ui()
    first = re.search(r'content-desc="Notebook ([^"]+)"', xml)
    open_ms = None
    if first:
        gfx(d, "reset")
        t = time.time()
        d.tap(f"Notebook {first.group(1)}")
        d.find("Handwriting canvas", timeout=20)
        open_ms = int((time.time() - t) * 1000)
        time.sleep(1.5)
        d.sh("shell", "input", "keyevent", "KEYCODE_BACK")
        d.find("Documents")
        time.sleep(1)
        roundtrip = summary(gfx(d))
    else:
        roundtrip = {}

    print(f"[{a.label or 'run'}] library interactions: {lib}")
    if first:
        print(f"[{a.label or 'run'}] open '{first.group(1)}' → canvas found in {open_ms} ms (includes UI-dump polling); open+back frames: {roundtrip}")


if __name__ == "__main__":
    main()
