"use strict";
// Русскоязычные трекеры: rutor (открытый), rutracker.org, kinozal.tv и NNM-Club (нужен аккаунт или cookie).
// Парсеры написаны по текущей вёрстке сайтов; если вёрстка изменится, включите SEARCH_DEBUG=1 — в лог попадёт
// фрагмент ответа, по нему парсер легко поправить. Самый устойчивый вариант — подключить эти же трекеры через Prowlarr.
const axios = require("axios");

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
const TIMEOUT = Number(process.env.SEARCH_TIMEOUT_MS || 10000);
const DEBUG = process.env.SEARCH_DEBUG === "1";

// ---- кодировка windows-1251 ------------------------------------------------------------------
const cp1251 = new TextDecoder("windows-1251");
function encodeCp1251(str) {
  let out = "";
  for (const ch of String(str)) {
    const c = ch.codePointAt(0);
    let b;
    if (c < 0x80) { out += /[A-Za-z0-9_.~-]/.test(ch) ? ch : "%" + c.toString(16).toUpperCase().padStart(2, "0"); continue; }
    if (c >= 0x410 && c <= 0x44f) b = c - 0x410 + 0xc0;
    else if (c === 0x401) b = 0xa8;
    else if (c === 0x451) b = 0xb8;
    else b = 0x3f; // '?'
    out += "%" + b.toString(16).toUpperCase().padStart(2, "0");
  }
  return out;
}
function formBody(pairs) { return pairs.map(([k, v]) => `${k}=${encodeCp1251(v)}`).join("&"); }

// ---- HTTP ------------------------------------------------------------------------------------
async function request(url, { method = "GET", headers = {}, data, charset = "utf-8", buffer = false } = {}) {
  const res = await axios.request({
    url, method, data, headers: { "User-Agent": UA, "Accept-Language": "ru,en;q=0.8", ...headers },
    timeout: TIMEOUT, maxRedirects: 0, responseType: "arraybuffer", maxContentLength: 10 * 1024 * 1024, validateStatus: () => true
  });
  const buf = Buffer.from(res.data);
  return {
    status: res.status, headers: res.headers, buf,
    text: buffer ? "" : (charset === "windows-1251" ? cp1251.decode(buf) : buf.toString("utf8")),
    cookies: [].concat(res.headers["set-cookie"] || []).map(c => c.split(";")[0]).filter(c => c && !/=\s*(deleted)?$/.test(c))
  };
}
// GET with up to 3 redirects (mirrors often redirect http -> https or to a canonical domain).
// The tracker's cookie (login session) never goes to another site a redirect points to — only within the same domain
// (www.x.org → x.org, http → https).
const siteOf = u => new URL(u).hostname.toLowerCase().split(".").slice(-2).join(".");
function withoutCookie(opts) {
  const headers = Object.fromEntries(Object.entries(opts.headers || {}).filter(([k]) => k.toLowerCase() !== "cookie"));
  return { ...opts, headers };
}
async function getFollow(url, opts = {}) {
  let cur = url;
  for (let i = 0; i < 4; i++) {
    const r = await request(cur, opts);
    if (r.status >= 300 && r.status < 400 && r.headers.location) {
      const next = new URL(String(r.headers.location), cur).toString();
      if (siteOf(next) !== siteOf(cur)) opts = withoutCookie(opts);
      cur = next;
      continue;
    }
    r.finalUrl = cur;
    return r;
  }
  throw new Error("слишком много редиректов");
}

// ---- Cloudflare: страница-проверка «Just a moment…» не проходится обычным запросом -------------------
// Если задан FLARESOLVERR_URL (https://github.com/FlareSolverr/FlareSolverr), страница открывается настоящим
// браузером; полученные cf_clearance + User-Agent кэшируются и дальше используются обычными запросами.
const flare = () => String(process.env.FLARESOLVERR_URL || "").trim().replace(/\/+$/, "");
const cfCache = new Map(); // host -> { cookie, ua, until }
function isChallenge(r) {
  if (![200, 403, 429, 503].includes(r.status)) return false;
  return /<title>\s*(Just a moment|Attention Required)|_cf_chl_opt|cf-browser-verification/i.test(r.buf.slice(0, 6000).toString("latin1"));
}
function parseCookies(str) {
  return String(str || "").split(";").map(p => p.trim()).filter(Boolean).map(p => { const i = p.indexOf("="); return i < 0 ? null : [p.slice(0, i).trim(), p.slice(i + 1).trim()]; }).filter(Boolean);
}
function mergeCookies(...parts) {
  const m = new Map();
  for (const p of parts) for (const [k, v] of parseCookies(p)) m.set(k, v);
  return [...m].map(([k, v]) => `${k}=${v}`).join("; ");
}
async function solveWithFlare(url, cookieStr) {
  const u = new URL(url);
  const cookies = parseCookies(cookieStr).map(([name, value]) => ({ name, value, domain: u.hostname, path: "/" }));
  const res = await axios.post(`${flare()}/v1`, { cmd: "request.get", url, maxTimeout: 45000, cookies },
    { timeout: 60000, validateStatus: () => true, headers: { "Content-Type": "application/json" } });
  if (res.data?.status !== "ok" || !res.data.solution) throw new Error("FlareSolverr: " + String(res.data?.message || "HTTP " + res.status).slice(0, 100));
  const sol = res.data.solution;
  return { status: sol.status, html: String(sol.response || ""), url: sol.url || url, ua: sol.userAgent || UA,
    cookie: (sol.cookies || []).map(c => `${c.name}=${c.value}`).join("; ") };
}
// Login form POST through the browser: the session is then created from the server's own IP.
async function flarePost(url, formStr) {
  const res = await axios.post(`${flare()}/v1`, { cmd: "request.post", url, postData: formStr, maxTimeout: 45000 },
    { timeout: 60000, validateStatus: () => true, headers: { "Content-Type": "application/json" } });
  if (res.data?.status !== "ok" || !res.data.solution) throw new Error("FlareSolverr: " + String(res.data?.message || "HTTP " + res.status).slice(0, 100));
  const sol = res.data.solution;
  return { html: String(sol.response || ""), ua: sol.userAgent || UA, cookie: (sol.cookies || []).map(c => `${c.name}=${c.value}`).join("; ") };
}
const rejectedCookie = new Set(); // sites that refused the cookie from .env (then login/password is tried instead)
const envCookie = (name, value, defName) => rejectedCookie.has(name) ? "" : normCookie(value, defName);

// Like getFollow, but passes the Cloudflare challenge (when FLARESOLVERR_URL is set) and reuses its clearance.
async function fetchPage(url, opts = {}) {
  const host = new URL(url).host;
  const baseCookie = (opts.headers && opts.headers.Cookie) || "";
  const cached = cfCache.get(host);
  const live = cached && cached.until > Date.now() ? cached : null;
  const withCf = o => ({ ...o, headers: { ...(o.headers || {}), Cookie: mergeCookies(baseCookie, live ? live.cookie : ""), ...(live ? { "User-Agent": live.ua } : {}) } });
  let r = await getFollow(url, withCf(opts));
  if (!isChallenge(r)) return r;
  cfCache.delete(host);
  if (!flare()) throw new Error("Cloudflare-защита сайта не пускает сервер: задайте FLARESOLVERR_URL (см. README) или подключите трекер через Prowlarr");
  const sol = await solveWithFlare(url, baseCookie);
  cfCache.set(host, { cookie: sol.cookie, ua: sol.ua, until: Date.now() + 20 * 60 * 1000 });
  if (!opts.buffer) { // text pages: the browser already decoded them
    return { status: sol.status, headers: {}, buf: Buffer.from(sol.html), text: sol.html, cookies: [], finalUrl: sol.url };
  }
  r = await getFollow(url, { ...opts, headers: { ...(opts.headers || {}), Cookie: mergeCookies(baseCookie, sol.cookie), "User-Agent": sol.ua } });
  if (isChallenge(r)) throw new Error("Cloudflare не пропустил скачивание .torrent даже после FlareSolverr");
  return r;
}

// ---- разбор HTML -----------------------------------------------------------------------------
const ENT = { nbsp: " ", amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", laquo: "«", raquo: "»", ndash: "–", mdash: "—", hellip: "…" };
function decodeHtml(s) {
  return String(s || "").replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e) => {
    if (e[0] === "#") { try { return String.fromCodePoint(e[1].toLowerCase() === "x" ? parseInt(e.slice(2), 16) : Number(e.slice(1))); } catch { return m; } }
    return ENT[e.toLowerCase()] ?? m;
  });
}
function text(html) { return decodeHtml(String(html || "").replace(/<[^>]*>/g, " ")).replace(/\s+/g, " ").trim(); }
const MONTHS = { янв: 0, фев: 1, мар: 2, апр: 3, май: 4, мая: 4, июн: 5, июл: 6, авг: 7, сен: 8, окт: 9, ноя: 10, дек: 11 };
function ruDate(s) { // "10 Окт 26" | "10.10.2026"
  const t = text(s).toLowerCase();
  let m = /(\d{1,2})\s+([а-я]{3})[а-я]*\.?\s+(\d{2,4})/.exec(t);
  if (m && MONTHS[m[2]] !== undefined) { const y = Number(m[3]) < 100 ? 2000 + Number(m[3]) : Number(m[3]); return Date.UTC(y, MONTHS[m[2]], Number(m[1])); }
  m = /(\d{2})[.-](\d{2})[.-](\d{4})/.exec(t);
  return m ? Date.UTC(Number(m[3]), Number(m[2]) - 1, Number(m[1])) : 0;
}
function debugEmpty(name, html, url) {
  if (!DEBUG) return;
  const title = (/<title>([\s\S]*?)<\/title>/i.exec(html) || [])[1] || "";
  console.warn(`[поиск:${name}] 0 строк. ${url} · <title>${text(title).slice(0, 80)}</title> · ${html.length} симв. · начало: ${text(html).slice(0, 300)}`);
}

// ---- сессии (логин по паролю или готовая cookie) -----------------------------------------------
const sessions = new Map(); // name -> { cookie, until }
async function sessionCookie(name, envCookie, login) {
  if (envCookie) return envCookie;
  const s = sessions.get(name);
  if (s && s.until > Date.now()) return s.cookie;
  const cookie = await login();
  sessions.set(name, { cookie, until: Date.now() + 6 * 3600 * 1000 });
  return cookie;
}

// "0-123-abc" (значение без имени, как его копируют из DevTools) -> "bb_session=0-123-abc"
function normCookie(v, defaultName) {
  const c = String(v || "").trim().replace(/^["']|["']$/g, "");
  return c && !c.includes("=") ? `${defaultName}=${c}` : c;
}

function make(h) {
  const { int, parseSize, normHash } = h;

  // ---------- rutor (без регистрации) ----------
  function rutorBases() {
    const list = String(process.env.RUTOR_URL || "https://rutor.info,https://rutor.is,https://6tor.net").split(",").map(s => s.trim().replace(/\/+$/, "")).filter(Boolean);
    return list;
  }
  function parseRutor(html) {
    const out = [];
    for (const m of html.matchAll(/<tr\b[^>]*class="(?:gai|tum)"[^>]*>([\s\S]*?)<\/tr>/gi)) {
      const row = m[1];
      const mg = /href="(magnet:\?[^"]+)"/i.exec(row);
      const t = /<a\b[^>]*href="\/torrent\/(\d+)[^"]*"[^>]*>([\s\S]*?)<\/a>/i.exec(row);
      if (!mg || !t) continue;
      const magnet = decodeHtml(mg[1]);
      const hash = normHash((/btih:([0-9a-f]{40})/i.exec(magnet) || [])[1]);
      if (!hash) continue;
      const cells = [...row.matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(c => c[1]);
      const sizeCell = cells.find(c => /^\s*[\d.,]+\s*(?:&nbsp;|\s)\s*[KMGT]B\s*$/i.test(c)) || "";
      const seed = /<span\b[^>]*class="green"[^>]*>([\s\S]*?)<\/span>/i.exec(row);
      const leech = /<span\b[^>]*class="red"[^>]*>([\s\S]*?)<\/span>/i.exec(row);
      out.push({
        title: text(t[2]), size: parseSize(text(sizeCell)), seeders: int(text(seed ? seed[1] : "")), leechers: int(text(leech ? leech[1] : "")),
        date: ruDate(cells[0] || ""), infoHash: hash, magnet
      });
    }
    return out;
  }
  const rutor = {
    label: "Rutor", ru: true,
    enabled: () => true,
    async search(q) {
      let lastErr;
      for (const base of rutorBases()) {
        try {
          const r = await fetchPage(`${base}/search/0/0/000/0/${encodeURIComponent(q)}`);
          if (r.status !== 200) throw new Error(`HTTP ${r.status}`);
          const rows = parseRutor(r.text);
          if (!rows.length) debugEmpty("rutor", r.text, r.finalUrl);
          return rows;
        } catch (err) { lastErr = err; } // следующее зеркало
      }
      throw lastErr || new Error("нет доступных зеркал");
    }
  };

  // ---------- rutracker.org (аккаунт или RUTRACKER_COOKIE) ----------
  const rtBase = () => String(process.env.RUTRACKER_URL || "https://rutracker.org").trim().replace(/\/+$/, "");
  async function rtCookie() {
    return sessionCookie("rutracker", envCookie("rutracker", process.env.RUTRACKER_COOKIE, "bb_session"), async () => {
      const base = rtBase();
      const form = formBody([["login_username", process.env.RUTRACKER_USER || ""], ["login_password", process.env.RUTRACKER_PASS || ""], ["login", "Вход"]]);
      if (flare()) { // Cloudflare-safe login from the server's own IP
        const sol = await flarePost(`${base}/forum/login.php`, form);
        if (!/bb_session=/.test(sol.cookie)) throw new Error("вход по паролю не удался (неверный пароль или капча) — проверьте RUTRACKER_USER/RUTRACKER_PASS");
        cfCache.set(new URL(base).host, { cookie: sol.cookie, ua: sol.ua, until: Date.now() + 20 * 60 * 1000 });
        return sol.cookie;
      }
      const r = await request(`${base}/forum/login.php`, {
        method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded", Referer: `${base}/forum/login.php`, Origin: base },
        data: form
      });
      if (isChallenge(r)) throw new Error("Cloudflare-защита мешает входу по паролю: используйте RUTRACKER_COOKIE + FLARESOLVERR_URL");
      const cookie = r.cookies.join("; ");
      if (!/bb_session=/.test(cookie)) throw new Error("вход не удался (пароль или капча) — задайте RUTRACKER_COOKIE");
      return cookie;
    });
  }
  function parseRutracker(html) {
    const out = [];
    for (const m of html.matchAll(/<tr\b([^>]*)>([\s\S]*?)<\/tr>/gi)) {
      const attrs = m[1], row = m[2];
      if (!/\btCenter\b|\bid="(?:trs-tr-|tor_)/i.test(attrs)) continue;
      const a = /<a\b([^>]*\btLink\b[^>]*)>([\s\S]*?)<\/a>/i.exec(row) || /<a\b([^>]*href="[^"]*viewtopic\.php\?t=\d+[^"]*"[^>]*)>([\s\S]*?)<\/a>/i.exec(row);
      const id = a && /viewtopic\.php\?t=(\d+)/.exec(a[1]);
      if (!a || !id) continue;
      const mg = /href="(magnet:\?xt=urn:btih:[^"]+)"/i.exec(row); // logged-in result rows usually carry the magnet already
      const magnet = mg ? decodeHtml(mg[1]) : "";
      const sizeAttr = /class="[^"]*\btor-size\b[^"]*"[^>]*data-ts_text="(\d+)"/i.exec(row) || /data-ts_text="(\d+)"[^>]*class="[^"]*\btor-size\b/i.exec(row);
      const sizeTxt = /<a\b[^>]*\bdl-stub\b[^>]*>([\s\S]*?)<\/a>/i.exec(row) || /<u>\s*(\d+)\s*<\/u>/i.exec(row);
      const seed = /class="[^"]*\bseedmed\b[^"]*"[^>]*>([\s\S]*?)<\/td>/i.exec(row);
      const leech = /class="[^"]*\bleechmed\b[^"]*"[^>]*>([\s\S]*?)<\/td>/i.exec(row);
      const stamps = [...row.matchAll(/data-ts_text="(\d{9,11})"/g)].map(x => Number(x[1]));
      let size = sizeAttr ? Number(sizeAttr[1]) : parseSize(text(sizeTxt ? sizeTxt[1] : ""));
      if (!size && sizeTxt && /^\d+$/.test(text(sizeTxt[1]))) size = Number(text(sizeTxt[1]));
      out.push({
        title: text(a[2]), size,
        seeders: int(text(seed ? seed[1] : "")), leechers: int(text(leech ? leech[1] : "")),
        date: (stamps.length ? stamps[stamps.length - 1] : 0) * 1000,
        infoHash: magnet ? normHash((/btih:([0-9a-f]{40})/i.exec(magnet) || [])[1]) : "", magnet, lazy: { topic: id[1] }
      });
    }
    return out;
  }
  // ---------- TorAPI (https://github.com/Lifailon/TorAPI): RuTracker и Kinozal без аккаунта ----------
  // Свой экземпляр на VPS (TORAPI_URL=http://127.0.0.1:8443). Используется, когда для трекера не задан
  // аккаунт/cookie; magnet берётся по id раздачи только при нажатии «В библиотеку».
  const torapiBase = () => String(process.env.TORAPI_URL || "").trim().replace(/\/+$/, "");
  const TORAPI_TIMEOUT = Number(process.env.TORAPI_TIMEOUT_MS || 20000);
  async function torapiGet(path) {
    const res = await axios.get(torapiBase() + path, {
      timeout: TORAPI_TIMEOUT, headers: { Accept: "application/json", "User-Agent": UA }, validateStatus: () => true, responseType: "json"
    });
    if (res.status === 404) return []; // «ничего не найдено» в некоторых версиях отдаётся как 404
    if (res.status !== 200) throw new Error(`TorAPI: HTTP ${res.status}`);
    let j = res.data;
    if (typeof j === "string") { try { j = JSON.parse(j); } catch { throw new Error("TorAPI вернул не JSON"); } }
    return j;
  }
  function torDate(v) {
    const t = String(v || "").trim();
    if (!t) return 0;
    const ru = ruDate(t.replace(/-/g, " ")) || ruDate(t);       // «29-Авг-25», «29.08.2025»
    if (ru) return ru;
    const d = Date.parse(t);                                      // ISO / «2025-08-29 12:00»
    return Number.isFinite(d) ? d : 0;
  }
  function torapiRows(j, tracker) {
    // {"Result":"Server is not available"} = TorAPI не достучался до трекера: показываем это, а не «0 найдено».
    if (j && !Array.isArray(j) && typeof j.Result === "string" && !/no matches|не найден|nothing/i.test(j.Result))
      throw new Error(`TorAPI: ${j.Result}${/not available/i.test(j.Result) ? " — трекер недоступен с VPS (блокировка?), нужен прокси" : ""}`);
    const list = Array.isArray(j) ? j : (Array.isArray(j && j.Result) ? j.Result : []);
    return list.filter(x => x && (x.Name || x.Title) && x.Id).map(x => ({
      title: String(x.Name || x.Title).replace(/\s+/g, " ").trim(),
      size: parseSize(String(x.Size || "")), seeders: int(String(x.Seeds ?? "").replace(/\D/g, "")), leechers: int(String(x.Peers ?? "").replace(/\D/g, "")),
      date: torDate(x.Date), infoHash: "", lazy: { via: "torapi", tracker, id: String(x.Id) }
    }));
  }
  async function torapiSearch(tracker, q) {
    return torapiRows(await torapiGet(`/api/search/title/${tracker}?query=${encodeURIComponent(q)}&page=0`), tracker);
  }
  async function torapiResolve(r) {
    const j = await torapiGet(`/api/search/id/${r.lazy.tracker}?query=${encodeURIComponent(r.lazy.id)}`);
    const x = Array.isArray(j) ? j[0] : j;
    const magnet = x && typeof x.Magnet === "string" && /^magnet:\?/i.test(x.Magnet) ? x.Magnet : "";
    const infoHash = normHash((x && x.Hash) || (/btih:([0-9a-f]{40})/i.exec(magnet) || [])[1] || "");
    if (!magnet && !infoHash) throw new Error("TorAPI не вернул magnet для этой раздачи");
    return { magnet, infoHash };
  }
  const viaTorapi = r => r && r.lazy && r.lazy.via === "torapi";

  const rtAccount = () => !!((process.env.RUTRACKER_USER && process.env.RUTRACKER_PASS) || process.env.RUTRACKER_COOKIE);
  const rutracker = {
    label: "RuTracker", ru: true,
    enabled: () => rtAccount() || !!torapiBase(),
    async search(q) {
      if (!rtAccount()) return torapiSearch("rutracker", q);
      const base = rtBase();
      for (let attempt = 0; attempt < 2; attempt++) {
        const cookie = await rtCookie();
        const r = await fetchPage(`${base}/forum/tracker.php?nm=${encodeCp1251(q)}`, { headers: { Cookie: cookie }, charset: "windows-1251" });
        if (r.status !== 200) throw new Error(`HTTP ${r.status}`);
        if (/name="login_username"/.test(r.text)) {
          sessions.delete("rutracker");
          if (process.env.RUTRACKER_COOKIE && !rejectedCookie.has("rutracker")) {
            if (process.env.RUTRACKER_USER && process.env.RUTRACKER_PASS) { rejectedCookie.add("rutracker"); continue; } // fall back to password login
            throw new Error("сайт не принял cookie (устарела, неполная или создана с другого IP). Войдите заново и скопируйте ВСЕ cookie rutracker.org либо задайте RUTRACKER_USER/RUTRACKER_PASS + FLARESOLVERR_URL");
          }
          continue;
        }
        const rows = parseRutracker(r.text);
        if (!rows.length) debugEmpty("rutracker", r.text, r.finalUrl);
        return rows;
      }
      throw new Error("не удалось войти");
    },
    async resolve(r) { // magnet со страницы раздачи; .torrent — отдельно
      if (viaTorapi(r)) return torapiResolve(r);
      const base = rtBase(), cookie = await rtCookie();
      const page = await fetchPage(`${base}/forum/viewtopic.php?t=${r.lazy.topic}`, { headers: { Cookie: cookie }, charset: "windows-1251" });
      const mg = /href="(magnet:\?xt=urn:btih:[^"]+)"/i.exec(page.text);
      return { magnet: mg ? decodeHtml(mg[1]) : "", infoHash: mg ? normHash((/btih:([0-9a-f]{40})/i.exec(mg[1]) || [])[1]) : "" };
    },
    async download(r) {
      if (viaTorapi(r) && !rtAccount()) throw new Error("Файл .torrent RuTracker отдаёт только с аккаунтом — используется magnet");
      const base = rtBase(), cookie = await rtCookie();
      const res = await fetchPage(`${base}/forum/dl.php?t=${r.lazy.topic}`, { headers: { Cookie: cookie, Referer: `${base}/forum/viewtopic.php?t=${r.lazy.topic}` }, buffer: true });
      if (res.status !== 200 || res.buf[0] !== 0x64) throw new Error("RuTracker не отдал .torrent (нужна авторизация или раздача закрыта)");
      return res.buf;
    }
  };

  // ---------- kinozal.tv (аккаунт или KINOZAL_COOKIE) ----------
  const kzBase = () => String(process.env.KINOZAL_URL || "https://kinozal.tv").trim().replace(/\/+$/, "");
  const kzDl = () => String(process.env.KINOZAL_DL_URL || kzBase().replace("//kinozal.", "//dl.kinozal.")).replace(/\/+$/, "");
  async function kzCookie() {
    return sessionCookie("kinozal", envCookie("kinozal", process.env.KINOZAL_COOKIE, "pass"), async () => {
      const base = kzBase();
      const form = formBody([["username", process.env.KINOZAL_USER || ""], ["password", process.env.KINOZAL_PASS || ""], ["returnto", ""]]);
      if (flare()) {
        const sol = await flarePost(`${base}/takelogin.php`, form);
        if (!/(^|;\s*)pass=/.test(sol.cookie)) throw new Error("вход по паролю не удался (неверный пароль или капча) — проверьте KINOZAL_USER/KINOZAL_PASS");
        cfCache.set(new URL(base).host, { cookie: sol.cookie, ua: sol.ua, until: Date.now() + 20 * 60 * 1000 });
        return sol.cookie;
      }
      const r = await request(`${base}/takelogin.php`, {
        method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded", Referer: `${base}/`, Origin: base },
        data: form, charset: "windows-1251"
      });
      if (isChallenge(r)) throw new Error("Cloudflare-защита мешает входу по паролю: используйте KINOZAL_COOKIE + FLARESOLVERR_URL");
      const cookie = r.cookies.join("; ");
      if (!/(^|;\s*)pass=/.test(cookie)) throw new Error("вход не удался (пароль или капча) — задайте KINOZAL_COOKIE");
      return cookie;
    });
  }
  function parseKinozal(html) {
    const out = [];
    for (const m of html.matchAll(/<tr\b[^>]*>([\s\S]*?)<\/tr>/gi)) {
      const row = m[1];
      if (!/class=['"](?:bt|nam|sl_s)['"]/i.test(row)) continue;
      const a = /<a\b[^>]*href=['"]\/?details\.php\?id=(\d+)[^'"]*['"][^>]*>([\s\S]*?)<\/a>/i.exec(row);
      if (!a) continue;
      const cells = [...row.matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(c => text(c[1]));
      const seed = /class=['"]sl_s['"][^>]*>\s*(\d+)/i.exec(row);
      const leech = /class=['"]sl_p['"][^>]*>\s*(\d+)/i.exec(row);
      const size = /(\d[\d.,]*\s*(?:ТБ|ГБ|МБ|КБ|[KMGT]B))/i.exec(cells[3] || "") || /(\d[\d.,]*\s*(?:ТБ|ГБ|МБ|КБ|[KMGT]B))/i.exec(text(row));
      out.push({
        title: text(a[2]), size: parseSize(size ? size[1] : ""),
        seeders: int(seed ? seed[1] : cells[4]), leechers: int(leech ? leech[1] : cells[5]),
        date: ruDate(text(row)), infoHash: "", lazy: { id: a[1] }
      });
    }
    return out;
  }
  const kzAccount = () => !!((process.env.KINOZAL_USER && process.env.KINOZAL_PASS) || process.env.KINOZAL_COOKIE);
  const kinozal = {
    label: "Kinozal", ru: true,
    enabled: () => kzAccount() || !!torapiBase(),
    async search(q) {
      if (!kzAccount()) return torapiSearch("kinozal", q);
      const base = kzBase();
      for (let attempt = 0; attempt < 2; attempt++) {
        const cookie = await kzCookie();
        const r = await fetchPage(`${base}/browse.php?s=${encodeCp1251(q)}&g=0&c=0&v=0&d=0&w=0&t=0&f=0`, { headers: { Cookie: cookie }, charset: "windows-1251" });
        if (r.status !== 200) throw new Error(`HTTP ${r.status}`);
        if (/name=['"]password['"]/.test(r.text) && !/browse\.php\?.*page=/.test(r.text) && !/class=['"]bg['"]/.test(r.text)) {
          sessions.delete("kinozal");
          if (process.env.KINOZAL_COOKIE && !rejectedCookie.has("kinozal")) {
            if (process.env.KINOZAL_USER && process.env.KINOZAL_PASS) { rejectedCookie.add("kinozal"); continue; }
            throw new Error("сайт не принял cookie (устарела или создана с другого IP): скопируйте заново uid и pass либо задайте KINOZAL_USER/KINOZAL_PASS + FLARESOLVERR_URL");
          }
          continue;
        }
        const rows = parseKinozal(r.text);
        if (!rows.length) debugEmpty("kinozal", r.text, r.finalUrl);
        return rows;
      }
      throw new Error("не удалось войти");
    },
    async resolve(r) { // инфо-хеш со страницы раздачи -> magnet
      if (viaTorapi(r)) return torapiResolve(r);
      const base = kzBase(), cookie = await kzCookie();
      const page = await fetchPage(`${base}/details.php?id=${r.lazy.id}`, { headers: { Cookie: cookie }, charset: "windows-1251" });
      const hm = /Инфо\s*хеш[\s\S]{0,200}?\b([0-9a-f]{40})\b/i.exec(page.text);
      const hash = hm ? normHash(hm[1]) : "";
      return { magnet: "", infoHash: hash };
    },
    async download(r) {
      if (viaTorapi(r) && !kzAccount()) throw new Error("Файл .torrent Kinozal отдаёт только с аккаунтом — используется magnet");
      const cookie = await kzCookie();
      const res = await fetchPage(`${kzDl()}/download.php?id=${r.lazy.id}`, { headers: { Cookie: cookie, Referer: `${kzBase()}/details.php?id=${r.lazy.id}` }, buffer: true });
      if (res.status !== 200 || res.buf[0] !== 0x64) throw new Error("Kinozal не отдал .torrent (нужна авторизация или недостаточно рейтинга)");
      return res.buf;
    }
  };

  // ---------- NNM-Club (аккаунт или NNMCLUB_COOKIE) ----------
  // Вёрстка — типовой phpBB-трекер (tracker.php, строки с a.topictitle и td.seedmed/leechmed). Написано по общей схеме
  // таких трекеров, без доступа к живому сайту: при расхождении включите SEARCH_DEBUG=1.
  const nnmBase = () => String(process.env.NNMCLUB_URL || "https://nnmclub.to").trim().replace(/\/+$/, "");
  const nnmLoggedOut = html => /name="username"[\s\S]{0,400}name="password"|name="password"[\s\S]{0,400}name="username"/i.test(html) && !/logout/i.test(html);
  const nnmHasAuth = () => !!((process.env.NNMCLUB_USER && process.env.NNMCLUB_PASS) || process.env.NNMCLUB_COOKIE);
  async function nnmCookie() {
    if (!nnmHasAuth()) return ""; // гостевой режим: аккаунт не нужен
    return sessionCookie("nnmclub", envCookie("nnmclub", process.env.NNMCLUB_COOKIE, "phpbb2mysql_4_sid"), async () => {
      const base = nnmBase();
      const form = formBody([["username", process.env.NNMCLUB_USER || ""], ["password", process.env.NNMCLUB_PASS || ""], ["autologin", "on"], ["redirect", "index.php"], ["login", "Вход"]]);
      if (flare()) {
        const sol = await flarePost(`${base}/forum/login.php`, form);
        if (!/_(?:sid|data)=[^;\s]{8,}/.test(sol.cookie)) throw new Error("вход по паролю не удался (неверный пароль или капча) — проверьте NNMCLUB_USER/NNMCLUB_PASS");
        cfCache.set(new URL(base).host, { cookie: sol.cookie, ua: sol.ua, until: Date.now() + 20 * 60 * 1000 });
        return sol.cookie;
      }
      const r = await request(`${base}/forum/login.php`, {
        method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded", Referer: `${base}/forum/login.php`, Origin: base }, data: form
      });
      if (isChallenge(r)) throw new Error("Защита сайта мешает входу по паролю: используйте NNMCLUB_COOKIE и/или FLARESOLVERR_URL");
      const cookie = r.cookies.join("; ");
      if (!/_(?:sid|data)=[^;\s]{8,}/.test(cookie)) throw new Error("вход не удался (пароль или капча) — задайте NNMCLUB_COOKIE");
      return cookie;
    });
  }
  function parseNnm(html) {
    const out = [];
    for (const m of html.matchAll(/<tr\b[^>]*>([\s\S]*?)<\/tr>/gi)) {
      const row = m[1];
      if (!/seedmed|leechmed/i.test(row)) continue;
      const a = /<a\b[^>]*class="[^"]*\btopictitle\b[^"]*"[^>]*href="[^"]*viewtopic\.php\?t=(\d+)[^"]*"[^>]*>([\s\S]*?)<\/a>/i.exec(row)
        || /<a\b[^>]*href="[^"]*viewtopic\.php\?t=(\d+)[^"]*"[^>]*class="[^"]*\btopictitle\b[^"]*"[^>]*>([\s\S]*?)<\/a>/i.exec(row)
        || /<a\b[^>]*href="[^"]*viewtopic\.php\?t=(\d+)[^"]*"[^>]*>([\s\S]*?)<\/a>/i.exec(row);
      if (!a) continue;
      const mg = /href="(magnet:\?xt=urn:btih:[^"]+)"/i.exec(row);
      const magnet = mg ? decodeHtml(mg[1]) : "";
      const cells = [...row.matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map(c => c[1]);
      const sizeCell = cells.map(text).find(c => /^\d[\d.,]*\s*(?:[KMGT]B|[КМГТ]Б)$/i.test(c)) || "";
      const seed = /class="[^"]*\bseedmed\b[^"]*"[^>]*>([\s\S]*?)<\/td>/i.exec(row);
      const leech = /class="[^"]*\bleechmed\b[^"]*"[^>]*>([\s\S]*?)<\/td>/i.exec(row);
      out.push({
        title: text(a[2]), size: parseSize(sizeCell), seeders: int(text(seed ? seed[1] : "")), leechers: int(text(leech ? leech[1] : "")),
        date: ruDate(text(cells[cells.length - 1] || "")),
        infoHash: magnet ? normHash((/btih:([0-9a-f]{40})/i.exec(magnet) || [])[1]) : "", magnet, lazy: { topic: a[1] }
      });
    }
    return out;
  }
  // NNM-Club порой отдаёт ленту свежих раздач вместо результатов (запрос в адресе игнорируется). Такие ответы
  // распознаются по тому, что большинство названий не содержит слов запроса: тогда запрос повторяется
  // как отправка формы поиска (POST), а в итог попадают только подходящие раздачи.
  const normQ = s => String(s || "").toLowerCase().replace(/[ёэ]/g, "е").replace(/[^\p{L}\p{N}]+/gu, " ").trim();
  function nnmMatching(rows, q) {
    const words = normQ(q).split(" ").filter(w => w.length >= 3);
    if (!words.length) return rows;
    const stems = words.map(w => (w.length > 5 ? w.slice(0, -2) : w)); // окончания: «бэтмена», «матрицы»
    return rows.filter(r => { const t = normQ(r.title); return stems.some(st => t.includes(st)); });
  }
  const looksIgnored = (rows, hits) => rows.length >= 5 && hits.length < rows.length * 0.3;
  async function nnmSearchPage(q, headers) {
    const base = nnmBase();
    const r = await fetchPage(`${base}/forum/tracker.php?nm=${encodeCp1251(q)}`, { headers, charset: "windows-1251" });
    if (r.status !== 200) throw new Error(`HTTP ${r.status}`);
    let rows = parseNnm(r.text), hits = nnmMatching(rows, q);
    if (looksIgnored(rows, hits)) {
      const body = `nm=${encodeCp1251(q)}&f=-1&o=10&s=2&submit_search=%CF%EE%E8%F1%EA`; // «Поиск» в cp1251
      const r2 = await fetchPage(`${base}/forum/tracker.php`, {
        method: "POST", data: body, charset: "windows-1251",
        headers: { ...headers, "Content-Type": "application/x-www-form-urlencoded", Referer: `${base}/forum/tracker.php` }
      }).catch(() => null);
      if (r2 && r2.status === 200) {
        const rows2 = parseNnm(r2.text), hits2 = nnmMatching(rows2, q);
        if (!looksIgnored(rows2, hits2)) return { r: r2, rows: rows2 };
        rows = rows2.length ? rows2 : rows; hits = nnmMatching(rows, q);
      }
      return { r, rows: hits }; // лента вместо поиска: оставляем только то, что подходит под запрос
    }
    return { r, rows };
  }

  const nnmclub = {
    label: "NNM-Club", ru: true,
    enabled: () => true,
    async search(q) {
      const base = nnmBase();
      if (!nnmHasAuth()) { // без аккаунта
        const { r, rows } = await nnmSearchPage(q, {});
        if (!rows.length) {
          if (nnmLoggedOut(r.text)) throw new Error("поиск на сайте требует вход: задайте NNMCLUB_USER/NNMCLUB_PASS или NNMCLUB_COOKIE");
          debugEmpty("nnmclub", r.text, r.finalUrl);
        }
        return rows;
      }
      for (let attempt = 0; attempt < 2; attempt++) {
        const cookie = await nnmCookie();
        const { r, rows } = await nnmSearchPage(q, { Cookie: cookie });
        if (nnmLoggedOut(r.text)) {
          sessions.delete("nnmclub");
          if (process.env.NNMCLUB_COOKIE && !rejectedCookie.has("nnmclub")) {
            if (process.env.NNMCLUB_USER && process.env.NNMCLUB_PASS) { rejectedCookie.add("nnmclub"); continue; }
            throw new Error("сайт не принял cookie (устарела или создана с другого IP): скопируйте заново либо задайте NNMCLUB_USER/NNMCLUB_PASS");
          }
          continue;
        }
        if (!rows.length) debugEmpty("nnmclub", r.text, r.finalUrl);
        return rows;
      }
      throw new Error("не удалось войти");
    },
    async resolve(r) { // magnet со страницы раздачи
      const base = nnmBase(), cookie = await nnmCookie();
      const page = await fetchPage(`${base}/forum/viewtopic.php?t=${r.lazy.topic}`, { headers: { Cookie: cookie }, charset: "windows-1251" });
      const mg = /href="(magnet:\?xt=urn:btih:[^"]+)"/i.exec(page.text);
      return { magnet: mg ? decodeHtml(mg[1]) : "", infoHash: mg ? normHash((/btih:([0-9a-f]{40})/i.exec(mg[1]) || [])[1]) : "" };
    }
  };

  return { rutor, rutracker, kinozal, nnmclub };
}

module.exports = { make, encodeCp1251, parseHelpers: { text, ruDate, decodeHtml } };
