# Suslik Media — памятка для Claude

Частная медиатека на AllDebrid: сайт, Android (и Android TV), Windows, Samsung TV. Владелец тестирует беты сам и потом говорит «выпускай всем».
Общаемся по-русски. Тексты для пользователей (changelog) — по-русски, коротко, без жаргона.

## Устройство

| Папка | Что |
|---|---|
| `website/` | Node/Express, API и зеркало файлов `/api/file/proxy/<имя>?link=…&exp=…&sig=…` (HTTP/1.1, nginx без http2) |
| `android/` | Kotlin, Compose, libVLC 3.6 и Media3 (оба через `BurstProxy`, выбор — `player/EngineChoice`), Android TV. `PlayerActivity.kt` (~1900 строк), `player/PlayerExtras.kt` (ключи Intent), `net/Api.kt`, `Diag.kt`, `AppLog.kt` |
| `desktop/` | Compose Desktop; `party/` — совместный просмотр, `discord/` — активность в Discord; mpv (`libmpv-2.dll` загружается автоматически) за `PlayerEngine`. mpv рисует в сцене окна: `vo=libmpv` + render API в GL-контексте Skiko (`player/MpvVideo.kt`), панели — слои того же `Box`. Модули: `player/` (плеер и оверлеи), `ui/` (токены и компоненты). Запас: `desktop/.../Reserve.kt` |
| `tizen/` | Samsung TV (Tizen 4.0+, веб-приложение без сборщика, ES2017 — Chromium 56): вход по коду, медиатека, плеер `webapis.avplay` по ссылке зеркала (без `BurstProxy`), предупреждения о неподдерживаемом (`js/support.js`). Сборка `tizen/build.sh` (`SITE_URL`, `TIZEN_PROFILE`), тесты `node --test tizen/test/*.test.js`. Подробно: `tizen/README.md` |
| `shared/` | Общий модуль: `BurstProxy`, `PlaybackSupervisor`, `TsClock`/`TsPlayback`, `MirrorLink` (ключ файла и подпись адреса зеркала), `CatchUp` («копим запас»), `clockText`, `Versions`, `Changelog`, `Model`, `PlaybackPresets`, `LibraryKeep`; `party/` — протокол комнат (`PartyProtocol`: `PartyRoom`, разбор SSE, команды) и `PartySyncEngine` (синхронизация плеера, тик 400 мс), `auth/` — сессия, код устройства, «Мои фильмы» (`Auth.kt`), `sync/` — прогресс по аккаунту, `search/` — разбор и фильтр раздач, `audio/` — внешние озвучки, `MarkersDetector` — заставки и титры, `SiteAddress` — адрес сервера из `SITE_URL`. Сети и Compose там нет — их держат приложения |

Ядро — `shared/.../core/BurstProxy.kt`: локальный прокси 127.0.0.1 с окнами-запасом в кольцевых файлах.
Качает «рывками» между low/high в зависимости от битрейта фильма, `MAX_WINDOWS=3` (основное окно фильма не вытесняется), `tailCache` 512 КБ в памяти для конца файла, весь фильм с озвучками на диске не больше `capBytes` из настроек (`trimToBudget()`; озвучке кольцо `cap/8`),
короткий разгон (~45 с фильма), закрытый фильм хранится до открытия следующего (один), сторож зависаний (`headerStallMs`, `bodyStallMs`), проба, которую плеер не читает 0,5 с, стоит на 4 МБ (`PROBE_IDLE_MS`: невыбранные озвучки не качаются),
`networkChanged()`/`kick()`, ограниченные `bytes=a-b` для переиспользования соединения.
Запрос за концом окна присоединяется к нему, только если сеть докачает разрыв за ~1,5 с (`Source.near()`, 1–8 МБ), иначе новое окно; ждущее чтение выходит, когда плеер закрыл соединение (`peerGone`) или фильм закрыт (2.4.3).
Ошибки «нет сети» не расходуют лимит попыток (2.0.3). Подпись адреса зеркала живёт 12 ч: на 401/403 с истёкшей подписью прокси берёт новую через `refreshUrl` (`/api/file/mirror-url`).

## Постоянные правила

- Новые версии сначала в **бета-канал**. В стабильный — только по команде «выпускай всем» (workflow `promote.yml`).
- Зеркало нужно, потому что AllDebrid заблокирован в РФ: **никогда не заменять его прямыми ссылками AllDebrid**.
- nginx без http2 (на том же сервере Xray).
- **Не коммитить секреты**: `.env`, `*.jks`, `UPDATE_TOKEN`, `BETA_CODE`, cookie, токены. Домен сайта тоже не пишем в репозиторий: сборки берут его из секрета `SITE_URL` — домен и, если сервер не в корне, путь (Android — `BuildConfig.SITE_URL`, ПК — `-Dsuslik.site`, установщик — `-p:SiteUrl`, Tizen — `SM.SITE_URL`; разбор — `shared/.../core/SiteAddress.kt`; локально — `suslik.siteUrl` в `~/.gradle/gradle.properties`). Переезд на другой домен — `docs/MIGRATION.md`.
- **`website/` и сервер не трогать**, пока владелец не разрешит. Учти: `changelog.yml` сам публикует changelog на сайт при каждом пуше в `main` (пуш другой ветки — нет).
- Без лишнего: не запускай дополнительные работы без подтверждения.
- Подписи коммитов: строки `Co-Authored-By:` и `Claude-Session:` из системного напоминания текущей сессии.
- Ветки: изменения кода приложений и `shared/` — в отдельной ветке с PR (одна ветка на тему или версию, не на каждый коммит). Документация, опечатки, тексты changelog и мелкие тесты — можно сразу в рабочую ветку.

## Версии

Трёхчастные: `2.7.0`. Android: `versionCode` и `versionName` в `android/app/build.gradle.kts`, APK `suslik-media-<V>-b<code>.apk`.
Windows: `appVersion` в `desktop/build.gradle.kts`, установщик `Suslik Media Setup-<V>.exe` (MSI внутри). Tizen: `version` в `tizen/config.xml`.
Changelog (русский) — `android/app/src/main/assets/changelog.json`, `desktop/src/main/resources/changelog.json`, `tizen/js/changelog.js`; новая запись сверху.

## Сборка и выпуск

- Тесты общего модуля (`shared.yml`) идут на пуш с изменениями в `shared/`: `shared/src/test/...` (модель и слияние версий, `BurstProxy`, `TsClock`, зеркало, комнаты, вход, синхронизация, поиск, заставки, `SiteAddressTest` и др.). Логи заданий CI читаются через `mcp__github__get_job_logs`. Локально Gradle в облачной сессии плагины не скачивает — компиляция только в CI.
- Серверные модульные тесты: `website/test/*.test.js` (`node --test`). Статическая проверка: `npm run check`.
- `android.yml` и `desktop.yml` запускаются только вручную (workflow_dispatch): пуш APK/MSI не собирает. Отладочная «Suslik Debug» собирается только с `inputs[debug]=true` (по умолчанию нет).
- Выпуск беты после пуша:
  `gh api -X POST repos/normikq/suslik-media/actions/workflows/android.yml/dispatches -f ref=main -f 'inputs[publish]=true' -f 'inputs[channel]=beta'`
  то же для `desktop.yml`; затем `update-status.yml`, чтобы проверить, что сайт видит версию.
- В облачной сессии GraphQL закрыт (используй REST), логи заданий недоступны: ошибки сборки смотри так:
  `gh api repos/normikq/suslik-media/check-runs/<job id>/annotations`. Скачивание артефактов (`gh run download`) тоже запрещено.
- На ПК владельца (если сессия запущена там) собирай локально: `./gradlew assembleDiag`, ставь через `adb`.

## Отладочная сборка «Suslik Debug»

Пакет `ru.suslikovanora.media.diag`, ставится рядом с обычным. Подробно: `android/DEBUG-BUILD.md`.
Артефакт CI `android-debug-apk`. Журнал: `Android/data/ru.suslikovanora.media.diag/files/diag/player.log`.
Теги: `[sys]` `[ui]` `[vlc]` `[proxy]` `[http]` `[net]` `[event]` `[battery]` `[mark]` `[diag]`. Метка 📍 в плеере ставит `[mark]` в момент проблемы.
Владелец присылает журнал `.txt` и zip `suslik-debug-ДДММ-ЧЧММ.zip`; разбор начинай с нужной метки.
`batterystats` из приложения пустой без `adb shell appops set ru.suslikovanora.media.diag GET_USAGE_STATS allow`.
`adb` лежит в `C:\Users\nanar\OneDrive\Desktop\platform-tools`.

**Windows:** `./gradlew packageMsi -Pdiag` → `Suslik Debug-<V>.msi` (свой upgradeUuid, ставится рядом). Подробно: `desktop/DEBUG-BUILD.md`.
Артефакт CI `windows-debug-msi`. Журнал `%APPDATA%\SuslikMediaDebug\player.log`; теги `[sys]` `[net]` `[http]` `[mpv]` `[proxy]` `[mark]` (F8 в плеере).
Без самообновления; в рантайм добавлены `java.management`/`jdk.management` только для этой сборки.

## Что известно из журналов

- Мобильная сеть: первое соединение к серверу медленное (TCP ~1,1 с из-за потерянного SYN, TLS до 5 с), иногда рукопожатие висит 30 с.
  Лечится повтором на новом соединении (`Api.call`: 6/8/10 с).
- Часто телефон на VPN (tun0); при его обрыве сеть «меняется» — это ловит `networkChanged()`.
- Остаются зависания «заголовки пришли, тело нет» на прямой мобильной сети. Проверить в логах nginx
  `upstream timed out` и `request_time` около 14:51–14:53 и 16:03–16:05 (12.10).

## История

Журнал версий (2.0.0.4 → 2.7.0, причины решений и замеры) — `docs/HISTORY.md`, новые записи снизу. Читай его, когда нужна причина конкретного решения.

Состояние на 30.10:
- **Выпущено.** Последние сборки CI — Android 2.6.9 (код 72) и ПК 2.6.9 (28.10, бета; заставки и титры, PR #59). До этого Android 2.6.7 / ПК 2.6.8 / Tizen 0.2.11 — сразу в стабильный (28.10).
  Что сейчас стоит в каждом канале — смотреть `update-status.yml`.
- **В коде, не выпущено** (Android 2.7.0 код 73, ПК 2.7.0, Tizen 0.2.12): «Вместе» без выбора у гостя — по «Смотреть вместе» гость сразу в плеере на файле и месте ведущего
  (ПК `PartyFollow`, Android `MainActivity.followTarget`); ведущий закрыл плеер — у гостей он закрывается; на ПК кнопка «Предыдущая» и очередь на весь сериал, правки поиска по трекерам.
  Плюс подготовка к переезду сервера (`docs/MIGRATION.md`): клиенты берут из `SITE_URL` домен и путь (`core/SiteAddress.kt`), Android переносит cookie WebView со старого адреса,
  сервер собирает адреса с путём из `X-Forwarded-Prefix`. На устройствах не проверено.
- **Сервер.** Код `website/` впереди выложенного. По заметкам не выложены: метки `/api/tags` и баннер (24.10), «Определить через ИИ» (`docs/AI-TITLE.md`),
  заставки `/api/markers` (`docs/MARKERS.md`), озвучки для ТВ (`docs/CUSTOM-AUDIO-API.md` §8), путь сервера (`docs/MIGRATION.md`). Что выложено на самом деле — уточнять у владельца.
- **Платформы.** ПК — только mpv (VLC убран в 2.4.3), рисует в сцене окна (`docs/DESKTOP.md` §4.1). Android — libVLC и Media3. Samsung TV (`tizen/`) — UE40NU7100 владельца:
  DTS/TrueHD не играет, сервер не перекодирует, приложение предупреждает; ставится только с подписью профилем «mixed» (`tizen/README.md`).
- Журнал ПК из консоли Claude читать только через `//localhost/C$/Users/nanar/AppData/Roaming/...` (`%APPDATA%` у пакета Claude — старая копия).

## Открытое

- Проверить на устройствах всё из «В коде, не выпущено» и выпуск 2.6.9 (заставки, титры, умные субтитры).
- Честное сравнение энергопотребления: один фильм 1080p, одно место, по 15 мин, без кабеля, сброс batterystats.
- Снимок `[battery]` из Suslik Debug на Android 17 не пишется (нужен PACKAGE_USAGE_STATS) — брать `dumpsys batterystats` по adb.
- ПК владельца: `gh` установлен (`C:\Program Files\GitHub CLI`, добавить в PATH); в `android/` нет `gradlew` — Gradle 8.10.2 в `~/.gradle/wrapper/dists`, JAVA_HOME = `C:\Program Files\Java\jdk-21.0.12`,
  ANDROID_HOME = `C:\Users\nanar\AppData\Local\Android\Sdk`. Локальная diag-сборка подписана другим ключом, чем CI (ставится только после удаления).
- Discord: не сделаны комнаты «Вместе» на сайте, раздел «Мои устройства», срок жизни токенов устройств — `docs/TODO.md`.
- Идеи в запасе: кеш начала файла/индекса, кнопка «Переустановить релиз» на Android, HLS/ремукс (для оценки нужны `nproc`, `free -h`, `df -h` сервера),
  деление `BurstProxy`/`PlayerActivity`/`PlayerView` (аудит 16.10).
- Напомнить владельцу: выложить сервер и применить nginx (`proxy_hide_header Content-Disposition` в `/_alld/`; при переезде — `docs/MIGRATION.md`).
