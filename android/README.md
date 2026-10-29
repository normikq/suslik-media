# Suslik Media — Android-приложение

> 📖 **Полная документация:** [docs/ANDROID.md](../docs/ANDROID.md); история версий — [docs/HISTORY.md](../docs/HISTORY.md).

Нативное приложение (Kotlin, Jetpack Compose) для телефонов и Android TV: медиатека, «Мои», «Вместе» (совместный просмотр),
поиск раздач, добавление magnet/.torrent, настройки. Плеер — libVLC 3.6 или Media3 (выбор — `player/EngineChoice`), оба
читают фильм через общий запас `BurstProxy` (`shared/`). Пакет `ru.suslikovanora.media`; отладочная сборка «Suslik Debug» —
`ru.suslikovanora.media.diag` ([DEBUG-BUILD.md](DEBUG-BUILD.md)).

## Адрес сервера

В репозитории его нет. Сборка берёт `SITE_URL` из окружения (в CI — секрет) или `suslik.siteUrl` из
`~/.gradle/gradle.properties`: домен и, если сервер не в корне, путь — `https://<домен>` или `https://<домен>/<путь>`.
В приложении это `BuildConfig.SITE_URL`; API — `<адрес>/api/…`, страницы сайта — `<адрес>/media/`. Без адреса задачи
`assemble*`/`bundle*` останавливаются с ошибкой (компиляция — нет). Переезд сервера — [docs/MIGRATION.md](../docs/MIGRATION.md).

## Сборка

- CI: workflow `android.yml` (только вручную), с `publish=true` и `channel=beta` — на сайт в бета-канал.
- Локально на ПК владельца: в `android/` нет `gradlew` — Gradle 8.10.2 из `~/.gradle/wrapper/dists`, JDK 21 (байткод JVM 17),
  Android SDK 35 (`minSdk` 24). `gradle :app:assembleRelease` или `:app:assembleDiag`; установка отладочной — `install-android-debug.ps1`.
- Release подписывается ключом из секретов (`ANDROID_KEYSTORE_B64` и др.); без ключа собирается только debug-APK.
  Локальная diag-сборка подписана другим ключом, чем CI: поверх неё ставится только после удаления.

## Версии

`versionCode` (растёт всегда) и `versionName` — в `app/build.gradle.kts`; «Что нового» — `app/src/main/assets/changelog.json`
(новая запись сверху, по-русски, без жаргона). APK на сайте — `suslik-media-<V>-b<code>.apk`.
