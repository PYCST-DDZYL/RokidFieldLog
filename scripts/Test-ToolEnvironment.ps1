. (Join-Path $PSScriptRoot 'Use-ProjectEnvironment.ps1')
& (Join-Path $PSScriptRoot 'Test-ProjectOperationLock.ps1')
$javaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe'
$versionRun = Invoke-ProjectNativeCapture -Executable $javaExecutable -NativeArguments @('-version')
if ($versionRun.ExitCode -ne 0 -or !($versionRun.Lines -match 'version')) { throw 'Informational stderr was mistaken for a failure.' }
$failedRun = Invoke-ProjectNativeCapture -Executable $javaExecutable -NativeArguments @('--fieldlog-invalid-fixture-option')
if ($failedRun.ExitCode -eq 0) { throw 'Nonzero exit status was lost.' }
$missingWasRejected = $false
try { Invoke-ProjectNativeCapture -Executable (Join-Path $localRoot 'missing-fixture-executable.exe') | Out-Null }
catch { $missingWasRejected = $_.Exception.Message -eq 'Required native executable is missing.' }
if (!$missingWasRejected) { throw 'Missing executable was not rejected.' }
$findExecutable = Join-Path $env:SystemRoot 'System32/findstr.exe'
$inputRun = Invoke-ProjectNativeCapture -Executable $findExecutable -NativeArguments @('/x','y') -InputLines @('y')
if ($inputRun.ExitCode -ne 0 -or $inputRun.Lines -notcontains 'y') { throw 'Native stdin did not reach the process.' }
foreach ($scriptFile in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1') {
    $tokens = $null; $parseErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($scriptFile.FullName, [ref]$tokens, [ref]$parseErrors) | Out-Null
    if ($parseErrors) { throw "Invalid PowerShell script: $($scriptFile.Name)" }
}
. (Join-Path $PSScriptRoot 'Get-ProjectBuildInputFingerprint.ps1')
$fixtureRoot = Join-Path $localRoot ('build-input-fixture-' + [Guid]::NewGuid().ToString('N'))
$fixtureSource = Join-Path $fixtureRoot 'phone-app/src/main'
New-Item -ItemType Directory -Path $fixtureSource -Force | Out-Null
$fixtureFile = Join-Path $fixtureSource 'Synthetic.kt'
[IO.File]::WriteAllText($fixtureFile, 'synthetic version 1')
$initialFingerprint = Get-ProjectBuildInputFingerprint -Root $fixtureRoot
Assert-ProjectBuildInputsUnchanged -Root $fixtureRoot -Expected $initialFingerprint
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'README.md'), 'documentation only')
Assert-ProjectBuildInputsUnchanged -Root $fixtureRoot -Expected $initialFingerprint
[IO.File]::WriteAllText($fixtureFile, 'synthetic version 2')
$changeRejected = $false
try { Assert-ProjectBuildInputsUnchanged -Root $fixtureRoot -Expected $initialFingerprint } catch { $changeRejected = $true }
if (!$changeRejected) { throw 'Source change during a build was not rejected.' }
[IO.File]::WriteAllText($fixtureFile, 'synthetic version 1')
Assert-ProjectBuildInputsUnchanged -Root $fixtureRoot -Expected $initialFingerprint
$extraFile = Join-Path $fixtureSource 'Added.kt'
[IO.File]::WriteAllText($extraFile, 'synthetic addition')
if ((Get-ProjectBuildInputFingerprint -Root $fixtureRoot) -eq $initialFingerprint) { throw 'New source file was not detected.' }
$withExtra = Get-ProjectBuildInputFingerprint -Root $fixtureRoot
Remove-Item -LiteralPath $extraFile
if ((Get-ProjectBuildInputFingerprint -Root $fixtureRoot) -eq $withExtra) { throw 'Removed source file was not detected.' }
Assert-ProjectBuildInputsUnchanged -Root $fixtureRoot -Expected $initialFingerprint
Write-Output "Tool regression checks passed on PowerShell $($PSVersionTable.PSVersion): native I/O, script parsing, unchanged inputs, documentation exclusion, source edits, additions and removals."
