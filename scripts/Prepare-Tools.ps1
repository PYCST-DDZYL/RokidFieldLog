param([switch]$AcceptSdkLicense)
$ErrorActionPreference = 'Stop'

if (!$AcceptSdkLicense) {
    throw '请先阅读 https://developer.android.com/studio#downloads 的 Android SDK 许可。明确同意后才使用 -AcceptSdkLicense。'
}
. (Join-Path $PSScriptRoot 'Use-ProjectEnvironment.ps1')

function Get-VerifiedArchive {
    param([string]$Url, [string]$Filename, [string]$Sha256)
    $archivePath = Join-Path $localRoot "downloads\$Filename"
    if (!(Test-Path -LiteralPath $archivePath)) {
        Write-Host "Downloading $Filename into project storage"
        & curl.exe --silent --show-error --location --fail --retry 2 --connect-timeout 30 --output $archivePath --url $Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $Filename" }
    }
    $actualHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $Sha256) { throw "SHA-256 mismatch for $Filename. Stop; do not extract." }
    Write-Host "Verified SHA-256: $Filename"
    return $archivePath
}

$toolsRoot = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Path $toolsRoot -Force | Out-Null
if (!(Test-Path -LiteralPath "$env:STUDIO_JDK\bin\java.exe")) {
    $studioArchive = Get-VerifiedArchive -Url 'https://edgedl.me.gvt1.com/android/studio/ide-zips/2026.2.1.8/android-studio-rabbit1-windows.zip' -Filename 'android-studio-rabbit1-windows.zip' -Sha256 '6e83e7ce3e0a76c4e8e78d98eb5ef538cbc9fb386c745bda425fc0bacb9de6d4'
    & tar.exe -xf $studioArchive -C $toolsRoot
    if ($LASTEXITCODE -ne 0) { throw 'Android Studio extraction failed' }
}

if (!(Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) {
    $jdkArchive = Get-VerifiedArchive -Url 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip' -Filename 'OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip' -Sha256 'f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e'
    New-Item -ItemType Directory -Path $env:JAVA_HOME -Force | Out-Null
    & tar.exe -xf $jdkArchive -C $env:JAVA_HOME --strip-components 1
    if ($LASTEXITCODE -ne 0) { throw 'JDK extraction failed' }
}

$sdkManager = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\sdkmanager.bat'
if (!(Test-Path -LiteralPath $sdkManager)) {
    $cliArchive = Get-VerifiedArchive -Url 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' -Filename 'commandlinetools-win-15859902_latest.zip' -Sha256 '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
    $unpackDirectory = Join-Path $localRoot 'cli-unpack'
    New-Item -ItemType Directory -Path $unpackDirectory -Force | Out-Null
    & tar.exe -xf $cliArchive -C $unpackDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Command-line tools extraction failed' }
    $cliParent = Join-Path $env:ANDROID_HOME 'cmdline-tools'
    New-Item -ItemType Directory -Path $cliParent -Force | Out-Null
    $sourcePath = [System.IO.Path]::GetFullPath((Join-Path $unpackDirectory 'cmdline-tools'))
    $destinationPath = [System.IO.Path]::GetFullPath((Join-Path $cliParent 'latest'))
    foreach ($checkedPath in @($sourcePath,$destinationPath)) {
        if (!$checkedPath.StartsWith($projectRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Path outside project' }
    }
    Move-Item -LiteralPath $sourcePath -Destination $destinationPath
}

# Accept only the requested packages' license; do not run global --licenses.
$buildJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $env:STUDIO_JDK
    $sdkRun = Invoke-ProjectNativeCapture -Executable $sdkManager -NativeArguments @("--sdk_root=$env:ANDROID_HOME", 'platform-tools', 'platforms;android-36', 'build-tools;35.0.0') -InputLines @('y','y','y')
    [System.IO.File]::WriteAllLines((Join-Path $localRoot 'sdk-install.log'), $sdkRun.Lines, [System.Text.UTF8Encoding]::new($false))
    if ($sdkRun.ExitCode -ne 0) { throw 'SDK package installation failed; inspect .local/sdk-install.log' }
} finally { $env:JAVA_HOME = $buildJava }

[System.IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), "sdk.dir=.tools/android-sdk`n", [System.Text.UTF8Encoding]::new($false))
Write-Output 'Project-local development tools are ready. No device software was installed.'
