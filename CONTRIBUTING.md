# Contributing

The project is currently validating Phase 1. Keep changes within the active phase and distinguish hardware evidence from unit tests and synthetic fixtures.

- Use Kotlin and the existing Android modules. Keep project tools, temporary evidence, and test assets within the project root.
- Verify Rokid API names and compatibility against official documentation and the actual distributed SDK before adding an integration. An SDK mismatch blocks that integration; do not replace it with a claimed successful mock.
- Preserve null unknown positions, raw capture times, fix time/accuracy/source, and connection state. Photographer location and manually corrected issue location are distinct.
- AI assistance is now in scope, but requires explicit model provenance, domain-specific evaluation, abstention, and human review before activation. Do not label manual tags or fixtures as recognition success. Accounts, cloud storage, automatic submissions, and broad storage permissions remain outside version 1. Background location requires its own Phase 2 design and device acceptance.
- Obtain the device owner's authorization before installation, permission changes, setting changes, or destructive tests. Never operate on other apps' data or public photos.

## Check changes

```powershell
.\scripts\Build.ps1 -Verify -Clean
# Only after device-test authorization:
.\scripts\Test-StorageRecovery.ps1 -ConfirmDeviceTest
```

Report what was tested, device model/API without serial, actual results, and remaining limits. A passing build is not GPS, wearable-input, or communication evidence. Do not add hardware-success claims from fixtures.

## Before a commit or push

```powershell
git add README.md LICENSE CONTRIBUTING.md .gitignore
# Stage the intended source changes, then inspect them:
git diff --cached --stat
.\scripts\Test-PublishSafety.ps1
```

The staged-file check rejects generated/private paths, photos, journals, probe snapshots, keys, unexpected binaries, and recognizable secrets. It also checks ignore rules. It is an additional guard, not a guarantee that every possible personal identifier is detectable. Review the staged diff yourself. Keep real evidence under ignored `.local/`; use clearly synthetic fixture values in tests. Never paste tokens, device serials, personal GPS, real photos, or raw device logs into commits or issues.

Dependencies retain their own licenses. The MIT license covers project code, not Android SDK tools, Google Play services, or Rokid SDK binaries.
