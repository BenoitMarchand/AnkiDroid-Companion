<#
.SYNOPSIS
    Checks that develop is ready, then starts the Release workflow on GitHub.

.DESCRIPTION
    1. Runs the build with lint and unit tests (`gradlew build`).
    2. Runs the device tests with the fake AnkiDroid (device-tests.ps1, on an emulator by default).
    3. Only if everything passed, and after confirmation: `gh workflow run release.yml` on the origin repository.
       The workflow increases the minor version, merges develop into main, tags vX.Y.0 and publishes the APK
       (it runs the device tests again on an emulator before tagging).

.PARAMETER Serial
    Device for the device tests (see device-tests.ps1). An emulator is used by default.

.PARAMETER Yes
    Start the workflow without asking for confirmation.
#>
param(
    [string]$Serial,
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot

. (Join-Path $PSScriptRoot 'android-env.ps1')

$branch = git -C $ProjectRoot rev-parse --abbrev-ref HEAD
if ($branch -ne 'develop') { Fail "Releases are made from develop (current branch: $branch)." }
git -C $ProjectRoot fetch --quiet origin develop
if ((git -C $ProjectRoot rev-parse HEAD) -ne (git -C $ProjectRoot rev-parse origin/develop)) {
    Fail 'The local develop differs from origin/develop: pull or push first. The release is built from origin/develop.'
}

$null = Initialize-AndroidEnv

Write-Step 'Build, lint and unit tests'
Push-Location $ProjectRoot
try {
    & .\gradlew.bat build --console=plain -q
    if ($LASTEXITCODE -ne 0) { Fail "Build or unit tests failed (exit code $LASTEXITCODE)." }
} finally {
    Pop-Location
}

Write-Step 'Device tests'
$deviceArgs = @{}
if ($Serial) { $deviceArgs.Serial = $Serial }
& (Join-Path $PSScriptRoot 'device-tests.ps1') @deviceArgs
if ($LASTEXITCODE -ne 0) { Fail 'Device tests failed: no release.' }

# The repository of the origin remote, as owner/name.
$origin = git -C $ProjectRoot remote get-url origin
$repo = $origin -replace '^.*github\.com[:/]', '' -replace '\.git$', ''

if (-not $Yes) {
    $answer = Read-Host "Everything passed. Start the Release workflow on $repo? (y/N)"
    if ($answer -notmatch '^[yY]') { Write-Host 'No release started.'; exit 0 }
}
Write-Step "Starting the Release workflow on $repo"
gh workflow run release.yml --repo $repo --ref develop
if ($LASTEXITCODE -ne 0) { Fail 'Could not start the workflow.' }
Write-Host "Started. Follow it with: gh run watch --repo $repo" -ForegroundColor Green
