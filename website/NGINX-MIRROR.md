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
        proxy_buffering off;                        # поток, без записи на диск
        proxy_read_timeout 30s;                     # файл-хост замолчал — плеер переподключится
        proxy_connect_timeout 10s;
        proxy_hide_header Set-Cookie;
        add_header X-Content-Type-Options nosniff always;
        # Ссылка устарела / хост ответил ошибкой — Node получит её заново и отдаст поток сам
        proxy_intercept_errors on;
        error_page 403 404 410 500 502 503 504 = @suslik_mirror_retry;
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

> Если сайт открыт по пути `/media/` и nginx отдаёт Node весь сайт через `location /media/ { proxy_pass ...; }`,
> ничего больше менять не нужно: адрес `/_alld/...` внутренний, его вызывает сам nginx.

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
