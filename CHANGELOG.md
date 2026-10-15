# Changelog

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
