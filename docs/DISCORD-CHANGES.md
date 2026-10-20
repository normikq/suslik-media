# Журнал изменений: Discord и совместный просмотр (ветка `feature/discord-integration`, ПК 2.5.0)

Как устроено — `docs/DISCORD.md` (§0 — что сделано на деле), ПК — `docs/DESKTOP.md` §12, сервер — `docs/WEBSITE.md` §3.1.
Android в этой ветке не менялся.

## Сервер (`website/`)
| Файл | Что |
|---|---|
| `src/config.js` | `DISCORD_*`, `SITE_PREFIX`, `PASSWORD_AUTH_ENABLED`, пути `users.json` / `devices.json` / `shelf.json` / `rooms.json` |
| `src/services/discord.js` | OAuth2 (обмен кода, профиль, отзыв токена), проверка участника и ролей ботом, ссылка на аватар |
| `src/services/users.js` | Пользователи, токены устройств (только хеши), коды связки, перепроверка роли раз в 15 мин |
| `src/services/rooms.js`, `src/routes/rooms.js` | Комнаты: SSE-поток, ведущий, участники = открытые потоки, ожидание загрузки, пауза пустой комнаты |
| `src/services/shelf.js` | Видимость и права удаления — **пока не подключено** |
| `src/routes/auth.js` | Вход через Discord с возвратом на исходную страницу, вход устройств по коду, `discord_client_id` в `/api/session`, отзыв токена при выходе |
| `src/middleware/auth.js` | `resolveAuth`: токен устройства или сессия; cookie `SameSite=Lax` (возврат с discord.com); при `PASSWORD_AUTH_ENABLED=false` вошедшие по паролю выкидываются |
| `src/services/precompressed.js` | CSP: аватары с `cdn.discordapp.com` |
| `public/index.html`, `redesign.css` | Кнопка «Войти через Discord», профиль, окно «Связать устройство» (кто войдёт, код из ссылки) |
| `test/discord.test.js` | Вход, устройства, комнаты (ведущий, ожидание загрузки, пустая комната) |

## ПК (`desktop/`)
| Файл | Что |
|---|---|
| `Library.kt`, `LibraryView.kt` | Вход через Discord по коду, профиль из ответа сервера, токен устройства; истёкший код больше не ждёт вечно |
| `Api.kt` | `X-Device-Token`; cookie, токен и бета-код — только своему серверу |
| `Sidebar.kt`, `Main.kt`, `SearchView.kt` | Профиль с аватаром; пункт «Совместный просмотр»; у участника комнаты закрыты «Библиотека» и «Избранное» |
| `party/Party.kt`, `PartySync.kt`, `TogetherView.kt` | Совместный просмотр (`docs/DESKTOP.md` §12.2) |
| `player/PlayerView.kt`, `PlayerOverlays.kt`, `PlayerControls.kt` | Синхронизация; у участника — полоса «● Вместе», без перемотки; серая кнопка, пока ведущий держит паузу |
| `discord/DiscordRpc.kt`, `Settings.kt` | Активность «Смотрит» в профиле Discord, переключатель |
| `build.gradle.kts` | 2.5.0; `-Dsuslik.discordClientId` (необязательно) |

## Общий модуль (`shared/`)
`core/party/PartyModels.kt` — `PartyClock`, `DriftController`, `SyncPlayer`; тесты `PartyTest.kt`.

## Включение на сервере
1. `.env`: `DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET`, `DISCORD_BOT_TOKEN`, `DISCORD_GUILD_ID` (и при желании роли, `DISCORD_OWNER_ID`),
   `SITE_PREFIX=/media`, `COOKIE_SECURE=true`, `SESSION_SECRET`. Без бота и белого списка пускают любой аккаунт Discord.
2. Developer Portal → OAuth2 → Redirects: `https://<домен>/api/auth/discord/callback`. Бот добавлен на сервер (scope `bot`, без прав).
   Rich Presence → Art Assets: картинки `brand-mark`, `play`, `pause`.
3. nginx: короткая ссылка для ТВ — `location = /link { return 302 /media/link$is_args$args; }` (`website/NGINX-MIRROR.md`).
4. `npm install` (пакет `ws`), перезапуск Node.
