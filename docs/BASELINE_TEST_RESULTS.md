# Baseline Test Results

There was no pre-existing repository, so there was no pre-existing build or test suite. The baseline is "nothing to run".

The first build of this project (2026-10-18) used:
- Gradle 9.3.1 wrapper
- AGP 9.1.0 (built-in Kotlin)
- Kotlin 2.2.21
- Compose BOM 2025.12.01

Results:
- `./gradlew :core:*:test`: 34 tests, 1 failure on the first run. `StrokeBuilder` still filtered input at stabilisation 0. I fixed it, and all tests passed.
- `./gradlew :app:assembleDebug`: succeeded on the first attempt, with one deprecation warning (`Icons.Filled.NoteAdd`), now fixed.
