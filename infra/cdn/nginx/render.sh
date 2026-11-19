#!/bin/bash
# render.sh — конфиги nginx CDN из шаблонов (install.sh и офлайн-проверки test/archive-stream-offline.sh).
#
#   render.sh MAIN_OUT INCLUDE_DIR
#
# MAIN_OUT     — сайт nginx (/etc/nginx/conf.d/hls-cdn.conf)
# INCLUDE_DIR  — куда положить hls-archive-stream.conf и hls-archive-stream-server.conf (/etc/nginx); MAIN_OUT включает их по этому пути.
# Переменные: HLS_CDN_DOMAIN HLS_CORS_ORIGIN HLS_ROOT HLS_TLS_CERT HLS_TLS_KEY HLS_SECRET_CONF HLS_LOG_DIR (как в шаблоне),
#   HLS_ARCHIVE_STREAM, HLS_ARCHIVE_REMOTE — просмотр прямо из архива (§20): оба файла с содержимым только при HLS_ARCHIVE_STREAM=1
#   и непустом HLS_ARCHIVE_REMOTE, иначе пустые (всё как раньше); HLS_ARCHIVE_ADDR — адрес rclone serve (127.0.0.1:8781).
# Печатает «archive-stream: on» или «archive-stream: off».
set -euo pipefail
[ $# = 2 ] || { echo "использование: render.sh MAIN_OUT INCLUDE_DIR" >&2; exit 2; }
MAIN_OUT=$1
INC=${2%/}
TPL=$(cd "$(dirname "$0")" && pwd)

STREAM_CONF="$INC/hls-archive-stream.conf"
STREAM_SERVER_CONF="$INC/hls-archive-stream-server.conf"
HLS_ARCHIVE_ADDR=${HLS_ARCHIVE_ADDR:-127.0.0.1:8781}

stream=off
if [ "${HLS_ARCHIVE_STREAM:-0}" = 1 ]; then
  if [ -n "${HLS_ARCHIVE_REMOTE:-}" ]; then
    stream=on
  else
    echo "ВНИМАНИЕ: HLS_ARCHIVE_STREAM=1, но HLS_ARCHIVE_REMOTE пуст — просмотр из архива выключен" >&2
  fi
fi

if [ "$stream" = on ]; then
  cp -- "$TPL/hls-archive-stream.conf.template" "$STREAM_CONF.new"
  HLS_LOG_DIR=$HLS_LOG_DIR HLS_ARCHIVE_ADDR=$HLS_ARCHIVE_ADDR \
    envsubst '${HLS_LOG_DIR} ${HLS_ARCHIVE_ADDR}' < "$TPL/hls-archive-stream-server.conf.template" > "$STREAM_SERVER_CONF.new"
else
  printf '# просмотр прямо из архива выключен (HLS_ARCHIVE_STREAM, docs/HLS.md §20)\n' > "$STREAM_CONF.new"
  cp -- "$STREAM_CONF.new" "$STREAM_SERVER_CONF.new"
fi
mv -f -- "$STREAM_CONF.new" "$STREAM_CONF"
mv -f -- "$STREAM_SERVER_CONF.new" "$STREAM_SERVER_CONF"

HLS_ARCHIVE_STREAM_CONF=$STREAM_CONF HLS_ARCHIVE_STREAM_SERVER_CONF=$STREAM_SERVER_CONF \
  envsubst '${HLS_CDN_DOMAIN} ${HLS_CORS_ORIGIN} ${HLS_ROOT} ${HLS_TLS_CERT} ${HLS_TLS_KEY} ${HLS_SECRET_CONF} ${HLS_LOG_DIR} ${HLS_ARCHIVE_STREAM_CONF} ${HLS_ARCHIVE_STREAM_SERVER_CONF}' \
  < "$TPL/hls-cdn.conf.template" > "$MAIN_OUT"
echo "archive-stream: $stream"
