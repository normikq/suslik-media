-- Suslik Cinema: пульт на телефоне V's API Overlay (docs/CYBERPUNK.md, этап 3).
--
-- Только публичный API VAO (VAO.Menu, виджеты, VAO.RegisterApp, VAO.RegisterHelper); код VAO не берём.
-- Связь с хостом suslik-cinema.exe — файлы в папке мода (как state.json):
--   remote.json  (мы → хост)  {"sid", "cmds": [{"n", "at", "op", …}]} — последние команды, хост выполняет новые по n;
--   host.json    (хост → мы)  состояние, раз в секунду; берём, только если seq == end;
--   library.json (хост → мы)  медиатека, перечитываем, когда в host.json меняется libRev;
--   host.txt     (хост → helper) путь к exe: companion/cinema_helper.py запускает по нему хост.
local R = {}

local MOD = "SuslikCinema"
local HELPER = "SuslikCinema_host"
local W, H = 560, 780
local ROWS = 8

local VAO
local menu
local st = {
    host = nil, lib = {}, libRev = -1, cmds = {}, n = 0, sid = nil,
    page = "lib", built = nil, filter = "", listPage = 1, film = nil, season = nil, episode = nil,
    fromStart = false, msgN = -1, dragSeek = false, dragVol = false, polls = 0, code = "",
}
local w = {} -- виджеты текущей страницы

-- ---- JSON (свой маленький разбор: CET не даёт готового) ---------------------------------------------------------

local function utf8char(cp)
    if cp < 0x80 then return string.char(cp) end
    if cp < 0x800 then return string.char(0xC0 + math.floor(cp / 0x40), 0x80 + cp % 0x40) end
    if cp < 0x10000 then
        return string.char(0xE0 + math.floor(cp / 0x1000), 0x80 + math.floor(cp / 0x40) % 0x40, 0x80 + cp % 0x40)
    end
    return string.char(0xF0 + math.floor(cp / 0x40000), 0x80 + math.floor(cp / 0x1000) % 0x40,
        0x80 + math.floor(cp / 0x40) % 0x40, 0x80 + cp % 0x40)
end

local ESC = { ['"'] = '"', ['\\'] = '\\', ['/'] = '/', b = '\b', f = '\f', n = '\n', r = '\r', t = '\t' }

local function decode(s)
    local i = 1
    local value
    local function ws() i = s:find("[^ \t\r\n]", i) or (#s + 1) end
    local function str()
        i = i + 1
        local parts = {}
        while true do
            local j = s:find('["\\]', i)
            if not j then error("eof") end
            parts[#parts + 1] = s:sub(i, j - 1)
            if s:sub(j, j) == '"' then i = j + 1 break end
            local e = s:sub(j + 1, j + 1)
            if e == "u" then
                local cp = tonumber(s:sub(j + 2, j + 5), 16) or 63
                i = j + 6
                if cp >= 0xD800 and cp <= 0xDBFF and s:sub(i, i + 1) == "\\u" then
                    local lo = tonumber(s:sub(i + 2, i + 5), 16) or 0xDC00
                    cp = 0x10000 + (cp - 0xD800) * 0x400 + (lo - 0xDC00)
                    i = i + 6
                end
                parts[#parts + 1] = utf8char(cp)
            else
                parts[#parts + 1] = ESC[e] or e
                i = j + 2
            end
        end
        return table.concat(parts)
    end
    value = function()
        ws()
        local c = s:sub(i, i)
        if c == "{" then
            local o = {}
            i = i + 1
            ws()
            if s:sub(i, i) == "}" then i = i + 1 return o end
            while true do
                ws()
                local k = str()
                ws()
                i = i + 1 -- :
                o[k] = value()
                ws()
                local d = s:sub(i, i)
                i = i + 1
                if d == "}" then return o end
                if d ~= "," then error("object") end
            end
        elseif c == "[" then
            local a = {}
            i = i + 1
            ws()
            if s:sub(i, i) == "]" then i = i + 1 return a end
            while true do
                a[#a + 1] = value()
                ws()
                local d = s:sub(i, i)
                i = i + 1
                if d == "]" then return a end
                if d ~= "," then error("array") end
            end
        elseif c == '"' then return str()
        elseif s:sub(i, i + 3) == "true" then i = i + 4 return true
        elseif s:sub(i, i + 4) == "false" then i = i + 5 return false
        elseif s:sub(i, i + 3) == "null" then i = i + 4 return nil
        end
        local num = s:match("^-?%d+%.?%d*[eE]?[-+]?%d*", i)
        if not num or num == "" then error("value") end
        i = i + #num
        return tonumber(num)
    end
    local ok, v = pcall(value)
    if ok then return v end
    return nil
end

local function enc(v)
    local t = type(v)
    if t == "string" then
        return '"' .. (v:gsub('[%c"\\]', function(c) return string.format("\\u%04x", c:byte()) end)) .. '"'
    elseif t == "boolean" then return v and "true" or "false"
    elseif t == "number" then
        if v == math.floor(v) then return string.format("%.0f", v) end
        return string.format("%.3f", v)
    end
    return "null"
end

local function readFile(name)
    local f = io.open(name, "r")
    if not f then return nil end
    local s = f:read("*a")
    f:close()
    return s
end

-- ---- команды хосту ---------------------------------------------------------------------------------------------

local function send(op, fields)
    st.n = st.n + 1
    local parts = { '"n":' .. st.n, '"at":' .. os.time(), '"op":' .. enc(op) }
    for k, v in pairs(fields or {}) do parts[#parts + 1] = enc(k) .. ":" .. enc(v) end
    st.cmds[#st.cmds + 1] = "{" .. table.concat(parts, ",") .. "}"
    while #st.cmds > 12 do table.remove(st.cmds, 1) end
    local f = io.open("remote.json", "w")
    if not f then return end
    f:write('{"sid":', enc(st.sid), ',"cmds":[', table.concat(st.cmds, ","), "]}\n")
    f:close()
end

-- ---- вид ------------------------------------------------------------------------------------------------------

local function alive() return st.host ~= nil and os.time() - (st.host.time or 0) <= 4 end

-- Русские буквы в нижний регистр (string.lower их не знает): А–П → а–п, Р–Я → р–я, Ё → ё.
local function lowerRu(s)
    s = s:lower()
    s = s:gsub("\208([\144-\159])", function(c) return "\208" .. string.char(c:byte() + 32) end)
    s = s:gsub("\208([\160-\175])", function(c) return "\209" .. string.char(c:byte() - 32) end)
    s = s:gsub("\208\129", "\209\145")
    return s
end

local function clock(sec)
    sec = math.max(0, math.floor(sec or 0))
    local h, m, s = math.floor(sec / 3600), math.floor(sec / 60) % 60, sec % 60
    if h > 0 then return string.format("%d:%02d:%02d", h, m, s) end
    return string.format("%d:%02d", m, s)
end

local function setText(widget, text)
    if widget and widget._last ~= text then
        widget._last = text
        widget:SetText(text)
    end
end

local function filtered()
    local out, f = {}, lowerRu(st.filter or "")
    for _, it in ipairs(st.lib) do
        if f == "" or lowerRu(it.t or ""):find(f, 1, true) then out[#out + 1] = it end
    end
    return out
end

local function kindLabel(it)
    if it.k == "s" then return "сериал" elseif it.k == "c" then return "сборник" end
    return "фильм"
end

local function header(title)
    menu:Text{ text = title, x = 20, y = 14, w = W - 40, h = 34, size = 24, color = "#F4F4F5" }
    w.status = menu:Text{ text = "", x = 20, y = H - 44, w = W - 40, h = 34, size = 14, color = "#A1A1AA", wrap = true }
end

local function button(text, x, y, bw, bh, fn, extra)
    local props = { text = text, x = x, y = y, w = bw, h = bh or 44, size = 16, onClick = fn }
    for k, v in pairs(extra or {}) do props[k] = v end
    return menu:Button(props)
end

local function build(page)
    menu:Clear()
    w = {}
    st.built = page
    if page == "nohost" then
        header("Suslik Cinema")
        w.info = menu:Text{ text = "", x = 20, y = 70, w = W - 40, h = 120, size = 16, wrap = true }
        button("Запустить хост", 20, 200, W - 40, 50, function() VAO.StartHelper(HELPER) end)
    elseif page == "nosite" then
        header("Suslik Cinema")
        menu:Text{ text = "Хосту не задан адрес сервера. Один раз в cmd:\nsuslik-cinema.exe --site <адрес> --login",
            x = 20, y = 70, w = W - 40, h = 120, size = 16, wrap = true }
    elseif page == "wait" then
        header("Suslik Cinema")
        menu:Text{ text = "Проверяю вход…", x = 20, y = 70, w = W - 40, h = 40, size = 16 }
    elseif page == "login" then
        header("Вход в Suslik Media")
        w.code = menu:Text{ text = "", x = 20, y = 70, w = W - 40, h = 60, size = 40, align = "center", color = "#FDE047" }
        w.url = menu:Text{ text = "", x = 20, y = 140, w = W - 40, h = 80, size = 14, wrap = true, color = "#A1A1AA" }
        w.get = button("Получить код", 20, 230, W - 40, 50, function() send("login") end)
        w.open = button("Открыть страницу на ПК", 20, 290, W - 40, 50, function()
            if st.host and st.host.url ~= "" then VAO.OpenUrl(st.host.url) end
        end)
        w.cancel = button("Отмена", 20, 350, W - 40, 44, function() send("cancelLogin") end)
    elseif page == "lib" then
        header("Медиатека")
        menu:Input{ text = st.filter, placeholder = "Поиск", x = 20, y = 60, w = 380, h = 42, size = 16,
            onChange = function(v) st.filter = v or ""; st.listPage = 1; R.updateList() end }
        button("Обновить", 410, 60, 130, 42, function() send("refresh") end)
        w.rows = {}
        for i = 1, ROWS do
            w.rows[i] = button("", 20, 112 + (i - 1) * 54, W - 40, 48, function()
                local it = w.rowItems and w.rowItems[i]
                if not it then return end
                st.film, st.season, st.episode = it, nil, nil
                if it.k == "s" and it.s and it.s[1] then
                    st.season = 1
                    st.episode = 1
                elseif it.k == "c" then st.episode = 1 end
                st.page = "film"
            end, { align = "left" })
        end
        w.prev = button("<", 20, 548, 80, 44, function() st.listPage = math.max(1, st.listPage - 1); R.updateList() end)
        w.pageText = menu:Text{ text = "", x = 110, y = 558, w = 340, h = 30, size = 15, align = "center" }
        w.next = button(">", 460, 548, 80, 44, function() st.listPage = st.listPage + 1; R.updateList() end)
        w.now = button("", 20, 604, W - 40, 48, function() st.page = "remote" end)
        w.together = button("Смотреть вместе", 20, 660, W - 40, 48, function() st.page = "room" end)
        R.updateList()
    elseif page == "room" then
        header("Вместе")
        w.roomInfo = menu:Text{ text = "", x = 20, y = 56, w = W - 40, h = 150, size = 16, wrap = true }
        -- вне комнаты: код и кнопки; в комнате — выход (видимость переключает R.live)
        w.codeIn = menu:Input{ text = st.code, placeholder = "Код комнаты", x = 20, y = 214, w = 300, h = 44, size = 18,
            onChange = function(v) st.code = v or "" end,
            onSubmit = function(v) st.code = v or st.code; if st.code ~= "" then send("roomJoin", { code = st.code }) end end }
        w.join = button("Войти", 330, 214, W - 350, 44, function()
            if (st.code or "") ~= "" then send("roomJoin", { code = st.code }) end
        end)
        w.create = button("Создать комнату", 20, 270, W - 40, 50, function() send("roomCreate") end)
        w.leave = button("Выйти из комнаты", 20, 270, W - 40, 50, function() send("roomLeave") end)
        w.toRemote = button("Пульт", 20, 330, W - 40, 48, function() st.page = "remote" end)
        w.follow = button("Включить фильм комнаты", 20, 386, W - 40, 48, function() send("roomFollow"); st.page = "remote" end)
        button("Медиатека", 20, H - 100, W - 40, 44, function() st.page = "lib" end)
        R.live()
    elseif page == "film" then
        local it = st.film
        header(it.t or "")
        menu:Text{ text = kindLabel(it) .. (it.y and (" · " .. it.y) or ""), x = 20, y = 50, w = W - 40, h = 24, size = 14,
            color = "#A1A1AA" }
        local y = 86
        if it.k == "s" then
            button("−", 20, y, 60, 44, function() R.stepSeason(-1) end)
            w.season = menu:Text{ text = "", x = 90, y = y + 10, w = 160, h = 30, size = 16, align = "center" }
            button("+", 260, y, 60, 44, function() R.stepSeason(1) end)
            y = y + 54
            button("−", 20, y, 60, 44, function() R.stepEpisode(-1) end)
            w.episode = menu:Text{ text = "", x = 90, y = y + 10, w = 160, h = 30, size = 16, align = "center" }
            button("+", 260, y, 60, 44, function() R.stepEpisode(1) end)
            y = y + 60
        elseif it.k == "c" then
            button("−", 20, y, 60, 44, function() st.episode = math.max(1, st.episode - 1); R.live() end)
            w.episode = menu:Text{ text = "", x = 90, y = y + 10, w = 200, h = 30, size = 16, align = "center" }
            button("+", 300, y, 60, 44, function() st.episode = math.min(it.n or 1, st.episode + 1); R.live() end)
            y = y + 60
        end
        if not it.ok then
            menu:Text{ text = "Раздача ещё не готова: " .. (it.st or ""), x = 20, y = y, w = W - 40, h = 50, size = 15, wrap = true }
            y = y + 60
        end
        local function play(release)
            send("play", { id = it.id, release = release or 0, season = st.season and R.seasonNumber() or -1,
                episode = st.episode or -1, fromStart = st.fromStart })
            st.page = "remote"
        end
        button("Смотреть (релиз как в плеерах)", 20, y, W - 40, 50, function() play(0) end)
        y = y + 58
        for i, rel in ipairs(it.rel or {}) do
            if i > 6 then break end
            button(rel.l or tostring(rel.id), 20, y, W - 40, 46, function() play(rel.id) end, { align = "left", size = 14 })
            y = y + 52
        end
        menu:Checkbox{ text = "С начала", checked = st.fromStart, x = 20, y = y + 6, w = 260, h = 36,
            onChange = function(v) st.fromStart = v and true or false end }
        button("Назад", 20, H - 100, W - 40, 44, function() st.page = "lib" end)
        R.live()
    elseif page == "remote" then
        header("Пульт")
        w.title = menu:Text{ text = "", x = 20, y = 50, w = W - 40, h = 50, size = 18, wrap = true }
        w.rel = menu:Text{ text = "", x = 20, y = 102, w = W - 40, h = 22, size = 13, color = "#A1A1AA" }
        w.time = menu:Text{ text = "", x = 20, y = 130, w = W - 40, h = 26, size = 16, align = "center" }
        w.seek = menu:Slider{ x = 20, y = 160, w = W - 40, h = 30, min = 0, max = 1, value = 0,
            onChange = function(v, _, final)
                st.dragSeek = not final
                if final then send("seek", { ms = math.floor(v * 1000) }) end
            end }
        local bw = (W - 40 - 4 * 8) / 5
        local labels = { { "−10 мин", -600000 }, { "−10 с", -10000 }, { "", 0 }, { "+10 с", 10000 }, { "+10 мин", 600000 } }
        for i, b in ipairs(labels) do
            local x = 20 + (i - 1) * (bw + 8)
            if b[2] == 0 then
                w.toggle = button("Пауза", x, 202, bw, 50, function() send("toggle") end)
            else
                button(b[1], x, 202, bw, 50, function() send("seekBy", { ms = b[2] }) end, { size = 14 })
            end
        end
        menu:Text{ text = "Громкость", x = 20, y = 268, w = 120, h = 26, size = 15 }
        w.vol = menu:Slider{ x = 140, y = 266, w = W - 160, h = 30, min = 0, max = 150, value = 100,
            onChange = function(v, _, final)
                st.dragVol = not final
                if final then send("volume", { v = math.floor(v + 0.5) }) end
            end }
        w.audio = button("", 20, 310, W - 40, 46, function() send("audio") end, { align = "left", size = 14 })
        w.subs = button("", 20, 364, W - 40, 46, function() send("sub") end, { align = "left", size = 14 })
        w.mute = menu:Checkbox{ text = "Глушить звук игры во время фильма", checked = true, x = 20, y = 424, w = W - 40, h = 36,
            onChange = function(v) send("opt", { key = "muteGame", on = v and true or false }) end }
        w.pauseGame = menu:Checkbox{ text = "Пауза фильма вместе с игрой", checked = true, x = 20, y = 466, w = W - 40, h = 36,
            onChange = function(v) send("opt", { key = "pauseWithGame", on = v and true or false }) end }
        w.roomLine = button("", 20, 580, W - 40, 44, function() st.page = "room" end, { align = "left", size = 14 })
        button("Стоп", 20, 520, (W - 50) / 2, 48, function() send("stop"); st.page = "lib" end)
        button("Медиатека", 30 + (W - 50) / 2, 520, (W - 50) / 2, 48, function() st.page = "lib" end)
        R.live()
    end
end

function R.seasonNumber()
    local s = st.film and st.film.s and st.film.s[st.season or 1]
    return s and s.n or -1
end

function R.stepSeason(d)
    local list = st.film.s or {}
    st.season = math.max(1, math.min(#list, (st.season or 1) + d))
    st.episode = 1
    R.live()
end

function R.stepEpisode(d)
    local s = st.film.s and st.film.s[st.season or 1]
    local n = s and #(s.e or {}) or 1
    st.episode = math.max(1, math.min(n, (st.episode or 1) + d))
    R.live()
end

function R.updateList()
    if st.built ~= "lib" then return end
    local list = filtered()
    local pages = math.max(1, math.ceil(#list / ROWS))
    st.listPage = math.max(1, math.min(pages, st.listPage))
    w.rowItems = {}
    for i = 1, ROWS do
        local it = list[(st.listPage - 1) * ROWS + i]
        w.rowItems[i] = it
        local row = w.rows[i]
        if it then
            local text = (it.t or "?") .. (it.y and (" (" .. it.y .. ")") or "") .. "  · " .. kindLabel(it)
            if not it.ok then text = text .. "  · не готово" end
            setText(row, text)
            if row._hidden then row._hidden = false; row:Show() end
        elseif not row._hidden then
            row._hidden = true
            row:Hide()
        end
    end
    setText(w.pageText, string.format("%d из %d · %d", st.listPage, pages, #list))
end

-- Изменения, которые не требуют пересоздать страницу: тексты, положение, флажки.
function R.live()
    local h = st.host or {}
    local p = h.play
    local line = (h.busy ~= nil and h.busy ~= "") and h.busy or (h.msg or "")
    if p and p.buf then line = "Буферизация…" end
    setText(w.status, line)
    if st.built == "nohost" then
        local state, msg
        if VAO.HelperStatus then state, msg = VAO.HelperStatus(HELPER) end
        local known = io.open("host.txt", "r")
        if known then known:close() end
        setText(w.info, (known and "Хост не запущен." or "Хост ещё не запускали: один раз запустите suslik-cinema.exe вручную — дальше он запускается отсюда.")
            .. "\nПрограмма запуска: " .. tostring(state or "—") .. ((msg and msg ~= "") and (" — " .. msg) or ""))
    elseif st.built == "login" then
        local has = (h.code or "") ~= ""
        setText(w.code, has and h.code or "")
        setText(w.url, has and ("Подтвердите код на сайте (с телефона или ПК):\n" .. (h.url or "")) or "Войдите, чтобы смотреть фильмы из медиатеки.")
        if has then w.get:Hide() w.open:Show() w.cancel:Show() else w.get:Show() w.open:Hide() w.cancel:Hide() end
    elseif st.built == "lib" then
        local r = h.room or {}
        setText(w.together, r["in"] and ("Вместе: комната " .. (r.code or "") .. (r.host and " (вы ведущий)" or "")) or "Смотреть вместе")
        if p then
            setText(w.now, "Сейчас: " .. (p.title or "") .. " — пульт")
            if w.now._hidden ~= false then w.now._hidden = false; w.now:Show() end
        elseif w.now._hidden ~= true then
            w.now._hidden = true
            w.now:Hide()
        end
    elseif st.built == "room" then
        local r = h.room or {}
        local function vis(wd, on)
            if wd._vis ~= on then wd._vis = on; if on then wd:Show() else wd:Hide() end end
        end
        if r["in"] then
            local who = r.host and "Вы ведущий: пауза, перемотка и выбор фильма — у вас."
                or ("Ведущий: " .. (r.hostName or "") .. ". Фильм и место — как у него.")
            local names = table.concat(r.members or {}, ", ")
            local film = (r.title or "") ~= "" and ("Фильм: " .. r.title .. "\n" .. (r.line or "")) or "Фильм ещё не выбран."
            setText(w.roomInfo, "Комната " .. (r.code or "") .. (r.connected == false and "  (нет связи…)" or "") .. "\n" .. who ..
                "\nУчастники: " .. names .. "\n" .. film)
        else
            setText(w.roomInfo, (r.busy and "Подключаюсь…" or "Смотрите фильм вместе с друзьями на ПК, телефоне или ТВ: создайте комнату и скажите код или войдите по коду друга.")
                .. ((r.error or "") ~= "" and ("\n" .. r.error) or ""))
        end
        vis(w.codeIn, not r["in"])
        vis(w.join, not r["in"])
        vis(w.create, not r["in"])
        vis(w.leave, r["in"] and true or false)
        vis(w.toRemote, p ~= nil)
        vis(w.follow, (r["in"] and not r.host and (r.title or "") ~= "" and not r.watching) and true or false)
    elseif st.built == "film" then
        local it = st.film
        if it.k == "s" then
            local s = it.s and it.s[st.season or 1]
            setText(w.season, "Сезон " .. tostring(s and s.n or "?"))
            local e = s and s.e and s.e[st.episode or 1]
            setText(w.episode, "Серия " .. tostring(e or "?"))
        elseif it.k == "c" then
            setText(w.episode, string.format("Фильм %d из %d", st.episode or 1, it.n or 1))
        end
    elseif st.built == "remote" and p then
        setText(w.title, p.title or "")
        setText(w.rel, p.rel or "")
        setText(w.time, clock(p.pos) .. " / " .. clock(p.dur))
        if not st.dragSeek and p.dur and p.dur > 0 and w.seek._max ~= p.dur then
            w.seek._max = p.dur
            w.seek:Set({ max = p.dur })
        end
        if not st.dragSeek and w.seek._v ~= p.pos then w.seek._v = p.pos; w.seek:SetValue(p.pos or 0) end
        if not st.dragVol and w.vol._v ~= p.vol then w.vol._v = p.vol; w.vol:SetValue(p.vol or 100) end
        setText(w.toggle, p.paused and "Играть" or "Пауза")
        local function sel(list, none)
            for _, t in ipairs(list or {}) do if t.sel then return t.l end end
            return none
        end
        setText(w.audio, "Озвучка: " .. sel(p.audio, "—") .. "  (" .. #(p.audio or {}) .. ")")
        setText(w.subs, "Субтитры: " .. sel(p.subs, "выкл.") .. "  (" .. #(p.subs or {}) .. ")")
        local r = h.room or {}
        setText(w.roomLine, r["in"] and ("Вместе · комната " .. (r.code or "") .. " · " .. (r.host and "вы ведущий" or ("ведущий " .. (r.hostName or ""))))
            or "Смотреть вместе…")
        if w.mute._v ~= h.muteGame then w.mute._v = h.muteGame; w.mute:SetValue(h.muteGame and true or false) end
        if w.pauseGame._v ~= h.pauseWithGame then w.pauseGame._v = h.pauseWithGame; w.pauseGame:SetValue(h.pauseWithGame and true or false) end
    end
end

local function wantedPage()
    if not alive() then return "nohost" end
    local h = st.host
    if h.site == false then return "nosite" end
    if (h.code or "") ~= "" or h.auth == 0 then return "login" end
    if h.auth ~= 1 then return "wait" end
    if st.page == "remote" and not h.play and (h.busy or "") == "" then st.page = "lib" end
    if st.page == "film" and not st.film then st.page = "lib" end
    return st.page
end

local function refresh()
    local page = wantedPage()
    local key = page == "film" and ("film" .. tostring(st.film.id)) or page
    if key ~= st.builtKey then
        st.builtKey = key
        build(page)
    else
        R.live()
    end
end

function R.poll()
    if not menu then return end
    local visible = menu:IsVisible()
    st.polls = st.polls + 1
    if not visible and st.polls % 4 ~= 0 then return end -- без окна — раз в секунду
    local raw = readFile("host.json")
    local h = raw and decode(raw)
    if h and h.seq and h.seq == h["end"] then
        st.host = h
        if h.libRev ~= st.libRev then
            local lraw = readFile("library.json")
            local lib = lraw and decode(lraw)
            if lib and lib.seq == lib["end"] and lib.seq == h.libRev then
                st.lib = lib.items or {}
                st.libRev = h.libRev
                if st.built == "lib" then R.updateList() end
            end
        end
        if h.msgN and h.msgN ~= st.msgN then
            if st.msgN >= 0 and (h.msg or "") ~= "" and VAO.Toast then VAO.Toast(h.msg) end
            st.msgN = h.msgN
        end
    end
    if visible then refresh() end
end

function R.init()
    VAO = GetMod("VsAPIOverlay")
    if not VAO or not VAO.Menu then
        print("[SuslikCinema] V's API Overlay не найден: пульт на телефоне выключен")
        return
    end
    math.randomseed(os.time())
    st.sid = tostring(os.time()) .. "-" .. tostring(math.random(100000, 999999))
    menu = VAO.Menu("SuslikCinema_remote", { mod = MOD, x = 160, y = 80, w = W, h = H, title = " Suslik Cinema ",
        closable = true,
        onShow = function()
            st.builtKey = nil
            R.poll()
            -- хоста нет — запускаем сразу (helper сам ничего не делает, если хост уже идёт)
            if not alive() and VAO.StartHelper then VAO.StartHelper(HELPER) end
        end })
    if VAO.RegisterHelper then
        VAO.RegisterHelper{ id = HELPER, mod = MOD, script = "companion/cinema_helper.py", name = "Suslik Cinema host",
            autostart = false, restart = false }
    end
    VAO.RegisterApp{ id = MOD, name = "Suslik Cinema", glyph = "S", color = "#E11D48", menu = menu, mod = MOD }
    VAO.Every(0.25, R.poll)
    print("[SuslikCinema] пульт на телефоне VAO готов")
end

return R
