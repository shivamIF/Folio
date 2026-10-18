# Implementation Plan

## Next task (start here)
**Hardware validation on a Galaxy Tab with S Pen**:
1. Install the debug APK.
2. Run `scripts/e2e_milestone.py -s <tablet serial>`.
3. By hand, check pressure, tilt shading (pencil), hover ring, button-hold erase, writing with the palm resting, and pinch/pan.
4. Record results in TEST_RESULTS.
5. Measure input-to-ink latency (high-speed video, or `adb shell dumpsys gfxinfo` frame stats) while writing.

## Phase status
| Phase | State |
|---|---|
| 0 Discovery | ✅ done (docs/PROJECT_INVENTORY, ARTIFACT_INVENTORY, REUSE_AND_COMPATIBILITY, BASELINE) |
| 1 Architecture | ✅ ADR-1…7, canonical model, repository interface |
| 2 Android foundation | ✅ builds, launches, persists, survives a restart |
| 3 Library | 🟡 folders/covers/sort/search implemented. Next: thumbnails, trash/recovery, multi-select, E2E coverage |
| 4 Handwriting slice | 🟡 milestone passes on the emulator. Next: hardware validation (above) |
| 5 Editor expansion | ⏳ lasso select/move (reuses `ReplaceStrokes`), straight-line hold, shapes, text box, images, PDF import (`PdfRenderer` for backgrounds) and export (`PdfDocument`) |
| 6 Formats | ⏳ `.goodnotes` read-only importer (protobuf records + attachments); `.sdocx` once samples arrive |
| 7 Sync | ⏳ `SyncEngine` interface, op-log outbox from `PageEdit`s, in-process test server, two-client tests (scenarios A–F) |
| 8 Productivity | ⏳ ML Kit Digital Ink (on-device, opt-in) for search inside handwritten notes |
| 9 Hardening | ⏳ latency/frame profiling, bitmap tiling, malformed-file fuzzing, instrumented tests |
| 10 Release | ⏳ signed release build, licence report |

## Multi-agent note
This session did the work directly, without sub-agents. The work was sequential and tightly coupled (one model shared by every layer), so parallel agents would have cost more usage without saving time. Phases 5 (editor), 6 (formats) and 7 (sync) only share the `core:model` contract, so they can run as separate parallel sessions from here.
