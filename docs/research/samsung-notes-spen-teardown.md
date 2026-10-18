# Samsung Notes 4.4.48.15: S Pen Teardown

Sources examined:
- jadx output of `com.samsung.android.app.notes` (32,914 Java files)
- the original APK's `lib/arm64-v8a` (about 90 native `.so` files, 88 MB) and `assets/`
- demangled C++ exports from the native libraries (~106k symbols)

Labels used below:
- *Observed* means the code, symbol or string is in the build.
- *Inferred* is my interpretation of those facts.

---

## 1. Tech stack overview

| Layer | Implementation |
|---|---|
| App UI | Kotlin/Java. Samsung One UI widgets (`sesl`), some Jetpack Compose, Glance home-screen widgets |
| App modules | `com.samsung.android.support.senl.nt.*`: `app` (list, lock, biometrics, labs), `composer` (the editor), `base`, `platform` (`spenwrapper` converters, `sem` framework wrappers) |
| Pen SDK (Java) | `com.samsung.android.sdk.pen.*` (583 classes). A thin Java layer: settings UI, listeners, and `native_*` JNI stubs (about 1,720 native methods) |
| Pen engine (C++) | `libSPen*.so`, all in the C++ namespace `SPen::`. JNI is registered at runtime with `RegisterNatives` (classes like `*Glue`), so there are no `Java_…` export names |
| Rendering | OpenGL ES plus their own Skia build (`libSPenSkia`). Also `androidx.graphics.lowlatency.GLFrontBufferedRenderer` and an HWUI path (`libSPenHwuiCompat`) |
| ML | TFLite (`libSPenTFLite`) and ONNX Runtime (`libSPenONNXRuntime`), used by the stroke predictor, palm rejection, handwriting recognition, math recognition and PDF layout analysis |
| Documents | `libSPenModel` (object model), `libSPenWDoc` (the current `.sdocx` format), `libSPenSDoc` (the older `.sdoc`), `libSNBConverter` (legacy `.snb`), `libSPenXmlSerializer`, `libSPenZlib` |
| PDF | Their own PDFium fork (`libSPenPdfiumB`), `libSPenPdf`, `libSPenPdfViewer`, and `libSPenPDFDocumentAnalyzer` (an RT-DETR DocLayNet ONNX model in `assets/pdfda`) |
| Collaboration | `libSamsungNotesCoedit` and `libSPenWordDocCoedit` (shared/co-edited notes). Each object has `GetBinaryByCoedit`/`SetBinaryByCoedit` |
| Cloud / AI | Samsung Cloud (`scsp`, `scloud`), Galaxy AI features (`sivs.ai`, `scs.ai`, `imagetranslation`, `livetranslation`), Samsung Analytics |
| Audio | Voice recordings inside notes (`SpenObjectVoice`, SoundAlive resampler) |

---

## 2. How an S Pen stroke flows (observed classes, inferred order)

```
Android MotionEvent (TOOL_TYPE_STYLUS = 2)
  └─ SpenMotionEvent (Java wrapper)            sdk/pen/view/SpenMotionEvent
       per pointer and per historical sample:
       x, y, rawX, rawY, pressure, tilt, orientation, major, minor,
       eventTime (ms) and eventTimeNano (read via reflection: getEventTimeNanos),
       isResampled (via reflection), buttonState, toolType, source, flags
  └─ JNI → SPen::View / SPen::GestureDetector / SPen::GestureRefiner     (libSPenView)
       ├─ SPen::PalmDetector → PalmPredictor (TFLite model, setPalmThreshold)
       ├─ SPen::FGClassifier (libSPenGestureModel, inferred: finger vs. gesture classifier)
       └─ pen input → SPen::Pen (libSPenPenCommon)
            ├─ smoothing: PenKalmanFilter, CSAPSPenStrokeSmoother (cubic smoothing spline),
            │   AverageCurveSmoother, MovingAverageV1, PointStabilizer, StreamLine,
            │   WidthSmoothManager, GradualChangeSmoother, PointBeautifier, PenTolerance
            ├─ StraightStrokeBuilder (hold to make a straight line)
            └─ the chosen pen plugin's StrokeDrawableGL / StrokeDrawableRT
  └─ Predictor (libSPenPredictor)
       NNPredictor (TFLite) or LinearPredictor, plus a KalmanFilter.
       Driven by VSync (OnVSync, GetAlignedToVSyncTimeNano), with a TouchSynchronizer.
       It draws a few predicted points ahead of the pen tip to hide latency.
  └─ Draw loop (libSPenEngine), several backends:
       DrawLoopAGLLGlue  (androidx GLFrontBufferedRenderer: front-buffer, low latency)
       DrawLoopHWUIGlue  (Android HWUI)
       DrawLoopSurfaceGlue / DrawLoopTextureGlue / DrawLoopHwBufferGlue
       (Java side: SpenDrawLoopSurface uses SurfaceHolder.Callback and Choreographer.FrameCallback)
  └─ Pen up → SPen::ObjectStroke added to a PageDoc (libSPenModel), with undo via HistoryManager
```

### Data stored per stroke (`SPen::ObjectStroke`, observed methods)

- **Points**: `PointF[]`, `pressure[]`, `timestamp[]`, `tilt[]` and `orientation[]` (the `SetPoint` and `Construct` signatures take these arrays). There's also a millisecond mode and an initial timestamp. This is enough to **replay** a stroke (`SpenReplayListener`).
- **Pen settings**: pen name (the plugin id), size and size level, colour and colour type, gradient colours, curve on/off, fixed width/opacity, alpha lock, blur, dash offset and line type, pattern index and scale, particle size/density/level, inside ratio, outline colour/width.
- **Rainbow effect**: type, distance, offset, time.
- **Flags**: eraser, straighten, shape, generated, reveal mode, replay-only, top-layer pen, rendering level.
- **Serialisation**: `GetBinary`/`ApplyBinary` for each document type (`SDoc`, `WDoc`), a compatible-binary path for older format versions, and separate co-edit binaries.

---

## 3. Pen types (one native plugin each)

Plugins are loaded through `libSPenPluginFW` / `JniPluginManager` (`SpenPluginInterface`, `SpenPenInterface`).

| Group | Plugins |
|---|---|
| Pens | `DefaultPen`, `InkPen`, `InkPen2`, `FountainPen` (17 GL shader versions), `MontblancFountainPen`, `MontblancCalligraphyPen`, `ObliquePen`, `ChineseBrush` |
| Pencils | `Pencil`, `Pencil2`, `Pencil3`, `ColoredPencil`, `Crayon2` |
| Markers | `Marker`, `Marker2`, `Marker3`, `Marker4` (highlighters) |
| Brushes / painting | `BrushPen`, `AirBrushPen`, `OilBrush3`, `WaterColorBrush`, `SimpleBrush`, `Smudge`, `PaintingCore` |
| Special | `MagicPen`, `LaserPen` (temporary pointer ink), `TapePen` (tape that hides text until tapped), `SelectPen` / `SelectPen2` (lasso selection), `Eraser` |

Pens with numbered versions (V1, V2, V3…) keep their old rendering code. That way, notes made with an earlier version look identical when reopened. This is why stroke data saves a format version.

---

## 4. S Pen features and where they are implemented

| Feature | How it works (observed) |
|---|---|
| **Pressure, tilt and orientation** | Read per sample into `SpenMotionEvent.EventInfo` and stored per point in `ObjectStroke`. Brushes, pencils and fountain pens use tilt and orientation for shading and nib angle |
| **Low-latency ink** | Front-buffer rendering (`GLFrontBufferedRenderer`), VSync-aligned neural prediction (`NNPredictor`) and resampling-aware nanosecond timestamps |
| **Palm rejection** | Native `PalmDetector` plus a TFLite `PalmPredictor` with an adjustable threshold. There's also an "S Pen only" mode (`settings_spen_only_mode`), where a finger scrolls and only the pen draws |
| **Side button (in the editor)** | Pen events are passed with their `buttonState` into the native engine (`SpenGesture`, `SpenMotionEvent.buttonState`). The engine changes tool while the button is held; the pointer icons for this are `SelectionIcon`, `EraserPenIcon` and `CutterRemoverIcon` |
| **Side button (in the note list)** | `BasePenRecyclerViewModel.isPenMultiSelectionMode`: `toolType == STYLUS && (buttonState & BUTTON_STYLUS_PRIMARY)` means drag with the button held to select several notes |
| **Hover (Air View)** | Per-tool hover cursors (`engine/pointericon/*`, `SePointerIcon`/`SdlPointerIcon`), `SpenHoverListener`, hover-scroll near edges (`setHoverScrollEnabled`, `stylusScroll*Type`), and hover previews of notes in the list (`pen_hovering_information_preview`) |
| **Samsung-only APIs** | `spen/libse` (`Se*`, for One UI ≥ "Semaphore" builds) and `spen/libsdl` (`Sdl*`, for older ones) behind `spen/libinterface`. They give access to the hidden `SemMotionEvent`, `SemPointerIcon`, `HoverPopupWindow`, `FloatingFeature`/CSC flags (`com.sec.feature.spen_usp` checks whether the device has an S Pen), desktop mode and adaptive-sync display |
| **Pen detach → Screen-off memo** | `composer/main/screenoff/*` and `app/labs/screenoffmemo`. The system sends `com.samsung.pen.INSERT` with a detach/attach reason. Start commands: `PenAttach`, `PenButtonHoverTap` (hover and press the button while the screen is off), `StartPenDoubleTap`, `StartPenDetach`. The memo draws over the always-on display and asks to save or pin on exit |
| **Air Command → Create note** | `app/labs/createnote/StartAirCommand` (an entry point launched from the system's Air Command menu) |
| **Straight lines / shapes** | Native `StraightStrokeBuilder` (hold at the end of a stroke). `AutoShapeRecognition` / `AutoShapeTransformer` / `AutoResultToStrokeConverter` (libSPenEngine) and `libSPenRecognizerShape` turn rough shapes into clean ones |
| **Handwriting → text** | `SpenSmartWritingManager` (libSPenSmartWriting): neural grouping into words and lines (`NNBaseGrouping`), reflow animations. Also `recoguifeature/converttotext`, `libSPenRecognizer*`, `libSPenHandwritingConverter`. Language packs come from a resource provider (`SpenResourceProviderInterface`) |
| **Straighten / beautify handwriting** | `libSPenBeautify`, `libSPenHwrBeautifier`, `libSPenHwrLetterBeautifier`, `libSPenTextLineCorrection`, `libSPenHandwritingGeneration` (inferred: AI "auto-format/straighten" that regenerates neater handwriting) |
| **Math** | HME engine (`assets/hme/*.dat`, LSTM models `PcmgLstm.dat` and `SCLstm.dat`), `RecognizerMath{Recognition,LineSplitter,Latex,Solver,Render}`. Converts handwritten maths to LaTeX, solves it, and plots graphs (`SpenObjectMath`, `SpenObjectPlot`, `SpenMathPlotListener`) |
| **Gestures** | `SpenGestureLowPassFilter`, `SpenHoldMotionDetector`, `SpenMultipleTapDetector`, `SpenShortPressDetector`, `SpenScaleGestureDetector` + `SpenScaleStabilizer`, native `RotateGestureDetector` and `FlingAnimation`, plus `DeltaZoom` for smooth pinch zoom |
| **Scratch-out / writing options** | `SpenWritingOptionPatternDetector` (inferred: recognises an editing gesture drawn over text and offers actions in `SpenWritingOptionView`) |
| **Replay** | `SpenReplayListener`, `SpenLayeredPaintingReplayListener`. Possible because per-point timestamps are stored |
| **Lenovo/other pens** | Not applicable. The S Pen hardware button and Air Actions are handled by the OS. This app relies on One UI system events, not the S Pen Remote SDK |

---

## 5. The `.sdocx` container (observed strings in `libSPenWDoc`/`libSPenModel`)

A `.sdocx` file is a ZIP archive containing:

```
note.note            note header (LoadHeader: property flags, fixed/flexible areas, canvas cache data)
pageIdInfo.dat       ordered list of page ids
<pageId>.page        one binary page each: layers → objects (ObjectStroke, Shape, TextBox, Image,
                     Table, Line, Formula/Math, Plot, Voice, Video, Web, Link, AttachedFile, Container)
media/mediaInfo.dat  media index; the referenced binaries sit next to it under media/
attach/attachInfo.dat
size.dat, qsave_state.dat, refer.dat, state.dat
end_tag.bin          trailer: format version, metadata, and (when the note is locked) encryption info
```

The legacy `.sdoc` format (`libSPenSDoc`) uses `content.dat`, `doc.dat`, `text.dat`, `searchData.dat`, `fileinfo.dat` and `endtag.dat`. Legacy `.snb` files are converted by `libSNBConverter`. PDF export/import goes through `/export.sdocx` and `page_%07d.spi` caches.

### Locked notes (design level only)

- The end tag records whether a note is encrypted and stores the key/IV/salt metadata (`WEndTagUtil_getEncryption{Key,Iv,Salt}`, `SDocEndTag::SetEncryptionInfo`).
- The Java side uses AES-CBC and PBKDF2-HMAC-SHA1. The password or biometric unlock (`app/lock`, `biometrics/fingerprint`, `iris`, `MultiBiometricPrompt`) protects the key.
- Unlocked notes can be read by a converter you write. Locked notes need their owner's password.

---

## 6. GoodNotes vs Samsung Notes

| | GoodNotes (Android) | Samsung Notes |
|---|---|---|
| Architecture | Trusted Web Activity around web.goodnotes.com | Native app: Kotlin/Java UI plus about 90 C++ engine libraries |
| Ink | In the browser (not in the APK) | Native OpenGL/Skia, front-buffer rendering, NN prediction, Kalman filtering, smoothing splines |
| Pen hardware | Forwards S-Pen/Lenovo button events to the web app over a localhost WebSocket | Deep One UI integration: hover, Air View, pointer icons, screen-off memo, detach events |
| File format | ZIP of length-prefixed protobuf records | ZIP of custom binary pages plus `.dat` indexes and a trailer |
| Recognition | Server/web side | On-device TFLite/ONNX: handwriting, maths, shapes, palm rejection, layout analysis |

---

## 7. What to copy as ideas for your own S Pen app

Build these with your own code.

1. **Input**: use `MotionEvent` historical samples, `getAxisValue(AXIS_PRESSURE / AXIS_TILT / AXIS_ORIENTATION)` and `BUTTON_STYLUS_PRIMARY`. Call `requestUnbufferedDispatch` for low latency.
2. **Rendering**: `androidx.graphics:graphics-core` `GLFrontBufferedRenderer` for wet ink. Commit to a cached multi-buffered layer when the pen lifts.
3. **Prediction**: `androidx.input:input-motionprediction` (`MotionEventPredictor`). It's the public equivalent of their NNPredictor.
4. **Smoothing**: a 1€ or Kalman filter on input, then Catmull-Rom/Bezier or a smoothing spline for the stroke path, with width taken from pressure.
5. **Storage**: per-stroke arrays of x, y, pressure, tilt, orientation and t. Save the pen type plus a version number, so old notes still render the same way.
6. **Recognition**: ML Kit Digital Ink Recognition (handwriting, shapes, many languages, on-device).
7. **Hover**: `ACTION_HOVER_*` events plus `PointerIcon` for cursors per tool.

## Open questions and limits

- The exact binary layout of `.page` records and `note.note` isn't decoded yet. Decoding it needs sample `.sdocx` files.
- Some conclusions come from symbol names, without disassembly: the side-button tool switch inside the native engine, and what `FGClassifier` and `WritingOptionPatternDetector` do. Confirming them would mean disassembling `libSPenView` and `libSPenEngine`.
- Nothing was run on a device. This is static analysis only.
