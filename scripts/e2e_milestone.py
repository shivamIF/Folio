#!/usr/bin/env python3
"""
Milestone 1 end-to-end check on a device or emulator, driven over adb.

Flow: fresh install state → create notebook → write 2 strokes with *stylus*
input → erase one → undo → back to library → force-stop → relaunch → reopen →
verify the persisted page holds exactly the 2 expected strokes.

Usage: scripts/e2e_milestone.py [-s SERIAL] [--out DIR]
Needs a debug build installed (it uses `run-as` to read app-private files).
"""
import argparse, json, os, re, subprocess, sys, time

PKG = "app.folio.notes"
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
_device = None


class Device:
    def __init__(self, serial, out):
        self.serial, self.out, self.step = serial, out, 0

    def sh(self, *args, check=True):
        cmd = [ADB] + (["-s", self.serial] if self.serial else []) + list(args)
        r = subprocess.run(cmd, capture_output=True, text=True)
        if check and r.returncode != 0:
            raise RuntimeError(f"{cmd}: {r.stderr.strip()}")
        return r.stdout

    def ui(self):
        self.sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        return self.sh("exec-out", "cat", "/sdcard/ui.xml")

    def find(self, label, timeout=10):
        """Centre of the first node whose text or content-desc equals label."""
        end = time.time() + timeout
        while time.time() < end:
            xml = self.ui()
            for node in re.findall(r'<node [^>]*>', xml):
                text = re.search(r' text="([^"]*)"', node).group(1)
                desc = re.search(r' content-desc="([^"]*)"', node).group(1)
                if label in (text, desc):
                    x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
                    return (x1 + x2) // 2, (y1 + y2) // 2, (x1, y1, x2, y2)
            time.sleep(0.5)
        raise AssertionError(f"UI element '{label}' not found")

    def tap(self, label):
        x, y, _ = self.find(label)
        self.sh("shell", "input", "tap", str(x), str(y))
        time.sleep(0.6)

    def stylus_swipe(self, x1, y1, x2, y2, ms=400):
        self.sh("shell", "input", "stylus", "swipe", *map(str, (x1, y1, x2, y2, ms)))
        time.sleep(0.4)

    def shot(self, name):
        self.step += 1
        path = os.path.join(self.out, f"{self.step:02d}-{name}.png")
        with open(path, "wb") as f:
            f.write(subprocess.run([ADB, "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout)
        return path

    def run_as(self, *args):
        return self.sh("shell", "run-as", PKG, *args)

    def library(self):
        return json.loads(self.run_as("cat", "files/library/library.json"))

    def page(self, nb_id, page_id):
        return json.loads(self.run_as("cat", f"files/library/notebooks/{nb_id}/{page_id}.page.json"))

    def launch(self):
        self.sh("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")
        time.sleep(1.5)


def wait_strokes(d, nb, pid, expected, timeout=8):
    end = time.time() + timeout
    n = None
    while time.time() < end:
        n = len(d.page(nb, pid)["strokes"])
        if n == expected:
            return
        time.sleep(0.5)
    raise AssertionError(f"expected {expected} saved strokes, found {n}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    global _device
    d = _device = Device(a.serial, a.out)
    results = []

    def check(name, fn):
        fn()
        # An error dialog anywhere means the step didn't really succeed.
        if ' text="Problem"' in d.ui():
            raise AssertionError(f"error dialog shown after: {name}")
        results.append(name)
        print(f"PASS  {name}")

    d.sh("shell", "pm", "clear", PKG)
    d.launch()
    check("library shows empty state", lambda: d.find("No notebooks yet. Tap New notebook to start writing."))

    def create():
        d.tap("New notebook")
        x, y, _ = d.find("Title")
        d.sh("shell", "input", "tap", str(x), str(y))
        d.sh("shell", "input", "text", "Milestone")
        time.sleep(0.5)
        # Keyboard "Done" confirms; this also works when the keyboard covers the dialog buttons.
        d.sh("shell", "input", "keyevent", "KEYCODE_ENTER")
        d.find("Handwriting canvas")
    check("create notebook opens editor", create)
    d.shot("editor-empty")

    lib = d.library()
    nb = lib["notebooks"][0]
    nb_id, pid = nb["id"], nb["pageIds"][0]
    assert nb["title"] == "Milestone", nb["title"]

    _, _, (cx1, cy1, cx2, cy2) = d.find("Handwriting canvas")
    w, h = cx2 - cx1, cy2 - cy1
    ya, yb = cy1 + int(h * 0.30), cy1 + int(h * 0.45)
    xl, xr = cx1 + int(w * 0.2), cx1 + int(w * 0.8)

    def write():
        d.stylus_swipe(xl, ya, xr, ya + 40)
        d.stylus_swipe(xl, yb, xr, yb - 40)
        wait_strokes(d, nb_id, pid, 2)
    check("two stylus strokes are written and autosaved", write)
    d.shot("two-strokes")

    saved = d.page(nb_id, pid)["strokes"]
    first_id, second_id = saved[0]["id"], saved[1]["id"]
    s0 = saved[0]
    assert len(s0["x"]) >= 5 and len(s0["x"]) == len(s0["pressure"]) == len(s0["t"]), "per-point channels stored"
    assert s0["tool"] == "FOUNTAIN"

    def erase():
        d.tap("Stroke eraser")
        midx = (xl + xr) // 2
        d.stylus_swipe(midx, ya - 30, midx, ya + 60, ms=300)
        wait_strokes(d, nb_id, pid, 1)
        ids = [s["id"] for s in d.page(nb_id, pid)["strokes"]]
        assert ids == [second_id], f"wrong stroke erased: {ids}"
    check("eraser removes only the crossed stroke", erase)
    d.shot("after-erase")

    def undo():
        d.tap("Undo")
        wait_strokes(d, nb_id, pid, 2)
        ids = [s["id"] for s in d.page(nb_id, pid)["strokes"]]
        assert ids == [first_id, second_id], f"undo did not restore order: {ids}"
    check("undo restores the erased stroke in its original z-order", undo)

    def reopen():
        d.sh("shell", "input", "keyevent", "KEYCODE_BACK")
        d.find("Notebook Milestone")
        d.sh("shell", "am", "force-stop", PKG)
        d.launch()
        d.tap("Notebook Milestone")
        d.find("Handwriting canvas")
        d.find("Undo")
        ids = [s["id"] for s in d.page(nb_id, pid)["strokes"]]
        assert ids == [first_id, second_id], ids
    check("content survives back, force-stop and relaunch", reopen)
    d.shot("reopened")

    print(f"\n{len(results)} checks passed. Screenshots in {a.out}")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"FAIL  {e}")
        if _device:
            print(f"      screen at failure: {_device.shot('FAILED')}")
        sys.exit(1)
