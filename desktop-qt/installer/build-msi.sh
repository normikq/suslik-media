#!/usr/bin/env bash
# MSI Qt-версии из готовой папки программы (после windeployqt). WiX 3 (heat, candle, light) — есть на windows-раннерах GitHub.
#   build-msi.sh <папка программы> <версия> <выход.msi> [diag]
# libmpv-2.dll в MSI не кладётся, даже если лежит в папке: её докачивает программа.
set -euo pipefail
SRC=$1; VER=$2; OUT=$3; DIAG=${4:-}
HERE=$(cd "$(dirname "$0")" && pwd)
ICON="$HERE/../resources/icon.ico"
if [ -n "$DIAG" ]; then
  PRODUCT="Suslik Debug Qt"; UPGRADE="{F79D44DC-CEC2-4619-824F-32180E6B7698}"; FOLDER="Suslik Debug Qt"
else
  PRODUCT="Suslik Media"; UPGRADE="{BFBB56B9-7E7F-4AF8-BA0B-C1BA1A054E70}"; FOLDER="Suslik Media Qt"
fi
W=$(mktemp -d)
mkdir -p "$W/src"
cp -r "$SRC"/. "$W/src/"
rm -f "$W/src/libmpv-2.dll"
win() { cygpath -w "$1"; }
heat dir "$(win "$W/src")" -nologo -cg AppFiles -dr INSTALLDIR -ag -sfrag -srd -sreg -scom -var var.Src -out "$(win "$W/files.wxs")"
candle -nologo -arch x64 -dVersion="$VER" -dProduct="$PRODUCT" -dUpgradeCode="$UPGRADE" -dFolder="$FOLDER" \
  -dIcon="$(win "$ICON")" -dSrc="$(win "$W/src")" -out "$(win "$W")\\" "$(win "$HERE/SuslikMediaQt.wxs")" "$(win "$W/files.wxs")"
# ICE38/64/91: файлы в профиле пользователя без ключа реестра — нормально для установки «для себя» (так же у jpackage)
light -nologo -cultures:ru-RU -sice:ICE38 -sice:ICE64 -sice:ICE91 -sice:ICE61 -spdb \
  -out "$(win "$OUT")" "$(win "$W/SuslikMediaQt.wixobj")" "$(win "$W/files.wixobj")"
rm -rf "$W"
ls -la "$OUT"
