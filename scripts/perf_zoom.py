#!/usr/bin/env python3
"""
Zoom/pan benchmark on a device (debug build, a notebook page open in the editor).

Triggers the in-app scripted pinch (zoom in to 3x, hold, pan, zoom out, settle; see
InkCanvasView.PerfScript) and reports the app probe plus Android frame stats.
Don't touch the device while it runs.
Usage: scripts/perf_zoom.py [-s SERIAL] [--skip ink|paper|desk] [--runs N]
"""
import argparse, os, re, subprocess, time

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "app.folio.notes"


def adb(serial, *args):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True).stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL", "R52R904TYYA"))
    ap.add_argument("--skip", default=None)
    ap.add_argument("--runs", type=int, default=1)
    a = ap.parse_args()
    for _ in range(a.runs):
        adb(a.serial, "logcat", "-c")
        adb(a.serial, "shell", "dumpsys", "gfxinfo", PKG, "reset")
        cmd = ["shell", "am", "start", "-f", "0x20000000", "-a", "app.folio.notes.debug.PERF_ZOOM", "-n", PKG + "/.MainActivity"]
        if a.skip:
            cmd += ["--es", "skip", a.skip]
        adb(a.serial, *cmd)
        time.sleep(9.5)
        probe = [l.split("FolioPerf: ")[1] for l in adb(a.serial, "logcat", "-d", "-s", "FolioPerf").splitlines() if "zoomscript" in l or "tiles rendered" in l]
        g = adb(a.serial, "shell", "dumpsys", "gfxinfo", PKG)
        def num(pat):
            m = re.search(pat, g)
            return m.group(1) if m else "?"
        print("\n".join(probe[-2:]) if probe else "no probe (is a page open?)")
        print("  gfx: frames=%s janky=%s p50=%sms p95=%sms gpu_p50=%sms gpu_p95=%sms slow_issue=%s slow_ui=%s uploads=%s" % (
            num(r"Total frames rendered: (\d+)"), num(r"Janky frames: \d+ \(([\d.]+%)\)"),
            num(r"50th percentile: (\d+)ms"), num(r"95th percentile: (\d+)ms"),
            num(r"50th gpu percentile: (\d+)ms"), num(r"95th gpu percentile: (\d+)ms"),
            num(r"Slow issue draw commands: (\d+)"), num(r"Slow UI thread: (\d+)"), num(r"Slow bitmap uploads: (\d+)")))


if __name__ == "__main__":
    main()
