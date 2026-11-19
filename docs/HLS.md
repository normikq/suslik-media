# HLS для сайта — контракт (T0)

Статус: **утверждён владельцем 19.11.2026** (решения по §15 приняты как предложено). После утверждения меняется только через T0
(этот файл правит только T0). Фикстуры и тестовые векторы — `docs/hls-fixtures/`.

## 1. Цель и границы

HLS-плеер на сайте (hls.js, в Safari нативно). Видео кодирует локальный сервер владельца
(RTX 3060 Ti, R5 5600, 100 Мбит), раздаёт CDN в Европе (гигабит, HDD 1 ТБ).
HLS — **дополнение к зеркалу**: пока ассет не готов, играет прямой файл через зеркало; зеркало AllDebrid остаётся.

Вне v1: HLS для внешних озвучек (custom-audio) и внешних субтитров, запасной H.264, клиенты Android, ПК, Tizen.
Ограничение v1 — только HEVC. Браузер без HEVC (Firefox без аппаратного декодирования) получает
«Открыть в приложении», а при `direct_ok` — зеркало.

## 2. Архитектура

```
AllDebrid ─► зеркало (основной сервер) ─► [Кодировщик] ──rsync/ssh──► [CDN: nginx, HDD]
                    ▲ задания, probe, статус (только исходящие)            ▲ pending-deletes / evicted / stats
                    └───────────── [Основной сервер] ◄─────────────────────┘
плеер сайта ◄── master.m3u8 (основной сервер) ──► медиаплейлисты и .m4s (CDN, подписанный префикс)
```

Кодировщик и CDN сами ходят на основной сервер (только исходящие соединения). Кодировщик к CDN — по rsync/ssh.

## 3. Хранилище

JSON в `DATA_DIR` (`website/src/config.js:71`) через `makeJsonWriter(file, collect, delayMs)`
(`website/src/utils/json-writer.js:52`, атомарная запись через `.tmp` + rename). Индексы — `Map` при старте. Таблиц и БД нет.

| Файл | Ключ | Содержимое |
|---|---|---|
| `hls-assets.json` | `hls_id` | ассет: `fingerprint`, `canonical_id`, `edition`, `episode`, `duration_ms`, `source {ad_id, path, size, hash}`, `profiles {v1-copy?, v1?: {status, created_at, bytes, manifest}}`, `active_profile`, `pending_delete [profile…]`, `last_access`, `error?` |
| `hls-aliases.json` | `"<adId>\|<путь>"` | `{hls_id, shift_ms}` — этот файл раздачи играет этот ассет со сдвигом |
| `hls-queue.json` | `job_id` | задание: `hls_id?`, `ad_id`, `path`, `size`, `priority`, `state` (`queued`/`running`/`failed`), `attempts`, `progress`, `eta_s`, `profile`, `leased_until` |

Индексы в памяти: `byFingerprint`, `byReuseKey` (`canonical_id|edition|episode` → `[hls_id]`), `byAlias`.

## 4. Идентификаторы

**hls_id** = первые 16 hex `sha1("<infohash>|<путь>|<размер>")`.
Если hash из AllDebrid нет: `sha1("ad:<adId>|<путь>|<размер>")`. Путь — как в дереве файлов раздачи (`/` как разделитель), размер — десятичное целое.
Сейчас `buildLibrary()` hash не сохраняет (`library.js:24`, нормализуются только `id`, `name`, `status`, `statusCode`, `size`, `downloaded`, `files`) — T3 добавляет `hash`.

Вектор: `vectors.json → hls_id`.

**Папка на CDN:** `/srv/hls/<hls_id>/<profile>/…`, профиль `v1` или `v1-copy`; какой играть — `active_profile` в записи ассета.

Раскладка профиля:

```
manifest.json                 # см. hls-fixtures/manifest.json
1080p/index.m3u8, video.m4s   # v1-copy: только 1080p/ (или исходное разрешение ≤1080p)
720p/index.m3u8,  video.m4s   # только v1
a0/ … a3/  index.m3u8, audio.m4s   # звук исходника → AAC стерео, до 4 дорожек
s0/ … s5/  index.m3u8, subs.vtt    # текстовые субтитры, до 6
```

Плейлисты на CDN ссылаются на файлы относительно (`video.m4s`, `subs.vtt`).

## 5. Профили кодирования (ffmpeg ≥ 6.1)

### v1
- HEVC NVENC: 1080p ≤ 5 Мбит, 720p ≤ 3 Мбит; ограниченный VBR (`-cq` + `-maxrate`/`-bufsize`).
- Ключевые кадры каждые 6 с на обеих дорожках (`-g`/`-force_key_frames "expr:gte(t,n_forced*6)"`, без scenecut-ключей между ними), чтобы сегменты 1080p и 720p совпадали.
- fMP4, `-hls_segment_type fmp4 -hls_flags independent_segments+single_file` — один `.m4s` на дорожку, `EXT-X-BYTERANGE`, `EXT-X-MAP` с байтовым диапазоном. Тег `-tag:v hvc1`.
- Звук DTS/TrueHD/AC3/E-AC3 и прочее → AAC-LC стерео (`mp4a.40.2`), 160 кбит.
- HDR (PQ/HLG, Dolby Vision с HDR10-базой) → SDR, тонмаппинг (`zscale`+`tonemap` или `tonemap_cuda`), `VIDEO-RANGE=SDR`.
- Пример: `master-v1.m3u8`, `media-1080p.m3u8`.

### v1-copy (быстрая дорожка)
Только если ffprobe показал всё сразу:
- видео H.264 или HEVC, 8 бит (`pix_fmt` без `10`), SDR (`color_transfer` не `smpte2084`/`arib-std-b67`, нет DV);
- высота ≤ 1080 (ширина ≤ 1920);
- средний битрейт файла ≤ 12 Мбит (`format.bit_rate`, у MKV у дорожки его часто нет);
- наибольший интервал ключевых кадров ≤ 10 с (воркер меряет по первым ~10 мин, `keyframe_interval_max_s`).

Одна видеодорожка `-c:v copy`, без 720p и ABR; звук — в AAC, как в v1. H.264 в v1-copy допустим (`avc1…` в `CODECS`).
После `done` сервер сам ставит `v1` в очередь с низшим приоритетом. Когда `v1` готов, `active_profile` = `v1`, а `v1-copy` уходит в `pending_delete`.

Решение, какой профиль, принимает **сервер** в ответе на probe.

### Субтитры
Только текстовые: `subrip`, `ass`/`ssa`, `webvtt`, `mov_text`; до 6. Воркер извлекает `subs.vtt` (`-c:s webvtt`) и пишет тривиальный медиаплейлист: один сегмент на всю длительность (`subs-s0.m3u8`).
PGS/VobSub/DVB пропускаются (в `manifest.json → skipped`), стили ASS теряются.

## 6. Подписи

### CDN (nginx `secure_link`, MD5)

```
URL:  /hls/<sig>/<exp>/<hls_id>/<profile>/<путь>
sig = base64url_без_паддинга( md5_raw( "<exp><hls_id> <HLS_SIGN_SECRET>" ) )
Node: crypto.createHash("md5").update(`${exp}${hlsId} ${secret}`).digest("base64url")
```

nginx: `secure_link $hls_sig,$hls_exp; secure_link_md5 "$secure_link_expires$hls_hid <secret>";` по **именованным** захватам из пути
(безымянные `$1…$3` затирает `rewrite` в том же location; конфиг — `infra/cdn/nginx/hls-cdn.conf.template`);
`$secure_link` пусто → **403**, `"0"` (истёк) → **410**. Срок — 12 ч. Профиль в подпись не входит (переключение `active_profile` не меняет подпись).
Вектор: `vectors.json → sign`.

### master.m3u8 (основной сервер)
В стиле `makeMirrorToken` (`website/src/services/mirror.js:11`): HMAC-SHA256 на `SESSION_SECRET`, hex 64 символа.

```
sig = HMAC_SHA256(SESSION_SECRET, "hls:<exp>:<hls_id>")  hex
GET /api/hls/<hls_id>/master.m3u8?exp=<exp>&sig=<sig>
```

Проверка как в `isValidMirrorToken`: `exp` целое в `[now, now + 12 ч + 60 с]`, 64 hex, `timingSafeEqual`. Cookie не нужна — работает в Tizen, mpv, ExoPlayer.
Внутри — абсолютные подписанные ссылки на медиаплейлисты CDN (`HLS_CDN_URL` + префикс), `EXT-X-MEDIA` для звука и субтитров. `exp` CDN-ссылок = `exp` мастера.
Вектор: `vectors.json → master_sign`.

## 7. Отпечаток контента

Кусок 1 МиБ. Файл < 3 МиБ хешируется целиком; иначе смещения `0`, `floor(size/2) − 512 КиБ`, `size − 1 МиБ`.

```
fingerprint = sha256( concat( utf8("<size>:"), c1, c2, c3 ) )  hex     // куски — сырые байты
```

Воркер читает куски по HTTP `Range` с зеркала. Вектор: `vectors.json → fingerprint` (байты файла генерирует формула `generator`, скрипт `gen-vectors.js`).

## 8. Переиспользование (не кодировать повторно)

1. **Тот же файл:** совпал отпечаток → алиас на этот ассет, `shift_ms = 0`, без других проверок.
2. **Тот же монтаж из другой раздачи** — совпали все:
   - `canonical_id` (`aititle.js:51`: `m:<title><year>:<edition>` / `s:<title><season>:<edition>`);
   - `edition` (`theatrical`/`extended`/`directors_cut`/`unrated`/`remastered`/`default`, `parseEdition`, `aititle.js:33`);
   - **`episode`** — у сериалов `canonical_id` один на весь сезон, поэтому ключ дополняется номером серии `SxxEyy` из пути. Номер не распознан → переиспользование только по отпечатку;
   - `|Δ длительности| ≤ 120 с`.

   `1,5 с < |Δ| ≤ 120 с` → в алиасе `shift_ms` (позиция ассета = позиция файла + `shift_ms`); `≤ 1,5 с` → `0`.
   Нет `canonical_id` (ИИ не определил) → только правило 1.
3. Режиссёрская версия или другой монтаж — отдельный ассет (другая `edition` → другой ключ).

## 9. direct_ok

Считает сервер при выдаче variant-url. Источник — кэш `media-tags.json` (`mediatags.js`): ключ — **id раздачи**, запись `{t: [метки], meta: {w, h, codec, durationSec, bitrateMbps}, at}`.
Аудиокодека и контейнера в кэше нет: контейнер — по расширению, видео — `meta.codec`, «плохой» звук — по меткам (`DTS*`, `TrueHD*`, `AC3*`, `DD+`).
Запись одна на раздачу (основной файл), для сериалов `meta` относится ко всем сериям — это допущение v1.

```
ext ∈ {mp4, m4v}: ok, если vcodec ∈ {h264} и нет плохого звука
ext = webm:       ok, если vcodec ∈ {h264, vp9, av1} и нет плохого звука
mkv, avi, ts, …:  false;  vcodec hevc/h265 → false
записи нет:       .mp4/.m4v/.webm → true, иначе false
```

Возвращается с `direct: {ext, vcodec, acodec}` (`acodec` — `aac`, если плохих меток нет, иначе `dts`/`truehd`/`ac3`/`eac3`; без записи — `""`), клиент делает финальную проверку `video.canPlayType`.
Тесты T3: mp4+H.264+AAC → true, mkv+DTS → false, HEVC → false, без записи `.mp4` → true, `.mkv` → false.

## 10. API

### POST /api/file/variant-url (дополнение)
Существующий ответ (`mirror.js:152`, `renderVariant`) кэшируется на 15 мин (`variantUrlCache`). Блок `hls` добавляется **при выдаче, мимо кэша** (подпись и статус свежие):

```
hls: { status: "none" | "encoding" | "ready",
       url?,        // только ready: подписанный master.m3u8
       profile?,    // v1 | v1-copy
       shift_ms?,   // ready, если играет алиас
       eta?, progress?,   // encoding (eta в секундах)
       direct_ok, direct: {ext, vcodec, acodec} }
```

`HLS_ENABLED` выключен или пользователь не в бете → блока `hls` нет (клиент ведёт себя как сейчас).
Фикстуры: `variant-url-none.json`, `-encoding.json`, `-ready.json`, `-mp4-direct.json`.

### GET /api/hls/:hls_id/master.m3u8?exp&sig
`application/vnd.apple.mpegurl`, `Cache-Control: no-store`. Нет ассета или не `ready` → 404, плохая подпись → 403, истекла → 410. Обновляет `last_access`. Пример — `master-v1.m3u8`.

### Воркер (`Authorization: Bearer <HLS_WORKER_TOKEN>`)
Свой `express.json({limit: "256kb"})`, регистрируется **до** общего `express.json({limit: "20kb"})` (`app.js:78`). Без токена → 401.

| Запрос | Тело → ответ | Фикстура |
|---|---|---|
| `GET /internal/hls/next` | → задание или `204` | `next-job.json` |
| `POST /internal/hls/probe` | `{job_id, fingerprint, duration_ms, format, streams}` → `{action:"alias", hls_id, shift_ms}` \| `{action:"encode", hls_id, profile}` \| `{action:"wait", retry_after_s}` | `probe-*.json` |
| `POST /internal/hls/progress` | `{job_id, stage, progress, eta_s}` | `progress-request.json` |
| `POST /internal/hls/done` | `{job_id, hls_id, profile, manifest_sha256, bytes}` | `done-request.json` |
| `POST /internal/hls/failed` | `{job_id, stage, error, retryable}` | `failed-request.json` |

`wait` — тот же ассет уже кодируется другим заданием (дедупликация). `next` выдаёт задание в аренду (`leased_until`, продлевается `progress`); просроченная аренда возвращает задание в очередь.

### CDN (`Authorization: Bearer <HLS_CDN_TOKEN>`)

| Запрос | Тело / ответ | Фикстура |
|---|---|---|
| `GET /internal/hls/pending-deletes` | → `{items:[{hls_id, profile}]}` | `pending-deletes.json` |
| `POST /internal/hls/evicted` | `[{hls_id, profile, last_access}]` — удалено с диска | `evicted-request.json` |
| `POST /internal/hls/cdn-stats` | `{disk_total, disk_used, assets, profiles, at}` | `cdn-stats-request.json` |

Физически удаляет только CDN (cron-скрипт); воркеру права удаления не нужны. Удаление — на уровне профиля; папка `<hls_id>/` удаляется, когда профилей не осталось.
Запись профиля исчезает из `hls-assets.json` после `evicted`; без профилей — исчезает ассет и его алиасы.

## 11. Очередь

Приоритеты (меньше — раньше): **0** пользователь ждёт (нажал «Смотреть», статус none) → **1** следующие серии N+1, N+2 → **2** новая раздача → **3** остальное и `v1-copy` → **4** `v1` после `v1-copy`.
Дедупликация по `ad_id|путь` и по `hls_id`; повышение приоритета у существующего задания вместо второго. Один активный фильм на воркер.

Постановка — при переходе раздачи в «готова, есть файлы» (`statusCode === 4`, `library.js:54`, в `buildLibrary()`), а не при `POST /api/magnets`. В очередь — видеофайлы раздачи (у сериалов — все серии, приоритет 2).
Если готовая версия этого фильма уже есть (`byReuseKey`), кодирование откладывается до первого «Смотреть» (задание не ставится; при «Смотреть» — приоритет 0, воркер всё равно проверит probe и получит `alias`).

`cleanup.forget(adId)` (`cleanup.js:86`) удаляет алиасы этого `adId` и его задания; ассет остаётся и находится снова по hls_id, отпечатку или правилу переиспользования. Вытеснение ассета удаляет его алиасы.

## 12. Сайт

- CSP (`pageCsp()`, `precompressed.js:17`): origin из `HLS_CDN_URL` в `connect-src` и `media-src`, добавить `worker-src 'self' blob:`.
- hls.js — локально `website/public/vendor/hls.min.js` (точная версия, лицензия рядом), без CDN.
- Лимитеры — как `mirrorUrlLimiter` (`rate-limit.js:67`: 300/мин, ключ IP или токен Activity).
- Админка: `isLibraryAdmin` (`downloads.js:48`).

## 13. Конфиг

Только `.env` (домены и секреты не в репозиторий): `HLS_ENABLED`, `HLS_CDN_URL`, `HLS_SIGN_SECRET`, `HLS_WORKER_TOKEN`, `HLS_CDN_TOKEN`, `HLS_BETA_USERS` (id через запятую; пусто — всем, если `HLS_ENABLED`).
Воркер: `SM_SITE_URL`, `HLS_WORKER_TOKEN`, `SM_ENCODER=nvenc|x265|x264`, `SM_MAX_SOURCE_GB=40`, `SM_CDN_SSH` (`hls-upload@<cdn>`). CDN: `HLS_SIGN_SECRET`, `HLS_CDN_TOKEN`, `SM_SITE_URL`.

## 14. Работа

Ветка `feature/hls`, у задачи подветка `feature/hls-tN` от неё; владелец сливает последовательно, PR в `main` только из `feature/hls`.
Документы без общих файлов: `docs/HLS.md` — T0; `infra/cdn/README.md` — T1; `encoder/README.md` — T2; `docs/HLS-SERVER.md` — T3, T4; `docs/HLS-CLIENT.md` — T5, T6 (по разделу).

```
T0 ─► T1 ┐
     T2 ─┼─► T4 ─► T5 ┐
     T3 ─┘      └► T6 ┴─► T7 ─► T8
```

T1, T2, T3 — параллельно; T5 и T6 — тоже. До T4 плеер работает на фикстурах T0. T3–T6 трогают `website/` — **только с разрешения владельца**.
Подсказки для параллельных сессий — `docs/hls-fixtures/SESSIONS.md`.
Архив — §17.

## 15. Решения при утверждении (19.11)

1. Номер серии входит в ключ переиспользования (§8): `canonical_id` сериала один на сезон.
2. `direct_ok` по `media-tags.json` (одна запись на раздачу, звук по меткам) — допущение v1.
3. Подпись мастера — на `SESSION_SECRET`, как у зеркала.
4. T7 и T8 — описать позже.

## 16. Уточнения по итогам T1–T3 (19.11)

- **probe:** у видеодорожки воркер передаёт `dovi: bool` (Dolby Vision → только `v1`). `keyframe_interval_max_s` воркер меряет, только если файл проходит в `v1-copy` по остальным условиям, иначе `null`.
- **alias:** после `action:"alias"` воркер `done` не шлёт — сервер закрывает задание и пишет алиас в ответе на probe.
- **wait и стадии:** во время `wait` воркер шлёт `progress` со `stage:"wait"` (продлевает аренду). Стадии: `probe`, `fingerprint`, `wait`, `encode`, `verify`, `upload`, `done`.
- **Раскладка:** у исходника ≤ 720p есть только `720p/`; мастер строится строго по массивам `manifest.video/audio/subtitles`. `BANDWIDTH` — пик по сегментам видео + самая тяжёлая звуковая дорожка, `AVERAGE-BANDWIDTH` — среднее. У `v1-copy` `encoder:"copy"`, видео может быть `avc1`.
- **Выкладка на CDN:** `rsync` в `.tmp/<hls_id>.<profile>.<rand>/`, затем `ssh hls-upload@<cdn> finalize <имя>` → `ok <hls_id>/<profile> <байт>`; `done` — только после `ok` (`infra/cdn/README.md`).
- **Вытеснение:** «сначала фильмы с несколькими версиями» на CDN = ассет с несколькими профилями (удаляется менее свежий). Уборка от 85 % до 80 %. Профиль, к которому обращались < 2 ч назад, не удаляется даже по `pending-deletes`. `evicted` может прийти для профиля, которого уже нет на диске (`last_access` = 0), и для неизвестных сайту — сайт принимает и пропускает.
- **Очередь:** первая сборка медиатеки после старта сервера ничего не ставит в очередь; `canonical_id` для переиспользования — только подтверждённый владельцем; совпал отпечаток, но ассет ещё кодируется → `wait`. `shift_ms` = длительность ассета − длительность файла.
- **Секрет:** `HLS_SIGN_SECRET` — только символы `A-Z a-z 0-9 . _ ~ + / = -` (идёт в строку nginx).
- **Тесты на Node 24:** только с маской или файлом — `node --test docs/hls-fixtures/*.test.js`, `node --test website/test/*.test.js`.

## 17. Холодный архив (Google Drive через rclone)

Утверждено владельцем 19.11. Архив — запасная копия готовых версий: вытесненное с CDN не пропадает, а возвращается из архива за минуты, без кодировщика и исходника.
Отдавать видео из архива нельзя — только возврат на CDN.

**Включение.** Только на CDN: `HLS_ARCHIVE_REMOTE` в `cdn.env` (например `gdrive-crypt:hls`, remote из `/etc/hls-cdn/rclone.conf`, шифрование `rclone crypt` обязательно).
Пусто — архива нет, всё как в §10/§16. Сайту новых настроек не нужно: об архиве он узнаёт из отчётов CDN.
Свой Google API client_id не обязателен (встроенный в rclone работает, но медленнее); замена — правкой `rclone.conf` без изменений кода.

**Что архивируется.** Только профиль `v1` (`v1-copy` временный). После `finalize` CDN ставит профиль в свою очередь архивации,
служба архивации делает `rclone sync /srv/hls/<id>/v1/ <REMOTE>/<id>/v1/` (sync — чтобы при перезаливе с тем же `hls_id` не оставались файлы старой версии), проверяет (`rclone check --size-only` или размеры) и сообщает сайту.
Лимит Google — 750 ГБ загрузки в сутки (`--drive-stop-on-upload-limit`, остаток — на следующем запуске).

**Состояния версии на сайте:** `ready` → (`archived: true` после отчёта) → `cold` (вытеснена, есть в архиве) → `restoring` (запрошен возврат) → `ready`.
Вытеснение версии без архива — как раньше: запись удаляется.

**API CDN** (`Bearer HLS_CDN_TOKEN`, как в §10):

| Запрос | Тело / ответ | Фикстура |
|---|---|---|
| `POST /internal/hls/archived` | `[{hls_id, profile, bytes, at}]` — копия в архиве проверена; сайт ставит у версии `archived: true`, `archive_bytes` | `archived-request.json` |
| `POST /internal/hls/evicted` | элементы получают `archived: bool`; `true` (или у версии уже `archived`) → версия становится `cold`, ассет и алиасы остаются | `evicted-request-archived.json` |
| `GET /internal/hls/pending-restores` | → `{items:[{hls_id, profile, requested_at}]}` — что вернуть на CDN | `pending-restores.json` |
| `POST /internal/hls/restored` | `[{hls_id, profile, ok, bytes?, error?}]`; `ok` → версия `ready`, `last_access` = сейчас; `ok:false` → см. ниже | `restored-request.json` |
| `GET /internal/hls/pending-deletes` | у архивной версии `archive: true` — CDN удаляет и локальную копию (если есть), и копию в архиве; отчёт — `evicted` с `archived:false` | `pending-deletes-archive.json` |

**Возврат.** «Смотреть» (variant-url) на `cold` → сайт ставит запрос возврата, ответ `hls.status: "restoring"`, `eta` ≈ `30 + archive_bytes / 40 МБ/с` секунд (фикстура `variant-url-restoring.json`).
Плеер показывает «Достаём из архива, примерно N мин» и играет зеркало при `direct_ok`, как при `encoding`. Админ может запросить возврат вручную.
CDN опрашивает `pending-restores` часто (каждые 10–20 с, отдельная служба), перед возвратом освобождает место вытеснением, качает в `.tmp/`,
проверяет `manifest.json` и переносит тем же `finalize`. Только что возвращённую версию вытеснение не трогает (как любую, к которой обращались < 2 ч).
Ошибки: `ok:false` c `error: "not_in_archive"` или 3 неудачи подряд, или нет ответа 30 мин трижды → у версии `archived: false`, запись версии удаляется,
файл ставится на кодирование заново (приоритет 0, если кто-то ждёт). Иная ошибка → повтор при следующем опросе.

**Удаление.** Удаление из админки и замена после перекодирования ставят архивную версию (в т. ч. `cold`) в `pending_delete` с `archive: true`.
`forget` раздачи ассет не удаляет (§11), поэтому и архивную копию не трогает.

**Уточнения реализации.** После неудачного возврата файл ставится на кодирование с приоритетом 0, если возврата ждал пользователь, иначе 3.
`eta` уменьшается на уже прошедшее ожидание, но не ниже 10 с. Тайм-ауты возврата проверяются при опросе `pending-restores` и при variant-url этого файла;
счёт «3 неудачи» начинается заново с каждым новым запросом возврата.

## 18. Внешние озвучки в HLS

Утверждено владельцем 19.11 (снимает «вне v1» из §1 для озвучек; внешние субтитры — по-прежнему вне).
Внешняя озвучка — `.mka` из той же раздачи (sidecar, `offsetMs = 0`) или привязанная через `/api/custom-audio` (`docs/CUSTOM-AUDIO-API.md`, свой `offsetMs`).
Браузер `.mka` не играет, поэтому кодировщик делает из неё ещё одну звуковую дорожку HLS того же ассета.

**Раскладка.** Внутри профиля: `x0/ … x7/` (до 8 внешних), в каждой `index.m3u8` + `audio.m4s` — AAC-LC стерео 128 кбит, сегменты по 6 с от 0,
длительность = длительности видео (тишина в начале/конце, лишнее обрезается). В `manifest.audio` — записи с `dir: "xN"` и
`external: { key, kind: "file"|"custom", offset_ms }`, `name` (название озвучки), `language`. Мастер строится по `manifest.audio` как раньше:
внешние дорожки — обычные `EXT-X-MEDIA TYPE=AUDIO` в той же группе, после дорожек файла.

**Ключ дорожки:** `file:<путь в раздаче>` или `custom:<trackId>`. Номер папки `xN` выдаёт сайт и не переиспользует, пока запись дорожки жива.

**Сдвиг.** Как на ТВ (`CUSTOM-AUDIO-API.md` §8): место в дорожке = место в видео − `offset`. `offset > 0` — тишина в начале (`adelay`),
`offset < 0` — первые |offset| мс дорожки пропускаются (`-ss`/`atrim`). Для ассета, играющего файл с `shift_ms` (§8), `offset = offsetMs + shift_ms`
файла, к которому привязана озвучка.

**Задание «звук».** Отдельный тип задания в той же очереди (§11), приоритет 3 (при «Смотреть» — 1):

```
GET /internal/hls/next → { job_id, type: "audio", hls_id, profile, duration_ms,
                           tracks: [{ key, dir, mirror_url, offset_ms, name, language }] }
```
Без probe и отпечатка: воркер кодирует каждую дорожку в `xN/`, выкладывает на CDN только эти папки (rsync в `<hls_id>.<profile>.a<rand>/xN/…` — путь от корня rrsync у `hls-upload`, на CDN это `/srv/hls/.tmp/`,
затем `ssh … finalize-audio <имя>` — CDN переносит каждую `xN/` в `/srv/hls/<hls_id>/<profile>/xN/` атомарной заменой, ответ `ok <hls_id>/<profile> <байт>`)
и сообщает:

```
POST /internal/hls/audio-done { job_id, hls_id, profile, tracks: [{ key, dir, codec: "mp4a.40.2", channels: 2, bytes }] }
```
Сайт дописывает записи в `manifest.audio` (повтор того же `key` — заменяет). Ошибки — `failed`, как у обычных заданий.
У задания без `type` — прежнее значение `"video"`.

**Когда ставится.**
- После `done` видео-профиля (`v1-copy` и затем `v1`) — все внешние озвучки файла и привязанные к его раздаче/серии.
- `POST /api/custom-audio/attach` и `PUT …/offset` — задание на эту дорожку для каждого ассета, где она нужна (при смене сдвига дорожка перекодируется в ту же `xN`).
- `DELETE /api/custom-audio/:trackId` и удаление раздачи-озвучки — запись убирается из `manifest.audio`, папка — в `pending_delete` уровня дорожки:
  в `pending-deletes` элемент `{ hls_id, profile, path: "xN" }`; CDN удаляет только эту папку, отчёт `evicted` с тем же `path`.
- Только `.mka` (как у клиентов, `CustomAudio.isSupported`).

**Архив (§17)** хранит профиль целиком, включая `xN/` (`rclone sync`); после `finalize-audio` профиль `v1` снова ставится в очередь архивации.

Уточнения реализации (19.11): из `.mka` берётся первая звуковая дорожка; 404/410 от зеркала на файле озвучки — `failed` без повтора
(файла нет), сеть/5xx/истёкшая подпись — с повтором. `name`, `language`, `offset_ms` сайт берёт из своего задания, в `audio-done` их нет.
В конце плейлиста дорожки бывает хвостовой сегмент ~0,02 с (кадры AAC) — это нормально.
Ключи — `custom:<id дорожки>` (вид `custom:trk_…`; в фикстурах условно `custom:42`) и `file:<путь>`. Язык: ISO 639-2 из custom-audio
переводится в 639-1 (`rus` → `ru`, `und` → без языка); у озвучки из раздачи — по тегу `[rus]` или кириллице в имени, иначе `null`.
`shift_ms` алиаса прибавляется и к озвучкам из его раздачи. Ответ `audio-done` — `{ ok, added, skipped }`.
После `finalize-audio` CDN сам заново ставит `v1` в архивацию; сайт флаг `archived` не сбрасывает.

Фикстуры: `next-job-audio.json`, `audio-done-request.json`, `pending-deletes-audio.json`, `manifest-external.json`.
