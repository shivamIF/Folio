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
- **Why JSON**: human-inspectable, easy to migrate, adequate size for v1 (about 30 bytes per point). A compact binary page codec can be added behind `DocumentCodec` once profiling shows the need.
- **Writes** (`AtomicFile`): write a temp file, fsync it, copy the previous file to `.bak`, then do an atomic rename.
- **Reads**: fall back to `.bak` when the main file is corrupt. If no backup exists, raise an error rather than silently showing an empty page.
- **Ids are validated** (`[A-Za-z0-9-]{1,64}`) before they're used as file names, which prevents path traversal.

### ADR-5: Editing = command pattern on a per-page `PageEditor`
- Edits are `PageEdit.AddStroke`, `RemoveStrokes` (remembers z-order) and `ReplaceStrokes` (move/recolour).
- Each page keeps bounded undo/redo stacks.
- Edits mutate state on the main thread. Saving takes an immutable snapshot and writes it on one ordered writer thread (`Dispatchers.IO.limitedParallelism(1)`).
- Autosave is debounced by 800 ms, and is flushed at once on `ON_STOP`, back navigation and `onCleared`.
- The same edit objects are the planned unit for the sync operation log (ADR-7).

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

### ADR-7: Sync (planned, not implemented)
- **Plan**: local-first.
  - Each page edit becomes an operation with a stable op id, kept in an outbox.
  - The server orders operations per notebook with a revision counter, not device clocks.
  - Strokes are immutable and addressed by id, so concurrent adds merge without conflict.
  - Concurrent delete-vs-edit of the same stroke resolves "delete wins, unless the stroke was modified after the delete was issued". That case gets surfaced to the user.
- **Status**: no backend exists. A `SyncEngine` interface and an in-process test server will be built in Phase 7.
