param(
    [Parameter(Mandatory=$true)][string]$RuntimeDirectory,
    [string]$ReportDirectory,
    [switch]$StorageFaults
)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runtimeAbsolute = [System.IO.Path]::GetFullPath($RuntimeDirectory)
if ([string]::IsNullOrWhiteSpace($ReportDirectory)) {
    $ReportDirectory = Join-Path $projectRoot ('output/acceptance/technical-chain-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
$reportDirectory = [System.IO.Path]::GetFullPath($ReportDirectory)
$fixturePath = Join-Path $reportDirectory 'fixture.private.json'
$logDirectory = Join-Path $runtimeAbsolute 'logs'
$buildDirectory = Join-Path $runtimeAbsolute 'java-target'
New-Item -ItemType Directory -Path $reportDirectory,$logDirectory -Force | Out-Null
Push-Location $projectRoot
try {
    # 不启动、升级或重建基础设施，只验证事先由用户启动的既有服务。
    foreach ($name in @('mysql','redis','redis-checkpoint','etcd','minio','milvus')) {
        $info = docker inspect $name | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0 -or -not $info.State.Running) { throw "Start the existing container first: $name" }
    }
    $javaPath = (Get-Command java.exe).Source
    $javaVersion = & $javaPath -version 2>&1 | Out-String
    if ($javaVersion -notmatch 'version "17\.') { throw 'JDK 17 is required.' }
    uv sync --locked --project (Join-Path $PSScriptRoot 'python') --python 3.14
    if ($LASTEXITCODE -ne 0) { throw 'Python dependency installation failed.' }
    $pythonPath = Join-Path $PSScriptRoot 'python/.venv/Scripts/python.exe'
    mvn -B -ntp "-Dmaven.repo.local=$projectRoot/.m2/repository" "-Dprobe.build.directory=$buildDirectory" -f (Join-Path $PSScriptRoot 'java/pom.xml') package *> (Join-Path $logDirectory 'maven-build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Java build failed. See runtime logs.' }
    & (Join-Path $PSScriptRoot 'prepare-fixture.ps1') -FixturePath $fixturePath
    $verificationExitCode = 1
    try {
        # 子进程使用本轮工作目录作为临时目录；日志结束后复制至指定运行目录。
        if ($StorageFaults) {
            . (Join-Path $PSScriptRoot 'storage-fault.ps1')
            $activeRunId = (Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json).run_id
            $arguments = @((Join-Path $PSScriptRoot 'python/run_validation.py'),'--runtime',(Join-Path $reportDirectory 'runtime'),
                '--report',$reportDirectory,'--java',$javaPath,'--jar',(Join-Path $buildDirectory 'technical-chain-0.0.1.jar'),
                '--fixture',$fixturePath,'--storage-faults') | ForEach-Object { '"' + $_ + '"' }
            $testProcess = Start-Process -FilePath $pythonPath -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDirectory 'chain.log') -RedirectStandardError (Join-Path $logDirectory 'chain.err.log')
            $handledOperation = ''
            $deadline = (Get-Date).AddMinutes(15)
            while (-not $testProcess.HasExited) {
                if ((Get-Date) -gt $deadline) { throw 'Storage verification deadline exceeded.' }
                $requestPath = Join-Path $reportDirectory 'storage-fault.request.json'
                if (Test-Path -LiteralPath $requestPath) {
                    $request = Get-Content -LiteralPath $requestPath -Raw | ConvertFrom-Json
                    if ($request.id -ne $handledOperation -and $request.run_id -eq $activeRunId) {
                        $handledOperation = $request.id
                        Write-Output "Storage verification step: $($request.action)"
                        try { $response = Invoke-StorageFault -Request $request -BackupDirectory (Join-Path $runtimeAbsolute 'backups') }
                        catch { $response = @{id=$request.id; action=$request.action; ok=$false}; Write-Output "Storage verification step failed: $($request.action): $($_.Exception.Message)" }
                        $response | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $reportDirectory 'storage-fault.response.tmp') -Encoding utf8NoBOM
                        Move-Item -LiteralPath (Join-Path $reportDirectory 'storage-fault.response.tmp') -Destination (Join-Path $reportDirectory 'storage-fault.response.json') -Force
                    }
                }
                Start-Sleep -Milliseconds 200
            }
            $testProcess.WaitForExit()
            $verificationExitCode = $testProcess.ExitCode
        } else {
            & $pythonPath (Join-Path $PSScriptRoot 'python/run_validation.py') --runtime (Join-Path $reportDirectory 'runtime') --report $reportDirectory --java $javaPath --jar (Join-Path $buildDirectory 'technical-chain-0.0.1.jar') --fixture $fixturePath *> (Join-Path $logDirectory 'chain.log')
            $verificationExitCode = $LASTEXITCODE
        }
    } finally {
        if ($StorageFaults) {
            if ($null -ne $testProcess -and -not $testProcess.HasExited) {
                taskkill /PID $testProcess.Id /T /F | Out-Null
                $testProcess.WaitForExit()
            }
            foreach ($name in @('redis','redis-checkpoint','milvus')) {
                $info = docker inspect $name | ConvertFrom-Json
                if (-not $info.State.Running) { docker start $name | Out-Null }
                docker update --restart=unless-stopped $name | Out-Null
            }
            $fixture = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json
            Invoke-StorageFault -Request ([pscustomobject]@{run_id=$fixture.run_id; action='remove-restore'; id='final-cleanup'}) -BackupDirectory (Join-Path $runtimeAbsolute 'backups') | Out-Null
        }
        $privateFiles = @(Get-ChildItem -LiteralPath (Join-Path $reportDirectory 'runtime') -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Directory.Name -eq 'secrets' })
        if ($privateFiles.Count -eq 0) {
            Copy-Item -LiteralPath (Join-Path $reportDirectory 'runtime') -Destination (Join-Path $runtimeAbsolute 'runs') -Recurse -Force
        }
        & (Join-Path $PSScriptRoot 'cleanup-fixture.ps1') -FixturePath $fixturePath
        $resultPath = Join-Path $reportDirectory 'verification.json'
        if (Test-Path -LiteralPath $resultPath) {
            $verification = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
            $verification | Add-Member -NotePropertyName scoped_mysql_account_removed -NotePropertyValue $true -Force
            $verification | Add-Member -NotePropertyName runtime_logs_copy -NotePropertyValue (Join-Path $runtimeAbsolute 'runs') -Force
            $verification | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $resultPath -Encoding utf8NoBOM
        }
    }
    Write-Output "Verification report: $reportDirectory/verification.json"
    if ($verificationExitCode -ne 0) { throw 'Some checks failed. See the verification report.' }
} finally {
    Pop-Location
}
