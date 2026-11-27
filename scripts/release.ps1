# Builds the release files (indexer jar, mcp-sql jar, zip, checksums) and, with -Publish,
# creates the git tag and the GitHub release with these files.
# The version comes from gradle.properties.
# Examples:
#   .\scripts\release.ps1                       (build only, files in build\release)
#   .\scripts\release.ps1 -SkipTests
#   .\scripts\release.ps1 -Publish              (tag, push the tag, gh release create)
param(
    [string]$Notes = "RELEASE_NOTES.md",
    [switch]$SkipTests,
    [switch]$Publish
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

$version = Get-ProjectVersion
Write-Host "Release version: $version" -ForegroundColor Green

$gradleArgs = "clean release"
if ($SkipTests) { $gradleArgs += " -x test" }
Run ".\gradlew.bat $gradleArgs"

$dir = "build\release"
Get-ChildItem $dir -File | ForEach-Object {
    Write-Host ("  {0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB))
}

if (-not $Publish) {
    Write-Host "Build only. To tag and publish on GitHub run again with -Publish." -ForegroundColor Yellow
    exit 0
}

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "GitHub CLI (gh) is not installed" }
$tag = "v$version"
if (git tag --list $tag) { throw "Tag $tag already exists" }

Run "git tag -a $tag -m 'Release $version'"
Run "git push origin $tag"

$files = (Get-ChildItem $dir -File | ForEach-Object { '"' + $_.FullName + '"' }) -join ' '
if (Test-Path $Notes) {
    Run "gh release create $tag $files --title 'v$version' --notes-file `"$Notes`""
} else {
    Run "gh release create $tag $files --title 'v$version' --generate-notes"
}
Write-Host "Release $tag is published." -ForegroundColor Green
