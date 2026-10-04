param(
    [Parameter(Mandatory = $true)]
    [string]$JavaHome
)

# Reuse the existing visual-acceptance environment; never seed or migrate it.
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$configPath = Join-Path $PSScriptRoot '.env.local'
$pythonExe = Join-Path $repoRoot 'python-service\.venv\Scripts\python.exe'
$guardPath = Join-Path $PSScriptRoot 'verify_isolated_e_target.py'
$backendRoot = Join-Path $repoRoot 'backend\DataOcean'

if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Pass the verified Java 17 installation path.'
}
if (-not (Test-Path -LiteralPath $configPath) -or -not (Test-Path -LiteralPath $pythonExe)) {
    throw 'The existing ignored acceptance configuration or Python runtime is missing.'
}
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Port 8080 is occupied. Identify and stop only the backend owned by this task first.'
}
$highestMigration = Get-ChildItem -LiteralPath (Join-Path $backendRoot 'src\main\resources\db\migration') -File |
    ForEach-Object { if ($_.Name -match '^V(\d+)__') { [int]$matches[1] } } |
    Measure-Object -Maximum
if ($highestMigration.Maximum -ne 64) {
    throw 'The frozen V64 visual environment cannot be used with additional migrations.'
}

$previousEnvironment = @{}
function Set-RunEnvironment([string]$Name, [string]$Value) {
    if (-not $previousEnvironment.ContainsKey($Name)) {
        $previousEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

try {
    foreach ($line in Get-Content -LiteralPath $configPath) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
            Set-RunEnvironment $matches[1] $matches[2].Trim().Trim([char]34, [char]39)
        }
    }
    & $pythonExe $guardPath
    if ($LASTEXITCODE -ne 0) { throw 'The read-only isolated target guard failed.' }

    Set-RunEnvironment 'JAVA_HOME' $JavaHome
    Set-RunEnvironment 'Path' ((Join-Path $JavaHome 'bin') + ';' + $env:Path)
    Set-RunEnvironment 'SPRING_DATASOURCE_URL' 'jdbc:mysql://127.0.0.1:3306/dataocean_e_acceptance_20260927?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true'
    Set-RunEnvironment 'SPRING_DATASOURCE_USERNAME' $env:LANGGRAPH_REVIEW_MYSQL_ROOT_USER
    Set-RunEnvironment 'SPRING_DATASOURCE_PASSWORD' $env:LANGGRAPH_REVIEW_MYSQL_ROOT_PASSWORD

    Push-Location $backendRoot
    try {
        & mvn spring-boot:run '-Dspring-boot.run.arguments=--server.address=127.0.0.1 --spring.flyway.enabled=false --dataocean.python-service.base-url=http://127.0.0.1:8000'
        if ($LASTEXITCODE -ne 0) { throw 'The isolated Java backend stopped with an error.' }
    } finally { Pop-Location }
} finally {
    foreach ($entry in $previousEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
}
