# Changelog

## Unreleased — 2026-10-14

- Allow capture during background recovery. Persist validated file statistics to skip unchanged JPEG decode/hash on startup; full validation remains available.
- Move record/probe models, versioned JSON codec, recovery and location orchestration into fieldlog-core with injected clocks, image inspection, storage and provider/scheduler interfaces.
- Replace locked disk-based UI counts with memory snapshots. Run journal creation, sync, undo and deletion on worker queues; recover photos individually instead of under one global storage lock.
- Add latest-photo review and confirmed undo. Move delete-all into data management. Empty-background taps show the controls; explicit camera input triggers capture. Show the selected category on save and preserve selection during Activity recreation.
- Use JPEG quality 93 and a 64 MiB pre-capture free-space reserve. Release camera after 60 seconds idle; clear screen-on when prompts hide.
- Extend the phone deadline to 60 seconds. Display provisional low-precision fixes while waiting; preserve explicit quality and reject stale/late/mock fixes. Cover cancellation, fallback and queued callbacks with JVM fake-provider tests.
- Add Ubuntu GitHub Actions running :fieldlog-core:test assembleDebug lintDebug and upload verification reports.
- Consolidate current status in README. Add channel research, the shared record contract, coordinate/privacy design and a device/energy test protocol. Prior dated documents are historical evidence.

Validation: 65 JVM tests passed; both debug APKs rebuilt and package/startup classes verified; Android Lint 0 errors (glasses 11 warnings, phone 2). Staged-source privacy and whitespace checks passed. Linux CI awaits its first remote run. ADB discovery was blocked by this execution environment's key-directory access restrictions; no new device success is claimed.

Hardware verification of these changes, local submission-form acceptance, map/redaction/export implementation and field battery measurements remain pending.

## 0.1.0 Preview 2 — 2026-10-13

- Verified local glasses photo capture and private JPEG/JSON persistence on first-generation RG-glasses.
- Added manual quick tags, capture time checks, saved-photo SHA-256 fingerprints and repair that preserves annotations.
- Built both APKs; passed 26 core JVM tests and 13 isolated Android recovery fixtures. Phone GPS and physical wearable gestures remained unverified.

## 0.1.0 Preview 1 — 2026-10-11 to 2026-10-12

- Added the glasses capture app, separate Android phone location probe and durable photo journals.
- Prepared project-local Windows tools and published the Phase 1 development preview.
