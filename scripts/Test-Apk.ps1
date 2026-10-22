param([Parameter(Mandatory=$true)][ValidateSet('glasses','phone')][string]$Target, [ValidateSet('debug','release')][string]$Variant = 'debug')
. (Join-Path $PSScriptRoot 'Use-ProjectEnvironment.ps1')
$moduleName = if ($Target -eq 'glasses') { 'glasses-app' } else { 'phone-app' }
$relativeApk = "$moduleName/build/outputs/apk/$Variant/$moduleName-$Variant.apk"
$analyzer = Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/apkanalyzer.bat'
$expectedPackage = "org.rokidfieldlog.$Target"
$requiredClasses = @("$expectedPackage.MainActivity")
$requiredClasses += 'org.rokidfieldlog.core.RecordJson'
if ($Target -eq 'glasses') {
    $requiredClasses += @("$expectedPackage.GlassesApplication", 'org.rokidfieldlog.core.CaptureFiles', 'org.rokidfieldlog.core.CapturePackage')
    if ($Variant -eq 'debug') { $requiredClasses += "$expectedPackage.RecoveryInstrumentation" }
} else {
    $requiredClasses += @("$expectedPackage.OnSiteActivity", "$expectedPackage.AddressSuggestionLookup", "$expectedPackage.ReportHandoffActivity", "$expectedPackage.ReferenceLocationPicker", "org.rokidfieldlog.core.PhotographerReference", "$expectedPackage.LocationProbe", "$expectedPackage.LocationActivity", "$expectedPackage.IssueActivity", "$expectedPackage.PrivacyActivity", "$expectedPackage.RedactionView", "$expectedPackage.InspectionActivity", "$expectedPackage.InspectionLinkActivity", "$expectedPackage.InspectionCompareActivity", "$expectedPackage.InspectionSamples", "$expectedPackage.ReportActivity", "$expectedPackage.MaterialSharing", "$expectedPackage.ReviewedPhotoProvider", 'org.rokidfieldlog.core.LocationAttempt', 'org.rokidfieldlog.core.OfflineLibrary', 'org.rokidfieldlog.core.MaterialExporter', 'org.rokidfieldlog.core.InspectionRepository', 'org.rokidfieldlog.core.ReportRepository', 'org.rokidfieldlog.core.ReviewedShareCache', 'org.rokidfieldlog.core.MaterialDocumentCopy')
}
if ($Target -eq 'phone') { $requiredClasses += 'org.rokidfieldlog.core.FreshJpeg' }
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
        # Release resource optimization can shorten res/xml paths. Resolve the named resource first.
        $packagedPathsResource = ((& $analyzer resources value --config default --type xml --name reviewed_share_paths $relativeApk) -join '').Trim()
        if ($LASTEXITCODE -ne 0 -or $packagedPathsResource -notmatch '^res/[A-Za-z0-9_/-]+\.xml$') { throw 'Cannot resolve packaged share paths.' }
        $pathsLines = & $analyzer resources xml --file "/$packagedPathsResource" $relativeApk
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect packaged share paths.' }
        [xml]$pathsXml = $pathsLines -join "`n"
        $sharePaths = @($pathsXml.SelectNodes('/paths/*'))
        if ($sharePaths.Count -ne 1 -or $sharePaths[0].LocalName -ne 'cache-path' -or $sharePaths[0].GetAttribute('path') -ne 'reviewed-shares/') {
            throw 'Share provider must expose only the reviewed-shares cache.'
        }
    }
    if ($Variant -eq 'release') {
        $signer = Join-Path $env:ANDROID_HOME 'build-tools/35.0.0/apksigner.bat'
        $signatureRun = Invoke-ProjectNativeCapture -Executable $signer -NativeArguments @('verify','--verbose','--print-certs',$relativeApk)
        if ($signatureRun.ExitCode -ne 0) { throw 'Release APK signature verification failed.' }
        $signatureRun.Lines | Write-Output
        $certificatePin = (Get-Content -LiteralPath (Join-Path $projectRoot 'release-certificate.sha256') -Raw).Trim()
        $signatureText = $signatureRun.Lines -join "
"
        if ($signatureText -notmatch ('Signer #1 certificate SHA-256 digest: ' + [regex]::Escape($certificatePin))) { throw 'APK does not use the retained release certificate.' }
        $debugFlag = ((& $analyzer manifest debuggable $relativeApk) -join '').Trim()
        if ($LASTEXITCODE -ne 0 -or $debugFlag -ne 'false') { throw 'Release APK must not be debuggable.' }
    }
    $apkFile = Get-Item -LiteralPath $relativeApk
    $apkHash = (Get-FileHash -LiteralPath $relativeApk -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output "APK verified: $expectedPackage; $($apkFile.Length) bytes; SHA-256 $apkHash"
} finally { Pop-Location }
