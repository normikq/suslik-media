"use strict";

const crypto = require("crypto");
const expressRateLimit = require("express-rate-limit");
const { ipKeyGenerator } = expressRateLimit;
const { verifyActivityToken } = require("../services/activity");

// Счёт по адресу; запросы Discord Activity — по её ключу: они все приходят с адресов прокси Discord,
// и по адресу друзья в одном голосовом канале делили бы один лимит. Ключ считается, только если он настоящий
// (выдан сервером и не истёк): иначе случайный «Bearer sa_…» в каждом запросе давал бы каждому запросу свой
// счётчик, и лимит не работал бы вовсе
function clientKey(req) {
  const auth = String(req.get("Authorization") || "");
  const m = /^Bearer\s+(sa_[0-9a-f]{20,80})$/i.exec(auth);
  if (m && verifyActivityToken(m[1])) return "sa:" + crypto.createHash("sha256").update(m[1]).digest("hex").slice(0, 32);
  return ipKey(req);
}

function ipKey(req) {
  return ipKeyGenerator(req.ip || "");
}

function rateLimit(options) {
  return expressRateLimit({ keyGenerator: clientKey, ...options });
}

// Вход по паролю и код устройства — всегда по адресу: до входа ключа Activity у человека нет
const loginLimiter = rateLimit({
  keyGenerator: ipKey,
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

// Separate, looser limits for requests that come in bursts and must not eat the general one: poster images (a library
// grid asks for 100+ at once; they are cached on disk) and renewed mirror addresses (the reserve and every episode's
// voice-overs / subtitles — a 429 there stops a film). Phone and PC behind one router share an IP.
const posterLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 1000,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  handler: (req, res, next, options) => res.status(options.statusCode).json({ error: "Слишком много запросов." })
});

// Сведения о видео: плитки медиатеки спрашивают их пачкой (ffprobe на сервере всё равно не больше двух сразу).
const mediaInfoLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 300,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  handler: (req, res, next, options) => res.status(options.statusCode).json({ error: "Слишком много запросов." })
});

const mirrorUrlLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 300,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  handler: (req, res, next, options) => res.status(options.statusCode).json({ error: "Слишком много запросов." })
});

const searchLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 15,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  handler: (req, res, next, options) => res.status(options.statusCode).json({ error: "Слишком много поисковых запросов. Подождите минуту." })
});

// Players send many Range requests while seeking; keep them out of the UI rate limit.
const mirrorLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 3000,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  message: "Слишком много запросов.",
  handler: (req, res, next, options) => res.status(options.statusCode).send(options.message)
});

const reportLimiter = rateLimit({
  windowMs: 60 * 60 * 1000,
  limit: 30,
  standardHeaders: "draft-7",
  legacyHeaders: false
});

// A crash before the login screen still reaches us, but without a login only 5 a hour and 64 KB each: strangers
// cannot push the real reports out (the newest 300 are kept).
const anonReportLimiter = rateLimit({
  windowMs: 60 * 60 * 1000,
  limit: 5,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  skip: req => !!(req.session && req.session.authenticated)
});

// Служебные запросы по X-Update-Token (выпуск, статус, отчёты) и первая загрузка беты по коду: CI и владелец шлют их
// единицами, а подбор токена или кода упирается в этот лимит.
const serviceLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 60,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  handler: (req, res, next, options) => res.status(options.statusCode).json({ error: "Слишком много запросов. Подождите немного." })
});

// Связка устройства по коду: не подбор пароля, свой счётчик (ТВ, ПК и ноутбук за одним роутером, «Отмена» и снова)
const deviceAuthLimiter = rateLimit({
  keyGenerator: ipKey,
  windowMs: 15 * 60 * 1000,
  limit: 60,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  handler: (req, res, next, options) => res.status(options.statusCode).json({ error: "Слишком много запросов кода. Подождите немного." })
});

// Опрос статуса связки: приложение спрашивает раз в несколько секунд, запас — на несколько устройств за одним роутером
const devicePollLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 120,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  handler: (req, res, next, options) => res.status(options.statusCode).json({ error: "Слишком частые запросы. Подождите немного." })
});

// Совместный просмотр: команды ведущего, «грузится», вход. Живой просмотр шлёт единицы в минуту — лимит только от сбоев
// Код комнаты — шесть цифр (900 000 вариантов): перебор по одному коду режем отдельно, настоящему человеку хватает с запасом
const roomLookupLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 40,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  handler: (req, res, next, options) => res.status(options.statusCode).json({ error: "Слишком много попыток подобрать комнату. Подождите минуту." })
});

const roomLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 300,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  handler: (req, res, next, options) => res.status(options.statusCode).json({ error: "Слишком много команд комнате." })
});

// События речи из Activity (приглушение): у каждого пульта в канале — начало и конец каждой фразы и повтор
// «ещё говорит» раз в 10 с. Пять говорящих болтают без пауз — около сотни в минуту; лимит только от сбоев
const speakingLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 600,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  handler: (req, res, next, options) => res.status(options.statusCode).json({ error: "Слишком много событий речи." })
});

// Вход из Discord Activity: ещё без ключа, а адрес у всех один — прокси Discord (адрес человека он не передаёт).
// Каждый запуск Activity входит заново, поэтому удачные входы не считаются: лимит тратят только неудачные
// (поддельный код, нет доступа), и обычные запуски у друзей его не съедают. Мусор вместо кода отсекается ещё
// до лимита (routes/activity.js, requireOAuthCode) и в Discord не уходит
const activityAuthLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 400,
  skipSuccessfulRequests: true,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  handler: (req, res, next, options) => res.status(options.statusCode).json({ error: "Слишком много входов. Попробуйте через несколько минут." })
});

module.exports = {
  activityAuthLimiter,
  deviceAuthLimiter,
  devicePollLimiter,
  roomLimiter,
  roomLookupLimiter,
  speakingLimiter,
  loginLimiter,
  apiLimiter,
  posterLimiter,
  mediaInfoLimiter,
  mirrorUrlLimiter,
  searchLimiter,
  mirrorLimiter,
  reportLimiter,
  anonReportLimiter,
  serviceLimiter,
  clientKey
};
