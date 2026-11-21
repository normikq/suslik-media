#!/bin/bash
# archive-offline.sh — автономная проверка hls-archive.sh, hls-restore.sh, hls-finalize и evict.sh с архивом (без сети, в Git Bash и Linux).
set -uo pipefail
export LC_ALL=C

HERE=$(cd "$(dirname "$0")" && pwd)
CDN="$HERE/.."
FIX="$HERE/../../../docs/hls-fixtures"

NOW=1767225600
D=86400

T=$(mktemp -d)
cleanup() { [ -n "${KEEP:-}" ] && echo "оставлено: $T" || rm -rf -- "$T"; }
trap cleanup EXIT

mkdir -p "$T/stub"
cp "$HERE/fake-curl" "$T/stub/curl"
chmod +x "$T/stub/curl"
if ! command -v flock >/dev/null 2>&1; then
  printf '#!/bin/bash\nexit 0\n' > "$T/stub/flock"
  chmod +x "$T/stub/flock"
fi
export PATH="$T/stub:$PATH"

export HLS_RCLONE="$HERE/fake-rclone"
export FAKE_RCLONE_ROOT="$T/archive"
export FAKE_RCLONE_LOG="$T/rclone.log"
export FAKE_SITE="$T/site"

cat > "$T/cdn.env" <<EOF
SM_SITE_URL=site.test
HLS_CDN_TOKEN=tok123
HLS_ARCHIVE_REMOTE=fake-crypt:hls
HLS_RCLONE_CONFIG=$T/rclone.conf
EOF

cat > "$T/cdn-noarch.env" <<EOF
SM_SITE_URL=site.test
HLS_CDN_TOKEN=tok123
HLS_ARCHIVE_REMOTE=
HLS_RCLONE_CONFIG=$T/rclone.conf
EOF

# Создать профиль ROOT/HID/PROF с точным размером SIZE (через manifest.json + video.m4s) и временем MTIME
mkprof() {
  local root=$1 hid=$2 prof=$3 size=$4 mtime=$5
  local dir="$root/$hid/$prof"
  mkdir -p "$dir"
  printf '{}' > "$dir/manifest.json"
  local msz
  msz=$(wc -c < "$dir/manifest.json")
  head -c $((size - msz)) /dev/zero > "$dir/video.m4s"
  touch -d "@$mtime" "$dir"
}

req_files() {
  local pat=$1
  ls -1 "$FAKE_SITE/req/"*"$pat"* 2>/dev/null | sort
}

last_req() {
  local pat=$1
  local f
  f=$(req_files "$pat" | tail -n 1)
  [ -n "$f" ] && cat "$f"
}

reset_site() {
  rm -rf "$FAKE_SITE"
  mkdir -p "$FAKE_SITE/resp"
}

PASS=0
FAIL=0
check() {
  local name=$1; shift
  if "$@"; then
    PASS=$((PASS + 1))
  else
    echo "FAIL: $name" >&2
    FAIL=$((FAIL + 1))
  fi
}

jq_clean() { jq "$@" | tr -d '\r'; }

# === Случай 1: архивация v1 + постановка в очередь через hls-finalize ===
reset_site
ROOT1="$T/root1"
S1="$T/st1"
Q1="$T/q1"
mkdir -p "$ROOT1/.tmp/178e77a7ddb10079.v1.ab12cd/1080p" "$Q1"
printf '{}' > "$ROOT1/.tmp/178e77a7ddb10079.v1.ab12cd/manifest.json"
head -c 128 /dev/zero > "$ROOT1/.tmp/178e77a7ddb10079.v1.ab12cd/1080p/video.m4s"

HLS_ROOT="$ROOT1" HLS_ARCHIVE_QUEUE="$Q1" bash "$CDN/bin/hls-finalize" "178e77a7ddb10079.v1.ab12cd" >/dev/null 2>&1
check "case1: файл очереди создан для v1" test -e "$Q1/178e77a7ddb10079.v1"

# старая H.264-копия (видео avc1) — временная, не архивируется
mkdir -p "$ROOT1/.tmp/5d0c1f2a9e7b3c44.v1-copy.cd34ef"
printf '{"video":[{"dir":"1080p","codec":"avc1.640028"},{"dir":"720p","codec":"hvc1.1.6.L93.90"}]}' > "$ROOT1/.tmp/5d0c1f2a9e7b3c44.v1-copy.cd34ef/manifest.json"
HLS_ROOT="$ROOT1" HLS_ARCHIVE_QUEUE="$Q1" bash "$CDN/bin/hls-finalize" "5d0c1f2a9e7b3c44.v1-copy.cd34ef" >/dev/null 2>&1
check "case1: файл очереди не создан для H.264-копии" test ! -e "$Q1/5d0c1f2a9e7b3c44.v1-copy"

# v1-copy из HEVC — окончательная, архивируется как v1
mkdir -p "$ROOT1/.tmp/9e0c1f2a9e7b3c45.v1-copy.ef56gh/1080p"
printf '{"video":[{"dir":"1080p","codec":"hvc1.1.6.L120.90"},{"dir":"720p","codec":"hvc1.1.6.L93.90"}]}' > "$ROOT1/.tmp/9e0c1f2a9e7b3c45.v1-copy.ef56gh/manifest.json"
head -c 64 /dev/zero > "$ROOT1/.tmp/9e0c1f2a9e7b3c45.v1-copy.ef56gh/1080p/video.m4s"
HLS_ROOT="$ROOT1" HLS_ARCHIVE_QUEUE="$Q1" bash "$CDN/bin/hls-finalize" "9e0c1f2a9e7b3c45.v1-copy.ef56gh" >/dev/null 2>&1
check "case1: файл очереди создан для HEVC-копии" test -e "$Q1/9e0c1f2a9e7b3c45.v1-copy"

OUT1=$(bash "$CDN/hls-archive.sh" --env "$T/cdn.env" --root "$ROOT1" --state-dir "$S1" --queue "$Q1" --now "$NOW" 2>&1 | tr -d '\r')
RC1=$?
[ -n "${VERBOSE:-}" ] && printf '%s\n' "$OUT1"

check "case1: код возврата 0" test "$RC1" -eq 0
check "case1: архивная копия video.m4s существует" test -f "$FAKE_RCLONE_ROOT/hls/178e77a7ddb10079/v1/1080p/video.m4s"
check "case1: файл очереди удалён" test ! -e "$Q1/178e77a7ddb10079.v1"
check "case1: archived содержит запись 178e77a7ddb10079 v1" grep -q "^178e77a7ddb10079 v1 " "$S1/archived"

REQ1_FILES=$(req_files "POST-internal_hls_archived")
check "case1: запрос POST-internal_hls_archived отправлен" test -n "$REQ1_FILES"
REQ1=$(last_req "POST-internal_hls_archived")
FIX_ARCH_KEYS=$(jq_clean -cS '.[0] | keys' "$FIX/archived-request.json")
check "case1: ключи запроса archived совпадают с фикстурой" test "$(printf '%s' "$REQ1" | jq_clean -cS '.[0] | keys')" = "$FIX_ARCH_KEYS"
check "case1: hls_id совпадает" test "$(printf '%s' "$REQ1" | jq_clean -r '.[0].hls_id')" = "178e77a7ddb10079"
check "case1: profile == v1" test "$(printf '%s' "$REQ1" | jq_clean -r '.[0].profile')" = "v1"
check "case1: at == NOW" test "$(printf '%s' "$REQ1" | jq_clean -r '.[0].at')" = "$NOW"
EXP_BYTES1=$(du -sb "$ROOT1/178e77a7ddb10079/v1" | awk '{print $1}')
check "case1: bytes совпадает с du -sb" test "$(printf '%s' "$REQ1" | jq_clean -r '.[0].bytes')" = "$EXP_BYTES1"
check "case1: archived.spool пуст" test ! -s "$S1/archived.spool"
check "case1: нет архива для 5d0c1f2a9e7b3c44" test ! -e "$FAKE_RCLONE_ROOT/hls/5d0c1f2a9e7b3c44"
check "case1: HEVC-копия в архиве" test -f "$FAKE_RCLONE_ROOT/hls/9e0c1f2a9e7b3c45/v1-copy/1080p/video.m4s"
check "case1: archived содержит 9e0c1f2a9e7b3c45 v1-copy" grep -q "^9e0c1f2a9e7b3c45 v1-copy " "$S1/archived"
check "case1: в запросе archived и v1-copy" test "$(printf '%s' "$REQ1" | jq_clean -r '.[1].profile')" = "v1-copy"

# === Случай 2: отсутствующий профиль и мусор в очереди ===
reset_site
ROOT2="$T/root2"
S2="$T/st2"
Q2="$T/q2"
mkdir -p "$ROOT2" "$Q2"
: > "$Q2/aaaaaaaaaaaaaaaa.v1"
: > "$Q2/junk"

OUT2=$(bash "$CDN/hls-archive.sh" --env "$T/cdn.env" --root "$ROOT2" --state-dir "$S2" --queue "$Q2" --now "$NOW" 2>&1 | tr -d '\r')
RC2=$?
[ -n "${VERBOSE:-}" ] && printf '%s\n' "$OUT2"

check "case2: код возврата 0" test "$RC2" -eq 0
check "case2: aaaaaaaaaaaaaaaa.v1 удалён из очереди" test ! -e "$Q2/aaaaaaaaaaaaaaaa.v1"
check "case2: junk удалён из очереди" test ! -e "$Q2/junk"
check "case2: POST archived не отправлен" test -z "$(req_files "POST-internal_hls_archived")"

# === Случай 3: спул при недоступном сайте ===
reset_site
ROOT3="$T/root3"
S3="$T/st3"
Q3="$T/q3"
mkdir -p "$ROOT3" "$Q3"
mkprof "$ROOT3" bbbbbbbbbbbbbbbb v1 1000 $((NOW - D))
: > "$Q3/bbbbbbbbbbbbbbbb.v1"
: > "$FAKE_SITE/down"

OUT3=$(bash "$CDN/hls-archive.sh" --env "$T/cdn.env" --root "$ROOT3" --state-dir "$S3" --queue "$Q3" --now "$NOW" 2>&1 | tr -d '\r')
RC3=$?
[ -n "${VERBOSE:-}" ] && printf '%s\n' "$OUT3"

check "case3: код возврата != 0 при падении сайта" test "$RC3" -ne 0
check "case3: archived.spool содержит 1 строку" test "$(wc -l < "$S3/archived.spool" | tr -d ' ')" = "1"
check "case3: archived содержит bbbbbbbbbbbbbbbb" grep -q "^bbbbbbbbbbbbbbbb v1 " "$S3/archived"

rm -f "$FAKE_SITE/down"
OUT3B=$(bash "$CDN/hls-archive.sh" --env "$T/cdn.env" --root "$ROOT3" --state-dir "$S3" --queue "$Q3" --now "$NOW" 2>&1 | tr -d '\r')
RC3B=$?
[ -n "${VERBOSE:-}" ] && printf '%s\n' "$OUT3B"

check "case3b: код возврата 0 после восстановления сайта" test "$RC3B" -eq 0
REQ3=$(last_req "POST-internal_hls_archived")
check "case3b: POST archived содержит bbbbbbbbbbbbbbbb" test "$(printf '%s' "$REQ3" | jq_clean -r '.[0].hls_id')" = "bbbbbbbbbbbbbbbb"
check "case3b: archived.spool пуст" test ! -s "$S3/archived.spool"

# === Случай 3c: лимит загрузки Google (rclone выходит с кодом 7) — очередь остаётся до следующего запуска ===
reset_site
mkprof "$ROOT3" dddddddddddddddd v1 100 $((NOW - D))
: > "$Q3/dddddddddddddddd.v1"
OUT3C=$(FAKE_RCLONE_FAIL="sync=7" bash "$CDN/hls-archive.sh" --env "$T/cdn.env" --root "$ROOT3" --state-dir "$S3" --queue "$Q3" --now "$NOW" 2>&1 | tr -d '\r')
RC3C=$?
[ -n "${VERBOSE:-}" ] && printf '%s\n' "$OUT3C"
check "case3c: код возврата != 0" test "$RC3C" -ne 0
check "case3c: файл очереди остался" test -e "$Q3/dddddddddddddddd.v1"
check "case3c: в состоянии архива нет dddd" bash -c '! grep -q "^dddddddddddddddd " "$1"' _ "$S3/archived"
rm -f "$Q3/dddddddddddddddd.v1"

# === Случай 4: без HLS_ARCHIVE_REMOTE ===
reset_site
: > "$FAKE_RCLONE_LOG"
ROOT4="$T/root4"
S4="$T/st4"
Q4="$T/q4"
mkdir -p "$ROOT4" "$Q4"
: > "$Q4/cccccccccccccccc.v1"

OUT4A=$(bash "$CDN/hls-archive.sh" --env "$T/cdn-noarch.env" --root "$ROOT4" --state-dir "$S4" --queue "$Q4" --now "$NOW" 2>&1 | tr -d '\r')
RC4A=$?
[ -n "${VERBOSE:-}" ] && printf '%s\n' "$OUT4A"

check "case4a: archive код возврата 0" test "$RC4A" -eq 0
check "case4a: вывод содержит 'архив выключен'" grep -q 'архив выключен' <<< "$OUT4A"
check "case4a: файл очереди остался на месте" test -e "$Q4/cccccccccccccccc.v1"
check "case4a: rclone не вызывался" test ! -s "$FAKE_RCLONE_LOG"

OUT4B=$(bash "$CDN/hls-restore.sh" --once --env "$T/cdn-noarch.env" --root "$ROOT4" --state-dir "$S4" --now "$NOW" 2>&1 | tr -d '\r')
RC4B=$?
[ -n "${VERBOSE:-}" ] && printf '%s\n' "$OUT4B"

check "case4b: restore код возврата 0" test "$RC4B" -eq 0
check "case4b: вывод содержит 'архив выключен'" grep -q 'архив выключен' <<< "$OUT4B"
check "case4b: запросов на сайт не было" test -z "$(req_files "")"

# === Случай 5: порядок вытеснения с archived ===
reset_site
ROOT5="$T/root5"
S5="$T/st5"
mkdir -p "$ROOT5" "$S5"
mkprof "$ROOT5" 1111111111111111 v1 100 $((NOW - 10 * D))
mkprof "$ROOT5" 2222222222222222 v1 100 $((NOW - 50 * D))
mkprof "$ROOT5" 3333333333333333 v1 100 $((NOW - 5 * D))
cat > "$S5/archived" <<EOF
1111111111111111 v1 100 $NOW
3333333333333333 v1 100 $NOW
EOF
printf '{"items":[]}\n' > "$T/empty-pending.json"

OUT5A=$(bash "$CDN/evict.sh" --root "$ROOT5" --state-dir "$S5" --env "$T/cdn.env" --now "$NOW" \
  --min-idle 7200 --log /dev/null --dry-run --pending-file "$T/empty-pending.json" \
  --disk-total 1000 --disk-used 900 --high 85 --low 75 2>&1 | tr -d '\r')
RC5A=$?
[ -n "${VERBOSE:-}" ] && printf '%s\n' "$OUT5A"

check "case5a: код возврата 0" test "$RC5A" -eq 0
DEL5A=$(printf '%s\n' "$OUT5A" | grep '\[dry-run\] удалить ' | grep -v 'брошенную' | sed -n 's/.*удалить \([^ ]*\).*/\1/p' | paste -sd' ' -)
check "case5a: порядок удаления — сначала архивированные" test "$DEL5A" = "1111111111111111/v1 3333333333333333/v1"
EV5A=$(printf '%s\n' "$OUT5A" | sed -n 's/^evicted: //p')
FIX_EV_KEYS=$(jq_clean -cS '.[0] | keys' "$FIX/evicted-request-archived.json")
check "case5a: оба элемента archived:true" test "$(printf '%s' "$EV5A" | jq_clean -c 'all(.archived == true)')" = "true"
check "case5a: ключи evicted совпадают с фикстурой" test "$(printf '%s' "$EV5A" | jq_clean -cS 'all(keys == '"$FIX_EV_KEYS"')')" = "true"

OUT5B=$(bash "$CDN/evict.sh" --root "$ROOT5" --state-dir "$S5" --env "$T/cdn-noarch.env" --now "$NOW" \
  --min-idle 7200 --log /dev/null --dry-run --pending-file "$T/empty-pending.json" \
  --disk-total 1000 --disk-used 900 --high 85 --low 75 2>&1 | tr -d '\r')
RC5B=$?
[ -n "${VERBOSE:-}" ] && printf '%s\n' "$OUT5B"

check "case5b: код возврата 0" test "$RC5B" -eq 0
DEL5B=$(printf '%s\n' "$OUT5B" | grep '\[dry-run\] удалить ' | grep -v 'брошенную' | sed -n 's/.*удалить \([^ ]*\).*/\1/p' | paste -sd' ' -)
check "case5b: без архива порядок по LRU" test "$DEL5B" = "2222222222222222/v1 1111111111111111/v1"
EV5B=$(printf '%s\n' "$OUT5B" | sed -n 's/^evicted: //p')
check "case5b: без архива все archived:false" test "$(printf '%s' "$EV5B" | jq_clean -c 'all(.archived == false)')" = "true"

# === Случай 6: pending-deletes с archive:true -> purge ===
reset_site
: > "$FAKE_RCLONE_LOG"
ROOT6="$T/root6"
S6="$T/st6"
mkdir -p "$ROOT6" "$S6" "$FAKE_RCLONE_ROOT/hls/5d0c1f2a9e7b3c44/v1"
mkprof "$ROOT6" 5d0c1f2a9e7b3c44 v1 100 $((NOW - 10 * D))
mkprof "$ROOT6" 178e77a7ddb10079 v1-copy 50 $((NOW - 10 * D))
printf '{}' > "$FAKE_RCLONE_ROOT/hls/5d0c1f2a9e7b3c44/v1/manifest.json"
printf '5d0c1f2a9e7b3c44 v1 100 %s\n' "$NOW" > "$S6/archived"

OUT6=$(bash "$CDN/evict.sh" --root "$ROOT6" --state-dir "$S6" --env "$T/cdn.env" --now "$NOW" \
  --min-idle 7200 --log /dev/null --no-report --pending-file "$FIX/pending-deletes-archive.json" \
  --disk-total 1000 --disk-used 100 2>&1 | tr -d '\r')
RC6=$?
[ -n "${VERBOSE:-}" ] && printf '%s\n' "$OUT6"

check "case6: код возврата 0" test "$RC6" -eq 0
check "case6: архив 5d0c1f2a9e7b3c44/v1 удалён" test ! -e "$FAKE_RCLONE_ROOT/hls/5d0c1f2a9e7b3c44/v1"
check "case6: локальный 5d0c1f2a9e7b3c44 удалён" test ! -e "$ROOT6/5d0c1f2a9e7b3c44"
check "case6: локальный 178e77a7ddb10079/v1-copy удалён" test ! -e "$ROOT6/178e77a7ddb10079/v1-copy"
check "case6: строка 5d0c1f2a9e7b3c44 убрана из archived" bash -c '! grep -q "^5d0c1f2a9e7b3c44 " "$1"' _ "$S6/archived"
check "case6: last-evicted 5d0c archived:false" test "$(jq_clean -r '.[] | select(.hls_id=="5d0c1f2a9e7b3c44") | .archived' "$S6/last-evicted.json")" = "false"
check "case6: last-evicted 178e archived:false" test "$(jq_clean -r '.[] | select(.hls_id=="178e77a7ddb10079") | .archived' "$S6/last-evicted.json")" = "false"
check "case6: rclone log содержит purge" grep -q 'purge' "$FAKE_RCLONE_LOG"

# === Случай 6b: ошибка purge ===
reset_site
ROOT6B="$T/root6b"
S6B="$T/st6b"
mkdir -p "$ROOT6B" "$S6B" "$FAKE_RCLONE_ROOT/hls/5d0c1f2a9e7b3c44/v1"
mkprof "$ROOT6B" 5d0c1f2a9e7b3c44 v1 100 $((NOW - 10 * D))
printf '{}' > "$FAKE_RCLONE_ROOT/hls/5d0c1f2a9e7b3c44/v1/manifest.json"
printf '5d0c1f2a9e7b3c44 v1 100 %s\n' "$NOW" > "$S6B/archived"

OUT6B=$(FAKE_RCLONE_FAIL="purge=1" bash "$CDN/evict.sh" --root "$ROOT6B" --state-dir "$S6B" --env "$T/cdn.env" --now "$NOW" \
  --min-idle 7200 --log /dev/null --no-report --pending-file "$FIX/pending-deletes-archive.json" \
  --disk-total 1000 --disk-used 100 2>&1 | tr -d '\r')
RC6B=$?
[ -n "${VERBOSE:-}" ] && printf '%s\n' "$OUT6B"

check "case6b: код возврата != 0" test "$RC6B" -ne 0
check "case6b: локальный 5d0c1f2a9e7b3c44 остался" test -d "$ROOT6B/5d0c1f2a9e7b3c44/v1"
check "case6b: архив 5d0c1f2a9e7b3c44 остался" test -d "$FAKE_RCLONE_ROOT/hls/5d0c1f2a9e7b3c44/v1"
check "case6b: last-evicted не содержит 5d0c1f2a9e7b3c44" bash -c '! test -f "$1" || [ "$(jq -r "[.[] | select(.hls_id==\"5d0c1f2a9e7b3c44\")] | length" "$1" | tr -d "\r")" = "0" ]' _ "$S6B/last-evicted.json"
check "case6b: archived всё ещё содержит 5d0c1f2a9e7b3c44" grep -q "^5d0c1f2a9e7b3c44 v1 " "$S6B/archived"

# === Случай 6c: холодная версия (нет локальной копии) ===
reset_site
ROOT6C="$T/root6c"
S6C="$T/st6c"
mkdir -p "$ROOT6C" "$S6C" "$FAKE_RCLONE_ROOT/hls/cccccccccccccccc/v1"
printf '{}' > "$FAKE_RCLONE_ROOT/hls/cccccccccccccccc/v1/manifest.json"
printf 'cccccccccccccccc v1 100 %s\n' "$NOW" > "$S6C/archived"
printf '{"items":[{"hls_id":"cccccccccccccccc","profile":"v1","archive":true}]}\n' > "$T/p6c.json"

OUT6C=$(bash "$CDN/evict.sh" --root "$ROOT6C" --state-dir "$S6C" --env "$T/cdn.env" --now "$NOW" \
  --min-idle 7200 --log /dev/null --no-report --pending-file "$T/p6c.json" \
  --disk-total 1000 --disk-used 100 2>&1 | tr -d '\r')
RC6C=$?
[ -n "${VERBOSE:-}" ] && printf '%s\n' "$OUT6C"

check "case6c: код возврата 0" test "$RC6C" -eq 0
check "case6c: архив cccccccccccccccc/v1 удалён" test ! -e "$FAKE_RCLONE_ROOT/hls/cccccccccccccccc/v1"
check "case6c: last-evicted cccc archived:false" test "$(jq_clean -r '.[] | select(.hls_id=="cccccccccccccccc") | .archived' "$S6C/last-evicted.json")" = "false"
check "case6c: last-evicted cccc last_access 0" test "$(jq_clean -r '.[] | select(.hls_id=="cccccccccccccccc") | .last_access' "$S6C/last-evicted.json")" = "0"

# === Случай 7: успешное восстановление ===
reset_site
ROOT7="$T/root7"
S7="$T/st7"
Q7="$T/q7"
mkdir -p "$ROOT7/.tmp" "$S7" "$FAKE_RCLONE_ROOT/hls/178e77a7ddb10079/v1/1080p"
mkprof "$ROOT7" 4444444444444444 v1 300 $((NOW - 30 * D))
printf '{}' > "$FAKE_RCLONE_ROOT/hls/178e77a7ddb10079/v1/manifest.json"
head -c 100 /dev/zero > "$FAKE_RCLONE_ROOT/hls/178e77a7ddb10079/v1/1080p/video.m4s"
printf '178e77a7ddb10079 v1 200 %s\n' "$NOW" > "$S7/archived"
cp "$FIX/pending-restores.json" "$FAKE_SITE/resp/internal_hls_pending-restores.json"

OUT7=$(HLS_ARCHIVE_QUEUE="$Q7" \
  HLS_EVICT_EXTRA="--disk-total 1000 --disk-used 700 --high 85 --low 80 --now $NOW --log /dev/null --no-report" \
  bash "$CDN/hls-restore.sh" --once --env "$T/cdn.env" --root "$ROOT7" --state-dir "$S7" --now "$NOW" 2>&1 | tr -d '\r')
RC7=$?
[ -n "${VERBOSE:-}" ] && printf '%s\n' "$OUT7"

check "case7: код возврата 0" test "$RC7" -eq 0
check "case7: manifest.json восстановлен" test -f "$ROOT7/178e77a7ddb10079/v1/manifest.json"
check "case7: 1080p/video.m4s восстановлен" test -f "$ROOT7/178e77a7ddb10079/v1/1080p/video.m4s"
LEFTOVER7=$(find "$ROOT7/.tmp" -mindepth 1 -maxdepth 1 ! -name '.lock' 2>/dev/null)
check "case7: нет лишних каталогов в .tmp" test -z "$LEFTOVER7"
check "case7: 4444444444444444 вытеснен" test ! -e "$ROOT7/4444444444444444"
check "case7: lastaccess содержит 178e77a7ddb10079 v1 NOW" grep -qx "178e77a7ddb10079 v1 $NOW" "$S7/lastaccess"

REQ7=$(last_req "POST-internal_hls_restored")
FIX_RES_OK_KEYS=$(jq_clean -cS '.[0] | keys' "$FIX/restored-request.json")
check "case7: restored ok == true" test "$(printf '%s' "$REQ7" | jq_clean -r '.[0].ok')" = "true"
EXP_BYTES7=$(du -sb "$ROOT7/178e77a7ddb10079/v1" | awk '{print $1}')
check "case7: restored bytes совпадает с du -sb" test "$(printf '%s' "$REQ7" | jq_clean -r '.[0].bytes')" = "$EXP_BYTES7"
check "case7: restored hls_id" test "$(printf '%s' "$REQ7" | jq_clean -r '.[0].hls_id')" = "178e77a7ddb10079"
check "case7: restored profile" test "$(printf '%s' "$REQ7" | jq_clean -r '.[0].profile')" = "v1"
check "case7: ключи restored совпадают с фикстурой" test "$(printf '%s' "$REQ7" | jq_clean -cS '.[0] | keys')" = "$FIX_RES_OK_KEYS"
check "case7: очередь архивации пуста" bash -c '[ ! -d "$1" ] || [ -z "$(ls -A "$1")" ]' _ "$Q7"

# === Случай 8: восстановление not_in_archive ===
reset_site
rm -rf "$FAKE_RCLONE_ROOT/hls/5d0c1f2a9e7b3c44"
ROOT8="$T/root8"
S8="$T/st8"
mkdir -p "$ROOT8" "$S8"
printf '{"items":[{"hls_id":"5d0c1f2a9e7b3c44","profile":"v1","requested_at":1}]}\n' > "$FAKE_SITE/resp/internal_hls_pending-restores.json"

OUT8=$(bash "$CDN/hls-restore.sh" --once --env "$T/cdn.env" --root "$ROOT8" --state-dir "$S8" --now "$NOW" 2>&1 | tr -d '\r')
RC8=$?
[ -n "${VERBOSE:-}" ] && printf '%s\n' "$OUT8"

check "case8: код возврата 0" test "$RC8" -eq 0
REQ8=$(last_req "POST-internal_hls_restored")
FIX_RES_ERR_KEYS=$(jq_clean -cS '.[1] | keys' "$FIX/restored-request.json")
check "case8: restored ok == false" test "$(printf '%s' "$REQ8" | jq_clean -r '.[0].ok')" = "false"
check "case8: restored error == not_in_archive" test "$(printf '%s' "$REQ8" | jq_clean -r '.[0].error')" = "not_in_archive"
check "case8: ключи ошибки совпадают с фикстурой" test "$(printf '%s' "$REQ8" | jq_clean -cS '.[0] | keys')" = "$FIX_RES_ERR_KEYS"
check "case8: профиль не создан" test ! -e "$ROOT8/5d0c1f2a9e7b3c44"

# === Случай 9: профиль уже на диске ===
reset_site
: > "$FAKE_RCLONE_LOG"
ROOT9="$T/root9"
S9="$T/st9"
mkdir -p "$ROOT9" "$S9"
mkprof "$ROOT9" 178e77a7ddb10079 v1 100 $((NOW - D))
cp "$FIX/pending-restores.json" "$FAKE_SITE/resp/internal_hls_pending-restores.json"

OUT9=$(bash "$CDN/hls-restore.sh" --once --env "$T/cdn.env" --root "$ROOT9" --state-dir "$S9" --now "$NOW" 2>&1 | tr -d '\r')
RC9=$?
[ -n "${VERBOSE:-}" ] && printf '%s\n' "$OUT9"

check "case9: код возврата 0" test "$RC9" -eq 0
REQ9=$(last_req "POST-internal_hls_restored")
check "case9: restored ok == true" test "$(printf '%s' "$REQ9" | jq_clean -r '.[0].ok')" = "true"
EXP_BYTES9=$(du -sb "$ROOT9/178e77a7ddb10079/v1" | awk '{print $1}')
check "case9: restored bytes совпадает с du -sb" test "$(printf '%s' "$REQ9" | jq_clean -r '.[0].bytes')" = "$EXP_BYTES9"
check "case9: rclone copy не вызывался" bash -c '! grep -qE "copy|sync" "$1"' _ "$FAKE_RCLONE_LOG"

echo "Итог: $PASS ok, $FAIL FAIL"
[ "$FAIL" -eq 0 ]
