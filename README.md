# Suslik Media

Частная медиатека поверх AllDebrid: сервер с зеркалом файлов и сайтом, приложения для Android (и Android TV), Windows и Samsung TV.
Как всё устроено — [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

| Папка | Что | Документация |
|---|---|---|
| `website/` | Сервер, API, зеркало файлов и сайт (Node/Express) | [docs/WEBSITE.md](docs/WEBSITE.md), [website/README.md](website/README.md), выкладка — [docs/DEPLOY.md](docs/DEPLOY.md) |
| `android/` | Android и Android TV (Kotlin, Compose, libVLC и Media3); `android/core/` — ядро без Android API (запас `BurstProxy`, сторож, медиатека, вход, комнаты, поиск) с тестами | [docs/ANDROID.md](docs/ANDROID.md), [android/README.md](android/README.md), [android/DEBUG-BUILD.md](android/DEBUG-BUILD.md) |
| `desktop-qt/` | Windows (C++20, Qt 6/QML, libmpv), установщик | [docs/DESKTOP.md](docs/DESKTOP.md), [desktop-qt/README.md](desktop-qt/README.md) |
| `tizen/` | Samsung Smart TV (Tizen web app) | [tizen/README.md](tizen/README.md) |
| `scripts/` | Выкладка сайта по SSH | [docs/DEPLOY.md](docs/DEPLOY.md) |
| `docs/` | Документация | история версий — [HISTORY.md](docs/HISTORY.md), отложенное — [TODO.md](docs/TODO.md), переезд сервера — [MIGRATION.md](docs/MIGRATION.md) |

`install-android-debug.bat` — собрать и поставить отладочную «Suslik Debug» на телефон по ADB (Windows, `android/gradlew.bat`).

## Локализация (i18n)

Тексты интерфейса лежат в JSON-словарях:
- **Windows**: `desktop-qt/resources/strings/ru.json`, функция `tx(key, ...)` (`desktop-qt/core/.../Strings`, в QML — `Ui.tx`).
- **Android**: `android/app/src/main/assets/strings/ru.json`, функция `tr(key, ...)` в `Strings.kt`.
- **Samsung Smart TV (Tizen)**: `tizen/locales/ru.json`, модуль `tizen/js/i18n.js`, атрибуты `data-i18n`.
- **Сайт**: `website/public/locales/ru.json`, модуль `website/public/i18n.js` (`window.tr`, атрибуты `data-i18n`).

Тексты — для людей: понятные, без технического жаргона и упоминаний внешних сервисов загрузки.

## Сборка и выпуск (GitHub Actions)

- **Android APK** (`android.yml`) и **Windows** (`desktop-qt.yml`: программа, MSI и установщик `Suslik Media Setup-<V>.exe`) собираются
  вручную: Actions → workflow → Run workflow. Галочка «Выложить на сайт» (`publish`) отправляет сборку в `updates/` сайта
  (канал `beta` или `stable`); «выпустить всем» бету — `promote.yml`, проверить, что видит сайт, — `update-status.yml`.
- Тесты: `android-core.yml` (пуш в `android/core/`), `desktop-qt.yml` (пуш в `desktop-qt/`: сборка и тесты ядра на Linux),
  `website.yml` (пуш в `website/`), Tizen — `node --test tizen/test/*.test.js` локально.
- `changelog.yml` при пуше в `main` с изменённым «Что нового» (`android/app/src/main/assets/changelog.json`,
  `desktop-qt/resources/changelog.json`) сразу публикует его на сайте.
- Сайт на сервер выкладывается только вручную: `website-deploy.yml` или `scripts/deploy-website.sh` ([docs/DEPLOY.md](docs/DEPLOY.md)).

Секреты репозитория (Settings → Secrets and variables → Actions):

| Секрет | Для чего |
|---|---|
| `ANDROID_KEYSTORE_B64` | release-ключ в base64 (`base64 -w0 release.jks`) |
| `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | пароли/алиас ключа |
| `UPDATE_TOKEN` | тот же, что `UPDATE_TOKEN` в `.env` сайта |
| `SITE_URL` | адрес сервера (`https://<домен>` или `https://<домен>/<путь>`, без `/` в конце): сборки берут из него домен и путь, в коде их нет. Переезд — `docs/MIGRATION.md`. Для сборки на своём ПК: Android — `suslik.siteUrl=https://<домен>` в `~/.gradle/gradle.properties`, Windows — `SITE_URL` в окружении или `-DSM_SITE_URL` |
| `DEPLOY_*` | выкладка сайта по SSH (`docs/DEPLOY.md`) |

Без ключа собирается debug-APK (для проверки компиляции; ставить как обновление нельзя).
