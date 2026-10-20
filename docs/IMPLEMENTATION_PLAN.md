# Implementation Plan

## Next task (start here)
**Merge the page navigator** (branch claude/folio-page-navigator-178027) with the library redesign (claude/goodnotes-landing-page-rea-4f69be) into one build. Then finish the Tab checks of single-page export (`scripts/e2e_page_navigator.py`). Continuous vertical page scrolling is a separate follow-up (docs/research/page-navigator-spec.md §4).

**Brush engine v2** (docs/BRUSH_ENGINE_PLAN.md, proposed change 1), plugged into the Brush Lab's v2 column; compare on the Tab before switching the app over. Fine liner and marker are already real shelf tools (schema 4).

**Commit the editor, import and sync work** (waiting for the user's go-ahead). Then the user's on-device checks of shapes, text boxes, pictures and PDF zoom. Later: search inside handwritten notes (phase 8); before any wide release, slow the 1 s Drive change check (see KNOWN_LIMITATIONS 9).

## Phase status
| Phase | State |
|---|---|
| 0 Discovery | ✅ done (docs/PROJECT_INVENTORY, ARTIFACT_INVENTORY, REUSE_AND_COMPATIBILITY, BASELINE) |
| 1 Architecture | ✅ ADR-1…7, canonical model, repository interface |
| 2 Android foundation | ✅ builds, launches, persists, survives a restart |
| 3 Library | 🟡 Goodnotes-style redesign (sidebar, folder colours/icons, move, duplicate, favourites, folder Trash, list view, multi-select); see docs/research/goodnotes-mac-library-teardown.md. Next: drag-and-drop onto folders, the user's check on the Tab |
| 4 Handwriting slice | 🟡 milestone passes on the emulator. Next: hardware validation (above) |
| 5 Editor expansion | ✅ lasso, PDF import/export, shape snap, text boxes, pictures |
| 6 Formats | ✅ `.goodnotes` and `.sdocx` import (strokes, PDFs, text). Open: pictures/sticky memos/GoodNotes shapes (need samples) |
| 7 Sync | ✅ Google Drive sync working between the Tab and the phone: live merge into open notebooks, background sync. Direct same-network link deferred |
| 8 Productivity | ⏳ ML Kit Digital Ink (on-device, opt-in) for search inside handwritten notes |
| 9 Hardening | ⏳ latency/frame profiling, bitmap tiling, malformed-file fuzzing, instrumented tests |
| 10 Release | ⏳ signed release build, licence report |

## Multi-agent note
The user asked (2026-10-19) for one agent at a time, handing off to a fresh one in sequence when needed; no parallel agents. This session did the work directly, without sub-agents. The work was sequential and tightly coupled (one model shared by every layer), so parallel agents would have cost more usage without saving time. Phases 5 (editor), 6 (formats) and 7 (sync) only share the `core:model` contract, so they can run as separate parallel sessions from here.
