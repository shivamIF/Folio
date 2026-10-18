# Release Checklist

- [x] Debug build reproducible from a clean checkout (`./gradlew :app:assembleDebug`)
- [x] Unit tests green (`./gradlew test`)
- [x] Milestone E2E green on an emulator
- [ ] E2E and manual S Pen checks green on a physical Galaxy Tab
- [ ] Input-to-ink latency measured (target p95 ≤ 20 ms) and frame stats recorded
- [ ] Release build with R8 succeeds and is smoke-tested (serialization keep rules verified)
- [ ] Signing configuration (needs the owner's keystore; never committed)
- [ ] PDF import/export, sync and search: either implemented and tested, or removed from release notes
- [ ] Security review: no open high-severity items for shipped features
- [ ] Licence report for dependencies
- [ ] Release notes and known limitations updated
