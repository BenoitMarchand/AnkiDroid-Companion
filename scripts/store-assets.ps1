<#
.SYNOPSIS
    Generates the Play Store listing assets on an emulator: the three GIFs (lock screen, notification, widget),
    screenshots (main screen, Settings and stills of the three flows) and the 512 px icon.

.DESCRIPTION
    Run on demand only (see docs/play-store/README.md). On an emulator where AnkiDroid is installed, with its API
    enabled:
    1. Builds and installs the app and the test APK (skipped with -SkipBuild), grants the AnkiDroid and
       notification permissions.
    2. Runs StoreAssetsSetup (app/src/androidTest): resets the "World capitals" demo deck in AnkiDroid (its cards
       are new again), selects it in the app, renders the icon.
    3. Sets the card wallpaper on the lock screen if needed (reboots the emulator when Android didn't bind it
       again after the reinstall), adds the Card widget to the home screen if needed, and tidies the screen
       (status bar demo mode, system notifications snoozed, screen kept on, touches shown).
    4. Takes the screenshots, records the three flows (each one answers cards of the demo deck) and converts
       them with ffmpeg.

    Output (default build/store-assets):
      gif/          lockscreen.gif, notification.gif, widget.gif (360 px wide, for the README or a website)
      screenshots/  PNGs cropped to the Play Store's 2:1 maximum ratio
      raw/          full-size PNGs and the recordings
      icon-512.png  the listing icon

    Only on an emulator: the script drives the screen (taps, lock, unlock), and the setup changes the deck
    selected in the app. Needs ffmpeg on the PATH, or Python with `pip install imageio-ffmpeg`.

.PARAMETER Serial
    Running emulator to use (see `adb devices`).

.PARAMETER Avd
    Emulator to use (see `emulator -list-avds`): the running one with this name, otherwise it is started.
    Without -Avd and -Serial: the first running emulator with AnkiDroid, otherwise the first one listed is
    started.

.PARAMETER OutputDir
    Where to write the assets. Default: build/store-assets in the project.

.PARAMETER SkipBuild
    Use the app and test APK already installed.

.PARAMETER ConvertOnly
    Don't use the emulator: only convert the captures already in OutputDir\raw again.

.PARAMETER KeepEmulator
    Leave the emulator started by the script running at the end.

.EXAMPLE
    .\scripts\store-assets.ps1
    .\scripts\store-assets.ps1 -Avd Pixel_9a -KeepEmulator
    .\scripts\store-assets.ps1 -Serial emulator-5554 -SkipBuild -OutputDir C:\temp\store
    .\scripts\store-assets.ps1 -ConvertOnly
#>
[CmdletBinding()]   # rejects unknown (mistyped) parameters
param(
    [string]$Serial,
    [string]$Avd,
    [string]$OutputDir,
    [switch]$SkipBuild,
    [switch]$ConvertOnly,
    [switch]$KeepEmulator
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AppId = 'com.ankidroid.companion'
$AnkiDroidId = 'com.ichi2.anki'
$Remote = '/sdcard/store-assets'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

. (Join-Path $PSScriptRoot 'android-env.ps1')

if (-not $OutputDir) { $OutputDir = Join-Path $ProjectRoot 'build\store-assets' }

# --- adb ---------------------------------------------------------------------

function Adb {
    # Windows PowerShell turns a native command's stderr into errors: don't stop on them.
    $ErrorActionPreference = 'Continue'
    & $script:adb -s $script:device @args 2>$null
}

function Shell([string]$Command) { Adb shell $Command }

function Test-Installed([string]$Package) { [bool](Shell "pm path $Package") }

function Test-Awake { (Shell 'dumpsys power') -match 'mWakefulness=Awake' }

function Test-Locked { (Shell 'dumpsys window') -match 'isKeyguardShowing=true' }

function Wait-Until([scriptblock]$Condition, [int]$Seconds = 10) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while (-not (& $Condition)) {
        if ((Get-Date) -gt $deadline) { return $false }
        Start-Sleep -Milliseconds 500
    }
    return $true
}

# --- Screen elements (uiautomator) -------------------------------------------

# Returns the first element whose text or content description matches $Pattern (a regex), with its bounds and
# center, or $null.
function Find-Node([string]$Pattern) {
    # A failed dump leaves the previous file: remove it first. The folder can vanish at a reboot.
    Shell "mkdir -p $Remote; rm -f $Remote/ui.xml; uiautomator dump $Remote/ui.xml" | Out-Null
    $dump = (Shell "cat $Remote/ui.xml") -join "`n"
    if (-not $dump.StartsWith('<?xml')) { return $null }
    foreach ($node in ([xml]$dump).SelectNodes('//node')) {
        if ($node.text -match $Pattern -or $node.'content-desc' -match $Pattern) {
            $b = [regex]::Match($node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
            $left = [int]$b.Groups[1].Value; $top = [int]$b.Groups[2].Value
            $right = [int]$b.Groups[3].Value; $bottom = [int]$b.Groups[4].Value
            return [pscustomobject]@{
                Left = $left; Top = $top; Right = $right; Bottom = $bottom
                X = [int](($left + $right) / 2); Y = [int](($top + $bottom) / 2)
            }
        }
    }
    return $null
}

function Wait-Node([string]$Pattern, [int]$Seconds = 10) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ($true) {
        $node = Find-Node $Pattern
        if ($node -or (Get-Date) -gt $deadline) { return $node }
        Start-Sleep -Milliseconds 500
    }
}

function Tap([int]$X, [int]$Y) { Shell "input tap $X $Y" | Out-Null }

# Taps the element matching $Pattern, then waits $Pause seconds. In a recording, finding the next element
# (a uiautomator dump: 2 to 4 s) already leaves the viewer time to read.
function Invoke-NodeTap([string]$Pattern, [double]$Pause = 0.5, [int]$Seconds = 10) {
    $node = Wait-Node $Pattern $Seconds
    if (-not $node) { Fail "'$Pattern' not found on the screen." }
    Tap $node.X $node.Y
    Start-Sleep -Milliseconds ([int]($Pause * 1000))
}

# --- Screen state --------------------------------------------------------------

function Lock-AndWake {
    Shell 'cmd statusbar collapse' | Out-Null
    for ($i = 0; $i -lt 3 -and (Test-Awake); $i++) {
        Shell 'input keyevent KEYCODE_SLEEP' | Out-Null
        Start-Sleep -Seconds 2
    }
    Shell 'input keyevent KEYCODE_WAKEUP' | Out-Null
    if (-not (Wait-Until { (Test-Awake) -and (Test-Locked) })) { Fail 'The emulator did not show its lock screen.' }
    Start-Sleep -Seconds 1
}

function Unlock {
    Shell 'cmd statusbar collapse' | Out-Null
    Shell 'wm dismiss-keyguard' | Out-Null
    if (-not (Wait-Until { -not (Test-Locked) })) { Fail 'Could not unlock the emulator (does it have a PIN?).' }
    Start-Sleep -Seconds 1
}

function Show-Home {
    Shell 'input keyevent KEYCODE_HOME' | Out-Null
    Start-Sleep -Milliseconds 1500
}

function Get-ScreenSize {
    $m = [regex]::Match(((Shell 'wm size') -join ' '), '(\d+)x(\d+)')
    return [pscustomobject]@{ Width = [int]$m.Groups[1].Value; Height = [int]$m.Groups[2].Value }
}

# Lock screen part of `dumpsys wallpaper`.
function Get-LockWallpaper {
    $dump = (Shell 'dumpsys wallpaper') -join "`n"
    $m = [regex]::Match($dump, '(?s)Lock wallpaper state:(.*?)(Fallback wallpaper state:|$)')
    return $m.Groups[1].Value
}

function Set-CardWallpaper {
    Shell "am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT $AppId/.CardWallpaperService" | Out-Null
    Invoke-NodeTap '^Set wallpaper$' 2 15
    $lockOnly = Wait-Node '^Lock screen$' 5
    if (-not $lockOnly) { Fail 'The wallpaper picker has no "Lock screen" choice: use an emulator with Android 14 or later.' }
    Tap $lockOnly.X $lockOnly.Y
    Start-Sleep -Seconds 3
}

function Initialize-CardWallpaper {
    if ((Get-LockWallpaper) -notmatch 'CardWallpaperService') {
        Write-Host '    setting the card wallpaper on the lock screen'
        Unlock
        Set-CardWallpaper
        Show-Home
    }
    # After a reinstall and an instrumentation run, Android may keep the lock wallpaper unbound (black lock
    # screen), and setting it again is ignored as "the same component": a reboot binds it again.
    if ((Get-LockWallpaper) -match 'mService=null') {
        Write-Host '    the card wallpaper is not running: rebooting the emulator'
        Adb reboot | Out-Null
        Start-Sleep -Seconds 5
        Wait-ForBoot $script:adb $script:device
        Shell 'input keyevent KEYCODE_WAKEUP' | Out-Null
        # Bound once Android has unlocked the user's storage, some time after the boot.
        if (-not (Wait-Until { (Get-LockWallpaper) -match 'mService=android' } 120)) {
            Fail 'The card wallpaper still is not running after a reboot.'
        }
        Start-Sleep -Seconds 5
    }
}

# Adds the Card widget to the home screen through the launcher (Pixel launcher). Returns $false if it can't.
function Add-CardWidget {
    $size = Get-ScreenSize
    Show-Home
    Shell "input swipe $([int]($size.Width / 2)) $([int]($size.Height * 0.45)) $([int]($size.Width / 2)) $([int]($size.Height * 0.45)) 1200" | Out-Null
    $widgets = Wait-Node '^Widgets$' 3
    if (-not $widgets) { return $false }
    Tap $widgets.X $widgets.Y
    $search = Wait-Node '^Search' 5
    if (-not $search) { return $false }
    Tap $search.X $search.Y
    Start-Sleep -Seconds 1
    Shell 'input text Micro' | Out-Null
    $app = Wait-Node '^Micro Review for AnkiDroid$' 5
    if (-not $app) { return $false }
    Tap $app.X $app.Y
    $preview = Wait-Node '^Card widget' 5
    if (-not $preview) { return $false }
    Tap $preview.X $preview.Y
    $add = Wait-Node '^Add( Card widget)?$' 5
    if (-not $add) { return $false }
    Tap $add.X $add.Y
    Start-Sleep -Seconds 3
    # The new widget is in resize mode: drag its right edge to the side of the screen for longer questions.
    $widget = Find-Node '^Card$'
    if ($widget) {
        Shell "input swipe $($widget.Right - 4) $($widget.Y) $($size.Width - 20) $($widget.Y) 800" | Out-Null
        Start-Sleep -Seconds 1
    }
    Show-Home
    Show-Home
    return [bool](Find-Node '^Start review$|cards due|^Show answer$')
}

# Status bar demo mode, system notifications snoozed (e.g. "Storage space running out"), screen kept on.
function Initialize-Screen {
    Shell 'settings put system show_touches 1' | Out-Null
    # "Stay on" only works on power: a simulated charger (the lock screen then says "Charged": blanked later).
    Shell 'dumpsys battery set ac 1' | Out-Null
    Shell 'svc power stayon true' | Out-Null
    Shell 'settings put global sysui_demo_allowed 1' | Out-Null
    foreach ($demo in @(
        '-e command enter',
        '-e command clock -e hhmm 0930',
        '-e command battery -e level 100 -e plugged false',
        '-e command network -e wifi show -e level 4 -e fully true -e mobile show -e datatype none -e level 4',
        '-e command notifications -e visible false')) {
        Shell "am broadcast -a com.android.systemui.demo $demo" | Out-Null
    }
    $keys = [regex]::Matches(((Shell 'dumpsys notification --noredact') -join "`n"), 'key=(-?\d+\|android\|[^\s]+)') |
        ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
    foreach ($key in $keys) { Shell "cmd notification snooze --for 3600000 '$key'" | Out-Null }
}

function Restore-Screen {
    Shell 'dumpsys battery reset' | Out-Null
    Shell 'svc power stayon false' | Out-Null
    Shell 'settings put system show_touches 0' | Out-Null
    Shell 'am broadcast -a com.android.systemui.demo -e command exit' | Out-Null
    Shell "rm -rf $Remote" | Out-Null
}

# --- Captures ------------------------------------------------------------------

function Save-Screenshot([string]$Name) {
    Shell "mkdir -p $Remote; screencap -p $Remote/$Name.png" | Out-Null
    Adb pull "$Remote/$Name.png" (Join-Path $RawDir "$Name.png") | Out-Null
}

function Start-Recording {
    Shell "mkdir -p $Remote; rm -f $Remote/rec.mp4" | Out-Null
    $script:recorder = Start-Process -FilePath $script:adb -NoNewWindow -PassThru `
        -ArgumentList @('-s', $script:device, 'shell', 'screenrecord', '--bit-rate', '8000000', "$Remote/rec.mp4")
    $script:recordingClock = [Diagnostics.Stopwatch]::StartNew()   # roughly the time in the video
    Start-Sleep -Milliseconds 1500
}

function Stop-Recording([string]$Name) {
    Start-Sleep -Milliseconds 500
    Shell 'pkill -INT screenrecord' | Out-Null
    $script:recorder.WaitForExit(10000) | Out-Null
    Start-Sleep -Seconds 2
    Adb pull "$Remote/rec.mp4" (Join-Path $RawDir "$Name.mp4") | Out-Null
}

# Box of the "Charged" text of the simulated charger on the lock screen, for ffmpeg's delogo filter.
function Get-ChargeLabel {
    $node = Find-Node '^(Charged|Charging)'
    if (-not $node) { return $null }
    $m = 16
    return "delogo=x=$($node.Left - $m):y=$($node.Top - $m):w=$($node.Right - $node.Left + 2 * $m):h=$($node.Bottom - $node.Top + 2 * $m)"
}

# --- ffmpeg --------------------------------------------------------------------

function Find-Ffmpeg {
    # -CommandType Application: not the Ffmpeg function below.
    $cmd = Get-Command ffmpeg -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) { return $cmd.Source }
    $pythons = @('python', 'py', "$env:USERPROFILE\anaconda3\python.exe", "$env:USERPROFILE\miniconda3\python.exe")
    foreach ($python in $pythons) {
        if (Get-Command $python -CommandType Application -ErrorAction SilentlyContinue) {
            $ErrorActionPreference = 'Continue'
            $exe = & $python -c 'import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())' 2>$null
            if ($LASTEXITCODE -eq 0 -and $exe) { return $exe.Trim() }
        }
    }
    Fail 'ffmpeg not found. Install it (winget install Gyan.FFmpeg) or run: pip install imageio-ffmpeg'
}

function Ffmpeg {
    $ErrorActionPreference = 'Continue'
    & $script:ffmpeg -y -loglevel error @args
    if ($LASTEXITCODE -ne 0) { Fail "ffmpeg failed (exit code $LASTEXITCODE)." }
}

# 360 px wide, 10 fps, ordered dithering: stable from frame to frame, so small files on pictures.
$GifFilter = 'fps=10,scale=360:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=192:stats_mode=full[p];[b][p]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle'

function Convert-ToGif([string]$Video, [string]$Gif, [string]$Before) {
    $filter = if ($Before) { "$Before,$GifFilter" } else { $GifFilter }
    Ffmpeg -i $Video -vf $filter $Gif
}

# The Play Store refuses screenshots longer than twice their width: crop the middle.
function Convert-ToStoreScreenshot([string]$Png, [string]$Out, [string]$Before) {
    $crop = "crop=iw:'min(ih,2*iw)':0:'(ih-min(ih,2*iw))/2'"
    $filter = if ($Before) { "$Before,$crop" } else { $crop }
    Ffmpeg -i $Png -vf $filter $Out
}

# Converts the captures in raw/ (see capture.json) to the GIFs and the Play Store screenshots.
function Convert-Captures {
    $capture = Get-Content (Join-Path $RawDir 'capture.json') -Raw | ConvertFrom-Json
    $chargeLabel = $capture.ChargeLabel
    Push-Location $RawDir
    try {
        Convert-ToGif 'notification.mp4' (Join-Path $GifDir 'notification.gif') ((@($chargeLabel, 'tpad=stop_mode=clone:stop_duration=2') | Where-Object { $_ }) -join ',')
        # Home screen, screen off, then the recording from the lock screen to the unlock.
        $w = $capture.Width; $h = $capture.Height
        # The label is only on the lock screen: not on the home screen after the unlock.
        $lockLabel = if ($chargeLabel) { "$($chargeLabel):enable='lt(t,$([string]::Format([Globalization.CultureInfo]::InvariantCulture, '{0:0.00}', $capture.UnlockAt)))'" } else { $null }
        $clip = (@($lockLabel, 'tpad=stop_mode=clone:stop_duration=1') | Where-Object { $_ }) -join ','
        # -vcodec, not -c:v: Windows PowerShell mangles "-name:value" arguments.
        Ffmpeg -loop 1 -t 1.5 -i 'home.png' -f lavfi -t 0.7 -i "color=black:s=${w}x${h}:r=30" -i 'lockscreen-unlock.mp4' `
            -filter_complex "[0]scale=${w}:${h},fps=30,format=yuv420p,setsar=1[a];[1]format=yuv420p,setsar=1[b];[2]scale=${w}:${h},fps=30,$clip,format=yuv420p,setsar=1[c];[a][b][c]concat=n=3:v=1[v]" `
            -map '[v]' -vcodec libx264 -crf 18 'lockscreen.mp4'
        Convert-ToGif 'lockscreen.mp4' (Join-Path $GifDir 'lockscreen.gif')
        if ($capture.HasWidget) { Convert-ToGif 'widget.mp4' (Join-Path $GifDir 'widget.gif') 'tpad=stop_mode=clone:stop_duration=1' }

        $shots = @('main', 'settings', 'lockscreen', 'notification')
        if ($capture.HasWidget) { $shots += 'widget' }
        $index = 1
        foreach ($shot in $shots) {
            $before = if ($shot -eq 'lockscreen') { $chargeLabel } else { $null }
            Convert-ToStoreScreenshot "$shot.png" (Join-Path $ShotDir ('{0}-{1}.png' -f $index, $shot)) $before
            $index++
        }
    } finally {
        Pop-Location
    }
}

# --- Main ----------------------------------------------------------------------

$RawDir = Join-Path $OutputDir 'raw'
$GifDir = Join-Path $OutputDir 'gif'
$ShotDir = Join-Path $OutputDir 'screenshots'
foreach ($dir in @($RawDir, $GifDir, $ShotDir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
$ffmpeg = Find-Ffmpeg

if (-not $ConvertOnly) {
    $adb = Initialize-AndroidEnv
    & $adb start-server | Out-Null

    Write-Step 'Selecting the emulator'
    $started = $false
    if ($Serial) {
        if ($Serial -notlike 'emulator-*') { Fail 'Use an emulator: the script drives the screen and changes the selected decks.' }
        if (-not (Get-Devices $adb | Where-Object { $_.Serial -eq $Serial -and $_.State -eq 'device' })) {
            Fail "Emulator '$Serial' is not running."
        }
        $device = $Serial
    } else {
        # -Avd: the running emulator with that name. Otherwise the first running one with AnkiDroid.
        $found = $null
        foreach ($emu in (Get-Devices $adb | Where-Object { $_.Serial -like 'emulator-*' -and $_.State -eq 'device' })) {
            $script:device = $emu.Serial   # for Adb and Test-Installed
            if ($Avd) {
                $name = Adb emu avd name | Select-Object -First 1
                if ($name -and $name.Trim() -eq $Avd) { $found = $emu.Serial; break }
            } elseif (Test-Installed $AnkiDroidId) {
                $found = $emu.Serial; break
            }
        }
        if ($found) {
            $device = $found
        } else {
            $device = Start-Emulator $adb $Avd
            $started = $true
        }
    }
    $script:device = $device
    Write-Host "    $device"
    if (-not (Test-Installed $AnkiDroidId)) {
        Fail ("AnkiDroid is not installed on $device. Install it, create a collection, enable its API " +
              '(Settings > Advanced > Enable AnkiDroid API), or pass -Serial / -Avd for another emulator.')
    }

    try {
        if (-not $SkipBuild) {
            Write-Step 'Building and installing the app and the test APK'
            Push-Location $ProjectRoot
            try {
                & .\gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain -q
                if ($LASTEXITCODE -ne 0) { Fail "Gradle failed (exit code $LASTEXITCODE)." }
            } finally {
                Pop-Location
            }
            $apks = Join-Path $ProjectRoot 'app\build\outputs\apk'
            Shell 'settings put global sys_storage_threshold_percentage 2' | Out-Null
            Adb install -r (Join-Path $apks 'debug\app-debug.apk') | Out-Null
            if ($LASTEXITCODE -ne 0) { Fail 'Could not install the app.' }
            Adb install -r -t (Join-Path $apks 'androidTest\debug\app-debug-androidTest.apk') | Out-Null
            if ($LASTEXITCODE -ne 0) { Fail 'Could not install the test APK.' }
        }
        Shell "pm grant $AppId com.ichi2.anki.permission.READ_WRITE_DATABASE" | Out-Null
        Shell "pm grant $AppId android.permission.POST_NOTIFICATIONS" | Out-Null

        Write-Step 'Resetting the demo deck and rendering the icon'
        $output = Shell "am instrument -w -e storeAssets true -e class $AppId.StoreAssetsSetup $AppId.test/androidx.test.runner.AndroidJUnitRunner"
        if (-not ($output -match '^OK \(2 tests\)')) {
            $output | ForEach-Object { Write-Host $_ }
            Fail 'The setup failed: is the AnkiDroid API enabled (AnkiDroid: Settings > Advanced)?'
        }
        Adb pull "/sdcard/Android/data/$AppId/files/store/icon-512.png" (Join-Path $OutputDir 'icon-512.png') | Out-Null

        Write-Step 'Preparing the emulator'
        Shell 'input keyevent KEYCODE_WAKEUP' | Out-Null
        Initialize-CardWallpaper
        Initialize-Screen
        Unlock
        Show-Home
        $hasWidget = [bool](Find-Node '^Start review$|cards due|^Show answer$')
        if (-not $hasWidget) {
            Write-Host '    adding the Card widget to the home screen'
            $hasWidget = Add-CardWidget
            if (-not $hasWidget) { Write-Warning 'Could not add the Card widget: add it by hand to get the widget GIF.' }
        }

        Write-Step 'Screenshots of the main screen and Settings'
        Shell "am start -n $AppId/.MainActivity" | Out-Null
        if (-not (Wait-Node '^Review order$' 15)) { Fail 'The main screen did not open.' }
        Start-Sleep -Seconds 1
        Save-Screenshot 'main'
        Shell "am start -n $AppId/.SettingsActivity" | Out-Null
        Start-Sleep -Seconds 3
        Save-Screenshot 'settings'
        Show-Home

        Write-Step 'Recording the notification'
        Lock-AndWake
        if (-not (Wait-Node 'capital of' 10)) { Fail 'The card notification is not on the lock screen.' }
        $chargeLabel = Get-ChargeLabel
        $expand = Wait-Node '^Expand' 5
        if (-not $expand) { Fail 'The card notification has no expand button.' }
        Start-Recording
        Start-Sleep -Seconds 1
        Tap $expand.X $expand.Y
        $show = Find-Node '^Show answer$'   # absent when the wallpaper already shows the answer
        if ($show) { Tap $show.X $show.Y }
        $good = Wait-Node '^Good$' 5
        if (-not $good) { Fail 'The answer buttons are not in the notification.' }
        Save-Screenshot 'notification'
        Tap $good.X $good.Y
        # Stop before the answered notification goes away (5 s): the lock screen clock then covers the card.
        Wait-Node 'Next card at your next unlock' 3 | Out-Null
        Stop-Recording 'notification'

        Write-Step 'Recording the lock screen'
        Unlock
        Show-Home
        Save-Screenshot 'home'
        Lock-AndWake
        Wait-Node 'capital of' 10 | Out-Null
        Start-Recording
        Start-Sleep -Milliseconds 1500
        Save-Screenshot 'lockscreen'
        Start-Sleep -Milliseconds 1500
        $size = Get-ScreenSize
        $unlockAt = $script:recordingClock.Elapsed.TotalSeconds
        Shell "input swipe $([int]($size.Width / 2)) $([int]($size.Height * 0.92)) $([int]($size.Width / 2)) $([int]($size.Height * 0.35)) 250" | Out-Null
        Start-Sleep -Milliseconds 2500
        Stop-Recording 'lockscreen-unlock'
        Unlock

        if ($hasWidget) {
            Write-Step 'Recording the widget'
            Show-Home
            $start = Find-Node '^Start review$'   # absent if the widget already shows the card
            Start-Recording
            if ($start) { Tap $start.X $start.Y }
            Invoke-NodeTap '^Show answer$'
            Save-Screenshot 'widget'
            Invoke-NodeTap '^Good$'
            Invoke-NodeTap '^Show answer$'
            Invoke-NodeTap '^Easy$' 2
            Stop-Recording 'widget'
        }

        @{ Width = $size.Width; Height = $size.Height; ChargeLabel = $chargeLabel; UnlockAt = $unlockAt; HasWidget = $hasWidget } |
            ConvertTo-Json | Set-Content (Join-Path $RawDir 'capture.json')
    } finally {
        Restore-Screen
        if ($started -and -not $KeepEmulator) {
            Write-Step 'Shutting down the emulator'
            & $adb -s $device emu kill | Out-Null
        }
    }
}

Write-Step 'Converting'
Convert-Captures

Write-Host ''
Write-Host "Store assets written to $OutputDir" -ForegroundColor Green
Get-ChildItem $OutputDir -Recurse -File |
    Where-Object { $_.DirectoryName -notlike '*raw' } |
    ForEach-Object { Write-Host ('    {0} ({1:N0} KB)' -f $_.FullName.Substring($OutputDir.Length + 1), ($_.Length / 1KB)) }
