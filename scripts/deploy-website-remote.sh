#!/usr/bin/env bash
# Серверная часть выкладки сайта. Запускается из scripts/deploy-website.sh так:
#   ssh user@host bash -s -- <папка> <коммит> <команда перезапуска> <адрес проверки> <dry_run 0|1> <откат 0|1> < этот файл
# Архив с кодом уже лежит в <папка>/.deploy/incoming.tgz.
#
# Что делает: распаковывает архив во временную папку, сравнивает с тем, что на сервере, сохраняет копию
# заменяемых файлов, копирует новые, удаляет файлы, которые раньше выкладывались, а теперь убраны из git,
# ставит зависимости (только если поменялся package-lock.json), перезапускает сайт и проверяет, что он отвечает.
# Если сайт не поднялся — возвращает прежние файлы и перезапускает ещё раз.
#
# Данные сервера (.env, *.json, updates/, changelog/, reports/, poster-cache/, node_modules/) не трогаются:
# копируются только файлы из git, удаляются только файлы из прошлой выкладки.
set -euo pipefail

DIR=${1:?папка сайта}
REF=${2:-unknown}
RESTART_CMD=${3:-}
HEALTH_URL=${4:-}
DRY=${5:-0}
ROLLBACK=${6:-1}
KEEP_BACKUPS=5

log() { printf '[deploy] %s\n' "$*"; }
die() { printf '[deploy] ОШИБКА: %s\n' "$*" >&2; exit 1; }
trap 'printf "[deploy] ОШИБКА: команда в строке %s завершилась с кодом %s\n" "$LINENO" "$?" >&2' ERR

# При входе по ssh без терминала профиль не читается: подхватываем node/npm/pm2 из nvm, если он есть.
if [ -s "$HOME/.nvm/nvm.sh" ]; then
  set +u; . "$HOME/.nvm/nvm.sh" >/dev/null 2>&1 || true; set -u
fi
export PATH="$PATH:/usr/local/bin:$HOME/.local/bin"

[ -d "$DIR" ] || die "нет папки $DIR"
DIR=$(cd "$DIR" && pwd -P)
STATE="$DIR/.deploy"
STAGE="$STATE/staging"
[ -f "$STATE/incoming.tgz" ] || die "архив не загружен ($STATE/incoming.tgz)"

if [ ! -f "$DIR/server.js" ] && [ -n "$(find "$DIR" -mindepth 1 -maxdepth 1 ! -name .deploy ! -name .env | head -n 1)" ]; then
  die "в $DIR нет server.js, но папка не пустая — похоже, DEPLOY_PATH указан неверно"
fi

rm -rf "$STAGE"
mkdir -p "$STAGE"
tar -xzf "$STATE/incoming.tgz" -C "$STAGE"
[ -f "$STAGE/server.js" ] && [ -f "$STAGE/package.json" ] || die "в архиве нет server.js/package.json"

# Списки файлов: новый (из архива) и прошлой выкладки.
(cd "$STAGE" && find . \( -type f -o -type l \) | sed 's|^\./||' | LC_ALL=C sort) > "$STATE/manifest.new"
OLD_MANIFEST="$STATE/manifest"
[ -f "$OLD_MANIFEST" ] || OLD_MANIFEST=/dev/null

: > "$STATE/changed.list"
while IFS= read -r f; do
  cmp -s "$STAGE/$f" "$DIR/$f" 2>/dev/null || printf '%s\n' "$f" >> "$STATE/changed.list"
done < "$STATE/manifest.new"

# Удаляем только то, что сами выкладывали раньше, и никогда — данные и настройки.
: > "$STATE/removed.list"
LC_ALL=C comm -23 "$OLD_MANIFEST" "$STATE/manifest.new" | while IFS= read -r f; do
  case "$f" in
    ""|/*|*..*|.env|.env.*|node_modules/*|.deploy/*) continue ;;
  esac
  if [ -e "$DIR/$f" ]; then printf '%s\n' "$f" >> "$STATE/removed.list"; fi
done

LOCK_NEW=$(sha256sum "$STAGE/package-lock.json" 2>/dev/null | cut -d' ' -f1 || true)
LOCK_OLD=$(cat "$STATE/lock.sha256" 2>/dev/null || true)
NEED_NPM=0
if [ "$LOCK_NEW" != "$LOCK_OLD" ] || [ ! -d "$DIR/node_modules" ]; then NEED_NPM=1; fi

N_CHANGED=$(wc -l < "$STATE/changed.list" | tr -d ' ')
N_REMOVED=$(wc -l < "$STATE/removed.list" | tr -d ' ')
log "коммит $REF → $DIR"
log "новых и изменённых файлов: $N_CHANGED, к удалению: $N_REMOVED, зависимости: $([ "$NEED_NPM" = 1 ] && echo 'переустановить' || echo 'без изменений')"
head -n 40 "$STATE/changed.list" | sed 's/^/  ~ /'
if [ "$N_CHANGED" -gt 40 ]; then log "  … и ещё $((N_CHANGED - 40))"; fi
sed 's/^/  - /' "$STATE/removed.list"
[ -f "$DIR/.env" ] || log "ВНИМАНИЕ: нет $DIR/.env — сайт без него не запустится (образец: .env.example)"

if [ "$DRY" = 1 ]; then
  log "пробный запуск: на сервере ничего не изменено"
  rm -rf "$STAGE" "$STATE/incoming.tgz"
  exit 0
fi

if [ "$N_CHANGED" = 0 ] && [ "$N_REMOVED" = 0 ] && [ "$NEED_NPM" = 0 ]; then
  log "на сервере уже эта версия — перезапуск не нужен"
  cp "$STATE/manifest.new" "$STATE/manifest"
  printf '%s %s\n' "$REF" "$(date -Is)" > "$STATE/last"
  rm -rf "$STAGE" "$STATE/incoming.tgz"
  exit 0
fi

# --- Резервная копия всего, что сейчас будет заменено или удалено ---
TS=$(date +%Y%m%d-%H%M%S)
BACKUP="$STATE/backup-$TS.tgz"
(cd "$DIR" && LC_ALL=C sort -u "$STATE/manifest.new" "$OLD_MANIFEST" | while IFS= read -r f; do
  if [ -e "$f" ]; then printf '%s\n' "$f"; fi
done) > "$STATE/backup.list"
tar -czf "$BACKUP" -C "$DIR" -T "$STATE/backup.list"
cp "$OLD_MANIFEST" "$STATE/backup-$TS.manifest"
log "резервная копия: $BACKUP"
ls -1t "$STATE"/backup-*.tgz 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | while IFS= read -r old; do
  rm -f "$old" "${old%.tgz}.manifest"
done

find_pm2_name() {
  command -v pm2 >/dev/null 2>&1 && command -v node >/dev/null 2>&1 || return 0
  pm2 jlist 2>/dev/null | node -e '
    const fs = require("fs"); let s = "";
    process.stdin.on("data", d => s += d).on("end", () => {
      try {
        const real = p => { try { return fs.realpathSync(p); } catch { return p; } };
        const want = real(process.argv[1]);
        const json = s.slice(s.indexOf("["));
        const p = JSON.parse(json).find(x => x.pm2_env && real(x.pm2_env.pm_cwd || "") === want);
        if (p) process.stdout.write(String(p.name));
      } catch {}
    });' "$DIR" || true
}

find_systemd_unit() {
  command -v systemctl >/dev/null 2>&1 || return 0
  local u wd
  for u in $(systemctl list-units --type=service --state=running --no-legend --plain 2>/dev/null | awk '{print $1}'); do
    wd=$(systemctl show -p WorkingDirectory --value "$u" 2>/dev/null || true)
    wd=${wd#!}
    [ -n "$wd" ] && [ "$(readlink -f "$wd" 2>/dev/null || echo "$wd")" = "$DIR" ] && { printf '%s' "$u"; return 0; }
  done
}

restart_site() {
  if [ -n "$RESTART_CMD" ]; then
    log "перезапуск: $RESTART_CMD"
    (cd "$DIR" && bash -c "$RESTART_CMD")
    return
  fi
  local name unit
  name=$(find_pm2_name)
  if [ -n "$name" ]; then
    log "перезапуск: pm2 restart $name"
    pm2 restart "$name" --update-env >/dev/null
    return
  fi
  unit=$(find_systemd_unit)
  if [ -n "$unit" ]; then
    log "перезапуск: systemctl restart $unit"
    if [ "$(id -u)" = 0 ]; then systemctl restart "$unit"; else sudo -n systemctl restart "$unit"; fi
    return
  fi
  die "не нашёл, как перезапустить сайт (ни pm2, ни служба systemd с WorkingDirectory=$DIR) — задайте DEPLOY_RESTART_CMD"
}

health_check() {
  local url=$HEALTH_URL port code i
  if [ -z "$url" ]; then
    port=$(grep -E '^[[:space:]]*PORT[[:space:]]*=' "$DIR/.env" 2>/dev/null | tail -n 1 | cut -d= -f2- | tr -d ' "\r' || true)
    url="http://127.0.0.1:${port:-3000}/"
  fi
  if ! command -v curl >/dev/null 2>&1; then
    log "curl не установлен — проверку ответа пропускаю"
    return 0
  fi
  sleep 2
  for i in $(seq 1 25); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$url" || true)
    case "$code" in
      2??|3??|401|403|404) log "сайт отвечает: $url → $code"; return 0 ;;
    esac
    sleep 1
  done
  log "сайт не отвечает: $url (последний код: ${code:-нет ответа})"
  return 1
}

install_deps() {
  log "npm ci --omit=dev"
  (cd "$DIR" && npm ci --omit=dev --no-audit --no-fund)
}

DONE=0
ROLLED_BACK=0
rollback() {
  [ "$ROLLED_BACK" = 0 ] || return 0
  ROLLED_BACK=1
  log "откат к прежним файлам из $BACKUP"
  # Файлы, которых до выкладки не было, убираем; прежние — возвращаем.
  LC_ALL=C comm -13 <(LC_ALL=C sort "$STATE/backup.list") "$STATE/manifest.new" | while IFS= read -r f; do
    rm -f "$DIR/$f"
  done
  tar -xzf "$BACKUP" -C "$DIR"
  if [ "$NEED_NPM" = 1 ]; then install_deps || log "npm ci при откате не прошёл — проверьте node_modules вручную"; fi
  restart_site || true
  health_check || log "после отката сайт тоже не отвечает — нужна ручная проверка"
}
on_exit() {
  local rc=$?
  if [ "$DONE" != 1 ] && [ "$ROLLBACK" = 1 ]; then
    rollback || true
    log "выкладка не удалась и откатена"
  elif [ "$DONE" != 1 ]; then
    log "выкладка не удалась; откат выключен, резервная копия: $BACKUP"
  fi
  rm -rf "$STAGE"
  exit "$rc"
}
trap on_exit EXIT

# --- Выкладка ---
cp -a "$STAGE/." "$DIR/"
while IFS= read -r f; do rm -f "$DIR/$f"; done < "$STATE/removed.list"
if [ "$NEED_NPM" = 1 ]; then install_deps; fi
restart_site
health_check || exit 1

cp "$STATE/manifest.new" "$STATE/manifest"
if [ -n "$LOCK_NEW" ]; then printf '%s\n' "$LOCK_NEW" > "$STATE/lock.sha256"; fi
printf '%s %s\n' "$REF" "$(date -Is)" > "$STATE/last"
rm -f "$STATE/incoming.tgz"
DONE=1
log "готово: на сервере коммит $REF"
