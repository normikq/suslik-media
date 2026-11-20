#!/bin/bash
# install.sh — установка кодировщика HLS Suslik Media на Ubuntu с NVIDIA (от root, можно запускать повторно).
#
#   sudo ./install.sh [--no-start] [--once] [--now]
#
# Перед первым запуском: заполнить /etc/suslik-encoder.env (образец — suslik-encoder.env.example; скрипт сам положит его, если файла нет).
# Что делает: пакеты (ffmpeg, nodejs, rsync, ssh из apt); проверка версий, NVENC и zscale; пользователь suslik-encoder;
# ssh-ключ для hls-upload на CDN и ключ хоста CDN; доступ к /dev/nvidia*; пробное кодирование NVENC; путь тонмаппинга HDR
# (libplacebo/Vulkan или cpu — та же самопроверка, что при старте, от пользователя службы с её ограничениями); проверка сайта и CDN;
# служба systemd suslik-encoder (включается и перезапускается). --no-start — не запускать службу; --once — после установки
# выполнить одно задание (или одно «204») от пользователя службы и показать журнал. Идёт кодирование — служба перезапустится
# после текущего фильма (метка .restart); --now — перезапустить сразу (текущее задание вернётся в очередь).
# Код берётся из той папки, где лежит этот скрипт (рекомендуется /opt/suslik/encoder; /home не подходит — ProtectHome).
set -euo pipefail
cd "$(dirname "$0")"
DIR=$(pwd -P)

START=1; ONCE=0; NOW=0
while [ $# -gt 0 ]; do
  case "$1" in
    --no-start) START=0 ;;
    --once) ONCE=1 ;;
    --now) NOW=1 ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "неизвестный ключ $1" >&2; exit 2 ;;
  esac
  shift
done

[ "$(id -u)" = 0 ] || { echo "нужен root: sudo $0" >&2; exit 1; }
step() { echo; echo "== $*"; }
ok()   { echo "   ok: $*"; }
warn() { echo "   ВНИМАНИЕ: $*" >&2; WARNED=1; }
die()  { echo "ОШИБКА: $*" >&2; exit 1; }
WARNED=0
USER_NAME=suslik-encoder
HOME_DIR=/var/lib/suslik-encoder
ENV=/etc/suslik-encoder.env
UNIT=/etc/systemd/system/suslik-encoder.service

case "$DIR" in /home/*|/root/*) die "код в $DIR — служба его не увидит (ProtectHome). Клонируйте в /opt/suslik и запускайте /opt/suslik/encoder/install.sh" ;; esac
[ -f "$DIR/index.js" ] || die "нет $DIR/index.js — запускайте скрипт из папки encoder/"

# --- настройки ---------------------------------------------------------------
if [ ! -f "$ENV" ]; then
  install -m 600 suslik-encoder.env.example "$ENV"
  echo "Создан $ENV — заполните SM_SITE_URL, HLS_WORKER_TOKEN, SM_CDN_SSH и запустите install.sh ещё раз." >&2
  exit 1
fi
chown root:root "$ENV"; chmod 600 "$ENV"
set -a
# shellcheck disable=SC1090
. "$ENV"
set +a
[[ ${SM_SITE_URL:-} =~ ^https?://[^/]+ ]] || die "SM_SITE_URL в $ENV — адрес сайта со схемой (https://…)"
[[ $SM_SITE_URL == *example.org* ]] && die "SM_SITE_URL в $ENV — ещё образец, впишите адрес сайта"
[ -n "${HLS_WORKER_TOKEN:-}" ] || die "HLS_WORKER_TOKEN в $ENV пуст (тот же, что в .env сайта)"
UPLOAD_MODE=${SM_UPLOAD_MODE:-rsync}
if [ "$UPLOAD_MODE" = rsync ]; then
  [[ ${SM_CDN_SSH:-} =~ ^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+$ ]] || die "SM_CDN_SSH в $ENV — вида hls-upload@cdn.<домен>"
  [[ $SM_CDN_SSH == *example.org* ]] && die "SM_CDN_SSH в $ENV — ещё образец"
fi
ENCODER=${SM_ENCODER:-nvenc}
FFMPEG=${SM_FFMPEG:-ffmpeg}
FFPROBE=${SM_FFPROBE:-ffprobe}
KEY=${SM_CDN_SSH_KEY:-$HOME_DIR/.ssh/hls-upload}
WORK=${SM_WORK_DIR:-$HOME_DIR/work}
LOGS=${SM_LOG_DIR:-/var/log/suslik-encoder}
ok "настройки $ENV"

# --- пакеты ------------------------------------------------------------------
step "пакеты"
need=()
command -v rsync >/dev/null || need+=(rsync)
command -v ssh >/dev/null || need+=(openssh-client)
command -v curl >/dev/null || need+=(curl)
command -v node >/dev/null || need+=(nodejs)
[ "$FFMPEG" = ffmpeg ] || [ "$FFMPEG" = /usr/bin/ffmpeg ] && { command -v ffmpeg >/dev/null || need+=(ffmpeg); }
if [ ${#need[@]} -gt 0 ]; then
  apt-get update -q
  DEBIAN_FRONTEND=noninteractive apt-get install -y -q "${need[@]}"
fi
ok "rsync, ssh, curl, node, ffmpeg"

NODE=$(command -v node)
node_major=$("$NODE" -p 'process.versions.node.split(".")[0]')
[ "$node_major" -ge 20 ] || die "Node.js $("$NODE" --version), нужен ≥ 20: curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash - && sudo apt install -y nodejs"
ok "Node.js $("$NODE" --version)"

command -v "$FFMPEG" >/dev/null || die "ffmpeg не найден: $FFMPEG (SM_FFMPEG в $ENV)"
command -v "$FFPROBE" >/dev/null || die "ffprobe не найден: $FFPROBE (SM_FFPROBE в $ENV)"
ffver=$("$FFMPEG" -hide_banner -version | sed -n '1s/^ffmpeg version \([^ ]*\).*/\1/p')
if [[ $ffver =~ ^n?([0-9]+)\.([0-9]+) ]]; then
  maj=${BASH_REMATCH[1]}; min=${BASH_REMATCH[2]}
  { [ "$maj" -gt 6 ] || { [ "$maj" -eq 6 ] && [ "$min" -ge 1 ]; }; } || die "ffmpeg $ffver, нужен ≥ 6.1 (encoder/README.md)"
fi   # сборки из master (N-…) считаем свежими
ok "ffmpeg $ffver"
filters=$("$FFMPEG" -hide_banner -filters 2>/dev/null)
grep -qE '^ *[.A-Z]+ zscale ' <<<"$filters" || die "в ffmpeg нет фильтра zscale (HDR → SDR)"
grep -qE '^ *[.A-Z]+ tonemap ' <<<"$filters" || die "в ffmpeg нет фильтра tonemap"
ok "zscale, tonemap"
if grep -qE '^ *[.A-Z]+ libplacebo ' <<<"$filters"; then ok "libplacebo (HDR → SDR на видеокарте)"
else warn "в ffmpeg нет фильтра libplacebo — HDR будет тонмаппиться на процессоре (медленно), Dolby Vision 5 не кодируется"; fi

if [ "$ENCODER" = nvenc ]; then
  command -v nvidia-smi >/dev/null || die "нет nvidia-smi — драйвер NVIDIA: sudo ubuntu-drivers install && sudo reboot"
  gpu=$(nvidia-smi --query-gpu=name,driver_version --format=csv,noheader 2>/dev/null | head -1) || true
  [ -n "$gpu" ] || die "nvidia-smi не видит видеокарту (драйвер загружен? перезагрузка после установки?)"
  ok "видеокарта: $gpu"
  # вывод — в переменную: с pipefail «ffmpeg | grep -q» падает от SIGPIPE, даже когда строка найдена
  encoders=$("$FFMPEG" -hide_banner -encoders 2>/dev/null || true)
  grep -q hevc_nvenc <<<"$encoders" || die "в ffmpeg нет hevc_nvenc"
  # Vulkan для libplacebo: ICD NVIDIA ставится с драйвером (libnvidia-gl-<версия>); mesa (llvmpipe) не нужен — воркер его не берёт
  icd=$(ls /usr/share/vulkan/icd.d/nvidia_icd*.json /etc/vulkan/icd.d/nvidia_icd*.json 2>/dev/null | head -1 || true)
  if [ -n "$icd" ]; then ok "Vulkan ICD NVIDIA: $icd"
  else warn "нет Vulkan ICD NVIDIA (/usr/share/vulkan/icd.d/nvidia_icd.json) — libplacebo не заработает: sudo apt install libnvidia-gl-<версия драйвера>"; fi
fi

# --- пользователь и каталоги -------------------------------------------------
step "пользователь $USER_NAME"
if ! id "$USER_NAME" >/dev/null 2>&1; then
  useradd --system --create-home --home-dir "$HOME_DIR" --shell /usr/sbin/nologin "$USER_NAME"
  ok "создан"
else
  ok "уже есть"
fi
install -d -m 700 -o "$USER_NAME" -g "$USER_NAME" "$HOME_DIR" "$HOME_DIR/.ssh" "$WORK"
install -d -m 750 -o "$USER_NAME" -g "$USER_NAME" "$LOGS"
sudo -u "$USER_NAME" test -r "$DIR/index.js" || die "$USER_NAME не может читать $DIR — нужны права на чтение (chmod -R o+rX)"
ok "каталоги $HOME_DIR, $WORK, $LOGS"

# доступ к видеокарте: группы-владельцы /dev/nvidia*, если устройства закрыты для остальных
GROUPS_EXTRA=()
for dev in /dev/nvidia* /dev/dri/renderD*; do
  [ -c "$dev" ] || continue
  perm=$(stat -c '%a' "$dev"); grp=$(stat -c '%G' "$dev")
  if [ $(( ${perm: -1} & 6 )) -ne 6 ] && [ "$grp" != root ] && [[ ! " ${GROUPS_EXTRA[*]} " == *" $grp "* ]]; then
    GROUPS_EXTRA+=("$grp")
  fi
done
if [ ${#GROUPS_EXTRA[@]} -gt 0 ]; then
  usermod -aG "$(IFS=,; echo "${GROUPS_EXTRA[*]}")" "$USER_NAME"
  ok "группы для видеокарты: ${GROUPS_EXTRA[*]}"
fi

# --- ssh до CDN --------------------------------------------------------------
if [ "$UPLOAD_MODE" = rsync ]; then
  step "ssh до CDN ($SM_CDN_SSH)"
  if [ ! -f "$KEY" ]; then
    sudo -u "$USER_NAME" ssh-keygen -q -t ed25519 -N "" -C "encoder@$(hostname)" -f "$KEY"
    ok "создан ключ $KEY"
  fi
  CDN_HOST=${SM_CDN_SSH#*@}
  PORT=${SM_CDN_SSH_PORT:-22}
  lookup=$CDN_HOST; [ "$PORT" = 22 ] || lookup="[$CDN_HOST]:$PORT"
  KH=$HOME_DIR/.ssh/known_hosts
  if ! sudo -u "$USER_NAME" ssh-keygen -F "$lookup" -f "$KH" >/dev/null 2>&1; then
    scan=$(ssh-keyscan -p "$PORT" -t ed25519 "$CDN_HOST" 2>/dev/null) || true
    [ -n "$scan" ] || die "ssh-keyscan не получил ключ хоста $CDN_HOST:$PORT (DNS, порт, ufw на CDN?)"
    sudo -u "$USER_NAME" sh -c "umask 077; cat >> '$KH'" <<<"$scan"
    sudo -u "$USER_NAME" ssh-keygen -H -f "$KH" >/dev/null 2>&1 && rm -f "$KH.old"
    echo "   добавлен ключ хоста CDN. Сверьте отпечаток с выводом на CDN: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub"
    ssh-keygen -lf - <<<"$scan" | sed 's/^/     /'
  fi
  # проверка входа: forced command отвергает «finalize test» — это и есть успех; «Permission denied» — ключ не добавлен
  out=$(sudo -u "$USER_NAME" ssh -i "$KEY" -p "$PORT" -o BatchMode=yes -o ConnectTimeout=10 "$SM_CDN_SSH" finalize test 2>&1) || true
  if grep -qi 'permission denied' <<<"$out"; then
    warn "CDN не принимает ключ. Добавьте на CDN (sudo ./install.sh --upload-key \"…\" из infra/cdn или вручную в /home/hls-upload/.ssh/authorized_keys):"
    echo; cat "$KEY.pub"; echo
  elif grep -qiE 'could not resolve|timed out|refused|no route' <<<"$out"; then
    warn "CDN недоступен по ssh: $(tail -1 <<<"$out")"
  else
    ok "вход как hls-upload работает"
  fi
fi

# --- сайт ----------------------------------------------------------------------
step "сайт ($SM_SITE_URL)"
# probe с пустым телом: токен принят → Node отвечает 404 {"code":"unknown_job"}; 404 без этого кода — nginx не пускает /internal/hls/
body=$(curl -s -m 20 -w '\n%{http_code}' -H "Authorization: Bearer $HLS_WORKER_TOKEN" -H 'Content-Type: application/json' \
       -X POST -d '{}' "${SM_SITE_URL%/}/internal/hls/probe" || true)
code=${body##*$'\n'}
[[ $code == 404 && $body != *unknown_job* ]] && code=nginx404
case "$code" in
  404|400) ok "токен принят (HTTP $code)" ;;
  401|403) warn "сайт отклонил HLS_WORKER_TOKEN (HTTP $code) — сверьте с .env сайта" ;;
  503)     warn "HLS на сайте не настроен (HTTP 503): HLS_WORKER_TOKEN в .env сайта пуст или код не выложен" ;;
  000)     warn "сайт недоступен (сеть, DNS, сертификат?)" ;;
  *)       warn "неожиданный ответ сайта: HTTP $code (nginx проксирует /internal/hls/? — docs/HLS-DEPLOY.md, 2.1)" ;;
esac

# --- пробное кодирование -------------------------------------------------------
if [ "$ENCODER" = nvenc ]; then
  step "пробное кодирование NVENC от $USER_NAME"
  if out=$(sudo -u "$USER_NAME" "$FFMPEG" -hide_banner -nostdin -f lavfi -i testsrc2=size=1920x1080:rate=24 -t 2 \
        -vf "format=yuv420p" -c:v hevc_nvenc -preset p5 -tag:v hvc1 -f null - 2>&1); then
    ok "$(grep -o 'speed=[^ ]*' <<<"$out" | tail -1)"
  else
    echo "$out" | tail -5 >&2
    die "hevc_nvenc не кодирует от $USER_NAME (драйвер, /dev/nvidia*, версия libnvidia-encode)"
  fi
fi

# --- тонмаппинг HDR → SDR ----------------------------------------------------------
# та же самопроверка, что воркер делает при старте (node index.js --tonemap-check), и в той же песочнице, что служба:
# ProtectSystem/ProtectHome/PrivateTmp не мешают (ICD в /usr/share/vulkan читается, /dev/nvidia* не закрыты — PrivateDevices/DeviceAllow нет)
step "тонмаппинг HDR → SDR от $USER_NAME"
if grep -qE '^(PrivateDevices=yes|DeviceAllow=)' systemd/suslik-encoder.service; then
  warn "в systemd/suslik-encoder.service есть PrivateDevices/DeviceAllow — служба не увидит /dev/nvidia* (NVENC, Vulkan)"
fi
tm_props=(-p EnvironmentFile="$ENV" -p WorkingDirectory="$DIR" -p NoNewPrivileges=yes -p ProtectSystem=strict -p ProtectHome=yes
          -p PrivateTmp=yes -p ReadWritePaths="$HOME_DIR $WORK $LOGS")
if [ ${#GROUPS_EXTRA[@]} -gt 0 ]; then tm_props+=(-p SupplementaryGroups="${GROUPS_EXTRA[*]}"); fi
tm_rc=0
tm_out=$(systemd-run --quiet --wait --pipe --collect --uid="$USER_NAME" --gid="$USER_NAME" "${tm_props[@]}" \
         "$NODE" "$DIR/index.js" --tonemap-check 2>&1) || tm_rc=$?
case "$tm_rc" in
  0) ok "$(tail -1 <<<"$tm_out")" ;;
  3) warn "$(tail -1 <<<"$tm_out") — HDR пойдёт на процессоре; что делать — encoder/README.md, «Тонмаппинг HDR → SDR»" ;;
  *) echo "$tm_out" | tail -5 >&2
     warn "проверка тонмаппинга не прошла (код $tm_rc); при SM_TONEMAP=libplacebo служба не запустится" ;;
esac

"$NODE" --check "$DIR/index.js" || die "index.js не разбирается этой версией Node"

# --- служба ----------------------------------------------------------------------
step "служба suslik-encoder"
sed -e "s|@ENCODER_DIR@|$DIR|g" -e "s|@NODE@|$NODE|g" -e "s|@GROUPS@|${GROUPS_EXTRA[*]:-}|g" \
    -e "s|^ReadWritePaths=.*|ReadWritePaths=$HOME_DIR $WORK $LOGS|" \
    systemd/suslik-encoder.service > "$UNIT.tmp"
mv "$UNIT.tmp" "$UNIT"; chmod 644 "$UNIT"
systemctl daemon-reload

if [ "$ONCE" = 1 ]; then
  step "одно задание (SM_ONCE=1)"
  systemctl stop suslik-encoder 2>/dev/null || true
  systemd-run --quiet --wait --pipe --collect --uid="$USER_NAME" --gid="$USER_NAME" \
    -p EnvironmentFile="$ENV" -p WorkingDirectory="$DIR" --setenv=SM_ONCE=1 \
    "$NODE" "$DIR/index.js" || warn "пробный запуск завершился с ошибкой — журнал выше и в $LOGS"
fi

if [ "$START" = 1 ]; then
  systemctl enable suslik-encoder >/dev/null 2>&1
  # идёт кодирование (в $WORK есть папка задания) — не прерываем: метка .restart, воркер доделает фильм,
  # выйдет и systemd (Restart=always) поднимет его уже с новым кодом. --now — перезапустить сразу.
  busy=$(find "$WORK" -mindepth 1 -maxdepth 1 ! -name .lock ! -name .restart -print -quit 2>/dev/null || true)
  if systemctl is-active --quiet suslik-encoder && [ -n "$busy" ] && [ "$NOW" = 0 ]; then
    install -m 644 -o "$USER_NAME" -g "$USER_NAME" /dev/null "$WORK/.restart"
    ok "идёт кодирование — служба перезапустится сама после текущего фильма (сразу: sudo $0 --now)"
  else
    rm -f "$WORK/.restart"
    systemctl restart suslik-encoder
    sleep 3
    if systemctl is-active --quiet suslik-encoder; then ok "запущена"; else warn "не запустилась: journalctl -u suslik-encoder -n 50"; fi
  fi
else
  ok "установлена, не запущена (--no-start): sudo systemctl enable --now suslik-encoder"
fi

echo
[ "$WARNED" = 0 ] && echo "Готово. Журнал: journalctl -u suslik-encoder -f (и $LOGS)." \
                  || echo "Готово с предупреждениями (выше). Исправьте и запустите install.sh ещё раз."
