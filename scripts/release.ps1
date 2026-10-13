<#
.SYNOPSIS
    Checks that develop is ready, then starts the Release workflow on GitHub.

.DESCRIPTION
    1. Runs the build with lint and unit tests (`gradlew build`).
    2. Runs the device tests with the fake AnkiDroid (device-tests.ps1, on an emulator by default).
    3. Only if everything passed, and after confirmation: `gh workflow run release.yml` on the origin repository
       (it runs the device tests again on an emulator before tagging).
       By default, a release candidate: the version becomes X.Y.0-rc1 (or the next -rcN), tagged on develop and
       published as a GitHub pre-release for users to validate. With -Final, the validated candidate is released:
       the -rcN suffix is dropped, develop is merged into main, tagged vX.Y.0 and published as the latest release.

.PARAMETER Final
    Release the validated release candidate instead of publishing a new candidate.

.PARAMETER Serial
    Device for the device tests (see device-tests.ps1). An emulator is used by default.

.PARAMETER Yes
    Start the workflow without asking for confirmation.
#>
param(
    [switch]$Final,
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

$stage = if ($Final) { 'final' } else { 'rc' }
$what = if ($Final) { 'the final release of the current release candidate' } else { 'a new release candidate' }
if (-not $Yes) {
    $answer = Read-Host "Everything passed. Start the Release workflow ($what) on $repo? (y/N)"
    if ($answer -notmatch '^[yY]') { Write-Host 'No release started.'; exit 0 }
}
Write-Step "Starting the Release workflow ($what) on $repo"
gh workflow run release.yml --repo $repo --ref develop -f stage=$stage
if ($LASTEXITCODE -ne 0) { Fail 'Could not start the workflow.' }
Write-Host "Started. Follow it with: gh run watch --repo $repo" -ForegroundColor Green
