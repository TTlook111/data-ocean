param(
    # 正式内部 API 使用独立端口，不占用 Java 或前端地址。
    [int]$Port = 18090
)

$ErrorActionPreference = 'Stop'

# 入口按仓库位置定位，各电脑只需选择兼容 Python 与 uv。
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path

$previousPort = $env:AI_PORT

$previousEncoding = $env:PYTHONUTF8

Push-Location (Join-Path $repository 'python-service')
try {
    # 配置文件只在当前 Python 工程显式加载，不误读仓库根目录的 Compose 环境。
    $arguments = @('run', '--locked', '--python', '3.13')

    if (Test-Path -LiteralPath '.env') {
        $arguments += @('--env-file', '.env')
    }

    $arguments += 'dataocean-ai-api'
    if ($PSBoundParameters.ContainsKey('Port')) {
        # 显式端口覆盖本机配置，省略时由 .env 和 Python 默认配置决定。
        $env:AI_PORT = [string]$Port
    }
    $env:PYTHONUTF8 = '1'
    & uv.exe @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Python API 退出，代码 $LASTEXITCODE"
    }
}
finally {
    $env:AI_PORT = $previousPort
    $env:PYTHONUTF8 = $previousEncoding
    Pop-Location
}
