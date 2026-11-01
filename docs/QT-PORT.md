# Перенос ПК-версии на C++/Qt (`desktop-qt/`)

Решено 31.10 с владельцем. Зачем: быстрее запуск и меньше памяти/CPU, чем у Compose Desktop на JVM, и без костылей нынешней архитектуры
(mpv в GL-контексте Skiko, JNA к Win32, маски окна). Интерфейс и функции — как в `desktop/` сейчас, вид один в один.

- **Стек:** C++20, Qt 6 (QML / Qt Quick), libmpv, CMake + Ninja, MSVC. Только Windows.
- **Рядом со старой:** `desktop/` не трогаем, новая ставится отдельно (свой upgradeUuid). Старую убираем только по команде владельца.
  **01.11 — команда дана:** с 3.0.0 Qt-версия заменяет Kotlin-версию (см. «Выпуск 3.0.0»).
- **Вместе и поиск по трекерам** — перенесены 01.11 (см. «Ход работ»).
- **Переносим:** Discord-активность («Смотрит…»), всё остальное из `desktop/`.

## Шаги

После каждого шага — PR.

1. **Каркас и ядро на C++** (`desktop-qt/core`). Перенос из `shared/` того, без чего ПК не работает, вместе с тестами (Qt Test), сборка в CI.
   Порядок: `SiteAddress`, `MirrorLink`, `Versions`/`UpdateChannel`, `Changelog`, `clockText` (готово) →
   `Model` (медиатека, сериалы, слияние версий) → `auth/` (сессия, код устройства) и HTTP-клиент на `QNetworkAccessManager` →
   `sync/WatchSync` → `MarkersDetector`, `CatchUp`, `PlaybackPresets`, `LibraryKeep`, `audio/CustomAudio` →
   `BurstProxy` (заново на Qt-сокетах и потоках, тесты из `BurstProxyTest`) → `PlaybackSupervisor`, `TsClock`/`TsPlayback`.
   `party/` и `search/` перенесены позже (01.11), вместе с RuTracker с ПК.
2. **Интерфейс без плеера** (`desktop-qt/app`). Токены «жидкого стекла» из `ui/Theme.kt` и компоненты из `ui/Controls.kt` в QML,
   окно со своим заголовком, боковая панель, медиатека, добавление, «Смотреть», «Какую версию включить?», ИИ-название, озвучки,
   настройки, «Что нового», обновления, одна копия приложения. Сверка по скриншотам с `desktop/`, экран за экраном.
3. **Плеер.** mpv рисует в сцене QML (`QQuickFramebufferObject` + render API), панели и оверлеи, горячие клавиши (`docs/DESKTOP.md` §5),
   полноэкранный режим и мини-плеер (§7), DRC (§8), SMTC (§10), запрет сна (§11), озвучки и субтитры, очередь сериала, заставки и титры,
   Discord-активность (§12.3).
4. **Установщик и бета.** MSI рядом со старым, самообновление, отладочная сборка с журналом, выпуск в бета-канал,
   замеры памяти, CPU и времени запуска против `desktop/`.

## Сборка

`desktop-qt/README.md`. CI — `.github/workflows/desktop-qt.yml`: тесты ядра на пуш в `desktop-qt/`, сборка для Windows вручную.

## Ход работ

- 31.10 — каркас: CMake, ядро (`SiteAddress`, `MirrorLink`, `Versions`, `Changelog`, `clockText`) с тестами, окно-заглушка на QML, CI.
- 31.10 — `Model` (медиатека, сериалы, сборники, версии, субтитры и озвучки) с тестами из `ModelTest` и `SeriesGroupingTest`.
- 31.10 — вход и код устройства (`Auth`), синхронизация прогресса и дорожек (`WatchSync`) с тестами.
- 31.10 — сетевая часть ПК: `ApiClient` (cookie и токен только своему серверу, ETag, тексты ошибок), `Store` (файлы `.properties` того же формата, что у Kotlin-версии, с `.bak`), `Strings` (`strings/ru.json`, функция `tx`), `CloudSync`. Тесты — с локальным HTTP-сервером.
- 31.10 — заставки и титры, умные субтитры (`MarkersDetector`), внешние озвучки (`CustomAudio`), `PlaybackPresets`, `LibraryKeep`, проверка .torrent и магнетов (`TorrentInspector`) с тестами.
- 31.10 — `PlaybackSupervisor` (сторож воспроизведения: застывшая картинка, тишина, стоящие часы, бюджеты) с тестами из `CoreTest` и `PlaybackSupervisorTest`. `TsClock`/`TsPlayback` на ПК не нужны (только Android).
- 31.10 — `BurstProxy` (запас на диске) и `CatchUp` с тестами из `BurstProxyTest` (24 теста против настоящего HTTP-источника). Отличия от Kotlin-версии:
  прокси — обычный объект (не синглтон), его держит приложение; загрузка — `HttpUpstream` на `QNetworkAccessManager`, свой в каждом потоке
  загрузчика (рывки одного окна идут по одному соединению, разные окна — по разным); ожидание сокетов — событиями Qt (`core/src/QtWait.h`), без
  `waitForReadyRead`, который на Windows ненадёжен; отмена запроса — флагом, замечается за 0,1 с. `readRange` идёт в отдельном потоке.
- 31.10 — шаг 2, интерфейс без плеера (`desktop-qt/app`): окно со своим заголовком, боковая панель, вход (пароль и код устройства), медиатека
  (плитки, список, «Продолжить просмотр», страницы, меню плитки), «Смотреть» (фильм, сериал, сборник), «Какую версию включить?», ответы ИИ,
  «Добавить» (раздачи и озвучки), настройки, «Что нового», кнопка обновления. Данные старой версии (вход, прогресс, настройки) переносятся
  при первом запуске (`AppDir::migrateFromKotlin`). Своя папка данных `%APPDATA%\SuslikMediaQt`, запас — `%TEMP%\SuslikMediaQt-stream`.
  Проверка вёрстки без мыши: `SM_SHOT`, `SM_OPEN` (`desktop-qt/app/main.cpp`, `qml/Main.qml`).
- 31.10 — шаг 3, плеер (`app/src/player`, `qml/PlayerView.qml`): libmpv подгружается сама (рядом с программой или в `%APPDATA%\SuslikMediaQt\mpv`,
  нет — докачивается с сервера с проверкой SHA-256, `MpvInstall`), кадры mpv рисуются в сцене QML (`MpvVideo`, OpenGL, без
  `ADVANCED_CONTROL` — с ним взаимная блокировка потоков Qt и mpv). `PlayerModel` — перенос `PlayerView.kt`: запас `BurstProxy`, прогресс,
  очередь серий, озвучки и субтитры (свои и с сервера), задержки, ночной режим, скорость, заставки и титры, сторож воспроизведения.
  Экран: верхняя и нижняя панели, перемотка, громкость, меню дорожек и «Ещё», сведения о потоке, карточка следующей серии, горячие клавиши.
  Только Windows: полный экран (окно на 1 px выше монитора) и мини-плеер (`WindowMode`), SMTC (`MediaControls`), запрет сна (`DisplayAwake`),
  Discord (`DiscordRpc`). Журнал плеера — `player.log` в папке данных. Проверка без мыши: `SM_OPEN=video:<файл>`, `SM_PLAYER_UI=bars|audio|spu|more|info`.
- 01.11 — поиск раздач и «Вместе». Ядро: `core/Search` (разбор названий раздач, фильтры, наборы, сортировка, очистка запроса),
  `core/Party` (комната, протокол `/api/rooms`, часы, `PartySyncEngine`), `core/Trackers` (выбор источников, разбор страниц RuTracker и cookie,
  cp1251) — с тестами `SearchTest`, `PartyTest`, `TrackersTest`. Приложение: экран поиска (`SearchModel`, `qml/SearchView.qml`) — выдача сервера
  сразу, RuTracker с ПК дописывается, значки раздачи, страницы по 25/50, «＋ В библиотеку» с состоянием AllDebrid; окно «Трекеры»
  (`trackers/TrackerSettingsModel`, `qml/TrackerSettingsDialog.qml`); фоновый браузер для RuTracker (`trackers/BrowserBridge`: Edge, Chrome,
  Яндекс и др. по DevTools на скрытом рабочем столе Windows, процесс — в Job Object; нужен модуль Qt WebSockets), прямой HTTP — запасной путь
  (`trackers/RuTrackerClient`). «Вместе»: `party/Party` (сеть, поток событий с переподключением, очередь действий), `party/PartySync` (плеер
  идёт за комнатой, ведущий ставит фильм, гость идёт за серией ведущего, ведущий закрыл фильм — у гостя тоже), страница комнаты
  (`qml/TogetherView.qml`), автооткрытие фильма ведущего у гостя (`qml/PartyFollow.qml`), замки гостя в плеере (перемотка, серии, пауза ведущего,
  полоса «Вместе»), медиатека у гостя закрыта, Discord «Смотрит вместе». Проверка без мыши: `SM_OPEN=search:<запрос>`, `trackers[:1]`,
  `together[:create|<код>]`. На устройствах не проверено.
- 01.11 — «Подбор по настроению» во вкладке поиска (переключатель «По названию / По настроению»): сервер `POST /api/search/ai-discovery`
  (`website/src/services/aidiscovery.js`: Gemini подбирает 6–10 фильмов с причиной, TMDB параллельно даёт постер w342 и рейтинг по
  «Original (год)»), `SearchModel::runAi` и сверка с медиатекой (тот же постер TMDB, название и год ±1, название в имени раздачи),
  карточки `qml/AiDiscoveryCard.qml` («Смотреть» или «Найти раздачу» — обычный поиск по трекерам), быстрые настроения, мерцающие
  заготовки. Постеры подборки — `image://suslik/tmp:…`, только в памяти (на диск не пишутся). Значки вместо эмодзи — в `IconProvider`
  (`sparkle`, `star`, `cup`, `spiral`, `smile`, `planet`, `dice`). В медиатеке — «Что посмотреть?». Проверка без мыши: `SM_OPEN=discover[:запрос]`.

## Выпуск 3.0.0 (замена Kotlin-версии)

- Версия — `project(... VERSION 3.0.0)` в `desktop-qt/CMakeLists.txt` (из неё — программа, MSI и установщик).
- `desktop-qt.yml` с `publish` выкладывает только `Suslik Media Setup-<V>.exe` на платформу `desktop` (как `desktop.yml`) и
  `desktop/src/main/resources/changelog.json`. Сервер не менялся: он оставляет самый новый файл платформы, так что Kotlin-версия (2.6.x)
  видит 3.0.0 как обычное обновление и запускает установщик с `/update`.
- Установщик (`installer/setup/Work.cs`, `Product.OldVersion`) до установки закрывает и удаляет Kotlin-версию по её UpgradeCode
  `{6F1D2B9E-4C7A-4F3E-9A51-2D8C5E7B3A10}`, убирает её запись `SuslikMedia` в «Установленных приложениях» и докачанную libmpv;
  данные `%APPDATA%\SuslikMedia` остаются — из них программа берёт вход, прогресс и настройки (`AppDir::migrateFromKotlin`).
- Программа спрашивает обновления на `platform=desktop` (`app/src/Updater.cpp`). В «Пуске» и «Установленных приложениях» — «Suslik Media».
- `desktop.yml` больше не запускать с `publish`: Kotlin-сборка 2.x старше 3.0.0 и обновлением не станет.

## Для шага 4 (установщик и обновления)

- UpgradeCode: обычная `{BFBB56B9-7E7F-4AF8-BA0B-C1BA1A054E70}`, отладочная `{F79D44DC-CEC2-4619-824F-32180E6B7698}` (`app/src/Updater.cpp`;
  по нему удаляется прежняя копия при откате с беты, старую Kotlin-версию это не трогает).
- ~~Приложение спрашивает `platform=desktop-qt`~~ — с 3.0.0 `platform=desktop` (см. выше).
- MSI (`desktop-qt/installer`, WiX 3, `build-msi.sh`): ставится для текущего пользователя без прав администратора в
  `%LOCALAPPDATA%\Programs\Suslik Media Qt` (отладочная — `Suslik Debug Qt`), ярлыки в «Пуске» и на рабочем столе, новая версия ставится
  поверх старой (MajorUpgrade). Файл — `Suslik Media Qt-<V>.msi`: по имени сервер должен отличать его от установщика старой версии.
- `libmpv-2.dll` в MSI нет: программа докачивает её с сервера в `%APPDATA%\SuslikMediaQt\mpv` (`MpvInstall`, проверка SHA-256).
- «Что нового» пока читает `changelog.json` старой версии (`desktop/src/main/resources`).
