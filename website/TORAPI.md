# RuTracker и Kinozal без аккаунта через TorAPI

TorAPI (https://github.com/Lifailon/TorAPI) — маленький Node-сервер, который ищет на RuTracker/Kinozal и
отдаёт magnet по id раздачи. Ставится на тот же VPS рядом с сайтом и слушает только 127.0.0.1 — снаружи не виден.

Сайт использует TorAPI для трекера, у которого в `.env` НЕ задан аккаунт/cookie (`RUTRACKER_*`, `KINOZAL_*`).
Если аккаунт задан — работает как раньше, напрямую.

## 0. Проверка: доступен ли RuTracker с VPS

```bash
curl -sI https://rutracker.org/forum/index.php | head -1
curl -sI https://kinozal.tv | head -1
```

Ответ `HTTP/... 200` (или 30x) — хорошо. Таймаут / `Could not resolve host` — трекер с этого VPS заблокирован;
тогда TorAPI нужно запускать с прокси (см. пункт «Прокси» ниже).

## 1а. Установка через Docker (рекомендуется)

```bash
docker run -d --name torapi --restart=unless-stopped \
  -p 127.0.0.1:8443:8443 --memory=200m \
  lifailon/torapi:latest
```

`127.0.0.1:` перед портом — важно: так TorAPI доступен только самому серверу.

Обновление позже:
```bash
docker pull lifailon/torapi:latest && docker rm -f torapi && <команда docker run выше>
```

## 1б. Установка без Docker (Node.js, уже стоит для сайта)

```bash
sudo git clone https://github.com/Lifailon/TorAPI /opt/torapi
cd /opt/torapi && sudo npm install --omit=dev
```

Служба systemd — файл `/etc/systemd/system/torapi.service`:

```ini
[Unit]
Description=TorAPI
After=network-online.target

[Service]
WorkingDirectory=/opt/torapi
ExecStart=/usr/bin/npm start -- --port 8443
Restart=always
RestartSec=5
MemoryMax=200M
Environment=NODE_ENV=production

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now torapi
```

Без Docker TorAPI слушает все адреса — закройте порт снаружи:
```bash
sudo ufw deny 8443/tcp      # если используете ufw
```

## 2. Проверка, что TorAPI работает

```bash
curl -s http://127.0.0.1:8443/api/provider/check
curl -s "http://127.0.0.1:8443/api/search/title/rutracker?query=matrix&page=0" | head -c 600; echo
```

Первая команда должна показать `"RuTracker": true`, вторая — JSON со списком раздач (`Name`, `Id`, `Size`, `Seeds`…).

## 3. Подключение к сайту

В `.env` сайта:

```
TORAPI_URL=http://127.0.0.1:8443
# по желанию, если TorAPI отвечает медленно:
# TORAPI_TIMEOUT_MS=25000
```

Если в `.env` задан `SEARCH_PROVIDERS`, в нём должны быть `rutracker` и `kinozal`
(по умолчанию они там есть). Перезапустите сайт так же, как обычно (`node server.js` / pm2 / systemd).

На странице «Поиск раздач» появятся чипы **RuTracker · N** и **Kinozal · N**. Кнопка «В библиотеку» у таких
раздач берёт magnet через TorAPI и отправляет в AllDebrid.

## Прокси (если трекер заблокирован на VPS)

Docker:
```bash
docker rm -f torapi
docker run -d --name torapi --restart=unless-stopped -p 127.0.0.1:8443:8443 --memory=200m \
  -e PROXY_ADDRESS="адрес_прокси" -e PROXY_PORT="порт" \
  -e USERNAME="логин_прокси" -e PASSWORD="пароль_прокси" \
  lifailon/torapi:latest
```

Без Docker — в `ExecStart`: `npm start -- --port 8443 --proxyAddress адрес --proxyPort порт --username логин --password пароль`.

## Если что-то не так

- В чипе «RuTracker» ошибка `TorAPI: HTTP 5xx` — TorAPI не смог открыть трекер (блокировка/вёрстка). Смотрите
  `docker logs torapi` или `journalctl -u torapi -n 50`.
- `ECONNREFUSED` — TorAPI не запущен или слушает другой порт.
- `таймаут` — увеличьте `TORAPI_TIMEOUT_MS`.
- TorAPI — сторонний проект: если RuTracker сменит вёрстку, его нужно обновить (`docker pull …` / `git pull`).
