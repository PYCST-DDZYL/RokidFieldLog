param([switch]$ConfirmDeviceTest)
if (!$ConfirmDeviceTest) { throw '需要已获得本项目真机测试授权，再使用 -ConfirmDeviceTest。' }
. (Join-Path $PSScriptRoot 'Use-ProjectEnvironment.ps1')
$adbExecutable = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$rows = & $adbExecutable -P 5047 devices -l
$glasses = @()
foreach ($row in $rows) {
    if ($row -notmatch '^([^\s]+)\s+device\b') { continue }
    $identifier = $Matches[1]
    $brand = ((& $adbExecutable -P 5047 -s $identifier shell getprop ro.product.brand) -join '').Trim()
    $model = ((& $adbExecutable -P 5047 -s $identifier shell getprop ro.product.model) -join '').Trim()
    if ($brand -eq 'Rokid' -and $model -eq 'RG-glasses') { $glasses += $identifier }
}
if ($glasses.Count -ne 1) { throw 'Expected one verified Rokid glasses device; no tests run.' }
$output = & $adbExecutable -P 5047 -s $glasses[0] shell am instrument -w org.rokidfieldlog.glasses/.RecoveryInstrumentation 2>&1
$testText = $output -join "`n"
$evidenceFolder = Join-Path $localRoot 'device'
New-Item -ItemType Directory -Path $evidenceFolder -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $evidenceFolder 'storage-recovery-tests.txt'), $testText, [System.Text.UTF8Encoding]::new($false))
if ($LASTEXITCODE -ne 0 -or $testText -notmatch 'fieldlog_test_status=PASS' -or $testText -notmatch 'storage_fixture_tests=9') {
    throw 'Isolated Android storage tests did not pass; inspect ignored .local/device/storage-recovery-tests.txt. No camera or GPS success inferred.'
}
Write-Output 'Android isolated storage tests: 9 passed. Synthetic fixtures are inside this App private fieldlog/test-fixtures directory.'
Write-Output 'This validates recovery code, not camera capture or GPS. Production photos are not changed or deleted.'
