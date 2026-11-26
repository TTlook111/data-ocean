param(
    # 本机配置默认位于 resources；仅在显式使用外部配置时指定其他目录。
    [string]$ConfigurationDirectory,

    # 本批开发应用使用的独立端口。
    [int]$Port = 18080
)

$ErrorActionPreference = 'Stop'

# 从脚本位置定位仓库，家里和公司的路径可以不同。
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path

$privateDirectory = Join-Path $repository '.dataocean'

if (-not $ConfigurationDirectory) {
    # 主配置、模板和本机覆盖文件放在同一资源目录，local 由 Git 忽略。
    $ConfigurationDirectory = Join-Path $repository 'backend/src/main/resources'
}

$configuration = Join-Path $ConfigurationDirectory 'application-local.yml'

if (-not (Test-Path -LiteralPath $configuration)) {
    throw "缺少本机连接配置：$configuration；请按 docs/backend/运行与验收.md 创建。"
}

# HMAC 密钥只初始化一次，重启应用沿用同一份命令及核对密钥。
New-Item -ItemType Directory -Force -Path $privateDirectory | Out-Null

$keyFile = Join-Path $privateDirectory 'hmac-key.txt'

if (-not (Test-Path -LiteralPath $keyFile)) {
    $bytes = New-Object byte[] 48

    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        # 不把随机材料或文件内容输出到控制台。
        $random.GetBytes($bytes)
        [System.IO.File]::WriteAllText($keyFile, [Convert]::ToBase64String($bytes), [System.Text.UTF8Encoding]::new($false))
    }
    finally {
        $random.Dispose()
    }
}

$previousSecretReference = $env:DATAOCEAN_SECRET_FILE

$configurationUri = [Uri]::new(([System.IO.Path]::GetFullPath($ConfigurationDirectory).TrimEnd('\') + '\')).AbsoluteUri

Push-Location (Join-Path $repository 'backend')
try {
    # 固定构建版本随仓库提供，运行地址和 Cookie 仅作用于 dev profile。
    $env:DATAOCEAN_SECRET_FILE = $keyFile
    $applicationArguments = "--spring.profiles.active=local,dev --spring.config.additional-location=optional:$configurationUri"
    if ($PSBoundParameters.ContainsKey('Port')) {
        # 只有显式指定端口时才覆盖当前机器 local 配置中的端口。
        $applicationArguments += " --server.port=$Port"
    }
    & .\mvnw.cmd -s .mvn/settings.xml '-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8' "-Dspring-boot.run.arguments=$applicationArguments" spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "后端退出，代码 $LASTEXITCODE"
    }
}
finally {
    $env:DATAOCEAN_SECRET_FILE = $previousSecretReference
    Pop-Location
}
