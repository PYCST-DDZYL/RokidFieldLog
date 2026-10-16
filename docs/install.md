# Installation, testing, and cleanup

This preview provides glasses photography, a phone offline issue editor/redaction/material exporter, and independent location diagnostics. Wireless photo sync and maps are pending. See the [offline companion guide](offline-companion.md) to practice with synthetic records without connecting glasses. See [current status](../README.md#current-status) before installing.

For ongoing use, install phone-app-release.apk from a retained-key release build on your own Android 9+ phone through the system installer. The existing CI debug-apks artifacts remain development builds; the manual Signed release APKs workflow requires the same private key in repository secrets. The phone workflow can select your own JPEGs, redact them, and share reviewed report photos/text without a glasses connection. Development ADB steps below are optional for ordinary APK installation. Real picker/share-target compatibility remains a separate device check.

<a id="preview-signing"></a>

## Preview signing and updates

Release builds now require the retained key. Initialize it once with scripts/Initialize-ReleaseSigning.ps1 and build with scripts/Build.ps1 -Release -Verify. Keep BOTH .private/fieldlog-release.p12 and .private/release-signing.properties in a secure backup. The script preserves an existing key and refuses to replace an incomplete pair. The tracked release-certificate.sha256 pins the public certificate: fresh clones must restore the existing key, and Gradle rejects a different certificate. Never commit either file. Release signing has no debug fallback. APKs are in each module's build/outputs/apk/release directory. Use Install-App.ps1 -Target phone -Release -ConfirmInstallation -StartApp for an already authorized device.

For another build computer, restore the same pair. For the manual Signed release APKs workflow, provision FIELDLOG_RELEASE_KEY_BASE64 (the same PKCS12 file encoded as base64), FIELDLOG_RELEASE_STORE_PASSWORD and FIELDLOG_RELEASE_KEY_PASSWORD as repository secrets. The alias is fieldlog-release. This change does not configure remote secrets or publish a release; missing secrets fail the workflow. CI debug artifacts remain for development.

Android will reject a direct update from a differently signed old debug build. Keep the old app and records; no uninstall or data clearing is attempted by the installer. There is no complete editable-library backup/restore yet, and a capture ZIP is not such a backup. Once a fixed-key release is installed, future builds must keep its application ID, key and increasing versionCode. Certificate verification does not replace an actual device update test.

## Windows development setup

1. Clone or extract the source into one `RokidFieldLog` directory. Keep all project tools and test files there. Use Windows PowerShell with `curl.exe` and `tar.exe` available.
2. Read the [Android SDK license](https://developer.android.com/studio#downloads). Run the following command only after accepting it. It downloads checksum-verified Android Studio, JDK 21, SDK command-line tools, API 36, build-tools 35.0.0, and platform-tools into `.tools/`; archives and temporary files go into `.local/`. The downloads require several GB of disk space. It does not install apps on devices.

```powershell
.\scripts\Prepare-Tools.ps1 -AcceptSdkLicense
.\scripts\Prepare-Gradle.ps1
.\scripts\Build.ps1 -Verify -Clean
```

The Gradle wrapper pins its distribution checksum. `Use-ProjectEnvironment.ps1` sets SDK, Java, IDE settings, temporary files, and Gradle caches for the current process and its children. It does not change global environment variables. Source, XML, and JSON use UTF-8; a project-private Gradle setting handles Windows Java argument-file encoding when the path contains Chinese characters.

To use Android Studio, run `scripts/Open-AndroidStudio.ps1`. Select the existing project SDK `.tools/android-sdk` and Gradle JDK `.tools/jdk-21` in the IDE. Do not install another SDK through the first-run wizard. IDE startup, Android USB drivers, and non-Windows setup are not included in the hardware verification to date.

## Device authorization

Use the Rokid developer data cable and the official bare-metal/on-device instructions from [Rokid](https://developerdoc.rokid.com/sdk?lang=en). An ordinary charging cable may not expose ADB. Do not flash firmware or root a device for this project.

On a phone, developer mode alone is insufficient: USB debugging and the device's authorization for the computer are required. Approve any setting change on the device explicitly. Drivers and USB configuration are outside the project's tool preparation script.

**Windows ADB exception:** ADB creates or reuses `%USERPROFILE%\.android\adbkey` and `adbkey.pub` outside the project. They are computer authentication files, not project source. Obtain the computer owner's permission before first starting ADB. Never commit them. Project scripts use ADB server port 5047 to avoid interfering with another Android development session.

```powershell
.\scripts\Inspect-Devices.ps1
```

The script hides serials. A `device` state permits debugging; `unauthorized` requires device-side approval. No matching phone means the phone install must wait. The earlier Samsung test-phone diagnosis found MTP and modem interfaces, but no ADB interface; see [diagnosis](phone-connection.md). Do not infer a working ADB connection from a working file-transfer connection.

## Install and test the glasses app

After approving installation on the correct glasses:

```powershell
.\scripts\Install-App.ps1 -Target glasses -ConfirmInstallation -StartApp
```

The script verifies the device, SDK level, package, and APK classes. `install -r` retains the app's data. Only FieldLog is started; nothing is uninstalled or cleared. Permit its camera permission. Stand still for a test photograph. Success should show a brief local-save message, then hide it. The save message includes the selected category. Empty-background taps reveal controls; use the capture button or touchpad Enter to take a photo. After 60 seconds idle, the first capture input wakes the camera and the next captures. Physical gestures and wake latency need validation. The record remains pending location confirmation.

Private paths are `files/fieldlog/photos`, `records`, and `journals`. Only a debuggable build's own data can be inspected through Android Studio Device Explorer or `run-as org.rokidfieldlog.glasses`. Keep any copied evidence inside ignored `.local/`. Never dump or upload unrelated device data.

After authorizing synthetic storage tests on this app:

```powershell
.\scripts\Test-StorageRecovery.ps1 -ConfirmDeviceTest
```

Expected: 13 passed. These tests do not take a photograph or obtain location, and do not delete production data. Fixtures stay in the app-private `fieldlog/test-fixtures` directory until the user clears the app's project data or uninstalls it. See the [record/recovery contract](record-format.md). This revision still needs Android fixture verification.

## Install and test the phone probe

After approving installation and confirming a physical phone with an authorized ADB connection:

```powershell
.\scripts\Install-App.ps1 -Target phone -ConfirmInstallation -StartApp
```

From the companion home screen choose “更多：演示与定位检查” → “定位检查”. Permit foreground precise or approximate location. Outdoors, tap the automatic location test. It waits up to 8 seconds for Fused, then uses remaining time for system providers, with a 60-second total deadline. A valid low-precision observation is shown provisionally while waiting for a more accurate fix. At the deadline only a fix no older than 5 seconds can become a labeled best-available result. The Android-system button bypasses Fused. Leaving the screen or pressing Stop cancels the request. Failure saves a reason and null location, never fabricated coordinates.

Historical results are labeled as history. The phone probe has not yet been hardware-tested, including no-Google-services behavior. The private latest-result check is:

```powershell
.\scripts\Read-PhoneProbe.ps1
```

This script only reads this package and hides coordinates in the terminal. Reading a previous fix is not a new GPS observation.

## Delete and uninstall

The glasses app offers latest-photo review and confirmed undo. Its delete-all action is under Review → Data management; the phone location-check screen has a separate delete-all button. Offline records also have a per-record delete action and a synthetic-only cleanup action. Confirming Delete removes only that app's internal `files/fieldlog` subtree, including its own records, photos, recovery backups, and fixture data; phone delete-all also clears its `cache/reviewed-shares` snapshots. Cancel must preserve the data. Ordinary Back from the issue editor saves valid edits. Actual positive deletion has not been tested against production photos. Android's app storage clear or uninstall also removes private caches.

Normal Android uninstall removes the app's private files, databases, and caches. Uninstall each FieldLog app through its device's normal app management UI or an explicitly authorized package uninstall. Do not use ADB shell commands to delete gallery or shared-storage directories. Reinstalling over an existing build retains private data; uninstalling normally removes it. ADB's keep-data uninstall option would retain data and is not used by this project.

Materials exported through the Android system file picker belong to the location chosen by the user and remain after app cleanup or uninstall. Generic material export is implemented; local official-channel acceptance and Android UI/device validation are pending.

On the computer, source, tools, IDE configuration, downloads, and evidence are under the project root. Removing that directory after closing its tools cleans the project-local setup; review any private evidence you want to keep first. The approved ADB computer keys outside the project are shared with other Android debugging sessions. Do not remove them as a project cleanup step; doing so could revoke those sessions' computer identity.

### 本机已验证的签名迁移（2026-10-16）

连接的眼镜原为debug code1，本机保留了与之匹配的旧私钥。先备份181个项目文件并逐一核对SHA-256，再用旧密钥授权固定发布密钥的v3证书轮换；迁移包及随后普通发布包均覆盖成功，UID保持。此路径只对拥有匹配旧私钥的版本成立，不代表任意debug包都可跨签名覆盖。签名轮换证明与备份留在忽略的本机目录，普通CI发布仍用固定密钥。手机已从同一固定发布密钥code7正常覆盖到code8。真实UI数据核对和设备限制见[本轮验证记录](material-flow-validation.md)。
