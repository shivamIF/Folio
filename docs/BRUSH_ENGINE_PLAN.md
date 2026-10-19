# Brush engine: findings and plan (2026-10-19)

Status (2026-10-19): **Brush Lab built; fine liner and marker added to the shelf (schema 4). Engine v2 not started.**
- Brush Lab: `app/src/debug/.../lab/` (debug builds only, no launcher icon). Open it with `adb shell am start -n app.folio.notes/.lab.BrushLabActivity` (`--ez sample true` adds scripted test strokes, `--es preset fountain` picks a preset, `--ef zoom 6` zooms the panels). Write in the top pad; the three panels below replay the same input through v1, v2 (empty slot until v2 exists) and AndroidX Ink 1.0 stock brushes, with shared pan/zoom and build/draw timings. Ink is `debugImplementation` only: the release classpath has no Ink; the debug APK grows 4.8 MB.
- First Tab reading (scripted strokes, fountain): v1 and Ink look alike at fit zoom; geometry build is now timed separately from drawing per engine.
- Per the user, new tools live in the app's existing shelf with the same look, not in a separate UI.
Baseline: `./gradlew test` 163 tests, 0 failures; debug APK 64,974,988 bytes.

## Current architecture
- **Model:** `core:model` `Stroke` stores per-point x, y, pressure (already mapped through `PressureCurve`), tilt, orientation and time. `ToolType` = FOUNTAIN, BALLPOINT, PENCIL, BRUSH, HIGHLIGHTER (persisted enum).
- **Geometry:** `core:ink` `StrokeGeometry.outline()` builds one polygon per stroke: an offset on each side of every raw input point, joined by straight `lineTo`s, with a semicircle at each end. `PenProfile` maps pressure and tilt to width (per-tool min width, gamma, tilt gain, opacity).
- **Rendering:** `app` `PageRenderer.drawStroke()` fills that polygon with one flat colour × profile opacity (Android `Canvas`, `WINDING` fill). The same path serves the live stroke, the tiles (`TileCache`), thumbnails and PDF export.

## Why it looks cheap
1. **Faceted curves:** the outline follows the raw samples with straight segments and no curve interpolation, so loops and bowls show corners when zoomed.
2. **Kinks at sharp turns:** offsets come from a central-difference normal with no join handling, so tight turns leave notches and lumps.
3. **Lagging width:** width smoothing is a one-way filter, so swelling and thinning trail behind the pen.
4. **Same blunt ends everywhere:** every tool ends in a full-radius round cap, so fountain and brush strokes end like sausages instead of tapering.
5. **Flat tool character:** the pencil is a 75% flat fill (no grain); the highlighter is 35% colour painted over the ink, with round caps, so it greys the text under it; the fountain pen has no nib-angle variation.
6. **Tool icons** are generic Material icons (since replaced by the glass shelf artwork).

## AndroidX Ink / Cahier compatibility (checked against the real artifacts)
- **Ink 1.0.0** (stable, 2025-12-17; 1.1.0 is alpha) is on Google Maven as `androidx.ink:ink-*-android` (KMP). minSdk **23** (Folio: 29 ✔).
  - `ink-nativeloader` carries `libink.so`: 1.27 MB arm64, plus armeabi-v7a, x86 and x86_64 builds.
  - The other jars total ≈ 1 MB.
- **Verified API (javap on 1.0.0):**
  - `StockBrushes.marker()`, `pressurePen()`, `highlighter(SelfOverlap)` and `dashedLine()`; `pencilUnstable` is experimental and needs a texture store.
  - `Stroke(Brush, StrokeInputBatch)` builds the stroke; `MutableStrokeInputBatch.add(toolType, x, y, timeMs, …, pressure, tilt, orientation)` fills the batch.
  - `CanvasStrokeRenderer.create().draw(Canvas, Stroke, Matrix)` draws into any existing Canvas.
  - `BrushFamilySerialization.decode(InputStream)` loads a brush family.
- **Fit:** Folio's stroke channels map one-to-one onto `StrokeInputBatch`, so Ink can render existing documents through an **adapter**: Folio `Stroke` → Ink `Stroke`, drawn into the existing tile Canvas. Nothing persisted changes.
- **Cahier (Apache 2.0)** exports `.brushfamily` from its brush designer. These are loaded with `BrushFamilySerialization.decode`.
  - **Unverified:** whether files exported by a newer Cahier decode on Ink 1.0.0. A max-version decode overload only exists from 1.1.0-alpha02.
  - This needs a real exported file to test; there is none yet.
- **Costs and risks of adopting Ink:**
  - about 1.3–2.3 MB per ABI of native code;
  - a native mesh per stroke, which must be cached for 35k-stroke notes;
  - the eraser and lasso still hit-test Folio's own outline, so Ink's shapes would have to match it.

## Proposed change
1. **Folio engine v2 (pure Kotlin, `core:ink`), alongside v1:**
   - **Smooth centreline:** centripetal Catmull-Rom with bounded subdivision.
   - **Smoothing:** symmetric (forward + backward) width smoothing.
   - **Joins:** round joins at sharp turns.
   - **Ends:** pressure-driven tapers (fountain, brush), with a de-spike of the first and last samples. Tapers come from real pressure, not arc length, so pieces cut by the partial eraser don't sprout fake tapers.
   - **Fountain nib:** subtle nib-angle width variation.
   - **Pencil:** paper grain via a bitmap shader, in the renderer only.
   - **Highlighter:** `BlendMode.MULTIPLY` with a chisel/square cap, so the text under it stays crisp.
2. **Brush Lab (debug source set only):**
   - Presets: ballpoint, fountain, fine liner, pencil, brush pen, highlighter, marker.
   - Each preset's same input is rendered three ways: v1 (current), v2 (proposed) and AndroidX Ink stock brushes (`debugImplementation` only).
   - A capture pad records a real S Pen stroke and replays it through all three.
3. **Fine liner and marker**: done. Both are real `ToolType`s (schema 4) on the glass shelf, with their own pen profiles (near-constant felt-tip width).

## Safe comparison plan
- v2 sits behind a switch, and the app keeps v1 until the Lab comparison and tests pass.
- Unit tests for v2:
  - closed and finite outlines;
  - width bounds;
  - no taper at partial-eraser cut ends;
  - dots, two-point strokes and hairpin turns.
- Before and after: `./gradlew test`, `scripts/e2e_milestone.py` (S Pen input, palm rejection, persistence, undo/redo, eraser) and `scripts/perf_zoom.py`/`perf_erase.py` on the Tab (dense pages must not regress).
- Screenshots of the Lab on the Tab for the report.
- Rollback: flip the switch back to v1. The document data is never changed.

## Recommendation (provisional, pending the Lab comparison)
Do **v2 in Folio's own engine** first. It fixes the six causes above with no native dependency, no format risk and full control over eraser/lasso consistency. Use AndroidX Ink in the Lab as a reference for quality and for testing Cahier brushes. Adopt it for rendering only if the side-by-side shows a clear win that v2 can't reach.
