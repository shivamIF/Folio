# Project Inventory (Phase 0, 2026-10-18)

## Starting state
- **No existing project repository.** I searched `~/code`, `~/Projects`, `~/AndroidStudioProjects`, `~/Developer`, the Desktop, and Spotlight for `settings.gradle*` outside Gradle caches; none was found.
- So there was no existing code, build, test suite, CI, backend, authentication or document format to preserve. The app was created from scratch in this folder.
- Version control: git, started 2026-10-19 on branch `main` in `~/Desktop/samsung notes` (local only, no remote).

## Toolchain on this Mac (verified)
| Tool | Version / location |
|---|---|
| macOS | 27.0, arm64 |
| JDK | OpenJDK 21.0.12 (Homebrew). `/usr/libexec/java_home` finds no JRE, but `java` on PATH works |
| Android SDK | `~/Library/Android/sdk`: platforms 35 and 36, build-tools 36.0.0, NDK 28.2, emulator, cmdline-tools |
| Emulator image | `android-35/google_apis/arm64-v8a`, AVD `ds` (phone, 1080×2400 @ 420 dpi) |
| Gradle | 9.3.1 (cached distribution, used to generate the wrapper) |
| Maven access | dl.google.com and repo.maven.apache.org both reachable (HTTP 200) |

## Project layout created
```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew
core/model    pure Kotlin/JVM  – canonical document model, JSON codec, undo/redo
core/ink      pure Kotlin/JVM  – pen profiles, stabilisation, outline geometry, eraser hit-testing
core/storage  pure Kotlin/JVM  – crash-safe file repository (library index + pages)
app           Android          – Compose library + editor, S Pen canvas view, input policy
docs/         engineering docs (this folder)
```
