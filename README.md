# Rokid FieldLog

Record neighborhood maintenance problems with Rokid Glasses and an Android phone. 眼镜采集公共设施问题照片，手机辅助确认位置并准备政务材料。

The current Phase 1 preview takes and stores photos on the glasses, with manual issue tags and latest-photo review/undo. The phone app is a separate location probe. Cross-device synchronization, map correction, privacy editing and report export are planned; the complete reporting workflow is still in development.

## Current status

This is the single current status table. Changes are recorded in [CHANGELOG](CHANGELOG.md); earlier dated reports are historical evidence.

| Capability / check | Current state |
| --- | --- |
| Glasses photo capture and private JPEG/JSON storage | Implemented; previous preview tested on RG-glasses, Android 12 / API 32 |
| Background recovery and capture without waiting | Implemented; cache, concurrency, repair and deletion JVM regressions passed |
| Manual tags, category on save, review/undo, idle-camera release | Implemented; this revision needs physical touchpad and display acceptance |
| Phone Fused/system location, 60-second deadline and provisional feedback | Implemented; real phone/GPS and non-GMS acceptance pending |
| Core JVM tests, both debug APKs, Android Lint | 65 tests passed; both APKs built and package/classes verified; Lint 0 errors (13 warnings total) |
| Linux GitHub Actions | Workflow added for pushes and pull requests; first remote run pending |
| Submission channel | Official candidate research documented; target region/form acceptance pending |
| Glasses-to-phone transfer and clock matching | Phase 2; actual Rokid SDK/device compatibility pending |
| Phone record list, map, issue-point correction and notes | Phase 3 |
| Third-party privacy review/redaction and channel-specific export | Phase 3; design/acceptance gates documented |
| Battery/heat, JPEG-size comparison and field throughput | Device measurement protocol ready; measurements pending |

## Try the preview

The glasses app uses CameraX without a live camera preview. It stores photos in app-private storage, uses JPEG quality 93, and checks a 64 MiB space reserve before capture. Explicit capture input takes a photo; empty-background taps show the controls. The three main actions are capture, category and review/undo. Delete-all is inside data management with confirmation. Save feedback includes the captured category, which remains selected until changed.

Prompts hide automatically. After 60 seconds idle the camera releases; a capture input wakes it and the next input takes the photo. This behavior needs wearable-input and latency validation. Startup verifies changed/interrupted files in the background; unchanged verified photos use a size/mtime cache. Full validation is available for future export. File statistics and SHA-256 do not establish authentic time or location.

Both apps disable Android backup and keep records inside their own private storage. No public gallery or unrelated files are accessed. Current photos have null phone/issue locations; a local save does not imply synchronization. Phone coordinates are WGS84 observations of the photographer; the issue point must later be confirmed separately.

Installable APKs for the **older** [Phase 1 Preview 2](https://github.com/PYCST-DDZYL/RokidFieldLog/releases/tag/v0.1.0-preview.2) are available; they do not contain the unreleased changes described above. For the current source, build both APKs below. See [installation](docs/install.md) and the [中文运行指南](docs/phase1-run.md).

## Build and verify

Use JDK 21, Android SDK platform 36 and Build Tools 35.0.0. The project pins Kotlin 2.2.21, AGP 8.13.2 and Gradle 8.13; the wrapper distribution has a checksum.

On Linux/macOS, configure your installed SDK through ANDROID_HOME or local.properties and run:

~~~sh
./gradlew --no-daemon :fieldlog-core:test assembleDebug lintDebug
~~~

GitHub Actions runs this command on Ubuntu. On Windows, after preparing the project-local tools:

~~~powershell
.\scripts\Build.ps1 -Verify -Clean
~~~

The Windows script also checks both APK packages/startup classes. APKs are under each module's build/outputs/apk/debug/. Building does not install apps. Device scripts and private evidence collection still use PowerShell. Physical device tests are separate from CI.

## Project and design

- glasses-app: Android capture, private storage adapter and glasses controls (minSdk 31).
- phone-app: foreground location provider adapters and diagnostic screen (minSdk 28).
- fieldlog-core: typed records/codec, journals, recovery, clocks, location sources and orchestration with JVM tests.
- docs and scripts: design, usage, device validation and Windows tools; .tools/.local are ignored local tools and private evidence.

[Roadmap](docs/roadmap.md) defines phase acceptance. Before Phase 2, select a local channel and verify the complete form using the [one-page channel research](docs/submission-channels.md). V1 will generate user-reviewed materials for manual submission through an official channel. The [coordinate/privacy design](docs/coordinates-and-privacy.md) defines WGS84→map conversion, map-provider information, independent corrected issue coordinates, and redacted export copies. The [record contract](docs/record-format.md) defines typed fields, additive version compatibility and cache limits. The [field validation protocol](docs/field-validation.md) covers GPS, gestures, JPEG size, battery and heat.

Bluetooth pairing alone does not provide phone GPS; Phase 2 needs a verified companion protocol. Automatic issue recognition requires the [separate model acceptance process](docs/recognition-readiness.md). Accounts, cloud photo storage and automatic municipal submissions remain outside V1.

Contributors should read [CONTRIBUTING](CONTRIBUTING.md), run the checks, and review staged files for private data before pushing. Do not commit photos, raw coordinates, device identifiers, logs or keys.

## License

[MIT](LICENSE) covers project source. The [Gradle wrapper license](gradle/wrapper/LICENSE) and [notices](gradle/wrapper/NOTICE), Android/Google dependencies and any future Rokid SDK retain their own licenses. This is an independent project.
