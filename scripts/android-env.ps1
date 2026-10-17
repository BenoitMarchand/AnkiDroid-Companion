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

function Get-EmulatorExe {
    $emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
    if (-not (Test-Path $emulator)) { Fail 'The Android emulator is not installed (Android Studio > SDK Manager > SDK Tools).' }
    return $emulator
}

$AndroidVersions = @{ 26 = '8.0'; 27 = '8.1'; 28 = '9'; 29 = '10'; 30 = '11'; 31 = '12'; 32 = '12L'; 33 = '13'; 34 = '14'; 35 = '15'; 36 = '16' }

# Emulators created in Android Studio (Device Manager), with their Android version and system image.
function Get-Avds {
    $ErrorActionPreference = 'Continue'
    $avdHome = $env:ANDROID_AVD_HOME
    if (-not $avdHome) {
        $userHome = if ($env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME } else { Join-Path $env:USERPROFILE '.android' }
        $avdHome = Join-Path $userHome 'avd'
    }
    & (Get-EmulatorExe) -list-avds 2>$null | Where-Object { $_.Trim() -and $_ -notmatch '^(INFO|WARNING|ERROR)\b' } | ForEach-Object {
        $name = $_.Trim()
        $api = $null; $image = ''
        $dir = Join-Path $avdHome "$name.avd"
        $ini = Join-Path $avdHome "$name.ini"
        if (Test-Path $ini) {
            $path = Select-String -Path $ini -Pattern '^path=(.+)$' | Select-Object -First 1
            if ($path) { $dir = $path.Matches[0].Groups[1].Value.Trim() }
        }
        $config = Join-Path $dir 'config.ini'
        if (Test-Path $config) {
            $sysdir = Select-String -Path $config -Pattern '^image\.sysdir\.1=(.+)$' | Select-Object -First 1
            if ($sysdir -and $sysdir.Line -match 'android-(\d+)') { $api = [int]$Matches[1] }
            if ($sysdir -and $sysdir.Line -match 'playstore') { $image = 'Google Play' }
            elseif ($sysdir -and $sysdir.Line -match 'google_apis') { $image = 'Google APIs' }
        }
        $version = '?'
        if ($api) { $version = if ($AndroidVersions[$api]) { "Android $($AndroidVersions[$api]) (API $api)" } else { "API $api" } }
        [pscustomobject]@{ Name = $name; Api = $api; Version = $version; Image = $image }
    }
}

# Running emulators with the name of their AVD, and whether Android finished booting in them.
function Get-RunningEmulators([string]$Adb) {
    $ErrorActionPreference = 'Continue'
    Get-Devices $Adb | Where-Object { $_.Serial -like 'emulator-*' } | ForEach-Object {
        $name = & $Adb -s $_.Serial emu avd name 2>$null | Select-Object -First 1
        $booted = $_.State -eq 'device' -and ((& $Adb -s $_.Serial shell getprop sys.boot_completed 2>$null) -eq '1')
        $avd = if ($name) { "$name".Trim() } else { $null }
        [pscustomobject]@{ Serial = $_.Serial; State = $_.State; Avd = $avd; Booted = $booted }
    }
}

function Show-EmulatorLog([string]$Log) {
    if (-not $Log) { return }
    foreach ($file in @($Log, "$Log.err")) {
        if (Test-Path $file) { Get-Content $file -Tail 15 | ForEach-Object { Write-Host "    | $_" } }
    }
    Write-Host "    Full output: $Log"
}

# Waits until Android has finished booting in $DeviceSerial, reporting progress every 15 s. With $Process (an
# emulator started by the script), fails as soon as it exits (crash, or its window closed) and shows the end
# of its $Log. Also fails if adb loses the device, or after $BootTimeoutSeconds.
function Wait-ForBoot([string]$Adb, [string]$DeviceSerial, [System.Diagnostics.Process]$Process, [string]$Log) {
    $ErrorActionPreference = 'Continue'
    $start = Get-Date
    $seen = $false
    $lastReport = 0
    while ($true) {
        $elapsed = [int]((Get-Date) - $start).TotalSeconds
        if ($Process -and $Process.HasExited) {
            Write-Host "    The emulator stopped after $elapsed s (exit code $($Process.ExitCode)). End of its output:" -ForegroundColor Red
            Show-EmulatorLog $Log
            Fail 'The emulator stopped while starting: see its output above.'
        }
        $device = Get-Devices $Adb | Where-Object { $_.Serial -eq $DeviceSerial }
        if ($device) {
            $seen = $true
            if ($device.State -eq 'device' -and ((& $Adb -s $DeviceSerial shell getprop sys.boot_completed 2>$null) -eq '1')) {
                Write-Host "    booted ($elapsed s)"
                return
            }
        } elseif ($seen) {
            Show-EmulatorLog $Log
            Fail "The emulator $DeviceSerial disappeared from adb while booting."
        }
        if ($elapsed -gt $BootTimeoutSeconds) {
            Show-EmulatorLog $Log
            Fail "The emulator didn't finish booting within $BootTimeoutSeconds s."
        }
        if ($elapsed - $lastReport -ge 15) {
            $state = if ($device) { "Android is booting ($($device.State))" } else { 'waiting for the emulator' }
            Write-Host "    $state... $elapsed s"
            $lastReport = $elapsed
        }
        Start-Sleep -Seconds 3
    }
}

# Starts the emulator $Avd (the first one of `emulator -list-avds` if empty) and returns its serial once Android
# has booted. If it's already running, only waits for it to finish booting. Its output goes to
# %TEMP%\emulator-<avd>.log.
function Start-Emulator([string]$Adb, [string]$Avd, [switch]$NoSnapshotSave) {
    $emulator = Get-EmulatorExe
    $name = $Avd
    if (-not $name) {
        $name = Get-Avds | Select-Object -First 1 -ExpandProperty Name
        if (-not $name) { Fail 'No emulator found. Create one in Android Studio (Device Manager), or pass -Serial.' }
    }
    $running = Get-RunningEmulators $Adb | Where-Object { $_.Avd -eq $name } | Select-Object -First 1
    if ($running) {
        Write-Host "    $name is already running ($($running.Serial))"
        Wait-ForBoot $Adb $running.Serial
        return $running.Serial
    }

    $before = @(Get-Devices $Adb | ForEach-Object Serial)
    $log = Join-Path $env:TEMP "emulator-$name.log"
    $arguments = @('-avd', $name, '-no-boot-anim')
    if ($NoSnapshotSave) { $arguments += '-no-snapshot-save' }
    Write-Host "    starting $name (output: $log)"
    $process = Start-Process -FilePath $emulator -ArgumentList $arguments -PassThru `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    $null = $process.Handle   # keeps the handle open: otherwise ExitCode is empty once it has exited

    # First the emulator shows up in adb, then Android boots in it.
    $start = Get-Date
    while ($true) {
        if ($process.HasExited) {
            Write-Host "    The emulator stopped (exit code $($process.ExitCode)). End of its output:" -ForegroundColor Red
            Show-EmulatorLog $log
            Fail "The emulator $name could not start: see its output above."
        }
        $new = Get-Devices $Adb | Where-Object { $_.Serial -like 'emulator-*' -and $_.Serial -notin $before } | Select-Object -First 1
        if ($new) { break }
        if (((Get-Date) - $start).TotalSeconds -gt $BootTimeoutSeconds) {
            Show-EmulatorLog $log
            Fail "The emulator $name didn't start within $BootTimeoutSeconds s."
        }
        Start-Sleep -Seconds 2
    }
    Write-Host "    $name is $($new.Serial), waiting for Android to boot"
    Wait-ForBoot $Adb $new.Serial $process $log
    return $new.Serial
}
