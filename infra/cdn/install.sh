#!/bin/bash
# install.sh — установка CDN Suslik Media для HLS на Ubuntu 22.04+/Debian 12+ (от root, можно запускать повторно).
#
#   sudo ./install.sh [--disk /dev/sdb1] [--upload-key "ssh-ed25519 AAAA… encoder"] [--sample] [--no-certbot] [--no-ufw]
#
# Перед первым запуском: заполнить /etc/hls-cdn/cdn.env (образец — cdn.env.example; скрипт сам положит его, если файла нет).
# Что делает: пакеты; HDD в /srv/hls с noatime (только уже размеченный раздел — форматирование руками, см. README);
# пользователь hls-upload с rrsync; nginx из шаблона (секрет — отдельным файлом 0640); certbot (webroot);
# ufw (ssh, 80, 443); fail2ban (sshd + перебор подписей); logrotate; таймер evict.sh; по --sample — образец для test.sh;
# при HLS_ARCHIVE_REMOTE в cdn.env — холодный архив: rclone, очередь, таймер hls-archive и служба hls-restore (README).
set -euo pipefail
cd "$(dirname "$0")"
SRC=$(pwd)

DISK=""; UPLOAD_KEY=""; SAMPLE=0; CERTBOT=1; UFW=1
while [ $# -gt 0 ]; do
  case "$1" in
    --disk) DISK=$2; shift ;;
    --upload-key) UPLOAD_KEY=$2; shift ;;
    --sample) SAMPLE=1 ;;
    --no-certbot) CERTBOT=0 ;;
    --no-ufw) UFW=0 ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "неизвестный ключ $1" >&2; exit 2 ;;
  esac
  shift
done

[ "$(id -u)" = 0 ] || { echo "нужен root" >&2; exit 1; }
step() { echo; echo "== $*"; }

ENV=/etc/hls-cdn/cdn.env
if [ ! -f "$ENV" ]; then
  install -d -m 755 /etc/hls-cdn
  install -m 600 cdn.env.example "$ENV"
  echo "Создан $ENV — заполните его и запустите install.sh ещё раз." >&2
  exit 1
fi
chmod 600 "$ENV"
# shellcheck disable=SC1090
. "$ENV"
HLS_ROOT=${HLS_ROOT:-/srv/hls}
HLS_LOG_DIR=/var/log/nginx
LIB=/usr/local/lib/hls-cdn
SSH_PORT=${SSH_PORT:-22}

[[ ${HLS_SIGN_SECRET:-} =~ ^[A-Za-z0-9._~+/=-]{16,}$ ]] || { echo "HLS_SIGN_SECRET: не меньше 16 символов из A-Z a-z 0-9 . _ ~ + / = -" >&2; exit 1; }
[[ ${HLS_CDN_DOMAIN:-} =~ ^[A-Za-z0-9.-]+$ ]] || { echo "HLS_CDN_DOMAIN не задан" >&2; exit 1; }
[[ ${HLS_CORS_ORIGIN:-} =~ ^https?://[A-Za-z0-9.:-]+$ ]] || { echo "HLS_CORS_ORIGIN: origin вида https://site.example.org" >&2; exit 1; }
[ -n "${SM_SITE_URL:-}" ] || echo "ВНИМАНИЕ: SM_SITE_URL пуст — evict.sh не сможет отчитываться сайту" >&2
[ -n "${HLS_CDN_TOKEN:-}" ] || echo "ВНИМАНИЕ: HLS_CDN_TOKEN пуст — evict.sh не сможет отчитываться сайту" >&2
ARCHIVE_REMOTE=${HLS_ARCHIVE_REMOTE:-}
RCLONE_CONF=${HLS_RCLONE_CONFIG:-/etc/hls-cdn/rclone.conf}
ARCHIVE_QUEUE=${HLS_ARCHIVE_QUEUE:-/var/lib/hls-archive-queue}

step "Пакеты"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q nginx rsync jq curl ufw fail2ban certbot gettext-base openssh-server util-linux logrotate
# rclone — для холодного архива; уже стоит (например, с rclone.org) — не трогаем. Нужен ≥ 1.54 (--drive-stop-on-upload-limit):
# в Ubuntu 22.04 пакет 1.53 — тогда ставить с rclone.org (README, «Архив на Google Drive»).
command -v rclone >/dev/null || apt-get install -y -q rclone || echo "ВНИМАНИЕ: rclone из apt не поставился" >&2
if command -v rclone >/dev/null; then
  rv=$(rclone version 2>/dev/null | sed -n '1s/^rclone v\([0-9]*\)\.\([0-9]*\).*/\1 \2/p')
  read -r rmaj rmin <<< "${rv:-0 0}"
  if [ "$rmaj" -lt 1 ] || { [ "$rmaj" = 1 ] && [ "$rmin" -lt 54 ]; }; then
    msg="rclone ${rmaj}.${rmin} старше 1.54 — поставьте свежий: curl https://rclone.org/install.sh | sudo bash"
    if [ -n "$ARCHIVE_REMOTE" ]; then echo "$msg" >&2; exit 1; else echo "ВНИМАНИЕ: $msg" >&2; fi
  fi
fi

step "Диск $HLS_ROOT"
mkdir -p "$HLS_ROOT"
if [ -n "$DISK" ]; then
  uuid=$(blkid -s UUID -o value "$DISK" || true)
  fstype=$(blkid -s TYPE -o value "$DISK" || true)
  [ -n "$uuid" ] && [ -n "$fstype" ] || { echo "на $DISK нет файловой системы — разметьте вручную (README, «Диск»)" >&2; exit 1; }
  if ! grep -q "^UUID=$uuid " /etc/fstab; then
    echo "UUID=$uuid $HLS_ROOT $fstype defaults,noatime,nofail 0 2" >> /etc/fstab
    echo "добавлено в /etc/fstab"
  fi
  systemctl daemon-reload
  mountpoint -q "$HLS_ROOT" || mount "$HLS_ROOT"
fi
if mountpoint -q "$HLS_ROOT"; then
  if ! findmnt -no OPTIONS "$HLS_ROOT" | tr ',' '\n' | grep -qx noatime; then
    mount -o remount,noatime "$HLS_ROOT"
    echo "перемонтировано с noatime (не забудьте noatime в /etc/fstab)"
  fi
  findmnt "$HLS_ROOT"
else
  echo "ВНИМАНИЕ: $HLS_ROOT не отдельный раздел (корневой диск). Для HDD: --disk /dev/sdXN" >&2
fi

step "Пользователь hls-upload и rrsync"
id hls-upload >/dev/null 2>&1 || useradd --system --create-home --home-dir /home/hls-upload --shell /bin/bash hls-upload
passwd -l hls-upload >/dev/null
chown hls-upload:hls-upload "$HLS_ROOT"
chmod 755 "$HLS_ROOT"
install -d -o hls-upload -g hls-upload -m 700 "$HLS_ROOT/.tmp"
touch "$HLS_ROOT/.tmp/.lock"; chown hls-upload:hls-upload "$HLS_ROOT/.tmp/.lock"

RRSYNC=""
for c in /usr/bin/rrsync /usr/local/bin/rrsync; do [ -x "$c" ] && RRSYNC=$c && break; done
if [ -z "$RRSYNC" ]; then
  # старые пакеты rsync кладут rrsync только в документацию
  for c in /usr/share/doc/rsync/scripts/rrsync /usr/share/doc/rsync/scripts/rrsync.gz; do
    if [ -f "$c" ]; then
      case "$c" in *.gz) zcat "$c" > /usr/local/bin/rrsync ;; *) cp "$c" /usr/local/bin/rrsync ;; esac
      chmod 755 /usr/local/bin/rrsync; RRSYNC=/usr/local/bin/rrsync; break
    fi
  done
fi
[ -n "$RRSYNC" ] || { echo "rrsync не найден (нужен rsync ≥ 3.2.4 или скрипт из документации rsync)" >&2; exit 1; }
"$RRSYNC" -help 2>&1 | grep -q -- '-wo' || echo "ВНИМАНИЕ: $RRSYNC не знает -wo — нужна версия rrsync из rsync 3.2.4+" >&2

install -d -m 755 "$LIB"
install -m 755 bin/hls-upload-shell bin/hls-finalize evict.sh hls-archive.sh hls-restore.sh test.sh make-sample.sh "$LIB/"
install -m 644 lib-sign.sh lib-archive.sh "$LIB/"
if [ -f ../../docs/hls-fixtures/vectors.json ]; then install -m 644 ../../docs/hls-fixtures/vectors.json "$LIB/"; fi
# без секретов: читается пользователем hls-upload
printf 'HLS_ROOT=%s\nRRSYNC=%s\nLIB_DIR=%s\n' "$HLS_ROOT" "$RRSYNC" "$LIB" > /etc/hls-cdn/upload.env
if [ -n "$ARCHIVE_REMOTE" ]; then
  # Очередь архивации: hls-finalize (от hls-upload) кладёт <hls_id>.v1; каталог только его, root читает и чистит.
  # Отдельно от /var/lib/hls-evict (0700 root), чтобы hls-upload не получал доступа к состоянию.
  install -d -o hls-upload -g hls-upload -m 700 "$ARCHIVE_QUEUE"
  printf 'HLS_ARCHIVE_QUEUE=%s\n' "$ARCHIVE_QUEUE" >> /etc/hls-cdn/upload.env
fi
chmod 644 /etc/hls-cdn/upload.env

install -d -o hls-upload -g hls-upload -m 700 /home/hls-upload/.ssh
AK=/home/hls-upload/.ssh/authorized_keys
touch "$AK"; chown hls-upload:hls-upload "$AK"; chmod 600 "$AK"
if [ -n "$UPLOAD_KEY" ]; then
  [[ $UPLOAD_KEY =~ ^(ssh-ed25519|ssh-rsa|ecdsa-sha2-[a-z0-9-]+)\ [A-Za-z0-9+/=]+(\ .*)?$ ]] || { echo "--upload-key: не похоже на открытый ключ" >&2; exit 1; }
  keybody=$(awk '{ print $2 }' <<< "$UPLOAD_KEY")
  if ! grep -qF "$keybody" "$AK"; then
    echo "command=\"$LIB/hls-upload-shell\",restrict $UPLOAD_KEY" >> "$AK"
    echo "ключ воркера добавлен"
  fi
fi
grep -q . "$AK" || echo "ВНИМАНИЕ: у hls-upload нет ключей — добавьте: install.sh --upload-key \"ssh-ed25519 …\"" >&2

step "nginx"
umask 027
export HLS_SIGN_SECRET
envsubst '${HLS_SIGN_SECRET}' < nginx/hls-secret.conf.template > /etc/nginx/hls-secret.conf
chown root:root /etc/nginx/hls-secret.conf; chmod 640 /etc/nginx/hls-secret.conf
umask 022
install -d -m 755 /var/www/certbot
install -m 644 nginx/logrotate-hls /etc/logrotate.d/nginx-hls

CERT=/etc/letsencrypt/live/$HLS_CDN_DOMAIN/fullchain.pem
KEY=/etc/letsencrypt/live/$HLS_CDN_DOMAIN/privkey.pem
render_full() {
  HLS_CDN_DOMAIN=$HLS_CDN_DOMAIN HLS_CORS_ORIGIN=$HLS_CORS_ORIGIN HLS_ROOT=$HLS_ROOT HLS_TLS_CERT=$CERT HLS_TLS_KEY=$KEY \
  HLS_SECRET_CONF=/etc/nginx/hls-secret.conf HLS_LOG_DIR=$HLS_LOG_DIR \
    envsubst '${HLS_CDN_DOMAIN} ${HLS_CORS_ORIGIN} ${HLS_ROOT} ${HLS_TLS_CERT} ${HLS_TLS_KEY} ${HLS_SECRET_CONF} ${HLS_LOG_DIR}' \
    < nginx/hls-cdn.conf.template > /etc/nginx/conf.d/hls-cdn.conf
}
if [ ! -f "$CERT" ]; then
  if [ "$CERTBOT" = 1 ]; then
    # сертификата ещё нет: временно только порт 80 для проверки certbot
    cat > /etc/nginx/conf.d/hls-cdn.conf <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name $HLS_CDN_DOMAIN;
    location ^~ /.well-known/acme-challenge/ { root /var/www/certbot; }
    location / { return 404; }
}
EOF
    nginx -t && systemctl reload nginx
    [ "$UFW" = 1 ] && ufw allow 80/tcp >/dev/null
    if [ -n "${CERTBOT_EMAIL:-}" ]; then mail=(--email "$CERTBOT_EMAIL"); else mail=(--register-unsafely-without-email); fi
    certbot certonly --webroot -w /var/www/certbot -d "$HLS_CDN_DOMAIN" -n --agree-tos "${mail[@]}" \
      --deploy-hook "systemctl reload nginx"
  else
    echo "нет $CERT и --no-certbot: положите сертификат сами и запустите ещё раз" >&2; exit 1
  fi
fi
render_full
nginx -t
systemctl enable --now nginx
systemctl reload nginx

if [ "$UFW" = 1 ]; then
  step "ufw"
  ufw default deny incoming >/dev/null
  ufw default allow outgoing >/dev/null
  ufw allow "$SSH_PORT"/tcp >/dev/null
  ufw allow 80/tcp >/dev/null
  ufw allow 443/tcp >/dev/null
  ufw --force enable
  ufw status verbose
fi

step "fail2ban"
install -m 644 fail2ban/nginx-hls-sig.conf /etc/fail2ban/filter.d/nginx-hls-sig.conf
install -m 644 fail2ban/jail-hls.local /etc/fail2ban/jail.d/hls-cdn.local
touch "$HLS_LOG_DIR/hls-access.log"
systemctl enable fail2ban >/dev/null
systemctl restart fail2ban
fail2ban-client status | sed -n '1,3p' || true

step "evict.sh по таймеру"
install -d -m 700 /var/lib/hls-evict
install -m 644 systemd/hls-evict.service systemd/hls-evict.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now hls-evict.timer
systemctl list-timers hls-evict.timer --no-pager || true

step "Холодный архив"
install -m 644 systemd/hls-archive.service systemd/hls-archive.timer systemd/hls-restore.service /etc/systemd/system/
systemctl daemon-reload
if [ -f "$RCLONE_CONF" ]; then chown root:root "$RCLONE_CONF"; chmod 600 "$RCLONE_CONF"; fi
if [ -n "$ARCHIVE_REMOTE" ]; then
  [ -f "$RCLONE_CONF" ] || { echo "HLS_ARCHIVE_REMOTE задан, но нет $RCLONE_CONF — сначала rclone config (README, «Архив на Google Drive»)" >&2; exit 1; }
  systemctl enable --now hls-archive.timer
  systemctl enable hls-restore.service
  systemctl restart hls-restore.service
  echo "архив: $ARCHIVE_REMOTE; уже лежащие на диске v1 — однократно: $LIB/hls-archive.sh --enqueue-existing"
else
  systemctl disable --now hls-archive.timer hls-restore.service >/dev/null 2>&1 || true
  echo "архив выключен (HLS_ARCHIVE_REMOTE пуст)"
fi

if [ "$SAMPLE" = 1 ]; then
  step "Образец для test.sh"
  runuser -u hls-upload -- bash "$LIB/make-sample.sh" "$HLS_ROOT"
fi

step "Готово"
echo "Проверка: CDN_URL=https://$HLS_CDN_DOMAIN HLS_SIGN_SECRET=… CORS_ORIGIN=$HLS_CORS_ORIGIN $LIB/test.sh"
echo "Пробный прогон уборки: $LIB/evict.sh --dry-run"
