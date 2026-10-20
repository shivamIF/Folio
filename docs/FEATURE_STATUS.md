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
| Library | Goodnotes-style layout: collapsible sidebar (full ↔ icon rail, remembered), Documents / Favorites / Trash, breadcrumb header, dark and light themes | 🟡 | Emulator walkthrough (light + dark, landscape); Tab check pending |
| Library | Folders: create, nest, customise colour (10 presets + custom hue/shade) and icon (26), rename, star, move, Trash with contents, restore, delete forever | 🟡 | Store tests ×7, codec tests ×4; emulator walkthrough |
| Library | Move notebooks and folders to any folder (folder-tree picker; refuses moving a folder into itself) | 🟡 | Store test; emulator walkthrough |
| Library | Rename / recolour notebook, duplicate (pages + attachments), star from the tile or menu | 🟡 | Duplicate store test; emulator walkthrough |
| Library | Sort (last modified / name / date created / type, either direction), filter (all / notebooks / folders), grid or list, search across folders | 🟡 | LibraryQueryTest ×7; emulator walkthrough |
| Library | First-page previews (default: imported PDFs; Settings: all notebooks or covers only); refresh when edited | ✅ | Tab E2E checks the preview's pixels; cached as PNGs in the cache dir |
| Library | Trash: Move to Trash (with Undo), Restore (to root if folder gone), Delete forever, Empty trash, auto-purge after 30 days | ✅ | Storage tests ×4; Tab E2E trash → restore with content intact |
| Library | Multi-select: select all, bulk star / move / Trash; in Trash bulk restore / delete forever | 🟡 | Emulator walkthrough |
| Library | Drag-and-drop onto folders, subject collections | ⏳ | Move uses the picker for now |
| Editor | Fountain, ballpoint, fine liner, pencil, brush, airbrush, marker, highlighter (glass shelf: pen tray + utilities tray) | 🟡 | Profiles unit-tested; `everyPenStyleIsDistinct` checks no two tools look alike. Airbrush added 2026-10-19 (schema 5, docs/research/pen-styles.md); calligraphy, crayon and watercolour tried on the Tab and withdrawn |
| Editor | Brush Lab (debug builds only, no launcher icon): one capture replayed through v1, v2 (slot) and AndroidX Ink | 🟡 | Runs on the Tab; v2 column waits for engine v2. `adb shell am start -n app.folio.notes/.lab.BrushLabActivity --ez sample true` |
| Editor | Colour palette, per-tool width, stabilisation slider | 🟡 | Persisted in prefs; not E2E tested |
| Editor | Toolbar slots Pen / Pencil / Highlighter (Material Symbols icons; the active one wears the ink colour). Tap the active slot (or long-press any) for its dropdown: pen style (fountain, ballpoint, fine liner, brush, marker, airbrush), stroke preview, fine/medium/bold + slider, 30-swatch palette + recent colours, pressure, stabilisation | 🟡 | `ToolStateTest` ×7; e2e 20/20 on the emulator; installed on the Galaxy Tab S6 Lite, awaiting the user's check |
| Editor | Pressure-sensitive width, calibrated to real S Pen data, with a sensitivity slider | 🟡 | Unit-tested; measured on the user's recorded strokes; awaiting their feel test |
| Editor | Tilt-sensitive pencil/brush (shading beyond natural writing angle) | 🟡 | Unit-tested against measured tilt range |
| Editor | Eraser menu: stroke eraser or partial eraser (cuts strokes, instant pixel wipe), "erase highlighter only", size slider; side button uses the same settings | ✅ | `PartialEraserTest` ×6; Tab E2E (stroke, partial + undo, highlighter-only); erase latency in PERFORMANCE.md |
| Performance | Ink layer cache, background rebuilds, fast erasing | ✅ | docs/PERFORMANCE.md: dense-page writing and scrolling p95 about 23 ms (was 93–117 ms) |
| Editor | Undo / redo | ✅ | Unit + E2E step 5 |
| Editor | Multiple pages: add, previous/next | ✅ | Tab E2E (page navigator run) |
| Editor | Page sidebar (top-bar toggle, remembered): live thumbnails (cached; only the edited page re-renders, 1 s after writing pauses), tap to open, open page outlined and kept in view, "+" tile adds a page at the end | ✅ | Tab E2E 2026-10-20 (`scripts/e2e_page_navigator.py`); spec: docs/research/page-navigator-spec.md |
| Editor | Page ⋮ menu: add before/after, duplicate, move… (+ long-press drag), delete (confirm + Undo), erase (only with content; Undo), export… | ✅ | `PageOpsTest` ×6, store tests ×6; Tab E2E for every item except export |
| Formats | Single-page export: PDF (one page, real size), PNG/JPEG at 72/150/300 dpi; Save to device (system picker) or Share | 🟡 | `PageExportSpecTest` ×5; on the Tab the share sheet opens with the page; full export checks interrupted (see TEST_RESULTS) |
| Editor | Paper: blank / ruled / grid / dotted | 🟡 | Rendered; seen on screenshot (ruled) |
| Editor | Page locked to screen: fit-width on open, zoom out to whole page (centred), edge-stopped pan, fling, rotation keeps place | 🟡 | `PageViewportTest` ×9; awaiting the user's check on the Tab |
| Editor | Lasso select (freeform or rectangle; any touch selects the whole stroke), move, resize, duplicate, delete, recolour; one undo step each | ✅ | `LassoTest` ×12; Tab E2E incl. on-screen pixel check after a move; resize by hand only |
| Editor | Shapes: hold the pen still at the end of a stroke → clean line, ellipse/circle, rectangle, triangle, any polygon or straight zig-zag; keep moving to adjust. Closes even if the end stops short of or runs past the start (gap ≤ 20% of the size). Near-regular shapes become exact (square, circle, equilateral/right/isosceles triangle, regular polygon, level edges and lines); clearly irregular ones keep their corners | ✅ | ShapeRecognizerTest ×8 (incl. stop-short, overshoot, 40% gap stays open, handwriting/arcs/dots left alone), RegularizeTest ×6; E2E: rough rectangle snaps. Not yet tried by hand on the Tab |
| Editor | Text boxes: Text tool (pen or finger tap), type in place, sizes S–XL, edit/delete; lasso-select, move, resize, recolour | ✅ | Model/codec tests; E2E: type, select, delete, undo |
| Editor | Pictures: insert from photo picker (camera rotation baked in), move/resize/delete via lasso; in PDF export and thumbnails | 🟡 | Storage tests; picker flow not automated (needs a person) |
| Editor | Big lasso selections (150+ strokes) drag as one picture | ✅ | Implemented; check on the Tab |
| Editor | PDF backgrounds sharp at any zoom (rendered per tile) | ✅ | Emulator screenshot at 3× |
| S Pen | Stylus vs finger detection, pen-only mode | 🟡 | Policy unit-tested; stylus-source E2E |
| S Pen | Historical samples, unbuffered dispatch, motion prediction | 🟡 | Implemented; latency not measured |
| Editor | Eraser button remembers the last type (stroke or partial); tap again for options | ✅ | E2E helper opens the menu with a second tap |
| S Pen | Side button = erase while held | ✅ | Confirmed on Galaxy Tab S6 Lite (user + input log). Handles Samsung actions 211–214 via window-level routing |
| S Pen | Eraser-end tool type | 🟡 | Policy unit-tested |
| S Pen | Hover cursor (pen size / eraser ring) | 🟡 | Implemented; hardware unverified |
| S Pen | Palm rejection heuristics + system cancel | 🟡 | Policy unit-tested; hardware unverified |
| Formats | Native format, versioned | ✅ | CodecTest |
| Formats | PDF import (library button or "Open with/Share to Folio"), annotate on top, keeps physical page size | ✅ | Storage tests ×5; Tab E2E: 3-page import, background visible, annotation saved |
| Formats | PDF export to Downloads/Folio + Share (vector ink and ruling; PDF backgrounds as 180 dpi images) | ✅ | Tab E2E: 3 pages, 612×792 pt preserved, rendered and inspected |
| Formats | `.goodnotes` import, editable (strokes incl. vector shapes, highlighters, PDF backgrounds, text boxes) | ✅ | GoodNotesImporterTest ×6 + real notebook (79 pages, 2,020 strokes); on the Tab, highlights line up with the PDF text and erase normally |
| Formats | `.sdocx` (Samsung Notes) import: strokes incl. shape-tool and rotated ones, colours, highlighters, pressure, PDF backgrounds, text boxes, vector shapes | ✅ | 35,860 strokes from 5 notes, all inside their boxes; synthetic byte-level test; on the Tab, a 22-page PDF note imports with backgrounds. Not yet: pictures (no sample), sticky memos. Locked notes are refused (by design) |
| Formats | Open `.sdocx` / `.goodnotes` from Files or Share | ✅ | Intent filters for application/sdoc, zip, octet-stream; Folio listed beside Samsung Notes for an `.sdocx` |
| Sync | Google Drive sync (free, private app folder): Settings → Auto sync toggle; tap "Auto sync" to link/change/unlink the account; Sync now; edits offline sync later; conflicts keep both; live merge into an open notebook; background sync (on leaving the app + every ~15 min) | ✅ | Unit: engine, store-to-store, live-editor hand-off, page merge ×6, fake-Drive HTTP (multipart, resumable, change feed). Real devices (Tab S6 Lite + Motorola Edge 50 Neo, 2026-10-19): first upload 37 items, phone received all 33 files byte-identical (MD5), a phone stroke appeared live in the notebook open on the Tab, background worker ran on leaving the app, and the 15-min periodic job ran on schedule (17:02, SUCCESS; Folio's process was already running, so a run from a cold start is unconfirmed) |
| Productivity | Search inside notes, OCR, handwriting recognition | ⏳ | |
