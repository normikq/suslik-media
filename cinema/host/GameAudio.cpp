#include "GameAudio.h"
#include "AppDir.h"
#include "PlayerLog.h"

#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>

#include <functional>

#ifdef Q_OS_WIN
#include <windows.h>
#include <audiopolicy.h>
#include <mmdeviceapi.h>
#endif

namespace suslik::cinema {

namespace {

QString stateFile() { return app::AppDir::file(QStringLiteral("game-mute.json")); }

#ifdef Q_OS_WIN
template <class T> struct Com {
    T *p = nullptr;
    ~Com() { if (p) p->Release(); }
    T **put() { return &p; }
    T *operator->() const { return p; }
    explicit operator bool() const { return p != nullptr; }
};

QString exeOf(DWORD pid) {
    HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!h) return {};
    wchar_t buf[MAX_PATH];
    DWORD n = MAX_PATH;
    QString name;
    if (QueryFullProcessImageNameW(h, 0, buf, &n)) name = QString::fromWCharArray(buf, int(n)).section(QLatin1Char('\\'), -1);
    CloseHandle(h);
    return name;
}

/// Каждая сессия процесса exe на всех активных устройствах вывода: (идентификатор сессии, громкость).
/// Идентификатор сессии (не экземпляра) один и тот же после перезапуска игры — по нему и восстанавливаем.
/// -1 — Core Audio недоступен.
int forEachSession(const QString &exe, const std::function<void(const QString &, ISimpleAudioVolume *)> &fn) {
    const HRESULT init = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    const bool uninit = SUCCEEDED(init); // RPC_E_CHANGED_MODE — поток уже в другом режиме, это нормально
    int seen = 0;
    {
        Com<IMMDeviceEnumerator> en;
        if (FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator),
                                    reinterpret_cast<void **>(en.put())))) {
            if (uninit) CoUninitialize();
            return -1;
        }
        Com<IMMDeviceCollection> devs;
        UINT count = 0;
        if (SUCCEEDED(en->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, devs.put()))) devs->GetCount(&count);
        for (UINT d = 0; d < count; ++d) {
            Com<IMMDevice> dev;
            if (FAILED(devs->Item(d, dev.put()))) continue;
            Com<IAudioSessionManager2> mgr;
            if (FAILED(dev->Activate(__uuidof(IAudioSessionManager2), CLSCTX_ALL, nullptr, reinterpret_cast<void **>(mgr.put()))))
                continue;
            Com<IAudioSessionEnumerator> sessions;
            if (FAILED(mgr->GetSessionEnumerator(sessions.put()))) continue;
            int n = 0;
            sessions->GetCount(&n);
            for (int i = 0; i < n; ++i) {
                Com<IAudioSessionControl> ctl;
                if (FAILED(sessions->GetSession(i, ctl.put()))) continue;
                Com<IAudioSessionControl2> ctl2;
                if (FAILED(ctl->QueryInterface(__uuidof(IAudioSessionControl2), reinterpret_cast<void **>(ctl2.put()))))
                    continue;
                DWORD pid = 0;
                if (FAILED(ctl2->GetProcessId(&pid)) || pid == 0) continue;
                if (exeOf(pid).compare(exe, Qt::CaseInsensitive) != 0) continue;
                LPWSTR id = nullptr;
                QString instance;
                if (SUCCEEDED(ctl2->GetSessionIdentifier(&id)) && id) {
                    instance = QString::fromWCharArray(id);
                    CoTaskMemFree(id);
                }
                Com<ISimpleAudioVolume> vol;
                if (FAILED(ctl->QueryInterface(__uuidof(ISimpleAudioVolume), reinterpret_cast<void **>(vol.put())))) continue;
                ++seen;
                fn(instance, vol.p);
            }
        }
    }
    if (uninit) CoUninitialize();
    return seen;
}
#endif

} // namespace

GameAudio::GameAudio(QString exeName) : m_exe(std::move(exeName)) {
    QFile f(stateFile());
    if (!f.open(QIODevice::ReadOnly)) return;
    const QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
    if (o.value(QStringLiteral("exe")).toString(m_exe).compare(m_exe, Qt::CaseInsensitive) != 0) return;
    for (const auto &v : o.value(QStringLiteral("sessions")).toArray()) m_pending << v.toString();
}

GameAudio::~GameAudio() {
    if (muted()) unmute();
}

int GameAudio::mute() {
#ifdef Q_OS_WIN
    int changed = 0;
    const int seen = forEachSession(m_exe, [&](const QString &id, ISimpleAudioVolume *v) {
        BOOL was = FALSE;
        v->GetMute(&was);
        const bool ourBefore = m_pending.removeAll(id) > 0;
        if (was) {
            // приглушено нами раньше (Windows помнит) — наше; иначе приглушил игрок — не трогаем
            if (ourBefore && !m_ours.contains(id)) { m_ours << id; ++changed; }
            return;
        }
        if (SUCCEEDED(v->SetMute(TRUE, nullptr))) {
            if (!m_ours.contains(id)) m_ours << id;
            ++changed;
        }
    });
    m_active = true;
    saveState();
    app::PlayerLog::write(QStringLiteral("[cinema] звук игры: приглушено %1 из %2 сессий %3").arg(changed).arg(seen).arg(m_exe));
    return seen < 0 ? -1 : changed;
#else
    return -1;
#endif
}

int GameAudio::unmute() {
#ifdef Q_OS_WIN
    int changed = 0;
    QStringList left = m_ours;
    forEachSession(m_exe, [&](const QString &id, ISimpleAudioVolume *v) {
        if (!left.contains(id)) return;
        if (SUCCEEDED(v->SetMute(FALSE, nullptr))) { ++changed; left.removeAll(id); }
    });
    for (const QString &id : left)
        if (!m_pending.contains(id)) m_pending << id;
    m_ours.clear();
    m_active = false;
    saveState();
    app::PlayerLog::write(left.isEmpty() ? QStringLiteral("[cinema] звук игры: возвращено %1 сессий").arg(changed)
                                         : QStringLiteral("[cinema] звук игры: возвращено %1 сессий, %2 — когда игра запустится")
                                               .arg(changed).arg(left.size()));
    return changed;
#else
    return -1;
#endif
}

int GameAudio::restorePending() {
#ifdef Q_OS_WIN
    if (m_pending.isEmpty()) return 0;
    int changed = 0;
    forEachSession(m_exe, [&](const QString &id, ISimpleAudioVolume *v) {
        if (m_pending.contains(id) && SUCCEEDED(v->SetMute(FALSE, nullptr))) { ++changed; m_pending.removeAll(id); }
    });
    if (changed > 0) {
        saveState();
        app::PlayerLog::write(QStringLiteral("[cinema] звук игры возвращён после прошлого сеанса: %1 сессий").arg(changed));
    }
    return changed;
#else
    return 0;
#endif
}

void GameAudio::saveState() const {
    QStringList all = m_ours;
    for (const QString &id : m_pending)
        if (!all.contains(id)) all << id;
    if (all.isEmpty()) {
        QFile::remove(stateFile());
        return;
    }
    QSaveFile f(stateFile());
    if (!f.open(QIODevice::WriteOnly)) return;
    f.write(QJsonDocument(QJsonObject{{QStringLiteral("exe"), m_exe},
                                      {QStringLiteral("sessions"), QJsonArray::fromStringList(all)}})
                .toJson());
    f.commit();
}

int GameAudio::recoverAfterCrash(const QString &exeName) {
    GameAudio a(exeName);
    return a.restorePending();
}

int GameAudio::unmuteAll(const QString &exeName) {
#ifdef Q_OS_WIN
    int changed = 0;
    const int seen = forEachSession(exeName, [&](const QString &, ISimpleAudioVolume *v) {
        BOOL was = FALSE;
        v->GetMute(&was);
        if (was && SUCCEEDED(v->SetMute(FALSE, nullptr))) ++changed;
    });
    QFile::remove(stateFile());
    return seen < 0 ? -1 : changed;
#else
    Q_UNUSED(exeName);
    return -1;
#endif
}

} // namespace suslik::cinema
