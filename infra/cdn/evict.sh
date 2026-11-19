#!/bin/bash
# evict.sh — уборка диска CDN Suslik Media (docs/HLS.md §10, раздел CDN).
#
# Каждый запуск (systemd timer, раз в 15 мин):
#   1. обновляет «последнее обращение» к <hls_id>/<profile> по журналу nginx (log_format hls_lru);
#   2. забирает GET /internal/hls/pending-deletes и удаляет указанные профили;
#   3. если диск занят больше HIGH % (85) — удаляет профили по LRU, пока не станет ≤ LOW % (80):
#      сначала менее свежий профиль ассета, у которого их несколько (v1-copy рядом с v1), потом самые старые;
#   4. сообщает POST /internal/hls/evicted (что удалено) и POST /internal/hls/cdn-stats;
#   5. чистит брошенные загрузки в .tmp/ (старше 48 ч).
# Удаление — на уровне профиля; <hls_id>/ удаляется, когда профилей не осталось.
# Профиль, к которому обращались за последние MIN_IDLE секунд (по умолчанию 2 ч), не трогается — его, скорее всего, смотрят;
# это касается и pending-deletes (удалится на следующих запусках).
#
# Настройки — /etc/hls-cdn/cdn.env (или --env FILE): SM_SITE_URL, HLS_CDN_TOKEN, HLS_ROOT, HLS_EVICT_HIGH, HLS_EVICT_LOW,
# HLS_EVICT_MIN_IDLE, HLS_EVICT_STATE, HLS_ACCESS_LOG. Ключи командной строки важнее.
#
# Ключи:
#   --dry-run            ничего не удалять и не отправлять; печатать план и тела запросов
#   --env FILE           файл настроек вместо /etc/hls-cdn/cdn.env
#   --root DIR           корень ассетов (/srv/hls)
#   --log FILE           журнал nginx (можно несколько раз; по умолчанию hls-access.log и .1)
#   --state-dir DIR      состояние: lastaccess, очередь отчётов (/var/lib/hls-evict)
#   --pending-file FILE  взять pending-deletes из файла, а не с сайта
#   --no-report          не отправлять evicted/cdn-stats (отчёты копятся в очереди)
#   --disk-total N --disk-used N   размер и занятость диска в байтах вместо df (для проверок)
#   --high P --low P     пороги, %
#   --min-idle SEC       защитный интервал после последнего обращения
#   --now TS             «текущее» время, Unix-секунды (для проверок)
set -euo pipefail
export LC_ALL=C

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; }

DRY=0; NO_REPORT=0; ENV_FILE=""; PENDING_FILE=""
OPT_ROOT=""; OPT_STATE=""; OPT_HIGH=""; OPT_LOW=""; OPT_MIN_IDLE=""; OPT_NOW=""
DISK_TOTAL=""; DISK_USED=""; LOGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --no-report) NO_REPORT=1 ;;
    --env) ENV_FILE=$2; shift ;;
    --root) OPT_ROOT=$2; shift ;;
    --log) LOGS+=("$2"); shift ;;
    --state-dir) OPT_STATE=$2; shift ;;
    --pending-file) PENDING_FILE=$2; shift ;;
    --disk-total) DISK_TOTAL=$2; shift ;;
    --disk-used) DISK_USED=$2; shift ;;
    --high) OPT_HIGH=$2; shift ;;
    --low) OPT_LOW=$2; shift ;;
    --min-idle) OPT_MIN_IDLE=$2; shift ;;
    --now) OPT_NOW=$2; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "evict: неизвестный ключ $1" >&2; exit 2 ;;
  esac
  shift
done

if [ -n "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  . "$ENV_FILE"
elif [ -r /etc/hls-cdn/cdn.env ]; then
  # shellcheck disable=SC1091
  . /etc/hls-cdn/cdn.env
fi

ROOT=${OPT_ROOT:-${HLS_ROOT:-/srv/hls}}
STATE_DIR=${OPT_STATE:-${HLS_EVICT_STATE:-/var/lib/hls-evict}}
HIGH=${OPT_HIGH:-${HLS_EVICT_HIGH:-85}}
LOW=${OPT_LOW:-${HLS_EVICT_LOW:-80}}
MIN_IDLE=${OPT_MIN_IDLE:-${HLS_EVICT_MIN_IDLE:-7200}}
TMP_MAX_AGE=${HLS_TMP_MAX_AGE:-172800}
NOW=${OPT_NOW:-$(date +%s)}
if [ ${#LOGS[@]} -eq 0 ]; then
  base=${HLS_ACCESS_LOG:-/var/log/nginx/hls-access.log}
  LOGS=("$base" "$base.1")
fi

log() { echo "[evict] $*"; }
warn() { echo "[evict] ВНИМАНИЕ: $*" >&2; }
is_int() { [[ $1 =~ ^[0-9]+$ ]]; }
valid_hid() { [[ $1 =~ ^[0-9a-f]{16}$ ]]; }
valid_prof() { [[ $1 =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]]; }

for v in HIGH LOW MIN_IDLE NOW; do is_int "${!v}" || { echo "evict: $v не число" >&2; exit 2; }; done
[ "$LOW" -le "$HIGH" ] || { echo "evict: LOW > HIGH" >&2; exit 2; }
[ -d "$ROOT" ] || { echo "evict: нет каталога $ROOT" >&2; exit 2; }
command -v jq >/dev/null || { echo "evict: нужен jq" >&2; exit 2; }

WORK=$(mktemp -d)
trap 'rm -rf -- "$WORK"' EXIT
if [ "$DRY" = 0 ]; then mkdir -p "$STATE_DIR"; fi
STATE_FILE="$STATE_DIR/lastaccess"   # строки «<hls_id> <profile> <unix-время>»
SPOOL="$STATE_DIR/evicted.spool"     # строки «<hls_id> <profile> <last_access>», ещё не принятые сайтом
RC=0

# --- адрес сайта и запросы ---------------------------------------------------------------------------------------------
SITE=${SM_SITE_URL:-}
if [ -n "$SITE" ] && [[ ! $SITE =~ ^https?:// ]]; then SITE="https://$SITE"; fi
SITE=${SITE%/}

api() { # api METHOD PATH [BODY_FILE] → тело ответа в stdout
  local method=$1 path=$2 body=${3:-}
  [ -n "$SITE" ] || { warn "SM_SITE_URL не задан"; return 1; }
  [[ ${HLS_CDN_TOKEN:-} =~ ^[A-Za-z0-9._~+/=-]+$ ]] || { warn "HLS_CDN_TOKEN не задан или с недопустимыми символами"; return 1; }
  local args=(-fsS --max-time 30 --retry 2 --retry-delay 3 -X "$method")
  if [ -n "$body" ]; then args+=(-H "Content-Type: application/json" --data-binary "@$body"); fi
  # токен — через конфиг из stdin, чтобы не светился в списке процессов
  printf 'header = "Authorization: Bearer %s"\n' "$HLS_CDN_TOKEN" | curl -K - "${args[@]}" "$SITE$path"
}

# --- 1. последнее обращение по журналу -----------------------------------------------------------------------------------
existing_logs=()
for f in "${LOGS[@]}"; do [ -r "$f" ] && existing_logs+=("$f"); done
touch "$WORK/state.old"
[ -r "$STATE_FILE" ] && cp "$STATE_FILE" "$WORK/state.old"
# журнал: <msec> <hls_id> <profile> <status> <bytes> <method> <ip>; учитываются только отданные (200/206/304)
awk -v statefile="$WORK/state.old" '
  BEGIN {
    while ((getline line < statefile) > 0) {
      n = split(line, a, " ")
      if (n == 3) { k = a[1] " " a[2]; if (a[3] + 0 > t[k] + 0) t[k] = a[3] + 0 }
    }
  }
  NF >= 5 && length($2) == 16 && $2 ~ /^[0-9a-f]+$/ && $3 ~ /^[a-z0-9][a-z0-9-]*$/ && ($4 == 200 || $4 == 206 || $4 == 304) {
    k = $2 " " $3; s = int($1); if (s > t[k] + 0) t[k] = s
  }
  END { for (k in t) print k, t[k] }
' ${existing_logs[@]+"${existing_logs[@]}"} </dev/null | sort > "$WORK/state"

# --- 2. что лежит на диске -----------------------------------------------------------------------------------------------
: > "$WORK/disk"   # «<hls_id> <profile> <байт> <mtime>»
for d in "$ROOT"/*/*/; do
  [ -d "$d" ] || continue
  d=${d%/}; prof=${d##*/}; hdir=${d%/*}; hid=${hdir##*/}
  valid_hid "$hid" && valid_prof "$prof" || continue
  [ -L "$d" ] && continue
  size=$(du -sb -- "$d" | cut -f1)
  mtime=$(stat -c %Y -- "$d")
  echo "$hid $prof $size $mtime" >> "$WORK/disk"
done

# Инвентарь «<hls_id> <profile> <байт> <last> <группа>»: last = max(журнал, время выкладки);
# группа 0 — не самый свежий профиль ассета с несколькими профилями (при равенстве свежее считается v1), 1 — остальные.
awk -v statefile="$WORK/state" '
  BEGIN { while ((getline line < statefile) > 0) { split(line, a, " "); t[a[1] " " a[2]] = a[3] + 0 } }
  {
    k = $1 " " $2; last = $4 + 0; if (t[k] + 0 > last) last = t[k] + 0
    n++; H[n] = $1; P[n] = $2; S[n] = $3; L[n] = last
    cnt[$1]++
    better = (!($1 in best)) || last > bestL[$1] || (last == bestL[$1] && $2 == "v1")
    if (better) { best[$1] = $2; bestL[$1] = last }
  }
  END {
    for (i = 1; i <= n; i++) {
      g = (cnt[H[i]] > 1 && best[H[i]] != P[i]) ? 0 : 1
      print H[i], P[i], S[i], L[i], g
    }
  }
' "$WORK/disk" > "$WORK/inv"

last_of() { awk -v h="$1" -v p="$2" '$1 == h && $2 == p { print $4; f = 1 } END { if (!f) print "" }' "$WORK/inv"; }
size_of() { awk -v h="$1" -v p="$2" '$1 == h && $2 == p { print $3 }' "$WORK/inv"; }

# --- занятость диска ---------------------------------------------------------------------------------------------------
read_df() {
  if [ -n "$DISK_TOTAL" ] && [ -n "$DISK_USED" ]; then
    echo "$DISK_TOTAL $DISK_USED"
  else
    df -B1 --output=size,used -- "$ROOT" | tail -n 1 | awk '{ print $1, $2 }'
  fi
}
read -r TOTAL USED < <(read_df)
is_int "$TOTAL" && is_int "$USED" && [ "$TOTAL" -gt 0 ] || { echo "evict: не удалось узнать размер диска" >&2; exit 2; }
log "диск: занято $USED из $TOTAL байт ($((USED * 100 / TOTAL)) %), порог $HIGH %, цель $LOW %"

# --- удаление ----------------------------------------------------------------------------------------------------------
: > "$WORK/deleted"   # «<hls_id> <profile> <last_access>»
LOCKED=0
take_lock() {
  [ "$DRY" = 0 ] && [ "$LOCKED" = 0 ] || return 0
  if command -v flock >/dev/null && [ -d "$ROOT/.tmp" ]; then
    exec 8>>"$ROOT/.tmp/.lock"
    flock -w 300 8 || { echo "evict: блокировка .tmp/.lock занята" >&2; exit 1; }
  fi
  LOCKED=1
}

delete_profile() { # hid prof last reason
  local hid=$1 prof=$2 last=$3 reason=$4
  valid_hid "$hid" && valid_prof "$prof" || { warn "пропуск подозрительного имени: $hid/$prof"; return 0; }
  if grep -q "^$hid $prof " "$WORK/deleted"; then return 0; fi
  if [ "$DRY" = 1 ]; then
    log "[dry-run] удалить $hid/$prof ($reason, last_access=$last)"
  else
    take_lock
    if [ -e "$ROOT/$hid/$prof" ]; then rm -rf -- "${ROOT:?}/$hid/$prof"; fi
    rmdir -- "$ROOT/$hid" 2>/dev/null || true
    log "удалён $hid/$prof ($reason, last_access=$last)"
  fi
  echo "$hid $prof ${last:-0}" >> "$WORK/deleted"
}

# 2. pending-deletes
pending_json=""
if [ -n "$PENDING_FILE" ]; then
  pending_json=$(cat -- "$PENDING_FILE")
elif [ -n "$SITE" ]; then
  if ! pending_json=$(api GET /internal/hls/pending-deletes); then
    warn "не удалось получить pending-deletes"; pending_json=""; RC=1
  fi
fi
if [ -n "$pending_json" ]; then
  # tr -d '\r': jq под Windows (Git Bash) пишет CRLF
  if ! printf '%s' "$pending_json" | jq -r '.items[]? | "\(.hls_id) \(.profile)"' 2>/dev/null | tr -d '\r' > "$WORK/pending"; then
    warn "pending-deletes: не JSON"; : > "$WORK/pending"; RC=1
  fi
  while read -r hid prof; do
    [ -n "$hid" ] || continue
    if ! valid_hid "$hid" || ! valid_prof "$prof"; then warn "pending-deletes: плохая запись «$hid $prof»"; continue; fi
    last=$(last_of "$hid" "$prof")
    if [ -z "$last" ]; then
      # на диске нет — сообщаем, что удалено, чтобы сайт убрал запись
      last=$(awk -v h="$hid" -v p="$prof" '$1 == h && $2 == p { print $3 }' "$WORK/state")
      delete_profile "$hid" "$prof" "${last:-0}" "pending, на диске нет"
      continue
    fi
    if [ $((NOW - last)) -lt "$MIN_IDLE" ]; then
      log "отложено $hid/$prof (pending, обращались $((NOW - last)) с назад)"
      continue
    fi
    size=$(size_of "$hid" "$prof")
    delete_profile "$hid" "$prof" "$last" "pending"
    USED=$((USED - size))
  done < "$WORK/pending"
fi

# 3. LRU при заполнении выше HIGH
if [ $((USED * 100)) -gt $((TOTAL * HIGH)) ]; then
  target=$((TOTAL * LOW / 100))
  log "занято больше $HIGH %: освобождаю до $LOW % ($((USED - target)) байт)"
  sort -k5,5n -k4,4n -k1,1 -k2,2r "$WORK/inv" > "$WORK/candidates"
  while read -r hid prof size last group; do
    [ "$USED" -gt "$target" ] || break
    grep -q "^$hid $prof " "$WORK/deleted" && continue
    if [ $((NOW - last)) -lt "$MIN_IDLE" ]; then
      log "пропуск $hid/$prof: обращались $((NOW - last)) с назад"
      continue
    fi
    if [ "$group" = 0 ]; then reason="LRU, лишняя версия"; else reason="LRU"; fi
    delete_profile "$hid" "$prof" "$last" "$reason"
    USED=$((USED - size))
  done < "$WORK/candidates"
  if [ $((USED * 100)) -gt $((TOTAL * HIGH)) ]; then
    warn "после уборки всё ещё больше $HIGH % (остальное свежее $MIN_IDLE с)"
  fi
fi

# --- 4. отчёты ---------------------------------------------------------------------------------------------------------
if [ "$DRY" = 0 ]; then
  cat "$WORK/deleted" >> "$SPOOL"
  report_src="$SPOOL"
else
  report_src="$WORK/deleted"
fi
jq -Rn '[inputs | select(length > 0) | split(" ") | {hls_id: .[0], profile: .[1], last_access: (.[2] | tonumber)}]' \
  < "$report_src" > "$WORK/evicted.json"

if [ "$DRY" = 0 ] && [ -z "$DISK_TOTAL" ]; then
  read -r TOTAL USED < <(read_df)
fi
[ "$USED" -ge 0 ] || USED=0
awk 'FILENAME == ARGV[1] { del[$1 " " $2] = 1; next } !(($1 " " $2) in del) { p++; a[$1] = 1 } END { n = 0; for (k in a) n++; print n + 0, p + 0 }' \
  "$WORK/deleted" "$WORK/inv" > "$WORK/counts"
read -r ASSETS PROFILES < "$WORK/counts"
jq -n --argjson t "$TOTAL" --argjson u "$USED" --argjson a "$ASSETS" --argjson p "$PROFILES" --argjson at "$NOW" \
  '{disk_total: $t, disk_used: $u, assets: $a, profiles: $p, at: $at}' > "$WORK/cdn-stats.json"

if [ "$DRY" = 1 ]; then
  echo "evicted: $(jq -c . "$WORK/evicted.json")"
  echo "cdn-stats: $(jq -c . "$WORK/cdn-stats.json")"
else
  cp "$WORK/evicted.json" "$STATE_DIR/last-evicted.json"
  cp "$WORK/cdn-stats.json" "$STATE_DIR/last-cdn-stats.json"
  if [ "$NO_REPORT" = 0 ]; then
    if [ -s "$SPOOL" ]; then
      if api POST /internal/hls/evicted "$WORK/evicted.json" >/dev/null; then
        : > "$SPOOL"; log "evicted отправлен ($(jq length "$WORK/evicted.json") шт.)"
      else
        warn "evicted не отправлен — повторю в следующий раз"; RC=1
      fi
    fi
    if api POST /internal/hls/cdn-stats "$WORK/cdn-stats.json" >/dev/null; then
      log "cdn-stats отправлен"
    else
      warn "cdn-stats не отправлен"; RC=1
    fi
  fi
  # состояние: только профили, которые остались на диске
  awk 'FILENAME == ARGV[1] { del[$1 " " $2] = 1; next } FILENAME == ARGV[2] { keep[$1 " " $2] = 1; next } (($1 " " $2) in keep) && !(($1 " " $2) in del)' \
    "$WORK/deleted" "$WORK/inv" "$WORK/state" > "$WORK/state.new"
  mv -f -- "$WORK/state.new" "$STATE_FILE"
fi

# --- 5. брошенные загрузки ---------------------------------------------------------------------------------------------
if [ -d "$ROOT/.tmp" ]; then
  find "$ROOT/.tmp" -mindepth 1 -maxdepth 1 -type d -mmin +$((TMP_MAX_AGE / 60)) -print0 2>/dev/null |
  while IFS= read -r -d '' t; do
    if [ "$DRY" = 1 ]; then log "[dry-run] удалить брошенную загрузку ${t##*/}"
    else rm -rf -- "$t"; log "удалена брошенная загрузка ${t##*/}"; fi
  done
fi

log "готово: удалено $(wc -l < "$WORK/deleted" | tr -d ' '), ассетов $ASSETS, профилей $PROFILES, занято $USED"
exit "$RC"
