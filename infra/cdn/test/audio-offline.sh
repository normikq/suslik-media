#!/bin/bash
# audio-offline.sh — автономная проверка внешней аудиодорожки (hls-finalize-audio, hls-upload-shell, evict.sh).
set -uo pipefail
export LC_ALL=C

HERE=$(cd "$(dirname "$0")" && pwd)
BIN="$HERE/../bin"
CDN="$HERE/.."
FIX="$HERE/../../../docs/hls-fixtures"

NOW=1767225600
D=86400
H="178e77a7ddb10079"

T=$(mktemp -d)
cleanup() { [ -n "${KEEP:-}" ] && echo "оставлено: $T" || rm -rf -- "$T"; }
trap cleanup EXIT

mkdir -p "$T/stub"
cp "$HERE/fake-curl" "$T/stub/curl"
chmod +x "$T/stub/curl"
if ! command -v flock >/dev/null 2>&1; then
  printf '#!/bin/sh\nexit 0\n' > "$T/stub/flock"
  chmod +x "$T/stub/flock"
fi
export PATH="$T/stub:$PATH"

export HLS_ROOT="$T/hls"
export LIB_DIR="$BIN"
export HLS_RCLONE="$HERE/fake-rclone"
export FAKE_RCLONE_ROOT="$T/archive"
export FAKE_RCLONE_LOG="$T/rclone.log"

PASS=0
FAIL=0
check() {
  local name=$1; shift
  if "$@"; then
    printf '  ok   %s\n' "$name"
    PASS=$((PASS + 1))
  else
    printf '  FAIL %s\n' "$name"
    FAIL=$((FAIL + 1))
  fi
}

jq_clean() { jq "$@" | tr -d '\r'; }

mkprofile() {
  local hid=$1 prof=$2
  mkdir -p "$T/hls/$hid/$prof/1080p"
  printf '{}' > "$T/hls/$hid/$prof/manifest.json"
  printf 'vid' > "$T/hls/$hid/$prof/1080p/video.m4s"
}

upload_audio() {
  local name=$1; shift
  local xn
  for xn in "$@"; do
    mkdir -p "$T/hls/.tmp/$name/$xn"
    printf '#EXTM3U\n' > "$T/hls/.tmp/$name/$xn/index.m3u8"
    printf '%s' "${CONTENT:-aud}" > "$T/hls/.tmp/$name/$xn/audio.m4s"
  done
}

# === Часть A: hls-finalize-audio ===

# 1) Успешная финализация x0 и x1
mkprofile "$H" v1
upload_audio "$H.v1.a1b2c3d4" x0 x1
OUT=$(bash "$BIN/hls-finalize-audio" "$H.v1.a1b2c3d4" 2>&1 | tr -d '\r')
RC=$?
check "A1: код возврата 0" test "$RC" -eq 0
check "A1: вывод соответствует формату" grep -qE "^ok $H/v1 [0-9]+$" <<< "$OUT"
check "A1: x0/audio.m4s существует" test -f "$T/hls/$H/v1/x0/audio.m4s"
check "A1: x1/index.m3u8 существует" test -f "$T/hls/$H/v1/x1/index.m3u8"
check "A1: .tmp/NAME удалён" test ! -e "$T/hls/.tmp/$H.v1.a1b2c3d4"
check "A1: нет .x0.new/.x0.old в профиле" bash -c '! ls -A "$1" | grep -qE "^\.x0\.(new|old)$"' _ "$T/hls/$H/v1"
check "A1: 1080p/video.m4s на месте" test -f "$T/hls/$H/v1/1080p/video.m4s"

# 2) Замена существующей дорожки x0
CONTENT="new" upload_audio "$H.v1.a9z9z9z9" x0
OUT=$(bash "$BIN/hls-finalize-audio" "$H.v1.a9z9z9z9" 2>&1 | tr -d '\r')
RC=$?
check "A2: замена x0 код 0" test "$RC" -eq 0
check "A2: x0/audio.m4s обновлён" test "$(cat "$T/hls/$H/v1/x0/audio.m4s")" = "new"
check "A2: x1 остался на месте" test -f "$T/hls/$H/v1/x1/audio.m4s"

# 3) Отказ без профиля
upload_audio "5d0c1f2a9e7b3c44.v1.aqqqqqq" x0
bash "$BIN/hls-finalize-audio" "5d0c1f2a9e7b3c44.v1.aqqqqqq" >/dev/null 2>&1
RC=$?
check "A3: отказ без профиля" test "$RC" -ne 0
check "A3: каталог 5d0c1f2a9e7b3c44 не создан" test ! -e "$T/hls/5d0c1f2a9e7b3c44"

# 4) Отказ при лишнем файле внутри x2
upload_audio "$H.v1.aextra1" x2
printf 'junk' > "$T/hls/.tmp/$H.v1.aextra1/x2/extra.txt"
bash "$BIN/hls-finalize-audio" "$H.v1.aextra1" >/dev/null 2>&1
RC=$?
check "A4: отказ при лишнем файле" test "$RC" -ne 0
check "A4: x2 не перенесён" test ! -e "$T/hls/$H/v1/x2"

# 5) Отказ при лишнем файле на верхнем уровне
upload_audio "$H.v1.aextra2" x3
printf '{}' > "$T/hls/.tmp/$H.v1.aextra2/manifest.json"
bash "$BIN/hls-finalize-audio" "$H.v1.aextra2" >/dev/null 2>&1
RC=$?
check "A5: отказ при manifest.json в .tmp" test "$RC" -ne 0
check "A5: x3 не перенесён" test ! -e "$T/hls/$H/v1/x3"

# 6) Отказ при неверном имени каталога x8
upload_audio "$H.v1.abadx8" x8
bash "$BIN/hls-finalize-audio" "$H.v1.abadx8" >/dev/null 2>&1
RC=$?
check "A6: отказ для x8" test "$RC" -ne 0

# 7) Отказ при отсутствии audio.m4s в x4
mkdir -p "$T/hls/.tmp/$H.v1.amissing/x4"
printf '#EXTM3U\n' > "$T/hls/.tmp/$H.v1.amissing/x4/index.m3u8"
bash "$BIN/hls-finalize-audio" "$H.v1.amissing" >/dev/null 2>&1
RC=$?
check "A7: отказ без audio.m4s" test "$RC" -ne 0

# 8) Отказ при неверных именах загрузки
for BAD in "$H.v1.b1234567" "$H.v2.a1234" "../x.v1.a1234" "$H.v1.a12"; do
  bash "$BIN/hls-finalize-audio" "$BAD" >/dev/null 2>&1
  RC=$?
  check "A8: отказ для имени $BAD" test "$RC" -ne 0
done

# 9) Отказ при симлинке
mkdir -p "$T/hls/.tmp/$H.v1.asymlink/x5"
printf '#EXTM3U\n' > "$T/hls/.tmp/$H.v1.asymlink/x5/index.m3u8"
ln -s /etc/passwd "$T/hls/.tmp/$H.v1.asymlink/x5/audio.m4s" 2>/dev/null || true
if test -L "$T/hls/.tmp/$H.v1.asymlink/x5/audio.m4s"; then
  bash "$BIN/hls-finalize-audio" "$H.v1.asymlink" >/dev/null 2>&1
  RC=$?
  check "A9: отказ при симлинке" test "$RC" -ne 0
else
  echo "  пропуск: нет symlink"
fi

# 10) Очередь архивации
export HLS_ARCHIVE_QUEUE="$T/queue"
mkdir -p "$HLS_ARCHIVE_QUEUE"
upload_audio "$H.v1.aqueue1" x6
bash "$BIN/hls-finalize-audio" "$H.v1.aqueue1" >/dev/null 2>&1
RC=$?
check "A10: v1 код 0" test "$RC" -eq 0
check "A10: файл очереди для v1 создан" test -e "$HLS_ARCHIVE_QUEUE/$H.v1"
rm -f "$HLS_ARCHIVE_QUEUE/$H.v1"

mkprofile "$H" v1-copy
upload_audio "$H.v1-copy.aqueue2" x0
bash "$BIN/hls-finalize-audio" "$H.v1-copy.aqueue2" >/dev/null 2>&1
RC=$?
check "A10: v1-copy код 0" test "$RC" -eq 0
check "A10: файл очереди для v1 не создан" test ! -e "$HLS_ARCHIVE_QUEUE/$H.v1"
unset HLS_ARCHIVE_QUEUE

# === Часть B: hls-upload-shell ===

upload_audio "$H.v1.ashell1" x7
SSH_ORIGINAL_COMMAND="finalize-audio $H.v1.ashell1" bash "$BIN/hls-upload-shell" >/dev/null 2>&1
RC=$?
check "B1: finalize-audio через shell код 0" test "$RC" -eq 0
check "B1: каталог x7 появился" test -d "$T/hls/$H/v1/x7"

SSH_ORIGINAL_COMMAND="finalize-audio a b" bash "$BIN/hls-upload-shell" >/dev/null 2>&1
check "B2: отказ при лишнем аргументе" test "$?" -ne 0

SSH_ORIGINAL_COMMAND="finalize-audio " bash "$BIN/hls-upload-shell" >/dev/null 2>&1
check "B3: отказ при пустом аргументе" test "$?" -ne 0

SSH_ORIGINAL_COMMAND="finalize-audio *" bash "$BIN/hls-upload-shell" >/dev/null 2>&1
check "B4: отказ при *" test "$?" -ne 0

SSH_ORIGINAL_COMMAND="finalize-audiox $H.v1.ashell2" bash "$BIN/hls-upload-shell" >/dev/null 2>&1
check "B5: отказ при неизвестной команде" test "$?" -ne 0

# === Часть C: evict.sh с path ===

cat > "$T/cdn.env" <<EOF
SM_SITE_URL=site.test
HLS_CDN_TOKEN=tok123
HLS_ARCHIVE_REMOTE=fake-crypt:hls
EOF

cat > "$T/cdn-noarch.env" <<EOF
SM_SITE_URL=site.test
HLS_CDN_TOKEN=tok123
HLS_ARCHIVE_REMOTE=
EOF

mkev() {
  local root=$1 hid=$2 prof=$3
  local dir="$root/$hid/$prof"
  mkdir -p "$dir/x0" "$dir/x1"
  printf '{}' > "$dir/manifest.json"
  printf 'vid' > "$dir/video.m4s"
  printf 'aud0' > "$dir/x0/audio.m4s"
  printf '#EXTM3U\n' > "$dir/x0/index.m3u8"
  printf 'aud1' > "$dir/x1/audio.m4s"
  touch -d "@$((NOW - 10 * D))" "$dir"
}

run_evict() {
  local root=$1 state=$2 envf=$3 pend=$4 logf=${5:-/dev/null}
  bash "$CDN/evict.sh" --root "$root" --state-dir "$state" --env "$envf" --now "$NOW" \
    --min-idle 7200 --log "$logf" --no-report --pending-file "$pend" \
    --disk-total 1000000 --disk-used 1000 2>&1 | tr -d '\r'
}

# C1: удаление path=x0 без архива
R1="$T/ev1" S1="$T/st1"
mkdir -p "$R1" "$S1"
mkev "$R1" "$H" v1
OUT1=$(run_evict "$R1" "$S1" "$T/cdn-noarch.env" "$FIX/pending-deletes-audio.json")
RC1=$?
check "C1: код возврата 0" test "$RC1" -eq 0
check "C1: x0 удалён" test ! -e "$R1/$H/v1/x0"
check "C1: x1 остался" test -d "$R1/$H/v1/x1"
check "C1: manifest.json остался" test -f "$R1/$H/v1/manifest.json"
check "C1: last-evicted длина 1" test "$(jq_clean 'length' "$S1/last-evicted.json")" = "1"
check "C1: path == x0" test "$(jq_clean -r '.[0].path' "$S1/last-evicted.json")" = "x0"
check "C1: archived == false" test "$(jq_clean -r '.[0].archived' "$S1/last-evicted.json")" = "false"
check "C1: ключи last-evicted совпадают" test "$(jq_clean -cS '.[0] | keys' "$S1/last-evicted.json")" = '["archived","hls_id","last_access","path","profile"]'
check "C1: last-cdn-stats profiles == 1" test "$(jq_clean -r '.profiles' "$S1/last-cdn-stats.json")" = "1"

# C2: MIN_IDLE защищает от удаления path
R2="$T/ev2" S2="$T/st2"
mkdir -p "$R2" "$S2"
mkev "$R2" "$H" v1
printf '%s.123 %s v1 206 1024 GET 10.0.0.1\n' "$((NOW - 600))" "$H" > "$T/c2.log"
OUT2=$(run_evict "$R2" "$S2" "$T/cdn-noarch.env" "$FIX/pending-deletes-audio.json" "$T/c2.log")
check "C2: x0 сохранён из-за MIN_IDLE" test -d "$R2/$H/v1/x0"
check "C2: last-evicted пуст" test "$(jq_clean 'length' "$S2/last-evicted.json")" = "0"

# C3: профиль отсутствует на диске
R3="$T/ev3" S3="$T/st3"
mkdir -p "$R3" "$S3"
OUT3=$(run_evict "$R3" "$S3" "$T/cdn-noarch.env" "$FIX/pending-deletes-audio.json")
check "C3: last-evicted длина 1" test "$(jq_clean 'length' "$S3/last-evicted.json")" = "1"
check "C3: path == x0" test "$(jq_clean -r '.[0].path' "$S3/last-evicted.json")" = "x0"

# C4: удаление path из архива
R4="$T/ev4" S4="$T/st4"
mkdir -p "$R4" "$S4" "$FAKE_RCLONE_ROOT/hls/$H/v1/x0" "$FAKE_RCLONE_ROOT/hls/$H/v1/x1"
mkev "$R4" "$H" v1
printf 'aud0' > "$FAKE_RCLONE_ROOT/hls/$H/v1/x0/audio.m4s"
printf 'aud1' > "$FAKE_RCLONE_ROOT/hls/$H/v1/x1/audio.m4s"
printf '%s v1 100 %s\n' "$H" "$NOW" > "$S4/archived"
OUT4=$(run_evict "$R4" "$S4" "$T/cdn.env" "$FIX/pending-deletes-audio.json")
RC4=$?
check "C4: код возврата 0" test "$RC4" -eq 0
check "C4: архив x0 удалён" test ! -e "$FAKE_RCLONE_ROOT/hls/$H/v1/x0"
check "C4: архив x1 остался" test -d "$FAKE_RCLONE_ROOT/hls/$H/v1/x1"
check "C4: запись archived сохранена" grep -q "^$H v1 " "$S4/archived"
check "C4: локальный x0 удалён" test ! -e "$R4/$H/v1/x0"

# C5: ошибка purge в архиве
R5="$T/ev5" S5="$T/st5"
mkdir -p "$R5" "$S5" "$FAKE_RCLONE_ROOT/hls/$H/v1/x0"
mkev "$R5" "$H" v1
printf 'aud0' > "$FAKE_RCLONE_ROOT/hls/$H/v1/x0/audio.m4s"
printf '%s v1 100 %s\n' "$H" "$NOW" > "$S5/archived"
FAKE_RCLONE_FAIL="purge=1" run_evict "$R5" "$S5" "$T/cdn.env" "$FIX/pending-deletes-audio.json" >/dev/null
RC5=$?
check "C5: код возврата != 0" test "$RC5" -ne 0
check "C5: локальный x0 остался" test -d "$R5/$H/v1/x0"
check "C5: last-evicted пуст" test "$(jq_clean 'length' "$S5/last-evicted.json")" = "0"

# C6: недопустимый path отклоняется
R6="$T/ev6" S6="$T/st6"
mkdir -p "$R6" "$S6"
mkev "$R6" "$H" v1
printf '{"items":[{"hls_id":"%s","profile":"v1","path":"x9"},{"hls_id":"%s","profile":"v1","path":"../v1-copy"}]}\n' "$H" "$H" > "$T/p6.json"
OUT6=$(run_evict "$R6" "$S6" "$T/cdn-noarch.env" "$T/p6.json")
check "C6: x0 остался" test -d "$R6/$H/v1/x0"
check "C6: last-evicted пуст" test "$(jq_clean 'length' "$S6/last-evicted.json")" = "0"
check "C6: профиль v1 остался" test -d "$R6/$H/v1"

# C7: смешанный список (path + обычный профиль)
R7="$T/ev7" S7="$T/st7"
mkdir -p "$R7" "$S7"
mkev "$R7" "$H" v1
mkev "$R7" "aaaaaaaaaaaaaaaa" v1-copy
printf '{"items":[{"hls_id":"%s","profile":"v1","path":"x1"},{"hls_id":"aaaaaaaaaaaaaaaa","profile":"v1-copy"}]}\n' "$H" > "$T/p7.json"
OUT7=$(run_evict "$R7" "$S7" "$T/cdn-noarch.env" "$T/p7.json")
check "C7: last-evicted длина 2" test "$(jq_clean 'length' "$S7/last-evicted.json")" = "2"
check "C7: элемент без path не имеет ключа path" test "$(jq_clean -r '.[] | select(.hls_id=="aaaaaaaaaaaaaaaa") | has("path")' "$S7/last-evicted.json")" = "false"

echo "Итог: $PASS ok, $FAIL FAIL"
[ "$FAIL" -eq 0 ]
