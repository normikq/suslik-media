# Кинотеатр Suslik Media в Cyberpunk 2077

V смотрит фильм из медиатеки на экране телевизора в мире игры (ПК, Borderless). Плеер — libmpv, как в ПК-версии,
поэтому кодеки те же. Звук игры на время фильма приглушается (можно отключить). «Вместе» в первый выпуск не входит.

## Устройство

```
Cyberpunk 2077 (D3D12, Borderless)
 ├─ CET, мод SuslikCinema (Lua) ──интерфейс──► VAO (телефон, меню, пульт)
 │      │ файлы ipc/ VAO
 ├─ helper cinema_helper.py (VAO.RegisterHelper)
 │      │ localhost TCP, JSON по строкам
 └─ suslik-cinema.exe (C++/Qt, cinema/host)
        ├─ ядро desktop-qt/core: BurstProxy, вход, медиатека, пресеты, озвучки, заставки, прогресс
        ├─ mpv: MpvEngine + MpvVideo из desktop-qt/app (без копий), кадр в FBO Qt Quick
        ├─ экран: кадр 1920×1080 → четырёхугольник телевизора (гомография, Item.transform) в прозрачном окне
        └─ звук игры: Core Audio, сессии процесса Cyberpunk2077.exe
```

VAO (`docs/bin/.../VsAPIOverlay`, чужой мод) используем только через его публичный API `VAO.*` и только для
интерфейса. Его код не копируем и не распространяем.

### Папки

| Папка | Что |
|---|---|
| `cinema/recon/SuslikCinemaRecon/` | мод CET для разведки: только журнал, инструкция — `cinema/recon/README.md` |
| `cinema/host/` | хост `suslik-cinema.exe`: подключается из `desktop-qt` с `-DSM_CINEMA=ON` |
| `cinema/helper/`, `cinema/mod/SuslikCinema/` | этап 3 (мост с игрой) |

### Хост (каркас, 0.1.0)

- `QuadMath`: прямоугольник кадра → четырёхугольник (`QTransform::squareToQuad` → `QMatrix4x4`; перспектива
  через w, текстура с перспективной коррекцией). Тест — `cinema/host/tests/QuadMathTest.cpp`.
- `ScreenQuad`: углы в долях окна, `screens.json` в папке данных.
- `Overlay`: окно `WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW`, всегда сверху,
  следует за клиентской областью окна игры (раз в 0,5 с). В режиме калибровки принимает мышь.
- `GameAudio`: приглушает только сессии игры, сессии, приглушённые игроком, не трогает. Список приглушённых нами сессий хранится в
  `game-mute.json`. После сбоя хост при запуске возвращает звук (`recoverAfterCrash`).
- `CinemaSettings.cpp`: заглушка `PlaybackSettings::cacheMs()`, чтобы не тянуть настройки всего приложения.
- Папка данных — `%APPDATA%\SuslikCinema` (`SUSLIK_APPDIR`), журнал `player.log` с тегом `[cinema]`.
  libmpv берётся у ПК-версии (`%APPDATA%\SuslikMediaQt\mpv`, `SUSLIK_MPV_DIR`).

Запуск: `suslik-cinema.exe [--file <путь|адрес>] [--no-game] [--no-mute] [--calibrate] [--mute-test]`.
Клавиши: Ctrl+Alt+C — калибровка (тянуть углы, S — сохранить, R — сбросить), Ctrl+Alt+P — пауза,
Ctrl+Alt+H — скрыть, Ctrl+Alt+Q — выход.

Локальная сборка (как ПК-версия):

```
cmake -S desktop-qt -B build-cinema -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_PREFIX_PATH=C:/Qt/6.5.3/msvc2019_64 -DSM_BUILD_APP=OFF -DSM_CINEMA=ON
cmake --build build-cinema --target suslik_cinema QuadMathTest
```

### Протокол хост ↔ helper (этап 3)

localhost TCP, одна JSON-строка на сообщение. Команды: `login_code`, `list`, `play{id, release, audio, sub}`, `pause`,
`seek{pos}`, `volume{v}`, `mute{on}`, `set_rect{corners}`, `calibrate{on}`, `stop`.
События: `state{pos, dur, paused}`, `error{msg}`, `progress{…}`, `mute{state}`.

## Этапы

0. **Разведка** — камера и FOV из CET, углы телевизора, проверка проекции (`cinema/recon`), Core Audio, окно поверх игры.
1. **Хост без игры** — вход по коду, медиатека, пресет, mpv и BurstProxy; матрица кодеков → `cinema/TEST.md`.
2. **Экран в мире** — проекция углов по камере каждый кадр, скрытие вне кадра и за предметами. Без этого MVP не готов.
3. **Мост** — helper, мод CET, телефон VAO, пульт, приглушение звука и его восстановление.
4. **Функции ПК** — релиз по пресету, озвучки, субтитры, прогресс, заставки.
5. **Бета** — сборка в CI вручную, zip мода, список проверенных версий игры и VAO.

## Состояние (08.11)

- Ветка `feature/cyberpunk-cinema`. Готовы мод разведки и каркас хоста (тестовая картинка или `--file`, калибровка,
  приглушение).
- Проверено на ПК владельца при запущенной игре (Borderless, 2560×1440): прозрачное окно хоста видно поверх игры,
  перспективный четырёхугольник рисуется. Как окно ведёт себя с фокусом и кликами в игре, ещё не проверено.
- Не проверено: `--mute-test` с игрой, видео mpv на четырёхугольнике, задержка звука.
- Ждём журнал разведки. По нему выбираем, как ставить экран: проекция по камере из CET каждый кадр (если рамка совпала)
  или ручная калибровка углов для неподвижного вида.

## Открытые вопросы

1. Место: телевизор в квартире V (для MVP: неподвижен, одна калибровка) или отдельный зал.
2. Версии игры и VAO у владельца — для списка совместимости.
3. Лицензия VAO до публичного выпуска.
4. Права на контент при публичной раздаче мода.
5. Задержка: CET отдаёт камеру в `onUpdate`, оверлей рисуется отдельно. Возможное отставание экрана от камеры
   при быстром повороте оценим по записи из разведки.
