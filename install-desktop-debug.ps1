# Build and install Suslik Debug on Windows PC
$ErrorActionPreference = "Stop"

Write-Host "=== [1/3] Environment setup ===" -ForegroundColor Cyan
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

$gradlew = Join-Path $projectRoot "desktop\gradlew.bat"
if (-not (Test-Path $gradlew)) {
    Write-Host "ERROR: gradlew.bat not found at $gradlew" -ForegroundColor Red
    exit 1
}

Write-Host "`n=== [2/3] Building Suslik Debug MSI (packageMsi -Pdiag) ===" -ForegroundColor Cyan
Push-Location (Join-Path $projectRoot "desktop")
try {
    & $gradlew packageMsi -Pdiag
    $buildExit = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($buildExit -ne 0) {
    Write-Host "ERROR: Build packageMsi failed (exit code $buildExit)!" -ForegroundColor Red
    exit $buildExit
}

$msiDir = Join-Path $projectRoot "desktop\build\compose\binaries\main\msi"
$msiFile = Get-ChildItem -Path $msiDir -Filter "Suslik Debug-*.msi" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1

if (-not $msiFile) {
    Write-Host "ERROR: MSI file not found in $msiDir" -ForegroundColor Red
    exit 1
}
Write-Host "MSI built: $($msiFile.FullName)" -ForegroundColor Green

Write-Host "`n=== [3/3] Preparing and Installing Suslik Debug on PC ===" -ForegroundColor Cyan

# Terminate running app instances to prevent locked file hangs
$runningProcesses = Get-Process -Name "Suslik Debug", "Suslik Media" -ErrorAction SilentlyContinue
if ($runningProcesses) {
    Write-Host "Closing running instances of Suslik..." -ForegroundColor Yellow
    $runningProcesses | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 800
}

# Check if an existing package is installed and uninstall it first
$uninstallPaths = @(
    "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*",
    "HKLM:\Software\Wow6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*",
    "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*",
    "HKCU:\Software\Wow6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*"
)
$installedApps = Get-ItemProperty $uninstallPaths -ErrorAction SilentlyContinue |
    Where-Object { $_.DisplayName -and ($_.DisplayName -eq "Suslik Debug" -or $_.DisplayName -like "Suslik Debug *") }

if ($installedApps) {
    foreach ($app in $installedApps) {
        $productCode = $app.PSChildName
        if ($productCode -notmatch '^\{[0-9A-Fa-f\-]+\}$') {
            if ($app.UninstallString -match '\{[0-9A-Fa-f\-]+\}') {
                $productCode = $matches[0]
            }
        }

        if ($productCode -match '^\{[0-9A-Fa-f\-]+\}$') {
            Write-Host "Found existing package: '$($app.DisplayName)' ($productCode)." -ForegroundColor Yellow
            Write-Host "Uninstalling previous version..." -ForegroundColor Yellow
            $uninstProc = Start-Process msiexec.exe -ArgumentList "/x", "`"$productCode`"", "/qn", "/norestart" -PassThru
            if (-not $uninstProc.WaitForExit(90000)) {
                Write-Host "WARNING: Uninstall timed out after 90 seconds! Killing process..." -ForegroundColor Red
                Stop-Process -Id $uninstProc.Id -Force -ErrorAction SilentlyContinue
            } else {
                Write-Host "Uninstall completed (exit code $($uninstProc.ExitCode))." -ForegroundColor Gray
            }
        }
    }
    Start-Sleep -Seconds 1
} else {
    Write-Host "No previous Suslik Debug installation found." -ForegroundColor Gray
}

Write-Host "Installing MSI: $($msiFile.Name)..." -ForegroundColor Yellow
$logPath = Join-Path $env:TEMP "suslik_debug_msi_install.log"
$installProcess = Start-Process msiexec.exe -ArgumentList "/i", "`"$($msiFile.FullName)`"", "/qn", "/norestart", "/l*v", "`"$logPath`"" -PassThru

if (-not $installProcess.WaitForExit(120000)) {
    Write-Host "ERROR: MSI install timed out after 120 seconds! Terminating process..." -ForegroundColor Red
    Stop-Process -Id $installProcess.Id -Force -ErrorAction SilentlyContinue
    Write-Host "Check MSI log file: $logPath" -ForegroundColor Yellow
    exit 1
}

# 0 = success, 3010 = success (restart required)
if ($installProcess.ExitCode -ne 0 -and $installProcess.ExitCode -ne 3010) {
    Write-Host "ERROR: MSI install failed (exit code $($installProcess.ExitCode))!" -ForegroundColor Red
    Write-Host "Check MSI log file: $logPath" -ForegroundColor Yellow
    exit $installProcess.ExitCode
}

$installedExe = "$env:LOCALAPPDATA\Suslik Debug\Suslik Debug.exe"
if (Test-Path $installedExe) {
    Write-Host "Executable updated: $installedExe" -ForegroundColor Green
}

Write-Host "`n[SUCCESS] Suslik Debug successfully installed on your PC!" -ForegroundColor Green
exit 0
