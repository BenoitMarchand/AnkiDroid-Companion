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

.PARAMETER Probe
    After installing, run AnkiDroidProbeTest on the device (read-only measurements of AnkiDroid's API, issue #21)
    and print its results. AnkiDroid is first stopped if it's in the background, to measure a cold start.
    The app is installed with `adb install -r` so its data is kept (Gradle's connectedAndroidTest uninstalls it).

.EXAMPLE
    .\scripts\install.ps1
    .\scripts\install.ps1 -Wait -Logcat
    .\scripts\install.ps1 -Probe
#>
param(
    [string]$Serial,
    [switch]$Wait,
    [switch]$NoLaunch,
    [switch]$Logcat,
    [switch]$Probe
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AppId = 'com.ankidroid.companion'
$LogTags = @('CardWallpaper', 'Notifications', 'BackgroundService')

# Shared helpers: Write-Step, Fail, Find-AndroidSdk, Find-Jdk, Get-Devices.
. (Join-Path $PSScriptRoot 'android-env.ps1')

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

$adb = Initialize-AndroidEnv

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

if ($Probe) {
    Write-Step 'Running the AnkiDroid probe'
    Push-Location $ProjectRoot
    try {
        & .\gradlew.bat assembleDebugAndroidTest --console=plain -q
        if ($LASTEXITCODE -ne 0) { Fail "Gradle failed (exit code $LASTEXITCODE)." }
    } finally {
        Pop-Location
    }
    $testApk = Join-Path $ProjectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    & $adb -s $device.Serial install -r -t $testApk | Out-Null
    # "am kill" only stops AnkiDroid if it's in the background: work in progress on screen is never lost.
    & $adb -s $device.Serial shell am kill com.ichi2.anki
    & $adb -s $device.Serial logcat -c
    & $adb -s $device.Serial shell am instrument -w -e class "$AppId.AnkiDroidProbeTest" "$AppId.test/androidx.test.runner.AndroidJUnitRunner"
    & $adb -s $device.Serial logcat -d -s AnkiProbe:I
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
