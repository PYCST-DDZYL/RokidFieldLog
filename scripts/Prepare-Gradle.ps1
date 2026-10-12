. (Join-Path $PSScriptRoot 'Use-ProjectEnvironment.ps1')
$gradleHash = '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
$gradleArchive = Join-Path $localRoot 'downloads\gradle-8.13-bin.zip'
if (!(Test-Path -LiteralPath $gradleArchive)) {
    & curl.exe --silent --show-error --location --fail --retry 2 --output $gradleArchive --url 'https://services.gradle.org/distributions/gradle-8.13-bin.zip'
    if ($LASTEXITCODE -ne 0) { throw 'Gradle download failed' }
}
if ((Get-FileHash -LiteralPath $gradleArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $gradleHash) { throw 'Gradle SHA-256 mismatch' }
$gradleExecutable = Join-Path $projectRoot '.tools\gradle-8.13\bin\gradle.bat'
if (!(Test-Path -LiteralPath $gradleExecutable)) {
    & tar.exe -xf $gradleArchive -C (Join-Path $projectRoot '.tools')
    if ($LASTEXITCODE -ne 0) { throw 'Gradle extraction failed' }
}
$bootstrapRoot = Join-Path $localRoot 'wrapper-bootstrap'
New-Item -ItemType Directory -Path $bootstrapRoot -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $bootstrapRoot 'settings.gradle.kts'), 'rootProject.name = "fieldlog-wrapper-bootstrap"')
& $gradleExecutable --no-daemon -p $bootstrapRoot wrapper --gradle-version 8.13 --distribution-type bin --gradle-distribution-sha256-sum $gradleHash
if ($LASTEXITCODE -ne 0) { throw 'Official Gradle wrapper generation failed' }
foreach ($wrapperFile in @('gradlew','gradlew.bat')) { Copy-Item -LiteralPath (Join-Path $bootstrapRoot $wrapperFile) -Destination (Join-Path $projectRoot $wrapperFile) }
$wrapperDestination = Join-Path $projectRoot 'gradle\wrapper'
New-Item -ItemType Directory -Path $wrapperDestination -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $bootstrapRoot 'gradle\wrapper\gradle-wrapper.jar') -Destination $wrapperDestination
Copy-Item -LiteralPath (Join-Path $bootstrapRoot 'gradle\wrapper\gradle-wrapper.properties') -Destination $wrapperDestination
foreach ($licenseFile in @('LICENSE', 'NOTICE')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ".tools\gradle-8.13\$licenseFile") -Destination $wrapperDestination
}
Write-Output 'Official Gradle 8.13 wrapper created with distribution checksum.'
