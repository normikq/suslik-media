"use strict";
// Мета-поиск по торрент-трекерам. Каждый провайдер возвращает единый формат:
//   { provider, title, size, seeders, leechers, date, infoHash, magnet, torrentUrl }
// `torrentUrl` (может содержать API-ключ Prowlarr/Jackett) НИКОГДА не отдаётся браузеру: клиент получает
// только непрозрачный id, а скачивание .torrent выполняет сервер.
const axios = require("axios");
const crypto = require("crypto");
const { classify } = require("./classify");
const { refineKinds } = require("./tmdb-kind");

const UA = "Mozilla/5.0 (compatible; SuslikMedia/1.0)";
const PROVIDER_TIMEOUT_MS = Number(process.env.SEARCH_TIMEOUT_MS || 10000);

const TRACKERS = [
  "udp://tracker.opentrackr.org:1337/announce",
  "udp://open.tracker.cl:1337/announce",
  "udp://tracker.torrent.eu.org:451/announce",
  "udp://exodus.desync.com:6969/announce",
  "udp://open.demonii.com:1337/announce"
];

function trimBase(url) { return String(url || "").trim().replace(/\/+$/, ""); }
function normHash(h) {
  const v = String(h || "").trim().toLowerCase();
  return /^[0-9a-f]{40}$/.test(v) ? v : "";
}
function buildMagnet(infoHash, title) {
  const hash = normHash(infoHash);
  if (!hash) return "";
  return `magnet:?xt=urn:btih:${hash}&dn=${encodeURIComponent(String(title || "").slice(0, 200))}` +
    TRACKERS.map(t => `&tr=${encodeURIComponent(t)}`).join("");
}
function hashFromMagnet(magnet) {
  const m = /xt=urn:btih:([0-9a-zA-Z]+)/i.exec(String(magnet || ""));
  if (!m) return "";
  const v = m[1];
  if (/^[0-9a-f]{40}$/i.test(v)) return v.toLowerCase();
  if (/^[A-Z2-7]{32}$/i.test(v)) { // base32 -> hex
    const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    let bits = "";
    for (const ch of v.toUpperCase()) bits += alphabet.indexOf(ch).toString(2).padStart(5, "0");
    let hex = "";
    for (let i = 0; i + 4 <= bits.length && hex.length < 40; i += 4) hex += parseInt(bits.slice(i, i + 4), 2).toString(16);
    return normHash(hex.slice(0, 40));
  }
  return "";
}
function parseSize(text) {
  const m = /([\d.,]+)\s*([KMGT]?i?B|[КМГТ]Б)/i.exec(String(text || "").replace(/\u00a0/g, " "));
  if (!m) return 0;
  const n = parseFloat(m[1].replace(",", "."));
  const unit = m[2].toUpperCase().replace("КБ", "KB").replace("МБ", "MB").replace("ГБ", "GB").replace("ТБ", "TB");
  const pow = { B: 0, KB: 1, KIB: 1, MB: 2, MIB: 2, GB: 3, GIB: 3, TB: 4, TIB: 4 }[unit] ?? 0;
  return Number.isFinite(n) ? Math.round(n * Math.pow(1024, pow)) : 0;
}
function int(v) { const n = parseInt(v, 10); return Number.isFinite(n) && n >= 0 ? n : 0; }

// ---- минимальный разбор XML (RSS / Torznab) без зависимостей ---------------------------------
function decodeXml(s) {
  return String(s || "")
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
    .replace(/&amp;/g, "&").trim();
}
function xmlItems(xml) { return [...String(xml).matchAll(/<item\b[^>]*>([\s\S]*?)<\/item>/gi)].map(m => m[1]); }
function xmlTag(block, tag) {
  const m = new RegExp(`<${tag}\\b[^>]*>([\\s\\S]*?)</${tag}>`, "i").exec(block);
  return m ? decodeXml(m[1]) : "";
}
function xmlAttrs(block) { // <torznab:attr name="seeders" value="12"/>
  const out = {};
  for (const m of block.matchAll(/<(?:torznab|newznab):attr\b[^>]*?name="([^"]*)"[^>]*?value="([^"]*)"/gi)) out[m[1].toLowerCase()] = decodeXml(m[2]);
  return out;
}

async function get(url, { headers = {}, params, responseType = "json" } = {}) {
  const res = await axios.get(url, {
    params, headers: { "User-Agent": UA, ...headers }, timeout: PROVIDER_TIMEOUT_MS,
    responseType: responseType === "json" ? "json" : "text", transformResponse: r => r,
    maxContentLength: 8 * 1024 * 1024, validateStatus: () => true
  });
  if (res.status < 200 || res.status >= 300) throw new Error(`HTTP ${res.status}`);
  if (responseType === "json") { try { return JSON.parse(res.data); } catch { throw new Error("ответ не JSON"); } }
  return String(res.data);
}

const RU_INDEXER = /rutracker|rutor|kinozal|nnm|toloka|megapeer|bitru|selezen|lostfilm|hdclub|tfile|rustorka|underverse|animelayer|riperam|\.ru\b|\brus/i;

// ---- провайдеры -----------------------------------------------------------------------------
const providers = {
  // The Pirate Bay (JSON API apibay.org)
  apibay: {
    label: "TPB",
    enabled: () => true,
    async search(q) {
      const base = trimBase(process.env.APIBAY_URL || "https://apibay.org");
      const rows = await get(`${base}/q.php`, { params: { q, cat: 0 } });
      if (!Array.isArray(rows)) return [];
      return rows.filter(r => normHash(r.info_hash) && !/^0+$/.test(r.info_hash)).map(r => ({
        title: String(r.name || ""), size: int(r.size), seeders: int(r.seeders), leechers: int(r.leechers),
        date: int(r.added) * 1000 || 0, infoHash: normHash(r.info_hash)
      }));
    }
  },
  // YTS (фильмы, JSON API)
  yts: {
    label: "YTS",
    enabled: () => true,
    async search(q) {
      const base = trimBase(process.env.YTS_URL || "https://yts.mx");
      const data = await get(`${base}/api/v2/list_movies.json`, { params: { query_term: q, limit: 30 } });
      const out = [];
      for (const mv of data?.data?.movies || []) {
        for (const t of mv.torrents || []) {
          if (!normHash(t.hash)) continue;
          out.push({
            title: `${mv.title_long || mv.title} [${t.quality || ""} ${t.type || ""}]`.replace(/\s+\]/, "]").trim(),
            size: int(t.size_bytes), seeders: int(t.seeds), leechers: int(t.peers),
            date: int(t.date_uploaded_unix) * 1000 || 0, infoHash: normHash(t.hash)
          });
        }
      }
      return out;
    }
  },
  // Nyaa (аниме, RSS)
  nyaa: {
    label: "Nyaa",
    enabled: () => true,
    async search(q) {
      const base = trimBase(process.env.NYAA_URL || "https://nyaa.si");
      const xml = await get(`${base}/`, { params: { page: "rss", q, c: "0_0", f: 0 }, responseType: "text" });
      return xmlItems(xml).map(b => ({
        title: xmlTag(b, "title"), size: parseSize(xmlTag(b, "nyaa:size")),
        seeders: int(xmlTag(b, "nyaa:seeders")), leechers: int(xmlTag(b, "nyaa:leechers")),
        date: Date.parse(xmlTag(b, "pubDate")) || 0, infoHash: normHash(xmlTag(b, "nyaa:infoHash"))
      })).filter(r => r.infoHash);
    }
  },
  // Prowlarr: один JSON-запрос ко всем вашим индексаторам (rutracker, rutor, 1337x, ...)
  prowlarr: {
    label: "Prowlarr",
    enabled: () => !!(process.env.PROWLARR_URL && process.env.PROWLARR_API_KEY),
    async search(q) {
      const base = trimBase(process.env.PROWLARR_URL);
      const rows = await get(`${base}/api/v1/search`, {
        params: { query: q, type: "search", limit: 100 }, headers: { "X-Api-Key": process.env.PROWLARR_API_KEY }
      });
      if (!Array.isArray(rows)) return [];
      return rows.map(r => {
        const magnet = typeof r.magnetUrl === "string" && r.magnetUrl.startsWith("magnet:?") ? r.magnetUrl : "";
        const infoHash = normHash(r.infoHash) || hashFromMagnet(magnet);
        return {
          title: String(r.title || ""), size: int(r.size), seeders: int(r.seeders), leechers: int(r.leechers),
          date: Date.parse(r.publishDate) || 0, infoHash, magnet,
          torrentUrl: typeof r.downloadUrl === "string" ? r.downloadUrl : "", sub: r.indexer || "", ru: RU_INDEXER.test(r.indexer || ""),
          cats: (Array.isArray(r.categories) ? r.categories : []).map(c => Number(c && c.id)).filter(Number.isFinite)
        };
      });
    }
  },
  // Jackett (Torznab XML, все индексаторы)
  jackett: {
    label: "Jackett",
    enabled: () => !!(process.env.JACKETT_URL && process.env.JACKETT_API_KEY),
    async search(q) {
      const base = trimBase(process.env.JACKETT_URL);
      const xml = await get(`${base}/api/v2.0/indexers/all/results/torznab/api`, {
        params: { apikey: process.env.JACKETT_API_KEY, t: "search", q }, responseType: "text"
      });
      return xmlItems(xml).map(b => {
        const a = xmlAttrs(b);
        const magnet = (a.magneturl || "").startsWith("magnet:?") ? a.magneturl : "";
        const link = xmlTag(b, "link") || (/<enclosure\b[^>]*url="([^"]+)"/i.exec(b) || [])[1] || "";
        return {
          title: xmlTag(b, "title"), size: int(a.size) || int(xmlTag(b, "size")),
          seeders: int(a.seeders), leechers: int(a.peers) ? Math.max(0, int(a.peers) - int(a.seeders)) : int(a.leechers),
          date: Date.parse(xmlTag(b, "pubDate")) || 0,
          infoHash: normHash(a.infohash) || hashFromMagnet(magnet), magnet,
          torrentUrl: decodeXml(link), sub: xmlTag(b, "jackettindexer") || "", ru: RU_INDEXER.test(xmlTag(b, "jackettindexer") || ""),
          cats: [...b.matchAll(/<torznab:attr\b[^>]*name="category"[^>]*value="(\d+)"/gi)].map(m => Number(m[1]))
        };
      });
    }
  }
};

// Русскоязычные трекеры (приоритетные): rutor, rutracker, kinozal.
Object.assign(providers, require("./search-ru").make({ int, parseSize, normHash }));

function enabledProviders() {
  const wanted = String(process.env.SEARCH_PROVIDERS || "rutor,rutracker,kinozal,nnmclub,prowlarr,jackett,apibay,nyaa")
    .split(",").map(s => s.trim().toLowerCase()).filter(Boolean);
  return wanted.filter(n => providers[n] && providers[n].enabled());
}

// Origins from which the server may download a .torrent file (only the configured indexer managers).
function trustedTorrentOrigins() {
  const out = [];
  for (const v of [process.env.PROWLARR_URL, process.env.JACKETT_URL]) {
    try { if (v) out.push(new URL(v).origin); } catch {}
  }
  return out;
}

// ---- кэш результатов: id -> результат (серверная сторона), чтобы клиент не присылал произвольные URL -------
const RESULT_TTL_MS = 30 * 60 * 1000;
const resultStore = new Map(); // id -> { r, until }
function storeResult(r) {
  const key = r.infoHash ? `h:${r.infoHash}` : `u:${r.providerKey || r.provider}:${r.lazy ? JSON.stringify(r.lazy) : (r.torrentUrl || r.title)}`;
  const id = crypto.createHash("sha1").update(key).digest("hex").slice(0, 20);
  resultStore.set(id, { r, until: Date.now() + RESULT_TTL_MS });
  if (resultStore.size > 5000) for (const [k, v] of resultStore) if (v.until < Date.now()) resultStore.delete(k);
  while (resultStore.size > 5000) resultStore.delete(resultStore.keys().next().value);
  return id;
}
function getResult(id) {
  const e = resultStore.get(String(id || ""));
  if (!e || e.until < Date.now()) { resultStore.delete(String(id || "")); return null; }
  return e.r;
}

// ---- поиск ----------------------------------------------------------------------------------
const searchCache = new Map(); // query -> { out, until }
const SEARCH_CACHE_MS = 5 * 60 * 1000;

async function search(rawQuery) {
  const q = String(rawQuery || "").replace(/\s+/g, " ").trim().slice(0, 120);
  if (q.length < 2) { const e = new Error("Введите минимум 2 символа."); e.status = 400; throw e; }
  const cacheKey = q.toLowerCase() + "|" + enabledProviders().join(",");
  const hit = searchCache.get(cacheKey);
  if (hit && hit.until > Date.now()) return hit.out;

  const names = enabledProviders();
  const started = Date.now();
  const settled = await Promise.all(names.map(async name => {
    const t0 = Date.now();
    try {
      const rows = await providers[name].search(q);
      return { name, ok: true, rows, ms: Date.now() - t0 };
    } catch (err) {
      const raw = String(err.message || err);
      const code = err.code || (/ENOTFOUND|ETIMEDOUT|ECONNREFUSED|ECONNRESET|ECONNABORTED/.exec(raw) || [])[0];
      const hint = { ENOTFOUND: "домен не найден с этого сервера (DNS/блокировка)", ETIMEDOUT: "нет ответа (таймаут или блокировка)",
        ECONNREFUSED: "соединение отклонено", ECONNRESET: "соединение сброшено (блокировка?)", ECONNABORTED: "таймаут" }[code];
      const msg = (hint ? `${hint}: укажите зеркало в .env или уберите «${name}» из SEARCH_PROVIDERS` : raw).slice(0, 160);
      console.warn(`[поиск:${name}] ошибка: ${raw.slice(0, 160)} (${Date.now() - t0} мс)`);
      return { name, ok: false, rows: [], error: msg, ms: Date.now() - t0 };
    }
  }));

  // Объединение: один infohash = одна запись (берём лучшую по сидам, запоминаем все источники).
  const byKey = new Map();
  for (const s of settled) {
    for (const row of s.rows) {
      if (!row.title) continue;
      if (!row.infoHash && !row.torrentUrl && !row.magnet && !row.lazy) continue;
      const r = { ...row, ru: !!(row.ru || providers[s.name].ru), providerKey: s.name,
        provider: providers[s.name].label + (row.sub ? ` · ${row.sub}` : ""), sources: [providers[s.name].label] };
      const key = r.infoHash ? `h:${r.infoHash}` : `u:${r.providerKey}:${r.lazy ? JSON.stringify(r.lazy) : (r.torrentUrl || r.title)}`;
      const prev = byKey.get(key);
      if (!prev) { byKey.set(key, r); continue; }
      const sources = [...new Set([...prev.sources, ...r.sources])];
      const best = r.seeders > prev.seeders ? r : prev;
      byKey.set(key, { ...best, ru: prev.ru || r.ru, magnet: best.magnet || prev.magnet || r.magnet, torrentUrl: best.torrentUrl || prev.torrentUrl || r.torrentUrl,
        lazy: best.lazy || prev.lazy || r.lazy, cats: best.cats || prev.cats || r.cats, providerKey: best.lazy ? best.providerKey : (prev.lazy ? prev.providerKey : best.providerKey), sources });
    }
  }
  const tier = r => (r.ru ? 0 : 1) + (r.seeders > 0 ? 0 : 2); // живые RU, живые остальные, мёртвые RU, мёртвые остальные
  const results = [...byKey.values()].sort((a, b) => tier(a) - tier(b) || b.seeders - a.seeders || b.size - a.size).slice(0, 300).map(r => {
    const magnet = r.magnet || buildMagnet(r.infoHash, r.title); // RU-трекеры с закрытым доступом получают magnet лениво, при добавлении
    const id = storeResult({ ...r, magnet });
    return { id, title: r.title, size: r.size, seeders: r.seeders, leechers: r.leechers, date: r.date,
      provider: r.provider, ru: !!r.ru, kind: classify(r.title, r.cats), sources: r.sources, infoHash: r.infoHash || null, hasMagnet: !!magnet, magnet: magnet || null };
  });
  await refineKinds(results).catch(() => {}); // тип по TMDB (мультфильмы и т. п.)
  const out = {
    query: q, total: results.length, tookMs: Date.now() - started, results,
    providers: settled.map(s => ({ name: providers[s.name].label, ok: s.ok, count: s.rows.length, ms: s.ms, error: s.error || null }))
  };
  // Ошибочные ответы (все провайдеры упали) не кэшируем.
  if (settled.some(s => s.ok)) { searchCache.set(cacheKey, { out, until: Date.now() + SEARCH_CACHE_MS }); if (searchCache.size > 200) searchCache.delete(searchCache.keys().next().value); }
  return out;
}

// Закрытые трекеры (rutracker, kinozal): magnet/.torrent берутся только когда пользователь жмёт «В библиотеку».
async function resolveLazy(r) {
  const p = providers[r.providerKey];
  if (!r.lazy || !p || !p.resolve) return { magnet: "", infoHash: "" };
  const x = await p.resolve(r);
  return { magnet: x.magnet || buildMagnet(x.infoHash, r.title), infoHash: x.infoHash || "" };
}
async function downloadLazy(r) {
  const p = providers[r.providerKey];
  if (!r.lazy || !p || !p.download) throw new Error("Для этого результата нет файла .torrent.");
  return p.download(r);
}

module.exports = { resolveLazy, downloadLazy, search, getResult, enabledProviders, trustedTorrentOrigins, buildMagnet, hashFromMagnet, parseSize, providers };
