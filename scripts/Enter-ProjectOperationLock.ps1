function Enter-ProjectOperationLock {
    param([Parameter(Mandatory=$true)][string]$Root)
    $directory = Join-Path ([IO.Path]::GetFullPath($Root)) '.local'
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    try {
        # Keep the handle open throughout verification/installation. Process exit
        # releases the lock; its empty file stays inside the ignored project cache.
        return [IO.File]::Open((Join-Path $directory 'project-operation.lock'),
            [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    } catch [IO.IOException] {
        throw 'Cannot acquire the project lock. Another FieldLog build or installation may be running; finish it before retrying.'
    }
}
