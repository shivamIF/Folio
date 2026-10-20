# Artifact Inventory

All artifacts were supplied by the user. Analysis was static only; no proprietary code was copied into this project.

| Path | Type / size | Classification | Purpose / finding | Reuse potential | Next action |
|---|---|---|---|---|---|
| `~/Desktop/DropShare/com.samsung.android.app.notes_4.4.48.15…apk` | APK, 207 MB | Application package (proprietary) | Samsung Notes. Native S Pen engine in about 90 `libSPen*.so` files | **None.** Proprietary. Used only to understand features and the `.sdocx` structure | Keep as reference; don't redistribute |
| `…apk_Decompiler.com.zip` | ZIP, 122 MB (32,914 Java files) | Decompiled source (proprietary) | Java side of the pen SDK, app modules | **None for code.** Informs the feature list and input design | Reference only |
| `~/Desktop/DropShare/com.goodnotes.android.app_2.0.9….apkm` and its decompiled ZIP | APKM 6 MB; ZIP 26 MB | Application package / decompiled source (proprietary) | A Trusted Web Activity shell around web.goodnotes.com. It has no ink engine | None | Reference only |
| `/Applications/Goodnotes.app` (Mac, 2026 build) | Mach-O universal app, 912 MB | Application (proprietary) | Library UI teardown with REA: interface archives, string tables, asset names, type names. See `docs/research/goodnotes-mac-library-teardown.md` | **None for code or assets.** Feature list only | Reference only |
| `~/Desktop/Governance---Part-1.goodnotes` | ZIP, 7.6 MB | Document-format sample (the user's own) | ZIP of length-prefixed protobuf records, plus PDF/PNG attachments | Test fixture for a future read-only importer (user data, not committed) | Phase 6 |
| Same APK, `classes*.dex` disassembled with the SDK's `dexdump` (build-tools 36.0.0), 2026-10-20 | Bytecode listing (proprietary) | Samsung Notes 4.4.48.15: page sorter classes the decompiled ZIP lacks (`PageListMoreMenuPresenter`, `MoreMenuListPopup`, `PageListHolderPresenter`) | The exact per-page ⋮ menu: items, order, conditions, and what each does (docs/research/page-navigator-spec.md) | **None for code**; behaviour only | Done |
| `/Applications/Goodnotes.app` (macOS 7.0.36) `en.lproj/*.strings` | UI strings (proprietary) | Goodnotes page-action vocabulary (Add Page Before/After, Clear Page, Export This Page, "Move Page to Trash?") | Behaviour only | Done |
| `.sdocx` samples | n/a | **Not yet supplied** | Needed to verify the page binary layout | n/a | Ask the user when Phase 6 starts |
| `docs/research/goodnotes-android-teardown.md`, `docs/research/samsung-notes-spen-teardown.md` | Markdown | Our analysis notes | S Pen pipeline, features, container layouts | Design input | Keep |

## What the analysis contributed (ideas, independently re-implemented)
- **Per-point channels**: x, y, pressure, tilt, orientation and t are stored per point, which allows replay and pressure re-rendering. Implemented in `core/model/Stroke`.
- **Low-latency input**: historical samples, unbuffered dispatch and motion prediction. Implemented with the public APIs `MotionEvent`, `View.requestUnbufferedDispatch` and `androidx.input:input-motionprediction`.
- **Palm rejection**: contact-size checks and suppression while the pen is near or hovering, plus honouring `FLAG_CANCELED`/`ACTION_CANCEL`. Implemented in `InputPolicy` / `InkCanvasView`.
- **Barrel button**: holding it erases, and hover shows a cursor ring. Uses the public `BUTTON_STYLUS_PRIMARY` and `ACTION_HOVER_*`.
- **Versioned pens**: a stroke records its tool, so rendering rules can be versioned later.
- **Samsung-private APIs** (`SemMotionEvent`, `SemPointerIcon`, Air Command, screen-off memo) were **not** used. They're undocumented for third parties.
