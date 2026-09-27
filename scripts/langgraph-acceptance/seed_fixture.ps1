param(
    [string]$ContainerName = "dataocean-langgraph-acceptance-mysql"
)

$ErrorActionPreference = "Stop"
$expectedLabel = docker inspect $ContainerName --format '{{ index .Config.Labels "dataocean.test-scope" }}'
if ($LASTEXITCODE -ne 0 -or $expectedLabel -ne "langgraph-acceptance") {
    throw "Refusing to seed: $ContainerName is not labeled as the dedicated LangGraph acceptance container."
}

$publishedPorts = docker port $ContainerName 3306/tcp
if ($LASTEXITCODE -ne 0 -or $publishedPorts -notmatch '^127\.0\.0\.1:13316$') {
    throw "Refusing to seed: the acceptance MySQL port must be bound only to 127.0.0.1:13316."
}

$fixturePath = Join-Path $PSScriptRoot "fixtures\g0_schema.sql"
docker cp $fixturePath "${ContainerName}:/tmp/g0_schema.sql"
if ($LASTEXITCODE -ne 0) { throw "Could not copy the test fixture SQL." }

docker exec $ContainerName sh -lc 'mysqladmin -uroot -p"$MYSQL_ROOT_PASSWORD" ping --silent'
if ($LASTEXITCODE -ne 0) { throw "The dedicated acceptance MySQL is not ready." }

docker exec $ContainerName sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "CREATE DATABASE IF NOT EXISTS langgraph_fixture"'
if ($LASTEXITCODE -ne 0) { throw "Could not prepare the test-only database." }

docker exec $ContainerName sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" langgraph_fixture < /tmp/g0_schema.sql'
if ($LASTEXITCODE -ne 0) { throw "Could not load the isolated fixture." }

Write-Host "Seeded only langgraph_fixture inside the labeled acceptance container."
