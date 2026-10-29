# Suslik Media: как устроен проект

Подробная карта проекта: что из чего состоит, как фильм доходит до экрана, где какой код и куда смотреть, если
хочется что-то поменять. Состояние на 30.10.2026: Android 2.7.0, Windows 2.7.0, Samsung TV 0.2.12 (в коде; последние сборки — 2.6.9).

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
   public/index.html  android/            desktop/             tizen/            (сборки, выпуск)
                        └── общий код shared/ ──┘
```

Части:

| Часть | Технологии | Что делает |
|---|---|---|
| `website/` | Node.js + Express, один HTML с JS | сервер: вход, медиатека, поиск торрентов, ссылки и зеркало видео, раздача обновлений приложений; плюс сам сайт |
| `android/` | Kotlin, Jetpack Compose, libVLC 3.6 и Media3 | приложение для телефона и ТВ-приставок (Android TV) |
| `desktop/` | Kotlin, Compose Desktop, mpv (`libmpv-2.dll`) | приложение для Windows |
| `tizen/` | HTML/JS (Tizen Web API, AVPlay) | приложение для Samsung Smart TV (Tizen 4.0+) |
| `shared/` | чистый Kotlin (без Android и без окон) | общий код Android и ПК: запас фильма, сторож воспроизведения, Blu-ray, модель медиатеки, поиск, комнаты, вход, синхронизация, заставки, адрес сервера |

---

## 2. Путь одного фильма: от нажатия до картинки

1. **Медиатека.** Приложение спрашивает сервер `GET /api/library`. Сервер берёт список «магнетов» (раздач) у AllDebrid,
   кеширует его и отдаёт в упрощённом виде. В `shared/Model.kt` этот ответ разбирается: раздача → файлы → фильм или
   сериал по сезонам, субтитры и внешние озвучки рядом с видео.
2. **Ссылка.** При выборе фильма приложение просит у сервера `GET /api/file/mirror-url?link=…`. Сервер возвращает
   **подписанную ссылку зеркала**:
   `https://ваш-сайт[/путь]/api/file/proxy/<имя>?link=<ссылка AllDebrid>&exp=<срок>&sig=<подпись>`
   (адрес сервера с путём приложения берут из сборки — секрет `SITE_URL`, `shared/.../SiteAddress.kt`).
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
6. **Прогресс** (где остановились) хранится на устройстве по ключу «раздача + путь файла», чтобы работало «Продолжить»;
   при входе через Discord он же уходит на сервер (`/api/sync`) и виден на других устройствах.

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
│   ├── DESKTOP.md       приложение Windows
│   ├── HISTORY.md       журнал версий и причины решений
│   ├── DISCORD.md, DISCORD-CHANGES.md  вход через Discord и совместный просмотр
│   ├── AI-TITLE.md, MARKERS.md, CUSTOM-AUDIO-API.md  отдельные подсистемы
│   ├── MIGRATION.md     переезд сервера на другой домен или путь
│   └── TODO.md          отложенные задачи
├── website/             сервер + сайт
├── shared/              общий код приложений (+ тесты)
├── android/             приложение Android
├── desktop/             приложение Windows (+ setup/ — установщик на C#)
├── tizen/               приложение Samsung TV
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

## 5. Общий модуль (`shared/`)

Код, который одинаково нужен Android и Windows. В нём нет ничего про экран, поэтому он покрыт модульными тестами
(`shared/src/test/...`, запускаются в CI на каждый пуш с изменениями в `shared/`):
- `ModelTest.kt` — парсинг ответа сервера, сериалы по папкам и именам, коллекции фильмов, очистка названий, внешние дорожки и субтитры, статусы и прогресс;
- `BurstProxyTest.kt` — локальный прокси, кольцевые файлы, скачивание рывками, смены сети, hold-буферизация, тайм-ауты;
- `TsClockTest.kt` — расчет длительности Blu-ray по меткам PCR, перемотка, сетка контрольных точек VBR;
- `CoreTest.kt` — сравнение версий (`Versions`), вердикты каналов, парсинг `changelog.json`, сторож плеера (`PlaybackSupervisor`), очистка кэша (`LibraryKeep`), формат времени (`clockText`), правила «копим запас» (`CatchUp`);
- `MirrorLinkTest.kt` — подписанные адреса зеркала: ключ файла, срок подписи, адрес для обновления;
- `SeriesGroupingTest.kt`, `search/…` — слияние версий, разбор и фильтр раздач; `PartyTest`/`PartyProtocolTest`/`PartySyncEngineTest` — комнаты;
  `AuthTest`, `WatchSyncTest`, `MarkersDetectorTest`, `CustomAudioTest`, `TorrentInspectorTest`, `PlaybackSupervisorTest`, `SiteAddressTest`.

| Файл | Что делает |
|---|---|
| `BurstProxy.kt` (~1000 строк) | **запас фильма**: локальный HTTP-сервер для VLC, «окна» в кольцевых файлах, рывки между нижней и верхней границей запаса (по битрейту фильма), короткий разгон после открытия/перемотки, сторож зависших запросов (`headerStallMs`, `bodyStallMs`), быстрый сброс соединений при разрыве сокетов/таймаутах и мгновенный повтор при голодающем буфере, перезапуск запросов при смене сети, ожидание без сети, «мосты» при перемотке назад, «копим запас» (`hold`) на сети медленнее фильма, замер скорости сети, адаптивная глубина запаса (`reserveSeconds`), порции с запасом на «съедание» буфера плеером, кэш конца файла в памяти (`tailCache`), остановка при выключенном экране/закрытом фильме/без сети (`stopped()`), `readRange` для маленьких кусков, новая подпись адреса вместо ошибки, когда старая истекла (`refreshUrl`, ответ 401/403) |
| `PlaybackSupervisor.kt` | **сторож воспроизведения**: по статистике VLC решает, что картинка застыла, звук пропал или часы стоят, и выбирает действие (по одному, с паузами и бюджетом на серию) |
| `TsClock.kt` | **Blu-ray (.m2ts)**: длительность по «часам» потока (PCR), сетка контрольных точек (срезы 25%, 50%, 75%) для кусочно-линейной интерполяции переменного битрейта (VBR), динамическое обучение (`learn`) в процессе воспроизведения, кэширование по ссылке |
| `TsPlayback.kt` | **Blu-ray**: общие решения для обоих плееров — точная перемотка по контрольным точкам (`TsClock.posFor`), «Продолжить» со смещением времени, динамическое обучение при воспроизведении |
| `Model.kt` | **модель медиатеки**: разбор ответа сервера, раздачи и файлы, главный видеофайл, сериалы по сезонам и сериям, субтитры и внешние озвучки, ключ прогресса просмотра |
| `PlaybackPresets.kt` | варианты настроек «Запас фильма» (до 160 и 256 МБ для 4K/Remux) и «Буфер плеера» |
| `MirrorLink.kt` | **адрес зеркала** `/api/file/proxy/<имя>?link=…&exp=…&sig=…`: ключ файла без подписи (`fileKey`, им пользуются `BurstProxy`, `TsClock`, выбор движка), срок подписи (`isExpired`), запрос новой подписи (`renewPath` → `/api/file/mirror-url`) |
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
| `PlayerBluRayController.kt` | поддержка `.m2ts` / MPEG-TS потоков поверх общего `TsPlayback` (те же правила смещения, перемотки и «приземления», что на ПК): длительность через `TsClock`, watchdog-таймер |
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

## 7. Windows (`desktop/`)

> 📖 **Подробная документация:** [docs/DESKTOP.md](DESKTOP.md)

Движок — только **mpv** (`libmpv-2.dll`; VLC убран в 2.4.3). Библиотеку не кладут в установщик: её качает установщик или
само приложение (`player/MpvInstall.kt`) с сервера и сверяет SHA-256 (`libmpv.properties`).

| Файл / модуль (`desktop/src/main/kotlin/ru/suslikovanora/media/desktop/`) | Что делает |
|---|---|
| `Main.kt` | запуск: одно окно, боковая панель, вкладки, плеер поверх медиатеки, горячие клавиши |
| `player/` | **плеер**: `PlayerView.kt` (экран и клавиши), `PlayerOverlays.kt`, `PlayerControls.kt`, `PlayerIcons.kt`, `PlayerMorePanel.kt`, `PlayerPlaylist.kt`, `PlayRequest.kt`; движок `PlayerEngine.kt` → `MpvEngine.kt` + `MpvLib.kt` (JNA) + `MpvVideo.kt` (вывод в сцене); `SideFiles.kt` — внешние озвучки и субтитры; `MediaControls.kt` — системные медиа-контролы Windows; `DisplayAwake.kt` — экран не гаснет во время фильма |
| `ui/` | дизайн-система: `Theme.kt` (токены `Sm`, шрифты), `Controls.kt`, `DesktopIcons.kt`, `Motion.kt`, `Pagination.kt` |
| `Library.kt`, `LibraryView.kt`, `PlayDialog.kt`, `VersionPicker.kt`, `Sidebar.kt` | медиатека: данные, экран, окно «Смотреть», выбор версии, боковая панель |
| `SearchView.kt`, `AddView.kt`, `trackers/` | поиск раздач (сервер + прямой поиск RuTracker через браузер ПК), добавление |
| `AiTitleDialog.kt`, `CustomAudio.kt`, `AudioAttachView.kt` | «Определить через ИИ», внешние озвучки |
| `Api.kt` | запросы к серверу (java.net.http), адрес из `-Dsuslik.site`; cookie и токен устройства — только своему серверу (`SiteAddress.isOwn`) |
| `party/` | **совместный просмотр**: `Party.kt` — комната (REST + SSE), `PartySync.kt` — плеер идёт за комнатой, `TogetherView.kt` — страница «Вместе» |
| `discord/DiscordRpc.kt` | активность «Смотрит» в профиле Discord через локальный канал discord-ipc |
| `CloudSync.kt` | прогресс и выбор дорожек по аккаунту Discord (`/api/sync`) |
| `Reserve.kt` | подключает общий запас `BurstProxy` к этому приложению |
| `Settings.kt`, `Storage.kt`, `Strings.kt` | настройки, хранение (`Store` с `fsync` и `.bak`), строки `tr()` |
| `Updater.kt`, `WhatsNew.kt`, `Uninstaller.kt` | обновление через «Suslik Media Setup.exe /update», «Что нового», удаление |
| `PlayerLog.kt`, `Diag.kt`, `CrashReports.kt` | журнал плеера, подробный журнал «Suslik Debug» (`-Pdiag`), отчёты о сбоях |
| `SingleInstance.kt`, `AppExit.kt`, `ExitFlight.kt`, `TitleBar.kt`, `WindowMode.kt` | один экземпляр, чистый выход, цвет заголовка, полный экран без рамки и мини-плеер через Win32 |

### Плеер Windows — главное

- **Видео и панели в одной сцене.** mpv рисует кадры (`vo=libmpv`, render API) в OpenGL-контексте, которым Skiko рисует окно,
  прямо в текстуру Skia; видео — слой Compose, панели — слои того же `Box`. Отдельных окон-оверлеев нет. Ловушки общего
  GL-контекста (освобождать render context до закрытия окна, ждать кадр без видео при закрытии) — `docs/DESKTOP.md` §4.1.
- **Полный экран и мини-плеер** — через Win32 (`WindowMode`), не через `WindowPlacement.Fullscreen` (`docs/DESKTOP.md` §7).
- **Слабая сеть: «копим запас».** На сети чуть быстрее фильма прокси держит фильм, пока не накопится ~30 с (`BurstProxy.hold`,
  правила — общий `CatchUp`, одинаково с Android).
- **Сторож** (`PlaybackSupervisor`) получает статистику из `MpvEngine.supervisorStats()` и при зависании перезапускает вывод
  видео без переоткрытия файла (`restartVideoOutput`).
- **Заставки и титры** — `shared/.../MarkersDetector.kt` + `/api/markers` (`docs/MARKERS.md`); «Следующая/Предыдущая серия», очередь — весь сериал.

Сборка: `desktop/build.gradle.kts` (там `appVersion`), `./gradlew packageMsi`, отладочная — `./gradlew packageMsi -Pdiag`
(подробно `desktop/DEBUG-BUILD.md`); установщик `desktop/setup` (C#). «Что нового»: `desktop/src/main/resources/changelog.json`.

---

## 8. Сборка и выпуск (GitHub Actions)

| Workflow | Когда запускается | Что делает |
|---|---|---|
| `shared.yml` | пуш с изменениями в `shared/` | тесты общего модуля |
| `android.yml` | только вручную | собирает APK (отладочный — с `debug=true`); с `publish=true` выкладывает на сайт в канал `beta` или `stable` |
| `desktop.yml` | только вручную | собирает MSI и установщик `Suslik Media Setup-<V>.exe`; выкладывает так же |
| `setup-preview.yml` | пуш в `desktop/setup/` | проверочная сборка установщика |
| `media3-ffmpeg.yml` | пуш в `android/ffmpeg/` (не `main`), или вручную | сборка AAR декодера ffmpeg для Media3 |
| `promote.yml` | вручную | «выпустить всем»: версия из беты становится релизом |
| `update-status.yml` | вручную | проверка: какие версии сайт видит в каналах |
| `changelog.yml` | пуш в `main` с изменением `changelog.json` | сразу отправляет «Что нового» на сайт |
| `website.yml` | пуш в `website/` | только проверка синтаксиса (на сервер ничего не ставит) |
| `reports.yml` | вручную | показывает последние отчёты о сбоях и журналы, которые прислали приложения |

**Каналы.** Обычный путь — бета (видят только те, у кого введён код беты) → «выпускай всем» (`promote.yml`).
Приложения сами спрашивают сервер `/api/app/update` и предлагают обновиться.

**Версия** — три числа `2.x.y`: Android — `versionCode`/`versionName`, Windows — `appVersion`. При выпуске:
поднять версию, добавить запись в оба `changelog.json` (новая сверху; Tizen — `tizen/js/changelog.js`), пуш, запустить сборку с публикацией.
Адрес сервера сборки берут из секрета `SITE_URL` (домен и, если нужно, путь; переезд — `docs/MIGRATION.md`).

---

## 9. Отладка

- **Отладочные сборки «Suslik Debug»** — отдельные приложения рядом с обычными, со своим входом и подробным журналом.
  - Android: журнал `Android/data/ru.suslikovanora.media.diag/files/diag/player.log`, метка 📍 в плеере.
  - Windows: журнал `%APPDATA%\SuslikMediaDebug\player.log`, метка — F8 в плеере, архив журнала — в настройках.
- **Теги журнала:** `[vlc]` (Android) / `[mpv]` (ПК) и `[proxy]` (раз в 5 с: что показывает плеер и сколько запаса впереди), `[sys]` (батарея,
  процессор, сеть), `[http]` (каждый запрос), `[net]` (смена сети), `[mark]` (метка), `[ts]` (Blu-ray).
  Читать удобно от нужной метки назад.
- **Обычные приложения** тоже пишут журнал (поменьше) и присылают на сервер отчёты о сбоях.

---

## 10. «Хочу поменять…» — куда смотреть

| Задача | Где |
|---|---|
| размер запаса, как часто качать, поведение на плохой сети | `shared/.../BurstProxy.kt`, пресеты — `PlaybackPresets.kt` |
| когда плеер сам что-то чинит (застывшая картинка, нет звука) | `shared/.../PlaybackSupervisor.kt` |
| как раздача превращается в фильм/сериал, серии, субтитры | `shared/.../Model.kt` |
| экран и кнопки плеера на телефоне | `android/.../PlayerActivity.kt`, панель над фильмом — `player/PlayerOverlay.kt` (иконки — `PlayerIcons.kt`), под фильмом — `PlayerPanel.kt`, показ/скрытие и блокировка — `player/PlayerControlsOverlay.kt` |
| жесты в плеере на телефоне (перемотка, зум, яркость) | `android/.../player/PlayerGestures.kt` |
| звук, кнопки гарнитуры и Bluetooth в плеере | `android/.../player/PlayerAudioSession.kt` |
| звуковые дорожки и субтитры (включая внешние) | `android/.../player/PlayerTrackSelector.kt` |
| «картинка в картинке» (PiP) на телефоне | `android/.../player/PlayerPipController.kt` |
| очередь серий и автопереход | `android/.../player/PlayerPlaylistQueue.kt` |
| сохранение прогресса просмотра фильма/серии | `android/.../player/PlayerWatchProgress.kt` |
| телеметрия, кодеки и fps дисплея на телефоне | `android/.../player/PlayerDiagnostics.kt` |
| экран и клавиши плеера на ПК | `desktop/.../player/PlayerView.kt` |
| лист «Смотреть» (зеркало/напрямую, продолжить) | Android `library/PlaySheet.kt`, ПК — `LibraryView.kt` |
| медиатека на телефоне / ПК | `library/LibraryScreen.kt` + `LibraryStore.kt` / `LibraryView.kt` + `Library.kt` |
| поиск торрентов (трекеры) | сервер: `website/search.js`, `search-ru.js`; экраны — `SearchScreen.kt` / `SearchView.kt` |
| вход, сессии, пароль | `website/src/routes/auth.js`, `src/middleware/auth.js` + `LoginScreen.kt` / вход в `LibraryView.kt` |
| ссылки зеркала, подпись, срок | `website/src/routes/mirror.js`, `src/services/mirror.js`; nginx — `NGINX-MIRROR.md` |
| сам сайт | `website/public/index.html` (+ `redesign.css/js`) |
| обновления и каналы | сервер `website/src/routes/updates.js`, приложения `Updater.kt`, workflows |
| тексты «Что нового» | `android/app/src/main/assets/changelog.json`, `desktop/src/main/resources/changelog.json`, `tizen/js/changelog.js` |
| совместный просмотр | `shared/.../party/`, ПК `desktop/.../party/`, Android `party/Party.kt`, сервер `website/src/routes/rooms.js`, Tizen `js/party*.js` |
| адрес сервера, переезд | `shared/.../SiteAddress.kt`, секрет `SITE_URL`, сервер `website/src/utils/helpers.js` (`basePath`), `docs/MIGRATION.md` |
| плеер в окне громкости Windows и клавиши мультимедиа на ПК | `desktop/.../player/MediaControls.kt`, подключение — `PlayerView.kt` (см. `docs/DESKTOP.md` §10) |

---

## 11. Правила проекта (кратко)

- Новые версии — сначала в бету, всем — по вашей команде.
- Зеркало не заменять прямыми ссылками AllDebrid.
- Секреты (`.env`, ключи подписи APK, токены, коды беты, cookie) не попадают в репозиторий.
- `website/` и сервер меняются только с вашего разрешения.
---

## 12. Подсистема локализации (i18n)

Все платформы проекта выносят пользовательские строки в JSON-файлы локализации:
- **Desktop**: `desktop/src/main/resources/strings/ru.json`, доступ через функцию `tr(key, ...)` (`Strings.kt`).
- **Android**: `android/app/src/main/assets/strings/ru.json`, доступ через `tr(key, ...)` (`Strings.kt`).
- **Samsung Smart TV (Tizen)**: `tizen/locales/ru.json`, модуль `tizen/js/i18n.js` (`tr()` и декларативные атрибуты `data-i18n`).
- **Веб-интерфейс**: `website/public/locales/ru.json`, модуль `website/public/i18n.js` (`window.tr`, декларативные атрибуты `data-i18n`).

Строки ориентированы на пользователя (понятные объяснения без технического жаргона) и не содержат прямых упоминаний сторонних сервисов (AllDebrid).
