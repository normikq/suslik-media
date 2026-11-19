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
