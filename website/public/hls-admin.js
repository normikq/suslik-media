/* Админка HLS (docs/HLS-CLIENT.md, T6): очередь кодирования, готовые видео, место на CDN.
   Данные — GET api/hls/admin/state, действия — POST api/hls/admin/{retry,reencode,delete}.
   Внешний скрипт без инлайна (CSP сайта: script-src 'self'). Чистые функции экспортируются для тестов в Node. */
(function () {
  "use strict";

  const REFRESH_MS = 10000;

  // ---- Чистые функции (без DOM) ----

  function plural(n, one, few, many) {
    const a = Math.abs(n) % 100;
    const b = a % 10;
    if (a > 10 && a < 20) return many;
    if (b === 1) return one;
    if (b >= 2 && b <= 4) return few;
    return many;
  }

  /** Размер в байтах: «512 Б», «1,5 ГБ», «120 МБ». Нет числа → «—». */
  function formatBytes(n) {
    const v = Number(n);
    if (n === null || n === undefined || n === "" || !Number.isFinite(v) || v < 0) return "—";
    const units = ["Б", "КБ", "МБ", "ГБ", "ТБ"];
    let x = v;
    let i = 0;
    while (x >= 1024 && i < units.length - 1) {
      x /= 1024;
      i++;
    }
    const text = i === 0 || x >= 100 ? String(Math.round(x)) : x.toFixed(1).replace(/\.0$/, "").replace(".", ",");
    return `${text} ${units[i]}`;
  }

  /** Сколько осталось (секунды): «меньше минуты», «12 мин», «1 ч 5 мин». Неизвестно → «—». */
  function formatEta(s) {
    const v = Number(s);
    if (s === null || s === undefined || s === "" || !Number.isFinite(v) || v < 0) return "—";
    if (v < 60) return "меньше минуты";
    const min = Math.round(v / 60);
    if (min < 60) return `${min} мин`;
    const h = Math.floor(min / 60);
    const m = min % 60;
    return m ? `${h} ч ${m} мин` : `${h} ч`;
  }

  /** Длительность фильма (мс): «1 ч 52 мин», «45 мин». */
  function formatDuration(ms) {
    const v = Number(ms);
    if (!ms || !Number.isFinite(v) || v <= 0) return "—";
    return formatEta(v / 1000);
  }

  /** Как давно (unix-секунды относительно now): «только что», «5 мин назад», «3 ч назад», «2 дня назад». */
  function formatAgo(at, now) {
    const t = Number(at);
    if (!at || !Number.isFinite(t)) return "никогда";
    const d = Math.max(0, Math.round(Number(now) - t));
    if (d < 60) return "только что";
    if (d < 3600) return `${Math.floor(d / 60)} мин назад`;
    if (d < 86400) return `${Math.floor(d / 3600)} ч назад`;
    const days = Math.floor(d / 86400);
    return `${days} ${plural(days, "день", "дня", "дней")} назад`;
  }

  /** Доля used/total в процентах (целое) или null. */
  function percent(used, total) {
    const u = Number(used);
    const t = Number(total);
    if (!Number.isFinite(u) || !Number.isFinite(t) || t <= 0 || u < 0) return null;
    return Math.min(100, Math.round((u / t) * 100));
  }

  /** Прогресс задания 0..1 → целые проценты 0..100. */
  function progressPercent(p) {
    const v = Number(p);
    if (!Number.isFinite(v) || v <= 0) return 0;
    return Math.min(100, Math.round(v * 100));
  }

  /** Последняя часть пути файла. */
  function fileName(path) {
    const s = String(path || "");
    return s.split(/[\\/]/).filter(Boolean).pop() || s || "—";
  }

  const STATES = { queued: "В очереди", running: "Кодируется", failed: "Ошибка" };
  const STAGES = {
    mirror: "получение ссылки",
    probe: "разбор файла",
    fingerprint: "проверка содержимого",
    wait: "ждёт такой же файл",
    encode: "кодирование",
    verify: "проверка",
    upload: "загрузка на CDN",
    done: "готово"
  };
  const PRIORITIES = ["Смотрят сейчас", "Следующие серии", "Новая раздача", "Остальное", "Улучшение качества"];
  const PROFILE_STATUS = { ready: "готово", encoding: "кодируется" };

  const stateText = s => STATES[s] || String(s || "—");
  const stageText = s => (s ? STAGES[s] || String(s) : "");
  const priorityText = p => PRIORITIES[Number(p)] || `Приоритет ${p}`;
  const profileStatusText = s => PROFILE_STATUS[s] || String(s || "—");

  /** Понятный текст ошибки по HTTP-коду и телу {error, code}. status 0 — нет связи. */
  function errorText(status, body) {
    const code = body && typeof body === "object" ? body.code : null;
    const msg = body && typeof body === "object" && typeof body.error === "string" ? body.error : "";
    if (!status) return "Нет связи с сервером.";
    if (status === 401) return "Нужно войти на сайт.";
    if (status === 403) return code === "forbidden" || !msg ? "Нет доступа." : msg;
    if (status === 409 || code === "busy") return "Задание сейчас в работе. Попробуйте позже.";
    if (code === "unknown_job") return "Задания уже нет.";
    if (code === "unknown_asset") return "Запись уже удалена.";
    if (code === "unknown_profile") return "Этой версии уже нет.";
    if (code === "unknown_file") return "Файла больше нет в раздаче.";
    if (code === "not_configured" || status === 503) return "HLS на сервере не настроен.";
    if (status === 429) return "Слишком много запросов. Подождите минуту.";
    if (status >= 500) return "Ошибка сервера. Попробуйте позже.";
    return msg || `Ошибка ${status}.`;
  }

  /** Что не задано в .env (по полю configured). */
  function missingConfig(configured) {
    const names = { cdn_url: "HLS_CDN_URL", sign_secret: "HLS_SIGN_SECRET", worker_token: "HLS_WORKER_TOKEN", cdn_token: "HLS_CDN_TOKEN" };
    const c = configured || {};
    return Object.keys(names).filter(k => !c[k]).map(k => names[k]);
  }

  const api = { formatBytes, formatEta, formatDuration, formatAgo, percent, progressPercent, fileName, stateText, stageText, priorityText, profileStatusText, errorText, missingConfig };

  if (typeof module === "object" && module && module.exports) {
    module.exports = api;
    return;
  }
  if (typeof document === "undefined") return;

  // ---- Страница ----

  const $ = id => document.getElementById(id);
  let timer = null;
  let loading = false;
  let lastOk = 0;
  let toastTimer = null;

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = String(text);
    return e;
  }

  function toast(text, isError) {
    const t = $("toast");
    t.textContent = text;
    t.className = isError ? "error" : "";
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.add("hidden"), 5000);
  }

  async function request(method, path, body) {
    let res;
    try {
      res = await fetch(path, {
        method,
        credentials: "same-origin",
        cache: "no-store",
        headers: body ? { "Content-Type": "application/json" } : {},
        body: body ? JSON.stringify(body) : undefined
      });
    } catch {
      return { status: 0, body: null };
    }
    let data = null;
    try {
      data = await res.json();
    } catch {}
    return { status: res.status, body: data };
  }

  function showAuth(status) {
    $("app").classList.add("hidden");
    $("loading").classList.add("hidden");
    $("authPanel").classList.remove("hidden");
    if (status === 401) {
      $("authText").textContent = "Чтобы открыть эту страницу, войдите через Discord как администратор.";
      $("loginLink").href = "api/auth/discord/start?return=" + encodeURIComponent(location.pathname);
      $("authLink").classList.remove("hidden");
    } else {
      $("authText").textContent = "Нет доступа: страница только для администратора медиатеки.";
      $("authLink").classList.add("hidden");
    }
  }

  async function load() {
    if (loading) return;
    loading = true;
    $("refresh").disabled = true;
    const r = await request("GET", "api/hls/admin/state");
    loading = false;
    $("refresh").disabled = false;
    if (r.status === 401 || r.status === 403) {
      stop();
      showAuth(r.status);
      return;
    }
    if (r.status !== 200 || !r.body) {
      $("loading").classList.add("hidden");
      const box = $("loadError");
      box.textContent = "Не удалось обновить: " + errorText(r.status, r.body) + (lastOk ? " Показаны прежние данные." : "");
      box.classList.remove("hidden");
      return;
    }
    lastOk = Date.now();
    $("loadError").classList.add("hidden");
    $("authPanel").classList.add("hidden");
    $("loading").classList.add("hidden");
    $("app").classList.remove("hidden");
    render(r.body);
    $("updated").textContent = "Обновлено в " + new Date().toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
  }

  async function act(button, path, body, okText) {
    button.disabled = true;
    const r = await request("POST", path, body);
    button.disabled = false;
    if (r.status === 200) {
      toast(okText);
      load();
    } else if (r.status === 401) {
      showAuth(401);
    } else {
      toast(errorText(r.status, r.body), true);
    }
  }

  function render(s) {
    const now = s.now || Math.floor(Date.now() / 1000);

    // Состояние
    $("enabledText").textContent = s.enabled ? "HLS включён." : "HLS выключен (HLS_ENABLED в .env). Сайт и приложения играют как раньше.";
    $("enabledText").className = s.enabled ? "ok" : "warn";
    const miss = missingConfig(s.configured);
    const cn = $("configNotice");
    if (miss.length) {
      cn.textContent = "Не задано в .env: " + miss.join(", ") + ".";
      cn.classList.remove("hidden");
    } else {
      cn.classList.add("hidden");
    }
    const t = s.totals || {};
    const totals = $("totals");
    totals.replaceChildren();
    for (const [value, label] of [
      [t.queued, "в очереди"],
      [t.running, "кодируется"],
      [t.failed, "с ошибкой"],
      [t.ready, "готово"],
      [t.assets, "всего записей"],
      [formatBytes(t.bytes), "занимают"]
    ]) {
      const box = el("div", "stat");
      box.append(el("b", null, value ?? 0), el("span", null, label));
      totals.append(box);
    }

    // CDN
    const cdn = $("cdn");
    cdn.replaceChildren();
    if (!s.cdn) {
      $("cdnAt").textContent = "";
      cdn.append(el("p", "muted", "CDN ещё не присылал данных."));
    } else {
      const c = s.cdn;
      const p = percent(c.disk_used, c.disk_total);
      $("cdnAt").textContent = "обновлено " + formatAgo(c.received_at || c.at, now);
      cdn.append(el("div", null, `Занято ${formatBytes(c.disk_used)} из ${formatBytes(c.disk_total)}` + (p === null ? "" : ` (${p}%)`)));
      const bar = el("div", "bar" + (p !== null && p >= 90 ? " full" : ""));
      const fill = el("div");
      fill.style.width = (p || 0) + "%";
      bar.append(fill);
      cdn.append(bar);
      const parts = [];
      if (c.assets !== null && c.assets !== undefined) parts.push(`видео: ${c.assets}`);
      if (c.profiles !== null && c.profiles !== undefined) parts.push(`версий: ${c.profiles}`);
      if (parts.length) cdn.append(el("div", "muted small", parts.join(", ")));
    }

    // Очередь
    const queue = Array.isArray(s.queue) ? s.queue : [];
    $("queueCount").textContent = queue.length ? String(queue.length) : "";
    const qBox = $("queue");
    qBox.replaceChildren();
    if (!queue.length) qBox.append(el("p", "muted", "Очередь пуста."));
    for (const j of queue) qBox.append(renderJob(j, now));

    // Ассеты
    const assets = Array.isArray(s.assets) ? s.assets : [];
    $("assetsCount").textContent = assets.length ? String(assets.length) : "";
    const aBox = $("assets");
    aBox.replaceChildren();
    if (!assets.length) aBox.append(el("p", "muted", "Пока ничего нет."));
    for (const a of assets) aBox.append(renderAsset(a, now));
  }

  function renderJob(j, now) {
    const item = el("div", "item");
    const head = el("div", "item-head");
    const left = el("div");
    left.style.minWidth = "0";
    left.append(el("div", "item-title", fileName(j.path)));
    left.append(el("div", "item-meta", `Раздача ${j.ad_id} · ${j.path}`));
    head.append(left);
    const tags = el("div");
    tags.append(el("span", "tag " + (j.state || ""), stateText(j.state)));
    tags.append(el("span", "tag", priorityText(j.priority)));
    if (j.profile) tags.append(el("span", "tag", j.profile));
    if (j.force) tags.append(el("span", "tag pending", "перекодирование"));
    head.append(tags);
    item.append(head);

    const meta = [];
    if (j.state === "running") {
      const stage = stageText(j.stage);
      if (stage) meta.push(stage);
      meta.push(`${progressPercent(j.progress)}%`);
      meta.push(`осталось ${formatEta(j.eta_s)}`);
    }
    if (j.size) meta.push(formatBytes(j.size));
    if (j.attempts) meta.push(`попыток: ${j.attempts}`);
    if (j.created_at) meta.push(`поставлено ${formatAgo(j.created_at, now)}`);
    item.append(el("div", "item-meta", meta.join(" · ")));
    if (j.state === "running") {
      const pr = el("div", "progress");
      const fill = el("div");
      fill.style.width = progressPercent(j.progress) + "%";
      pr.append(fill);
      item.append(pr);
    }
    if (j.error && j.error.error) {
      const st = stageText(j.error.stage);
      item.append(el("div", "item-meta error", "Ошибка" + (st ? ` (${st})` : "") + ": " + j.error.error));
    }
    if (j.state === "failed") {
      const actions = el("div", "item-actions");
      const b = el("button", "primary", "Повторить");
      b.type = "button";
      b.addEventListener("click", () => act(b, "api/hls/admin/retry", { job_id: j.job_id }, "Задание снова в очереди."));
      actions.append(b);
      item.append(actions);
    }
    return item;
  }

  function renderAsset(a, now) {
    const item = el("div", "item");
    const src = a.source || {};
    const pending = Array.isArray(a.pending_delete) ? a.pending_delete : [];
    const head = el("div", "item-head");
    const left = el("div");
    left.style.minWidth = "0";
    left.append(el("div", "item-title", fileName(src.path)));
    const meta = [`id ${a.hls_id}`];
    if (a.duration_ms) meta.push(formatDuration(a.duration_ms));
    if (a.episode) meta.push(`серия ${a.episode}`);
    if (a.edition) meta.push(a.edition);
    meta.push(`занимает ${formatBytes(a.bytes)}`);
    meta.push(`смотрели ${formatAgo(a.last_access, now)}`);
    left.append(el("div", "item-meta", meta.join(" · ")));
    head.append(left);
    const tags = el("div");
    tags.append(el("span", "tag" + (a.active_profile ? " ready" : ""), a.active_profile ? `играет ${a.active_profile}` : "не играет"));
    head.append(tags);
    item.append(head);

    if (a.error && a.error.error) item.append(el("div", "item-meta error", "Ошибка: " + a.error.error));

    // Профили
    const profiles = el("div", "profiles");
    for (const [name, p] of Object.entries(a.profiles || {})) {
      const row = el("div", "profile");
      const info = el("div");
      info.append(el("b", null, name + " "));
      info.append(el("span", "tag " + (p.status === "ready" ? "ready" : "running"), profileStatusText(p.status)));
      if (pending.includes(name)) info.append(el("span", "tag pending", "ждёт удаления на CDN"));
      const bits = [formatBytes(p.bytes)];
      if (p.encoder) bits.push(p.encoder);
      if (p.created_at) bits.push(formatAgo(p.created_at, now));
      info.append(el("span", "muted", " " + bits.join(" · ")));
      row.append(info);
      if (!pending.includes(name)) {
        const del = el("button", "danger", "Удалить");
        del.type = "button";
        del.addEventListener("click", () => {
          if (!window.confirm(`Удалить версию ${name} у «${fileName(src.path)}»? Файлы удалит CDN, запись исчезнет после этого.`)) return;
          act(del, "api/hls/admin/delete", { hls_id: a.hls_id, profile: name }, "Версия ждёт удаления на CDN.");
        });
        row.append(del);
      }
      profiles.append(row);
    }
    if (!Object.keys(a.profiles || {}).length) profiles.append(el("div", "muted small", "Версий нет."));
    item.append(profiles);

    // Файлы: источник и алиасы, с каждого можно перекодировать
    const files = el("ul", "files");
    const seen = new Set();
    const rows = [];
    if (src.ad_id && src.path) rows.push({ ad_id: src.ad_id, path: src.path, label: "источник" });
    for (const al of Array.isArray(a.aliases) ? a.aliases : []) {
      rows.push({ ad_id: al.ad_id, path: al.path, label: al.shift_ms ? `сдвиг ${(al.shift_ms / 1000).toFixed(1).replace(".", ",")} с` : "" });
    }
    for (const f of rows) {
      const key = f.ad_id + "|" + f.path;
      if (seen.has(key)) continue;
      seen.add(key);
      const li = el("li");
      li.append(el("span", null, `Раздача ${f.ad_id} · ${f.path}` + (f.label ? ` (${f.label})` : "")));
      const b = el("button", null, "Перекодировать с этой раздачи");
      b.type = "button";
      b.addEventListener("click", () => {
        if (!window.confirm(`Перекодировать заново из «${fileName(f.path)}»? Пока идёт кодирование, играет старая версия.`)) return;
        act(b, "api/hls/admin/reencode", { ad_id: Number(f.ad_id), path: f.path, hls_id: a.hls_id }, "Поставлено в очередь первым.");
      });
      li.append(b);
      files.append(li);
    }
    if (rows.length) item.append(files);

    const names = Object.keys(a.profiles || {});
    const live = names.filter(n => !pending.includes(n));
    if (live.length > 1 || !names.length) {
      const actions = el("div", "item-actions");
      const delAll = el("button", "danger", names.length ? "Удалить всё" : "Удалить запись");
      delAll.type = "button";
      delAll.addEventListener("click", () => {
        if (!window.confirm(`Удалить все версии «${fileName(src.path)}»? Файлы удалит CDN, запись исчезнет после этого.`)) return;
        act(delAll, "api/hls/admin/delete", { hls_id: a.hls_id }, "Ждёт удаления на CDN.");
      });
      actions.append(delAll);
      item.append(actions);
    }
    return item;
  }

  function start() {
    stop();
    timer = setInterval(load, REFRESH_MS);
  }

  function stop() {
    if (timer) clearInterval(timer);
    timer = null;
  }

  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      stop();
    } else if ($("authPanel").classList.contains("hidden")) {
      load();
      start();
    }
  });
  $("refresh").addEventListener("click", () => load());

  load();
  if (!document.hidden) start();
})();
