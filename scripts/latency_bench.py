#!/usr/bin/env python3
"""
Pen-event → on-screen latency of live ink, measured on a device (debug build installed).

Opens InkBenchActivity (the real InkCanvasView on an in-memory page: no notebook is
touched), injects stylus strokes, then pairs:
  - app log (tag FolioLatency): for each posted ink frame, its post time and the event
    time of the newest pen sample it contains;
  - SurfaceFlinger (`dumpsys SurfaceFlinger --latency <layer>`): when each buffer of the
    page surface actually reached the screen (actual present time).
All clocks are CLOCK_MONOTONIC. Reported per frame:
  event→post    : app work (input dispatch, geometry, drawing, buffer post)
  event→present : the above plus queueing and composition, up to the vsync the frame
                  appears at.
  wet           : with front-buffered live ink (WetInkLayer) the live stroke skips the page
                  surface: event → its GPU work submitted to the front buffer. It shows as
                  the display next scans that area (0–16.7 ms at 60 Hz); there is no queue. Scan-out down the panel (0–16.7 ms at 60 Hz) and the
                  digitiser's own delay are not included; those need a high-speed camera.
Injected strokes (`input stylus swipe`) have constant pressure and no tilt, so this
measures the pipeline, not the feel of real handwriting.

Usage: scripts/latency_bench.py -s SERIAL [--tool FOUNTAIN] [--stab 0.1] [--strokes 12] [--dense N] [--zoom Z]
       scripts/latency_bench.py -s SERIAL --manual 30 [--editor]   # you write with the S Pen
"""
import argparse, json, os, re, statistics, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from e2e_milestone import PKG, Device  # noqa: E402


def pct(v, q):
    v = sorted(v)
    return v[int((len(v) - 1) * q)] if v else float("nan")


def layer_name(d, activity):
    for line in d.sh("shell", "dumpsys", "SurfaceFlinger", "--list").splitlines():
        # The BLAST layer is the one that receives the page's buffers.
        if line.startswith("SurfaceView[") and activity in line and "(BLAST)" in line:
            return line.strip()
    raise RuntimeError(f"{activity} page SurfaceView layer not found (is it on screen?)")


WET = []  # front-buffered live ink: event → GPU work submitted (ms)
PRED = []  # how far ahead of the newest pen sample the motion predictor reaches (ms)


def collect(d, sf_rows, log, event_post, event_present, draw_p95, draw_max, post_ready=None, ready_present=None):
    """Pairs logged ink frames with SurfaceFlinger rows (see module doc)."""
    for m in re.finditer(r"ink frames=\d+ strokes=\d+ latency_p50=\S+ latency_p95=\S+ draw_p50=(\S+) draw_p95=(\S+) draw_max=(\S+)", log):
        draw_p95.append(float(m.group(2))); draw_max.append(float(m.group(3)))
    for m in re.finditer(r"FolioLatency: predict (\S+)", log):
        PRED.extend(float(v) for v in m.group(1).split(","))
    for m in re.finditer(r"FolioLatency: wet (\S+)", log):
        WET.extend(float(v) for v in m.group(1).split(","))
    frames = []
    for m in re.finditer(r"frames (\S+)", log):
        for item in m.group(1).split(","):
            post, ev = map(int, item.split(":"))
            frames.append((post, ev))
    sf = sorted(sf_rows)
    for post, ev in frames:
        event_post.append((post - ev) / 1e6)
        # The buffer posted at `post` is the first SurfaceFlinger frame queued at or after it.
        cands = [r for r in sf if r[0] >= post - 2_000_000]
        if cands:
            desired, actual, ready = cands[0]
            if desired - post < 20_000_000:
                event_present.append((actual - ev) / 1e6)
                if post_ready is not None and ready < 2**62:
                    post_ready.append((ready - post) / 1e6)      # GPU finishing the frame
                    ready_present.append((actual - ready) / 1e6)  # waiting for SurfaceFlinger and the display


def sf_frames(d, layer):
    """(desired_present, actual_present, frame_ready) ns per buffer, from SurfaceFlinger."""
    out = d.sh("shell", "dumpsys", "SurfaceFlinger", "--latency", f"'{layer}'")  # quoted for the device shell
    rows = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) == 3 and all(p.isdigit() for p in parts):
            a, b, c = map(int, parts)
            if a and b and b < 2**62:
                rows.append((a, b, c))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--serial")
    ap.add_argument("--tool", default="FOUNTAIN")
    ap.add_argument("--stab", type=float, default=0.1)
    ap.add_argument("--strokes", type=int, default=12)
    ap.add_argument("--dense", type=int, default=0, help="strokes already on the page (synthetic handwriting)")
    ap.add_argument("--zoom", type=float, default=1.0, help="multiple of fit width; 5 is the editor maximum")
    ap.add_argument("--wet", action="store_true", help="force the front-buffered wet-ink layer on")
    ap.add_argument("--manual", type=int, default=0, metavar="SECONDS",
                    help="inject nothing: listen for SECONDS while you write with the S Pen")
    ap.add_argument("--editor", action="store_true",
                    help="with --manual: measure the open notebook in the editor instead of the bench")
    ap.add_argument("--out", default="build/perf")
    args = ap.parse_args()
    d = Device(args.serial, args.out)
    os.makedirs(args.out, exist_ok=True)

    activity = "MainActivity" if args.editor else "InkBenchActivity"
    if not args.editor:
        d.sh("shell", "am", "force-stop", PKG)
        d.sh("shell", "am", "start", "-W", "-n", f"{PKG}/.lab.InkBenchActivity",
             "--es", "tool", args.tool, "--ef", "stab", str(args.stab),
             "--ei", "dense", str(args.dense), "--ef", "zoom", str(args.zoom), "--ez", "wet", str(args.wet).lower())
        time.sleep(1.5 + args.dense / 1500)  # let the first tiles render
    for _ in range(180 if args.manual else 1):  # manual runs: give the user time to open the page
        try:
            layer = layer_name(d, activity); break
        except RuntimeError:
            if not args.manual: raise
            time.sleep(1)
    else:
        raise RuntimeError(f"{activity} page not on screen")
    w, h = map(int, re.search(r"(\d+)x(\d+)", d.sh("shell", "wm", "size")).groups())
    d.sh("shell", "logcat", "-c")

    event_post, event_present, draw_p95, draw_max, post_ready, ready_present = [], [], [], [], [], []
    if args.manual:
        print(f"Write with the S Pen now, for {args.manual} s ...", file=sys.stderr, flush=True)
        rows, t_end = set(), time.time() + args.manual
        while time.time() < t_end:
            rows.update(sf_frames(d, layer))  # SurfaceFlinger keeps only ~128 frames: poll
            time.sleep(0.8)
        rows.update(sf_frames(d, layer))
        collect(d, rows, d.sh("shell", "logcat", "-d", "-s", "FolioLatency:D", "FolioPerf:D"),
                event_post, event_present, draw_p95, draw_max, post_ready, ready_present)
    else:
        for i in range(args.strokes):
            y = int(h * (0.25 + 0.5 * i / max(1, args.strokes - 1)))
            # ~700 px in 350 ms: brisk handwriting speed on a 10" screen.
            d.sh("shell", "input", "stylus", "swipe", str(int(w * 0.15)), str(y), str(int(w * 0.75)), str(y + 40), "350")
            time.sleep(0.4)
            # SurfaceFlinger keeps only the last 128 frames: read after each stroke.
            rows = sf_frames(d, layer)
            log = d.sh("shell", "logcat", "-d", "-s", "FolioLatency:D", "FolioPerf:D")
            d.sh("shell", "logcat", "-c")
            collect(d, rows, log, event_post, event_present, draw_p95, draw_max, post_ready, ready_present)

    res = {
        "source": ("S Pen in the editor" if args.editor else "S Pen in the bench") if args.manual else "injected",
        **({} if args.editor else {"tool": args.tool, "stabilization": args.stab, "dense": args.dense, "zoom": args.zoom}),
        "frames": len(event_post), "matched": len(event_present),
        "frame_draw_ms": {"p95_of_strokes": round(pct(draw_p95, .5), 1), "worst": max(draw_max, default=None)},
        "event_to_post_ms": {"p50": round(pct(event_post, .5), 1), "p95": round(pct(event_post, .95), 1)},
        "prediction_ahead_ms": {"p50": round(pct(PRED, .5), 1), "p95": round(pct(PRED, .95), 1)},
        "wet_event_to_submitted_ms": {"n": len(WET), "p50": round(pct(WET, .5), 1), "p95": round(pct(WET, .95), 1)},
        "post_to_gpu_ready_ms": {"p50": round(pct(post_ready, .5), 1), "p95": round(pct(post_ready, .95), 1)},
        "gpu_ready_to_present_ms": {"p50": round(pct(ready_present, .5), 1), "p95": round(pct(ready_present, .95), 1)},
        "event_to_present_ms": {"p50": round(pct(event_present, .5), 1), "p95": round(pct(event_present, .95), 1),
                                "mean": round(statistics.mean(event_present), 1) if event_present else None},
    }
    print(json.dumps(res, indent=1))
    name = "editor" if args.editor else f"{args.tool}-{args.stab}-d{args.dense}-z{args.zoom}"
    with open(os.path.join(args.out, f"latency-{'manual-' if args.manual else ''}{name}.json"), "w") as f:
        json.dump(res, f, indent=1)
    if not args.editor:
        d.sh("shell", "am", "force-stop", PKG)


if __name__ == "__main__":
    main()
