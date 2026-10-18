param([switch]$Verify, [switch]$Clean, [switch]$Release)
. (Join-Path $PSScriptRoot 'Use-ProjectEnvironment.ps1')
if (!(Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) { throw 'Project JDK is missing. Prepare development tools first.' }
$variantTask = if ($Release) { 'Release' } else { 'Debug' }
$gradleArguments = @('--no-daemon', ":glasses-app:assemble$variantTask", ":phone-app:assemble$variantTask")
if ($Clean) { $gradleArguments = @('--no-daemon', ':glasses-app:clean', ':phone-app:clean') + $gradleArguments[1..2] }
if ($Verify) { $gradleArguments += @(':fieldlog-core:test', ":glasses-app:lint$variantTask", ":phone-app:lint$variantTask") }
Push-Location $projectRoot
try {
    # Windows PowerShell 5 treats redirected informational stderr as terminating
    # errors under Stop. Keep Gradle diagnostics and use its real exit code.
    $buildRun = Invoke-ProjectNativeCapture -Executable (Join-Path $projectRoot 'gradlew.bat') -NativeArguments $gradleArguments
    $buildRun.Lines | Write-Output
    if ($buildRun.ExitCode -ne 0) { throw 'Gradle build or verification failed.' }
    & (Join-Path $PSScriptRoot 'Test-Apk.ps1') -Target glasses -Variant $variantTask.ToLowerInvariant()
    & (Join-Path $PSScriptRoot 'Test-Apk.ps1') -Target phone -Variant $variantTask.ToLowerInvariant()
} finally { Pop-Location }
