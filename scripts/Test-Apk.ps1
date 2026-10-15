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
    $requiredClasses += @("$expectedPackage.LocationProbe", "$expectedPackage.LocationActivity", "$expectedPackage.IssueActivity", "$expectedPackage.PrivacyActivity", "$expectedPackage.RedactionView", "$expectedPackage.InspectionActivity", "$expectedPackage.InspectionLinkActivity", "$expectedPackage.InspectionCompareActivity", "$expectedPackage.InspectionSamples", "$expectedPackage.ReportActivity", "$expectedPackage.MaterialSharing", "$expectedPackage.ReviewedPhotoProvider", 'org.rokidfieldlog.core.LocationAttempt', 'org.rokidfieldlog.core.OfflineLibrary', 'org.rokidfieldlog.core.MaterialExporter', 'org.rokidfieldlog.core.InspectionRepository', 'org.rokidfieldlog.core.ReportRepository', 'org.rokidfieldlog.core.ReviewedShareCache', 'org.rokidfieldlog.core.MaterialDocumentCopy')
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
    if ($Target -eq 'phone') {
        $manifestLines = & $analyzer manifest print $relativeApk
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the packaged manifest.' }
        [xml]$manifestXml = $manifestLines -join "`n"
        $androidNamespace = 'http://schemas.android.com/apk/res/android'
        $providers = @($manifestXml.SelectNodes('/manifest/application/provider') | Where-Object { $_.GetAttribute('authorities', $androidNamespace) -eq "$expectedPackage.reviewed-shares" })
        if ($providers.Count -ne 1 -or $providers[0].GetAttribute('name', $androidNamespace) -ne "$expectedPackage.ReviewedPhotoProvider" -or
            $providers[0].GetAttribute('exported', $androidNamespace) -ne 'false' -or $providers[0].GetAttribute('grantUriPermissions', $androidNamespace) -ne 'true') {
            throw 'Reviewed photo provider is missing or has unsafe access settings.'
        }
        $pathsLines = & $analyzer resources xml --file /res/xml/reviewed_share_paths.xml $relativeApk
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect packaged share paths.' }
        [xml]$pathsXml = $pathsLines -join "`n"
        $sharePaths = @($pathsXml.SelectNodes('/paths/*'))
        if ($sharePaths.Count -ne 1 -or $sharePaths[0].LocalName -ne 'cache-path' -or $sharePaths[0].GetAttribute('path') -ne 'reviewed-shares/') {
            throw 'Share provider must expose only the reviewed-shares cache.'
        }
    }
    $apkFile = Get-Item -LiteralPath $relativeApk
    $apkHash = (Get-FileHash -LiteralPath $relativeApk -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output "APK verified: $expectedPackage; $($apkFile.Length) bytes; SHA-256 $apkHash"
} finally { Pop-Location }
