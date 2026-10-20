# Security Review (milestone 1)

| # | Threat | Severity | Status | Evidence / mitigation |
|---|---|---|---|---|
| 1 | Path traversal through ids used as file names | High | ✅ Mitigated | `SAFE_ID` regex in `FileNotebookStore`; `pathTraversalIdsAreRejected` test |
| 2 | Torn writes / crash during save → corrupt notebook | High | ✅ Mitigated | `AtomicFile` (fsync + atomic rename + `.bak`); recovery tests |
| 3 | Malformed or hostile document file crashing the app | Medium | 🟡 Partly mitigated | Codec maps all parse errors to `CorruptDocumentException`; channel-length validation. PDF import: 300 MB cap, `%PDF-` header check, page count ≤ 2000, staged outside the library and discarded on any failure; rendering uses the platform PdfRenderer (password PDFs refused). **Open**: no size cap on native page files |
| 4 | Notes leaking through Android backup / device transfer | Medium | ✅ Mitigated | `allowBackup=false` plus `data_extraction_rules` exclude all files |
| 5 | Other apps reading notes | Medium | ✅ Mitigated | App-private `filesDir`; no exported providers; only the launcher activity is exported |
| 6 | Sensitive logging | Low | ✅ | The app doesn't log note content |
| 7 | Archive bombs / zip-slip in `.goodnotes` / `.sdocx` import | High | ⏳ Not applicable yet | Import isn't implemented. Requirements: entry-count, size and ratio limits, canonical-path checks |
| 8 | Sync authentication and authorisation, transport | High | ⏳ Not applicable yet | No backend |
| 9 | Dependency vulnerabilities and licences | Low | 🟡 | Only AndroidX/Kotlin (Apache-2.0). No automated scan yet |

There are no open critical findings. High-severity items 7 and 8 are prerequisites for their features.
