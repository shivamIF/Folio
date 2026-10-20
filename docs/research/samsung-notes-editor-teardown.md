# Samsung Notes note editor: UI/UX teardown (clean-room spec)

Target: Samsung Notes **4.4.45.37** on the user's Galaxy Tab S6 Lite (SM-P615, adb `R52R904TYYA`, Android 13,
2000×1200, **280 dpi override → 1 dp = 1.75 px**). Studied 2026-10-20 by black-box observation: REA screen
captures plus `uiautomator` view trees, taps and injected stylus strokes (`adb shell input stylus …`), all
inside one scratch note ("Handwritten note 20/10", left for the user to delete). Static facts come from the
APK's resource table (`aapt2 dump resources`) and REA/Ghidra string search of native libraries.

**Clean-room rule:** this file describes behaviour and measurements only. Folio's implementation must be
original code, icons and textures (CLAUDE.md). Reference screenshots and view trees live **outside the repo**
in `~/Developer/Folio-reference/samsung-notes/captures/<step>/{screen.png,ui.txt}`; the step names are
cited below as `[cap NN-name]`. Never copy them into the app or the repo.

Labels: *Observed* = seen on the device or in the resource table; *Inferred* = my interpretation.

REA evidence: package `ev_27e219a9…`, APK pull `ev_1398df2f…` (base.apk sha256 `daed1eff…`), APK inventory
`ev_fcbd1a0f…`/`ev_a7244222…`, first editor capture `ev_82e91032…`. Native-library evidence: see
[samsung-notes-ink-techniques.md](samsung-notes-ink-techniques.md).

---

## 1. Screen layout (observed)

| Part | Size | Notes |
|---|---|---|
| Status bar | 24 dp | Hidden in full screen |
| App bar | 56 dp | Back (60 dp wide), title (tap to rename, inferred), actions 46 dp wide each, overflow 36 dp. 1 px divider under it |
| Toolbar strip | 42 dp | Directly under the app bar, full width, own background, 1 px border top and bottom |
| Canvas | rest | Pages on a desk colour; page number "1/3" in small grey text at the page's bottom-right |
| Zoom pill | ~106×46 dp | Top-right of the canvas while zooming: "125%" ⎮ fit-to-screen button; fades out after [cap 50-easy-pad] |

Landscape: toolbar content (≈ 895 dp) is **centred**. Portrait: it doesn't fit, so the strip **scrolls
horizontally** and starts at the left edge (user explicitly wants this) [cap 43-relaunch, 45-toolbar-scrolled2].

## 2. Dark-theme colours (observed pixel samples)

| Token | Value | Where |
|---|---|---|
| appBar | #000000 | app bar and status bar |
| divider | #434343 / #3C3D43 | under app bar; toolbar top/bottom border |
| toolbar | #252525 | toolbar strip |
| toolSelected | #5A5A5D | circle behind the selected tool (≈32 dp) |
| desk | #2F2F32 (top rows #29292C) | around pages |
| page (dark) | #030303 | page paper in dark mode |
| popup | #2D2D30 | every popup card, radius ≈ 26 dp |
| sliderTrack | #47474A | slider track (14 dp tall) |
| sliderDot / thumb | #77777A | 10 step dots; numbered thumb circle 28 dp |
| pill / pillSelected | #38383B / #4F4F51 + 1 dp light border | line-style pills, tabs |
| pageDot / pageDotCurrent | #4D4D52 / #848487 | palette page indicator |
| text | #FFFFFF (body), #FCFCFF (switch thumb off) | |
| accent | One UI blue (≈ #3E91FF) | radio on, switch on, selection boxes |

Light theme was not observed (the tablet stays in dark mode; don't change system settings to see it).
Folio defines its own light equivalents (white app bar, #F2F2F2 toolbar, white popups, #E3E3E6 pills).

### Dark-mode ink adaptation (observed, exact)
Per note setting **"Adjust colour in Dark mode"** (Page settings, default on). Display only; stored colours
don't change. Rule that reproduces every sample: convert to HSL; **if L < 0.5 show HSL(h, s, 1 − L)**, else
unchanged. #000000 → #FFFFFF, #249150 → #6EDB9A; #FF1010, #EB7317, #0F87FF unchanged. Page paper goes dark
(#030303). The toolbar's quick-colour dots use the adapted colour too.

## 3. Toolbar (observed) [cap 02-newnote, 03-again]

Order (left → right): **Text mode** (keyboard), **Pen**, **Highlighter**, **Eraser**, **Selection** │
divider │ **5 quick colours** │ **3 quick thicknesses** │ **Undo** │ **Redo** │ **S Pen to text** │
**Change style** │ **Tape** │ **Easy writing pad** │ **Laser pointer** │ divider │ **Customise** (2×2 circles icon).

- Tool buttons 32×32 dp, icon 24 dp, gap ≈ 11.4 dp. Quick colour cells 34 dp wide with a 20 dp dot.
  Quick thickness cells 34 dp wide showing a short horizontal bar of that weight. Dividers 1 px × 18 dp.
- **Pen / highlighter icons are two layers**: an outline glyph plus a colour layer tinted with the tool's
  current colour (so the pen icon shows red when the pen is red) — resource names
  `comp_hw_toolbar_ic_<pen>` + `comp_hw_toolbar_ic_<pen>_color`. Same for laser (`…_laser_pen_color`,
  `…_color_rainbow`) and Change style (colour view).
- **First tap selects a tool; tapping the selected tool opens its popup.** Applies to Pen, Highlighter,
  Eraser, Selection, Change style, Tape, Laser. Quick colours and quick thicknesses work the same way:
  first tap selects the slot; tapping the selected slot opens the colour picker / the thickness slider for
  that slot [cap 26/27, 30/31].
- **S Pen to text** and **Easy writing pad** are toggles (no popup). Text mode puts a text cursor in the
  note body.
- Undo/Redo disabled state = dimmed icon. Undo is long-clickable (inferred: history list; not explored).
- Defaults: quick colours #FF1010, #EB7317, #0F87FF, #249150, #000000; pen quick thicknesses 5, 10, 31.
- The Customise button opens the **Customise toolbar** screen directly (§17).

## 4. Popup frame (observed, shared by every tool popup)

Card 300 dp wide, radius ≈ 26 dp, colour `popup`, soft shadow, appears **16 dp below the toolbar**,
**horizontally centred on its toolbar button and clamped to the screen edges** (e.g. the laser popup is
pinned to the right edge). Title row 60 dp: title text (or header icons) left, close button right (32 dp
circle, lighter fill, ✕). Outside tap or Back closes it. Rows: slider 56 dp, switch row 48 dp, radio row
48 dp, palette 50 dp + page indicator 28 dp. Section separators are dotted lines.

**Number slider** (used everywhere): "−" button 32 dp │ track (rounded, `sliderTrack`) with 10 evenly spaced
dots │ "+" 32 dp; the thumb is a 28 dp circle showing the current value; the − button disables at the minimum.
**Opacity slider**: same, track is a checkerboard→colour gradient and the thumb is tinted with the colour.

**Palette** (pen, highlighter, change style, tape, laser): a pager of 5 pages with a dot indicator whose
first item is a clock icon.
- Page 1 = **recent colours** (up to 7), then an **eyedropper** button and a **full-picker** button (grid of
  coloured dots icon).
- Pages 2–5 = 8 fixed colours each, plus a fixed slot at the right showing the current colour inside a
  rainbow ring (opens the picker). Swipe to change page. Selected colour shows a white ✓.
- Folio must use **its own colour sets** (structure only).

## 5. Pen popup [cap 10-pen-popup, 16-fav-mode, 11..14 mini]

Header: **Favourite pens** toggle (pen-with-star icon) · **Add to favourites** (star) · … · **Shrink**
(inward arrows) · **Close**.
1. **Pen type row** (100 dp tall): pen illustrations 36×67 dp, 49 dp apart, standing on a line; the selected
   one is raised 12 dp and shows a small squiggle of its current stroke above it. Samsung's set: Fountain pen,
   Calligraphy pen, Pen, Pencil, Calligraphy brush. **Folio keeps all of its own pen types here** (fountain,
   ballpoint, fine liner, brush, marker, airbrush, pencil); make the row scroll if it overflows.
   Each illustration = greyscale body + a colour band/tip tinted with the current colour (Samsung builds
   them from `_body`, `_effect` and `_mask` layers; Folio draws its own in code).
2. **Size**: number slider (Samsung 1–? with dots; value 5/10/31 seen).
3. **Line style pills** (57×32 dp each): **Fixed thickness**, **Variable thickness** (pressure, default),
   **Dashes**, **Dotted line**.
4. **Straighten lines** switch.
5. **Palette** (§4).
- Folio extras to keep (user's request): **Stroke stabilisation** slider and pressure sensitivity, in the
  same visual style, below the switch.

**Favourites view**: grid of saved presets (illustration + squiggle in its colour), a "+" circle to add the
current pen; header becomes [Pen mode toggle (highlighted)] … [⋮ → Add, Delete] [Shrink] [Close]. Tapping a
favourite selects it.

**Shrink (mini) mode**: popup collapses to a 52 dp-wide vertical strip docked at the left edge under the
toolbar: Favourite pens icon, current pen (horizontal), colour dot, thickness dot, divider, close. Tapping
pen / colour / thickness turns the strip into a vertical list (all pen types incl. highlighters / the
palette vertically / sizes) with a "Collapse" button at the bottom. Closing it and tapping the pen again
opens the full popup.

## 6. Highlighter popup [cap 20-hl-popup]
Two types: **Highlighter** (chisel) and **Marker pen** (round tip). Size slider, **Opacity** slider (33 %
default), **Straighten lines**, **Adjust thickness to text** (inferred: the highlight height snaps to the
height of the text/handwriting it covers), palette (recent page first).

## 7. Eraser popup [cap 21-eraser, 22-area-eraser]
Title "Handwriting eraser". Radios **Stroke eraser** / **Area eraser**. Area eraser adds a size slider
(1–10). Switches **Erase highlighter only**, **Erase tape only**. Dotted divider, then a bold
**Erase all handwriting** button. (Folio's partial eraser = Area eraser; keep Folio's size slider.)

## 8. Selection popup and lasso UI [cap 24-selection, 87-lasso, 88-lasso-more, 83-exit-drawing]
Popup: radios **Lasso** / **Rectangle**, switch **Include partially selected objects** (on by default).
After selecting: thin blue box, white round handles at the corners, a **rotate handle** (circle with a
rotate arrow) on a stem above the top edge, and a **floating dark rounded menu bar above the selection**:
`Cut · Copy · Paste · Delete · Change style · Move forwards · ⋮` (⋮ → `Move backwards`). For a picture the
bar is `Cut · Copy · Paste · Delete · Crop · Drawing · Anchor to text · ⋮` with 8 resize handles.

## 9. Quick colours and the colour picker [cap 26..29, 28-spectrum]
Floating panel ≈ 360 dp wide anchored under the colour group, extends down the screen.
- Tabs (pill style): **Swatches** | **Spectrum**.
- Swatches: 13×13 grid of 24 dp cells: a grey column (white→black) + 12 hue columns (red, orange, gold,
  yellow, green, spring green, cyan, azure, blue, violet, magenta, pink), light (top) → dark (bottom).
  Generate it in code.
- Spectrum: field with hue across and saturation down (white at top), circular marker; below it a
  brightness slider (black → colour) with an editable "100 %".
- Preview chip split in two (current | new), editable **Hex**, **Red**, **Green**, **Blue** fields.
- Dotted divider, **5 recent colours** (42 dp circles) + **eyedropper**, then **Cancel | Done**.

## 10. Quick thicknesses [cap 30..34]
Each slot stores its own value per tool (pen defaults 5 / 10 / 31). Tapping the selected slot opens a
slider-only card (300×56 dp) centred under the group.

## 11. Change style [cap 37..40]
Tool + popup (title, size slider default 20, palette). **Sweeping it across strokes recolours every touched
stroke as a whole and sets its thickness to the popup size** (observed: thin red line → thick blue line).
One undo step per sweep (inferred).

## 12. Tape [cap 42-tape-popup, 46..49]
Popup: title "Tape", **Hide tape** icon (hides/shows all tape), close; a preview box (80 dp tall, rounded)
showing a sample strip; size slider 1–10; **Straighten lines**; **Adjust thickness to text**; dotted
divider; tabs **Colour | Pattern**; Colour → palette; Pattern → 3×3 grid of 9 pattern chips (68×24 dp):
crosses, stripes, checks, polka dots, grid, vertical stripes, hearts, waves, half-circles (Folio draws
its own procedural patterns); dotted divider; **Remove all tape**.
Behaviour: draws an **opaque strip that hides what's under it**. **Tapping a tape peels it** (it turns into a
faint translucent band with a thin outline in the tape colour, revealing the ink); tapping again covers
it. Eraser has an "Erase tape only" switch.

## 13. Laser pointer [cap 58-laser-popup, 59-laser]
Popup: title + close; three style chips **Solid**, **Outlined (white core)**, **Dot**; radios **Rainbow** /
**Single colour**; palette (single colour). Drawing shows a glowing trail (bright core + soft glow) that
**fades out about 0.5–1 s after the pen lifts**; nothing is saved. Also available in Reading mode via the
app bar's "Laser pointer on/off" button. Not usable on sticky notes (toast).

## 14. Easy writing pad [cap 50..56]
Toggle. The page zooms to fit; a **magnifier window** (blue rectangle with a resize triangle at its
bottom-right) appears on the page at the top-left of the writing area; a **pad** docks at the bottom of the
screen: a top bar (drag handle ⋮⋮ at left; **Previous ‹**, **Next ›**, **Enter ↵** at right) and a large
writing area showing the window's content magnified (≈ 2.8×), with a draggable blue **end marker** near
the right edge (area past it is shaded). Writing in the pad lands, scaled down, inside the window.
- Next → window moves right by its own width. Enter → next line (window height down) at the left margin.
- Previous at the start of a line → end of the previous line (window right-aligned).
- Inferred: writing past the end marker advances the window automatically.
- Back closes the pad. The current tool (pen, tape…) is used in the pad.

## 15. S Pen to text [cap 35, 36, 92-spen-text]
Toggle tool. Handwriting is recognised after a short pause and **replaced by typed text in a text box at
the place it was written** (selected, with handles and a cursor). A small floating bar appears over the
toolbar: **Settings** (gear: recognition language), **Keyboard**, **Space**, **Backspace**, **Enter**.
Folio: ML Kit Digital Ink Recognition (user approved), one-time language model download with progress.

## 16. Text mode (keyboard icon) [cap 89-textmode]
Typed body text flowing from the top of the page; the soft keyboard opens and a **rich-text toolbar docks
above it**: Checklist, Font colour, Font background, Text format (paragraph style), Font size (17), Bold,
Italic, Underline, Strikethrough, Numbered list, Bullets, Align, Indent, Outdent, Text box. Folio has
text boxes (plain text, size, colour); map Text mode to the text box tool and grow the formatting set.

## 17. Customise toolbar screen [cap 61-settings-menu, 62..64]
Full-screen settings page: app bar "Customise toolbar" + **Reset**. Section **Preview** (a live mini
toolbar). Section **Available tools** + "Drag tools to remove or reorder them." Rows 56 dp: icon, title,
drag handle (≡) at right. **Pen colour ⌄** → 3 / 4 / 5 colours; **Pen thickness ⌄** → 1 / 2 / 3
thicknesses. A divider row "Drag tools below this line to remove them." Default above the line: Pen colour,
Pen thickness, Undo, Redo, S Pen to text, Change style, Tape, Easy writing pad, Laser pointer. Below (hidden):
**Auto-fix shapes**, **Lock canvas**, **Clean up handwriting**. Text mode, Pen, Highlighter, Eraser and
Selection are fixed (always first, not listed).

## 18. App bar and menus
- Normal: Back · Title · **Reading mode** (open book) · **Insert** (+) · **Page sorter** · **Full screen** · ⋮.
- **Reading mode** [cap 78-reading]: toolbar hidden; actions become Reading mode (on) · **Laser pointer on/off**
  · Insert · Full screen · ⋮ (no page sorter). The pen doesn't write.
- **Full screen** [cap 90-fullscreen]: status bar and app bar hidden, toolbar stays at the very top, a
  small dark round **exit full screen** button floats at the top-right under the toolbar.
- **Insert** (+) [cap 70-insert]: `PDF`, `Voice recording` ┄ `Image`, `Camera`, `Document scan`,
  `Audio file`, `Drawing`, `Sticky note`, `Table`. Menu = rounded card, icon + text rows 44 dp, dotted group
  dividers.
  - **Sticky note** [cap 71, 72]: a floating square memo (~440 dp) with a decorative tape strip on top,
    **Minimise** and ⋮ (Background colour: warm yellow, mint, shell pink, light blue; Delete — confirmed
    by a bottom dialog "Delete sticky note?"), a resize handle at the bottom edge; you write on it with the
    pen; minimised it becomes a small icon at the page's top-left.
  - **Table** [cap 75]: dialog "Table properties" with Rows × Columns pickers (default 2 × 3), Cancel/Done.
  - **Drawing** [cap 76, 80, 81]: a full-screen sketch canvas (paper texture) with an art-brush tray
    (Water colour, Oil paint, Calligraphy brush, Pencil, Tilt pencil, Smudge, Airbrush, Marker, Crayon), a
    palette panel, bottom pill Eraser/Undo/Redo, Done; leaving it inserts the drawing as a picture.
    Brush behaviour: [samsung-notes-ink-techniques.md](samsung-notes-ink-techniques.md).
  - Not explored: PDF, Voice recording, Camera, Document scan, Audio file (they open system pickers or
    record; avoid on the user's device).
- **Page sorter** [cap 79-sorter; user's screenshot 2 for landscape]: left panel with a 1 px border
  (portrait 208 dp, one column; landscape ≈ 404 dp, two columns). Header: "**4/4**" (current page number is
  editable to jump) and, in landscape, **Select**, **Bookmarks** filter, **Search**; portrait shows ⋮.
  Thumbnails with a **bookmark toggle** (top-right) and **⋮** (bottom-right), page number under each, the
  current page outlined in blue, a "+" tile at the end.
- **⋮ More** [cap 66-more]: `Search`, `Note style`, `Page settings` ┄ `Add note to ›`, `Add tags`,
  `Save as`, `Turn on finger drawing`, `Turn on Maths solver` ┄ icon row: Add to favourites, Lock, Share,
  Print, Delete.
  - **Page settings** [cap 67]: Scrolling direction **Vertical / Horizontal** (radio rows with
    illustrations); **Background colour** (Transparent, Light grey, Light beige, Pastel pink, Pale blue, Black,
    + more pages); **Adjust colour in Dark mode** switch.
  - **Note style** [cap 68]: tabs Add cover | Page; categories Basic / Academic / Planners / Journals;
    templates Blank page, Lined, Lined (narrow), Lined (wide), Grid, Grid (narrow), Grid (wide), Dot,
    Dot (narrow) …; Apply.

## 19. Open questions (not observed yet)
Light-theme colours; what long-press on Undo does; Auto-fix shapes / Lock canvas / Clean up handwriting
behaviour (toolbar-only tools, hidden by default); Search inside the note; how bookmarks filter;
horizontal scrolling feel; voice recording UI. Check them inside the scratch note when needed.
