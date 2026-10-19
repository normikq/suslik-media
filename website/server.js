"use strict";

require("dotenv").config();

const config = require("./src/config");
const { createApp } = require("./src/app");

const app = createApp();

const server = app.listen(config.PORT, "127.0.0.1", () => {
  console.log(`Медиапортал запущен: http://127.0.0.1:${config.PORT}`);
  console.log("Для публикации используйте HTTPS-обратный прокси-сервер.");
  if (config.MIRROR_ACCEL) console.log("Видео через nginx (MIRROR_ACCEL) включено — см. NGINX-MIRROR.md");
});

// Longer than the reverse proxy's upstream keep-alive (nginx default 60 s): avoids sporadic 502 when the proxy
// reuses a connection that Node has just closed — which showed up as failed seeks/reconnects.
server.keepAliveTimeout = 75_000;
server.headersTimeout = 80_000;

module.exports = { app, server };
