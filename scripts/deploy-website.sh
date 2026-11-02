#!/usr/bin/env bash
# Выкладка сайта (website/) на сервер по SSH-ключу. Подробно: docs/DEPLOY.md
#
#   DEPLOY_HOST=… DEPLOY_USER=… DEPLOY_PATH=/abs/path/website DEPLOY_KEY=~/.ssh/suslik_deploy \
#     scripts/deploy-website.sh [--dry-run]
#
# Выкладывается закоммиченное состояние website/ (DEPLOY_REF, по умолчанию HEAD), а не рабочая папка.
# Работает в Linux, macOS и Git Bash на Windows: нужны git, tar, ssh (rsync не нужен).
set -euo pipefail

usage() {
  cat <<'EOF'
Использование: scripts/deploy-website.sh [--dry-run]

Обязательные переменные:
  DEPLOY_HOST          адрес сервера (домен или IP)
  DEPLOY_USER          пользователь SSH
  DEPLOY_PATH          абсолютный путь к папке сайта на сервере (там, где server.js и .env)
Необязательные:
  DEPLOY_PORT          порт SSH (22)
  DEPLOY_KEY           путь к закрытому ключу (иначе — ssh-agent и ~/.ssh/config)
  DEPLOY_KNOWN_HOSTS   файл known_hosts с ключом сервера (иначе — ~/.ssh/known_hosts)
  DEPLOY_REF           что выкладывать: коммит, ветка или тег (HEAD)
  DEPLOY_RESTART_CMD   команда перезапуска на сервере (иначе ищется pm2 или служба systemd по папке)
  DEPLOY_HEALTH_URL    адрес проверки на сервере (http://127.0.0.1:<PORT из .env>/)
  DEPLOY_ROLLBACK      1 — откатить, если сайт не поднялся (1)
  DEPLOY_SKIP_CHECKS   1 — не запускать npm run check перед выкладкой
  DEPLOY_DRY_RUN       1 — то же, что --dry-run: показать, что изменится, ничего не меняя
EOF
}

die() { printf '[deploy] ОШИБКА: %s\n' "$*" >&2; exit 1; }
log() { printf '[deploy] %s\n' "$*"; }

DRY=${DEPLOY_DRY_RUN:-0}
for a in "$@"; do
  case "$a" in
    --dry-run|-n) DRY=1 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "неизвестный параметр: $a" ;;
  esac
done
[ "$DRY" = 1 ] || DRY=0

HOST=${DEPLOY_HOST:-}; USER_=${DEPLOY_USER:-}; RPATH=${DEPLOY_PATH:-}
[ -n "$HOST" ] || die "не задан DEPLOY_HOST"
[ -n "$USER_" ] || die "не задан DEPLOY_USER"
[ -n "$RPATH" ] || die "не задан DEPLOY_PATH"
case "$RPATH" in /*) ;; *) die "DEPLOY_PATH должен быть абсолютным путём (сейчас: $RPATH)" ;; esac
case "$RPATH" in /|/root|/home|/home/*/) die "DEPLOY_PATH=$RPATH похож на ошибку — укажите папку сайта" ;; esac
RPATH=${RPATH%/}
PORT=${DEPLOY_PORT:-22}
REF=${DEPLOY_REF:-HEAD}

for tool in git tar ssh; do command -v "$tool" >/dev/null 2>&1 || die "не найден $tool"; done

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || die "запускайте из репозитория suslik-media"
cd "$ROOT"
SHA=$(git rev-parse --verify --quiet "$REF^{commit}") || die "не найден коммит $REF"
SHORT=$(git rev-parse --short "$SHA")
if [ "$REF" = HEAD ] && ! git diff --quiet HEAD -- website/; then
  log "ВНИМАНИЕ: в website/ есть незакоммиченные правки — они НЕ будут выложены"
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
git archive --format=tar "$SHA" website/ | tar -x -C "$TMP"
PKG="$TMP/website"
[ -f "$PKG/server.js" ] || die "в коммите $SHORT нет website/server.js"

if [ "${DEPLOY_SKIP_CHECKS:-0}" != 1 ]; then
  if command -v node >/dev/null 2>&1; then
    log "проверка синтаксиса (npm run check)"
    (cd "$PKG" && node scripts/check.js >/dev/null) || die "npm run check не прошёл — выкладка отменена"
  else
    log "node не найден — проверку синтаксиса пропускаю"
  fi
fi

tar -czf "$TMP/site.tgz" -C "$PKG" .
SIZE=$(wc -c < "$TMP/site.tgz" | tr -d ' ')

SSH=(ssh -p "$PORT" -o BatchMode=yes -o ConnectTimeout=20 -o ServerAliveInterval=15 -o ServerAliveCountMax=4)
if [ -n "${DEPLOY_KEY:-}" ]; then
  KEY=${DEPLOY_KEY/#\~/$HOME}
  [ -r "$KEY" ] || die "не читается ключ $KEY"
  SSH+=(-i "$KEY" -o IdentitiesOnly=yes)
fi
if [ -n "${DEPLOY_KNOWN_HOSTS:-}" ]; then
  KH=${DEPLOY_KNOWN_HOSTS/#\~/$HOME}
  [ -r "$KH" ] || die "не читается $KH"
  SSH+=(-o "UserKnownHostsFile=$KH" -o StrictHostKeyChecking=yes)
fi
TARGET="$USER_@$HOST"

q() { printf '%q' "$1"; }

log "коммит $SHORT ($REF) → $TARGET:$RPATH$([ "$DRY" = 1 ] && echo ' [пробный запуск]')"
log "загрузка архива ($((SIZE / 1024)) КБ)"
"${SSH[@]}" "$TARGET" "mkdir -p $(q "$RPATH/.deploy") && cat > $(q "$RPATH/.deploy/incoming.tgz")" < "$TMP/site.tgz" \
  || die "не удалось загрузить архив по ssh (ключ, known_hosts, права на $RPATH?)"

"${SSH[@]}" "$TARGET" "bash -s -- $(q "$RPATH") $(q "$SHORT") $(q "${DEPLOY_RESTART_CMD:-}") $(q "${DEPLOY_HEALTH_URL:-}") $DRY $(q "${DEPLOY_ROLLBACK:-1}")" \
  < "$ROOT/scripts/deploy-website-remote.sh"
