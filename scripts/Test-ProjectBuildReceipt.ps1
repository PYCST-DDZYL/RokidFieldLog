$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Invoke-ProjectNativeCapture.ps1')
$receiptFixture = Join-Path (Join-Path $PSScriptRoot '../.local') ('receipt-fixture-' + [Guid]::NewGuid().ToString('N'))
$fixtureScripts = Join-Path $receiptFixture 'scripts'
[IO.Directory]::CreateDirectory($fixtureScripts) | Out-Null
foreach ($name in @('Build.ps1','Enter-ProjectOperationLock.ps1','Get-ProjectBuildInputFingerprint.ps1','Assert-ProjectBuildReceipt.ps1')) {
    $source = Join-Path $PSScriptRoot $name
    if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $fixtureScripts }
}
# Script-control fixtures only. No Java, Android SDK, APK verification or device is invoked.
$environment = @'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localRoot = Join-Path $projectRoot '.local'
$env:JAVA_HOME = Join-Path $localRoot 'jdk'
function Invoke-ProjectNativeCapture {
    param([string]$Executable, [string[]]$NativeArguments)
    [IO.File]::WriteAllText((Join-Path $localRoot 'tasks.txt'), ($NativeArguments -join ' '))
    [pscustomobject]@{ExitCode=0;Lines=@('SYNTHETIC build-control fixture')}
}
'@
[IO.File]::WriteAllText((Join-Path $fixtureScripts 'Use-ProjectEnvironment.ps1'), $environment)
[IO.File]::WriteAllText((Join-Path $fixtureScripts 'Test-Apk.ps1'), 'param([string]$Target,[string]$Variant)')
$fakeJava = Join-Path $receiptFixture '.local/jdk/bin'
[IO.Directory]::CreateDirectory($fakeJava) | Out-Null
[IO.File]::WriteAllText((Join-Path $fakeJava 'java.exe'), 'SYNTHETIC inert placeholder, never executed')
foreach ($target in @('glasses','phone')) {
    $directory = Join-Path $receiptFixture "$target-app/build/outputs/apk/release"
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    [IO.File]::WriteAllText((Join-Path $directory "$target-app-release.apk"), 'SYNTHETIC inert APK placeholder')
}
$shellName = if ($PSVersionTable.PSEdition -eq 'Core') { 'pwsh.exe' } else { 'powershell.exe' }
$shell = Join-Path $PSHOME $shellName
$buildArguments = @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $fixtureScripts 'Build.ps1'),'-Release')
$partialRun = Invoke-ProjectNativeCapture -Executable $shell -NativeArguments $buildArguments
if ($partialRun.ExitCode -ne 0) { throw 'APK-only script-control fixture failed.' }
$receiptFile = Join-Path $receiptFixture '.local/verified-build-release.json'
$partial = Get-Content -Raw -Encoding UTF8 $receiptFile | ConvertFrom-Json
if ($partial.status -cne 'APK_VERIFIED') { throw 'A build without -Verify was mislabeled as complete verification.' }
if ($partial.checks -contains 'CORE_TESTS' -or $partial.checks -contains 'ANDROID_LINT') { throw 'Unperformed checks were recorded.' }
$fullRun = Invoke-ProjectNativeCapture -Executable $shell -NativeArguments ($buildArguments + '-Verify')
if ($fullRun.ExitCode -ne 0) { throw 'Full script-control fixture failed.' }
$full = Get-Content -Raw -Encoding UTF8 $receiptFile | ConvertFrom-Json
. (Join-Path $PSScriptRoot 'Assert-ProjectBuildReceipt.ps1')
Assert-ProjectBuildReceipt -Receipt $full -Variant release
$debug = $full | ConvertTo-Json -Depth 5 | ConvertFrom-Json
$debug.variant = 'debug'; $debug.checks = @($debug.checks | Where-Object { $_ -ne 'RELEASE_SIGNATURE' })
Assert-ProjectBuildReceipt -Receipt $debug -Variant debug
$withoutSignature = $full | ConvertTo-Json -Depth 5 | ConvertFrom-Json
$withoutSignature.checks = @($withoutSignature.checks | Where-Object { $_ -ne 'RELEASE_SIGNATURE' })
$tasks = Get-Content -Raw -Encoding UTF8 (Join-Path $receiptFixture '.local/tasks.txt')
foreach ($required in @(':fieldlog-core:test',':glasses-app:lintRelease',':phone-app:lintRelease')) {
    if (!$tasks.Contains($required)) { throw 'Full verification omitted a required Gradle task.' }
}
$badReceipts = @($partial, $withoutSignature)
foreach ($key in @('schemaVersion','checks','inputFingerprint','status','variant','apkHashes')) {
    $copy = $full | ConvertTo-Json -Depth 5 | ConvertFrom-Json
    switch ($key) {
        'schemaVersion' { $copy.schemaVersion = '2' }
        'checks' { $copy.checks = @($copy.checks | Where-Object { $_ -ne 'CORE_TESTS' }) }
        'inputFingerprint' { $copy.inputFingerprint = 'invalid' }
        'status' { $copy.status = 'BUILDING' }
        'variant' { $copy.variant = 'debug' }
        'apkHashes' { $copy.apkHashes.phone = 'invalid' }
    }
    $badReceipts += $copy
}
foreach ($invalid in $badReceipts) {
    $rejected = $false
    try { Assert-ProjectBuildReceipt -Receipt $invalid -Variant release } catch { $rejected = $true }
    if (!$rejected) { throw 'Incomplete or malformed verification receipt was accepted.' }
}
Write-Output 'Build receipt control checks passed: APK-only/full distinction, required tasks and malformed receipt rejection. These fixtures do not verify real APKs.'
