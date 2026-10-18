# Performance (Galaxy Tab S6 Lite, SM-P615, Android 13, 60 Hz)

Measured with `python3 -I scripts/perf_tab.py -s <serial>` on synthetic handwriting pages.
- **Workload**: 12 stylus strokes, 8 partial-eraser rubs, 10 finger scrolls per density.
- **"Input → drawn"**: from the pen sample's timestamp to the end of the frame's UI-thread drawing. It doesn't include the GPU, compositor or display scan-out, so true pen-to-glass latency is higher and needs a high-speed camera to measure.
- **Frame times and jank**: from `dumpsys gfxinfo`, which covers the whole frame.

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
