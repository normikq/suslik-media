#!/bin/bash
# finalize-offline.sh — приём загрузки (hls-finalize) и разбор команд (hls-upload-shell) без ssh. Git Bash и Linux.
# flock подменяется заглушкой, если его нет (Git Bash).
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
BIN="$HERE/../bin"
T=$(mktemp -d); trap 'rm -rf -- "$T"' EXIT
mkdir -p "$T/stub" "$T/hls/.tmp"
if ! command -v flock >/dev/null; then printf '#!/bin/sh\nexit 0\n' > "$T/stub/flock"; chmod +x "$T/stub/flock"; fi
export PATH="$T/stub:$PATH" HLS_ROOT="$T/hls" LIB_DIR="$BIN"
PASS=0; FAIL=0
check() { local n=$1; shift; if "$@"; then PASS=$((PASS + 1)); echo "  ok   $n"; else FAIL=$((FAIL + 1)); echo "  FAIL $n"; fi; }
upload() { mkdir -p "$T/hls/.tmp/$1/1080p"; printf '%s' "$2" > "$T/hls/.tmp/$1/manifest.json"; echo x > "$T/hls/.tmp/$1/1080p/video.m4s"; }
H=178e77a7ddb10079

upload "$H.v1.ab12cd" '{"n":1}'
out=$(bash "$BIN/hls-finalize" "$H.v1.ab12cd" 2>&1); rc=$?
check "приём v1 → код 0" [ "$rc" = 0 ]
check "вывод «ok $H/v1 …»" grep -q "^ok $H/v1 " <<< "$out"
check "профиль на месте" [ -f "$T/hls/$H/v1/1080p/video.m4s" ]
check ".tmp/<имя> исчезла" [ ! -e "$T/hls/.tmp/$H.v1.ab12cd" ]

upload "$H.v1.zz99zz" '{"n":2}'
bash "$BIN/hls-finalize" "$H.v1.zz99zz" >/dev/null 2>&1
check "перезалив заменяет профиль" [ "$(cat "$T/hls/$H/v1/manifest.json")" = '{"n":2}' ]
check "старый профиль не остался в .tmp" [ -z "$(ls -A "$T/hls/.tmp" | grep -v '^\.lock$')" ]

upload "$H.v1-copy.q1w2e3" '{}'
SSH_ORIGINAL_COMMAND="finalize $H.v1-copy.q1w2e3" bash "$BIN/hls-upload-shell" >/dev/null 2>&1
check "finalize через hls-upload-shell" [ -d "$T/hls/$H/v1-copy" ]

for bad in "../etc.v1.abcd" "$H.v2.abcd" "$H.v1.ab" "$H.v1.nope" "ABCDEF0123456789.v1.abcd"; do
  bash "$BIN/hls-finalize" "$bad" >/dev/null 2>&1
  check "отказ: $bad" [ $? -ne 0 ]
done
mkdir -p "$T/hls/.tmp/$H.v1.nomani"
bash "$BIN/hls-finalize" "$H.v1.nomani" >/dev/null 2>&1
check "отказ: нет manifest.json" [ $? -ne 0 ]

SSH_ORIGINAL_COMMAND="rm -rf /" bash "$BIN/hls-upload-shell" >/dev/null 2>&1
check "чужая команда запрещена" [ $? -ne 0 ]
SSH_ORIGINAL_COMMAND="finalize a b" bash "$BIN/hls-upload-shell" >/dev/null 2>&1
check "finalize с лишним аргументом запрещён" [ $? -ne 0 ]
SSH_ORIGINAL_COMMAND="finalize *" bash "$BIN/hls-upload-shell" >/dev/null 2>&1
check "finalize * не раскрывается" [ $? -ne 0 ]

echo "Итог: $PASS ok, $FAIL FAIL"
[ "$FAIL" = 0 ]
