#!/bin/bash
# archive-stream-offline.sh — просмотр прямо из архива (docs/HLS.md §20): рендер nginx, юнит hls-archive-serve, evict.sh при обращениях к профилю не на диске, живой nginx (если есть).
set -uo pipefail
export LC_ALL=C

HERE=$(cd "$(dirname "$0")" && pwd)
CDN="$HERE/.."
RENDER="$CDN/nginx/render.sh"
UNIT="$CDN/systemd/hls-archive-serve.service"
EVICT="$CDN/evict.sh"
# shellcheck source=../lib-sign.sh
. "$CDN/lib-sign.sh"

T=$(mktemp -d)
cleanup() {
  if [ -n "${KEEP:-}" ]; then
    echo "Временный каталог сохранён: $T" >&2
  else
    rm -rf -- "$T"
  fi
}
trap cleanup EXIT

PASS=0
FAIL=0
check() {
  local name=$1; shift
  if "$@"; then
    PASS=$((PASS + 1))
    echo "  ok   $name"
  else
    FAIL=$((FAIL + 1))
    echo "  FAIL: $name" >&2
  fi
}

render() {
  local dir=$1; shift
  mkdir -p "$dir"
  env HLS_CDN_DOMAIN=cdn.example.org HLS_CORS_ORIGIN=https://site.example.org \
      HLS_ROOT=/srv/hls HLS_TLS_CERT=cert.pem HLS_TLS_KEY=key.pem \
      HLS_SECRET_CONF=hls-secret.conf HLS_LOG_DIR=logs HLS_ARCHIVE_ADDR=127.0.0.1:8781 \
      "$@" bash "$RENDER" "$dir/hls-cdn.conf" "$dir" 2>"$dir/stderr" | tr -d '\r'
}

# --- 1. рендер -----------------------------------------------------------------------------------------------------------
echo "1. рендер"

D1="$T/r1"
OUT1=$(render "$D1" HLS_ARCHIVE_STREAM=1 HLS_ARCHIVE_REMOTE=gdrive-crypt:hls)
check "stream=1: stdout = archive-stream: on" [ "$OUT1" = "archive-stream: on" ]
check "stream=1: try_files в hls-archive-stream.conf" \
  grep -qE '^try_files \$uri @hls_archive;$' "$D1/hls-archive-stream.conf"
for s in \
  'location @hls_archive {' \
  'proxy_pass http://127.0.0.1:8781$uri;' \
  'proxy_http_version 1.1;' \
  'access_log logs/hls-access.log hls_lru;'
do
  check "stream=1: server-conf содержит «$s»" grep -qF -- "$s" "$D1/hls-archive-stream-server.conf"
done
check "stream=1: Cache-Control no-store always" \
  grep -qE 'add_header +Cache-Control +"no-store" always;' "$D1/hls-archive-stream-server.conf"
check "stream=1: Access-Control-Allow-Origin \$hls_cors_origin always" \
  grep -qE 'add_header +Access-Control-Allow-Origin +\$hls_cors_origin always;' "$D1/hls-archive-stream-server.conf"
check "stream=1: нет нераскрытых \${" \
  bash -c '! cat "$1/hls-cdn.conf" "$1/hls-archive-stream.conf" "$1/hls-archive-stream-server.conf" | grep -v "^[[:space:]]*#" | grep -qF "\${"' _ "$D1"
check "stream=1: main include hls-archive-stream.conf" \
  grep -qE "^ +include .*/hls-archive-stream.conf;$" "$D1/hls-cdn.conf"
check "stream=1: main include hls-archive-stream-server.conf" \
  grep -qE "^ +include .*/hls-archive-stream-server.conf;$" "$D1/hls-cdn.conf"

L_INC=$(grep -nE '^ +include .*/hls-archive-stream.conf;$' "$D1/hls-cdn.conf" | head -1 | cut -d: -f1)
L_REW=$(grep -nF 'rewrite ^/hls/' "$D1/hls-cdn.conf" | head -1 | cut -d: -f1)
L_SEC=$(grep -nF 'secure_link_md5' "$D1/hls-cdn.conf" | head -1 | cut -d: -f1)
check "stream=1: include try_files после rewrite и secure_link_md5" \
  bash -c '[ -n "$1" ] && [ -n "$2" ] && [ -n "$3" ] && [ "$1" -gt "$2" ] && [ "$1" -gt "$3" ]' _ "$L_INC" "$L_REW" "$L_SEC"

D2="$T/r2"
OUT2=$(render "$D2" HLS_ARCHIVE_STREAM=0 HLS_ARCHIVE_REMOTE=gdrive-crypt:hls)
check "stream=0: stdout = archive-stream: off" [ "$OUT2" = "archive-stream: off" ]
check "stream=0: include-файлы без try_files/@hls_archive/proxy_pass" \
  bash -c '! cat "$1/hls-archive-stream.conf" "$1/hls-archive-stream-server.conf" | grep -v "^[[:space:]]*#" | grep -qE "try_files|@hls_archive|proxy_pass"' _ "$D2"
check "stream=0: main include hls-archive-stream.conf" \
  grep -qE "^ +include .*/hls-archive-stream.conf;$" "$D2/hls-cdn.conf"
check "stream=0: main include hls-archive-stream-server.conf" \
  grep -qE "^ +include .*/hls-archive-stream-server.conf;$" "$D2/hls-cdn.conf"

D3="$T/r3"
OUT3=$(render "$D3" HLS_ARCHIVE_STREAM=1 HLS_ARCHIVE_REMOTE=)
check "stream=1, пустой remote: stdout = archive-stream: off" [ "$OUT3" = "archive-stream: off" ]
check "stream=1, пустой remote: предупреждение в stderr" \
  grep -qF "HLS_ARCHIVE_REMOTE пуст" "$D3/stderr"
check "stream=1, пустой remote: include-файлы без try_files" \
  bash -c '! cat "$1/hls-archive-stream.conf" "$1/hls-archive-stream-server.conf" | grep -v "^[[:space:]]*#" | grep -qF "try_files"' _ "$D3"

# --- 2. юнит -------------------------------------------------------------------------------------------------------------
echo "2. юнит"

UNIT_CLEAN="$T/unit.service"
tr -d '\r' < "$UNIT" > "$UNIT_CLEAN"
EXEC_LINE=$(grep '^ExecStart=' "$UNIT_CLEAN" | head -1)
for s in \
  'rclone serve http ${HLS_ARCHIVE_REMOTE}' \
  '--addr 127.0.0.1:8781' \
  '--read-only' \
  '--config ${HLS_RCLONE_CONFIG}' \
  '--vfs-cache-mode full' \
  '--vfs-cache-max-size ${HLS_ARCHIVE_CACHE_GB}G' \
  '--cache-dir /var/cache/hls-archive' \
  '--vfs-read-chunk-size 8M' \
  '--dir-cache-time 1h'
do
  check "ExecStart содержит «$s»" grep -qF -- "$s" <<< "$EXEC_LINE"
done
check "ExecStart без 0.0.0.0" bash -c '! grep -qF "0.0.0.0" <<< "$1"' _ "$EXEC_LINE"
check "ExecStart без --addr :" bash -c '! grep -qF -- "--addr :" <<< "$1"' _ "$EXEC_LINE"

for line in \
  'EnvironmentFile=/etc/hls-cdn/cdn.env' \
  'Restart=always' \
  'ProtectSystem=strict' \
  'PrivateTmp=yes' \
  'NoNewPrivileges=yes'
do
  check "юнит: строка «$line»" grep -qx "$line" "$UNIT_CLEAN"
done
check "юнит: ReadWritePaths содержит /etc/hls-cdn и /var/cache/hls-archive" \
  bash -c 'grep "^ReadWritePaths=" "$1" | grep -qF "/etc/hls-cdn" && grep "^ReadWritePaths=" "$1" | grep -qF "/var/cache/hls-archive"' _ "$UNIT_CLEAN"
check "юнит: Environment содержит HLS_ARCHIVE_CACHE_GB=20" \
  bash -c 'grep "^Environment=" "$1" | grep -qF "HLS_ARCHIVE_CACHE_GB=20"' _ "$UNIT_CLEAN"

L_ENV=$(grep -n '^Environment=' "$UNIT_CLEAN" | head -1 | cut -d: -f1)
L_ENVF=$(grep -n '^EnvironmentFile=' "$UNIT_CLEAN" | head -1 | cut -d: -f1)
check "юнит: Environment= перед EnvironmentFile=" \
  bash -c '[ -n "$1" ] && [ -n "$2" ] && [ "$1" -lt "$2" ]' _ "$L_ENV" "$L_ENVF"

for s in \
  'systemd/hls-archive-serve.service' \
  'http://127.0.0.1:8781/' \
  'systemctl disable --now hls-archive-serve.service' \
  'nginx/render.sh'
do
  check "install.sh содержит «$s»" grep -qF -- "$s" "$CDN/install.sh"
done
check "cdn.env.example: HLS_ARCHIVE_STREAM=0" grep -qx 'HLS_ARCHIVE_STREAM=0' "$CDN/cdn.env.example"
check "cdn.env.example: HLS_ARCHIVE_CACHE_GB=20" grep -qx 'HLS_ARCHIVE_CACHE_GB=20' "$CDN/cdn.env.example"

# --- 3. evict.sh: обращения к профилю не на диске ------------------------------------------------------------------------
echo "3. evict.sh: обращения к профилю не на диске"

NOW=1767225600
D=86400
ROOT="$T/hls"
mkdir -p "$ROOT/aaaaaaaaaaaaaaaa/v1"
head -c 100 /dev/zero > "$ROOT/aaaaaaaaaaaaaaaa/v1/video.m4s"
touch -d "@$((NOW - 10*D))" "$ROOT/aaaaaaaaaaaaaaaa/v1"

cat > "$T/access.log" <<EOF
$((NOW - 3600)).100 bbbbbbbbbbbbbbbb v1 206 8388608 GET 10.0.0.2
$((NOW - 60)).100 bbbbbbbbbbbbbbbb v1 206 8388608 GET 10.0.0.2
$((NOW - 5*D)).100 aaaaaaaaaaaaaaaa v1 206 1024 GET 10.0.0.1
EOF
echo '{"items":[]}' > "$T/empty.json"

OUT=$(bash "$EVICT" --root "$ROOT" --log "$T/access.log" --now "$NOW" --min-idle 7200 \
  --env /dev/null --no-report --state-dir "$T/st" --pending-file "$T/empty.json" \
  --disk-total 1000 --disk-used 900 --high 85 --low 80 2>&1)
RC=$?
OUT=$(printf '%s' "$OUT" | tr -d '\r')

check "evict (случай 1): rc=0" [ "$RC" -eq 0 ]
check "evict (случай 1): нет ВНИМАНИЕ" bash -c '! grep -qF "ВНИМАНИЕ" <<< "$1"' _ "$OUT"
check "evict (случай 1): нет удаления bbbbbbbbbbbbbbbb" \
  bash -c '! grep -E "удал.*bbbbbbbbbbbbbbbb|bbbbbbbbbbbbbbbb.*удал" <<< "$1"' _ "$OUT"
check "evict (случай 1): aaaaaaaaaaaaaaaa удалён с диска" [ ! -e "$ROOT/aaaaaaaaaaaaaaaa" ]
check "evict (случай 1): lastaccess существует и без bbbbbbbbbbbbbbbb" \
  bash -c '[ -f "$1" ] && ! grep -qF "bbbbbbbbbbbbbbbb" "$1"' _ "$T/st/lastaccess"
check "evict (случай 1): evicted.spool — 1 строка, начинается с aaaaaaaaaaaaaaaa v1 " \
  bash -c '[ -f "$1" ] && [ "$(wc -l < "$1" | tr -d " ")" -eq 1 ] && grep -q "^aaaaaaaaaaaaaaaa v1 " "$1"' _ "$T/st/evicted.spool"

echo '{"items":[{"hls_id":"bbbbbbbbbbbbbbbb","profile":"v1"}]}' > "$T/p.json"
OUT2=$(bash "$EVICT" --root "$ROOT" --log "$T/access.log" --now "$NOW" --min-idle 7200 \
  --env /dev/null --no-report --state-dir "$T/st2" --pending-file "$T/p.json" \
  --disk-total 1000 --disk-used 100 --high 85 --low 80 2>&1)
RC2=$?
OUT2=$(printf '%s' "$OUT2" | tr -d '\r')

check "evict (случай 2): rc=0" [ "$RC2" -eq 0 ]
check "evict (случай 2): удалён bbbbbbbbbbbbbbbb/v1" grep -qF "удалён bbbbbbbbbbbbbbbb/v1" <<< "$OUT2"
TS_B=$(awk '$1=="bbbbbbbbbbbbbbbb" {print $3}' "$T/st2/evicted.spool" 2>/dev/null || true)
check "evict (случай 2): в evicted.spool поле 3 = $((NOW - 60))" [ "$TS_B" = "$((NOW - 60))" ]

# --- 4. живой nginx ------------------------------------------------------------------------------------------------------
echo "4. живой nginx"

NGX=${HLS_TEST_NGINX:-$(command -v nginx || true)}
if [ -z "$NGX" ] || [ ! -x "$NGX" ]; then
  echo "  пропуск живой проверки: nginx не найден (HLS_TEST_NGINX=путь/к/nginx)"
elif ! command -v node >/dev/null 2>&1; then
  echo "  пропуск живой проверки: node не найден"
elif ! command -v openssl >/dev/null 2>&1; then
  echo "  пропуск живой проверки: openssl не найден"
else
  WIN=""
  case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) WIN=1 ;; esac
  np() { if [ -n "$WIN" ]; then cygpath -m "$1"; else echo "$1"; fi; }

  P="$T/ngx"
  mkdir -p "$P"/{conf,logs,temp,srv/hls,arch,certbot}
  # относительные пути: в Git Bash openssl — программа Windows, а MSYS2_ARG_CONV_EXCL (ради -subj /CN=…) выключает перевод путей
  ( cd "$P/conf" && MSYS2_ARG_CONV_EXCL='*' openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout key.pem -out cert.pem -days 2 -subj /CN=localhost >/dev/null 2>&1 )

  NPORT=${ARCH_TEST_PORT:-18443}
  APORT=${ARCH_TEST_APORT:-18781}

  env HLS_CDN_DOMAIN=localhost HLS_CORS_ORIGIN=https://site.example.org \
      HLS_ROOT="$(np "$P/srv/hls")" HLS_TLS_CERT=cert.pem HLS_TLS_KEY=key.pem \
      HLS_SECRET_CONF=hls-secret.conf HLS_LOG_DIR=logs HLS_ARCHIVE_ADDR="127.0.0.1:$APORT" \
      HLS_ARCHIVE_STREAM=1 HLS_ARCHIVE_REMOTE=gdrive-crypt:hls \
      bash "$RENDER" "$P/conf/hls-cdn.raw" "$(np "$P/conf")" >/dev/null

  sed -e 's/^\(\s*aio\s\+threads;\)/# \1/' \
      -e 's/listen 80;/listen 127.0.0.1:18080;/; s/listen \[::\]:80;//; s/listen 443 ssl http2;/listen 127.0.0.1:'"$NPORT"' ssl;/; s/listen \[::\]:443 ssl http2;//' \
      -e 's|root /var/www/certbot;|root certbot;|' \
      "$P/conf/hls-cdn.raw" > "$P/conf/hls.conf"

  SECRET=live-test-secret-1234
  printf 'map $host $hls_sign_secret {\n    default "%s";\n}\n' "$SECRET" > "$P/conf/hls-secret.conf"
  printf 'worker_processes 1;\nerror_log logs/error.log warn;\npid logs/nginx.pid;\nevents { worker_connections 64; }\nhttp { include hls.conf; }\n' > "$P/conf/nginx.conf"

  mkdir -p "$P/srv/hls/1111111111111111/v1/1080p"
  head -c 4096 /dev/urandom > "$P/srv/hls/1111111111111111/v1/1080p/video.m4s"
  printf '#EXTM3U\n' > "$P/srv/hls/1111111111111111/v1/1080p/index.m3u8"

  mkdir -p "$P/arch/2222222222222222/v1/1080p" "$P/arch/2222222222222222/v1/x0"
  head -c 1048576 /dev/urandom > "$P/arch/2222222222222222/v1/1080p/video.m4s"
  printf '#EXTM3U\n#EXT-X-ENDLIST\n' > "$P/arch/2222222222222222/v1/1080p/index.m3u8"
  echo '{}' > "$P/arch/2222222222222222/v1/manifest.json"
  head -c 100 /dev/urandom > "$P/arch/2222222222222222/v1/x0/audio.m4s"

  FPID=""; NPID=""
  live_cleanup() {
    "$NGX" -p "$(np "$P")/" -c conf/nginx.conf -s stop >/dev/null 2>&1 || true
    [ -n "$FPID" ] && kill "$FPID" 2>/dev/null || true
    # остановка асинхронная: ждём выхода, иначе в Windows каталог занят и не удаляется
    for _ in $(seq 1 25); do
      { [ -z "$NPID" ] || ! kill -0 "$NPID" 2>/dev/null; } && [ ! -e "$P/logs/nginx.pid" ] && break
      sleep 0.2
    done
    [ -n "$FPID" ] && wait "$FPID" 2>/dev/null
    cleanup
  }
  trap live_cleanup EXIT

  node "$HERE/fake-archive-server.js" "$(np "$P/arch")" "$APORT" "$(np "$P/arch.log")" > "$P/fake.out" 2>&1 &
  FPID=$!
  for _ in $(seq 1 25); do
    grep -qF "listening" "$P/fake.out" 2>/dev/null && break
    sleep 0.2
  done
  touch "$P/arch.log"

  if ! "$NGX" -p "$(np "$P")/" -c conf/nginx.conf -t > "$P/t.out" 2>&1; then
    cat "$P/t.out" >&2
    check "nginx -t" false
  else
    check "nginx -t" true
    if [ -n "$WIN" ]; then
      ( cd "$P" && "$NGX" -p "$(np "$P")/" -c conf/nginx.conf ) &
      NPID=$!
    else
      "$NGX" -p "$P/" -c conf/nginx.conf
    fi
    for _ in $(seq 1 25); do
      [ "$(curl -sk "https://127.0.0.1:$NPORT/healthz" 2>/dev/null | tr -d '\r')" = "ok" ] && break
      sleep 0.2
    done

    EXP=$(( $(date +%s) + 3600 ))
    u() {
      local hid=$1 path=$2 sig
      sig=$(hls_sign "$EXP" "$hid" "$SECRET")
      echo "https://127.0.0.1:$NPORT/hls/$sig/$EXP/$hid/v1/$path"
    }
    req() {
      local name=$1; shift
      curl -sk --max-time 10 -H 'Origin: https://site.example.org' \
        -o "$P/$name.body" -D "$P/$name.raw" -w '%{http_code}' "$@" > "$P/$name.code" 2>/dev/null || true
      tr -d '\r' < "$P/$name.raw" 2>/dev/null | tr 'A-Z' 'a-z' > "$P/$name.h" || : > "$P/$name.h"
    }
    code() { cat "$P/$1.code"; }
    hdr() { sed -n "s/^$2: //p" "$P/$1.h" | head -1; }
    hcount() { grep -c "^$2:" "$P/$1.h"; }
    alines() { wc -l < "$P/arch.log" | tr -d ' '; }

    FAIL_BEFORE=$FAIL

    # 1. disk
    n0=$(alines)
    req disk "$(u 1111111111111111 1080p/video.m4s)"
    check "live 1: disk 200" [ "$(code disk)" = 200 ]
    check "live 1: disk Cache-Control immutable" grep -qF "immutable" <<< "$(hdr disk cache-control)"
    check "live 1: disk Content-Type video/mp4" [ "$(hdr disk content-type)" = "video/mp4" ]
    check "live 1: disk Allow-Origin" [ "$(hdr disk access-control-allow-origin)" = "https://site.example.org" ]
    check "live 1: архив не тронут" [ "$(alines)" = "$n0" ]

    # 2. archive with Range
    req arch -H 'Range: bytes=0-1023' "$(u 2222222222222222 1080p/video.m4s)"
    check "live 2: archive Range 206" [ "$(code arch)" = 206 ]
    check "live 2: archive Content-Range" [ "$(hdr arch content-range)" = "bytes 0-1023/1048576" ]
    check "live 2: archive body 1024" [ "$(wc -c < "$P/arch.body" | tr -d ' ')" = 1024 ]
    check "live 2: archive Cache-Control no-store (1x)" \
      bash -c '[ "$1" = "no-store" ] && [ "$2" -eq 1 ]' _ "$(hdr arch cache-control)" "$(hcount arch cache-control)"
    check "live 2: archive Content-Type video/mp4 (1x)" \
      bash -c '[ "$1" = "video/mp4" ] && [ "$2" -eq 1 ]' _ "$(hdr arch content-type)" "$(hcount arch content-type)"
    check "live 2: archive Accept-Ranges bytes (1x)" \
      bash -c '[ "$1" = "bytes" ] && [ "$2" -eq 1 ]' _ "$(hdr arch accept-ranges)" "$(hcount arch accept-ranges)"
    check "live 2: archive Allow-Origin" [ "$(hdr arch access-control-allow-origin)" = "https://site.example.org" ]
    check "live 2: archive Expose-Headers content-range" grep -qF "content-range" <<< "$(hdr arch access-control-expose-headers)"
    check "live 2: arch.log содержит путь и Range" \
      grep -qE '/2222222222222222/v1/1080p/video\.m4s.*bytes=0-1023' "$P/arch.log"
    check "live 2: arch.log без '?'" bash -c '! grep -qF "?" "$1"' _ "$P/arch.log"

    # 3. archive m3u8
    req m3u8 "$(u 2222222222222222 1080p/index.m3u8)"
    check "live 3: archive m3u8 200" [ "$(code m3u8)" = 200 ]
    check "live 3: archive m3u8 Content-Type" [ "$(hdr m3u8 content-type)" = "application/vnd.apple.mpegurl" ]
    check "live 3: archive m3u8 Cache-Control no-store" [ "$(hdr m3u8 cache-control)" = "no-store" ]

    # 4. archive missing
    req miss "$(u 2222222222222222 1080p/nope.m4s)"
    check "live 4: archive missing 404" [ "$(code miss)" = 404 ]
    check "live 4: archive missing Allow-Origin" [ "$(hdr miss access-control-allow-origin)" = "https://site.example.org" ]

    # 5. bad signature
    n0=$(alines)
    req badsig "https://127.0.0.1:$NPORT/hls/AAAAAAAAAAAAAAAAAAAAAA/$EXP/2222222222222222/v1/1080p/video.m4s"
    check "live 5: bad signature 403" [ "$(code badsig)" = 403 ]
    check "live 5: bad signature архив не тронут" [ "$(alines)" = "$n0" ]
    check "live 5: bad signature Allow-Origin" [ -n "$(hdr badsig access-control-allow-origin)" ]

    # 6. expired
    n0=$(alines)
    EXP2=$(( $(date +%s) - 10 ))
    sig2=$(hls_sign "$EXP2" 2222222222222222 "$SECRET")
    req exp "https://127.0.0.1:$NPORT/hls/$sig2/$EXP2/2222222222222222/v1/1080p/video.m4s"
    check "live 6: expired 410" [ "$(code exp)" = 410 ]
    check "live 6: expired архив не тронут" [ "$(alines)" = "$n0" ]

    # 7. traversal
    n0=$(alines)
    sig=$(hls_sign "$EXP" 2222222222222222 "$SECRET")
    req tr1 --path-as-is "https://127.0.0.1:$NPORT/hls/$sig/$EXP/2222222222222222/v1/../../1111111111111111/v1/1080p/video.m4s"
    check "live 7: traversal tr1 не 200/206" bash -c '[ "$1" != 200 ] && [ "$1" != 206 ]' _ "$(code tr1)"
    req tr2 --path-as-is "https://127.0.0.1:$NPORT/hls/$sig/$EXP/2222222222222222/v1/1080p/%2e%2e/%2e%2e/%2e%2e/etc/passwd"
    check "live 7: traversal tr2 не 200/206" bash -c '[ "$1" != 200 ] && [ "$1" != 206 ]' _ "$(code tr2)"
    check "live 7: traversal архив не тронут" [ "$(alines)" = "$n0" ]

    # 8. directories
    n0=$(alines)
    req dir1 "$(u 2222222222222222 x0)"
    req dir2 "$(u 2222222222222222 x0/)"
    check "live 8: dir1 404" [ "$(code dir1)" = 404 ]
    check "live 8: dir2 404" [ "$(code dir2)" = 404 ]
    check "live 8: directories архив не тронут" [ "$(alines)" = "$n0" ]

    # 9. HEAD
    req head -I "$(u 2222222222222222 1080p/video.m4s)"
    check "live 9: HEAD 200" [ "$(code head)" = 200 ]
    check "live 9: HEAD Content-Length 1048576" [ "$(hdr head content-length)" = 1048576 ]

    # 10. hls-access.log
    check "live 10: hls-access.log содержит 2222222222222222 v1 206" \
      grep -qF " 2222222222222222 v1 206 " "$P/logs/hls-access.log"

    if [ "$FAIL" -gt "$FAIL_BEFORE" ] && [ -n "${VERBOSE:-}" ] && [ -r "$P/logs/error.log" ]; then
      cat "$P/logs/error.log" >&2
    fi
  fi
fi

echo "Итог: $PASS ok, $FAIL FAIL"
[ "$FAIL" -eq 0 ]
