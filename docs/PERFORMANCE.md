# Performance (Galaxy Tab S6 Lite, SM-P615, Android 13, 60 Hz)

Measured with `python3 -I scripts/perf_tab.py -s <serial>` on synthetic handwriting pages.
- **Workload**: 12 stylus strokes, 8 partial-eraser rubs, 10 finger scrolls per density.
- **"Input → drawn"**: from the pen sample's timestamp to the end of the frame's UI-thread drawing. It doesn't include the GPU, compositor or display scan-out, so true pen-to-glass latency is higher and needs a high-speed camera to measure.
- **Frame times and jank**: from `dumpsys gfxinfo`, which covers the whole frame.

## Eraser on a dense page (2026-10-19)
Measured with `scripts/perf_erase.py`: three 1.2 s stylus rubs across the 7,090-stroke imported page, debug build, Galaxy Tab S6 Lite. "Pen-up" is UI-thread work after lifting the pen.

| | Stroke eraser before | Stroke eraser after | Partial eraser after |
|---|---|---|---|
| Frames per 1.2 s rub | 5–29 | 60–72 | 66–69 |
| Pen → screen latency p95 | 50–260 ms | 12–16 ms | 10–12 ms |
| UI draw p50 | 15–74 ms | 4–5 ms | 0.8 ms |
| Pen-up on the UI thread | 71–140 ms | ~35–95 ms | ~20–30 ms (pieces computed in the background: 115–430 ms) |

What was slow, and the fix for each:
- **Hit-testing tested every stroke on every pen sample**, allocating a 7,000-item list each time. Fix: `StrokeIndex`, a uniform grid of stroke bounds. Queries only look near the eraser, and tile rendering and patching use it too.
- **Tile diffs built identity sets of the whole page every frame.** Fix: when only the hidden set changes, the diff looks up just the flipped ids.
- **Pen-up pieces were cut segment by segment** (with boxed lists). Fix: one `PartialEraser.erasePath` pass per stroke against the whole path, with per-segment box rejection. It runs on a background thread together with the next page's index; a new gesture first waits for a pending commit, so edits stay in order.
- **`SplitStrokes` and undo were O(changed × page).** Fix: one-pass apply and one-pass merge for re-inserting at the original positions (tested against the old behaviour).
- **Full-page id sets on every publish and `setPage`.** Fix: only built when something is selected or pending.

## Zoom and pan on a dense page: surface + tiles (2026-10-19)
Measured with `scripts/perf_zoom.py`: a scripted pinch to 3×, hold, pan and zoom back out (the debug build drives the same viewport code as a real pinch), on an imported 7,106-stroke Samsung Notes page. Frame stats come from `dumpsys gfxinfo`; `scripts/framestats.py` breaks frames down by stage.

| | Single cache bitmap in a View | Own surface (SurfaceView) | Surface + tiles |
|---|---|---|---|
| Janky frames | 36–57% | 0.2% | **0–0.2%** |
| Frame time p50 / p95 | 22–29 / 36–44 ms | 5 / 16 ms | **5 / 7–8 ms** |
| GPU per frame (p50) | 10–13 ms | 3 ms | **4 ms** |
| Sharp after zooming stops | never within 1.5 s (2–2.6 s full re-render) | same | **17 ms (one frame)** |

**Two causes, found by switching layers off one at a time:**
1. **Every page frame redrew the whole window.** The toolbar alone cost about 5.6 ms of render-thread time per frame, and a blank page still janked 30% of frames. Fix: the page draws on its own `SurfaceView` (`lockHardwareCanvas` from a Choreographer loop). Pitfall found on the way: overriding `invalidate()` swallowed SurfaceView's own invalidations, so the window "hole" was never punched and the page stayed black.
2. **One whole-page bitmap was stretched while zooming (blur), then fully re-rendered** (2–2.6 s for 7,106 strokes). Fix: `TileCache`, made of opaque 512 px tiles at zoom levels √2 apart.
   - Each tile draws only the strokes touching it, on 3 threads, centre-out.
   - Stroke outlines are cached per stroke.
   - The whole-page base level is kept as a fallback.
   - Edits are diffed: appends are painted on, and changed areas are patched.

## Before → after the ink layer cache

| Density | Writing frame p95 | Writing jank | Scroll frame p95 | Scroll jank | Erase input→drawn p95 | Erase jank |
|---|---|---|---|---|---|---|
| Empty page | 20 → 23 ms | 5% → 5% | 20 → 22 ms | 3% → 2% | – → 4.9 ms | – → 8% |
| ~600 strokes (full page) | 36 → **23 ms** | 10% → **4%** | 36 → **22 ms** | 19% → **4%** | 15.2 → **4.5 ms** | 28% → **10%** |
| 3,000 strokes (stress) | 93 → **23 ms** | 100% → **5%** | 117 → **23 ms** | 100% → **7%** | 25.5 → **4.8 ms** | 64% → **8%** |

Writing input→drawn p95 is 5–6.5 ms at every density (8–14 ms before).

## What changed
1. **InkLayerCache** (`editor/InkLayerCache.kt`):
   - Finished strokes are rasterised once.
   - New strokes are painted onto the cache directly.
   - Rebuilds (erase, undo, move, zoom) happen on a background thread, and outdated builds are discarded.
   - Dense pages no longer compute every outline on the UI thread when they open.
2. **Partial eraser fast path**: the eraser wipes pixels off the cache on every pen sample. The exact stroke pieces are computed once at pen-up, and the wiped cache is adopted, so consecutive rubs stay fast.
3. **Stroke bounds** are computed once per stroke, which speeds up eraser, lasso and selection hit-testing.
4. **Bug found by the log**: a rounding error made the cache rebuild endlessly at the page bottom. Fixed by rounding sizes up and allowing a one-pixel tolerance.

## Known limits
- **Erase frames**: p95 is still 29–40 ms. Each wipe re-uploads the whole cache bitmap to the GPU; splitting it into tiles would cut this.
- **Rebuilding the 3,000-stroke stress page** takes about 0.85 s in the background. During that time the previous image is shown, for example right after an undo.
- **Zooming in** shows a scaled (softer) image until the pinch pauses for 150 ms.
- **Front-buffered rendering** (`GLFrontBufferedRenderer`) isn't used yet. It's the next lever for raw pen-to-glass latency.

## Library (2026-10-20, Galaxy Tab S6 Lite, the user's own library)
Measured with `scripts/perf_library.py` (taps and scrolls only, safe on real data) and per-interaction `dumpsys gfxinfo` framestats.

| | Debug build | `perf` build (not debuggable) |
|---|---|---|
| Scroll jank | 34% (GPU p50 15 ms, p90 25 ms) | 14–42% (GPU-bound; noisy right after install) |
| Section switch / grid↔list: one UI-thread frame | 200–300 ms | 140–230 ms |
| Sidebar collapse/expand: UI thread p50 | 6 ms | 5 ms |

Changes: the sidebar animates in the layout phase and the grid re-lays out once instead of every frame; page previews render two at a time; library items are `@Immutable` and memoised, so unrelated state changes don't recompose tiles; folder art caches its shapes; date formatters are built once.
**Still open:** building a full grid takes 140–230 ms on the UI thread, and every library frame costs about 15 ms of GPU on this tablet. Next steps: profile one grid build with a Perfetto trace, and add a baseline profile (`androidx.profileinstaller`) so release-type builds start compiled.
