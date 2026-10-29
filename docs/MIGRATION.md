# Переезд сервера на другой домен (и, возможно, под путь)

Подготовка от 30.10. Сам переезд не делали, на сервер ничего не выкладывали; код `website/` подготовлен (раздел 3). Домены в этом файле условные:
`old.example` — нынешний, `new.example/suslik` — новый адрес с путём. Настоящие домены в репозиторий не пишем.

## 0. Коротко

- **Одна настройка на всё — секрет `SITE_URL`** = `https://домен` или `https://домен/путь` (без `/` в конце). Из него
  клиенты берут API (`<SITE_URL>/api/…`) и страницы сайта (`<SITE_URL>/media/`). Раньше из секрета брался только домен,
  а путь отбрасывался. Это исправлено (раздел 2).
- **Сервер под путём.** Раньше он собирал адреса зеркала, плейлистов, озвучек, потока ТВ и Discord как
  `https://<host>/api/…`, без пути, а сайт в браузере звал `/api/…` от корня. Исправлено (раздел 3): путь сообщает nginx
  заголовком `X-Forwarded-Prefix`, так что один Node обслуживает и старый адрес в корне, и новый под путём.
- **Старые установленные версии** знают только старый домен. Перенаправление (301/302) им не годится: Android шлёт cookie
  только своему домену, а POST после редиректа превращается в GET. Поэтому **старый домен на переходный период должен
  проксировать на тот же Node** (не редирект). Через него же старые Android и ПК получат обновление (раздел 5).
- Подписи ссылок зеркала, плейлистов и потока ТВ от домена не зависят (HMAC от ссылки и срока): ссылка, подписанная на
  старом адресе, действует и на новом.

## 1. Что зависит от домена и пути

### Сервер (`website/`) — как было до правок раздела 3

| Где | Что | Под путём |
|---|---|---|
| `src/routes/mirror.js:34` (`/api/file/mirror-url`) | `https://${host}/api/file/proxy/…` | ❌ нет пути |
| `src/routes/mirror.js:62` (`POST /api/playlist`) | `https://${host}/api/playlist/…m3u` | ❌ |
| `src/routes/mirror.js:86–98` (сам `.m3u`) | строки `https://${host}/api/file/proxy/…` | ❌ |
| `src/routes/custom-audio.js:322` | адреса озвучек `https://${host}/api/file/proxy/…` | ❌ |
| `src/routes/tvstream.js:89–91` | поток ТВ `https://${host}/api/file/tv/…` | ❌ |
| `src/routes/auth.js:58–61` `getRedirectUri` | OAuth Discord: `DISCORD_REDIRECT_URI` из `.env` или `${proto}://${host}/api/auth/discord/callback` | ❌; при заданном `DISCORD_REDIRECT_URI` работает только один домен |
| `src/routes/auth.js:28` `siteHome`, `:241` код устройства | `SITE_PREFIX` (`/media`) — путь сайта от корня | ⚠ один на оба домена |
| `src/routes/auth.js:43`, `:237` | сверка `Referer`/`site_url` с `Host` | ✅ |
| `src/middleware/auth.js:213`, `src/routes/auth.js:122`, сеанс (`middleware/auth.js:132`) | cookie `Path=/`, без `Domain` | ✅ (на новом домене cookie видят и другие службы домена, как и сейчас) |
| `src/middleware/security.js` | `sameOriginWrites` сверяет `Origin` с `Host`/`X-Forwarded-Host`; HSTS без поддоменов | ✅ |
| `src/services/mirror.js:189` | `X-Accel-Redirect: /_alld/…` — внутренний адрес nginx | ✅, но `location /_alld/` должен быть в блоке `server` нового домена |
| `src/routes/updates.js:82` | ссылка на APK/установщик относительная: `/api/app/download/…` | ✅ клиенты дописывают свой адрес |
| CORS | не используется (сайт и API на одном адресе) | ✅ |
| `public/redesign.js`, `public/home.js`, `public/index.html` | ~40 запросов `fetch("/api/…")`, ссылки `href="/api/auth/discord/start"` | ❌ от корня домена |
| `NGINX-MIRROR.md` `@suslik_mirror_retry` | `proxy_pass …$request_uri` — с путём в Node уйдёт `/suslik/api/…` | ❌ (раздел 4) |

### Клиенты

| Где | Было | Стало (раздел 2) |
|---|---|---|
| Android `app/build.gradle.kts` | `BuildConfig.SITE_HOST` — только домен | `BuildConfig.SITE_URL` — домен и путь |
| Android `net/Api.kt` | `BASE = "https://$HOST"`, cookie/токен/бета-код — по `host == HOST` | `BASE` из `SiteAddress.normalize`, «свой сервер» — `SiteAddress.isOwn` (домен **и** путь) |
| Android `MainActivity.kt:341` | плейлист только с `host == siteHost` | `SiteAddress.isOwn(плейлист, BASE)` |
| Android cookie WebView | хранятся по адресу: смена домена = выход для входа по паролю, новый `sdev` | `Api.moveCookies()` переносит cookie со старого адреса один раз |
| ПК `build.gradle.kts`, `Api.kt` | `-Dsuslik.host` — только домен | `-Dsuslik.site` — домен и путь (`-Dsuslik.host` — запасной) |
| ПК `MpvInstall.kt` | `Api.BASE + /media/mpv/libmpv-2.dll` | без изменений, но теперь `BASE` с путём |
| Установщик `setup/Mpv.cs`, `SuslikSetup.csproj` | `-p:SiteHost=домен` | `-p:SiteUrl=https://домен/путь` (`SiteHost` — запасной) |
| Tizen `build.sh`, `build.ps1`, `js/api.js` | `SM.SITE_HOST` | `SM.SITE_URL` (+ `SITE_HOST` для старого `site.js`) |
| Tizen `config.xml` | `<access origin="*">` | ✅ домен не прописан |
| Общий модуль | `DeviceAuth.isOwnServer` | через новый `core/SiteAddress.kt` (+ `SiteAddressTest`) |
| Не зависит от адреса | вход через Discord по коду (токен устройства), прогресс и дорожки по аккаунту, ключи прогресса `w:magnetId\|path`, cookie на ПК (одна банка без домена) | ✅ |

### CI и выпуск

| Workflow | Что | Под путём |
|---|---|---|
| `android.yml`, `desktop.yml` | `SITE_URL` в сборку; выкладка `"$SITE_URL/api/app/upload"`, changelog | ✅ (секрет без `/` в конце) |
| `desktop.yml` (установщик) | раньше вырезал домен в `SiteHost` | ✅ теперь `-p:SiteUrl="${SITE_URL%/}"` |
| `changelog.yml` (сам на пуш в `main`), `promote.yml`, `update-status.yml`, `reports.yml` | `"$SITE_URL/api/app/…"` | ✅ |

## 2. Что сделано в клиентах (компилируется, на устройствах не проверено)

- `shared/.../core/SiteAddress.kt`: `normalize` (схема по умолчанию `https://`, без `/` в конце, `http://` только для
  `localhost`), `host`, `path`, `isOwn` (тот же домен и внутри пути: `…/suslik-evil` и `…/other` — чужие).
  `DeviceAuth.isOwnServer` теперь через него. Тест `SiteAddressTest`.
- Android: `BuildConfig.SITE_URL`; `Api.BASE/HOST/SITE` из него; cookie, токен устройства и бета-код — только на
  `SiteAddress.isOwn`; при первом запуске с новым адресом cookie WebView (`movieportal.sid`, `sdev`) копируются со старого
  (`auth` → `site_base`), если на новом их ещё нет.
- ПК: `-Dsuslik.site`; `Api.BASE` из него (запасной `-Dsuslik.host`). Cookie и токен на ПК от адреса не зависят.
- Установщик: метаданные `SiteUrl`; `Mpv.Url` = `SiteUrl` + путь из `libmpv.properties` (запасной `SiteHost`).
- Tizen: `site.js` получает `SITE_URL` и `SITE_HOST`; `api.js` берёт `SITE_URL`, старый `site.js` тоже понимает.
  Вход на ТВ — токен в `localStorage` приложения, от адреса не зависит.

Версии не менялись. Выпускать эти правки можно и до переезда: со старым `SITE_URL` (только домен) всё работает как раньше.

## 3. Что сделано в `website/` (30.10, с разрешения владельца; на сервер не выложено)

Один Node обслуживает оба адреса сразу — старый в корне, новый под путём. Путь сообщает nginx заголовком
`X-Forwarded-Prefix`, поэтому в `.env` про домен и путь ничего не нужно (нужен только `TRUST_PROXY`, он и так стоит).

1. `src/utils/helpers.js`: `basePath(req)` — заголовок `X-Forwarded-Prefix`, только при `TRUST_PROXY` и только вида
   `/слово[/слово…]` (буквы, цифры, `._~-`, сегмент не с точки), иначе `""`; `publicBase(req)` = `https://<host><путь>`.
2. Адреса, которые собирает сервер, — через `publicBase(req)`: `routes/mirror.js` (`mirror-url`, `POST /api/playlist`,
   строки `.m3u`), `routes/custom-audio.js` (озвучки), `routes/tvstream.js` (поток ТВ).
3. `routes/auth.js`: redirect URI Discord — `<proto>://<host><путь>/api/auth/discord/callback`; `siteHome(req)` и запасной
   адрес кода устройства — `<путь> + SITE_PREFIX`. **`DISCORD_REDIRECT_URI` в `.env` убрать**: пока он задан, вход через
   Discord работает только на том адресе, что в нём записан.
4. Сайт (`public/index.html`, `public/home.js`): `API_ROOT` — путь страницы без последнего `media/` (страница всегда
   `<база>/media/…`), `apiUrl()` дописывает его к `/api/…`: все запросы `api()`, вход через Discord, постеры, фоны,
   ссылки на скачивание приложений. На старом адресе `API_ROOT` пустой — всё как раньше.
5. Cookie оставлены с `Path=/` — на это рассчитан перенос cookie в Android. Сузить до пути можно позже, отдельно.
6. Тест `test/base-path.test.js`: подделанный/кривой заголовок не действует, без заголовка адреса прежние, с ним —
   redirect URI Discord и ссылка кода устройства с путём. Все тесты сайта: 164 прошли, 0 упали; `npm run check` — OK.

**Важно для nginx старого адреса:** добавить `proxy_set_header X-Forwarded-Prefix "";` в `location`, которые ведут в
Node, — иначе посетитель может прислать свой заголовок (вреда другим нет, но свои ссылки он себе сломает).

## 4. nginx на новом адресе (без http2 — на сервере Xray)

Общие строки — в файл `/etc/nginx/snippets/suslik-proxy.conf`:

```nginx
proxy_http_version 1.1;
proxy_set_header Connection "";
proxy_set_header Host $host;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Prefix /suslik;   # путь сервера для Node (раздел 3)
proxy_buffering off;                           # SSE комнат, поток ТВ
proxy_read_timeout 300s;
# client_max_body_size — как в нынешнем блоке (выкладка APK/установщика через /api/app/upload)
```

```nginx
# Путь без префикса — для повтора зеркала (в Node должен уйти /api/…, а не /suslik/api/…)
map $request_uri $suslik_uri { ~^/suslik(?<rest>/.*)$ $rest; default $request_uri; }

server {
    listen 443 ssl;               # без http2
    server_name new.example;
    ssl_certificate     /etc/letsencrypt/live/new.example/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/new.example/privkey.pem;
    keepalive_timeout 180s;
    keepalive_requests 10000;

    # Раскладка как на старом адресе, только с /suslik впереди: API — /suslik/api/ → Node /api/,
    # сайт — /suslik/media/ → Node / (Node отдаёт страницу и её файлы из корня, как за старым /media/)
    location = /suslik  { return 301 /suslik/media/; }
    location = /suslik/ { return 301 /suslik/media/; }
    location /suslik/api/ {
        proxy_pass http://suslik_node/api/;        # срезает /suslik
        include snippets/suslik-proxy.conf;
    }
    location /suslik/media/ {
        proxy_pass http://suslik_node/;            # срезает /suslik/media
        include snippets/suslik-proxy.conf;
    }
    location = /suslik/link { return 302 /suslik/media/link$is_args$args; }

    # Блок _alld из NGINX-MIRROR.md без изменений (internal, адрес /_alld/ — от корня сервера)
    location ~ ^/_alld/(https?)/([^/]+)/(.*)$ { … }
    location @suslik_mirror_retry {
        proxy_pass http://suslik_node$suslik_uri;   # не $request_uri: в нём /suslik
        … остальное как в NGINX-MIRROR.md …
    }
}
```

`upstream suslik_node` — из `NGINX-MIRROR.md` §4 (или `http://127.0.0.1:3000`). `client_max_body_size`, таймауты и
прочее перенести из нынешнего блока `server` старого домена — сверить построчно (`nginx -T`). Если на старом адресе
`/media/mpv/libmpv-2.dll` отдаёт отдельная `location` (а не Node), повторить её как `/suslik/media/mpv/`. Если новый
адрес — корень домена, `/suslik` не нужен: `location /api/` и `location /media/` как сейчас, без `X-Forwarded-Prefix`.

## 5. Пошаговый план

**Подготовка (ничего не ломает)**

1. Выбрать домен и путь. Если меняется и VPS: проверить, что AllDebrid пускает новый IP (с IP дата-центров бывает
   `NO_SERVER`), перенести папку данных `website/` (`DATA_DIR`: `sessions.json`, `users.json`, `devices.json`,
   `shelf.json`, `sync.json`, `ai-titles.json`, `markers-cache.json`, файлы обновлений и changelog) и `.env`.
2. Выпустить в бету клиентов из раздела 2 со **старым** `SITE_URL` (поведение то же) и проверить на устройствах. Так
   код поиска адреса проверяется отдельно от переезда.
3. Выложить `website/` с правками раздела 3 (`helpers.js`, `routes/mirror.js`, `custom-audio.js`, `tvstream.js`,
   `auth.js`, `public/index.html`, `public/home.js`), в nginx старого адреса — `proxy_set_header X-Forwarded-Prefix "";`.
   На старом адресе заголовка нет — всё как было. До выкладки на новом адресе можно жить только в его корне.

**Новый адрес параллельно со старым**

4. DNS: запись `A` нового домена на сервер; подождать TTL. Сертификат: `certbot certonly --webroot` (или `--nginx`)
   для нового домена.
5. nginx: блок из раздела 4 (без http2), `nginx -t && systemctl reload nginx`. Старый блок не трогать.
6. Discord Developer Portal → OAuth2 → Redirects: **добавить** `https://new.example/suslik/api/auth/discord/callback`,
   старый оставить. В `.env` убрать `DISCORD_REDIRECT_URI` (или заменить на вычисление по запросу из п. 3). Перезапуск Node.
7. Проверка нового адреса: `curl https://new.example/suslik/api/app/status -H "X-Update-Token: …"`; в браузере
   `https://new.example/suslik/media/` — вход через Discord, медиатека; `mirror-url` возвращает адрес с `/suslik/`;
   фильм играет и перематывается (зеркало через `_alld`); ТВ-поток; комната «Вместе».

**Переключение сборок**

8. Секрет `SITE_URL` (GitHub → Settings → Secrets) = `https://new.example/suslik`. Локально — `suslik.siteUrl` в
   `~/.gradle/gradle.properties`. С этого момента пуш в `main` публикует changelog уже на новый адрес — сервер тот же,
   разницы нет.
9. Бета Android и ПК (`android.yml`, `desktop.yml` с `channel=beta`), `update-status.yml`. Владелец ставит бету: вход по
   паролю должен сохраниться (перенос cookie), вход через Discord — сохраниться (токен устройства), libmpv на ПК —
   скачаться с нового адреса. Tizen — собрать с новым `SITE_URL` и поставить на ТВ вручную (самообновления нет).
10. «Выпускай всем» → `promote.yml`. Старые приложения спрашивают обновление у **старого** домена (тот же Node) и
    получают сборку с новым адресом.

**Переходный период и выключение старого**

11. Держать старый домен рабочим, пока в журнале nginx старого блока есть запросы приложений (`/api/` с
    `User-Agent` okhttp / Java-http-client), — ориентир 4–6 недель. Обновиться может только тот, кто запускает
    приложение: у редко запускающих старый адрес нужен дольше.
12. Потом на старом домене оставить только `location /media/ { return 301 https://new.example/suslik/media/; }` для
    закладок в браузере, а `/api/` — `410` с понятным текстом. Убрать старый redirect URI из Discord, сертификат старого
    домена — по желанию.

**Если сервер тоже переезжает на другой VPS.** Старый домен можно либо направить DNS на новый VPS (два блока `server`
на одном nginx — проще всего), либо на старом VPS проксировать `/api/` и `/media/` на новый адрес
(`proxy_pass https://new.example/suslik/; proxy_ssl_server_name on;`, `Host` — новый). Во втором случае
ссылки зеркала старые клиенты получат уже с новым адресом — это нормально, подпись от адреса не зависит.

## 6. Откат

- До шага 8 (секрет не менялся): убрать блок нового домена из nginx — старые и новые клиенты на старом адресе.
- После шага 8: вернуть `SITE_URL` на старый адрес, пересобрать и выпустить. Приложения, уже получившие новый адрес,
  узнают об откате только через него же, поэтому **новый адрес не выключать**, пока они не обновятся обратно (откат — это
  ещё одна новая версия, а не выключение). Android при возврате снова перенесёт cookie (`site_base`).
- Правки `website/` из раздела 3 без заголовка `X-Forwarded-Prefix` ничего не меняют — их откатывать не нужно.

## 7. Что нужно от владельца

- Новый домен и путь (или «корень»), и тот же ли VPS.
- Выкладка `website/` (раздел 3) — на сервер.
- После переезда — поменять секрет `SITE_URL` и redirect URI в Discord (их может сделать только владелец).
