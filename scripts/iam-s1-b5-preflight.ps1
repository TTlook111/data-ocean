# IAM-SIMPLE-1 B5 只读 preflight。
# 禁止：Flyway migrate、任何 DDL、bootstrap、写数据库、启动应用、读写密钥。
# 本脚本不读取 application.yml / .env 中的密码、JWT、API key，也不打印这些值。

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $RepoRoot

$script:Failures = New-Object System.Collections.Generic.List[string]
$script:Warnings = New-Object System.Collections.Generic.List[string]
$script:PendingUserInput = New-Object System.Collections.Generic.List[string]

function Add-Fail([string]$Message) {
    $script:Failures.Add($Message) | Out-Null
    Write-Host "FAIL  $Message"
}

function Add-Pass([string]$Message) {
    Write-Host "PASS  $Message"
}

function Add-Pending([string]$Message) {
    $script:PendingUserInput.Add($Message) | Out-Null
    Write-Host "NEED  $Message"
}

function Get-FileText([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path)) {
        Add-Fail "missing file $Path"
        return ""
    }
    return [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $Path).Path, [System.Text.Encoding]::UTF8)
}

function Find-Doc([string]$Directory, [string]$Filter) {
    $matches = @(Get-ChildItem -LiteralPath $Directory -Filter $Filter -File -ErrorAction SilentlyContinue)
    if ($matches.Count -eq 1) {
        return $matches[0].FullName
    }
    Add-Fail "expected exactly one file matching $Filter under $Directory, found $($matches.Count)"
    return $null
}

Write-Host "IAM-SIMPLE-1 B5 read-only preflight"
Write-Host "repo: $RepoRoot"
Write-Host "this script does not migrate, bootstrap, or print secrets"
Write-Host ""

# --- 分支与工作树 ---
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($branch -eq "codex/iam-s1-b5-preparation") {
    Add-Pass "branch is $branch"
} else {
    Add-Fail "branch must be codex/iam-s1-b5-preparation, got $branch"
}

$headShort = (git rev-parse --short HEAD).Trim()
$headFull = (git rev-parse HEAD).Trim()
Write-Host "INFO  HEAD $headShort"

git merge-base --is-ancestor 1a6e426 HEAD
if ($LASTEXITCODE -eq 0) {
    Add-Pass "HEAD contains IAM commit 1a6e426"
} else {
    Add-Fail "HEAD does not contain IAM commit 1a6e426"
}

$status = git status --short
$workingTreeClean = [string]::IsNullOrWhiteSpace($status)
if ($workingTreeClean) {
    Add-Pass "working tree is clean"
} else {
    Add-Fail "working tree is dirty; real B5 execution requires a clean tree"
    Write-Host $status
}

$expectedSha = [Environment]::GetEnvironmentVariable("B5_EXPECTED_SHA")
if (-not [string]::IsNullOrWhiteSpace($expectedSha)) {
    $expectedSha = $expectedSha.Trim()
}
if ([string]::IsNullOrWhiteSpace($expectedSha)) {
    Add-Fail "B5_EXPECTED_SHA is not provided; user must confirm the exact deployment SHA before real B5"
} elseif (-not $workingTreeClean) {
    Add-Fail "HEAD cannot be accepted as B5_EXPECTED_SHA while the working tree is dirty"
} elseif ([string]::Equals($expectedSha, $headFull, [System.StringComparison]::OrdinalIgnoreCase)) {
    Add-Pass "HEAD matches B5_EXPECTED_SHA"
    Write-Host "INFO  confirmed SHA $headFull"
    Write-Host "INFO  write this SHA into backup, switch, and acceptance records"
} else {
    Add-Fail "HEAD does not match B5_EXPECTED_SHA; containing 1a6e426 is not enough"
}

# --- migration 文件 ---
$migrationDir = Join-Path $RepoRoot "backend\DataOcean\src\main\resources\db\migration"
$required = @(
    "V51__knowledge_version_review_status_backfill.sql",
    "V52__add_query_suggested_questions.sql",
    "V54__iam_s1_configuration_base.sql",
    "V55__iam_s1_data_authorization.sql",
    "V56__iam_s1_query_execution_evidence.sql",
    "V57__iam_s1_access_request.sql"
)
foreach ($name in $required) {
    $path = Join-Path $migrationDir $name
    if (Test-Path -LiteralPath $path) {
        Add-Pass "migration present $name"
    } else {
        Add-Fail "missing migration $name"
    }
}

$v53 = Get-ChildItem -LiteralPath $migrationDir -Filter "V53*.sql" -ErrorAction SilentlyContinue
if ($null -eq $v53 -or @($v53).Count -eq 0) {
    Add-Pass "no V53 migration file"
} else {
    Add-Fail ("V53 must stay unused, found: " + (($v53 | ForEach-Object { $_.Name }) -join ", "))
}

$versions = Get-ChildItem -LiteralPath $migrationDir -Filter "V*.sql" |
    ForEach-Object {
        if ($_.Name -match '^V(\d+)__') { [int]$Matches[1] }
    } | Sort-Object
$unexpected = @($versions | Where-Object { $_ -gt 50 -and $_ -ne 51 -and $_ -ne 52 -and $_ -ne 54 -and $_ -ne 55 -and $_ -ne 56 -and $_ -ne 57 })
if ($unexpected.Count -eq 0) {
    Add-Pass "post-V50 migration numbers are only 51,52,54-57"
} else {
    Add-Fail ("unexpected migration versions: " + ($unexpected -join ", "))
}

# --- V54～V57 不得回填旧权限 ---
$legacyBackfill = @(
    "sys_role_permission",
    "datasource_access_policy",
    "INSERT INTO sys_role",
    "INSERT INTO sys_permission",
    "INSERT INTO sys_user_role",
    "INSERT INTO datasource_access",
    "INSERT INTO access_approval_request"
)
foreach ($file in @("V54__iam_s1_configuration_base.sql", "V55__iam_s1_data_authorization.sql", "V56__iam_s1_query_execution_evidence.sql", "V57__iam_s1_access_request.sql")) {
    $path = Join-Path $migrationDir $file
    if (-not (Test-Path -LiteralPath $path)) { continue }
    $text = Get-FileText $path
    $hits = @($legacyBackfill | Where-Object { $text -match [regex]::Escape($_) })
    if ($hits.Count -eq 0 -and $text -notmatch '(?i)FOREIGN KEY' -and $text -notmatch '(?i)\bREFERENCES\b') {
        Add-Pass "$file has no legacy backfill and no foreign keys"
    } else {
        Add-Fail "$file contains legacy backfill or FOREIGN KEY: $($hits -join ', ')"
    }
}

# --- 固定 54 个功能码 ---
$v54 = Get-FileText (Join-Path $migrationDir "V54__iam_s1_configuration_base.sql")
$insertStart = $v54.IndexOf("INSERT INTO iam_s1_function")
$insertEnd = $v54.IndexOf("ON DUPLICATE KEY UPDATE", $insertStart)
if ($insertStart -lt 0 -or $insertEnd -lt 0) {
    Add-Fail "V54 function catalog insert not found"
} else {
    $section = $v54.Substring($insertStart, $insertEnd - $insertStart)
    $codes = [regex]::Matches($section, "\(\d+,\s*'([^']+)'") | ForEach-Object { $_.Groups[1].Value }
    $unique = $codes | Select-Object -Unique
    if ($codes.Count -eq 54 -and $unique.Count -eq 54) {
        Add-Pass "V54 contains exactly 54 unique function codes"
    } else {
        Add-Fail "V54 function codes count=$($codes.Count) unique=$($unique.Count), expected 54"
    }
}

# --- 旧权限关键词不得进入 S1 Resolver ---
$resolverRoots = @(
    (Join-Path $RepoRoot "backend\DataOcean\src\main\java\com\dataocean\module\permission\s1\resource"),
    (Join-Path $RepoRoot "backend\DataOcean\src\main\java\com\dataocean\module\permission\s1\service\impl\IamS1AuthorizationResolverImpl.java"),
    (Join-Path $RepoRoot "backend\DataOcean\src\main\java\com\dataocean\module\permission\s1\service\impl\IamS1DataAuthorizationResolverImpl.java")
)
$legacyKeywords = @(
    "sys_role_permission",
    "sys_permission",
    "datasource_access_policy",
    "DatasourceAccessService",
    "PermissionCalculator",
    "hasAnyAuthority",
    "Caffeine"
)
$resolverHits = @()
foreach ($root in $resolverRoots) {
    if (-not (Test-Path -LiteralPath $root)) {
        Add-Fail "resolver path missing $root"
        continue
    }
    $files = if (Test-Path -LiteralPath $root -PathType Container) {
        Get-ChildItem -LiteralPath $root -Recurse -Filter "*.java"
    } else {
        @(Get-Item -LiteralPath $root)
    }
    foreach ($file in $files) {
        $text = Get-FileText $file.FullName
        foreach ($kw in $legacyKeywords) {
            if ($text.Contains($kw)) {
                $resolverHits += "$($file.Name):$kw"
            }
        }
    }
}
if ($resolverHits.Count -eq 0) {
    Add-Pass "S1 resolvers do not reference legacy permission keywords"
} else {
    Add-Fail ("legacy keywords in S1 resolvers: " + ($resolverHits -join ", "))
}

# --- @Aspect 与真实代理回归测试 ---
$aspectPath = Join-Path $RepoRoot "backend\DataOcean\src\main\java\com\dataocean\module\permission\s1\aspect\IamS1AuthorizationAspect.java"
$aspectText = Get-FileText $aspectPath
if ($aspectText -match '(?m)^\s*@Aspect\s*$' -and $aspectText -match '(?m)^\s*@Component\s*$') {
    Add-Pass "IamS1AuthorizationAspect declares line-level @Aspect and @Component"
} else {
    Add-Fail "IamS1AuthorizationAspect must declare both @Aspect and @Component as standalone annotations, not JavaDoc text"
}

$coveragePath = Join-Path $RepoRoot "backend\DataOcean\src\test\java\com\dataocean\module\permission\s1\coverage\IamS1EndpointCoverageTest.java"
$coverageText = Get-FileText $coveragePath
if ($coverageText -match "migratedControllersAreActuallyProxiedSoTheAnnotationsRun") {
    Add-Pass "coverage test asserts real AOP proxies"
} else {
    Add-Fail "missing migratedControllersAreActuallyProxiedSoTheAnnotationsRun"
}

$govAuth = Join-Path $RepoRoot "backend\DataOcean\src\test\java\com\dataocean\module\permission\s1\aspect\MetadataGovernanceControllerAuthorizationTest.java"
if ((Test-Path -LiteralPath $govAuth) -and (Get-FileText $govAuth) -match "AspectJProxyFactory") {
    Add-Pass "AspectJProxyFactory regression test exists"
} else {
    Add-Fail "missing AspectJProxyFactory regression test"
}

# --- 覆盖扫描仍包含全部已迁移 Controller ---
$requiredControllers = @(
    "DashboardController",
    "DatasourceAdminController",
    "MetadataCatalogController",
    "MetadataCollectionController",
    "SnapshotVersionController",
    "MetadataGovernanceController",
    "GlossaryController",
    "KnowledgeDocController",
    "PromptTemplateController",
    "UserController",
    "DepartmentController",
    "AuditLogController",
    "LineageController",
    "LineageEdgeController",
    "AlertController",
    "SystemHealthController",
    "OperationLogController",
    "AiConfigController",
    "SyncScheduleController",
    "FieldAdminController",
    "FieldTagController",
    "FieldConfidenceController",
    "FeedbackReviewController"
)
$missingControllers = @($requiredControllers | Where-Object { $coverageText -notmatch "`"$_`"" })
if ($missingControllers.Count -eq 0 -and $requiredControllers.Count -eq 23) {
    Add-Pass "coverage scan lists all 23 migrated controllers"
} else {
    Add-Fail ("coverage scan missing controllers: " + ($missingControllers -join ", "))
}

# --- B6 删除清单（按 ASCII 文件名模式查找，避免 Windows PowerShell 中文路径编码问题） ---
$b0 = Find-Doc (Join-Path $RepoRoot "docs\development") "*B0*.md"
if ($b0) {
    $b0Text = Get-FileText $b0
    if ($b0Text -match "B6" -and $b0Text -match "DatasourcePermissionController" -and $b0Text -match "sys_role_permission") {
        Add-Pass "B6 deletion inventory exists in B0 document"
    } else {
        Add-Fail "B6 deletion inventory missing from B0 document"
    }
}

$handbook = Find-Doc (Join-Path $RepoRoot "docs\development\guides") "*IAM-S1-B5*.md"
if ($handbook) {
    Add-Pass "B5 handbook exists"
    $handbookText = Get-FileText $handbook
    $requiredHandbook = @(
        "DRILL_HOST",
        "DRILL_PORT",
        "event_scheduler",
        "@@server_uuid",
        "--set-gtid-purged=OFF",
        "--hex-blob",
        "--quick",
        "--no-tablespaces",
        "--binary-mode=1",
        "backup is incomplete: Dump completed marker missing",
        "backup unexpectedly contains iam_s1 tables",
        "Length -lt 4",
        "restoring events/routines onto the same instance is forbidden"
    )
    $missingHandbook = @($requiredHandbook | Where-Object { $handbookText -notmatch [regex]::Escape($_) })
    if ($missingHandbook.Count -eq 0) {
        Add-Pass "B5 handbook requires isolated drill instance, dump safety flags, and failing backup assertions"
    } else {
        Add-Fail ("B5 handbook missing required restore-drill guards: " + ($missingHandbook -join ", "))
    }
    if ($handbookText -match 'mysql --host=\$env:DB_HOST --port=\$env:DB_PORT --user=\$env:DB_USERNAME --password --execute="CREATE DATABASE \$env:DRILL_DB') {
        Add-Fail "B5 handbook still restores the drill database onto the production MySQL instance"
    } else {
        Add-Pass "B5 handbook does not create DRILL_DB on the production host"
    }
}

# --- 待用户确认项：不从旧权限推导，也不读取密钥 ---
Write-Host ""
Write-Host "User confirmation required before real B5 (values are not read from secrets or legacy roles):"
Add-Pending "bootstrap userId (IAM_S1_BOOTSTRAP_USER_ID / iam.s1.bootstrap.user-id). Do not infer from old ADMIN, old roles, or old *"
Add-Pending "B5_EXPECTED_SHA equal to git rev-parse HEAD, with a clean working tree"
Add-Pending "confirmed enabled undeleted account for that userId"
Add-Pending "production MySQL backup directory; SHA256 is computed after dump, not supplied in advance"
Add-Pending "isolated drill MySQL instance DRILL_HOST/DRILL_PORT plus DRILL_DB; same instance as production is forbidden"
Add-Pending "maintenance window start/end"
Add-Pending "new role bindings, responsible datasources, and data grants from the handbook template"
Add-Pending "live-DB read-only SQL gate in the handbook section 4.4; run only after isolated-instance restore drill; this script does not connect"

$userId = [Environment]::GetEnvironmentVariable("IAM_S1_BOOTSTRAP_USER_ID")
if (-not [string]::IsNullOrWhiteSpace($userId)) {
    $userId = $userId.Trim()
}
if ([string]::IsNullOrWhiteSpace($userId)) {
    Add-Fail "bootstrap userId is not provided in the environment; user must confirm it before real B5"
} elseif ($userId -notmatch '^[1-9]\d*$') {
    Add-Fail "bootstrap userId must match ^[1-9]\d*$ (reject 0); do not read any secret store"
} else {
    Add-Pass "bootstrap userId is present as a positive integer id (value not printed)"
}

Write-Host ""
Write-Host "Summary"
Write-Host ("failures: " + $script:Failures.Count)
Write-Host ("pending user inputs: " + $script:PendingUserInput.Count)
if ($script:Failures.Count -gt 0) {
    Write-Host "preflight NOT ready for real B5"
    exit 1
}
Write-Host "preflight static checks passed; still do not migrate or switch in this round"
exit 0
