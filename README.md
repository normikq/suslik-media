# Suslik Media

| Папка | Что | Документация |
|---|---|---|
| `docs/` | Архитектура и документация проекта | [Общая архитектура](docs/ARCHITECTURE.md) |
| `website/` | Сайт и API (Node/Express) | [docs/WEBSITE.md](docs/WEBSITE.md), [website/README.md](website/README.md) |
| `android/` | Android-приложение и Android TV (Kotlin, Compose, libVLC и Media3) | [docs/ANDROID.md](docs/ANDROID.md), [android/DEBUG-BUILD.md](android/DEBUG-BUILD.md) |
| `desktop/` | Windows-приложение (Compose Desktop, mpv) | [docs/DESKTOP.md](docs/DESKTOP.md), [desktop/README.md](desktop/README.md) |
| `tizen/` | Клиент для Samsung Smart TV (Tizen web app) | [tizen/README.md](tizen/README.md) |

История версий — [docs/HISTORY.md](docs/HISTORY.md), отложенное — [docs/TODO.md](docs/TODO.md), переезд сервера — [docs/MIGRATION.md](docs/MIGRATION.md).
| `shared/` | Общий модуль Android и ПК (запас `BurstProxy`, сторож, модель медиатеки, поиск, комнаты, вход) | [В общей архитектуре](docs/ARCHITECTURE.md#5-общий-модуль-shared) |

## Локализация (i18n)

Все клиентские приложения и веб-интерфейс используют централизованную систему локализации на базе JSON-словарей:
- **Desktop (Windows)**: ресурсы `desktop/src/main/resources/strings/ru.json`, функция `tr(key, ...)` в `Strings.kt`.
- **Android**: ассеты `android/app/src/main/assets/strings/ru.json`, функция `tr(key, ...)` в `Strings.kt`.
- **Samsung Smart TV (Tizen)**: словарь `tizen/locales/ru.json`, клиентский модуль `tizen/js/i18n.js`, декларативные атрибуты `data-i18n`.
- **Веб-сайт**: словарь `website/public/locales/ru.json`, клиентский модуль `website/public/i18n.js` (`window.tr`, атрибуты `data-i18n`).

Все тексты ориентированы на пользователя (понятные и дружелюбные описания без технического жаргона и упоминаний внешних сервисов загрузки).

## Сборка (GitHub Actions)

- **Android APK** (`android.yml`) и **Windows** (`desktop.yml`: MSI и установщик `Suslik Media Setup-<V>.exe`) собираются
  только вручную: Actions → workflow → Run workflow. Галочка «Выложить на сайт» отправляет сборку в `updates/` сайта
  (канал `beta` или `stable`); «выпустить всем» бету — `promote.yml`, проверить, что видит сайт, — `update-status.yml`.
- Тесты: `shared.yml` (пуш в `shared/`), `website.yml` (пуш в `website/`), Tizen — `node --test tizen/test/*.test.js` локально.
- `changelog.yml` при пуше в `main` с изменённым `changelog.json` сразу публикует «Что нового» на сайте.

Секреты репозитория (Settings → Secrets and variables → Actions):

| Секрет | Для чего |
|---|---|
| `ANDROID_KEYSTORE_B64` | release-ключ в base64 (`base64 -w0 release.jks`) |
| `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | пароли/алиас ключа |
| `UPDATE_TOKEN` | тот же, что `UPDATE_TOKEN` в `.env` сайта |
| `SITE_URL` | адрес сервера (`https://<домен>` или `https://<домен>/<путь>`, без `/` в конце): сборки берут из него домен и путь, в коде их нет. Переезд — `docs/MIGRATION.md`. Для сборки на своём ПК — `suslik.siteUrl=https://<домен>` в `~/.gradle/gradle.properties` |

Без ключа собирается debug-APK (для проверки компиляции; ставить как обновление нельзя).
