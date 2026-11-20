#!/bin/bash
# test.sh — проверка работающего CDN (docs/HLS.md §6, §10).
#
#   CDN_URL=https://cdn.example.org HLS_SIGN_SECRET=… CORS_ORIGIN=https://site.example.org ./test.sh
#   ./test.sh --offline        # только вектор подписи vectors.json → sign (без сети)
#
# Переменные:
#   CDN_URL          адрес CDN (схема обязательна)
#   HLS_SIGN_SECRET  секрет подписи (как в /etc/nginx/hls-secret.conf)
#   CORS_ORIGIN      origin сайта, разрешённый в конфиге
#   HLS_ID           ассет для проверки (по умолчанию 0000000000000000 — образец, его кладёт install.sh --sample)
#   HLS_PROFILE      профиль (v1)
#   SEG_PATH         сегмент внутри профиля (1080p/video.m4s), PLAYLIST_PATH (1080p/index.m3u8), VTT_PATH (s0/subs.vtt)
#   CURL_INSECURE=1  не проверять сертификат (самоподписанный на стенде)
#   VECTORS          путь к vectors.json (по умолчанию docs/hls-fixtures/vectors.json в репозитории)
set -uo pipefail
export LC_ALL=C

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=lib-sign.sh
. "$HERE/lib-sign.sh"

if [ -z "${VECTORS:-}" ]; then
  VECTORS=$HERE/../../docs/hls-fixtures/vectors.json
  [ -r "$VECTORS" ] || VECTORS=$HERE/vectors.json   # копия рядом (кладёт install.sh)
fi
PASS=0; FAIL=0
ok()   { PASS=$((PASS + 1)); echo "  ok   $*"; }
bad()  { FAIL=$((FAIL + 1)); echo "  FAIL $*"; }
jqr() { jq -r "$@" | tr -d '\r'; }   # jq на Windows пишет CRLF
check() { local name=$1; shift; if "$@"; then ok "$name"; else bad "$name"; fi; }

# --- вектор подписи (офлайн) ---------------------------------------------------------------------------------------------
echo "Подпись: vectors.json → sign"
if [ ! -r "$VECTORS" ]; then
  bad "нет $VECTORS"
elif ! command -v jq >/dev/null; then
  bad "нужен jq"
else
  n=$(jqr ".sign | length" "$VECTORS")
  [ "$n" -gt 0 ] || bad "в vectors.json нет sign"
  for i in $(seq 0 $((n - 1))); do
    read -r exp hid sig < <(jqr ".sign[$i] | \"\(.exp) \(.hls_id) \(.sig)\"" "$VECTORS")
    secret=$(jqr ".sign[$i].secret" "$VECTORS")
    input=$(jqr ".sign[$i].input" "$VECTORS")
    check "строка подписи #$i = \"<exp><hls_id> <secret>\"" [ "$exp$hid $secret" = "$input" ]
    got=$(hls_sign "$exp" "$hid" "$secret")
    check "sign #$i: $got = $sig" [ "$got" = "$sig" ]
  done
fi

if [ "${1:-}" = "--offline" ]; then
  echo "Итог: $PASS ok, $FAIL FAIL"
  [ "$FAIL" = 0 ]; exit $?
fi

# --- против работающего CDN ----------------------------------------------------------------------------------------------
: "${CDN_URL:?задайте CDN_URL}" "${HLS_SIGN_SECRET:?задайте HLS_SIGN_SECRET}" "${CORS_ORIGIN:?задайте CORS_ORIGIN}"
CDN_URL=${CDN_URL%/}
HLS_ID=${HLS_ID:-0000000000000000}
HLS_PROFILE=${HLS_PROFILE:-v1}
SEG_PATH=${SEG_PATH:-1080p/video.m4s}
PLAYLIST_PATH=${PLAYLIST_PATH:-1080p/index.m3u8}
VTT_PATH=${VTT_PATH:-s0/subs.vtt}
CURL=(curl -sS --max-time 20)
[ "${CURL_INSECURE:-0}" = 1 ] && CURL+=(-k)

T=$(mktemp -d)
trap 'rm -rf -- "$T"' EXIT
NOW=$(date +%s)
EXP=$((NOW + 3600))
SIG=$(hls_sign "$EXP" "$HLS_ID" "$HLS_SIGN_SECRET")
url() { echo "$CDN_URL/hls/$1/$2/$3/$HLS_PROFILE/$4"; }   # url SIG EXP HLS_ID PATH
GOOD_SEG=$(url "$SIG" "$EXP" "$HLS_ID" "$SEG_PATH")

# req NAME [curl args…] URL → код в $T/NAME.code, заголовки (строчными) в $T/NAME.h
req() {
  local name=$1; shift
  "${CURL[@]}" -o "$T/$name.body" -D "$T/$name.raw" -w '%{http_code}' "$@" > "$T/$name.code" 2>"$T/$name.err" || true
  tr -d '\r' < "$T/$name.raw" 2>/dev/null | tr 'A-Z' 'a-z' > "$T/$name.h" || : > "$T/$name.h"
}
code() { cat "$T/$1.code"; }
hdr() { sed -n "s/^$2: *//p" "$T/$1.h" | tail -n 1; }   # значение заголовка (имя строчными); значение тоже строчными
has() { grep -q "$2" <<< "$(hdr "$1" "$3")"; }       # has NAME ШАБЛОН ЗАГОЛОВОК

echo "CDN: $CDN_URL, ассет $HLS_ID/$HLS_PROFILE"

echo "Range и типы"
req full "$GOOD_SEG"
check "GET сегмента → 200 (было $(code full))" [ "$(code full)" = 200 ]
check "Accept-Ranges: bytes" [ "$(hdr full accept-ranges)" = bytes ]
SIZE=$(hdr full content-length)
req range -H "Range: bytes=0-99" "$GOOD_SEG"
check "Range bytes=0-99 → 206 (было $(code range))" [ "$(code range)" = 206 ]
check "Content-Range: bytes 0-99/$SIZE (было «$(hdr range content-range)»)" [ "$(hdr range content-range)" = "bytes 0-99/$SIZE" ]
check "Content-Length: 100" [ "$(hdr range content-length)" = 100 ]
check "тело 100 байт" [ "$(wc -c < "$T/range.body" | tr -d ' ')" = 100 ]
check "Content-Type video/mp4 для .m4s" [ "$(hdr range content-type)" = video/mp4 ]
check "Cache-Control immutable для .m4s" has range immutable cache-control
req mid -H "Range: bytes=$((SIZE / 2))-" "$GOOD_SEG"
check "Range до конца → 206 bytes $((SIZE / 2))-$((SIZE - 1))/$SIZE" [ "$(hdr mid content-range)" = "bytes $((SIZE / 2))-$((SIZE - 1))/$SIZE" ]
req pl "$(url "$SIG" "$EXP" "$HLS_ID" "$PLAYLIST_PATH")"
check "плейлист → 200" [ "$(code pl)" = 200 ]
check "Content-Type application/vnd.apple.mpegurl" [ "$(hdr pl content-type)" = application/vnd.apple.mpegurl ]
check "Cache-Control у .m3u8 короткий (max-age=60)" has pl "max-age=60" cache-control
req vtt "$(url "$SIG" "$EXP" "$HLS_ID" "$VTT_PATH")"
check "субтитры → 200" [ "$(code vtt)" = 200 ]
check "Content-Type text/vtt" [ "$(hdr vtt content-type)" = text/vtt ]
req nf "$(url "$SIG" "$EXP" "$HLS_ID" "1080p/nope.m4s")"
check "нет файла при верной подписи → 404" [ "$(code nf)" = 404 ]

echo "Подпись"
req badsig "$(url "AAAAAAAAAAAAAAAAAAAAAA" "$EXP" "$HLS_ID" "$SEG_PATH")"
check "чужая подпись → 403 (было $(code badsig))" [ "$(code badsig)" = 403 ]
req junksig "$(url "x" "$EXP" "$HLS_ID" "$SEG_PATH")"
check "мусор вместо подписи → 403" [ "$(code junksig)" = 403 ]
req otherid "$(url "$SIG" "$EXP" "ffffffffffffffff" "$SEG_PATH")"
check "подпись от другого hls_id → 403" [ "$(code otherid)" = 403 ]
req otherexp "$(url "$SIG" "$((EXP + 1))" "$HLS_ID" "$SEG_PATH")"
check "подделанный exp → 403" [ "$(code otherexp)" = 403 ]
OLD=$((NOW - 60))
req expired "$(url "$(hls_sign "$OLD" "$HLS_ID" "$HLS_SIGN_SECRET")" "$OLD" "$HLS_ID" "$SEG_PATH")"
check "истёкшая верная подпись → 410 (было $(code expired))" [ "$(code expired)" = 410 ]
req prof "$CDN_URL/hls/$SIG/$EXP/$HLS_ID/v1-copy/$SEG_PATH"
check "другой профиль с той же подписью проходит проверку (не 403/410, было $(code prof))" grep -qv '^4[01][03]$' "$T/prof.code"
req trav --path-as-is "$CDN_URL/hls/$SIG/$EXP/$HLS_ID/$HLS_PROFILE/../../.tmp/.lock"
check "выход из профиля через ../ не отдаёт файл (было $(code trav))" [ "$(code trav)" != 200 ]

echo "CORS"
req cors -H "Origin: $CORS_ORIGIN" -H "Range: bytes=0-9" "$GOOD_SEG"
ORIGIN_LC=$(tr 'A-Z' 'a-z' <<< "$CORS_ORIGIN")
check "Access-Control-Allow-Origin = $CORS_ORIGIN" [ "$(hdr cors access-control-allow-origin)" = "$ORIGIN_LC" ]
check "Expose-Headers: Content-Range" has cors content-range access-control-expose-headers
check "Expose-Headers: Content-Length" has cors content-length access-control-expose-headers
check "Expose-Headers: Accept-Ranges" has cors accept-ranges access-control-expose-headers
check "Vary: Origin" has cors origin vary
req foreign -H "Origin: https://evil.example.net" "$GOOD_SEG"
check "чужой Origin — без Allow-Origin" [ -z "$(hdr foreign access-control-allow-origin)" ]
req cors403 -H "Origin: $CORS_ORIGIN" "$(url "AAAAAAAAAAAAAAAAAAAAAA" "$EXP" "$HLS_ID" "$SEG_PATH")"
check "403 тоже с Allow-Origin (ошибка видна плееру)" [ "$(hdr cors403 access-control-allow-origin)" = "$ORIGIN_LC" ]
req pre -X OPTIONS -H "Origin: $CORS_ORIGIN" -H "Access-Control-Request-Method: GET" -H "Access-Control-Request-Headers: range" "$GOOD_SEG"
check "preflight OPTIONS → 204 (было $(code pre))" [ "$(code pre)" = 204 ]
check "preflight: Allow-Headers Range" has pre range access-control-allow-headers
check "preflight: Allow-Methods GET" has pre get access-control-allow-methods
check "preflight: Allow-Origin" [ "$(hdr pre access-control-allow-origin)" = "$ORIGIN_LC" ]
req post -X POST "$GOOD_SEG"
check "POST → 405" [ "$(code post)" = 405 ]

if [[ $CDN_URL == https://* ]]; then
  echo "HTTP/2"
  if curl -V | grep -qi '^features:.*http2'; then
    v=$("${CURL[@]}" --http2 -o /dev/null -w '%{http_version}' "$GOOD_SEG" 2>/dev/null)
    check "HTTP/2 (было $v)" [ "$v" = 2 ]
  else
    # curl без HTTP/2 (Git Bash): спросим ALPN у сервера через openssl
    host=${CDN_URL#https://}; host=${host%%/*}; port=443
    case "$host" in *:*) port=${host##*:}; host=${host%:*} ;; esac
    alpn=$(openssl s_client -connect "$host:$port" -servername "$host" -alpn h2 </dev/null 2>/dev/null | sed -n 's/^ALPN protocol: *//p' | tr -d '')
    check "HTTP/2 по ALPN (было «$alpn»)" [ "$alpn" = h2 ]
  fi
fi

echo "Итог: $PASS ok, $FAIL FAIL"
[ "$FAIL" = 0 ]
