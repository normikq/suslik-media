# Suslik Media для Windows на C++/Qt

Новая ПК-версия, растёт рядом с Kotlin-версией (`desktop/`) и заменит её, когда догонит по функциям. План и порядок работ — `docs/QT-PORT.md`.

| Папка | Что |
|---|---|
| `core/` | Ядро без интерфейса, перенос `shared/` (только Qt Core/Network): адрес сервера, зеркало, версии, changelog… |
| `app/` | Приложение на Qt Quick (QML): медиатека, плеер на libmpv |
| `installer/` | MSI (WiX 3) и `setup/` — стилизованный установщик и окно удаления (перенос `desktop/setup`, WPF) |
| `tests/` | Тесты ядра (Qt Test) — те же случаи, что в `shared/src/test` |

## Сборка

Нужны CMake 3.21+, Qt 6.5+ (на Linux для тестов хватает 6.4 из apt), Ninja.

```
cmake -S desktop-qt -B build -G Ninja -DSM_SITE_URL=https://домен[/путь]
cmake --build build
ctest --test-dir build --output-on-failure
```

Адрес сервера в репозиторий не пишем: `SITE_URL` из окружения или `-DSM_SITE_URL`. Для отладки его можно переопределить переменной `SUSLIK_SITE` при запуске.
Только тесты, без Qt Quick: `-DSM_BUILD_APP=OFF`.

CI — `.github/workflows/desktop-qt.yml`: тесты на каждый пуш в `desktop-qt/`, сборка для Windows вручную: папка программы `suslik-media-qt-windows`, установщик `suslik-media-qt-msi`,
с `debug` — отладочный `suslik-media-qt-debug-msi`. MSI собирает `installer/build-msi.sh` (WiX 3).
Файл для людей — `Suslik Media Qt Setup-<V>.exe` (артефакт `suslik-media-qt-setup`): тот же мастер, что у Kotlin-версии, с MSI внутри; ставит в `%LOCALAPPDATA%\Programs\Suslik Media Qt`,
качает видеодвижок в `%APPDATA%\SuslikMediaQt\mpv`, кладёт рядом с программой `SuslikUninstall.exe` (окно удаления с очисткой данных). Снимки его окон — `setup-preview.yml` (артефакт `setup-preview-qt`).
