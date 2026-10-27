$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Enter-ProjectOperationLock.ps1')
. (Join-Path $PSScriptRoot 'Invoke-ProjectNativeCapture.ps1')
$lockTestRoot = Join-Path (Join-Path $PSScriptRoot '../.local') ('operation-lock-fixture-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($lockTestRoot) | Out-Null
$childScript = Join-Path $lockTestRoot 'Try-Lock.ps1'
$childCode = @'
param([string]$Helper, [string]$Root)
$ErrorActionPreference = 'Stop'
. $Helper
try { $held = Enter-ProjectOperationLock -Root $Root }
catch { if ($_.Exception.Message -like 'Cannot acquire the project lock.*') { exit 17 }; throw }
try { Write-Output 'acquired' } finally { $held.Dispose() }
'@
[IO.File]::WriteAllText($childScript, $childCode, [Text.UTF8Encoding]::new($false))
$shellName = if ($PSVersionTable.PSEdition -eq 'Core') { 'pwsh.exe' } else { 'powershell.exe' }
$shell = Join-Path $PSHOME $shellName
$helper = Join-Path $PSScriptRoot 'Enter-ProjectOperationLock.ps1'
$arguments = @('-NoProfile','-ExecutionPolicy','Bypass','-File',$childScript,'-Helper',$helper,'-Root',$lockTestRoot)
$first = Enter-ProjectOperationLock -Root $lockTestRoot
try {
    $blocked = Invoke-ProjectNativeCapture -Executable $shell -NativeArguments $arguments
    if ($blocked.ExitCode -ne 17) { throw 'A second process acquired a held project lock.' }
    $other = Enter-ProjectOperationLock -Root (Join-Path $lockTestRoot 'other-project')
    $other.Dispose()
} finally { $first.Dispose() }
$retried = Invoke-ProjectNativeCapture -Executable $shell -NativeArguments $arguments
if ($retried.ExitCode -ne 0 -or $retried.Lines -notcontains 'acquired') { throw 'Lock remained held after release.' }
try {
    $failed = Enter-ProjectOperationLock -Root $lockTestRoot
    try { throw 'SYNTHETIC operation failure' } finally { $failed.Dispose() }
} catch { if ($_.Exception.Message -ne 'SYNTHETIC operation failure') { throw } }
$afterFailure = Enter-ProjectOperationLock -Root $lockTestRoot
$afterFailure.Dispose()
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$activeBuild = Enter-ProjectOperationLock -Root $project
try {
    $attempt = Invoke-ProjectNativeCapture -Executable $shell -NativeArguments @(
        '-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Build.ps1'),'-Clean')
    if ($attempt.ExitCode -eq 0 -or ($attempt.Lines -join ' ') -notlike '*Cannot acquire the project lock.*') {
        throw 'Build did not reject an active operation before running its clean tasks.'
    }
} finally { $activeBuild.Dispose() }
# Exercise the actual build wrapper's finally block without invoking Java/Gradle.
$fixtureScripts = Join-Path $lockTestRoot 'scripts'
[IO.Directory]::CreateDirectory($fixtureScripts) | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Build.ps1'),$helper -Destination $fixtureScripts
[IO.File]::WriteAllText((Join-Path $fixtureScripts 'Use-ProjectEnvironment.ps1'), "throw 'SYNTHETIC environment failure'")
$environmentFailure = Invoke-ProjectNativeCapture -Executable $shell -NativeArguments @(
    '-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $fixtureScripts 'Build.ps1'))
if ($environmentFailure.ExitCode -eq 0) { throw 'Synthetic setup failure was lost.' }
$afterSetupFailure = Enter-ProjectOperationLock -Root $lockTestRoot
$afterSetupFailure.Dispose()
Write-Output 'Operation lock checks passed: cross-process exclusion, independent projects, release and failure recovery.'
