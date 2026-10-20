# Known Limitations

1. **No physical S Pen testing yet.** Pressure, tilt, hover, the barrel button, palm rejection and latency have only been unit-tested and checked on an emulator, where adb stylus input has constant pressure, no tilt and no button.
2. **Rendering**: see `docs/PERFORMANCE.md`.
   - Tiled rendering on the page's own surface (ADR-8): zoom and pan stay at 60 fps on a 7,000-stroke page.
   - Edits touching more than 300 strokes in one tile's area (e.g. undoing a huge move) show the old tile for a moment while it re-renders.
   - A lasso move of thousands of strokes still draws them as vectors every frame.
   - There's no front-buffered (`GLFrontBufferedRenderer`) wet ink yet.
3. **PDF backgrounds are images.** They're rendered per tile, so they stay sharp at any zoom. In exports they're embedded as 180 dpi images, so the original PDF text isn't selectable. Password-protected PDFs are refused with a message.
4. **Stroke eraser only.** There's no partial (pixel/segment) eraser yet.
5. **Palm rejection is heuristic.** It uses contact size, pen proximity (400 ms) and system cancel flags. A palm that lands well before the pen hovers and looks like a small finger contact may pan the page in pen-only mode.
6. **Unknown JSON fields are dropped on re-save.** A newer minor version's extra fields survive reading but not writing.
7. **The library index is rewritten on every page save.** That's fine at hundreds of notebooks; for thousands, it should become per-notebook metadata files or SQLite.
8. **Very dense pages open slowly.** A 7,000-stroke page (imported from Samsung Notes) takes about 4.4 s to load plus 2.6 s to draw on a Tab S6 Lite. Typical handwritten pages (hundreds of strokes) are well under a second. A binary page file and progressive drawing would fix it.
9. **Schema 5 needs this build on every synced device.** Any page saved by this build is stamped schema 5 (the airbrush tool), and an older build refuses it ("update the app"). The airbrush exports to PDF without its blur. Install the same APK on the phone and the Tab before syncing.
10. **Sync speed is bounded by Google Drive:**
   - another device's edit shows up about 5–10 s after it's written while a notebook is open, 30 s in the library and about 15 min while Folio is closed (Android may stretch this);
   - Drive takes ~2 s per saved file and 1–1.5 s per fetched one, so 1–2 s would need a direct device-to-device link (discussed, not built);
   - page deletions and same-page conflicts in an open notebook wait until it's closed;
   - a page merged live loses its undo history;
   - the 1 s change check uses ~60 Drive requests a minute per device with a notebook open, so slow it down before a wide release (Drive's free per-project quota).

   **No search inside notes** yet.
12. **Imports bring almost everything.** Not yet: Samsung pictures and sticky memos, GoodNotes shape-tool shapes (type 5) and placed pictures, because no sample contains a placed picture to decode. Each import says what it skipped. Locked Samsung notes are refused: they need the owner's password, and Folio won't bypass that.
10. **Debug APK is about 60 MB**, mostly the material-icons-extended library. Release builds enable R8 shrinking, but no release build has been made yet.
11. **No remote backup yet.** The git history is local to this Mac (`~/Developer/Folio`). Push it to a private GitHub repo or similar to protect against disk loss.
13. **Text boxes:** there's no rich text (bold, mixed sizes), no text rotation, and the box doesn't scroll into view above the keyboard yet.
14. **Pictures:** removing a picture keeps its file in the notebook, so undo can bring it back. Files aren't cleaned up yet.
15. **No Mac app yet.** The core modules are plain Kotlin, ready for one.
16. **Page navigator (2026-10-20):**
   - The editor still shows one page at a time. The sidebar follows the open page; there's no continuous vertical scroll between pages.
   - No copy/paste of pages between notebooks (it would have to copy PDF attachments), and no multi-select. Duplicate covers copying within a notebook.
   - No bookmarks, page rotation, hidden pages, outline or per-page template change. Each needs a persisted field and a schema migration.
   - Undo for delete and move lasts while the snackbar is shown (about 10 s). Erase is also in the page's own undo history.
   - Deleting a page keeps its PDF or picture attachments in the notebook (as with pictures, item 14).
   - Thumbnail PNGs in `cache/page-thumbs` aren't pruned when a notebook is deleted. They're cache, so Android may clear them.
   - Single-page export offers PDF, PNG and JPEG only. Word, PowerPoint and text need a text layer Folio doesn't have.
   - Sync: a page deleted here can come back if another device edits the same notebook's details before syncing (notebook details merge pages as a union, ADR-7).
17. **Folder extras and older builds.** Folder icon, folder star and folder Trash were added without a schema bump, so schema-5 builds still open the library. Those builds ignore the three fields, though, and if one of them saves the library, folder icons and stars are dropped and trashed folders reappear. Notebooks are unaffected. Folders deleted forever can come back through Drive sync, because sync unites folder lists by id; use Trash instead (it syncs) until sync learns folder deletions.
18. **Library moves use a picker**: no drag-and-drop onto folders yet.
