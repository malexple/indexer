# Build and publish the images mcp-sql, mcp-sql-bot and mcp-sql-web.
# The version comes from gradle.properties unless -Version is given.
# Before the first run: docker login
# Examples:
#   .\scripts\publish.ps1 -NoPush
#   .\scripts\publish.ps1
#   .\scripts\publish.ps1 -SkipTests -NoLatest
param(
    [string]$Version = "",
    [string]$Registry = "malexple",
    [switch]$SkipTests,
    [switch]$NoLatest,
    [switch]$NoPush
)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

function Get-ProjectVersion {
    $line = Get-Content "gradle.properties" | Where-Object { $_ -match '^\s*version\s*=' } | Select-Object -First 1
    if (-not $line) { throw "version is not set in gradle.properties" }
    return ($line -split '=', 2)[1].Trim()
}

function Run([string]$Command) {
    Write-Host ">> $Command" -ForegroundColor Cyan
    Invoke-Expression $Command
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $Command" }
}

if (-not $Version) { $Version = Get-ProjectVersion }
Write-Host "Version: $Version" -ForegroundColor Green

if (-not $SkipTests) { Run ".\gradlew.bat test" }
Run ".\gradlew.bat :mcp-sql-service:bootJar :mcp-sql-bot:bootJar :mcp-sql-web:bootJar"

$images = @(
    @{ Name = "mcp-sql";     Dir = "mcp-sql-service" },
    @{ Name = "mcp-sql-bot"; Dir = "mcp-sql-bot" },
    @{ Name = "mcp-sql-web"; Dir = "mcp-sql-web" }
)

foreach ($image in $images) {
    $repo = "$Registry/$($image.Name)"
    $tags = "-t ${repo}:$Version"
    if (-not $NoLatest) { $tags += " -t ${repo}:latest" }
    Run "docker build --platform linux/amd64 $tags $($image.Dir)"
}

if ($NoPush) {
    Write-Host "Images are built, push skipped (-NoPush)." -ForegroundColor Yellow
    exit 0
}

foreach ($image in $images) {
    $repo = "$Registry/$($image.Name)"
    Run "docker push ${repo}:$Version"
    if (-not $NoLatest) { Run "docker push ${repo}:latest" }
}

Write-Host "Done, version $Version. On the server set MCPSQL_TAG=$Version in .env, then run: docker compose pull; docker compose up -d" -ForegroundColor Green
