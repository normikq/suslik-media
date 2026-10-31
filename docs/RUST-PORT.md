# Перенос ПК-версии на Rust (`desktop-rs/`)

Цель: заменить `desktop/` (Kotlin, Compose Desktop, JVM ~20 тыс. строк) и общую логику из `shared/` (~5 тыс. строк)
нативным приложением на Rust. Один exe без JVM, меньше памяти и быстрее старт.

Решения (31.10, выбрал владелец):

- **Интерфейс — egui (eframe, бэкенд glow/OpenGL).** mpv рисует в том же GL-контексте через render API
  (`vo=libmpv`), как сейчас `player/MpvVideo.kt` рисует в Skiko. Панели плеера — обычные слои egui поверх видео.
- **mpv** — та же `libmpv-2.dll`, которую уже скачивает установщик (`libmpv.properties`); привязка через `libmpv2`/свой FFI.
- Kotlin-версия остаётся рабочей и выпускается как раньше, пока Rust-версия не догонит её. Правила, которые есть
  в обеих (`shared/` ↔ `suslik-core`), меняем в обеих; тесты переносим вместе с кодом.

## Устройство

| Крейт | Что |
|---|---|
| `crates/suslik-core` | Общая логика без сети и UI (перенос `shared/`): каждый модуль повторяет одноимённый файл Kotlin, с его тестами |
| `crates/suslik-desktop` | Приложение egui, exe `suslik-media` |

Сборка: `cd desktop-rs && SITE_URL=https://… cargo build --release`. Адрес сервера берётся из переменной `SITE_URL`
при сборке (в репозиторий не пишем). Версия — `desktop-rs/Cargo.toml` (`workspace.package.version`).
CI: `.github/workflows/desktop-rs.yml` — fmt, clippy, тесты на Linux и проверочная сборка на Windows; exe-артефакт
только при ручном запуске. Ничего не публикует.

## Этапы

1. ✅ **Каркас.** Рабочая область, `suslik-core`: `SiteAddress`, `MirrorLink`, `Versions`/`UpdateChannel`, `clockText`,
   `Changelog`, `PlaybackPresets`. Окно egui с темой «Проекторная» и «Что нового».
2. ✅ **Модель, поиск, «Вместе».** `model` (`Model.kt`: медиатека, что играть, ответ ИИ, слияние и порядок версий,
   субтитры и озвучки), `search` (`ReleaseParser`, `SearchHit`, `SearchFilter`), `party` (часы и дрейф, протокол комнаты
   и SSE, `PartySyncEngine`). Тесты `ModelTest`, `SeriesGroupingTest`, `ReleaseParserTest`, `SearchFilterTest`,
   `PartyProtocolTest`, `PartyTest`, `PartySyncEngineTest` перенесены целиком (114 тестов в `suslik-core`).
   Осталось из чистой логики: `LibraryKeep`, `MarkersDetector`, `auth/`, `sync/`, `audio/`, `torrent/` — переедут
   вместе с этапами, где они нужны.
3. ✅ **Сеть и хранилище.** `properties` (формат `.properties` Java), `auth` (сеанс, код устройства, «Мои фильмы»,
   перенос `AuthTest`). В приложении: `Store` — те же файлы в `%APPDATA%\SuslikMedia` (`SuslikMediaDebug` у отладочной),
   запись с задержкой 0,4 с через `.tmp` и `.bak`; `Api` на `ureq` (rustls, HTTP/1.1): cookie, `X-Device-Token` и
   `X-Beta-Code` только своему серверу, ETag/304, gzip, ошибки сервера по-русски. Окно на старте проверяет `/api/session`.
   Повтор на новом соединении (`Api.call` Android) у Kotlin-ПК нет — добавим, если понадобится по журналам.
4. ✅ **Вход и медиатека.** Вход по коду устройства (Discord) и по паролю; боковая панель с «Коллекцией»; медиатека
   (`library.rs`, `ui/library_view.rs`): сохранённая копия `library.json`, ETag, поиск по названию (и по русскому
   названию TMDB), сортировка, «Только готовые», страницы по три ряда, слияние версий, избранное и «Мои фильмы»,
   метки Dolby Vision / HDR / DTS, прогресс, чистка за ушедшими фильмами (`library_keep`). Постеры — те же файлы
   `posters\<sha1>.img` и `posters.properties`, 4 потока, в памяти не больше 72. «Поиск раздач» по трекерам сервера с
   фильтрами и «В библиотеку»; «Добавить»: magnet и `.torrent` (кнопка — диалог Windows, перетаскивание), проверка
   «есть ли видео» (`torrent`, перенос `TorrentInspectorTest`). Шрифты Onest и Unbounded из `desktop/`.
   Ещё не перенесено: «Продолжить просмотр», «Определить через ИИ», RuTracker с ПК (Edge), озвучка к фильму,
   настройки трекеров, вид «Список»; нажатие на плитку пока открывает карточку «что будет играть» (плеер — этап 6).
5. ✅ **`BurstProxy`.** `burst_proxy` — построчный перенос `BurstProxy.kt`: окна-кольца на диске, рывки low/high по
   битрейту, короткий разгон, мостики при поиске ключевого кадра, хвост файла в памяти, сторож зависаний, смена и
   потеря сети, экран выключен, обновление подписи (`refresh_url`), `hold` для «копим запас», хранение закрытого
   фильма. Вместо `tokio` — обычные потоки, как у Kotlin (пул потоков Java): у каждого окна `Mutex` с файлом и
   `Condvar` вместо `synchronized`/`wait`, поля, которые читают без замка, — атомарные; порядок замков «источник →
   окно». К зеркалу — свой HTTP/1.1-клиент `http1` с пулом соединений (ограниченные `bytes=a-b` не платят за новый
   TLS); `Cancel` закрывает сокет из другого потока — так сторож обрывает и повисшее рукопожатие. TLS (rustls)
   подключит приложение в этапе 6 через `Connector`. Ещё `playback_supervisor`, `ts_clock` (`TsClock` и `TsPlayback`),
   `catch_up`. Тесты `BurstProxyTest` (все 23, против настоящего HTTP-источника с Range, медленной отдачей, зависанием
   и истёкшей подписью), `PlaybackSupervisorTest`, `TsClockTest` и надзор/«копим запас» из `CoreTest` перенесены
   (185 тестов в `suslik-core`).
6. **Плеер.** mpv render API в GL egui, панели и горячие клавиши (`docs/DESKTOP.md` §5), озвучки и субтитры,
   заставки и титры, мини-плеер и полноэкранный режим, SMTC, запрет сна (`DisplayAwake`).
7. **«Вместе» и Discord.** `PartySyncEngine`, комнаты, активность Discord (IPC).
8. **Установщик и обновление.** MSI (WiX через `cargo-wix`) с тем же upgradeUuid, самообновление по каналам,
   отладочная сборка «Suslik Debug» с журналом `player.log` и теми же тегами.
9. **Переход.** Бета Rust-версии владельцу рядом с Kotlin-версией → «выпускай всем» → `desktop/` в архив.

## Сверено с Kotlin

- `url_codec` кодирует и раскодирует строго как `java.net.URLEncoder`/`URLDecoder`: от этого зависят ключ файла
  в запасе и путь продления подписи зеркала.
- Регулярные выражения переносятся текстом как есть (одни и те же на сайте, Android, ТВ и ПК); `jrx` переводит только
  то, что в Java значит другое: `\d`, `\s`, `\w`, `\b` там только ASCII, в Rust — Unicode. Шаблоны `ReleaseParser`
  с `UNICODE_CHARACTER_CLASS` идут без перевода (`rx!(…, unicode)`). Заглядывания даёт `fancy-regex`.
- JSON читается так же снисходительно, как `org.json` (`json.rs`: число в строке — число, `null` — значение по умолчанию).
- Пороги, заданные длиной строки, считаются в UTF-16, как в Kotlin (`search::kt_len`).
- Хранилище читает и пишет те же `.properties`, что Kotlin-версия (`java.util.Properties`): ISO-8859-1, `\uXXXX`,
  экранированные пробелы и `=`/`:` в ключах. Вход, настройки и прогресс переходят без потерь в обе стороны.
- `Library` пишет те же файлы, что `Library.kt`: `library.json`, `library_etag`, `posters`, `poster_title`, `poster_anim`,
  `media_info`, `last_episode`, `continue_hidden`, `watch` и картинки `posters\<SHA-1 пути>.img`. Чистка после
  обновления удаляет те же ключи (`LibraryKeep`), запас на диске — когда переедет `BurstProxy`.
- `burst_proxy` повторяет Kotlin-версию в числах и решениях (константы, пороги, формулы low/high/разгона, строки
  журнала `reserve: …` и `snapshot()` — те же, чтобы журналы двух версий читались одинаково). Своё: часы монотонные
  (перевод системных часов не трогает сторожей), «нет сети» — по виду ошибки ОС (`ConnectionRefused`,
  `HostUnreachable`, `NetworkUnreachable`, `AddrNotAvailable`; ошибка DNS считается `HostUnreachable`), свободное место
  — `fs4::available_space`.
