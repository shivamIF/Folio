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
| `.goodnotes` (ZIP + protobuf) | – | – | – | – | – | Planned: read-only import of attachments and strokes, once the schema is decoded |
| `.sdocx` (ZIP + custom binary pages) | – | – | – | – | – | Planned: read-only import. Blocked on sample files; locked (encrypted) notes will not be supported |

No external-format compatibility is claimed yet.
