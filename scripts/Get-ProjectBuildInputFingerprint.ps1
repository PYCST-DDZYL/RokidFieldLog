function Get-ProjectBuildInputFingerprint {
    param([Parameter(Mandatory=$true)][string]$Root)
    $rootPath = [IO.Path]::GetFullPath($Root).TrimEnd('\','/')
    $files = @()
    foreach ($module in @('glasses-app','phone-app','fieldlog-core')) {
        $source = Join-Path $rootPath ($module + '/src')
        if (Test-Path -LiteralPath $source) { $files += Get-ChildItem -LiteralPath $source -Recurse -File }
        $buildFile = Join-Path $rootPath ($module + '/build.gradle.kts')
        if (Test-Path -LiteralPath $buildFile) { $files += Get-Item -LiteralPath $buildFile }
    }
    foreach ($directory in @('gradle','scripts')) {
        $path = Join-Path $rootPath $directory
        if (Test-Path -LiteralPath $path) { $files += Get-ChildItem -LiteralPath $path -Recurse -File }
    }
    foreach ($name in @('build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat','release-certificate.sha256')) {
        $path = Join-Path $rootPath $name
        if (Test-Path -LiteralPath $path) { $files += Get-Item -LiteralPath $path }
    }
    $rows = foreach ($file in $files | Sort-Object -Property FullName -Unique) {
        $relative = $file.FullName.Substring($rootPath.Length + 1).Replace('\','/')
        $relative + ':' + (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes(($rows -join "`n"))))).Replace('-','').ToLowerInvariant() }
    finally { $hash.Dispose() }
}

function Assert-ProjectBuildInputsUnchanged {
    param([Parameter(Mandatory=$true)][string]$Root, [Parameter(Mandatory=$true)][string]$Expected)
    if ((Get-ProjectBuildInputFingerprint -Root $Root) -ne $Expected) {
        throw 'Build inputs changed during verification. Do not install these APKs; finish editing and rebuild.'
    }
}
