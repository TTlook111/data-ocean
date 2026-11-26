function Invoke-StorageDocker {
    param([string[]]$Arguments)
    $lines = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Docker fault operation failed: $($Arguments[0])" }
    $lines | Write-Host
}

function Wait-StorageHealthy {
    param([string[]]$Names)
    $deadline = (Get-Date).AddSeconds(100)
    do {
        $waiting = foreach ($name in $Names) {
            $info = docker inspect $name | ConvertFrom-Json
            if ($LASTEXITCODE -ne 0 -or $info.State.Health.Status -ne 'healthy') { $name }
        }
        if (@($waiting).Count -eq 0) { return }
        if ((Get-Date) -gt $deadline) { throw 'Storage recovery readiness deadline exceeded.' }
        Start-Sleep -Milliseconds 500
    } while ($true)
}

function Invoke-StorageFault {
    param($Request, [string]$BackupDirectory)
    if ($Request.run_id -notmatch '^tech_[0-9a-f]{12}$') { throw 'Invalid test identity.' }
    foreach ($name in @('redis','redis-checkpoint','milvus')) {
        $info = docker inspect $name | ConvertFrom-Json
        $expectedProject = if ($name -eq 'milvus') { 'milvus' } else { 'redis' }
        if ($LASTEXITCODE -ne 0 -or $info.Config.Labels.'com.docker.compose.project' -ne $expectedProject) { throw "Unexpected fault target: $name" }
        $expectedImage = if ($name -eq 'milvus') { 'milvusdb/milvus:v2.6.26' } else { 'redis:8.10.3-alpine' }
        if ($info.Config.Image -ne $expectedImage) { throw "Unexpected image: $name" }
    }
    $backupRoot = [System.IO.Path]::GetFullPath($BackupDirectory)
    $runBackup = Join-Path $backupRoot $Request.run_id
    $restoreName = 'do-restore-' + $Request.run_id
    $restoreVolume = 'do-restore-' + $Request.run_id
    $details = @{id=$Request.id; action=$Request.action; ok=$true; exit_codes=@()}
    switch ($Request.action) {
        'backup-redis' {
            New-Item -ItemType Directory -Path $runBackup -Force | Out-Null
            Invoke-StorageDocker @('stop','--time','30','redis','redis-checkpoint')
            try {
                foreach ($name in @('redis','redis-checkpoint')) {
                    $info = docker inspect $name | ConvertFrom-Json
                    $volume = @($info.Mounts | Where-Object Destination -eq '/data')[0].Name
                    $expectedVolume = if ($name -eq 'redis') { 'redis_redis-data' } else { 'redis_checkpoint-data' }
                    if ($volume -ne $expectedVolume) { throw 'Unexpected backup source volume.' }
                    Invoke-StorageDocker @('run','--rm','--network','none','--entrypoint','tar',
                        '--mount',"type=volume,src=$volume,dst=/source,readonly",'--mount',"type=bind,src=$runBackup,dst=/backup",
                        'redis:8.10.3-alpine','-czf',"/backup/$name.tar.gz",'-C','/source','.')
                }
                Get-ChildItem -LiteralPath $runBackup -Filter '*.tar.gz' | Get-FileHash -Algorithm SHA256 |
                    Select-Object @{Name='file';Expression={Split-Path $_.Path -Leaf}},Hash |
                    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runBackup 'manifest.json') -Encoding utf8NoBOM
                $details.backup_files = 2
            } finally {
                Invoke-StorageDocker @('start','redis','redis-checkpoint')
                Wait-StorageHealthy @('redis','redis-checkpoint')
            }
        }
        'redis-stop' {
            Invoke-StorageDocker @('update','--restart=no','redis','redis-checkpoint')
            Invoke-StorageDocker @('kill','--signal','KILL','redis','redis-checkpoint')
            $details.exit_codes = @(foreach ($name in @('redis','redis-checkpoint')) { [int](docker inspect --format '{{.State.ExitCode}}' $name) })
        }
        'redis-start' {
            Invoke-StorageDocker @('start','redis','redis-checkpoint')
            Wait-StorageHealthy @('redis','redis-checkpoint')
            Invoke-StorageDocker @('update','--restart=unless-stopped','redis','redis-checkpoint')
        }
        'milvus-restart' {
            $before = docker inspect --format '{{.State.StartedAt}}' milvus
            Invoke-StorageDocker @('update','--restart=no','milvus')
            Invoke-StorageDocker @('kill','--signal','KILL','milvus')
            $details.exit_codes = @([int](docker inspect --format '{{.State.ExitCode}}' milvus))
            Invoke-StorageDocker @('start','milvus')
            Wait-StorageHealthy @('milvus')
            Invoke-StorageDocker @('update','--restart=unless-stopped','milvus')
            $details.process_restarted = $before -ne (docker inspect --format '{{.State.StartedAt}}' milvus)
        }
        'restore-checkpoint' {
            if (Get-NetTCPConnection -State Listen -LocalPort 16380 -ErrorAction SilentlyContinue) { throw 'Restore port already occupied.' }
            $archive = Join-Path $runBackup 'redis-checkpoint.tar.gz'
            $manifest = Get-Content -LiteralPath (Join-Path $runBackup 'manifest.json') -Raw | ConvertFrom-Json
            $expectedHash = @($manifest | Where-Object file -eq 'redis-checkpoint.tar.gz')[0].Hash
            if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expectedHash) { throw 'Backup digest mismatch.' }
            $existingVolume = docker volume ls --format '{{.Name}}' | Where-Object { $_ -eq $restoreVolume }
            if ($existingVolume) { throw 'Restore volume already exists.' }
            Invoke-StorageDocker @('volume','create','--label',"dataocean.validation.run=$($Request.run_id)",$restoreVolume)
            Invoke-StorageDocker @('run','--rm','--network','none','--entrypoint','tar',
                '--mount',"type=volume,src=$restoreVolume,dst=/target",'--mount',"type=bind,src=$runBackup,dst=/backup,readonly",
                'redis:8.10.3-alpine','-xzf','/backup/redis-checkpoint.tar.gz','-C','/target')
            Invoke-StorageDocker @('run','--detach','--name',$restoreName,'--label',"dataocean.validation.run=$($Request.run_id)",
                '--publish','127.0.0.1:16380:6379','--mount',"type=volume,src=$restoreVolume,dst=/data",'--health-cmd','redis-cli ping',
                '--health-interval','1s','--health-retries','20','redis:8.10.3-alpine','redis-server',
                '--appendonly','yes','--appendfsync','always','--maxmemory-policy','noeviction','--aof-load-truncated','no')
            Wait-StorageHealthy @($restoreName)
        }
        'remove-restore' {
            $info = docker inspect $restoreName 2>$null | ConvertFrom-Json
            if ($LASTEXITCODE -eq 0) {
                if ($info.Config.Labels.'dataocean.validation.run' -ne $Request.run_id) { throw 'Unexpected restore container owner.' }
                Invoke-StorageDocker @('stop','--time','15',$restoreName)
                Invoke-StorageDocker @('rm',$restoreName)
            }
            $volumeInfo = docker volume inspect $restoreVolume 2>$null | ConvertFrom-Json
            if ($LASTEXITCODE -eq 0) {
                if ($volumeInfo.Labels.'dataocean.validation.run' -ne $Request.run_id) { throw 'Unexpected restore volume owner.' }
                Invoke-StorageDocker @('volume','rm',$restoreVolume)
            }
        }
        default { throw 'Unsupported storage fault action.' }
    }
    return $details
}
