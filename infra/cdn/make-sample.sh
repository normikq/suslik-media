#!/bin/bash
# Образец ассета для test.sh: <корень>/0000000000000000/v1/ (не настоящее видео — только файлы нужных типов).
# Использование: make-sample.sh [/srv/hls]
set -euo pipefail
ROOT=${1:-/srv/hls}
D="$ROOT/0000000000000000/v1"
mkdir -p "$D/1080p" "$D/s0"
printf '{"sample":true,"note":"образец для infra/cdn/test.sh"}\n' > "$D/manifest.json"
head -c 1048576 /dev/urandom > "$D/1080p/video.m4s"
cat > "$D/1080p/index.m3u8" <<'M3U8'
#EXTM3U
#EXT-X-VERSION:7
#EXT-X-TARGETDURATION:6
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-MAP:URI="video.m4s",BYTERANGE="1000@0"
#EXTINF:6.000,
#EXT-X-BYTERANGE:1047576@1000
video.m4s
#EXT-X-ENDLIST
M3U8
printf 'WEBVTT\n\n00:00:00.000 --> 00:00:02.000\nОбразец\n' > "$D/s0/subs.vtt"
printf '#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:6.000,\nsubs.vtt\n#EXT-X-ENDLIST\n' > "$D/s0/index.m3u8"
chmod -R u=rwX,go=rX "$ROOT/0000000000000000"
echo "образец: $D"
