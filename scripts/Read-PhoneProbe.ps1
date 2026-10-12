. (Join-Path $PSScriptRoot 'Use-ProjectEnvironment.ps1')
$adbExecutable = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$rows = & $adbExecutable -P 5047 devices -l
if ($LASTEXITCODE -ne 0) { throw 'ADB discovery failed.' }
$phoneCandidates = @()
foreach ($row in $rows) {
    if ($row -notmatch '^([^\s]+)\s+device\b') { continue }
    $identifier = $Matches[1]
    $brand = ((& $adbExecutable -P 5047 -s $identifier shell getprop ro.product.brand) -join '').Trim()
    $emulator = ((& $adbExecutable -P 5047 -s $identifier shell getprop ro.kernel.qemu) -join '').Trim()
    if ($brand -ne 'Rokid' -and $emulator -ne '1') { $phoneCandidates += $identifier }
}
if ($phoneCandidates.Count -ne 1) { throw 'Expected exactly one physical phone; no private data read.' }
# Read only this project's diagnostic JSON, never shared storage or other packages.
$rawResult = & $adbExecutable -P 5047 -s $phoneCandidates[0] exec-out run-as org.rokidfieldlog.phone cat files/fieldlog/last-probe.json 2>&1
if ($LASTEXITCODE -ne 0) { throw 'No readable Companion result yet. Run a real location test first.' }
$rawJson = $rawResult -join "`n"
$probe = $rawJson | ConvertFrom-Json
if ($probe.schemaVersion -ne 1 -or $probe.probeId -notmatch '^[0-9a-f-]{36}$') { throw 'Unsupported diagnostic record.' }
$privateFolder = Join-Path $localRoot 'device'
New-Item -ItemType Directory -Path $privateFolder -Force | Out-Null
$savedResult = Join-Path $privateFolder "phone-probe-$($probe.probeId).json"
[System.IO.File]::WriteAllText($savedResult, $rawJson, [System.Text.UTF8Encoding]::new($false))
Write-Output "Location test status: $($probe.status); source: $($probe.source); permission: $($probe.permissionLevel)."
if ($probe.status -eq 'SUCCESS') {
    if ($null -eq $probe.location -or $probe.location.isMock -ne $false) { throw 'Success record lacks a non-mock observation.' }
    if ($null -eq $probe.location.latitude -or $null -eq $probe.location.longitude) { throw 'Success record has missing coordinates.' }
    $latitude = [double]$probe.location.latitude
    $longitude = [double]$probe.location.longitude
    if ([double]::IsNaN($latitude) -or [double]::IsInfinity($latitude) -or [double]::IsNaN($longitude) -or [double]::IsInfinity($longitude) -or $latitude -lt -90 -or $latitude -gt 90 -or $longitude -lt -180 -or $longitude -gt 180) { throw 'Invalid recorded coordinates.' }
    $age = [long]$probe.location.ageAtReceiptMillis
    if ($age -lt 0 -or $age -gt 5000) { throw 'Recorded observation is stale or from a future monotonic time.' }
    $accuracy = if ($null -eq $probe.location.accuracyMeters) { 'unknown' } else { $probe.location.accuracyMeters }
    Write-Output "Provider: $($probe.location.provider); accuracy estimate meters: $accuracy; age at receipt milliseconds: $age."
    Write-Output "Coordinate values hidden. Device location timestamp present: $([long]$probe.location.locationTimeUtcMillis -gt 0)."
} else {
    if ($null -ne $probe.location) { throw 'Failure result must not contain fabricated coordinates.' }
    Write-Output "Reason: $($probe.reason); location is null."
}
Write-Output 'Private evidence saved only in ignored .local/device; this is the last test result, not a new fix.'
