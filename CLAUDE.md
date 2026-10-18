# Folio: notebook app for Android tablets (S Pen first)

## Build & test
- `./gradlew test`: JVM unit tests for all modules (core + app input policy)
- `./gradlew :app:assembleDebug`: debug APK at `app/build/outputs/apk/debug/app-debug.apk`
- `python3 -I scripts/e2e_milestone.py -s <serial>`: end-to-end milestone check on a device or emulator. It needs the debug APK installed, uses stylus-source input, and reads saved files through `run-as`.
- `local.properties` must have `sdk.dir=$HOME/Library/Android/sdk`. Gradle runs on JDK 21; bytecode targets 17.

## Layout and rules
- `core/model`, `core/ink`, `core/storage` are **pure Kotlin/JVM**. Never add Android imports there; they're the portable layer for future macOS/web clients.
- The document model is versioned (`SCHEMA_VERSION`). Any change to persisted fields needs a migration plus a codec test.
- All disk writes go through `AtomicFile`. Ids that become file names must match `[A-Za-z0-9-]{1,64}`.
- Edits are `PageEdit` commands on `PageEditor`. Don't mutate stroke lists directly.
- Saves: never cancel an in-flight write. Only the debounce timer is cancellable (see the bug in TEST_RESULTS).
- Stylus input lives in `app/.../editor/InkCanvasView.kt`. The decision rules (palm, pen-only, barrel button) are in `input/InputPolicy.kt`, with unit tests.
- No code or assets from Samsung Notes / GoodNotes. Only public Android APIs, no Samsung-private ones.

## Status docs
`docs/FEATURE_STATUS.md`, `docs/TEST_RESULTS.md`, `docs/KNOWN_LIMITATIONS.md`, `docs/IMPLEMENTATION_PLAN.md` (the next task is at the top), `docs/ARCHITECTURE_DECISIONS.md`.
