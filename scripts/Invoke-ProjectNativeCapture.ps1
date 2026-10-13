function Invoke-ProjectNativeCapture {
    param(
        [Parameter(Mandatory=$true)][string]$Executable,
        [string[]]$NativeArguments = @(),
        [string[]]$InputLines
    )
    if (!(Test-Path -LiteralPath $Executable -PathType Leaf)) { throw 'Required native executable is missing.' }
    # Windows PowerShell 5.1 turns redirected native stderr into ErrorRecord.
    # Informational stderr is not failure: inspect the process exit code instead.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = $null
        if ($PSBoundParameters.ContainsKey('InputLines')) {
            $nativeLines = @($InputLines | & $Executable @NativeArguments 2>&1 | ForEach-Object { $_.ToString() })
        } else {
            $nativeLines = @(& $Executable @NativeArguments 2>&1 | ForEach-Object { $_.ToString() })
        }
        $nativeExitCode = $global:LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($null -eq $nativeExitCode) { throw 'Native process did not return an exit code.' }
    [pscustomobject]@{ Lines = [string[]]$nativeLines; ExitCode = [int]$nativeExitCode }
}
