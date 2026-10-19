#!/usr/bin/env python3
"""Summarise `dumpsys gfxinfo <pkg> framestats` (per-frame stage times, ms). Usage: framestats.py SERIAL"""
import os, subprocess, sys, statistics
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
out = subprocess.run([ADB, "-s", sys.argv[1], "shell", "dumpsys", "gfxinfo", "app.folio.notes", "framestats"], capture_output=True, text=True).stdout
lines = out.splitlines()
hdr = next(i for i, l in enumerate(lines) if l.startswith("Flags,"))
cols = lines[hdr].strip(",").split(",")
rows = []
for l in lines[hdr + 1:]:
    if not l or not l[0].isdigit():
        break
    v = l.strip(",").split(",")
    r = dict(zip(cols, map(int, v)))
    if r["Flags"] == 0:
        rows.append(r)
def stage(a, b):
    return [(r[b] - r[a]) / 1e6 for r in rows if r[a] and r[b]]
def show(name, xs):
    xs = sorted(xs)
    if xs: print("%-22s p50=%6.1f p90=%6.1f max=%6.1f" % (name, xs[len(xs)//2], xs[int(len(xs)*.9)], xs[-1]))
print("frames", len(rows))
show("total (vsync->done)", stage("IntendedVsync", "FrameCompleted"))
show("ui draw", stage("PerformTraversalsStart", "SyncQueued"))
show("sync", stage("SyncStart", "IssueDrawCommandsStart"))
show("issue draw cmds", stage("IssueDrawCommandsStart", "SwapBuffers"))
show("swap->completed", stage("SwapBuffers", "FrameCompleted"))
if "DequeueBufferDuration" in cols: show("dequeueBuffer", [r["DequeueBufferDuration"] / 1e6 for r in rows])
if "GpuCompleted" in cols: show("gpu (issue->gpu done)", stage("IssueDrawCommandsStart", "GpuCompleted"))
