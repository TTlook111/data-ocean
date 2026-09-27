param(
    [Parameter(Mandatory = $false)]
    [string]$ReaderPassword = $env:LANGGRAPH_ACCEPTANCE_READER_PASSWORD
)

$ErrorActionPreference = 'Stop'
$baseUrl = 'http://127.0.0.1:18080'
$redisContainer = 'dataocean-langgraph-acceptance-redis8'
$mysqlContainer = 'dataocean-langgraph-acceptance-mysql'
$milvusContainer = 'dataocean-langgraph-acceptance-milvus2'

if ([string]::IsNullOrWhiteSpace($ReaderPassword)) {
    throw 'Set LANGGRAPH_ACCEPTANCE_READER_PASSWORD to the disposable reader account password.'
}

function Assert-TestContainer([string]$Name, [string]$Port, [string]$ContainerPort) {
    $label = docker inspect -f '{{index .Config.Labels "dataocean.test-scope"}}' $Name
    $hostPort = docker inspect -f "{{(index (index .NetworkSettings.Ports `"$ContainerPort/tcp`") 0).HostPort}}" $Name
    if ($LASTEXITCODE -ne 0 -or $label -ne 'langgraph-acceptance' -or $hostPort -ne $Port) {
        throw "Refusing acceptance run: $Name is not the labeled loopback fixture container on port $Port."
    }
}

Assert-TestContainer $mysqlContainer '13316' '3306'
Assert-TestContainer $redisContainer '16379' '6379'
Assert-TestContainer $milvusContainer '19531' '19530'

$captchaResponse = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/auth/captcha"
$captchaKey = [string]$captchaResponse.data.captchaKey
if ([string]::IsNullOrWhiteSpace($captchaKey)) { throw 'The test login returned no captcha key.' }
$captchaCode = docker exec $redisContainer redis-cli --raw GET "captcha:$captchaKey"
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($captchaCode)) {
    throw 'The disposable captcha could not be read from the dedicated Redis 8 container.'
}

$loginBody = @{
    username = 'langgraph_test_reader'
    password = $ReaderPassword
    captchaKey = $captchaKey
    captchaCode = ($captchaCode | Out-String).Trim()
} | ConvertTo-Json -Compress
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType 'application/json' -Body $loginBody
$token = [string]$login.data.token
if ([string]::IsNullOrWhiteSpace($token) -or $login.data.userId -ne 9002) {
    throw 'Acceptance login did not resolve to the disposable reader account.'
}
$headers = @{ Authorization = "Bearer $token" }

$datasource = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/admin/datasources/1" -Headers $headers
if ($datasource.data.host -ne '127.0.0.1' -or $datasource.data.port -ne 13316 -or
    $datasource.data.databaseName -ne 'langgraph_fixture') {
    throw 'Refusing acceptance run: datasource 1 is not the dedicated loopback fixture.'
}
$builds = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/admin/knowledge-docs/rag-builds?datasourceId=1" -Headers $headers
$activeBuild = @($builds.data | Where-Object { $_.status -eq 'ACTIVE' -and $_.sourceSnapshotId -eq 1 })[0]
if (-not $activeBuild) { throw 'No active RAG build is bound to fixture snapshot 1.' }

$fixturePath = Join-Path $PSScriptRoot 'fixtures/g0_questions.json'
$cases = Get-Content -Raw -Encoding UTF8 $fixturePath | ConvertFrom-Json
$results = @()

function Get-RowKey($Row) {
    $values = @($Row.PSObject.Properties | ForEach-Object {
        [Convert]::ToString($_.Value, [Globalization.CultureInfo]::InvariantCulture)
    })
    return (($values | Sort-Object) -join '|')
}

foreach ($case in $cases) {
    $questionJson = ConvertTo-Json -InputObject $case.question -Compress
    $body = "{`"protocolVersion`":`"IAM-SIMPLE-1`",`"datasourceId`":1,`"question`":$questionJson}"
    $submitted = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/iam-s1/query/ask" `
        -Headers $headers -ContentType 'application/json' -Body $body
    $taskId = [string]$submitted.data.taskId
    $task = $null
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        $response = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/iam-s1/query/tasks/$taskId" -Headers $headers
        $task = $response.data
        if ($task.status -ne 'PROCESSING') { break }
        Start-Sleep -Milliseconds 1000
    }

    $usedTables = @($task.usedTables | ForEach-Object { ([string]$_).ToLowerInvariant() })
        $sqlAndResources = (@($task.sql, $task.usedTables, $task.usedColumns, $task.errorMessage) | ConvertTo-Json -Depth 6 -Compress).ToLowerInvariant()
    if ($case.answerable) {
        $expected = @($case.expectedRows | ForEach-Object { Get-RowKey $_ } | Sort-Object)
        $actual = @($task.data | ForEach-Object { Get-RowKey $_ } | Sort-Object)
        $sameRows = (($expected -join "`n") -ceq ($actual -join "`n"))
        $authorizedTables = @($usedTables | Where-Object { $_ -notin @('sales_orders', 'products') }).Count -eq 0
        $passed = $task.status -eq 'COMPLETED' -and $sameRows -and $authorizedTables `
            -and $task.finalProtectionStatus -in @('FINAL_PROTECTED', 'FINAL_MASKED')
        $results += [pscustomobject]@{
            id = $case.id
            answerable = $true
            passed = $passed
            taskId = $taskId
            conversationId = $submitted.data.conversationId
            status = $task.status
            expectedRows = $expected
            actualRows = $actual
            usedTables = $usedTables
            usedColumns = $task.usedColumns
            finalProtectionStatus = $task.finalProtectionStatus
            chartConfigPresent = [bool]$task.chartConfig
            errorMessage = $task.errorMessage
        }
    } else {
        $forbidden = @($case.mustNotReference | Where-Object { $sqlAndResources.Contains(([string]$_).ToLowerInvariant()) })
        $dataCount = 0
        if ($null -ne $task.data) { $dataCount = @($task.data).Count }
        $usedTableCount = 0
        if ($null -ne $task.usedTables) { $usedTableCount = @($task.usedTables).Count }
        $passed = $task.status -eq 'CLARIFICATION_REQUIRED' -and -not $task.sql `
            -and $dataCount -eq 0 -and $usedTableCount -eq 0 -and -not $forbidden
        $results += [pscustomobject]@{
            id = $case.id
            answerable = $false
            passed = $passed
            taskId = $taskId
            conversationId = $submitted.data.conversationId
            status = $task.status
            sql = $task.sql
            usedTables = $task.usedTables
            usedColumns = $task.usedColumns
            forbiddenReferences = $forbidden
            errorMessage = $task.errorMessage
        }
    }
}

$answerableResults = @($results | Where-Object { $_.answerable })
$refusalResults = @($results | Where-Object { -not $_.answerable })
$summary = [pscustomobject]@{
    datasourceId = 1
    snapshotId = 1
    ragBuildId = $activeBuild.buildId
    readerUserId = 9002
    caseCount = $results.Count
    answerablePassed = @($answerableResults | Where-Object passed).Count
    answerableTotal = $answerableResults.Count
    refusalsPassed = @($refusalResults | Where-Object passed).Count
    refusalsTotal = $refusalResults.Count
    securityFailures = @($results | Where-Object { -not $_.passed }).Count
    results = $results
}
$summary | ConvertTo-Json -Depth 10 -Compress
if ($summary.securityFailures -ne 0) { exit 1 }
exit 0
