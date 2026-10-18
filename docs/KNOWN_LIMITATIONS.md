# Known Limitations

1. **No physical S Pen testing yet.** Pressure, tilt, hover, the barrel button, palm rejection and latency have only been unit-tested and checked on an emulator, where adb stylus input has constant pressure, no tilt and no button.
2. **Rendering is unoptimised.** Every stroke is a cached `Path` redrawn each frame, with no bitmap tile cache or front-buffered layer yet. Very dense pages may drop frames while panning. Measure first (Phase 9); a `GLFrontBufferedRenderer` wet-ink layer is the planned upgrade.
3. **PDF backgrounds are images.** They're rendered at 1.5× screen size, so they blur above about 1.5× zoom (tiled re-rendering is planned). In exports they're embedded as 180 dpi images, so the original PDF text isn't selectable. Password-protected PDFs are refused with a message.
4. **Stroke eraser only.** There's no partial (pixel/segment) eraser yet.
5. **Palm rejection is heuristic.** It uses contact size, pen proximity (400 ms) and system cancel flags. A palm that lands well before the pen hovers and looks like a small finger contact may pan the page in pen-only mode.
6. **Unknown JSON fields are dropped on re-save.** A newer minor version's extra fields survive reading but not writing.
7. **The library index is rewritten on every page save.** That's fine at hundreds of notebooks; for thousands, it should become per-notebook metadata files or SQLite.
8. **JSON page size** is about 30 bytes per point. A compact binary codec is deferred until it's measured.
9. **No cloud sync, `.goodnotes`/`.sdocx` import or search inside notes** yet.
10. **Debug APK is about 60 MB**, mostly the material-icons-extended library. Release builds enable R8 shrinking, but no release build has been made yet.
11. **No remote backup yet.** The git history is local to this Mac (`~/Developer/Folio`). Push it to a private GitHub repo or similar to protect against disk loss.
