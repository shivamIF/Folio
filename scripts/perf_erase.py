#!/usr/bin/env python3
"""
Eraser benchmark on the page open in the editor (debug build). Don't touch the device.

Selects the eraser (stroke or partial), rubs N stylus swipes across the page, reports
the app probes (FolioPerf: per-gesture latency/draw, pen-up commit time) and frame
stats, then taps Undo N times so the page is back as it was (check with a backup).
Usage: scripts/perf_erase.py [-s SERIAL] [--mode stroke|partial] [--swipes N] [--no-undo]
"""
import argparse, os, re, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from e2e_milestone import ADB, PKG, Device  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL", "R52R904TYYA"))
    ap.add_argument("--mode", choices=["stroke", "partial"], default="stroke")
    ap.add_argument("--swipes", type=int, default=3)
    ap.add_argument("--no-undo", action="store_true")
    a = ap.parse_args()
    d = Device(a.serial, "build/perf")
    d.eraser_menu()
    d.tap("Partial eraser" if a.mode == "partial" else "Stroke eraser")
    d.sh("logcat", "-c")
    d.sh("shell", "dumpsys", "gfxinfo", PKG, "reset")
    for i in range(a.swipes):
        y = 700 + i * 140
        d.sh("shell", "input", "stylus", "swipe", "150", str(y), "1050", str(y + 60), "1200")
        time.sleep(2.5)
    log = d.sh("logcat", "-d", "-s", "FolioPerf")
    for l in log.splitlines():
        if "erase" in l:
            print(l.split("FolioPerf: ")[1])
    g = d.sh("shell", "dumpsys", "gfxinfo", PKG)
    def num(pat):
        m = re.search(pat, g)
        return m.group(1) if m else "?"
    print("gfx: frames=%s janky=%s p50=%sms p90=%sms p95=%sms p99=%sms" % (
        num(r"Total frames rendered: (\d+)"), num(r"Janky frames: \d+ \(([\d.]+%)\)"),
        num(r"50th percentile: (\d+)ms"), num(r"90th percentile: (\d+)ms"), num(r"95th percentile: (\d+)ms"), num(r"99th percentile: (\d+)ms")))
    if not a.no_undo:
        for _ in range(a.swipes):
            d.tap("Undo")
            time.sleep(1.5)


if __name__ == "__main__":
    main()
