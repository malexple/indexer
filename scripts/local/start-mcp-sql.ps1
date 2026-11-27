# Starts mcp-sql-service on this computer only (127.0.0.1), in single-user mode.
# Build the jar first:  .\gradlew.bat :mcp-sql-service:bootJar
# Then copy mcp-sql-service\build\libs\app.jar to the path below (or pass -Jar).
param(
    [string]$Jar = "D:\tools\mcp-sql\app.jar",
    [string]$DataDir = "D:\tools\mcp-sql\data",
    [string]$ReadKey = "local-read",
    [string]$WriteKey = "local-write",
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Jar)) {
    throw "Jar not found: $Jar. Build it with: .\gradlew.bat :mcp-sql-service:bootJar"
}
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null

$env:MCPSQL_DATA_DIR = $DataDir
$env:MCPSQL_READ_KEY = $ReadKey
$env:MCPSQL_WRITE_KEY = $WriteKey
$env:MCPSQL_MULTI_ENABLED = "false"

Write-Host "mcp-sql: http://127.0.0.1:$Port/mcp  read key: $ReadKey  data: $DataDir" -ForegroundColor Green
Write-Host "Stop with Ctrl+C." -ForegroundColor Green
java -jar $Jar --server.address=127.0.0.1 --server.port=$Port
