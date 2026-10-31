# Suslik Media для Windows на C++/Qt

Новая ПК-версия, растёт рядом с Kotlin-версией (`desktop/`) и заменит её, когда догонит по функциям. План и порядок работ — `docs/QT-PORT.md`.

| Папка | Что |
|---|---|
| `core/` | Ядро без интерфейса, перенос `shared/` (только Qt Core/Network): адрес сервера, зеркало, версии, changelog… |
| `app/` | Приложение на Qt Quick (QML). Пока окно-заглушка |
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

CI — `.github/workflows/desktop-qt.yml`: тесты на каждый пуш в `desktop-qt/`, сборка для Windows вручную (артефакт `suslik-media-qt-windows`).
