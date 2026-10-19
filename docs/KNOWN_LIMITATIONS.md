# Known Limitations

1. **No physical S Pen testing yet.** Pressure, tilt, hover, the barrel button, palm rejection and latency have only been unit-tested and checked on an emulator, where adb stylus input has constant pressure, no tilt and no button.
2. **Rendering**: see `docs/PERFORMANCE.md`.
   - Tiled rendering on the page's own surface (ADR-8): zoom and pan stay at 60 fps on a 7,000-stroke page.
   - Edits touching more than 300 strokes in one tile's area (e.g. undoing a huge move) show the old tile for a moment while it re-renders.
   - A lasso move of thousands of strokes still draws them as vectors every frame.
   - There's no front-buffered (`GLFrontBufferedRenderer`) wet ink yet.
3. **PDF backgrounds are images.** They're rendered once at 1.5× screen size and drawn into the ink tiles, so they blur above about 1.5× zoom (rendering the PDF per tile is planned). In exports they're embedded as 180 dpi images, so the original PDF text isn't selectable. Password-protected PDFs are refused with a message.
4. **Stroke eraser only.** There's no partial (pixel/segment) eraser yet.
5. **Palm rejection is heuristic.** It uses contact size, pen proximity (400 ms) and system cancel flags. A palm that lands well before the pen hovers and looks like a small finger contact may pan the page in pen-only mode.
6. **Unknown JSON fields are dropped on re-save.** A newer minor version's extra fields survive reading but not writing.
7. **The library index is rewritten on every page save.** That's fine at hundreds of notebooks; for thousands, it should become per-notebook metadata files or SQLite.
8. **Very dense pages open slowly.** A 7,000-stroke page (imported from Samsung Notes) takes about 4.4 s to load plus 2.6 s to draw on a Tab S6 Lite. Typical handwritten pages (hundreds of strokes) are well under a second. A binary page file and progressive drawing would fix it.
9. **No cloud sync or search inside notes** yet.
12. **Imports bring strokes, not everything.** From `.sdocx`: no PDF backgrounds, text boxes, images, shapes or sticky memos yet, and about 0.3% of strokes (rotated ones) are skipped. One sample note reports 1,897 skipped items that still need checking. From `.goodnotes`: text boxes and images are skipped. Each import says how many items it skipped. Locked Samsung notes are refused: they need the owner's password, and Folio won't bypass that.
10. **Debug APK is about 60 MB**, mostly the material-icons-extended library. Release builds enable R8 shrinking, but no release build has been made yet.
11. **No remote backup yet.** The git history is local to this Mac (`~/Developer/Folio`). Push it to a private GitHub repo or similar to protect against disk loss.
