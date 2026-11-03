$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Invoke-ProjectNativeCapture.ps1')
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixture = Join-Path $project ('.local/publish-safety-fixture-' + [Guid]::NewGuid().ToString('N'))
$fixtureScripts = Join-Path $fixture 'scripts'
[IO.Directory]::CreateDirectory($fixtureScripts) | Out-Null
foreach ($name in @('Test-PublishSafety.ps1', 'Invoke-ProjectNativeCapture.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $fixtureScripts $name)
}
Copy-Item -LiteralPath (Join-Path $project '.gitignore') -Destination (Join-Path $fixture '.gitignore')
$gitExecutable = (Get-Command git -ErrorAction Stop).Source
$shellName = if ($PSVersionTable.PSEdition -eq 'Core') { 'pwsh.exe' } else { 'powershell.exe' }
$shell = Join-Path $PSHOME $shellName
function Invoke-FixtureGit([string[]]$Arguments) {
    $run = Invoke-ProjectNativeCapture -Executable $gitExecutable -NativeArguments (@('-C', $fixture) + $Arguments)
    if ($run.ExitCode -ne 0) { throw 'Isolated publish-safety Git fixture failed.' }
}
function Assert-PublishResult([bool]$Allowed, [switch]$AllTracked) {
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $fixtureScripts 'Test-PublishSafety.ps1'))
    if ($AllTracked) { $arguments += '-AllTracked' }
    $run = Invoke-ProjectNativeCapture -Executable $shell -NativeArguments $arguments
    if (($run.ExitCode -eq 0) -ne $Allowed) { throw "Publish-safety fixture returned the wrong result (expected allow=$Allowed)." }
    if (!$Allowed -and ($run.Lines -join ' ') -notlike '*Publish safety check failed*') {
        throw 'A fixture failed for an unrelated reason instead of rejecting publication.'
    }
}
Invoke-FixtureGit -Arguments @('init', '--quiet')
$readme = Join-Path $fixture 'README.md'
[IO.File]::WriteAllText($readme, 'SYNTHETIC harmless source')
Invoke-FixtureGit -Arguments @('add', '--', '.gitignore', 'README.md')
Assert-PublishResult $true
Assert-PublishResult $true -AllTracked

# The index is authoritative: unstaged edits cannot hide a sensitive staged blob.
$syntheticToken = ('gh' + 'p_') + ('A' * 36)
[IO.File]::WriteAllText($readme, $syntheticToken)
Assert-PublishResult $true
Invoke-FixtureGit -Arguments @('add', '--', 'README.md')
[IO.File]::WriteAllText($readme, 'SYNTHETIC safe working tree hiding an unsafe staged blob')
Assert-PublishResult $false
Assert-PublishResult $false -AllTracked
Invoke-FixtureGit -Arguments @('add', '--', 'README.md')
Assert-PublishResult $true

# Force-added ignored files must still be rejected before a real commit.
foreach ($relative in @('.private/fixture.txt', '.local/fixture.txt', '.env', 'captures/fixture.jpg', 'fixture.dll',
        'signing-copy.p12', 'signing-copy.pfx', 'last-probe.json.bak', 'last-probe.json.new',
        'last-successful-probe.json', 'last-successful-probe.json.bak', 'last-successful-probe.json.new')) {
    $target = Join-Path $fixture $relative
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
    [IO.File]::WriteAllText($target, 'SYNTHETIC publication rejection fixture')
    Invoke-FixtureGit -Arguments @('add', '-f', '--', $relative)
    Assert-PublishResult $false
    Assert-PublishResult $false -AllTracked
    Invoke-FixtureGit -Arguments @('rm', '--cached', '--quiet', '--', $relative)
}
Assert-PublishResult $true
Write-Output 'Publish-safety regressions passed: staged-blob authority, private paths, photos and unexpected binaries. No network or user repository index was used.'
