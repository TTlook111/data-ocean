param([string]$JavaHome = $env:JAVA_HOME)

# 已升级语义工作区的本机入口；旧 V64 冻结验收入口保持不变。
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$backendRoot = Join-Path $repoRoot 'backend\DataOcean'
$pythonExe = Join-Path $repoRoot 'python-service\.venv\Scripts\python.exe'
$configPath = Join-Path $PSScriptRoot '.env.local'
if (-not (Test-Path -LiteralPath $configPath) -or -not (Test-Path -LiteralPath $pythonExe)) {
    throw '本机隔离环境配置或项目 Python 环境不存在。'
}
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw '8080 已被占用，请先核对该服务的用途。'
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw '请使用 -JavaHome 指定本机已核实的 Java 17 安装目录。'
}
$migrationVersions = Get-ChildItem -LiteralPath (Join-Path $backendRoot 'src\main\resources\db\migration') -File |
    ForEach-Object { if ($_.Name -match '^V(\d+)__') { [int]$matches[1] } }
$highestMigration = ($migrationVersions | Measure-Object -Maximum).Maximum
if ($migrationVersions -contains 53) { throw 'V53 永久不可使用。' }
$savedEnvironment = @{}
function Set-RunEnvironment([string]$Name, [string]$Value) {
    if (-not $savedEnvironment.ContainsKey($Name)) {
        $savedEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}
try {
    foreach ($line in Get-Content -LiteralPath $configPath) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
            Set-RunEnvironment $matches[1] $matches[2].Trim().Trim([char]34, [char]39)
        }
    }
    if ($env:LANGGRAPH_REVIEW_APP_SCHEMA -ne 'dataocean_e_acceptance_20260927') {
        throw '配置不属于已经确认的本机隔离应用库。'
    }
    Set-RunEnvironment 'DATAOCEAN_EXPECTED_MIGRATION' ([string]$highestMigration)
    $guard = @'
import os,sys,pymysql
try:
    with pymysql.connect(host='127.0.0.1',port=3306,database=os.environ['LANGGRAPH_REVIEW_APP_SCHEMA'],
            user=os.environ['LANGGRAPH_REVIEW_MYSQL_ROOT_USER'],password=os.environ['LANGGRAPH_REVIEW_MYSQL_ROOT_PASSWORD'],
            connect_timeout=5,read_timeout=5) as connection:
        with connection.cursor() as cursor:
            cursor.execute('SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1')
            row=cursor.fetchone()
            assert row and str(row[0])==os.environ['DATAOCEAN_EXPECTED_MIGRATION']
            cursor.execute('SELECT COUNT(*) FROM flyway_schema_history WHERE success=0')
            assert cursor.fetchone()[0]==0
    print('隔离应用库与当前代码迁移版本一致。')
except Exception:
    print('隔离库只读核验失败。此入口不自动升级或重置数据库。',file=sys.stderr)
    sys.exit(2)
'@
    & $pythonExe -B -c $guard
    if ($LASTEXITCODE -ne 0) { throw '请先按已获授权的迁移流程核对隔离库。' }
    Set-RunEnvironment 'JAVA_HOME' $JavaHome
    Set-RunEnvironment 'Path' ((Join-Path $JavaHome 'bin') + ';' + $env:Path)
    Set-RunEnvironment 'SPRING_DATASOURCE_URL' 'jdbc:mysql://127.0.0.1:3306/dataocean_e_acceptance_20260927?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true'
    Set-RunEnvironment 'SPRING_DATASOURCE_USERNAME' $env:LANGGRAPH_REVIEW_MYSQL_ROOT_USER
    Set-RunEnvironment 'SPRING_DATASOURCE_PASSWORD' $env:LANGGRAPH_REVIEW_MYSQL_ROOT_PASSWORD
    Set-RunEnvironment 'SERVER_ADDRESS' '127.0.0.1'
    Set-RunEnvironment 'PYTHON_SERVICE_BASE_URL' 'http://127.0.0.1:8000'
    Set-RunEnvironment 'SPRING_FLYWAY_ENABLED' 'true'
    Set-RunEnvironment 'JAVA_TOOL_OPTIONS' (($env:JAVA_TOOL_OPTIONS + ' -Dfile.encoding=UTF-8').Trim())
    Push-Location $backendRoot
    try {
        & mvn spring-boot:run
        if ($LASTEXITCODE -ne 0) { throw '语义工作区后端启动失败，请检查启动日志。' }
    } finally { Pop-Location }
} finally {
    foreach ($entry in $savedEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
}
