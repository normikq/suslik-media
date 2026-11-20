#!/bin/bash
# evict-offline.sh — автономная проверка ../evict.sh (без сети, в Git Bash и Linux).
set -uo pipefail
export LC_ALL=C

HERE=$(cd "$(dirname "$0")" && pwd)
EVICT="$HERE/../evict.sh"
FIX="$HERE/../../../docs/hls-fixtures"

NOW=1767225600
D=86400

T=$(mktemp -d)
cleanup() { [ -n "${KEEP:-}" ] && echo "оставлено: $T" || rm -rf -- "$T"; }
trap cleanup EXIT

ROOT="$T/hls"
mkdir -p "$ROOT"

# Создать профиль HID/PROF с точным размером SIZE (через manifest.json + video.m4s) и временем MTIME
mkprof() {
  local hid=$1 prof=$2 size=$3 mtime=$4
  local dir="$ROOT/$hid/$prof"
  mkdir -p "$dir"
  printf '{}' > "$dir/manifest.json"
  local msz
  msz=$(wc -c < "$dir/manifest.json")
  head -c $((size - msz)) /dev/zero > "$dir/video.m4s"
  touch -d "@$mtime" "$dir"
}

# Дерево каталогов для тестов
mkprof aaaaaaaaaaaaaaaa v1      100 $((NOW - 10 * D))
mkprof aaaaaaaaaaaaaaaa v1-copy  50 $((NOW - 20 * D))
mkprof bbbbbbbbbbbbbbbb v1      200 $((NOW - 30 * D))
mkprof cccccccccccccccc v1        5 $((NOW - 40 * D))
mkprof dddddddddddddddd v1      300 $((NOW - 50 * D))
mkprof eeeeeeeeeeeeeeee v1       80 $((NOW - 25 * D))
mkprof 178e77a7ddb10079 v1-copy  40 $((NOW - 30 * D))
mkprof 178e77a7ddb10079 v1      100 $((NOW - 1 * D))
mkdir -p "$ROOT/notahexid/v1"

# Брошенные и свежие загрузки в .tmp
mkdir -p "$ROOT/.tmp/stale.x" "$ROOT/.tmp/fresh.x"
REAL_NOW=$(date +%s)
touch -d "@$((REAL_NOW - 3 * D))" "$ROOT/.tmp/stale.x"
touch -d "@$REAL_NOW" "$ROOT/.tmp/fresh.x"

# Журнал доступа nginx
cat > "$T/hls-access.log" <<EOF
$((NOW - 5 * D)).123 aaaaaaaaaaaaaaaa v1-copy 206 1024 GET 10.0.0.1
$((NOW - 1 * D)).123 aaaaaaaaaaaaaaaa v1 206 1024 GET 10.0.0.1
$((NOW - 3 * D)).123 bbbbbbbbbbbbbbbb v1 200 1024 GET 10.0.0.1
$((NOW - 600)).123 dddddddddddddddd v1 206 1024 GET 10.0.0.1
$((NOW - 2 * D)).123 178e77a7ddb10079 v1-copy 206 1024 GET 10.0.0.1
$((NOW - 1)).123 eeeeeeeeeeeeeeee v1 403 0 GET 10.0.0.1
garbage line without valid fields
EOF

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

COMMON=(--root "$ROOT" --log "$T/hls-access.log" --now "$NOW" --min-idle 7200 --env /dev/null)

# === Случай 1: dry-run ===
OUT1=$(bash "$EVICT" "${COMMON[@]}" --dry-run --state-dir "$T/st1" \
  --pending-file "$FIX/pending-deletes.json" \
  --disk-total 1000 --disk-used 900 --high 85 --low 80 2>&1 | tr -d '\r')
RC1=$?
[ -n "${VERBOSE:-}" ] && printf "%s
" "$OUT1"

check "case1: код возврата 0" test "$RC1" -eq 0

DEL_LIST1=$(printf '%s\n' "$OUT1" | grep '\[dry-run\] удалить ' | grep -v 'брошенную загрузку' | sed -n 's/.*удалить \([^ ]*\).*/\1/p' | paste -sd' ' -)
check "case1: порядок удаления" test "$DEL_LIST1" = "178e77a7ddb10079/v1-copy aaaaaaaaaaaaaaaa/v1-copy cccccccccccccccc/v1 eeeeeeeeeeeeeeee/v1"
check "case1: упомянута брошенная загрузка stale.x" grep -q 'брошенную загрузку stale.x' <<< "$OUT1"
check "case1: fresh.x не упомянута" bash -c '! grep -q "fresh\.x" <<< "$1"' _ "$OUT1"

EV_JSON1=$(printf '%s\n' "$OUT1" | sed -n 's/^evicted: //p')
check "case1: evicted длина 4" test "$(printf '%s' "$EV_JSON1" | jq_clean 'length')" = "4"
check "case1: evicted[0].hls_id" test "$(printf '%s' "$EV_JSON1" | jq_clean -r '.[0].hls_id')" = "178e77a7ddb10079"
check "case1: evicted[0].profile" test "$(printf '%s' "$EV_JSON1" | jq_clean -r '.[0].profile')" = "v1-copy"
check "case1: evicted[0].last_access" test "$(printf '%s' "$EV_JSON1" | jq_clean -r '.[0].last_access')" = "$((NOW - 2 * D))"
check "case1: eeee last_access (403 игнорирован)" test "$(printf '%s' "$EV_JSON1" | jq_clean -r '.[] | select(.hls_id=="eeeeeeeeeeeeeeee") | .last_access')" = "$((NOW - 25 * D))"
check "case1: cccc last_access" test "$(printf '%s' "$EV_JSON1" | jq_clean -r '.[] | select(.hls_id=="cccccccccccccccc") | .last_access')" = "$((NOW - 40 * D))"

# с §17 у каждого элемента evicted есть archived (без архива — false)
FIX_EV_KEYS=$(jq_clean -cS '.[0] | keys' "$FIX/evicted-request-archived.json")
check "case1: ключи evicted совпадают с фикстурой" test "$(printf '%s' "$EV_JSON1" | jq_clean -cS 'all(keys == '"$FIX_EV_KEYS"')')" = "true"
check "case1: без архива все archived:false" test "$(printf '%s' "$EV_JSON1" | jq_clean -c 'all(.archived == false)')" = "true"

ST_JSON1=$(printf '%s\n' "$OUT1" | sed -n 's/^cdn-stats: //p')
check "case1: cdn-stats disk_total" test "$(printf '%s' "$ST_JSON1" | jq_clean -r '.disk_total')" = "1000"
check "case1: cdn-stats disk_used" test "$(printf '%s' "$ST_JSON1" | jq_clean -r '.disk_used')" = "725"
check "case1: cdn-stats assets" test "$(printf '%s' "$ST_JSON1" | jq_clean -r '.assets')" = "4"
check "case1: cdn-stats profiles" test "$(printf '%s' "$ST_JSON1" | jq_clean -r '.profiles')" = "4"
check "case1: cdn-stats at" test "$(printf '%s' "$ST_JSON1" | jq_clean -r '.at')" = "$NOW"

FIX_ST_KEYS=$(jq_clean -cS 'keys' "$FIX/cdn-stats-request.json")
check "case1: ключи cdn-stats совпадают с фикстурой" test "$(printf '%s' "$ST_JSON1" | jq_clean -cS 'keys')" = "$FIX_ST_KEYS"

check "case1: все 8 профилей на месте" test -d "$ROOT/178e77a7ddb10079/v1-copy" -a -d "$ROOT/aaaaaaaaaaaaaaaa/v1-copy" -a -d "$ROOT/cccccccccccccccc/v1" -a -d "$ROOT/eeeeeeeeeeeeeeee/v1"
check "case1: stale.x на месте" test -d "$ROOT/.tmp/stale.x"
check "case1: state-dir st1 не создан" test ! -e "$T/st1"

# === Случай 2: min-idle и плохая запись в pending ===
cat > "$T/p2.json" <<'EOF'
{"items":[{"hls_id":"dddddddddddddddd","profile":"v1"},{"hls_id":"ffffffffffffffff","profile":"v1"},{"hls_id":"../../etc","profile":"v1"}]}
EOF

OUT2=$(bash "$EVICT" "${COMMON[@]}" --dry-run --state-dir "$T/st2" \
  --pending-file "$T/p2.json" \
  --disk-total 1000 --disk-used 990 --low 10 --high 85 2>&1 | tr -d '\r')
RC2=$?

check "case2: код возврата 0" test "$RC2" -eq 0
check "case2: отложено dddddddddddddddd/v1" grep -q 'отложено dddddddddddddddd/v1' <<< "$OUT2"
check "case2: пропуск dddddddddddddddd/v1" grep -q 'пропуск dddddddddddddddd/v1' <<< "$OUT2"
check "case2: ВНИМАНИЕ о плохой записи" grep -q 'ВНИМАНИЕ: pending-deletes: плохая запись' <<< "$OUT2"

DEL_LIST2=$(printf '%s\n' "$OUT2" | grep '\[dry-run\] удалить ' | grep -v 'брошенную загрузку' | sed -n 's/.*удалить \([^ ]*\).*/\1/p')
check "case2: dddddddddddddddd не в списке удаления" bash -c '! grep -q "dddddddddddddddd" <<< "$1"' _ "$DEL_LIST2"

EV_JSON2=$(printf '%s\n' "$OUT2" | sed -n 's/^evicted: //p')
check "case2: ffffffffffffffff/v1 в evicted с last_access 0" test "$(printf '%s' "$EV_JSON2" | jq_clean -r '.[] | select(.hls_id=="ffffffffffffffff" and .profile=="v1") | .last_access')" = "0"

# === Случай 3: реальный запуск ===
OUT3=$(bash "$EVICT" "${COMMON[@]}" --no-report --state-dir "$T/st3" \
  --pending-file "$FIX/pending-deletes.json" \
  --disk-total 1000 --disk-used 900 --high 85 --low 80 2>&1 | tr -d '\r')
RC3=$?

check "case3: код возврата 0" test "$RC3" -eq 0
check "case3: 178e77a7ddb10079/v1-copy удалён" test ! -e "$ROOT/178e77a7ddb10079/v1-copy"
check "case3: aaaaaaaaaaaaaaaa/v1-copy удалён" test ! -e "$ROOT/aaaaaaaaaaaaaaaa/v1-copy"
check "case3: cccccccccccccccc удалён целиком" test ! -e "$ROOT/cccccccccccccccc"
check "case3: eeeeeeeeeeeeeeee удалён целиком" test ! -e "$ROOT/eeeeeeeeeeeeeeee"
check "case3: aaaaaaaaaaaaaaaa/v1 остался" test -d "$ROOT/aaaaaaaaaaaaaaaa/v1"
check "case3: 178e77a7ddb10079/v1 остался" test -d "$ROOT/178e77a7ddb10079/v1"
check "case3: bbbbbbbbbbbbbbbb остался" test -d "$ROOT/bbbbbbbbbbbbbbbb/v1"
check "case3: dddddddddddddddd остался" test -d "$ROOT/dddddddddddddddd/v1"
check "case3: .tmp/stale.x удалена" test ! -e "$ROOT/.tmp/stale.x"
check "case3: .tmp/fresh.x осталась" test -d "$ROOT/.tmp/fresh.x"

check "case3: evicted.spool содержит 4 строки" test "$(wc -l < "$T/st3/evicted.spool" | tr -d ' ')" = "4"
check "case3: last-evicted.json существует и валиден" test "$(jq_clean -cS 'all(keys == '"$FIX_EV_KEYS"')' "$T/st3/last-evicted.json")" = "true"
check "case3: last-cdn-stats.json существует и валиден" test "$(jq_clean -cS 'keys' "$T/st3/last-cdn-stats.json")" = "$FIX_ST_KEYS"
check "case3: lastaccess содержит bbbb v1" grep -qx "bbbbbbbbbbbbbbbb v1 $((NOW - 3 * D))" "$T/st3/lastaccess"
check "case3: lastaccess не содержит cccc" bash -c '! grep -q "^cccccccccccccccc " "$1"' _ "$T/st3/lastaccess"

# === Случай 4: повторный реальный запуск при низком заполнении ===
OUT4=$(bash "$EVICT" "${COMMON[@]}" --no-report --state-dir "$T/st3" \
  --disk-total 1000 --disk-used 500 --high 85 --low 80 2>&1 | tr -d '\r')
RC4=$?

check "case4: код возврата 0" test "$RC4" -eq 0
check "case4: удалено 0" grep -q 'готово: удалено 0,' <<< "$OUT4"
check "case4: evicted.spool всё ещё 4 строки" test "$(wc -l < "$T/st3/evicted.spool" | tr -d ' ')" = "4"

echo "Итог: $PASS ok, $FAIL FAIL"
[ "$FAIL" -eq 0 ]
