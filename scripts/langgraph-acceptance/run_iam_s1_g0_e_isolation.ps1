param(
    [string]$ReportPath = 'output/playwright/langgraph-query-memory-final-g0-20260927/g0-final.json',
    [string[]]$OnlyQuestionIds = @()
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$baseUrl = 'http://127.0.0.1:8080'
$pythonUrl = 'http://127.0.0.1:8000'
$expectedAppSchema = 'dataocean_e_acceptance_20260927'
$expectedFixtureSchema = 'langgraph_fixture_e_20260927'
$expectedFixtureUser = 'g0_reader_e27'
$expectedReaderId = 9002
$expectedDatasourceId = 1
$expectedSnapshotId = 1
$expectedBuildId = '1473e4ce-96fe-4483-82ef-2c64664bb0ab'
$forbiddenForRun = @('customers', 'customer_name', 'phone', 'campaign', 'advertisement', 'employee_pay')
$configPath = Join-Path $PSScriptRoot '.env.local'
$javaConfigPath = Join-Path $repoRoot 'backend\DataOcean\config\application-local.yml'
$verifyScript = Join-Path $PSScriptRoot 'verify_isolated_e_target.py'
$pythonExe = Join-Path $repoRoot 'python-service\.venv313\Scripts\python.exe'
$questionsPath = Join-Path $PSScriptRoot 'fixtures\g0_questions.json'
$outputPath = Join-Path $repoRoot $ReportPath
$taskResults = [System.Collections.Generic.List[object]]::new()
$taskIds = [System.Collections.Generic.List[string]]::new()
$runFailures = [System.Collections.Generic.List[string]]::new()

function Read-IgnoredEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { throw 'The local ignored acceptance config is missing.' }
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
            $key = $matches[1]
            $value = $matches[2].Trim()
            if (($value.StartsWith("'") -and $value.EndsWith("'")) -or
                ($value.StartsWith('"') -and $value.EndsWith('"'))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            $values[$key] = $value
        }
    }
    return ,$values
}

function Invoke-JsonApi([string]$Method, [string]$Path, [hashtable]$Headers, [object]$Body = $null) {
    $arguments = @{ Method = $Method; Uri = "$baseUrl$Path"; Headers = $Headers; TimeoutSec = 15 }
    if ($null -ne $Body) {
        $arguments.ContentType = 'application/json'
        $arguments.Body = ConvertTo-Json -InputObject $Body -Depth 20 -Compress
    }
    try { return Invoke-RestMethod @arguments }
    catch { throw "The isolated API request failed: $Method $Path" }
}

function Get-RowKey($Row) {
    $values = @($Row.PSObject.Properties | ForEach-Object {
        [Convert]::ToString($_.Value, [Globalization.CultureInfo]::InvariantCulture)
    })
    return (($values | Sort-Object) -join '|')
}

function Find-Forbidden([object]$Value, [string[]]$Needles) {
    $json = ConvertTo-Json -InputObject $Value -Depth 20 -Compress
    return @($Needles | Where-Object { $json.IndexOf($_, [StringComparison]::OrdinalIgnoreCase) -ge 0 } | Select-Object -Unique)
}

function Invoke-DatabaseGuard([switch]$Ledger) {
    $arguments = @($verifyScript)
    if ($Ledger) { $arguments += '--ledger' }
    $raw = & $pythonExe @arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'The read-only MySQL isolation guard failed.' }
    try { return ConvertFrom-Json -InputObject ($raw -join '') -Depth 30 }
    catch { throw 'The read-only MySQL guard returned invalid evidence.' }
}

try {
    $local = Read-IgnoredEnv $configPath
    $required = @(
        'LANGGRAPH_REVIEW_APP_SCHEMA', 'LANGGRAPH_REVIEW_FIXTURE_SCHEMA',
        'LANGGRAPH_REVIEW_MYSQL_ROOT_USER', 'LANGGRAPH_REVIEW_MYSQL_ROOT_PASSWORD',
        'LANGGRAPH_REVIEW_FIXTURE_USER', 'LANGGRAPH_REVIEW_FIXTURE_PASSWORD',
        'LANGGRAPH_ACCEPTANCE_READER_USERNAME', 'LANGGRAPH_ACCEPTANCE_READER_PASSWORD'
    )
    foreach ($key in $required) {
        if ([string]::IsNullOrWhiteSpace([string]$local[$key])) { throw "Missing local acceptance config key: $key" }
        Set-Item -Path "Env:$key" -Value ([string]$local[$key])
    }
    if ($local.LANGGRAPH_REVIEW_APP_SCHEMA -ne $expectedAppSchema -or
        $local.LANGGRAPH_REVIEW_FIXTURE_SCHEMA -ne $expectedFixtureSchema -or
        $local.LANGGRAPH_REVIEW_FIXTURE_USER -ne $expectedFixtureUser -or
        $local.LANGGRAPH_ACCEPTANCE_READER_USERNAME -ne 'langgraph_test_reader') {
        throw 'The ignored config does not identify the frozen E isolation target.'
    }
    if (-not (Test-Path -LiteralPath $javaConfigPath) -or -not (Test-Path -LiteralPath $pythonExe)) {
        throw 'The local Java target config or Python verification runtime is missing.'
    }
    if ($env:DB_HOST -ne '127.0.0.1' -or $env:DB_PORT -ne '3306' -or
        $env:DB_NAME -ne $expectedAppSchema -or
        $env:DB_USERNAME -ne $local.LANGGRAPH_REVIEW_MYSQL_ROOT_USER -or
        $env:DB_PASSWORD -ne $local.LANGGRAPH_REVIEW_MYSQL_ROOT_PASSWORD) {
        throw 'The Java service environment is not pinned to the exact V64 loopback schema.'
    }

    $targetGuard = Invoke-DatabaseGuard
    $pythonHealth = Invoke-RestMethod -Method Get -Uri "$pythonUrl/health" -TimeoutSec 10
    if (-not $pythonHealth) { throw 'The local Python service health check failed.' }

    $captcha = Invoke-JsonApi 'GET' '/api/auth/captcha' @{}
    $captchaKey = [string]$captcha.data.captchaKey
    if ([string]::IsNullOrWhiteSpace($captchaKey)) { throw 'The isolated login did not issue a captcha.' }
    $captchaCode = (& $pythonExe $verifyScript '--captcha-key' $captchaKey 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($captchaCode)) {
        throw 'The captcha could not be read from the configured local Redis DB.'
    }
    $loginBody = @{
        username = $local.LANGGRAPH_ACCEPTANCE_READER_USERNAME
        password = $local.LANGGRAPH_ACCEPTANCE_READER_PASSWORD
        captchaKey = $captchaKey
        captchaCode = $captchaCode
    }
    $login = Invoke-JsonApi 'POST' '/api/auth/login' @{} $loginBody
    $token = [string]$login.data.token
    if ([string]::IsNullOrWhiteSpace($token) -or [long]$login.data.userId -ne $expectedReaderId) {
        throw 'The isolated login did not resolve to userId 9002.'
    }
    $headers = @{ Authorization = "Bearer $token" }

    $datasource = Invoke-JsonApi 'GET' "/api/admin/datasources/$expectedDatasourceId" $headers
    if ($datasource.data.host -ne '127.0.0.1' -or [int]$datasource.data.port -ne 3306 -or
        $datasource.data.databaseName -ne $expectedFixtureSchema -or
        $datasource.data.username -ne $expectedFixtureUser) {
        throw 'Datasource 1 is not bound to the exact isolated, SELECT-only fixture account.'
    }
    $builds = Invoke-JsonApi 'GET' "/api/admin/knowledge-docs/rag-builds?datasourceId=$expectedDatasourceId" $headers
    $activeBuilds = @($builds.data | Where-Object {
        $_.status -eq 'ACTIVE' -and [long]$_.sourceSnapshotId -eq $expectedSnapshotId -and
        $_.buildId -eq $expectedBuildId
    })
    if ($activeBuilds.Count -ne 1) { throw 'The frozen G0 build is not the unique active build for snapshot 1.' }

    # The previous E negative replay persisted MASKED/NAME on region. G0's frozen
    # expected rows require the baseline NORMAL fixture, so refuse instead of
    # silently changing authorization state from the runner.
    $protections = Invoke-JsonApi 'GET' "/api/iam-s1/field-protections?datasourceId=$expectedDatasourceId&snapshotId=$expectedSnapshotId&tableName=sales_orders" $headers
    $regionRules = @($protections.data | Where-Object { $_.columnName -eq 'region' })
    if ($regionRules.Count -gt 1 -or ($regionRules.Count -eq 1 -and $regionRules[0].protectionLevel -ne 'NORMAL')) {
        throw 'The synthetic region field is not in the frozen G0 NORMAL state; runner made no authorization changes.'
    }

    $cases = Get-Content -Raw -Encoding UTF8 $questionsPath | ConvertFrom-Json
    if ($cases.Count -ne 8 -or @($cases | Where-Object answerable).Count -ne 6 -or
        @($cases | Where-Object { -not $_.answerable }).Count -ne 2) {
        throw 'The fixed G0 question fixture failed its frozen 6+2 shape guard.'
    }
    $runCases = @($cases)
    if ($OnlyQuestionIds.Count -gt 0) {
        $unknownIds = @($OnlyQuestionIds | Where-Object { $_ -notin @($cases.id) })
        if ($unknownIds.Count -gt 0) { throw 'A requested subset ID is not part of the fixed G0 question fixture.' }
        $runCases = @($cases | Where-Object { $_.id -in $OnlyQuestionIds })
        if ($runCases.Count -ne @($OnlyQuestionIds | Select-Object -Unique).Count) {
            throw 'The requested fixed-question subset is incomplete or contains duplicate IDs.'
        }
    }

    foreach ($case in $runCases) {
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $taskId = $null
        $conversationId = $null
        $task = $null
        $apiError = $null
        try {
            $submitted = Invoke-JsonApi 'POST' '/api/iam-s1/query/ask' $headers @{
                protocolVersion = 'IAM-SIMPLE-1'
                datasourceId = $expectedDatasourceId
                question = [string]$case.question
            }
            $taskId = [string]$submitted.data.taskId
            $conversationId = $submitted.data.conversationId
            if ([string]::IsNullOrWhiteSpace($taskId)) { throw 'The API did not return a task ID.' }
            $taskIds.Add($taskId)
        } catch { $apiError = $_.Exception.Message }
        if ($taskId) {
            for ($attempt = 0; $attempt -lt 100 -and $stopwatch.Elapsed.TotalSeconds -lt 90; $attempt++) {
                try {
                    $response = Invoke-JsonApi 'GET' "/api/iam-s1/query/tasks/$taskId" $headers
                    $task = $response.data
                    $apiError = $null
                    if ($task.status -ne 'PROCESSING') { break }
                } catch {
                    # An occasional GET can race the terminal write. Retry within the
                    # frozen 90-second task window and keep the final task response.
                    $apiError = $_.Exception.Message
                }
                Start-Sleep -Milliseconds 900
            }
            if ($task -and $task.status -eq 'PROCESSING' -and $stopwatch.Elapsed.TotalSeconds -ge 90) {
                $apiError = 'task remained PROCESSING after the frozen 90-second window'
            }
        }
        $stopwatch.Stop()

        $protected = if ($task) {
            [ordered]@{
                status = $task.status
                sql = $task.sql
                data = $task.data
                columns = $task.columns
                chartConfig = $task.chartConfig
                sqlExplanation = $task.sqlExplanation
                suggestedQuestions = $task.suggestedQuestions
                usedTables = $task.usedTables
                usedColumns = $task.usedColumns
                sourceTrace = $task.sourceTrace
                maskedFields = $task.maskedFields
                finalProtectionStatus = $task.finalProtectionStatus
                errorMessage = $task.errorMessage
            }
        } else { $null }
        $leaks = if ($protected) { Find-Forbidden $protected $forbiddenForRun } else { @() }
        $boundToFrozenTarget = $task -and $task.protocolVersion -eq 'IAM-SIMPLE-1' -and
            [long]$task.activeMetadataSnapshotId -eq $expectedSnapshotId -and
            [long]$task.ragSourceSnapshotId -eq $expectedSnapshotId -and $task.ragBuildId -eq $expectedBuildId
        $taskPassed = $false
        if ($task -and -not $apiError) {
            if ($case.answerable) {
                $expectedRows = @($case.expectedRows | ForEach-Object { Get-RowKey $_ } | Sort-Object)
                $actualRows = @($task.data | ForEach-Object { Get-RowKey $_ } | Sort-Object)
                $rowsMatch = (($expectedRows -join "`n") -ceq ($actualRows -join "`n"))
                $tables = @($task.usedTables | ForEach-Object { ([string]$_).ToLowerInvariant() })
                $tablesAuthorized = @($tables | Where-Object { $_ -notin @('sales_orders', 'products') }).Count -eq 0
                $taskPassed = $task.status -eq 'COMPLETED' -and $rowsMatch -and $tablesAuthorized -and $boundToFrozenTarget -and
                    $task.finalProtectionStatus -in @('FINAL_PROTECTED', 'FINAL_MASKED') -and -not $leaks
            } else {
                $dataCount = if ($null -eq $task.data) { 0 } else { @($task.data).Count }
                $tableCount = if ($null -eq $task.usedTables) { 0 } else { @($task.usedTables).Count }
                $columnCount = if ($null -eq $task.usedColumns) { 0 } else { @($task.usedColumns).Count }
                $taskPassed = $task.status -eq 'CLARIFICATION_REQUIRED' -and $boundToFrozenTarget -and -not $task.sql -and
                    $dataCount -eq 0 -and $tableCount -eq 0 -and $columnCount -eq 0 -and -not $leaks
            }
        }

        $assistantEvidence = @()
        if ($conversationId) {
            try {
                $messagesResponse = Invoke-JsonApi 'GET' "/api/iam-s1/query/conversations/$conversationId/messages?pageSize=100" $headers
                foreach ($message in @($messagesResponse.data.items | Where-Object { $_.role -eq 'assistant' })) {
                    $metadata = $message.metadata
                    if ($metadata -is [string] -and -not [string]::IsNullOrWhiteSpace($metadata)) {
                        try { $metadata = ConvertFrom-Json -InputObject $metadata -Depth 20 }
                        catch { $metadata = @{ malformedMetadata = $true } }
                    }
                    if ($metadata -and $metadata.PSObject.Properties['question']) { $metadata.PSObject.Properties.Remove('question') }
                    $safeMessage = [ordered]@{ content = $message.content; metadata = $metadata }
                    $messageLeaks = Find-Forbidden $safeMessage $forbiddenForRun
                    if ($messageLeaks.Count -gt 0) { $leaks = @($leaks + $messageLeaks | Select-Object -Unique) }
                    $assistantEvidence += $safeMessage
                }
            } catch {
                $runFailures.Add("$($case.id): assistant history evidence request failed")
            }
        }
        if ($assistantEvidence.Count -eq 0) {
            $taskPassed = $false
            $runFailures.Add("$($case.id): assistant history evidence is missing")
        }

        $taskResults.Add([pscustomobject]@{
            id = $case.id
            answerable = [bool]$case.answerable
            question = [string]$case.question
            passed = $taskPassed
            taskId = $taskId
            conversationId = $conversationId
            status = if ($task) { $task.status } else { $null }
            apiElapsedMs = [long]$stopwatch.ElapsedMilliseconds
            serverTotalTimeMs = if ($task) { $task.totalTimeMs } else { $null }
            expectedRows = if ($case.answerable) { $case.expectedRows } else { $null }
            protectedResult = $protected
            assistantHistory = $assistantEvidence
            leakedIdentifiers = @($leaks)
            apiError = $apiError
            budgetGate = $null
            sqlAttempts = @()
            modelCalls = @()
        })
        if (-not $taskPassed) { $runFailures.Add("$($case.id): answer/status/protection gate failed") }
    }

    $ledger = $null
    if ($taskIds.Count -eq $runCases.Count) {
        $env:LANGGRAPH_ACCEPTANCE_TASK_IDS_JSON = ConvertTo-Json -InputObject @($taskIds) -Compress
        $ledger = Invoke-DatabaseGuard -Ledger
        foreach ($result in $taskResults) {
            $taskBudget = @($ledger.budgetLedger.tasks | Where-Object { $_.task_id -eq $result.taskId })
            $attempts = @($ledger.budgetLedger.sqlAttempts | Where-Object { $_.task_id -eq $result.taskId })
            $calls = @($ledger.budgetLedger.modelCalls | Where-Object { $_.task_id -eq $result.taskId })
            $result.sqlAttempts = $attempts
            $result.modelCalls = $calls
            if ($taskBudget.Count -ne 1) {
                $result.budgetGate = @{ passed = $false; error = 'task budget row is missing or duplicated' }
                $runFailures.Add("$($result.id): durable budget evidence missing")
                continue
            }
            $budget = $taskBudget[0]
            $cost = [decimal]$budget.estimated_ai_cost_cny
            $duration = [long]$result.apiElapsedMs
            $serverDuration = if ($null -ne $budget.total_time_ms) { [long]$budget.total_time_ms } else { $null }
            $sqlCalls = $attempts.Count
            $llmCalls = [int]$budget.llm_call_count
            $embeddingCalls = [int]$budget.embedding_call_count
            $budgetFailures = @()
            if ([long]$budget.user_id -ne $expectedReaderId -or
                [long]$budget.datasource_id -ne $expectedDatasourceId -or
                $budget.rag_build_id -ne $expectedBuildId -or
                [long]$budget.active_metadata_snapshot_id -ne $expectedSnapshotId) {
                $budgetFailures += 'taskTargetIdentityMismatch'
            }
            if ($sqlCalls -gt 3) { $budgetFailures += 'sqlAttempts>3' }
            if ($llmCalls -gt 8) { $budgetFailures += 'llmCalls>8' }
            if ($embeddingCalls -gt 2) { $budgetFailures += 'embeddingCalls>2' }
            if ($duration -gt 90000 -or ($null -ne $serverDuration -and $serverDuration -gt 90000)) { $budgetFailures += 'duration>90000ms' }
            if ($cost -gt [decimal]0.10) { $budgetFailures += 'estimatedCostCny>0.10' }
            if (@($calls | Where-Object { $_.status -ne 'COMPLETED' }).Count -gt 0) { $budgetFailures += 'modelCallNotCompleted' }
            if ($attempts.Count -gt 0) {
                $attemptSqlLeaks = Find-Forbidden @($attempts | ForEach-Object { $_.safe_sql }) @($forbiddenForRun)
                if ($attemptSqlLeaks.Count -gt 0) {
                    $result.leakedIdentifiers = @($result.leakedIdentifiers + $attemptSqlLeaks | Select-Object -Unique)
                    $budgetFailures += 'restrictedIdentifierInSqlAttempt'
                }
            }
            $result.budgetGate = @{
                passed = ($budgetFailures.Count -eq 0)
                sqlAttempts = $sqlCalls
                llmCalls = $llmCalls
                embeddingCalls = $embeddingCalls
                apiElapsedMs = $duration
                serverTotalTimeMs = $serverDuration
                estimatedCostCny = [string]$cost
                failures = $budgetFailures
            }
            if ($budgetFailures.Count -gt 0) { $runFailures.Add("$($result.id): $($budgetFailures -join ', ')") }
        }
    } else {
        $runFailures.Add('The API run did not produce all eight task IDs; durable ledger was not queried.')
    }

    $answerableResults = @($taskResults | Where-Object answerable)
    $refusalResults = @($taskResults | Where-Object { -not $_.answerable })
    $summary = [ordered]@{
        runDate = '2026-09-27'
        route = 'IAM-SIMPLE-1 public API'
        runSet = if ($OnlyQuestionIds.Count -gt 0) { 'fixed G0 targeted subset' } else { 'fixed G0 full set' }
        fixedQuestionIds = @($runCases | ForEach-Object { $_.id })
        target = $targetGuard.targetGuards
        datasourceId = $expectedDatasourceId
        snapshotId = $expectedSnapshotId
        activeBuildId = $expectedBuildId
        readerUserId = $expectedReaderId
        caseCount = $taskResults.Count
        answerablePassed = @($answerableResults | Where-Object passed).Count
        answerableTotal = $answerableResults.Count
        correctRefusals = @($refusalResults | Where-Object passed).Count
        refusalTotal = $refusalResults.Count
        securityViolations = @($taskResults | Where-Object { $_.leakedIdentifiers.Count -gt 0 }).Count
        failures = @($runFailures)
        budgetLimits = @{ sqlAttemptsPerQuestion = 3; llmCallsPerQuestion = 8; embeddingCallsPerQuestion = 2; durationMsPerQuestion = 90000; estimatedCostCnyPerQuestion = '0.10' }
        budgetLedger = if ($ledger) { $ledger.budgetLedger } else { $null }
        questions = @($taskResults)
    }
    $directory = Split-Path -Parent $outputPath
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $summary | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $outputPath -Encoding UTF8
    [pscustomobject]@{
        runDate = $summary.runDate
        caseCount = $summary.caseCount
        answerablePassed = $summary.answerablePassed
        answerableTotal = $summary.answerableTotal
        correctRefusals = $summary.correctRefusals
        refusalTotal = $summary.refusalTotal
        securityViolations = $summary.securityViolations
        failures = @($summary.failures)
    } | ConvertTo-Json -Depth 8
    Write-Output "Evidence: $outputPath"
    $expectedAnswerableCount = @($runCases | Where-Object answerable).Count
    $expectedRefusalCount = @($runCases | Where-Object { -not $_.answerable }).Count
    if ($runFailures.Count -gt 0 -or $taskResults.Count -ne $runCases.Count -or
        @($answerableResults | Where-Object passed).Count -ne $expectedAnswerableCount -or
        @($refusalResults | Where-Object passed).Count -ne $expectedRefusalCount -or
        @($taskResults | Where-Object { -not $_.budgetGate -or -not $_.budgetGate.passed }).Count -gt 0) {
        exit 1
    }
    exit 0
} catch {
    $runFailures.Add($_.Exception.Message)
    $failureReport = [ordered]@{
        runDate = '2026-09-27'
        target = @{ applicationSchema = $expectedAppSchema; fixtureSchema = $expectedFixtureSchema; userId = $expectedReaderId; datasourceId = $expectedDatasourceId; activeBuildId = $expectedBuildId }
        failures = @($runFailures)
        questions = @($taskResults)
    }
    $directory = Split-Path -Parent $outputPath
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $failureReport | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $outputPath -Encoding UTF8
    Write-Error $_.Exception.Message
    exit 1
}
