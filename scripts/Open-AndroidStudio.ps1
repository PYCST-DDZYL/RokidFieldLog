. (Join-Path $PSScriptRoot 'Use-ProjectEnvironment.ps1')
$studioExecutable = Join-Path $projectRoot '.tools\android-studio\bin\studio64.exe'
if (!(Test-Path -LiteralPath $studioExecutable)) { throw 'Android Studio has not been prepared.' }
# This script is explicitly for opening the editor window for the user.
& $studioExecutable $projectRoot
