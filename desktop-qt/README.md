# Suslik Media для Windows (C++/Qt)

ПК-версия Suslik Media. С 3.0.0 заменила прежнюю Kotlin-версию (Compose Desktop, удалена из репозитория 03.11).
Подробно об устройстве, плеере, установщике и отладке — [docs/DESKTOP.md](../docs/DESKTOP.md).

| Папка | Что |
|---|---|
| `core/` | Ядро без интерфейса (только Qt Core/Network): запас `BurstProxy`, медиатека, вход, синхронизация, комнаты, поиск, трекеры, адрес сервера… Перенос ядра Android `android/core/` и сетевой части ПК |
| `app/` | Программа на Qt Quick (QML): медиатека, поиск, «Вместе», плеер на libmpv |
| `resources/` | Шрифты, значки, `icon.ico`, строки `strings/ru.json`, «Что нового» `changelog.json`, версия libmpv `libmpv.properties` |
| `installer/` | MSI (WiX 3) и `setup/` — стилизованный установщик и окно удаления (WPF) |
| `tests/` | Тесты ядра (Qt Test) — те же случаи, что в `android/core/src/test` |
| `third_party/mpv/` | Заголовки libmpv |

## Сборка

Нужны CMake 3.21+, Qt 6.5+ (на Linux для тестов хватает 6.4 из apt), Ninja.

```
cmake -S desktop-qt -B build -G Ninja -DSM_SITE_URL=https://домен[/путь]
cmake --build build
ctest --test-dir build --output-on-failure
```

Адрес сервера в репозиторий не пишем: `SITE_URL` из окружения или `-DSM_SITE_URL`. Для отладки его можно переопределить переменной `SUSLIK_SITE` при запуске.
Только тесты, без Qt Quick: `-DSM_BUILD_APP=OFF`. Отладочная «Suslik Debug Qt»: `-DSM_DIAG=ON`. Версия — `project(SuslikMediaQt VERSION …)` в `CMakeLists.txt`.

CI — `.github/workflows/desktop-qt.yml`: тесты на каждый пуш в `desktop-qt/`, сборка для Windows вручную: папка программы `suslik-media-qt-windows`, установщик `suslik-media-qt-msi`,
с `debug` — отладочный `suslik-media-qt-debug-msi`. MSI собирает `installer/build-msi.sh` (WiX 3).
Файл для людей — `Suslik Media Setup-<V>.exe` (артефакт `suslik-media-qt-setup`): мастер с MSI внутри; удаляет Kotlin-версию, если она стоит
(её данные остаются, программа переносит их при первом запуске), ставит в `%LOCALAPPDATA%\Programs\Suslik Media Qt`,
качает видеодвижок в `%APPDATA%\SuslikMediaQt\mpv`, кладёт рядом с программой `SuslikUninstall.exe` (окно удаления с очисткой данных).

Выпуск: `gh api -X POST repos/normikq/suslik-media/actions/workflows/desktop-qt.yml/dispatches -f ref=main -f 'inputs[publish]=true' -f 'inputs[channel]=beta'` —
на сайт идёт только этот exe (платформа `desktop`) и «Что нового»; всем — `promote.yml` с `desktop`. Снимки окон мастера — `setup-preview.yml` (артефакт `setup-preview-qt`).
