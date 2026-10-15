# Changelog

## 0.4.0 Multi-photo materials preview — Unreleased

- Add persistent multi-photo report drafts with one recipient, receiver-specific category, address, landmark, manually confirmed visit date and description. Reuse existing manual text when starting from a record; photo tags do not silently become official categories.
- Add missing-material checks for 2–5 distinct photos, context/detail roles, a findable location, same-issue/same-visit confirmation, known date conflicts and independent privacy review. Roles and site/date judgments remain manual.
- Export whitelisted shared text/JSON and reviewed JPEGs, excluding original GPS, private source fields, receipts and longitudinal repair history. Revalidate source/review fingerprints and draft/photo revisions immediately before external saving.
- Protect photos referenced by report drafts, retain originals when deleting a report, preserve unreadable/future records, and discover AtomicFile companions for recovery/deletion safeguards. Isolate synthetic/real material and clear synthetic reports with demo records.
- Add phone entry points, searchable/paged photo selection, editable roles, save-on-back and rotation state. Phone version is 0.4.0-materials-preview (versionCode 4). Android interaction acceptance remains pending.
- Define a practical one-site product hypothesis and proposed first-pass usability/full-work-time measures. Add current official multi-photo channel evidence; no local form compatibility, field efficiency or paying customer is claimed.

Validation: 130 JVM tests passed (20 new real-library multi-photo integration tests). Both debug APKs built; package/required DEX classes verified, including ReportActivity and ReportRepository, and the final phone manifest/version checked. Android Lint has 0 errors and 37 remaining warnings (11 glasses, 26 phone: UseKtx, SetTextI18n and a newer-API manifest attribute ignored on older Android). Synthetic report ZIP/text/manifest and both redacted JPEG fixtures were checked. Android UI, system picker/return gestures and field efficiency remain untested on a device.

## 0.3.0 Inspection preview — Unreleased

- Add stable issue IDs with append-only, revision-checked observations, repair feedback and separate human verification. Original record receipt stages do not imply physical repair verification.
- Add manual photo association with confirmed site/date/visibility/finding. Reject reused IDs, identical bytes under a new ID, mixed synthetic/real evidence, out-of-order dates and altered originals. A later problem reopens the issue; an unclear new view returns to recheck.
- Add paged issue history/search, pending-recheck filtering, and zoomable original/latest photo comparison. Repair confirmation requires clear new evidence after the latest feedback plus explicit human chronology confirmation; unknown and obstructed views cannot close an issue.
- Bind comparisons and verdicts to import-time photo fingerprints. Protect linked records from single-photo deletion; confirmed tracking deletion retains originals. Preserve unreadable/future histories and stop mutations that could lose unknown links.
- Add an actionable synthetic Android walkthrough and integrated host workflow fixtures. Optional test artifacts include a standalone interactive inspection-demo.html, a clearly synthetic capture ZIP and inspectable history JSON.
- Add a one-site pilot protocol measuring full human effort, phone/glasses usability and willingness to pay; no measured tenfold improvement or field acceptance is claimed. Phone version is 0.3.0-inspection-preview (versionCode 3).

Validation: 110 JVM tests passed (26 new inspection tests), both debug APKs built and package/required classes verified, Android Lint 0 errors (35 remaining style/localization warnings). The five-step synthetic HTML data and three JPEG fixtures were checked. Isolated browser screenshot rendering was unavailable because GPU subprocess startup was denied; no Android UI rendering or touch success is claimed. Wireless sync, visual matching/recognition, maps, physical Android UI tests and real pilot outcomes remain pending.


## 0.2.0 Offline companion preview — Unreleased

- Replace the phone launch screen with an offline record library: import validated capture packages, search records, edit confirmed category/address/date/description and optional coordinates with an explicit CRS. Preserve immutable source annotations separately from revisioned drafts.
- Add bounded, staged ZIP imports with path/identity/version/photo checks, duplicate preservation and atomic batch commit. Corrupted existing entries remain on disk and do not hide readable records or permit import overwrite.
- Add local upright photo viewing, zoom/pan, opaque mask drawing/undo, derived JPEG generation and explicit review of the actual output. No-mask reviews require an explicit no-third-party-information declaration. Limit decode memory, output long edge and bytes; omit original metadata.
- Add generic manual-material ZIPs containing text, a whitelisted manifest and the reviewed JPEG. Bind the original to its import-time fingerprint before first review and recheck original/output fingerprints on export; reject private metadata, and require confirmed content and photo review. Save with the system document picker; no automatic government submission or official channel compatibility claim.
- Track material save, explicit manual submission and resolution receipts. Content edits return to draft while retaining receipt history; a late export cannot mark newer edits exported.
- Add clearly marked synthetic Android demos and host-only JPEG workflow tests. Opt-in -PfieldlogDemo=true produces ignored synthetic sample packages; CI uploads these, verification reports and debug APKs.
- Keep independent location diagnostics under “定位检查”; extend APK verification to the new activities, redaction view and core workflows. Phone version is 0.2.0-offline-preview (versionCode 2).

Validation: 84 JVM tests passed (19 new offline workflow tests), both debug APKs built and package/required classes verified, Android Lint 0 errors (29 remaining style/localization warnings across both apps). Synthetic material ZIP/text/JPEG generated and inspected. Linux CI configuration is updated; a remote run is still pending. Wireless glasses transfer, map integration, selected-city form acceptance, Android rendering/input acceptance and hardware energy measurements remain pending. No phone was connected for this work.

## Capture reliability update — 2026-10-14

- Allow capture during background recovery. Persist validated file statistics to skip unchanged JPEG decode/hash on startup; full validation remains available.
- Move record/probe models, versioned JSON codec, recovery and location orchestration into fieldlog-core with injected clocks, image inspection, storage and provider/scheduler interfaces.
- Replace locked disk-based UI counts with memory snapshots. Run journal creation, sync, undo and deletion on worker queues; recover photos individually instead of under one global storage lock.
- Add latest-photo review and confirmed undo. Move delete-all into data management. Empty-background taps show the controls; explicit camera input triggers capture. Show the selected category on save and preserve selection during Activity recreation.
- Use JPEG quality 93 and a 64 MiB pre-capture free-space reserve. Release camera after 60 seconds idle; clear screen-on when prompts hide.
- Extend the phone deadline to 60 seconds. Display provisional low-precision fixes while waiting; preserve explicit quality and reject stale/late/mock fixes. Cover cancellation, fallback and queued callbacks with JVM fake-provider tests.
- Add Ubuntu GitHub Actions running :fieldlog-core:test assembleDebug lintDebug and upload verification reports.
- Consolidate current status in README. Add channel research, the shared record contract, coordinate/privacy design and a device/energy test protocol. Prior dated documents are historical evidence.

Validation: 65 JVM tests passed; both debug APKs rebuilt and package/startup classes verified; Android Lint 0 errors (glasses 11 warnings, phone 2). Staged-source privacy and whitespace checks passed. Linux CI awaits its first remote run. ADB discovery was blocked by this execution environment's key-directory access restrictions; no new device success is claimed.

At this revision, hardware verification, local submission-form acceptance, map/redaction/export implementation and field battery measurements remained pending. The offline companion work above subsequently implements generic redaction/export.

## 0.1.0 Preview 2 — 2026-10-13

- Verified local glasses photo capture and private JPEG/JSON persistence on first-generation RG-glasses.
- Added manual quick tags, capture time checks, saved-photo SHA-256 fingerprints and repair that preserves annotations.
- Built both APKs; passed 26 core JVM tests and 13 isolated Android recovery fixtures. Phone GPS and physical wearable gestures remained unverified.

## 0.1.0 Preview 1 — 2026-10-11 to 2026-10-12

- Added the glasses capture app, separate Android phone location probe and durable photo journals.
- Prepared project-local Windows tools and published the Phase 1 development preview.
