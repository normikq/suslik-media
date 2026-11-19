# lib-archive.sh — общее для hls-archive.sh и hls-restore.sh (холодный архив, docs/HLS.md §17). Подключается через «.».
# До подключения задать: ENV_FILE (пусто — /etc/hls-cdn/cdn.env), OPT_ROOT, OPT_STATE, LOG_TAG.
# shellcheck shell=bash

if [ -n "${ENV_FILE:-}" ]; then
  # shellcheck disable=SC1090
  . "$ENV_FILE"
elif [ -r /etc/hls-cdn/cdn.env ]; then
  # shellcheck disable=SC1091
  . /etc/hls-cdn/cdn.env
fi

ROOT=${OPT_ROOT:-${HLS_ROOT:-/srv/hls}}
STATE_DIR=${OPT_STATE:-${HLS_EVICT_STATE:-/var/lib/hls-evict}}
ARCH_FILE="$STATE_DIR/archived"      # «<hls_id> <profile> <байт> <unix-время>» — копия в архиве проверена
REMOTE=${HLS_ARCHIVE_REMOTE:-}
REMOTE=${REMOTE%/}
RCLONE=${HLS_RCLONE:-rclone}
RCLONE_ARGS=(--retries=3 --low-level-retries=10)
if [ -n "${HLS_RCLONE_CONFIG:-}" ]; then RCLONE_ARGS+=("--config=$HLS_RCLONE_CONFIG"); fi

log() { echo "[$LOG_TAG] $*"; }
warn() { echo "[$LOG_TAG] ВНИМАНИЕ: $*" >&2; }
is_int() { [[ $1 =~ ^[0-9]+$ ]]; }
valid_hid() { [[ $1 =~ ^[0-9a-f]{16}$ ]]; }

# --- сайт (как в evict.sh) ---------------------------------------------------------------------------------------------
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

# --- состояние: общая блокировка с evict.sh ($STATE_DIR/.lock, fd 7) ------------------------------------------------------
state_lock() {
  mkdir -p "$STATE_DIR"
  exec 7>>"$STATE_DIR/.lock"
  if command -v flock >/dev/null; then flock -w 900 7 || { warn "блокировка $STATE_DIR/.lock занята"; return 1; }; fi
}
state_unlock() { exec 7>&-; }

# Заменить строку «<hid> <prof> …» в файле (без NEW — удалить). Вызывать под state_lock.
state_put() { # FILE HID PROF [NEW_LINE]
  local f=$1 hid=$2 prof=$3 line=${4:-}
  { [ -f "$f" ] && awk -v h="$hid" -v p="$prof" '!($1 == h && $2 == p)' "$f"; [ -n "$line" ] && echo "$line"; } > "$f.new" || true
  mv -f -- "$f.new" "$f"
}
arch_bytes() { [ -r "$ARCH_FILE" ] && awk -v h="$1" -v p="$2" '$1 == h && $2 == p { print $3; exit }' "$ARCH_FILE"; return 0; }
