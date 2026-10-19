# Samsung Notes `.sdocx` format (as decoded by Folio)

This was reverse-engineered independently from five of the user's own notes (exported from Samsung Notes on One UI, 2024–2026) for read-only import. No Samsung code or assets are used. The research tool is `scripts/formats/sdocx_probe.py`; the importer is `core/interop/SamsungNotesImporter.kt`.

**Locked notes are not supported.** A password-protected note is encrypted with the owner's password. Folio refuses it and asks the user to unlock it in Samsung Notes and export it again. It does not try to bypass the encryption.

All integers are little-endian.

## Container
A ZIP. Entries may sit under a folder prefix; Folio finds them by the name of `pageIdInfo.dat`.

| Entry | Contents |
|---|---|
| `note.note` | Note-level metadata (not used yet) |
| `pageIdInfo.dat` | Page order. `u16` count at 0x20, then entries of `u16 36` + page id as UTF-16LE (72 bytes) + extra bytes |
| `<pageId>.page` | One page: header plus a list of objects |
| `media/` | Attachments: PDFs (`N@pdf_*.pdf`), cached tiles (`.spi`), embedded sticky memos (nested `.sdocx`) |
| `end_tag.bin` | Trailer; mentions encryption for locked notes |

On Android, One UI labels these files with MIME type `application/sdoc`.

## Page header
- u16 flags at 0x0E; u32 width at 0x16 (1200 in every sample) and u32 height at 0x1A. Folio scales the page so its width is 1000 units (Folio's A4 width).
- Optional blocks start at 0x80: flag 0x1 adds 32 bytes, flag 0x10 adds 4; then 8 bytes (colour, a width).
- **Flag 0x100: PDF background.** Then comes `{u16 1, u32 media id, u32 PDF page (0-based), 8 zero bytes, u32 w, u32 h}`. The PDF is `media/<media id>@….pdf`. Verified on every page of both PDF-backed samples (one note switches between two PDFs).

## Object
`u16 36` + object id as ASCII UUID, `i64` time, `f64` bbox[4] (left, top, right, bottom, **after** any rotation), then 5 zero bytes and, when the object was rotated, an `f32` angle in degrees. Then come optional properties, e.g. `extra_key_stroke_shape` (shape tool) or `RecogUIFeature_MathStrokeUuidStr…` (handwriting recognition). These can be kilobytes long, so the header search covers the whole object. Then the page marker `u32 1200` + `u32 height`, and within about 64 bytes:

`u32 size1`, `u16 type`, `u32 size2`, payload.
- **Type 1, a stroke:** payload starts `0x02`; `size1 − size2` is 24/28 for pen strokes and 16–36 for shape-tool strokes.
- **Type 6, a shape or text object:** payload `01 00 01 <subtype>`.

## Stroke payload
| Offset | Field |
|---|---|
| 0 | 6-byte header. **Byte 4, bit 0x04: the stroke stores its own colour** (0x8E yes, 0x8A no) |
| 6 | `u16` unknown |
| 8 | `u16 n`, the point count |
| 10 | `f64 x0`, `f64 y0`: the first point |
| 26 | `(n − 1) × (i16 dx, i16 dy)`: **sign-magnitude** deltas in 1/32 units |
| then | `f32` base pressure + `(n − 1) × u16` sign-magnitude deltas in 1/4096 |
| then | Time, tilt and orientation streams (not decoded yet) |

**Trailer**, at `tail = payload + size2`:
- **With colour:** RGBA bytes at `tail−6 … tail−3`, size `f32` at `tail−2`. Alpha below 0xF0 means a highlighter.
- **Without colour:** size `f32` at `tail−6`, repeated at `tail+2`. The colour is the default, black.
- Before this rule, about 1,100 strokes were misread as translucent "highlighters"; with it, all 35,860 strokes have a plausible size.

**Rotation:** if the points don't fit the object's box, rotate them by **−angle** about the box centre. This made all 367 rotated strokes in the samples fit exactly; the importer applies it only when it does.

Folio width = size × `WIDTH_FACTOR` (2.0) × page scale. The user compared it with Samsung Notes and confirmed it.

## Type 6
- **Subtype 0x0C, a text box:** 4 frame points; then a text block `u16 6, u32 block length, u32 chars, UTF-16 text`; then style spans: `u32 count`, records of `length + 2` bytes `{u16 length, u16 kind, …, value at +18}`. Kind 3 is the font size (f32, in points on an A4-wide page) and kind 1 the colour (ARGB). Seen once: a red 23 pt title.
- **Subtype 0x08, a vector shape:** `u32 n`, n × (f64 x, f64 y), and the line width (f32) 13 bytes after the points. Seen as an arrow (line plus two heads). Imported as an even-width stroke.

## Validation (5 notes)
| | Count |
|---|---|
| Strokes decoded, all inside their own boxes | 35,860, up from 33,105. Newly read: strokes with recognition data, shape-tool strokes, and 367 rotated strokes (categories overlap) |
| Highlighters | 747 |
| Pages with a PDF background | 49 |
| Text boxes | 1 |
| Vector shapes | 3 |
| Not imported | 1 attached sticky memo (an embedded `.sdocx`), 2 objects |

A synthetic test builds an `.sdocx` byte by byte covering each rule (`SamsungNotesImporterTest`).

## Title
Samsung exports are named `<title>_<yyMMdd>_<HHmmss>.sdocx`. Folio takes the title from the file name, because the title inside `note.note` wasn't reliable across samples.

## Not imported yet
- Pictures: none of the samples contains one, so the format is unknown.
- Attached sticky memos.
- Stroke time, tilt and orientation streams.
