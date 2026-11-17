param(
    # 显式指向本批开发后端，不访问同机旧项目的 8080。
    [string]$ApiUrl = 'http://127.0.0.1:18080'
)

$ErrorActionPreference = 'Stop'

# 使用当前机器已选择的项目 Node 版本，路径不写死在脚本中。
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path

$previousProxy = $env:DATAOCEAN_API_PROXY_URL

Push-Location (Join-Path $repository 'frontend')
try {
    # 用户提供的开发示例账号由 frontend/.env.development 随代码加载。
    $env:DATAOCEAN_API_PROXY_URL = $ApiUrl
    & npm.cmd run dev
    if ($LASTEXITCODE -ne 0) {
        throw "前端退出，代码 $LASTEXITCODE"
    }
}
finally {
    $env:DATAOCEAN_API_PROXY_URL = $previousProxy
    Pop-Location
}
