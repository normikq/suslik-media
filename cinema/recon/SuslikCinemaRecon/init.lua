-- Suslik Cinema — разведка (этап 0). Только читает состояние игры и пишет журнал recon.log в папке мода.
-- Ничего в игре не меняет: не трогает сохранения, настройки, объекты. Рисует поверх игры только линии ImGui.
--
-- Что пишет:
--   * версии игры и CET, разрешение, какие функции API доступны;
--   * снимок камеры: позиция, направления, FOV из нескольких источников, объект под прицелом, точка попадания луча;
--   * 4 угла экрана телевизора (луч из камеры в точку прицела) -> corners.txt;
--   * запись 10 с камеры по кадрам (частота, задержки);
--   * рамку по углам, посчитанную нашей формулой проекции: совпадает ли она с телевизором.
--
-- Надписи в игре — латиницей: шрифт CET по умолчанию без кириллицы.

local LOG_FILE = "recon.log"
local CORNERS_FILE = "corners.txt"
local RECORD_SECONDS = 10

local R = {
    api = {},          -- имя -> "ok" / текст ошибки
    corners = {},      -- до 4 точек {x,y,z}
    nextCorner = 1,
    hud = false,       -- рамка и прицел поверх игры
    -- game — проекция самой игры (CameraSystem:ProjectPoint): без угадывания FOV, как в TVKit
    fovModes = { "game", "vertical", "horizontal", "horizontal_16x9" },
    fovMode = 1,
    fovSources = { "component", "settings", "manual" },
    fovSource = 1,
    fovManual = 70.0,
    fovScale = 1.0,    -- подстройка, если рамка «дышит» относительно телевизора
    recording = false,
    hist = {},         -- положения камеры за последние 0,5 с (onUpdate): рамка по камере «N мс назад»
    delayMs = 0,       -- на сколько отстаёт картинка игры от камеры (генерация кадров, очередь кадров)
    -- экран центром: точка на стекле, нормаль, размер и сдвиги (как у TVKit); углы считаются из них
    screen = nil,      -- { c, n, w, h, sr, su, sn }
    -- упреждение по повороту камеры: frames — сдвиг экранных углов (Lag frames), pose — поворот камеры
    -- вперёд на leadMs с затуханием при остановке и перепроекция с фокусом, подогнанным под ProjectPoint
    predModes = { "pose", "frames" },
    predMode = 1,
    leadMs = 15,
    pose = { yaw = nil, pitch = nil, wy = 0, wp = 0, still = 0, t = nil },
    lagFrames = -1,    -- сдвиг рамки в кадрах по экранным углам: >0 — рамка из прошлых кадров, <0 — упреждение
    qhist = {},        -- экранные углы последних кадров (для lagFrames)
    draws = 0, updates = 0, rateT = 0, drawRate = 0, updateRate = 0,
    recordLeft = 0,
    recordFrames = 0,
    lastSnap = nil,
    status = "",
    overlayOpen = false,
}

-- ---------- журнал ----------

local function now()
    local ok, s = pcall(os.date, "%Y-%m-%d %H:%M:%S")
    local c = 0
    pcall(function() c = os.clock() end)
    return (ok and s or "?") .. string.format(" +%.3f", c)
end

local function log(line)
    local f = io.open(LOG_FILE, "a")
    if f then
        f:write(now(), " ", line, "\n")
        f:close()
    end
    print("[SuslikRecon] " .. line)
end

local function fmt(v)
    if v == nil then return "nil" end
    if type(v) == "number" then return string.format("%.4f", v) end
    return tostring(v)
end

-- Вызов с записью, доступна ли функция (первый результат хранится в R.api).
local function try(name, fn)
    local ok, a, b, c = pcall(fn)
    if R.api[name] == nil then R.api[name] = ok and (a ~= nil and "ok" or "ok, nil") or ("ERR " .. tostring(a)) end
    if ok then return a, b, c end
    return nil
end

-- ---------- векторы ----------

local function vec(v)
    if v == nil then return nil end
    local ok, r = pcall(function() return { x = v.x + 0.0, y = v.y + 0.0, z = v.z + 0.0 } end)
    if ok then return r end
    ok, r = pcall(function()
        local w = GetSingleton("WorldPosition"):ToVector4(v)
        return { x = w.x, y = w.y, z = w.z }
    end)
    if ok then return r end
    return nil
end

local function vstr(v)
    if not v then return "nil" end
    return string.format("(%.4f, %.4f, %.4f)", v.x, v.y, v.z)
end

local function dot(a, b) return a.x * b.x + a.y * b.y + a.z * b.z end
local function sub(a, b) return { x = a.x - b.x, y = a.y - b.y, z = a.z - b.z } end
local function add(a, b) return { x = a.x + b.x, y = a.y + b.y, z = a.z + b.z } end
local function mul(a, k) return { x = a.x * k, y = a.y * k, z = a.z * k } end
local function cross(a, b)
    return { x = a.y * b.z - a.z * b.y, y = a.z * b.x - a.x * b.z, z = a.x * b.y - a.y * b.x }
end
local function norm(a)
    local l = math.sqrt(dot(a, a))
    if l < 1e-9 then return a end
    return mul(a, 1 / l)
end

-- ---------- чтение состояния игры ----------

local function screenSize()
    local w, h = try("GetDisplayResolution", function() return GetDisplayResolution() end)
    if not w or w <= 0 then
        w, h = try("ImGui.GetDisplayResolution", function() return ImGui.GetDisplayResolution() end)
    end
    return w or 0, h or 0
end

local function fovNow()
    local player = Game.GetPlayer()
    local comp = try("FPPCamera.GetFOV", function() return player:GetFPPCameraComponent():GetFOV() end)
    local zoom = try("FPPCamera.GetZoom", function() return player:GetFPPCameraComponent():GetZoom() end)
    local settings = try("Settings.FieldOfView", function()
        return Game.GetSettingsSystem():GetVar("/graphics/basic", "FieldOfView"):GetValue()
    end)
    return comp, settings, zoom
end

local function nonZero(v)
    return v and (math.abs(v.x) + math.abs(v.y) + math.abs(v.z)) > 0.001
end

-- Из объекта Transform (или чего-то с .position) — позиция, если она не нулевая.
local function posOf(obj)
    if obj == nil or type(obj) == "boolean" or type(obj) == "number" then return nil end
    local ok, p = pcall(function() return obj.position end)
    if not ok or p == nil then return nil end
    local v = vec(p)
    if nonZero(v) then return v end
    return nil
end

-- Позиция глаз (камеры) разными способами. GetActiveCameraWorldTransform в CET 1.38 требует out-параметр.
-- Возвращает список {name, pos}: в снимке пишутся все, для расчёта берётся первый.
local function eyeCandidates(cs)
    local out = {}
    local function put(name, v) if nonZero(v) then out[#out + 1] = { name = name, pos = v } end end
    put("WorldTransform(out)", try("CamPos.WorldTransformOut", function()
        local t = Transform.new()
        local r1, r2 = cs:GetActiveCameraWorldTransform(t)
        return posOf(r2) or posOf(r1) or posOf(t)
    end))
    put("FPP.GetLocalToWorld", try("CamPos.FPPLocalToWorld", function()
        local m = Game.GetPlayer():GetFPPCameraComponent():GetLocalToWorld()
        local okA, a = pcall(function() return m:GetTranslation() end)
        if okA and a then return vec(a) end
        return vec(Matrix.GetTranslation(m))
    end))
    put("FPP.GetLocalPosition+player", try("CamPos.FPPLocalPosition", function()
        local p = vec(Game.GetPlayer():GetWorldPosition())
        local l = vec(Game.GetPlayer():GetFPPCameraComponent():GetLocalPosition())
        if not (p and l) then return nil end
        -- локальное смещение камеры почти всегда вертикальное (высота глаз); поворот игрока не учитываем
        return { x = p.x + l.x, y = p.y + l.y, z = p.z + l.z }
    end))
    return out
end

local function camera()
    local cs = Game.GetCameraSystem()
    local c = {}
    c.eyes = eyeCandidates(cs)
    if c.eyes[1] then
        c.pos = c.eyes[1].pos
        c.posSource = c.eyes[1].name
    end
    c.f = vec(try("Camera.GetActiveCameraForward", function() return cs:GetActiveCameraForward() end))
    c.r = vec(try("Camera.GetActiveCameraRight", function() return cs:GetActiveCameraRight() end))
    c.u = vec(try("Camera.GetActiveCameraUp", function() return cs:GetActiveCameraUp() end))
    if not c.pos then
        -- запасной путь: ноги игрока + примерная высота глаз стоя. Неточно: только чтобы было с чем сравнить
        local p = vec(try("Player.GetWorldPosition", function() return Game.GetPlayer():GetWorldPosition() end))
        if p then c.pos = { x = p.x, y = p.y, z = p.z + 1.7 } end
        c.posFromPlayer = true
        c.posSource = "player+1.7"
    end
    if c.f and (not c.r or not c.u) then
        c.r = norm(cross(c.f, { x = 0, y = 0, z = 1 }))
        c.u = cross(c.r, c.f)
        c.derivedAxes = true
    end
    return c
end

local function fovUsed(comp, settings)
    local src = R.fovSources[R.fovSource]
    local v = (src == "component" and comp) or (src == "settings" and settings) or nil
    if not v or v <= 0 then v = R.fovManual end
    return v * R.fovScale
end

-- Точка мира -> пиксели окна. nil — точка за камерой.
-- mode "game": проекция игры (камера текущего кадра, её FOV и соотношение сторон); остальные — своя камера-обскура.
local function project(p, c, W, H, fovDeg, mode)
    if not (c.pos and c.f and c.r and c.u) or W <= 0 or H <= 0 then return nil end
    local d = sub(p, c.pos)
    local xc, yc, zc = dot(d, c.r), dot(d, c.u), dot(d, c.f)
    if zc < 0.05 then return nil end
    if mode == "game" then
        local q = try("Camera.ProjectPoint", function()
            return Game.GetCameraSystem():ProjectPoint(Vector4.new(p.x, p.y, p.z, 1))
        end)
        if not q then return nil end
        return (q.x + 1) * W / 2, (1 - q.y) * H / 2, zc
    end
    local aspect = W / H
    local t = math.tan(math.rad(fovDeg) / 2)
    local tx, ty
    if mode == "vertical" then
        ty = t; tx = t * aspect
    elseif mode == "horizontal" then
        tx = t; ty = t / aspect
    else -- горизонтальный FOV, заданный для 16:9 (Hor+)
        ty = t / (16 / 9); tx = ty * aspect
    end
    return (1 + xc / (zc * tx)) * W / 2, (1 - yc / (zc * ty)) * H / 2, zc
end

local function className(obj)
    return try("Object.GetClassName", function()
        local cn = obj:GetClassName()
        local ok, s = pcall(function() return Game.NameToString(cn) end)
        if ok and s then return s end
        return tostring(cn.value or cn)
    end)
end

local function lookAt()
    local player = Game.GetPlayer()
    local obj = try("Targeting.GetLookAtObject", function()
        return Game.GetTargetingSystem():GetLookAtObject(player, false, false)
    end)
    if not obj then return nil end
    local o = { cls = className(obj) }
    o.pos = vec(try("Object.GetWorldPosition", function() return obj:GetWorldPosition() end))
    o.fwd = vec(try("Object.GetWorldForward", function() return obj:GetWorldForward() end))
    o.right = vec(try("Object.GetWorldRight", function() return obj:GetWorldRight() end))
    o.up = vec(try("Object.GetWorldUp", function() return obj:GetWorldUp() end))
    o.id = try("Object.GetEntityID", function() return tostring(obj:GetEntityID().hash) end)
    o.name = try("Object.GetDisplayName", function() return GetLocalizedText(obj:GetDisplayName()) end)
    return o
end

-- Луч из камеры по направлению взгляда: ближайшее попадание среди групп столкновений.
local GROUPS = { "Static", "Dynamic", "Interaction", "Vehicle" }
local function raycast(c, dist)
    if not (c.pos and c.f) then return nil end
    local from = c.pos
    local to = add(c.pos, mul(c.f, dist or 15))
    local sq = Game.GetSpatialQueriesSystem()
    local best, hits = nil, {}
    for _, g in ipairs(GROUPS) do
        local ok, res = try("Raycast." .. g, function()
            return sq:SyncRaycastByCollisionGroup(Vector4.new(from.x, from.y, from.z, 1),
                Vector4.new(to.x, to.y, to.z, 1), g, false, false)
        end)
        if ok and res then
            local p = vec(res.position)
            if p then
                local d = math.sqrt(dot(sub(p, from), sub(p, from)))
                local mat = ""
                pcall(function() mat = Game.NameToString(res.material) end)
                hits[#hits + 1] = string.format("%s d=%.3f p=%s mat=%s", g, d, vstr(p), mat)
                -- ближе 5 см — луч начался внутри предмета (камера в стене/полу): такое попадание не годится
                if d > 0.05 then
                    -- стекло экрана важнее корпуса: коллизия корпуса (Static, plastic) стоит на ~5 см ближе стекла,
                    -- и рамка по ней «висит» перед экраном
                    local glass = mat:find("glass") ~= nil
                    local better = not best or (glass and not best.glass) or (glass == best.glass and d < best.d)
                    if better then best = { p = p, d = d, group = g, mat = mat, glass = glass, n = vec(res.normal) } end
                end
            end
        end
    end
    return best, hits
end

-- ---------- действия ----------

local function logCamera(prefix, c)
    log(string.format("%s cam.pos=%s [%s] f=%s r=%s u=%s%s", prefix, vstr(c.pos), tostring(c.posSource),
        vstr(c.f), vstr(c.r), vstr(c.u), c.derivedAxes and "(axes derived)" or ""))
    for _, e in ipairs(c.eyes or {}) do log(string.format("%s   eye %s = %s", prefix, e.name, vstr(e.pos))) end
end

local function logLookAt(prefix, o)
    if not o then log(prefix .. "lookAt none"); return end
    log(string.format("%slookAt class=%s name=%s id=%s pos=%s fwd=%s right=%s up=%s", prefix, tostring(o.cls),
        tostring(o.name), tostring(o.id), vstr(o.pos), vstr(o.fwd), vstr(o.right), vstr(o.up)))
end

local function snapshot(tag)
    local W, H = screenSize()
    local c = camera()
    local comp, settings, zoom = fovNow()
    log(string.format("=== SNAPSHOT %s screen=%dx%d fov.component=%s fov.settings=%s zoom=%s", tag or "",
        W, H, fmt(comp), fmt(settings), fmt(zoom)))
    logCamera("  ", c)
    local player = Game.GetPlayer()
    local ppos = vec(try("Player.GetWorldPosition", function() return player:GetWorldPosition() end))
    local yaw = try("Player.GetWorldYaw", function() return player:GetWorldYaw() end)
    log(string.format("  player.pos=%s yaw=%s", vstr(ppos), fmt(yaw)))
    logLookAt("  ", lookAt())
    local best, hits = raycast(c, 15)
    for _, h in ipairs(hits or {}) do log("  ray " .. h) end
    if best then log(string.format("  ray.best %s d=%.3f p=%s", best.group, best.d, vstr(best.p))) end
    -- где наша формула видит углы (по всем вариантам FOV) — сверить со скриншотом
    for i, p in ipairs(R.corners) do
        local gx, gy = project(p, c, W, H, 0, "game")
        log(string.format("  corner%d game -> %s", i, gx and string.format("px=(%.1f, %.1f)", gx, gy) or "behind/none"))
        for _, mode in ipairs(R.fovModes) do
            if mode ~= "game" then for _, srcName in ipairs({ "component", "settings" }) do
                local fv = (srcName == "component") and comp or settings
                if fv and fv > 0 then
                    local x, y, z = project(p, c, W, H, fv, mode)
                    log(string.format("  corner%d %s/%s fov=%.3f -> %s", i, mode, srcName, fv,
                        x and string.format("px=(%.1f, %.1f) depth=%.3f", x, y, z) or "behind"))
                end
            end end
        end
    end
    R.lastSnap = { c = c, comp = comp, settings = settings, W = W, H = H }
    R.status = "snapshot written"
end

local function saveCorners()
    local f = io.open(CORNERS_FILE, "w")
    if not f then return end
    for i, p in ipairs(R.corners) do
        f:write(string.format("%d %.5f %.5f %.5f\n", i, p.x, p.y, p.z))
    end
    f:close()
end

local function loadCorners()
    local f = io.open(CORNERS_FILE, "r")
    if not f then return end
    for line in f:lines() do
        local i, x, y, z = line:match("^(%d+)%s+(%S+)%s+(%S+)%s+(%S+)")
        if i then R.corners[tonumber(i)] = { x = tonumber(x), y = tonumber(y), z = tonumber(z) } end
    end
    f:close()
    log("corners loaded: " .. #R.corners)
    R.needOrder = #R.corners == 4 -- старый файл мог быть отмечен в другом порядке: разберём, когда будет камера
end

-- ---------- задержка ----------

local function nowSec()
    local ok, c = pcall(os.clock) -- на Windows — время с запуска процесса
    return ok and c or 0
end

local function pushPose()
    local c = camera()
    if not (c.pos and c.f and c.r and c.u) then return end
    local t = nowSec()
    local h = R.hist
    h[#h + 1] = { t = t, pos = c.pos, f = c.f, r = c.r, u = c.u }
    while #h > 2 and h[1].t < t - 0.5 do table.remove(h, 1) end
end

local function lerp(a, b, k) return add(a, mul(sub(b, a), k)) end

local function blend(a, b, k)
    return { pos = lerp(a.pos, b.pos, k), f = norm(lerp(a.f, b.f, k)), r = norm(lerp(a.r, b.r, k)),
        u = norm(lerp(a.u, b.u, k)) }
end

-- Камера на момент t (между двумя записями — линейно; позже последней — упреждение по двум последним).
-- nil — истории не хватает.
local function poseAt(t)
    local h = R.hist
    if #h >= 2 and t > h[#h].t then
        local a, b = h[#h - 1], h[#h]
        if b.t <= a.t then return nil end
        return blend(a, b, math.min((t - a.t) / (b.t - a.t), 4))
    end
    for i = #h, 2, -1 do
        local a, b = h[i - 1], h[i]
        if a.t <= t and t <= b.t then
            local k = (b.t > a.t) and (t - a.t) / (b.t - a.t) or 0
            return blend(a, b, k)
        end
    end
    return nil
end

-- Углы по местам ЛВ, ПВ, ПН, ЛН относительно зрителя (в каком порядке их ни отметили). false — не разобрать.
local function orderCorners(cs, eye)
    local ctr = mul(add(add(cs[1], cs[2]), add(cs[3], cs[4])), 0.25)
    local f = norm(sub(ctr, eye))
    local right = norm(cross(f, { x = 0, y = 0, z = 1 }))
    local out = {}
    for _, p in ipairs(cs) do
        local d = sub(p, ctr)
        local left = dot(d, right) < 0
        local idx = (d.z > 0) and (left and 1 or 2) or (left and 4 or 3)
        if out[idx] then return cs, false end
        out[idx] = p
    end
    return out, true
end

-- ---------- экран центром ----------

local SCREEN_FILE = "screen.txt"

-- Оси экрана для зрителя: n — к зрителю, r — вправо, u — вверх (горизонт мира сохраняется).
local function screenAxes(n)
    local f = mul(n, -1)
    local r = norm(cross(f, { x = 0, y = 0, z = 1 }))
    local u = norm(cross(r, f))
    return r, u
end

local function cornersFromScreen(sc)
    local r, u = screenAxes(sc.n)
    local c = add(add(add(sc.c, mul(r, sc.sr)), mul(u, sc.su)), mul(sc.n, sc.sn))
    local hw, hh = sc.w / 2, sc.h / 2
    return {
        add(add(c, mul(r, -hw)), mul(u, hh)),  -- ЛВ
        add(add(c, mul(r, hw)), mul(u, hh)),   -- ПВ
        add(add(c, mul(r, hw)), mul(u, -hh)),  -- ПН
        add(add(c, mul(r, -hw)), mul(u, -hh)), -- ЛН
    }
end

local function saveScreen()
    local sc = R.screen
    if not sc then return end
    local f = io.open(SCREEN_FILE, "w")
    if not f then return end
    f:write(string.format("%.5f %.5f %.5f %.5f %.5f %.5f %.4f %.4f %.4f %.4f %.4f\n", sc.c.x, sc.c.y, sc.c.z,
        sc.n.x, sc.n.y, sc.n.z, sc.w, sc.h, sc.sr, sc.su, sc.sn))
    f:close()
end

local function applyScreen()
    if not R.screen then return end
    R.corners = cornersFromScreen(R.screen)
    R.nextCorner = 1
    saveCorners()
    saveScreen()
end

local function loadScreen()
    local f = io.open(SCREEN_FILE, "r")
    if not f then return end
    local line = f:read("*l")
    f:close()
    local v = {}
    for x in (line or ""):gmatch("%S+") do v[#v + 1] = tonumber(x) end
    if #v < 11 then return end
    R.screen = { c = { x = v[1], y = v[2], z = v[3] }, n = { x = v[4], y = v[5], z = v[6] }, w = v[7], h = v[8],
        sr = v[9], su = v[10], sn = v[11] }
    R.corners = cornersFromScreen(R.screen)
    log(string.format("screen loaded: %.3f x %.3f m", R.screen.w, R.screen.h))
end

-- Наведите прицел на центр экрана телевизора. Нормаль — от объекта TV (точнее) или от поверхности под лучом.
local function markCenter()
    local c = camera()
    local best, hits = raycast(c, 15)
    log("=== CENTER")
    logCamera("  ", c)
    for _, h in ipairs(hits or {}) do log("  ray " .. h) end
    local o = lookAt()
    logLookAt("  ", o)
    if not best then
        R.status = "center: ray hit nothing"
        return
    end
    local n, src = nil, ""
    if o and o.cls == "TV" and o.fwd then
        n, src = norm({ x = o.fwd.x, y = o.fwd.y, z = 0 }), "TV entity"
    elseif best.n then
        n, src = norm(best.n), "surface"
    else
        n, src = norm(mul(c.f, -1)), "camera"
    end
    if dot(n, sub(c.pos, best.p)) < 0 then n = mul(n, -1) end -- нормаль к зрителю
    local old = R.screen
    R.screen = { c = best.p, n = n, w = old and old.w or 1.15, h = old and old.h or 1.15 * 9 / 16, sr = 0, su = 0, sn = 0 }
    applyScreen()
    R.status = string.format("center %s, normal from %s", vstr(best.p), src)
    log(string.format("  center=%s normal=%s (%s) size %.3f x %.3f", vstr(best.p), vstr(n), src, R.screen.w, R.screen.h))
end

local function screenControls()
    local sc = R.screen
    if not sc then
        ImGui.Text("Screen: aim at the TV centre and press 'Mark centre'")
        return
    end
    local changed = false
    local function slider(label, val, lo, hi)
        local v, used = ImGui.SliderFloat(label, val, lo, hi, "%.3f")
        if used then changed = true; return v end
        return val
    end
    sc.w = slider("Width m", sc.w, 0.2, 4.0)
    if R.lock169 ~= false then sc.h = sc.w * 9 / 16 else sc.h = slider("Height m", sc.h, 0.1, 3.0) end
    local lock, usedLock = ImGui.Checkbox("16:9", R.lock169 ~= false)
    if usedLock then R.lock169 = lock; changed = true end
    sc.sr = slider("Shift right m", sc.sr, -0.5, 0.5)
    sc.su = slider("Shift up m", sc.su, -0.5, 0.5)
    sc.sn = slider("Shift out m", sc.sn, -0.2, 0.2)
    if changed then applyScreen() end
end

local CORNER_NAMES = { "top-left", "top-right", "bottom-right", "bottom-left" }

local function markCorner()
    local c = camera()
    local best, hits = raycast(c, 15)
    local i = R.nextCorner
    log(string.format("=== CORNER %d (%s)", i, CORNER_NAMES[i]))
    logCamera("  ", c)
    for _, h in ipairs(hits or {}) do log("  ray " .. h) end
    logLookAt("  ", lookAt())
    if not best then
        R.status = "corner " .. i .. ": ray hit nothing (or started inside an object)"
        log("  no hit")
        return
    end
    R.corners[i] = best.p
    R.nextCorner = i % 4 + 1
    saveCorners()
    R.status = string.format("corner %d (%s) = %s", i, CORNER_NAMES[i], vstr(best.p))
    log(string.format("  saved %s (%s, %.3f m, %s)", vstr(best.p), best.group, best.d, tostring(best.mat)))
    if #R.corners == 4 then
        local ordered, ok = orderCorners(R.corners, c.pos)
        if ok then
            local changed = false
            for k = 1, 4 do if ordered[k] ~= R.corners[k] then changed = true end end
            R.corners = ordered
            saveCorners()
            if changed then log("  corners reordered to TL, TR, BR, BL") end
        else
            log("  corners: can't tell top/left of each, kept as marked")
        end
        local a, b, d = R.corners[1], R.corners[2], R.corners[4]
        local wv, hv = sub(b, a), sub(d, a)
        local w, h = math.sqrt(dot(wv, wv)), math.sqrt(dot(hv, hv))
        log(string.format("  screen size %.3f x %.3f m, aspect %.3f, normal=%s", w, h, h > 0 and w / h or 0,
            vstr(norm(cross(wv, hv)))))
    end
end

local function startRecord()
    R.recording = true
    R.recordLeft = RECORD_SECONDS
    R.recordFrames = 0
    local W, H = screenSize()
    log(string.format("=== RECORD start %ds screen=%dx%d", RECORD_SECONDS, W, H))
    R.status = "recording..."
end

local function recordFrame(dt)
    local c = camera()
    local comp = fovNow()
    R.recordFrames = R.recordFrames + 1
    log(string.format("rec dt=%.4f pos=%s f=%s fov=%s", dt, vstr(c.pos), vstr(c.f), fmt(comp)))
    R.recordLeft = R.recordLeft - dt
    if R.recordLeft <= 0 then
        R.recording = false
        log(string.format("=== RECORD end frames=%d (%.1f fps)", R.recordFrames, R.recordFrames / RECORD_SECONDS))
        R.status = "record done"
    end
end

-- ---------- упреждение по повороту ----------

local function wrapPi(a)
    while a > math.pi do a = a - 2 * math.pi end
    while a < -math.pi do a = a + 2 * math.pi end
    return a
end

-- Углы на экране с упреждением: камера поворачивается вперёд на leadMs по сглаженной угловой скорости,
-- углы перепроецируются той же «линзой», что у игры (kx, ky подгоняются под ProjectPoint каждый кадр).
local function predictPose(c, ndc, W, H)
    local P = R.pose
    local t = nowSec()
    local yaw = math.atan2(c.f.y, c.f.x)
    local pitch = math.asin(math.max(-1, math.min(1, c.f.z)))
    if P.t and t > P.t then
        local dt = t - P.t
        if dt > 0.001 and dt < 0.1 then
            local ry, rp = wrapPi(yaw - P.yaw) / dt, (pitch - P.pitch) / dt
            local a = 0.5
            P.wy, P.wp = a * P.wy + (1 - a) * ry, a * P.wp + (1 - a) * rp
            -- остановка: быстро гасим скорость, чтобы рамка не проскакивала и не «возвращалась»
            if math.abs(ry) < 0.01 and math.abs(rp) < 0.01 then P.still = P.still + 1 else P.still = 0 end
            if P.still >= 1 then P.wy, P.wp = P.wy * 0.4, P.wp * 0.4 end
        else
            P.wy, P.wp = 0, 0
        end
    end
    P.yaw, P.pitch, P.t = yaw, pitch, t
    -- фокус игры: ndc = k * (x/z) по каждой оси (наименьшие квадраты по четырём углам)
    local sxa, saa, syb, sbb = 0, 0, 0, 0
    for i = 1, 4 do
        local d = sub(R.corners[i], c.pos)
        local zc = dot(d, c.f)
        if zc < 0.05 or not ndc[i] then return nil end
        local a, b = dot(d, c.r) / zc, dot(d, c.u) / zc
        sxa, saa = sxa + ndc[i][1] * a, saa + a * a
        syb, sbb = syb + ndc[i][2] * b, sbb + b * b
    end
    if saa < 1e-9 or sbb < 1e-9 then return nil end
    local kx, ky = sxa / saa, syb / sbb
    local lead = R.leadMs / 1000
    local lim = math.rad(5)
    local py = yaw + math.max(-lim, math.min(lim, P.wy * lead))
    local pp = pitch + math.max(-lim, math.min(lim, P.wp * lead))
    local f = { x = math.cos(pp) * math.cos(py), y = math.cos(pp) * math.sin(py), z = math.sin(pp) }
    local r = norm(cross(f, { x = 0, y = 0, z = 1 }))
    local u = cross(r, f)
    local out = {}
    for i = 1, 4 do
        local d = sub(R.corners[i], c.pos)
        local zc = dot(d, f)
        if zc < 0.05 then return nil end
        out[i] = { (1 + kx * dot(d, r) / zc) * W / 2, (1 - ky * dot(d, u) / zc) * H / 2 }
    end
    return out
end

-- ---------- рисование ----------

local COL_FRAME = 0xFF00FF00 -- ABGR: зелёный
local COL_CROSS = 0xFF00FFFF -- жёлтый
local COL_TEXT = 0xFFFFFFFF

local function drawHud()
    local W, H = screenSize()
    if W <= 0 then return end
    ImGui.SetNextWindowPos(0, 0)
    ImGui.SetNextWindowSize(W, H)
    local flags = ImGuiWindowFlags.NoDecoration + ImGuiWindowFlags.NoInputs + ImGuiWindowFlags.NoBackground
        + ImGuiWindowFlags.NoSavedSettings + ImGuiWindowFlags.NoFocusOnAppearing
        + ImGuiWindowFlags.NoBringToFrontOnFocus
    if ImGui.Begin("##SuslikReconHud", flags) then
        local ok, err = pcall(function()
            local dl = ImGui.GetWindowDrawList()
            local cx, cy = W / 2, H / 2
            ImGui.ImDrawListAddLine(dl, cx - 12, cy, cx + 12, cy, COL_CROSS, 1.5)
            ImGui.ImDrawListAddLine(dl, cx, cy - 12, cx, cy + 12, COL_CROSS, 1.5)
            if R.needOrder then
                local c0 = camera()
                if c0.pos and not c0.posFromPlayer then
                    local ordered, okO = orderCorners(R.corners, c0.pos)
                    if okO then R.corners = ordered; saveCorners(); log("loaded corners ordered TL, TR, BR, BL") end
                    R.needOrder = false
                end
            end
            if #R.corners == 4 then
                local c = (R.delayMs ~= 0 and poseAt(nowSec() - R.delayMs / 1000)) or camera()
                local comp, settings = fovNow()
                local fv = fovUsed(comp, settings)
                local mode = R.fovModes[R.fovMode]
                local pts = {}
                for i = 1, 4 do
                    local x, y = project(R.corners[i], c, W, H, fv, mode)
                    pts[i] = x and { x, y } or nil
                end
                -- сдвиг во времени в экранных координатах: работает и для проекции игры (её камеру назад не отмотать)
                if mode == "game" and R.predModes[R.predMode] == "pose" and c.f then
                    local ndc = {}
                    for i = 1, 4 do
                        if pts[i] then ndc[i] = { pts[i][1] / W * 2 - 1, 1 - pts[i][2] / H * 2 } end
                    end
                    local pp = predictPose(c, ndc, W, H)
                    if pp then pts = pp end
                end
                local qh = R.qhist
                qh[#qh + 1] = pts
                while #qh > 8 do table.remove(qh, 1) end
                local lag = (R.predModes[R.predMode] == "frames") and R.lagFrames or 0
                if lag > 0 and #qh > lag then
                    pts = qh[#qh - lag]
                elseif lag < 0 and #qh >= 2 then
                    local prev, out = qh[#qh - 1], {}
                    for i = 1, 4 do
                        local a, b = prev[i], pts[i]
                        out[i] = (a and b) and { b[1] + (b[1] - a[1]) * -lag, b[2] + (b[2] - a[2]) * -lag } or b
                    end
                    pts = out
                end
                for i = 1, 4 do
                    local a, b = pts[i], pts[i % 4 + 1]
                    if a and b then ImGui.ImDrawListAddLine(dl, a[1], a[2], b[1], b[2], COL_FRAME, 2.0) end
                    if a then ImGui.ImDrawListAddText(dl, a[1] + 4, a[2] + 2, COL_TEXT, tostring(i)) end
                end
                if pts[1] and pts[3] then ImGui.ImDrawListAddLine(dl, pts[1][1], pts[1][2], pts[3][1], pts[3][2], COL_FRAME, 1.0) end
                if pts[2] and pts[4] then ImGui.ImDrawListAddLine(dl, pts[2][1], pts[2][2], pts[4][1], pts[4][2], COL_FRAME, 1.0) end
                ImGui.ImDrawListAddText(dl, 20, H - 40, COL_TEXT,
                    string.format("Suslik recon: %s, predict %s (lead %d ms, lag %d), draw %d/s, update %d/s", mode,
                        R.predModes[R.predMode], R.leadMs, R.lagFrames, R.drawRate, R.updateRate))
            end
        end)
        if not ok and R.api["Hud.draw"] == nil then
            R.api["Hud.draw"] = "ERR " .. tostring(err)
            log("hud draw error: " .. tostring(err))
        end
    end
    ImGui.End()
end

local function drawWindow()
    if not R.overlayOpen then return end
    if ImGui.Begin("Suslik Cinema recon") then
        ImGui.Text("Log: recon.log (mod folder). Nothing in the game is changed.")
        if ImGui.Button("Snapshot") then snapshot("button") end
        ImGui.SameLine()
        if ImGui.Button("Mark corner " .. R.nextCorner .. " (" .. CORNER_NAMES[R.nextCorner] .. ")") then markCorner() end
        ImGui.SameLine()
        if ImGui.Button("Mark centre") then markCenter() end
        ImGui.SameLine()
        if ImGui.Button("Reset corners") then
            R.corners = {}; R.nextCorner = 1; saveCorners(); log("corners reset")
        end
        if ImGui.Button(R.recording and "Recording..." or "Record 10 s") and not R.recording then startRecord() end
        ImGui.SameLine()
        if ImGui.Button(R.hud and "Hide frame" or "Show frame") then R.hud = not R.hud; log("hud " .. tostring(R.hud)) end
        if ImGui.Button("FOV mode: " .. R.fovModes[R.fovMode]) then
            R.fovMode = R.fovMode % #R.fovModes + 1; log("fov mode " .. R.fovModes[R.fovMode])
        end
        ImGui.SameLine()
        if ImGui.Button("FOV source: " .. R.fovSources[R.fovSource]) then
            R.fovSource = R.fovSource % #R.fovSources + 1; log("fov source " .. R.fovSources[R.fovSource])
        end
        local v, used = ImGui.SliderFloat("FOV scale", R.fovScale, 0.5, 1.5)
        if used then R.fovScale = v end
        local m, usedM = ImGui.SliderFloat("Manual FOV", R.fovManual, 30, 120)
        if usedM then R.fovManual = m end
        if ImGui.Button("Predict: " .. R.predModes[R.predMode]) then
            R.predMode = R.predMode % #R.predModes + 1; log("predict " .. R.predModes[R.predMode])
        end
        local lm, usedLm = ImGui.SliderInt("Lead ms (pose)", R.leadMs, 0, 60)
        if usedLm then R.leadMs = lm end
        local lf, usedL = ImGui.SliderInt("Lag frames (frames, <0 = predict)", R.lagFrames, -3, 4)
        if usedL then R.lagFrames = lf; log("lag frames " .. lf) end
        local dm, usedD = ImGui.SliderInt("Delay ms (not for game mode)", R.delayMs, -100, 150)
        if usedD then R.delayMs = dm end
        ImGui.Text(string.format("draw %d/s, update %d/s", R.drawRate, R.updateRate))
        if ImGui.Button("Log 'frame matches TV'") then
            log(string.format("=== MATCH confirmed: mode=%s source=%s scale=%.4f manual=%.2f lag=%d delay=%dms draw=%d/s update=%d/s",
                R.fovModes[R.fovMode], R.fovSources[R.fovSource], R.fovScale, R.fovManual, R.lagFrames, R.delayMs, R.drawRate,
                R.updateRate))
            snapshot("match")
        end
        ImGui.Separator()
        ImGui.Separator()
        screenControls()
        if R.screen and ImGui.Button("Log screen") then
            local sc = R.screen
            log(string.format("=== SCREEN c=%s n=%s w=%.4f h=%.4f shift r=%.4f u=%.4f n=%.4f", vstr(sc.c), vstr(sc.n),
                sc.w, sc.h, sc.sr, sc.su, sc.sn))
        end
        ImGui.Separator()
        ImGui.Text("Corners: " .. #R.corners .. "/4")
        ImGui.Text(R.status)
    end
    ImGui.End()
end

-- ---------- события CET ----------

registerForEvent("onInit", function()
    log("==================== Suslik Cinema recon start")
    local cet = try("GetVersion", function() return GetVersion() end)
    local game = try("GameVersion", function() return Game.GetSystemRequestsHandler():GetGameVersion() end)
    local W, H = screenSize()
    log(string.format("CET=%s game=%s screen=%dx%d", tostring(cet), tostring(game), W, H))
    local cw = try("Codeware", function() return Codeware and Codeware.Version() end)
    log("Codeware=" .. tostring(cw))
    loadCorners()
    loadScreen()
end)

registerForEvent("onOverlayOpen", function() R.overlayOpen = true end)
registerForEvent("onOverlayClose", function() R.overlayOpen = false end)

registerForEvent("onUpdate", function(dt)
    if R.recording then pcall(recordFrame, dt) end
    R.updates = R.updates + 1
    if R.hud and #R.corners == 4 then pcall(pushPose) end
end)

registerForEvent("onDraw", function()
    R.draws = R.draws + 1
    local t = nowSec()
    if t - R.rateT >= 1 then
        local span = t - R.rateT
        R.drawRate, R.updateRate = math.floor(R.draws / span + 0.5), math.floor(R.updates / span + 0.5)
        R.draws, R.updates, R.rateT = 0, 0, t
    end
    if R.hud then drawHud() end
    drawWindow()
end)

registerForEvent("onShutdown", function()
    local lines = {}
    for k, v in pairs(R.api) do lines[#lines + 1] = k .. ": " .. v end
    table.sort(lines)
    log("API report:\n  " .. table.concat(lines, "\n  "))
    log("==================== Suslik Cinema recon stop")
end)

registerHotkey("SuslikRecon_Snapshot", "Suslik recon: snapshot", function() snapshot("hotkey") end)
registerHotkey("SuslikRecon_Corner", "Suslik recon: mark next TV corner", function() markCorner() end)
registerHotkey("SuslikRecon_Center", "Suslik recon: mark TV screen centre", function() markCenter() end)
registerHotkey("SuslikRecon_Hud", "Suslik recon: show/hide frame", function() R.hud = not R.hud end)
registerHotkey("SuslikRecon_Record", "Suslik recon: record camera 10 s", function()
    if not R.recording then startRecord() end
end)
registerHotkey("SuslikRecon_Report", "Suslik recon: write API report", function()
    local lines = {}
    for k, v in pairs(R.api) do lines[#lines + 1] = k .. ": " .. v end
    table.sort(lines)
    log("API report:\n  " .. table.concat(lines, "\n  "))
    R.status = "API report written"
end)
