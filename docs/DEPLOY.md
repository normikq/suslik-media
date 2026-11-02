# Выкладка сайта по SSH-ключу

Сайт (`website/`) выкладывается на сервер одной командой — из GitHub Actions (workflow **Website deploy**, только вручную)
или с ПК (`scripts/deploy-website.sh`). Вход на сервер — по отдельному SSH-ключу. Ключ, адрес сервера и пути в репозиторий
не пишутся: в Actions они лежат в секретах, на ПК — в переменных окружения.

## Что делает выкладка

1. Берёт **закоммиченное** состояние `website/` нужного коммита (`git archive`), проверяет синтаксис (`npm run check`;
   в Actions ещё `npm test`).
2. Загружает архив по ssh в `<DEPLOY_PATH>/.deploy/incoming.tgz` (rsync не нужен, хватает `tar`, `ssh`, `bash` на сервере).
3. На сервере (`scripts/deploy-website-remote.sh`):
   - показывает, какие файлы изменятся и какие удалятся;
   - сохраняет копию заменяемых файлов в `.deploy/backup-<время>.tgz` (хранятся 5 последних);
   - копирует новые файлы; удаляет только те, что **сам выкладывал раньше** и которых больше нет в git
     (список прошлой выкладки — `.deploy/manifest`);
   - `npm ci --omit=dev` — только если поменялся `package-lock.json` или нет `node_modules`;
   - перезапускает сайт и ждёт ответа на `http://127.0.0.1:<PORT из .env>/` (до ~25 с);
   - сайт не ответил или команда упала — возвращает прежние файлы, перезапускает ещё раз, выкладка считается неудачной.
4. Записывает выложенный коммит в `.deploy/last`.

**Не трогает:** `.env`, данные сервера (`sessions.json`, `users.json`, `devices.json` и прочие `*.json`, `updates/`, `changelog/`,
`reports/`, `poster-cache/`), `node_modules/` (кроме `npm ci`), nginx. Если `DATA_DIR` не задан, данные лежат прямо в папке
сайта — поэтому выкладка копирует только файлы из git и никогда не чистит папку целиком.

Пробный запуск (`--dry-run` / галочка «Пробный запуск») загружает архив и показывает список изменений, ничего не меняя.

## Настройка — один раз

### 1. Ключ только для выкладки

На своём ПК (Git Bash / PowerShell / Linux), **без пароля** — им будет пользоваться GitHub Actions:

```bash
ssh-keygen -t ed25519 -N "" -C "suslik-deploy" -f ~/.ssh/suslik_deploy
```

Получатся `~/.ssh/suslik_deploy` (закрытый — никуда не коммитить, только в секрет) и `~/.ssh/suslik_deploy.pub` (открытый).

### 2. Пользователь и ключ на сервере

Лучше выкладывать не от root, а от пользователя, которому принадлежит папка сайта (тот, от кого запущен node).
Добавьте открытый ключ в его `~/.ssh/authorized_keys` одной строкой. Префикс `restrict` запрещает через этот ключ
проброс портов, агент и X11 (выкладке они не нужны):

```
restrict ssh-ed25519 AAAA…ключ… suslik-deploy
```

```bash
# на сервере, от этого пользователя
mkdir -p ~/.ssh && chmod 700 ~/.ssh
nano ~/.ssh/authorized_keys     # вставить строку выше
chmod 600 ~/.ssh/authorized_keys
```

Проверка с ПК: `ssh -i ~/.ssh/suslik_deploy <user>@<сервер> 'cd <папка сайта> && ls server.js'`.

### 3. Как перезапускается сайт

Если `DEPLOY_RESTART_CMD` не задан, скрипт ищет сам:
- процесс **pm2**, запущенный из папки сайта (`pm2 restart <имя> --update-env`);
- иначе **службу systemd** с `WorkingDirectory=<папка сайта>` (`sudo -n systemctl restart <служба>`).

Для systemd от не-root пользователя нужно разрешить именно эту команду без пароля (`sudo visudo -f /etc/sudoers.d/suslik-deploy`):

```
deploy ALL=(root) NOPASSWD: /usr/bin/systemctl restart suslik-media.service
```

(`deploy` и `suslik-media.service` — замените на своих пользователя и службу; путь к systemctl — `command -v systemctl`.)
Иначе задайте команду явно в `DEPLOY_RESTART_CMD`, например `pm2 restart suslik` или `sudo -n systemctl restart suslik-media`.
Node из **nvm** подхватывается сам (`~/.nvm/nvm.sh`).

### 4. Ключ сервера (known_hosts)

Чтобы выкладка не подключилась к подменённому серверу, запишите его ключ заранее:

```bash
ssh-keyscan -p 22 <сервер>          # с ПК; сверьте отпечаток с `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` на сервере
```

Вывод целиком — в секрет `DEPLOY_KNOWN_HOSTS`. Без него workflow берёт ключ сервера `ssh-keyscan` при каждом запуске
и пишет предупреждение.

### 5. Секреты GitHub

Settings → Secrets and variables → Actions (или Settings → Environments → **website** → Environment secrets;
в окружении можно включить «Required reviewers» — тогда каждую выкладку нужно подтвердить).

| Секрет | Обязателен | Что |
|---|---|---|
| `DEPLOY_SSH_KEY` | да | содержимое `~/.ssh/suslik_deploy` целиком, с `-----BEGIN…` и `-----END…` |
| `DEPLOY_HOST` | да | домен или IP сервера |
| `DEPLOY_USER` | да | пользователь SSH |
| `DEPLOY_PATH` | да | абсолютный путь к папке сайта на сервере (где `server.js` и `.env`) |
| `DEPLOY_KNOWN_HOSTS` | желательно | вывод `ssh-keyscan` (шаг 4) |
| `DEPLOY_PORT` | нет | порт SSH, по умолчанию 22 |
| `DEPLOY_RESTART_CMD` | нет | команда перезапуска (шаг 3) |
| `DEPLOY_HEALTH_URL` | нет | адрес проверки на сервере, по умолчанию `http://127.0.0.1:<PORT из .env>/` |

## Выкладка

**Из GitHub:** Actions → **Website deploy** → Run workflow → ветка/коммит (по умолчанию `main`), при желании «Пробный запуск».

Или из консоли:

```bash
gh api -X POST repos/normikq/suslik-media/actions/workflows/website-deploy.yml/dispatches -f ref=main -f 'inputs[ref]=main'
```

**С ПК** (Git Bash на Windows, Linux, macOS), из корня репозитория:

```bash
export DEPLOY_HOST=… DEPLOY_USER=… DEPLOY_PATH=/путь/к/website DEPLOY_KEY=~/.ssh/suslik_deploy
scripts/deploy-website.sh --dry-run   # посмотреть, что изменится
scripts/deploy-website.sh
```

Выкладывается `DEPLOY_REF` (по умолчанию `HEAD`); незакоммиченные правки не уходят — скрипт об этом предупредит.
Остальные переменные — `scripts/deploy-website.sh --help`.

Автовыкладки при пуше в `main` нет: сервер обновляется только по команде владельца.

## Откат вручную

```bash
cd <папка сайта>
ls -t .deploy/backup-*.tgz | head      # копии до каждой выкладки
tar -xzf .deploy/backup-<время>.tgz     # вернуть файлы
cp .deploy/backup-<время>.manifest .deploy/manifest
# если менялся package-lock.json: npm ci --omit=dev
# перезапустить сайт (pm2 restart … / systemctl restart …)
```

Проще — выложить нужный старый коммит: Run workflow с `ref` = этот коммит.

## Первая выкладка в новую папку

Создайте папку и положите туда `.env` (образец — `website/.env.example`), заведите службу или процесс pm2 с этой папкой —
дальше выкладка сама скопирует код и поставит зависимости. В непустую папку без `server.js` скрипт выкладывать
откажется (защита от ошибки в `DEPLOY_PATH`). При переезде на новый сервер — `docs/MIGRATION.md`, затем поменять секреты.

## Ограничения

- Проверка ответа понимает «сайт отвечает» как любой код 2xx/3xx/401/403/404; если перезапуск не дождался остановки
  старого процесса, ответить может и старый (pm2 и `systemctl restart` обычно ждут).
- nginx не перезагружается: правки `NGINX-MIRROR.md` и `docs/MIGRATION.md` применяются руками.
