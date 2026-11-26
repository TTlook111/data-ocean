param([Parameter(Mandatory=$true)][string]$FixturePath)
$ErrorActionPreference = 'Stop'
$fixtureAbsolute = [System.IO.Path]::GetFullPath($FixturePath)
$fixture = Get-Content -LiteralPath $fixtureAbsolute -Raw | ConvertFrom-Json
$runId = $fixture.run_id
if ($runId -notmatch '^tech_[0-9a-f]{12}$') { throw 'Invalid probe identity.' }
$schema = 'dataocean_' + $runId
$container = docker inspect mysql | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $container.Config.Image -ne 'mysql:8.0' -or -not $container.State.Running) { throw 'Unexpected MySQL target.' }
if (-not ($container.Mounts | Where-Object Name -eq 'dataocean-shared-mysql-data')) { throw 'Unexpected MySQL volume.' }
$existsSql = "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$schema';"
$remaining = $existsSql | docker exec -i mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --raw --skip-column-names'
if ($LASTEXITCODE -ne 0 -or "$remaining".Trim() -ne '0') { throw 'Probe schema remains; preserve credentials for scoped cleanup.' }
# 账号名来自已验证的本轮 fixture，且库已由带 marker 的验证程序清理。
"DROP USER '$runId'@'%';" | docker exec -i mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --raw --skip-column-names'
if ($LASTEXITCODE -ne 0) { throw 'Cannot remove scoped fixture account.' }
Remove-Item -LiteralPath $fixtureAbsolute
Write-Output "Removed isolated fixture account: $runId"
