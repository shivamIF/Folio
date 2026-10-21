# macOS Liquid Glass and the Dock: what Folio's glass is based on

Research for the Glass UI work (branch `claude/folio-glass-ui-design-bf518c`, 2026-10-21).
Target: the user's MacBook, macOS 27.0 (build 26A5378j). Tool: REA 6.5.0 (`rea inspect-plist`,
`rea search`, `rea inspect-asset-catalog`). This note is in our own words. **No Apple code,
shader or image is used in Folio.** Only numbers are reused: material parameters and
colour matrices, read from plists.

Evidence IDs below are REA Evidence records (first 16 hex digits).

## 1. Where the Dock's glass comes from

- `/System/Library/CoreServices/Dock.app/Contents/MacOS/Dock` (arm64, sha256 `875c07b9…a8ed`).
  Its strings name the pieces: `DockGlassMaterialLayerController`, `ECRoundGlassLayerController`,
  `ECCartoucheGlassLayerController` (the glass label shown above a hovered icon),
  `RimLayerController` / `_innerRimLayer` (the bright edge), `DLMaterialSDFTracker`,
  `CASDFLayer`, `SDFElementCornerRadius` (the glass shape is a signed-distance field, which is
  what lets the edge refract), `_ECVibrantTextLayer` (`_vibrantCompositingFilter`), and
  accessibility monitors `AXReduceTransparencyMonitor` / `AXIncreaseContrastMonitor`.
  Ghidra procedure search: ev_83fcadf3db0f12cb (the magnification maths is in a separate
  note, `macos-dock-magnification.md`).
- The material parameters themselves are plists in
  `/System/Library/PrivateFrameworks/CoreMaterial.framework/Versions/A/Resources/`.
- The Dock's asset catalog only holds app-icon colours, with no glass art
  (ev_f40bdb41e5026d6c).
- The user's Dock prefs: tilesize 41, largesize 128 (×3.1 magnification), magnification on,
  autohide on, bottom.

## 2. Materials: how the backdrop is treated

Every material is the same pipeline with different numbers:

1. Sample the backdrop at **backdropScale 0.25** (quarter resolution). This is the main
   performance trick, and Folio does the same.
2. Multiply **saturation** (1.1–2.0). Glass looks *more* colourful than what's behind it.
3. Add **brightness**.
4. Push luminance through a **curve** (`luminanceValues`, four control values; we read them as
   a cubic Bézier over L = 0…1), mixed in by `luminanceAmount`. This step guarantees
   legibility: light materials squeeze any backdrop into a light band and dark materials into
   a dark band, so text on top always has contrast.
5. **Blur** (`blurRadius`, points; `blurAtEnd: true`).

| recipe | blur | sat | bright | luminance curve | amount | evidence |
|---|---|---|---|---|---|---|
| dockLight | 30 | 1.8 | 0.08 | 0.3, 0.5, 1, 0.77 | 0.5 | ev_05faafeffd77d2a5 |
| dockDark | 30 | 1.6 | 0 | 0.29, −0.2, 0.375, 0.65 | 0.5 | ev_e3e7ebc457752146 |
| platformContentUltraThinLight | 22.5 | 1.1 | 0.12 | 0.45, 0.55, 0.65, 0.68 | 0.5 | ev_38b4fdd2d3246978 |
| platformContentThinLight | 30 | 1.35 | 0.12 | 0.725, 0.825, 0.76, 0.73 | 0.6 | ev_1ae39c9664fcc568 |
| platformContentLight | 30 | 1.5 | 0.1 | 0.9, 0.83, 0.925, 0.815 | 0.75 | ev_20fcf3fbd4f40fe3 |
| platformContentThickLight | 45 | 1.5 | 0.045 | 0.99, 0.95, 0.98, 0.905 | 0.88 | ev_0e69098c8d74eb92 |
| platformContentUltraThinDark | 22.5 | 1.1 | 0 | 0.24, 0.24, 0.3, 0.39 | 0.5 | ev_cd262fd997e48edf |
| platformContentThinDark | 30 | 1.35 | 0 | 0.2, 0.21, 0.1, 0.15 | 0.6 | ev_2dacff3c1cfaeea0 |
| platformContentDark | 30 | 1.5 | 0 | 0.16, 0.26, 0.1, 0.1 | 0.75 | ev_d07e5ba68c982379 |
| platformContentThickDark | 45 | 1.5 | 0 | 0.14, 0.16, 0.1, 0.03 | 0.88 | ev_066574a621535fc4 |
| platformChromeLight | 22.5 | 1.1 | 0.1 | 0.8, 0.9, 1.1, 0.825 | 0.75 | ev_649cca337f14ffc5 |
| platformChromeDark | 22.5 | 2.0 | −0.1 | 0.23, 0.52, 0.27, 0.255 | 0.75 | ev_bd0ab06cfaf61197 |
| toolbarButtonBackground | 15 | 1.1 | – | 0.24, 0.24, 0.3, 0.39 | 0.5 | ev_2baaaf2ca2799589 |

The "Glass" recipes (`platformContentGlass*`, `platformSelected`, `platformPinched`) are a
plain colour matrix (offset 0.235, gain about 0.66, mild saturation) with blur 45. That is the
selected/pressed platter (ev_7003b970378382d0, ev_4580ee5dd2f9ca6f).

Reduce transparency (`platformChromeLightReduceTransparency`, ev_137a9cb8ea8e2a8f) turns the
blur off and uses the backdrop's *average colour* instead. Folio's "Reduce transparency" draws
an opaque tile of the material's colour.

## 3. Text on glass: vibrancy

Labels on glass are not drawn in a fixed colour. Each label style is a **colour matrix
applied to what's behind the glyphs** (`filterType: vibrantColorMatrix`, styles in
`platformFillLight/Dark.visualstyleset`, ev_021c8e3012cef8c5 and ev_9eb338b7b7af01d5;
strokes in `platformStrokeLight/Dark`, ev_fd7630b7abb0f93b and ev_f1bc899d8b31ffa4).

Decoding the matrices (they factor cleanly into Rec. 601 luma weights 0.299/0.587/0.114):

| style | light glass | dark glass |
|---|---|---|
| primary | 0.375·L + 0.0625, saturation ×5.4 | 0.5·L + 0.5, saturation ×3.5 |
| secondary | 0.85·L, saturation ×1.4 | ≈0.82·L + 0.18 |
| tertiary | ≈0.96·L − 0.084 | ≈0.97·L + 0.12 |
| separator | ≈0.72·L + 0.019 | ≈0.70·L + 0.298 |

In plain words: on light glass, primary text is a strongly darkened, over-saturated copy of
the backdrop, so it picks up a hint of the colour behind it. On dark glass it is lightened
towards white. Because the material already squeezed the backdrop's luminance (§2), these
fixed matrices always land at a readable contrast. The tab-bar text in window chrome uses a
gentler matrix (`platformChromeFillLight/Dark`, ev_fce64e2c1794c4ae / ev_d37a3d6936ed5f89).
Accent colours have their own vibrant matrices (`platformColorsLight`, ev_987fb6d79678d67b).

**Folio's version** (`ui/glass/GlassMaterial.kt`): we apply the same matrices to the
material's *expected* colour over a typical Folio page rather than per pixel. Per-pixel
backdrop sampling for every glyph would be too expensive on the Tab S6 Lite. Then we push
the result until it meets WCAG contrast: 4.5:1 for primary and 3:1 for secondary, or 7:1 and
4.5:1 with Increase contrast. Unit-tested in `GlassMathTest`.

## 4. The edge: SDF lens and rim

The Dock draws its glass as an SDF layer (`CASDFLayer`, `SDFElementCornerRadius`) with a
rim (`RimLayerController`, `RimKind`) and a key-light highlight whose diffuse amount, height
and spread scale (`GlassKeyFillHighlight::get_diffuse*Scale`, SwiftUICore imports). The
distance field is what makes Liquid Glass refract: near the rim, content behind is pulled in
from further out, as if seen through the rounded edge of a lens.

**Folio's version** (`ui/glass/GlassShader.kt`, Android 13+ AGSL): a rounded-rect SDF.
Within `lensWidth` of the rim the backdrop is sampled outwards along the edge normal by
`lens · t³`, where t goes 0→1 towards the rim. The rim is a diagonal gradient stroke, bright
top-left and softer bottom-right, plus a top sheen, all drawn in Compose. All of these scale
with Settings › Highlight / Lensing.

## 5. What runs where on the tablet

- Galaxy Tab S6 Lite: Android 13 (API 33), so AGSL is available. Cross-window blur is
  **disabled** (`mBlurEnabled=false` in `dumpsys window`), so system window blur can't be used.
- The editor page is a `SurfaceView` (InkCanvasView). Compose can't record it, so glass over
  the page samples a quarter-resolution `PixelCopy` of it (`SurfaceBackdropSampler`). Copies
  are throttled to ≤24 per second, run off the main thread and only refresh on change.
- Library content is Compose and is recorded live into a GraphicsLayer (`glassBackdropSource`).
- Android 12: blur plus a colour matrix (the curve linearised). Android ≤ 11: frosted tint, no blur.
