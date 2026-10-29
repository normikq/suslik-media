#!/usr/bin/env bash
# Сборка приложения для Samsung TV: папка build/app (готова для Tizen Studio) и, если есть Tizen CLI, подписанный .wgt.
#   SITE_URL=https://… ./build.sh                 — только папка build/app
#   SITE_URL=https://… TIZEN_PROFILE=tv ./build.sh — ещё и build/SuslikMedia-<версия>.wgt (профиль подписи из Tizen Studio)
# Домен сайта в репозиторий не пишем: он попадает только в build/app/js/site.js.
set -euo pipefail
cd "$(dirname "$0")"

# Домен и, если сервер не в корне, путь: https://домен или https://домен/путь
URL=$(printf '%s' "${SITE_URL:-}" | sed -E 's#[?#].*$##; s#^https?://##; s#/+$##')
HOST=${URL%%/*}
if [ -z "$HOST" ]; then echo "Не задан SITE_URL (адрес сайта)" >&2; exit 1; fi
VERSION=$(sed -n 's/.*<widget[^>]* version="\([^"]*\)".*/\1/p' config.xml)

rm -rf build && mkdir -p build/app
cp -r config.xml index.html icon.png css js locales build/app/
printf "window.SM = window.SM || {};\nwindow.SM.SITE_URL = 'https://%s';\nwindow.SM.SITE_HOST = '%s';\n" "$URL" "$HOST" > build/app/js/site.js
echo "Папка приложения: tizen/build/app (версия $VERSION)"

if [ -n "${TIZEN_PROFILE:-}" ]; then
  command -v tizen >/dev/null || { echo "Нет Tizen CLI (tizen-studio/tools/ide/bin) в PATH" >&2; exit 1; }
  tizen package -t wgt -s "$TIZEN_PROFILE" -- build/app
  mv build/app/*.wgt "build/SuslikMedia-$VERSION.wgt"
  echo "Пакет: tizen/build/SuslikMedia-$VERSION.wgt"
  echo "Установка на ТВ: sdb connect <IP телевизора> && tizen install -n build/SuslikMedia-$VERSION.wgt -t <имя ТВ из sdb devices>"
fi
