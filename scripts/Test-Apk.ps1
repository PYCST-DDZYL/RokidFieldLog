param([Parameter(Mandatory=$true)][ValidateSet('glasses','phone')][string]$Target)
. (Join-Path $PSScriptRoot 'Use-ProjectEnvironment.ps1')
$moduleName = if ($Target -eq 'glasses') { 'glasses-app' } else { 'phone-app' }
$relativeApk = "$moduleName/build/outputs/apk/debug/$moduleName-debug.apk"
$analyzer = Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/apkanalyzer.bat'
$expectedPackage = "org.rokidfieldlog.$Target"
$requiredClasses = @("$expectedPackage.MainActivity")
if ($Target -eq 'glasses') {
    $requiredClasses += @("$expectedPackage.GlassesApplication", "$expectedPackage.RecoveryInstrumentation", 'org.rokidfieldlog.core.CaptureFiles')
} else {
    $requiredClasses += @("$expectedPackage.LocationProbe", "$expectedPackage.LocationActivity", "$expectedPackage.IssueActivity", "$expectedPackage.PrivacyActivity", "$expectedPackage.RedactionView", "$expectedPackage.InspectionActivity", "$expectedPackage.InspectionLinkActivity", "$expectedPackage.InspectionCompareActivity", "$expectedPackage.InspectionSamples", 'org.rokidfieldlog.core.LocationAttempt', 'org.rokidfieldlog.core.OfflineLibrary', 'org.rokidfieldlog.core.MaterialExporter', 'org.rokidfieldlog.core.InspectionRepository')
}
Push-Location $projectRoot
try {
    if (!(Test-Path -LiteralPath $relativeApk)) { throw 'APK missing; build first.' }
    $packageId = ((& $analyzer manifest application-id $relativeApk) -join '').Trim()
    if ($LASTEXITCODE -ne 0 -or $packageId -ne $expectedPackage) { throw 'Unexpected APK package.' }
    # Inspect defined DEX classes, not strings/references or intermediate .class files.
    $dexRows = & $analyzer dex packages --defined-only $relativeApk
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect APK bytecode.' }
    foreach ($requiredClass in $requiredClasses) {
        $classPattern = '^C\s+d\s+.*\s' + [regex]::Escape($requiredClass) + '$'
        if (!($dexRows | Where-Object { $_ -match $classPattern })) {
            throw "APK is missing $requiredClass. Rebuild with -Clean before installing."
        }
    }
    $apkFile = Get-Item -LiteralPath $relativeApk
    $apkHash = (Get-FileHash -LiteralPath $relativeApk -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output "APK verified: $expectedPackage; $($apkFile.Length) bytes; SHA-256 $apkHash"
} finally { Pop-Location }
