# Media3 FFmpeg decoder

Звуковой декодер FFmpeg для будущего плеера Media3: DTS (и ядро DTS-HD), Dolby TrueHD, AC3, E-AC3 (включая Atmos JOC).
В Maven его нет, поэтому он собирается из исходников `androidx/media` и `FFmpeg` с закреплёнными версиями.

- Скрипт: `android/ffmpeg/build.sh` (версии Media3, FFmpeg, NDK и список декодеров — в начале файла).
- CI: `.github/workflows/media3-ffmpeg.yml` — запускается при изменении скрипта (не на `main`) или вручную,
  кладёт `android/app/libs/media3-decoder-ffmpeg-<версия>.aar` в ветку, если его там ещё нет.
- Подключение: `android/app/build.gradle.kts`, блок «Media3 + FFmpeg». Пока AAR нет, сборка приложения не меняется.

Локально (Linux/macOS, Java 17, Android SDK):

```
sdkmanager "ndk;27.2.12479018"
android/ffmpeg/build.sh
```

Лицензия: FFmpeg собирается без `--enable-gpl`, то есть под LGPL 2.1+.
