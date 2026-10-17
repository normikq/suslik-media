# Suslik Media

| Папка | Что | Документация |
|---|---|---|
| `docs/` | Архитектура и документация проекта | [Общая архитектура](docs/ARCHITECTURE.md) |
| `website/` | Сайт и API (Node/Express) | [docs/WEBSITE.md](docs/WEBSITE.md), [website/README.md](website/README.md) |
| `android/` | Android-приложение (Kotlin, Compose, libVLC) | [docs/ANDROID.md](docs/ANDROID.md), [android/DEBUG-BUILD.md](android/DEBUG-BUILD.md) |
| `desktop/` | Windows-приложение (Compose Desktop, mpv / VLC) | [docs/DESKTOP.md](docs/DESKTOP.md), [desktop/README.md](desktop/README.md) |
| `shared/` | Общий модуль (BurstProxy, Supervisor, TsClock) | [В общей архитектуре](docs/ARCHITECTURE.md#5-общий-модуль-shared) |

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
| `SITE_URL` | адрес сайта (`https://<домен>`): сборки берут из него домен, в коде его нет. Для сборки на своём ПК — `suslik.siteUrl=https://<домен>` в `~/.gradle/gradle.properties` |

Без ключа собирается debug-APK (для проверки компиляции; ставить как обновление нельзя).
