$ErrorActionPreference = 'Stop'

# 只在当前仓库的私有目录初始化服务身份。
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path

$privateDirectory = Join-Path $repository '.dataocean'

New-Item -ItemType Directory -Path $privateDirectory -Force | Out-Null

foreach ($service in @('java', 'python')) {
    # 保留已有的成对身份，部分文件缺失时交由操作者核对。
    $signingKey = Join-Path $privateDirectory "$service-private.pem"
    $verifyKey = Join-Path $privateDirectory "$service-public.pem"
    $signingExists = Test-Path -LiteralPath $signingKey -PathType Leaf
    $verifyExists = Test-Path -LiteralPath $verifyKey -PathType Leaf
    if ($signingExists -and $verifyExists) {
        # 重复执行沿用原签名关系。
        continue
    }
    if ($signingExists -or $verifyExists) {
        # 单独重新生成一个文件会破坏两端的信任关系。
        throw "服务 $service 的签名文件不完整，请核对已有文件。"
    }
    $rsa = [System.Security.Cryptography.RSA]::Create(2048)
    try {
        # PEM 内容只写到私有文件，CreateNew 防止并发覆盖。
        $signingPem = $rsa.ExportPkcs8PrivateKeyPem()
        $verifyPem = $rsa.ExportSubjectPublicKeyInfoPem()
        $encoding = [System.Text.UTF8Encoding]::new($false)
        foreach ($entry in @(
            @{ Path = $signingKey; Content = $signingPem }
            @{ Path = $verifyKey; Content = $verifyPem }
        )) {
            # 为每个文件独立关闭写入句柄。
            $file = [System.IO.File]::Open($entry.Path, [System.IO.FileMode]::CreateNew)
            try {
                # UTF-8 PEM 与 Java 和 Python 加载器通用。
                $bytes = $encoding.GetBytes($entry.Content)
                $file.Write($bytes, 0, $bytes.Length)
            }
            finally {
                $file.Dispose()
            }
        }
    }
    finally {
        $rsa.Dispose()
    }
}

Write-Output 'Java 和 Python 服务签名文件已就绪。'
