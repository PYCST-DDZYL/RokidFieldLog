# Rokid FieldLog

An open-source Kotlin project for recording neighborhood maintenance problems with Rokid Glasses and an Android phone.

**Status: Phase 1 development preview.** Local photography has been tested on first-generation Rokid RG-glasses (Android 12 / API 32). The phone location probe builds, but has not yet been tested on a real phone. The test phone is currently disconnected; earlier USB diagnosis is documented separately. This preview does not yet provide the complete field-reporting workflow.

## Available now

- An on-device glasses app using CameraX ImageCapture, without a camera preview.
- Real JPEG capture into app-private storage, UUID records, original request/completion timestamps, and a short status message that hides automatically.
- Durable capture journals, interrupted-write recovery, validation of saved images, and preservation of existing annotations during repair.
- Human-selected quick tags for road damage, damaged facilities, litter, and other issues. Tags remain pending review; they are not AI recognition.
- Saved-photo SHA-256 fingerprints and capture clock-interval checks. Changed bytes require review; raw timestamps are preserved.
- A separate phone location probe: Fused Location Provider when Google Play services are available, Android system providers otherwise.
- Location quality checks, cancellation, a 20-second deadline, and explicit null coordinates on failure. System fallback can also be selected manually.
- A confirmed "delete all project data" action in each app. No shared storage permission or background location tracking.

**Not implemented:** glasses-to-phone communication, photo/location time matching across devices, phone photo browsing, a map, manual issue-position correction, phone category editing, notes, report export, and validated automatic issue recognition. AI assistance has now been requested, but no model has passed domain-specific acceptance tests; see [recognition readiness](docs/recognition-readiness.md). Servers, accounts, and automatic municipal submissions remain outside version 1.

Bluetooth pairing alone does not grant access to phone GPS. Phase 2 must implement an explicit companion protocol using verified Rokid APIs. No Rokid SDK binary or invented SDK interface is included in this preview.

## Project layout

```text
RokidFieldLog/
  glasses-app/       On-device Android app, minSdk 31
  phone-app/         Android companion location probe, minSdk 28
  fieldlog-core/     Shared Kotlin validation and capture-journal logic
  docs/             Feasibility, installation, testing, and known limitations
  scripts/          Project-local Windows tooling and explicit device tests
  gradle/wrapper/   Pinned Gradle wrapper with distribution checksum
  .tools/           Local development tools; ignored by Git
  .local/           Local caches and private device evidence; ignored by Git
```

Android Studio, Kotlin 2.2.21, Android Gradle Plugin 8.13.2, Gradle 8.13, and JDK 21 are used. Both apps compile against Android API 36. Windows PowerShell is the tested setup; other desktop operating systems have not been validated.

## Build and run

Installable debug APKs are available in [Phase 1 Preview 2](https://github.com/PYCST-DDZYL/RokidFieldLog/releases/tag/v0.1.0-preview.2). This is a prerelease with the limitations above. The glasses APK supports local photography and manual tags; the phone APK is an independent location probe. Downloading does not install anything or change device settings.

See [installation and cleanup](docs/install.md) for a clean checkout, SDK license acceptance, device authorization, and uninstall instructions. 中文操作步骤见 [Phase 1 运行指南](docs/phase1-run.md)。

From the project root in PowerShell, after preparing the project-local tools:

```powershell
.\scripts\Build.ps1 -Verify -Clean
.\scripts\Open-AndroidStudio.ps1
```

Expected: `BUILD SUCCESSFUL` and an `APK verified` line for each module. Building does not install anything on a device. APKs are under each module's `build/outputs/apk/debug/` directory. Android Studio must use `.tools/jdk-21` for Gradle; its bundled Java 25 cannot run this pinned Gradle version.

After authorizing installation on the intended device:

```powershell
.\scripts\Inspect-Devices.ps1
.\scripts\Install-App.ps1 -Target glasses -ConfirmInstallation -StartApp
# Run only when the physical phone has an authorized ADB connection:
.\scripts\Install-App.ps1 -Target phone -ConfirmInstallation -StartApp
```

The scripts reject a missing or ambiguous device and verify the APK package and defined classes. Updates use `install -r` to retain this app's private data. They do not change USB settings or install drivers. Permit camera access for FieldLog and foreground precise or approximate location for FieldLog Companion when testing their respective functions.

On the glasses, a single touchpad tap is mapped by Rokid firmware to Enter. Actual captures were triggered through ADB Enter on the test device; physical touchpad operation and optical readability still require user acceptance. On the phone, test outdoors using the automatic or Android-system location button. No phone result has been claimed as a successful hardware test yet.

## Verification and limits

| Check | Evidence |
| --- | --- |
| Both debug APKs build, startup classes present | Passed |
| Kotlin core tests | 26 passed |
| Android Lint | No errors; dependency/style warnings remain |
| Real glasses camera and private JPEG/JSON save | Passed on RG-glasses, 4032 × 3024 |
| Cold restart, original records retained, prompt hidden | Passed |
| Android storage recovery fixtures | 13 passed on the glasses; synthetic fixtures, not camera/GPS evidence |
| Cancel a delete dialog with Back | Passed on the glasses; no deletion performed |
| Physical touchpad gestures and optical display | Pending |
| Real phone location, approximate permission, non-GMS phones | Pending |
| Cross-device communication and reporting workflow | Not implemented |

Latest changes and reproducible checks: [reliability update](docs/reliability-update.md). Earlier evidence: [Phase 1 status](docs/phase1-status.md), [capture recovery](docs/capture-recovery.md), and [phone connection diagnosis](docs/phone-connection.md). The storage test runner is debug-only:

```powershell
.\scripts\Test-StorageRecovery.ps1 -ConfirmDeviceTest
```

It writes synthetic 16 × 16 JPEG fixtures under this app's private `fieldlog/test-fixtures/` directory. They are excluded from production record scanning and photo counts. Real power loss, full storage, camera contention, and deletion failures have not been reproduced on hardware.

## Location and time semantics

Glasses records currently contain `phoneLocation: null`, `issueLocation: null`, `locationStatus: PENDING_CONFIRMATION`, and `phoneConnectionStatus: NOT_CONFIGURED_PHASE1`. A successful local photo save never means it has been synchronized or geolocated.

Capture timestamps identify the app's request/completion interval, **not the sensor exposure time**. Unknown recovered timestamps remain null. The test glasses' wall clock differs from the actual date; it has not been changed. Phase 2 must preserve raw clocks and estimate clock offset and uncertainty. Uptime values from different devices cannot be compared directly.

`captureWallClockStatus: CONSISTENT_INTERVAL` checks only one interval, not the absolute date. `photoSha256` is a content fingerprint, not a signature or proof of time/location. Older complete records without a saved fingerprint remain unchanged. Recovery-generated fingerprints are labeled as a verification of the current bytes, not an original-capture fingerprint.

Phone fixes record WGS84 coordinates, accuracy when available, fix time, monotonic fix age, permission level, and source. Mock, stale, future, non-finite, or out-of-range observations are rejected. The photographer's position is an observation; the issue's actual position must be independently confirmed or corrected on a map in Phase 3.

## Privacy and storage

Photos, records, probe history, recovery backups, and test fixtures stay inside their owning app's private storage. Android backup and device-transfer backup are disabled for both apps. No public gallery, unrelated files, or other app data are accessed. Export through Android's system file picker is planned for Phase 3 and is not available in this preview.

The repository excludes tools, caches, APKs, photos, location records, device logs, keys, and local settings. Do not paste personal coordinates or device identifiers into issues. Use [the staged-file privacy check](CONTRIBUTING.md) before committing.

## Roadmap

1. Complete Phase 1 with a real Android phone location test and wearable input acceptance.
2. Verify actual Rokid CXR-L/CXR-S SDK compatibility; implement communication, clock matching, and failure handling.
3. Add the phone record list, map correction, categories, notes, and user-selected report export.
4. Complete installation documentation and release checks for the whole workflow. Source publication of this preview does not complete the earlier phases.

Official starting points: [Rokid developer documentation](https://developerdoc.rokid.com/sdk?lang=en), [CameraX configuration](https://developer.android.com/media/camera/camerax/configuration#camera-limiter), [Fused CurrentLocationRequest](https://developers.google.com/android/reference/com/google/android/gms/location/CurrentLocationRequest.Builder).

## License

Project source is licensed under [MIT](LICENSE). The Gradle wrapper retains its [Apache license and bundled dependency notices](gradle/wrapper/LICENSE), with the [Gradle NOTICE](gradle/wrapper/NOTICE). Android, Google Play services, and any future Rokid SDK remain subject to their respective licenses. This project is independent and is not an official Rokid product.
