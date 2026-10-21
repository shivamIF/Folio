# Samsung Notes-style editor: implementation plan

**Goal (user, 2026-10-20):** rebuild Folio's note-making page so its UI/UX matches Samsung Notes on the
user's Galaxy Tab S6 Lite: toolbar, every tool popup, colour picker, customise-toolbar screen, insert menu,
page sorter, reading mode, full screen, laser, tape, change style, easy writing pad, S Pen to text. It must
be smooth and bug free. **Keep every Folio extra** that Samsung lacks (user: "don't remove, integrate"):
stroke-stabilisation slider (in Samsung's style), pressure sensitivity, Folio's extra pen types (fine liner,
marker, airbrush, brush, ballpoint, fountain, pencil), partial-eraser size, text boxes, pictures, PDF
import/export, page navigator ops, continuous scrolling, hold-to-snap shapes, lasso resize, sync, Brush Lab.
The toolbar must **scroll sideways when it doesn't fit** (portrait), as Samsung's does (user asked).

**Spec:** [research/samsung-notes-editor-teardown.md](research/samsung-notes-editor-teardown.md) (layout,
sizes in dp, colours, every popup and behaviour). **Ink/icon techniques:**
[research/samsung-notes-ink-techniques.md](research/samsung-notes-ink-techniques.md). Reference screenshots
and view trees: `~/Developer/Folio-reference/samsung-notes/captures/` (outside the repo; never copy into it).

## Hard rules
- Clean room: original code, icons (drawn in code / `ImageVector`), procedural textures. No Samsung assets,
  SVGs, shader code or decompiled code (CLAUDE.md). Colour *sets* in palettes must be Folio's own.
- `core/*` stays pure Kotlin. Edits go through `PageEdit` on `PageEditor`. Disk writes through `AtomicFile`.
  Never cancel an in-flight save.
- Tablet: back up and install with `adb install -r` only (memory: test-on-users-tablet); **never** `pm clear`
  or run `scripts/e2e_milestone.py` there (it clears data); use the emulator (`emulator-5554`) for scripted E2E.
  The tablet's library is schema 5.
- Samsung Notes research on the tablet: only inside the scratch note "Handwritten note 20/10"; never touch the
  user's other notes; don't delete the scratch note (the user does). REA CLI: see memory `rea-cli-setup`
  (`REA_ADB_PATH`, `GHIDRA_INSTALL_DIR`); in-chat REA adb tools don't work without that env.
- ML Kit is approved by the user for S Pen to text (`com.google.mlkit:digital-ink-recognition`, on-device after
  a one-time model download). Record it in REUSE_AND_COMPATIBILITY.md (Google ML Kit terms, not Apache-2.0).
- Update docs as you go: FEATURE_STATUS, TEST_RESULTS, KNOWN_LIMITATIONS, ARCHITECTURE_DECISIONS (add an ADR for
  the editor redesign and for schema 6), IMPLEMENTATION_PLAN.

## Where things are today (branch `claude/new-session-6a4076`, base 79606d5 = library redesign + page navigator + continuous scrolling, 198 JVM tests green)
| Area | Files |
|---|---|
| Editor screen (top bar, current `ToolBar`, canvas host, dialogs) | `app/src/main/kotlin/app/folio/notes/editor/EditorScreen.kt` |
| Tool state + prefs (`ToolState`, palette, presets) | `editor/ToolPrefs.kt` |
| Pen slot dropdowns (current) | `editor/ToolPalette.kt`; old glass shelf `editor/ToolShelf.kt` is **unused** (has nice procedural pen art in `drawTool`) |
| Canvas: input, draw/erase/lasso/text, shapes, wet ink | `editor/InkCanvasView.kt` (1.7k lines), `editor/WetInkLayer.kt`, `editor/PageSlot.kt` |
| Rendering | `editor/PageRenderer.kt` (paper, strokes, text, images), `editor/StrokeArt.kt` (geometry, pencil grain), `editor/TileCache.kt` (tiles) |
| View model | `editor/EditorViewModel.kt` (state, edits, NotebookHistory undo, saves, page ops, sidebar) |
| Page sidebar | `editor/PageSidebar.kt`, `editor/PageDialogs.kt`, `editor/PageThumbnails.kt` |
| UI kit / theme | `ui/Glass.kt`, `ui/Theme.kt` |
| Model | `core/model/.../Document.kt` (Stroke, ToolType, Page, TextBox, PageImage, `SCHEMA_VERSION = 5`), `StrokeSerializer.kt`, `Codec.kt`, `History.kt` (PageEdit), `PageOps.kt` |
| Ink | `core/ink/.../BrushEngine.kt` (BrushSpec per tool), `PenProfile.kt`, `StrokeBuilder.kt` (stabilisation), `StrokeGeometry.kt`, `Lasso.kt`, `PartialEraser.kt`, `ShapeRecognizer.kt`, `Regularize.kt` |
| Storage | `core/storage/.../NotebookStore.kt` |
| E2E scripts | `scripts/e2e_*.py` (emulator, stylus-source input, `run-as` reads) |

## Tasks, in order (each ends with `./gradlew test`, `:app:assembleDebug`, an emulator check, then the Tab)

### T1. One UI-style kit (new package `app/folio/notes/editor/oneui/`)
`OneUiTheme.kt` (dark + light tokens from the spec §2; follows system theme), `OneUiControls.kt`
(PopupCard anchored under a toolbar item and clamped to the screen; NumberSlider with − / dotted track /
numbered thumb / +; OpacitySlider; SwitchRow; RadioRow; Pill / pill tabs; dotted divider; close button),
`ToolIcons.kt` (original two-layer icons for every toolbar item: keyboard, pen per type, highlighter
chisel/round, eraser, lasso/rectangle, undo, redo, S Pen to text, change style, tape, easy writing pad,
laser, customise, reading mode, insert, page sorter, full screen/exit, bookmark…), `PenArt.kt` (procedural pen
illustrations for the popup, body + tinted ink part; start from `ToolShelf.drawTool`), `PaletteView.kt`
(5-page pager: recent + eyedropper + full picker; 4 Folio palettes of 8 + current-colour slot),
`ColorPickerPanel.kt` (Swatches 13×13 generated grid, Spectrum hue×saturation field + brightness slider,
old/new preview, Hex/R/G/B fields, 5 recent + eyedropper, Cancel/Done). Unit-test colour maths
(HSV/HSL/hex parsing) in `app/src/test`.
**Done when** a debug preview screen or the editor shows each control matching the spec in dark and light.

### T2. Toolbar model + persistence
`editor/ToolbarConfig.kt`: ordered customisable items (PEN_COLOURS, PEN_SIZES, UNDO, REDO, SPEN_TO_TEXT,
CHANGE_STYLE, TAPE, EASY_PAD, LASER, AUTO_FIX_SHAPES, LOCK_CANVAS, CLEAN_UP, plus Folio extras such as
INSERT_PICTURE, PAGE_NAVIGATION), the hidden set (below the line), colour count 3–5, size count 1–3, the quick
colours, quick sizes **per tool**, Reset. Persist in `ToolPrefs` (SharedPreferences, versioned keys). Unit tests.

### T3. New editor chrome (replace `ToolBar` and the glass top bar in `EditorScreen.kt`)
App bar (spec §18): Back, title (tap → rename dialog), Reading mode, Insert, Page sorter, Full screen, ⋮.
Toolbar strip (spec §3): fixed tools | divider | customisable items | divider | Customise; **horizontally
scrollable, centred when it fits**; selected-tool circle; first tap selects, tap again opens the popup; quick
colours/sizes behave the same. Reading mode (toolbar hidden, pen doesn't write, laser toggle in the app bar)
and Full screen (system bars + app bar hidden, floating exit button) live in `EditorUiState`. Zoom pill and
page number overlay. Keep every current function reachable (PDF export, add page → in menus).

### T4. Tool popups (spec §5–10)
Pen (type row with all Folio pens, size, line-style pills, straighten lines, Folio extras: stabilisation +
pressure, palette), Highlighter (chisel/round, size, opacity, straighten, adjust-to-text), Eraser (Stroke /
Area — Area = Folio's partial eraser with its size slider, erase highlighter only, erase tape only, Erase all
handwriting with confirm + one undo step), Selection (lasso/rectangle, include partially selected — add a
"fully enclosed only" mode to `core/ink/Lasso.kt` with tests), quick-size card, colour picker wiring
(changing a quick colour edits that slot). Then favourites (save/apply/delete presets) and the shrink (mini)
strip.

### T5. Model, schema 6 and rendering
Add, with migrations and `CodecTest`/`ModelTest` coverage:
- `Stroke`: line style (SOLID/DASHED/DOTTED), fixed-width flag, highlighter tip (CHISEL/ROUND); **tape** as a new
  `ToolType.TAPE` with an optional pattern id. Store new fields only when non-default (StrokeRecord).
- Notebook-level: page bookmarks (`NotebookMeta.bookmarks: List<String>`), page background colour, "adjust
  colour in dark mode" flag.
- **Write the lowest schema a document needs** (5 unless it uses a schema-6 feature), so pages without new
  features still open in schema-5 builds still installed from sibling branches (the user hates "schema newer
  than supported"). Document this in ARCHITECTURE_DECISIONS.
- Renderer: dashes/dots, fixed width, round highlighter, tape (flat band, opaque, drawn last, procedural
  patterns, peeled state), dark-mode display adaptation (HSL rule in spec §2) for screen only (not PDF export).
  Update `TileCache` keys so a theme change re-renders.

### T6. Tool behaviour (canvas + VM)
Straighten lines (pen/highlighter/tape: replace the finished stroke with a straight segment start→end, keep
pressure ends); adjust thickness to text (highlighter/tape: snap width to the height of covered ink/text);
**Change style** tool (sweep recolours + re-widths whole strokes, one undo); **Laser** (transient overlay,
3 styles, rainbow, fade ~0.6 s after last pen-up; works in Reading mode; never saved); **Tape** (draw, tap to
peel/cover, Hide tape toggle, Remove all tape, erase tape only); lasso floating menu (Cut, Copy, Paste,
Delete, Change style, Move forwards/backwards, + Folio's Duplicate/Recolour) with rotate handle (add rotation
to `SelectionTransform` with tests) ; Auto-fix shapes toggle (snap on pen-up without holding); Lock canvas
(no pan/zoom).

### T7. Customise toolbar screen (spec §17)
Full-screen route from the toolbar's Customise button: live preview, drag to reorder, drag below the line to
hide, colour count 3/4/5, size count 1/2/3, Reset.

### T8. Insert menu (spec §18)
Image (exists), Camera (TakePicture → insert), PDF (append pages of a PDF to this notebook, reusing the
importer), Sticky note (new page object: colour, size, minimised; write on it), Drawing (full-screen sketch
canvas → picture), Table (rows × columns object), Voice recording / Audio file (attachment + playback; needs
RECORD_AUDIO), Document scan (ML Kit document scanner). Do them in this order; each needs model support where
it adds an object (schema 6 rules above).

### T9. S Pen to text + Easy writing pad (spec §14, §15)
ML Kit Digital Ink: model download UI, recognise after a pause, replace ink with a text box at its place, the
floating Settings/Keyboard/Space/Backspace/Enter bar. Easy writing pad: magnifier window on the page + docked pad
(Previous/Next/Enter, end marker, auto-advance), strokes mapped into the window.

### T10. Page sorter + More menu
Restyle `PageSidebar` to spec §18 (two columns when wide, N/M jump, bookmarks toggle + filter, Select for
multi-page actions, search later). More menu: Search (later), Note style (paper templates incl. narrow/wide),
Page settings (background colour, dark-mode adjust, scrolling direction — horizontal paging can be a later
ADR), Add note to (move to folder), Save as (PDF/image export), finger drawing (= pen-only off), Share, Print
(PrintManager with the exported PDF), Delete (Trash), Add to favourites.

## Testing checklist per milestone
1. `./gradlew test` (all JVM tests) and `./gradlew :app:assembleDebug`.
2. Emulator: drive the new toolbar/popups with `adb shell input` (stylus source) and screenshots; extend
   `scripts/e2e_*.py` where a flow is stable.
3. Tablet (`R52R904TYYA`): back up `files` + `shared_prefs` via `run-as` tar, check `files/library/library.json`
   schemaVersion, `adb install -r`, verify by hand; compare side by side with Samsung Notes screenshots in
   `~/Developer/Folio-reference/samsung-notes/captures/`.
4. Record results in docs/TEST_RESULTS.md.
