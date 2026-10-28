"use strict";

const express = require("express");
const path = require("path");
const config = require("./config");
const { securityHeaders, setupTrustProxy, sameOriginWrites } = require("./middleware/security");
const { createSessionMiddleware } = require("./middleware/auth");
const { PRECOMPRESSED, precompressed, sendPrecompressed } = require("./services/precompressed");
const { libraryChanged } = require("./services/library");

// Routers
const authRoutes = require("./routes/auth");
const libraryRoutes = require("./routes/library");
const favoritesRoutes = require("./routes/favorites");
const magnetsRoutes = require("./routes/magnets");
const searchRoutes = require("./routes/search");
const mirrorRoutes = require("./routes/mirror");
const updatesRoutes = require("./routes/updates");
const mediaRoutes = require("./routes/media");
const roomsRoutes = require("./routes/rooms");
const syncRoutes = require("./routes/sync");
const tagsRoutes = require("./routes/tags");
const tvRoutes = require("./routes/tvstream");
const customAudioRoutes = require("./routes/custom-audio");
const aiTitleRoutes = require("./routes/aititle");
const markersRoutes = require("./routes/markers");

function createApp() {
  const app = express();

  app.disable("x-powered-by");

  // Заголовки безопасности
  app.use(securityHeaders);

  // Behind nginx/Caddy set TRUST_PROXY=1 so req.protocol/host and client IP reflect the public HTTPS request.
  setupTrustProxy(app);

  // Страница и стили сайта сжимаются один раз (gzip и brotli), а не на каждый запрос
  app.get(["/", "/index.html", "/redesign.css"], (req, res, next) => {
    if (!sendPrecompressed(req, res, PRECOMPRESSED[req.path] || "index.html")) next();
  });

  // Сжатие текстовых ответов (страница, JSON медиатеки и поиска, CSS/JS)
  try {
    const compression = require("compression");
    const NO_COMPRESS = /^\/(?:api\/file\/proxy|api\/playlist|api\/app\/download)(?:\/|$)/;
    app.use(
      compression({
        threshold: 1024,
        filter: (req, res) => !NO_COMPRESS.test(req.path) && !req.headers.range && compression.filter(req, res)
      })
    );
  } catch {
    console.warn("Модуль compression не установлен (npm install): ответы отдаются без сжатия.");
  }

  // Something changed through the site/apps: the short library cache is dropped
  const LIBRARY_CHANGES = /^\/api\/(?:magnets|torrents|search\/add)(?:\/|$)/;
  app.use((req, res, next) => {
    if (req.method !== "GET" && req.method !== "HEAD" && LIBRARY_CHANGES.test(req.path)) {
      res.on("finish", () => {
        if (res.statusCode < 400) libraryChanged();
      });
    }
    next();
  });

  // Body parsers
  app.use("/api/playlist", express.json({ limit: "200kb" }));
  app.use("/api/app/changelog", express.json({ limit: "200kb" }));
  app.use("/api/sync", express.json({ limit: "512kb" })); // первая выгрузка прогресса — до 500 записей
  app.use(express.json({ limit: "20kb" }));
  app.use(express.urlencoded({ extended: false, limit: "20kb" }));

  // Изменяющие запросы с cookie — только со страниц самого сайта
  app.use(sameOriginWrites);

  // Sessions
  app.use(createSessionMiddleware());

  // Mount API routes
  app.use(authRoutes);
  app.use(libraryRoutes);
  app.use(favoritesRoutes);
  app.use(magnetsRoutes);
  app.use(searchRoutes);
  app.use(mirrorRoutes);
  app.use(updatesRoutes);
  app.use(mediaRoutes);
  app.use(roomsRoutes);
  app.use(syncRoutes);
  app.use(tagsRoutes);
  app.use(tvRoutes);
  app.use(customAudioRoutes);
  app.use(aiTitleRoutes);
  app.use(markersRoutes);

  // Static files
  app.use(
    express.static(config.PUBLIC_DIR, {
      index: "index.html",
      dotfiles: "deny",
      setHeaders: (res, filePath) => {
        if (/\.html?$/i.test(filePath)) {
          res.setHeader("Cache-Control", "no-cache");
          const page = precompressed("index.html");
          if (page && page.csp) res.setHeader("Content-Security-Policy", page.csp);
        } else if (
          /\.woff2$/i.test(filePath) ||
          (/\.(?:css|js)$/i.test(filePath) && res.req && res.req.query && res.req.query.v)
        ) {
          res.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        }
      }
    })
  );

  // SPA fallback
  app.get("*path", (req, res, next) => {
    if (req.path.startsWith("/api/")) return res.status(404).json({ error: "Не найдено." });
    if (sendPrecompressed(req, res, "index.html")) return;
    const page = precompressed("index.html");
    if (page && page.csp) res.setHeader("Content-Security-Policy", page.csp);
    res.sendFile(path.join(config.PUBLIC_DIR, "index.html"), err => {
      if (err) next(err);
    });
  });

  // Error handling middleware
  app.use((err, req, res, next) => {
    if (res.headersSent) {
      console.error("Ошибка после начала ответа:", err.message);
      return res.destroy();
    }
    if (err && err.type === "entity.too.large") {
      return res.status(413).json({
        error: req.path === "/api/torrents" ? "Торрент-файл должен быть не больше 1 МБ." : "Слишком большой запрос."
      });
    }
    if (err && err.type === "entity.parse.failed") {
      return res.status(400).json({ error: "Некорректный запрос: тело не является JSON." });
    }
    if (err && err.status >= 400 && err.status < 500 && err.expose) {
      return res.status(err.status).json({ error: "Некорректный запрос." });
    }
    console.error("Ошибка запроса:", err.message);
    res.status(err.status || 500).json({ error: "Непредвиденная ошибка сервера." });
  });

  return app;
}

module.exports = { createApp };
