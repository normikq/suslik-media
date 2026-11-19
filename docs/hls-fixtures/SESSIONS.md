# Параллельные сессии HLS

Каждую задачу ведёт отдельная сессия Claude Code в своём worktree от `feature/hls`
(рабочая папка `suslik-media` занята другой веткой — её не трогать):

```
git -C suslik-media worktree add -b feature/hls-t1 ../suslik-hls-t1 feature/hls
```

Общее для всех: прочитать `docs/HLS.md` целиком; контракт не менять (вопросы — владельцу, правит только T0);
домены и секреты не коммитить; файлы читать через `shunt:bulk-reader` / `shunt:bulk-outline`, тесты генерировать через `shunt:code-writer` и дожидаться ответа;
`node --test docs/hls-fixtures/` должен оставаться зелёным; коммиты с подписями из системного напоминания; пуш и PR в `feature/hls` — по команде владельца.

## Волна 1 (после утверждения T0, параллельно)

**T1 — CDN** (`infra/cdn/`, `website/` не трогает)
> Ветка feature/hls-t1. Сделай T1 из docs/HLS.md §6, §10 (CDN), §14: nginx-конфиг (`root /srv/hls`, `rewrite ^/hls/[^/]+/[0-9]+/(.*)$ /$1 break` в location с secure_link, Range/206, sendfile, tcp_nopush, aio threads, http2, CORS с preflight, immutable для .m4s, text/vtt), пользователь hls-upload с rrsync и приёмом в .tmp/ + атомарный mv, evict.sh (LRU по логу nginx, порог 85 %, сначала фильмы с несколькими версиями, evicted/cdn-stats/pending-deletes, удаление по профилю), ufw, fail2ban, certbot, noatime, `infra/cdn/README.md`. Готово: `test.sh` (206 на Range, 403, 410, CORS, preflight, вектор `vectors.json → sign`).

**T2 — воркер** (`encoder/`, Node, `website/` не трогает)
> Ветка feature/hls-t2. Сделай T2 из docs/HLS.md §5, §7, §10 (воркер): цикл next → ffprobe по зеркалу → отпечаток (Range-запросы) → probe → alias/encode/wait → ffmpeg с чтением по HTTP (`-reconnect…`) → субтитры → rsync на CDN → done; аренда и progress; SM_ENCODER=nvenc|x265|x264; лимит исходника; один фильм; перезапуск, чистка, журнал; `encoder/README.md` (NSSM, systemd, драйвер NVIDIA). Сервер до T4 — заглушка на фикстурах `docs/hls-fixtures/`. Готово: тест на образце (EXT-X-BYTERANGE, EXT-X-MAP, непрерывные смещения, выровненные ключевые кадры v1, отпечаток = `vectors.json → fingerprint`).

**T3 — серверное ядро** (`website/`, **только с разрешения владельца**)
> Ветка feature/hls-t3. Сделай T3 из docs/HLS.md §3, §4, §6, §8, §9, §11: `website/src/services/hls-store.js`, `hls.js` (hlsId, canReuse, shiftMs, fingerprintMatch, signHlsUrl, signMaster, computeDirectOk, handleProbe, очередь с приоритетами и дедупликацией, active_profile, v1-copy → v1, удаление по профилю), hash в `library.js`, триггер на `statusCode === 4`, связка с `cleanup.forget()`, `docs/HLS-SERVER.md`. Готово: `node --test website/test/hls*.test.js`, `npm run check`; кейсы из задания T3 и векторы T0.

## Волна 2

- **T4 — API** (после T1–T3): маршруты §10, CSP и `vendor/hls.min.js` §12.
- **T5 — плеер** и **T6 — админка** — параллельно после T4 (T5 может начать раньше на фикстурах `variant-url-*.json`).
- **T7, T8** — уточнить у владельца.
