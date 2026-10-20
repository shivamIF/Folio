#!/usr/bin/env python3
"""
End-to-end check of continuous vertical scrolling (ADR-10), driven over adb.

Safe on a real tablet: it never clears app data, works only in a notebook it creates
("Scroll check <time>") and moves that notebook to Trash at the end (recoverable).

Checks: a finger drag scrolls from one page into the next and the sidebar highlight follows;
a stroke that starts on page 1 and crosses into page 2 is saved whole on page 1; Undo reverts
the notebook's latest edit across pages.

Usage: scripts/e2e_continuous_scroll.py [-s SERIAL] [--out DIR]
Needs this repo's debug build installed (it reads saved files through `run-as`).
"""
import argparse, importlib.util, os, re, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("e2e_page_navigator", os.path.join(HERE, "e2e_page_navigator.py"))
nav = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(nav)
m, PKG = nav.m, nav.PKG


class Scroll(nav.Nav):
    def highlighted(self):
        """Page numbers whose sidebar thumbnail is marked selected."""
        out = []
        for node in re.findall(r'<node [^>]*>', self.ui()):
            g = re.search(r' content-desc="Page (\d+)"', node)
            if g and ' selected="true"' in node:
                out.append(int(g.group(1)))
        return out

    def drag(self, x, y0, y1, steps=20):
        """A slow finger drag that stops before lifting (no fling)."""
        self.sh("shell", "input", "touchscreen", "motionevent", "DOWN", str(x), str(y0))
        for k in range(1, steps + 1):
            self.sh("shell", "input", "touchscreen", "motionevent", "MOVE", str(x), str(y0 + (y1 - y0) * k // steps))
        time.sleep(0.4)
        self.sh("shell", "input", "touchscreen", "motionevent", "UP", str(x), str(y1))
        time.sleep(0.8)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    ap.add_argument("--out", default="build/e2e-scroll")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    d = Scroll(a.serial, a.out)
    m._device = d
    results = []

    def check(name, fn):
        fn()
        if ' text="Problem"' in d.ui():
            d.shot("error")
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

    title = "Scroll check " + time.strftime("%H%M%S")
    d.launch()  # no force-stop: a cold debug build can trip ANRs on a slow emulator
    if d.find_any("New notebook", "Back to library", timeout=15) == "Back to library":  # reopened inside the last notebook
        d.tap("Back to library")

    def meta():
        return next(n for n in d.library()["notebooks"] if n["title"] == title)

    def strokes(i):
        nb = meta()
        return d.page(nb["id"], nb["pageIds"][i])["strokes"]

    def canvas():
        return d.find("Handwriting canvas")[2]

    def setup():
        d.tap("New notebook")
        x, y, _ = d.find("Title")
        d.sh("shell", "input", "tap", str(x), str(y))
        d.sh("shell", "input", "text", title.replace(" ", "%s"))
        time.sleep(0.5)
        d.sh("shell", "input", "keyevent", "KEYCODE_ENTER")
        d.find("Handwriting canvas")
        # A stylus stroke first: from then on fingers scroll (pen-only mode), as on a tablet.
        x1, y1, x2, y2 = canvas()
        d.stylus_swipe(x1 + (x2 - x1) // 5, y1 + (y2 - y1) // 6, x2 - (x2 - x1) // 5, y1 + (y2 - y1) // 6 + 20)
        d.tap("Add page after this one"); wait_for(lambda: d.status() == (2, 2), "page 2 not added")
        d.tap("Add page after this one"); wait_for(lambda: d.status() == (3, 3), "page 3 not added")
        if d.absent("Hide pages"):
            d.tap("Show pages")
        d.reveal(1)
        d.tap_page(1)
        wait_for(lambda: d.status() == (1, 3), "page 1 not shown")
        wait_for(lambda: len(strokes(0)) == 1, "first stroke not saved")
    check("three pages, sidebar open, page 1 in view", setup)

    def scroll_follows():
        x1, y1, x2, y2 = canvas()
        x = (x1 + x2) // 2
        for _ in range(6):
            if d.status()[0] == 2:
                break
            d.drag(x, y1 + (y2 - y1) * 3 // 4, y1 + (y2 - y1) // 4)
        assert d.status() == (2, 3), f"dragging didn't scroll into page 2: {d.status()}"
        assert d.highlighted() == [2], f"sidebar highlights {d.highlighted()}, not page 2"
        d.shot("scrolled-to-page-2")
    check("a finger drag scrolls continuously into the next page; the sidebar follows", scroll_follows)

    def boundary_stroke():
        d.tap_page(2)  # page 2's top at the top of the canvas
        wait_for(lambda: d.status() == (2, 3), "page 2")
        x1, y1, x2, y2 = canvas()
        h = y2 - y1
        boundary = y1 + int(h * 0.4)
        d.drag((x1 + x2) // 2, y1 + h // 5, y1 + h // 5 + (boundary - y1))  # page 1's bottom edge now near `boundary`
        before1, before2 = len(strokes(0)), len(strokes(1))
        d.stylus_swipe(x1 + (x2 - x1) // 3, boundary - 160, x1 + (x2 - x1) // 2, boundary + 160, ms=500)
        wait_for(lambda: len(strokes(0)) == before1 + 1, "the crossing stroke wasn't saved on page 1")
        time.sleep(1.0)
        assert len(strokes(1)) == before2, "part of the stroke landed on page 2"
        ys = m.channels(strokes(0)[-1])["y"]
        page_h = d.page(meta()["id"], meta()["pageIds"][0])["height"]
        assert max(ys) > page_h, f"stroke should run past page 1's bottom ({max(ys):.0f} ≤ {page_h})"
        d.shot("boundary-stroke")
    check("a stroke crossing the page boundary stays whole on the page where it started", boundary_stroke)

    def undo_across_pages():
        x1, y1, x2, y2 = canvas()
        n1, n2 = len(strokes(0)), len(strokes(1))
        y = y1 + int((y2 - y1) * 0.75)  # well inside page 2
        d.stylus_swipe(x1 + (x2 - x1) // 4, y, x2 - (x2 - x1) // 4, y + 20)
        wait_for(lambda: len(strokes(1)) == n2 + 1, "stroke on page 2 not saved")
        d.tap("Undo")
        wait_for(lambda: len(strokes(1)) == n2, "Undo didn't remove the page 2 stroke")
        assert len(strokes(0)) == n1
        d.tap("Undo")
        wait_for(lambda: len(strokes(0)) == n1 - 1, "the second Undo didn't go back to page 1's stroke")
        d.tap("Redo")
        wait_for(lambda: len(strokes(0)) == n1, "Redo didn't restore it")
    check("Undo/Redo follow the order of edits across pages", undo_across_pages)

    def clean_up():
        d.sh("shell", "input", "keyevent", "KEYCODE_BACK")
        x, y, _ = d.find(f"Notebook {title}")
        d.sh("shell", "input", "swipe", str(x), str(y), str(x), str(y), "900")
        time.sleep(0.6)
        d.tap("Move to Trash")
        wait_for(lambda: meta().get("deletedAt") is not None, "test notebook not trashed")
    check("test notebook moved to Trash", clean_up)
    print(f"\n{len(results)} checks passed")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        if m._device:
            print("screenshot:", m._device.shot("failure"))
        print(f"FAIL  {e}")
        sys.exit(1)
