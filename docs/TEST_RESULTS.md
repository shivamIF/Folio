# Test Results (2026-10-18)

## Unit tests: `./gradlew test`. 84 tests (after step 2: + Trash ×4; earlier counts below), 0 failures (40 in the first table below, plus page formats ×3, page size storage ×2, PageViewport ×10, notebook defaults ×2, pressure curve ×4, natural-tilt pencil ×1)
| Module | Suites | Tests | Covers |
|---|---|---|---|
| core:model | CodecTest, PageEditorTest | 10 | Every stroke channel round-trips; Unicode; newer schema rejected; malformed, truncated, empty and inconsistent input → `CorruptDocumentException`; unknown fields tolerated; add/erase/undo/redo with z-order; redo cleared by a new edit; replace; snapshot isolation; bounded history |
| core:ink | PenProfileTest, StrokeBuilderTest, StrokeGeometryTest | 14 | Pressure→width; highlighter ignores pressure; tilt widens pencil; clamping; stabilisation reduces jitter; stroke ends at the pen-up point; monotonic time; duplicate merging; buffer growth; outline width and caps; dot; empty; eraser hit/miss; fast eraser swipe doesn't skip strokes |
| core:storage | NotebookStoreTest | 10 | Reopen after "restart"; corrupt page → `.bak` recovery; corrupt with no backup → error, not silent loss; leftover `.tmp` after a crash ignored; nested folders and safe folder delete; unknown parent rejected; page insert position; delete removes files; path-traversal ids rejected; blank names |
| app | InputPolicyTest | 6 | Finger draws until a stylus is seen, then navigates; barrel button / eraser end / eraser tool erase; large contact = palm; finger within 400 ms of pen = palm; multi-touch navigates; pen-only can be switched off |

## End-to-end: `scripts/e2e_milestone.py`. 16 checks (milestone 6 + lasso 5 + PDF 3 + library 2); latest run 16/16 on the Galaxy Tab S6 Lite
- **Device**: Android emulator `sdk_gphone64_arm64`, API 35, 1080×2400 (a phone form factor, not a tablet).
- **Input**: `adb shell input stylus swipe` (`TOOL_TYPE_STYLUS`, constant pressure).

Checks:
1. Library shows the empty state.
2. Creating the notebook "Milestone" opens the editor.
3. Two stylus strokes are written; the autosaved page file holds exactly 2 strokes with matching per-point channel lengths.
4. Eraser tool + stylus swipe across stroke 1 → the saved file holds only stroke 2.
5. Undo → the saved file holds strokes 1 and 2 in their original order.
6. Back → force-stop → relaunch → reopen the notebook → both strokes are still saved, and both are drawn (screenshot `build/e2e/04-reopened.png` checked by eye).

Each step also fails the run if an error dialog is on screen.

### Defects found by testing and fixed
| # | Found by | Defect | Fix |
|---|---|---|---|
| 1 | unit test | Stabilisation 0 still filtered input and dropped samples | Raw pass-through at strength 0 |
| 2 | E2E | Empty grid was drawn over the empty-state text, so the text was hidden from accessibility | Render the grid only when there are items |
| 3 | E2E | "New notebook" button had no accessible label | Explicit content description |
| 4 | E2E (intermittent) | Starting a stroke cancelled an in-flight save, giving a false "Saving failed" dialog. The promised retry didn't exist. No data loss: the next save covered it | Only the debounce timer is cancellable; cancellation is rethrown, not reported; real failures retry with backoff 2 s → 60 s |
| 5 | screenshot review | Eraser ring stayed on screen after pen-up | Hide the cursor on pen-up |
| 6 | Tab E2E | In landscape, the keyboard covered the dialog's Create button | Keyboard "Done" creates the notebook; dialog content scrolls |
| 7 | user, check D | Holding the S Pen side button and dragging didn't erase. Root cause (from the input log): One UI delivers these touches as actions 211/212/213 with `buttons=32`, and the Compose host dropped them before the canvas | The canvas maps 211–214 to erase, and `MainActivity.dispatchTouchEvent` routes side-button gestures straight to the canvas. **Confirmed on the Tab by the user and the log** |
| 8 | user, check G | Ink blinked when the pen lifted (live stroke dropped a frame before the saved copy was drawn) | Finished strokes stay drawn until the page state echoes them. **Awaiting confirmation** |
| 9 | user | Page floated over the background, zoomed out past the screen, moved separately from it | `PageViewport`: opens at fit-width, zooms out to whole-page (centred), pans stop at page edges, rotation keeps zoom and position, fling scrolling. Unit-tested. **Awaiting confirmation** |
| 10 | user | Page drew over the toolbar when scrolled | Canvas clipped to its own bounds. **Awaiting confirmation** |
| 11 | user | Page size was asked for on every new notebook | Default page size (A4/A5/Letter/Legal/Auto), orientation and paper moved to Settings on the home screen |
| 13 | Tab data + user | Lines too thin: normal writing pressure is about 0.3, but pens assumed 1.0 | `PressureCurve` (raw 0.6 = full effort, applied when writing, stored in the stroke), "Pen pressure" slider (light ↔ firm hand), fountain pen retuned. On the user's 3,245 recorded samples the median width went from 42% to 70% of the set size, and the light-to-firm range from 32–56% to 52–89%. **Awaiting the user's feel test** |
| 14 | Tab data | Pencil shaded during normal writing (natural tilt 37–54°) | Shading starts only past 52° of lean |
| 15 | user | Lasso needed most of a stroke inside the loop | Any touch (point inside, or the loop outline crossing the ink) selects the whole stroke |
| 16 | user + log | Moved selection snapped back on pen-up and looked deselected. The data had moved; the screen drew a stale cached path (cache keyed by stroke id only) | Cache also checks stroke identity. E2E now checks screen pixels after a move |
| 17 | Tab E2E | In portrait the toolbar overflowed, hiding Lasso/Eraser off-screen | Toolbar groups wrap onto extra rows |
| 18 | Tab E2E + log | A finger touching mid-stroke made Android 13 cancel the pen, and the stroke was lost | Cancelled *pen* strokes are kept up to the cancel point; finger strokes are still discarded |
| 19 | PDF E2E | Imported Letter pages were normalised to A4 width, so the export was 3% smaller | Physical page size kept; E2E asserts the 612×792 pt MediaBox round-trips |
| 21 | screenshot review | Selected black swatch looked like an empty ring on the dark toolbar | Outer selection ring with gap + thin outline on every swatch; verified on a Tab screenshot |
| 20 | build | After moving to Desktop, builds ran stale copied outputs and iCloud created conflict copies (`Lasso 2.kt`); builds took 10 min | Project moved to `~/Developer/Folio` (not synced); clean rebuild; committed code re-verified (62/62) |
| 12 | user | Wanted to zoom out further, page always centred | Zoom out to half of whole-page, centred on both axes in both orientations; Auto page size shaped like the screen. **Awaiting confirmation** |

## Physical device: Galaxy Tab S6 Lite (SM-P615, Android 13, One UI 5.1.1)
- **Milestone E2E**: 6/6 (after defect 6). One earlier failure was caused by someone touching the screen during the run (multi-finger events in the input log). Later runs were clean.
- **Raw S Pen data** (`getevent`, `sec_e-pen`):
  - 240 Hz report rate.
  - Pressure 0–4095. The user's handwriting stays between 1 and 2464 (median about 1031, i.e. 0.25).
  - Tilt X/Y ±63; hover distance 0–255; side button as `BTN_STYLUS`.
- **What the app saved**: every 4 ms sample is kept, along with pressure, tilt and orientation.
- **Finding**: typical writing pressure is about 0.3 (peaks about 0.6); natural tilt 0.64–0.94 rad. These drove defects 13 and 14.

## Not yet tested
- **Manual S Pen checks** on the Tab: re-checks of D (button erase), G (blink), zoom/page locking and toolbar clipping are pending. A, B, C, E and F haven't been reported.
- **Tablet form factor**: no tablet emulator was used.
- **Performance and latency**: not measured. There's no p95 latency number yet.
- **Instrumented (on-device) tests**: none yet. The E2E runs from the host.
