# Architecture Decisions

### ADR-1: Native Android, Kotlin + Jetpack Compose, with a custom `View` for ink
- **Context**: there was no existing stack. The top requirement is excellent S Pen input on Galaxy Tab.
- **Decision**:
  - Kotlin.
  - Compose for library, toolbars and dialogs.
  - A dedicated `android.view.View` (`InkCanvasView`) for the writing surface, which gives direct access to `MotionEvent` history, `requestUnbufferedDispatch` and hover events.
- **Rejected**:
  - Flutter / React Native: an extra input-pipeline layer, and weaker access to stylus axes.
  - A pure Compose canvas: its pointer API drops some stylus axes and makes unbuffered dispatch awkward.

### ADR-2: Portable core in pure Kotlin/JVM modules
- `core:model`, `core:ink` and `core:storage` have no Android dependency.
- They're unit-tested on the JVM in seconds, and can later become Kotlin Multiplatform modules for the macOS/web clients.
- Platform code (rendering, input, UI) stays in `:app`.

### ADR-3: Canonical document model (`core:model`)
- **Library**: `LibraryIndex { folders[], notebooks[] }`.
  - `Folder`: id, name, parentId (nesting), colour.
  - `NotebookMeta`: id, title, folderId, cover, paper, page size, ordered pageIds, timestamps, favourite.
- **Pages**: `Page { schemaVersion, id, size, paper, strokes[] }`, stored separately so the library never loads page content.
- **Strokes**: `Stroke` has tool, colour, nominal width and parallel arrays `x, y, pressure, tilt, orientation, t` in page units.
- **Page units**: 1000 × 1414 (A4 ratio), independent of screen density.
- **Ids**: random UUIDs, stable across devices, ready for sync.
- **Versioning**: an explicit `schemaVersion`. Newer schemas are rejected (`UnsupportedSchemaException`); unknown fields are ignored for forward-compatible minor additions.
- **Known gap**: unknown fields are not round-tripped yet (see KNOWN_LIMITATIONS).

### ADR-4: Native format = JSON per page, atomic writes
- **Why JSON**: human-inspectable and easy to migrate.
- **Schema 2 (2026-10-19): packed points.** Each stroke's point channels are one base64 string (`pts`: little-endian float32 x, y, pressure, tilt, orientation, then int32 t; 24 bytes per point). Lossless. Measured on the Tab with an imported 7,106-stroke page: schema 1 took 17.3 s to open, schema 2 takes 4.4 s, and the file is 20% smaller. Schema 1 files still load and are rewritten as schema 2 on their next save. See `StrokeSerializer`.
- **Schema 3 (2026-10-19): text boxes and pictures** (`Page.texts`, `Page.images`; pictures are notebook attachments `<id>.img`). An older app refuses these pages rather than dropping content on save. Element edits share the undo history (`PutText`, `PutImage`, `Composite`).
- **Schema 4 (2026-10-19): fine-liner and marker tools** (`ToolType.FINELINER`, `MARKER`). No data migration is needed: schema 3 pages load as they are. Every encode now stamps the current version (before, a page loaded from an older schema kept its old number after edits, so an older app could misread newer content).
- **No JSON tree on read.** `schemaVersion` is found by a key scan. Building a `JsonElement` tree of a 28 MB page ran out of the 256 MB heap and crashed the app; `core:model` tests now run with a 256 MB heap to catch that.
- **Still open**: a fully binary page file (and progressive drawing) would cut the remaining 4.4 s for very dense pages.
- **Writes** (`AtomicFile`): write a temp file, fsync it, copy the previous file to `.bak`, then do an atomic rename.
- **Reads**: fall back to `.bak` when the main file is corrupt. If no backup exists, raise an error rather than silently showing an empty page.
- **Ids are validated** (`[A-Za-z0-9-]{1,64}`) before they're used as file names, which prevents path traversal.

### ADR-5: Editing = command pattern on a per-page `PageEditor`
- Edits are `PageEdit.AddStroke`, `RemoveStrokes` (remembers z-order) and `ReplaceStrokes` (move/recolour).
- Each page keeps bounded undo/redo stacks.
- Edits mutate state on the main thread. Saving takes an immutable snapshot and writes it on one ordered writer thread (`Dispatchers.IO.limitedParallelism(1)`).
- Autosave is debounced by 800 ms, and is flushed at once on `ON_STOP`, back navigation and `onCleared`.
- The same edit objects are the planned unit for the sync operation log (ADR-7).

### ADR-8: Page rendering = own surface + tiles (2026-10-19)
- **The page draws on a `SurfaceView`, not in the window.** The display hardware composites it under the toolbar, so page frames (pen, pinch, pan) never redraw the Compose UI.
- **Ink is cached in opaque 512 px tiles** (paper + PDF + ink, so one paint per pixel) at zoom levels 2^(k/2). A level is never more than 11% coarser than the screen.
  - Workers render only the strokes intersecting a tile.
  - The UI thread only blits tiles, paints appended strokes onto them, and patches edited areas (re-rendering in the background above 300 strokes per area).
  - The whole-page level stays cached as the fallback.
- **The partial eraser wipes the visible tiles directly** (paper repainted along the path). The exact pieces then patch the same area.
- **Stroke outline Paths are cached on the stroke** (`Stroke.renderCache`, opaque to core) and shared read-only by the render threads; bounds are computed before sharing.
- Measurements are in `docs/PERFORMANCE.md`.

### ADR-6: Input pipeline and S Pen strategy
```
MotionEvent → InputPolicy (draw/erase/navigate/ignore)
  → every historical sample → page coordinates (inverse view matrix)
  → StrokeBuilder (speed-adaptive 1€-style stabilisation, merges near-duplicate samples, snaps to the pen-up point)
  → StrokeGeometry.outline (variable-width polygon with round caps)
  → android.graphics.Path → Canvas
```
- **Prediction**: `MotionEventPredictor` adds predicted points that are drawn but never stored.
- **Erasing**:
  - Triggered by `TOOL_TYPE_ERASER`, by holding `BUTTON_STYLUS_PRIMARY` at pen-down, or by the eraser tool.
  - It's a whole-stroke eraser that sweeps along the path, so fast swipes don't skip strokes. The whole sweep is one undo step.
- **Palm handling**:
  - A finger with a large touch-major is ignored.
  - Fingers are ignored within 400 ms of stylus hover or touch.
  - `FLAG_CANCELED` / `ACTION_CANCEL` throws away the in-progress stroke.
  - If the pen touches while a palm is already down, the pen takes over the gesture.
- **Pen-only mode** turns on automatically when a stylus is first seen (this can be switched off). Fingers then pan and zoom; two fingers always pan and zoom.
- **Only public Android APIs** are used. Samsung-private APIs are avoided (see ARTIFACT_INVENTORY).

### ADR-7: Sync = Google Drive app folder, three-way file sync (2026-10-19)
- **Remote:** the user's own Google Drive, in the app-private `appDataFolder` (scope `drive.appdata`). It's free and has no server of ours. Sign-in uses Play services authorisation; the app is registered by package plus SHA-1 in the user's own Cloud project (docs/SYNC_SETUP.md).
- **Items:**
  - `f/folders.json`, `n/<nb>/meta.json`, `n/<nb>/p/<page>.json` (gzip on Drive) and `n/<nb>/a/<id>.pdf|img`;
  - the content MD5 is kept in `appProperties.h`, so a listing shows what changed;
  - the local layout is unchanged; `StoreSync` maps keys to the store under its lock.
- **Engine** (`core:sync`, pure Kotlin): a three-way compare per key (local, remote, base at last sync). Edits beat deletes; a true conflict keeps both (a page copy after the original) or merges (notebook details: newer wins, pages united; folders united by id). Attachments go first and details last; the base is saved as it goes; a pass restarts after a conflict.
- **Safety:**
  - remote input is validated (key shapes, ids, pages must decode and match their key);
  - the open notebook still sends its edits; pages arriving for it go through the editor, which merges them with unsaved edits per element (`PageMerge`, three-way by id) and returns the page to store, written on the editor's own writer before any later save; deletions and conflicts there wait until it's closed;
  - a notebook is removed only when none of its pages remain.
- **When:** ~0.25 s after any local change; Drive's change feed every 1 s while a notebook is open; a full check every 30 s on screen; WorkManager when the app leaves the screen and every 15 min in the background (network required).
- **Speed (measured 2026-10-19):** Drive answers a listing in ~0.6 s, saves a small file in ~2 s and serves one in 1–1.5 s, so device-to-device takes ~5–10 s. Small uploads are one multipart request, connections are kept alive, tokens are reused for 40 min. A same-network direct link was discussed for ~1 s and deferred by the user.
- **Rejected:** a per-edit op-log. It would need a server or a much more complex Drive layout; per-page files fit Drive and our storage, and conflicts are per page.
