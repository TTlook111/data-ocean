param([Parameter(Mandatory=$true)][string]$FixturePath)
$ErrorActionPreference = 'Stop'
$fixtureAbsolute = [System.IO.Path]::GetFullPath($FixturePath)
if (Test-Path -LiteralPath $fixtureAbsolute) { throw 'Refusing to overwrite an existing fixture.' }
$runId = 'tech_' + [guid]::NewGuid().ToString('N').Substring(0,12)
$schema = 'dataocean_' + $runId
$container = docker inspect mysql | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or -not $container.State.Running -or $container.Config.Image -ne 'mysql:8.0') { throw 'Unexpected MySQL target.' }
if (-not ($container.Mounts | Where-Object Name -eq 'dataocean-shared-mysql-data')) { throw 'Unexpected MySQL volume.' }
$testPassword = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
# 只创建本轮临时账号，授权范围固定为唯一测试 schema，不导出容器 root 密码。
$sql = "CREATE USER '$runId'@'%' IDENTIFIED BY '$testPassword'; GRANT ALL ON ``$schema``.* TO '$runId'@'%';"
$sql | docker exec -i mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --raw --skip-column-names'
if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare scoped fixture account.' }
$fixture = @{run_id=$runId; database_password=$testPassword; mysql=@{State=@{Running=$true}; Config=@{Image=$container.Config.Image}; Mounts=@(@{Name='dataocean-shared-mysql-data'})}}
$fixture | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fixtureAbsolute -Encoding utf8NoBOM
Write-Output "Prepared isolated fixture account: $runId"
