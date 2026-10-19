# Reuse and Compatibility

## Code reuse policy
- No code, shaders, models, fonts or artwork from Samsung Notes or GoodNotes is included. All implementation is original.
- Third-party code comes only from open-source libraries under compatible licences (all Apache-2.0):

| Dependency | Licence | Used for |
|---|---|---|
| AndroidX (Compose, Activity, Lifecycle, input-motionprediction) | Apache-2.0 | UI, lifecycle, stroke prediction |
| kotlinx.serialization, kotlinx.coroutines | Apache-2.0 | JSON codec, async I/O |
| Material Symbols (Rounded): `res/drawable/ms_*.xml` (ink_pen, stylus, brush, edit, ink_highlighter, ink_eraser) | Apache-2.0 | Writing-tool icons |
| JUnit 4 / kotlin-test | EPL-1.0 / Apache-2.0 | Tests only (not shipped) |

## Format compatibility matrix

| Format | Recognise | Open | Edit | Save/Export | Round-trip verified | Status |
|---|---|---|---|---|---|---|
| Folio native (JSON pages + library index, schema v1) | ✅ | ✅ | ✅ | ✅ | ✅ unit tests (`CodecTest`, `NotebookStoreTest`) | **Implemented** |
| PDF import/annotate/export | – | – | – | – | – | Planned (Phase 5) |
| `.goodnotes` (ZIP + protobuf) | – | – | – | – | – | Planned: read-only import of attachments and strokes, once the schema is decoded |
| `.sdocx` (ZIP + custom binary pages) | – | – | – | – | – | Planned: read-only import. Blocked on sample files; locked (encrypted) notes will not be supported |

No external-format compatibility is claimed yet.
