// Перенос desktop/.../trackers/BrowserDetector.kt и EdgeBrowserBridge.kt.
#include "BrowserBridge.h"

#include "../AppDir.h"
#include "../player/PlayerLog.h"

#include "suslik/core/Safety.h"
#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"

#include <QDir>
#include <QElapsedTimer>
#include <QEventLoop>
#include <QFile>
#include <QFileInfo>
#include <QHash>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMutex>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QProcess>
#include <QSettings>
#include <QTcpServer>
#include <QThread>
#include <QTimer>
#include <QWebSocket>
#include <atomic>
#include <deque>

#ifdef Q_OS_WIN
#include <windows.h>
#include <tlhelp32.h>
#endif

namespace suslik::app {

using namespace suslik::core;
#define S QStringLiteral

namespace {

Store &trackerStore() { return Store::of(S("local_trackers")); }

QString envDir(const char *name) { return qEnvironmentVariable(name); }

QMutex g_exeLock;
QHash<int, QString> g_exeCache;

QString findInRegistry(const QString &exeName) {
#ifdef Q_OS_WIN
    for (const QString root : {S("HKEY_LOCAL_MACHINE"), S("HKEY_CURRENT_USER")}) {
        QSettings reg(root + S("\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\") + exeName, QSettings::NativeFormat);
        QString p = reg.value(S("Default")).toString().trimmed();
        if (p.startsWith(QLatin1Char('"')) && p.endsWith(QLatin1Char('"')) && p.size() > 1) p = p.mid(1, p.size() - 2);
        if (!p.isEmpty() && QFileInfo(p).isFile()) return p;
    }
#else
    Q_UNUSED(exeName)
#endif
    return {};
}

} // namespace

// ---- браузеры ----

QList<BrowserType> Browsers::all() {
    return {BrowserType::FIREFOX, BrowserType::EDGE, BrowserType::CHROME, BrowserType::YANDEX,
            BrowserType::OPERA, BrowserType::OPERA_GX, BrowserType::BRAVE, BrowserType::VIVALDI};
}

QString Browsers::key(BrowserType t) {
    switch (t) {
    case BrowserType::FIREFOX: return S("FIREFOX");
    case BrowserType::EDGE: return S("EDGE");
    case BrowserType::CHROME: return S("CHROME");
    case BrowserType::YANDEX: return S("YANDEX");
    case BrowserType::OPERA: return S("OPERA");
    case BrowserType::OPERA_GX: return S("OPERA_GX");
    case BrowserType::BRAVE: return S("BRAVE");
    case BrowserType::VIVALDI: return S("VIVALDI");
    }
    return {};
}

std::optional<BrowserType> Browsers::fromKey(const QString &k) {
    for (const auto t : all())
        if (key(t) == k) return t;
    return std::nullopt;
}

QString Browsers::displayName(BrowserType t) {
    switch (t) {
    case BrowserType::FIREFOX: return S("Mozilla Firefox");
    case BrowserType::EDGE: return S("Microsoft Edge");
    case BrowserType::CHROME: return S("Google Chrome");
    case BrowserType::YANDEX: return S("Яндекс Браузер");
    case BrowserType::OPERA: return S("Opera");
    case BrowserType::OPERA_GX: return S("Opera GX");
    case BrowserType::BRAVE: return S("Brave");
    case BrowserType::VIVALDI: return S("Vivaldi");
    }
    return {};
}

QString Browsers::icon(BrowserType t) {
    switch (t) {
    case BrowserType::FIREFOX: return S("🦊");
    case BrowserType::YANDEX: return S("🔴");
    case BrowserType::OPERA: return S("⭕");
    case BrowserType::OPERA_GX: return S("🎮");
    case BrowserType::BRAVE: return S("🦁");
    case BrowserType::VIVALDI: return S("⚡");
    default: return S("🌐");
    }
}

QString Browsers::executable(BrowserType t) {
    {
        QMutexLocker l(&g_exeLock);
        if (g_exeCache.contains(int(t))) return g_exeCache.value(int(t));
    }
    const QString local = envDir("LOCALAPPDATA");
    const QString pf = qEnvironmentVariableIsSet("ProgramFiles") ? envDir("ProgramFiles") : S("C:\\Program Files");
    const QString pf86 = qEnvironmentVariableIsSet("ProgramFiles(x86)") ? envDir("ProgramFiles(x86)") : S("C:\\Program Files (x86)");
    QStringList c;
    QString exeName;
    switch (t) {
    case BrowserType::EDGE:
        c = {pf86 + S("\\Microsoft\\Edge\\Application\\msedge.exe"), pf + S("\\Microsoft\\Edge\\Application\\msedge.exe"),
             local + S("\\Microsoft\\Edge\\Application\\msedge.exe")};
        exeName = S("msedge.exe");
        break;
    case BrowserType::CHROME:
        c = {pf + S("\\Google\\Chrome\\Application\\chrome.exe"), pf86 + S("\\Google\\Chrome\\Application\\chrome.exe"),
             local + S("\\Google\\Chrome\\Application\\chrome.exe")};
        exeName = S("chrome.exe");
        break;
    case BrowserType::YANDEX:
        c = {local + S("\\Yandex\\YandexBrowser\\Application\\browser.exe"), pf + S("\\Yandex\\YandexBrowser\\Application\\browser.exe"),
             pf86 + S("\\Yandex\\YandexBrowser\\Application\\browser.exe")};
        exeName = S("browser.exe");
        break;
    case BrowserType::BRAVE:
        c = {pf + S("\\BraveSoftware\\Brave-Browser\\Application\\brave.exe"), pf86 + S("\\BraveSoftware\\Brave-Browser\\Application\\brave.exe"),
             local + S("\\BraveSoftware\\Brave-Browser\\Application\\brave.exe")};
        exeName = S("brave.exe");
        break;
    case BrowserType::OPERA:
        c = {local + S("\\Programs\\Opera\\opera.exe"), pf + S("\\Opera\\launcher.exe"), pf86 + S("\\Opera\\launcher.exe")};
        exeName = S("opera.exe");
        break;
    case BrowserType::OPERA_GX:
        c = {local + S("\\Programs\\Opera GX\\opera.exe"), pf + S("\\Opera GX\\launcher.exe")};
        exeName = S("opera.exe");
        break;
    case BrowserType::VIVALDI:
        c = {local + S("\\Vivaldi\\Application\\vivaldi.exe"), pf + S("\\Vivaldi\\Application\\vivaldi.exe")};
        exeName = S("vivaldi.exe");
        break;
    case BrowserType::FIREFOX:
        c = {pf + S("\\Mozilla Firefox\\firefox.exe"), pf86 + S("\\Mozilla Firefox\\firefox.exe")};
        exeName = S("firefox.exe");
        break;
    }
    QString found;
#ifdef Q_OS_WIN
    for (const auto &p : c)
        if (!p.startsWith(QLatin1Char('\\')) && QFileInfo(p).isFile()) { found = p; break; }
    if (found.isEmpty()) found = findInRegistry(exeName);
#else
    // Проверка на Linux (разработка): Chromium из системы под видом Edge/Chrome
    Q_UNUSED(c)
    Q_UNUSED(exeName)
    if (t == BrowserType::EDGE || t == BrowserType::CHROME) {
        for (const auto &p : {S("/usr/bin/chromium"), S("/usr/bin/chromium-browser"), S("/usr/bin/google-chrome")})
            if (QFileInfo(p).isFile()) { found = p; break; }
        if (found.isEmpty() && qEnvironmentVariableIsSet("SM_CHROMIUM")) found = qEnvironmentVariable("SM_CHROMIUM");
    }
#endif
    QMutexLocker l(&g_exeLock);
    g_exeCache.insert(int(t), found);
    return found;
}

QString DetectedBrowser::title() const {
    return Browsers::icon(type) + QLatin1Char(' ') + Browsers::displayName(type) +
           (!profileName.isEmpty() && profileName != S("Default") ? S(" (%1)").arg(profileName) : QString());
}

QList<DetectedBrowser> detectBrowsers() {
    {
        QMutexLocker l(&g_exeLock);
        g_exeCache.clear(); // окно «Трекеры» открыли: браузер могли поставить только что
    }
    QList<DetectedBrowser> out;
    const QString local = envDir("LOCALAPPDATA"), roaming = envDir("APPDATA");

    // 1. Firefox
    if (!roaming.isEmpty()) {
        const QDir ff(roaming + S("/Mozilla/Firefox/Profiles"));
        for (const auto &p : ff.entryInfoList(QDir::Dirs | QDir::NoDotAndDotDot)) {
            const QString db = p.absoluteFilePath() + S("/cookies.sqlite");
            if (QFile::exists(db)) out.append({BrowserType::FIREFOX, p.fileName(), db, {}});
        }
    }

    auto checkChromium = [&](BrowserType type, const QString &userData) {
        const QDir dir(userData);
        if (userData.isEmpty() || !dir.exists()) return false;
        const QString localState = dir.exists(S("Local State")) ? dir.filePath(S("Local State")) : QString();
        QStringList profiles;
        if (dir.exists(S("Default"))) profiles << S("Default");
        for (const auto &e : dir.entryList(QDir::Dirs | QDir::NoDotAndDotDot))
            if ((e.startsWith(S("Profile ")) || e == S("Default")) && !profiles.contains(e)) profiles << e;
        bool any = false;
        for (const auto &p : profiles) {
            const QString c1 = dir.filePath(p + S("/Network/Cookies")), c2 = dir.filePath(p + S("/Cookies"));
            const QString cookie = QFile::exists(c1) ? c1 : QFile::exists(c2) ? c2 : QString();
            if (!cookie.isEmpty()) { out.append({type, p, cookie, localState}); any = true; }
        }
        return any;
    };
    auto withFallback = [&](BrowserType type, const QString &userData) {
        const bool found = !userData.isEmpty() && checkChromium(type, userData);
        if (!found && !Browsers::executable(type).isEmpty()) {
            const QDir prof(BrowserBridge::profileDir(type));
            out.append({type, S("Default"), prof.filePath(S("Default/Network/Cookies")),
                        prof.exists(S("Local State")) ? prof.filePath(S("Local State")) : QString()});
        }
    };
    const auto at = [](const QString &base, const char *rel) { return base.isEmpty() ? QString() : base + QLatin1Char('/') + QString::fromUtf8(rel); };
    withFallback(BrowserType::EDGE, at(local, "Microsoft/Edge/User Data"));
    withFallback(BrowserType::CHROME, at(local, "Google/Chrome/User Data"));
    withFallback(BrowserType::YANDEX, at(local, "Yandex/YandexBrowser/User Data"));
    withFallback(BrowserType::OPERA, at(roaming, "Opera Software/Opera Stable"));
    withFallback(BrowserType::OPERA_GX, at(roaming, "Opera Software/Opera GX Stable"));
    withFallback(BrowserType::BRAVE, at(local, "BraveSoftware/Brave-Browser/User Data"));
    withFallback(BrowserType::VIVALDI, at(local, "Vivaldi/User Data"));
    return out;
}

// ---- процесс браузера ----

namespace {

#ifdef Q_OS_WIN
static std::atomic<DWORD> s_activeVisiblePid{0};

struct SM_UNICODE_STRING {
    USHORT Length;
    USHORT MaximumLength;
    PWSTR Buffer;
};

using pfnNtQueryInformationProcess = NTSTATUS(NTAPI *)(
    HANDLE ProcessHandle,
    ULONG ProcessInformationClass,
    PVOID ProcessInformation,
    ULONG ProcessInformationLength,
    PULONG ReturnLength
);

QString getProcessCommandLine(DWORD pid) {
    static auto pNtQuery = []() -> pfnNtQueryInformationProcess {
        HMODULE ntdll = GetModuleHandleW(L"ntdll.dll");
        if (!ntdll) ntdll = LoadLibraryW(L"ntdll.dll");
        return ntdll ? reinterpret_cast<pfnNtQueryInformationProcess>(GetProcAddress(ntdll, "NtQueryInformationProcess")) : nullptr;
    }();
    if (!pNtQuery) return {};

    HANDLE hProc = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!hProc) return {};

    ULONG retLen = 0;
    // ProcessCommandLineInformation = 60
    pNtQuery(hProc, 60, nullptr, 0, &retLen);
    if (retLen == 0) {
        CloseHandle(hProc);
        return {};
    }

    QByteArray buf(int(retLen), 0);
    NTSTATUS status = pNtQuery(hProc, 60, buf.data(), retLen, &retLen);
    CloseHandle(hProc);

    if (status != 0) return {};
    auto *uStr = reinterpret_cast<SM_UNICODE_STRING *>(buf.data());
    if (!uStr->Buffer || uStr->Length == 0) return {};
    return QString::fromWCharArray(uStr->Buffer, int(uStr->Length / sizeof(wchar_t)));
}

void cleanOrphanTrackerProcesses() {
    static const QStringList kProfiles = {
        S("edge_tracker_profile"),
        S("chrome_tracker_profile"),
        S("yandex_tracker_profile"),
        S("brave_tracker_profile"),
        S("opera_tracker_profile"),
        S("opera_gx_tracker_profile"),
        S("vivaldi_tracker_profile"),
        S("chromium_tracker_profile"),
        S("_tracker_profile")
    };

    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return;

    PROCESSENTRY32W pe{};
    pe.dwSize = sizeof pe;
    QList<DWORD> pidsToKill;
    const DWORD myPid = GetCurrentProcessId();
    const DWORD activeVis = s_activeVisiblePid.load();

    if (Process32FirstW(snap, &pe)) {
        do {
            if (pe.th32ProcessID == 0 || pe.th32ProcessID == myPid) continue;
            if (activeVis != 0 && (pe.th32ProcessID == activeVis || pe.th32ParentProcessID == activeVis)) continue;
            const QString cmd = getProcessCommandLine(pe.th32ProcessID);
            if (cmd.isEmpty()) continue;
            for (const auto &prof : kProfiles) {
                if (cmd.contains(prof, Qt::CaseInsensitive)) {
                    pidsToKill.append(pe.th32ProcessID);
                    break;
                }
            }
        } while (Process32NextW(snap, &pe));
    }
    CloseHandle(snap);

    for (DWORD pid : pidsToKill) {
        if (activeVis != 0 && pid == activeVis) continue;
        PlayerLog::write(S("[trackers] cleanOrphanTrackerProcesses: terminating orphan pid %1").arg(pid));
        HANDLE h = OpenProcess(PROCESS_TERMINATE, FALSE, pid);
        if (h) {
            TerminateProcess(h, 0);
            CloseHandle(h);
        } else {
            // нет прав открыть процесс — тогда taskkill (отдельный процесс, ждём его: только в этом редком случае)
            QProcess::execute(S("taskkill"), {S("/F"), S("/PID"), QString::number(pid)});
        }
    }
}
#else
void cleanOrphanTrackerProcesses() {}
#endif

/// Процесс браузера и всё, что его держит: Job Object (закрыть — и все процессы браузера закрыты), скрытый рабочий стол.
struct Proc {
    qint64 pid = 0;
#ifdef Q_OS_WIN
    HANDLE process = nullptr, thread = nullptr, job = nullptr;
    HDESK desk = nullptr;
#endif
    QProcess *qp = nullptr;

    bool running() const {
#ifdef Q_OS_WIN
        if (process) return WaitForSingleObject(process, 0) == WAIT_TIMEOUT;
#endif
        return qp && qp->state() != QProcess::NotRunning;
    }
    bool waitExit(int ms) {
#ifdef Q_OS_WIN
        if (process) return WaitForSingleObject(process, DWORD(ms)) == WAIT_OBJECT_0;
#endif
        return !qp || qp->waitForFinished(ms);
    }
    void kill() {
#ifdef Q_OS_WIN
        // 1. Собираем всё дерево дочерних процессов (GPU, renderers, utility, crashpad)
        QSet<DWORD> toKill;
        if (pid > 0) {
            toKill.insert(DWORD(pid));
            HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
            if (snap != INVALID_HANDLE_VALUE) {
                PROCESSENTRY32W pe{};
                pe.dwSize = sizeof pe;
                QHash<DWORD, QList<DWORD>> children;
                if (Process32FirstW(snap, &pe)) {
                    do {
                        children[pe.th32ParentProcessID].append(pe.th32ProcessID);
                    } while (Process32NextW(snap, &pe));
                }
                CloseHandle(snap);

                QList<DWORD> q;
                q.append(DWORD(pid));
                while (!q.isEmpty()) {
                    DWORD parent = q.takeFirst();
                    for (DWORD child : children.value(parent)) {
                        if (!toKill.contains(child)) {
                            toKill.insert(child);
                            q.append(child);
                        }
                    }
                }
            }
        }
        if (job) { TerminateJobObject(job, 0); CloseHandle(job); job = nullptr; }
        for (DWORD p : toKill) {
            HANDLE h = OpenProcess(PROCESS_TERMINATE, FALSE, p);
            if (h) {
                TerminateProcess(h, 0);
                CloseHandle(h);
            }
        }
        if (process) {
            TerminateProcess(process, 0);
            CloseHandle(process);
            process = nullptr;
        }
        if (thread) { CloseHandle(thread); thread = nullptr; }
        if (desk) { CloseDesktop(desk); desk = nullptr; }
#endif
        if (qp) {
            qp->kill();
            qp->waitForFinished(1000);
            delete qp; qp = nullptr;
        }
        pid = 0;
#ifdef Q_OS_WIN
        cleanOrphanTrackerProcesses();
#endif
    }
};

enum class Launch { Hidden, Headless, Visible };

#ifdef Q_OS_WIN
/// По правилам CommandLineToArgvW: путь профиля с «\» в конце иначе съедал закрывающую кавычку.
QString quoteArg(const QString &a) { return core::Safety::quoteWindowsArg(a); }

HANDLE killOnCloseJob(HANDLE process) {
    HANDLE job = CreateJobObjectW(nullptr, nullptr);
    if (!job) return nullptr;
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION info{};
    info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK;
    SetInformationJobObject(job, JobObjectExtendedLimitInformation, &info, sizeof info);
    if (!AssignProcessToJobObject(job, process)) { CloseHandle(job); return nullptr; }
    return job;
}
#endif

/// Запуск браузера. Hidden — на скрытом рабочем столе (не вышло создать его — без окна, --headless=new).
bool launch(Proc &p, const QString &exe, QStringList args, Launch mode) {
#ifdef Q_OS_WIN
    HDESK desk = nullptr;
    if (mode == Launch::Hidden) {
        desk = CreateDesktopW(L"SuslikHiddenDesktop", nullptr, nullptr, 0, GENERIC_ALL, nullptr);
        if (!desk) mode = Launch::Headless; // иначе окно браузера выскочило бы поверх всего
    }
    if (mode == Launch::Headless) args.prepend(S("--headless=new"));
    QString cmd = quoteArg(QDir::toNativeSeparators(exe));
    for (const auto &a : args) cmd += QLatin1Char(' ') + quoteArg(a);
    std::wstring wcmd = cmd.toStdWString();
    wchar_t deskName[] = L"SuslikHiddenDesktop";
    STARTUPINFOW si{};
    si.cb = sizeof si;
    if (desk) si.lpDesktop = deskName;
    PROCESS_INFORMATION pi{};
    if (!CreateProcessW(nullptr, wcmd.data(), nullptr, nullptr, FALSE, CREATE_SUSPENDED, nullptr, nullptr, &si, &pi)) {
        if (desk) CloseDesktop(desk);
        return false;
    }
    p.process = pi.hProcess;
    p.thread = pi.hThread;
    p.pid = pi.dwProcessId;
    p.desk = desk;
    // в Job Object — только для фонового/безголового запуска, чтобы не ломать песочницу и жизненный цикл видимого Edge
    if (mode != Launch::Visible) {
        p.job = killOnCloseJob(pi.hProcess);
    }
    ResumeThread(pi.hThread);
    return true;
#else
    if (mode != Launch::Visible) args.prepend(S("--headless=new"));
    p.qp = new QProcess;
    p.qp->setProcessChannelMode(QProcess::ForwardedErrorChannel);
    p.qp->setStandardOutputFile(QProcess::nullDevice());
    p.qp->start(exe, args);
    if (!p.qp->waitForStarted(5000)) { delete p.qp; p.qp = nullptr; return false; }
    p.pid = p.qp->processId();
    return true;
#endif
}

int freePort() {
    QTcpServer s;
    s.listen(QHostAddress::LocalHost, 0);
    const int port = s.serverPort();
    s.close();
    return port;
}

QStringList browserArgs(int port, const QString &profile, bool visible = false) {
    QStringList args = {
        S("--remote-debugging-port=%1").arg(port),
        S("--disable-blink-features=AutomationControlled"),
        S("--user-data-dir=") + QDir::toNativeSeparators(profile),
        S("--no-first-run"),
        S("--no-default-browser-check")
    };
    if (!visible) {
        args << S("--disable-background-mode")
             << S("--no-service-autorun")
             << S("--disable-breakpad")
             << S("--password-store=basic");
    }
    return args;
}

/// Строка JS из значения: «"текст"» с экранированием.
QString jsQuote(const QString &s) {
    const QByteArray a = QJsonDocument(QJsonArray{s}).toJson(QJsonDocument::Compact);
    return QString::fromUtf8(a.mid(1, a.size() - 2));
}

} // namespace

// ---- рабочий поток ----

/// Всё, что делает браузер, — в одном потоке и по одному (как mutex у Kotlin-версии): запросы пишутся «сверху вниз»,
/// ожидания крутят события потока (сокет CDP, ответы HTTP).
class BrowserBridge::Worker : public QObject {
public:
    static constexpr int IDLE_SHUTDOWN_MS = 15000;
    /// Длинные адреса GET — формой POST: сервер отвечает 414 на строку запроса больше ~8 КБ.
    static constexpr int MAX_GET_URL = 6000;

    std::atomic<bool> aborting{false};
    bool m_stopRequested = false;

    Worker() {
        m_idle.setParent(this);
        m_idle.setSingleShot(true);
        connect(&m_idle, &QTimer::timeout, this, [this] { scheduleStop(); });
    }

    void enqueue(std::function<void()> job) {
        m_stopRequested = false;
        m_queue.push_back(std::move(job));
        if (!m_running) pump();
    }

    void scheduleStop() {
        m_stopRequested = true;
        if (!m_running && m_queue.empty()) {
            m_stopRequested = false;
            stopInternal();
        }
    }

    void shutdownNow() {
        aborting = true;
        m_stopRequested = true;
        m_queue.clear();
        stopInternal(false);
    }

    // ---- задания (поток браузера) ----

    struct SearchOut { std::optional<QString> json; QString error; };
    SearchOut search(const QString &baseUrl, const QString &query, const CookieList &cookies) {
        QElapsedTimer clock; clock.start();
        const auto over = [&] { return clock.elapsed() > 25000 || aborting || m_stopRequested; };
        QString err;
        const int port = ensureRunning(&err);
        if (!port) return {std::nullopt, {}};
        Tab tab(this, port);
        if (!tab.ok) return {std::nullopt, {}};
        setCookies(tab, baseUrl, cookies, false);

        const QString base = trimSlash(baseUrl);
        // Отсев на стороне трекера: только разделы кино, сериалов, мультфильмов и аниме
        if (!m_videoForums) loadVideoForumIds(tab, base);
        const QStringList forums = m_videoForums.value_or(QStringList());
        const QString fParam = forums.isEmpty() ? QString() : S("f=") + forums.join(QLatin1Char(',')) + QLatin1Char('&');
        const QString target = base + S("/forum/tracker.php?") + fParam + S("nm=") + QString::fromLatin1(Cp1251::urlEncode(query));
        if (target.length() <= MAX_GET_URL) tab.cdp.send(S("Page.navigate"), {{S("url"), target}});
        else postTrackerSearch(tab, base, forums, query);

        static const QString js = S(R"js((() => {
  if (window.__smPosted) return '';
  const table = document.getElementById('tor-tbl');
  if (!table) {
    if (document.querySelector('input[name="login_username"]')) return '__LOGIN__';
    if (document.body && (document.body.innerText.includes('Не найдено') || document.body.innerText.includes('Результатов поиска: 0'))) return '[]';
    if (/just a moment|один момент/i.test(document.title) || document.querySelector('#challenge-form, .cf-turnstile, iframe[src*="challenges.cloudflare.com"]')) return '__CF__';
    return '';
  }
  const rows = table.querySelectorAll('tr.hl-tr, tr.tCenter, tr[id^="trs-tr-"]');
  if (!rows || rows.length === 0) return '[]';
  const items = [];
  for (const r of rows) {
    const link = r.querySelector('a.tLink, a[href*="viewtopic.php?t="]');
    if (!link) continue;
    const href = link.getAttribute('href') || '';
    const tMatch = href.match(/viewtopic\.php\?t=(\d+)/);
    const topicId = tMatch ? tMatch[1] : '';
    if (!topicId) continue;
    const title = link.textContent.trim();
    if (!title) continue;
    const forumEl = r.querySelector('a.f, a[href*="tracker.php?f="]');
    const forum = forumEl ? forumEl.textContent.trim() : '';
    const sizeEl = r.querySelector('td.tor-size');
    const sizeBytes = sizeEl ? (parseInt(sizeEl.getAttribute('data-ts_text') || '0', 10) || 0) : 0;
    const seedEl = r.querySelector('td.seedmed');
    const seeds = seedEl ? (parseInt(seedEl.textContent.replace(/\D/g, ''), 10) || 0) : 0;
    const leechEl = r.querySelector('td.leechmed');
    const leeches = leechEl ? (parseInt(leechEl.textContent.replace(/\D/g, ''), 10) || 0) : 0;
    const magnetEl = r.querySelector('a[href^="magnet:"]');
    const magnet = magnetEl ? magnetEl.getAttribute('href') : '';
    items.push({ topicId, title, forum, sizeBytes, seeds, leeches, magnet });
  }
  return JSON.stringify(items);
})())js");
        QString last;
        for (int attempt = 0; attempt < 28 && !over(); ++attempt) {
            sleepMs(400);
            const QString v = evalString(tab.cdp, js);
            if (v == S("__LOGIN__")) return {std::nullopt, S("куки не приняты — обновите bb_session в «Трекеры»")};
            if (v.startsWith(QLatin1Char('[')) && v.endsWith(QLatin1Char(']'))) return {v, {}};
            if (!v.isEmpty()) last = v;
        }
        if (last == S("__CF__")) return {std::nullopt, S("Cloudflare не пропустил проверку — повторите поиск позже")};
        return {std::nullopt, {}};
    }

    QByteArray torrent(const QString &baseUrl, const QString &topicId, const CookieList &cookies) {
        QString err;
        const int port = ensureRunning(&err);
        if (!port) return {};
        Tab tab(this, port);
        if (!tab.ok) return {};
        setCookies(tab, baseUrl, cookies, false);
        tab.cdp.send(S("Page.navigate"), {{S("url"), trimSlash(baseUrl) + S("/forum/viewtopic.php?t=") + topicId}});
        for (int i = 0; i < 16 && !aborting; ++i) {
            sleepMs(400);
            const auto r = tab.cdp.send(S("Runtime.evaluate"), {{S("expression"), S("document.readyState === 'complete'")}, {S("returnByValue"), true}});
            if (r && r->value(S("result")).toObject().value(S("result")).toObject().value(S("value")).toBool()) break;
        }
        const QString js = S(R"js(new Promise((resolve) => {
  fetch('/forum/dl.php?t=%1', { credentials: 'include' })
    .then(r => r.blob())
    .then(blob => { const reader = new FileReader(); reader.onload = () => resolve(reader.result.split(',')[1]); reader.readAsDataURL(blob); })
    .catch(() => resolve(''));
}))js").arg(topicId);
        const auto r = tab.cdp.send(S("Runtime.evaluate"), {{S("expression"), js}, {S("awaitPromise"), true}, {S("returnByValue"), true}}, 12000);
        const QString b64 = r ? r->value(S("result")).toObject().value(S("result")).toObject().value(S("value")).toString() : QString();
        return b64.isEmpty() ? QByteArray() : QByteArray::fromBase64(b64.toLatin1());
    }

    struct CheckOut { bool ok = false; QString message, cookie; };
    CheckOut check(const QString &baseUrl, const CookieList &cookies) {
        QString err;
        const int port = ensureRunning(&err);
        if (!port) return {false, err.isEmpty() ? S("Ошибка проверки подключения") : err, {}};
        Tab tab(this, port);
        if (!tab.ok) return {false, S("Не удалось подключиться к браузеру"), {}};
        setCookies(tab, baseUrl, cookies, true);
        tab.cdp.send(S("Page.navigate"), {{S("url"), trimSlash(baseUrl) + S("/forum/index.php")}});
        static const QString js = S("(() => { "
            "const u = document.querySelector('#logged-in-username, .logged-in-username, a[href*=\"mode=viewprofile\"] b'); "
            "if (u && u.innerText.trim()) return 'USER:' + u.innerText.trim(); "
            "if (document.querySelector('input[name=\"login_username\"]')) return '__LOGIN__'; "
            "if (/just a moment|один момент/i.test(document.title) || document.querySelector('#challenge-form, .cf-turnstile, iframe[src*=\"challenges.cloudflare.com\"]')) return '__CF__'; "
            "return ''; })()");
        for (int i = 0; i < 28 && !aborting && !m_stopRequested; ++i) {
            sleepMs(500);
            const QString v = evalString(tab.cdp, js);
            if (v.startsWith(S("USER:"))) {
                QString updated;
                if (const auto r = tab.cdp.send(S("Storage.getCookies"), {}))
                    updated = RuTracker::formatTrackerCookies(r->value(S("result")).toObject().value(S("cookies")).toArray(), baseUrl).value_or(QString());
                return {true, v.mid(5).trimmed(), updated};
            }
            if (v == S("__LOGIN__")) return {false, S("Сессия не принята (обновите bb_session в %1)").arg(BrowserBridge::activeBrowserName()), {}};
        }
        return {false, S("Не удалось проверить авторизацию (таймаут ответа)"), {}};
    }

    std::optional<QString> existing(BrowserType type, const QString &baseUrl) {
        const QString exe = Browsers::executable(type);
        if (exe.isEmpty()) return std::nullopt;
        const QString prof = BrowserBridge::profileDir(type);
        const QFileInfo db(prof + S("/Default/Network/Cookies"));
        if (!db.exists() || db.size() == 0) return std::nullopt;

        if (alive() && BrowserBridge::preferredType() == type) {
            // Фоновый браузер уже работает с этим профилем: второй на том же профиле не открыл бы порт CDP
            Cdp cdp;
            if (openBrowserSession(cdp, m_port))
                if (const auto r = cdp.send(S("Storage.getCookies"), {}))
                    return RuTracker::formatTrackerCookies(r->value(S("result")).toObject().value(S("cookies")).toArray(), baseUrl);
            return std::nullopt;
        }
        Proc tmp;
        const int port = freePort();
        QStringList args = browserArgs(port, prof);
        args << S("about:blank");
        if (!launch(tmp, exe, args, Launch::Headless)) return std::nullopt;
        std::optional<QString> res;
        for (int i = 0; i < 25 && !aborting && !m_stopRequested; ++i) {
            sleepMs(150);
            Cdp cdp;
            if (!openBrowserSession(cdp, port)) continue;
            if (const auto r = cdp.send(S("Storage.getCookies"), {}))
                res = RuTracker::formatTrackerCookies(r->value(S("result")).toObject().value(S("cookies")).toArray(), baseUrl);
            break;
        }
        tmp.kill();
        return res;
    }

    std::pair<bool, QString> login(BrowserType type, const QString &baseUrl, const std::function<void(QString)> &status) {
        PlayerLog::write(S("[trackers] login() started for %1, baseUrl=%2").arg(Browsers::displayName(type)).arg(baseUrl));
        const QString exe = Browsers::executable(type);
        const QString name = Browsers::displayName(type);
        if (exe.isEmpty()) return {false, tx(S("trackers.browser_not_found"), {{"browser", name}})};
        if (const auto have = existing(type, baseUrl); have && have->contains(S("bb_session"))) {
            PlayerLog::write(S("[trackers] login() found valid existing cookies in profile"));
            BrowserBridge::setPreferredType(type);
            return {true, *have};
        }
        stopInternal(); // фоновый браузер держит профиль
        sleepMs(300);   // даём ОС гарантированно освободить файловые блокировки SingletonLock профиля

        Proc proc;
        const int port = freePort();
        QStringList args = browserArgs(port, BrowserBridge::profileDir(type), true);
        args << trimSlash(baseUrl) + S("/forum/login.php");
        PlayerLog::write(S("[trackers] launching visible %1 on port %2").arg(name).arg(port));
        status(tx(S("trackers.browser_opening"), {{"browser", name}}));
        if (!launch(proc, exe, args, Launch::Visible)) {
            PlayerLog::write(S("[trackers] launch visible %1 failed").arg(name));
            return {false, tx(S("trackers.browser_connect_failed"), {{"browser", name}})};
        }
#ifdef Q_OS_WIN
        s_activeVisiblePid = DWORD(proc.pid);
#endif
        PlayerLog::write(S("[trackers] visible %1 launched (pid %2), connecting to CDP...").arg(name).arg(proc.pid));
        Cdp cdp;
        bool connected = false;
        for (int i = 0; i < 30 && !connected && !aborting; ++i) { sleepMs(200); connected = openBrowserSession(cdp, port); }
        if (!connected) {
            PlayerLog::write(S("[trackers] visible %1 failed to connect to CDP on port %2").arg(name).arg(port));
#ifdef Q_OS_WIN
            s_activeVisiblePid = 0;
#endif
            proc.kill();
            return {false, tx(S("trackers.browser_connect_failed"), {{"browser", name}})};
        }
        PlayerLog::write(S("[trackers] visible %1 CDP connected, waiting for user login...").arg(name));
        status(tx(S("trackers.browser_login_prompt"), {{"browser", name}}));
        int consecutiveClosed = 0;
        for (int i = 0; i < 360 && !aborting; ++i) { // до 3 минут
            sleepMs(500);
            const bool running = proc.running();
            const bool wsConnected = (cdp.ws.state() == QAbstractSocket::ConnectedState);
            if (!running && !wsConnected) {
                // И процесс корня завершён, и веб-сокет CDP отключился
                ++consecutiveClosed;
                if (consecutiveClosed >= 2) {
                    PlayerLog::write(S("[trackers] visible %1 closed by user (proc dead and CDP disconnected)").arg(name));
#ifdef Q_OS_WIN
                    s_activeVisiblePid = 0;
#endif
                    proc.kill();
                    return {false, tx(S("trackers.browser_window_closed"))};
                }
            } else {
                consecutiveClosed = 0;
            }
            const auto r = cdp.send(S("Storage.getCookies"), {});
            if (!r) continue;
            const auto header = RuTracker::formatTrackerCookies(r->value(S("result")).toObject().value(S("cookies")).toArray(), baseUrl);
            if (header && header->contains(S("bb_session"))) {
                PlayerLog::write(S("[trackers] visible %1 login successful (cookie %2 chars)").arg(name).arg(header->size())); // сами cookie в журнал не пишем
                status(tx(S("trackers.browser_login_success")));
                BrowserBridge::setPreferredType(type);
                cdp.send(S("Browser.close"), {}, 1500);
                proc.waitExit(1500);
#ifdef Q_OS_WIN
                s_activeVisiblePid = 0;
#endif
                proc.kill();
                return {true, *header};
            }
        }
        PlayerLog::write(S("[trackers] visible %1 login loop ended (timeout or aborting=%2)").arg(name).arg(aborting.load()));
#ifdef Q_OS_WIN
        s_activeVisiblePid = 0;
#endif
        proc.kill();
        return {false, tx(S("trackers.browser_timeout"))};
    }

private:
    // ---- HTTP и CDP ----

    QNetworkAccessManager &nam() {
        if (!m_nam) m_nam = new QNetworkAccessManager(this);
        return *m_nam;
    }

    struct Http { bool ok = false; int code = 0; QByteArray body; };
    Http http(const QString &url, const QByteArray &verb = "GET", int timeoutMs = 2000) {
        QNetworkRequest req{QUrl(url)};
        req.setTransferTimeout(timeoutMs);
        QNetworkReply *r = nam().sendCustomRequest(req, verb);
        QEventLoop loop;
        QObject::connect(r, &QNetworkReply::finished, &loop, &QEventLoop::quit);
        QTimer::singleShot(timeoutMs + 200, &loop, &QEventLoop::quit);
        loop.exec();
        Http h;
        if (r->isFinished() && r->error() == QNetworkReply::NoError) {
            h.ok = true;
            h.code = r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
            h.body = r->readAll();
        } else {
            r->abort();
        }
        r->deleteLater();
        return h;
    }

    static void sleepMs(int ms) {
        QEventLoop loop;
        QTimer::singleShot(ms, &loop, &QEventLoop::quit);
        loop.exec();
    }

    /// Сессия CDP: команды по одной, ответ — по id.
    class Cdp {
    public:
        bool open(const QString &url, int timeoutMs = 5000) {
            QEventLoop loop;
            QObject::connect(&ws, &QWebSocket::connected, &loop, &QEventLoop::quit);
            QObject::connect(&ws, &QWebSocket::disconnected, &loop, &QEventLoop::quit);
            QObject::connect(&ws, &QWebSocket::textMessageReceived, &ws, [this](const QString &m) {
                const auto o = QJsonDocument::fromJson(m.toUtf8()).object();
                const int id = o.value(S("id")).toInt(-1);
                if (id >= 0 && m_waiting.contains(id)) m_replies.insert(id, o);
            });
            QTimer::singleShot(timeoutMs, &loop, &QEventLoop::quit);
            ws.open(QUrl(url));
            loop.exec();
            return ws.state() == QAbstractSocket::ConnectedState;
        }
        std::optional<QJsonObject> send(const QString &method, const QJsonObject &params, int timeoutMs = 7000) {
            if (ws.state() != QAbstractSocket::ConnectedState) return std::nullopt;
            const int id = m_next++;
            m_waiting.insert(id);
            ws.sendTextMessage(QString::fromUtf8(QJsonDocument(QJsonObject{{S("id"), id}, {S("method"), method}, {S("params"), params}})
                                                      .toJson(QJsonDocument::Compact)));
            QElapsedTimer t; t.start();
            while (!m_replies.contains(id) && t.elapsed() < timeoutMs && ws.state() == QAbstractSocket::ConnectedState) {
                QEventLoop loop;
                QObject::connect(&ws, &QWebSocket::textMessageReceived, &loop, &QEventLoop::quit);
                QObject::connect(&ws, &QWebSocket::disconnected, &loop, &QEventLoop::quit);
                QTimer::singleShot(int(std::max<qint64>(1, timeoutMs - t.elapsed())), &loop, &QEventLoop::quit);
                if (!m_replies.contains(id)) loop.exec();
            }
            m_waiting.remove(id);
            if (!m_replies.contains(id)) return std::nullopt;
            return m_replies.take(id);
        }
        void close() { if (ws.state() != QAbstractSocket::UnconnectedState) ws.close(); }
        ~Cdp() { close(); }
        QWebSocket ws;
    private:
        int m_next = 1;
        QSet<int> m_waiting;
        QHash<int, QJsonObject> m_replies;
    };

    /// Новая вкладка (закрывается сама).
    struct Tab {
        Tab(Worker *w, int port) : w(w), port(port) {
            const auto r = w->http(S("http://127.0.0.1:%1/json/new?about:blank").arg(port), "PUT", 3000);
            if (!r.ok || r.code != 200) return;
            const auto o = QJsonDocument::fromJson(r.body).object();
            id = o.value(S("id")).toString();
            ok = !id.isEmpty() && cdp.open(o.value(S("webSocketDebuggerUrl")).toString());
        }
        ~Tab() {
            cdp.close();
            if (!id.isEmpty()) w->http(S("http://127.0.0.1:%1/json/close/%2").arg(port).arg(id), "GET", 1500);
        }
        Worker *w;
        int port;
        QString id;
        Cdp cdp;
        bool ok = false;
    };

    bool openBrowserSession(Cdp &cdp, int port, int timeoutMs = 2000) {
        const auto r = http(S("http://127.0.0.1:%1/json/version").arg(port), "GET", timeoutMs);
        if (!r.ok || r.code != 200) return false;
        const QString ws = QJsonDocument::fromJson(r.body).object().value(S("webSocketDebuggerUrl")).toString();
        return !ws.isEmpty() && cdp.open(ws);
    }

    static QString evalString(Cdp &cdp, const QString &js) {
        const auto r = cdp.send(S("Runtime.evaluate"), {{S("expression"), js}, {S("returnByValue"), true}});
        return r ? r->value(S("result")).toObject().value(S("result")).toObject().value(S("value")).toString() : QString();
    }

    static QString trimSlash(QString s) { while (s.endsWith(QLatin1Char('/'))) s.chop(1); return s; }

    void setCookies(Tab &tab, const QString &baseUrl, const CookieList &cookies, bool withUrl) {
        const QString domain = QLatin1Char('.') + RuTracker::cookieDomain(baseUrl);
        for (const auto &[k, v] : cookies) {
            QJsonObject c{{S("name"), k}, {S("value"), v}, {S("domain"), domain}, {S("path"), S("/")}};
            if (withUrl) c.insert(S("url"), trimSlash(baseUrl) + QLatin1Char('/'));
            tab.cdp.send(S("Network.setCookie"), c);
        }
    }

    void loadVideoForumIds(Tab &tab, const QString &base) {
        tab.cdp.send(S("Page.navigate"), {{S("url"), base + S("/forum/tracker.php")}});
        static const QString js = S(R"js((() => {
  const sel = document.querySelector('select#fs-main, select[name="f[]"], select[name="f"]');
  if (!sel) return '';
  const keep = /кино|видео|мульт|аниме|сериал/i, drop = /обуч|музык|игр|программ|книг|софт/i;
  const ids = [];
  for (const g of sel.querySelectorAll('optgroup')) {
    const label = g.getAttribute('label') || '';
    if (drop.test(label)) continue;
    const whole = keep.test(label);
    for (const o of g.querySelectorAll('option')) {
      if (!/^\d+$/.test(o.value)) continue;
      const name = o.textContent || '';
      if (whole || (keep.test(name) && !drop.test(name))) ids.push(o.value);
    }
  }
  return JSON.stringify(ids);
})())js");
        for (int i = 0; i < 15 && !aborting; ++i) {
            sleepMs(400);
            const QString v = evalString(tab.cdp, js);
            if (v.startsWith(QLatin1Char('['))) {
                QStringList ids;
                for (const auto &x : QJsonDocument::fromJson(v.toUtf8()).array())
                    if (!ids.contains(x.toString())) ids << x.toString();
                if (!ids.isEmpty()) m_videoForums = ids;
                return;
            }
        }
        // список не получили — ищем без отбора разделов
    }

    void postTrackerSearch(Tab &tab, const QString &base, const QStringList &forums, const QString &query) {
        tab.cdp.send(S("Page.navigate"), {{S("url"), base + S("/forum/tracker.php")}});
        const QString js = S(R"js((() => {
  if (document.readyState !== 'complete' || !/tracker\.php$/.test(location.pathname)) return 'wait';
  const form = document.createElement('form');
  form.method = 'post'; form.action = 'tracker.php'; form.acceptCharset = document.characterSet;
  const add = (name, value) => { const i = document.createElement('input'); i.type = 'hidden'; i.name = name; i.value = value; form.appendChild(i); };
  for (const f of %1) add('f[]', f);
  add('nm', %2);
  document.body.appendChild(form);
  window.__smPosted = true;
  form.submit();
  return 'sent';
})())js").arg(QString::fromUtf8(QJsonDocument(QJsonArray::fromStringList(forums)).toJson(QJsonDocument::Compact)), jsQuote(query));
        for (int i = 0; i < 15 && !aborting; ++i) {
            sleepMs(400);
            if (evalString(tab.cdp, js) == S("sent")) return;
        }
    }

    // ---- фоновый браузер ----

    bool alive() {
        if (!m_port || !m_proc.running()) return false;
        const auto r = http(S("http://127.0.0.1:%1/json/version").arg(m_port), "GET", 2000);
        return r.ok && r.code == 200;
    }

    int ensureRunning(QString *err) {
        m_idle.stop();
        BrowserType type = BrowserBridge::preferredType();
        QString exe = Browsers::executable(type);
        for (const auto t : {BrowserType::EDGE, BrowserType::CHROME, BrowserType::YANDEX})
            if (exe.isEmpty()) exe = Browsers::executable(t);
        if (exe.isEmpty()) {
            *err = S("Подходящий браузер (Edge, Chrome, Яндекс) не найден на компьютере");
            return 0;
        }
        if (alive()) return m_port; // уже запущен: cookie и пройденная проверка Cloudflare при нём
        stopInternal();
        const int port = freePort();
        QStringList args = browserArgs(port, BrowserBridge::profileDir(type));
        args << S("about:blank");
        if (!launch(m_proc, exe, args, Launch::Hidden)) {
            *err = S("Не удалось запустить фоновый процесс браузера");
            return 0;
        }
        m_port = port;
        PlayerLog::write(S("[trackers] background %1 started (pid %2)").arg(Browsers::displayName(type)).arg(m_proc.pid));
        for (int i = 0; i < 30 && !aborting; ++i) { // до 6 с
            sleepMs(200);
            const auto r = http(S("http://127.0.0.1:%1/json/version").arg(port), "GET", 1000);
            if (r.ok && r.code == 200) return port;
        }
        stopInternal();
        *err = S("Не удалось запустить фоновый процесс браузера");
        return 0;
    }

    /// Просим браузер закрыться самому (допишет cookie и cf_clearance в профиль), потом закрываем всё.
    void stopInternal(bool graceful = true) {
        m_idle.stop();
        m_stopRequested = false;
        if (m_port && graceful && m_proc.running()) {
            const auto r = http(S("http://127.0.0.1:%1/json/version").arg(m_port), "GET", 300);
            if (r.ok && r.code == 200) {
                Cdp cdp;
                if (cdp.open(QJsonDocument::fromJson(r.body).object().value(S("webSocketDebuggerUrl")).toString(), 500)) {
                    cdp.ws.sendTextMessage(S(R"({"id":1,"method":"Browser.close"})"));
                    cdp.ws.flush();
                    m_proc.waitExit(500);
                }
            }
        }
        if (m_proc.pid) PlayerLog::write(S("[trackers] background browser closed"));
        m_proc.kill();
        m_port = 0;
    }

    void pump() {
        m_running = true;
        while (!m_queue.empty()) {
            auto job = std::move(m_queue.front());
            m_queue.pop_front();
            m_idle.stop();
            job();
        }
        m_running = false;
        if (!aborting) {
            if (m_stopRequested) {
                m_stopRequested = false;
                stopInternal();
            } else {
                // Браузер закрывается через 15 с без нового поиска
                m_idle.start(IDLE_SHUTDOWN_MS);
            }
        }
    }

    QNetworkAccessManager *m_nam = nullptr;
    Proc m_proc;
    int m_port = 0;
    QTimer m_idle;
    std::deque<std::function<void()>> m_queue;
    bool m_running = false;
    std::optional<QStringList> m_videoForums;
};

// ---- снаружи ----

BrowserBridge &BrowserBridge::instance() {
    static BrowserBridge b;
    return b;
}

BrowserBridge::BrowserBridge() {
#ifdef Q_OS_WIN
    cleanOrphanTrackerProcesses();
#endif
    m_thread = new QThread;
    m_thread->setObjectName(S("browser-bridge"));
    m_worker = new Worker;
    m_worker->moveToThread(m_thread);
    m_thread->start();
}

BrowserBridge::~BrowserBridge() { shutdown(); }

void BrowserBridge::post(std::function<void()> job) {
    QMetaObject::invokeMethod(m_worker, [w = m_worker, job = std::move(job)]() mutable { w->enqueue(std::move(job)); }, Qt::QueuedConnection);
}

namespace {
template <class F>
void deliver(QObject *ctx, F &&f) {
    if (ctx) QMetaObject::invokeMethod(ctx, std::forward<F>(f), Qt::QueuedConnection);
}
} // namespace

BrowserType BrowserBridge::preferredType() {
    const QString saved = trackerStore().get(S("tracker.browser_type")).value_or(QString());
    if (const auto t = Browsers::fromKey(saved); t && !Browsers::executable(*t).isEmpty()) return *t;
    for (const auto t : {BrowserType::EDGE, BrowserType::CHROME, BrowserType::YANDEX, BrowserType::BRAVE, BrowserType::OPERA, BrowserType::VIVALDI})
        if (!Browsers::executable(t).isEmpty()) return t;
    return BrowserType::EDGE;
}

void BrowserBridge::setPreferredType(BrowserType t) { trackerStore().put(S("tracker.browser_type"), Browsers::key(t)); }

bool BrowserBridge::isAnyAvailable() {
    for (const auto t : Browsers::all())
        if (Browsers::isChromium(t) && !Browsers::executable(t).isEmpty()) return true;
    return false;
}

QString BrowserBridge::profileDir(BrowserType t) {
    QString name;
    switch (t) {
    case BrowserType::EDGE: name = S("edge_tracker_profile"); break;
    case BrowserType::CHROME: name = S("chrome_tracker_profile"); break;
    case BrowserType::YANDEX: name = S("yandex_tracker_profile"); break;
    case BrowserType::BRAVE: name = S("brave_tracker_profile"); break;
    case BrowserType::OPERA: name = S("opera_tracker_profile"); break;
    case BrowserType::OPERA_GX: name = S("opera_gx_tracker_profile"); break;
    case BrowserType::VIVALDI: name = S("vivaldi_tracker_profile"); break;
    default: name = S("chromium_tracker_profile"); break;
    }
    const QString dir = AppDir::file(name);
    QDir().mkpath(dir);
    return dir;
}

void BrowserBridge::searchHtml(const QString &baseUrl, const QString &query, const CookieList &cookies, QObject *ctx,
                               std::function<void(std::optional<QString>, QString)> cb) {
    post([w = m_worker, baseUrl, query, cookies, ctx, cb = std::move(cb)] {
        const auto r = w->search(baseUrl, query, cookies);
        deliver(ctx, [cb, r] { cb(r.json, r.error); });
    });
}

void BrowserBridge::downloadTorrent(const QString &baseUrl, const QString &topicId, const CookieList &cookies, QObject *ctx,
                                    std::function<void(QByteArray)> cb) {
    post([w = m_worker, baseUrl, topicId, cookies, ctx, cb = std::move(cb)] {
        const QByteArray b = w->torrent(baseUrl, topicId, cookies);
        deliver(ctx, [cb, b] { cb(b); });
    });
}

void BrowserBridge::checkConnection(const QString &baseUrl, const CookieList &cookies, QObject *ctx,
                                    std::function<void(bool, QString, QString)> cb) {
    post([w = m_worker, baseUrl, cookies, ctx, cb = std::move(cb)] {
        const auto r = w->check(baseUrl, cookies);
        deliver(ctx, [cb, r] { cb(r.ok, r.message, r.cookie); });
    });
}

void BrowserBridge::existingCookies(BrowserType t, const QString &baseUrl, QObject *ctx, std::function<void(std::optional<QString>)> cb) {
    post([w = m_worker, t, baseUrl, ctx, cb = std::move(cb)] {
        const auto r = w->existing(t, baseUrl);
        deliver(ctx, [cb, r] { cb(r); });
    });
}

void BrowserBridge::loginAndCapture(BrowserType t, const QString &baseUrl, QObject *ctx, std::function<void(QString)> onStatus,
                                    std::function<void(bool, QString)> cb) {
    post([w = m_worker, t, baseUrl, ctx, onStatus = std::move(onStatus), cb = std::move(cb)] {
        const auto r = w->login(t, baseUrl, [ctx, onStatus](const QString &s) { deliver(ctx, [onStatus, s] { onStatus(s); }); });
        deliver(ctx, [cb, r] { cb(r.first, r.second); });
    });
}

void BrowserBridge::stopAsync() {
    QMetaObject::invokeMethod(m_worker, [w = m_worker] { w->scheduleStop(); }, Qt::QueuedConnection);
}

void BrowserBridge::shutdown() {
    if (!m_thread) return;
    m_worker->aborting = true;
    QMetaObject::invokeMethod(m_worker, [w = m_worker] { w->shutdownNow(); }, Qt::QueuedConnection);
    m_thread->quit();
    // Долгое задание (вход) замечает aborting за полсекунды; не дождались — браузер всё равно закроется с программой
    // (Job Object закрывается вместе с процессом)
    if (m_thread->wait(2000)) {
        delete m_worker;
        delete m_thread;
    }
    m_worker = nullptr;
    m_thread = nullptr;
#ifdef Q_OS_WIN
    cleanOrphanTrackerProcesses();
#endif
}

void BrowserBridge::cleanOrphanProcesses() {
#ifdef Q_OS_WIN
    cleanOrphanTrackerProcesses();
#endif
}

} // namespace suslik::app
