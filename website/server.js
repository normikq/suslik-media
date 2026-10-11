require("dotenv").config();

const express = require("express");
const axios = require("axios");
const session = require("express-session");
const rateLimit = require("express-rate-limit");
const path = require("path");
const crypto = require("crypto");
const { spawn } = require("child_process");
const fs = require("fs");
const https = require("https");
const torrentSearch = require("./search");

const app = express();
const PORT = Number(process.env.PORT || 3000);
const SITE_PASSWORD = process.env.SITE_PASSWORD || "";
const SESSION_SECRET = process.env.SESSION_SECRET || "";
const ALLDEBRID_API_KEY = process.env.ALLDEBRID_API_KEY || "";
const TMDB_API_KEY = process.env.TMDB_API_KEY || "";
const COOKIE_SECURE = process.env.COOKIE_SECURE === "true";
// Where small runtime files live (sessions, library cache, metadata cache). Defaults to the app folder.
const DATA_DIR = process.env.DATA_DIR ? path.resolve(process.env.DATA_DIR) : __dirname;
try { fs.mkdirSync(DATA_DIR, { recursive: true }); } catch {}

// Debounced atomic JSON writer shared by the small file-backed caches.
function makeJsonWriter(file, collect, delayMs = 2000) {
  let timer = null;
  const write = () => {
    timer = null;
    try {
      const tmp = file + ".tmp";
      fs.writeFileSync(tmp, JSON.stringify(collect()), { mode: 0o600 });
      fs.renameSync(tmp, file);
    } catch (err) { console.error(`Не удалось сохранить ${path.basename(file)}:`, err.message); }
  };
  const schedule = (ms = delayMs) => { if (!timer) { timer = setTimeout(write, ms); timer.unref?.(); } };
  schedule.flush = () => { if (timer) { clearTimeout(timer); write(); } };
  pendingWriters.push(schedule);
  return schedule;
}
const pendingWriters = [];
for (const sig of ["SIGINT", "SIGTERM"]) process.on(sig, () => { for (const w of pendingWriters) w.flush(); process.exit(0); });

// Limit each client IP to two unfinished magnet/torrent submissions.
// This is process-local; use a shared store if running multiple Node workers/instances.
const MAX_ACTIVE_DOWNLOADS_PER_IP = 2;
const activeDownloadsByIp = new Map(); // ip -> { ids: Set<string>, pending: number }
const downloadOwnerById = new Map(); // magnet id -> ip

function clientIp(req) {
  return req.ip || req.socket?.remoteAddress || "unknown";
}
// ---- Владелец раздачи ------------------------------------------------------------------------
// Кто добавил magnet/torrent в библиотеку, тот один и может его удалить/отменить. Устройство определяется
// долгоживущим cookie "sdev" (случайный токен; IP меняется при переходе Wi-Fi <-> мобильная сеть, поэтому
// на него нельзя опираться). Записи без владельца (добавлены раньше или напрямую в AllDebrid) удалять может любой.
// Отключить проверку: OWNER_LOCK=false.
const OWNER_LOCK = process.env.OWNER_LOCK !== "false";
const OWNERS_FILE = path.join(DATA_DIR, "owners.json");
const magnetOwners = new Map(); // magnet id -> { dev, ip, at }
try {
  for (const [k, v] of Object.entries(JSON.parse(fs.readFileSync(OWNERS_FILE, "utf8")))) if (v && typeof v.dev === "string") magnetOwners.set(k, v);
} catch {}
const persistOwners = makeJsonWriter(OWNERS_FILE, () => Object.fromEntries(magnetOwners), 1000);
function readCookie(req, name) {
  const m = new RegExp(`(?:^|;\\s*)${name}=([^;]+)`).exec(req.headers.cookie || "");
  return m ? m[1] : "";
}
function deviceId(req, res) {
  let id = readCookie(req, "sdev");
  if (!/^[a-f0-9]{32}$/.test(id)) {
    id = crypto.randomBytes(16).toString("hex");
    if (res) res.cookie("sdev", id, { httpOnly: true, sameSite: "lax", secure: COOKIE_SECURE, maxAge: 5 * 365 * 24 * 3600 * 1000, path: "/" });
  }
  return id;
}
function recordOwner(id, dev, ip) {
  if (id === undefined || id === null) return;
  magnetOwners.set(String(id), { dev, ip, at: Date.now() });
  if (magnetOwners.size > 5000) for (const k of [...magnetOwners.keys()].slice(0, magnetOwners.size - 5000)) magnetOwners.delete(k);
  persistOwners();
}
// ---- Избранное -------------------------------------------------------------------------------
// У каждого устройства (cookie "sdev") своё избранное: отмеченные раздачи показываются в библиотеке первыми.
const FAVORITES_FILE = path.join(DATA_DIR, "favorites.json");
const favorites = new Map(); // dev -> Set(magnet id)
try {
  for (const [dev, ids] of Object.entries(JSON.parse(fs.readFileSync(FAVORITES_FILE, "utf8"))))
    if (/^[a-f0-9]{32}$/.test(dev) && Array.isArray(ids)) favorites.set(dev, new Set(ids.map(String).slice(0, 2000)));
} catch {}
const persistFavorites = makeJsonWriter(FAVORITES_FILE, () => Object.fromEntries([...favorites].map(([d, set]) => [d, [...set]])), 1000);
function favoritesOf(req) {
  const id = readCookie(req, "sdev");
  return /^[a-f0-9]{32}$/.test(id) ? (favorites.get(id) || null) : null;
}
function canRemove(id, req) {
  if (!OWNER_LOCK) return true;
  const o = magnetOwners.get(String(id));
  return !o || o.dev === deviceId(req, null); // только cookie устройства, IP не учитывается
}
function ipDownloadState(ip) {
  if (!activeDownloadsByIp.has(ip)) activeDownloadsByIp.set(ip, { ids: new Set(), pending: 0 });
  return activeDownloadsByIp.get(ip);
}
function reserveDownloadSlot(req, res) {
  const ip = clientIp(req);
  const dev = deviceId(req, res);
  const state = ipDownloadState(ip);
  if (state.ids.size + state.pending >= MAX_ACTIVE_DOWNLOADS_PER_IP) {
    res.status(429).json({ error: "Для этого IP-адреса уже выполняются две загрузки. Дождитесь завершения одной из них или отмените её." });
    return null;
  }
  state.pending += 1;
  let completed = false;
  return {
    attach(id, ready) {
      if (completed) return;
      completed = true;
      state.pending = Math.max(0, state.pending - 1);
      recordOwner(id, dev, ip);
      if (!ready && id !== undefined && id !== null) {
        const key = String(id);
        state.ids.add(key);
        downloadOwnerById.set(key, ip);
      }
      cleanupIpState(ip, state);
    },
    release() {
      if (completed) return;
      completed = true;
      state.pending = Math.max(0, state.pending - 1);
      cleanupIpState(ip, state);
    }
  };
}
function cleanupIpState(ip, state) {
  if (state && state.ids.size === 0 && state.pending === 0) activeDownloadsByIp.delete(ip);
}
function releaseTrackedDownload(id) {
  const key = String(id);
  const ip = downloadOwnerById.get(key);
  if (!ip) return;
  downloadOwnerById.delete(key);
  const state = activeDownloadsByIp.get(ip);
  if (state) {
    state.ids.delete(key);
    cleanupIpState(ip, state);
  }
}

if (!SESSION_SECRET) {
  console.warn("ПРЕДУПРЕЖДЕНИЕ: SESSION_SECRET не задан. Укажите его в .env перед использованием сервера вне локального компьютера.");
}
if (!SITE_PASSWORD) {
  console.warn("ПРЕДУПРЕЖДЕНИЕ: SITE_PASSWORD не задан. Вход будет недоступен, пока пароль не будет указан в .env.");
}

app.disable("x-powered-by");
// Behind nginx/Caddy set TRUST_PROXY=1 so req.protocol/host reflect the public https URL.
if (process.env.TRUST_PROXY) app.set("trust proxy", /^\d+$/.test(process.env.TRUST_PROXY) ? Number(process.env.TRUST_PROXY) : process.env.TRUST_PROXY);
// Сжатие текстовых ответов (страница, JSON медиатеки и поиска, CSS/JS): меньше байт — меньше работы радио телефона.
// Видео-прокси, WebDAV и плейлисты не сжимаются: это потоки с Range, им сжатие только мешает.
try {
  const compression = require("compression");
  const NO_COMPRESS = /^\/(?:api\/file\/proxy|api\/playlist|api\/app\/download|webdav)(?:\/|$)/;
  app.use(compression({
    threshold: 1024,
    filter: (req, res) => !NO_COMPRESS.test(req.path) && !req.headers.range && compression.filter(req, res)
  }));
} catch { console.warn("Модуль compression не установлен (npm install): ответы отдаются без сжатия."); }
// Playlists carry one AllDebrid link per episode and can exceed the default 20 kb.
app.use("/api/playlist", express.json({ limit: "200kb" }));
app.use(express.json({ limit: "20kb" }));
app.use(express.urlencoded({ extended: false, limit: "20kb" }));

const SESSION_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;

// Sessions survive a server restart (the default MemoryStore forgets everyone and grows without bound).
// One shared password means very few sessions, so a JSON file is plenty for this server.
class FileSessionStore extends session.Store {
  constructor(file) {
    super();
    this.map = new Map();
    try {
      const saved = JSON.parse(fs.readFileSync(file, "utf8"));
      const now = Date.now();
      for (const [sid, entry] of Object.entries(saved)) if (entry && entry.expires > now) this.map.set(sid, entry);
    } catch {}
    this.save = makeJsonWriter(file, () => {
      const now = Date.now();
      for (const [sid, entry] of this.map) if (entry.expires < now) this.map.delete(sid);
      while (this.map.size > 500) this.map.delete(this.map.keys().next().value);
      return Object.fromEntries(this.map);
    });
  }
  _expiry(sess) {
    const e = sess && sess.cookie && sess.cookie.expires;
    const t = e ? new Date(e).getTime() : NaN;
    return Number.isFinite(t) ? t : Date.now() + SESSION_MAX_AGE_MS;
  }
  get(sid, cb) {
    const entry = this.map.get(sid);
    if (!entry) return cb(null, null);
    if (entry.expires < Date.now()) { this.map.delete(sid); return cb(null, null); }
    try { cb(null, JSON.parse(entry.data)); } catch (err) { cb(err); }
  }
  set(sid, sess, cb) { this.map.set(sid, { data: JSON.stringify(sess), expires: this._expiry(sess) }); this.save(); cb && cb(null); }
  touch(sid, sess, cb) {
    const entry = this.map.get(sid);
    if (entry) { entry.expires = this._expiry(sess); this.save(30000); } // rolling cookies: write at most every 30 s
    cb && cb(null);
  }
  destroy(sid, cb) { this.map.delete(sid); this.save(); cb && cb(null); }
}
const sessionStore = new FileSessionStore(path.join(DATA_DIR, "sessions.json"));

app.use(session({
  name: "movieportal.sid",
  store: sessionStore,
  secret: SESSION_SECRET || crypto.randomBytes(32).toString("hex"),
  resave: false,
  saveUninitialized: false,
  rolling: true, // Refresh cookie expiration while the authenticated session is active.
  cookie: {
    httpOnly: true,
    sameSite: "strict",
    secure: COOKIE_SECURE,
    maxAge: SESSION_MAX_AGE_MS
  }
}));

const loginLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 10,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  message: { error: "Слишком много попыток входа. Попробуйте позже." }
});

const apiLimiter = rateLimit({
  windowMs: 60 * 1000,
  // Status polling and library refreshes are frequent; keep this above normal UI usage.
  limit: 120,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  message: { error: "Слишком много запросов. Подождите немного и повторите попытку." },
  handler: (req, res, next, options) => res.status(options.statusCode).json(options.message)
});

function requireLogin(req, res, next) {
  if (req.session && req.session.authenticated) return next();
  return res.status(401).json({ error: "Сначала выполните вход." });
}

function safeHttpsUrl(value) {
  try {
    const u = new URL(value);
    return u.protocol === "https:" ? u : null;
  } catch {
    return null;
  }
}

async function alldebridRequest(endpoint, params = {}) {
  if (!ALLDEBRID_API_KEY) {
    const err = new Error("На сервере не задан ALLDEBRID_API_KEY. Добавьте ключ в .env и перезапустите сервер.");
    err.status = 503;
    throw err;
  }

  const response = await axios.get(`https://api.alldebrid.com/v4${endpoint}`, {
    params,
    headers: { Authorization: `Bearer ${ALLDEBRID_API_KEY}` },
    timeout: 15000,
    validateStatus: () => true
  });

  if (response.status < 200 || response.status >= 300) {
    const err = new Error(`AllDebrid HTTP error (${response.status}).`);
    err.status = 502;
    throw err;
  }

  const data = response.data;
  if (data && data.status === "error") {
    const err = new Error(data.error?.message || "AllDebrid отклонил запрос.");
    err.status = 502;
    throw err;
  }
  return data;
}

app.post("/api/login", loginLimiter, (req, res) => {
  if (!SITE_PASSWORD) return res.status(503).json({ error: "Set SITE_PASSWORD in .env first." });
  const password = typeof req.body.password === "string" ? req.body.password : "";
  if (!password || password.length > 256 || password !== SITE_PASSWORD) {
    return res.status(401).json({ error: "Неверный пароль." });
  }
  req.session.authenticated = true;
  req.session.save(err => {
    if (err) return res.status(500).json({ error: "Не удалось создать сеанс." });
    res.json({ ok: true });
  });
});

app.post("/api/logout", requireLogin, (req, res) => {
  req.session.destroy(() => {
    res.clearCookie("movieportal.sid");
    res.json({ ok: true });
  });
});

app.get("/api/session", (req, res) => {
  res.json({ authenticated: Boolean(req.session?.authenticated) });
});

app.get("/api/status", requireLogin, apiLimiter, async (req, res) => {
  try {
    const data = await alldebridRequest("/user");
    res.json({ ok: true, account: data.data?.user ? {
      username: data.data.user.username,
      premium: data.data.user.isPremium
    } : null });
  } catch (err) {
    res.status(err.status || 502).json({ error: err.message || "Не удалось связаться с AllDebrid." });
  }
});

app.post("/api/unlock", requireLogin, apiLimiter, async (req, res) => {
  const raw = typeof req.body.url === "string" ? req.body.url.trim() : "";
  const url = safeHttpsUrl(raw);
  if (!url) return res.status(400).json({ error: "Введите корректную ссылку, начинающуюся с https://." });

  // Only submit a URL to the provider; never fetch user-supplied URLs from this server.
  try {
    const data = await alldebridRequest("/link/unlock", { link: url.toString() });
    const result = data.data || {};
    const streamUrl = typeof result.link === "string" ? result.link : "";
    if (!safeHttpsUrl(streamUrl)) {
      return res.status(502).json({ error: "AllDebrid не вернул подходящую HTTPS-ссылку для воспроизведения." });
    }

    res.json({
      ok: true,
      filename: result.filename || "Ссылка для воспроизведения",
      filesize: result.filesize || null,
      mimeType: result.type || null,
      url: streamUrl,
      host: result.host || null
    });
  } catch (err) {
    res.status(err.status || 502).json({ error: err.message || "Не удалось обработать эту ссылку." });
  }
});


// Magnet support: submit a magnet, check its status, then retrieve available files.
app.post("/api/magnets", requireLogin, apiLimiter, async (req, res) => {
  const magnet = typeof req.body.magnet === "string" ? req.body.magnet.trim() : "";
  if (!magnet || magnet.length > 5000 || !magnet.startsWith("magnet:?")) {
    return res.status(400).json({ error: "Введите корректную ссылку magnet:?." });
  }
  const slot = reserveDownloadSlot(req, res);
  if (!slot) return;
  try {
    const response = await axios.post("https://api.alldebrid.com/v4/magnet/upload",
      new URLSearchParams({ "magnets[]": magnet }),
      { headers: { Authorization: `Bearer ${ALLDEBRID_API_KEY}`, "Content-Type": "application/x-www-form-urlencoded" },
        timeout: 20000, validateStatus: () => true });
    if (response.status < 200 || response.status >= 300 || response.data?.status === "error") {
      slot.release();
      return res.status(502).json({ error: response.data?.error?.message || "AllDebrid не смог принять эту magnet-ссылку." });
    }
    const item = response.data?.data?.magnets?.[0];
    if (!item || item.error) {
      slot.release();
      return res.status(400).json({ error: item?.error?.message || "AllDebrid не вернул результат для magnet-ссылки." });
    }
    slot.attach(item.id, Boolean(item.ready) || Number(item.statusCode) === 4);
    const hash = typeof item.hash === "string" ? item.hash.trim().toLowerCase() : null;
    const sha256 = hash ? crypto.createHash("sha256").update(hash).digest("hex") : null;
    res.json({ ok: true, id: item.id, name: item.name || "Magnet", ready: Boolean(item.ready), hash, sha256 });
  } catch (err) {
    slot.release();
    res.status(502).json({ error: err.message || "Не удалось отправить magnet-ссылку." });
  }
});

// Upload a .torrent file to AllDebrid; each IP shares the same two-download limit.
app.post("/api/torrents", requireLogin, apiLimiter, express.raw({ type: "application/x-bittorrent", limit: "1mb" }), async (req, res) => {
  const bytes = req.body;
  let rawName = typeof req.get("X-Torrent-Filename") === "string" ? req.get("X-Torrent-Filename") : "upload.torrent";
  try { rawName = decodeURIComponent(rawName); } catch {}
  const filename = path.basename(rawName).replace(/[\r\n"\\]/g, "_").slice(0, 180);
  if (!Buffer.isBuffer(bytes) || bytes.length === 0) return res.status(400).json({ error: "Выберите непустой файл .torrent." });
  if (bytes.length > 1024 * 1024) return res.status(413).json({ error: "Torrent file must be 1 MB or smaller." });
  if (!filename.toLowerCase().endsWith(".torrent")) return res.status(400).json({ error: "Поддерживаются только файлы .torrent." });
  const slot = reserveDownloadSlot(req, res);
  if (!slot) return;
  const boundary = `----MoviePortal${crypto.randomBytes(16).toString("hex")}`;
  const body = Buffer.concat([
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="files[]"; filename="${filename}"\r\nContent-Type: application/x-bittorrent\r\n\r\n`),
    bytes,
    Buffer.from(`\r\n--${boundary}--\r\n`)
  ]);
  try {
    const response = await axios.post("https://api.alldebrid.com/v4/magnet/upload/file", body, {
      headers: { Authorization: `Bearer ${ALLDEBRID_API_KEY}`, "Content-Type": `multipart/form-data; boundary=${boundary}`, "Content-Length": body.length },
      timeout: 30000, maxBodyLength: 2 * 1024 * 1024, validateStatus: () => true
    });
    if (response.status < 200 || response.status >= 300 || response.data?.status === "error") {
      slot.release();
      return res.status(502).json({ error: response.data?.error?.message || "AllDebrid не смог принять этот торрент-файл." });
    }
    const item = response.data?.data?.files?.[0];
    if (!item || item.error) {
      slot.release();
      return res.status(400).json({ error: item?.error?.message || "AllDebrid не вернул результат для торрент-файла." });
    }
    slot.attach(item.id, Boolean(item.ready) || Number(item.statusCode) === 4);
    const hash = typeof item.hash === "string" ? item.hash.trim().toLowerCase() : null;
    const sha256 = hash ? crypto.createHash("sha256").update(hash).digest("hex") : null;
    res.json({ ok: true, id: item.id, name: item.name || filename.replace(/\.torrent$/i, "") || "Torrent download", ready: Boolean(item.ready), hash, sha256, size: item.size || 0, filename });
  } catch (err) {
    slot.release();
    res.status(502).json({ error: err.message || "Не удалось загрузить торрент-файл." });
  }
});

// ---- Поиск по трекерам + добавление результата в библиотеку --------------------------------
// Провайдеры описаны в search.js. Браузер получает только непрозрачный id результата: URL .torrent
// (он может содержать API-ключ Prowlarr/Jackett) остаётся на сервере, произвольные адреса клиент задать не может.
const AD_BASE = process.env.ALLDEBRID_API_BASE || "https://api.alldebrid.com";
const searchLimiter = rateLimit({
  windowMs: 60 * 1000, limit: 15, standardHeaders: "draft-7", legacyHeaders: false,
  handler: (req, res, next, options) => res.status(options.statusCode).json({ error: "Слишком много поисковых запросов. Подождите минуту." })
});
app.get("/api/search", requireLogin, searchLimiter, async (req, res) => {
  res.setHeader("Cache-Control", "private, no-store");
  try {
    res.json(await torrentSearch.search(typeof req.query.q === "string" ? req.query.q : ""));
  } catch (err) {
    res.status(err.status || 502).json({ error: err.status ? err.message : "Не удалось выполнить поиск. Попробуйте ещё раз." });
  }
});

function adFail(status, message) { const e = new Error(message); e.status = status; return e; }
async function adUploadMagnet(magnet) {
  const response = await axios.post(`${AD_BASE}/v4/magnet/upload`, new URLSearchParams({ "magnets[]": magnet }),
    { headers: { Authorization: `Bearer ${ALLDEBRID_API_KEY}`, "Content-Type": "application/x-www-form-urlencoded" }, timeout: 20000, validateStatus: () => true });
  if (response.status < 200 || response.status >= 300 || response.data?.status === "error") {
    throw adFail(502, response.data?.error?.message || "AllDebrid не смог принять эту magnet-ссылку.");
  }
  const item = response.data?.data?.magnets?.[0];
  if (!item || item.error) throw adFail(400, item?.error?.message || "AllDebrid не вернул результат для magnet-ссылки.");
  return item;
}
async function adUploadTorrent(bytes, filename) {
  const safeName = path.basename(filename).replace(/[\r\n"\\]/g, "_").slice(0, 180);
  const boundary = `----MoviePortal${crypto.randomBytes(16).toString("hex")}`;
  const body = Buffer.concat([
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="files[]"; filename="${safeName}"\r\nContent-Type: application/x-bittorrent\r\n\r\n`),
    bytes, Buffer.from(`\r\n--${boundary}--\r\n`)
  ]);
  const response = await axios.post(`${AD_BASE}/v4/magnet/upload/file`, body, {
    headers: { Authorization: `Bearer ${ALLDEBRID_API_KEY}`, "Content-Type": `multipart/form-data; boundary=${boundary}`, "Content-Length": body.length },
    timeout: 30000, maxBodyLength: 4 * 1024 * 1024, validateStatus: () => true });
  if (response.status < 200 || response.status >= 300 || response.data?.status === "error") {
    throw adFail(502, response.data?.error?.message || "AllDebrid не смог принять этот торрент-файл.");
  }
  const item = response.data?.data?.files?.[0];
  if (!item || item.error) throw adFail(400, item?.error?.message || "AllDebrid не вернул результат для торрент-файла.");
  return item;
}
// .torrent only from the configured Prowlarr/Jackett (their download URLs carry the API key).
// Jackett often answers with a redirect to a magnet: link — that is accepted as well.
async function fetchTorrentFromIndexer(url) {
  let current = url;
  for (let hop = 0; hop < 3; hop++) {
    let origin;
    try { origin = new URL(current).origin; } catch { throw adFail(400, "Некорректная ссылка на торрент."); }
    if (!torrentSearch.trustedTorrentOrigins().includes(origin)) throw adFail(400, "Скачивание .torrent разрешено только через настроенный Prowlarr/Jackett.");
    const r = await axios.get(current, { responseType: "arraybuffer", maxRedirects: 0, timeout: 20000,
      maxContentLength: 3 * 1024 * 1024, validateStatus: () => true, headers: { "User-Agent": "SuslikMedia/1.0" } });
    if (r.status >= 300 && r.status < 400 && r.headers.location) {
      const loc = String(r.headers.location);
      if (loc.startsWith("magnet:?")) return { magnet: loc };
      try { current = new URL(loc, current).toString(); } catch { throw adFail(502, "Индексатор вернул некорректный редирект."); }
      continue;
    }
    if (r.status !== 200) throw adFail(502, `Индексатор не отдал торрент (HTTP ${r.status}).`);
    const bytes = Buffer.from(r.data);
    if (bytes.length === 0 || bytes[0] !== 0x64) throw adFail(502, "Индексатор вернул не .torrent файл (возможно, нужна авторизация на трекере).");
    return { bytes };
  }
  throw adFail(502, "Слишком много редиректов при скачивании торрента.");
}

app.post("/api/search/add", requireLogin, apiLimiter, async (req, res) => {
  const r = torrentSearch.getResult(req.body?.id);
  if (!r) return res.status(404).json({ error: "Результат устарел. Повторите поиск." });
  if (!ALLDEBRID_API_KEY) return res.status(503).json({ error: "На сервере не задан ALLDEBRID_API_KEY." });
  const slot = reserveDownloadSlot(req, res);
  if (!slot) return;
  try {
    // Attempts in order of cost; the first one AllDebrid accepts wins, the last error is reported otherwise.
    const fileName = (cleanFileName(r.title) || "download") + ".torrent";
    const attempts = [];
    if (r.magnet) attempts.push(() => adUploadMagnet(r.magnet));
    if (r.lazy) {
      attempts.push(async () => { // closed trackers (rutracker/kinozal): magnet from the topic page / info hash
        const lz = await torrentSearch.resolveLazy(r);
        if (!lz.magnet) throw adFail(502, "Не удалось получить magnet-ссылку со страницы раздачи.");
        return adUploadMagnet(lz.magnet);
      });
      // Через TorAPI (без аккаунта) .torrent недоступен: остаётся только magnet, его ошибка и показывается.
      if (r.lazy.via !== "torapi") attempts.push(async () => adUploadTorrent(await torrentSearch.downloadLazy(r), fileName));
    }
    if (r.torrentUrl) {
      attempts.push(async () => {
        const got = await fetchTorrentFromIndexer(r.torrentUrl);
        return got.magnet ? adUploadMagnet(got.magnet) : adUploadTorrent(got.bytes, fileName);
      });
    }
    let item = null, lastErr = null;
    for (const attempt of attempts) {
      try { item = await attempt(); break; } catch (err) { lastErr = err; }
    }
    if (!item) throw lastErr || adFail(400, "У этого результата нет magnet-ссылки или торрент-файла.");
    slot.attach(item.id, Boolean(item.ready) || Number(item.statusCode) === 4);
    const hash = typeof item.hash === "string" ? item.hash.trim().toLowerCase() : (r.infoHash || null);
    const sha256 = hash ? crypto.createHash("sha256").update(hash).digest("hex") : null;
    res.json({ ok: true, id: item.id, name: item.name || r.title, ready: Boolean(item.ready), hash, sha256 });
  } catch (err) {
    slot.release();
    res.status(err.status || 502).json({ error: err.message || "Не удалось добавить результат в библиотеку." });
  }
});

// Browse the user's complete AllDebrid magnet library. All provider requests are server-side.
// ---- Library -------------------------------------------------------------------------
// The file tree of a finished magnet never changes, so it is fetched from AllDebrid once and kept
// (memory + file). A refresh then costs one magnet/status call plus the trees of new magnets only.
const TREE_CACHE_FILE = path.join(DATA_DIR, "library-files-cache.json");
const treeCache = new Map(); // magnet id -> flat file list (finished magnets only)
try {
  for (const [id, files] of Object.entries(JSON.parse(fs.readFileSync(TREE_CACHE_FILE, "utf8")))) {
    if (Array.isArray(files)) treeCache.set(Number(id), files);
  }
} catch {}
const persistTreeCache = makeJsonWriter(TREE_CACHE_FILE, () => Object.fromEntries(treeCache));

// Flatten AllDebrid's nested tree. The order of this list is the file "index" used by playlists.
function flattenMagnetFiles(root) {
  const out = [];
  const walk = (nodes, prefix = "") => {
    if (!Array.isArray(nodes)) return;
    for (const node of nodes) {
      if (!node || typeof node !== "object") continue;
      const name = typeof node.n === "string" ? node.n : (typeof node.name === "string" ? node.name : "");
      if (!name) continue;
      const fullPath = prefix ? `${prefix}/${name}` : name;
      const children = node.e || node.files || node.children;
      const isFolder = Array.isArray(children) && children.length > 0;
      const link = typeof node.l === "string" && safeHttpsUrl(node.l) ? node.l : (typeof node.link === "string" && safeHttpsUrl(node.link) ? node.link : null);
      out.push({ name, path: fullPath, type: isFolder ? "folder" : "file", size: Number(node.s || node.size || 0) || null, sourceLink: link });
      if (Array.isArray(children)) walk(children, fullPath);
    }
  };
  walk(root?.files || root?.links || root?.e || []);
  return out;
}

const adAuth = () => ({ Authorization: `Bearer ${ALLDEBRID_API_KEY}` });
async function fetchMagnetTrees(ids) {
  const result = new Map();
  for (let offset = 0; offset < ids.length; offset += 25) {
    const form = new URLSearchParams();
    for (const id of ids.slice(offset, offset + 25)) form.append("id[]", String(id));
    const response = await axios.post("https://api.alldebrid.com/v4/magnet/files", form,
      { headers: { ...adAuth(), "Content-Type": "application/x-www-form-urlencoded" }, timeout: 25000, validateStatus: () => true });
    // Keep the library usable even if the file details of one batch are unavailable.
    if (response.status < 200 || response.status >= 300 || response.data?.status === "error") continue;
    const raw = response.data?.data?.magnets;
    for (const root of Array.isArray(raw) ? raw : (raw && typeof raw === "object" ? [raw] : [])) {
      result.set(Number(root.id), flattenMagnetFiles(root));
    }
  }
  return result;
}

// File list of one magnet for playlists: cached tree when known, otherwise a single API call.
async function getMagnetFileList(id) {
  if (treeCache.has(id)) return treeCache.get(id);
  return (await fetchMagnetTrees([id])).get(id) || null;
}

// Для каждого устройства свои права: кнопка «Отменить/Удалить» показывается только владельцу раздачи.
function withRemovable(data, req) {
  const fav = favoritesOf(req);
  return { ...data, magnets: data.magnets.map(m => ({ ...m, canRemove: canRemove(m.id, req), favorite: Boolean(fav && fav.has(String(m.id))) })) };
}

// Отметить / снять избранное для этого устройства. Тело: { id, favorite: true|false }.
app.post("/api/favorites", requireLogin, apiLimiter, (req, res) => {
  const id = String(req.body?.id ?? "").trim();
  if (!/^\d{1,20}$/.test(id)) return res.status(400).json({ error: "Некорректный id раздачи." });
  const dev = deviceId(req, res);
  let set = favorites.get(dev);
  if (!set) { set = new Set(); favorites.set(dev, set); }
  const on = req.body?.favorite !== false;
  if (on) { set.add(id); if (set.size > 2000) set.delete(set.values().next().value); } else set.delete(id);
  if (!set.size) favorites.delete(dev);
  if (favorites.size > 5000) favorites.delete(favorites.keys().next().value);
  persistFavorites();
  res.json({ ok: true, id, favorite: on });
});
// ---- Обновления приложений ---------------------------------------------------------------------
// Достаточно положить файл в папку updates/ (рядом с server.js или в DATA_DIR): версия берётся из имени.
//   Android: *.apk  — например suslik-media-1.3.4.apk
//   Windows: *.msi (или *.exe) — например "Suslik Media-1.4.6.msi"
// Описание изменений (необязательно): файл с тем же именем и расширением .txt.
// Выбирается файл с самой большой версией. Загрузить можно и по сети (если задан UPDATE_TOKEN):
//   curl -H "X-Update-Token: <токен>" -H "X-File-Name: suslik-media-1.3.4.apk" -H "Content-Type: application/octet-stream" \
//        --data-binary @app.apk https://<сайт>/api/app/upload
const UPDATES_DIR = process.env.UPDATES_DIR ? path.resolve(process.env.UPDATES_DIR) : path.join(DATA_DIR, "updates");
try { fs.mkdirSync(UPDATES_DIR, { recursive: true }); } catch {}
const UPDATE_KINDS = { android: /\.apk$/i, desktop: /\.(msi|exe)$/i };
function versionOf(name) { const m = /(\d+)\.(\d+)\.(\d+)/.exec(name); return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null; }
function cmpVersion(a, b) { for (let i = 0; i < 3; i++) if ((a[i] || 0) !== (b[i] || 0)) return (a[i] || 0) - (b[i] || 0); return 0; }
function latestUpdate(platform) {
  const re = UPDATE_KINDS[platform]; if (!re) return null;
  let best = null;
  for (const name of fs.readdirSync(UPDATES_DIR)) {
    if (!re.test(name)) continue;
    const v = versionOf(name); if (!v) continue;
    const full = path.join(UPDATES_DIR, name);
    let st; try { st = fs.statSync(full); } catch { continue; }
    if (!st.isFile() || st.size === 0) continue;
    // при одинаковой версии msi предпочтительнее exe
    if (!best || cmpVersion(v, best.v) > 0 || (cmpVersion(v, best.v) === 0 && /\.msi$/i.test(name))) best = { v, name, full, size: st.size, at: st.mtimeMs };
  }
  if (!best) return null;
  pruneOldUpdates(platform, best);
  let notes = "";
  try { notes = fs.readFileSync(best.full.replace(/\.[^.]+$/, ".txt"), "utf8").slice(0, 4000).trim(); } catch {}
  return { ...best, version: best.v.join("."), notes };
}
// Остаётся только самая новая версия каждой платформы: старые .apk / .msi / .exe (и их .txt) удаляются —
// сразу после загрузки новой и при первом же запросе после того, как файл подложили вручную (scp).
function pruneOldUpdates(platform, best) {
  const re = UPDATE_KINDS[platform];
  let names; try { names = fs.readdirSync(UPDATES_DIR); } catch { return; }
  for (const name of names) {
    if (!re.test(name) || name === best.name) continue;
    const v = versionOf(name);
    // та же версия в другом формате (exe рядом с msi) тоже лишняя
    if (v && cmpVersion(v, best.v) > 0) continue;
    const full = path.join(UPDATES_DIR, name);
    fs.rm(full, { force: true }, err => { if (!err) console.log(`[updates] удалена старая версия: ${name}`); });
    const notes = full.replace(/\.[^.]+$/, ".txt");
    if (notes !== best.full.replace(/\.[^.]+$/, ".txt")) fs.rm(notes, { force: true }, () => {});
  }
}
// «Что нового»: changelog/releases.json — одно место для текстов сайта и обоих приложений.
// Формат: { "desktop": [{ version, title, items: [..] }, ...], "android": [...] }, новые версии сверху.
// Файл перечитывается, только если изменился (можно править на сервере без перезапуска).
const CHANGELOG_FILE = process.env.CHANGELOG_FILE ? path.resolve(process.env.CHANGELOG_FILE) : path.join(__dirname, "changelog", "releases.json");
const vparts = v => String(v).split(".").map(n => parseInt(n, 10) || 0);
let changelogCache = { mtime: 0, data: {} };
function readChangelog() {
  try {
    const st = fs.statSync(CHANGELOG_FILE);
    if (st.mtimeMs !== changelogCache.mtime) {
      const data = JSON.parse(fs.readFileSync(CHANGELOG_FILE, "utf8"));
      changelogCache = { mtime: st.mtimeMs, data: data && typeof data === "object" ? data : {} };
    }
  } catch (err) {
    if (err.code !== "ENOENT") console.warn(`[changelog] не удалось прочитать ${CHANGELOG_FILE}: ${err.message}`);
  }
  return changelogCache.data;
}
// GET /api/app/changelog?platform=desktop&upto=1.6.0 → { releases: [3 последних версии не новее upto] }
app.get("/api/app/changelog", apiLimiter, (req, res) => {
  const platform = String(req.query.platform || "");
  if (!UPDATE_KINDS[platform]) return res.status(400).json({ error: "platform: android | desktop" });
  const upto = typeof req.query.upto === "string" && /^\d+(\.\d+){0,3}$/.test(req.query.upto) ? req.query.upto : "";
  const limit = Math.min(10, Math.max(1, parseInt(req.query.limit, 10) || 3));
  const list = Array.isArray(readChangelog()[platform]) ? readChangelog()[platform] : [];
  const releases = list
    .filter(r => r && typeof r.version === "string" && Array.isArray(r.items))
    .map(r => ({ version: r.version, title: String(r.title || ""), items: r.items.map(String).slice(0, 12) }))
    .filter(r => !upto || cmpVersion(vparts(r.version), vparts(upto)) <= 0)
    .sort((a, b) => cmpVersion(vparts(b.version), vparts(a.version)))
    .slice(0, limit);
  res.setHeader("Cache-Control", "no-cache");
  res.json({ releases });
});
app.get("/api/app/update", requireLogin, apiLimiter, (req, res) => {
  const platform = String(req.query.platform || "");
  if (!UPDATE_KINDS[platform]) return res.status(400).json({ error: "platform: android | desktop" });
  const u = latestUpdate(platform);
  if (!u) return res.json({ available: false });
  res.json({ available: true, version: u.version, size: u.size, notes: u.notes, file: u.name, publishedAt: Math.round(u.at),
    url: `/api/app/download/${platform}/${encodeURIComponent(u.name)}` });
});
app.get("/api/app/download/:platform/:name", requireLogin, (req, res) => {
  const u = latestUpdate(String(req.params.platform));
  if (!u || u.name !== req.params.name) return res.status(404).json({ error: "Файл обновления не найден (возможно, уже вышла новее)." });
  res.setHeader("Cache-Control", "no-store");
  res.download(u.full, u.name, { dotfiles: "deny" }); // поддерживает Range: прерванная загрузка докачивается
});
app.post("/api/app/upload", (req, res) => {
  if (req.body && Object.keys(req.body).length) return res.status(400).json({ error: "Укажите -H \"Content-Type: application/octet-stream\"" });
  const token = process.env.UPDATE_TOKEN || "";
  const given = String(req.get("X-Update-Token") || "");
  if (!token || given.length !== token.length || !crypto.timingSafeEqual(Buffer.from(given), Buffer.from(token)))
    return res.status(403).json({ error: "Загрузка выключена или неверный X-Update-Token." });
  const name = path.basename(String(req.get("X-File-Name") || "")).replace(/[^\w .()+-]/g, "_").slice(0, 120);
  if (!name || !versionOf(name) || !Object.values(UPDATE_KINDS).some(re => re.test(name)))
    return res.status(400).json({ error: "X-File-Name: имя с версией и расширением .apk / .msi / .exe, например suslik-media-1.3.4.apk" });
  const tmp = path.join(UPDATES_DIR, `.upload-${crypto.randomBytes(6).toString("hex")}`);
  const out = fs.createWriteStream(tmp);
  let size = 0; const LIMIT = 500 * 1024 * 1024;
  req.on("data", c => { size += c.length; if (size > LIMIT) { req.destroy(); out.destroy(); fs.rm(tmp, { force: true }, () => {}); } });
  req.pipe(out); // на диск потоком — без буфера в памяти
  out.on("finish", () => {
    if (size === 0 || size > LIMIT) { fs.rm(tmp, { force: true }, () => {}); return res.status(400).json({ error: "Пустой или слишком большой файл." }); }
    fs.rename(tmp, path.join(UPDATES_DIR, name), err => {
      if (err) return res.status(500).json({ error: err.message });
      // сразу убрать прошлые версии этой платформы
      for (const platform of Object.keys(UPDATE_KINDS)) if (UPDATE_KINDS[platform].test(name)) latestUpdate(platform);
      res.json({ ok: true, file: name, size });
    });
  });
  out.on("error", err => { fs.rm(tmp, { force: true }, () => {}); if (!res.headersSent) res.status(500).json({ error: err.message }); });
});

app.get("/api/favorites", requireLogin, apiLimiter, (req, res) => {
  res.json({ ids: [...(favoritesOf(req) || [])] });
});
let libraryInflight = null;
let libraryLastGood = null;
async function buildLibrary() {
  const statusResponse = await axios.post("https://api.alldebrid.com/v4.1/magnet/status",
    new URLSearchParams(),
    { headers: { ...adAuth(), "Content-Type": "application/x-www-form-urlencoded" }, timeout: 20000, validateStatus: () => true });
  if (statusResponse.status < 200 || statusResponse.status >= 300 || statusResponse.data?.status === "error") {
    const err = new Error(statusResponse.data?.error?.message || "Не удалось загрузить список magnet-ссылок из AllDebrid.");
    err.userMessage = err.message;
    throw err;
  }
  const rawMagnets = statusResponse.data?.data?.magnets;
  const magnets = Array.isArray(rawMagnets) ? rawMagnets : (rawMagnets && typeof rawMagnets === "object" ? [rawMagnets] : []);
  const normalized = magnets.filter(m => m && Number.isSafeInteger(Number(m.id))).slice(0, 1000).map(m => ({
    id: Number(m.id), name: m.filename || m.name || `Magnet ${m.id}`,
    status: m.status || "Неизвестно", statusCode: Number(m.statusCode ?? 0),
    size: Number(m.size || 0), downloaded: Number(m.downloaded || 0), files: []
  }));
  const isReady = m => m.statusCode === 4;
  const missing = normalized.filter(m => !(isReady(m) && treeCache.has(m.id))).map(m => m.id);
  const fetched = missing.length ? await fetchMagnetTrees(missing) : new Map();
  let cacheChanged = false;
  for (const m of normalized) {
    if (isReady(m) && treeCache.has(m.id)) { m.files = treeCache.get(m.id); continue; }
    m.files = fetched.get(m.id) || [];
    if (isReady(m) && m.files.length) { treeCache.set(m.id, m.files); cacheChanged = true; }
  }
  // Forget trees of magnets that were deleted from the account.
  const present = new Set(normalized.map(m => m.id));
  for (const id of [...treeCache.keys()]) if (!present.has(id)) { treeCache.delete(id); cacheChanged = true; }
  if (cacheChanged) persistTreeCache();
  return { ok: true, total: normalized.length, magnets: normalized };
}

app.get("/api/library", requireLogin, apiLimiter, async (req, res) => {
  try {
    // Concurrent refreshes (several tabs/people) share one AllDebrid round trip.
    if (!libraryInflight) libraryInflight = buildLibrary().finally(() => { libraryInflight = null; });
    const data = await libraryInflight;
    libraryLastGood = data;
    res.setHeader("Cache-Control", "private, no-cache"); // revalidate; Express adds an ETag, so unchanged = 304
    res.json(withRemovable(data, req));
  } catch (err) {
    console.error("Не удалось получить медиатеку:", err.message);
    if (libraryLastGood) { res.setHeader("Cache-Control", "no-store"); return res.json({ ...withRemovable(libraryLastGood, req), stale: true }); }
    res.status(502).json({ error: err.userMessage || "Не удалось загрузить медиатеку AllDebrid. Попробуйте обновить её через некоторое время." });
  }
});
app.post(["/api/magnets/:id/cancel", "/api/magnets/:id/delete"], requireLogin, apiLimiter, async (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isSafeInteger(id) || id <= 0) return res.status(400).json({ error: "Некорректный идентификатор magnet-ссылки." });
  if (!canRemove(id, req)) return res.status(403).json({ error: "Удалить эту раздачу может только то устройство, с которого она была добавлена." });
  try {
    // Remove the magnet from the user's AllDebrid list, stopping its processing/download.
    const response = await axios.post("https://api.alldebrid.com/v4/magnet/delete",
      new URLSearchParams({ id: String(id) }),
      { headers: { Authorization: `Bearer ${ALLDEBRID_API_KEY}`, "Content-Type": "application/x-www-form-urlencoded" }, timeout: 15000, validateStatus: () => true });
    if (response.status < 200 || response.status >= 300 || response.data?.status === "error") {
      return res.status(502).json({ error: response.data?.error?.message || "Не удалось отменить эту magnet-ссылку в AllDebrid." });
    }
    releaseTrackedDownload(id);
    if (magnetOwners.delete(String(id))) persistOwners();
    res.json({ ok: true, id, message: response.data?.data?.message || "Magnet-ссылка удалена." });
  } catch (err) { res.status(502).json({ error: err.message || "Не удалось отменить magnet-ссылку." }); }
});

app.get("/api/magnets/:id", requireLogin, apiLimiter, async (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isSafeInteger(id) || id <= 0) return res.status(400).json({ error: "Некорректный идентификатор magnet-ссылки." });
  try {
    const response = await axios.post("https://api.alldebrid.com/v4.1/magnet/status",
      new URLSearchParams({ id: String(id) }),
      { headers: { Authorization: `Bearer ${ALLDEBRID_API_KEY}`, "Content-Type": "application/x-www-form-urlencoded" },
        timeout: 15000, validateStatus: () => true });
    if (response.status < 200 || response.status >= 300 || response.data?.status === "error") {
      return res.status(502).json({ error: response.data?.error?.message || "Не удалось получить состояние magnet-ссылки." });
    }
    const magnets = response.data?.data?.magnets;
    const list = Array.isArray(magnets) ? magnets : (magnets && typeof magnets === "object" ? [magnets] : []);
    // Match the requested ID; never assume the first item is the requested magnet.
    let magnet = list.find(item => String(item.id) === String(id));

    // Some API responses may omit the id-filtered result temporarily. Retry using GET query parameters.
    if (!magnet) {
      const fallback = await axios.get("https://api.alldebrid.com/v4.1/magnet/status", {
        params: { id },
        headers: { Authorization: `Bearer ${ALLDEBRID_API_KEY}` },
        timeout: 15000,
        validateStatus: () => true
      });
      const fallbackMagnets = fallback.data?.data?.magnets;
      const fallbackList = Array.isArray(fallbackMagnets) ? fallbackMagnets : (fallbackMagnets && typeof fallbackMagnets === "object" ? [fallbackMagnets] : []);
      magnet = fallbackList.find(item => String(item.id) === String(id));
    }
    if (!magnet) {
      console.warn("В ответе AllDebrid отсутствует запрошенный идентификатор magnet-ссылки", id, "response keys:", Object.keys(response.data?.data || {}));
      return res.status(502).json({ error: "AllDebrid не вернул состояние этой magnet-ссылки. Возможно, она ещё регистрируется. Повторите попытку через несколько секунд." });
    }
    const statusCode = Number.isFinite(Number(magnet.statusCode)) ? Number(magnet.statusCode) : 0;
    // AllDebrid status 4 is ready; 5+ are terminal errors. Free the IP slot in either case.
    if (statusCode >= 4) releaseTrackedDownload(id);
    res.json({
      id: magnet.id, filename: magnet.filename, status: magnet.status || "Обработка",
      statusCode,
      downloaded: magnet.downloaded || 0,
      size: magnet.size || 0, ready: statusCode === 4
    });
  } catch (err) {
    res.status(502).json({ error: err.message || "Не удалось получить состояние magnet-ссылки." });
  }
});

// Resolve an AllDebrid magnet file's /f/ page into a true download URL.
// Never return the original account-page URL as if it were a direct link.
app.post("/api/file/unlock", requireLogin, apiLimiter, async (req, res) => {
  const raw = typeof req.body.link === "string" ? req.body.link.trim() : "";
  const parsed = safeHttpsUrl(raw);
  if (!parsed || !["alldebrid.com", "www.alldebrid.com"].includes(parsed.hostname.toLowerCase()) || !parsed.pathname.startsWith("/f/")) {
    return res.status(400).json({ error: "Некорректная ссылка на файл AllDebrid." });
  }
  try {
    const response = await axios.post("https://api.alldebrid.com/v4/link/unlock",
      new URLSearchParams({ link: parsed.toString() }),
      { headers: { Authorization: `Bearer ${ALLDEBRID_API_KEY}`, "Content-Type": "application/x-www-form-urlencoded" },
        timeout: 20000, validateStatus: () => true });
    if (response.status < 200 || response.status >= 300 || response.data?.status === "error") {
      return res.status(502).json({ error: response.data?.error?.message || "AllDebrid не смог создать прямую ссылку на скачивание этого файла." });
    }
    const result = response.data?.data || {};
    const direct = typeof result.link === "string" ? safeHttpsUrl(result.link) : null;
    if (!direct || (direct.hostname.toLowerCase().endsWith("alldebrid.com") && direct.pathname.startsWith("/f/"))) {
      return res.status(502).json({ error: "AllDebrid вернул страницу файла вместо прямой ссылки для скачивания. API не предоставил ссылку для воспроизведения." });
    }
    res.json({ ok: true, url: direct.toString(), filename: result.filename || "Ссылка для скачивания", filesize: result.filesize || null, host: result.host || null });
  } catch (err) {
    res.status(502).json({ error: "Не удалось получить прямую ссылку AllDebrid. Попробуйте ещё раз." });
  }
});

// Stream a resolved AllDebrid file through this VPS. The input must be an AllDebrid /f/ page;
// the client cannot supply an arbitrary upstream URL (SSRF protection).
// External players (PotPlayer, VLC, mpv) have no website session cookie, so mirror links are
// signed: /api/file/proxy?link=...&exp=...&sig=... . A signature covers one specific link only.
const MIRROR_TTL = Math.max(1, Number(process.env.MIRROR_TTL_HOURS || 12)) * 3600;
function makeMirrorToken(link, expires) {
  return crypto.createHmac("sha256", SESSION_SECRET || ALLDEBRID_API_KEY).update(`mirror:${expires}:${link}`).digest("hex");
}
function isValidMirrorToken(link, expires, token) {
  const now = Math.floor(Date.now() / 1000);
  if (!/^\d+$/.test(String(expires || "")) || Number(expires) < now || Number(expires) > now + MIRROR_TTL + 60 ||
      typeof token !== "string" || !/^[0-9a-f]{64}$/i.test(token)) return false;
  try { return crypto.timingSafeEqual(Buffer.from(makeMirrorToken(link, expires), "hex"), Buffer.from(token, "hex")); }
  catch { return false; }
}
function isAllDebridFileLink(parsed) {
  return !!parsed && ["alldebrid.com", "www.alldebrid.com"].includes(parsed.hostname.toLowerCase()) && parsed.pathname.startsWith("/f/");
}
// A readable file name (with extension) in the URL path and Content-Disposition lets browsers and
// players pick the right container/title instead of calling every download "proxy".
function cleanFileName(name) {
  return String(name || "").replace(/[\\/\x00-\x1f\x7f"<>|?*:]/g, "_").replace(/^\.+/, "").trim().slice(0, 150);
}
function contentDisposition(name) {
  const clean = cleanFileName(name);
  if (!clean) return "";
  const ascii = clean.replace(/[^\x20-\x7e]/g, "_").replace(/%/g, "_");
  return `inline; filename="${ascii}"; filename*=UTF-8''${encodeURIComponent(clean).replace(/['()*]/g, c => "%" + c.charCodeAt(0).toString(16).toUpperCase())}`;
}
app.get("/api/file/mirror-url", requireLogin, apiLimiter, (req, res) => {
  const parsed = safeHttpsUrl(typeof req.query.link === "string" ? req.query.link.trim() : "");
  if (!isAllDebridFileLink(parsed)) return res.status(400).json({ error: "Некорректная ссылка на файл AllDebrid." });
  const link = parsed.toString();
  const name = cleanFileName(typeof req.query.name === "string" ? req.query.name.split(/[\\/]/).pop() : "");
  const expires = String(Math.floor(Date.now() / 1000) + MIRROR_TTL);
  res.setHeader("Cache-Control", "no-store");
  res.json({ url: `https://${req.get("host")}/api/file/proxy${name ? "/" + encodeURIComponent(name) : ""}?link=${encodeURIComponent(link)}&exp=${expires}&sig=${makeMirrorToken(link, expires)}` });
});
// Playlists for external players / the Android app: one .m3u with a signed mirror link per episode.
// Stateless: the URL carries the magnet id and file indices (position in the cached file list), signed
// with HMAC, and the .m3u is rebuilt on every request. Nothing is stored, so it survives restarts.
const PLAYLIST_MAX_ITEMS = 200;
function makePlaylistToken(id, idxStr, expires) {
  return crypto.createHmac("sha256", SESSION_SECRET || ALLDEBRID_API_KEY).update(`playlist:${expires}:${id}:${idxStr}`).digest("hex");
}
function isValidPlaylistToken(id, idxStr, expires, token) {
  const now = Math.floor(Date.now() / 1000);
  if (!/^\d+$/.test(String(expires || "")) || Number(expires) < now || Number(expires) > now + MIRROR_TTL + 60 ||
      typeof token !== "string" || !/^[0-9a-f]{64}$/i.test(token)) return false;
  try { return crypto.timingSafeEqual(Buffer.from(makePlaylistToken(id, idxStr, expires), "hex"), Buffer.from(token, "hex")); }
  catch { return false; }
}
// Body: { magnetId, title, items: [{ index, link }] }. `link` is checked against the server's own list,
// so a stale browser snapshot (library changed since) is rejected instead of playing the wrong files.
app.post("/api/playlist", requireLogin, apiLimiter, async (req, res) => {
  const magnetId = Number(req.body?.magnetId);
  const items = Array.isArray(req.body?.items) ? req.body.items : [];
  if (!Number.isSafeInteger(magnetId) || magnetId <= 0) return res.status(400).json({ error: "Некорректный идентификатор magnet-ссылки." });
  if (!items.length || items.length > PLAYLIST_MAX_ITEMS) {
    return res.status(400).json({ error: `Плейлист должен содержать от 1 до ${PLAYLIST_MAX_ITEMS} серий.` });
  }
  try {
    const list = await getMagnetFileList(magnetId);
    if (!list) return res.status(404).json({ error: "Для этой magnet-ссылки не найдено файлов." });
    for (const item of items) {
      const f = Number.isInteger(item?.index) ? list[item.index] : null;
      if (!f || f.type !== "file" || !f.sourceLink || f.sourceLink !== item.link) {
        return res.status(409).json({ error: "Медиатека изменилась. Нажмите «Обновить» и повторите." });
      }
    }
    const idxStr = items.map(i => i.index).join(".");
    const expires = String(Math.floor(Date.now() / 1000) + MIRROR_TTL);
    const title = cleanFileName(typeof req.body?.title === "string" ? req.body.title : "") || "playlist";
    res.setHeader("Cache-Control", "no-store");
    res.json({
      url: `https://${req.get("host")}/api/playlist/${magnetId}/${encodeURIComponent(title)}.m3u?f=${idxStr}&exp=${expires}&sig=${makePlaylistToken(magnetId, idxStr, expires)}`,
      count: items.length
    });
  } catch {
    res.status(502).json({ error: "Не удалось подготовить плейлист. Попробуйте ещё раз." });
  }
});
app.get("/api/playlist/:id/:name", (req, res, next) => mirrorLimiter(req, res, next), async (req, res) => {
  const id = Number(req.params.id);
  const idxStr = typeof req.query.f === "string" ? req.query.f : "";
  if (!Number.isSafeInteger(id) || !/^\d+(\.\d+){0,199}$/.test(idxStr) ||
      !isValidPlaylistToken(id, idxStr, String(req.query.exp || ""), String(req.query.sig || ""))) {
    return res.status(401).send("Плейлист устарел или недействителен. Создайте новый на сайте.");
  }
  try {
    const list = await getMagnetFileList(id);
    if (!list) return res.status(404).send("Файлы не найдены.");
    const host = req.get("host");
    const expires = String(Math.floor(Date.now() / 1000) + MIRROR_TTL);
    const lines = ["#EXTM3U"];
    for (const raw of idxStr.split(".")) {
      const f = list[Number(raw)];
      const parsed = f && f.type === "file" ? safeHttpsUrl(f.sourceLink || "") : null;
      if (!isAllDebridFileLink(parsed)) continue;
      const link = parsed.toString();
      const name = cleanFileName(f.path.split("/").pop()) || f.name;
      lines.push(`#EXTINF:-1,${name.replace(/\.[a-z0-9]{2,5}$/i, "")}`,
        `https://${host}/api/file/proxy/${encodeURIComponent(name)}?link=${encodeURIComponent(link)}&exp=${expires}&sig=${makeMirrorToken(link, expires)}`);
    }
    res.setHeader("Content-Type", "audio/x-mpegurl; charset=utf-8");
    res.setHeader("Content-Disposition", contentDisposition(req.params.name));
    res.setHeader("Cache-Control", "private, no-store");
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.send("\uFEFF" + lines.join("\r\n") + "\r\n");
  } catch {
    res.status(502).send("Не удалось построить плейлист.");
  }
});
// Players send many Range requests while seeking; keep them out of the UI rate limit.
const mirrorLimiter = rateLimit({
  windowMs: 60 * 1000, limit: 3000, standardHeaders: "draft-7", legacyHeaders: false,
  message: "Слишком много запросов.", handler: (req, res, next, options) => res.status(options.statusCode).send(options.message)
});
// Set MIRROR_DEBUG=1 in .env to log every mirror request (what the player sent and what we answered).
const MIRROR_DEBUG = process.env.MIRROR_DEBUG === "1";
function mirrorLog(req, msg) {
  if (MIRROR_DEBUG) console.log(`[зеркало] ${req.method} ${req.originalUrl.split("?")[0]} range=${req.headers.range || "-"} ua="${req.headers["user-agent"] || "-"}" -> ${msg}`);
}
function mirrorAuth(req, res, next) {
  if (req.session && req.session.authenticated) { mirrorLog(req, "auth: session"); return next(); }
  const parsed = safeHttpsUrl(typeof req.query.link === "string" ? req.query.link.trim() : "");
  if (parsed && isValidMirrorToken(parsed.toString(), String(req.query.exp || ""), String(req.query.sig || ""))) { mirrorLog(req, "auth: signature ok"); return next(); }
  mirrorLog(req, `401 (link=${parsed ? "ok" : "BAD"} exp=${req.query.exp ? "present" : "MISSING"} sig=${req.query.sig ? "present" : "MISSING"})`);
  return res.status(401).send("Ссылка через зеркало устарела или недействительна. Создайте новую на сайте.");
}
// Unlocking calls the AllDebrid API; cache it so each Range request doesn't repeat the call.
// A player sends several parallel Range requests (seek, reconnect): concurrent unlocks of one link share a
// single API call, and a cached direct URL that stops working is dropped and re-resolved once.
const unlockCache = new Map(); // link -> { url, name, until }
const unlockInflight = new Map(); // link -> Promise<entry>
class UnlockError extends Error {}
async function resolveDirect(key, fresh) {
  if (!fresh) {
    const hit = unlockCache.get(key);
    if (hit && hit.until > Date.now()) return hit;
  } else unlockCache.delete(key);
  let pending = unlockInflight.get(key);
  if (!pending) {
    pending = (async () => {
      const unlocked = await axios.post("https://api.alldebrid.com/v4/link/unlock",
        new URLSearchParams({ link: key }),
        { headers: { Authorization: `Bearer ${ALLDEBRID_API_KEY}`, "Content-Type": "application/x-www-form-urlencoded" },
          timeout: 20000, validateStatus: () => true });
      if (unlocked.status < 200 || unlocked.status >= 300 || unlocked.data?.status === "error") {
        throw new UnlockError(unlocked.data?.error?.message || "AllDebrid не смог получить ссылку на этот файл.");
      }
      const found = safeHttpsUrl(unlocked.data?.data?.link || "");
      if (!found || (found.hostname.toLowerCase().endsWith("alldebrid.com") && found.pathname.startsWith("/f/"))) {
        throw new UnlockError("AllDebrid не вернул прямой URL файла.");
      }
      const apiName = typeof unlocked.data?.data?.filename === "string" ? unlocked.data.data.filename : "";
      let urlName = ""; try { urlName = decodeURIComponent(found.pathname.split("/").pop() || ""); } catch {}
      const entry = { url: found.toString(), name: apiName || urlName, until: Date.now() + 10 * 60 * 1000 };
      unlockCache.set(key, entry);
      if (unlockCache.size > 500) for (const [k, v] of unlockCache) if (v.until < Date.now()) unlockCache.delete(k);
      return entry;
    })().finally(() => unlockInflight.delete(key));
    unlockInflight.set(key, pending);
  }
  return pending;
}
// Keep-alive towards the file host: a seek or reconnect does not pay a new TCP+TLS handshake every time.
const upstreamAgent = new https.Agent({ keepAlive: true, maxSockets: 64, maxFreeSockets: 8, keepAliveMsecs: 15000 });
// If the file host stops sending (but keeps the socket open) the player would hang forever: cut it so it can reconnect.
const STREAM_IDLE_MS = Number(process.env.STREAM_IDLE_MS || 25000);

async function openUpstream(key, rangeHeaders) {
  let lastErr;
  for (let attempt = 0; attempt < 2; attempt++) {
    const entry = await resolveDirect(key, attempt > 0);
    try {
      const upstream = await axios.get(entry.url, {
        responseType: "stream", headers: rangeHeaders, timeout: 30000, maxRedirects: 5, decompress: false,
        httpsAgent: upstreamAgent,
        validateStatus: status => (status >= 200 && status < 300) || status === 206 || status === 416
      });
      return { entry, upstream };
    } catch (err) {
      lastErr = err;
      unlockCache.delete(key); // expired / rotated direct URL or a flaky host: resolve again once
      if (err && err.response && err.response.data && typeof err.response.data.destroy === "function") err.response.data.destroy();
    }
  }
  throw lastErr;
}

app.get(["/api/file/proxy", "/api/file/proxy/:name"], mirrorAuth, mirrorLimiter, async (req, res) => {
  const parsed = safeHttpsUrl(typeof req.query.link === "string" ? req.query.link.trim() : "");
  if (!isAllDebridFileLink(parsed)) return res.status(400).send("Некорректная ссылка на файл AllDebrid.");
  const key = parsed.toString();
  let upstream;
  let idleTimer;
  const stopIdle = () => clearTimeout(idleTimer);
  try {
    const headers = {};
    if (typeof req.headers.range === "string") headers.Range = req.headers.range;
    if (typeof req.headers["if-range"] === "string") headers["If-Range"] = req.headers["if-range"];
    // The player may already be gone (it reconnects aggressively): do not start work for a closed socket.
    let clientGone = false;
    res.on("close", () => { clientGone = true; });
    let entry;
    try { ({ entry, upstream } = await openUpstream(key, headers)); }
    catch (err) {
      if (err instanceof UnlockError) { mirrorLog(req, `502 ${err.message}`); return res.status(502).send(err.message); }
      throw err;
    }
    if (clientGone) { upstream.data.destroy(); return; }
    mirrorLog(req, `upstream ${upstream.status} type=${upstream.headers["content-type"] || "-"} len=${upstream.headers["content-length"] || "-"}`);
    res.status(upstream.status);
    for (const name of ["content-type", "content-length", "content-range", "accept-ranges", "last-modified", "etag"]) {
      if (upstream.headers[name]) res.setHeader(name, upstream.headers[name]);
    }
    // Some file hosts omit a useful MIME type; provide a conservative type for disc images/streams.
    if (!upstream.headers["content-type"] || /^application\/octet-stream$/i.test(upstream.headers["content-type"])) {
      const requestedName = String(req.params.name || entry.name || "").toLowerCase();
      if (requestedName.endsWith(".iso")) res.setHeader("Content-Type", "application/x-iso9660-image");
      else if (requestedName.endsWith(".m2ts") || requestedName.endsWith(".ts")) res.setHeader("Content-Type", "video/mp2t");
    }
    // Prefer the real file name: URL path name (set by the site) > AllDebrid filename > upstream header.
    const disp = contentDisposition(req.params.name || entry.name);
    if (disp) res.setHeader("Content-Disposition", disp);
    else if (upstream.headers["content-disposition"]) res.setHeader("Content-Disposition", upstream.headers["content-disposition"]);
    if (!upstream.headers["accept-ranges"]) res.setHeader("Accept-Ranges", "bytes");
    res.setHeader("Cache-Control", "private, no-store");
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("X-Accel-Buffering", "no"); // nginx: stream, do not buffer video to disk
    res.flushHeaders();
    const data = upstream.data;
    // Idle watchdog. Not armed while we are waiting for the *client* (backpressure): a player with a full
    // buffer legitimately stops reading for a long time.
    const armIdle = () => {
      stopIdle();
      idleTimer = setTimeout(() => {
        if (data.destroyed) return;
        if (data.isPaused() || res.writableNeedDrain) return armIdle();
        mirrorLog(req, "upstream idle, closing so the player reconnects");
        data.destroy(new Error("upstream idle"));
      }, STREAM_IDLE_MS);
      idleTimer.unref?.();
    };
    data.on("data", armIdle);
    data.on("end", stopIdle);
    data.on("close", stopIdle);
    armIdle();
    data.on("error", () => { stopIdle(); if (!res.headersSent) res.status(502); res.destroy(); });
    res.on("close", () => { stopIdle(); if (!res.writableEnded && !data.destroyed) data.destroy(); });
    data.pipe(res);
  } catch (err) {
    stopIdle();
    mirrorLog(req, `502 ${err.message}`);
    if (upstream?.data && !upstream.data.destroyed) upstream.data.destroy();
    if (!res.headersSent) res.status(502).send("Не удалось передать этот файл через сервер. Попробуйте прямую ссылку.");
    else res.destroy();
  }
});

// WebDAV is proxied through this VPS: the AllDebrid credential is never sent to clients.
function normalizeWebdavPath(rawPath) {
  // Express may expose an already percent-encoded pathname. Decode each segment
  // once before validating and encoding it for the upstream URL, preventing %20 -> %2520.
  const normalized = String(rawPath || "").replace(/\\/g, "/");
  const parts = normalized.split("/").filter(Boolean).map(part => {
    let decoded;
    try { decoded = decodeURIComponent(part); }
    catch { throw new Error("Некорректный путь WebDAV."); }
    if (decoded === "." || decoded === ".." || decoded.includes("/") || decoded.includes("\\") || /[\x00-\x1f\x7f]/.test(decoded)) {
      throw new Error("Некорректный путь WebDAV.");
    }
    return decoded;
  });
  return parts.map(part => encodeURIComponent(part)).join("/");
}

// Short-lived signed URLs allow external players such as PotPlayer to use the VPS proxy
// without needing the website session cookie or the user's own AllDebrid credentials.
// The token is carried in the URL PATH (/webdav/<exp>/<sig>/...), not in the query string:
// WebDAV clients build child URLs from <href> values and routinely drop or mangle "?query".
// Links for mounting WebDAV as a Windows network drive must outlive a single playback session.
const WEBDAV_MOUNT_TTL = Math.max(1, Number(process.env.WEBDAV_MOUNT_TTL_HOURS || 12)) * 3600;
function makeWebdavToken(pathPart, expires) {
  return crypto.createHmac("sha256", SESSION_SECRET || ALLDEBRID_API_KEY)
    .update(`${expires}:${pathPart}`).digest("hex");
}
function isValidWebdavToken(pathPart, expires, token) {
  if (!/^\d+$/.test(String(expires || "")) || Number(expires) < Math.floor(Date.now()/1000) ||
      Number(expires) > Math.floor(Date.now()/1000) + WEBDAV_MOUNT_TTL + 60 || typeof token !== "string" ||
      !/^[0-9a-f]{64}$/i.test(token)) return false;
  const expected = makeWebdavToken(pathPart, expires);
  try { return crypto.timingSafeEqual(Buffer.from(expected, "hex"), Buffer.from(token, "hex")); }
  catch { return false; }
}
const WEBDAV_UPSTREAM = "https://webdav.debrid.it/";
const WEBDAV_METHODS = new Set(["GET", "HEAD", "OPTIONS", "PROPFIND"]); // read-only proxy
function webdavUrl(req, expires, sig, encodedPath) {
  return `${req.protocol}://${req.get("host")}${webdavPathUrl(expires, sig, encodedPath)}`;
}
function webdavPathUrl(expires, sig, encodedPath) {
  return `/webdav/${expires}/${sig}/${encodedPath}`;
}
app.get("/api/webdav", requireLogin, apiLimiter, (req, res) => {
  if (!ALLDEBRID_API_KEY) return res.status(503).json({ error: "ALLDEBRID_API_KEY is not configured in .env." });
  const rawQueryPath = typeof req.query.path === "string" ? req.query.path : "";
  let suffix;
  try { suffix = normalizeWebdavPath(rawQueryPath); }
  catch { return res.status(400).json({ error: "Некорректный путь WebDAV." }); }
  // Directories keep their trailing slash (WebDAV servers treat /dir and /dir/ differently).
  if (suffix && /[\/\\]$/.test(rawQueryPath)) suffix += "/";
  const ttl = req.query.mount === "1" ? WEBDAV_MOUNT_TTL : 1800;
  const expires = String(Math.floor(Date.now()/1000) + ttl);
  const token = makeWebdavToken("", expires);
  res.setHeader("Cache-Control", "no-store");
  res.json({ url: webdavUrl(req, expires, token, suffix), root: webdavUrl(req, expires, token, "") });
});

// WebDAV traffic (PROPFIND per folder, many Range requests per video) is far heavier than UI
// polling, so it gets its own, higher limit. Access is already guarded by the signed token.
const webdavLimiter = rateLimit({
  windowMs: 60 * 1000, limit: 3000, standardHeaders: "draft-7", legacyHeaders: false,
  message: "Слишком много запросов WebDAV.",
  handler: (req, res, next, options) => res.status(options.statusCode).send(options.message)
});

app.use("/webdav", webdavLimiter, async (req, res) => {
  if (!ALLDEBRID_API_KEY) return res.status(503).send("WebDAV is not configured.");
  const method = req.method.toUpperCase();
  if (!WEBDAV_METHODS.has(method)) {
    res.setHeader("Allow", "GET, HEAD, OPTIONS, PROPFIND");
    return res.status(405).send("Read-only WebDAV proxy.");
  }
  // app.use("/webdav", ...) strips the mount prefix, so read the original URL.
  let rest, expires, sig, suffix, isDir;
  try {
    const originalPath = new URL(req.originalUrl, "http://localhost").pathname;
    rest = originalPath.replace(/^\/webdav(?:\/|$)/i, "");
    const m = rest.match(/^(\d+)\/([0-9a-f]{64})(?:\/(.*))?$/i);
    if (m) { expires = m[1]; sig = m[2]; rest = m[3] || ""; }
    else { expires = String(req.query.exp || ""); sig = String(req.query.sig || ""); } // legacy ?exp&sig links
    suffix = normalizeWebdavPath(rest);
    isDir = rest === "" || /\/$/.test(rest);
  } catch { return res.status(400).send("Некорректный путь WebDAV."); }
  // The signature authorizes this temporary WebDAV session for the whole tree.
  if (!isValidWebdavToken("", expires, sig)) return res.status(401).send("WebDAV link expired or invalid. Generate a new link from the website.");
  const upstreamUrl = WEBDAV_UPSTREAM + suffix + (isDir && suffix ? "/" : "");
  let upstream;
  try {
    const headers = { "Accept-Encoding": "identity" }; // avoids gzip/Content-Length mismatches
    for (const name of ["depth", "range", "if", "if-range", "if-none-match", "if-modified-since", "content-type", "content-length"]) {
      if (req.headers[name]) headers[name.replace(/(^|-)([a-z])/g, (_, a, b) => a + b.toUpperCase())] = req.headers[name];
    }
    const hasBody = req.headers["content-length"] > 0 || req.headers["transfer-encoding"];
    upstream = await axios.request({
      url: upstreamUrl, method, headers,
      // AllDebrid WebDAV: username = API key, password = any non-empty string.
      auth: { username: ALLDEBRID_API_KEY, password: "eeeeee" },
      data: hasBody ? req : undefined,
      responseType: "stream", decompress: false, timeout: 120000,
      // A body stream can't be replayed, so only follow redirects for body-less GET/HEAD.
      maxRedirects: (method === "GET" || method === "HEAD") ? 5 : 0,
      validateStatus: () => true
    });
    let status = upstream.status;
    // Never forward upstream 401/403: a WWW-Authenticate-less 401 makes clients prompt for credentials.
    if (status === 401 || status === 403) { upstream.data.destroy(); return res.status(502).send(`WebDAV upstream rejected the credentials (HTTP ${status}). Check ALLDEBRID_API_KEY.`); }
    const contentType = String(upstream.headers?.["content-type"] || "");
    const rewriteListing = status >= 200 && status < 300 && status !== 204 && (method === "PROPFIND" ||
      (method === "GET" && /text\/html|application\/(?:xml|xhtml\+xml)|text\/xml/i.test(contentType)));

    // Turn any upstream URL/path into a path-only URL on this proxy that carries the token.
    // `baseDir` is the directory (with trailing slash) used to resolve relative references.
    const baseDir = isDir ? `/${suffix ? suffix + "/" : ""}` : `/${suffix.split("/").slice(0, -1).join("/")}${suffix.includes("/") ? "/" : ""}`;
    const toLocalPath = (rawHref) => {
      let value = String(rawHref || "").trim();
      if (!value) return rawHref;
      try {
        if (/^https?:\/\//i.test(value)) {
          const u = new URL(value);
          if (u.hostname.toLowerCase() !== "webdav.debrid.it") return rawHref;
          value = u.pathname;
        }
        value = value.split(/[?#]/)[0];
        if (!value.startsWith("/")) value = baseDir + value;
        const trailing = /\/$/.test(value);
        const encoded = normalizeWebdavPath(value);
        return webdavPathUrl(expires, sig, encoded + (trailing && encoded ? "/" : ""));
      } catch { return rawHref; }
    };

    if (rewriteListing) {
      const chunks = [];
      upstream.data.on("data", chunk => chunks.push(Buffer.from(chunk)));
      upstream.data.on("error", () => { if (!res.headersSent) res.status(502); res.end(); });
      upstream.data.on("end", () => {
        let body = Buffer.concat(chunks).toString("utf8");
        // DAV <href> element text (PROPFIND XML) ...
        body = body.replace(/(<(?:[A-Za-z0-9_-]+:)?href\b[^>]*>)([\s\S]*?)(<\/(?:[A-Za-z0-9_-]+:)?href\s*>)/gi,
          (all, before, value, after) => {
            const decoded = value.replace(/&amp;/g, "&").trim();
            return `${before}${toLocalPath(decoded).replace(/&/g, "&amp;")}${after}`;
          });
        // ... and href="" attributes (HTML listings).
        body = body.replace(/\bhref=(["'])([^"']*)\1/gi, (all, quote, value) => `href=${quote}${toLocalPath(value)}${quote}`);
        res.status(status);
        res.setHeader("Content-Type", contentType || "application/xml; charset=utf-8");
        res.setHeader("Cache-Control", "private, no-store");
        // Advertise the read-only DAV levels supported by this proxy even when the upstream omits DAV.
        res.setHeader("DAV", upstream.headers?.dav || "1, 2");
        res.setHeader("Allow", "GET, HEAD, OPTIONS, PROPFIND");
        res.end(body);
      });
      return;
    }
    res.status(status);
    // Keep DAV discovery usable for clients that probe OPTIONS/PROPFIND before listing folders.
    if (method === "OPTIONS" || method === "PROPFIND") {
      res.setHeader("DAV", upstream.headers?.dav || "1, 2");
      res.setHeader("Allow", "GET, HEAD, OPTIONS, PROPFIND");
    }
    for (const [name, value] of Object.entries(upstream.headers || {})) {
      const lower = name.toLowerCase();
      if (["content-type","content-length","content-range","accept-ranges","last-modified","etag","dav","allow","content-disposition"].includes(lower)) res.setHeader(name, value);
      else if (lower === "location") res.setHeader("Location", toLocalPath(value));
    }
    res.setHeader("Cache-Control", "private, no-store");
    upstream.data.on("error", () => { if (!res.headersSent) res.status(502); res.destroy(); });
    res.on("close", () => { if (!res.writableEnded && upstream?.data && !upstream.data.destroyed) upstream.data.destroy(); });
    upstream.data.pipe(res);
  } catch (err) {
    if (upstream?.data && !upstream.data.destroyed) upstream.data.destroy();
    if (!res.headersSent) res.status(502).send("Ошибка запроса к серверу WebDAV.");
    else res.destroy();
  }
});
app.get("/api/magnets/:id/files", requireLogin, apiLimiter, async (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isSafeInteger(id) || id <= 0) return res.status(400).json({ error: "Некорректный идентификатор magnet-ссылки." });
  try {
    const response = await axios.post("https://api.alldebrid.com/v4/magnet/files",
      new URLSearchParams({ "id[]": String(id) }),
      { headers: { Authorization: `Bearer ${ALLDEBRID_API_KEY}`, "Content-Type": "application/x-www-form-urlencoded" },
        timeout: 20000, validateStatus: () => true });
    if (response.status < 200 || response.status >= 300 || response.data?.status === "error") {
      return res.status(502).json({ error: response.data?.error?.message || "Не удалось получить список файлов magnet-ссылки." });
    }
    const rawMagnets = response.data?.data?.magnets;
    const magnetList = Array.isArray(rawMagnets)
      ? rawMagnets
      : (rawMagnets && typeof rawMagnets === "object" ? [rawMagnets] : []);
    const root = magnetList.find(m => Number(m.id) === id) || magnetList[0];
    if (!root) return res.status(404).json({ error: "Для этой magnet-ссылки не найдено файлов." });

    // Flatten documented nested file structures; keep only named files with HTTP(S) links.
    const files = [];
    const walk = (nodes, prefix = "") => {
      if (!Array.isArray(nodes)) return;
      for (const node of nodes) {
        if (typeof node === "string") continue;
        if (!node || typeof node !== "object") continue;
        const name = typeof node.n === "string" ? node.n : (typeof node.name === "string" ? node.name : "");
        const link = typeof node.l === "string" ? node.l : (typeof node.link === "string" ? node.link : "");
        const fullName = [prefix, name].filter(Boolean).join("/");
        if (name && link && safeHttpsUrl(link)) {
          files.push({ name: fullName, sourceUrl: link, size: Number(node.s || node.size || 0) || null });
        }
        const children = node.e || node.files || node.children;
        if (Array.isArray(children)) walk(children, fullName);
      }
    };
    walk(root.files || root.links || []);

    // Links are NOT unlocked here: that was one AllDebrid call per file. The page unlocks a file
    // (POST /api/file/unlock) only when the user asks for its direct link; mirror links need no unlock.
    const resolvedFiles = files.map(file => ({ name: file.name, url: null, sourceUrl: file.sourceUrl, size: file.size }));
    res.json({ id, filename: root.filename || root.name || "Magnet files", status: root.status || "Ready", files: resolvedFiles });
  } catch (err) {
    res.status(502).json({ error: err.message || "Не удалось получить список файлов magnet-ссылки." });
  }
});


// Optional TMDB poster lookup. The API key stays on the server.

// Persistent media metadata cache. Cache keys are hashes of provider file links;
// direct URLs themselves are never written to disk.
const MEDIA_CACHE_FILE = path.join(DATA_DIR, "media-metadata-cache.json");
const MEDIA_CACHE_TTL = 30 * 24 * 60 * 60 * 1000;
const mediaMetadataCache = new Map();
try {
  const saved = JSON.parse(fs.readFileSync(MEDIA_CACHE_FILE, "utf8"));
  for (const [key, value] of Object.entries(saved)) if (value && value.expiresAt > Date.now()) mediaMetadataCache.set(key, value);
} catch {}
let cacheWriteQueued = false;
function persistMediaCache() {
  if (cacheWriteQueued) return;
  cacheWriteQueued = true;
  setTimeout(() => {
    cacheWriteQueued = false;
    try {
      const obj = Object.fromEntries(mediaMetadataCache);
      const tmp = MEDIA_CACHE_FILE + ".tmp";
      fs.writeFileSync(tmp, JSON.stringify(obj), { mode: 0o600 });
      fs.renameSync(tmp, MEDIA_CACHE_FILE);
    } catch (err) { console.error("Не удалось сохранить кэш сведений о медиафайлах:", err.message); }
  }, 250);
}
function runFfprobe(url) {
  return new Promise((resolve, reject) => {
    const args = ["-v", "error", "-rw_timeout", "12000000", "-user_agent", "Mozilla/5.0", "-analyzeduration", "8000000", "-probesize", "8000000", "-select_streams", "v:0", "-show_entries", "format=bit_rate,duration:stream=codec_name,width,height,bit_rate,avg_frame_rate", "-of", "json", url];
    const child = spawn(process.env.FFPROBE_PATH || "ffprobe", args, { stdio: ["ignore", "pipe", "pipe"], windowsHide: true });
    let stdout = "", stderr = "";
    const timeout = setTimeout(() => child.kill("SIGKILL"), 25000);
    child.stdout.on("data", chunk => { if (stdout.length < 1024 * 1024) stdout += chunk.toString(); });
    child.stderr.on("data", chunk => { if (stderr.length < 8000) stderr += chunk.toString(); });
    child.on("error", err => { clearTimeout(timeout); reject(err); });
    child.on("close", code => {
      clearTimeout(timeout);
      if (code !== 0) return reject(new Error(stderr || "ffprobe не смог проанализировать этот медиафайл."));
      try {
        const data = JSON.parse(stdout);
        const stream = (data.streams || []).find(s => s.width && s.height);
        if (!stream) return reject(new Error("Видеопоток не найден."));
        const rate = Number(stream.bit_rate || data.format?.bit_rate || 0);
        resolve({ width: Number(stream.width), height: Number(stream.height), codec: stream.codec_name || null,
          bitrate: rate || null, bitrateMbps: rate ? Math.round(rate / 100000) / 10 : null,
          frameRate: stream.avg_frame_rate || null });
      } catch { reject(new Error("Не удалось разобрать ответ ffprobe.")); }
    });
  });
}
app.post("/api/media-metadata", requireLogin, apiLimiter, async (req, res) => {
  let raw = typeof req.body.link === "string" ? req.body.link.trim() : "";
  let parsed = safeHttpsUrl(raw);
  // Library snapshots may not contain file links; resolve the requested file from AllDebrid by magnet ID + path.
  if (!isAllDebridFileLink(parsed)) {
    const magnetId = Number(req.body.magnetId);
    const wantedPath = typeof req.body.path === "string" ? req.body.path : "";
    if (!Number.isSafeInteger(magnetId) || magnetId <= 0 || !wantedPath) {
      return res.status(400).json({ error: "Отсутствует ссылка на файл или идентификатор/путь magnet-ссылки." });
    }
    try {
      const filesResponse = await axios.post("https://api.alldebrid.com/v4/magnet/files",
        new URLSearchParams({ "id[]": String(magnetId) }),
        { headers: { Authorization: `Bearer ${ALLDEBRID_API_KEY}`, "Content-Type": "application/x-www-form-urlencoded" },
          timeout: 20000, validateStatus: () => true });
      if (filesResponse.status < 200 || filesResponse.status >= 300 || filesResponse.data?.status === "error")
        return res.status(502).json({ error: "AllDebrid пока не смог получить этот файл." });
      const rawRoots = filesResponse.data?.data?.magnets;
      const roots = Array.isArray(rawRoots) ? rawRoots : (rawRoots && typeof rawRoots === "object" ? [rawRoots] : []);
      let foundLink = "";
      const walk = (nodes, prefix = "") => {
        if (!Array.isArray(nodes) || foundLink) return;
        for (const node of nodes) {
          if (!node || typeof node !== "object") continue;
          const name = typeof node.n === "string" ? node.n : (typeof node.name === "string" ? node.name : "");
          if (!name) continue;
          const fullPath = prefix ? prefix + "/" + name : name;
          const children = node.e || node.files || node.children;
          const candidate = typeof node.l === "string" ? node.l : (typeof node.link === "string" ? node.link : "");
          if (fullPath === wantedPath && candidate && isAllDebridFileLink(safeHttpsUrl(candidate))) { foundLink = candidate; return; }
          if (Array.isArray(children)) walk(children, fullPath);
          if (foundLink) return;
        }
      };
      for (const root of roots) { walk(root.files || root.links || root.e || []); if (foundLink) break; }
      parsed = safeHttpsUrl(foundLink);
      if (!isAllDebridFileLink(parsed)) return res.status(404).json({ error: "Ссылка на файл пока недоступна. Обновите медиатеку после завершения обработки в AllDebrid." });
      raw = parsed.toString();
    } catch {
      return res.status(502).json({ error: "Не удалось найти файл в AllDebrid." });
    }
  }
  const key = crypto.createHash("sha256").update(parsed.toString()).digest("hex");
  const cached = mediaMetadataCache.get(key);
  if (cached && cached.expiresAt > Date.now()) return res.json({ ok: true, metadata: cached.metadata, cached: true });
  try {
    // Resolve through the official provider API; only inspect the resulting direct URL.
    const unlocked = await axios.post("https://api.alldebrid.com/v4/link/unlock",
      new URLSearchParams({ link: parsed.toString() }),
      { headers: { Authorization: `Bearer ${ALLDEBRID_API_KEY}`, "Content-Type": "application/x-www-form-urlencoded" },
        timeout: 20000, validateStatus: () => true });
    if (unlocked.status < 200 || unlocked.status >= 300 || unlocked.data?.status === "error")
      return res.status(502).json({ error: "AllDebrid не смог получить ссылку на это видео." });
    const direct = safeHttpsUrl(unlocked.data?.data?.link || "");
    if (!direct || !["http:", "https:"].includes(direct.protocol))
      return res.status(502).json({ error: "AllDebrid вернул некорректный URL видео." });
    const metadata = await runFfprobe(direct.toString());
    mediaMetadataCache.set(key, { metadata, expiresAt: Date.now() + MEDIA_CACHE_TTL });
    persistMediaCache();
    res.json({ ok: true, metadata, cached: false });
  } catch (err) {
    const message = /ENOENT/.test(err.message) ? "На сервере не установлен ffprobe." :
      (/SIGKILL|timed out/i.test(err.message) ? "Media analysis timed out." : "Не удалось прочитать сведения о видео.");
    res.status(502).json({ error: message });
  }
});

// Ответы TMDB на поиск постера кэшируются в памяти на сутки (до 3000 названий): одно и то же название не ищется повторно.
const posterLookupCache = new Map(); // query -> { at, body }
app.get("/api/poster", requireLogin, apiLimiter, async (req, res) => {
  const query = typeof req.query.q === "string" ? req.query.q.trim().slice(0, 160) : "";
  if (!query) return res.status(400).json({ error: "Не указано название." });
  if (!TMDB_API_KEY) return res.status(503).json({ error: "TMDB_API_KEY is not configured." });
  const ck = query.toLowerCase();
  const hit = posterLookupCache.get(ck);
  if (hit && Date.now() - hit.at < 24 * 3600 * 1000) { res.set("Cache-Control", "private, max-age=604800"); return res.json(hit.body); }
  const origJson = res.json.bind(res);
  res.json = body => {
    if (res.statusCode === 200 && body && "poster" in body) {
      posterLookupCache.set(ck, { at: Date.now(), body });
      if (posterLookupCache.size > 3000) posterLookupCache.delete(posterLookupCache.keys().next().value);
    }
    return origJson(body);
  };
  try {
    const response = await axios.get("https://api.themoviedb.org/3/search/multi", {
      params: { api_key: TMDB_API_KEY, query, include_adult: false }, timeout: 10000
    });
    const results = Array.isArray(response.data?.results) ? response.data.results : [];
    const match = results.find(item => item.poster_path && (item.media_type === "movie" || item.media_type === "tv"));
    res.set("Cache-Control", "private, max-age=604800");
    // Картинку браузер берёт у нашего сайта (/api/poster/img/...), а не напрямую у TMDB: так работает и там, где image.tmdb.org недоступен.
    const file = match ? String(match.poster_path).replace(/^\//, "") : "";
    res.json({ poster: /^[A-Za-z0-9_-]+\.(?:jpg|png|webp)$/.test(file) ? `/api/poster/img/w500/${file}` : null, title: match?.title || match?.name || null });
  } catch { res.status(502).json({ error: "Не удалось найти постер." }); }
});

// Прокси картинок TMDB с дисковым кэшем (DATA_DIR/poster-cache): повторные открытия не ходят во внешнюю сеть.
const POSTER_DIR = path.join(DATA_DIR, "poster-cache");
try { fs.mkdirSync(POSTER_DIR, { recursive: true }); } catch {}
const POSTER_SIZES = new Set(["w185", "w342", "w500"]);
const posterInflight = new Map();
app.get("/api/poster/img/:size/:file", requireLogin, apiLimiter, async (req, res) => {
  const { size, file } = req.params;
  if (!POSTER_SIZES.has(size) || !/^[A-Za-z0-9_-]+\.(?:jpg|png|webp)$/.test(file)) return res.status(400).end();
  const cached = path.join(POSTER_DIR, `${size}_${file}`);
  const type = file.endsWith(".png") ? "image/png" : file.endsWith(".webp") ? "image/webp" : "image/jpeg";
  const send = () => {
    res.set({ "Content-Type": type, "Cache-Control": "private, max-age=2592000, immutable", "X-Content-Type-Options": "nosniff" });
    res.sendFile(cached, err => { if (err && !res.headersSent) res.status(404).end(); });
  };
  if (fs.existsSync(cached)) return send();
  try {
    const key = `${size}/${file}`;
    if (!posterInflight.has(key)) {
      posterInflight.set(key, (async () => {
        const r = await axios.get(`https://image.tmdb.org/t/p/${size}/${file}`, { responseType: "arraybuffer", timeout: 15000, maxContentLength: 5 * 1024 * 1024, validateStatus: () => true });
        if (r.status !== 200 || !String(r.headers["content-type"] || "").startsWith("image/")) throw new Error("HTTP " + r.status);
        const tmp = cached + "." + process.pid + ".tmp";
        fs.writeFileSync(tmp, r.data); fs.renameSync(tmp, cached);
      })().finally(() => posterInflight.delete(key)));
    }
    await posterInflight.get(key);
    send();
  } catch { res.status(502).end(); }
});

app.use(express.static(path.join(__dirname, "public"), {
  index: "index.html",
  dotfiles: "deny",
  // Always revalidate the page so a server update is never hidden behind a cached old index.html.
  // Шрифты не меняются — кэш на год. CSS/JS с ?v=… в адресе (версия в index.html) — тоже на год, без повторных проверок.
  setHeaders: (res, filePath) => {
    if (/\.html?$/i.test(filePath)) res.setHeader("Cache-Control", "no-cache");
    else if (/\.woff2$/i.test(filePath) || (/\.(?:css|js)$/i.test(filePath) && res.req && res.req.query && res.req.query.v)) res.setHeader("Cache-Control", "public, max-age=31536000, immutable");
  }
}));

app.get("*path", (req, res, next) => {
  if (req.path.startsWith("/api/")) return res.status(404).json({ error: "Не найдено." });
  res.sendFile(path.join(__dirname, "public", "index.html"), err => {
    if (err) next(err);
  });
});

app.use((err, req, res, next) => {
  if (err && err.type === "entity.too.large") return res.status(413).json({ error: "Torrent file must be 1 MB or smaller." });
  console.error("Ошибка запроса:", err.message);
  res.status(err.status || 500).json({ error: "Непредвиденная ошибка сервера." });
});

const server = app.listen(PORT, "127.0.0.1", () => {
  console.log(`Медиапортал запущен: http://127.0.0.1:${PORT}`);
  console.log("Для публикации используйте HTTPS-обратный прокси-сервер.");
});
// Longer than the reverse proxy's upstream keep-alive (nginx default 60 s): avoids sporadic 502 when the proxy
// reuses a connection that Node has just closed — which showed up as failed seeks/reconnects.
server.keepAliveTimeout = 75_000;
server.headersTimeout = 80_000;
