"use strict";

const express = require("express");
const crypto = require("crypto");
const config = require("../config");
const { loginLimiter, apiLimiter, deviceAuthLimiter, devicePollLimiter } = require("../middleware/rate-limit");
const { requireLogin, verifyPassword, resolveAuth, sessionStore } = require("../middleware/auth");
const { alldebridRequest } = require("../services/alldebrid");
const { exchangeOAuthCode, fetchMe, revokeOAuthToken, checkGuildMember } = require("../services/discord");
const {
  getUser,
  findOrCreateUser,
  createDevicePairing,
  findPendingPairing,
  authorizeDevicePairing,
  pollDevicePairing,
  adoptDevice,
  revokeDeviceToken,
  hashToken,
  listDevices,
  revokeDeviceById
} = require("../services/users");
const { readCookie } = require("../utils/helpers");

const router = express.Router();

// Главная сайта с учётом префикса (/media/ за nginx)
function siteHome() {
  return `${config.SITE_PREFIX || ""}/`;
}

// Куда вернуть после входа: только путь этого сайта, без чужих доменов
function safeReturnPath(value) {
  const v = typeof value === "string" ? value : "";
  if (!v.startsWith("/") || v.startsWith("//") || v.startsWith("/\\") || v.length > 512) return null;
  return v;
}

// Страница, с которой нажали «Войти» (если старая страница не передала return): только этот же сайт
function refererPath(req) {
  try {
    const ref = new URL(req.get("referer") || "");
    if (ref.host !== req.get("host")) return null;
    return safeReturnPath(ref.pathname + ref.search);
  } catch {
    return null;
  }
}

// Главная того сайта, откуда пришли: /media/link?code=… → /media/. Не зависит от SITE_PREFIX в .env
function homeOf(returnPath) {
  const p = String(returnPath || "").split(/[?#]/)[0].replace(/link\/?$/, "");
  const home = p.endsWith("/") ? p : p.slice(0, p.lastIndexOf("/") + 1);
  // Идёт в href страницы «Доступ ограничен»: только простые символы пути
  return home && home !== "/" && /^\/[A-Za-z0-9/_.~%-]*$/.test(home) ? home : siteHome();
}

function getRedirectUri(req) {
  if (config.DISCORD_REDIRECT_URI) return config.DISCORD_REDIRECT_URI;
  return `${req.protocol}://${req.get("host")}/api/auth/discord/callback`;
}

// Пометка входа для списка «Активные сессии»: с чего вошли и когда (адрес не пишем)
function markSession(req) {
  req.session.meta = { ua: String(req.get("user-agent") || "").slice(0, 300), at: Date.now() };
}

// «Chrome · Windows» из User-Agent
function describeAgent(ua) {
  ua = String(ua || "");
  if (!ua) return "Браузер";
  const os = /Android/i.test(ua) ? "Android" : /iPhone|iPad|iPod/i.test(ua) ? "iOS" : /Windows/i.test(ua) ? "Windows"
    : /Mac OS X|Macintosh/i.test(ua) ? "macOS" : /Linux|X11/i.test(ua) ? "Linux" : "";
  const br = /Edg\//.test(ua) ? "Edge" : /OPR\/|Opera/.test(ua) ? "Opera" : /YaBrowser/.test(ua) ? "Яндекс Браузер"
    : /Firefox\//.test(ua) ? "Firefox" : /Chrome\/|CriOS/.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Браузер";
  return os ? `${br} · ${os}` : br;
}

// 1. Вход по общему паролю (запасной / админский)
router.post("/api/login", loginLimiter, (req, res) => {
  if (!config.PASSWORD_AUTH_ENABLED) {
    return res.status(403).json({ error: "Вход по паролю отключён в настройках сервера." });
  }
  if (!config.SITE_PASSWORD) return res.status(503).json({ error: "На сервере не задан SITE_PASSWORD." });
  const password = typeof req.body?.password === "string" ? req.body.password : "";
  if (!password || password.length > 256) {
    return res.status(401).json({ error: "Неверный пароль." });
  }

  if (!verifyPassword(password)) {
    return res.status(401).json({ error: "Неверный пароль." });
  }

  req.session.regenerate(regenErr => {
    if (regenErr) return res.status(500).json({ error: "Не удалось создать сеанс." });
    req.session.authenticated = true;
    markSession(req);
    delete req.session.uid; // Вход по паролю: shared сессия
    req.session.save(err => {
      if (err) return res.status(500).json({ error: "Не удалось создать сеанс." });
      res.json({ ok: true });
    });
  });
});

// 2. Discord OAuth2 — начало авторизации
router.get("/api/auth/discord/start", (req, res) => {
  if (!config.DISCORD_ENABLED) {
    return res.status(503).send("Вход через Discord не настроен в конфигурации сервера.");
  }

  const state = crypto.randomBytes(24).toString("hex");
  req.session.oauth_state = state;
  const returnTo = safeReturnPath(req.query.return) || refererPath(req) || siteHome();
  req.session.oauth_return = returnTo;

  const tmpCookie = {
    httpOnly: true,
    sameSite: "lax",
    secure: config.COOKIE_SECURE,
    maxAge: 10 * 60 * 1000,
    path: "/"
  };
  res.cookie("s_ostate", state, tmpCookie);
  // Запасная копия адреса возврата: сеанс до входа может не сохраниться
  res.cookie("s_oret", encodeURIComponent(returnTo), tmpCookie);

  const redirectUri = getRedirectUri(req);
  const discordUrl = new URL("https://discord.com/oauth2/authorize");
  discordUrl.searchParams.set("client_id", config.DISCORD_CLIENT_ID);
  discordUrl.searchParams.set("response_type", "code");
  discordUrl.searchParams.set("scope", "identify");
  discordUrl.searchParams.set("state", state);
  discordUrl.searchParams.set("redirect_uri", redirectUri);

  res.redirect(discordUrl.toString());
});

// 3. Discord OAuth2 — обратный вызов
router.get("/api/auth/discord/callback", async (req, res) => {
  if (!config.DISCORD_ENABLED) {
    return res.status(503).send("Вход через Discord не настроен.");
  }

  const { code, state, error, error_description } = req.query;

  let savedReturn = req.session?.oauth_return;
  if (!savedReturn) {
    try { savedReturn = decodeURIComponent(readCookie(req, "s_oret")); } catch { savedReturn = ""; }
  }
  const returnTo = safeReturnPath(savedReturn) || siteHome();
  const home = homeOf(returnTo);
  res.clearCookie("s_oret");
  if (req.session) delete req.session.oauth_return;

  if (error) {
    return res.redirect(`${home}?auth_error=${encodeURIComponent(error_description || error)}`);
  }

  const expectedState = req.session?.oauth_state || readCookie(req, "s_ostate");
  if (!state || !expectedState || state !== expectedState) {
    return res.status(400).send("Ошибка авторизации: неверный или устаревший параметр state. Попробуйте снова.");
  }

  res.clearCookie("s_ostate");
  delete req.session.oauth_state;

  const redirectUri = getRedirectUri(req);
  let accessToken = null;

  try {
    const tokenData = await exchangeOAuthCode(code, redirectUri);
    accessToken = tokenData.access_token;

    const profile = await fetchMe(accessToken);

    // Немедленно отзываем временный токен пользователя
    revokeOAuthToken(accessToken);
    accessToken = null;

    // Проверяем права участника на Discord-сервере
    const check = await checkGuildMember(profile.id);

    if (!check.allowed) {
      let msg = "Доступ закрыт: у вас нет разрешения на вход.";
      if (check.reason === "not_in_guild") {
        msg = "Доступ закрыт: ваш Discord-аккаунт не состоит на сервере Suslik Media.";
      } else if (check.reason === "no_role") {
        msg = "Доступ закрыт: у вашего аккаунта нет требуемой роли для просмотра медиатеки.";
      }
      return res.status(403).send(`
        <!DOCTYPE html>
        <html lang="ru">
        <head><meta charset="utf-8"><title>Доступ закрыт</title>
        <style>body{background:#090a0f;color:#e2e8f0;font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;}
        .card{background:#13151f;padding:32px;border-radius:16px;max-width:440px;text-align:center;border:1px solid #2d3748;}
        h2{color:#f87171;margin-top:0;}p{color:#94a3b8;line-height:1.5;}a{color:#60a5fa;text-decoration:none;font-weight:bold;}
        </style></head>
        <body><div class="card"><h2>Доступ ограничен</h2><p>${msg}</p><p><a href="${home}">Вернуться на главную</a></p></div></body></html>
      `);
    }

    // Сохраняем или обновляем пользователя
    const user = findOrCreateUser(profile, check.role);

    // Переносим данные старого устройства (sdev) в нового пользователя
    const currentSdev = readCookie(req, "sdev");
    if (currentSdev) adoptDevice(currentSdev, user.id);

    req.session.regenerate(regenErr => {
      if (regenErr) return res.status(500).send("Не удалось инициализировать сеанс.");
      req.session.authenticated = true;
      markSession(req);
      req.session.uid = user.id;
      req.session.save(saveErr => {
        if (saveErr) return res.status(500).send("Не удалось сохранить сеанс.");
        res.redirect(returnTo);
      });
    });
  } catch (err) {
    if (accessToken) revokeOAuthToken(accessToken);
    console.error("Ошибка при OAuth авторизации Discord:", err.message);
    res.status(500).send("Произошла ошибка при связке с Discord. Попробуйте позже.");
  }
});

// 4. Device Flow — запрос кода привязки (для ТВ / ПК)
router.post("/api/auth/device", deviceAuthLimiter, (req, res) => {
  const pairing = createDevicePairing(req.body?.name);
  const host = req.get("host");
  const protocol = req.protocol;
  // site_url присылает клиент (адрес с префиксом, например /media), но только свой же хост: иначе в QR-код попал бы чужой адрес
  let siteUrl = null;
  try {
    const u = new URL(String(req.body?.site_url || ""));
    if ((u.protocol === "https:" || u.protocol === "http:") && !u.username && !u.password && !u.search && !u.hash
      && u.host.toLowerCase() === String(host || "").toLowerCase()) {
      siteUrl = `${u.origin}${u.pathname}`.replace(/\/+$/, "");
    }
  } catch { /* нет или не адрес: берём адрес сервера */ }
  const prefix = siteUrl ? "" : (config.SITE_PREFIX || "");
  const base = siteUrl || `${protocol}://${host}${prefix}`;
  const verificationUrl = `${base}/link?code=${encodeURIComponent(pairing.user_code)}`;

  res.json({
    ok: true,
    code: pairing.code,
    user_code: pairing.user_code,
    verification_url: verificationUrl,
    expires_in: pairing.expires_in,
    interval: pairing.interval
  });
});

// 5. Device Flow — проверка кода перед подтверждением
router.get("/api/auth/device/check", deviceAuthLimiter, (req, res) => {
  const code = req.query?.code;
  const item = findPendingPairing(code);
  if (!item) {
    return res.status(404).json({ error: "Код связки не найден или истёк." });
  }
  res.json({ ok: true, user_code: item.user_code, device_name: item.name });
});

// 6. Device Flow — подтверждение кода пользователем
// Код короткий (6 знаков): подбор упирается в тот же лимит, что и выдача кодов
router.post("/api/auth/device/confirm", deviceAuthLimiter, requireLogin, (req, res) => {
  const userCode = req.body?.user_code || req.body?.code;
  const userId = req.user ? req.user.id : "shared";

  const result = authorizeDevicePairing(userCode, userId);
  if (!result.ok) {
    return res.status(400).json({ error: result.error });
  }

  res.json({ ok: true, message: `Устройство «${result.device_name}» успешно подключено!` });
});

// 7. Device Flow — поллинг статуса с устройства
router.post("/api/auth/device/poll", devicePollLimiter, (req, res) => {
  const code = req.body?.code;
  if (!code) return res.status(400).json({ error: "Параметр code обязателен." });

  const result = pollDevicePairing(code);

  if (result.status === "authorized") {
    // Приложение (ПК, ТВ) опрашивает со своим cookie устройства: его избранное и раздачи переходят пользователю
    if (result.user?.id) adoptDevice(readCookie(req, "sdev"), result.user.id);
    // Устанавливаем сессионную cookie, чтобы клиент мог работать как обычный браузер
    req.session.regenerate(err => {
      if (!err) {
        req.session.authenticated = true;
        req.session.meta = { app: true, dev: result.device_token ? hashToken(result.device_token) : "" }; // сеанс приложения: в списке сессий его представляет ключ устройства
        if (result.user?.id) req.session.uid = result.user.id;
        req.session.save(() => {
          res.json({
            ok: true,
            status: "authorized",
            device_token: result.device_token,
            user: result.user
          });
        });
        return;
      }
      res.json({
        ok: true,
        status: "authorized",
        device_token: result.device_token,
        user: result.user
      });
    });
    return;
  }

  if (result.status === "expired") {
    return res.status(410).json({ ok: false, status: "expired", error: "Код авторизации истёк." });
  }

  res.json({ ok: true, status: "pending" });
});

// 8. Завершение сеанса
router.post("/api/logout", requireLogin, (req, res) => {
  // Приложение вошло по ключу устройства: ключ больше не действует
  if (req.device) {
    revokeDeviceToken(req.get("X-Device-Token") || (req.get("Authorization") || "").replace(/^Bearer\s+/i, ""));
  }
  req.session.destroy(() => {
    res.clearCookie("movieportal.sid");
    res.json({ ok: true });
  });
});

// 8а. Активные сессии: входы в браузере и подключённые устройства (ПК, ТВ) этого пользователя
function sessionOwner(req) { return req.user ? req.user.id : "shared"; }
function currentDeviceHash(req) {
  const t = req.get("X-Device-Token") || (req.get("Authorization") || "").replace(/^Bearer\s+/i, "");
  return t ? hashToken(t) : "";
}
function collectSessions(req) {
  const uid = sessionOwner(req);
  const curHash = currentDeviceHash(req);
  const list = [];
  for (const s of sessionStore.listFor(uid)) {
    list.push({ id: "b" + s.id, kind: "browser", name: describeAgent(s.ua), created_at: s.created_at, last_seen: s.last_seen,
      current: !curHash && s.sid === req.sessionID, _sid: s.sid });
  }
  for (const d of listDevices(uid)) {
    list.push({ _hash: d.hash, id: "d" + d.id, kind: "device", name: d.name, created_at: d.created_at, last_seen: d.last_seen, current: d.hash === curHash });
  }
  list.sort((a, b) => (b.current - a.current) || (b.last_seen - a.last_seen));
  // Вход по общему паролю не привязан к человеку: у всех парольных один владелец "shared". Чужие входы
  // им не показываем и завершать не даём — только свою текущую сессию
  return uid === "shared" ? list.filter(s => s.current) : list;
}

router.get("/api/sessions", requireLogin, apiLimiter, (req, res) => {
  res.set("Cache-Control", "no-store");
  res.json({ ok: true, shared: sessionOwner(req) === "shared", sessions: collectSessions(req).map(({ _sid, _hash, ...s }) => s) });
});

function revokeOne(req, res, item) {
  if (item.kind === "device") {
    const ok = revokeDeviceById(sessionOwner(req), item.id.slice(1));
    if (ok) sessionStore.destroyAppSessions(sessionOwner(req), item._hash);
    return ok;
  }
  if (item.current && req.session) {
    // Свою сессию — через req.session: иначе express-session («скользящая» cookie) в конце ответа снова поставит cookie
    req.session.destroy(() => {});
    res.clearCookie("movieportal.sid");
  } else {
    sessionStore.destroy(item._sid);
  }
  return true;
}

router.post("/api/sessions/revoke", requireLogin, apiLimiter, (req, res) => {
  const id = String(req.body?.id || "");
  const item = collectSessions(req).find(s => s.id === id);
  if (!item) return res.status(404).json({ error: "Сессия не найдена или уже завершена." });
  revokeOne(req, res, item);
  res.json({ ok: true, current: item.current });
});

router.post("/api/sessions/revoke-others", requireLogin, apiLimiter, (req, res) => {
  let n = 0;
  for (const item of collectSessions(req)) if (!item.current && revokeOne(req, res, item)) n++;
  res.json({ ok: true, revoked: n });
});

// Завершить давно неактивные (по умолчанию — не заходили больше 7 дней); текущую не трогает
router.post("/api/sessions/revoke-stale", requireLogin, apiLimiter, (req, res) => {
  const days = Math.min(365, Math.max(1, Number(req.body?.days) || 7));
  const limit = Date.now() - days * 864e5;
  let n = 0;
  for (const item of collectSessions(req)) if (!item.current && item.last_seen < limit && revokeOne(req, res, item)) n++;
  res.json({ ok: true, revoked: n });
});

// 9. Получение текущего состояния сеанса и пользователя
router.get("/api/session", (req, res) => {
  const isAuth = resolveAuth(req);

  if (!isAuth) {
    return res.json({
      authenticated: false,
      discord_enabled: config.DISCORD_ENABLED,
      password_enabled: config.PASSWORD_AUTH_ENABLED
    });
  }

  let userInfo = null;
  if (req.user) {
    userInfo = {
      id: req.user.id,
      name: req.user.name,
      username: req.user.username,
      avatar: req.user.avatar,
      role: req.user.role
    };
  } else {
    // Вход по паролю
    userInfo = {
      id: "shared",
      name: "Общий доступ",
      username: "shared",
      avatar: "",
      role: "user" // не "admin": удалять готовые раздачи общий пароль не может (isLibraryAdmin)
    };
  }

  res.json({
    authenticated: true,
    user: userInfo,
    discord_enabled: config.DISCORD_ENABLED,
    // ID приложения Discord (публичный, он и так в ссылке входа): ПК показывает по нему активность в профиле
    discord_client_id: config.DISCORD_CLIENT_ID || "",
    password_enabled: config.PASSWORD_AUTH_ENABLED
  });
});

// 10. Статус аккаунта AllDebrid
router.get("/api/status", requireLogin, apiLimiter, async (req, res) => {
  try {
    const data = await alldebridRequest("/user");
    res.json({
      ok: true,
      account: data.data?.user
        ? {
            username: data.data.user.username,
            premium: data.data.user.isPremium
          }
        : null
    });
  } catch (err) {
    res.status(err.status || 502).json({ error: err.userMessage || "Не удалось связаться с облачным сервисом." });
  }
});

module.exports = router;
