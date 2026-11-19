# Внедрение HLS: пошагово

Три машины: **CDN** (Европа, Linux, HDD 1 ТБ), **основной сервер** (сайт), **кодировщик** (ПК владельца, Windows + NVIDIA).
Подробности — `infra/cdn/README.md`, `encoder/README.md`, `docs/HLS-SERVER.md`, `docs/DEPLOY.md`. Контракт — `docs/HLS.md`.

Порядок: 0 → 1 (CDN) → 2 (сайт, HLS выключен) → 3 (кодировщик) → 4 (бета на себя) → 5 (всем).
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

## 3. Кодировщик (ПК, Windows)

**Нужно:** драйвер NVIDIA (`nvidia-smi`), Node.js ≥ 20, ffmpeg ≥ 6.1 с `hevc_nvenc` и `zscale`
(`ffmpeg -hide_banner -encoders | findstr nvenc`, `ffmpeg -hide_banner -filters | findstr "zscale tonemap"`), rsync (MSYS2 `pacman -S rsync` или cwRsync), NSSM.

**Код:** отдельная копия, не рабочая папка репозитория, например `C:\suslik\` (`git clone` и `git checkout feature/hls` / `main`). Зависимостей нет: `cd C:\suslik\encoder && npm test` — должно быть зелёным.

**Ключ для CDN:**
```bat
ssh-keygen -t ed25519 -f %USERPROFILE%\.ssh\hls-upload -N "" -C encoder
type %USERPROFILE%\.ssh\hls-upload.pub
```
Строку `.pub` добавить на CDN (шаг 1, `authorized_keys`). Один раз зайти, чтобы принять ключ хоста:
`ssh -i %USERPROFILE%\.ssh\hls-upload hls-upload@cdn.<ваш домен> finalize test` — ответ об ошибке имени — это нормально, главное — хост добавлен в `known_hosts`
(тем же `ssh`, что будет в `SM_SSH`; у cwRsync/MSYS2 свой `known_hosts`).

**Пробный запуск одного задания** (`cmd`):
```bat
cd C:\suslik\encoder
set SM_SITE_URL=https://<сайт>& set HLS_WORKER_TOKEN=<токен>& set SM_CDN_SSH=hls-upload@cdn.<ваш домен>& set SM_CDN_SSH_KEY=%USERPROFILE%\.ssh\hls-upload& set SM_RSYNC=C:\msys64\usr\bin\rsync.exe& set SM_SSH=C:\msys64\usr\bin\ssh.exe& set SM_ONCE=1& node index.js
```
Очередь пуста — выйдет с «204». Чтобы было что кодировать: на сайте (вы в бете — шаг 4) откройте фильм → «Смотреть» → задание с приоритетом 0.

**Служба (NSSM):**
```bat
nssm install SuslikEncoder "C:\Program Files\nodejs\node.exe" "C:\suslik\encoder\index.js"
nssm set SuslikEncoder AppDirectory C:\suslik\encoder
nssm set SuslikEncoder AppEnvironmentExtra SM_SITE_URL=https://<сайт> HLS_WORKER_TOKEN=<токен> SM_ENCODER=nvenc SM_CDN_SSH=hls-upload@cdn.<ваш домен> SM_CDN_SSH_KEY=C:\Users\<user>\.ssh\hls-upload SM_RSYNC=C:\msys64\usr\bin\rsync.exe SM_SSH=C:\msys64\usr\bin\ssh.exe SM_FFMPEG=C:\ffmpeg\bin\ffmpeg.exe SM_FFPROBE=C:\ffmpeg\bin\ffprobe.exe
nssm set SuslikEncoder AppStopMethodConsole 30000
nssm set SuslikEncoder AppExit Default Restart
nssm set SuslikEncoder AppRestartDelay 30000
nssm set SuslikEncoder AppStdout C:\suslik\encoder\logs\service.out.log
nssm set SuslikEncoder AppStderr C:\suslik\encoder\logs\service.err.log
nssm start SuslikEncoder
```
Служба от `LocalSystem` не видит ваш `known_hosts` и ключ в профиле — либо `nssm set SuslikEncoder ObjectName .\<user> <пароль>`, либо положить ключ и `known_hosts` туда, откуда их читает выбранный `ssh`.
Журнал: `C:\suslik\encoder\logs\`. Прочие переменные (`SM_MAX_SOURCE_GB=40`, `SM_CQ`, `SM_NVENC_PRESET`…) — `encoder/README.md`.

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

## 5. Всем

Добавить id в `HLS_BETA_USERS` через запятую или убрать строку целиком (= все), перезапустить сайт.
Нагрузку смотреть в админке (очередь, место на CDN): уборка начинается при 85 % диска и чистит до 80 %.

## 6. Откат и обслуживание

| Что | Как |
|---|---|
| Выключить HLS для всех | `HLS_ENABLED=false` в `.env`, перезапустить сайт. Плеер вернётся к прежнему поведению, зеркало работает как было |
| Остановить кодирование | `nssm stop SuslikEncoder` (задание вернётся в очередь по истечении аренды) |
| Откатить код сайта | `docs/DEPLOY.md` (резервная копия `.deploy/backup-<время>.tgz`) |
| Ассет плохой | админка → «Перекодировать с этой раздачи» или «Удалить» (удаляется на CDN при следующем запуске `evict`) |
| Сменить `HLS_SIGN_SECRET` | одновременно в `.env` сайта и `/etc/hls-cdn/cdn.env`, затем `sudo ./install.sh` на CDN (перегенерирует `/etc/nginx/hls-secret.conf`) и перезапуск сайта; открытые плееры получат 403 и возьмут новую ссылку |
| Сменить токены | `.env` сайта + служба кодировщика (`nssm set … AppEnvironmentExtra`) / `cdn.env` |

## 7. Если не работает

| Признак | Где смотреть |
|---|---|
| Кодировщик: `401` | `HLS_WORKER_TOKEN` не совпадает с `.env` сайта |
| Кодировщик: `404` на `/internal/hls/next` | nginx основного сервера не проксирует `/internal/hls/` (2.1) |
| `finalize: неожиданный ответ` / `Permission denied` | ключ не в `authorized_keys`, или `known_hosts` у другого `ssh`; на CDN `journalctl -t hls-upload -t hls-finalize` |
| Плеер: `403` на `.m4s` | `HLS_SIGN_SECRET` на сайте и CDN разные; на CDN проверить `test.sh` |
| Плеер: `410` | ссылка старше 12 ч (плеер сам переподписывает) или часы на CDN/сайте расходятся — `timedatectl` |
| Плеер: CORS в консоли | `HLS_CORS_ORIGIN` в `cdn.env` ≠ origin сайта (схема + домен, без пути) |
| Ошибка CSP в консоли | `HLS_CDN_URL` в `.env` сайта не задан или с другим доменом |
| В админке «место на CDN» пустое | `HLS_CDN_TOKEN` / `SM_SITE_URL` в `cdn.env`; `journalctl -u hls-evict` |
| ffmpeg падает на HDR | ffmpeg без `zscale` — нужна сборка «full» (gyan.dev) |
