param()
. (Join-Path $PSScriptRoot 'Use-ProjectEnvironment.ps1')
$privateSigningDirectory = Join-Path $projectRoot '.private'
$signingPropertiesPath = Join-Path $privateSigningDirectory 'release-signing.properties'
$signingKeyPath = Join-Path $privateSigningDirectory 'fieldlog-release.p12'
$certificatePinPath = Join-Path $projectRoot 'release-certificate.sha256'
$hasProperties = Test-Path -LiteralPath $signingPropertiesPath
$hasKey = Test-Path -LiteralPath $signingKeyPath
if ($hasProperties -ne $hasKey) { throw 'Incomplete signing files; restore the existing pair instead of replacing the key.' }
if (!$hasKey -and (Test-Path -LiteralPath $certificatePinPath)) { throw 'This project already has a release certificate. Restore its .private signing files; a new key cannot update existing installations.' }
if (!$hasKey) {
    New-Item -ItemType Directory -Path $privateSigningDirectory -Force | Out-Null
    $randomBytes = New-Object byte[] 32
    $randomGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $randomGenerator.GetBytes($randomBytes) } finally { $randomGenerator.Dispose() }
    $signingPassword = [Convert]::ToBase64String($randomBytes)
    $env:FIELDLOG_INIT_STORE_PASSWORD = $signingPassword
    try {
        $keyRun = Invoke-ProjectNativeCapture -Executable "$env:JAVA_HOME\bin\keytool.exe" -NativeArguments @('-genkeypair','-keystore',$signingKeyPath,'-storetype','PKCS12','-storepass:env','FIELDLOG_INIT_STORE_PASSWORD','-keypass:env','FIELDLOG_INIT_STORE_PASSWORD','-alias','fieldlog-release','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=FieldLog Release, O=FieldLog')
        if ($keyRun.ExitCode -ne 0) { throw 'Release key generation failed; inspect .private before retrying.' }
        $signingProperties = "storeFile=.private/fieldlog-release.p12
storePassword=$signingPassword
keyAlias=fieldlog-release
keyPassword=$signingPassword
"
        [System.IO.File]::WriteAllText($signingPropertiesPath, $signingProperties, [System.Text.UTF8Encoding]::new($false))
    } finally { Remove-Item Env:FIELDLOG_INIT_STORE_PASSWORD -ErrorAction SilentlyContinue }
}
$storedSigningLines = Get-Content -LiteralPath $signingPropertiesPath
$storedPasswordLine = $storedSigningLines | Where-Object { $_.StartsWith('storePassword=') } | Select-Object -First 1
if (!$storedPasswordLine) { throw 'Missing signing password; restore the signing configuration.' }
$env:FIELDLOG_INIT_STORE_PASSWORD = $storedPasswordLine.Substring('storePassword='.Length)
$publicCertificatePath = Join-Path $localRoot 'release-public-certificate.der'
try {
    $certRun = Invoke-ProjectNativeCapture -Executable "$env:JAVA_HOME\bin\keytool.exe" -NativeArguments @('-exportcert','-keystore',$signingKeyPath,'-storepass:env','FIELDLOG_INIT_STORE_PASSWORD','-alias','fieldlog-release','-file',$publicCertificatePath)
    if ($certRun.ExitCode -ne 0) { throw 'Cannot verify the retained release certificate.' }
    $certificateHash = (Get-FileHash -LiteralPath $publicCertificatePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if (Test-Path -LiteralPath $certificatePinPath) {
        if ((Get-Content -LiteralPath $certificatePinPath -Raw).Trim() -ne $certificateHash) { throw 'Release certificate mismatch; restore the original signing key.' }
    } else { [System.IO.File]::WriteAllText($certificatePinPath, $certificateHash + "
", [System.Text.UTF8Encoding]::new($false)) }
    Write-Output "Persistent release certificate: $certificateHash. Back up BOTH .private signing files securely."
} finally { Remove-Item Env:FIELDLOG_INIT_STORE_PASSWORD -ErrorAction SilentlyContinue }
