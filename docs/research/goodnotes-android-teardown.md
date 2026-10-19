# GoodNotes for Android 2.0.9 (v320533): Teardown

Source: jadx output of `com.goodnotes.android.app` (sources/, resources/, smali/). R8-obfuscated, but Kotlin metadata keeps the original file and class names.

## Main finding

**The Android app is a thin native shell (a Trusted Web Activity) around the web app at `https://web.goodnotes.com/`.**
The APK has no note editor, ink engine, PDF renderer, `.goodnotes` parser, sync or handwriting recognition. All of that runs in the browser (Chrome) as the GoodNotes web app.
GoodNotes' own code is about 4,400 decompiled lines. The rest of the ~14k files are third-party SDKs.

Evidence: the Kotlin module is named `app_productionPlayTwaRelease`; the launcher `twa.GoodnotesLauncherActivity` extends androidbrowserhelper's `LauncherActivity`; the manifest `DEFAULT_URL` is `@string/launchUrl`, which resolves to `https://web.goodnotes.com/`.

## Tech stack (observed)

| Layer | What they use |
|---|---|
| Language | Kotlin + coroutines, kotlinx-serialization, Gson for bridge JSON |
| Shell | androidx.browser TWA (`com.google.androidbrowserhelper`), with a WebView fallback (`webview.MainWebView`, `WebViewChromeClient`) |
| DI | Hilt/Dagger-style `modules.ApplicationModule` |
| Native↔web bridge | **Embedded Ktor server on Netty, loopback only (`127.0.0.1`), WebSocket**, plus CORS |
| Payments | Google Play Billing, exposed to the web as the Digital Goods API (`DigitalGoods::*`), plus androidbrowserhelper `playbilling` PaymentActivity/PaymentService |
| Device trust | Play Integrity (`integrity.RequestIntegrityToken`, `BuildEncodedMessage`), Media DRM device ID, install-source detection |
| Push | Firebase Cloud Messaging (`GNFirebaseMessagingService`), Braze (marketing/in-app messages) |
| Telemetry | Datadog RUM + Logs, Firebase Analytics/Crashlytics |
| Other | WorkManager, Room, DataStore, Play In-App Review, Play Install Referrer (deferred deep links), FileProvider |

## Native↔web bridge (how the web app reaches native features)

- `localwebsocket.netty.KtorLocalWebSocketEngineFactory` starts Netty on `127.0.0.1:7711`. If 7711 is taken, it falls back to a dynamic port.
- Routes: `GET /status` (health check) and WebSocket `/`.
- `websitecommunicationbridge.CommunicationBridge` parses each incoming text frame as JSON, reads `id`, and sends it to the listeners registered for that id. Replies carry `callId`/`result`/`error`.
- Commands from the web app to native:
  - `DigitalGoods::getDetails`
  - `DigitalGoods::listPurchases`
  - `DigitalGoods::consume`
  - `DigitalGoods::launchBillingFlow`
  - `PushNotification::requestToken`
  - `Review::requestReview`
  - `Memory::requestAvailableMemory`
  - notification-permission requests
  - URL-change events
- Events from native to the web app: S-Pen button pressed/released and Air Actions (`spen.SPenListener`, which uses Samsung's S-Pen Remote SDK through reflection); Lenovo pen key events (`KEYCODE_DOUBLE_TAP`, `KEYCODE_SWIPE_UP/DOWN`) and haptics (`lenovo.LenovoPenListener`); push taps; permission status.
- Lenovo haptic and tool vocabulary in the payloads: `PENCIL`, `BALL_POINT_PEN`, `CHISEL_MARKER`, `ERASER`, `TEXT_BOX_FOCUS`, `RECOGNITION_FINISH`, `BRUSH_CHANGE`; intensity levels `LIGHTLY` through `INTENSELY`.
- `DRMContextSwitch(enabled)`: the web app tells native when a DRM-protected document is open.

## Launch flow

`GoodnotesLauncherActivity.onCreate`:
1. Requests a Play Integrity token in parallel.
2. Resolves a deferred deep link (install referrer: `path` + `guid`).
3. Builds the launch URL with device ID, partner license key and display refresh rate.
4. Picks a TWA-capable browser. If none is found, it shows "no browser detected" and exits.
5. Launches the TWA and starts `KeepAliveService`.

Hosts it accepts as deep links: web, marketplace, classroom, org-admin, edu-admin and www `.goodnotes.com`, plus the `web+goodnotes:` scheme.

## Where the real engine is (not in this APK)

- The web app at web.goodnotes.com (by GoodNotes' public statements, their Swift codebase compiled to WebAssembly). The APK doesn't show this. It could be checked by capturing the web bundle.
- **`/Applications/Goodnotes.app` on this Mac** is the native Apple build, with the full engine (document model, ink, PDF, OCR, sync). It's the best target for the next pass.

## What I could confirm about the `.goodnotes` file format

From the user's own `Governance---Part-1.goodnotes`, unpacked:
- It's a plain ZIP, not encrypted.
- Top-level files: `schema.pb`, `index.notes.pb`, `index.attachments.pb`, `index.events.pb`, `index.search.pb` and `thumbnail.jpg`.
- Folders: `notes/<UUID>` (79 files), `attachments/<UUID>` (PDFs/PNGs as-is) and `search/<UUID>` (recognised text index).
- Records are **varint-length-prefixed protobuf messages** with UUID string keys and timestamps.

## Open items

- Bridge reply envelope: inferred from field names, because some coroutine bodies didn't decompile.
- Protobuf schema of `notes/*` records: needs decoding (no `protoc` on this Mac yet) or the Mac binary.
- Web/WASM engine: not examined.

## Writing-tool dropdown (Mac app, 2026-10-19): layout and behaviour only

Read with REA from the `en.lproj/Tools.strings` and `GN6ToolbarUI.strings` string tables in `/Applications/Goodnotes.app`. No code, icons, colours or other assets were copied. Folio's dropdown uses Material Symbols and its own palette.

- Toolbar "Writing" group: Pen, Pencil, Highlighter, Eraser, plus a compact "Stroke" size control and a "Color" well with addable slots.
- Pen options, in order: PEN STYLE (Fountain Pen / Ball Pen / Brush Pen), Pen thickness, colour picker (Presets / Custom / History segments; "last used colors" history), PRESSURE SENSITIVITY (Min–Max), STROKE STABILIZATION, SETTINGS (react to writing speed, pen rotation), saved styles ("palette shortcuts").
- Pencil options: style (Standard, HB, H, B, Soft, Hard), TIP SHARPNESS (Sharp / Round / Blunt), opacity and thickness sensitivity.
- Highlighter options: thickness, Tip Flatness, Draw in Straight Line.
- Folio implemented: the pen style, thickness, colour (palette + recent), pressure and stabilisation sections. Pencil grades, tip sharpness, saved styles and straight-line highlighter need ink-engine work and are not built yet.
