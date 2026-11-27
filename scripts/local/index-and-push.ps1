# Indexes a project and uploads the result to the local mcp-sql service.
# First run (or -Full): full index, then the whole database is uploaded.
# Next runs: incremental index, then only the patch is uploaded.
# Examples:
#   .\scripts\local\index-and-push.ps1 -Workspace myproj -Root D:\work\myproj
#   .\scripts\local\index-and-push.ps1 -Workspace myproj -Root D:\work\myproj -Full
param(
    [Parameter(Mandatory = $true)][string]$Workspace,
    [string]$Root = ".",
    [string]$Jar = "D:\tools\Indexer.jar",
    [string]$Server = "http://localhost:8080",
    [string]$WriteKey = "local-write",
    [switch]$Full
)

$ErrorActionPreference = "Stop"
$env:MCPSQL_WRITE_KEY = $WriteKey

$Root = (Resolve-Path $Root).Path
$db = Join-Path $Root ".index\index.db"
$patches = Join-Path $Root ".index\patches"

function Run([string[]]$Arguments) {
    Write-Host ">> java $($Arguments -join ' ')" -ForegroundColor Cyan
    & java @Arguments
    if ($LASTEXITCODE -ne 0) { throw "java failed with exit code $LASTEXITCODE" }
}

if ($Full -or -not (Test-Path $db)) {
    Run @("-jar", $Jar, "-r", $Root, "-l=java", "--stats")
    Run @("-jar", $Jar, "push", "--server", $Server, "--workspace", $Workspace, "--db", $db)
    Write-Host "Full database uploaded." -ForegroundColor Green
    exit 0
}

if (Test-Path $patches) { Remove-Item -Recurse -Force $patches }
Run @("-jar", $Jar, "-r", $Root, "-l=java", "--incremental", "--patch-dir", $patches, "--stats")

$files = @()
if (Test-Path $patches) { $files = @(Get-ChildItem $patches -Filter "patch-*.sql.gz") }
if ($files.Count -eq 0) {
    Write-Host "No patch was written: either nothing changed, or the indexer did a full run (see mode=full above)." -ForegroundColor Yellow
    Write-Host "If it was a full run, upload the database: add -Full to this command." -ForegroundColor Yellow
    exit 0
}

Run @("-jar", $Jar, "push", "--server", $Server, "--workspace", $Workspace, "--patch-dir", $patches)
Write-Host "Patch uploaded." -ForegroundColor Green
