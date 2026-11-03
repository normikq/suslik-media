# Suslik Media — памятка для Claude

Частная медиатека на AllDebrid: сайт, Android (и Android TV), Windows, Samsung TV. Владелец тестирует беты сам и потом говорит «выпускай всем».
Общаемся по-русски. Тексты для пользователей (changelog) — по-русски, коротко, без жаргона.

## Устройство

| Папка | Что |
|---|---|
| `website/` | Node/Express, API и зеркало файлов `/api/file/proxy/<имя>?link=…&exp=…&sig=…` (HTTP/1.1, nginx без http2) |
| `android/` | Kotlin, Compose, libVLC 3.6 и Media3 (оба через `BurstProxy`, выбор — `player/EngineChoice`), Android TV. `PlayerActivity.kt` (~1900 строк), `player/PlayerExtras.kt` (ключи Intent), `net/Api.kt`, `Diag.kt`, `AppLog.kt`. Gradle wrapper — `android/gradlew` (8.10.2) |
| `android/core/` | Ядро Android без Android API (бывший общий `shared/`, до 03.11 его делила Kotlin-версия ПК): `BurstProxy`, `PlaybackSupervisor`, `TsClock`/`TsPlayback`, `MirrorLink` (ключ файла и подпись адреса зеркала), `CatchUp` («копим запас»), `clockText`, `Versions`, `Changelog`, `Model`, `PlaybackPresets`, `LibraryKeep`; `party/` — протокол комнат (`PartyProtocol`: `PartyRoom`, разбор SSE, команды) и `PartySyncEngine` (тик 400 мс), `auth/` — сессия, код устройства, «Мои фильмы» (`Auth.kt`), `sync/` — прогресс по аккаунту, `search/` — разбор и фильтр раздач, `audio/` — внешние озвучки, `MarkersDetector` — заставки и титры, `SiteAddress` — адрес сервера из `SITE_URL`. Отдельная сборка Gradle (`includeBuild("core")`, `ru.suslikovanora.media:core`), пакеты `ru.suslikovanora.media.core.*`, тесты на JVM |
| `desktop-qt/` | ПК-версия на C++20/Qt 6 (QML, libmpv), с 3.0.0 заменила Kotlin-версию (Compose Desktop, `desktop/` удалена 03.11). `core/` — C++-перенос `android/core/` и сетевой части ПК с тестами Qt Test, `app/` — интерфейс (`qml/`, `src/`, `src/player/` — mpv рисует в FBO Qt Quick, `src/party/`, `src/trackers/`), `resources/` — шрифты, значки, строки, `changelog.json`, `libmpv.properties`, `installer/` — MSI (WiX 3) и мастер установки на WPF. Подробно — `docs/DESKTOP.md`, CI `desktop-qt.yml` |
| `tizen/` | Samsung TV (Tizen 4.0+, веб-приложение без сборщика, ES2017 — Chromium 56): вход по коду, медиатека, плеер `webapis.avplay` по ссылке зеркала (без `BurstProxy`), предупреждения о неподдерживаемом (`js/support.js`). Сборка `tizen/build.sh` (`SITE_URL`, `TIZEN_PROFILE`), тесты `node --test tizen/test/*.test.js`. Подробно: `tizen/README.md` |
| `scripts/`, `install-android-debug.bat` | выкладка сайта по SSH; сборка и установка «Suslik Debug» на телефон по ADB |

Правила медиатеки, поиска и комнат есть в трёх копиях: Kotlin (`android/core/`), C++ (`desktop-qt/core/`), JS (`tizen/js/`, сайт). Меняешь правило — меняй везде, паритет держат одинаковые тесты.

Ядро — `BurstProxy` (`android/core/.../core/BurstProxy.kt`, C++-перенос — `desktop-qt/core/src/BurstProxy.cpp`): локальный прокси 127.0.0.1 с окнами-запасом в кольцевых файлах.
Качает «рывками» между low/high в зависимости от битрейта фильма, `MAX_WINDOWS=3` (основное окно фильма не вытесняется), `tailCache` 512 КБ в памяти для конца файла, весь фильм с озвучками на диске не больше `capBytes` из настроек (`trimToBudget()`; озвучке кольцо `cap/8`),
короткий разгон (~45 с фильма), закрытый фильм хранится до открытия следующего (один), сторож зависаний (`headerStallMs`, `bodyStallMs`), проба, которую плеер не читает 0,5 с, стоит на 4 МБ (`PROBE_IDLE_MS`: невыбранные озвучки не качаются),
`networkChanged()`/`kick()`, ограниченные `bytes=a-b` для переиспользования соединения.
Запрос за концом окна присоединяется к нему, только если сеть докачает разрыв за ~1,5 с (`Source.near()`, 1–8 МБ), иначе новое окно; ждущее чтение выходит, когда плеер закрыл соединение (`peerGone`) или фильм закрыт (2.4.3).
Ошибки «нет сети» не расходуют лимит попыток (2.0.3). Подпись адреса зеркала живёт 12 ч: на 401/403 с истёкшей подписью прокси берёт новую через `refreshUrl` (`/api/file/mirror-url`).

## Постоянные правила

- Новые версии сначала в **бета-канал**. В стабильный — только по команде «выпускай всем» (workflow `promote.yml`).
- Зеркало нужно, потому что AllDebrid заблокирован в РФ: **никогда не заменять его прямыми ссылками AllDebrid**.
- nginx без http2 (на том же сервере Xray).
- **Не коммитить секреты**: `.env`, `*.jks`, `UPDATE_TOKEN`, `BETA_CODE`, cookie, токены. Домен сайта тоже не пишем в репозиторий: сборки берут его из секрета `SITE_URL` — домен и, если сервер не в корне, путь (Android — `BuildConfig.SITE_URL`, ПК — `SM_SITE_URL` при сборке CMake, установщик — `-p:SiteUrl`, Tizen — `SM.SITE_URL`; разбор — `SiteAddress` в ядрах; локально — `suslik.siteUrl` в `~/.gradle/gradle.properties` для Android, `SITE_URL`/`-DSM_SITE_URL` для ПК). Переезд на другой домен — `docs/MIGRATION.md`.
- **`website/` и сервер не трогать**, пока владелец не разрешит. Учти: `changelog.yml` сам публикует changelog на сайт при каждом пуше в `main` (пуш другой ветки — нет).
- Без лишнего: не запускай дополнительные работы без подтверждения.
- Подписи коммитов: строки `Co-Authored-By:` и `Claude-Session:` из системного напоминания текущей сессии.
- Ветки: изменения кода приложений и ядер — в отдельной ветке с PR (одна ветка на тему или версию, не на каждый коммит). Документация, опечатки, тексты changelog и мелкие тесты — можно сразу в рабочую ветку.

## Версии

Трёхчастные: `2.7.0`. Android: `versionCode` и `versionName` в `android/app/build.gradle.kts`, APK `suslik-media-<V>-b<code>.apk`.
Windows: `project(SuslikMediaQt VERSION …)` в `desktop-qt/CMakeLists.txt`, установщик `Suslik Media Setup-<V>.exe` (MSI внутри). Tizen: `version` в `tizen/config.xml`.
Changelog (русский) — `android/app/src/main/assets/changelog.json`, `desktop-qt/resources/changelog.json`, `tizen/js/changelog.js`; новая запись сверху.

## Сборка и выпуск

- Тесты ядра Android (`android-core.yml`) идут на пуш с изменениями в `android/core/`: `android/core/src/test/...` (модель и слияние версий, `BurstProxy`, `TsClock`, зеркало, комнаты, вход, синхронизация, поиск, заставки, `SiteAddressTest` и др.).
  Тесты ядра ПК (Qt Test) — `desktop-qt.yml` на пуш в `desktop-qt/` (сборка и `ctest` на Linux). Логи заданий CI читаются через `mcp__github__get_job_logs`. Локально Gradle в облачной сессии плагины не скачивает — компиляция Kotlin только в CI.
- Выкладка сайта — только по команде владельца: workflow `website-deploy.yml` (вручную) или `scripts/deploy-website.sh` по SSH-ключу; секреты `DEPLOY_*`, данные и `.env` на сервере не трогаются, при неответе — откат. Подробно `docs/DEPLOY.md`.
- Серверные модульные тесты: `website/test/*.test.js` (`node --test`). Статическая проверка: `npm run check`.
- `android.yml` и сборка Windows в `desktop-qt.yml` запускаются только вручную (workflow_dispatch): пуш APK/установщик не собирает. Отладочные «Suslik Debug» / «Suslik Debug Qt» собираются только с `inputs[debug]=true` (по умолчанию нет).
- Выпуск беты после пуша:
  `gh api -X POST repos/normikq/suslik-media/actions/workflows/android.yml/dispatches -f ref=main -f 'inputs[publish]=true' -f 'inputs[channel]=beta'`
  то же для `desktop-qt.yml` (на сайт идёт только `Suslik Media Setup-<V>.exe`, платформа `desktop`); затем `update-status.yml`, чтобы проверить, что сайт видит версию.
- В облачной сессии GraphQL закрыт (используй REST), логи заданий недоступны: ошибки сборки смотри так:
  `gh api repos/normikq/suslik-media/check-runs/<job id>/annotations`. Скачивание артефактов (`gh run download`) тоже запрещено.
- На ПК владельца (если сессия запущена там) собирай локально: `android\gradlew.bat -p android assembleDiag` (или `install-android-debug.bat`), ставь через `adb`.

## Отладочная сборка «Suslik Debug»

Пакет `ru.suslikovanora.media.diag`, ставится рядом с обычным. Подробно: `android/DEBUG-BUILD.md`.
Артефакт CI `android-debug-apk`. Журнал: `Android/data/ru.suslikovanora.media.diag/files/diag/player.log`.
Теги: `[sys]` `[ui]` `[vlc]` `[proxy]` `[http]` `[net]` `[event]` `[battery]` `[mark]` `[diag]`. Метка 📍 в плеере ставит `[mark]` в момент проблемы.
Владелец присылает журнал `.txt` и zip `suslik-debug-ДДММ-ЧЧММ.zip`; разбор начинай с нужной метки.
`batterystats` из приложения пустой без `adb shell appops set ru.suslikovanora.media.diag GET_USAGE_STATS allow`.
`adb` лежит в `C:\Users\nanar\OneDrive\Desktop\platform-tools`.

**Windows:** «Suslik Debug Qt» — CMake `-DSM_DIAG=ON`, в CI `desktop-qt.yml` с `debug=true` → артефакт `suslik-media-qt-debug-msi` (`Suslik Debug Qt-<V>.msi`, свой UpgradeCode, ставится рядом).
Журнал `%APPDATA%\SuslikMediaQtDebug\player.log` (обычная — `%APPDATA%\SuslikMediaQt\player.log`); теги `[mpv]` `[proxy]` `[party]` `[mark]` (F8 в плеере).
Без самообновления. Подробно: `docs/DESKTOP.md` §9.

## Что известно из журналов

- Мобильная сеть: первое соединение к серверу медленное (TCP ~1,1 с из-за потерянного SYN, TLS до 5 с), иногда рукопожатие висит 30 с.
  Лечится повтором на новом соединении (`Api.call`: 6/8/10 с).
- Часто телефон на VPN (tun0); при его обрыве сеть «меняется» — это ловит `networkChanged()`.
- Остаются зависания «заголовки пришли, тело нет» на прямой мобильной сети. Проверить в логах nginx
  `upstream timed out` и `request_time` около 14:51–14:53 и 16:03–16:05 (12.10).

## История

Журнал версий (2.0.0.4 → 3.1.3, причины решений и замеры) — `docs/HISTORY.md`, новые записи снизу. Читай его, когда нужна причина конкретного решения.

Состояние на 04.11:
- **ПК 3.1.3, Android 2.7.4 (код 77), Tizen 0.2.13** (04.11, не выпущено): вытеснение старых устройств в комнатах «Вместе» (PR #89, #90) при отключении питания или смене устройства, защита от гонки, передача прав ведущего новому устройству.
- **03.11 — реструктуризация репозитория:** Kotlin-версия ПК `desktop/` удалена (ПК — только `desktop-qt/`), `shared/` → `android/core/`, `shared.yml` → `android-core.yml`, ресурсы ПК — `desktop-qt/resources/`. Версии не менялись (`docs/HISTORY.md`, последняя запись).
- **Выпущено.** Последние сборки CI — Android 2.6.9 (код 72) и ПК 2.6.9 (28.10, бета, ещё Kotlin; заставки и титры, PR #59). До этого Android 2.6.7 / ПК 2.6.8 / Tizen 0.2.11 — сразу в стабильный (28.10).
  Что сейчас стоит в каждом канале — смотреть `update-status.yml`.
- **В коде, не выпущено** (Android 2.7.0 код 73, Tizen 0.2.12; на ПК то же вошло в Qt-версию 3.x): «Вместе» без выбора у гостя — по «Смотреть вместе» гость сразу в плеере на файле и месте ведущего
  (ПК `qml/PartyFollow.qml`, Android `MainActivity.followTarget`); ведущий закрыл плеер — у гостей он закрывается; на ПК кнопка «Предыдущая» и очередь на весь сериал, правки поиска по трекерам.
  Плюс подготовка к переезду сервера (`docs/MIGRATION.md`): клиенты берут из `SITE_URL` домен и путь (`SiteAddress`), Android переносит cookie WebView со старого адреса,
  сервер собирает адреса с путём из `X-Forwarded-Prefix`. На устройствах не проверено.
- **PR #72 — ПК 3.1.0 и Android 2.7.1 (код 74)** (ветка `claude/project-thread-thor8q`, 01.11; не выпущено). ПК: «Подбор по настроению» (PR #73: Gemini + TMDB,
  `docs/AI-DISCOVERY.md`), ссылки `suslik://room/<код>` из Discord Activity (PR #74), падение при удалении сериала из окна запуска (`PlayModel`);
  память без `EmptyWorkingSet` на действиях — кэш постеров `PosterCache` (LRU 48 МБ) в `ImageProvider`, выгрузка только при сворачивании > 30 с;
  сглаженные шрифты (`PreferNoHinting`), мини-плеер тянется за любой край, режим 21:9 (C), буфер mpv 48/16 МБ, cookie RuTracker не пишутся в журнал.
  Android: вход в комнату по `suslik://room/<код>`. Сборка ПК в CI проходит; на устройствах, с настоящим Gemini и в Discord не проверено.
- **ПК 3.1.1 и Android 2.7.2 (код 75)** (02.11, не выпущено): правки «Вместе» по аудиту рассинхрона (PR #77: скорость комнаты, монотонные часы с пересчётом раз в минуту,
  постоянный `client_id`, серия с места комнаты, на Android — другой сезон ведущего, на ПК — гость не включает свой фильм) и мини-плеер гостя при закрытии фильма ведущим (PR #79).
  Discord Activity (PR #75, #76, #80: статус, «Позвать в чат», «Итог сейчас», рулетка, категории, карточка, подбор ИИ) — только сервер, в changelog приложений не входит.
- **ПК 3.1.2** (PR #83, 03.11, не выпущено): интерфейс по аудиту — свой заголовок окна (`TitleBar.qml`, «свои поля» Qt на Windows), меню плитки под курсором
  и в списке, меню правки в полях, прокрутка окна запуска, тосты с переносом, правки плеера («Вместе», подсказки, громкость). Windows-часть заголовка на устройстве не проверена.
- **Сервер.** Код `website/` впереди выложенного. По заметкам не выложены: метки `/api/tags` и баннер (24.10), «Определить через ИИ» (`docs/AI-TITLE.md`),
  заставки `/api/markers` (`docs/MARKERS.md`), озвучки для ТВ (`docs/CUSTOM-AUDIO-API.md` §8), путь сервера (`docs/MIGRATION.md`), подбор по настроению `/api/search/ai-discovery` (`docs/AI-DISCOVERY.md`). Что выложено на самом деле — уточнять у владельца.
- **Платформы.** ПК — C++/Qt и только mpv, рисует в сцене QML (`docs/DESKTOP.md` §5). Android — libVLC и Media3. Samsung TV (`tizen/`) — UE40NU7100 владельца:
  DTS/TrueHD не играет, сервер не перекодирует, приложение предупреждает; ставится только с подписью профилем «mixed» (`tizen/README.md`).
- Журнал ПК из консоли Claude читать только через `//localhost/C$/Users/nanar/AppData/Roaming/...` (`%APPDATA%` у пакета Claude — старая копия).

## Открытое

- Проверить на устройствах всё из «В коде, не выпущено» и выпуск 2.6.9 (заставки, титры, умные субтитры).
- Честное сравнение энергопотребления: один фильм 1080p, одно место, по 15 мин, без кабеля, сброс batterystats.
- Снимок `[battery]` из Suslik Debug на Android 17 не пишется (нужен PACKAGE_USAGE_STATS) — брать `dumpsys batterystats` по adb.
- ПК владельца: `gh` установлен (`C:\Program Files\GitHub CLI`, добавить в PATH); Gradle — `android/gradlew` (8.10.2, кэш в `~/.gradle/wrapper/dists`), JAVA_HOME = `C:\Program Files\Java\jdk-21.0.12`,
  ANDROID_HOME = `C:\Users\nanar\AppData\Local\Android\Sdk`. Локальная diag-сборка подписана другим ключом, чем CI (ставится только после удаления).
- Discord Activity «Вместе» в голосовом канале (`docs/DISCORD-ACTIVITY.md`): сервер, страница, ссылки `suslik://room/<код>` на ПК и Android — в коде,
  не выложено и в Discord не проверено; настройка в Developer Portal — руками владельца. Развитие по ТЗ (PR после #75): статус «Смотрит …» (SET_ACTIVITY),
  «Позвать в чат» ботом, «Итог сейчас», рулетка, категории и сортировка, карточка фильма, «Новое в медиатеке», «Посоветовать фильм» (ИИ).
- Discord: не сделаны комнаты «Вместе» на сайте, раздел «Мои устройства», срок жизни токенов устройств — `docs/TODO.md`.
- Идеи в запасе: кеш начала файла/индекса, кнопка «Переустановить релиз» на Android, HLS/ремукс (для оценки нужны `nproc`, `free -h`, `df -h` сервера),
  деление `BurstProxy`/`PlayerActivity` (аудит 16.10).
- Напомнить владельцу: выложить сервер и применить nginx (`proxy_hide_header Content-Disposition` в `/_alld/`; при переезде — `docs/MIGRATION.md`).
