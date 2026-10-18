# Feature Status

Legend: ✅ done and tested · 🟡 implemented, partially verified · ⏳ planned · ⛔ blocked

| Area | Feature | Status | Evidence / notes |
|---|---|---|---|
| Foundation | Debug build, launch | ✅ | assembleDebug; E2E step 1 |
| Foundation | Crash-safe local storage | ✅ | `AtomicFile` + `.bak`; NotebookStoreTest |
| Foundation | Autosave (800 ms debounce, flush on stop/back) + retry | ✅ | E2E steps 3–6; save-cancellation bug fixed |
| Foundation | Restore open notebook after rotation / process death | 🟡 | `rememberSaveable` route; not tested yet |
| Library | Create notebook (title, cover colour/style, paper) | ✅ | E2E step 2 |
| Library | Default page size in Settings: A4 / A5 / Letter / Legal / Auto (screen-shaped), portrait or landscape, default paper | 🟡 | Model, store and defaults unit-tested; Settings UI not E2E tested |
| Library | Nested folders: create, rename, delete (keeps notebooks) | 🟡 | Store unit-tested; UI not E2E tested |
| Library | Rename / recolour notebook, favourites, delete with confirm | 🟡 | UI wired to the repository; not E2E tested |
| Library | Sort (recent / title / created), title search | 🟡 | Implemented; not tested |
| Library | First-page previews (default: imported PDFs; Settings: all notebooks or covers only); refresh when edited | ✅ | Tab E2E checks the preview's pixels; cached as PNGs in the cache dir |
| Library | Trash: Move to Trash (with Undo), Restore (to root if folder gone), Delete forever, Empty trash, auto-purge after 30 days | ✅ | Storage tests ×4; Tab E2E trash → restore with content intact |
| Library | Subject collections, multi-select, drag-and-drop | ⏳ | |
| Editor | Fountain, ballpoint, pencil, brush, highlighter | 🟡 | Profiles unit-tested; on-device look only on the emulator |
| Editor | Colour palette, per-tool width, stabilisation slider | 🟡 | Persisted in prefs; not E2E tested |
| Editor | Pressure-sensitive width, calibrated to real S Pen data, with a sensitivity slider | 🟡 | Unit-tested; measured on the user's recorded strokes; awaiting their feel test |
| Editor | Tilt-sensitive pencil/brush (shading beyond natural writing angle) | 🟡 | Unit-tested against measured tilt range |
| Editor | Stroke eraser (tool) | ✅ | E2E step 4 |
| Editor | Undo / redo | ✅ | Unit + E2E step 5 |
| Editor | Multiple pages: add, previous/next | 🟡 | Implemented; not E2E tested |
| Editor | Paper: blank / ruled / grid / dotted | 🟡 | Rendered; seen on screenshot (ruled) |
| Editor | Page locked to screen: fit-width on open, zoom out to whole page (centred), edge-stopped pan, fling, rotation keeps place | 🟡 | `PageViewportTest` ×9; awaiting the user's check on the Tab |
| Editor | Lasso select (freeform or rectangle; any touch selects the whole stroke), move, resize, duplicate, delete, recolour; one undo step each | ✅ | `LassoTest` ×12; Tab E2E incl. on-screen pixel check after a move; resize by hand only |
| Editor | Shapes, text boxes, images | ⏳ | |
| S Pen | Stylus vs finger detection, pen-only mode | 🟡 | Policy unit-tested; stylus-source E2E |
| S Pen | Historical samples, unbuffered dispatch, motion prediction | 🟡 | Implemented; latency not measured |
| S Pen | Side button = erase while held | ✅ | Confirmed on Galaxy Tab S6 Lite (user + input log). Handles Samsung actions 211–214 via window-level routing |
| S Pen | Eraser-end tool type | 🟡 | Policy unit-tested |
| S Pen | Hover cursor (pen size / eraser ring) | 🟡 | Implemented; hardware unverified |
| S Pen | Palm rejection heuristics + system cancel | 🟡 | Policy unit-tested; hardware unverified |
| Formats | Native format, versioned | ✅ | CodecTest |
| Formats | PDF import (library button or "Open with/Share to Folio"), annotate on top, keeps physical page size | ✅ | Storage tests ×5; Tab E2E: 3-page import, background visible, annotation saved |
| Formats | PDF export to Downloads/Folio + Share (vector ink and ruling; PDF backgrounds as 180 dpi images) | ✅ | Tab E2E: 3 pages, 612×792 pt preserved, rendered and inspected |
| Formats | `.goodnotes` / `.sdocx` read-only import | ⏳ / ⛔ | `.sdocx` blocked on sample files |
| Sync | Offline queue, cloud sync, conflicts | ⏳ | No backend exists; design in ADR-7 |
| Productivity | Search inside notes, OCR, handwriting recognition | ⏳ | |
