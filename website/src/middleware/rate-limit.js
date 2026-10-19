"use strict";

const rateLimit = require("express-rate-limit");

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

module.exports = {
  loginLimiter,
  apiLimiter,
  posterLimiter,
  mediaInfoLimiter,
  mirrorUrlLimiter,
  searchLimiter,
  mirrorLimiter,
  reportLimiter,
  anonReportLimiter,
  serviceLimiter
};
