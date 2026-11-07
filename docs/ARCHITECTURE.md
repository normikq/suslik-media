# Suslik Media: как устроен проект

Подробная карта проекта: что из чего состоит, как фильм доходит до экрана, где какой код и куда смотреть, если
хочется что-то поменять. Состояние на 06.11.2026: в коде Android 2.7.6 (код 79), Windows 3.1.5 (C++/Qt), Samsung TV 0.2.15.

---

## 1. Общая картина

Suslik Media — частная медиатека поверх **AllDebrid**. AllDebrid сам скачивает торренты на свои серверы и отдаёт
готовые файлы по прямым ссылкам. Но в России AllDebrid заблокирован, поэтому все запросы и само видео идут через
ваш сервер (VPS), который работает как **зеркало**.

```
                 ┌──────────────────────── ваш VPS ───────────────────────┐
  AllDebrid  ◄───┤  nginx (HTTPS, без http2)  ──►  Node.js (website/)     │
  (файлы,        │     │   отдаёт видео сам          API: вход, медиатека,│
   торренты,     │     │   (X-Accel-Redirect)        поиск, ссылки,       │
   API)          │     │                             обновления приложений│
                 │  Xray (VPN) — на том же сервере                        │
                 └─────┬──────────────────────────────────────────────────┘
                       │ HTTPS
        ┌──────────────┼───────────────────┬──────────────────────────┐
        ▼              ▼                   ▼                          ▼
   Сайт (браузер)   Android-приложение   Windows-приложение   Samsung TV        GitHub Actions
   public/index.html  android/            desktop-qt/          tizen/            (сборки, выпуск)
                      (ядро android/core/)  (ядро desktop-qt/core/)
```

Части:

| Часть | Технологии | Что делает |
|---|---|---|
| `website/` | Node.js + Express, один HTML с JS | сервер: вход, медиатека, поиск торрентов, ссылки и зеркало видео, раздача обновлений приложений; плюс сам сайт |
| `android/` | Kotlin, Jetpack Compose, libVLC 3.6 и Media3 | приложение для телефона и ТВ-приставок (Android TV) |
| `android/core/` | чистый Kotlin (без Android API) | ядро Android: запас фильма, сторож воспроизведения, Blu-ray, модель медиатеки, поиск, комнаты, вход, синхронизация, заставки, адрес сервера; тесты на JVM |
| `desktop-qt/` | C++20, Qt 6 (QML), libmpv (`libmpv-2.dll`) | приложение для Windows; ядро `desktop-qt/core/` — перенос `android/core/` на C++ с теми же тестами |
| `tizen/` | HTML/JS (Tizen Web API, AVPlay) | приложение для Samsung Smart TV (Tizen 4.0+); правила медиатеки, поиска и комнат — JS-перенос того же ядра |

Ядро продублировано на трёх языках (Kotlin — Android, C++ — ПК, JS — ТВ и сайт): правила общие, паритет держат одинаковые тесты.

---

## 2. Путь одного фильма: от нажатия до картинки

1. **Медиатека и профили качества.** Приложение спрашивает сервер `GET /api/library`. Сервер берёт список «магнетов» (раздач) у AllDebrid,
   кеширует его и отдаёт в упрощённом виде. В `Model` ядра (Android — `android/core/.../Model.kt`, ПК — `desktop-qt/core/src/Model.cpp`, ТВ — `tizen/js/model.js`) этот ответ разбирается: раздача → файлы → фильм или
   сериал по сезонам, субтитры и внешние озвучки рядом с видео. При наличии нескольких раздач одного произведения `groupVersions` склеивает их в одну плитку (фильмы — по названию и году, сериалы — по названию); монтажные версии (театральная, режиссёрская, расширенная, unrated) разделяются на отдельные карточки. Внутри плитки версии упорядочиваются по разрешению (`qualityScore` 0..4) и размеру, а в профиле варианта формируется композитный профиль качества и весовой рейтинг `smartQualityScore`.
2. **Ссылка.** При выборе фильма приложение просит у сервера `GET /api/file/mirror-url?link=…`. Сервер возвращает
   **подписанную ссылку зеркала**:
   `https://ваш-сайт[/путь]/api/file/proxy/<имя>?link=<ссылка AllDebrid>&exp=<срок>&sig=<подпись>`
   (адрес сервера с путём приложения берут из сборки — секрет `SITE_URL`, `SiteAddress` в ядре).
   Подпись — HMAC с секретом сервера: без неё зеркало не отдаст файл, и ссылка работает ограниченное время.
   Прямую ссылку AllDebrid приложения не спрашивают и не показывают (аудит безопасности 25.10): она есть только у сервера.
3. **Зеркало на сервере.** Запрос на `/api/file/proxy` приходит в Node: он проверяет подпись и узнаёт у AllDebrid
   настоящий адрес файла. Сами байты видео отдаёт **nginx** (заголовок `X-Accel-Redirect`, настройка в
   `website/NGINX-MIRROR.md`), чтобы Node на одном ядре не гонял через себя гигабайты. Если ссылка AllDebrid устарела,
   nginx возвращает запрос в Node, тот получает свежую.
4. **Запас на устройстве (`BurstProxy`).** Приложение не даёт плееру ссылку зеркала напрямую. Оно запускает на устройстве
   крошечный HTTP-сервер `127.0.0.1` и даёт плееру адрес на нём. Этот прокси качает фильм с зеркала **рывками** вперёд
   (до ~130 с фильма, не больше 256 МБ) в кольцевой файл, а плеер читает уже с диска. (Samsung TV играет ссылку зеркала
   напрямую, без запаса.) Зачем: радио телефона отдыхает
   между рывками, обрывы связи не прерывают фильм, внешняя озвучка не расходится с видео.
5. **Плеер** (Android — libVLC или Media3, ПК — mpv) читает с локального прокси и показывает видео. Над ним работает **сторож** (`PlaybackSupervisor`): замечает
   застывшую картинку, пропавший звук, «стоящие часы» и мягко чинит (перезапуск видео → перевыбор звука → переоткрытие).
   В плеере доступно меню выбора пресетов качества (4K, 1080p, 720p) с бесшовным переключением на лету без выхода из комнат и с компенсацией смещения (`offset_ms`); при затяжной буферизации (>10 с на битрейтах >25 Мбит/с) плеер предлагает снизить качество.
6. **Прогресс** (где остановились) хранится на устройстве по ключу «раздача + путь файла», чтобы работало «Продолжить»;
   при входе через Discord он же уходит на сервер (`/api/sync`) вместе с каноническим ключом `cid` (`canonical_id`). Если фильм открыт на другом устройстве в другом пресете качества (например, лёгкий BDRip на телефоне после просмотра BDRemux на ПК), плеер подхватывает позицию по `cid`.

---

## 3. Папки репозитория

```
suslik-media/
├── CLAUDE.md            памятка для Claude: правила, выпуск, отладка, история версий
├── README.md            общее описание
├── docs/
│   ├── ARCHITECTURE.md  общая архитектура проекта (этот файл)
│   ├── WEBSITE.md       сервер и веб-интерфейс
│   ├── ANDROID.md       приложение Android
│   ├── DESKTOP.md       приложение Windows (C++/Qt)
│   ├── DEPLOY.md        выкладка сервера
│   ├── HISTORY.md       журнал версий и причины решений
│   ├── DISCORD.md, DISCORD-ACTIVITY.md  вход через Discord, совместный просмотр, Discord Activity
│   ├── AI-TITLE.md, AI-DISCOVERY.md, MARKERS.md, CUSTOM-AUDIO-API.md  отдельные подсистемы
│   ├── WEBSITE-DESIGN-AND-SESSIONS.md  дизайн сайта и «Сессии»
│   ├── MIGRATION.md     переезд сервера на другой домен или путь
│   └── TODO.md          отложенные задачи
├── website/             сервер + сайт
├── android/             приложение Android
│   ├── app/             приложение (Compose, libVLC, Media3)
│   ├── core/            ядро без Android API (+ тесты на JVM)
│   ├── ffmpeg/          сборка декодера ffmpeg для Media3
│   └── gradlew          Gradle wrapper (8.10.2)
├── desktop-qt/          приложение Windows (core/, app/, installer/, resources/, tests/)
├── tizen/               приложение Samsung TV
├── scripts/             выкладка сайта по SSH
├── install-android-debug.bat/.ps1  сборка и установка «Suslik Debug» на телефон по ADB
└── .github/workflows/   сборки и выпуск в GitHub Actions
```

---

## 4. Сервер и сайт (`website/`)

> 📖 **Подробная документация:** [docs/WEBSITE.md](WEBSITE.md)

### Файлы

| Файл | Что внутри |
|---|---|
| `server.js` (~25 строк) | точка входа: запуск Express-сервера и таймауты сокетов |
| `src/` | модульная архитектура сервера: `app.js` (сборка приложения), `config.js` (настройки), `routes/` (маршруты API), `services/` (бизнес-логика), `middleware/` (сессии, лимитеры, безопасность), `utils/` (хелперы, json-writer) |
| `search.js` | мета-поиск торрентов по трекерам (через Prowlarr/Jackett и др.), единый формат результатов |
| `search-ru.js` | парсеры русских трекеров (rutor, rutracker, kinozal, NNM-Club), кодировка windows-1251 |
| `classify.js` | определяет тип раздачи по названию/категориям: сериал, фильм, «другое» |
| `public/index.html` | сам сайт: одна страница с HTML, CSS и JS внутри |
| `public/redesign.css`, `redesign.js`, `home.js`, `searchcore.js`, `i18n.js` | оформление, баннер и боковая панель, разбор раздач в поиске, строки |
| `scripts/check.js` | проверка синтаксиса всего JS (`src/`, скрипты, встроенный JS страницы) (`npm run check`) |
| `test/` | модульные тесты (`npm test`) |
| `NGINX-MIRROR.md` | как настроить nginx, чтобы он сам отдавал видео |
| `TORAPI.md`, `UPDATES.md`, `README.md` | трекеры, раздача обновлений, установка сервера |
| `.env` (не в репозитории) | секреты: ключ AllDebrid, пароль входа, секрет подписи, `UPDATE_TOKEN`, `BETA_CODE`; образец — `.env.example` |

### Маршруты API (`src/routes/`) по группам

| Группа | Маршруты | Смысл |
|---|---|---|
| Вход | `POST /api/login`, `/api/logout`, `GET /api/session`, `GET /api/auth/discord/start`, `/callback`, `POST /api/auth/device`, `/device/confirm`, `/device/poll` | вход через Discord (роли проверяет бот) или по паролю; ТВ и ПК — по коду с экрана и токену устройства |
| Вместе | `POST /api/rooms`, `GET /api/rooms/:id`, `/events` (SSE), `POST /api/rooms/:id/join`, `/leave`, `/action` | комнаты совместного просмотра: ведущий управляет, комната ждёт тех, у кого грузится |
| Медиатека | `GET /api/library`, `/api/magnets/:id`, `/api/magnets/:id/files`, `POST /api/magnets/:id/cancel` (и удалить) | список раздач, файлы раздачи, отмена/удаление |
| Добавление | `POST /api/magnets` (magnet-ссылка), `/api/torrents` (.torrent) | отправить раздачу в AllDebrid |
| Поиск | `GET /api/search`, `POST /api/search/add`, `/api/favorites` | поиск по трекерам, добавить найденное, избранное |
| Ссылки и видео | `GET /api/file/mirror-url`, `GET /api/file/proxy/...` | подписанная ссылка зеркала, само зеркало (прямую ссылку сервер не отдаёт с 25.10) |
| Сериалы | `POST /api/playlist`, `GET /api/playlist/:id/:name` | плейлист серий до конца сезона (.m3u) |
| Оформление | `POST /api/media-metadata`, `GET /api/poster`, `/api/poster/img/...`, `GET /api/tags` | постеры, кадры, логотипы, описания фильмов (с кешем); метки Dolby Vision / HDR / DTS по файлу |
| Аккаунт | `GET/POST /api/sync`, `POST /api/shelf` | прогресс и дорожки по аккаунту Discord, «Мои фильмы» |
| ТВ, озвучки, ИИ, заставки | `/api/file/tv-info`, `/api/file/tv/…`, `/api/custom-audio/…`, `/api/library/…/ai-title`, `GET /api/markers` | поток для Samsung TV, внешние озвучки, «Определить через ИИ», метки заставок и титров |
| Приложения | `GET /api/app/update`, `/api/app/download/...`, `/api/app/beta…`, `POST /api/app/upload`, `/api/app/promote`, `GET /api/app/status`, `GET/POST /api/app/changelog` | раздача обновлений, каналы «релиз/бета», загрузка сборок из CI |
| Ошибки | `POST /api/app/report`, `GET /api/app/reports` | приложения присылают отчёты о сбоях |

Небольшие данные сервер хранит в JSON-файлах рядом с собой (сессии, пользователи Discord без их токенов, хеши токенов устройств,
кеш медиатеки и файлов раздач, кеш метаданных; комнаты — только в памяти),
сборки приложений — в папке `updates/` (с подпапкой для беты).

### Что важно помнить
- Пуш в GitHub **не обновляет сервер**: workflow `website.yml` только проверяет синтаксис. На сервер код ставите вы.
- Зеркало нельзя заменять прямыми ссылками AllDebrid (блокировка в РФ).
- nginx без http2, потому что на сервере ещё Xray.

---

## 5. Ядро Android (`android/core/`)

Логика без экрана и без Android API (бывший общий модуль `shared/`, до 03.11 его делили Android и Kotlin-версия ПК). Подключается к сборке
Android как отдельная сборка Gradle (`includeBuild("core")` в `android/settings.gradle.kts`, зависимость `ru.suslikovanora.media:core`),
пакеты — `ru.suslikovanora.media.core.*`. ПК держит C++-перенос того же кода в `desktop-qt/core/` с теми же тестами (Qt Test), ТВ и сайт —
JS-переносы части правил (`tizen/js/model.js`, `searchcore.js`, `partycore.js`). Меняете правило здесь — поменяйте и переносы.

Модульные тесты — `android/core/src/test/...`, в CI на каждый пуш с изменениями в `android/core/` (`android-core.yml`):
- `ModelTest.kt` — парсинг ответа сервера, сериалы по папкам и именам, коллекции фильмов, очистка названий, внешние дорожки и субтитры, статусы и прогресс;
- `BurstProxyTest.kt` — локальный прокси, кольцевые файлы, скачивание рывками, смены сети, hold-буферизация, тайм-ауты;
- `CoreTest.kt` — сравнение версий (`Versions`), вердикты каналов, парсинг `changelog.json`, сторож плеера (`PlaybackSupervisor`), очистка кэша (`LibraryKeep`), формат времени (`clockText`), правила «копим запас» (`CatchUp`);
- `MirrorLinkTest.kt` — подписанные адреса зеркала: ключ файла, срок подписи, адрес для обновления;
- `SeriesGroupingTest.kt`, `search/…` — слияние версий, разбор и фильтр раздач; `PartyTest`/`PartyProtocolTest`/`PartySyncEngineTest` — комнаты;
  `AuthTest`, `WatchSyncTest`, `MarkersDetectorTest`, `CustomAudioTest`, `TorrentInspectorTest`, `PlaybackSupervisorTest`, `SiteAddressTest`.

| Файл | Что делает |
|---|---|
| `BurstProxy.kt` (~1000 строк) | **запас фильма**: локальный HTTP-сервер для VLC, «окна» в кольцевых файлах, рывки между нижней и верхней границей запаса (по битрейту фильма), короткий разгон после открытия/перемотки, сторож зависших запросов (`headerStallMs`, `bodyStallMs`), быстрый сброс соединений при разрыве сокетов/таймаутах и мгновенный повтор при голодающем буфере, перезапуск запросов при смене сети, ожидание без сети, «мосты» при перемотке назад, «копим запас» (`hold`) на сети медленнее фильма, замер скорости сети, адаптивная глубина запаса (`reserveSeconds`), порции с запасом на «съедание» буфера плеером, кэш конца файла в памяти (`tailCache`), остановка при выключенном экране/закрытом фильме/без сети (`stopped()`), `readRange` для маленьких кусков, новая подпись адреса вместо ошибки, когда старая истекла (`refreshUrl`, ответ 401/403) |
| `PlaybackSupervisor.kt` | **сторож воспроизведения**: по статистике VLC решает, что картинка застыла, звук пропал или часы стоят, и выбирает действие (по одному, с паузами и бюджетом на серию) |
| `Model.kt` | **модель медиатеки**: разбор ответа сервера, раздачи и файлы, главный видеофайл, сериалы по сезонам и сериям, субтитры и внешние озвучки, ключ прогресса просмотра |
| `PlaybackPresets.kt` | варианты настроек «Запас фильма» (до 160 и 256 МБ для 4K/Remux) и «Буфер плеера» |
| `MirrorLink.kt` | **адрес зеркала** `/api/file/proxy/<имя>?link=…&exp=…&sig=…`: ключ файла без подписи (`fileKey`, им пользуются `BurstProxy` и выбор движка), срок подписи (`isExpired`), запрос новой подписи (`renewPath` → `/api/file/mirror-url`) |
| `CatchUp.kt` | правила «копим запас» для обоих плееров: когда начинать, прогресс, когда заканчивать и нужна ли перемотка для перезапуска декодеров |
| `TimeText.kt` | `clockText(ms)` — время «05:03» / «1:05:03» в обоих приложениях |
| `LibraryKeep.kt` | что оставить после обновления медиатеки (постеры, прогресс, запас на диске) |
| `Versions.kt` | сравнение версий (`2.2.3` новее `2.2.1` и т.п.) |
| `Changelog.kt` | чтение `changelog.json` («Что нового») |
| `party/` | совместный просмотр: протокол комнат (`PartyProtocol`: `PartyRoom`, разбор SSE, команды), часы сервера и подгонка расхождения, `PartySyncEngine` (тик 400 мс) |
| `auth/Auth.kt` | сессия, вход по коду устройства, «Мои фильмы» |
| `sync/WatchSync.kt` | прогресс и выбор дорожек по аккаунту |
| `search/` | разбор названий раздач (`ReleaseParser`), фильтр и пресеты (`SearchFilter`), `SearchHit` |
| `audio/CustomAudio.kt`, `torrent/` | внешние озвучки; разбор `.torrent` (`TorrentInspector`) |
| `MarkersDetector.kt` | заставки и титры: главы файла и `/api/markers` |
| `SiteAddress.kt` | адрес сервера из `SITE_URL` (домен и путь), «свой ли это сервер» |

---

## 6. Android (`android/`)

> 📖 **Подробная документация:** [docs/ANDROID.md](ANDROID.md)

### Главные файлы (`android/app/src/main/java/ru/suslikovanora/media/`)

| Файл | Что делает |
|---|---|
| `MainActivity.kt` | главное окно: вкладки (медиатека, «Мои», «Вместе», поиск, добавить, настройки), запуск плеера, переход гостя комнаты к фильму ведущего; на Android TV — управление пультом |
| `PlayerActivity.kt` (~1900 строк) | **плеер**: координация движков (libVLC и Media3), привязка поверхностей видео, жизненный цикл и взаимодействие контроллеров, защита от сброса прогресса и мигания «00:00» при переподключениях/открытии, мгновенный сброс буфера аудио (`reseekHereNow`) |
| `PlayerPanel.kt` | панель под фильмом, когда телефон вертикально: название, кнопки, серии, шторка дорожек (Jetpack Compose) |
| `player/` | **модули плеера**: движки (`VlcEngine`, `Media3Engine`, `HybridEngine`, выбор — `EngineChoice`) и контроллеры (жесты, звук, дорожки, PiP, очередь, прогресс, оверлей, сеть, Blu-ray, телеметрия); панель над фильмом в стиле Windows — `PlayerOverlay` и `PlayerIcons`, субтитры в кадре — `SubtitleFit`; `PlayerExtras` — все ключи Intent плеера |
| `library/LibraryScreen.kt`, `LibraryStore.kt` | медиатека: экран и данные (загрузка, кеш на устройстве, обновление) |
| `library/PlaySheet.kt` | лист «Смотреть»: зеркало/напрямую, «Продолжить/С начала», субтитры и озвучки, плейлист сезона |
| `library/SearchScreen.kt`, `AddScreen.kt`, `LoginScreen.kt` | поиск, добавление раздач, вход |
| `net/Api.kt` | запросы к серверу (OkHttp): адрес из `BuildConfig.SITE_URL`, повтор на новом соединении, если ответа нет 6/8/10 с, перенос cookie при смене адреса сервера |
| `party/Party.kt`, `library/TogetherScreen.kt`, `player/PlayerPartySync.kt` | совместный просмотр: комната, вкладка «Вместе», плеер идёт за комнатой |
| `Settings.kt` | настройки (запас, буфер, канал обновлений; в отладочной сборке — свой раздел) |
| `Updater.kt`, `WhatsNew.kt` | проверка обновлений и «Что нового» |
| `TrafficMeter.kt` | счётчик трафика на мобильной сети |
| `AppLog.kt` | журнал приложения и отчёты о сбоях на сервер |
| `Diag.kt` | **только отладочная сборка**: подробный журнал (`[sys]`, `[net]`, `[http]`, `[ui]`, батарея…) |
| `ui/` | тема, общие элементы интерфейса, нижняя панель |

### Модули плеера (`android/app/src/main/java/ru/suslikovanora/media/player/`)

Плеер декомпозирован на специализированные контроллеры с изоляцией ответственности:

| Контроллер | Что делает |
|---|---|
| `PlayerAudioSession.kt` | `MediaSessionCompat`, управление AudioFocus (приглушение/пауза при звонках), реакция на отключение наушников (`BecomingNoisy`), системная громкость |
| `PlayerGestures.kt` | распознавание жестов: горизонтальный свайп перемотки, вертикальные свайпы громкости и яркости, зум (`pinch-to-zoom`), двойной тап (±10 с) |
| `PlayerNetworkMonitor.kt` | мониторинг `ConnectivityManager`: переключение Wi-Fi ↔ мобильная сеть, валидация живости сети, сигнал на переподключение |
| `PlayerTrackSelector.kt` | переключение звуковых дорожек и субтитров, сохранение предпочтений в `player_ui`, подключение внешних аудио и субтитров (sidecars) — у каждой серии плейлиста свои, подписываются при переходе (`/api/file/mirror-url`), восстановление после переподключения |
| `PlayerPipController.kt` | режим «картинка в картинке» (Picture-in-Picture): расчет aspect ratio с учетом SAR видео, авто-вход на Android 12+, обновление параметров |
| `PlayerWatchProgress.kt` | сохранение и восстановление прогресса просмотра (`positions`, `watch`), последние 15 с — серия просмотрена, место сбрасывается в начало |
| `PlayerPlaylistQueue.kt` | очередь серий и плейлисты M3U: навигация («Предыдущая/Следующая серия»), таймер и плашка обратного отсчета до следующего эпизода |
| `PlayerControlsOverlay.kt` | показ/скрытие панели (анимацию рисует `PlayerOverlay`), таймер автоскрытия (3.8 с, не при открытой шторке), блокировка экрана от случайных нажатий (экранный щит, кнопка разблокировки), системные бары (System UI) |
| `PlayerOverlay.kt` | панель над фильмом в стиле плеера Windows («жидкое стекло», Compose): стеклянные кнопки, белый таймлайн, три зоны кнопок, шторки дорожек/субтитров/«⚙», капсула «Следующая серия»; касания мимо кнопок уходят в жесты; фокус пульта TV |
| `PlayerIcons.kt` | векторные иконки плеера (перенесены с ПК) |
| `SubtitleFit.kt` | субтитры не уходят за край в «Заполнить» / «Растянуть» / «16:9»: слой субтитров VLC и Media3 — на месте вписанного кадра |
| `PlayerDiagnostics.kt` | телеметрия и отладка (сбор метрик каждые 5 с в `[vlc]`/`[proxy]`), обнаружение программного декодирования (VC-1, кодеки через `MediaCodecList`), перезапуск при черном экране, динамический fps дисплея на Android 11+ (`Surface.setFrameRate`) |

### Сборки
- `android/app/build.gradle.kts`: версия (`versionCode`, `versionName`), варианты сборки. Кроме обычной есть
  **`diag`** — «Suslik Debug» с пакетом `ru.suslikovanora.media.diag`, ставится рядом с обычной. Подробно:
  `android/DEBUG-BUILD.md`.
- «Что нового»: `android/app/src/main/assets/changelog.json`.

---

## 7. Windows (`desktop-qt/`)

> 📖 **Подробная документация:** [docs/DESKTOP.md](DESKTOP.md), сборка — [desktop-qt/README.md](../desktop-qt/README.md)

C++20 и Qt 6 (QML), только Windows; с 3.0.0 заменяет Kotlin-версию на Compose Desktop (удалена 03.11). Движок — **mpv**
(`libmpv-2.dll`): её не кладут в программу, её качает установщик или сама программа (`player/MpvInstall`) с сервера и сверяет SHA-256
(`resources/libmpv.properties`).

| Папка / модуль (`desktop-qt/`) | Что делает |
|---|---|
| `core/` | ядро без интерфейса (Qt Core/Network): `BurstProxy`, `PlaybackSupervisor`, `Model`, `Auth`, `WatchSync`/`CloudSync`, `Party`, `Search`, `Trackers`, `ApiClient`, `Store`, `Strings`… |
| `app/main.cpp`, `app/qml/` | запуск; экраны на QML: `Main.qml` (окно, вкладки), `Theme.qml` (токены «жидкого стекла»), `LibraryView`, `PlayDialog`, `PlayerView`, `SearchView`, `TogetherView`, `SettingsSheet`… |
| `app/src/` | модели для QML: `Library`, `LibraryPage`, `PlayModel`, `SearchModel`, `AddModel`, `AudioAttach`, `Settings`, `Updater`, `AppDir`, `ImageProvider`/`PosterCache`, `IconProvider`, `SingleInstance`, `TitleBar`, `UrlScheme` |
| `app/src/player/` | **плеер**: `PlayerModel` (очередь, прогресс, дорожки, сторож), `MpvEngine` + `Mpv` + `MpvVideo` (вывод в сцене QML), `Reserve` (запас), `WindowMode` (полный экран, мини-плеер), `MediaControls`/`MediaSession` (медиа-кнопки Windows), `DisplayAwake`, `DiscordRpc`, `PlayerLog` |
| `app/src/party/` | **«Вместе»**: `Party` (комната, REST + SSE), `PartySync` (плеер идёт за комнатой, `core::PartySyncEngine`) |
| `app/src/trackers/` | RuTracker с ПК через фоновый браузер (`BrowserBridge`) и напрямую (`RuTrackerClient`), окно «Трекеры» |
| `installer/` | MSI (WiX 3, `build-msi.sh`) и мастер установки/удаления на WPF (`setup/`) |
| `resources/` | шрифты, значки, `strings/ru.json`, `changelog.json`, `libmpv.properties` |
| `tests/` | тесты ядра (Qt Test, `ctest`) |

### Плеер Windows — главное

- **Видео и панели в одной сцене.** mpv рисует кадры (render API, OpenGL) в FBO Qt Quick (`MpvVideo`), панели — слои QML поверх.
- **Полный экран и мини-плеер** — `WindowMode` (окно без рамки на 1 px выше монитора, мини-плеер с пропорциями фильма).
- **Слабая сеть: «копим запас»** — `BurstProxy.hold` и правила `CatchUp`, как на Android.
- **Сторож** (`PlaybackSupervisor`) получает статистику от `MpvEngine` и при зависании перезапускает вывод видео без переоткрытия файла.
- **Заставки и титры** — `MarkersDetector` + `/api/markers` (`docs/MARKERS.md`); «Следующая/Предыдущая серия», очередь — весь сериал.

Сборка: `cmake -S desktop-qt -B build -G Ninja && cmake --build build`; версия — `project(SuslikMediaQt VERSION …)` в `desktop-qt/CMakeLists.txt`;
отладочная — `-DSM_DIAG=ON`. «Что нового»: `desktop-qt/resources/changelog.json`.

---

## 8. Сборка и выпуск (GitHub Actions)

| Workflow | Когда запускается | Что делает |
|---|---|---|
| `android-core.yml` | пуш с изменениями в `android/core/` | тесты ядра Android |
| `android.yml` | только вручную | собирает APK (отладочный — с `debug=true`); с `publish=true` выкладывает на сайт в канал `beta` или `stable` |
| `desktop-qt.yml` | пуш в `desktop-qt/` — тесты ядра на Linux; вручную — сборка для Windows | программа, MSI, установщик `Suslik Media Setup-<V>.exe` (отладочная — с `debug=true`); с `publish=true` выкладывает установщик на сайт (платформа `desktop`) |
| `setup-preview.yml` | пуш в `desktop-qt/installer/setup/` | снимки окон установщика (PNG) |
| `media3-ffmpeg.yml` | пуш в `android/ffmpeg/` (не `main`), или вручную | сборка AAR декодера ffmpeg для Media3 |
| `promote.yml` | вручную | «выпустить всем»: версия из беты становится релизом |
| `update-status.yml` | вручную | проверка: какие версии сайт видит в каналах |
| `changelog.yml` | пуш в `main` с изменением `changelog.json` | сразу отправляет «Что нового» на сайт |
| `website.yml` | пуш в `website/` | только проверка синтаксиса (на сервер ничего не ставит) |
| `website-deploy.yml` | только вручную, по команде владельца | выкладка сайта на сервер по SSH (`docs/DEPLOY.md`) |
| `reports.yml` | вручную | показывает последние отчёты о сбоях и журналы, которые прислали приложения |

**Каналы.** Обычный путь — бета (видят только те, у кого введён код беты) → «выпускай всем» (`promote.yml`).
Приложения сами спрашивают сервер `/api/app/update` и предлагают обновиться.

**Версия** — три числа: Android — `versionCode`/`versionName` в `android/app/build.gradle.kts`, Windows — `project(SuslikMediaQt VERSION …)`
в `desktop-qt/CMakeLists.txt`, Tizen — `version` в `tizen/config.xml`. При выпуске: поднять версию, добавить запись в «Что нового»
(`android/app/src/main/assets/changelog.json`, `desktop-qt/resources/changelog.json`, `tizen/js/changelog.js`; новая сверху), пуш, запустить сборку с публикацией.
Адрес сервера сборки берут из секрета `SITE_URL` (домен и, если нужно, путь; переезд — `docs/MIGRATION.md`).

---

## 9. Отладка

- **Отладочные сборки «Suslik Debug»** — отдельные приложения рядом с обычными, со своим входом и подробным журналом.
  - Android: журнал `Android/data/ru.suslikovanora.media.diag/files/diag/player.log`, метка 📍 в плеере.
  - Windows: «Suslik Debug Qt» (`-DSM_DIAG=ON`), журнал `%APPDATA%\SuslikMediaQtDebug\player.log`, метка — F8 в плеере (`docs/DESKTOP.md` §9).
- **Теги журнала:** `[vlc]` (Android) / `[mpv]` (ПК) и `[proxy]` (раз в 5 с: что показывает плеер и сколько запаса впереди), `[sys]` (батарея,
  процессор, сеть), `[http]` (каждый запрос), `[net]` (смена сети), `[mark]` (метка), `[ts]` (Blu-ray).
  Читать удобно от нужной метки назад.
- **Обычные приложения** тоже пишут журнал (поменьше) и присылают на сервер отчёты о сбоях.

---

## 10. «Хочу поменять…» — куда смотреть

| Задача | Где |
|---|---|
| размер запаса, как часто качать, поведение на плохой сети | `android/core/.../BurstProxy.kt` и `desktop-qt/core/src/BurstProxy.cpp`, пресеты — `PlaybackPresets` |
| когда плеер сам что-то чинит (застывшая картинка, нет звука) | `PlaybackSupervisor` в `android/core/` и `desktop-qt/core/` |
| как раздача превращается в фильм/сериал, серии, субтитры | `Model` в `android/core/` и `desktop-qt/core/` (+ `tizen/js/model.js`, сайт) |
| профили качества и умный скоринг (`smartQualityScore`) | `Model` (`smartQualityScore`), сервер `website/src/services/mediatags.js` (`buildMediaVariant`) |
| монтажные версии и канонический ID (`canonical_id`, `edition`) | `website/src/services/aititle.js` (`canonicalIdOf`), `Model` (`groupVersions`) |
| пресеты качества и варианты в комнатах «Вместе» | `website/src/services/rooms.js` (`cleanVariant`, `room.file.variants`), клиенты `PartySync` |
| экран и кнопки плеера на телефоне | `android/.../PlayerActivity.kt`, панель над фильмом — `player/PlayerOverlay.kt` (иконки — `PlayerIcons.kt`), под фильмом — `PlayerPanel.kt`, показ/скрытие и блокировка — `player/PlayerControlsOverlay.kt` |
| жесты в плеере на телефоне (перемотка, зум, яркость) | `android/.../player/PlayerGestures.kt` |
| звук, кнопки гарнитуры и Bluetooth в плеере | `android/.../player/PlayerAudioSession.kt` |
| звуковые дорожки и субтитры (включая внешние) | `android/.../player/PlayerTrackSelector.kt` |
| «картинка в картинке» (PiP) на телефоне | `android/.../player/PlayerPipController.kt` |
| очередь серий и автопереход | `android/.../player/PlayerPlaylistQueue.kt` |
| сохранение прогресса просмотра фильма/серии | `android/.../player/PlayerWatchProgress.kt` |
| телеметрия, кодеки и fps дисплея на телефоне | `android/.../player/PlayerDiagnostics.kt` |
| экран и клавиши плеера на ПК | `desktop-qt/app/qml/PlayerView.qml`, логика — `app/src/player/PlayerModel.cpp` |
| лист «Смотреть» (зеркало/напрямую, продолжить) | Android `library/PlaySheet.kt`, ПК — `qml/PlayDialog.qml` + `PlayModel` |
| медиатека на телефоне / ПК | `library/LibraryScreen.kt` + `LibraryStore.kt` / `qml/LibraryView.qml` + `Library`, `LibraryPage` |
| поиск торрентов (трекеры) | сервер: `website/search.js`, `search-ru.js`; экраны — `SearchScreen.kt` / `qml/SearchView.qml` + `SearchModel` |
| вход, сессии, пароль | `website/src/routes/auth.js`, `src/middleware/auth.js` + `LoginScreen.kt` / `qml/LoginView.qml` |
| ссылки зеркала, подпись, срок | `website/src/routes/mirror.js`, `src/services/mirror.js`; nginx — `NGINX-MIRROR.md` |
| сам сайт | `website/public/index.html` (+ `redesign.css/js`) |
| обновления и каналы | сервер `website/src/routes/updates.js`, приложения — Android `Updater.kt`, ПК `app/src/Updater.cpp`; workflows |
| тексты «Что нового» | `android/app/src/main/assets/changelog.json`, `desktop-qt/resources/changelog.json`, `tizen/js/changelog.js` |
| совместный просмотр | ядро `android/core/.../party/` и `desktop-qt/core/src/Party.cpp`, ПК `app/src/party/`, Android `party/Party.kt`, сервер `website/src/routes/rooms.js`, Tizen `js/party*.js` |
| адрес сервера, переезд | `SiteAddress` в `android/core/` и `desktop-qt/core/`, секрет `SITE_URL`, сервер `website/src/utils/helpers.js` (`basePath`), `docs/MIGRATION.md` |
| плеер в окне громкости Windows и клавиши мультимедиа на ПК | `desktop-qt/app/src/player/MediaControls.cpp`, подключение — `MediaSession.cpp` (`docs/DESKTOP.md` §6) |

---

## 11. Правила проекта (кратко)

- Новые версии — сначала в бету, всем — по вашей команде.
- Зеркало не заменять прямыми ссылками AllDebrid.
- Секреты (`.env`, ключи подписи APK, токены, коды беты, cookie) не попадают в репозиторий.
- `website/` и сервер меняются только с вашего разрешения.
---

## 12. Подсистема локализации (i18n)

Все платформы проекта выносят пользовательские строки в JSON-файлы локализации:
- **Windows**: `desktop-qt/resources/strings/ru.json`, доступ через `tx(key, ...)` (`core/Strings`, в QML — `Ui.tx`).
- **Android**: `android/app/src/main/assets/strings/ru.json`, доступ через `tr(key, ...)` (`Strings.kt`).
- **Samsung Smart TV (Tizen)**: `tizen/locales/ru.json`, модуль `tizen/js/i18n.js` (`tr()` и декларативные атрибуты `data-i18n`).
- **Веб-интерфейс**: `website/public/locales/ru.json`, модуль `website/public/i18n.js` (`window.tr`, декларативные атрибуты `data-i18n`).

Строки ориентированы на пользователя (понятные объяснения без технического жаргона) и не содержат прямых упоминаний сторонних сервисов (AllDebrid).
