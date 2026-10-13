param([switch]$Verify, [switch]$Clean)
. (Join-Path $PSScriptRoot 'Use-ProjectEnvironment.ps1')
if (!(Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) { throw 'Project JDK is missing. Prepare development tools first.' }
$gradleArguments = @('--no-daemon', ':glasses-app:assembleDebug', ':phone-app:assembleDebug')
if ($Clean) { $gradleArguments = @('--no-daemon', ':glasses-app:clean', ':phone-app:clean') + $gradleArguments[1..2] }
if ($Verify) { $gradleArguments += @(':fieldlog-core:test', ':glasses-app:lintDebug', ':phone-app:lintDebug') }
Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build or verification failed.' }
    & (Join-Path $PSScriptRoot 'Test-Apk.ps1') -Target glasses
    & (Join-Path $PSScriptRoot 'Test-Apk.ps1') -Target phone
} finally { Pop-Location }
