# Reuse and Compatibility

## Code reuse policy
- No code, shaders, models, fonts or artwork from Samsung Notes or GoodNotes is included. All implementation is original.
- Third-party code comes only from open-source libraries under compatible licences (all Apache-2.0):

| Dependency | Licence | Used for |
|---|---|---|
| AndroidX (Compose, Activity, Lifecycle, input-motionprediction) | Apache-2.0 | UI, lifecycle, stroke prediction |
| kotlinx.serialization, kotlinx.coroutines | Apache-2.0 | JSON codec, async I/O |
| JUnit 4 / kotlin-test | EPL-1.0 / Apache-2.0 | Tests only (not shipped) |

## Format compatibility matrix

| Format | Recognise | Open | Edit | Save/Export | Round-trip verified | Status |
|---|---|---|---|---|---|---|
| Folio native (JSON pages + library index, schema v1) | ✅ | ✅ | ✅ | ✅ | ✅ unit tests (`CodecTest`, `NotebookStoreTest`) | **Implemented** |
| PDF import, annotate, export | ✅ | ✅ (as page backgrounds) | ✅ ink on top | ✅ export to PDF | ✅ 3-page fixture: page count and 612×792 pt size preserved (Tab E2E) | **Implemented**; backgrounds rasterised in export |
| `.goodnotes` (ZIP + protobuf) | – | – | – | – | – | Import ✅: strokes (tpl curves and vector shapes), highlighters, PDF backgrounds. Text boxes and images skipped. See docs/formats/GOODNOTES.md |
| `.sdocx` (ZIP + custom binary pages) | – | – | – | – | – | Import 🟡: strokes, colours, highlighters, pressure. Not yet: PDF backgrounds, text, images, shapes. Locked (encrypted) notes are refused, by design. See docs/formats/SDOCX.md |

No external-format compatibility is claimed yet.
