# CDN для HLS (T1)

Машина в Европе (гигабит, HDD 1 ТБ), которая раздаёт медиаплейлисты и сегменты `.m4s` плееру сайта.
Контракт — `docs/HLS.md` (§6 подписи, §10 API CDN). Здесь — установка и эксплуатация.

```
кодировщик ──rsync/ssh (hls-upload)──► /srv/hls/.tmp/<id>.<profile>.<rand>/ ──finalize (mv)──► /srv/hls/<hls_id>/<profile>/
плеер ──https /hls/<sig>/<exp>/<hls_id>/<profile>/<путь>──► nginx (secure_link) ──► /srv/hls/<hls_id>/<profile>/<путь>
evict.sh (таймер 15 мин) ──► сайт: GET pending-deletes, POST evicted, POST cdn-stats
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
| `fail2ban/` | sshd и перебор подписей (много 403 с одного IP) |
| `install.sh` | установка на Ubuntu 22.04+ / Debian 12+ |
| `cdn.env.example` | образец `/etc/hls-cdn/cdn.env` |
| `test.sh` | проверка работающего CDN; `--offline` — только вектор подписи |
| `make-sample.sh` | образец ассета `0000000000000000/v1` для test.sh |
| `test/offline.sh` | все офлайн-проверки (вектор, evict, finalize); работает в Git Bash на Windows |

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
4. `POST /internal/hls/evicted` — `[{hls_id, profile, last_access}]`; если сайт недоступен, список копится в `/var/lib/hls-evict/evicted.spool`
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

## Проверки

Офлайн (Windows/Git Bash или Linux; нужны `bash`, `openssl`, `jq`, `curl`):
```
bash infra/cdn/test/offline.sh
```
- `test.sh --offline` — формула подписи из `test.sh` даёт `sig` из `docs/hls-fixtures/vectors.json → sign`;
- `test/evict-offline.sh` — evict.sh на фейковом дереве и журнале: порядок удаления, `--dry-run` ничего не трогает, MIN_IDLE,
  отсев 403 и мусорных строк, плохие записи pending-deletes, формат `evicted`/`cdn-stats` по фикстурам, настоящий прогон, очередь отчётов;
- `test/finalize-offline.sh` — приём загрузки, перезалив, отказы на плохие имена и команды.

Против работающего CDN — `test.sh` (переменные в шапке файла): 200 и `Accept-Ranges`, 206 с `Content-Range`, типы и `Cache-Control`,
404/403/410/405, подделка `exp` и `hls_id`, `../`, CORS на ответах и на 403, чужой Origin, preflight 204, HTTP/2.
`CURL_INSECURE=1` — для стенда с самоподписанным сертификатом.

## Эксплуатация

- Занятость: `df -h /srv/hls`; что занимает: `du -sh /srv/hls/* | sort -h | tail`.
- Удалить профиль вручную: `rm -r /srv/hls/<hls_id>/<profile>` и сообщить сайту — проще добавить его в pending-deletes на сайте и дождаться evict.sh.
- Логи: `/var/log/nginx/hls-access.log`, `hls-error.log`; блокировки — `fail2ban-client status nginx-hls-sig`.
- Сертификат: `certbot certificates`, проверка продления — `certbot renew --dry-run`.
- Обновление скриптов и конфига: `git pull` и `sudo ./install.sh` (данные и `cdn.env` не трогаются).
