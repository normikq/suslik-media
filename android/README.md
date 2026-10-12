# Suslik Media — Android-приложение

Исходный проект Android-приложения для частного медиапортала:
`https://short.suslikovanora.ru/media/`

## Возможности
- Оболочка Android на Kotlin с WebView для входа на сайт, каталога, действий AllDebrid и серверного API.
- Нативный Android-плеер VLC/libVLC для прямых HTTP(S)-ссылок, доступных через действия сайта «Открыть» и «Открыть ссылку».
- Воспроизведение VLC в полноэкранном режиме, пауза/воспроизведение, перемотка, сетевой буфер, предотвращение выключения экрана и восстановление позиции в сеансе.
- Выбор файла для загрузки торрент-файла.

## Целевая версия JVM
Проект компилирует Java и Kotlin в байткод JVM 17. Более новая JDK может быть установлена в Android Studio; целевая версия 17 задана для согласования параметров компиляции.

## Сборка APK
1. Откройте эту папку в актуальной стабильной версии Android Studio.
2. Дождитесь синхронизации Gradle и установите Android SDK 35, если появится запрос.
3. Выберите **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Android Studio покажет путь к собранному APK.

## Ограничения и проверки перед публикацией
- Это исходный проект Android, а не готовый APK. Сборка и проверка на реальном устройстве в этой среде не выполнялись. Зависимость VLC загружается Gradle из Maven Central во время синхронизации или сборки.
- The website's private password/login flow is used as-is inside WebView. Не встраивайте общий пароль или ключ API AllDebrid в приложение.
- The native VLC player intercepts common “Open” actions. Некоторые источники возвращают временные URL, требуют специальных заголовков/cookie или ограничивают воспроизведение определёнными клиентами; такие сценарии нужно проверить на реальном аккаунте и потоке.
- Позиция воспроизведения сохраняется при остановке или пересоздании активности плеера, но не синхронизируется с сайтом.
- `android:usesCleartextTraffic="true"` is enabled to allow external HTTP media URLs in the prototype. The site itself is loaded over HTTPS. For a production release, prefer HTTPS streams and remove cleartext access once all sources support it.

## Идентификатор приложения
`ru.suslikovanora.media`


### Playback improvements
- Double-tap the left half of the video to seek backward 10 seconds; double-tap the right half to seek forward 10 seconds.
- When VLC cannot determine an M2TS/transport-stream duration, the player displays `--:--` instead of a misleading `00:00` and disables the duration-based seek bar.


### Battery and M2TS handling update
- Progress UI refresh interval is 1 second instead of 500 ms.
- Keep-screen-on is enabled only while playback is active, and cleared on pause/stop.
- Seeking UI displays an unknown duration as `--:--` rather than `00:00`.
- These are conservative code changes; battery/thermal improvements require measurement on the target phone. M2TS duration remains unknown when libVLC/source cannot report it; no FFmpeg parser has been added.


### Optimization pass (0.1.1)
- Player UI is driven by libVLC events instead of a 1 s polling loop; it updates only while controls are visible. Keep-screen-on follows Playing/Paused/Stopped events.
- `MainActivity` pauses the WebView and its JS timers while in background / while the player is on top.
- Native libVLC teardown runs off the UI thread; `configChanges` extended so fold/theme/PiP changes do not restart playback.
- Resume uses `:start-time`, and the last position is persisted across app restarts (by host+path; cleared near the end).
- Audio focus + pause on headphones unplug; buffering indicator; error toast; volume bar synced with VLC's default (100).
- Subtitles are now actually rendered (`attachViews(..., enableSubtitles = true, ...)`) — verify on device.
- Picture-in-Picture when leaving the player while playing.
- Edge-to-edge (required by targetSdk 35) with WindowInsets; cutout-aware controls; predictive-back-ready back handling.
- JS bridge is origin-restricted via `androidx.webkit` (fallback to the legacy bridge on old WebViews).
- Downloads send the WebView session cookie and use a proper file name.
- Release build: R8 minify + resource shrinking, VLC keep rules. Gradle: parallel + build cache.
- Не включены: иконка приложения и Gradle Wrapper. Сборка не проверена на реальном устройстве; перед публикацией выполните сборку и базовые проверки.

### Network switching (0.1.2)
- The player watches the default network (`registerDefaultNetworkCallback`). On Wi-Fi <-> mobile switches or when connectivity returns, the stream is re-opened in place at the last position (same activity, LibVLC instance and views; no restart).
- With no network the player shows "Нет сети — ждём подключение…" and resumes automatically; if the user had paused, it reloads on the next Play press.
- Watchdog: if buffering produces no events for 15 s, the stream is reloaded. Transient errors retry with capped exponential backoff (up to 15 s) while the player remains open; retry backoff resets only after 30 s of stable playback.
- Reconnect position uses the latest confirmed playback timestamp and keeps it updated during playback, reducing jumps backwards when VLC reports stale/zero time after a dropped connection.
- Limitation: expiring or IP-bound URLs may still be rejected by the server after a network change.

### 0.1.3 fix
- A dropped HTTP connection is reported by VLC as end-of-file, which previously marked playback as "ended" and blocked reconnects. EOF now counts as a real end only near the known duration; otherwise the stream is reloaded.
- Reconnect no longer depends on VLC's Paused events (only on the user's own pause) and swaps in a fresh MediaPlayer instead of calling a potentially blocking `stop()` on the old one.


### Native playback bridge fix
- The website's Android player action generates an `intent://` URL. WebView previously consumed this non-HTTP scheme without launching the native player, and the click hook did not recognize the “▶ Плеер” action.
- The bridge now intercepts the play-sheet player action, extracts its embedded HTTP(S) media URL, and starts the native VLC activity. `shouldOverrideUrlLoading` also handles Android intent URLs as a fallback.
- This source patch has not been built or device-tested in this environment; rebuild the APK in Android Studio and test both direct and mirror playback.


## Подготовка к релизу 1.0.0
- Обновлены отображаемое название и версия приложения.
- Пользовательские уведомления плеера и загрузок отображаются на русском языке.
- Включены R8 и уменьшение ресурсов для release-сборки.
- Перед публикацией обязательно выполните сборку `assembleRelease`, проверьте воспроизведение прямых и зеркальных ссылок, загрузку `.torrent`, поворот экрана, PiP, восстановление после потери сети и работу на минимальной поддерживаемой версии Android.
- Для публикации настройте собственную подпись релиза в Android Studio/CI и храните ключ подписи вне репозитория. В архив не включены keystore и секреты.
- `android:usesCleartextTraffic="true"` пока оставлен для источников HTTP. Это расширяет поверхность риска; отключите его после проверки, что все используемые медиассылки работают по HTTPS.


### Network recovery improvement (1.0.1 source patch)
- Retry no longer stops after five ordinary stream errors; it continues at a capped interval while the player remains open, so a CDN/origin that recovers slowly can be retried.
- Retry backoff is not reset by the first brief `Playing` event. It resets after 30 seconds of continuous playback.
- Playback position is refreshed from VLC time events and reconnect uses the furthest confirmed position to avoid falling back to a stale timestamp.
- Not yet built or verified on a physical Android device. Test airplane-mode toggling, Wi-Fi/mobile switching, temporary CDN outage, manual pause, and seek-back before release.


### 1.1.0 — плейлист сезона и явный контракт с сайтом
- Сайт вызывает одну функцию: `SuslikNative.postMessage('{"url":…,"title":…,"playlist":…}')` (для старых WebView — `SuslikNative.playJson(json)`). Приложение объявляет себя через `window.__suslikApp = 2`. Поиск кнопок по тексту (`HOOKS_JS`) удалён: подписи кнопок на сайте можно менять свободно.
- `playlist` — адрес `.m3u` на нашем же сайте (только `https://<siteHost>/…`, иначе игнорируется). В нём выбранная серия и все следующие до конца сезона. Плеер скачивает его без cookie (адрес подписан), показывает кнопки ⏮ / ⏭, в заголовке — «3/12», а по окончании серии сам переходит к следующей. Позиции просмотра хранятся отдельно для каждой серии.
- Сайт и приложение нужно обновлять вместе: новое приложение со старым сайтом откроет ссылки через запасной путь `intent://`, но без плейлиста.
- Сборка и проверка на устройстве в этой среде не выполнялись (нет Android SDK/Maven): соберите APK и проверьте — одиночная серия, плейлист, переход по окончании серии, ⏮/⏭, потерю сети во второй серии.

## 1.1.1 — стабильность плеера
- Watchdog зависшего воспроизведения (время не идёт ≥8 с) и таймаут «открытия» потока после переподключения (раньше при зависании в Opening повторных попыток не было).
- Перемотка: ±10 с по двойному тапу суммируются, применяются одним seek; не подтверждённый VLC seek (мёртвый сокет после смены Wi-Fi ↔ мобильная сеть) выполняется заново на новом соединении.
- События уже заменённого плеера игнорируются; нативное освобождение выполняется строго по порядку (плееры → LibVLC).
- Повторное подключение ждёт «валидированную» сеть; восстанавливается выбранная аудиодорожка/субтитры; позиция сохраняется каждые 10 с.
- Повторный тап «Плеер» не запускает второй плеер (singleTop).

## 1.1.2 — энергоэффективность

- **Частота экрана под видео (Android 11+).** Плеер сообщает системе реальную частоту кадров ролика (`Surface.setFrameRate`). На экранах 90/120 Гц дисплей во время просмотра может работать на 24/30/60 Гц — это одна из самых заметных статей расхода батареи при видео. На Android 12+ разрешено только бесшовное переключение (`CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS`), поэтому чёрных вспышек и пауз при смене режима нет; если устройство не может переключиться бесшовно, всё остаётся как было.
- **Сторож зависаний работает только во время воспроизведения.** Раньше он просыпался каждую секунду всё время, пока открыт плеер (в том числе на паузе). Теперь — раз в 2 с и только когда VLC действительно играет; порог срабатывания прежний (8 с без движения времени).
- Без изменений и уже оптимально: аппаратное декодирование, SurfaceView (дешевле TextureView), обновление полосы прогресса не чаще 1 раза в секунду и только при видимых кнопках, WebView сайта ставится на паузу, пока открыт плеер или приложение в фоне.

## 1.1.3 — картинка в режиме «картинка в картинке»

- Исправлен чёрный экран в мини-окне и после возврата на весь экран. При переходе в PiP система может пересоздать поверхность видео, а libVLC сам не перезапускает на ней вывод картинки (звук при этом шёл дальше). Теперь плеер замечает пересоздание поверхности и заново подключает к ней видео — без остановки звука и без потери позиции.
- Android 12+: вход в PiP автоматический (`setAutoEnterEnabled`), с подсказкой исходной области — анимация плавнее, поверхность реже пересоздаётся. Пропорции мини-окна берутся из самого видео (а не всегда 16:9).
- При возврате из PiP VLC заново измеряет окно (`updateVideoSurfaces`).

## 1.2.0 — нативная библиотека (шаг 1 из 3)

- Вкладка «Библиотека» теперь нативная (Jetpack Compose), в стиле сайта «Проекторная»: те же цвета, шрифты Unbounded/Onest (лежат в `res/font`, лицензия OFL — `FONTS-LICENSE-OFL.txt`), янтарная «линза» в нижней панели, постеры 2:3, кнопка ▶, выбор сезона/серии, «Отменить/Удалить» с подтверждением вторым нажатием, выбор источника (зеркало с плейлистом сезона / напрямую).
- «Ссылка» и «Поиск» пока остаются сайтом в WebView. Пока открыта библиотека, WebView стоит на паузе (не тратит CPU и батарею). Своя нижняя панель сайта в приложении скрыта (нужен сайт с `redesign.css?v=5`).
- Вход общий с сайтом: нативные запросы используют cookie сессии WebView (CookieManager), поэтому вход/выход и «владелец раздачи» (cookie `sdev`) у сайта и приложения одни и те же. Сервер менять не нужно.
- Постеры: загружаются только для плиток на экране, адреса запоминаются на 30 дней, сами картинки кэширует Coil (память + диск). Последний ответ медиатеки хранится в файле — при запуске сетка появляется сразу, потом обновляется.
- Новые зависимости: Compose BOM 2024.12.01, activity-compose, OkHttp 4.12, Coil 2.7, плагин `org.jetbrains.kotlin.plugin.compose` 2.0.21.
- Пока нет (будет в следующих шагах): список файлов раздачи по нажатию на плитку, строка «разрешение/битрейт», нативные «Поиск» и «Ссылка».

## 1.3.0 — плеер: жесты, кадр, гарнитура, субтитры из раздачи, дорожки, следующая серия, прогресс

- **Жесты:** горизонтальный свайп — перемотка с подсказкой (вся ширина экрана = 3 мин), вертикальный слева — яркость, справа — громкость (системная, как у кнопок телефона; ползунок громкости тоже системный). У верхнего/нижнего края свайпы не перехватываются — там системные жесты. Двойной тап ±10 с работает как раньше.
- **Кадр:** кнопка «⤢» переключает Вписать / Заполнить (обрезать полосы) / Растянуть / 16:9, щипок — Заполнить/Вписать. Выбор запоминается.
- **MediaSession:** пауза/воспроизведение с наушников и Bluetooth, «следующая/предыдущая» (серия, а если плейлиста нет — ±10 с), кнопки в окне «картинка в картинке».
- **Субтитры из раздачи:** .srt/.ass/.ssa/.vtt рядом с видео (по имени файла, в той же папке или в «Subs») подключаются как дополнительные дорожки. Работает при запуске из нативной библиотеки.
- **Дорожки запоминаются:** выбранная озвучка и субтитры (или «выключены») применяются к следующим сериям — по имени дорожки, затем по языку; есть и общий запасной выбор.
- **Следующая серия:** в конце серии — карточка с отсчётом 10 с, «Смотреть сейчас» / «Отмена».
- **Прогресс в библиотеке:** янтарная полоска на постере, «▶ Продолжить S02E05 · 41:20» / «✓ Просмотрено»; у сериала сразу выбрана последняя просмотренная серия. Позиция для продолжения теперь привязана к файлу раздачи, а не к ссылке (зеркало и прямая ссылка продолжают с одного места).
- **Застывшая картинка при идущем времени:** перемотка теперь идёт к ближайшему ключевому кадру (`:input-fast-seek`) — аппаратный декодер не «зависает» на середине группы кадров. Дополнительно сторож сверяет счётчик показанных кадров: если время идёт, а новых кадров нет ~4 с, перезапускается видеодекодер, при повторе — поток переоткрывается на той же позиции.
- **Буфер сети** увеличен с 4 до 6 с — переживает короткие провалы мобильной сети.
