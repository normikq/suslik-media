#!/bin/bash
# hls-archive.sh — копия готовых профилей v1 и окончательных v1-copy (из HEVC) в холодный архив (docs/HLS.md §17), раз в 5 мин по таймеру hls-archive.timer.
#
# Очередь — файлы <hls_id>.<профиль> в HLS_ARCHIVE_QUEUE (/var/lib/hls-archive-queue): их кладёт hls-finalize после приёма
# v1 или v1-copy из HEVC (H.264-копия — временная, не архивируется). Для каждого:
#   rclone copy /srv/hls/<id>/v1/ → $HLS_ARCHIVE_REMOTE/<id>/v1/ (--drive-stop-on-upload-limit, ретраи, --bwlimit по желанию);
#   rclone check --size-only --one-way; запись «<id> v1 <байт> <время>» в $HLS_EVICT_STATE/archived; отчёт POST /internal/hls/archived.
# Профиля на диске уже нет (вытеснен или удалён до архивации) — снимается с очереди. Лимит Google (750 ГБ/сутки):
# rclone выходит с кодом 7, остаток очереди — на следующем запуске. Неотправленный отчёт копится в archived.spool.
# HLS_ARCHIVE_REMOTE пуст — архива нет, скрипт сразу выходит.
#
# Ключи:
#   --enqueue-existing   поставить в очередь все v1 и v1-copy из HEVC на диске, которых ещё нет в архиве (однократно при включении), и выйти
#   --env FILE           файл настроек вместо /etc/hls-cdn/cdn.env
#   --root DIR           корень ассетов (/srv/hls)
#   --state-dir DIR      состояние (/var/lib/hls-evict, общее с evict.sh)
#   --queue DIR          очередь (/var/lib/hls-archive-queue)
#   --no-report          не отправлять archived (копится в archived.spool)
#   --now TS             «текущее» время, Unix-секунды (для проверок)
set -euo pipefail
export LC_ALL=C
HERE=$(cd "$(dirname "$0")" && pwd)

ENV_FILE=""; OPT_ROOT=""; OPT_STATE=""; OPT_QUEUE=""; OPT_NOW=""; ENQUEUE=0; NO_REPORT=0
while [ $# -gt 0 ]; do
  case "$1" in
    --enqueue-existing) ENQUEUE=1 ;;
    --env) ENV_FILE=$2; shift ;;
    --root) OPT_ROOT=$2; shift ;;
    --state-dir) OPT_STATE=$2; shift ;;
    --queue) OPT_QUEUE=$2; shift ;;
    --no-report) NO_REPORT=1 ;;
    --now) OPT_NOW=$2; shift ;;
    -h|--help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "hls-archive: неизвестный ключ $1" >&2; exit 2 ;;
  esac
  shift
done

LOG_TAG=archive
# shellcheck source=lib-archive.sh
. "$HERE/lib-archive.sh"
QUEUE=${OPT_QUEUE:-${HLS_ARCHIVE_QUEUE:-/var/lib/hls-archive-queue}}
SPOOL="$STATE_DIR/archived.spool"     # «<hls_id> <profile> <байт> <время>», ещё не принятые сайтом
NOW=${OPT_NOW:-$(date +%s)}
is_int "$NOW" || { echo "hls-archive: --now не число" >&2; exit 2; }

if [ -z "$REMOTE" ]; then log "архив выключен (HLS_ARCHIVE_REMOTE пуст)"; exit 0; fi
command -v jq >/dev/null || { echo "hls-archive: нужен jq" >&2; exit 2; }
[ -d "$ROOT" ] || { echo "hls-archive: нет каталога $ROOT" >&2; exit 2; }
mkdir -p "$STATE_DIR" "$QUEUE"

# Один запуск за раз (своя блокировка: копирование идёт часами и не должно держать evict.sh)
exec 6>>"$STATE_DIR/.archive.lock"
if command -v flock >/dev/null && ! flock -n 6; then log "предыдущий запуск ещё идёт"; exit 0; fi

# --- однократная постановка уже имеющихся v1 и окончательных v1-copy (из HEVC) ---------------------------------------------
if [ "$ENQUEUE" = 1 ]; then
  n=0
  for d in "$ROOT"/*/v1/ "$ROOT"/*/v1-copy/; do
    d=${d%/}; prof=${d##*/}; hid=${d%/*}; hid=${hid##*/}
    valid_hid "$hid" && [ ! -L "$d" ] && [ -f "$d/manifest.json" ] || continue
    archivable "$prof" "$d/manifest.json" || continue
    [ -n "$(arch_bytes "$hid" "$prof")" ] && continue
    : > "$QUEUE/$hid.$prof"; n=$((n + 1))
  done
  log "поставлено в очередь: $n (обработает таймер hls-archive.timer или запуск без ключей)"
  exit 0
fi

# --- очередь -----------------------------------------------------------------------------------------------------------
COPY_ARGS=(--drive-stop-on-upload-limit)
if [ -n "${HLS_ARCHIVE_BWLIMIT:-}" ]; then COPY_ARGS+=("--bwlimit=$HLS_ARCHIVE_BWLIMIT"); fi
RC=0; DONE=0
for q in "$QUEUE"/*; do
  [ -f "$q" ] || continue
  name=${q##*/}
  if [[ ! $name =~ ^([0-9a-f]{16}).(v1|v1-copy)$ ]]; then warn "чужой файл в очереди: ${name:0:80}"; rm -f -- "$q"; continue; fi
  hid=${BASH_REMATCH[1]}; prof=${BASH_REMATCH[2]}
  src="$ROOT/$hid/$prof"
  if [ ! -d "$src" ] || [ -L "$src" ] || [ ! -f "$src/manifest.json" ]; then
    log "снят с очереди $hid/$prof: на диске нет"
    rm -f -- "$q"; continue
  fi
  m0=$(stat -c %Y -- "$src")
  # sync, а не copy: при перезаливе v1 с тем же hls_id в архиве не остаются файлы старой версии
  rc=0; out=$("$RCLONE" sync "${RCLONE_ARGS[@]}" "${COPY_ARGS[@]}" "$src/" "$REMOTE/$hid/$prof/" 2>&1) || rc=$?
  if [ "$rc" = 7 ]; then
    warn "rclone copy $hid/$prof: фатальная ошибка (лимит загрузки Google?) — остаток очереди на следующем запуске: $(tail -n 2 <<< "$out" | tr '\n' ' ')"
    RC=1; break
  fi
  if [ ! -d "$src" ]; then log "снят с очереди $hid/$prof: удалён во время копирования"; rm -f -- "$q"; continue; fi
  if [ "$rc" != 0 ]; then warn "rclone copy $hid/$prof: код $rc: $(tail -n 2 <<< "$out" | tr '\n' ' ')"; RC=1; continue; fi
  rc=0; out=$("$RCLONE" check "${RCLONE_ARGS[@]}" --size-only --one-way "$src/" "$REMOTE/$hid/$prof/" 2>&1) || rc=$?
  if [ "$rc" != 0 ]; then warn "rclone check $hid/$prof: расхождение (код $rc), повтор позже: $(tail -n 2 <<< "$out" | tr '\n' ' ')"; RC=1; continue; fi
  if [ ! -d "$src" ] || [ "$(stat -c %Y -- "$src")" != "$m0" ]; then
    log "$hid/$prof заменён во время копирования — повтор на следующем запуске"; continue
  fi
  bytes=$(du -sb -- "$src" | cut -f1)
  state_lock
  state_put "$ARCH_FILE" "$hid" "$prof" "$hid $prof $bytes $NOW"
  echo "$hid $prof $bytes $NOW" >> "$SPOOL"
  state_unlock
  rm -f -- "$q"
  DONE=$((DONE + 1))
  log "в архиве $hid/$prof ($bytes байт)"
done

# --- отчёт -------------------------------------------------------------------------------------------------------------
if [ "$NO_REPORT" = 0 ] && [ -s "$SPOOL" ]; then
  WORK=$(mktemp -d)
  trap 'rm -rf -- "$WORK"' EXIT
  jq -Rn '[inputs | select(length > 0) | split(" ") | {hls_id: .[0], profile: .[1], bytes: (.[2] | tonumber), at: (.[3] | tonumber)}]' \
    < "$SPOOL" > "$WORK/archived.json"
  if api POST /internal/hls/archived "$WORK/archived.json" >/dev/null; then
    state_lock; : > "$SPOOL"; state_unlock
    log "archived отправлен ($(jq length "$WORK/archived.json" | tr -d '\r') шт.)"
  else
    warn "archived не отправлен — повторю в следующий раз"; RC=1
  fi
fi

left=$(find "$QUEUE" -maxdepth 1 -type f | wc -l | tr -d ' ')
log "готово: заархивировано $DONE, в очереди $left"
exit "$RC"
