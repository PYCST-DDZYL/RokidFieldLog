# Rokid FieldLog

现场一次整理能找到位置、看清问题的报修材料，并在后续观察中核对处理结果。眼镜负责采集，手机负责整理、隐私审阅和人工提交准备。

Current product hypothesis: help a fixed-area inspector and a real maintenance recipient avoid missing photos, unclear locations and repeated entry. The [practical scope and acceptance plan](docs/practical-product-scope.md) defines how to compare against the existing phone/WeChat/form workflow; real-user value has not yet been measured.

The glasses preview takes and stores photos with manual issue tags and latest-photo review/undo. The phone companion imports private capture packages, lets you review and edit records, makes manually redacted JPEGs, and exports materials for manual submission. A new multi-photo report combines 2–5 reviewed views with one recipient/category/address/landmark/date/description, checks missing context/detail views, and keeps a single visit separate from repair history. It also retains the independent location probe. The new inspection preview links multiple observations to a stable issue ID, separates repair feedback from human verification, and provides a recheck list and before/latest photo comparison. Association and verdicts remain manual. Glasses-to-phone wireless synchronization and map correction are still pending.

## Current status

This is the single current status table. Changes are recorded in [CHANGELOG](CHANGELOG.md); earlier dated reports are historical evidence.

| Capability / check | Current state |
| --- | --- |
| Glasses photo capture and private JPEG/JSON storage | Implemented; previous preview tested on RG-glasses, Android 12 / API 32 |
| Background recovery and capture without waiting | Implemented; cache, concurrency, repair and deletion JVM regressions passed |
| Manual tags, category on save, review/undo, idle-camera release | Implemented; this revision needs physical touchpad and display acceptance |
| Phone Fused/system location, 60-second deadline and provisional feedback | Implemented; real phone/GPS and non-GMS acceptance pending |
| Core JVM tests, both debug APKs, Android Lint | 148 tests passed; both APKs built and package/classes verified; Lint 0 errors; physical Android UI acceptance pending |
| Linux GitHub Actions | Ubuntu 24.04 workflow prepares the Android SDK, then tests, builds and lints; see [current runs](https://github.com/PYCST-DDZYL/RokidFieldLog/actions) |
| Submission channel | Official candidate research documented; target region/form acceptance pending |
| Glasses-to-phone transfer and clock matching | Phase 2; actual Rokid SDK/device compatibility pending |
| Phone record import/list/search/edit and optional manual issue coordinates | Implemented offline; Android UI/device acceptance pending |
| Stable issue history, manual observation links, repair feedback and recheck queue | Implemented; metadata/event rules, import integration and evidence safeguards passed JVM tests |
| Before/latest photo comparison and manual verdict | Implemented; obstruction/unknown views remain pending; Android UI acceptance pending |
| Map-based issue-point correction and coordinate conversion | Pending map-provider integration |
| Manual privacy redaction, actual JPEG review and generic material export | Implemented; synthetic JVM workflow covers file/pixel checks; Android rendering acceptance pending |
| Multi-photo report draft, context/detail checklist and shared text | Implemented offline; per-photo privacy and source/version checks; Android UI and real recipient acceptance pending |
| Channel-specific classification/export | Pending a selected local form; current profile is generic manual materials |
| Battery/heat, JPEG-size comparison and field throughput | Device measurement protocol ready; measurements pending |

## Try the preview

Start with “报修材料（环境照 + 细节照）” on the phone home screen. Create a draft, name a real recipient, enter their category and a findable address/landmark, select context/detail photos from the same visit, and review every output JPEG. Save the ZIP, then extract the text and JPGs for manual entry into the recipient’s existing channel. Follow the [multi-photo guide](docs/multi-photo-materials.md). The app limit of 2–5 photos is not a universal government rule.

The companion home screen now includes “问题历史与待复查清单” and “载入复查流程演示”. The synthetic workflow stops at an obstructed revisit, with another photo ready to attach and compare. Repair feedback never automatically closes an issue. Follow the [inspection guide](docs/inspection-workflow.md); the [single-site pilot protocol](docs/inspection-pilot.md) defines how to test real value and compare phone/glasses inputs. No field efficiency or customer payment outcome is claimed.


The glasses app uses CameraX without a live camera preview. It stores photos in app-private storage, uses JPEG quality 93, and checks a 64 MiB space reserve before capture. Explicit capture input takes a photo; empty-background taps show the controls. The three main actions are capture, category and review/undo. Delete-all is inside data management with confirmation. Save feedback includes the captured category, which remains selected until changed.

Prompts hide automatically. After 60 seconds idle the camera releases; a capture input wakes it and the next input takes the photo. This behavior needs wearable-input and latency validation. Startup verifies changed/interrupted files in the background; unchanged verified photos use a size/mtime cache. Full validation is available for future export. File statistics and SHA-256 do not establish authentic time or location.

The phone home screen offers “载入演示记录” to practice without a glasses connection. Demonstration records and materials remain explicitly synthetic. Review the address, category and date, draw privacy masks, inspect the actual JPEG, then save a material ZIP through the system file picker. Export does not submit an issue; official receipts are recorded manually. See the [offline companion guide](docs/offline-companion.md) for inputs, limits, cleanup and computer-only verification.

Both apps disable Android backup and keep records inside their own private storage. No public gallery or unrelated files are accessed. Glasses photos still have null phone/issue locations; a local save does not imply synchronization. Imported source observations are preserved separately from manual issue coordinates. Phone coordinates are WGS84 observations of the photographer; the issue point must later be confirmed separately.

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
- phone-app: offline issue library/editor, upright photo redaction/review, material saving, and foreground location diagnostics (minSdk 28).
- fieldlog-core: typed source/draft contracts, atomic import, export validation, journals, recovery, clocks and location orchestration with JVM tests.
- docs and scripts: design, usage, device validation and Windows tools; .tools/.local are ignored local tools and private evidence.

[Roadmap](docs/roadmap.md) defines phase acceptance. Before Phase 2, select a local channel and verify the complete form using the [one-page channel research](docs/submission-channels.md). The current offline preview generates generic user-reviewed materials for manual submission; a researched channel profile is a later adapter. The [coordinate/privacy design](docs/coordinates-and-privacy.md) defines WGS84→map conversion, map-provider information, independent corrected issue coordinates, and redacted export copies. The [record contract](docs/record-format.md) defines typed fields, additive version compatibility and cache limits. The [field validation protocol](docs/field-validation.md) covers GPS, gestures, JPEG size, battery and heat.

Bluetooth pairing alone does not provide phone GPS; Phase 2 needs a verified companion protocol. Automatic issue recognition requires the [separate model acceptance process](docs/recognition-readiness.md). Accounts, cloud photo storage and automatic municipal submissions remain outside V1.

Opt-in -PfieldlogDemo=true also writes a standalone synthetic inspection-demo.html into ignored .local/offline-preview. It visualizes core workflow transitions with synthetic images and is not an Android screenshot.

Contributors should read [CONTRIBUTING](CONTRIBUTING.md), run the checks, and review staged files for private data before pushing. Do not commit photos, raw coordinates, device identifiers, logs or keys.

## License

[MIT](LICENSE) covers project source. The [Gradle wrapper license](gradle/wrapper/LICENSE) and [notices](gradle/wrapper/NOTICE), Android/Google dependencies and any future Rokid SDK retain their own licenses. This is an independent project.
