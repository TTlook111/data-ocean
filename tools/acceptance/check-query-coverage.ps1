param(
    [Parameter(Mandatory)][string]$JavaReport,
    [Parameter(Mandatory)][string]$PythonReport,
    [Parameter(Mandatory)][string]$FrontendReport
)

$ErrorActionPreference = 'Stop'

# 使用计数做门槛判断，显示百分比的四舍五入不影响验收。
function Measure-QueryCoverage {
    param([string]$Name, [int]$Covered, [int]$Total, [int]$BranchCovered, [int]$BranchTotal)
    if ($Total -le 0 -or $Covered -lt 0 -or $Covered -gt $Total -or
        $BranchTotal -le 0 -or $BranchCovered -lt 0 -or $BranchCovered -gt $BranchTotal) {
        throw "$Name 没有完整行和分支计数"
    }
    return [pscustomobject]@{
        module = $Name
        lines = [math]::Round(100.0 * $Covered / $Total, 2)
        branches = [math]::Round(100.0 * $BranchCovered / $BranchTotal, 2)
        linesCovered = $Covered
        linesTotal = $Total
        branchesCovered = $BranchCovered
        branchesTotal = $BranchTotal
        passed = $Covered * 10 -ge $Total * 9
    }
}

[xml]$java = Get-Content -LiteralPath $JavaReport -Raw
$python = Get-Content -LiteralPath $PythonReport -Raw | ConvertFrom-Json -AsHashtable
$front = Get-Content -LiteralPath $FrontendReport -Raw | ConvertFrom-Json -AsHashtable

# 三份报告都必须限定在完整的正式问数包，避免别的业务代码拉高结果。
$packages = @($java.report.package)
if (!$packages.Count -or ($packages | Where-Object { $_.name -notlike 'com/dataocean/module/query/*' -and $_.name -ne 'com/dataocean/module/query' })) {
    throw 'Java 报告必须限定在 com.dataocean.module.query'
}
$pythonFiles = @($python['files'].Keys)
if (!$pythonFiles.Count -or ($pythonFiles | Where-Object { ($_ -replace '\\', '/') -notmatch '(^|/)dataocean_ai/query/' })) {
    throw 'Python 报告必须限定在 dataocean_ai.query'
}
$frontendFiles = @($front.Keys | Where-Object { $_ -ne 'total' })
if (!$frontendFiles.Count -or ($frontendFiles | Where-Object { ($_ -replace '\\', '/') -notmatch '(^|/)src/modules/query/' })) {
    throw '前端报告必须限定在 src/modules/query'
}

$jLine = $java.report.counter | Where-Object type -eq 'LINE'
$jBranch = $java.report.counter | Where-Object type -eq 'BRANCH'
$p = $python['totals']
$f = $front['total']
$results = @(
    Measure-QueryCoverage 'Java' ([int]$jLine.covered) ([int]$jLine.covered + [int]$jLine.missed) ([int]$jBranch.covered) ([int]$jBranch.covered + [int]$jBranch.missed)
    Measure-QueryCoverage 'Python' $p['covered_lines'] $p['num_statements'] $p['covered_branches'] $p['num_branches']
    Measure-QueryCoverage '前端' $f['lines']['covered'] $f['lines']['total'] $f['branches']['covered'] $f['branches']['total']
)
ConvertTo-Json -InputObject $results -Depth 4
if ($results | Where-Object { !$_.passed }) {
    exit 1
}
