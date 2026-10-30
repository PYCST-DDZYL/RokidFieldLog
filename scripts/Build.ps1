param([switch]$Verify, [switch]$Clean, [switch]$Release)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Enter-ProjectOperationLock.ps1')
$operationLock = Enter-ProjectOperationLock -Root (Join-Path $PSScriptRoot '..')
try {
. (Join-Path $PSScriptRoot 'Use-ProjectEnvironment.ps1')
. (Join-Path $PSScriptRoot 'Get-ProjectBuildInputFingerprint.ps1')
if (!(Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) { throw 'Project JDK is missing. Prepare development tools first.' }
$variantTask = if ($Release) { 'Release' } else { 'Debug' }
$gradleArguments = @('--no-daemon', ":glasses-app:assemble$variantTask", ":phone-app:assemble$variantTask")
if ($Clean) { $gradleArguments = @('--no-daemon', '--no-build-cache', ':fieldlog-core:clean', ':glasses-app:clean', ':phone-app:clean') + $gradleArguments[1..2] }
if ($Verify) { $gradleArguments += @(':fieldlog-core:test', ":glasses-app:lint$variantTask", ":phone-app:lint$variantTask") }
Push-Location $projectRoot
try {
    $variant = $variantTask.ToLowerInvariant()
    $inputFingerprint = Get-ProjectBuildInputFingerprint -Root $projectRoot
    $receiptPath = Join-Path $localRoot ("verified-build-$variant.json")
    [IO.File]::WriteAllText($receiptPath, (@{schemaVersion=2;status='BUILDING';variant=$variant;inputFingerprint=$inputFingerprint;checks=@()} | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    # Windows PowerShell 5 treats redirected informational stderr as terminating
    # errors under Stop. Keep Gradle diagnostics and use its real exit code.
    $buildRun = Invoke-ProjectNativeCapture -Executable (Join-Path $projectRoot 'gradlew.bat') -NativeArguments $gradleArguments
    $buildRun.Lines | Write-Output
    if ($buildRun.ExitCode -ne 0) { throw 'Gradle build or verification failed.' }
    Assert-ProjectBuildInputsUnchanged -Root $projectRoot -Expected $inputFingerprint
    & (Join-Path $PSScriptRoot 'Test-Apk.ps1') -Target glasses -Variant $variantTask.ToLowerInvariant()
    & (Join-Path $PSScriptRoot 'Test-Apk.ps1') -Target phone -Variant $variantTask.ToLowerInvariant()
    Assert-ProjectBuildInputsUnchanged -Root $projectRoot -Expected $inputFingerprint
    $apkHashes = @{}
    foreach ($target in @('glasses','phone')) {
        $apkHashes[$target] = (Get-FileHash -LiteralPath (Join-Path $projectRoot "$target-app/build/outputs/apk/$variant/$target-app-$variant.apk") -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $checks = @('APK_CONTENT','BUILD_INPUTS')
    if ($Release) { $checks += 'RELEASE_SIGNATURE' }
    if ($Verify) { $checks += @('CORE_TESTS','ANDROID_LINT') }
    $status = if ($Verify) { 'VERIFIED' } else { 'APK_VERIFIED' }
    [IO.File]::WriteAllText($receiptPath, (@{schemaVersion=2;status=$status;variant=$variant;inputFingerprint=$inputFingerprint;apkHashes=$apkHashes;checks=$checks} | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    Write-Output "Build inputs unchanged; private receipt status: $status."
    if (!$Verify) { Write-Output 'Core tests and Android Lint were not requested. Run Build.ps1 -Verify before guarded installation.' }
} finally { Pop-Location }
} finally { $operationLock.Dispose() }
