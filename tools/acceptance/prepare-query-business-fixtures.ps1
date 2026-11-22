# 在现有本机开发 MySQL 中准备 E 批两个合成业务库。
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Text.UTF8Encoding]::new($false)
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$private = Join-Path $repo '.dataocean/query-coverage-business.json'
$energyDb = 'do_query_e2e_energy_20261122'
$deliveryDb = 'do_query_e2e_delivery_20261122'
$energyUser = 'do_query_energy_ro'
$deliveryUser = 'do_query_delivery_ro'

# 只允许本机 Docker 套接字和已运行的开发验收实例。
$context = docker context show
if ($LASTEXITCODE -ne 0) { throw '无法读取 Docker 使用的上下文' }
$endpoint = docker context inspect $context --format '{{.Endpoints.docker.Host}}'
if ($LASTEXITCODE -ne 0 -or $endpoint -notmatch '^(npipe:|unix:)' -or
    ($env:DOCKER_HOST -and $env:DOCKER_HOST -notmatch '^(npipe:|unix:)')) {
    throw '验收需要本机 Docker 套接字'
}
$target = docker inspect --format '{{.Name}}|{{.State.Status}}|{{index .Config.Labels "com.docker.compose.project"}}' mysql
if ($LASTEXITCODE -ne 0 -or $target -ne '/mysql|running|mysql') {
    throw '无法确认本机开发 MySQL 实例'
}
$ports = docker port mysql 3306/tcp
if ($LASTEXITCODE -ne 0 -or '127.0.0.1:3306' -notin @($ports)) {
    throw '开发 MySQL 的本机端口与问数验收配置不一致'
}
if (Test-Path -LiteralPath $private) {
    # 已有正确的合成配置可直接复用，不覆盖数据库和凭据。
    $existing = Get-Content -LiteralPath $private -Raw | ConvertFrom-Json
    if ($existing.energy.database -ne $energyDb -or $existing.delivery.database -ne $deliveryDb -or
        $existing.energy.username -ne $energyUser -or $existing.delivery.username -ne $deliveryUser) {
        throw '本机旧配置不对应本验收的专用数据源'
    }
    Write-Output $private
    return
}

# 写入之前确认信任库和账户都仅用于当前验收。
$trustStore = (Resolve-Path -LiteralPath (Join-Path $repo '.dataocean/datasource-local/mysql-ca.p12')).Path
$local = Get-Content -LiteralPath (Join-Path $repo 'backend/src/main/resources/application-local.yml') -Raw
$store = [regex]::Match($local, '(?m)^\s*mysql-trust-store-password:\s*["'']?([^"''\r\n]+)')
if (!$store.Success) { throw '缺少本机 MySQL 信任库配置' }
$preflight = @"
SELECT (SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name IN ('$energyDb','$deliveryDb'))
     + (SELECT COUNT(*) FROM mysql.user WHERE User IN ('$energyUser','$deliveryUser'));
"@
$count = $preflight | docker exec -i mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names' 2>$null
if ($LASTEXITCODE -ne 0 -or $count -ne '0') { throw '合成库或专用账号已存在，需核对残留状态' }

# 随机密码只通过输入流和本机忽略文件传递，输出仅返回配置路径。
$energy = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$delivery = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$sql = @"
CREATE DATABASE $energyDb CHARACTER SET utf8mb4;
CREATE DATABASE $deliveryDb CHARACTER SET utf8mb4;
CREATE TABLE $energyDb.zone (id INT PRIMARY KEY, name VARCHAR(60));
INSERT INTO $energyDb.zone VALUES (1,'装配区'), (2,'冷却区');
CREATE TABLE $energyDb.daily_facts (
  zone_id INT, business_day DATE, amount DECIMAL(12,3), uom VARCHAR(12),
  FOREIGN KEY (zone_id) REFERENCES zone(id)
);
INSERT INTO $energyDb.daily_facts VALUES
  (1,'2026-11-17',82.125,'kWh'), (1,'2026-11-18',0,'kWh'),
  (1,'2026-11-20',-4.250,'kWh'), (1,'2026-11-20',13.500,'kWh'), (2,'2026-11-20',7.000,'MWh');
CREATE TABLE $deliveryDb.daily_facts (
  route_code VARCHAR(30), business_day DATE, amount INT, signed_state VARCHAR(12)
);
INSERT INTO $deliveryDb.daily_facts VALUES
  ('T-17','2026-11-20',42,'签收'), ('T-17','2026-11-20',9,'退回'),
  ('T-17','2026-11-20',42,'签收'), ('T-17','2026-11-21',8,'签收'),
  ('T-18','2026-11-20',100,'签收');
CREATE USER '$energyUser'@'%' IDENTIFIED BY '$energy' REQUIRE SSL;
CREATE USER '$deliveryUser'@'%' IDENTIFIED BY '$delivery' REQUIRE SSL;
GRANT SELECT ON $energyDb.* TO '$energyUser'@'%';
GRANT SELECT ON $deliveryDb.* TO '$deliveryUser'@'%';
"@
$sql | docker exec -i mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --default-character-set=utf8mb4' 2>$null
if ($LASTEXITCODE -ne 0) { throw '创建合成数据失败，未输出私有 SQL' }
@{
    trustStore = $trustStore
    trustStorePassword = $store.Groups[1].Value.Trim()
    energy = @{ database = $energyDb; username = $energyUser; password = $energy }
    delivery = @{ database = $deliveryDb; username = $deliveryUser; password = $delivery }
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $private -Encoding utf8
Write-Output $private
