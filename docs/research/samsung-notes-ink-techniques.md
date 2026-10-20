# Samsung Notes: how its icons and ink textures are made (REA findings, for adaptation)

The user asked (2026-10-20) to reverse engineer Samsung Notes' icons and ink textures with REA, then
clarified: "we won't use, we will adapt for ourselves". This file records **techniques in our own words** so
Folio can build original equivalents. No Samsung images, SVGs, shader source or code may enter Folio.

Sources: Samsung Notes 4.4.45.37 `base.apk` pulled from the Tab with REA (`ev_1398df2f…`); resource table via
`aapt2 dump resources` (copy at `~/Developer/Folio-reference/samsung-notes/rea-evidence/aapt2-resources-4.4.45.37.txt`);
REA artifact inventory (`ev_fcbd1a0f…`); REA `search` (Ghidra 12.1.4) over the pen plugin libraries
(`lib/arm64-v8a/libSPen*.so`). Raw REA outputs are in `~/Developer/Folio-reference/samsung-notes/rea-evidence/`
(outside the repo; they contain Samsung strings, read them only to verify a claim).

## Icons and pen art (observed in the resource table)
- **Toolbar icons are vectors in two layers**: an outline (`comp_hw_toolbar_ic_<tool>`) and a fill layer
  (`…_color`) that the app tints with the tool's colour at runtime. Laser has a rainbow variant.
  → Folio: draw each tool icon as a Compose `ImageVector`/Canvas pair: outline + tinted "ink" part.
- **Pen illustrations in the settings popup are raster layers per pen**, each in 6 density buckets (WebP):
  `<pen>_body` (greyscale pen), `<pen>_body_hover` (lifted look while an S Pen hovers), `<pen>_effect` (the
  part that takes the ink colour: band, nib tip), `<pen>_effect_ruler`, `<pen>_mask` / `_mask_stroke`
  (tint masks), plus animated vectors `<pen>_av_selected/_unselected` for the rise-on-select animation.
  → Folio: draw pens procedurally (Folio's old `ToolShelf.drawTool` already does body + tinted parts);
  animate the selected pen rising 12 dp; show a hover lift.
- **Tape patterns are 9 SVG tiles** (`raw/tape_pattern_01..09`) with matching vector thumbnails.
  → Folio: generate its own pattern tiles in code (stripes, checks, dots, crosses, hearts, waves…).
- **Laser style previews** are 3 raster images (line, neon, dot). Lottie JSON animations are used for small
  affordances (bookmark toggle on/off in the page sorter, add page, hide/show memo, recording equalizer).
- Paper/cover patterns are PNGs (`cover_type_pattern_01..12`) — Folio has its own covers.

## Ink rendering per pen (observed in native-library strings; technique only)
No pen library embeds images (no PNG/KTX/ASTC/JPEG signatures). Textures are **generated at runtime**
(`buildTextures(width, height)` builds `mPatternBitmap`/`mPointBitmap`/`mPatternsTexture`/`mTextureBG`),
or come from the canvas (a "depth map" = paper tooth texture set by the app). Rendering is GL shaders.

| Pen (library) | Technique |
|---|---|
| Pencil (`libSPenPencil3`), Coloured pencil | **Stamp-based**: each sample stamps a procedurally generated tip mask; alpha = colour alpha × tip mask × **paper grain sampled in page (canvas) space** × per-point alpha (pressure/tilt) × a gain (~1.5). Grain fixed to the page means overlapping strokes catch the same paper tooth. |
| Crayon (`libSPenCrayon2`) | Same as pencil plus a **second canvas-space grain texture**, and a much higher gain (~5×, clamped) → broken, chalky coverage. |
| Airbrush | Stamps a soft radial pattern texture; alpha = colour × pattern (× opacity). |
| Watercolour | Two passes: stamps accumulate into a mask (coverage + wetness); the composite fades alpha up with wetness to a cap (~0.8) and **lightens/pools above a wetness threshold** → soft interior with darker, pooled edges; overlaps darken. |
| Oil paint | Bristle brush texture × brush mask × paper depth; a mixing pass **blends paint already on the canvas into the brush** (picks up and smears colour). |
| Smudge | Samples the canvas around each point (small box blur) and re-deposits it with a radial falloff, max ~40 % — smears existing ink. |
| Marker (`Marker4`) | Chisel/rectangular anti-aliased stamp drawn into a **per-stroke texture first, then composited at one alpha**, so a stroke never darkens where it overlaps itself; optional screen-space grain mask. |
| Fountain pen | Analytic circular/elliptic stamps with derivative-based anti-aliasing and a gradient across the nib; many versioned variants. |
| Calligraphy / Chinese brush | A tiled bristle texture sampled with per-stamp offsets; colour slightly darker where coverage is thin (ink edges). |
| Laser | Two-part trail: **soft outer glow** (radial falloff, low alpha) + **solid inner core** (width ratio); rainbow mode samples a colour gradient along the trail. |
| Tape | Each stamp is a **flat rotated ellipse** (aspect ≈ 1:4, rotated to the stroke direction) rendered as a signed-distance shape → a flat band; patterned tapes multiply in a pattern tile; the peeled state draws a thin border ("reveal border width"). |

## What Folio should do (adaptation)
- Folio already renders pencil with a procedural, seeded page-space grain (`StrokeArt.grain`, triangle mesh
  with per-vertex darkness). Keep that approach; tune it toward the pencil reference strip
  (`~/Developer/Folio-reference/samsung-notes/sketch-canvas/row-3-pencil.png`).
- **Tape**: render as a flat band (constant width, square-ish ends), opaque colour or a procedural pattern
  tile; peeled = 12–15 % fill + 1 dp outline. Draw tape above all other ink.
- **Laser**: transient overlay on the canvas surface (not saved): glow pass (blurred, low alpha) + core pass;
  styles solid / outlined (white core, coloured glow) / dot (only the pen tip); fade all trails together
  ~600 ms after the last pen-up over ~400 ms.
- **Dashed / dotted lines**: Android `DashPathEffect` on the centreline (or dash the outline mesh) with
  dash length proportional to the width.
- **Highlighter round tip**: round caps, same multiply blend as the chisel highlighter.
- Watercolour, oil, smudge, crayon belong to a future **Drawing (sketch canvas)** feature; reference strips
  for each are in `~/Developer/Folio-reference/samsung-notes/sketch-canvas/`.
