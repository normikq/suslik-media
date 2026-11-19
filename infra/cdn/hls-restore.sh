#!/bin/bash
# hls-restore.sh — возврат версий из холодного архива на CDN (docs/HLS.md §17), служба hls-restore.service.
#
# Цикл: каждые HLS_RESTORE_POLL_S секунд (15) GET /internal/hls/pending-restores, запросы — по одному:
#   профиль уже на диске → сразу ok:true;
#   размер — из состояния архива ($HLS_EVICT_STATE/archived) или «rclone size»; в архиве нет → ok:false, error:"not_in_archive";
#   место — evict.sh --reserve <байт> (вытеснение до порога с учётом возвращаемого);
#   rclone copy → /srv/hls/.tmp/<id>.<profile>.<rand>/, затем bin/hls-finalize (manifest.json, атомарный mv) с
#   HLS_FINALIZE_NO_ARCHIVE=1 — заново в очередь архивации не ставится; от root — под пользователем hls-upload;
#   «последнее обращение» = сейчас (вытеснение не тронет его ближайшие 2 ч); POST /internal/hls/restored {ok:true, bytes}.
#   Иная ошибка → ok:false с текстом (сайт повторит при следующем опросе).
# HLS_ARCHIVE_REMOTE пуст — архива нет, служба сразу выходит.
#
# Ключи:
#   --once               один опрос и выход (для проверок и ручного запуска)
#   --env FILE           файл настроек вместо /etc/hls-cdn/cdn.env
#   --root DIR           корень ассетов (/srv/hls)
#   --state-dir DIR      состояние (/var/lib/hls-evict, общее с evict.sh)
#   --now TS             «текущее» время, Unix-секунды (для проверок)
# HLS_EVICT_EXTRA — дополнительные ключи для evict.sh (для проверок: --disk-total/--disk-used и т. п.).
set -euo pipefail
export LC_ALL=C
HERE=$(cd "$(dirname "$0")" && pwd)

ENV_FILE=""; OPT_ROOT=""; OPT_STATE=""; OPT_NOW=""; ONCE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --once) ONCE=1 ;;
    --env) ENV_FILE=$2; shift ;;
    --root) OPT_ROOT=$2; shift ;;
    --state-dir) OPT_STATE=$2; shift ;;
    --now) OPT_NOW=$2; shift ;;
    -h|--help) sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "hls-restore: неизвестный ключ $1" >&2; exit 2 ;;
  esac
  shift
done

LOG_TAG=restore
# shellcheck source=lib-archive.sh
. "$HERE/lib-archive.sh"
POLL_S=${HLS_RESTORE_POLL_S:-15}
UPLOAD_USER=${HLS_UPLOAD_USER:-hls-upload}
EVICT=${HLS_EVICT:-$HERE/evict.sh}
FINALIZE=${HLS_FINALIZE:-}
if [ -z "$FINALIZE" ]; then
  if [ -x "$HERE/hls-finalize" ]; then FINALIZE="$HERE/hls-finalize"; else FINALIZE="$HERE/bin/hls-finalize"; fi
fi
is_int "$POLL_S" && [ "$POLL_S" -gt 0 ] || { echo "hls-restore: HLS_RESTORE_POLL_S не число" >&2; exit 2; }
if [ -n "$OPT_NOW" ] && ! is_int "$OPT_NOW"; then echo "hls-restore: --now не число" >&2; exit 2; fi

if [ -z "$REMOTE" ]; then log "архив выключен (HLS_ARCHIVE_REMOTE пуст)"; exit 0; fi
command -v jq >/dev/null || { echo "hls-restore: нужен jq" >&2; exit 2; }
[ -d "$ROOT" ] || { echo "hls-restore: нет каталога $ROOT" >&2; exit 2; }
mkdir -p "$STATE_DIR"

WORK=$(mktemp -d)
trap 'rm -rf -- "$WORK"' EXIT

now() { echo "${OPT_NOW:-$(date +%s)}"; }
oneline() { tail -n 2 <<< "$1" | tr '\n' ' ' | cut -c1-300; }

report() { # hid prof ok bytes|error
  local hid=$1 prof=$2 ok=$3 val=$4
  if [ "$ok" = true ]; then
    jq -n --arg h "$hid" --arg p "$prof" --argjson b "$val" '[{hls_id: $h, profile: $p, ok: true, bytes: $b}]' > "$WORK/restored.json"
    log "возвращён $hid/$prof ($val байт)"
  else
    jq -n --arg h "$hid" --arg p "$prof" --arg e "$val" '[{hls_id: $h, profile: $p, ok: false, error: $e}]' > "$WORK/restored.json"
    warn "не возвращён $hid/$prof: $val"
  fi
  # не дошло — не страшно: сайт снова попросит, профиль уже на диске → ok:true сразу
  api POST /internal/hls/restored "$WORK/restored.json" >/dev/null || warn "restored не отправлен ($hid/$prof)"
}

mark_access() { # hid prof — «последнее обращение» = сейчас (evict.sh берёт максимум из журнала и этого файла)
  state_lock
  state_put "$STATE_DIR/lastaccess" "$1" "$2" "$1 $2 $(now)"
  state_unlock
}

restore_one() { # hid prof
  local hid=$1 prof=$2 dst bytes out rc rand tmp name
  dst="$ROOT/$hid/$prof"
  if [ -f "$dst/manifest.json" ] && [ ! -L "$dst" ]; then
    bytes=$(du -sb -- "$dst" | cut -f1)
    mark_access "$hid" "$prof"
    log "$hid/$prof уже на диске"
    report "$hid" "$prof" true "$bytes"; return
  fi

  # размер возвращаемого: из состояния архива, иначе у самого архива
  bytes=$(arch_bytes "$hid" "$prof")
  if [ -z "$bytes" ]; then
    rc=0; out=$("$RCLONE" size --json "${RCLONE_ARGS[@]}" "$REMOTE/$hid/$prof" 2>&1) || rc=$?
    if [ "$rc" = 3 ] || { [ "$rc" = 0 ] && [ "$(jq -r '.count // 0' <<< "$out" 2>/dev/null | tr -d '\r')" = 0 ]; }; then
      report "$hid" "$prof" false not_in_archive; return
    fi
    if [ "$rc" != 0 ]; then report "$hid" "$prof" false "rclone size: код $rc: $(oneline "$out")"; return; fi
    bytes=$(jq -r '.bytes // 0' <<< "$out" | tr -d '\r')
    is_int "$bytes" || bytes=0
  fi

  # место: вытеснение до порога с учётом возвращаемого (evict.sh сам берёт блокировки)
  # shellcheck disable=SC2086
  if ! "$EVICT" --root "$ROOT" --state-dir "$STATE_DIR" ${ENV_FILE:+--env "$ENV_FILE"} --reserve "$bytes" ${HLS_EVICT_EXTRA:-} \
      > "$WORK/evict.log" 2>&1; then
    warn "evict.sh завершился с ошибкой (продолжаю): $(oneline "$(cat "$WORK/evict.log")")"
  fi
  local avail
  avail=$(df -B1 --output=avail -- "$ROOT" 2>/dev/null | tail -n 1 | tr -d ' ' || true)
  if is_int "$avail" && [ "$avail" -lt "$bytes" ]; then
    report "$hid" "$prof" false "мало места: нужно $bytes, свободно $avail"; return
  fi

  mkdir -p "$ROOT/.tmp"
  rand=$(od -An -tx1 -N6 /dev/urandom | tr -d ' \n')
  name="$hid.$prof.r$rand"
  tmp="$ROOT/.tmp/$name"
  rc=0; out=$("$RCLONE" copy "${RCLONE_ARGS[@]}" "$REMOTE/$hid/$prof/" "$tmp/" 2>&1) || rc=$?
  if [ "$rc" != 0 ]; then
    rm -rf -- "$tmp"
    if [ "$rc" = 3 ]; then report "$hid" "$prof" false not_in_archive
    else report "$hid" "$prof" false "rclone copy: код $rc: $(oneline "$out")"; fi
    return
  fi
  if [ ! -d "$tmp" ] || [ -z "$(ls -A -- "$tmp")" ]; then
    rm -rf -- "$tmp"; report "$hid" "$prof" false not_in_archive; return
  fi

  # finalize — тот же, что у воркера; от root — под hls-upload, чтобы следующий перезалив воркером мог заменить профиль
  rc=0
  if [ "$(id -u)" = 0 ] && id "$UPLOAD_USER" >/dev/null 2>&1; then
    chown -R "$UPLOAD_USER:$UPLOAD_USER" -- "$tmp"
    out=$(setpriv --reuid="$UPLOAD_USER" --regid="$UPLOAD_USER" --init-groups \
      env HLS_FINALIZE_NO_ARCHIVE=1 "$FINALIZE" "$name" 2>&1) || rc=$?
  else
    out=$(HLS_FINALIZE_NO_ARCHIVE=1 HLS_ROOT="$ROOT" "$FINALIZE" "$name" 2>&1) || rc=$?
  fi
  if [ "$rc" != 0 ] || [[ ! $out =~ (^|$'\n')ok\ $hid/$prof\ ([0-9]+) ]]; then
    rm -rf -- "$tmp"
    report "$hid" "$prof" false "finalize: $(oneline "$out")"; return
  fi
  bytes=${BASH_REMATCH[2]}
  mark_access "$hid" "$prof"
  # состояние архива могло потеряться — копия в архиве есть, запомним
  if [ -z "$(arch_bytes "$hid" "$prof")" ]; then
    state_lock; state_put "$ARCH_FILE" "$hid" "$prof" "$hid $prof $bytes $(now)"; state_unlock
  fi
  report "$hid" "$prof" true "$bytes"
}

poll_once() {
  local json hid prof
  if ! json=$(api GET /internal/hls/pending-restores); then warn "не удалось получить pending-restores"; return 1; fi
  [ -n "$json" ] || return 0
  if ! jq -r '.items[]? | "\(.hls_id) \(.profile)"' <<< "$json" 2>/dev/null | tr -d '\r' > "$WORK/pending"; then
    warn "pending-restores: не JSON"; return 1
  fi
  while read -r hid prof <&3; do
    [ -n "$hid" ] || continue
    if ! valid_hid "$hid" || [[ ! $prof =~ ^(v1|v1-copy)$ ]]; then warn "pending-restores: плохая запись «${hid:0:40} ${prof:0:40}»"; continue; fi
    restore_one "$hid" "$prof"
  done 3< "$WORK/pending"
}

if [ "$ONCE" = 1 ]; then poll_once; exit $?; fi
log "запущен: архив $REMOTE, опрос раз в $POLL_S с"
while true; do
  poll_once || true
  sleep "$POLL_S"
done
