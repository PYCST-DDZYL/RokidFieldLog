param(
    [Parameter(Mandatory=$true)][ValidateSet('glasses','phone')][string]$Target,
    [switch]$ConfirmInstallation,
    [switch]$StartApp,
    [switch]$Release
)
if (!$ConfirmInstallation) { throw '设备安装须经用户明确授权，确认后使用 -ConfirmInstallation。' }
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Enter-ProjectOperationLock.ps1')
$operationLock = Enter-ProjectOperationLock -Root (Join-Path $PSScriptRoot '..')
try {
. (Join-Path $PSScriptRoot 'Use-ProjectEnvironment.ps1')
. (Join-Path $PSScriptRoot 'Get-ProjectBuildInputFingerprint.ps1')
$adbExecutable = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$env:ANDROID_ADB_LOG_PATH = Join-Path $localRoot 'adb-server.log'
$expectedPackage = "org.rokidfieldlog.$Target"
$moduleName = if ($Target -eq 'glasses') { 'glasses-app' } else { 'phone-app' }
$variant = if ($Release) { "release" } else { "debug" }
$relativeApk = "$moduleName/build/outputs/apk/$variant/$moduleName-$variant.apk"
if (!(Test-Path -LiteralPath (Join-Path $projectRoot $relativeApk))) { throw 'Build the APK first.' }
$receiptPath = Join-Path $localRoot ("verified-build-$variant.json")
if (!(Test-Path -LiteralPath $receiptPath)) { throw 'Run scripts/Build.ps1 to verify these APKs before installing.' }
$receipt = Get-Content -LiteralPath $receiptPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($receipt.status -cne 'VERIFIED' -or $receipt.variant -cne $variant) { throw 'Build did not complete verification; rebuild before installing.' }
Assert-ProjectBuildInputsUnchanged -Root $projectRoot -Expected $receipt.inputFingerprint
if ($receipt.apkHashes.$Target -cne (Get-FileHash -LiteralPath (Join-Path $projectRoot $relativeApk) -Algorithm SHA256).Hash.ToLowerInvariant()) { throw 'APK differs from the verified build; rebuild before installing.' }
& (Join-Path $PSScriptRoot 'Test-Apk.ps1') -Target $Target -Variant $variant
$candidates = @()
$rows = & $adbExecutable -P 5047 devices -l
foreach ($row in $rows) {
    if ($row -notmatch '^([^\s]+)\s+device\b') { continue }
    $identifier = $Matches[1]
    $brand = ((& $adbExecutable -P 5047 -s $identifier shell getprop ro.product.brand) -join '').Trim()
    $model = ((& $adbExecutable -P 5047 -s $identifier shell getprop ro.product.model) -join '').Trim()
    $apiLevel = ((& $adbExecutable -P 5047 -s $identifier shell getprop ro.build.version.sdk) -join '').Trim()
    $emulator = ((& $adbExecutable -P 5047 -s $identifier shell getprop ro.kernel.qemu) -join '').Trim()
    $isGlasses = $brand -eq 'Rokid' -and $model -eq 'RG-glasses'
    if (($Target -eq 'glasses' -and $isGlasses -and [int]$apiLevel -ge 31) -or
        ($Target -eq 'phone' -and !$isGlasses -and $brand -ne 'Rokid' -and $emulator -ne '1' -and [int]$apiLevel -ge 28)) {
        $candidates += @{Identifier=$identifier; Brand=$brand; Model=$model}
    }
}
if ($candidates.Count -ne 1) { throw "Expected exactly one verified $Target device; found $($candidates.Count). No APK installed." }
$device = $candidates[0]
Push-Location $projectRoot
try {
    $aaptExecutable = Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\aapt.exe'
    $badging = (& $aaptExecutable dump badging $relativeApk) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $badging -notmatch "package: name='$([regex]::Escape($expectedPackage))'") { throw 'Unexpected APK application ID; installation cancelled.' }
    Write-Output "Installing $expectedPackage on $($device.Brand) $($device.Model) (identifier hidden)."
    # -r updates only this project's package and preserves its private data.
    & $adbExecutable -P 5047 -s $device.Identifier install -r $relativeApk
    if ($LASTEXITCODE -ne 0) { throw 'APK installation failed. No uninstall or data clearing will be attempted.' }
    if ($StartApp) {
        $launchOutput = & $adbExecutable -P 5047 -s $device.Identifier shell am start -W -n "$expectedPackage/.MainActivity"
        if ($LASTEXITCODE -ne 0 -or ($launchOutput -join "`n") -notmatch 'Status: ok') { throw 'App launch failed.' }
        $launchOutput | Where-Object { $_ -match '^(Status|Activity|LaunchState|TotalTime):' } | Write-Output
        # am start can say ok even when Application immediately crashes.
        Start-Sleep -Seconds 2
        $appProcess = ((& $adbExecutable -P 5047 -s $device.Identifier shell pidof $expectedPackage) -join '').Trim()
        if ($appProcess -notmatch '^\d+(\s+\d+)*$') { throw 'App process exited after launch; inspect this package crash before continuing.' }
    }
} finally { Pop-Location }
} finally { $operationLock.Dispose() }
