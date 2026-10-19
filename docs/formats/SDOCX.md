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

## Page
- `u32` width at 0x16 (1200 in every sample) and `u32` height at 0x1a.
- Folio scales the page so its width is 1000 units (Folio's A4 width).

## Object
`u16 36` + object id as ASCII UUID, `i64` time, `f64` bbox[4] (left, top, right, bottom), more fields, then the page marker `u32 1200` + `u32 height`. Within about 64 bytes after the marker:

`u32 size1`, `u16 type`, `u32 size2`, payload. For strokes `type = 1`, `size1 − size2 ∈ {24, 28}`, and the payload starts with `0x02`.

Other types (text, images, shapes) are skipped. The import reports how many it skipped.

## Stroke payload
| Offset | Field |
|---|---|
| 0 | 6-byte header (flags vary: `02 25 …`, `02 65 …`, `02 05 …`) |
| 6 | `u16` unknown |
| 8 | `u16 n`, the point count |
| 10 | `f64 x0`, `f64 y0`: the first point |
| 26 | `(n − 1) × (i16 dx, i16 dy)`: **sign-magnitude** deltas in 1/32 units |
| then | `f32` base pressure + `(n − 1) × u16` sign-magnitude deltas in 1/4096 |
| then | Time, tilt and orientation streams (not decoded yet) |

**Trailer**, at `tail = payload + size2`:
- If bytes `[tail−6, tail−2)` equal `[tail+2, tail+6)` and `[tail−2, tail+2)` is zero, the stroke has **no colour** (default black) and its size `f32` is at `tail − 6`.
- Otherwise RGBA bytes are at `tail−6 … tail−3` and the size `f32` is at `tail − 2`.
- Alpha below 0xF0 means a highlighter.

Folio width = size × `WIDTH_FACTOR` (2.0) × page scale. The factor was chosen by eye and still needs a side-by-side check against Samsung Notes.

## Validation
- 33,001 of about 33,105 strokes across the five notes decode with every point inside the object's own bounding box. 1,838 of them are highlighters.
- The ~100 that don't are mostly rotated or transformed strokes (the transform isn't decoded yet).
- On the Galaxy Tab S6 Lite, a 5-page, 25,303-stroke note imports in about 17 s, and its pages render as in Samsung Notes (checked visually).

## Title
Samsung exports are named `<title>_<yyMMdd>_<HHmmss>.sdocx`. Folio takes the title from the file name, because the title inside `note.note` wasn't reliable across samples.

## Not imported yet
- PDF backgrounds (the link from a page to its `media/*.pdf` page isn't decoded).
- Text boxes, images, shapes and sticky memos.
- Stroke rotation/transform, and the time/tilt/orientation streams.
