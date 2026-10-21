#!/usr/bin/env python3
"""
End-to-end check of the page navigator (sidebar, page menu, single-page export), driven over adb.

Safe on a real tablet: it never clears app data. It works only in a notebook it creates
("Navigator check <time>"), moves that notebook to Trash at the end (recoverable), and
deletes the one file it saves to the device.

Flow: create notebook → 3 pages with different ink → sidebar shows live thumbnails →
tap navigates → editing refreshes only that thumbnail → duplicate → move (+ Undo) →
add before → erase (+ Undo; hidden on empty pages) → delete (confirm + Undo) →
drag to reorder → export PNG/JPEG (share) and PDF (save via the system picker) →
hide/show keeps the page → reopen keeps the order.

Usage: scripts/e2e_page_navigator.py [-s SERIAL] [--out DIR]
Needs this repo's debug build installed (it reads saved files through `run-as`).
Don't touch the device while it runs.
"""
import argparse, importlib.util, json, os, re, struct, subprocess, sys, time, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("e2e_milestone", os.path.join(HERE, "e2e_milestone.py"))
m = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(m)
PKG, ADB = m.PKG, m.ADB

UNITS_PER_MM = 1000 / 210


def raster_size(w, h, dpi, cap=4096):
    """Same as PageExportSpec.rasterSize."""
    px_per_unit = dpi / (UNITS_PER_MM * 25.4)
    k = min(1.0, cap / (max(w, h) * px_per_unit))
    return max(1, round(w * px_per_unit * k)), max(1, round(h * px_per_unit * k))


class Nav(m.Device):
    def nodes(self):
        out = []
        for node in re.findall(r'<node [^>]*>', self.ui()):
            text = re.search(r' text="([^"]*)"', node).group(1)
            desc = re.search(r' content-desc="([^"]*)"', node).group(1)
            x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
            out.append((text, desc, (x1, y1, x2, y2)))
        return out

    def find_text(self, label, timeout=10):
        """Like find(), but only nodes whose *text* is label (e.g. the snackbar's Undo, not the toolbar's)."""
        end = time.time() + timeout
        while time.time() < end:
            for text, _, b in self.nodes():
                if text == label:
                    return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2, b
            time.sleep(0.4)
        raise AssertionError(f"text '{label}' not found")

    def tap_text(self, label, timeout=10):
        x, y, _ = self.find_text(label, timeout)
        self.sh("shell", "input", "tap", str(x), str(y))
        time.sleep(0.6)

    def tap_any(self, *labels, timeout=10):
        end = time.time() + timeout
        while time.time() < end:
            for text, desc, b in self.nodes():
                if text in labels or desc in labels:
                    self.sh("shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
                    time.sleep(0.8)
                    return
            time.sleep(0.4)
        raise AssertionError(f"none of {labels} found")

    def find_any(self, *labels, timeout=10):
        end = time.time() + timeout
        while time.time() < end:
            for text, desc, b in self.nodes():
                if text in labels or desc in labels:
                    return text or desc
            time.sleep(0.4)
        raise AssertionError(f"none of {labels} found")

    def absent(self, label):
        return all(label not in (t, d) for t, d, _ in self.nodes())

    def status(self):
        """(page number, page count) from the top bar."""
        for text, _, _ in self.nodes():
            g = re.match(r"Page (\d+) of (\d+)", text)
            if g:
                return int(g.group(1)), int(g.group(2))
        raise AssertionError("page status not shown")

    def thumbs(self):
        """Visible page thumbnails: {number: bounds}."""
        out = {}
        for _, desc, b in self.nodes():
            g = re.fullmatch(r"Page (\d+)", desc)
            if g and b[2] > b[0] and b[3] > b[1]:  # off-screen items report empty bounds
                out[int(g.group(1))] = b
        return out

    def reveal(self, number, tries=12):
        """Scrolls the sidebar until page [number]'s thumbnail is fully on screen; returns its bounds."""
        for _ in range(tries):
            t = self.thumbs()
            if not t:
                raise AssertionError("no thumbnails on screen")
            full = max(b[3] - b[1] for b in t.values())
            b = t.get(number)
            if b and b[3] - b[1] >= full - 3:
                return b
            any_b = next(iter(t.values()))
            x = (any_b[0] + any_b[2]) // 2
            mid = (min(v[1] for v in t.values()) + max(v[3] for v in t.values())) // 2
            dy = 260 if (b is None and number < min(t)) or (b is not None and b[1] < mid) else -260
            self.sh("shell", "input", "swipe", str(x), str(mid), str(x), str(mid + dy), "350")
            time.sleep(0.7)
        raise AssertionError(f"page {number} thumbnail can't be brought into view (visible: {self.thumbs()})")

    def tap_page(self, number):
        b = self.reveal(number)
        # Upper part of the thumbnail: clear of the ⋮ in its bottom corner.
        self.sh("shell", "input", "tap", str((b[0] + b[2]) // 2), str(b[1] + (b[3] - b[1]) // 3))
        time.sleep(0.7)

    def page_menu(self, number, item):
        self.reveal(number)
        self.tap(f"More options for page {number}")
        self.tap_text(item)

    def finger_drag(self, x0, y0, x1, y1, hold_s=0.9, steps=24):
        """Touch, hold still (long press), then drag and release."""
        self.sh("shell", "input", "touchscreen", "motionevent", "DOWN", str(x0), str(y0))
        time.sleep(hold_s)
        for k in range(1, steps + 1):
            self.sh("shell", "input", "touchscreen", "motionevent", "MOVE", str(x0 + (x1 - x0) * k // steps), str(y0 + (y1 - y0) * k // steps))
        time.sleep(0.3)
        self.sh("shell", "input", "touchscreen", "motionevent", "UP", str(x1), str(y1))
        time.sleep(0.8)

    def thumb_ink(self, number):
        """Ink pixels (not paper or ruling) in the top 70 % of a page's thumbnail (the ⋮ sits in the bottom corner)."""
        x1, y1, x2, y2 = self.reveal(number)
        return self.ink_pixels(x1 + 6, x2 - 6, y1 + 6, y1 + int((y2 - y1) * 0.7))

    def ink_pixels(self, x0, x1, y0, y1):
        """Saturated or dark pixels in a screen rectangle: any pen colour, but not white paper or pale ruling."""
        raw = subprocess.run([ADB, "-s", self.serial, "exec-out", "screencap"], capture_output=True).stdout
        w, h = int.from_bytes(raw[0:4], "little"), int.from_bytes(raw[4:8], "little")
        off = len(raw) - w * h * 4
        n = 0
        for y in range(max(0, y0), min(h, y1)):
            row = off + (y * w) * 4
            for x in range(max(0, x0), min(w, x1), 2):
                p = row + x * 4
                r, g, b = raw[p], raw[p + 1], raw[p + 2]
                if max(r, g, b) - min(r, g, b) > 90 or max(r, g, b) < 90:
                    n += 1
        return n


def png_size(data):
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    return struct.unpack(">II", data[16:24])


def jpeg_size(data):
    assert data[:3] == b"\xff\xd8\xff", "not a JPEG"
    i = 2
    while i < len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xC0, 0xC1, 0xC2):
            h, w = struct.unpack(">HH", data[i + 5:i + 9])
            return w, h
        i += 2 + struct.unpack(">H", data[i + 2:i + 4])[0]
    raise AssertionError("no JPEG size")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    ap.add_argument("--out", default="build/e2e-navigator")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    d = Nav(a.serial, a.out)
    m._device = d
    results = []

    def check(name, fn):
        fn()
        if ' text="Problem"' in d.ui():
            d.shot("error-" + re.sub(r"\W+", "-", name)[:40])
            raise AssertionError(f"error dialog shown after: {name}")
        results.append(name)
        print(f"PASS  {name}", flush=True)

    def wait_for(pred, what, timeout=10):
        end = time.time() + timeout
        while time.time() < end:
            if pred():
                return
            time.sleep(0.4)
        raise AssertionError(what)

    title = "Navigator check " + time.strftime("%H%M%S")
    # Close anything a previous run left open (a share sheet or file picker), then start fresh.
    for key in ("KEYCODE_BACK", "KEYCODE_BACK", "KEYCODE_HOME"):
        d.sh("shell", "input", "keyevent", key)
        time.sleep(0.4)
    d.sh("shell", "am", "force-stop", PKG)
    d.launch()

    def meta():
        return next(n for n in d.library()["notebooks"] if n["title"] == title)

    def stroke_ids(pid):
        return [s["id"] for s in d.page(meta()["id"], pid)["strokes"]]

    # ------------------------------------------------------------ set up
    def create():
        # The library's "New notebook" button only shows while the library is empty;
        # otherwise a notebook is made from the header's New menu.
        try:
            d.find("New notebook", timeout=2)
            d.tap("New notebook")
        except AssertionError:
            d.tap("New")
            d.tap("Notebook")
        x, y, _ = d.find("Title")
        d.sh("shell", "input", "tap", str(x), str(y))
        d.sh("shell", "input", "text", title.replace(" ", "%s"))
        time.sleep(0.5)
        d.sh("shell", "input", "keyevent", "KEYCODE_ENTER")
        d.find("Handwriting canvas")
    check("create a test notebook", create)

    def canvas_box():
        return d.find("Handwriting canvas")[2]

    def write(n):
        x1, y1, x2, y2 = canvas_box()
        w, h = x2 - x1, y2 - y1
        for i in range(n):
            y = y1 + int(h * (0.15 + 0.1 * i))
            d.stylus_swipe(x1 + w // 6, y, x2 - w // 6, y + 30)

    def three_pages():
        write(1)
        d.tap("Add page after this one")
        wait_for(lambda: d.status() == (2, 2), "page 2 not opened")
        write(3)
        d.tap("Add page after this one")
        wait_for(lambda: d.status() == (3, 3), "page 3 not opened")
        ids = meta()["pageIds"]
        wait_for(lambda: len(stroke_ids(ids[0])) == 1 and len(stroke_ids(ids[1])) == 3, "strokes not saved")
    check("three pages: 1 stroke, 3 strokes, blank", three_pages)
    pages0 = meta()["pageIds"]

    # ------------------------------------------------------------ sidebar
    def open_sidebar():
        if not d.absent("Hide pages"):  # remembered open from an earlier session
            d.tap("Hide pages")
            d.find("Show pages")
            time.sleep(0.8)
        before = canvas_box()
        d.tap("Show pages")
        d.find("Hide pages")
        for n in (3, 2, 1):
            d.reveal(n)
        after = canvas_box()
        assert after[2] - after[0] < before[2] - before[0], f"canvas didn't make room: {before} → {after}"
        assert d.status() == (3, 3), "opening the sidebar changed the page"
    check("sidebar opens beside the canvas with a thumbnail per page", open_sidebar)

    def live_thumbnails():
        wait_for(lambda: d.thumb_ink(1) > 30, "page 1's thumbnail doesn't show its ink", timeout=15)
        one = d.thumb_ink(1)
        wait_for(lambda: d.thumb_ink(2) > one * 2, "page 2's thumbnail doesn't show its 3 strokes", timeout=15)
        assert d.thumb_ink(3) < 15, f"blank page thumbnail has ink ({d.thumb_ink(3)})"
    check("thumbnails show each page's real content", live_thumbnails)
    d.shot("sidebar")

    def tap_navigates():
        d.tap_page(1)
        wait_for(lambda: d.status() == (1, 3), "tapping page 1 didn't open it")
        d.tap_page(2)
        wait_for(lambda: d.status() == (2, 3), "tapping page 2 didn't open it")
    check("tapping a thumbnail opens that page", tap_navigates)

    def thumb_follows_edits():
        d.tap_page(3)
        wait_for(lambda: d.status() == (3, 3), "page 3")
        p2 = d.thumb_ink(2)
        write(2)
        wait_for(lambda: d.thumb_ink(3) > 30, "page 3's thumbnail didn't refresh after writing", timeout=8)
        assert abs(d.thumb_ink(2) - p2) <= 4, "another page's thumbnail changed"
    check("writing refreshes only that page's thumbnail", thumb_follows_edits)

    # ------------------------------------------------------------ page menu
    def duplicate():
        d.page_menu(1, "Duplicate page")
        wait_for(lambda: len(meta()["pageIds"]) == 4, "no copy added")
        ids = meta()["pageIds"]
        assert ids[0] == pages0[0] and ids[2:] == pages0[1:], f"copy not right after page 1: {ids}"
        copy = d.page(meta()["id"], ids[1])["strokes"]
        orig = d.page(meta()["id"], ids[0])["strokes"]
        assert len(copy) == len(orig) == 1 and copy[0]["id"] != orig[0]["id"], "copy should hold new strokes with new ids"
        assert copy[0].get("pts") == orig[0].get("pts"), "copy's ink differs"
        wait_for(lambda: d.status() == (2, 4), "the copy wasn't opened")
    check("Duplicate page inserts an independent copy after it", duplicate)

    def move_and_undo():
        ids = meta()["pageIds"]
        d.page_menu(2, "Move page…")
        d.tap_text("To the end")
        d.tap_text("Move")
        wait_for(lambda: meta()["pageIds"] == [ids[0], ids[2], ids[3], ids[1]], "move to the end failed")
        assert d.status() == (4, 4), "the open page should keep its place in the list"
        d.find_text("Page 2 moved to position 4")
        d.tap_text("Undo")
        wait_for(lambda: meta()["pageIds"] == ids, "Undo didn't put the page back")
    check("Move page… reorders; Undo puts it back", move_and_undo)

    def add_before():
        ids = meta()["pageIds"]
        d.page_menu(1, "Add page before")
        wait_for(lambda: len(meta()["pageIds"]) == 5, "no page added")
        new = meta()["pageIds"]
        assert new[1:] == ids, f"new page should be first: {new}"
        assert stroke_ids(new[0]) == [], "new page isn't blank"
        wait_for(lambda: d.status() == (1, 5), "new page not opened")
    check("Add page before inserts a blank page in front", add_before)

    def erase_hidden_on_blank():
        d.tap("More options for page 1")
        d.find_text("Add page after")
        assert d.absent("Erase page"), "Erase page offered on a blank page"
        d.sh("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.6)
    check("Erase page isn't offered for a blank page", erase_hidden_on_blank)

    def erase_and_undo():
        pid = meta()["pageIds"][1]
        before = stroke_ids(pid)
        d.page_menu(2, "Erase page")
        wait_for(lambda: stroke_ids(pid) == [], "page wasn't erased")
        d.find_text("Page 2 erased")
        d.tap_text("Undo")
        wait_for(lambda: stroke_ids(pid) == before, "Undo didn't bring the ink back")
    check("Erase page clears ink as one step; Undo restores it", erase_and_undo)

    def delete_and_undo():
        ids = meta()["pageIds"]
        d.page_menu(1, "Delete page")
        d.find_text("Delete page 1?")
        d.tap_text("Cancel")
        assert meta()["pageIds"] == ids, "Cancel still deleted"
        d.page_menu(1, "Delete page")
        d.tap_text("Delete")
        wait_for(lambda: meta()["pageIds"] == ids[1:], "page not deleted")
        files = d.run_as("ls", f"files/library/notebooks/{meta()['id']}")
        assert ids[0] not in files, "deleted page's file is still there"
        d.find_text("Page 1 deleted")
        d.tap_text("Undo")
        wait_for(lambda: meta()["pageIds"] == ids, "Undo didn't restore the page in its place")
    check("Delete page asks first, removes the page, and Undo restores it", delete_and_undo)
    d.shot("after-menu-actions")

    def drag_reorder():
        ids = meta()["pageIds"]
        b1 = d.reveal(1)
        b2 = d.thumbs()[2]
        x = (b2[0] + b2[2]) // 2
        d.finger_drag(x, (b2[1] + b2[3]) // 2, x, b1[1] + 8)
        expect = [ids[1], ids[0]] + ids[2:]
        wait_for(lambda: meta()["pageIds"] == expect, f"drag didn't move page 2 above page 1: {meta()['pageIds']}")
        d.tap_text("Undo")
        wait_for(lambda: meta()["pageIds"] == ids, "Undo after drag failed")
    check("long-press and drag a thumbnail reorders pages", drag_reorder)

    # ------------------------------------------------------------ export
    def last_share_file(ext):
        # One name per line: export names contain spaces ("Navigator check 142904 – page 2.png").
        out = d.run_as("sh", "-c", "'ls -t cache/exports/*/*." + ext + "'").splitlines()
        assert out and not out[0].startswith("ls:"), f"no shared .{ext} file"
        return out[0]

    def page_size(pid):
        p = d.page(meta()["id"], pid)
        return p["width"], p["height"]

    def export_share(fmt_label, ext, dpi_label, dpi):
        pid = meta()["pageIds"][1]
        d.page_menu(2, "Export page…")
        d.tap_text(fmt_label)
        if dpi_label:
            d.tap_text(dpi_label)
        d.tap_text("Share")
        # AOSP shows the chooser title; One UI's sheet shows the item count and its targets.
        d.find_any("Share page", "1 item", "Quick Share", timeout=20)
        d.shot("share-sheet-" + ext)
        d.sh("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.8)
        path = last_share_file(ext)
        assert path.endswith(f"– page 2.{ext}"), path
        # adb joins the arguments into one shell line, so the spaced path must be quoted.
        data = subprocess.run([ADB, "-s", a.serial, "exec-out", "run-as", PKG, "cat", f"'{path}'"], capture_output=True).stdout
        size = png_size(data) if ext == "png" else jpeg_size(data)
        w, h = page_size(pid)
        assert size == raster_size(w, h, dpi), f"{ext} is {size}, expected {raster_size(w, h, dpi)} for {w}×{h} at {dpi} dpi"
        local = os.path.join(a.out, f"page2.{ext}")
        open(local, "wb").write(data)
        return local

    check("Export page… → PNG at 300 dpi → share sheet, right pixel size", lambda: export_share("PNG image", "png", "300 dpi", 300))
    check("Export page… → JPEG at 150 dpi → share sheet, right pixel size", lambda: export_share("JPEG image", "jpg", None, 150))

    saved_pdf = {}

    def export_pdf_save():
        pid = meta()["pageIds"][1]
        name = f"{title} – page 2.pdf"
        d.page_menu(2, "Export page…")
        d.tap_text("PDF")
        d.tap_text("Save to device")
        time.sleep(2.5)
        d.shot("save-picker")
        d.tap_any("SAVE", "Save", "Done", timeout=15)
        wait_for(lambda: bool(d.sh("shell", "find", "/sdcard/", "-name", f"'{name}'", check=False).strip()), "saved PDF not found", timeout=20)
        path = d.sh("shell", "find", "/sdcard/", "-name", f"'{name}'", check=False).split("\n")[0].strip()
        saved_pdf["path"] = path
        local = os.path.join(a.out, "page2.pdf")
        d.sh("pull", path, local)
        data = open(local, "rb").read()
        assert data.startswith(b"%PDF"), "not a PDF"
        assert len(re.findall(rb"/Type\s*/Page[^s]", data)) == 1, "PDF should have exactly one page"
        w, h = page_size(pid)
        box = re.search(rb"/MediaBox\s*\[\s*0\s+0\s+(\d+)\s+(\d+)\s*\]", data)
        assert box and (int(box.group(1)), int(box.group(2))) == (round(w * 595.2756 / 1000), round(h * 595.2756 / 1000)), f"page size: {box and box.groups()}"
        d.find_text("Page 2 saved as PDF")
    check("Export page… → PDF → Save to device writes a one-page PDF at the page's size", export_pdf_save)

    def export_cancel():
        d.page_menu(2, "Export page…")
        d.tap_text("PNG image")
        d.tap_text("Save to device")
        time.sleep(2.5)
        d.sh("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.0)
        d.find("Handwriting canvas")
        assert d.absent("Exporting page…"), "progress left showing after cancelling"
    check("cancelling the file picker writes nothing and shows no error", export_cancel)

    # ------------------------------------------------------------ hide / show, reopen
    def hide_show():
        d.tap_page(2)
        wait_for(lambda: d.status() == (2, 5), "page 2")
        pid = meta()["pageIds"][1]
        before = stroke_ids(pid)
        wide = canvas_box()
        d.tap("Hide pages")
        wait_for(lambda: not d.thumbs(), "sidebar still shown")
        assert canvas_box()[2] - canvas_box()[0] > wide[2] - wide[0], "canvas didn't widen"
        assert d.status() == (2, 5)
        d.tap("Show pages")
        d.reveal(2)
        assert d.status() == (2, 5) and stroke_ids(pid) == before, "page or content changed"
    check("hiding and showing the sidebar keeps the page and its content", hide_show)

    def reopen():
        ids = meta()["pageIds"]
        d.sh("shell", "input", "keyevent", "KEYCODE_BACK")
        d.find(f"Notebook {title}")
        d.sh("shell", "am", "force-stop", PKG)
        d.launch()
        d.tap(f"Notebook {title}")
        d.find("Handwriting canvas")
        d.reveal(1)  # the sidebar is remembered as open
        assert meta()["pageIds"] == ids
    check("page order and the sidebar survive a restart", reopen)
    d.shot("reopened")

    # ------------------------------------------------------------ clean up
    def clean_up():
        if saved_pdf.get("path"):
            d.sh("shell", "rm", f"'{saved_pdf['path']}'", check=False)
        d.sh("shell", "input", "keyevent", "KEYCODE_BACK")
        # This run's notebook, and any left by an interrupted earlier run.
        leftovers = [n["title"] for n in d.library()["notebooks"]
                     if re.fullmatch(r"Navigator check \d{6}", n["title"]) and not n.get("deletedAt")]
        for t in leftovers:
            x, y, _ = d.find(f"Notebook {t}")
            d.sh("shell", "input", "swipe", str(x), str(y), str(x), str(y), "900")
            time.sleep(0.6)
            d.tap("Move to Trash")
            wait_for(lambda: next(n for n in d.library()["notebooks"] if n["title"] == t).get("deletedAt") is not None, f"{t} not trashed")
    check("test notebook moved to Trash; saved test file removed", clean_up)

    print(f"\n{len(results)} checks passed")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        if m._device:
            print("screenshot:", m._device.shot("failure"))
        print(f"FAIL  {e}")
        sys.exit(1)
