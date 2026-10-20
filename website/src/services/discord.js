"use strict";

const axios = require("axios");
const config = require("../config");

const DISCORD_API_BASE = "https://discord.com/api/v10";

function getAvatarUrl(user) {
  if (!user || !user.id) return "";
  if (user.avatar) {
    const ext = user.avatar.startsWith("a_") ? "gif" : "png";
    return `https://cdn.discordapp.com/avatars/${user.id}/${user.avatar}.${ext}?size=128`;
  }
  try {
    const idx = Number((BigInt(user.id) >> 22n) % 6n);
    return `https://cdn.discordapp.com/embed/avatars/${idx}.png`;
  } catch {
    return "https://cdn.discordapp.com/embed/avatars/0.png";
  }
}

async function exchangeOAuthCode(code, redirectUri) {
  if (!config.DISCORD_CLIENT_ID || !config.DISCORD_CLIENT_SECRET) {
    throw new Error("Discord OAuth2 не настроен на сервере (отсутствуют CLIENT_ID / CLIENT_SECRET).");
  }

  const params = new URLSearchParams({
    client_id: config.DISCORD_CLIENT_ID,
    client_secret: config.DISCORD_CLIENT_SECRET,
    grant_type: "authorization_code",
    code: String(code),
    redirect_uri: redirectUri
  });

  const res = await axios.post(`${DISCORD_API_BASE}/oauth2/token`, params.toString(), {
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    timeout: 10000
  });

  return res.data;
}

async function fetchMe(accessToken) {
  const res = await axios.get(`${DISCORD_API_BASE}/users/@me`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    timeout: 10000
  });
  return res.data;
}

async function revokeOAuthToken(accessToken) {
  if (!accessToken || !config.DISCORD_CLIENT_ID || !config.DISCORD_CLIENT_SECRET) return;
  try {
    const params = new URLSearchParams({
      client_id: config.DISCORD_CLIENT_ID,
      client_secret: config.DISCORD_CLIENT_SECRET,
      token: accessToken,
      token_type_hint: "access_token"
    });
    await axios.post(`${DISCORD_API_BASE}/oauth2/token/revoke`, params.toString(), {
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      timeout: 5000
    });
  } catch (err) {
    // Revocation failure is non-fatal
    console.warn("Не удалось отозвать временный токен Discord:", err.message);
  }
}

async function memberRoles(idStr) {
  const res = await axios.get(`${DISCORD_API_BASE}/guilds/${config.DISCORD_GUILD_ID}/members/${idStr}`, {
    headers: { Authorization: `Bot ${config.DISCORD_BOT_TOKEN}` },
    timeout: 8000
  });
  return Array.isArray(res.data?.roles) ? res.data.roles : [];
}

async function checkGuildMember(discordId) {
  const idStr = String(discordId);

  // 1. Владелец проекта всегда админ
  if (config.DISCORD_OWNER_ID && config.DISCORD_OWNER_ID === idStr) {
    return { allowed: true, role: "admin" };
  }

  // 2. Белый список пользователей: пускаем всегда. DISCORD_ROLE_ADMIN — id ролей, не пользователей: админ ли он,
  // узнаём у бота по ролям на сервере Discord (если бот не ответил или не настроен — обычный доступ)
  if (config.DISCORD_ALLOWED_USERS.includes(idStr)) {
    if (config.DISCORD_BOT_TOKEN && config.DISCORD_GUILD_ID && config.DISCORD_ROLE_ADMIN.length > 0) {
      try {
        const roles = await memberRoles(idStr);
        if (config.DISCORD_ROLE_ADMIN.some(r => roles.includes(r))) return { allowed: true, role: "admin" };
      } catch {}
    }
    return { allowed: true, role: "allow" };
  }

  // 3. Проверка через бота на сервере Discord
  if (config.DISCORD_BOT_TOKEN && config.DISCORD_GUILD_ID) {
    try {
      const res = await axios.get(
        `${DISCORD_API_BASE}/guilds/${config.DISCORD_GUILD_ID}/members/${idStr}`,
        {
          headers: { Authorization: `Bot ${config.DISCORD_BOT_TOKEN}` },
          timeout: 8000
        }
      );
      const member = res.data;
      const roles = Array.isArray(member.roles) ? member.roles : [];

      const isAdmin = config.DISCORD_ROLE_ADMIN.some(r => roles.includes(r));
      if (isAdmin) return { allowed: true, role: "admin", member };

      // Если список разрешённых ролей не задан — пускаем всех участников гильдии
      if (config.DISCORD_ROLE_ALLOW.length === 0 || config.DISCORD_ROLE_ALLOW.some(r => roles.includes(r))) {
        return { allowed: true, role: "allow", member };
      }

      return { allowed: false, reason: "no_role", member };
    } catch (err) {
      if (err.response && err.response.status === 404) {
        return { allowed: false, reason: "not_in_guild" };
      }
      if (err.response && err.response.status === 429) {
        console.warn("Discord API rate-limit при проверке участника:", err.response.data);
      }
      // При сетевом сбое Discord (5xx/timeout) не блокируем наглухо — выбрасываем ошибку для обработки выше
      throw err;
    }
  }

  // 4. Если бот/гильдия не настроены, но белый список задан — не пускаем тех, кто не в белом списке
  if (config.DISCORD_ALLOWED_USERS.length > 0) {
    return { allowed: false, reason: "not_in_whitelist" };
  }

  // 5. Открытый режим (если ни бот, ни белый список не заданы, но клиент включён)
  return { allowed: true, role: "allow" };
}

module.exports = {
  getAvatarUrl,
  exchangeOAuthCode,
  fetchMe,
  revokeOAuthToken,
  checkGuildMember
};
