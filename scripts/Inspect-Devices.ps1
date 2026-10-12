. (Join-Path $PSScriptRoot 'Use-ProjectEnvironment.ps1')
$adbExecutable = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (!(Test-Path -LiteralPath $adbExecutable)) { throw 'ADB is missing' }
$env:ANDROID_ADB_LOG_PATH = Join-Path $localRoot 'adb-server.log'
# Port 5047 keeps this session separate from another Android Studio's server.
$deviceRows = & $adbExecutable -P 5047 devices -l
if ($LASTEXITCODE -ne 0) { throw 'ADB device discovery failed' }
$deviceNumber = 0
foreach ($row in $deviceRows) {
    if ($row -notmatch '^([^\s]+)\s+(device|unauthorized|offline)\b') { continue }
    $deviceId = $Matches[1]
    $state = $Matches[2]
    $deviceNumber++
    Write-Output "Device $deviceNumber : $state (identifier hidden)"
    if ($state -eq 'device') {
        foreach ($property in @('ro.product.brand','ro.product.model','ro.build.version.release','ro.build.version.sdk')) {
            $value = (& $adbExecutable -P 5047 -s $deviceId shell getprop $property) -join ''
            Write-Output "  $property=$value"
        }
        $packages = & $adbExecutable -P 5047 -s $deviceId shell pm list packages com.rokid
        Write-Output '  Official Rokid packages:'
        $packages | Where-Object { $_ -match '^package:com\.rokid\.(sprite\.(global\.)?aiapp|uxr)$' } | ForEach-Object { Write-Output "  $_" }
    }
}
if ($deviceNumber -eq 0) { Write-Output 'No ADB devices detected. Check USB debugging and the phone authorization dialog.' }
