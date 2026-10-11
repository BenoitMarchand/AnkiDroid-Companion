<#
.SYNOPSIS
    Builds the debug APK, installs it on a connected Android device and launches the app.

.DESCRIPTION
    1. Locates the Android SDK (adb) and a JDK 17 or newer (required by Gradle 9 / AGP 9).
    2. Detects connected devices (USB or Wi-Fi debugging) and lets you pick one if several are connected.
    3. Runs `gradlew installDebug` on that device, then starts MainActivity.

.PARAMETER Serial
    Device serial to use (see `adb devices`). Skips the device prompt.

.PARAMETER Wait
    Wait for a device to be connected instead of failing when none is found.

.PARAMETER NoLaunch
    Install only; don't start the app afterwards.

.PARAMETER Logcat
    After launching, stream the app's logs (tags CardWallpaper, Notifications, BackgroundService).

.EXAMPLE
    .\scripts\install.ps1
    .\scripts\install.ps1 -Wait -Logcat
#>
param(
    [string]$Serial,
    [switch]$Wait,
    [switch]$NoLaunch,
    [switch]$Logcat
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AppId = 'com.ankidroid.companion'
$LogTags = @('CardWallpaper', 'Notifications', 'BackgroundService')

function Write-Step([string]$Message) { Write-Host "==> $Message" -ForegroundColor Cyan }
function Fail([string]$Message) { Write-Host "ERROR: $Message" -ForegroundColor Red; exit 1 }

# --- Android SDK -------------------------------------------------------------

function Find-AndroidSdk {
    $candidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    $localProps = Join-Path $ProjectRoot 'local.properties'
    if (Test-Path $localProps) {
        $line = Select-String -Path $localProps -Pattern '^sdk\.dir=(.+)$' | Select-Object -First 1
        if ($line) { $candidates += ($line.Matches[0].Groups[1].Value -replace '\\\\', '\' -replace '\\:', ':') }
    }
    $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    foreach ($dir in $candidates) {
        if ($dir -and (Test-Path (Join-Path $dir 'platform-tools\adb.exe'))) { return $dir }
    }
    return $null
}

# --- JDK ---------------------------------------------------------------------

function Get-JavaMajorVersion([string]$JavaHome) {
    $java = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path $java)) { return $null }
    $release = Join-Path $JavaHome 'release'
    if (Test-Path $release) {
        $m = Select-String -Path $release -Pattern '^JAVA_VERSION="(\d+)(\.(\d+))?' | Select-Object -First 1
        if ($m) {
            $major = [int]$m.Matches[0].Groups[1].Value
            if ($major -eq 1) { $major = [int]$m.Matches[0].Groups[3].Value }
            return $major
        }
    }
    return $null
}

function Find-Jdk {
    # Gradle 9 and AGP 9 need JDK 17+. JDK_HOME / JAVA_HOME win when valid, otherwise the newest JDK found.
    $roots = @(
        (Join-Path $env:USERPROFILE '.jdks'),            # JDKs downloaded by Android Studio / IntelliJ
        (Join-Path $env:USERPROFILE '.gradle\jdks'),
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Microsoft",
        "$env:ProgramFiles\Zulu",
        "$env:ProgramFiles\Amazon Corretto"
    )
    foreach ($preferred in @($env:JDK_HOME, $env:JAVA_HOME)) {
        if ($preferred -and (Get-JavaMajorVersion $preferred) -ge 17) {
            return [pscustomobject]@{ Path = $preferred; Major = (Get-JavaMajorVersion $preferred) }
        }
    }
    $candidates = @()
    foreach ($root in $roots) {
        if (Test-Path $root) { $candidates += (Get-ChildItem $root -Directory | ForEach-Object FullName) }
    }
    $candidates += (Get-ChildItem "$env:ProgramFiles\Android" -Directory -Filter 'Android Studio*' -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'jbr' })

    $found = foreach ($dir in ($candidates | Where-Object { $_ } | Select-Object -Unique)) {
        $major = Get-JavaMajorVersion $dir
        if ($major -ge 17) { [pscustomobject]@{ Path = $dir; Major = $major } }
    }
    return $found | Sort-Object Major -Descending | Select-Object -First 1
}

# --- Devices -----------------------------------------------------------------

function Get-Devices([string]$Adb) {
    & $Adb devices -l | Select-Object -Skip 1 | Where-Object { $_.Trim() } | ForEach-Object {
        $parts = $_ -split '\s+'
        $model = ($parts | Where-Object { $_ -like 'model:*' }) -replace '^model:', ''
        [pscustomobject]@{ Serial = $parts[0]; State = $parts[1]; Model = $model }
    }
}

function Select-Device([string]$Adb) {
    while ($true) {
        $all = @(Get-Devices $Adb)
        foreach ($d in $all | Where-Object State -eq 'unauthorized') {
            Write-Host "Device $($d.Serial) is unauthorized: unlock it and accept the 'Allow USB debugging' prompt." -ForegroundColor Yellow
        }
        foreach ($d in $all | Where-Object State -eq 'offline') {
            Write-Host "Device $($d.Serial) is offline: reconnect the cable or toggle USB debugging." -ForegroundColor Yellow
        }
        $ready = @($all | Where-Object State -eq 'device')

        if ($Serial) {
            $match = $ready | Where-Object Serial -eq $Serial
            if ($match) { return $match }
            if (-not $Wait) { Fail "Device '$Serial' is not connected or not ready." }
        } elseif ($ready.Count -eq 1) {
            return $ready[0]
        } elseif ($ready.Count -gt 1) {
            Write-Host 'Several devices are connected:'
            for ($i = 0; $i -lt $ready.Count; $i++) {
                Write-Host ("  [{0}] {1}  {2}" -f ($i + 1), $ready[$i].Serial, $ready[$i].Model)
            }
            $choice = Read-Host "Pick a device (1-$($ready.Count))"
            $index = 0
            if ([int]::TryParse($choice, [ref]$index) -and $index -ge 1 -and $index -le $ready.Count) {
                return $ready[$index - 1]
            }
            Fail 'Invalid choice.'
        } elseif (-not $Wait) {
            Fail ("No device found. Connect your phone with USB debugging enabled " +
                  "(Settings > About phone > tap 'Build number' 7 times, then Developer options > USB debugging), " +
                  "or run again with -Wait.")
        }

        Write-Host 'Waiting for a device... (Ctrl+C to cancel)'
        & $Adb wait-for-device
        Start-Sleep -Seconds 1
    }
}

# --- Main --------------------------------------------------------------------

Write-Step 'Locating Android SDK'
$sdk = Find-AndroidSdk
if (-not $sdk) { Fail 'Android SDK not found. Install it with Android Studio, or set ANDROID_HOME.' }
$env:ANDROID_HOME = $sdk
$adb = Join-Path $sdk 'platform-tools\adb.exe'
Write-Host "    $sdk"

Write-Step 'Locating JDK'
$jdk = Find-Jdk
if (-not $jdk) {
    Fail ("No JDK 17+ found. Install Android Studio (it bundles one), or download one from Android Studio: " +
          "Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK > Download JDK. Or set JDK_HOME.")
}
$env:JAVA_HOME = $jdk.Path
Write-Host "    $($jdk.Path) (Java $($jdk.Major))"

Write-Step 'Detecting devices'
& $adb start-server | Out-Null
$device = Select-Device $adb
Write-Host "    $($device.Serial)  $($device.Model)"

Write-Step 'Building and installing the debug APK'
$env:ANDROID_SERIAL = $device.Serial
Push-Location $ProjectRoot
try {
    & .\gradlew.bat installDebug --console=plain
    if ($LASTEXITCODE -ne 0) { Fail "Gradle failed (exit code $LASTEXITCODE)." }
} finally {
    Pop-Location
}

if (-not $NoLaunch) {
    Write-Step 'Launching the app'
    & $adb -s $device.Serial shell am start -n "$AppId/.MainActivity" | Out-Null
}

Write-Host 'Done.' -ForegroundColor Green

if ($Logcat) {
    Write-Step "Streaming logs ($($LogTags -join ', ')) - Ctrl+C to stop"
    & $adb -s $device.Serial logcat -c
    & $adb -s $device.Serial logcat -s @($LogTags)
}
