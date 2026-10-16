#!/usr/bin/env bash
# Builds the Media3 FFmpeg audio decoder (DTS, TrueHD, AC3, E-AC3) as an AAR for the Android app.
# Everything is pinned below, so the same inputs give the same decoder set. Linux or macOS, needs git, make,
# Java 17 and an Android SDK (ANDROID_HOME) with the NDK below installed (or NDK_PATH pointing at it).
#
#   android/ffmpeg/build.sh            → android/app/libs/media3-decoder-ffmpeg-<MEDIA3_VERSION>.aar
#
# Bumping MEDIA3_VERSION: change it here AND `media3` in android/app/build.gradle.kts, then rebuild.
set -euo pipefail

MEDIA3_VERSION="1.5.1"          # androidx/media tag; must match the media3-* artifacts the app uses
FFMPEG_TAG="n6.0.1"             # release/6.0 line, the version Media3 recommends
NDK_VERSION="27.2.12479018"     # r27c: API 21+, 16 KB page alignment
ANDROID_API=21                  # native min API (≤ app minSdk 24)
ABI="arm64-v8a"                 # the app ships arm64 only
# ffmpeg decoder names Media3 maps to: ac3, eac3 (+ JOC/Atmos), dca (DTS, DTS-HD core), truehd (+ mlp, same decoder)
DECODERS=(ac3 eac3 dca truehd mlp)

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../app/libs/media3-decoder-ffmpeg-$MEDIA3_VERSION.aar"
WORK="${WORK:-$HERE/work}"
case "$(uname -s)" in Linux) HOST="linux-x86_64" ;; Darwin) HOST="darwin-x86_64" ;; *) echo "Linux/macOS only" >&2; exit 1 ;; esac
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
NDK_PATH="${NDK_PATH:-$ANDROID_HOME/ndk/$NDK_VERSION}"
[ -d "$NDK_PATH/toolchains/llvm/prebuilt/$HOST" ] || {
  echo "NDK $NDK_VERSION not found at $NDK_PATH. Install: sdkmanager \"ndk;$NDK_VERSION\"" >&2; exit 1; }

mkdir -p "$WORK"
MEDIA="$WORK/media-$MEDIA3_VERSION"
FFMPEG="$WORK/ffmpeg-$FFMPEG_TAG"
[ -d "$MEDIA" ] || git clone --depth 1 -b "$MEDIA3_VERSION" https://github.com/androidx/media.git "$MEDIA"
[ -d "$FFMPEG" ] || git clone --depth 1 -b "$FFMPEG_TAG" https://github.com/FFmpeg/FFmpeg.git "$FFMPEG"

MODULE="$MEDIA/libraries/decoder_ffmpeg/src/main"
ln -sfn "$FFMPEG" "$MODULE/jni/ffmpeg"
# Upstream script builds all four ABIs; only arm64 is packaged below, the rest is a few extra minutes.
(cd "$MODULE/jni" && ./build_ffmpeg.sh "$MODULE" "$NDK_PATH" "$HOST" "$ANDROID_API" "${DECODERS[@]}")

# Same NDK for the JNI glue as for ffmpeg itself (otherwise AGP picks its own default NDK).
GRADLE_FILE="$MEDIA/libraries/decoder_ffmpeg/build.gradle"
grep -q "ndkVersion" "$GRADLE_FILE" || echo "android.ndkVersion = '$NDK_VERSION'" >> "$GRADLE_FILE"
echo "sdk.dir=$ANDROID_HOME" > "$MEDIA/local.properties"
(cd "$MEDIA" && ./gradlew --no-daemon -q :lib-decoder-ffmpeg:assembleRelease -Pandroid.injected.build.abi="$ABI")

AAR="$(find "$MEDIA/libraries/decoder_ffmpeg" -name '*release.aar' -path '*outputs/aar*' | head -1)"
[ -n "$AAR" ] || { echo "AAR not found" >&2; exit 1; }
mkdir -p "$(dirname "$OUT")"
cp "$AAR" "$OUT"
# Drop other ABIs if any slipped in; the app packages arm64-v8a only.
for other in armeabi-v7a x86 x86_64; do zip -qd "$OUT" "jni/$other/*" 2>/dev/null || true; done
unzip -l "$OUT" | grep -q "jni/$ABI/libffmpegJNI.so" || { echo "libffmpegJNI.so for $ABI missing in AAR" >&2; exit 1; }
echo "Built $OUT ($(du -h "$OUT" | cut -f1)); decoders: ${DECODERS[*]}"
