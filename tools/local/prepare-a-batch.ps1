param(
    [Parameter(Mandatory=$true)][string]$RuntimeDirectory,
    [string]$MySqlContainer = 'mysql',
    [string]$ExpectedVolume = 'dataocean-shared-mysql-data',
    [string]$RedisEnvironmentFile
)
$ErrorActionPreference = 'Stop'
$runtimePath = [IO.Path]::GetFullPath($RuntimeDirectory)
$privatePath = Join-Path $runtimePath 'private'
New-Item -ItemType Directory -Force -Path $privatePath,(Join-Path $runtimePath 'logs') | Out-Null
$configPath = Join-Path $privatePath 'application-local.properties'
if (Test-Path -LiteralPath $configPath) { Write-Output 'Existing private application configuration retained.'; return }
$info = docker inspect $MySqlContainer | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or -not $info.State.Running -or $info.Config.Image -ne 'mysql:8.0' -or
    -not ($info.Mounts | Where-Object Name -eq $ExpectedVolume)) { throw 'MySQL identity or mount does not match the verified target.' }
function Invoke-PlatformSql([string]$Sql) {
    $result = $Sql | docker exec -i $MySqlContainer sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --raw --skip-column-names'
    if ($LASTEXITCODE -ne 0) { throw 'Scoped database preparation failed.' }
    return $result
}
$existing = Invoke-PlatformSql "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='dataocean';"
if ([int]$existing -ne 0) { throw 'The target platform database already exists; inspect it before preparing this environment.' }
$suffix = [guid]::NewGuid().ToString('N').Substring(0,12)
$testDatabase = 'dataocean_a_it_' + $suffix
$testUser = 'do_a_it_' + $suffix
$appPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$testPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$initialPassword = 'A1' + [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(15))
$hmacKey = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$sql = @"
CREATE DATABASE dataocean CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER 'dataocean_app'@'%' IDENTIFIED BY '$appPassword';
GRANT ALL ON dataocean.* TO 'dataocean_app'@'%';
CREATE DATABASE ``$testDatabase`` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER '$testUser'@'%' IDENTIFIED BY '$testPassword';
GRANT ALL ON ``$testDatabase``.* TO '$testUser'@'%';
"@
Invoke-PlatformSql $sql | Out-Null
$redisPassword = ''
if ($RedisEnvironmentFile) {
    $line = Get-Content -LiteralPath $RedisEnvironmentFile | Where-Object { $_ -match '^REDIS_PASSWORD=' } | Select-Object -First 1
    if ($line) { $redisPassword = $line.Substring('REDIS_PASSWORD='.Length).Trim('"',"'") }
}
$passwordPath = Join-Path $privatePath 'bootstrap-password.txt'
$hmacPath = Join-Path $privatePath 'hmac-key.txt'
[IO.File]::WriteAllText($passwordPath,$initialPassword,[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($hmacPath,$hmacKey,[Text.UTF8Encoding]::new($false))
$properties = @"
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/dataocean?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
spring.datasource.username=dataocean_app
spring.datasource.password=$appPassword
spring.data.redis.password=$redisPassword
server.servlet.session.cookie.secure=false
dataocean.secret-key-file=$($hmacPath.Replace('\','/'))
dataocean.bootstrap-username=admin
dataocean.bootstrap-display-name=系统管理员
dataocean.bootstrap-department-code=ROOT
dataocean.bootstrap-department-name=企业
dataocean.bootstrap-password-file=$($passwordPath.Replace('\','/'))
"@
[IO.File]::WriteAllText($configPath,$properties,[Text.UTF8Encoding]::new($false))
$testProperties = $properties.Replace('/dataocean?','/' + $testDatabase + '?').Replace('username=dataocean_app','username=' + $testUser).Replace('password=' + $appPassword,'password=' + $testPassword)
$testProperties += "`nspring.session.redis.namespace=dataocean:a-it:$suffix`ndataocean.outbox-stream=dataocean:a-it:$suffix:events`n"
[IO.File]::WriteAllText((Join-Path $privatePath 'application-test.properties'),$testProperties,[Text.UTF8Encoding]::new($false))
# 仅限制本轮新建私有目录，不改动共享基础设施或用户原有目录的权限。
$acl = Get-Acl -LiteralPath $privatePath
$acl.SetAccessRuleProtection($true,$false)
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$rule = [Security.AccessControl.FileSystemAccessRule]::new($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
$acl.AddAccessRule($rule)
Set-Acl -LiteralPath $privatePath -AclObject $acl
@{database='dataocean';testDatabase=$testDatabase;username='admin';privateDirectory=$privatePath} | ConvertTo-Json | Write-Output
