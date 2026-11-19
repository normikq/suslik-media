# HLS на сервере сайта

Контракт — `docs/HLS.md` (правит только T0). Здесь — как он устроен в `website/`.

## T3 — серверное ядро

Только функции и данные. Маршрутов нет, их добавляет T4. Включается через `HLS_ENABLED=true` в `.env`.
Пока HLS выключен, сайт работает как раньше: в очередь ничего не ставится, файлы `hls-*.json` не появляются.

### Модули

| Файл | Что делает |
|---|---|
| `website/src/services/hls-store.js` | Читает три JSON-файла при старте и пишет их через `makeJsonWriter`. Держит индексы в памяти. Логики в нём нет |
| `website/src/services/hls.js` | Идентификаторы, подписи, переиспользование, `direct_ok`, выбор профиля, очередь, ответы воркеру и CDN, статус для variant-url |
| `website/src/services/library.js` | Сохраняет в раздаче `hash` (infohash из AllDebrid, 40 hex в нижнем регистре, иначе `null`). Если HLS включён, после `buildLibrary()` вызывает `hls.onLibrary()` |
| `website/src/services/cleanup.js` | `forget(adId)` вызывает `hls.forgetMagnet()`: удаляет алиасы и задания раздачи, ассеты остаются |
| `website/src/config.js` | Читает из env `HLS_ENABLED`, `HLS_CDN_URL`, `HLS_SIGN_SECRET`, `HLS_WORKER_TOKEN`, `HLS_CDN_TOKEN`, `HLS_BETA_USERS`. Значений по умолчанию нет |

### Файлы данных (`DATA_DIR`)

| Файл | Ключ → значение |
|---|---|
| `hls-assets.json` | `hls_id` → `{fingerprint, canonical_id, edition, episode, duration_ms, source{ad_id,path,size,hash}, profiles{v1-copy?,v1?:{status,created_at,bytes,manifest}}, active_profile, pending_delete[], last_access, error?}` |
| `hls-aliases.json` | `"<adId>\|<путь>"` → `{hls_id, shift_ms}`. Алиас есть и у файла-источника ассета (`shift_ms` 0): он появляется в момент `encode` |
| `hls-queue.json` | `job_id` → `{job_id, hls_id, ad_id, path, size, hash, canonical_id, edition, episode, priority, state, attempts, progress, eta_s, profile, leased_until, created_at, seq, stage?, error?}` |

Время хранится в unix-секундах. Параметр `now` у функций задаётся в миллисекундах, чтобы тесты могли подставить своё время.
Индексы в памяти:
- `byFingerprint`: отпечаток → `hls_id`;
- `byReuseKey`: `canonical_id|edition|episode` → `hls_id`. У сериала без номера серии и у файла без `canonical_id` ключа нет;
- `byAlias`: `hls_id` → ключи его алиасов (обратный индекс, чтобы при вытеснении удалить алиасы);
- `jobByFile`, `jobByHls`: для дедупликации очереди.

### Как это работает

- **Постановка.** `onLibrary(magnets)` вызывается из `buildLibrary()`. Первый вызов после запуска только запоминает статусы.
  Дальше раздача, которая перешла в `statusCode === 4` и у которой есть файлы, ставит свои видеофайлы (без `sample`) с приоритетом 2.
  Если по `byReuseKey` уже есть готовая версия, файл не ставится. Его поставит «Смотреть» (T4) через `enqueue({…, priority: 0})`.
- **Дедупликация.** Задания сравниваются по `ad_id|путь` и по `hls_id`. При совпадении повышается приоритет у существующего задания.
  Задание в состоянии `failed` при этом снова встаёт в очередь. Файл, у которого уже есть готовый ассет, не ставится (`skipped: "ready"`).
- **Аренда.** `next()` выдаёт задание с наименьшим приоритетом, при равном приоритете — поставленное раньше. Аренда — `LEASE_S` = 10 мин,
  её продлевает `progress()`. Если аренда истекла, при следующем `next()` задание возвращается в очередь.
- **probe** (`handleProbe`):
  1. Задание уже привязано к ассету и профилю (повтор после истёкшей аренды, `v1` после `v1-copy`) → `encode` того же профиля.
  2. Совпал отпечаток. Ассет готов → `alias` (сдвиг 0). Ассет кодирует другое задание → `wait` (60 с, задание ждёт в очереди). Иначе → `encode`.
  3. Совпал ключ переиспользования и |Δ длительности| ≤ 2 мин → `alias` со `shift_ms` (выбирается вариант с наименьшим сдвигом). Если такой ассет кодируется → `wait`.
  4. Иначе создаётся новый ассет с `hls_id` задания, ответ `encode`. Профиль `v1-copy` или `v1` выбирается по §5 (`decideProfile`).
     Если у файла нет `format.bit_rate`, битрейт считается как размер / длительность.
- **done.** `v1-copy` становится `active_profile` (если `v1` ещё нет), и в очередь ставится `v1` с приоритетом 4.
  `v1` становится `active_profile`, а `v1-copy` уходит в `pending_delete`.
- **failed.** Если ошибка `retryable`, задание повторяется через attempts × 5 мин, всего до 3 попыток. Иначе состояние `failed`,
  профиль со статусом `encoding` снимается с ассета, ошибка записывается в `asset.error`.
- **evicted.** Профиль удаляется из записи. Если профилей не осталось, удаляются ассет и его алиасы.
- **canonical_id.** Берётся из «Определить через ИИ», и только из ответа, подтверждённого владельцем (`status: "accepted"`), как в `isSameMovieRelease`.

### API `hls.js` для T4

```
enabledFor(userId) → bool                                  // HLS_ENABLED и HLS_BETA_USERS
statusFor(adId, path) → {status, hls_id, profile, shift_ms, eta, progress, state}
directFor(adId, path) / computeDirectOk(path, tagsEntry) → {direct_ok, direct:{ext,vcodec,acodec}}
signMaster(hlsId, exp) → hex;  verifyMaster(hlsId, exp, sig, now?) → "ok" | "expired" | "invalid"
masterPath(hlsId, exp?) → "/api/hls/<id>/master.m3u8?exp=…&sig=…" (с SITE_PREFIX)
signHlsUrl(hlsId, profile, relPath, exp) → абсолютная ссылка CDN;  signCdn(exp, hlsId, secret?)
readyProfile(asset), touch(hlsId), require("./hls-store").getAsset(hlsId)
enqueue({ad_id, path, size, hash?, priority}) → {job, created} | {job, deduped} | {skipped:"ready", hls_id}
next() → задание | null;  handleProbe(jobId, body) → alias | encode | wait (HlsError 404/400)
progress(body) → задание | null;  done(body) → {ok, active_profile} (HlsError 400/404);  failed(body) → задание | null
pendingDeletes() → {items};  evicted(list) → число;  cdnStats(body), getCdnStats()
```

Ошибки — `HlsError` с полями `code` и `status` (HTTP-код для ответа).
К заданию из `next()` T4 сам добавляет `mirror_url`: подписанная ссылка зеркала на `path` раздачи `ad_id`.

### Тесты

```
cd website
npm ci
node --test test/hls*.test.js       # только HLS
node --test test/*.test.js          # все (на Node 24 папку как аргумент передавать нельзя — нужен glob)
npm run check
node --test ../docs/hls-fixtures/*.test.js
```

`hls.test.js` проверяет векторы, подписи, серии, сдвиг, `direct_ok`, профиль, переиспользование, очередь, аренду,
цепочку v1-copy → v1 → evicted, `failed` и `forget`. `hls-library.test.js` проверяет `buildLibrary` с подменённым AllDebrid
и перестройку индексов с диска. `hls-disabled.test.js` проверяет, что при выключенном HLS сайт работает как раньше.

## T4 — API

Маршруты — `website/src/routes/hls.js`. Регистрация в `app.js`: `hlsRoutes.internal` — до общего `express.json({limit: "20kb"})`,
`hlsRoutes.router` — вместе с остальными маршрутами. Блок `hls` в variant-url добавляет `routes/mirror.js` через `variantHls()`.

### Уточнения контракта (T4)

- **done присылает manifest целиком.** Тело `POST /internal/hls/done` — как `done-request.json` плюс поле `manifest`: содержимое
  `manifest.json` профиля (как `hls-fixtures/manifest.json`). Без `manifest` или без `manifest.video` → `400 {code: "bad_manifest"}`, профиль не становится ready.
  Сервер хранит в `profiles.<p>` поля `manifest_sha256` (как прислано) и `manifest` (только поля для master: `duration_ms`, `encoder`,
  `video[] {dir, codec, width, height, bandwidth, avg_bandwidth, fps}`, `audio[] {dir, codec, channels, language, name, default}`,
  `subtitles[] {dir, language, name, forced}`; до 4 видео, 4 звуковых, 6 субтитров).
- **master.m3u8** строится строго по `manifest.video` (у исходника ≤ 720p — только `720p/`, у v1-copy — одна дорожка, кодек может быть `avc1…`),
  звук — по `manifest.audio`, субтитры — по `manifest.subtitles`. `CODECS` = кодек видео + кодек звука по умолчанию. Нет звука — без `AUDIO=`,
  нет субтитров — без `SUBTITLES=`. Ни одна дорожка не `default` — `DEFAULT=YES` у первой. `FORCED=YES` идёт с `AUTOSELECT=YES`.
- **probe:** у видеодорожки `dovi: true` → всегда `v1`.
- **progress** принимает любую стадию (`probe`, `fingerprint`, `wait`, `encode`, `verify`, `upload`, `done`) и продлевает аренду; во время `wait` воркер шлёт её сам.
- После `alias` воркер `done` не шлёт: задание закрывает сам probe.
- **evicted** принимает массив или `{items: [...]}`; неизвестные `hls_id`/профили и `last_access: 0` пропускаются, ответ `200 {ok, removed}`.
- **next:** если файла уже нет в раздаче — задание `failed` без повтора; AllDebrid не ответил — `failed` с повтором. Берётся следующее (до 3 за запрос).
- `HLS_SIGN_SECRET` — только `A-Z a-z 0-9 . _ ~ + / = -` (nginx). Иначе при старте с `HLS_ENABLED` — предупреждение в журнал (`config.HLS_SECRET_RE`).
- **direct_ok:** `mediatags.js` теперь пишет в `meta.acodecs` кодеки звука из ffprobe (`["aac","ac3"]`). Если они есть, `computeDirectOk` смотрит на них:
  годятся `aac`, `mp3`, `opus`, `vorbis`, `flac`; в `direct.acodec` — первый неподходящий (`dts`, `truehd`, `ac3`, `eac3` или имя ffprobe), иначе первый.
  Старые записи без `acodecs` — по меткам, как раньше (появятся при следующем разборе раздачи). Видимые метки не изменились.

### Внутренние запросы (`/internal/hls/*`)

Bearer-токен сравнивается через `timingSafeEqual` (по sha256). Токен в `.env` не задан → `503 {code: "not_configured"}`; нет или чужой → `401 {code: "unauthorized"}`
(токен CDN к запросам воркера не подходит, и наоборот). Тело разбирается только после проверки токена, до 256 КБ (больше → 413).
Ошибки `HlsError` → `{error, code}` с её HTTP-кодом. Лимит — 300 запросов в минуту с адреса (`hlsInternalLimiter`).

| Запрос | Токен | Ответ |
|---|---|---|
| `GET /internal/hls/next` | воркер | задание (`next-job.json`) с `mirror_url` — подписанная ссылка зеркала `/api/file/proxy/<имя>?link&exp&sig` на 12 ч; пусто → `204` |
| `POST /internal/hls/probe` | воркер | `alias` / `encode` / `wait`; неизвестное задание → `404 unknown_job` |
| `POST /internal/hls/progress` | воркер | `{ok, leased_until}`; неизвестное задание → `404 unknown_job` (воркеру — бросить его) |
| `POST /internal/hls/done` | воркер | `{ok, active_profile}`; `400 bad_manifest`, `404 unknown_asset` |
| `POST /internal/hls/failed` | воркер | `{ok, state, attempts}` (`state: null` — задание уже снято); `404 unknown_job` |
| `GET /internal/hls/pending-deletes` | CDN | `{items: [{hls_id, profile}]}` |
| `POST /internal/hls/evicted` | CDN | `{ok, removed}` |
| `POST /internal/hls/cdn-stats` | CDN | `{ok}` |

### master.m3u8

`GET /api/hls/<hls_id>/master.m3u8?exp&sig` — без cookie. HLS выключен → 404; подпись неверна → 403; срок вышел → 410; нет ассета, не ready или нет manifest → 404;
CDN не настроен (`HLS_CDN_URL`/`HLS_SIGN_SECRET`) → 503. Ответ `application/vnd.apple.mpegurl`, `Cache-Control: no-store`, ссылки CDN с тем же `exp`.
Обновляет `last_access`. Лимит — `hlsLimiter` (как `mirrorUrlLimiter`, 300/мин).

### variant-url

Блок `hls` (§10) собирается на каждый ответ, в 15-минутный кэш не попадает: кэш хранит ответ без `hls`, подпись мастера свежая (12 ч).
Блока нет, если `HLS_ENABLED` выключен или пользователя нет в `HLS_BETA_USERS`. Если статус `none` и файл — видео, он ставится в очередь с приоритетом 0,
а следующие две серии того же сезона (`nextEpisodes`, по `episodeOf`) — с приоритетом 1 (кроме упавших заданий). Ответ уже с новым статусом:
после постановки — `encoding` с `progress: 0` и `eta: null`. `none` остаётся у файлов не-видео и если поставить не удалось.
`hash` для `hls_id` берётся из последней медиатеки в памяти (`library.cachedMagnetHash`), без запроса к AllDebrid.

### CSP и hls.js

`pageCsp()`: origin `HLS_CDN_URL` (без пути) в `connect-src` и `media-src`, всегда `worker-src 'self' blob:`.
Плеер — `website/public/vendor/hls.min.js`, **hls.js 1.7.4** (npm, `dist/hls.min.js`, выпуск 28.10.2026,
sha256 `cfc70cc9e9fc63d071919e087670abc760c8fd1bc889b88588026e4d22193bbe`), лицензия Apache-2.0 — `vendor/hls.js.LICENSE`.
Отдаётся `express.static` как `application/javascript`. `scripts/check.js` папку `vendor/` не проверяет. Файл `.map` не кладём.

### Админка (для T6)

Все запросы — `requireLogin` + `isLibraryAdmin` (Discord-пользователь с ролью admin): без входа → 401, не админ → `403 {code: "forbidden"}`.
POST — с cookie сайта (как остальной сайт, `sameOriginWrites`) или с `X-Device-Token`. Ошибки — `{error, code}`. Лимит — `hlsLimiter`.

**`GET /api/hls/admin/state`** →

```
{ enabled, configured: {cdn_url, sign_secret, worker_token, cdn_token},   // bool: что задано в .env
  now,                                                                     // unix-с
  totals: {jobs, queued, running, failed, assets, ready, bytes},
  queue: [{ job_id, hls_id, ad_id, path, size, hash, canonical_id, edition, episode, priority, profile, leased_until,
            state, attempts, progress, eta_s, stage, created_at, error: {stage, error, at} | null, force, replaces }],
            // порядок — как выдаёт next (приоритет, потом очередь постановки)
  assets: [{ hls_id, source: {ad_id, path, size, hash}, canonical_id, edition, episode, duration_ms, active_profile,
             pending_delete: [profile], last_access, error, bytes,
             profiles: { "v1"|"v1-copy": {status: "encoding"|"ready", bytes, created_at, encoder} },
             aliases: [{ad_id, path, shift_ms}] }],                        // по last_access, свежие сверху
  cdn: {disk_total, disk_used, assets, profiles, at, received_at} | null } // последнее cdn-stats (в памяти)
```

**`POST /api/hls/admin/retry {job_id}`** → `{ok, job}`. Задание снова в очереди сразу, попытки с нуля, ошибка ассета снята.
Нет задания → `404 unknown_job`; задание в аренде у воркера → `409 busy`.

**`POST /api/hls/admin/reencode {ad_id, path, hls_id?}`** → `{ok, job, replaces}`. Новый источник: файл раздачи кодируется в `v1` с приоритетом 0,
без переиспользования (probe всегда `encode`), в ассет со своим `hls_id` (по этому файлу). `hls_id` — какой ассет он заменяет; по умолчанию —
тот, что сейчас играет этот файл, иначе готовый по ключу переиспользования. После `done` алиасы старого ассета переходят на новый
(сдвиг пересчитывается по длительностям; разница > 2 мин — алиас остаётся на старом), у старого `active_profile: null` и все профили в `pending_delete`.
Пока идёт кодирование, старый ассет играет как раньше. Если `hls_id` нового совпал со старым (тот же файл) — файлы на CDN перезаписываются на месте.
Повторное перекодирование того же файла или того же ассета, пока первое в очереди или в работе, → 409 `already_queued` (в админке кнопка «Уже в очереди» неактивна); упавшее перекодирование не мешает.
Файла нет в раздаче → `404 unknown_file`; не видео → `400`; файл сейчас кодируется → `409 busy`; нет такого `hls_id` → `404 unknown_asset`.

**`POST /api/hls/admin/delete {hls_id, profile?}`** → `{ok, hls_id, pending_delete, removed}`. Профиль (или все без `profile`) уходит в `pending_delete`;
если это `active_profile`, играть начинает другой готовый профиль или никакой. CDN удаляет файлы и присылает `evicted` — тогда запись исчезает
(без профилей — ассет и его алиасы). Без `profile` снимаются и задания ассета вне аренды; ассет без профилей удаляется сразу (`removed: true`).
Нет ассета → `404 unknown_asset`, нет профиля → `404 unknown_profile`.

### Тесты T4

`website/test/hls-api.test.js`: токены воркера и CDN (401, 503), лимит тела 256 КБ на `/internal` и 20 КБ на остальном, `next` с `mirror_url` и 204,
`progress` со стадией `wait`, `evicted` с неизвестными, variant-url (none / encoding / ready, серии N+1 и N+2, статус мимо кэша, без `HLS_ENABLED` и не в бете —
без `hls`), master.m3u8 (звук, субтитры, 403/410/404, только 720p и avc1), CSP, `/vendor/hls.min.js`, админка, reencode с переносом алиасов,
`meta.acodecs` в `direct_ok`, `dovi`.

## Архив (§17)

Холодный архив на CDN (Google Drive через rclone, `docs/HLS.md` §17). Сайт настроек не получает: всё узнаёт из отчётов CDN.

### Версия на сайте

Поля в записи версии (`hls-assets.json` → `profiles[<профиль>]`, переживают перезапуск):

| Поле | Что |
|---|---|
| `archived`, `archive_bytes`, `archived_at` | копия проверена в архиве (`POST /internal/hls/archived`) |
| `status: "cold"` | вытеснена с CDN, есть в архиве; ассет и алиасы остаются |
| `restore_requested_at` | запрошен возврат (секунды); есть — для клиента и админки статус `restoring` |
| `restore_attempts`, `restore_timeouts`, `restore_error` | неудачи `restored` подряд, тайм-ауты (30 мин без ответа), последняя ошибка |
| `restore_user`, `restore_file` | возврат ждёт пользователь (`variant-url`) и на каком файле он нажал «Смотреть» |

`readyProfile` считает готовой только `ready`: у `cold` `master.m3u8` → 404. Переиспользование (§8: алиас, отпечаток, ключ, отложенное
кодирование серий, reencode) считает `cold` доступной (`availableProfile`) — новый файл получает алиас, при «Смотреть» версию возвращают.

### Запросы CDN (`Bearer HLS_CDN_TOKEN`, тело до 256 КБ, неизвестные записи пропускаются, ответ 200)

| Запрос | Что делает сайт | Ответ |
|---|---|---|
| `POST /internal/hls/archived` `[{hls_id, profile, bytes, at}]` | у `ready`/`cold` версии `archived: true`, `archive_bytes`, `archived_at` | `{ok, archived: N}` |
| `POST /internal/hls/evicted` `[{…, archived?}]` | `archived: true` или версия уже `archived` → `cold` (`evicted_at`), `active_profile` — другая готовая версия, иначе прежний; версия ждёт удаления → запись удаляется, как раньше | `{ok, removed: N}` |
| `GET /internal/hls/pending-restores` | сначала считает тайм-ауты; запросы `cold`-версий не из `pending_delete`, старые первыми | `{items:[{hls_id, profile, requested_at}]}` |
| `POST /internal/hls/restored` `[{hls_id, profile, ok, bytes?, error?}]` | `ok` → `ready`, `bytes`, `last_access` = сейчас, запрос снят, `archived` остаётся; ошибка — см. ниже | `{ok, restored, failed, dropped}` |
| `GET /internal/hls/pending-deletes` | у версии с `archived: true` — `archive: true` | `{items:[{hls_id, profile, archive?}]}` |

### Возврат

- `variant-url` на `cold` вызывает `requestRestore(hls_id, profile, now, {user, file})` и отвечает `hls.status: "restoring"`, `eta` = `30 + archive_bytes / 40 МБ/с`
  минус сколько уже ждём (не меньше 10 с), `profile`, `direct_ok`, `direct`. Повторное «Смотреть» запрос не дублирует. Если запросить не удалось — `none`.
  Файл без алиаса, но с тем же `hls_id` у `cold`-ассета: сначала `enqueue` ставит алиас, затем тот же запрос возврата.
- Админка: `POST /api/hls/admin/restore {hls_id, profile?}` → `{ok, hls_id, profile, requested_at, already}`; не `cold` (или ждёт удаления) → `409 not_cold`,
  нет ассета → `404 unknown_asset`, нет профиля → `404 unknown_profile`, не админ → 403.
- Ошибка `ok:false`: `not_in_archive` или третья неудача подряд, или третий тайм-аут (нет ответа 30 мин; считается при опросе `pending-restores`
  и при `variant-url` этого файла) → у версии `archived: false`, запись версии удаляется (без версий — ассет и алиасы), файл ставится на кодирование:
  если ждёт пользователь — его файл с приоритетом 0, иначе источник ассета с тем же `hls_id`, приоритет 3. Иная ошибка — запрос остаётся, тайм-аут считается заново.
- Только что возвращённую версию CDN не вытесняет (< 2 ч после обращения) — правило CDN, сайт его не проверяет.

### Удаление

Удаление из админки и `replaceAsset` (после перекодирования) ставят все версии, в т.ч. `cold`, в `pending_delete`; у архивных в `pending-deletes` — `archive: true`.
Запрос возврата для версии из `pending_delete` не выдаётся и не ставится. После `evicted` (CDN пришлёт `archived:false`) запись удаляется.
`forgetMagnet` ассеты не удаляет (§10), поэтому пункт §17 про `forget` последнего источника сейчас ничего не делает.

### Админка (`GET /api/hls/admin/state`)

У профиля: `status` (`cold` или `restoring` вместо `cold` с запросом), `archived`, `archive_bytes`, `archived_at`, `restore_requested_at`, `restore_attempts`,
`restore_error`, `eta_s` (у `restoring`). В `totals`: `archived` (версий с копией), `archive_bytes`, `cold`, `restoring`; `bytes` — только то, что на CDN (без `cold`).

### Тесты архива

`website/test/hls-archive.test.js`: `archived` по HTTP; `evicted` с архивом → `cold`, ассет и алиас живы, master 404; без архива — удаление; `variant-url`
на `cold` → `restoring` (`eta` 150), запрос в `pending-restores`; `restored` ok → `ready`, master 200; `not_in_archive` → версия удалена, задание приоритет 0;
три тайм-аута и три ошибки подряд; `archive: true` в `pending-deletes`; удаление `cold` из админки; токены (без, чужой, воркера — 401); фикстуры §17;
перезапуск (перечитывание JSON); `admin/restore` (401/403/409, `already`) и сводка в `state`.
