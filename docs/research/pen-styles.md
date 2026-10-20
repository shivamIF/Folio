# Pen styles: what Samsung Notes and GoodNotes do, and what Folio built (2026-10-19)

The goal was to find out which pen and brush types each app offers and what makes each one look different.
- **Samsung Notes**: static analysis of the 4.4.48.15 APK (SHA-256 `484d3dd5…a467ce`): the decompiled Java pen SDK plus demangled exports of the native pen plugins `libSPen<Name>.so`.
- **GoodNotes**: strings in the macOS GoodNotes binary.
- **REA**: confirmed the native library identities. Its disassembler providers (Ghidra, Hopper, IDA) and JADX aren't installed on this Mac, so no function bodies were decompiled.
- **What the conclusions rest on**: everything below comes from **class names, symbol names, field names and default settings** (*observed*). How a pen behaves is *inferred* from those names.
- **What wasn't copied**: no shader, texture, curve, constant or code. Folio's tools are original implementations and tunings.

## Samsung Notes: pen plugins and what drives each one (observed symbols → inferred behaviour)

The default shelf (`DefaultPens`) is: ink pen (`InkPen2`, with `InkPen` as a legacy name), fountain pen, oblique (calligraphy) pen, brush pen and pencil (`Pencil2`). Every plugin keeps old drawables per version (`…DrawableGLV1…V12`), so old notes render as they did.

| Plugin | Observed hooks | Inferred character |
|---|---|---|
| InkPen2 | `GetFixedWidth`, `IsCurveEnabled`, dash/line type, rainbow/gradient colours | Plain ink with optional constant width, dashes and colour effects |
| FountainPen | `adjustTilt`, `checkPressure`, `getSpeed`, `getSpeedScale`, `checkOrientationNanValue`, `getCalculatedWidth` (12 versions) | Width from pressure, tilt, azimuth **and speed** (faster = thinner) |
| ObliquePen | `Matrix4::rotation`, `makeTriangles`, `sAngle`, `getSpeedScale` | Flat nib at a fixed angle: the direction of travel sets thick or thin |
| BrushPen / ChineseBrush | `getAlpha` (per version), `buildTextures`, `getSpeedScale`, `endPen_NoCurve` | Pressure and speed drive width **and opacity**; textured bristles; special end-of-stroke handling |
| Pencil2 / Pencil3 | `adjustTilt`, `getPressureAlpha`, `buildTextures` | Graphite: pressure → darkness, tilt → broad side, grain texture |
| ColoredPencil / Crayon2 | `g_*RandomX/Y`, `buildTextures`, `getIncrementalWidth`, `getAdjustedPressure` | Stamped texture with random jitter: broken, waxy edges |
| Marker3 / Marker4 | `getAlpha`, `checkPressure`, `getSpeedScale` | Translucent felt tip, highlighter family |
| WaterColorBrush | `Dry`, `GetWetDryable`, `ResetPatternAngle`, `getWidth` | Wet pigment that dries; patterned wash |
| AirBrushPen | `buildTextures`, `SetOpacity` | Soft sprayed stamps |
| Also | `OilBrush3`, `Smudge`, `MagicPen`, `LaserPen`, `TapePen`, `MontblancFountainPen`/`MontblancCalligraphyPen` | Painting and special-effect tools (not in scope) |

Per-stroke settings (`SpenSettingPenInfo`): `size`/`sizeLevel`, `isFixedWidth`, `isFixedOpacity`, `isCurvable` (smoothing), `lineType`/`dashOffset`, `colorType` (colour, gradient, pattern), `particleSize`/`particleDensity`, plus `advancedSetting`.

## GoodNotes (macOS): pen types and options (observed strings)

- **Pens**: fountain (`fountainPen`), ball (`ballPen`) and brush (`brushPen`).
- **Highlighter**: a straight highlighter plus a freehand one (`highlighterFree`), with "erase highlighter only" (`erasesHighlighterOnly`).
- **Pressure sensitivity per pen** (`pressureSensitivityFountain`, `pressureSensitivityBrush`) and a pen-stabilisation slider (`PenStabilization`, "STROKE STABILIZATION").
- **Stroke styles**: solid, dashed or dotted (`Pen stroke style: dashed/dotted`, `SerializedDashedStrokePattern`).

## What Folio now has (schema 5)

The tool shelf is split into two trays: pens and brushes, then eraser, lasso, text and picture. The pen tray scrolls sideways on narrow screens.

| Tool | Modelled on | Folio's own implementation |
|---|---|---|
| Ballpoint | GN ball pen, SN ink pen | Near-even width, solid |
| Fountain | GN and SN fountain pen | Pressure taper plus a subtle 45° nib |
| Fine liner | n/a | Constant felt-tip width |
| Pencil | SN `Pencil2/3` | Tilt footprint, graphite shading, fine grain |
| Brush pen | GN and SN brush pen | Hairline to full width with a steep pressure curve, plus a lean footprint |
| **Airbrush** (new) | SN `AirBrushPen` | Even core, edges blurred in page units (scales with zoom), pressure → density |
| Marker | SN `Marker*` | Chisel nib, square caps |
| Highlighter | GN and SN highlighter | Chisel, multiply blend, constant width |

`BrushEngineTest.everyPenStyleIsDistinct` builds a signature for every tool and fails if any two tools look alike. The signature covers:
- pressure response;
- nib-direction response;
- tilt response;
- shading at light and firm pressure;
- edge raggedness;
- caps;
- blur and pressure sensitivity.

**Withdrawn after trying them on the Tab (2026-10-19):** calligraphy (SN `ObliquePen`), crayon (SN `Crayon2`) and watercolour (SN `WaterColorBrush`). They were only in test builds. Strokes saved with them load as fountain pen, pencil and highlighter (`@JsonNames` aliases on `ToolType`, tested in `ModelTest`).

## Not done (ideas from the survey)
- **Speed-scaled width** (SN `getSpeedScale` on almost every pen). Needs per-point time passed into `BrushEngine.mesh`; `Stroke.t` already stores it.
- **Dashed and dotted stroke styles** (GN, SN `lineType`). Needs a persisted per-stroke field, so a schema change.
- **Per-tool pressure sensitivity** (GN). Folio has one global setting.
