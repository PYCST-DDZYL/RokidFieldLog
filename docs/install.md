# Installation, testing, and cleanup

This is a Phase 1 preview: glasses photography and an independent phone location probe. There is no photo sync, map, or export UI yet. See [current verification](phase1-status.md) before installing.

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

The script hides serials. A `device` state permits debugging; `unauthorized` requires device-side approval. No matching phone means the phone install must wait. The current Samsung test phone exposes MTP and modem interfaces, but no ADB interface; see [diagnosis](phone-connection.md). Do not infer a working ADB connection from a working file-transfer connection.

## Install and test the glasses app

After approving installation on the correct glasses:

```powershell
.\scripts\Install-App.ps1 -Target glasses -ConfirmInstallation -StartApp
```

The script verifies the device, SDK level, package, and APK classes. `install -r` retains the app's data. Only FieldLog is started; nothing is uninstalled or cleared. Permit its camera permission. Stand still for a test photograph. Success should show a brief local-save message, then hide it. The record remains pending location confirmation.

Private paths are `files/fieldlog/photos`, `records`, and `journals`. Only a debuggable build's own data can be inspected through Android Studio Device Explorer or `run-as org.rokidfieldlog.glasses`. Keep any copied evidence inside ignored `.local/`. Never dump or upload unrelated device data.

After authorizing synthetic storage tests on this app:

```powershell
.\scripts\Test-StorageRecovery.ps1 -ConfirmDeviceTest
```

Expected: 13 passed. These tests do not take a photograph or obtain location, and do not delete production data. Fixtures stay in the app-private `fieldlog/test-fixtures` directory until the user clears the app's project data or uninstalls it. See the [latest reliability update](reliability-update.md).

## Install and test the phone probe

After approving installation and confirming a physical phone with an authorized ADB connection:

```powershell
.\scripts\Install-App.ps1 -Target phone -ConfirmInstallation -StartApp
```

Permit foreground precise or approximate location. Outdoors, tap the automatic location test. It waits up to 8 seconds for Fused, then uses remaining time for system providers, with a 20-second total deadline. The Android-system button bypasses Fused. Leaving the screen or pressing Stop cancels the request. Failure saves a reason and null location, never fabricated coordinates.

Historical results are labeled as history. The phone probe has not yet been hardware-tested, including no-Google-services behavior. The private latest-result check is:

```powershell
.\scripts\Read-PhoneProbe.ps1
```

This script only reads this package and hides coordinates in the terminal. Reading a previous fix is not a new GPS observation.

## Delete and uninstall

Each app offers **delete all project data**. Confirming Delete removes only that app's internal `files/fieldlog` subtree, including its own records, photos, recovery backups, and fixture data. Cancel and Back must preserve the data. Actual positive deletion has not been tested against production photos. App-private runtime caches, if present, are removed by Android's app storage clear or uninstall.

Normal Android uninstall removes the app's private files, databases, and caches. Uninstall each FieldLog app through its device's normal app management UI or an explicitly authorized package uninstall. Do not use ADB shell commands to delete gallery or shared-storage directories. Reinstalling over an existing build retains private data; uninstalling normally removes it. ADB's keep-data uninstall option would retain data and is not used by this project.

Future reports exported through the Android system file picker will belong to the location chosen by the user and will remain after app cleanup or uninstall. Export is not implemented yet.

On the computer, source, tools, IDE configuration, downloads, and evidence are under the project root. Removing that directory after closing its tools cleans the project-local setup; review any private evidence you want to keep first. The approved ADB computer keys outside the project are shared with other Android debugging sessions. Do not remove them as a project cleanup step; doing so could revoke those sessions' computer identity.
