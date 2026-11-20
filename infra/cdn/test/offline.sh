#!/bin/bash
# Все офлайн-проверки CDN (без сервера и сети): вектор подписи, evict.sh, приём загрузки, архив, внешние озвучки, просмотр из архива.
# Работает в Git Bash. Живая часть просмотра из архива — если есть nginx (в PATH или HLS_TEST_NGINX=путь) и node, иначе пропуск.
HERE=$(cd "$(dirname "$0")" && pwd)
rc=0
echo "== test.sh --offline";            bash "$HERE/../test.sh" --offline     || rc=1
echo "== test/evict-offline.sh";        bash "$HERE/evict-offline.sh"         || rc=1
echo "== test/finalize-offline.sh";     bash "$HERE/finalize-offline.sh"      || rc=1
echo "== test/archive-offline.sh";      bash "$HERE/archive-offline.sh"       || rc=1
echo "== test/audio-offline.sh";        bash "$HERE/audio-offline.sh"         || rc=1
echo "== test/archive-stream-offline.sh"; bash "$HERE/archive-stream-offline.sh" || rc=1
[ "$rc" = 0 ] && echo "ВСЁ ЗЕЛЁНОЕ" || echo "ЕСТЬ ОШИБКИ"
exit "$rc"
