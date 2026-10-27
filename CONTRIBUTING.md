# Contributing

当前重点是让一个真实报修事项顺利完成照片整理、隐私审阅、交接及复查。欢迎从 [问题反馈](https://github.com/PYCST-DDZYL/RokidFieldLog/issues/new/choose) 或 [本轮执行清单](docs/practical-product-scope.md#current-work) 开始。先说明具体使用障碍、预期行为及验证方法，改动尽量围绕一个可复现问题。

手机端的真实照片入口采用系统选择器。照片只在明确选择后复制到本应用私有存储；分享通过独立已审阅副本及临时读取授权完成。不要把 FileProvider 扩大到整个私有目录、存储根目录或原图库。导出或唤起分享面板不等于已受理或维修完成。

Keep hardware evidence distinct from unit tests and synthetic fixtures. Offline companion work is active; wireless glasses integration, maps and real recipient acceptance remain separate validation work.

- Use Kotlin and the existing Android modules. Keep project tools, temporary evidence, and test assets within the project root.
- Verify Rokid API names and compatibility against official documentation and the actual distributed SDK before adding an integration. An SDK mismatch blocks that integration; do not replace it with a claimed successful mock.
- Preserve null unknown positions, raw capture times, fix time/accuracy/source, and connection state. Photographer location and manually corrected issue location are distinct.
- AI assistance is now in scope, but requires explicit model provenance, domain-specific evaluation, abstention, and human review before activation. Do not label manual tags or fixtures as recognition success. Accounts, cloud storage, automatic submissions, and broad storage permissions remain outside version 1. Background location requires its own Phase 2 design and device acceptance.
- Obtain the device owner's authorization before installation, permission changes, setting changes, or destructive tests. Never operate on other apps' data or public photos.

## Check changes

GitHub Actions runs the shared tests, both APK builds and Android Lint on Ubuntu. A separate Windows job verifies cross-process operation locking with Windows PowerShell 5.1, without a device, release key or Android SDK. With JDK 21 and Android SDK 36 configured, Linux/macOS contributors can run:

~~~sh
./gradlew --no-daemon :fieldlog-core:test assembleDebug lintDebug
~~~


```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Test-ProjectOperationLock.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Build.ps1 -Verify -Clean
# Only after device-test authorization:
.\scripts\Test-StorageRecovery.ps1 -ConfirmDeviceTest
```

Report what was tested, device model/API without serial, actual results, and remaining limits. A passing build is not GPS, wearable-input, or communication evidence. Do not add hardware-success claims from fixtures.

## Before a commit or push

```powershell
git add README.md LICENSE CONTRIBUTING.md .gitignore
# Stage the intended source changes, then inspect them:
git diff --cached --stat
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Test-PublishSafety.ps1
```

The staged-file check rejects generated/private paths, photos, journals, probe snapshots, keys, unexpected binaries, and recognizable secrets. It also checks ignore rules. It is an additional guard, not a guarantee that every possible personal identifier is detectable. Review the staged diff yourself. Keep real evidence under ignored `.local/`; use clearly synthetic fixture values in tests. Never paste tokens, device serials, personal GPS, real photos, or raw device logs into commits or issues.

Dependencies retain their own licenses. The MIT license covers project code, not Android SDK tools, Google Play services, or Rokid SDK binaries.
