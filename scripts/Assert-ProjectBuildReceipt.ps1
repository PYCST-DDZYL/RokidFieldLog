function Assert-ProjectBuildReceipt {
    param([Parameter(Mandatory=$true)][object]$Receipt,
        [Parameter(Mandatory=$true)][ValidateSet('debug','release')][string]$Variant)
    $failure = 'Full verification required. Run scripts/Build.ps1 -Verify (and -Release for release) before installing.'
    if (!(($Receipt.schemaVersion -is [int] -or $Receipt.schemaVersion -is [long]) -and $Receipt.schemaVersion -eq 2) -or
        $Receipt.status -isnot [string] -or $Receipt.status -cne 'VERIFIED' -or
        $Receipt.variant -isnot [string] -or $Receipt.variant -cne $Variant -or
        $Receipt.inputFingerprint -isnot [string] -or $Receipt.inputFingerprint -cnotmatch '^[0-9a-f]{64}$') { throw $failure }
    $required = @('APK_CONTENT','BUILD_INPUTS','CORE_TESTS','ANDROID_LINT')
    if ($Variant -eq 'release') { $required += 'RELEASE_SIGNATURE' }
    foreach ($check in $required) { if ($Receipt.checks -cnotcontains $check) { throw $failure } }
    foreach ($target in @('glasses','phone')) {
        $value = $Receipt.apkHashes.$target
        if ($value -isnot [string] -or $value -cnotmatch '^[0-9a-f]{64}$') { throw $failure }
    }
}
