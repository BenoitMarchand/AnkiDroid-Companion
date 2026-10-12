<#
.SYNOPSIS
    Runs the device tests (app/src/androidTest) with a fake AnkiDroid, on an emulator by default.

.DESCRIPTION
    1. Locates the Android SDK and a JDK 17+ (see android-env.ps1).
    2. Picks the device: -Serial if given, otherwise a running emulator, otherwise starts one (-Avd, or the
       first emulator listed by `emulator -list-avds`). A phone is only used when its serial is given.
    3. Builds and installs the app and the test APK with `adb install -r` (the app's data is kept), then runs
       the tests with `am instrument`. AnkiDroidProbeTest (measurements on the real AnkiDroid) is left out.
    4. Exits with 0 if every test passed, 1 otherwise. An emulator started by the script is shut down.

    Running instrumented tests force-stops the app, and Android then resets the lock screen card wallpaper:
    that's why an emulator is used by default. The tests save and restore the app's data.

.PARAMETER Serial
    Device to use (see `adb devices`). For a phone, the card wallpaper has to be selected again afterwards.

.PARAMETER Avd
    Emulator to start when none is running (see `emulator -list-avds`).

.PARAMETER KeepEmulator
    Leave the emulator started by the script running at the end.

.EXAMPLE
    .\scripts\device-tests.ps1
    .\scripts\device-tests.ps1 -Avd Pixel_9a -KeepEmulator
    .\scripts\device-tests.ps1 -Serial 0123456789ABCDEF
#>
param(
    [string]$Serial,
    [string]$Avd,
    [switch]$KeepEmulator
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AppId = 'com.ankidroid.companion'
$BootTimeoutSeconds = 300

. (Join-Path $PSScriptRoot 'android-env.ps1')

function Wait-ForBoot([string]$Adb, [string]$DeviceSerial) {
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    & $Adb -s $DeviceSerial wait-for-device
    while ((& $Adb -s $DeviceSerial shell getprop sys.boot_completed 2>$null) -ne '1') {
        if ((Get-Date) -gt $deadline) { Fail "The emulator didn't finish booting within $BootTimeoutSeconds s." }
        Start-Sleep -Seconds 2
    }
}

function Start-Emulator([string]$Adb) {
    $emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
    if (-not (Test-Path $emulator)) { Fail 'The Android emulator is not installed (Android Studio > SDK Manager > SDK Tools).' }
    $name = $Avd
    if (-not $name) {
        $name = & $emulator -list-avds | Where-Object { $_.Trim() } | Select-Object -First 1
        if (-not $name) { Fail 'No emulator found. Create one in Android Studio (Device Manager), or pass -Serial.' }
    }
    $before = @(Get-Devices $Adb | ForEach-Object Serial)
    Write-Host "    starting $name"
    Start-Process -FilePath $emulator -ArgumentList @('-avd', $name, '-no-snapshot-save', '-no-boot-anim') -WindowStyle Minimized | Out-Null
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    while ($true) {
        $new = Get-Devices $Adb | Where-Object { $_.Serial -like 'emulator-*' -and $_.Serial -notin $before } | Select-Object -First 1
        if ($new) { break }
        if ((Get-Date) -gt $deadline) { Fail "The emulator $name didn't start within $BootTimeoutSeconds s." }
        Start-Sleep -Seconds 2
    }
    Wait-ForBoot $Adb $new.Serial
    return $new.Serial
}

$adb = Initialize-AndroidEnv
& $adb start-server | Out-Null

Write-Step 'Selecting the device'
$started = $false
if ($Serial) {
    $device = Get-Devices $adb | Where-Object { $_.Serial -eq $Serial -and $_.State -eq 'device' }
    if (-not $device) { Fail "Device '$Serial' is not connected or not ready." }
    if ($Serial -notlike 'emulator-*') {
        Write-Warning ("Running the tests force-stops the app: Android then resets the lock screen card wallpaper " +
            "on this phone. Select it again afterwards (main screen > Set lock screen wallpaper). " +
            "The app's data (current card, decks, settings) is restored after each test.")
    }
    $deviceSerial = $Serial
} else {
    $running = Get-Devices $adb | Where-Object { $_.Serial -like 'emulator-*' -and $_.State -eq 'device' } | Select-Object -First 1
    if ($running) {
        $deviceSerial = $running.Serial
    } else {
        $deviceSerial = Start-Emulator $adb
        $started = $true
    }
}
Write-Host "    $deviceSerial"

$exitCode = 1
try {
    Write-Step 'Building the app and the tests'
    Push-Location $ProjectRoot
    try {
        & .\gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain -q
        if ($LASTEXITCODE -ne 0) { Fail "Gradle failed (exit code $LASTEXITCODE)." }
    } finally {
        Pop-Location
    }

    Write-Step 'Installing'
    # `adb install -r` keeps the app's data (Gradle's connectedAndroidTest would uninstall the app).
    $apks = Join-Path $ProjectRoot 'app\build\outputs\apk'
    & $adb -s $deviceSerial install -r (Join-Path $apks 'debug\app-debug.apk') | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail 'Could not install the app.' }
    & $adb -s $deviceSerial install -r -t (Join-Path $apks 'androidTest\debug\app-debug-androidTest.apk') | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail 'Could not install the tests.' }

    Write-Step 'Running the device tests'
    $output = & $adb -s $deviceSerial shell am instrument -w -e notClass "$AppId.AnkiDroidProbeTest" "$AppId.test/androidx.test.runner.AndroidJUnitRunner"
    $output | ForEach-Object { Write-Host $_ }
    if ($output -match '^OK \(\d+ tests?\)') {
        Write-Host 'All device tests passed.' -ForegroundColor Green
        $exitCode = 0
    } else {
        Write-Host 'Device tests failed.' -ForegroundColor Red
    }
} finally {
    if ($started -and -not $KeepEmulator) {
        Write-Step 'Shutting down the emulator'
        & $adb -s $deviceSerial emu kill | Out-Null
    }
}
exit $exitCode
