# GoodNotes 6 `.goodnotes` format (as decoded by Folio)

This was reverse-engineered independently from the user's own notebook (schema version 31, March 2026) for read-only import. No GoodNotes code was used. Research tools are in `scripts/formats/` (`pbdump.py`, `applelz4.py`, `tpl.py`). The importer is `core/interop/GoodNotesImporter.kt`.

## Container
A ZIP containing:

| Entry | Contents |
|---|---|
| `schema.pb` | `{1: 31}`, the schema version |
| `index.events.pb` | Document event log: varint-length-prefixed protobuf records `{1: id, <kind>: {...}}` |
| `index.attachments.pb` | `{1: attachment record id, 2: "attachments/<file id>"}` |
| `notes/<id>` | One element log per page slot |
| `attachments/<id>` | Original files: PDFs and PNGs |
| `search/<id>` | Search index (not used) |
| `thumbnail.jpg` | Preview of the first page |

Many values are last-writer-wins registers of the form `{1: value, 2: {1: counter, 2: hash}}`. This is a sync-friendly design.

## Event kinds (field number inside a record)

| Kind | Meaning | Fields used |
|---|---|---|
| 30 | Document | `2.1` title |
| 2 | Page | `2` page id; `4` attachment record id; `5` PDF page number (1-based); `8 = {1: w, 2: h}` size in f32 |
| 54 | Page slot | `2` slot id; `3.1` page id; `4.1` **fractional order key**, sorted as strings to get page order |
| 6 | Attachment link | `1` record id; `2` file id |
| 130 / 133 | Text box: position / content | Not imported yet |
| 240–244 | Stopwatch | Ignored |
| 103, 104, 105, 102, 60, 10, 65 | Metadata | Ignored |

The ink file of a slot is `notes/<slot id + 1>`: the UUID incremented by one. This held for all 79 of 79 slots.

## Page element log (`notes/…`)
- **Header record**: `{1: element id, 2: {1: version, 2: hash}, 3: 1 if deleted, …}`. The latest header wins. Deleted elements keep their content in the log (368 of them in the sample) and must be skipped.
- **Content record**: has **only** field 7. `7.1` is the id, `7.2` a geometry blob, `7.3` the type (absent = ink, 1 = empty placeholder, 5 = image), and `7.4` an RGBA colour as f32 fields 1–4 (missing = 0; alpha < 1 means highlighter).
- **Freehand ink**: `7.2` is Apple LZ4 (`bv41`… `bv4$`) wrapping a **tpl** image (troydhanson/tpl) with format `vuA(v)A(S(uu))A(S(uuuu))vA(f)`. The fields are:
  1. version
  2. width (f32 bits)
  3. segment kinds (0 = move, 1 = quadratic)
  4. move points
  5. quadratic control and end points
  6. an unknown value
  7. an optional width array
- **Vector ink** (straightened lines, most highlighter marks): the tpl arrays are empty and the geometry is in `7.9`. `9.1 = {1: point, 1: point, …}` is a polyline, `9.2 = {1, 2, 3}` a three-point curve, `9.15` the width. `9.3` and `9.4` are other shapes, not imported yet.
- **Units**: 132 per inch. An A4 PDF page (595.9 × 842.9 pt) is 1093 × 1545.

## Not yet imported
- Typed text boxes and images (Folio has no text or image objects yet)
- Shapes `9.3` / `9.4`
- Per-point width arrays (none are present in the sample)

## Verification
- Unit tests build synthetic files covering each rule above.
- The user's notebook imports as 79 pages, 2,020 strokes and 2 PDFs.
- On a Galaxy Tab S6 Lite, the highlighter marks line up exactly with the highlighted PDF text.
