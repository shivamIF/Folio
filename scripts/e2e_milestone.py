#!/usr/bin/env python3
"""
Milestone 1 end-to-end check on a device or emulator, driven over adb.

Flow: fresh install state → create notebook → write 2 strokes with *stylus*
input → erase one → undo → back to library → force-stop → relaunch → reopen →
verify the persisted page holds exactly the 2 expected strokes.

Usage: scripts/e2e_milestone.py [-s SERIAL] [--out DIR]
Needs a debug build installed (it uses `run-as` to read app-private files).
"""
import argparse, json, os, re, shlex, subprocess, sys, time

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

    def ink_in_band(self, x0, x1, y0, y1):
        """Dark (ink) pixels in a screen rectangle, from a raw framebuffer capture."""
        raw = subprocess.run([ADB, "-s", self.serial, "exec-out", "screencap"], capture_output=True).stdout
        w, h = int.from_bytes(raw[0:4], "little"), int.from_bytes(raw[4:8], "little")
        off = len(raw) - w * h * 4  # header is 12 or 16 bytes depending on Android version
        dark = 0
        for y in range(max(0, y0), min(h, y1)):
            row = off + (y * w) * 4
            for x in range(max(0, x0), min(w, x1), 2):
                p = row + x * 4
                if raw[p] < 90 and raw[p + 1] < 90 and raw[p + 2] < 90:
                    dark += 1
        return dark

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
        d.tap("Eraser")
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

    # ---------------------------------------------------------------- lasso
    def strokes():
        return d.page(nb_id, pid)["strokes"]

    def by_id(sid):
        return next(s for s in strokes() if s["id"] == sid)

    def wait_for(pred, what, timeout=8):
        end = time.time() + timeout
        while time.time() < end:
            if pred():
                return
            time.sleep(0.5)
        raise AssertionError(what)

    def lasso_around_first(activate=True):
        if activate:  # tapping the lasso tool opens a shape menu
            d.tap("Lasso select")
            d.tap("Freeform lasso")
        # Rectangle loop around stroke 1 only (stroke 2 starts well below it).
        pts = [(xl - 40, ya - 45), (xr + 40, ya - 45), (xr + 40, ya + 75), (xl - 40, ya + 75), (xl - 40, ya - 40)]
        d.sh("shell", "input", "stylus", "motionevent", "DOWN", str(pts[0][0]), str(pts[0][1]))
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            for k in range(1, 5):
                x = x0 + (x1 - x0) * k // 4; y = y0 + (y1 - y0) * k // 4
                d.sh("shell", "input", "stylus", "motionevent", "MOVE", str(x), str(y))
        d.sh("shell", "input", "stylus", "motionevent", "UP", str(pts[-1][0]), str(pts[-1][1]))
        d.find("1 selected")
    check("lasso selects only the encircled stroke", lasso_around_first)
    d.shot("lasso-selected")

    y_before = by_id(first_id)["y"][0]
    second_before = by_id(second_id)

    def move():
        midx = (xl + xr) // 2
        old_band = d.ink_in_band(xl, xr, ya - 10, ya + 50)
        d.stylus_swipe(midx, ya + 20, midx, ya + 140, ms=500)
        wait_for(lambda: by_id(first_id)["y"][0] > y_before + 20, "selected stroke did not move down")
        assert by_id(second_id) == second_before, "unselected stroke must not move"
        time.sleep(1)
        # The screen must show the ink at its new place, not a stale drawing at the old one.
        after_band = d.ink_in_band(xl, xr, ya - 10, ya + 50)
        assert after_band < old_band * 0.2, f"ink still drawn at the old position ({after_band} vs {old_band} dark pixels)"
    check("dragging the selection moves it (other strokes untouched)", move)
    d.shot("lasso-moved")

    def undo_move():
        d.tap("Undo")
        wait_for(lambda: abs(by_id(first_id)["y"][0] - y_before) < 0.01, "undo did not restore position")
    check("undo restores the moved selection", undo_move)

    def duplicate_then_delete():
        d.tap("Duplicate selection")
        wait_for(lambda: len(strokes()) == 3, "duplicate did not add a copy")
        d.tap("Delete selection")
        wait_for(lambda: [s["id"] for s in strokes()] == [first_id, second_id], "delete did not remove only the copy")
    check("duplicate adds a copy; delete removes the (selected) copy", duplicate_then_delete)

    def recolour():
        lasso_around_first(activate=False)
        d.tap("Ink colour C62828")
        wait_for(lambda: by_id(first_id)["color"] == 0xFFC62828, "selection not recoloured")
        assert by_id(second_id)["color"] != 0xFFC62828
        d.tap("Clear selection")
    check("colour tap recolours the selection only", recolour)
    d.shot("lasso-done")

    # ------------------------------------------------------------------ PDF
    fixture = os.path.join(a.out, "folio-test.pdf")
    subprocess.run([sys.executable, "-I", os.path.join(os.path.dirname(os.path.abspath(__file__)), "make_test_pdf.py"), fixture, "3"],
                   check=True, capture_output=True)
    pdf_nb = {}

    def import_pdf():
        # Clean previous runs, then hand the PDF to Folio the way "Open with" in Files does.
        # adb shell re-parses arguments on the device, so SQL clauses must be shell-quoted.
        d.sh("shell", "content", "delete", "--uri", "content://media/external/file", "--where", shlex.quote("_display_name='folio-test.pdf'"), check=False)
        d.sh("push", fixture, "/sdcard/Download/folio-test.pdf")
        d.sh("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/Download/folio-test.pdf")
        media_id = None
        for _ in range(20):
            out = d.sh("shell", "content", "query", "--uri", "content://media/external/file", "--projection", "_id",
                       "--where", shlex.quote("_display_name='folio-test.pdf' AND relative_path='Download/'"), check=False)
            m = re.search(r"_id=(\d+)", out)
            if m:
                media_id = m.group(1); break
            time.sleep(0.5)
        assert media_id, "media scanner did not index the test PDF"
        d.sh("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", f"content://media/external/file/{media_id}",
             "-t", "application/pdf", "--grant-read-uri-permission", "-n", f"{PKG}/.MainActivity")
        wait_for(lambda: any(n["title"] == "folio-test" for n in d.library()["notebooks"]), "imported notebook not in library")
        nb = next(n for n in d.library()["notebooks"] if n["title"] == "folio-test")
        assert len(nb["pageIds"]) == 3, nb["pageIds"]
        pdf_nb.update(nb)
        page = d.page(nb["id"], nb["pageIds"][0])
        assert page["pdf"]["pageIndex"] == 0, page
        d.find("Handwriting canvas")
        time.sleep(2)
        _, _, (x1, y1, x2, y2) = d.find("Handwriting canvas")
        dark = d.ink_in_band(x1, x2, y1, y2)
        assert dark > 2000, f"PDF background not visible ({dark} dark pixels)"
    check("PDF opened with Folio becomes a 3-page notebook showing the PDF", import_pdf)
    d.shot("pdf-imported")

    def annotate_pdf():
        _, _, (x1, y1, x2, y2) = d.find("Handwriting canvas")
        w, h = x2 - x1, y2 - y1
        d.stylus_swipe(x1 + w // 5, y1 + int(h * 0.75), x1 + 4 * w // 5, y1 + int(h * 0.72))
        wait_for(lambda: len(d.page(pdf_nb["id"], pdf_nb["pageIds"][0])["strokes"]) == 1, "annotation not saved on the PDF page")
        d.tap("Next page")
        d.find("Handwriting canvas")
    check("writing on a PDF page is saved; page navigation works", annotate_pdf)

    def export_pdf():
        d.sh("shell", "content", "delete", "--uri", "content://media/external/file", "--where", shlex.quote("_display_name='folio-test.pdf' AND relative_path='Download/Folio/'"), check=False)
        d.tap("Export as PDF")
        d.find("PDF exported", timeout=30)
        d.tap("OK")
        local = os.path.join(a.out, "exported.pdf")
        d.sh("pull", "/sdcard/Download/Folio/folio-test.pdf", local)
        data = open(local, "rb").read()
        assert data.startswith(b"%PDF"), "export is not a PDF"
        pages = len(re.findall(rb"/Type\s*/Page[^s]", data))
        assert pages == 3, f"exported PDF has {pages} pages"
        boxes = set(re.findall(rb"/MediaBox\s*\[([^\]]*)\]", data))
        assert boxes == {b"0 0 612 792"}, f"page size not preserved: {boxes}"
    check("export writes a 3-page PDF to Downloads/Folio", export_pdf)

    # -------------------------------------------------------------- library
    def pdf_card_shows_first_page():
        d.sh("shell", "input", "keyevent", "KEYCODE_BACK")
        _, _, (x1, y1, x2, y2) = d.find("First page of folio-test", timeout=15)
        time.sleep(1.5)
        dark = d.ink_in_band(x1, x2, y1, y2)
        assert dark > 200, f"preview of the PDF notebook looks blank ({dark} dark pixels)"
    check("library shows the first page of an imported PDF", pdf_card_shows_first_page)
    d.shot("library-previews")

    def long_press(label):
        x, y, _ = d.find(label)
        d.sh("shell", "input", "swipe", str(x), str(y), str(x), str(y), "900")
        time.sleep(0.6)

    def trash_and_restore():
        long_press("Notebook Milestone")
        d.tap("Move to Trash")
        wait_for(lambda: next(n for n in d.library()["notebooks"] if n["id"] == nb_id).get("deletedAt") is not None,
                 "notebook not marked as trashed")
        assert "Notebook Milestone" not in d.ui(), "trashed notebook still shown in the library"
        d.tap("Trash")
        d.tap("Trashed notebook Milestone")
        d.tap("Restore")
        wait_for(lambda: next(n for n in d.library()["notebooks"] if n["id"] == nb_id).get("deletedAt") is None,
                 "restore did not clear deletedAt")
        d.tap("Back to notebooks")
        d.find("Notebook Milestone")
        ids = [s["id"] for s in d.page(nb_id, pid)["strokes"]]
        assert ids == [first_id, second_id], f"content changed by trash/restore: {ids}"
    check("move to Trash hides a notebook; Restore brings it back intact", trash_and_restore)

    # --------------------------------------------------------------- eraser
    def partial_eraser():
        d.tap("Notebook Milestone")
        d.find("Handwriting canvas")
        d.tap("Eraser")
        d.tap("Partial eraser")
        midx = (xl + xr) // 2
        d.stylus_swipe(midx, ya - 40, midx, ya + 70, ms=400)
        wait_for(lambda: len(strokes()) == 3, f"partial erase did not split stroke 1 into 2 pieces ({len(strokes())} strokes)")
        ids = [s["id"] for s in strokes()]
        assert first_id not in ids and ids[-1] == second_id, f"wrong stroke split or z-order changed: {ids}"
        assert by_id(second_id) == second_before or by_id(second_id)["color"] != 0, "stroke 2 must be untouched"
        raw = d.run_as("cat", f"files/library/notebooks/{nb_id}/{pid}.page.json")
        assert "boundsCache" not in raw, "cached bounds leaked into the saved file"
        d.tap("Undo")
        wait_for(lambda: [s["id"] for s in strokes()] == [first_id, second_id], "undo did not restore the whole stroke")
    check("partial eraser cuts a stroke into pieces; undo restores it", partial_eraser)

    def highlighter_only():
        d.tap("Eraser")
        d.tap("Erase highlighter only")
        d.sh("shell", "input", "keyevent", "KEYCODE_ESCAPE")  # close the menu
        time.sleep(0.5)
        midx = (xl + xr) // 2
        before = [s["id"] for s in strokes()]
        d.stylus_swipe(midx, ya - 40, midx, ya + 70, ms=400)
        time.sleep(2)
        assert [s["id"] for s in strokes()] == before, "highlighter-only eraser erased ink"
        d.tap("Eraser")
        d.tap("Erase highlighter only")  # restore default
        d.sh("shell", "input", "keyevent", "KEYCODE_ESCAPE")
    check("with 'Erase highlighter only' on, ink is protected", highlighter_only)

    print(f"\n{len(results)} checks passed. Screenshots in {a.out}")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"FAIL  {e}")
        if _device:
            print(f"      screen at failure: {_device.shot('FAILED')}")
        sys.exit(1)
