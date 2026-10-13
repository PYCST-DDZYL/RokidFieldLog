. (Join-Path $PSScriptRoot 'Use-ProjectEnvironment.ps1')
Add-Type -Path (Join-Path $PSScriptRoot 'Read-SamsungUsbDescriptors.cs')
[FieldLogUsbReader]::Read()
