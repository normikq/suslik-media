# Внедрение HLS: пошагово

Три машины: **CDN** (Европа, Linux, HDD 1 ТБ), **основной сервер** (сайт), **кодировщик** (Ubuntu + NVIDIA).
Подробности — `infra/cdn/README.md`, `encoder/README.md`, `docs/HLS-SERVER.md`, `docs/DEPLOY.md`. Контракт — `docs/HLS.md`.

Порядок: 0 → 1 (CDN) → 2 (сайт, HLS выключен) → 3 (кодировщик) → 4 (бета на себя) → 4а (архив, по желанию) → 5 (всем).
До шага 4 пользователи ничего не замечают.

## 0. Подготовка

**DNS.** A/AAAA-запись поддомена CDN (например `cdn.<ваш домен>`) на IP CDN-сервера. Нужна до certbot.

**Три секрета** (сгенерировать один раз, хранить в менеджере паролей, не в репозитории):

```bash
openssl rand -base64 33 | tr '+/' '-_'   # HLS_SIGN_SECRET  — сайт и CDN (только A-Z a-z 0-9 . _ ~ + / = -)
openssl rand -hex 32                     # HLS_WORKER_TOKEN — сайт и кодировщик
openssl rand -hex 32                     # HLS_CDN_TOKEN    — сайт и CDN
```

**Ваш id пользователя** для беты: войдите на сайт и откройте `https://<сайт>/api/session` — поле `id`.

## 1. CDN

Ubuntu 22.04+ / Debian 12+, отдельный HDD под `/srv/hls`.

**Пользователи на CDN.** `install.sh` сам создаёт `hls-upload` — только для приёма файлов от кодировщика: вход по одному ключу,
принудительная команда (rsync в `.tmp/` и `finalize`), без шелла, проброса портов и sftp. nginx работает от `www-data` и только читает `/srv/hls`,
уборка (`hls-evict`) — служба systemd. Для себя заведите отдельного администратора с `sudo` и ключом и закройте вход root и по паролю
(`install.sh` этого не делает):

```bash
sudo adduser <вы> && sudo usermod -aG sudo <вы>      # ключ — в /home/<вы>/.ssh/authorized_keys
# /etc/ssh/sshd_config.d/10-hardening.conf:
#   PermitRootLogin no
#   PasswordAuthentication no
sudo sshd -t && sudo systemctl reload ssh            # проверить вход новой учёткой в другом окне, не закрывая текущее
```

```bash
git clone https://github.com/normikq/suslik-media.git && cd suslik-media
git checkout feature/hls          # или main после слияния PR
cd infra/cdn
sudo ./install.sh                 # первый запуск создаёт /etc/hls-cdn/cdn.env и останавливается
sudo nano /etc/hls-cdn/cdn.env
```

В `cdn.env`:

```
SM_SITE_URL=https://<сайт, с путём, если сервер не в корне>
HLS_CDN_TOKEN=<из шага 0>
HLS_SIGN_SECRET=<из шага 0>
HLS_CDN_DOMAIN=cdn.<ваш домен>
HLS_CORS_ORIGIN=https://<домен сайта>      # только origin, без пути
CERTBOT_EMAIL=<почта для Let's Encrypt>
```

Публичный ключ кодировщика появится в шаге 3. Можно поставить CDN сейчас без него и добавить ключ потом (ниже).

```bash
lsblk                                            # найти диск, например /dev/sdb1 (размечен и отформатирован заранее)
sudo ./install.sh --disk /dev/sdb1 --sample      # с ключом: добавить --upload-key "ssh-ed25519 AAAA… encoder"
```

`install.sh` ставит пакеты, монтирует диск в `/srv/hls` с `noatime`, nginx с подписанными ссылками и HTTP/2, сертификат certbot,
пользователя `hls-upload` (только rsync в `.tmp/` и `finalize`), ufw (порт SSH, 80, 443), fail2ban, таймер `hls-evict.timer` (раз в 15 мин).
SSH на другом порту: `SSH_PORT=2222 sudo ./install.sh …`. `--sample` кладёт тестовый ассет `0000000000000000` для проверки.

**Проверка:**

```bash
CDN_URL=https://cdn.<ваш домен> HLS_SIGN_SECRET=<секрет> CORS_ORIGIN=https://<домен сайта> \
  /usr/local/lib/hls-cdn/test.sh        # все строки ok: 206 на Range, 403, 410, CORS, preflight
sudo /usr/local/lib/hls-cdn/evict.sh --dry-run
systemctl list-timers hls-evict.timer --no-pager
```

**Ключ кодировщика позже:** в `/home/hls-upload/.ssh/authorized_keys` (600, `hls-upload:hls-upload`) строка

```
command="/usr/local/lib/hls-cdn/hls-upload-shell",restrict ssh-ed25519 AAAA… encoder
```

## 2. Основной сервер (сайт)

### 2.1 nginx — проверить `/internal/hls/`
Кодировщик и CDN ходят на `https://<сайт>/internal/hls/*`. Если nginx отдаёт в Node всё подряд — ничего делать не надо. Проверка (после выкладки кода):

```bash
curl -si https://<сайт>/internal/hls/next | head -1    # нужно 401 или 503 от Node (JSON), не 404 от nginx
```

Если 404 (например, сервер под путём и проксируются только `/…/api/` и `/…/media/`) — добавить рядом с `location` для `api`:

```nginx
location /internal/hls/ {              # под путём: location /<путь>/internal/hls/ { proxy_pass http://suslik_node/internal/hls/; … }
    proxy_pass http://suslik_node;     # тот же upstream, что у /api/
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    client_max_body_size 300k;
    proxy_read_timeout 60s;
}
```

`sudo nginx -t && sudo systemctl reload nginx`. http2 на основном сервере по-прежнему не включать. `/api/hls/…` идёт через существующий `location` для `api`.

### 2.2 Выложить код с выключенным HLS
В `.env` сайта пока **нет** `HLS_ENABLED` — код ведёт себя как раньше.

```bash
# ветку до слияния PR:
gh api -X POST repos/normikq/suslik-media/actions/workflows/website-deploy.yml/dispatches -f ref=main -f 'inputs[ref]=feature/hls'
# после слияния PR — как обычно, ref main
```

Или с ПК: `scripts/deploy-website.sh --dry-run`, затем `scripts/deploy-website.sh` (`docs/DEPLOY.md`). Скрипт сам перезапускает сайт, при неответе откатывает.
Проверить: сайт открывается, фильм играет как раньше; `curl` из 2.1 даёт не 404.

### 2.3 Конфиг HLS
Дописать в `.env` сайта (тот же файл, где `SESSION_SECRET`):

```
HLS_CDN_URL=https://cdn.<ваш домен>
HLS_SIGN_SECRET=<из шага 0>
HLS_WORKER_TOKEN=<из шага 0>
HLS_CDN_TOKEN=<из шага 0>
HLS_BETA_USERS=<ваш id>
HLS_ENABLED=true
```

**Важно:** пустой `HLS_BETA_USERS` при `HLS_ENABLED=true` = HLS для всех. Поэтому `HLS_BETA_USERS` ставится раньше или одновременно с `HLS_ENABLED`.

Перезапустить сайт так же, как его перезапускает выкладка (`pm2 restart … --update-env` или `systemctl restart …`).
В журнале сайта не должно быть предупреждения про символы `HLS_SIGN_SECRET`.
Данные HLS появятся в `DATA_DIR`: `hls-assets.json`, `hls-aliases.json`, `hls-queue.json`. Миграций нет.

Проверка:
```bash
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer <HLS_WORKER_TOKEN>" https://<сайт>/internal/hls/next  # 204 — очередь пуста
curl -s -H "Authorization: Bearer <HLS_CDN_TOKEN>" https://<сайт>/internal/hls/pending-deletes                     # {"items":[]}
sudo systemctl start hls-evict.service && journalctl -u hls-evict -n 20                                           # на CDN: отчёт ушёл без ошибок
```
Админка: `https://<сайт>/hls-admin.html` (под учёткой администратора) — «HLS включён», ничего не «не настроено», место на CDN видно.

После старта сайта медиатека в очередь **не** ставится целиком: в очередь попадают новые готовые раздачи и то, что нажали «Смотреть».

## 3. Кодировщик (Ubuntu + NVIDIA)

Кодировщик работает от отдельного системного пользователя `suslik-encoder` без пароля и входа. Ваш ключ и ваша учётка
на CDN ему не нужны. На CDN он заходит только как `hls-upload`, а там разрешены лишь rsync в `.tmp/` и `finalize` (шаг 1).

**Нужно:**
```bash
sudo ubuntu-drivers install && sudo reboot          # драйвер NVIDIA, затем nvidia-smi
sudo apt install -y rsync openssh-client git curl xz-utils
# Node.js ≥ 20 (в Ubuntu 24.04 apt даёт 18 — ставить из NodeSource или nvm для root):
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash - && sudo apt install -y nodejs
# ffmpeg ≥ 6.1 с hevc_nvenc и zscale (apt-шный обычно старый или без nvenc) — статическая сборка BtbN:
sudo mkdir -p /opt/ffmpeg && curl -L https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linux64-gpl.tar.xz \
  | sudo tar -xJ -C /opt/ffmpeg --strip-components=1
/opt/ffmpeg/bin/ffmpeg -hide_banner -encoders | grep nvenc          # есть hevc_nvenc
/opt/ffmpeg/bin/ffmpeg -hide_banner -filters  | grep -E 'zscale|tonemap'
```

**Код** (копия только для службы, владелец root — пользователь кодировщика его не меняет):
```bash
sudo git clone https://github.com/normikq/suslik-media.git /opt/suslik
sudo git -C /opt/suslik checkout feature/hls            # после слияния PR — main
cd /opt/suslik/encoder && npm test                      # зелёное; зависимостей нет
```

**Пользователь и ключ для CDN:**
```bash
sudo useradd --system --create-home --home-dir /var/lib/suslik-encoder --shell /usr/sbin/nologin suslik-encoder
sudo -u suslik-encoder install -d -m 700 /var/lib/suslik-encoder/.ssh /var/lib/suslik-encoder/work
sudo -u suslik-encoder ssh-keygen -t ed25519 -f /var/lib/suslik-encoder/.ssh/hls-upload -N "" -C encoder
sudo cat /var/lib/suslik-encoder/.ssh/hls-upload.pub      # → на CDN в authorized_keys (шаг 1)
sudo -u suslik-encoder sh -c 'ssh-keyscan -H cdn.<ваш домен> >> ~/.ssh/known_hosts'   # SSH не на 22: ssh-keyscan -p <порт>
```
Проверить отпечаток ключа хоста: на CDN `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` должен совпасть с
`sudo -u suslik-encoder ssh-keygen -lF cdn.<ваш домен>`.

Пробный вход (ожидаемо отказ по имени — значит ключ и forced command работают):
```bash
sudo -u suslik-encoder ssh -i /var/lib/suslik-encoder/.ssh/hls-upload hls-upload@cdn.<ваш домен> finalize test
```

**Настройки** — `/etc/suslik-encoder.env` (`sudo install -m 600 /dev/null /etc/suslik-encoder.env`, затем редактировать):
```
SM_SITE_URL=https://<сайт, с путём, если сервер не в корне>
HLS_WORKER_TOKEN=<из шага 0>
SM_ENCODER=nvenc
SM_CDN_SSH=hls-upload@cdn.<ваш домен>
SM_CDN_SSH_KEY=/var/lib/suslik-encoder/.ssh/hls-upload
# SM_CDN_SSH_PORT=2222                 # если SSH на CDN не на 22
SM_FFMPEG=/opt/ffmpeg/bin/ffmpeg
SM_FFPROBE=/opt/ffmpeg/bin/ffprobe
SM_WORK_DIR=/var/lib/suslik-encoder/work
SM_LOG_DIR=/var/log/suslik-encoder
```
Под `SM_WORK_DIR` нужно место на результат одного фильма (обычно 3–6 ГБ): исходник не скачивается, ffmpeg читает его с зеркала.

**Пробный запуск одного задания:**
```bash
sudo install -d -o suslik-encoder -g suslik-encoder /var/log/suslik-encoder
sudo -u suslik-encoder env $(sudo grep -v '^#' /etc/suslik-encoder.env | xargs) SM_ONCE=1 node /opt/suslik/encoder/index.js
```
Очередь пуста — выйдет с «204». Чтобы было что кодировать: на сайте (вы в бете — шаг 4) откройте фильм → «Смотреть».

**Служба** — `/etc/systemd/system/suslik-encoder.service`:
```ini
[Unit]
Description=Suslik Media HLS encoder
After=network-online.target
Wants=network-online.target

[Service]
User=suslik-encoder
Group=suslik-encoder
EnvironmentFile=/etc/suslik-encoder.env
WorkingDirectory=/opt/suslik/encoder
ExecStart=/usr/bin/node /opt/suslik/encoder/index.js
Restart=always
RestartSec=30
KillSignal=SIGTERM
TimeoutStopSec=30
LogsDirectory=suslik-encoder
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=/var/lib/suslik-encoder /var/log/suslik-encoder
PrivateTmp=yes

[Install]
WantedBy=multi-user.target
```
```bash
sudo systemctl daemon-reload && sudo systemctl enable --now suslik-encoder
journalctl -u suslik-encoder -f            # плюс /var/log/suslik-encoder/
```
`ProtectHome=yes` не мешает: домашняя папка пользователя — `/var/lib/suslik-encoder`, не `/home`. Доступ к видеокарте (`/dev/nvidia*`) у обычных пользователей в Ubuntu есть по умолчанию;
если `hevc_nvenc` в службе не стартует, а из консоли работает — `ls -l /dev/nvidia*` и добавить пользователя в группу-владельца (`video`/`render`).

Обновление кода: `sudo git -C /opt/suslik pull && sudo systemctl restart suslik-encoder`. Прочие переменные (`SM_MAX_SOURCE_GB=40`, `SM_CQ`, `SM_NVENC_PRESET`…) — `encoder/README.md`.

## 4. Бета на себя

1. Откройте на сайте фильм → «Смотреть». В админке появится задание «Пользователь ждёт», кодировщик возьмёт его, пойдёт прогресс.
   На сайте: «Готовим для браузера, примерно N мин» (или сразу зеркало, если файл mp4/H.264).
2. После `done` в админке ассет «готов». Откройте фильм снова:
   - **Chrome / Edge** с аппаратным HEVC, **Safari** — играет HLS (в DevTools запросы `master.m3u8` к сайту и `.m4s` к CDN, ответы 206);
   - **Firefox** — «Открыть в приложении» или зеркало.
3. Проверить: перемотка, озвучки, субтитры, продолжение с места (позиция пишется только при входе через Discord), смена серии.
4. Консоль браузера — без ошибок CSP. На CDN `tail -f /var/log/nginx/hls-access.log` — обращения есть.
5. Повторное «Смотреть» того же фильма из другой раздачи → в журнале кодировщика `alias`, без кодирования.

Если что-то не так — шаг 6 (откат), это не задевает обычный просмотр.

## 4а. Архив на Google Drive (по желанию)

Запасная копия готовых фильмов (`docs/HLS.md` §17): вытесненное с CDN не пропадает, по «Смотреть» CDN возвращает его из архива за минуты,
без кодировщика и исходника. Включается только на CDN, сайту настройки не нужны. Подробно — `infra/cdn/README.md`, раздел «Архив на Google Drive».

1. **rclone ≥ 1.54** на CDN: `rclone version`; если старее — `curl https://rclone.org/install.sh | sudo bash`.
2. **Remote** (`sudo rclone config --config /etc/hls-cdn/rclone.conf`):
   - `gdrive`, тип `drive`, `client_id`/`client_secret` — **пусто** (пока нет своего ключа API работает встроенный, медленнее);
     на вопрос про браузер — `n`, команду `rclone authorize "drive" "…"` выполнить на ПК с браузером, токен вставить обратно;
   - `gdrive-crypt`, тип `crypt`, `remote = gdrive:suslik-hls`, шифрование имён и папок, пароль и соль — сгенерировать.
   **Пароль и соль crypt сохранить в менеджер паролей** — без них архив не прочитать.
3. Проверка: `sudo rclone --config /etc/hls-cdn/rclone.conf lsd gdrive-crypt:` (в Диске видны только зашифрованные имена).
4. Включение:
   ```bash
   sudo nano /etc/hls-cdn/cdn.env      # HLS_ARCHIVE_REMOTE=gdrive-crypt:hls   (по желанию HLS_ARCHIVE_BWLIMIT=20M)
   cd suslik-media/infra/cdn && sudo ./install.sh
   sudo /usr/local/lib/hls-cdn/hls-archive.sh --enqueue-existing     # однократно: то, что уже на диске
   ```
5. Наблюдение: `journalctl -u hls-archive -n 50`, `journalctl -u hls-restore -f`; в админке — сводка «в архиве», у версий метка «есть в архиве».
   Проверка возврата: в админке у версии «в архиве» → «Достать из архива» (или дождаться вытеснения и нажать «Смотреть»).

Лимит Google — 750 ГБ загрузки в сутки: первая выгрузка всего диска займёт больше суток, rclone сам продолжит на следующих запусках.
**Свой ключ API позже:** OAuth client (Desktop) в Google Cloud Console + включить Drive API, вписать `client_id`/`client_secret` в секцию `[gdrive]`
`/etc/hls-cdn/rclone.conf`, `sudo rclone config reconnect gdrive: --config /etc/hls-cdn/rclone.conf`. Код и архив не меняются.

## 5. Всем

Добавить id в `HLS_BETA_USERS` через запятую или убрать строку целиком (= все), перезапустить сайт.
Нагрузку смотреть в админке (очередь, место на CDN): уборка начинается при 85 % диска и чистит до 80 %.

## 6. Откат и обслуживание

| Что | Как |
|---|---|
| Выключить HLS для всех | `HLS_ENABLED=false` в `.env`, перезапустить сайт. Плеер вернётся к прежнему поведению, зеркало работает как было |
| Выключить архив | `HLS_ARCHIVE_REMOTE=` в `cdn.env`, `sudo ./install.sh` — новые копии не делаются, сделанные остаются |
| Остановить кодирование | `sudo systemctl stop suslik-encoder` (задание вернётся в очередь по истечении аренды) |
| Откатить код сайта | `docs/DEPLOY.md` (резервная копия `.deploy/backup-<время>.tgz`) |
| Ассет плохой | админка → «Перекодировать с этой раздачи» или «Удалить» (удаляется на CDN при следующем запуске `evict`) |
| Сменить `HLS_SIGN_SECRET` | одновременно в `.env` сайта и `/etc/hls-cdn/cdn.env`, затем `sudo ./install.sh` на CDN (перегенерирует `/etc/nginx/hls-secret.conf`) и перезапуск сайта; открытые плееры получат 403 и возьмут новую ссылку |
| Сменить токены | `.env` сайта + `/etc/suslik-encoder.env` + `systemctl restart suslik-encoder` / `cdn.env` |

## 7. Если не работает

| Признак | Где смотреть |
|---|---|
| Кодировщик: `401` | `HLS_WORKER_TOKEN` не совпадает с `.env` сайта |
| Кодировщик: `404` на `/internal/hls/next` | nginx основного сервера не проксирует `/internal/hls/` (2.1) |
| `finalize: неожиданный ответ` / `Permission denied` | ключ не в `authorized_keys`, или хоста CDN нет в `known_hosts` пользователя `suslik-encoder`; на CDN `journalctl -t hls-upload -t hls-finalize` |
| Плеер: `403` на `.m4s` | `HLS_SIGN_SECRET` на сайте и CDN разные; на CDN проверить `test.sh` |
| Плеер: `410` | ссылка старше 12 ч (плеер сам переподписывает) или часы на CDN/сайте расходятся — `timedatectl` |
| Плеер: CORS в консоли | `HLS_CORS_ORIGIN` в `cdn.env` ≠ origin сайта (схема + домен, без пути) |
| Ошибка CSP в консоли | `HLS_CDN_URL` в `.env` сайта не задан или с другим доменом |
| В админке «место на CDN» пустое | `HLS_CDN_TOKEN` / `SM_SITE_URL` в `cdn.env`; `journalctl -u hls-evict` |
| ffmpeg падает на HDR | ffmpeg без `zscale` — нужна сборка «full» (gyan.dev) |
