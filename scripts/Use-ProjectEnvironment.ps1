$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Invoke-ProjectNativeCapture.ps1')
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localRoot = Join-Path $projectRoot '.local'
$env:ANDROID_HOME = Join-Path $projectRoot '.tools\android-sdk'
$env:ANDROID_USER_HOME = Join-Path $localRoot 'android-user-home'
$env:GRADLE_USER_HOME = Join-Path $localRoot 'gradle-user-home'
$env:JAVA_HOME = Join-Path $projectRoot '.tools\jdk-21'
$env:TEMP = Join-Path $localRoot 'tmp'
$env:TMP = $env:TEMP
$env:STUDIO_PROPERTIES = Join-Path $localRoot 'studio\fieldlog-studio.properties'
$env:STUDIO_JDK = Join-Path $projectRoot '.tools\android-studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

foreach ($relativeDirectory in @('tmp', 'downloads', 'gradle-user-home', 'android-user-home', 'studio\config', 'studio\system', 'studio\plugins', 'studio\log')) {
    New-Item -ItemType Directory -Path (Join-Path $localRoot $relativeDirectory) -Force | Out-Null
}

function ConvertTo-JavaPropertyPath([string]$Value) {
    $normalizedValue = $Value.Replace('\','/').Replace(':','\:')
    return [regex]::Replace($normalizedValue, '[^\x00-\x7F]', {
        param($match)
        '\u{0:x4}' -f [int][char]$match.Value
    })
}
$studioProperties = @(
    "idea.config.path=$(ConvertTo-JavaPropertyPath (Join-Path $localRoot 'studio\config'))",
    "idea.system.path=$(ConvertTo-JavaPropertyPath (Join-Path $localRoot 'studio\system'))",
    "idea.plugins.path=$(ConvertTo-JavaPropertyPath (Join-Path $localRoot 'studio\plugins'))",
    "idea.log.path=$(ConvertTo-JavaPropertyPath (Join-Path $localRoot 'studio\log'))",
    "java.io.tmpdir=$(ConvertTo-JavaPropertyPath $env:TEMP)"
)
[System.IO.File]::WriteAllLines($env:STUDIO_PROPERTIES, $studioProperties, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), "sdk.dir=.tools/android-sdk`n", [System.Text.UTF8Encoding]::new($false))

# Java reads @argument files using the Windows native encoding even on JDK 21.
# Gradle writes those files using its daemon's default encoding. Match them in
# a project-private user property, while leaving all Kotlin/XML/JSON in UTF-8.
if (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe") {
    $javaRun = Invoke-ProjectNativeCapture -Executable "$env:JAVA_HOME\bin\java.exe" -NativeArguments @('-XshowSettings:properties', '-version')
    if ($javaRun.ExitCode -ne 0) { throw 'Project Java settings query failed.' }
    $javaSettings = $javaRun.Lines
    $nativeEncodingMatch = [regex]::Match(($javaSettings -join "`n"), 'native\.encoding\s*=\s*([A-Za-z0-9_-]+)')
    if ($nativeEncodingMatch.Success) {
        $nativeEncoding = $nativeEncodingMatch.Groups[1].Value
        $gradleUserProperties = "org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=$nativeEncoding`n"
        [System.IO.File]::WriteAllText((Join-Path $env:GRADLE_USER_HOME 'gradle.properties'), $gradleUserProperties, [System.Text.UTF8Encoding]::new($false))
    }
}
