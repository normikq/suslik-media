# CDN для HLS (T1)

Машина в Европе (гигабит, HDD 1 ТБ), которая раздаёт медиаплейлисты и сегменты `.m4s` плееру сайта.
Контракт — `docs/HLS.md` (§6 подписи, §10 API CDN, §17 холодный архив). Здесь — установка и эксплуатация.

```
кодировщик ──rsync/ssh (hls-upload)──► /srv/hls/.tmp/<id>.<profile>.<rand>/ ──finalize (mv)──► /srv/hls/<hls_id>/<profile>/
плеер ──https /hls/<sig>/<exp>/<hls_id>/<profile>/<путь>──► nginx (secure_link) ──► /srv/hls/<hls_id>/<profile>/<путь>
evict.sh (таймер 15 мин) ──► сайт: GET pending-deletes, POST evicted, POST cdn-stats
hls-archive.sh (таймер 5 мин) ──rclone sync──► Google Drive (crypt) ──► сайт: POST archived          (если задан архив)
hls-restore.sh (служба, опрос 15 с) ◄── сайт: GET pending-restores; rclone copy ◄── архив; POST restored
```

Домены и секреты в репозиторий не попадают: в файлах только `cdn.example.org` / `site.example.org`, настоящие значения — в `/etc/hls-cdn/cdn.env` на сервере.

## Файлы

| Файл | Что |
|---|---|
| `nginx/hls-cdn.conf.template` | сайт nginx (`/etc/nginx/conf.d/hls-cdn.conf`), подстановка `envsubst` |
| `nginx/hls-secret.conf.template` | секрет подписи (`/etc/nginx/hls-secret.conf`, 0640) |
| `nginx/logrotate-hls` | ротация `hls-access.log` (evict.sh читает `.log` и `.log.1`) |
| `bin/hls-upload-shell` | принудительная команда ssh для `hls-upload`: только rsync в `.tmp/` и `finalize` |
| `bin/hls-finalize` | проверка и атомарный перенос загрузки в `/srv/hls/<hls_id>/<profile>/` |
| `evict.sh` | уборка диска и отчёты сайту |
| `systemd/hls-evict.{service,timer}` | запуск evict.sh раз в 15 мин |
| `hls-archive.sh`, `systemd/hls-archive.{service,timer}` | копия v1 в холодный архив раз в 5 мин |
| `hls-restore.sh`, `systemd/hls-restore.service` | возврат версий из архива (служба, опрос сайта) |
| `lib-archive.sh` | общее для hls-archive.sh и hls-restore.sh |
| `fail2ban/` | sshd и перебор подписей (много 403 с одного IP) |
| `install.sh` | установка на Ubuntu 22.04+ / Debian 12+ |
| `cdn.env.example` | образец `/etc/hls-cdn/cdn.env` |
| `test.sh` | проверка работающего CDN; `--offline` — только вектор подписи |
| `make-sample.sh` | образец ассета `0000000000000000/v1` для test.sh |
| `test/offline.sh` | все офлайн-проверки (вектор, evict, finalize, архив); работает в Git Bash на Windows |
| `test/fake-rclone`, `test/fake-curl` | заглушки rclone (архив — локальная папка) и сайта для офлайн-проверок |

## Установка

1. **Диск.** HDD размечается руками, один раз (скрипт не форматирует):
   ```
   lsblk                                  # найти диск, например /dev/sdb
   parted /dev/sdb mklabel gpt mkpart hls ext4 0% 100%
   mkfs.ext4 -m 0 -T largefile -L hls /dev/sdb1
   ```
   `install.sh --disk /dev/sdb1` пропишет в `/etc/fstab` `UUID=… /srv/hls ext4 defaults,noatime,nofail 0 2` и смонтирует.
   `noatime` — чтобы чтение сегментов не превращалось в запись на HDD; «последнее обращение» evict.sh берёт из журнала nginx, а не из atime.
2. **DNS:** A/AAAA-запись домена CDN на эту машину (нужна до certbot).
3. **Настройки:** скопировать каталог `infra/cdn/` на сервер вместе с `docs/hls-fixtures/vectors.json` (или весь репозиторий), затем
   ```
   sudo ./install.sh            # первый раз создаст /etc/hls-cdn/cdn.env и остановится
   sudo nano /etc/hls-cdn/cdn.env
   ```
   `HLS_SIGN_SECRET` и `HLS_CDN_TOKEN` — те же, что в `.env` сайта; секрет — только `A-Z a-z 0-9 . _ ~ + / = -`, не короче 16
   (он попадает в строку nginx, `$` и кавычки там недопустимы). Сгенерировать: `openssl rand -base64 33 | tr '+/' '-_'`.
4. **Установка:**
   ```
   sudo ./install.sh --disk /dev/sdb1 --upload-key "ssh-ed25519 AAAA… encoder" --sample
   ```
   Что делается: пакеты (`nginx rsync jq curl ufw fail2ban certbot gettext-base`), диск, пользователь `hls-upload`, rrsync,
   nginx (сначала только порт 80 для certbot, после выпуска сертификата — полный конфиг), certbot `--webroot` (продление — таймер пакета certbot,
   после продления `systemctl reload nginx`), ufw (ssh, 80, 443; порт ssh — `SSH_PORT=2222 ./install.sh`), fail2ban, logrotate, таймер evict.sh.
   Повторный запуск безопасен: так же обновляются конфиг и скрипты после правок в репозитории.
5. **Проверка:**
   ```
   CDN_URL=https://cdn.example.org HLS_SIGN_SECRET=… CORS_ORIGIN=https://site.example.org /usr/local/lib/hls-cdn/test.sh
   ```
   Образец `0000000000000000/v1` после проверки можно удалить: `rm -r /srv/hls/0000000000000000`.

Рекомендуется (скрипт не трогает): `PasswordAuthentication no` в `/etc/ssh/sshd_config`, вход только по ключам.

## nginx

- URL: `/hls/<sig>/<exp>/<hls_id>/<profile>/<путь>`; `sig = base64url_без_паддинга(md5_raw("<exp><hls_id> <secret>"))`, профиль в подпись не входит.
- В location с `secure_link` захваты именованные (`$hls_sig`, `$hls_exp`, `$hls_hid` = `$1..$3` контракта): безымянный захват `rewrite` их не затирает.
  Затем `rewrite ^/hls/[^/]+/[0-9]+/(.*)$ /$1 break;` и файл берётся из `root /srv/hls` (без `alias`).
- Неверная подпись → **403**, верная, но истёкшая → **410**, нет файла → 404, не GET/HEAD/OPTIONS → 405. `hls_id` — строго 16 hex, `.tmp/` снаружи недоступна.
- Range/206 — штатно для статики; `sendfile`, `tcp_nopush`, `aio threads`, `open_file_cache`; HTTP/2 (на CDN нет Xray, можно).
- CORS: `Access-Control-Allow-Origin` — только для `HLS_CORS_ORIGIN` (map по `$http_origin`, плюс `Vary: Origin`),
  `Allow-Headers: Range`, `Expose-Headers: Content-Range, Content-Length, Accept-Ranges`; preflight `OPTIONS` → 204 до проверки подписи.
  CORS-заголовки ставятся и на 403/410, чтобы плеер видел код ошибки. Второй origin — ещё одна строка в `map $http_origin`.
- Кэш: `.m4s` — `public, max-age=31536000, immutable`; `.m3u8` — `max-age=60`; `.vtt` — час; `manifest.json` — `no-cache`.
- Типы: `.m3u8` → `application/vnd.apple.mpegurl`, `.m4s` → `video/mp4`, `.vtt` → `text/vtt`.
- Журнал `/var/log/nginx/hls-access.log` (`log_format hls_lru`): `<epoch.ms> <hls_id> <profile> <статус> <байт> <метод> <IP>` — для evict.sh и fail2ban.
- `listen 443 ssl http2` выбран ради nginx 1.24 из Ubuntu 24.04; на 1.25+ это предупреждение, не ошибка (можно заменить на `http2 on;`).
- `map_hash_bucket_size 128` в начале конфига — если он уже задан в `nginx.conf`, nginx скажет «duplicate»: убрать строку из `hls-cdn.conf`.

Смена секрета: новый `HLS_SIGN_SECRET` в `cdn.env` и `.env` сайта, `sudo ./install.sh` (или правка `/etc/nginx/hls-secret.conf` и `nginx -t && systemctl reload nginx`).
Ссылки, выданные со старым секретом, перестанут работать сразу (живут до 12 ч).

## Как воркер выкладывает профиль

Пользователь `hls-upload`, вход только по ключу, в `authorized_keys`:
```
command="/usr/local/lib/hls-cdn/hls-upload-shell",restrict ssh-ed25519 AAAA… encoder
```
`hls-upload-shell` пропускает ровно две команды: `rsync --server …` (через `rrsync -wo -no-del /srv/hls/.tmp/` — только запись,
только внутрь `.tmp/`, без удаления) и `finalize <имя>`. Шелл, scp, sftp, проброс портов запрещены.

Воркер (`SM_CDN_SSH=hls-upload@cdn.example.org`):
```
name="$hls_id.$profile.$(openssl rand -hex 6)"                     # profile: v1 | v1-copy
rsync -a --partial ./out/ "$SM_CDN_SSH:$name/"                      # путь относительно /srv/hls/.tmp/
ssh "$SM_CDN_SSH" finalize "$name"                                  # → «ok <hls_id>/<profile> <байт>», код 0
```
`finalize` проверяет имя (`^[0-9a-f]{16}\.(v1|v1-copy)\.[A-Za-z0-9_-]{4,64}$`), наличие `manifest.json`, отсутствие ссылок и спецфайлов,
выставляет права (папки 755, файлы 644) и переносит папку `rename`-ом в `/srv/hls/<hls_id>/<profile>/` (та же ФС — атомарно).
Если профиль уже был (перезалив), старый уходит в `.tmp/` и удаляется; между двумя `rename` есть короткое окно 404.
Блокировка `.tmp/.lock` общая с evict.sh. Брошенные загрузки старше 48 ч evict.sh удаляет сам.
Код ≠ 0 у `finalize` — профиль не принят; воркер повторяет загрузку под новым именем. `done` сайту — только после `ok`.

## evict.sh

Запускается таймером `hls-evict.timer` раз в 15 мин от root (`systemctl list-timers hls-evict.timer`, журнал — `journalctl -u hls-evict`).

1. По `hls-access.log` (и `.log.1`) обновляет «последнее обращение» к каждому `<hls_id>/<profile>` (учитываются 200/206/304)
   и хранит его в `/var/lib/hls-evict/lastaccess` — ротация журнала не теряет историю. Профиль без обращений считается по времени выкладки.
2. `GET /internal/hls/pending-deletes` → удаляет перечисленные профили. Профиля нет на диске — всё равно сообщает, что удалён (сайт уберёт запись).
3. Если диск занят больше `HLS_EVICT_HIGH` (85 %), удаляет до `HLS_EVICT_LOW` (80 %): сначала менее свежий профиль ассетов,
   у которых профилей несколько (обычно `v1-copy` рядом с `v1`), затем профили по давности последнего обращения.
4. `POST /internal/hls/evicted` — `[{hls_id, profile, last_access, archived}]` (`archived` — есть ли копия в архиве; без архива `false`); если сайт недоступен, список копится в `/var/lib/hls-evict/evicted.spool`
   и уходит при следующем запуске. `POST /internal/hls/cdn-stats` — `{disk_total, disk_used, assets, profiles, at}`.
5. Удаляет брошенные загрузки в `.tmp/`.

Профиль, к которому обращались меньше `HLS_EVICT_MIN_IDLE` (2 ч) назад, не удаляется ни по LRU, ни по pending-deletes — его смотрят прямо сейчас;
pending-delete выполнится на следующем запуске. Удаление — по профилю; папка `<hls_id>/` удаляется, когда профилей не осталось.
Адрес сайта — `SM_SITE_URL` (без схемы — `https://`), токен — `Authorization: Bearer $HLS_CDN_TOKEN` (передаётся curl через stdin, не в аргументах).

Ручной запуск:
```
sudo /usr/local/lib/hls-cdn/evict.sh --dry-run        # план без удаления и отправки
sudo systemctl start hls-evict.service                 # настоящий прогон
```
Ключи для отладки: `--root`, `--log`, `--state-dir`, `--pending-file`, `--disk-total/--disk-used`, `--high/--low`, `--min-idle`, `--now`, `--no-report` (`evict.sh --help`).

## Архив на Google Drive

Холодный архив (`docs/HLS.md` §17): запасная копия готовых `v1`. Вытесненная с диска версия не пропадает — сайт помечает её `cold`
и по «Смотреть» просит CDN вернуть её из архива (минуты, без кодировщика). Отдавать видео прямо из архива нельзя — только возврат на CDN.
Без `HLS_ARCHIVE_REMOTE` в `cdn.env` архива нет и всё работает как раньше (`evicted` с `archived:false`, службы архива не включены).

**Как устроено.**
- `hls-finalize` после приёма `v1` (не `v1-copy`) кладёт метку `<hls_id>.v1` в `/var/lib/hls-archive-queue/` — каталог 0700 пользователя `hls-upload`
  (только туда он и может писать; состояние `/var/lib/hls-evict/` остаётся закрытым, 0700 root). Метка не легла — приём всё равно успешен.
- `hls-archive.sh` (таймер раз в 5 мин, `hls-archive.timer`): `rclone sync /srv/hls/<id>/v1/ <REMOTE>/<id>/v1/` с `--drive-stop-on-upload-limit`
  и ретраями, проверка `rclone check --size-only --one-way`, строка `<id> v1 <байт> <время>` в `/var/lib/hls-evict/archived`,
  `POST /internal/hls/archived`. Отчёт не дошёл — копится в `archived.spool` и уходит при следующем запуске. Профиль удалён до архивации — снимается с очереди.
- `evict.sh`: в `evicted` поле `archived`; при нехватке места в своей группе (лишние версии, затем остальные) сначала вытесняет уже заархивированные;
  pending-deletes с `archive:true` — `rclone purge <REMOTE>/<id>/<profile>`, потом локальная копия (её может и не быть — холодная версия), отчёт `archived:false`.
  Ошибка purge — ничего не удаляется и не сообщается, повтор на следующем запуске.
- `hls-restore.sh` (служба `hls-restore`, опрос `GET /internal/hls/pending-restores` раз в `HLS_RESTORE_POLL_S` = 15 с, по одному):
  профиль уже на диске → сразу `ok:true`; иначе `evict.sh --reserve <размер>` (место под возвращаемое), `rclone copy` в `.tmp/<id>.<profile>.r<rand>/`,
  тот же `hls-finalize` от `hls-upload` (без повторной постановки в очередь архивации), «последнее обращение» = сейчас,
  `POST /internal/hls/restored`. В архиве нет — `ok:false, error:"not_in_archive"`; иная ошибка — `ok:false` с текстом (сайт повторит).
- Одна блокировка состояния `/var/lib/hls-evict/.lock` на evict.sh и записи архива; копирование в архив её не держит.

**rclone.** Нужен ≥ 1.54 (`--drive-stop-on-upload-limit`). `install.sh` ставит его из apt, если rclone нет; в Ubuntu 22.04 там 1.53 —
тогда свежий с сайта rclone (кладёт `/usr/bin/rclone`, apt больше не нужен):
```
curl https://rclone.org/install.sh | sudo bash
rclone version
```

**Настройка remote** (на CDN, от root; файл — `/etc/hls-cdn/rclone.conf`):
1. `sudo rclone config --config /etc/hls-cdn/rclone.conf` → `n` (new remote), имя `gdrive`, тип `drive`.
   `client_id` и `client_secret` — **оставить пустыми**, пока нет своего Google API client_id: работает встроенный в rclone, только медленнее (общие квоты).
   `scope` — `drive` (или `drive.file`, тогда rclone видит только свои файлы), `root_folder_id`/`service_account_file` — пусто.
2. «Use web browser to automatically authenticate?» → `n` (на сервере нет браузера). rclone напечатает команду вида `rclone authorize "drive" "…"`:
   выполнить её **на машине с браузером** (там тоже нужен rclone той же или новее версии), войти в Google, скопировать выданный токен
   и вставить его в консоль сервера. «Shared Drive» — `n`.
3. Ещё один remote: `n`, имя `gdrive-crypt`, тип `crypt`, `remote` = `gdrive:suslik-hls` (папка на Диске), `filename_encryption` = `standard`,
   `directory_name_encryption` = `true`, пароль и соль (password2) — сгенерировать (`g`, 256 бит) или задать свои.
   **Пароль и соль сохранить отдельно от сервера** (менеджер паролей): без них архив не прочитать, в `rclone.conf` они лишь обфусцированы.
4. Права: `sudo chown root:root /etc/hls-cdn/rclone.conf && sudo chmod 600 /etc/hls-cdn/rclone.conf` (install.sh делает это сам при каждом запуске).
5. Проверка:
   ```
   sudo rclone --config /etc/hls-cdn/rclone.conf lsd gdrive:
   sudo rclone --config /etc/hls-cdn/rclone.conf mkdir gdrive-crypt:hls
   sudo rclone --config /etc/hls-cdn/rclone.conf lsd gdrive-crypt:
   ```
   В веб-интерфейсе Диска в `suslik-hls/` должны быть только зашифрованные имена.

**Свой client_id позже.** Создать OAuth client (тип Desktop) в Google Cloud Console, включить Google Drive API, вписать в секцию `[gdrive]`
`/etc/hls-cdn/rclone.conf` строки `client_id = …` и `client_secret = …`, затем переавторизоваться:
`sudo rclone config reconnect gdrive: --config /etc/hls-cdn/rclone.conf` (снова через `rclone authorize` на машине с браузером).
Код и `cdn.env` не меняются; содержимое архива сохраняется (crypt-ключи те же).

**Включение.**
```
sudo nano /etc/hls-cdn/cdn.env            # HLS_ARCHIVE_REMOTE=gdrive-crypt:hls (HLS_ARCHIVE_BWLIMIT по желанию)
sudo ./install.sh                         # очередь, upload.env, таймер hls-archive, служба hls-restore
sudo /usr/local/lib/hls-cdn/hls-archive.sh --enqueue-existing   # однократно: уже лежащие на диске v1 — в очередь
```
Выключение — `HLS_ARCHIVE_REMOTE=` и `sudo ./install.sh` (таймер и служба останавливаются; копии в архиве остаются).

**Наблюдение.**
```
journalctl -u hls-archive -n 50           # «в архиве <id>/v1 (… байт)», «archived отправлен»
journalctl -u hls-restore -f              # «возвращён …», «не возвращён …: not_in_archive»
ls /var/lib/hls-archive-queue | wc -l     # сколько ждёт архивации
wc -l /var/lib/hls-evict/archived         # сколько версий в архиве
sudo rclone --config /etc/hls-cdn/rclone.conf size gdrive-crypt:hls
```
**Лимит Google — 750 ГБ загрузки в сутки** на аккаунт. Упёршись в него, rclone останавливается (код 7, в журнале «фатальная ошибка (лимит загрузки Google?)»),
остаток очереди уходит при следующих запусках. Первая выгрузка всего диска (≈ 1 ТБ) займёт больше суток — это нормально.
`HLS_ARCHIVE_BWLIMIT` (например `20M` или `"08:00,10M 23:00,off"`) — чтобы архивация не мешала раздаче.

## Проверки

Офлайн (Windows/Git Bash или Linux; нужны `bash`, `openssl`, `jq`, `curl`):
```
bash infra/cdn/test/offline.sh
```
- `test.sh --offline` — формула подписи из `test.sh` даёт `sig` из `docs/hls-fixtures/vectors.json → sign`;
- `test/evict-offline.sh` — evict.sh на фейковом дереве и журнале: порядок удаления, `--dry-run` ничего не трогает, MIN_IDLE,
  отсев 403 и мусорных строк, плохие записи pending-deletes, формат `evicted`/`cdn-stats` по фикстурам, настоящий прогон, очередь отчётов;
- `test/finalize-offline.sh` — приём загрузки, перезалив, отказы на плохие имена и команды;
- `test/archive-offline.sh` — архив с заглушками rclone и сайта: постановка в очередь из finalize (v1 — да, v1-copy — нет), копия и отчёт
  `archived` по фикстуре, снятие с очереди удалённого, spool при недоступном сайте, лимит Google (код 7), `evicted` с `archived`,
  порядок вытеснения (архивные первыми), pending-deletes с `archive:true` → purge (и ошибка purge, и холодная версия),
  возврат: успех (файлы, lastaccess, место, `restored ok`), `not_in_archive`, профиль уже на диске; без `HLS_ARCHIVE_REMOTE` — всё выключено.

Против работающего CDN — `test.sh` (переменные в шапке файла): 200 и `Accept-Ranges`, 206 с `Content-Range`, типы и `Cache-Control`,
404/403/410/405, подделка `exp` и `hls_id`, `../`, CORS на ответах и на 403, чужой Origin, preflight 204, HTTP/2.
`CURL_INSECURE=1` — для стенда с самоподписанным сертификатом.

## Эксплуатация

- Занятость: `df -h /srv/hls`; что занимает: `du -sh /srv/hls/* | sort -h | tail`.
- Удалить профиль вручную: `rm -r /srv/hls/<hls_id>/<profile>` и сообщить сайту — проще добавить его в pending-deletes на сайте и дождаться evict.sh.
- Логи: `/var/log/nginx/hls-access.log`, `hls-error.log`; блокировки — `fail2ban-client status nginx-hls-sig`.
- Сертификат: `certbot certificates`, проверка продления — `certbot renew --dry-run`.
- Обновление скриптов и конфига: `git pull` и `sudo ./install.sh` (данные и `cdn.env` не трогаются).
