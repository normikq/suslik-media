-- Тест пульта remote.lua без игры: поддельный VAO, файлы хоста в папке запуска.
-- Запуск (LuaJIT — тот же движок, что у CET):  cd <временная папка> && luajit <путь>/cinema/mod/tests/remote_test.lua <путь к SuslikCinema>
local modDir = assert(arg[1], "usage: luajit remote_test.lua <SuslikCinema dir>")
package.path = modDir .. "/?.lua;" .. package.path

local failures, checks = 0, 0
local function check(cond, what)
    checks = checks + 1
    if not cond then failures = failures + 1; print("FAIL: " .. what) end
end

-- ---- поддельный VAO: виджеты запоминают свойства, кнопки можно «нажать» ----
local menus = {}
local VAO = { started = {}, toasts = {}, opened = {} }
local Widget = {}
Widget.__index = Widget
function Widget:SetText(t) self.props.text = t end
function Widget:SetValue(v) if self.kind == "checkbox" then self.props.checked = v else self.props.value = v end end
function Widget:Set(p) for k, v in pairs(p) do self.props[k] = v end end
function Widget:Show() self.props.visible = true end
function Widget:Hide() self.props.visible = false end
local Menu = {}
Menu.__index = Menu
function Menu:Add(kind, props)
    local wd = setmetatable({ kind = kind, props = props }, Widget)
    self.widgets[#self.widgets + 1] = wd
    return wd
end
for _, k in ipairs({ "Text", "Button", "Slider", "Checkbox", "Input" }) do
    Menu[k] = function(self, p) return self:Add(k:lower(), p) end
end
function Menu:Clear() self.widgets = {} end
function Menu:IsVisible() return self.visible end
function Menu:Show() self.visible = true; if self.props.onShow then self.props.onShow(self) end end
function VAO.Menu(id, props)
    local m = setmetatable({ id = id, props = props, widgets = {}, visible = false }, Menu)
    menus[id] = m
    return m
end
function VAO.RegisterApp(def) VAO.app = def end
function VAO.RegisterHelper(def) VAO.helper = def end
function VAO.StartHelper(id) VAO.started[#VAO.started + 1] = id end
function VAO.HelperStatus() return "stopped", "" end
function VAO.Every(_, fn) VAO.tick = fn end
function VAO.Toast(t) VAO.toasts[#VAO.toasts + 1] = t end
function VAO.OpenUrl(u) VAO.opened[#VAO.opened + 1] = u end
function GetMod(name) if name == "VsAPIOverlay" then return VAO end end

local function write(name, text)
    local f = assert(io.open(name, "w"))
    f:write(text)
    f:close()
end
local function read(name)
    local f = io.open(name, "r")
    if not f then return nil end
    local s = f:read("*a")
    f:close()
    return s
end
local function find(m, pred)
    for _, wd in ipairs(m.widgets) do if pred(wd) then return wd end end
end
local function byText(m, text)
    return find(m, function(wd) return wd.props.text and tostring(wd.props.text):find(text, 1, true) end)
end
local function click(m, text)
    local b = byText(m, text)
    check(b and b.props.onClick, "есть кнопка «" .. text .. "»")
    if b and b.props.onClick then b.props.onClick(b) end
end
local function lastCmd()
    local s = read("remote.json") or ""
    local op = s:match('.*"op":"([^"]+)"')
    return op, s
end

os.remove("remote.json")
local status = function(extra)
    return string.format('{"seq":%d,"time":%d,"libRev":%d,"site":true,%s,"end":%d}\n', extra.seq, os.time(), extra.rev or 1,
        extra.body, extra.seq)
end

-- 1. хоста нет: страница запуска, helper запускается при открытии окна
local remote = dofile(modDir .. "/remote.lua")
remote.init()
local m = menus["SuslikCinema_remote"]
check(m ~= nil, "окно пульта создано")
check(VAO.app and VAO.app.id == "SuslikCinema", "значок на телефоне")
check(VAO.helper and VAO.helper.script == "companion/cinema_helper.py" and VAO.helper.autostart == false, "helper зарегистрирован, без автозапуска")
os.remove("host.json")
m:Show()
check(VAO.started[1] == "SuslikCinema_host", "окно открыли без хоста — helper запущен")
check(byText(m, "Запустить хост") ~= nil, "страница «хост не запущен»")

-- 2. хост жив, нужен вход: код, кнопка открытия страницы
write("host.json", status{ seq = 1, body = '"auth":0,"user":"","code":"","url":"","busy":"","msg":"","msgN":0' })
VAO.tick()
check(byText(m, "Получить код") ~= nil, "страница входа")
click(m, "Получить код")
check(lastCmd() == "login", "команда login")
write("host.json", status{ seq = 2, body = '"auth":0,"code":"E21-3B7","url":"https://x/media/link?code=E21-3B7","busy":"","msg":"","msgN":0' })
VAO.tick()
check(byText(m, "E21-3B7") ~= nil, "код на экране")
click(m, "Открыть страницу на ПК")
check(VAO.opened[1] == "https://x/media/link?code=E21-3B7", "страница открывается через VAO.OpenUrl")

-- 3. вошли: медиатека (русские названия, \u-экранирование, поиск без учёта регистра)
write("library.json", '{"seq":5,"items":[' ..
    '{"id":822307535,"t":"Апгрейд","y":2018,"k":"m","ok":true,"rel":[{"id":822307535,"l":"1080p · BDRemux (43.3 GB)"},{"id":798350249,"l":"1080p · BDRip"}]},' ..
    '{"id":42,"t":"Монстр \\u2014 история","k":"s","ok":true,"s":[{"n":1,"e":[1,2,3]},{"n":2,"e":[1,2]}],"rel":[{"id":42,"l":"1080p"}]},' ..
    '{"id":7,"t":"Ёлки","k":"m","ok":false,"st":"Качается 40%","rel":[]}' ..
    '],"end":5}\n')
write("host.json", status{ seq = 3, rev = 5, body = '"auth":1,"user":"V","code":"","url":"","busy":"","msg":"","msgN":0' })
VAO.tick()
check(byText(m, "Апгрейд (2018)") ~= nil, "плитка с годом")
check(byText(m, "Монстр — история") ~= nil, "\\u2014 разобран в UTF-8")
check(byText(m, "не готово") ~= nil, "неготовая раздача отмечена")
local input = find(m, function(wd) return wd.kind == "input" end)
input.props.onChange("АПГ")
local visibleRows = 0
for _, wd in ipairs(m.widgets) do
    if wd.kind == "button" and wd.props.visible ~= false and tostring(wd.props.text):find("·", 1, true) then visibleRows = visibleRows + 1 end
end
check(visibleRows == 1, "поиск «АПГ» нашёл одну плитку (русский регистр), найдено " .. visibleRows)
input.props.onChange("ёлки")
check(byText(m, "Ёлки") and byText(m, "Ёлки").props.visible ~= false, "поиск с ё")
input.props.onChange("")

-- 4. сериал: выбор сезона и серии, команда play с ними
click(m, "Монстр — история")
VAO.tick()
check(byText(m, "Сезон 1") ~= nil, "страница сериала")
local plus = {}
for _, wd in ipairs(m.widgets) do if wd.props.text == "+" then plus[#plus + 1] = wd end end
plus[1].props.onClick() -- сезон 2
plus[2].props.onClick() -- серия 2
check(byText(m, "Сезон 2") and byText(m, "Серия 2"), "сезон 2, серия 2")
click(m, "Смотреть")
local op, raw = lastCmd()
check(op == "play" and raw:find('"id":42', 1, true) and raw:find('"season":2', 1, true) and raw:find('"episode":2', 1, true),
    "play сериала с сезоном и серией: " .. raw)

-- 5. играет: пульт, время, дорожки, перемотка, флажки
write("host.json", status{ seq = 4, rev = 5, body = '"auth":1,"code":"","busy":"","msg":"Готово","msgN":1,"muteGame":true,"pauseWithGame":false,' ..
    '"play":{"title":"Монстр — история · S2E2","rel":"1080p","pos":3725,"dur":6006,"paused":false,"buf":false,"vol":100,' ..
    '"audio":[{"id":1,"l":"DTS [en]","sel":false},{"id":2,"l":"MVO [ru]","sel":true}],"subs":[]}' })
VAO.tick()
check(byText(m, "1:02:05 / 1:40:06") ~= nil, "время на пульте")
check(byText(m, "Озвучка: MVO [ru]  (2)") ~= nil, "выбранная озвучка")
check(byText(m, "Субтитры: выкл.  (0)") ~= nil, "субтитров нет")
local pg = find(m, function(wd) return wd.kind == "checkbox" and tostring(wd.props.text):find("Пауза фильма", 1, true) end)
check(pg and pg.props.checked == false, "флажок паузы — как у хоста")
check(VAO.toasts[1] == "Готово", "новое сообщение хоста — всплывашка")
check(byText(m, "3D-звук: объёмный") ~= nil, "3D-звук по умолчанию — объёмный")
click(m, "3D-звук:")
op, raw = lastCmd()
check(op == "opt" and raw:find('"key":"audio3d"', 1, true) and raw:find('"v":0', 1, true), "3D-звук по кругу: объёмный → выкл: " .. raw)
local mf = find(m, function(wd) return wd.kind == "checkbox" and wd.props.text == "За стеной глуше" end)
mf.props.onChange(false)
op, raw = lastCmd()
check(op == "opt" and raw:find('"key":"muffle"', 1, true) and raw:find('"on":false', 1, true), "флажок «за стеной глуше»")
click(m, "+10 мин")
op, raw = lastCmd()
check(op == "seekBy" and raw:find('"ms":600000', 1, true), "+10 мин")
local seek = find(m, function(wd) return wd.kind == "slider" and wd.props.max == 6006 end)
check(seek ~= nil, "ползунок места по длине фильма")
seek.props.onChange(1800.4, seek, true)
op, raw = lastCmd()
check(op == "seek" and raw:find('"ms":1800400', 1, true), "перемотка ползунком")
click(m, "Пауза")
check(lastCmd() == "toggle", "пауза")
-- команды копятся (хост мог пропустить одно чтение), номера растут
local _, all = lastCmd()
local n1 = tonumber(all:match('"n":(%d+)'))
local nLast = tonumber(all:match('.*"n":(%d+)'))
check(nLast > n1, "в remote.json несколько последних команд по порядку")

-- 6. «Вместе»: вход по коду, создание, комната с участниками, выход
click(m, "Смотреть вместе")
VAO.tick()
check(byText(m, "Создать комнату") ~= nil, "страница «Вместе» вне комнаты")
local code = find(m, function(wd) return wd.kind == "input" and wd.props.placeholder == "Код комнаты" end)
check(code ~= nil, "поле кода комнаты")
code.props.onChange("AB12")
click(m, "Войти")
op, raw = lastCmd()
check(op == "roomJoin" and raw:find('"code":"AB12"', 1, true), "вход по коду: " .. raw)
click(m, "Создать комнату")
check(lastCmd() == "roomCreate", "создание комнаты")
write("host.json", status{ seq = 5, rev = 5, body = '"auth":1,"code":"","busy":"","msg":"Готово","msgN":1,"muteGame":true,"pauseWithGame":false,' ..
    '"room":{"in":true,"code":"AB12","host":false,"hostName":"Nora","members":["Nora","V"],"title":"Апгрейд","line":"Идёт · 12:34","connected":true},' ..
    '"play":{"title":"Апгрейд","rel":"1080p","pos":754,"dur":6006,"paused":false,"buf":false,"vol":100,"audio":[],"subs":[]}' })
VAO.tick()
check(byText(m, "Ведущий: Nora") ~= nil, "в комнате: ведущий")
check(byText(m, "Участники: Nora, V") ~= nil, "участники")
check(byText(m, "Идёт · 12:34") ~= nil, "состояние фильма комнаты")
local leave = byText(m, "Выйти из комнаты")
check(leave and leave.props.visible ~= false, "кнопка выхода видна")
check(byText(m, "Создать комнату").props.visible == false, "«Создать» в комнате спрятана")
check(byText(m, "Включить фильм комнаты").props.visible == true, "гость не смотрит фильм комнаты — кнопка видна")
click(m, "Включить фильм комнаты")
check(lastCmd() == "roomFollow", "гость снова за ведущим")
VAO.tick()
click(m, "Вместе · комната AB12")
VAO.tick()
click(m, "Выйти из комнаты")
check(lastCmd() == "roomLeave", "выход из комнаты")
click(m, "Пульт")
VAO.tick()
check(byText(m, "Вместе · комната AB12 · ведущий Nora") ~= nil, "строка комнаты на пульте")

-- 7. испорченный (недописанный) host.json не ломает пульт
write("host.json", '{"seq":9,"time":' .. os.time() .. ',"auth":1,"play":{"title":"об')
VAO.tick()
check(byText(m, "12:34 / 1:40:06") ~= nil, "недописанный файл пропущен, прежнее состояние осталось")

-- 8. краевые случаи ревью 08.11: суррогаты, null в массиве, дорожка без подписи, повторный запуск helper
write("library.json", '{"seq":6,"items":[{"id":1,"t":"A\\uD83D\\u0041B","k":"m","ok":true,"rel":[null,{"id":1,"l":"x"}]},' ..
    '{"id":2,"t":"\\uD83D\\uDE00","k":"m","ok":true,"rel":[]}],"end":6}\n')
write("host.json", status{ seq = 10, rev = 6, body = '"auth":1,"code":"","busy":"","msg":"","msgN":1,' ..
    '"play":{"title":"T","rel":"r","pos":1,"dur":10,"paused":false,"buf":false,"vol":100,"audio":[{"id":1,"sel":true}],"subs":[]}' })
VAO.tick()
check(byText(m, "Озвучка: ?") ~= nil, "выбранная дорожка без подписи — «?», без ошибки")
click(m, "Медиатека")
VAO.tick()
check(byText(m, "A\239\191\189AB") ~= nil, "одинокий высокий суррогат — знак замены, следующая буква цела")
check(byText(m, "\240\159\152\128") ~= nil, "пара суррогатов — один символ")
local started = #VAO.started
os.remove("host.json")
m.visible = false
m:Show()
m.visible = false
m:Show()
check(#VAO.started == started, "окно открыли ещё раз за 20 с без хоста — второй helper не запускается")
-- хост замолчал: последний host.json старше 4 с
write("host.json", '{"seq":11,"time":' .. (os.time() - 10) .. ',"auth":1,"end":11}\n')
VAO.tick()
click(m, "Запустить хост")
check(#VAO.started == started + 1, "кнопка «Запустить хост» запускает сразу")

print(string.format("%d checks, %d failed", checks, failures))
os.exit(failures == 0 and 0 or 1)
