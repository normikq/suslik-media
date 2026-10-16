# Build and install Suslik Debug APK on Android via ADB
$ErrorActionPreference = "Stop"

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
& $adb start-server | Out-Null

$connected = $false
$deviceId = ""
for ($i = 1; $i -le 5; $i++) {
    $devicesOutput = & $adb devices
    foreach ($line in ($devicesOutput -split "`r?`n")) {
        if ($line -match "^\s*([^\s]+)\s+device\s*$") {
            $connected = $true
            $deviceId = $matches[1]
            break
        }
    }
    if ($connected) { break }
    Start-Sleep -Seconds 1
}

if (-not $connected) {
    Write-Host "ERROR: No authorized Android device connected via USB debugging!" -ForegroundColor Red
    Write-Host "Please connect your phone, turn on USB debugging, and allow connection on the phone screen." -ForegroundColor Yellow
    exit 1
}
Write-Host "Connected device: $deviceId" -ForegroundColor Green

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
& $adb -s $deviceId shell am force-stop $pkg 2>$null

Write-Host "`n[SUCCESS] Suslik Debug APK successfully installed and configured on your phone!" -ForegroundColor Green
exit 0
