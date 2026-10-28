# Suslik Media — памятка для Claude

Частная медиатека на AllDebrid: сайт, Android, Windows. Владелец тестирует беты сам и потом говорит «выпускай всем».
Общаемся по-русски. Тексты для пользователей (changelog) — по-русски, коротко, без жаргона.

## Устройство

| Папка | Что |
|---|---|
| `website/` | Node/Express, API и зеркало файлов `/api/file/proxy/<имя>?link=…&exp=…&sig=…` (HTTP/1.1, nginx без http2) |
| `android/` | Kotlin, Compose, libVLC 3.6 и Media3 (оба через `BurstProxy`, выбор — `player/EngineChoice`), Android TV. `PlayerActivity.kt` (~1900 строк), `player/PlayerExtras.kt` (ключи Intent), `net/Api.kt`, `Diag.kt`, `AppLog.kt` |
| `desktop/` | Compose Desktop; `party/` — совместный просмотр, `discord/` — активность в Discord; mpv (`libmpv-2.dll` загружается автоматически) за `PlayerEngine`. mpv рисует в сцене окна: `vo=libmpv` + render API в GL-контексте Skiko (`player/MpvVideo.kt`), панели — слои того же `Box`. Модули: `player/` (плеер и оверлеи), `ui/` (токены и компоненты). Запас: `desktop/.../Reserve.kt` |
| `tizen/` | Samsung TV (Tizen 4.0+, веб-приложение без сборщика, ES2017 — Chromium 56): вход по коду, медиатека, плеер `webapis.avplay` по ссылке зеркала (без `BurstProxy`), предупреждения о неподдерживаемом (`js/support.js`). Сборка `tizen/build.sh` (`SITE_URL`, `TIZEN_PROFILE`), тесты `node --test tizen/test/*.test.js`. Подробно: `tizen/README.md` |
| `shared/` | Общий модуль: `BurstProxy`, `PlaybackSupervisor`, `TsClock`/`TsPlayback`, `MirrorLink` (ключ файла и подпись адреса зеркала), `CatchUp` («копим запас»), `clockText`, `Versions`, `Changelog`, `Model`, `PlaybackPresets`, `LibraryKeep`; `party/` — протокол комнат (`PartyProtocol`: `PartyRoom`, разбор SSE, команды) и `PartySyncEngine` (синхронизация плеера, тик 400 мс), `auth/` — сессия, код устройства, «Мои фильмы» (`Auth.kt`). Сети и Compose там нет — их держат приложения |

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
- **Не коммитить секреты**: `.env`, `*.jks`, `UPDATE_TOKEN`, `BETA_CODE`, cookie, токены. Домен сайта тоже не пишем в репозиторий: сборки берут его из секрета `SITE_URL` (Android — `BuildConfig.SITE_HOST`, ПК — `-Dsuslik.host`, установщик — `-p:SiteHost`; локально — `suslik.siteUrl` в `~/.gradle/gradle.properties`).
- **`website/` и сервер не трогать**, пока владелец не разрешит. Учти: `changelog.yml` сам публикует changelog на сайт при каждом пуше в `main` (пуш другой ветки — нет).
- Без лишнего: не запускай дополнительные работы без подтверждения.
- Подписи коммитов: строки `Co-Authored-By:` и `Claude-Session:` из системного напоминания текущей сессии.
- Ветки: изменения кода приложений и `shared/` — в отдельной ветке с PR (одна ветка на тему или версию, не на каждый коммит). Документация, опечатки, тексты changelog и мелкие тесты — можно сразу в рабочую ветку.

## Версии

Теперь трёхчастные: `2.0.x`. Android: `versionCode` в `android/app/build.gradle.kts`, APK `suslik-media-<V>-b<code>.apk`.
Windows: `appVersion` в `desktop/build.gradle.kts`, MSI `Suslik Media-<V>.msi` и установщик `Suslik Media Setup-<V>.exe` (первую установку при смене схемы версий делают вручную).
Changelog (русский) — `android/app/src/main/assets/changelog.json` и `desktop/src/main/resources/changelog.json`, новая запись сверху.

## Сборка и выпуск

- Тесты общего модуля (`shared.yml`) идут на пуш с изменениями в `shared/`: `shared/src/test/...` (`ModelTest`, `BurstProxyTest`, `TsClockTest`, `CoreTest`, `MirrorLinkTest`, `PartyTest`, `PartyProtocolTest`, `PartySyncEngineTest`, `AuthTest`). Логи заданий CI читаются через `mcp__github__get_job_logs`. Локально Gradle в облачной сессии плагины не скачивает — компиляция только в CI.
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
Артефакт CI `windows-debug-msi`. Журнал `%APPDATA%\SuslikMediaDebug\player.log`; теги `[sys]` `[net]` `[http]` `[vlc]` `[proxy]` `[mark]` (F8 в плеере).
Без самообновления; в рантайм добавлены `java.management`/`jdk.management` только для этой сборки.

## Что известно из журналов

- Мобильная сеть: первое соединение к серверу медленное (TCP ~1,1 с из-за потерянного SYN, TLS до 5 с), иногда рукопожатие висит 30 с.
  Лечится повтором на новом соединении (`Api.call`: 6/8/10 с).
- Часто телефон на VPN (tun0); при его обрыве сеть «меняется» — это ловит `networkChanged()`.
- Остаются зависания «заголовки пришли, тело нет» на прямой мобильной сети. Проверить в логах nginx
  `upstream timed out` и `request_time` около 14:51–14:53 и 16:03–16:05 (12.10).

## История

Полный журнал версий (2.0.0.4 → 2.4.3, причины и замеры) — `docs/HISTORY.md`. Читай его, когда нужна причина конкретного решения.
Коротко о текущем состоянии:
- Стабильные: 2.0.4, 2.0.6, 2.0.8, 2.0.9, 2.1.x; ПК 2.4.0 (mpv в сцене окна); ПК 2.5.2 (Discord, совместный просмотр); ПК 2.2 — mpv (`libmpv`) основной движок, VLC (`vlcj`) запасной, оба за `PlayerEngine`.
- Бета: Windows 2.3.5 (SMTC, единый установщик `desktop/setup`, libmpv качается отдельно), Android 2.3.5/2.3.6 (плеер в стиле ПК: `PlayerOverlay.kt`; Media3 не переоткрывается при возврате сети и включении экрана).
- Бета: **Android 2.4.1** (код 59, 18.10): от 2.3.6 отличается только `BurstProxy` — проба, которую плеер не читает 0,5 с, стоит на 4 МБ (`PROBE_IDLE_MS`); номер выровнен с ПК. Проверить на телефоне паузу сразу после открытия по мобильной сети (`player idle`, без «Буферизации» после паузы).
- Бета: **ПК 2.4.1** (ветка `fix-part-1`, 18.10): память mpv (кэш демуксера 96/24 МиБ, постеры 40), панели (0,5 с после ухода курсора, без возврата над неподвижным курсором), `MpvEngine.playingSent`, `AppExit.armWatchdog()` при закрытии окна; закрытие окна с видео ждёт кадр без видео (иначе Skia зависает в цикле `glGetError` без GL-контекста). Там же (журнал 18.10): озвучки/субтитры не теряются при быстром «Смотреть», грузятся параллельно (`player/SideFiles.kt`), выбранные — первыми, выбор по раздаче; «Предыдущая» в SMTC — сразу прошлая серия (весь сезон в плейлисте); `Store` пишет с `fsync` и копией `.bak`. Открыто: такое же зависание при закрытии плеера. Подробно: `docs/HISTORY.md`, `docs/DESKTOP.md` §4.1.
- Готовится: **ПК и Android 2.4.3** (ветка `vlc-remove-desktop`, 18.10, номер 2.4.2 не выпускался): ПК — полный отказ от VLC, только mpv; оба — `BurstProxy`: короткий прыжок вперёд на медленной сети открывает своё окно (`Source.near()`), ждущее чтение замечает ушедший плеер (`peerGone`). Android 2.4.3 — код 60. Не проверено на устройствах.
- **ПК 2.5.2 — стабильная** (20.10, сразу всем по команде владельца; PR #27, ветка `feature/discord-integration`; 2.5.0/2.5.1 не выпускались): вход через Discord (OAuth2, роли — ботом, ПК/ТВ — код с экрана
  и токен устройства), совместный просмотр (`desktop/…/party/`, сервер `routes/rooms.js` на SSE: ведущий управляет, комната ждёт тех,
  у кого грузится, у участника полоса «● Вместе», своя пауза и подсказки о действиях ведущего), активность «Смотрит» в Discord (ID приложения — с сервера).
  «Мои фильмы» вместо «Избранного» (`services/shelf.js`, `/api/shelf`, флаг `mine`); готовые фильмы удаляет только админ; автоочистка `services/cleanup.js` (`AUTO_CLEAN=false` выключает).
  Логика комнат и входа — в `shared/` (`party/PartyProtocol`, `PartySyncEngine`, `auth/Auth.kt`), ПК и Android на ней.
  Подробно: `docs/DESKTOP.md` §12, `docs/DISCORD-CHANGES.md`.
- Готовится: **Android 2.5.0** (код 62, та же ветка, 20.10; поверх 2.4.4 «новый дизайн»): то же на общем ядре — вход через Discord по коду
  (ТВ — ссылка и код для телефона), вкладки «Мои» и «Вместе» (`party/Party.kt`, `library/TogetherScreen.kt`), плеер идёт за комнатой
  (`player/PlayerPartySync.kt`, полоса «● Вместе» у гостя). Компилируется локально, на устройствах не проверено. Подробно: `docs/ANDROID.md` §6а, `docs/TODO.md`.
- Готовится: **ПК 2.5.3 и Android 2.5.1** (код 63, 20.10; ещё постеры в Discord, новая иконка, .avi на Android сразу в VLC): **синхронизация по аккаунту Discord** — прогресс (`magnetId|path`) и выбор озвучки/субтитров
  (`m<id>` и `*`; Android ещё файл озвучки рядом). Сервер `services/sync.js` + `routes/sync.js` (`/api/sync`, `sync.json`), запись,
  которой не пользовались `SYNC_IDLE_DAYS` (10) дней, удаляется (прогресс фильма продлевает и его дорожки); протокол — `shared/.../sync/WatchSync.kt`,
  клиенты — `desktop/.../CloudSync.kt`, `android/.../library/CloudSync.kt`. Android теперь помнит дорожки и по `m<id>`. На устройствах не проверено.
- Готовится: **Samsung TV 0.1.0** (`tizen/`, 21.10): ТВ владельца UE40NU7100 (2018, Tizen 4.0): DTS/TrueHD и внешние озвучки не играет; решение владельца — сервер не перекодирует, приложение предупреждает. 0.1.0 на ТВ работает (установка — только после «Permit to install» и подпись всегда профилем «mixed»: автор и дистрибьютор — Samsung, иначе `118012`; `tizen/README.md`). Анализ: `/mnt/project-files/tizen/Анализ портирования на Tizen.md`.
  **0.2.0** (21.10): вход по QR-коду, вкладки Медиатека/Мои/Поиск/Добавить/Вместе/Настройки, «Продолжить просмотр», «Вместе» (`js/partycore.js` — перенос `core/party`, поток XHR), прогресс по аккаунту (`js/sync.js`), предупреждение о `.m2ts` (на ТВ шёл 1 кадр/с без звука) и о «рывках». Добавить — только magnet (`/api/unlock` — прямая ссылка AllDebrid).
  **0.2.7** (23.10): озвучки отдельным файлом (из раздачи и пользователей) склеивает с видео сервер — второй вход ffmpeg в потоке для ТВ (`&x=&xo=`, `docs/CUSTOM-AUDIO-API.md` §8); выбор помнится для раздачи. Сервер и ТВ не выложены, на ТВ не проверено.
- Готовится: **слияние версий и общий поиск раздач** (26.10, PR #39 влит в `main`, сборки не выпущены; Android 2.6.3 код 67, Tizen 0.2.8, ПК — в 2.6.3): плитка на все версии/сезоны (`shared/.../core/Model.kt`: `groupVersions`, `titleKey`, `numberedEpisodesOf`), поиск как на ПК на Android (`shared/core/search`), Tizen и сайте (`searchcore.js` — две копии), фильтр «Только RU-трекеры»; прямой поиск RuTracker — только ПК. Исправлены падение поиска и 20 fps клавиатуры на Android. Не проверено на устройствах. Подробно: `docs/HISTORY.md`, `docs/REPORT-2026-10-26.md`.
- Готовится: **«Определить через ИИ»** (27.10, PR #45 и #46 влиты в `main`; сервер не выложен, сборки не выпущены): Gemini (`gemini-3.5-flash-lite`, ключ только `GEMINI_API_KEY` на сервере) по названию и путям видеофайлов раздач плитки предлагает название, тип, сезон, оригинал и год; после «Принять» меняются плитка, постер (TMDB по «оригинал (год)»), серии, поиск и слияние версий. Сервер — `website/src/services/aititle.js` + `routes/aititle.js` (хранилище `ai-titles.json`, кулдаун 15 мин, дневной лимит 200, `/api/library/ai-title`, `…/decision`, пакетный `/api/library/ai-title/decision`); клиенты — `shared/.../core/Model.kt` (`AiTitle`), ПК (`AiTitleDialog.kt`), Tizen (только показ принятого), сайт; на Android кнопки нет. Правки по обзору кода 27.10 (4K/3D не сезон, `none` повторяется через час, 5xx не списывают лимит и т. д.) внесены. Не сделано: `force` в интерфейсе, кнопка на Android/Tizen, пакетное сохранение на ПК. Подробно: `docs/AI-TITLE.md`.
- Готовится: **исправления по обзору кода** (28.10, Android 2.6.6 код 70, ПК 2.6.6, Tizen 0.2.10; поверх кинофраншиз 2.6.5/0.2.9): сборники по словам названия
  с юникодными границами (`\b` в Java 19+ и в JS без `u` — только латиница), «сага»/«коллекция» — сборник только при фильмах разных лет, общие `collectionKind`/`versionLine`,
  сортировка версий без зацикливания, Android — `PlayerExtras.COLLECTION`, сервер не переспрашивает принятые ответы ИИ. Подробно: `docs/HISTORY.md`.
- **ПК 2.4.0 — стабильная** (18.10, сразу всем по команде владельца; PR #15, #16, #17): mpv рисует в сцене окна — `vo=libmpv` + render API в GL-контексте Skiko, кадр без копии (рисует прямо в текстуру Skia), панели — слои Compose; пока открыт плеер mpv, `Toolkit.setDynamicLayout(false)` (иначе ресайз держит поток интерфейса по 1–5 с). PR #16 (в ветку PR #15): render context освобождать до закрытия окна — иначе mpv роняет процесс (`0xC0000409`). Подробно: `docs/DESKTOP.md` §4.1.
- Аудит 16.10 (отчёт `/mnt/project-files/audit/Аудит Suslik Media.md`): подпись зеркала обновляется (`MirrorLink`, `refreshUrl`), Blu-ray на Android через `TsPlayback`, «копим запас» общий (`CatchUp`). Не сделано: деление `BurstProxy`/`PlayerActivity`/`PlayerView`.
- **Интерфейс ПК и сайта 24.10** (версии не менялись): боковая панель «Коллекция/Сервис» со счётчиками, баннер «Продолжить просмотр» (TMDB: кадр, логотип, описание), метки Dolby Vision/HDR/DTS на постерах (сервер определяет ffprobe: `GET /api/tags`, `services/mediatags.js`, хранится до удаления раздачи). Нужна выкладка `website/` (`posters.js`, `mediatags.js`, `tags.js`, `metadata.js`, `config.js`, `app.js`, `public/*`) и строка nginx `proxy_hide_header Content-Disposition` в `/_alld/`. Подробно: `docs/DESKTOP.md` §13, `docs/WEBSITE.md` §9.
- Журнал ПК из консоли Claude читать только через `//localhost/C$/Users/nanar/AppData/Roaming/...` (`%APPDATA%` у пакета Claude — старая копия).

## Открытое

- Проверить в 2.0.4 на телефоне: «мосты» (в журнале `bridge at`) — пока ни разу не сработали в реальном просмотре;
  Windows с новым сторожем/запасом вообще не запускали.
- Честное сравнение энергопотребления: один фильм 1080p, одно место, по 15 мин, без кабеля, сброс batterystats.
- Снимок `[battery]` из Suslik Debug на Android 17 не пишется (нужен PACKAGE_USAGE_STATS) — брать `dumpsys batterystats` по adb.
- ПК владельца: `gh` установлен (`C:\Program Files\GitHub CLI`, добавить в PATH); в `android/` нет `gradlew` — Gradle 8.10.2 в `~/.gradle/wrapper/dists`, JAVA_HOME = `C:\Program Files\Java\jdk-21.0.12`,
  ANDROID_HOME = `C:\Users\nanar\AppData\Local\Android\Sdk`. Локальная diag-сборка подписана другим ключом, чем CI (ставится только после удаления).
- Discord (ПК 2.5.0): сделаны вход через Discord и по коду устройства, совместный просмотр на ПК, активность в Discord.
  Не сделаны: `requireAdmin`, «Мои фильмы» и комнаты на сайте (Android — 2.5.0) — `docs/TODO.md`.
  Что сделано иначе, чем в плане (SSE вместо WebSocket, комната ждёт загрузку) — `docs/DISCORD.md` §0.
- Идеи в запасе: кеш начала файла/индекса, кнопка «Переустановить релиз» на Android, TODO по мобильным данным,
  HLS/ремукс (для оценки нужны `nproc`, `free -h`, `df -h` сервера), опциональный вариант Б (перезапрос по зависанию — частично сделан).
- Напомнить владельцу: применить/проверить nginx; первая установка MSI после смены схемы версий — вручную.
