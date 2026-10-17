# Helpers shared by the Android scripts (install.ps1, device-tests.ps1, release.ps1). Dot-source it:
#     . (Join-Path $PSScriptRoot 'android-env.ps1')
# Expects $ProjectRoot to be set by the caller.

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

# Locates the SDK and a JDK 17+, and sets ANDROID_HOME / JAVA_HOME for Gradle. Returns the path of adb.
function Initialize-AndroidEnv {
    Write-Step 'Locating Android SDK'
    $sdk = Find-AndroidSdk
    if (-not $sdk) { Fail 'Android SDK not found. Install it with Android Studio, or set ANDROID_HOME.' }
    $env:ANDROID_HOME = $sdk
    Write-Host "    $sdk"

    Write-Step 'Locating JDK'
    $jdk = Find-Jdk
    if (-not $jdk) {
        Fail ("No JDK 17+ found. Install Android Studio (it bundles one), or download one from Android Studio: " +
              "Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK > Download JDK. Or set JDK_HOME.")
    }
    $env:JAVA_HOME = $jdk.Path
    Write-Host "    $($jdk.Path) (Java $($jdk.Major))"
    return (Join-Path $sdk 'platform-tools\adb.exe')
}

# --- Emulator ----------------------------------------------------------------

$BootTimeoutSeconds = 300

function Wait-ForBoot([string]$Adb, [string]$DeviceSerial) {
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    & $Adb -s $DeviceSerial wait-for-device
    while ((& $Adb -s $DeviceSerial shell getprop sys.boot_completed 2>$null) -ne '1') {
        if ((Get-Date) -gt $deadline) { Fail "The emulator didn't finish booting within $BootTimeoutSeconds s." }
        Start-Sleep -Seconds 2
    }
}

# Starts the emulator $Avd (the first one of `emulator -list-avds` if empty) and returns its serial once booted.
function Start-Emulator([string]$Adb, [string]$Avd) {
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
