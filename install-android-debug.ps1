param(
    [string]$DeviceId = $env:ANDROID_SERIAL
)

# Build and install Suslik Debug APK on Android via ADB
$ErrorActionPreference = "Stop"

function Select-AdbDevice {
    param(
        [Parameter(Mandatory = $true)]
        [string]$AdbPath,
        [string]$PreferredDeviceId = $null
    )

    & $AdbPath start-server | Out-Null

    $devices = @()
    $unauthorized = @()

    for ($i = 1; $i -le 5; $i++) {
        $devicesOutput = & $AdbPath devices -l
        $devices = @()
        $unauthorized = @()

        foreach ($line in ($devicesOutput -split "`r?`n")) {
            if ($line -match '^\s*([^\s]+)\s+device\b(.*)$') {
                $id = $matches[1]
                $info = $matches[2].Trim()
                $model = if ($info -match 'model:([^\s]+)') { $matches[1] -replace '_', ' ' } else { "" }
                $product = if ($info -match 'product:([^\s]+)') { $matches[1] -replace '_', ' ' } else { "" }
                $deviceCode = if ($info -match 'device:([^\s]+)') { $matches[1] } else { "" }
                $displayName = if ($model) { $model } elseif ($product) { $product } elseif ($deviceCode) { $deviceCode } else { "" }

                $devices += [PSCustomObject]@{
                    Id = $id
                    Model = $displayName
                    Label = if ($displayName) { "$id ($displayName)" } else { "$id" }
                }
            } elseif ($line -match '^\s*([^\s]+)\s+unauthorized\b') {
                $unauthorized += $matches[1]
            }
        }
        if ($devices.Count -gt 0) { break }
        Start-Sleep -Seconds 1
    }

    if ($devices.Count -eq 0) {
        Write-Host "ERROR: No authorized Android device connected via USB debugging!" -ForegroundColor Red
        if ($unauthorized.Count -gt 0) {
            Write-Host "Found unauthorized device(s): $($unauthorized -join ', ')" -ForegroundColor Yellow
            Write-Host "Please check your device screen and confirm USB debugging authorization." -ForegroundColor Yellow
        } else {
            Write-Host "Please connect your phone, turn on USB debugging, and allow connection on the phone screen." -ForegroundColor Yellow
        }
        exit 1
    }

    # If preferred device was requested, try to find and use it
    if ($PreferredDeviceId) {
        $found = $devices | Where-Object { $_.Id -eq $PreferredDeviceId }
        if ($found) {
            Write-Host "Using specified device: $($found[0].Label)" -ForegroundColor Green
            return $found[0].Id
        } else {
            Write-Host "WARNING: Specified device '$PreferredDeviceId' not found among connected authorized devices." -ForegroundColor Yellow
        }
    }

    # If only one device is connected, select it automatically
    if ($devices.Count -eq 1) {
        Write-Host "Connected device: $($devices[0].Label)" -ForegroundColor Green
        return $devices[0].Id
    }

    # Multiple devices connected - prompt user to select
    Write-Host "`nMultiple Android devices connected:" -ForegroundColor Yellow
    for ($idx = 0; $idx -lt $devices.Count; $idx++) {
        Write-Host ("  [{0}] {1}" -f ($idx + 1), $devices[$idx].Label) -ForegroundColor Cyan
    }
    if ($unauthorized.Count -gt 0) {
        Write-Host "  (Unauthorized: $($unauthorized -join ', '))" -ForegroundColor DarkGray
    }

    $selectedIndex = -1
    while ($selectedIndex -lt 0 -or $selectedIndex -ge $devices.Count) {
        $inputVal = $null
        try {
            $inputVal = Read-Host ("Select device (1-{0}) [Default: 1, 'q' to cancel]" -f $devices.Count)
        } catch {
            $selectedIndex = 0
            break
        }

        if ([string]::IsNullOrWhiteSpace($inputVal)) {
            $selectedIndex = 0
            break
        }

        if ($inputVal.Trim() -in @("q", "quit", "exit")) {
            Write-Host "Operation cancelled by user." -ForegroundColor Yellow
            exit 0
        }

        if ($inputVal.Trim() -match '^\d+$') {
            $parsed = [int]$inputVal.Trim() - 1
            if ($parsed -ge 0 -and $parsed -lt $devices.Count) {
                $selectedIndex = $parsed
                break
            }
        }

        Write-Host "Invalid choice. Please enter a number between 1 and $($devices.Count)." -ForegroundColor Red
    }

    $chosen = $devices[$selectedIndex]
    Write-Host "Selected device: $($chosen.Label)" -ForegroundColor Green
    return $chosen.Id
}

Write-Host "=== [1/4] Environment setup ===" -ForegroundColor Cyan
$projectRoot = $PSScriptRoot

if ($env:JAVA_HOME -match '\\bin\\?$') {
    $env:JAVA_HOME = $env:JAVA_HOME -replace '\\bin\\?$', ''
}
if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
    $defaultJava = "C:\Program Files\Java\jdk-21.0.12"
    if (Test-Path $defaultJava) {
        $env:JAVA_HOME = $defaultJava
    }
}
Write-Host "JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Gray

if (-not $env:ANDROID_HOME -or -not (Test-Path $env:ANDROID_HOME)) {
    $defaultSdk = "$env:LOCALAPPDATA\Android\Sdk"
    if (Test-Path $defaultSdk) {
        $env:ANDROID_HOME = $defaultSdk
    }
}
Write-Host "ANDROID_HOME: $env:ANDROID_HOME" -ForegroundColor Gray

# Find ADB
$adbCandidates = @(
    "C:\Users\nanar\OneDrive\Desktop\platform-tools\adb.exe",
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "adb"
)
$adb = $null
foreach ($cand in $adbCandidates) {
    if ((Test-Path $cand -ErrorAction SilentlyContinue) -or (Get-Command $cand -ErrorAction SilentlyContinue)) {
        $adb = $cand
        break
    }
}

if (-not $adb) {
    Write-Host "ERROR: ADB not found!" -ForegroundColor Red
    exit 1
}
Write-Host "Using ADB: $adb" -ForegroundColor Gray

Write-Host "`n=== [2/4] Checking device connection ===" -ForegroundColor Cyan
$deviceId = Select-AdbDevice -AdbPath $adb -PreferredDeviceId $DeviceId

Write-Host "`n=== [3/4] Building Suslik Debug APK (assembleDiag) ===" -ForegroundColor Cyan
$gradlew = Join-Path $projectRoot "desktop\gradlew.bat"
if (-not (Test-Path $gradlew)) {
    Write-Host "ERROR: gradlew.bat not found at $gradlew" -ForegroundColor Red
    exit 1
}

& $gradlew -p android assembleDiag
$buildExit = $LASTEXITCODE
if ($buildExit -ne 0) {
    Write-Host "ERROR: Build assembleDiag failed (exit code $buildExit)!" -ForegroundColor Red
    exit $buildExit
}

$apkPath = Join-Path $projectRoot "android\app\build\outputs\apk\diag\app-diag.apk"
if (-not (Test-Path $apkPath)) {
    Write-Host "ERROR: APK not found: $apkPath" -ForegroundColor Red
    exit 1
}
Write-Host "APK built: $apkPath" -ForegroundColor Green

Write-Host "`n=== [4/4] Installing APK and configuring permissions ===" -ForegroundColor Cyan
$pkg = "ru.suslikovanora.media.diag"

# Check if previous package is installed
Write-Host "Checking if package '$pkg' is already installed on device ($deviceId)..." -ForegroundColor Yellow
$installedPackages = & $adb -s $deviceId shell pm list packages $pkg
if ($installedPackages -match "package:$([regex]::Escape($pkg))\b") {
    Write-Host "Found existing package '$pkg'. Uninstalling previous version..." -ForegroundColor Yellow
    $uninstallResult = & $adb -s $deviceId uninstall $pkg
    Write-Host "Uninstall result: $uninstallResult" -ForegroundColor Gray
    Start-Sleep -Seconds 1
} else {
    Write-Host "No existing installation found for '$pkg'." -ForegroundColor Gray
}

Write-Host "Installing APK to device ($deviceId)..." -ForegroundColor Yellow
Write-Host "(Note: If your phone displays a confirmation prompt on screen, tap 'Install' / 'Allow')" -ForegroundColor DarkYellow

$tempOut = [System.IO.Path]::GetTempFileName()
$tempErr = [System.IO.Path]::GetTempFileName()
try {
    # -r: replace existing application
    # -d: allow version code downgrade
    # -t: allow test APKs
    $installArgs = @("-s", $deviceId, "install", "-r", "-d", "-t", $apkPath)
    $installProc = Start-Process -FilePath $adb -ArgumentList $installArgs -RedirectStandardOutput $tempOut -RedirectStandardError $tempErr -NoNewWindow -PassThru

    $timeoutSec = 120
    if (-not $installProc.WaitForExit($timeoutSec * 1000)) {
        Stop-Process -Id $installProc.Id -Force -ErrorAction SilentlyContinue
        Write-Host "ERROR: ADB install timed out after $timeoutSec seconds!" -ForegroundColor Red
        Write-Host "Ensure phone screen is unlocked and check if a USB installation prompt appeared." -ForegroundColor Yellow
        exit 1
    }

    $outText = (Get-Content $tempOut -Raw -ErrorAction SilentlyContinue)
    $errText = (Get-Content $tempErr -Raw -ErrorAction SilentlyContinue)
    if ($outText) { Write-Host $outText.Trim() }
    if ($errText) { Write-Host $errText.Trim() -ForegroundColor Yellow }

    $installProc.WaitForExit()
    if (($outText -notmatch "Success") -and ($installProc.ExitCode -ne 0 -or $outText -match "Failure|INSTALL_FAILED")) {
        Write-Host "ERROR: APK installation failed (exit code $($installProc.ExitCode))!" -ForegroundColor Red
        exit 1
    }
} finally {
    Remove-Item $tempOut, $tempErr -Force -ErrorAction SilentlyContinue
}

Write-Host "Granting permissions for $pkg..." -ForegroundColor Yellow
& $adb -s $deviceId shell pm grant $pkg android.permission.READ_LOGS 2>$null
& $adb -s $deviceId shell pm grant $pkg android.permission.DUMP 2>$null
& $adb -s $deviceId shell pm grant $pkg android.permission.READ_PHONE_STATE 2>$null
& $adb -s $deviceId shell appops set $pkg GET_USAGE_STATS allow 2>$null

Write-Host "Cleaning old diag logs on device..." -ForegroundColor Yellow
& $adb -s $deviceId shell "rm -rf /sdcard/Android/data/$pkg/files/diag/*" 2>$null

Write-Host "Resetting battery stats on device..." -ForegroundColor Yellow
& $adb -s $deviceId shell dumpsys batterystats --reset 2>$null
& $adb -s $deviceId shell dumpsys battery reset 2>$null

Write-Host "Stopping application..." -ForegroundColor Yellow
& $adb -s $deviceId shell am force-stop $pkg 2>$null

Write-Host "`n[SUCCESS] Suslik Debug APK successfully installed and configured on your phone!" -ForegroundColor Green
exit 0
