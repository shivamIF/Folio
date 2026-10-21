# macOS Dock magnification: curve, timings, label, autohide (REA findings, for adaptation)

The coordinating glass-UI session asked (2026-10-21) how the macOS Dock really magnifies icons under the
pointer, so Folio's S Pen hover toolbar can use the real curve and timings instead of a guessed cosine falloff.
This file records **formulas and numbers in our own words**. No Apple code, assets or decompiled text are in the
repo (CLAUDE.md rule); raw REA output stays outside the repo (see "Evidence").

Target: `/System/Library/CoreServices/Dock.app/Contents/MacOS/Dock`, macOS 27.0, arm64e, sha256
`875c07b9…a8ed` (a copy in a scratch folder was analysed, never the original). The binary is stripped, so I mapped
Objective-C selectors to implementation addresses by parsing its class lists myself. Method: REA 6.5.0 + Ghidra
12.1.4 (`procedure_pseudo_code`, evidence IDs in the ledger at the end), each claim cross-checked against
`otool -tV` disassembly of the same copy.

Labels: **[O]** observed in code (address given), **[I]** inference from observed code, **[U]** unknown.
"pt" is a macOS point; Folio can treat it as 1 dp. `T` = a tile's base size, `L` = its maximum magnified size.

## Summary (the numbers to implement)
1. Magnification is a **sinusoidal displacement warp of positions along the bar axis**, not a per-icon size
   function. Icon edge at unmagnified position `x` moves to `x + D(x - c)`, with `c` = pointer position on the axis.
2. `D(u) = R * sin(pi*u / (2F))` for `|u| < F`, and `±R` beyond. `F = 3T`. `R = ((L-T)/2) / sin(pi/12) * fish`
   (`sin(pi/12) = 0.2588`, so `R = 1.932 * (L-T) * fish`; for T=41, L=128 → R = 168 pt at full strength).
3. Consequence: an icon's width is `T + (L-T) * fish * cos(pi*d / (6T))` for icon-centre distance `|d| <= 2.5T`;
   it tapers to exactly `T` at `|d| >= 3.5T`. Peak is exactly `L`; half-way point is `d = 2T`.
4. `fish` is an animated 0..1 strength. Enter bar → animate to 1, leave → animate to 0, **cosine ease-in-out**
   (`0.5 - 0.5*cos(pi*p)`), duration `floor(42 * ln(1 + |Δsize|/2) + 1)` ms; ≈ 160 ms for T=41 → L=128.
5. The **bar widens** (both floor edges use the same warp: up to `2R` = 336 pt wider) and grows `(L-T)*fish` taller;
   icons keep their **bottom edge anchored** (bottom Dock), the top edge rises.
6. The pointer (axis coordinate) is sampled once per frame, **clamped to the bar's unmagnified span**, no smoothing.
7. Touch/stylus magnification is the **same `fish` animation** (same duration, same easing) plus a state machine
   (active / suppressed / ramp-complete flags); it can force magnification on even if the user pref is off.
8. Hover label: **no show delay**, appears opaque instantly, sits 5 pt above the magnified icon's top edge
   (magnification on); fades out in 300 ms (cosine) after the pointer leaves (autohide duration if autohide is on).
9. Autohide slide: `autoHide` 0..1 animated with the same cosine easing, duration
   `floor(ln(1 + Δ/2) + 1) * 60 * autohide-time-modifier` ms (240 ms typical, 300 ms if magnified), offset
   `(top + tile + bottom padding + 10) * autoHide`. Reveal delay pref `autohide-delay` defaults to 0.15 s.
10. Glass: floor glass frame follows the warped edges; each icon's glass backing is built at base size and
    **scaled by a transform** to the magnified rect. No other magnification-dependent glass parameter was found.

## 1. The magnification curve

### Inputs and internal sizes [O]
`tilesize` (user pref, 41 here) and `largesize` (pref, range 16..128, 128 here) are not used raw. The Dock adds a
small inset on each side of a tile, chosen from the pref tile size (table from the layout routine, 0x1000815a8; the
"glass" branch is the only one reachable on this build because its style check is a constant true, 0x10016a85c):

| pref tilesize `t` | tileInset | distanceBottom | distanceSides | distanceTop |
|---|---|---|---|---|
| `t <= 31` | 1 | 7 | 6 | 5 |
| `31 < t <= 47` (41 is here) | 1 | 7 | 3 | 5 |
| `47 < t <= 127` | 2 | 10 | 8 | 6 |
| `t > 127` | 3 | 10 | 9 | 6 |

Internal slot size `T = t + 2*tileInset` (41 → 43) and internal max `L = largesize + 2*tileInset` (128 → 130) [O,
0x100011700 and 0x1000815a8]. Only `L - T` (= 87 here) and `T` matter below; the inset changes sizes by < 5 %.
Magnification runs only when `L > T` and the `magnification` pref is on [O, 0x100011b38]. If the tile row would
be wider than the screen (+12 pt slack) the whole row is shrunk to fit [O, 0x100011700].

### Strength and amplitude [O, 0x100011b38]
- `F` (half-width of the influence zone) = `fSize * T`, with the constant `fSize = 3.0` [O, 0x10024da90].
  So the zone is **±3 icon widths** around the pointer (±123 pt at T=41).
- `fish` ∈ [0,1] is the animated strength. `currentFish = (L - T) * fish` (extra bar height).
- Amplitude `R = ((L - T) / 2) / sin(pi*T / (4F)) * fish`. Because `F = 3T` the `T` cancels: `sin(pi/12)`.
  The normalisation is chosen so the icon exactly under the pointer ends up exactly `L` wide at `fish = 1`.

### Displacement and per-icon size [O, 0x100011dcc; Ghidra and disassembly agree]
For every tile the Dock warps the two edges of the icon square (centre ± T/2) and the two edges of its slot.
With `u = x - c` (`c` = `fishCenter`, the pointer on the bar axis):
- `u <= -F` → `x' = x - R`
- `u >= +F` → `x' = x + R`
- otherwise `x' = x + R * sin(pi*u / (2F))`

The pointer position is the fixed point of the map (`D(0) = 0`), so the unmagnified slot under the pointer stays
under the pointer. Icon size is the warped distance between its two edges. Closed form [I, algebra on the above]:
`w(d) = T + D(d + T/2) - D(d - T/2)`, which for `|d| <= F - T/2 = 2.5T` simplifies to
`w(d) = T + (L - T) * fish * cos(pi*d / (2F))`. In the taper band `2.5T < |d| < 3.5T` one edge is saturated:
`w = T + R * (1 - sin(pi*(|d| - T/2) / (2F)))`. For `|d| >= 3.5T` the width is exactly `T`.

Sample values, `fish = 1` (T and L in pt; "pure" = pref sizes, "internal" = with the inset):

| d / T | pure d (pt) | pure width (T=41, L=128) | internal width (T=43, L=130) | scale vs base (pure) |
|---|---|---|---|---|
| 0 | 0 | 128.0 | 130.0 | 3.12 |
| 0.5 | 20.5 | 125.0 | 127.0 | 3.05 |
| 1 | 41 | 116.3 | 118.3 | 2.84 |
| 1.5 | 61.5 | 102.5 | 104.5 | 2.50 |
| 2 | 82 | 84.5 (= (T+L)/2) | 86.5 | 2.06 |
| 2.5 | 102.5 | 63.5 | 65.5 | 1.55 |
| 3 | 123 | 46.7 | 48.7 | 1.14 |
| 3.5 | 143.5 | 41.0 | 43.0 | 1.00 |
| ≥ 4 | ≥ 164 | 41.0 | 43.0 | 1.00 |

Shape: a cosine lobe (main lobe ±2T at half height, zero slope at the peak), **not** a gaussian or a linear ramp.
Wider than a plain per-icon cosine of the same lobe because the taper band reaches 3.5T.

### Does the bar widen? Where do icons sit? [O unless marked]
- **Widens: yes.** The floor (glass strip) uses the same warp on the unmagnified bar edges, then adds
  `distanceSides` (0x1000128d4). Total widening = `2R` when the pointer is far enough from both ends, i.e.
  `(L - T) / sin(pi/12) * fish` = 336 pt at T=41, L=128 (a 12-icon bar: 492 pt → 828 pt). The stored `barWidth`
  is set only by the unmagnified layout pass [I]; the drawn and hit rects widen.
- **Taller:** floor height = `distanceTop + T + distanceBottom + currentFish`, i.e. it grows by `(L-T)*fish`
  upward (bottom Dock) [O, 0x1000128d4, 0x10005f580].
- **Bottom edge anchored** (bottom Dock): the icon rect's bottom is at a fixed distance above the screen edge
  (stage bottom, minus `distanceBottom`, plus the autohide offset, minus a 5 pt constant); its top is the bottom
  minus the warped width. Horizontal layout is the warp above. Left/right docks use the same maths on the other axis
  (the pointer component is chosen by orientation) [O, 0x100011dcc, 0x100011b38].
- **Pointer sampling:** each frame the Dock copies the latest pointer event position (the drag location while a
  drag is active) and uses its axis component as `c`, clamped to `[barLeft, barRight]` of the unmagnified bar (so
  the end icon stays max-magnified when the pointer slides past the end); no smoothing or lag filter
  [O, 0x100011b38, 0x1000817c0]. A tile can also be an
  `externalFishTile` (drag or keyboard focus drives `c`) [O].
- The y position of the pointer does **not** change the scale; magnification starts when the pointer enters the
  bar's hit rect (floor rect rounded to whole points, with ±10 pt constants in its construction) [O for the
  constants, I for which sides they extend; 0x1000128d4].

## 2. Touch / stylus magnification [O, 0x10008134c, 0x10008142c, 0x1000814dc, 0x10007fd6c]
There is **no separate ramp curve**. The "touch ramp" is the ordinary `fish` animation with extra flags:
- `updateTouchMagnificationState`: while `touchMagnificationActive` (and not already forced): clear
  `rampComplete`; if the `magnification` pref is off, **turn it on temporarily**; call the same start-magnify
  routine as a mouse enter (ramp-in); mark `forcedFisheye` and `forcingFisheye`.
- While `touchMagnificationSuppressed` (flag set by the touch handler; the reason is not decoded): turn
  magnification off and run the stop routine (ramp-out), again flagged forced.
- `touchMagnificationExitedBar`: clear `rampComplete` and `forcingFisheye`, run the stop routine, fade the label
  away, `myInBar = false`. `...ReenteredBar` just sets `touchMagnificationActive`.
- Each frame, when the `fish` animation finishes and touch magnification is active and forcing: set
  `rampComplete = true` and run the stored completion block asynchronously on the main queue
  (`touchMagnificationRampCompletionHandler`). `handleFishCompletionIfNeeded` then restores the original
  `magnification` value and clears all forced flags once forcing has ended.
- While the touch is active the **hover label is suppressed** until the ramp is complete
  (`touchMagnificationSuppressesLabel`: deferring-end, or active and not ramp-complete) [O, 0x100081494].
- `realScale` (the amplitude `R` above) is recomputed each frame as `R0 * fish`; it is not a separate timer.
Ramp timing is therefore section 3's formula: ≈ 160 ms in, ≈ 160 ms out at T=41 → L=128 [I from O].
[U] The Swift touch event handler (`DockBarTouchEventHandler.handleTouchEvent`, 0x1001ac964) that raises the
flags was not decoded; thresholds for entering/leaving by touch are unknown.

## 3. Entering and leaving [O]
`fish` is a Dock float animator with fields from / current / to / start-time / duration / easing. It is created
with easing = 1 (**cosine ease-in-out**); progress `p = clamp((now - start) / duration, 0, 1)`, eased
`e = 0.5 - 0.5*cos(pi*p)`, value `= from + (to - from) * e` (0x100050298, 0x1002d64dc). The easing value 1 is
passed when `fish`, `autoHide`, the label opacity and the tile animators are created (0x100252e30, 0x100252f78,
0x10004029c, tile init 0x10002e4f4).
Times are milliseconds.

- Pointer enters the bar (`handleDockEventMouseEnter`, 0x100094e6c) and `magnification` is on → `startFishing`:
  target `fish = 1`, destination size `L`. Pointer leaves (0x1000950bc) → `stopFishing`: target 0, size `T`.
- Duration (0x10005f174): `ms = floor(42 * ln(0.5 * |dest - (T + currentFish)| + 1) + 1)`, where `42` is the
  constant `fishSpeed` (0x10024da84) and `currentFish` is the extra size at call time. While a tile drag is in
  progress the duration is a flat 270 ms (`moveSpeed`, 0x10024da6c) instead.
- A new target starts **from the current value** with a duration recomputed from the remaining size gap, so an
  interrupted ramp is shorter (the log makes small gaps fast).

| size gap Δ (pt) | duration (ms) |
|---|---|
| 10 | 76 |
| 25 | 110 |
| 50 | 137 |
| 87 (T=41 → L=128) | **160** |
| 100 | 166 |
| 150 | 182 |

Eased strength for the 160 ms ramp: p = 0.1 → 0.024, 0.25 → 0.146, 0.5 → 0.5, 0.75 → 0.854, 0.9 → 0.976.
Reflow of tile positions (add/remove/reorder, not magnification) uses `moveSpeed` = 270 ms with the same easing
[O, 0x10024da6c, 0x100011460]; a sibling constant `dropSpeed` = 350 exists (0x10024da78), its use not traced.

Springs: `SpringBehavior`, `WASpringTimingFunction` and `SpringingPreferences` are **not on this path**. "Springing" is the spring-loaded-folder drag delay
(`com.apple.springing.delay`); `WASpringTimingFunction` occurs only inside a Swift type-name string, with no code
reference; I found no spring in the `fish`/`autoHide` animators [O for absence of references, I that it is unused here].

## 4. Hover title label [O, 0x100013b10, 0x1000147ec, 0x10005f6dc, 0x10007ffb8]
- `_titleHoverTimer` is **not** the Dock bar label: it is an ivar of the folder-stack grid layer
  (`DOCKStackGridLayer`, long-title expansion). The bar label (`DOCKLabelLayer`) has **no timer**.
- **Show:** every frame, when the pointer is in the bar, labels are enabled, no core drag/full screen/stack
  expansion, and touch isn't suppressing, the tile under the pointer (hit-tested against its *current magnified*
  rect) becomes the label's source. Attaching sets label opacity to 1 with duration 0: **no delay and no fade-in**;
  moving from icon to icon re-attaches instantly (no cross-fade). Exception: if autohide is on and the Dock was
  fully hidden, the label fades 0.001 → 1 over the autohide duration.
- **Position (bottom Dock):** centred on the tile's rect centre, rounded to whole points, kept on screen
  (a 2 pt margin is visible on the far side) [I]. Vertically the label's bottom edge is **5 pt above the tile's current top
  edge** when magnification is on; with magnification off it is `9 pt + distanceTop` above the tile top.
  Left/right docks use similar but not identical offsets (not decoded in detail).
- **Fade out** (`blendAwayLabel`, on pointer leave and touch exit): opacity → 0, cosine ease, **300 ms**; when
  autohide is on it takes the autohide animator's duration instead, so they finish together.
- `ECCartoucheGlassLayerController` is a separate glass pill, parameterised by an enum 1 or 2: radius 13 / 26 pt,
  rect side 16 / 24, corner radius 2.5 / 5, vertical offset 4 / 5 (0x10022037c family). Its use (label vs badge)
  is [U]; likely the status badge (`ECStatusLabelLayer`), so do not size the hover label from it.

## 5. Autohide [O, 0x10005f2a8, 0x10005f580, 0x100080d84, 0x100252e90]
- State is `autoHide` ∈ [0 shown, 1 hidden], same animator, cosine ease. The slide offset used by layout is
  `autoHideOffset = (distanceTop + T + distanceBottom + 10) * autoHide` (0x100080d84): 65 pt at T=43 (top 5, bottom 7).
- Duration (both reveal and hide): `ms = floor(ln(0.5 * Δ + 1) + 1) * autoHideSpeed`, where
  `autoHideSpeed = autohide-time-modifier * 60` (the pref defaults to 1.0) and Δ is the distance still to travel:
  hide `|autoHideOffset - (top + T + bottom + currentFish)|`; reveal `|size - (barThickness - autoHideOffset)|`
  with `size = L` if magnification is on and the pointer is already in the bar, else `T`.
- Typical results at defaults (T=43, L=130): reveal/hide **240 ms** (non-magnified), **300 ms** (when the pointer
  is in the bar with magnification). If `fish` is animating at the same time its duration is overwritten with the
  autohide duration so the two finish together.
- Hide starts immediately on pointer leave (no delay constant found); a temporarily-revealed Dock, an open menu
  or a modal can keep it up through separate paths (not traced).
- Reveal delay: pref `autohide-delay` (read at startup into the mouse handler, default **0.15 s**) feeds a
  one-shot main-queue timer started from the edge-entry path (0x100073a90) [I]. A second "dig" timer family
  (`digduration-duration` 0.25 s, `-perframe` 0.1 s, `-angle` 25, `-log` off) exists in the same handler.
  [U] exactly which gesture (edge dwell vs dig) uses which timer; the plain delay is 0.15 s.
- The label and the bar both fade/slide with this same duration while autohiding.

## 6. Glass and magnification [O/I]
- **Floor glass:** `ModernFloorLayer` / `LegacyFloorLayer` take the floor frame from the warped edges (section 1),
  so the glass strip widens and grows taller with `fish` [I: floor frame is written by the same routine, 0x1000128d4].
- **Per-icon glass backing:** `DockGlassMaterialLayerController` is built per tile at the **base layout size**;
  when the tile moves/scales, the Dock applies a scale transform (current rect / base rect) to the material layer
  instead of re-laying it out (0x1000889ac). I: corner radius, rim and blur therefore **scale with the icon**.
  The material's layout frame is the tile bounds inset by ≈ 9.4 % of the size (9.8 % on 2x displays), rounded [I].
- **Static recipes:** `effect.keySpread/fillSpread/diffuse*Scale` and friends are set once per glass variant at
  controller creation (0x1000d9138…0x1000d94f0); `NSGlassTintAmount` is read once from the environment
  (0x10028c918). I found **no per-frame dependence of these parameters on `fish`, `realScale` or the icon size**.
  `enableAdaptiveGlass` only picks the adaptive (luminance-following) variant. [U] anything the private
  CoreMaterial renderer does with size internally.

## How Folio should use this (design choices, not observations)
- Implement the warp, not a per-icon scale: lay icons on unmagnified slots, compute each slot's left/right edge
  `x' = x + D(x - c)`, draw icon = square of the warped width. This gives widening, neighbour push and anchored
  bottom for free. Use `F = 3T`, `R = 1.932 * (L-T) * fish`.
- Animate `fish` with `0.5 - 0.5*cos(pi*p)` over `floor(42*ln(1+Δ/2)+1)` ms; restart from the current value.
- Clamp `c` to the unmagnified toolbar span; hit-test against the magnified rects; show the label with no delay,
  5 dp above the magnified icon, fade out 300 ms.
- A hover pen reports raw positions; the Dock applies none, but S Pen hover jitter may justify a light filter [I].
- A toolbar with few icons rarely reaches the saturation zone, so keep `L - T` modest (e.g. 40 dp → 64 dp) or clamp
  total widening to the screen width as the Dock does with its shrink-to-fit.

## Unknowns / not covered
- Left/right/top docks and RTL were read only as "same maths on the other axis"; not verified numerically.
- The touch gesture recogniser (Swift) and its thresholds; the dig-timer gating; `inBarRect` side extents.
- Purpose of the cartouche glass sizes; any behaviour inside the private CoreMaterial/CoreAnimation renderer.
- Sizes were derived for T=41 → 43 internal; other `tilesize` values follow the inset table, not separately tested.
- Static analysis only: nothing was run against a live Dock. Durations are milliseconds on the code's own clock
  (`CACurrentMediaTime` × 1000), so frame-rate effects are not modelled.

## Evidence
All REA calls: `rea` 6.5.0 CLI/MCP, Ghidra 12.1.4, target sha256 `875c07b953756e5b…86a8ed`, one analysis session.
Raw outputs (decompiled text) live outside the repo, in the shared notes folder of the coordinating session; read
them only to verify a claim, never copy them into Folio.

| Claim | Address(es) | REA evidence (`procedure_pseudo_code`) |
|---|---|---|
| Per-edge sine displacement, saturation at ±F, tile rect and bottom anchor | 0x100011dcc | `ev_b6fe2fc0e04e…` |
| `fSize` = 3.0 (influence half-width = 3T) | 0x10024da90 | `ev_a5295bfc8e19…` |
| `fishSpeed` = 42, `moveSpeed` = 270, `dropSpeed` = 350 | 0x10024da84, 0x10024da6c, 0x10024da78 | `ev_f9027949a4cf…`, `ev_8a4726d56218…`, `ev_ed05ab32e621…` |
| `fishSize`, amplitude `R`, `currentFish`; pointer clamp to bar span; magnify only if `L > T` | 0x100011b38 | `ev_f08c82fbe0db…` |
| Fish ramp duration `floor(42*ln(0.5Δ+1)+1)`; dragging uses 270 ms | 0x10005f174 | `ev_8f677a59e7e7…` |
| `startFishing` (target L, fish 1) / `stopFishing` (target T, fish 0) | 0x10005f138 / 0x10005f26c | `ev_23f7d9419e30…`, `ev_d2c36e998079…` |
| Animator update: from/to/start/duration, cosine ease `0.5-0.5*cos(pi*p)` | 0x100050298, 0x1002d64dc | `ev_c27aeebcc9b7…`, `ev_efe4db355e68…` |
| Mouse enter → startFishing / startAutoShow; leave → stopFishing / startAutoHide / label fade | 0x100094e6c / 0x1000950bc | `ev_614f85581cf1…`, `ev_ac546888c0b6…` |
| Touch magnification state machine and ramp completion | 0x10008134c, 0x10008142c, 0x1000814dc, 0x10007fd6c | `ev_7b08f4ed1d48…`, `ev_4d75125c07da…`, `ev_b2d9ea373953…`, `ev_591595a70f37…` |
| Autohide duration (reveal / hide) with `autoHideSpeed` | 0x10005f2a8 / 0x10005f580 | `ev_5c425234de94…`, `ev_a134d05a6772…` |
| Autohide slide offset `(top+T+bottom+10)*autoHide` | 0x100080d84 | `ev_37d68b91266f…` |
| `autoHideSpeed = autohide-time-modifier*60`, animator creation (`fish`, `autoHide`), initial offsets | 0x100252678 | `ev_b56e5136fba4…` |
| Floor edges use the same warp; floor frame and in-bar rect (±10 pt constants) | 0x1000128d4 | `ev_ce8ca75c151f…` |
| Bar layout: unmagnified `barWidth`, shrink-to-fit, `largeSize = actual + 2*inset` | 0x100011700 | `ev_2ab396aa518e…` |
| Inset / distance table by tile size | 0x1000815a8 | `ev_34c3d7801043…` |
| Hover label position (±5 / 9+top), fade-out, opacity source | 0x100013b10, 0x10005f6dc | `ev_144eed7f7e41…`, `ev_6cb45537f4e1…` |
| Per-icon glass material scaled by transform (not re-laid-out) | 0x1000889ac | `ev_a44e62e6e069…` |

Checked by `otool` disassembly only (no REA evidence record): the selector → address map (own parser over the
Objective-C class lists, kept in the shared notes folder as `objc-selector-to-imp.tsv`); the animator constructor
argument `1` (cosine easing) at 0x100252e30, 0x100252f78 and the tile init 0x10002e4f4 (REA pseudocode drops the
argument); the `autohide-delay` default 0.15 s and the `digduration-*` defaults (0x100072010 onward); the cartouche
size constants (0x10022037c family); the always-true style flag 0x10016a85c. Full evidence IDs and the raw REA
JSON per address are in the shared notes folder under `dock-magnification-evidence/`.

