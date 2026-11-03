# Suslik Media — приложение для Windows (`desktop-qt/`)

ПК-версия на C++/Qt: медиатека, поиск раздач, плеер на libmpv, «Вместе», активность в Discord, установщик и самообновление.
С версии 3.0.0 (01.11.2026) она заменила прежнюю Kotlin-версию на Compose Desktop (`desktop/`, удалена из репозитория 03.11;
её история — `docs/HISTORY.md` и git). Вид и функции перенесены один в один.

---

## 1. Стек

- **C++20, Qt 6** (QML / Qt Quick, Qt Network, Qt WebSockets, Qt ShaderTools), CMake 3.21+ и Ninja, MSVC. Программа — только Windows.
- **Видео:** `libmpv-2.dll` (x86_64), загружается при запуске фильма; кадры рисуются в сцене QML через render API mpv (OpenGL).
- **Ядро** (`core/`) — без интерфейса, только Qt Core и Qt Network: собирается и тестируется и на Linux (CI).
- **Установщик:** MSI (WiX 3) и мастер установки/удаления на WPF (.NET Framework 4.7.2).

---

## 2. Структура

```
desktop-qt/
├── CMakeLists.txt        проект и версия: project(SuslikMediaQt VERSION x.y.z); SM_BUILD_APP, SM_BUILD_TESTS, SM_SITE_URL
├── core/                 ядро (библиотека suslik_core): include/suslik/core/*.h, src/*.cpp
├── app/                  программа SuslikMediaQt.exe
│   ├── main.cpp          запуск, адрес сервера, проверка вёрстки (SM_SHOT)
│   ├── qml/              экраны и компоненты (Main.qml, Theme.qml, LibraryView, PlayerView, SearchView, TogetherView…)
│   ├── src/              модели для QML: Library, LibraryPage, PlayModel, SearchModel, AddModel, AudioAttach, Settings, Updater…
│   │   ├── player/       PlayerModel, MpvEngine, MpvVideo, Mpv, MpvInstall, Reserve, WindowMode, MediaControls, MediaSession,
│   │   │                 DisplayAwake, DiscordRpc, PlayerLog
│   │   ├── party/        Party (комната, REST + SSE), PartySync (плеер идёт за комнатой)
│   │   └── trackers/     RuTracker с ПК: BrowserBridge (фоновый браузер), RuTrackerClient, TrackerSettingsModel
│   ├── shaders/          скруглённые углы слоёв (.frag → .qsb)
│   └── win/app.rc.in     значок и версия в .exe
├── resources/            шрифты (лицензия — FONTS-LICENSE-OFL.txt), значки, icon.ico, strings/ru.json,
│                         changelog.json («Что нового»), libmpv.properties (версия и SHA-256 libmpv)
├── installer/            SuslikMediaQt.wxs + build-msi.sh (MSI), setup/ — мастер установки и окно удаления (WPF)
├── tests/                Qt Test: тесты ядра (ctest)
└── third_party/mpv/      заголовки libmpv (client.h, render.h, render_gl.h)
```

**Ядро (`core/`).** Перенос ядра Android (`android/core/`, Kotlin) на C++ с теми же правилами и тестами, плюс сетевая часть ПК:

| Модуль | Что делает |
|---|---|
| `BurstProxy`, `HttpUpstream`, `CatchUp` | запас фильма на диске: локальный HTTP-сервер 127.0.0.1, окна-кольца, рывки, «копим запас» (как на Android, см. `docs/ARCHITECTURE.md` §5) |
| `PlaybackSupervisor`, `MarkersDetector`, `PlaybackPresets` | сторож воспроизведения; заставки, титры и умные субтитры; варианты настроек |
| `Model`, `LibraryKeep`, `TorrentInspector`, `CustomAudio` | медиатека (раздачи, сериалы, слияние версий), что хранить после обновления, проверка .torrent/magnet, внешние озвучки |
| `ApiClient`, `Auth`, `CloudSync`, `WatchSync`, `Store` | запросы к серверу (cookie и токен — только своему), вход и код устройства, прогресс по аккаунту, файлы настроек `.properties` с `.bak` |
| `Party`, `Search`, `Trackers` | протокол комнат и `PartySyncEngine`; разбор и фильтр раздач; RuTracker (страницы, cookie, cp1251) |
| `SiteAddress`, `MirrorLink`, `Versions`, `Changelog`, `Strings`, `TimeText`, `UrlCoding` | адрес сервера, подпись адреса зеркала, версии и каналы, «Что нового», строки `tx()`, время |

---

## 3. Сборка и запуск

```
cmake -S desktop-qt -B build -G Ninja -DSM_SITE_URL=https://домен[/путь]
cmake --build build
ctest --test-dir build --output-on-failure
```

- Нужны CMake 3.21+, Qt 6.5+ (для тестов на Linux хватает 6.4 из apt), Ninja; для Windows — MSVC и Qt 6.8 (как в CI).
- Только ядро и тесты, без Qt Quick: `-DSM_BUILD_APP=OFF`.
- Адрес сервера в репозиторий не пишем: `SITE_URL` из окружения или `-DSM_SITE_URL`; при запуске его можно подменить `SUSLIK_SITE`.
- Отладочная сборка «Suslik Debug Qt»: `-DSM_DIAG=ON` (см. §9).

**CI** — `.github/workflows/desktop-qt.yml`: на пуш в `desktop-qt/` — сборка и тесты ядра на Linux; сборка для Windows — вручную
(Actions → Desktop Qt → Run workflow). Артефакты: папка программы `suslik-media-qt-windows`, `suslik-media-qt-msi`, установщик
`suslik-media-qt-setup`, снимок окна при запуске `suslik-media-qt-start-shot`, с `debug` — `suslik-media-qt-debug-msi`.
`wintests` — только тесты ядра на Windows по одной функции (поиск зависаний).

---

## 4. Данные на диске

| Что | Где |
|---|---|
| Настройки, вход, прогресс, журнал `player.log` (больше 4 МБ → `player.old.log`) | `%APPDATA%\SuslikMediaQt` (отладочная — `SuslikMediaQtDebug`; `SUSLIK_APPDIR` подменяет) |
| Видеодвижок `libmpv-2.dll` | рядом с программой или `%APPDATA%\SuslikMediaQt\mpv` (`SUSLIK_MPV_DIR` — ещё одна папка) |
| Запас фильма | `%TEMP%\SuslikMediaQt-stream` |
| Скачанное обновление | `%TEMP%\SuslikMediaQt-update` |
| Программа | `%LOCALAPPDATA%\Programs\Suslik Media Qt` (отладочная — `Suslik Debug Qt`) |

При первом запуске программа переносит вход, прогресс, настройки и трекеры прежней Kotlin-версии из `%APPDATA%\SuslikMedia`
(`AppDir::migrateFromKotlin`); старая папка не удаляется.

---

## 5. Плеер

- **Видео в сцене QML.** `MpvVideo` — `QQuickFramebufferObject`: mpv рисует кадр (render API, OpenGL) в FBO Qt Quick, панели плеера — обычные
  слои QML поверх. Без `ADVANCED_CONTROL` (с ним — взаимная блокировка потоков Qt и mpv). Перед вызовами mpv состояние GL сбрасывается.
- **`MpvEngine`** — ядро mpv: события в главном потоке, дорожки, перемотка, громкость, скорость, задержки, ночной режим
  (`af=lavfi=[acompressor…]`), режим 21:9 (`panscan`), сводка для журнала. `SUSLIK_MPV_HWDEC` меняет `hwdec`, `SUSLIK_MPV_LOG` пишет `mpv.log`.
- **`PlayerModel`** — плеер без вёрстки: открытие через запас `BurstProxy` (`Reserve`), очередь серий (весь сериал), «Предыдущая/Следующая»,
  прогресс в медиатеке и по аккаунту, озвучки и субтитры (свои и с сервера), заставки и титры, сторож воспроизведения, «Вместе».
- **libmpv** не входит ни в программу, ни в MSI: её кладёт установщик, а если её нет — `MpvInstall` докачивает с сервера
  (`path` в `resources/libmpv.properties`, сейчас `/media/mpv/libmpv-2.dll`) и сверяет SHA-256. Новая версия libmpv — выложить файл на сервер
  и поменять `version` и `dllSha256`.

### Горячие клавиши (`qml/PlayerView.qml`)

Клавиши работают в любой раскладке (по скан-коду).

| Клавиша | Действие |
|---|---|
| `Space` / `K` | пауза (`Space` при отсчёте — следующая серия сразу) |
| `←` / `→`, с `Shift` | −10 / +10 с, −60 / +60 с |
| `↑` / `↓` | громкость ±5 % |
| `M` | без звука |
| `F` / `F11` / `Enter` | полный экран |
| `Esc` / `Backspace` | выйти из полного экрана / мини-плеера, закрыть плеер, отменить отсчёт |
| `N` / `P` | следующая / предыдущая серия |
| `S` | пропустить заставку |
| `[` / `]` | задержка звука ∓100 мс, с `Shift` — субтитров; `\` и `|` — сбросить |
| `<` / `>` (`,` / `.`) | скорость |
| `PageUp` / `PageDown` | глава назад / вперёд |
| `I` | сведения о потоке |
| `C` | режим 21:9 |
| `O` | мини-плеер |
| `T` | поверх всех окон |
| `F8` | метка 📍 в журнале (только «Suslik Debug Qt») |

---

## 6. Окно и Windows

- **Полный экран и мини-плеер** (`WindowMode`) — средствами Qt: полный экран — окно без рамки во весь монитор и на 1 px выше
  (так Windows не включает эксклюзивный DirectFlip); мини-плеер тянется за любой край с пропорциями фильма, «поверх окон».
- **Заголовок окна** (`TitleBar`) — в цветах приложения (Windows 11: полоса, текст, рамка; Windows 10 — тёмный режим).
- **Системные медиа-кнопки** (`MediaControls`, C++/WinRT, SMTC): плеер виден в окне громкости и Media Flyout, работают клавиши мультимедиа.
- **Экран не гаснет** во время фильма (`DisplayAwake`, запрос питания «Suslik Media: воспроизведение видео»).
- `MediaSession` связывает состояние плеера с медиа-кнопками, запретом сна и Discord.
- **Одна копия** (`SingleInstance`): второй запуск поднимает окно первой.
- **Ссылки `suslik://room/<код>`** (`UrlScheme`, `HKCU\Software\Classes\suslik`) открывают комнату «Вместе» — их даёт Discord Activity
  (`docs/DISCORD-ACTIVITY.md`). Отладочная сборка ссылку себе не забирает.

---

## 7. Discord и «Вместе»

- **Вход:** «Войти через Discord» — код устройства (`/api/auth/device`), подтверждение в браузере, токен устройства в заголовке
  `X-Device-Token`; запасной — по паролю. Cookie, токен и бета-код уходят только на свой сервер (`ApiClient`, `SiteAddress`).
- **«Вместе»** (`party/`, `qml/TogetherView.qml`): создать комнату или войти по коду. Ведущий ставит фильм и серию, его пауза и перемотка — у всех;
  гость сразу попадает в плеер на файле и месте ведущего (`qml/PartyFollow.qml`), у гостя закрыта медиатека, таймлайн — полоса «● Вместе».
  Синхронизация — `core::PartySyncEngine` (тик 400 мс; то же правило, что на Android и ТВ): скорость комнаты, монотонные часы,
  мелкое расхождение — скоростью 0,95/1,05×, больше 2 с — перемоткой; комната ждёт, пока у всех загрузится.
  Ведущий закрыл фильм — у гостей закрывается плеер (мини-плеер сворачивает окно). Журнал — строки `[party]`.
  Протокол и сервер — `docs/DISCORD.md`.
- **Активность «Смотрит»** (`DiscordRpc`): локальный канал `\\.\pipe\discord-ipc-N`; ID приложения приходит от сервера
  (`discord_client_id` в `/api/session`). Переключатель — в настройках.

---

## 8. Установщик, обновления, выпуск

- **MSI** (`installer/build-msi.sh`, `SuslikMediaQt.wxs`, WiX 3): для текущего пользователя без прав администратора, ярлыки в «Пуске» и на
  рабочем столе, новая версия ставится поверх (MajorUpgrade). UpgradeCode: обычная `{BFBB56B9-7E7F-4AF8-BA0B-C1BA1A054E70}`,
  отладочная `{F79D44DC-CEC2-4619-824F-32180E6B7698}`. libmpv в MSI не кладётся.
- **Мастер** (`installer/setup`, WPF): `Suslik Media Setup-<V>.exe` — MSI внутри (`-p:MsiPath`), адрес сервера — `-p:SiteUrl`;
  качает libmpv, ставит программу. Он же `SuslikUninstall.exe` (без MSI) — окно удаления с галочкой «удалить данные и кэш».
  Убирает прежнюю Kotlin-версию (UpgradeCode `{6F1D2B9E-4C7A-4F3E-9A51-2D8C5E7B3A10}`, `installer/setup/Work.cs`), её данные оставляет.
  Снимки окон мастера — `setup-preview.yml` (артефакт `setup-preview-qt`).
- **Обновления** (`app/src/Updater.cpp`): программа спрашивает сервер `platform=desktop`, скачивает `Suslik Media Setup-<V>.exe` и запускает его с `/update`.
- **Выпуск:** версия — `project(SuslikMediaQt VERSION …)` в `desktop-qt/CMakeLists.txt`, запись сверху в `resources/changelog.json`, пуш,
  затем `desktop-qt.yml` с `publish` и `channel=beta`:
  `gh api -X POST repos/normikq/suslik-media/actions/workflows/desktop-qt.yml/dispatches -f ref=main -f 'inputs[publish]=true' -f 'inputs[channel]=beta'`.
  На сайт уходит только установщик exe (платформа `desktop`) и «Что нового». Всем — `promote.yml` с `desktop`; проверка — `update-status.yml`.

---

## 9. Отладка

- **«Suslik Debug Qt»** (`-DSM_DIAG=ON`, в CI — `debug=true`, артефакт `suslik-media-qt-debug-msi`): ставится рядом с обычной, своя папка
  данных `%APPDATA%\SuslikMediaQtDebug`, подробный журнал mpv, `F8` — метка `[mark]`. Без самообновления, на сайт не выкладывается.
- **Журнал:** `player.log` в папке данных; теги `[mpv]` (сводка потока), `[proxy]` (запас), `[party]`, `[mark]` (F8).
- **Проверка вёрстки без мыши** (переменные окружения):
  `SM_SHOT=<файл.png>` (+ `SM_SHOT_MS`) — снимок окна и выход;
  `SM_OPEN=play:<id>:<путь> | versions:<id>,<id> | settings | add[:film|audio:<magnet>] | search:<запрос> | discover[:запрос] | trackers[:1] | together[:create|<код>] | video:<файл>`;
  `SM_PLAYER_UI=bars|audio|spu|more|info` — меню плеера открыто.
- Журнал ПК владельца из консоли Claude читается только через `//localhost/C$/Users/nanar/AppData/Roaming/...`.
