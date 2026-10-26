# Сборка приложения для Samsung TV на Windows: папка build\app (открыть в VS Code с расширением Tizen TV).
#   .\build.ps1                         — адрес сайта из SITE_URL или suslik.siteUrl в ~\.gradle\gradle.properties
#   .\build.ps1 -SiteUrl https://…      — адрес явно
#   $env:TIZEN_PROFILE="tv"; .\build.ps1 — ещё и подписанный build\SuslikMedia-<версия>.wgt через Tizen CLI (Tizen Studio)
# Домен сайта в репозиторий не пишем: он попадает только в build\app\js\site.js.
param([string]$SiteUrl = $env:SITE_URL)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

if (-not $SiteUrl) {
    $props = Join-Path $HOME ".gradle\gradle.properties"
    if (Test-Path $props) {
        $line = Get-Content $props | Where-Object { $_ -match '^\s*suslik\.siteUrl\s*=' } | Select-Object -First 1
        if ($line) { $SiteUrl = ($line -split '=', 2)[1].Trim() }
    }
}
$hostName = ($SiteUrl -replace '^https?://', '') -replace '/.*$', ''
if (-not $hostName) {
    Write-Host "Не задан адрес сайта: .\build.ps1 -SiteUrl https://<сайт> (или SITE_URL, или suslik.siteUrl в ~\.gradle\gradle.properties)" -ForegroundColor Red
    exit 1
}
$version = ([xml](Get-Content (Join-Path $root "config.xml") -Encoding UTF8)).widget.version

$build = Join-Path $root "build"
$app = Join-Path $build "app"
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Path $app | Out-Null
foreach ($item in "config.xml", "index.html", "icon.png", "css", "js", "locales") {
    Copy-Item (Join-Path $root $item) -Destination $app -Recurse
}
# UTF-8 без BOM, как у остальных файлов приложения
[System.IO.File]::WriteAllText((Join-Path $app "js\site.js"),
    "window.SM = window.SM || {};`nwindow.SM.SITE_HOST = '$hostName';`n", (New-Object System.Text.UTF8Encoding $false))
Write-Host "Папка приложения: $app (версия $version)" -ForegroundColor Green
Write-Host "Дальше: открыть её в VS Code -> Tizen TV: Build Signed Package -> Tizen TV: Launch Application"

if ($env:TIZEN_PROFILE) {
    if (-not (Get-Command tizen -ErrorAction SilentlyContinue)) {
        Write-Host "Нет Tizen CLI (tizen-studio\tools\ide\bin) в PATH" -ForegroundColor Red
        exit 1
    }
    & tizen package -t wgt -s $env:TIZEN_PROFILE -- $app
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $wgt = Get-ChildItem $app -Filter *.wgt | Select-Object -First 1
    $out = Join-Path $build "SuslikMedia-$version.wgt"
    Move-Item $wgt.FullName $out
    Write-Host "Пакет: $out" -ForegroundColor Green
}
