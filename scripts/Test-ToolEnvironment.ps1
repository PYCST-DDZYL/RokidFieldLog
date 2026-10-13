. (Join-Path $PSScriptRoot 'Use-ProjectEnvironment.ps1')
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
Write-Output "Tool regression checks passed on PowerShell $($PSVersionTable.PSVersion): stderr, failure exit, missing executable, stdin, and script parsing."
