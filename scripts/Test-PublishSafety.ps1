param([switch]$AllTracked)
$ErrorActionPreference = 'Stop'
$sourceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'Invoke-ProjectNativeCapture.ps1')
Push-Location $sourceRoot
try {
    $gitExecutable = (Get-Command git -ErrorAction Stop).Source
    # --show-prefix avoids decoding an absolute Chinese path from native Git stdout.
    $prefixRun = Invoke-ProjectNativeCapture -Executable $gitExecutable -NativeArguments @('rev-parse','--show-prefix')
    if ($prefixRun.ExitCode -ne 0 -or ($prefixRun.Lines -join '').Trim() -ne '') {
        throw 'Initialize Git only in the RokidFieldLog project root first.'
    }
    $checkedPaths = if ($AllTracked) { @(& git -c core.quotepath=false ls-files --cached) }
        else { @(& git -c core.quotepath=false diff --cached --name-only --diff-filter=ACMR) }
    if ($LASTEXITCODE -ne 0 -or @($checkedPaths).Count -eq 0) { throw 'No source files to check.' }
    $failures = [System.Collections.Generic.List[string]]::new()
    $pathPattern = '(^|/)(\.tools|\.local|\.private|\.gradle|\.kotlin|\.idea|build|exports|recordings|device-data|captures|gps-records)(/|$)|(^|/)(local\.properties|\.env[^/]*|adbkey(?:\.pub)?|last-(?:successful-)?probe\.json(?:\.(?:bak|new))?)$|\.(apk|aab|zip|jpg|jpeg|png|heic|mp4|wav|jks|keystore|p12|pfx|pem|key|log|hprof|journal)(\.new)?$'
    $secretPatterns = @(
        '\bgh[pousr]_[A-Za-z0-9]{20,}\b',
        '\bgithub_pat_[A-Za-z0-9_]{30,}\b',
        '\bAKIA[A-Z0-9]{16}\b',
        '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
        '(?m)^\s*"(?:latitude|longitude)"\s*:\s*-?\d'
    )
    foreach ($relativePath in $checkedPaths) {
        if ($relativePath -match $pathPattern) { $failures.Add("Private/generated path: $relativePath"); continue }
        if ($relativePath -match '\.(jar|class|dex|dll|exe|so|bin)$' -and $relativePath -ne 'gradle/wrapper/gradle-wrapper.jar') {
            $failures.Add("Unexpected binary: $relativePath"); continue
        }
        if ($relativePath -eq 'gradle/wrapper/gradle-wrapper.jar') { continue }
        # Scan the staged blob, not just the working-tree file being committed.
        $stagedText = ((& git show ":$relativePath") -join "`n")
        if ($LASTEXITCODE -ne 0) { throw "Cannot read staged file: $relativePath" }
        foreach ($pattern in $secretPatterns) {
            if ($stagedText -match $pattern) { $failures.Add("Secret or GPS snapshot pattern in $relativePath (contents hidden)"); break }
        }
    }
    $mustBeIgnored = @('.local/device/photo.jpg', '.local/device/probe.json', '.tools/android-sdk/tool.exe', '.private/release-signing.properties', '.private/release.jks', '.env', 'adbkey', 'last-probe.json', 'last-probe.json.bak', 'last-probe.json.new', 'last-successful-probe.json', 'last-successful-probe.json.bak', 'last-successful-probe.json.new', 'phone-app/build/outputs/apk/debug/phone-app-debug.apk', 'captures/private.jpg', 'fixture.p12', 'fixture.pfx')
    foreach ($probePath in $mustBeIgnored) {
        & git check-ignore -q -- $probePath
        if ($LASTEXITCODE -ne 0) { $failures.Add("Missing ignore rule: $probePath") }
    }
    & git check-ignore -q -- gradle/wrapper/gradle-wrapper.jar
    if ($LASTEXITCODE -eq 0) { $failures.Add('Gradle wrapper JAR must remain eligible for tracking.') }
    if ($failures.Count -gt 0) { $failures | ForEach-Object { Write-Output $_ }; throw 'Publish safety check failed. Do not commit or push.' }
    $scope = if ($AllTracked) { 'tracked' } else { 'staged' }
    Write-Output "Publish safety passed: $(@($checkedPaths).Count) $scope source files; private paths and recognizable secret/GPS patterns excluded. Review the diff before pushing."
} finally { Pop-Location }
