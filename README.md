# Suslik Media

| Папка | Что | Документация |
|---|---|---|
| `docs/` | Архитектура и документация проекта | [Общая архитектура](file:///c:/Users/nanar/OneDrive/Desktop/suslik-media-mpv/docs/ARCHITECTURE.md) |
| `website/` | Сайт и API (Node/Express) | [docs/WEBSITE.md](file:///c:/Users/nanar/OneDrive/Desktop/suslik-media-mpv/docs/WEBSITE.md), [website/README.md](file:///c:/Users/nanar/OneDrive/Desktop/suslik-media-mpv/website/README.md) |
| `android/` | Android-приложение (Kotlin, Compose, libVLC) | [docs/ANDROID.md](file:///c:/Users/nanar/OneDrive/Desktop/suslik-media-mpv/docs/ANDROID.md), [android/DEBUG-BUILD.md](file:///c:/Users/nanar/OneDrive/Desktop/suslik-media-mpv/android/DEBUG-BUILD.md) |
| `desktop/` | Windows-приложение (Compose Desktop, mpv / VLC) | [docs/DESKTOP.md](file:///c:/Users/nanar/OneDrive/Desktop/suslik-media-mpv/docs/DESKTOP.md), [desktop/README.md](file:///c:/Users/nanar/OneDrive/Desktop/suslik-media-mpv/desktop/README.md) |
| `shared/` | Общий модуль (BurstProxy, Supervisor, TsClock) | [В общей архитектуре](file:///c:/Users/nanar/OneDrive/Desktop/suslik-media-mpv/docs/ARCHITECTURE.md#5-общий-модуль-shared) |

## Сборка (GitHub Actions)

- **Android APK** — при каждом изменении в `android/`. Артефакт `android-apk` (Actions → запуск → Artifacts).
- **Windows MSI** — при каждом изменении в `desktop/`. Артефакт `windows-msi`.
- Ручной запуск (Actions → workflow → Run workflow) с галочкой «Выложить на сайт» — отправляет файл в `updates/` сайта.

Секреты репозитория (Settings → Secrets and variables → Actions):

| Секрет | Для чего |
|---|---|
| `ANDROID_KEYSTORE_B64` | release-ключ в base64 (`base64 -w0 release.jks`) |
| `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | пароли/алиас ключа |
| `UPDATE_TOKEN` | тот же, что `UPDATE_TOKEN` в `.env` сайта |
| `SITE_URL` | `https://short.suslikovanora.ru` |

Без ключа собирается debug-APK (для проверки компиляции; ставить как обновление нельзя).
