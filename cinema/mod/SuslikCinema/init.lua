-- Suslik Cinema — мод CET (этап 2, docs/CYBERPUNK.md). Ставит виртуальный экран на телевизор и каждый кадр
-- сообщает хосту suslik-cinema.exe камеру игры. Видео рисует хост в своём окне поверх игры.
--
-- Файлы в папке мода:
--   screens.json — калибровка: центр на стекле, нормаль, размер, сдвиги (пишется при изменении);
--   screen.json  — углы экрана в мире для хоста, rev растёт при каждом изменении;
--   state.json   — каждый кадр: {seq, t, pos, f, r, u, kx, ky, ox, oy, W, H, visible, occl, paused, cal, rev, end};
--   remote.json, host.json, library.json, host.txt — пульт на телефоне VAO (remote.lua).
--                  Пишется целиком на месте; хост берёт файл, только если seq == end (иначе он недописан).
-- Игру мод не меняет: не трогает сохранения, настройки и объекты. Надписи латиницей (шрифт CET без кириллицы).

local STATE_FILE = "state.json"
local SCREEN_FILE = "screen.json"
local CALIB_FILE = "screens.json"
local OCCL_EVERY = 6        -- лучи на загораживание раз в N кадров (~10 раз в секунду)
local PROBE = 0.25          -- точки для подгонки линзы игры: x/z = ±PROBE

local S = {
    seq = 0,
    rev = 0,
    screen = nil,           -- { c, n, w, h, sr, su, sn, lock }
    corners = nil,          -- 4 угла в мире: ЛВ, ПВ, ПН, ЛН
    normal = nil,
    occl = 0,
    occlTick = 0,
    visible = false,
    overlayOpen = false,
    frame = false,          -- рамка ImGui (проверка без хоста)
    userHidden = false,     -- экран скрыт клавишей мода
    hideArmed = true,       -- прятать экран, когда оружие в руках (оверлей рисуется поверх оружия и прицела)
    offDist = 0,            -- дальше этого расстояния до экрана, м, экран гаснет (0 — не выключать)
    npcAlpha = 0.25,        -- прозрачность экрана, когда в прицеле NPC перед экраном
    npc = false,            -- NPC перед экраном (подтверждено двумя проверками подряд)
    npcRaw = false,
    occlRaw = -1,           -- прошлое сырое значение лучей: новое принимается, только если повторилось
    fade = 1,               -- множитель прозрачности для хоста: дистанция и NPC перед экраном
    status = "",
    lastErr = nil,
}

-- ---------- векторы ----------

local function vec(v)
    if v == nil then return nil end
    local ok, r = pcall(function() return { x = v.x + 0.0, y = v.y + 0.0, z = v.z + 0.0 } end)
    return ok and r or nil
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
local function vstr(v) return v and string.format("(%.3f, %.3f, %.3f)", v.x, v.y, v.z) or "nil" end

local function note(msg)
    S.status = msg
    print("[SuslikCinema] " .. msg)
end

-- Ошибка в кадре пишется в консоль CET один раз (иначе 60 строк в секунду).
local function guard(name, fn, ...)
    local ok, err = pcall(fn, ...)
    if not ok then
        local m = name .. ": " .. tostring(err)
        if m ~= S.lastErr then S.lastErr = m; print("[SuslikCinema] " .. m) end
    end
end

-- ---------- игра ----------

local function screenSize()
    local ok, w, h = pcall(GetDisplayResolution)
    if ok and w and w > 0 then return w, h end
    return 0, 0
end

-- Камера кадра: позиция глаз и оси. В CET 1.38 GetActiveCameraWorldTransform требует out-параметр.
local function camera()
    local cs = Game.GetCameraSystem()
    local pos
    pcall(function()
        local t = Transform.new()
        local r1, r2 = cs:GetActiveCameraWorldTransform(t)
        for _, o in ipairs({ r2, r1, t }) do
            if type(o) == "userdata" or type(o) == "table" then
                local okP, p = pcall(function() return o.position end)
                local v = okP and vec(p) or nil
                if v and (math.abs(v.x) + math.abs(v.y) + math.abs(v.z)) > 0.001 then pos = v; return end
            end
        end
    end)
    if not pos then
        pcall(function() pos = vec(Game.GetPlayer():GetFPPCameraComponent():GetLocalToWorld():GetTranslation()) end)
    end
    local f = vec(cs:GetActiveCameraForward())
    local r = vec(cs:GetActiveCameraRight())
    local u = vec(cs:GetActiveCameraUp())
    if not (pos and f and r and u) then return nil end
    return { pos = pos, f = f, r = r, u = u }
end

-- Линза игры: ndc = k·(x/z) + o по каждой оси, по ProjectPoint четырёх точек на оси камеры.
-- Подгонка под проекцию самой игры: FOV, аспект и знак осей угадывать не нужно.
local function lens(c)
    local cs = Game.GetCameraSystem()
    local function ndc(dx, dy)
        local p = add(add(add(c.pos, c.f), mul(c.r, dx)), mul(c.u, dy))
        local q = cs:ProjectPoint(Vector4.new(p.x, p.y, p.z, 1))
        return q.x, q.y
    end
    local xr = ndc(PROBE, 0)
    local xl = ndc(-PROBE, 0)
    local _, yu = ndc(0, PROBE)
    local _, yd = ndc(0, -PROBE)
    local kx, ky = (xr - xl) / (2 * PROBE), (yu - yd) / (2 * PROBE)
    if math.abs(kx) < 0.05 or math.abs(ky) < 0.05 then return nil end
    return kx, ky, (xr + xl) / 2, (yu + yd) / 2
end

local function isPaused()
    local ok, p = pcall(function() return Game.GetSystemRequestsHandler():IsGamePaused() end)
    return ok and p == true
end

-- Оружие в правой руке (слот WeaponRight не пуст).
local function isArmed()
    local ok, item = pcall(function()
        return Game.GetTransactionSystem():GetItemInSlot(Game.GetPlayer(), TweakDBID.new("AttachmentSlots.WeaponRight"))
    end)
    return ok and item ~= nil
end

-- Замедление времени: колесо оружия, сканер в бою, (кибер)замедление — интерфейс поверх всего экрана.
local function isDilated()
    local ok, d = pcall(function() return Game.GetTimeSystem():IsTimeDilationActive() end)
    return ok and d == true
end

-- Почему экран сейчас лучше спрятать (оверлей хоста рисуется поверх оружия и интерфейса игры), или nil.
local function hideReason()
    if S.userHidden then return "user" end
    if isDilated() then return "wheel" end
    if S.hideArmed and isArmed() then return "weapon" end
    return nil
end

local SETTINGS_FILE = "settings.txt"

local function saveSettings()
    local f = io.open(SETTINGS_FILE, "w")
    if not f then return end
    f:write("hideArmed=", S.hideArmed and "1" or "0", "\n")
    f:write(string.format("offDist=%.1f\nnpcAlpha=%.2f\n", S.offDist, S.npcAlpha))
    f:close()
end

local function loadSettings()
    local f = io.open(SETTINGS_FILE, "r")
    if not f then return end
    local t = f:read("*a") or ""
    f:close()
    if t:match("hideArmed=0") then S.hideArmed = false end
    S.offDist = tonumber(t:match("offDist=([%d%.]+)")) or S.offDist
    S.npcAlpha = tonumber(t:match("npcAlpha=([%d%.]+)")) or S.npcAlpha
end

-- ---------- экран ----------

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

local function v3json(v, p)
    local f = "%." .. (p or 5) .. "f"
    return string.format("[" .. f .. "," .. f .. "," .. f .. "]", v.x, v.y, v.z)
end

local function writeFile(name, text)
    local f = io.open(name, "w")
    if not f then return false end
    f:write(text)
    f:close()
    return true
end

local function writeScreen()
    if not S.corners then
        writeFile(SCREEN_FILE, string.format('{"rev":%d,"corners":null}\n', S.rev))
        return
    end
    local cs = {}
    for i = 1, 4 do cs[i] = v3json(S.corners[i], 4) end
    writeFile(SCREEN_FILE, string.format('{"rev":%d,"w":%.4f,"h":%.4f,"n":%s,"corners":[%s]}\n', S.rev,
        S.screen.w, S.screen.h, v3json(S.normal), table.concat(cs, ",")))
end

local function saveCalib()
    local sc = S.screen
    if not sc then
        os.remove(CALIB_FILE)
        return
    end
    writeFile(CALIB_FILE, string.format(
        '{"version":1,"c":%s,"n":%s,"w":%.4f,"h":%.4f,"sr":%.4f,"su":%.4f,"sn":%.4f,"lock":%s,"occl":%s}\n',
        v3json(sc.c), v3json(sc.n), sc.w, sc.h, sc.sr, sc.su, sc.sn, sc.lock and "true" or "false",
        sc.occl == false and "false" or "true"))
end

-- Экран изменился: новые углы, rev + 1, оба файла.
local function applyScreen()
    if S.screen then
        S.corners = cornersFromScreen(S.screen)
        S.normal = S.screen.n
    else
        S.corners, S.normal = nil, nil
    end
    S.rev = S.rev + 1
    writeScreen()
    saveCalib()
end

local function loadCalib()
    local f = io.open(CALIB_FILE, "r")
    if not f then return end
    local s = f:read("*a")
    f:close()
    local function arr(key)
        local x, y, z = s:match('"' .. key .. '":%[([^,]+),([^,]+),([^%]]+)%]')
        if not x then return nil end
        return { x = tonumber(x), y = tonumber(y), z = tonumber(z) }
    end
    local function num(key) return tonumber(s:match('"' .. key .. '":([%-%d%.eE]+)')) end
    local sc = { c = arr("c"), n = arr("n"), w = num("w"), h = num("h"), sr = num("sr") or 0, su = num("su") or 0,
        sn = num("sn") or 0, lock = s:match('"lock":false') == nil, occl = s:match('"occl":false') == nil }
    if not (sc.c and sc.n and sc.w and sc.h) then
        note("screens.json is damaged, mark the screen again")
        return
    end
    S.screen = sc
    note(string.format("screen loaded: %.2f x %.2f m", sc.w, sc.h))
end

-- Луч из камеры по взгляду: стекло экрана важнее корпуса (коллизия корпуса на ~5 см ближе стекла).
local GROUPS = { "Static", "Dynamic", "Interaction", "Vehicle" }
local function aimHit(c, dist)
    local sq = Game.GetSpatialQueriesSystem()
    local from, to = c.pos, add(c.pos, mul(c.f, dist))
    local best
    for _, g in ipairs(GROUPS) do
        local ok, hit, res = pcall(function()
            return sq:SyncRaycastByCollisionGroup(Vector4.new(from.x, from.y, from.z, 1),
                Vector4.new(to.x, to.y, to.z, 1), g, false, false)
        end)
        if ok and hit and res then
            local p = vec(res.position)
            if p then
                local d = math.sqrt(dot(sub(p, from), sub(p, from)))
                local mat = ""
                pcall(function() mat = Game.NameToString(res.material) end)
                if d > 0.05 then
                    local glass = mat:find("glass") ~= nil
                    if not best or (glass and not best.glass) or (glass == best.glass and d < best.d) then
                        best = { p = p, d = d, glass = glass, n = vec(res.normal), mat = mat, group = g }
                    end
                end
            end
        end
    end
    return best
end

local function lookAtTV()
    local ok, obj = pcall(function() return Game.GetTargetingSystem():GetLookAtObject(Game.GetPlayer(), false, false) end)
    if not ok or not obj then return nil end
    local okC, cls = pcall(function() return Game.NameToString(obj:GetClassName()) end)
    if not okC or cls ~= "TV" then return nil end
    local okF, fwd = pcall(function() return vec(obj:GetWorldForward()) end)
    return okF and fwd or nil
end

-- Прицел в центр экрана телевизора: точка на стекле, нормаль от объекта TV или от поверхности.
local function markCenter()
    local c = camera()
    if not c then note("no camera"); return end
    local hit = aimHit(c, 15)
    if not hit then note("centre: ray hit nothing"); return end
    local n, src
    local fwd = lookAtTV()
    if fwd then n, src = norm({ x = fwd.x, y = fwd.y, z = 0 }), "TV"
    elseif hit.n then n, src = norm(hit.n), "surface"
    else n, src = norm(mul(c.f, -1)), "camera" end
    if dot(n, sub(c.pos, hit.p)) < 0 then n = mul(n, -1) end
    local old = S.screen
    S.screen = { c = hit.p, n = n, w = old and old.w or 1.16, h = old and old.h or 1.16 * 9 / 16,
        sr = old and old.sr or 0, su = old and old.su or 0, sn = 0, lock = not old or old.lock,
        occl = not old or old.occl ~= false }
    applyScreen()
    note(string.format("centre %s (%s, %s), normal from %s", vstr(hit.p), hit.group, hit.mat, src))
end

-- ---------- кадр ----------

-- Видно ли экран: лицом к камере и центр или угол перед камерой примерно в кадре. Точно решает хост: он обрезает
-- часть экрана за камерой, так что большой экран вблизи может быть углами за спиной.
local function screenVisible(c, kx, ky, ox, oy)
    local cs = S.corners
    if not cs then return false end
    local ctr = mul(add(add(cs[1], cs[2]), add(cs[3], cs[4])), 0.25)
    if dot(S.normal, sub(c.pos, ctr)) <= 0 then return false end
    for _, p in ipairs({ ctr, cs[1], cs[2], cs[3], cs[4] }) do
        local d = sub(p, c.pos)
        local z = dot(d, c.f)
        if z > 0.05 then
            local nx, ny = kx * dot(d, c.r) / z + ox, ky * dot(d, c.u) / z + oy
            if math.abs(nx) < 1.5 and math.abs(ny) < 1.5 then return true end
        end
    end
    return false
end

-- Загораживание: лучи в центр и углы экрана (точки чуть перед ним). Static и машины прячут, Dynamic притеняет.
local function occlusion(c)
    local cs = S.corners
    local sq = Game.GetSpatialQueriesSystem()
    local ctr = mul(add(add(cs[1], cs[2]), add(cs[3], cs[4])), 0.25)
    local margin = 0.12 + math.max(0, -(S.screen.sn or 0))
    local pts = { ctr, cs[1], cs[2], cs[3], cs[4] }
    local sum = 0
    for _, p in ipairs(pts) do
        local d = sub(c.pos, p)
        local len = math.sqrt(dot(d, d))
        local w = 0
        if len > margin + 0.45 then
            local to = add(p, mul(d, margin / len))
            -- луч начинается в 35 см от глаз к экрану: тело и оружие V не считаются преградой
            local from = add(c.pos, mul(d, -0.35 / len))
            for _, g in ipairs({ "Static", "Vehicle", "Dynamic" }) do
                local ok, hit, res = pcall(function()
                    return sq:SyncRaycastByCollisionGroup(Vector4.new(from.x, from.y, from.z, 1),
                        Vector4.new(to.x, to.y, to.z, 1), g, false, false)
                end)
                if ok and hit and res then
                    local hp = vec(res.position)
                    local hd = hp and math.sqrt(dot(sub(hp, c.pos), sub(hp, c.pos))) or 0
                    -- ближе 30 см — луч вышел из тела V, это не преграда
                    if hd > 0.3 then
                        w = math.max(w, (g == "Dynamic") and 0.5 or 1)
                        if w >= 1 then break end
                    end
                end
            end
        end
        sum = sum + w
    end
    return sum / #pts
end

-- В прицеле NPC, и он ближе экрана: экран рисуется поверх него, поэтому делаем экран полупрозрачным.
local function npcInFront(c)
    local ok, obj = pcall(function() return Game.GetTargetingSystem():GetLookAtObject(Game.GetPlayer(), false, false) end)
    if not ok or not obj then return false end
    local okN, isNpc = pcall(function() return obj:IsNPC() end)
    if not (okN and isNpc) then return false end
    local okP, p = pcall(function() return vec(obj:GetWorldPosition()) end)
    if not (okP and p) then return false end
    local cs = S.corners
    local ctr = mul(add(add(cs[1], cs[2]), add(cs[3], cs[4])), 0.25)
    local dNpc, dScr = dot(sub(p, c.pos), c.f), dot(sub(ctr, c.pos), c.f)
    return dNpc > 0 and dNpc < dScr + 0.5
end

-- Множитель прозрачности: дистанция выключения с плавным краем (до 2 м) и NPC перед экраном.
local function fadeFactor(c)
    local f = 1
    if S.offDist > 0 then
        local cs = S.corners
        local ctr = mul(add(add(cs[1], cs[2]), add(cs[3], cs[4])), 0.25)
        local dist = math.sqrt(dot(sub(ctr, c.pos), sub(ctr, c.pos)))
        local edge = math.min(2.0, S.offDist * 0.3)
        if dist >= S.offDist then f = 0
        elseif dist > S.offDist - edge then f = (S.offDist - dist) / edge end
    end
    if S.npc then f = f * S.npcAlpha end
    return f
end

local function writeState()
    local W, H = screenSize()
    local c = camera()
    local t = os.clock()
    S.seq = S.seq + 1
    local kx, ky, ox, oy
    if c then kx, ky, ox, oy = lens(c) end
    if not (c and kx) then
        writeFile(STATE_FILE, string.format('{"seq":%d,"t":%.4f,"cam":false,"rev":%d,"end":%d}\n', S.seq, t, S.rev, S.seq))
        return
    end
    S.visible = screenVisible(c, kx, ky, ox, oy)
    -- В меню CET (калибровка) экран показываем всегда
    S.hideWhy = (not S.overlayOpen) and hideReason() or nil
    if S.visible and S.screen.occl ~= false then
        S.occlTick = S.occlTick + 1
        if S.occlTick >= OCCL_EVERY then
            S.occlTick = 0
            -- новое значение принимается, только если две проверки подряд согласны: меньше ложных миганий
            local raw = occlusion(c)
            if raw == S.occlRaw then S.occl = raw end
            S.occlRaw = raw
        end
    else
        -- не видно или загораживание выключено (большой экран заходит в стены): не притеняем
        S.occl, S.occlTick, S.occlRaw = 0, OCCL_EVERY, -1
    end
    -- NPC перед экраном: раз в OCCL_EVERY кадров, тоже с подтверждением двумя проверками
    S.npcTick = (S.npcTick or 0) + 1
    if S.visible and S.npcTick >= OCCL_EVERY then
        S.npcTick = 0
        local raw = npcInFront(c)
        if raw == S.npcRaw then S.npc = raw end
        S.npcRaw = raw
    elseif not S.visible then
        S.npc, S.npcRaw = false, false
    end
    S.fade = S.visible and fadeFactor(c) or 1
    writeFile(STATE_FILE, string.format(
        '{"seq":%d,"t":%.4f,"pos":%s,"f":%s,"r":%s,"u":%s,"kx":%.6f,"ky":%.6f,"ox":%.6f,"oy":%.6f,' ..
        '"W":%d,"H":%d,"visible":%s,"occl":%.2f,"paused":%s,"cal":%s,"hide":%s,"fade":%.2f,"rev":%d,"end":%d}\n',
        S.seq, t, v3json(c.pos, 4), v3json(c.f, 6), v3json(c.r, 6), v3json(c.u, 6), kx, ky, ox, oy, W, H,
        S.visible and "true" or "false", S.occl, isPaused() and "true" or "false",
        S.overlayOpen and "true" or "false", S.hideWhy and ('"' .. S.hideWhy .. '"') or "null", S.fade, S.rev, S.seq))
    S.cam, S.kx, S.ky, S.ox, S.oy, S.W, S.H = c, kx, ky, ox, oy, W, H
end

-- ---------- рисование ----------

local function drawFrame()
    local c, cs = S.cam, S.corners
    if not (c and cs and S.kx and S.W > 0) then return end
    local pts = {}
    for i = 1, 4 do
        local d = sub(cs[i], c.pos)
        local z = dot(d, c.f)
        if z < 0.05 then return end
        pts[i] = { (1 + S.kx * dot(d, c.r) / z + S.ox) * S.W / 2, (1 - S.ky * dot(d, c.u) / z - S.oy) * S.H / 2 }
    end
    ImGui.SetNextWindowPos(0, 0)
    ImGui.SetNextWindowSize(S.W, S.H)
    local flags = ImGuiWindowFlags.NoDecoration + ImGuiWindowFlags.NoInputs + ImGuiWindowFlags.NoBackground
        + ImGuiWindowFlags.NoSavedSettings + ImGuiWindowFlags.NoFocusOnAppearing + ImGuiWindowFlags.NoBringToFrontOnFocus
    if ImGui.Begin("##SuslikCinemaFrame", flags) then
        local dl = ImGui.GetWindowDrawList()
        for i = 1, 4 do
            local a, b = pts[i], pts[i % 4 + 1]
            ImGui.ImDrawListAddLine(dl, a[1], a[2], b[1], b[2], 0xFF00FF00, 2.0)
        end
    end
    ImGui.End()
end

local function drawWindow()
    if ImGui.Begin("Suslik Cinema") then
        ImGui.Text("Aim at the TV screen centre (or any wall) and press 'Mark centre' (or the hotkey).")
        ImGui.Text("Ctrl+click a slider to type a value. Big screens: turn off 'Hide behind walls'.")
        if ImGui.Button("Mark centre") then markCenter() end
        ImGui.SameLine()
        if S.screen and ImGui.Button("Forget screen") then
            S.screen = nil
            applyScreen()
            note("screen forgotten")
        end
        local sc = S.screen
        if sc then
            local changed = false
            local function slider(label, val, lo, hi)
                local v, used = ImGui.SliderFloat(label, val, lo, hi, "%.3f")
                if used then changed = true; return v end
                return val
            end
            -- Ctrl+клик по ползунку — ввести число
            sc.w = slider("Width m", sc.w, 0.2, 40.0)
            local lock, usedLock = ImGui.Checkbox("16:9", sc.lock)
            if usedLock then sc.lock = lock; changed = true end
            if sc.lock then sc.h = sc.w * 9 / 16 else sc.h = slider("Height m", sc.h, 0.1, 25.0) end
            sc.sr = slider("Shift right m", sc.sr, -15.0, 15.0)
            sc.su = slider("Shift up m", sc.su, -5.0, 15.0)
            sc.sn = slider("Shift out m", sc.sn, -0.5, 5.0)
            local oc, usedOc = ImGui.Checkbox("Hide behind walls", sc.occl ~= false)
            if usedOc then sc.occl = oc; changed = true end
            if changed then applyScreen() end
            ImGui.Text(string.format("Screen %.2f x %.2f m, visible %s, occluded %.0f%%", sc.w, sc.h,
                S.visible and "yes" or "no", S.occl * 100))
        end
        local ha, usedHa = ImGui.Checkbox("Hide screen while a weapon is drawn", S.hideArmed)
        if usedHa then S.hideArmed = ha; saveSettings() end
        local od, usedOd = ImGui.SliderFloat("Turn off beyond m (0 = never)", S.offDist, 0, 200, "%.0f")
        if usedOd then S.offDist = od; saveSettings() end
        local na, usedNa = ImGui.SliderFloat("Opacity with NPC in front", S.npcAlpha, 0.05, 0.85, "%.2f")
        if usedNa then S.npcAlpha = na; saveSettings() end
        local fr, usedFr = ImGui.Checkbox("Green frame (check without the host)", S.frame)
        if usedFr then S.frame = fr end
        if S.kx then ImGui.Text(string.format("Lens kx %.4f ky %.4f, frame %d", S.kx, S.ky, S.seq)) end
        ImGui.Text(S.status)
    end
    ImGui.End()
end

-- ---------- события CET ----------

registerForEvent("onInit", function()
    loadSettings()
    loadCalib()
    -- rev с отметкой времени: хост увидит новый экран и после перезапуска мода
    S.rev = math.floor(os.time() % 100000) * 10
    applyScreen()
    -- пульт на телефоне V's API Overlay (remote.lua): без VAO мод работает, как раньше
    local ok, remote = pcall(require, "remote")
    if ok and remote then guard("remote", remote.init) else note("remote.lua: " .. tostring(remote)) end
end)

registerForEvent("onOverlayOpen", function() S.overlayOpen = true end)
registerForEvent("onOverlayClose", function() S.overlayOpen = false end)

registerForEvent("onUpdate", function()
    guard("state", writeState)
end)

registerForEvent("onDraw", function()
    if S.frame then guard("frame", drawFrame) end
    if S.overlayOpen then drawWindow() end
end)

registerForEvent("onShutdown", function()
    -- хост увидит, что камеры больше нет, и спрячет экран сразу, не дожидаясь устаревания
    S.seq = S.seq + 1
    writeFile(STATE_FILE, string.format('{"seq":%d,"t":%.4f,"cam":false,"rev":%d,"end":%d}\n', S.seq, os.clock(), S.rev, S.seq))
end)

registerHotkey("SuslikCinema_Center", "Suslik Cinema: mark TV screen centre", function() markCenter() end)
registerHotkey("SuslikCinema_Hide", "Suslik Cinema: hide/show screen", function() S.userHidden = not S.userHidden end)
registerHotkey("SuslikCinema_Frame", "Suslik Cinema: show/hide green frame", function() S.frame = not S.frame end)
