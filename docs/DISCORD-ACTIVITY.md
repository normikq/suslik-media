# Discord Activity: «Вместе» в голосовом канале

Activity — страница сайта внутри Discord (iframe). Это пульт комнаты: что смотрим, кто ведущий, медиатека,
голосование, «Следующая серия», код для ТВ и кнопка «Открыть на ПК или телефоне». Видео в Activity не играет:
раздачи тяжёлые (MKV, DTS), их играют приложения, а комната держит их вместе, как обычно.

## Как это работает

```
Голосовой канал ──► Activity (iframe <id>.discordsays.com → наш сайт)
                       │  вход: commands.authorize → POST /api/auth/discord/activity → ключ sa_… (12 ч, в памяти)
                       │  комната: POST /api/activity/room { channel_id } → одна на канал, id из 6 цифр
                       ▼
             Сервер комнат (/api/rooms, SSE) ◄── ПК (Qt), Android, ТВ — по коду или по ссылке suslik://room/<код>
```

- **Ведущий** — тот, кто первым открыл Activity в канале. Ушёл и не вернулся за минуту — ведущим становится
  тот, кто дольше всех в комнате (с плеером или у пульта).
- **Код комнаты** — её id, шесть цифр (`842-190`). Приложения при входе убирают всё, кроме букв и цифр, поэтому
  код с дефисом подходит и ТВ, и ПК, и Android без изменений в них.
- **У пульта, а не в плеере.** Activity открывает свой поток событий (`/api/activity/rooms/:id/events`) и в
  участники плеера не попадает: иначе ПК того же человека не вошёл бы («один аккаунт — одна комната»).
- **Фильм ставит ведущий** обычной командой комнаты `change_file` (`{ key: "<id раздачи>|<путь>", name, ad_id }`).
  Гости-приложения открывают его сами (`PartyFollow`), пауза и место — общие.
- **Голосование.** Любой предлагает фильм (до 8), ведущий запускает голосование на 45 секунд. Больше голосов —
  победил; поровну — тот, кого предложили раньше; никто не проголосовал — фильм не меняется. Победитель сразу
  ставится в комнату тем же `change_file`.
- **Следующая серия** — кнопка у ведущего на карточке «Сейчас», если в раздаче есть следующая серия.
- **Открыть в приложении.** Activity открывает в браузере `https://<сайт>/join/<код>` (commands.openExternalLink),
  страница — `suslik://room/<код>` (на Android — `intent://…;package=ru.suslikovanora.media`). ПК регистрирует
  ссылку сам при запуске (`HKCU\Software\Classes\suslik`, `desktop-qt/app/src/UrlScheme.cpp`), установщик убирает
  её при удалении. Android — intent-filter в `AndroidManifest.xml`. ТВ — код вручную: «Вместе» → «Войти по коду».
- **Поиск раздач.** «Медиатека» → «На трекерах»: трекеры сервера (обычный `/api/search`) и RuTracker через ПК.
  Разбор релизов, метки (4K, HDR, WEB-DL, озвучка, битрейт), пресеты, фильтры и сортировка — тот же
  `public/searchcore.js`, что у сайта и ТВ. Трекеры включаются и выключаются (помнится в браузере), по 20 раздач
  на страницу.
- **RuTracker через ПК в комнате.** У сервера своего входа на RuTracker может не быть, у ПК он есть (cookie,
  фоновый браузер). Activity просит сервер (`POST /api/activity/rooms/:id/rutracker/search`), сервер шлёт в поток
  комнаты `rutracker_request`, ПК — участник комнаты — ищет у себя и отвечает `POST /api/rooms/:id/rutracker/reply`
  (первый ответ, ждём до 25 с; ПК без RuTracker сразу отвечает ошибкой). ПК, не вошедший в комнату, не спрашивается.
  Раздачу с magnet Activity добавляет сама (`/api/magnets`), без magnet — просит тот же ПК (`…/rutracker/add`,
  адресно по отпечатку его client_id): он качает .torrent и кладёт в AllDebrid. Дальше Activity ждёт облако,
  обновляет медиатеку и включает фильм в комнате или предлагает его (у сериала — выбор серии). Ответ принимается
  только от ПК этой комнаты под его же аккаунтом, поля чистятся (`services/pc-search.js`). В ПК —
  `Party::onRemoteRequest`; старый ПК на Kotlin не отвечает (ждём до 25 с).

Почему ключ, а не cookie: страница живёт в iframe на `<id>.discordsays.com`, для браузера это сторонний сайт, и
cookie там режутся. Ключ `sa_…` идёт заголовком `Authorization: Bearer` (как у приложений), поэтому и проверка
«запрос со страницы сайта» (`sameOriginWrites`) его не трогает. Лимиты запросов считаются по ключу: все запросы
Activity приходят с адресов прокси Discord.

Код: `website/src/routes/activity.js`, `website/src/services/activity.js`, комнаты канала и голосование —
`website/src/services/rooms.js`, страница — `website/public/activity/` (`model.js` — копия `tizen/js/model.js`,
тест следит, чтобы копии совпадали), тесты — `website/test/activity.test.js`.

## Что сделать в Discord Developer Portal (руками)

Приложение то же, что для входа через Discord (его `DISCORD_CLIENT_ID` / `DISCORD_CLIENT_SECRET` уже на сервере).

1. https://discord.com/developers/applications → приложение Suslik Media.
2. **Activities → Settings**: включить **Enable Activities**. Supported Platforms: Desktop, Android, iOS
   (Web — по желанию).
3. **Activities → URL Mappings**: `PREFIX` `/` → `TARGET` `<домен сайта>` (без `https://`; если сервер не в корне —
   с путём, например `example.com/media`). Других сопоставлений не нужно: страница, скрипты и API идут через это.
4. **OAuth2**: в Redirects ничего добавлять не нужно (код из Activity меняется без redirect_uri).
   Scope — только `identify`: членство на сервере и роль проверяет бот, как при обычном входе.
5. **Installation**: Installation Contexts — Guild Install (и User Install, если удобно), Install Link —
   Discord Provided Link, scope `applications.commands`. Открыть ссылку установки и добавить приложение на сервер:
   тогда Activity появится в списке «Активности» голосового канала.
6. В голосовом канале: кнопка «Активности» (ракета) → Suslik Media.

## Сервер

- Выложить `website/` (страница — `public/activity/`, маршруты подключены в `src/app.js`).
- `.env`: при желании `ACTIVITY_SITE_URL=https://<домен>[/путь]` — адрес для «Открыть в приложении». Без него
  берётся из запроса, а если запрос пришёл с discordsays.com — из `DISCORD_REDIRECT_URI`.
- Скрипты и стили страницы подключены с `?v=` — сервер подставляет хеш файла (`routes/activity.js`), а такие файлы
  отдаются как неизменные на год. Поэтому после выкладки Discord сразу берёт новые; руками версию менять не нужно.
- nginx: ничего нового. Поток событий отдаётся с `X-Accel-Buffering: no` и `Cache-Control: no-transform`.
  Если прокси Discord всё же держит поток пачкой, страница через 7 секунд сама переходит на опрос раз в 3 секунды.
- Страница Activity отдаётся по `/?frame_id=…&instance_id=…` (так её открывает Discord) и по `/?activity` (проверка
  в обычном браузере покажет «Это страница для Discord»). Встраивать её можно только в discord.com и discordsays.com
  (`frame-ancestors`), остальной сайт по-прежнему `frame-ancestors 'none'`.

## Не проверено на живом Discord

Протокол связи с клиентом Discord (`public/activity/discord.js`) повторяет `@discord/embedded-app-sdk` 2.x без
сборщика: рукопожатие, READY, AUTHORIZE, OPEN_EXTERNAL_LINK. Проверено в браузере с подставным «Discord»
(страница-родитель отвечает теми же сообщениями), в самом Discord — ещё нет.
