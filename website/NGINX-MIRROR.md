# Видео через nginx (разгрузка Node)

Сейчас каждый байт фильма проходит через Node. На одном ядре во время просмотра это основная нагрузка:
сайт, библиотека и поиск начинают тормозить, а перемотка отвечает медленнее.

После настройки Node только проверяет подпись ссылки и узнаёт у AllDebrid прямой адрес файла. Сами байты nginx
забирает с файл-хоста и отдаёт плееру. Видео по-прежнему идёт **через ваш VPS**, так что обход блокировок не меняется.

## 1. Добавьте два блока в nginx

Откройте конфиг сайта (обычно `/etc/nginx/sites-available/<ваш сайт>` или `/etc/nginx/conf.d/*.conf`). Внутри
блока `server { ... }`, где уже есть `listen 443`, добавьте рядом с остальными `location`:

```nginx
    # --- Suslik Media: видео отдаёт nginx (Node присылает X-Accel-Redirect) ---
    location ~ ^/_alld/(https?)/([^/]+)/(.*)$ {
        internal;                                   # снаружи этот адрес недоступен
        resolver 1.1.1.1 8.8.8.8 valid=300s ipv6=off;
        resolver_timeout 5s;
        set $alld_proto $1;
        set $alld_host  $2;
        set $alld_path  $3;
        proxy_pass $alld_proto://$alld_host/$alld_path$is_args$args;
        proxy_http_version 1.1;
        proxy_set_header Host $alld_host;
        proxy_set_header Connection "";
        proxy_set_header Cookie "";                 # куки сайта файл-хосту не нужны
        proxy_set_header Authorization "";
        proxy_ssl_server_name on;
        proxy_ssl_name $alld_host;
        proxy_ssl_verify on;                        # проверять сертификат файл-хоста
        proxy_ssl_trusted_certificate /etc/ssl/certs/ca-certificates.crt;
        proxy_ssl_verify_depth 3;
        # Читать файл-хост крупными кусками в память (до 4 МБ на поток), без записи на диск: меньше системных вызовов
        # на одном ядре, файл-хост не ждёт медленного клиента. (С proxy_buffering off nginx читает по 4–8 КБ.)
        proxy_buffering on;
        proxy_buffer_size 64k;
        proxy_buffers 32 128k;
        proxy_busy_buffers_size 512k;
        proxy_max_temp_file_size 0;
        proxy_read_timeout 30s;                     # файл-хост замолчал — плеер переподключится
        proxy_connect_timeout 10s;
        proxy_hide_header Set-Cookie;
        proxy_hide_header Content-Disposition;      # имя файла ставит Node; второй заголовок от файл-хоста (attachment) ломает открытие в Firefox (NS_ERROR_CORRUPTED_CONTENT)
        add_header X-Content-Type-Options nosniff always;
        add_header Content-Security-Policy "sandbox; default-src 'none'" always;   # .html из раздачи не выполняется как страница сайта
        # Ссылка устарела / хост ответил ошибкой — Node получит её заново и отдаст поток сам
        proxy_intercept_errors on;
        error_page 400 403 404 410 500 502 503 504 = @suslik_mirror_retry;
    }
    location @suslik_mirror_retry {
        proxy_pass http://127.0.0.1:3000$request_uri;   # порт Node (PORT в .env), обычно 3000
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Mirror-Retry 1;
        proxy_buffering off;
        proxy_read_timeout 300s;
    }
```

Если Node у вас на другом порту, замените `3000` на свой.

> Обновление 16.10: если блок уже стоит, добавьте в него три строки `proxy_ssl_verify…`, строку
> `add_header Content-Security-Policy…` и `400` в `error_page`. Путь к сертификатам `/etc/ssl/certs/ca-certificates.crt` —
> для Debian/Ubuntu. Проверка: фильм с пробелом или кириллицей в имени файла должен играть.

> Если сайт открыт по пути `/media/` и nginx отдаёт Node весь сайт через `location /media/ { proxy_pass ...; }`,
> ничего больше менять не нужно: адрес `/_alld/...` внутренний, его вызывает сам nginx.
> Для удобной работы ссылки связки устройств (`https://домен/link` с экрана ТВ/ПК) добавьте в nginx редирект:
> ```nginx
> location = /link {
>     return 302 /media/link$is_args$args;
> }
> ```

Проверьте и примените:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

## 2. Включите режим в Node

В `website/.env` добавьте строку:

```
MIRROR_ACCEL=1
```

Затем перезапустите Node (`pm2 restart …` или `systemctl restart …`, как у вас заведено).

## 3. Проверка

При запуске Node пишет в лог строку `Видео через nginx (MIRROR_ACCEL) включено`.
Откройте любой фильм в приложении. Если он играет и перематывается, всё работает: без настроенного nginx видео
просто не запустилось бы.

Если что-то не так, уберите `MIRROR_ACCEL=1` и перезапустите Node: всё вернётся как было.
nginx-блоки можно не удалять, без этой строки они не используются.

## 4. Держать соединения дольше (лаги на мобильном интернете)

Приложение качает фильм рывками: примерно раз в полторы минуты оно запрашивает следующий кусок. По умолчанию nginx
закрывает простаивающее соединение через 75 секунд, то есть к следующему рывку оно уже закрыто. Тогда телефон каждый
раз заново устанавливает TCP и TLS, а на 4G это 0,3–1 секунды до первого байта, иногда больше. Если держать
соединение 3 минуты, следующий рывок начнётся сразу. Батарее это не вредит: пустое соединение радио не будит.

Заодно nginx будет держать открытыми соединения к Node и не будет открывать новое на каждый запрос (меньше работы на одном ядре).

**Вне** блока `server { ... }` (в начале того же файла) добавьте:

```nginx
upstream suslik_node {
    server 127.0.0.1:3000;      # порт Node (PORT в .env)
    keepalive 16;
}
```

**Внутри** блока `server { ... }` с `listen 443` добавьте:

```nginx
    keepalive_timeout 180s;
    keepalive_requests 10000;
```

В `location`, который передаёт сайт в Node, и в `@suslik_mirror_retry` замените `proxy_pass http://127.0.0.1:3000`
на `proxy_pass http://suslik_node`. Если в этих блоках ещё нет следующих строк, добавьте их:

```nginx
        proxy_http_version 1.1;
        proxy_set_header Connection "";
```

(`$request_uri` в `@suslik_mirror_retry` оставьте: `proxy_pass http://suslik_node$request_uri;`.)

Проверьте и примените: `sudo nginx -t && sudo systemctl reload nginx`. HTTP/2 не включайте: он конфликтует с Xray на этом сервере.

**Как увидеть эффект.** В журнале плеера (бета 2.0.0.1) у каждого рывка есть строка
`reserve: burst at … · answer N ms, first data M ms`. До настройки `answer` на 4G обычно 500–1500 мс, после —
примерно время одного пинга (100–200 мс).
