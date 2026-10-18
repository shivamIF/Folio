#!/usr/bin/env python3
"""
Rendering/latency benchmark on a device (debug build installed).

For each page density (0, ~600 = full handwritten page, 3000 = stress) it loads a
synthetic page, then (1) writes 12 stylus strokes and (2) scrolls with 10 finger
swipes, collecting:
  - app probes (logcat tag FolioPerf): input→draw-complete latency and onDraw time
  - Android frame stats (dumpsys gfxinfo): frame-time percentiles, janky frames
Usage: scripts/perf_tab.py [-s SERIAL] [--out build/perf]
"""
import argparse, json, math, os, random, re, statistics, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from e2e_milestone import ADB, PKG, Device  # noqa: E402


def handwriting_page(page_id, n_strokes, seed=7):
    """Squiggly 'words' along ruled lines, roughly like real handwriting."""
    rnd = random.Random(seed)
    strokes = []
    line, x = 160.0, 130.0
    for i in range(n_strokes):
        pts = rnd.randint(30, 80)
        w = rnd.uniform(25, 70)
        xs, ys, ps = [], [], []
        for k in range(pts):
            t = k / (pts - 1)
            xs.append(x + t * w)
            ys.append(line - 12 + 12 * math.sin(t * math.pi * rnd.uniform(2, 5)) + rnd.uniform(-1, 1))
            ps.append(0.3 + 0.4 * math.sin(t * math.pi))
        strokes.append({
            "id": f"perf-{i}", "tool": "FOUNTAIN", "color": 0xFF1B1B1F, "width": 3.5,
            "x": xs, "y": ys, "pressure": ps, "tilt": [0.7] * pts, "orientation": [0.0] * pts,
            "t": [k * 4 for k in range(pts)], "startedAt": 0,
        })
        x += w + rnd.uniform(8, 20)
        if x > 900:
            x, line = 130.0, line + 40
            if line > 1380:
                line = 160.0  # wrap: the stress page overprints, like several layers of notes
    return {"schemaVersion": 1, "id": page_id, "width": 1000.0, "height": 1414.0, "paper": "RULED", "strokes": strokes}


def gfx(d):
    out = d.sh("shell", "dumpsys", "gfxinfo", PKG)
    def num(pat):
        m = re.search(pat, out)
        return float(m.group(1)) if m else None
    return {
        "frames": num(r"Total frames rendered: (\d+)"),
        "janky_pct": num(r"Janky frames: \d+ \(([\d.]+)%\)"),
        "p50": num(r"50th percentile: (\d+)ms"), "p90": num(r"90th percentile: (\d+)ms"),
        "p95": num(r"95th percentile: (\d+)ms"), "p99": num(r"99th percentile: (\d+)ms"),
    }


def perf_lines(d, kind):
    log = d.sh("shell", "logcat", "-d", "-s", "FolioPerf:D")
    rows = []
    for m in re.finditer(kind + r" frames=(\d+) strokes=(\d+) latency_p50=([-\d.]+) latency_p95=([-\d.]+) draw_p50=([-\d.]+) draw_p95=([-\d.]+) draw_max=([-\d.]+)", log):
        rows.append([float(v) for v in m.groups()])
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL", "R52R904TYYA"))
    ap.add_argument("--out", default="build/perf")
    ap.add_argument("--densities", default="0,600,3000")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    d = Device(a.serial, a.out)

    d.sh("shell", "pm", "clear", PKG)
    d.launch()
    d.tap("New notebook")
    x, y, _ = d.find("Title"); d.sh("shell", "input", "tap", str(x), str(y))
    d.sh("shell", "input", "text", "Perf"); time.sleep(0.5)
    d.sh("shell", "input", "keyevent", "KEYCODE_ENTER")
    d.find("Handwriting canvas")
    nb = d.library()["notebooks"][0]
    nb_id, pid = nb["id"], nb["pageIds"][0]
    results = {}

    for n in [int(v) for v in a.densities.split(",")]:
        page = handwriting_page(pid, n)
        path = os.path.join(a.out, f"page-{n}.json")
        json.dump(page, open(path, "w"))
        d.sh("shell", "am", "force-stop", PKG)
        with open(path, "rb") as f:
            subprocess.run([ADB, "-s", a.serial, "exec-in", "run-as", PKG, "sh", "-c",
                            f"cat > files/library/notebooks/{nb_id}/{pid}.page.json"], stdin=f, check=True)
        d.launch()
        t0 = time.time()
        d.tap("Notebook Perf")
        # "Page 1 of 1" text appears once the page is loaded and shown.
        _, _, (x1, y1, x2, y2) = d.find("Handwriting canvas", timeout=90)
        open_s = round(time.time() - t0, 1)
        time.sleep(2)
        w, h = x2 - x1, y2 - y1

        # Ink: 12 stylus strokes across the visible page.
        d.sh("shell", "logcat", "-c"); d.sh("shell", "dumpsys", "gfxinfo", PKG, "reset")
        for k in range(12):
            yy = y1 + int(h * (0.15 + 0.06 * k))
            d.sh("shell", "input", "stylus", "swipe", str(x1 + w // 6), str(yy), str(x1 + 5 * w // 6), str(yy + 25), "450")
        time.sleep(1.5)
        ink, ink_gfx = perf_lines(d, "ink"), gfx(d)

        # Scroll: zoom in a little first is not possible via adb, so scroll the fit-width page vertically.
        d.sh("shell", "logcat", "-c"); d.sh("shell", "dumpsys", "gfxinfo", PKG, "reset")
        for k in range(10):
            ya, yb = (y1 + int(h * 0.8), y1 + int(h * 0.3)) if k % 2 == 0 else (y1 + int(h * 0.3), y1 + int(h * 0.8))
            d.sh("shell", "input", "touchscreen", "swipe", str(x1 + w // 2), str(ya), str(x1 + w // 2), str(yb), "350")
            time.sleep(0.4)
        time.sleep(1.5)
        pan, pan_gfx = perf_lines(d, "pan"), gfx(d)

        # Erase: partial eraser rubbed across existing writing.
        d.tap("Eraser"); d.tap("Partial eraser")
        d.sh("shell", "logcat", "-c"); d.sh("shell", "dumpsys", "gfxinfo", PKG, "reset")
        for k in range(8):
            xx = x1 + int(w * (0.2 + 0.08 * k))
            d.sh("shell", "input", "stylus", "swipe", str(xx), str(y1 + int(h * 0.1)), str(xx + 30), str(y1 + int(h * 0.6)), "500")
        time.sleep(1.5)
        er, er_gfx = perf_lines(d, "erase"), gfx(d)
        d.tap("Fountain pen")

        def agg(rows, i):
            vals = [r[i] for r in rows if r[i] >= 0]
            return round(statistics.median(vals), 1) if vals else None
        results[n] = {
            "ink_latency_p50": agg(ink, 2), "ink_latency_p95": agg(ink, 3), "ink_draw_p50": agg(ink, 4), "ink_draw_p95": agg(ink, 5),
            "ink_frames_gfx": ink_gfx, "pan_draw_p50": agg(pan, 4), "pan_draw_p95": agg(pan, 5), "pan_draw_max": max([r[6] for r in pan], default=None),
            "erase_latency_p50": agg(er, 2), "erase_latency_p95": agg(er, 3), "erase_draw_p95": agg(er, 5), "erase_frames_gfx": er_gfx,
            "pan_frames_gfx": pan_gfx, "strokes_on_page": n, "open_seconds_incl_ui_dump": open_s,
        }
        print(f"density {n}: {json.dumps(results[n])}", flush=True)

    json.dump(results, open(os.path.join(a.out, "results.json"), "w"), indent=2)
    print(f"\nsaved {a.out}/results.json")


if __name__ == "__main__":
    main()
