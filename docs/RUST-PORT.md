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
2. **Модель и разбор.** `Model.kt` (медиатека, слияние версий и сериалов), `search/` (разбор и фильтр раздач),
   `LibraryKeep`, `MarkersDetector`, `party/PartyProtocol` (разбор SSE), `auth/`, `sync/` — чистая логика с тестами.
3. **Сеть и хранилище.** HTTP-клиент (`ureq`/`reqwest` на rustls, HTTP/1.1), повтор на новом соединении как в
   `Api.call`, cookie сеанса только для своего сервера (`site_address::is_own`), настройки и прогресс в
   `%APPDATA%\SuslikMedia` (совместимо с Kotlin-версией, чтобы переход не терял прогресс).
4. **Вход и медиатека.** Вход по коду устройства, боковая панель, сетка медиатеки с постерами, поиск, добавление.
5. **`BurstProxy`.** Локальный прокси 127.0.0.1 с окнами-запасом (`tokio`), `PlaybackSupervisor`, `TsClock`,
   `CatchUp` — с переносом `BurstProxyTest` целиком. Самая рискованная часть: поведение должно совпасть до мелочей.
6. **Плеер.** mpv render API в GL egui, панели и горячие клавиши (`docs/DESKTOP.md` §5), озвучки и субтитры,
   заставки и титры, мини-плеер и полноэкранный режим, SMTC, запрет сна (`DisplayAwake`).
7. **«Вместе» и Discord.** `PartySyncEngine`, комнаты, активность Discord (IPC).
8. **Установщик и обновление.** MSI (WiX через `cargo-wix`) с тем же upgradeUuid, самообновление по каналам,
   отладочная сборка «Suslik Debug» с журналом `player.log` и теми же тегами.
9. **Переход.** Бета Rust-версии владельцу рядом с Kotlin-версией → «выпускай всем» → `desktop/` в архив.

## Сверено с Kotlin

`url_codec` кодирует и раскодирует строго как `java.net.URLEncoder`/`URLDecoder`: от этого зависят ключ файла
в запасе и путь продления подписи зеркала.
