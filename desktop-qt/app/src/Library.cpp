#include "Library.h"
#include "AudioAttach.h"
#include "AppDir.h"

#include "suslik/core/ApiClient.h"
#include "suslik/core/CloudSync.h"
#include "suslik/core/CustomAudio.h"
#include "suslik/core/LibraryKeep.h"
#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"
#include "suslik/core/UrlCoding.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDesktopServices>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QUrl>
#include <algorithm>

namespace suslik::app {

namespace {
qint64 now() { return QDateTime::currentMSecsSinceEpoch(); }
constexpr qint64 DAY = 86400000LL;
constexpr qint64 POSTER_CAP = 100LL << 20;
constexpr qint64 POSTER_UNUSED_DAYS = 7;
constexpr int AI_IDS_PER_CALL = 10; // предел сервера (routes/aititle.js MAX_IDS)

QString S(const char *s) { return QString::fromUtf8(s); }
QString after(const QString &v, QChar c) { const auto i = v.indexOf(c); return i < 0 ? QString() : v.mid(i + 1); }
QString before(const QString &v, QChar c) { const auto i = v.indexOf(c); return i < 0 ? v : v.left(i); }

/// Раздача только с аудио — внешняя озвучка к фильму: играет внутри фильма, отдельной плиткой её нет.
bool isVoiceOverOnly(const Magnet &m) {
    bool any = false;
    for (const MFile &f : m.files) {
        if (f.isFolder) continue;
        any = true;
        if (!CustomAudio::isSupported(f.path)) return false;
    }
    return any;
}

// То же правило, что у сайта (website/classify.js).
const QRegularExpression &cartoonRe() {
    static const QRegularExpression re(S("мульт(?!сериал)|анимаци|\\bcartoons?\\b|\\banimation\\b|\\bPixar\\b|\\bDreamWorks\\b|\\bDisney\\b"),
                                       QRegularExpression::CaseInsensitiveOption);
    return re;
}

QStringList nameTags(const LibraryItem &item) {
    static const QRegularExpression dv(S("\\b(dolby[ ._-]?vision|dovi|dv)\\b"), QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression hdr(S("\\bhdr(10)?(\\+|plus)?\\b"), QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression dts(S("\\bdts(?:[ ._:-]?(hd|x))?(?:[ ._-]?(ma))?\\b"), QRegularExpression::CaseInsensitiveOption);
    QString text = item.m.name;
    for (const MFile &f : item.m.files) text += QLatin1Char('\n') + f.path;
    QStringList out;
    if (dv.match(text).hasMatch()) out << S("Dolby Vision");
    if (hdr.match(text).hasMatch()) out << S("HDR");
    const auto m = dts.match(text);
    if (m.hasMatch()) {
        const QString kind = m.captured(1).toLower();
        const bool ma = !m.captured(2).isEmpty();
        out << (kind == QLatin1String("hd") ? (ma ? S("DTS-HD MA") : S("DTS-HD")) : kind == QLatin1String("x") ? S("DTS:X") : S("DTS"));
    }
    return out;
}
} // namespace

Library::Library(ApiClient &api, CloudSync &sync, QObject *parent)
    : QObject(parent), m_api(api), m_sync(sync), m_libraryEtag(Store::of(S("library_etag"))), m_posterIndex(Store::of(S("posters"))),
      m_animIndex(Store::of(S("poster_anim"))), m_titleIndex(Store::of(S("poster_title"))), m_mediaIndex(Store::of(S("media_info"))),
      m_episodes(Store::of(S("last_episode"))), m_watch(Store::of(S("watch"))), m_hidden(Store::of(S("continue_hidden"))) {
    m_status = tx(S("library.nazhmite_obnovit_chtoby_zagruz"));
    m_profileName = tx(S("sidebar.profil_imya"));
    m_profileNote = tx(S("sidebar.profil_vhod_po_parolyu"));
    m_pollTimer.setInterval(2000);
    connect(&m_pollTimer, &QTimer::timeout, this, &Library::pollDeviceAuth);
}

void Library::bump() {
    // Несколько изменений подряд — один пересчёт страниц.
    // Не чаще раза в 400 мс: фоновые ответы (жанры, постеры, названия — каждые 120 мс) иначе пересобирали
    // страницы медиатеки несколько раз в секунду.
    if (m_quiet) { m_quietDirty = true; return; } // пачка ответов: пересчёт один раз в конце
    if (m_bumpQueued) return;
    m_bumpQueued = true;
    const qint64 wait = std::max<qint64>(0, m_bumpAt + 400 - QDateTime::currentMSecsSinceEpoch());
    QTimer::singleShot(int(wait), this, [this] {
        m_bumpQueued = false;
        m_bumpAt = QDateTime::currentMSecsSinceEpoch();
        ++m_version;
        emit versionChanged();
    });
}

void Library::setStatus(const QString &s, int kind) {
    m_status = s;
    m_statusKind = kind;
    emit statusChanged();
}

void Library::setAuth(int a) {
    if (m_auth == a) return;
    m_auth = a;
    emit authenticatedChanged();
    if (a == 1) afterSignIn();
}

const LibraryItem *Library::item(qint64 id) const {
    for (const auto &i : m_items)
        if (i.m.id == id) return &i;
    return nullptr;
}

QList<LibraryItem> Library::versionsOf(qint64 magnetId) const {
    if (m_versionsMap.isEmpty() && !m_items.isEmpty()) {
        const auto [shown, vMap] = groupVersions(m_items, [this](const LibraryItem &i) { return posterPathOf(i); });
        m_versionsMap = vMap;
    }
    if (m_versionsMap.contains(magnetId)) return m_versionsMap.value(magnetId);
    for (auto it = m_versionsMap.constBegin(); it != m_versionsMap.constEnd(); ++it) {
        for (const auto &item : it.value()) {
            if (item.m.id == magnetId) return it.value();
        }
    }
    if (const LibraryItem *single = item(magnetId)) return {*single};
    return {};
}

std::optional<core::PartyVariant> Library::serverProfile(qint64 magnetId) const {
    const auto it = m_serverProfiles.constFind(magnetId);
    if (it != m_serverProfiles.cend()) return *it;
    return std::nullopt;
}

void Library::publish(const QList<Magnet> &list) {
    QList<Magnet> library;
    m_voiceOvers.clear();
    for (const Magnet &m : list) (isVoiceOverOnly(m) ? m_voiceOvers : library) << m;
    m_items = prepareItems(library);
    m_versionsMap.clear();
    bump();
}

// ---- запуск ----
void Library::start() {
    restoreSnapshot();
    checkSession([this](std::optional<bool> ok) {
        if (!ok && !m_items.isEmpty()) refresh(); // проверка без сети не удалась: всё равно пробуем с сохранённой копией
    });
}

void Library::restoreSnapshot() {
    if (!m_items.isEmpty()) return;
    QFile f(AppDir::file(S("library.json")));
    if (!f.open(QIODevice::ReadOnly)) return;
    const auto doc = QJsonDocument::fromJson(f.readAll());
    if (!doc.isObject()) return;
    publish(parseLibrary(doc.object()));
    setStatus(tx(S("library.pokazana_sohranennaya_kopiya_o")), 0);
}

void Library::applyProfile(const std::optional<UserProfile> &u) {
    if (!u || u->isShared()) {
        m_profileName = tx(S("sidebar.profil_imya"));
        m_profileNote = tx(S("sidebar.profil_vhod_po_parolyu"));
        m_avatar.clear();
    } else {
        m_profileName = u->name.trimmed().isEmpty() ? tx(S("profile.user")) : u->name;
        m_profileNote = u->isAdmin() ? tx(S("profile.admin")) : tx(S("profile.member"));
        m_avatar = u->avatarUrl.value_or(QString());
    }
    emit profileChanged();
}

// Профиль ставится ДО authenticated: смена флага закрывает экран входа.
void Library::checkSession(std::function<void(std::optional<bool>)> done) {
    m_api.get(S(SessionInfo::PATH), [this, done](const ApiResult &r) {
        if (!r.ok()) { done(std::nullopt); return; }
        const SessionInfo s = SessionInfo::parse(r.json);
        // приложение Discord нашего сервера (активность «Смотрит»): запоминаем между запусками
        if (s.discordClientId) Store::of(QStringLiteral("settings")).put(QStringLiteral("discord_client_id"), s.discordClientId->trimmed());
        applyProfile(s.authenticated ? s.user : std::nullopt);
        m_sync.setUserId(s.authenticated && s.user ? std::optional<QString>(s.user->id) : std::nullopt);
        setAuth(s.authenticated ? 1 : 0);
        done(s.authenticated);
    });
}

void Library::login(const QString &password) {
    m_api.post(S("/api/login"), QJsonObject{{S("password"), password}}, [this](const ApiResult &r) {
        if (!r.ok()) { emit loginFinished(ApiClient::describe(r.error)); return; }
        checkSession([this](std::optional<bool> ok) {
            if (!ok) setAuth(1);
            emit loginFinished(QString());
        });
    });
}

void Library::startDeviceAuth() {
    m_api.post(S(DeviceAuth::START), DeviceAuth::startBody(S("Suslik Media Windows"), m_api.site()), [this](const ApiResult &r) {
        if (!r.ok()) { emit loginFinished(ApiClient::describe(r.error)); return; }
        // Ссылку сервер собирает из site_url; наша — запасная для старого сервера.
        m_pairing = DeviceAuth::parsePairing(r.json, m_api.site());
        if (!m_pairing) { emit loginFinished(tx(S("api.oshibka_servera"))); return; }
        emit pairingChanged();
        openPairingPage();
        m_pollTimer.start();
    });
}

void Library::openPairingPage() {
    if (m_pairing) QDesktopServices::openUrl(QUrl(m_pairing->url));
}

void Library::pollDeviceAuth() {
    if (!m_pairing) { m_pollTimer.stop(); return; }
    m_api.post(S(DeviceAuth::POLL), DeviceAuth::pollBody(*m_pairing), [this](const ApiResult &r) {
        if (!m_pairing) return;
        DevicePoll p;
        if (r.ok()) p = DeviceAuth::parsePoll(r.json);
        else if (r.error.kind == ApiError::Kind::Http) p = DeviceAuth::pollFailed(r.error.code); // 410: код истёк (10 мин)
        if (p.status == DevicePoll::Status::Authorized) {
            if (p.token) m_api.setDeviceToken(p.token); // токен устройства живёт дольше cookie-сессии
            applyProfile(p.user);
            m_sync.setUserId(p.user ? std::optional<QString>(p.user->id) : std::nullopt);
            m_pairing.reset();
            m_pollTimer.stop();
            emit pairingChanged();
            setAuth(1);
        } else if (p.status == DevicePoll::Status::Expired) {
            m_pairing.reset();
            m_pollTimer.stop();
            emit pairingChanged();
            emit loginFinished(tx(S("login.vremya_ozhidaniya_isteklo")));
        }
    });
}

void Library::cancelDeviceAuth() {
    m_pairing.reset();
    m_pollTimer.stop();
    emit pairingChanged();
}

void Library::logout() {
    m_api.post(S("/api/logout"), {}, [](const ApiResult &) {});
    m_api.setDeviceToken(std::nullopt);
    m_items.clear();
    m_versionsMap.clear();
    m_voiceOvers.clear();
    applyProfile(std::nullopt);
    m_sync.setUserId(std::nullopt);
    cancelDeviceAuth();
    QFile::remove(AppDir::file(S("library.json")));
    setAuth(0);
    bump();
}

void Library::afterSignIn() {
    refresh();
}

void Library::refreshThen(std::function<void()> done) {
    m_refreshWaiters.push_back(std::move(done));
    refresh();
}

void Library::refresh() {
    if (m_loading) return;
    m_loading = true;
    setStatus(tx(S("library.zagruzka_torrentov_i_faylov")), 0);
    // Не изменилось с прошлой загрузки (тот же ETag): крошечный 304 вместо всего списка.
    const std::optional<QString> known = m_items.isEmpty() ? std::nullopt : m_libraryEtag.get(S("etag"));
    m_api.getIfChanged(S("/api/library"), known, [this](const ApiResult &r) {
        m_loading = false;
        // кто ждал свежего списка — после этого ответа (как бы он ни кончился)
        struct Notify {
            Library *l;
            ~Notify() {
                auto w = std::move(l->m_refreshWaiters);
                l->m_refreshWaiters.clear();
                for (auto &f : w) f();
            }
        } notify{this};
        if (!r.ok()) {
            if (r.error.kind == ApiError::Kind::Http && r.error.code == 401) setAuth(0);
            setStatus(ApiClient::describe(r.error), 2);
            return;
        }
        if (r.code == 304) {
            setStatus(tx(S("library.zagruzheno_size_torrent_ov"), {{"size", QString::number(m_items.size())}}), 1);
            // медиатека с диска та же — жанры (мультфильмы) и отметки всё равно поднять: без этого после
            // перезапуска мультфильмы узнавались только по названию
            classifyAnimation();
            m_tagsRound = 0;
            refreshTags(0);
            return;
        }
        const QList<Magnet> list = parseLibrary(r.json);
        m_aiReadyAt = aiTitleReadyAt(r.json);
        publish(list);
        AudioDesk::cleanupPending(*this); // озвучки, не привязанные в прошлый раз
        cleanUp(); // что оставили фильмы, ушедшие из медиатеки (постеры, прогресс, запас…)
        QFile f(AppDir::file(S("library.json")));
        if (f.open(QIODevice::WriteOnly)) f.write(QJsonDocument(r.json).toJson(QJsonDocument::Compact));
        const bool stale = r.json.value(S("stale")).toBool();
        m_libraryEtag.put(S("etag"), stale ? std::nullopt : r.etag);
        setStatus(stale ? tx(S("library.alldebrid_ne_otvetil_pokazana"), {{"size", QString::number(list.size())}})
                        : tx(S("library.zagruzheno_size_torrent_ov"), {{"size", QString::number(list.size())}}),
                  stale ? 0 : 1);
        // В фоне: категории (жанр TMDB), отметки DV/HDR/DTS.
        classifyAnimation();
        m_tagsRound = 0;
        refreshTags(0);
    });
}

// ---- чистка после фильмов, ушедших из медиатеки ----
void Library::cleanUp() {
    const LibraryKeep keep(m_items);
    if (!keep.usable()) return;
    auto prune = [](Store &store, const std::function<bool(const QString &)> &keepIt) {
        for (const QString &k : store.keys())
            if (!keepIt(k)) store.put(k, std::nullopt);
    };
    auto path = [](const std::optional<QString> &v) { return v ? after(*v, QLatin1Char('|')) : QString(); };
    QSet<QString> keptPaths;
    QStringList gonePaths;
    for (const QString &k : m_posterIndex.keys()) {
        const QString p = path(m_posterIndex.get(k));
        if (p.isEmpty()) continue;
        if (keep.keepsPoster(k)) keptPaths.insert(p);
        else gonePaths << p;
    }
    auto keepsPoster = [&](const QString &k) { return keep.keepsPoster(k); };
    auto keepsKey = [&](const QString &k) { return keep.keepsKey(k); };
    prune(m_posterIndex, keepsPoster);
    prune(m_titleIndex, keepsPoster);
    for (const QString &p : gonePaths)
        if (!keptPaths.contains(p)) QFile::remove(posterFile(p));
    prune(m_mediaIndex, keepsKey);
    prune(m_watch, keepsKey);
    prune(m_episodes, keepsKey);
    prune(m_hidden, keepsKey);
    prunePosterFiles();
}

void Library::prunePosterFiles() {
    const QString dir = AppDir::file(S("posters"));
    struct F { QString path; qint64 size; qint64 at; };
    std::vector<F> files;
    const qint64 old = now() - POSTER_UNUSED_DAYS * DAY;
    QDirIterator it(dir, QDir::Files);
    while (it.hasNext()) {
        it.next();
        const QFileInfo fi = it.fileInfo();
        const qint64 at = fi.lastModified().toMSecsSinceEpoch();
        if (at < old) { QFile::remove(fi.filePath()); continue; }
        files.push_back({fi.filePath(), fi.size(), at});
    }
    qint64 total = 0;
    for (const auto &f : files) total += f.size;
    std::sort(files.begin(), files.end(), [](const F &a, const F &b) { return a.at < b.at; });
    for (const auto &f : files) {
        if (total <= POSTER_CAP) break;
        total -= f.size;
        QFile::remove(f.path);
    }
}

QString Library::posterFile(const QString &path) {
    const QString name = QString::fromLatin1(QCryptographicHash::hash(path.toUtf8(), QCryptographicHash::Sha1).toHex());
    QDir().mkpath(AppDir::file(S("posters")));
    return QDir(AppDir::file(S("posters"))).filePath(name + S(".img"));
}

// ---- избранное, «Мои фильмы», удаление ----
void Library::updateMagnet(qint64 id, const std::function<void(Magnet &)> &f) {
    for (auto &i : m_items)
        if (i.m.id == id) f(i.m);
    bump();
}

void Library::setFavorite(qint64 id, bool on) {
    const LibraryItem *it = item(id);
    if (!it) return;
    const bool before = it->m.favorite;
    updateMagnet(id, [on](Magnet &m) { m.favorite = on; });
    m_api.post(S("/api/favorites"), QJsonObject{{S("id"), id}, {S("favorite"), on}}, [this, id, before](const ApiResult &r) {
        if (r.ok()) return;
        updateMagnet(id, [before](Magnet &m) { m.favorite = before; });
        emit actionFailed(ApiClient::describe(r.error));
    });
}

void Library::setMine(qint64 id, bool on) {
    const LibraryItem *it = item(id);
    if (!it) return;
    const bool before = it->m.mine;
    updateMagnet(id, [on](Magnet &m) { m.mine = on; });
    m_api.post(S(Shelf::PATH), Shelf::body(id, on), [this, id, before](const ApiResult &r) {
        if (r.ok()) return;
        updateMagnet(id, [before](Magnet &m) { m.mine = before; });
        emit actionFailed(ApiClient::describe(r.error));
    });
}

void Library::remove(qint64 id) {
    const LibraryItem *it = item(id);
    if (!it) return;
    const QString path = S("/api/magnets/%1/%2").arg(id).arg(it->m.downloading() ? S("cancel") : S("delete"));
    m_api.post(path, {}, [this, id](const ApiResult &r) {
        if (!r.ok()) { emit actionFailed(ApiClient::describe(r.error)); return; }
        m_items.erase(std::remove_if(m_items.begin(), m_items.end(), [id](const LibraryItem &i) { return i.m.id == id; }), m_items.end());
        bump();
    });
}

// ---- ИИ ----
void Library::setAi(const QHash<qint64, std::optional<AiTitle>> &answers) {
    if (answers.isEmpty()) return;
    for (auto &i : m_items) {
        const auto a = answers.constFind(i.m.id);
        if (a != answers.cend()) i = i.withAiTitle(*a);
    }
    bump();
}

void Library::askAi(const QList<qint64> &ids) {
    if (ids.isEmpty() || m_aiBusy) return;
    m_aiBusy = true;
    emit aiChanged();
    setStatus(tx(S("library.ai_sprashivaem")), 0);
    QJsonArray list;
    for (int i = 0; i < std::min<int>(AI_IDS_PER_CALL, ids.size()); ++i) list.append(ids[i]);
    m_api.post(S("/api/library/ai-title"), QJsonObject{{S("ids"), list}}, [this](const ApiResult &r) {
        m_aiBusy = false;
        emit aiChanged();
        if (!r.ok()) {
            QString msg = ApiClient::describe(r.error);
            const qint64 sec = r.error.body.value(S("retryAfterSec")).toInteger();
            if (r.error.code == 429 && sec > 0 && r.error.body.value(S("reason")).toString() == QLatin1String("cooldown")) {
                m_aiReadyAt = now() + sec * 1000;
                msg = tx(S("library.ai_podozhdite"), {{"min", QString::number((sec + 59) / 60)}});
            }
            setStatus(msg, 2);
            emit aiPanelRequested({}, msg);
            return;
        }
        m_aiReadyAt = r.json.value(S("aiTitleReadyAt")).toInteger(); // у админа кулдауна нет: 0
        QHash<qint64, std::optional<AiTitle>> answers;
        QVariantList got;
        for (const auto &v : r.json.value(S("results")).toArray()) {
            const QJsonObject o = v.toObject();
            const qint64 id = o.value(S("id")).toInteger();
            const QJsonObject ai = o.value(S("aiTitle")).toObject();
            answers.insert(id, parseAiTitle(o.value(S("aiTitle")).isObject() ? &ai : nullptr));
            got << id;
        }
        setAi(answers);
        int found = 0;
        for (const auto &i : m_items)
            if (answers.contains(i.m.id) && i.m.aiTitle && i.m.aiTitle->status == QLatin1String("suggested")) ++found;
        setStatus(found > 0 ? tx(S("library.ai_gotovo")) : tx(S("library.ai_ne_smog")), 1);
        emit aiPanelRequested(got, found == 0 ? tx(S("library.ai_ne_smog")) : QString());
    });
}

void Library::saveAi(const QHash<qint64, bool> &decisions) {
    QJsonArray todo;
    for (auto it = decisions.cbegin(); it != decisions.cend(); ++it) {
        const LibraryItem *i = item(it.key());
        if (!i || !i->m.aiTitle) continue;
        if (i->m.aiTitle->status == (it.value() ? QLatin1String("accepted") : QLatin1String("rejected"))) continue;
        todo.append(QJsonObject{{S("id"), it.key()}, {S("accept"), it.value()}});
    }
    for (int from = 0; from < todo.size(); from += AI_IDS_PER_CALL) {
        QJsonArray chunk;
        for (int i = from; i < std::min<int>(from + AI_IDS_PER_CALL, todo.size()); ++i) chunk.append(todo[i]);
        m_api.post(S("/api/library/ai-title/decision"), QJsonObject{{S("decisions"), chunk}}, [this](const ApiResult &r) {
            if (!r.ok()) { setStatus(ApiClient::describe(r.error), 2); return; }
            QHash<qint64, std::optional<AiTitle>> done;
            for (const auto &v : r.json.value(S("results")).toArray()) {
                const QJsonObject o = v.toObject();
                const QJsonObject ai = o.value(S("aiTitle")).toObject();
                done.insert(o.value(S("id")).toInteger(), parseAiTitle(o.value(S("aiTitle")).isObject() ? &ai : nullptr));
            }
            setAi(done);
        });
    }
}

namespace {
QString aiLabel(const AiTitle &ai) {
    const QString kind = ai.kind == QLatin1String("movie") ? tx(S("library.ai_tip_film"))
                         : ai.kind == QLatin1String("series") ? tx(S("library.ai_tip_serial"))
                         : ai.kind == QLatin1String("collection") ? tx(S("library.ai_tip_sbornik")) : QString();
    // оригинал и год — по ним ищется постер: «Дюна» (Dune, 2021) · фильм
    QStringList orig;
    if (ai.original && *ai.original != ai.base) orig << *ai.original;
    if (ai.year) orig << QString::number(*ai.year);
    const QString title = S("«") + ai.base + S("»") + (orig.isEmpty() ? QString() : S(" (") + orig.join(S(", ")) + S(")")) +
                          (kind.isEmpty() ? QString() : S(" · ") + kind);
    return ai.season ? tx(S("library.ai_sezon"), {{"title", title}, {"season", QString::number(*ai.season)}}) : title;
}
} // namespace

QVariantList Library::aiRows(const QVariantList &ids) const {
    QVariantList out;
    for (const auto &v : ids) {
        const LibraryItem *i = item(v.toLongLong());
        if (!i || !i->m.aiTitle) continue;
        const AiTitle &ai = *i->m.aiTitle;
        out << QVariantMap{{S("id"), i->m.id},
                           {S("label"), ai.status == QLatin1String("none") ? tx(S("library.ai_ne_smog")) : aiLabel(ai)},
                           {S("name"), i->m.name},
                           {S("canTick"), i->m.canAiTitle && ai.status != QLatin1String("none")},
                           // предложенное и принятое отмечены сразу: «Сохранить» принимает найденное ИИ
                           {S("ticked"), ai.status != QLatin1String("rejected")}};
    }
    return out;
}

void Library::saveAiDecisions(const QVariantMap &decisions) {
    QHash<qint64, bool> d;
    for (auto it = decisions.cbegin(); it != decisions.cend(); ++it) d.insert(it.key().toLongLong(), it.value().toBool());
    saveAi(d);
}

// ---- категории и подписи ----
bool Library::isAnime(const LibraryItem &item) {
    const QString text = (item.m.name + QLatin1Char(' ') + item.baseTitle).toLower();
    for (const char *k : {"anime", "аниме", "[horriblesubs]", "[erai-raws]", "[subsplease]", "[judas]", "[anilibria]", "anilibria"})
        if (text.contains(S(k))) return true;
    return false;
}

bool Library::isCartoon(const LibraryItem &item) const {
    if (cartoonRe().match(item.m.name).hasMatch() || cartoonRe().match(item.baseTitle).hasMatch()) return true;
    for (const QString &q : item.posterQueries)
        if (m_animation.value(q.toLower(), false)) return true;
    return false;
}

Category Library::category(const LibraryItem &item) const {
    if (isAnime(item)) return Category::Anime;
    if (isCartoon(item)) return Category::Cartoons;
    if (isCollection(item.playable, &item.m)) return Category::Movies;
    switch (item.playable.kind) {
    case Playable::Kind::Series: return Category::Series;
    case Playable::Kind::Movie: return Category::Movies;
    default: return Category::Other;
    }
}

QString Library::typeLabel(const LibraryItem &item) const {
    const auto films = collectionFilms(item);
    if (!films.isEmpty()) return tx(collectionKindTrKey(collectionKind(int(films.size()), item.m.name)));
    switch (category(item)) {
    case Category::Anime: return tx(S("library.anime"));
    case Category::Cartoons: return tx(S("library.multfilm"));
    case Category::Series: return tx(S("library.serial"));
    case Category::Movies: return tx(S("library.film"));
    case Category::Other: return tx(S("library.drugoe"));
    default: return tx(S("library.video"));
    }
}

QString Library::titleOf(const LibraryItem &item) const {
    return tileTitle(item, [this](const QString &q) -> std::optional<QString> {
        const auto it = m_ruTitles.constFind(q);
        return it == m_ruTitles.cend() ? std::nullopt : std::optional<QString>(*it);
    });
}

QStringList Library::tagsOf(const LibraryItem &item) const {
    const auto it = m_serverTags.constFind(item.m.id);
    return it != m_serverTags.cend() ? *it : nameTags(item);
}

QVariantMap Library::categoryCounts(bool mine) const {
    // Считаются плитки, а не раздачи: версии одного фильма — одна плитка (так же группирует LibraryPage).
    QList<LibraryItem> by[6];
    for (const auto &i : m_items)
        if (!mine || i.m.mine) by[int(category(i))] << i;
    QVariantMap out;
    for (int k = 1; k < 6; ++k)
        out.insert(QString::number(k), int(groupVersions(by[k], [this](const LibraryItem &i) { return posterPathOf(i); }).first.size()));
    return out;
}

// ---- постеры, названия, жанры ----
void Library::noteTitle(const QString &key, const QString &t) {
    if (t.isEmpty()) {
        if (m_ruTitles.remove(key)) bump();
    } else if (m_ruTitles.value(key) != t) {
        m_ruTitles.insert(key, t);
        bump();
    }
}

void Library::recordTitle(const QString &key, const QJsonObject &j) {
    const QString t = j.contains(S("title_ru")) && !j.value(S("title_ru")).isNull() ? j.value(S("title_ru")).toString().trimmed() : QString();
    m_titleIndex.put(key, QString::number(now()) + QLatin1Char('|') + t);
    noteTitle(key, t);
}

bool Library::titleKnown(const QString &key) {
    const auto v = m_titleIndex.get(key);
    if (!v) return false;
    const QString t = after(*v, QLatin1Char('|'));
    const qint64 at = before(*v, QLatin1Char('|')).toLongLong();
    if (!t.isEmpty()) noteTitle(key, t);
    return now() - at < (t.isEmpty() ? 3 : 30) * DAY;
}

void Library::recordAnimation(const QString &key, const QJsonObject &j) {
    if (!j.contains(S("animation"))) return;
    const bool a = j.value(S("animation")).toBool();
    m_animIndex.put(key, QString::number(now()) + (a ? S("|1") : S("|0")));
    if (m_animation.value(key, !a) != a) { m_animation.insert(key, a); bump(); }
}

void Library::notePoster(const QString &key, const QString &path) {
    if (m_posterPaths.value(key) != path) { m_posterPaths.insert(key, path); bump(); }
}

std::optional<QString> Library::posterPathOf(const LibraryItem &item) const {
    for (const QString &q : item.posterQueries) {
        const auto it = m_posterPaths.constFind(q.toLower());
        if (it != m_posterPaths.cend()) return *it;
    }
    return std::nullopt;
}

QString Library::posterPath(const QStringList &queries) {
    bool pending = false;
    for (const QString &q : queries) {
        const QString key = q.toLower();
        if (const auto it = m_posterPaths.constFind(key); it != m_posterPaths.cend()) return *it;
        const auto stored = m_posterIndex.get(key);
        if (stored) {
            const qint64 at = before(*stored, QLatin1Char('|')).toLongLong();
            const QString path = after(*stored, QLatin1Char('|'));
            if (now() - at < (path.isEmpty() ? 3 : 30) * DAY) {
                if (!path.isEmpty()) { m_posterPaths.insert(key, path); return path; }
                continue; // у этого запроса постера нет: следующий
            }
        }
        if (!m_posterAsked.contains(key)) lookupPoster(q);
        pending = true;
        break; // ответа ждём по порядку: следующий запрос — только если у этого постера нет
    }
    Q_UNUSED(pending);
    return {};
}

void Library::lookupPoster(const QString &query) {
    const QString key = query.toLower();
    m_posterAsked.insert(key);
    m_api.get(S("/api/poster?q=") + formEncode(query), [this, key](const ApiResult &r) {
        if (!r.ok()) { m_posterAsked.remove(key); return; } // сбой сети спросится ещё раз
        const QString p = r.json.value(S("poster")).toString();
        const QString path = p.startsWith(QLatin1String("/api/poster/img/")) ? p : QString();
        recordAnimation(key, r.json);
        recordTitle(key, r.json);
        m_posterIndex.put(key, QString::number(now()) + QLatin1Char('|') + path);
        if (!path.isEmpty()) notePoster(key, path);
        else bump(); // следующий запрос плитки
    });
}

void Library::image(const QString &path, std::function<void(const QByteArray &)> cb, bool persist) {
    const QString file = posterFile(path);
    QFile f(file);
    if (f.open(QIODevice::ReadOnly)) {
        const QByteArray b = f.readAll();
        f.close();
        f.setFileTime(QDateTime::currentDateTime(), QFileDevice::FileModificationTime); // показан — недавно нужен
        cb(b);
        return;
    }
    auto &waiters = m_imageWaiters[path];
    waiters.push_back(std::move(cb));
    // временный постер подборки может качаться, когда тот же постер понадобился медиатеке: тогда — на диск
    if (persist) m_imagePersist.insert(path);
    if (waiters.size() > 1) return; // уже качается
    // временная картинка (подборка ИИ) — только в память: папка постеров медиатеки не растёт от каждого подбора
    m_api.bytes(path, [this, path, file](const ApiResult &r) {
        const bool keep = m_imagePersist.remove(path);
        if (keep && r.ok() && !r.bytes.isEmpty()) {
            QFile out(file);
            if (out.open(QIODevice::WriteOnly)) out.write(r.bytes);
        }
        const auto waiters = m_imageWaiters.take(path);
        for (const auto &w : waiters) w(r.bytes);
    });
}

void Library::classifyAnimation() {
    QStringList todo;
    for (const auto &item : m_items) {
        bool genreKnown = isCartoon(item) || isAnime(item);
        bool posterKnown = false, titleFresh = false;
        for (const QString &q : item.posterQueries) {
            const QString key = q.toLower();
            if (titleKnown(key)) titleFresh = true;
            if (const auto v = m_animIndex.get(key)) {
                if (now() - before(*v, QLatin1Char('|')).toLongLong() < 60 * DAY) {
                    m_animation.insert(key, after(*v, QLatin1Char('|')) == QLatin1String("1"));
                    genreKnown = true;
                }
            }
            if (const auto p = m_posterIndex.get(key)) {
                const QString path = after(*p, QLatin1Char('|'));
                if (path.startsWith(QLatin1String("/api/poster/img/"))) { m_posterPaths.insert(key, path); posterKnown = true; }
            }
        }
        if ((!genreKnown || !posterKnown || !titleFresh) && !item.posterQueries.isEmpty()) todo << item.posterQueries.first();
    }
    bump();
    todo.removeDuplicates();
    m_classifyTodo = todo.mid(0, 80);
    if (m_classifyRunning) return; // идущая цепочка возьмёт новый список
    m_classifyRunning = true;
    // по одному запросу раз в 120 мс, без спешки
    auto step = std::make_shared<std::function<void()>>();
    *step = [this, weak = std::weak_ptr<std::function<void()>>(step)] {
        if (m_classifyTodo.isEmpty() || m_auth != 1) {
            m_classifyRunning = false;
            if (m_quietDirty) { m_quietDirty = false; bump(); }
            return;
        }
        const auto step = weak.lock();
        if (!step) { m_classifyRunning = false; return; }
        const QString q = m_classifyTodo.takeFirst();
        const QString key = q.toLower();
        m_api.get(S("/api/poster?q=") + formEncode(q), [this, key, step](const ApiResult &r) {
            if (r.ok()) {
                // ответы копятся: страницы медиатеки пересчитываются раз в 12 ответов (~1,5 с) и в конце цепочки,
                // а не после каждого
                ++m_quiet;
                const QString p = r.json.value(S("poster")).toString();
                if (p.startsWith(QLatin1String("/api/poster/img/"))) notePoster(key, p);
                recordAnimation(key, r.json);
                recordTitle(key, r.json);
                --m_quiet;
            }
            if (m_quietDirty && (++m_classifyDone % 12 == 0 || m_classifyTodo.isEmpty())) { m_quietDirty = false; bump(); }
            QTimer::singleShot(120, this, [step] { (*step)(); });
        });
    };
    m_classifyStep = step; // держит цепочку живой
    (*step)();
}

void Library::refreshTags(int round) {
    if (round >= 40 || m_auth != 1) return;
    m_api.get(S("/api/tags"), [this, round](const ApiResult &r) {
        if (!r.ok()) return;
        const QJsonObject tags = r.json.value(S("tags")).toObject();
        for (auto it = tags.begin(); it != tags.end(); ++it) {
            bool ok = false;
            const qint64 id = it.key().toLongLong(&ok);
            if (!ok || !it.value().isArray()) continue;
            QStringList list;
            for (const auto &v : it.value().toArray()) list << v.toString();
            m_serverTags.insert(id, list);
        }
        const QJsonObject profiles = r.json.value(S("profiles")).toObject();
        for (auto it = profiles.begin(); it != profiles.end(); ++it) {
            bool ok = false;
            const qint64 id = it.key().toLongLong(&ok);
            if (!ok || !it.value().isObject()) continue;
            m_serverProfiles.insert(id, core::parsePartyVariant(it.value().toObject()));
        }
        bump();
        // сервер разбирает новые фильмы по одному в фоне: пока остались — спросить ещё
        if (r.json.value(S("pending")).toInt() > 0) QTimer::singleShot(30000, this, [this, round] { refreshTags(round + 1); });
    });
}

// ---- «Продолжить просмотр»: баннер ----
static QString heroKey(const QString &q, int season) { return season > 0 ? q.toLower() + S("|s") + QString::number(season) : q.toLower(); }

std::optional<Library::Hero> Library::cachedHero(const QStringList &queries, int season) const {
    for (const QString &q : queries) {
        const auto it = m_heroes.constFind(heroKey(q, season));
        if (it != m_heroes.cend()) return *it;
    }
    return std::nullopt;
}

void Library::requestHero(const QStringList &queries, int season) {
    if (queries.isEmpty() || cachedHero(queries, season)) return;
    const QString first = heroKey(queries.first(), season);
    if (m_heroAsked.contains(first)) return;
    m_heroAsked.insert(first);
    // по запросам по очереди: первый, у которого сервер что-то знает
    auto next = std::make_shared<std::function<void(int)>>();
    *next = [this, queries, season, next](int i) {
        if (i >= queries.size()) return;
        const QString q = queries[i];
        m_api.get(S("/api/poster?hero=1&q=") + formEncode(q) + (season > 0 ? S("&season=%1").arg(season) : QString()),
                  [this, q, season, i, next](const ApiResult &r) {
                      if (!r.ok()) { (*next)(i + 1); return; }
                      Hero h;
                      auto img = [&](const char *k) {
                          const QString v = r.json.value(S(k)).toString();
                          return v.startsWith(QLatin1String("/api/poster/img/")) ? v : QString();
                      };
                      h.backdrop = img("backdrop"); // только широкий кадр: растянутый постер выглядит сломанным
                      h.logo = img("logo");
                      h.overview = r.json.value(S("overview")).toString();
                      h.year = r.json.value(S("year")).toVariant().toString();
                      h.rating = r.json.value(S("rating")).toDouble();
                      h.title = r.json.value(S("title")).toString();
                      if (h.backdrop.isEmpty() && h.overview.isEmpty() && h.title.isEmpty() && h.rating <= 0) { (*next)(i + 1); return; }
                      m_heroes.insert(heroKey(q, season), h);
                      emit heroReady();
                  });
    };
    (*next)(0);
}

// ---- строка о видео ----
std::optional<QString> Library::cachedMediaLine(qint64 id, const MFile &f) const {
    const auto raw = m_mediaIndex.get(QString::number(id) + QLatin1Char('|') + f.path);
    if (!raw) return std::nullopt;
    const QJsonObject j = QJsonDocument::fromJson(raw->toUtf8()).object();
    const qint64 failedAt = j.value(S("failedAt")).toInteger();
    if (failedAt > 0) return now() - failedAt < 6 * 3600000LL ? std::optional<QString>(tx(S("library.razreshenie_n_d"))) : std::nullopt;
    // «1920×1080 · 12 Мбит/с · HEVC · 23.98 fps»
    QStringList parts;
    const int w = j.value(S("width")).toInt(), h = j.value(S("height")).toInt();
    if (w > 0 && h > 0) parts << S("%1×%2").arg(w).arg(h);
    const double br = j.value(S("bitrateMbps")).toDouble();
    parts << (br > 0 ? QString::number(std::round(br * 10) / 10, 'g', 10) + tx(S("library.mbit_s")) : tx(S("library.bitreyt_n_d")));
    const QString codec = j.value(S("codec")).toString();
    if (!codec.trimmed().isEmpty()) parts << codec.toUpper();
    static const QRegularExpression fr(S("^(\\d+)/(\\d+)$"));
    const auto m = fr.match(j.value(S("frameRate")).toString());
    if (m.hasMatch()) {
        const double a = m.captured(1).toDouble(), b = m.captured(2).toDouble();
        if (b > 0 && a / b > 0) parts << QString::number(std::round(a / b * 100) / 100, 'g', 10) + S(" fps");
    }
    return parts.join(S(" · "));
}

std::pair<int, int> Library::cachedMediaDimensions(const QString &key) const {
    if (key.isEmpty()) return {0, 0};
    const auto raw = m_mediaIndex.get(key);
    if (!raw) return {0, 0};
    const QJsonObject j = QJsonDocument::fromJson(raw->toUtf8()).object();
    return {j.value(S("width")).toInt(), j.value(S("height")).toInt()};
}

std::pair<int, int> Library::cachedMediaDimensions(qint64 id, const QString &path) const {
    return cachedMediaDimensions(QString::number(id) + QLatin1Char('|') + path);
}

void Library::saveMediaDimensions(const QString &key, int width, int height) {
    if (key.isEmpty() || width <= 0 || height <= 0) return;
    const auto raw = m_mediaIndex.get(key);
    QJsonObject j = raw ? QJsonDocument::fromJson(raw->toUtf8()).object() : QJsonObject{};
    if (j.value(S("width")).toInt() == width && j.value(S("height")).toInt() == height) return;
    j.insert(S("width"), width);
    j.insert(S("height"), height);
    m_mediaIndex.put(key, QString::fromUtf8(QJsonDocument(j).toJson(QJsonDocument::Compact)));
}

void Library::saveMediaDimensions(qint64 id, const QString &path, int width, int height) {
    saveMediaDimensions(QString::number(id) + QLatin1Char('|') + path, width, height);
}

void Library::requestMediaLine(qint64 id, const MFile &f) {
    const QString key = QString::number(id) + QLatin1Char('|') + f.path;
    if (m_mediaAsked.contains(key) || cachedMediaLine(id, f) || !f.link) return;
    m_mediaAsked.insert(key);
    m_mediaQueue.emplace_back(id, f);
    runMediaQueue();
}

void Library::runMediaQueue() {
    // по одному: ffprobe на сервере тяжёлый
    if (m_mediaBusy || m_mediaQueue.empty()) return;
    m_mediaBusy = true;
    const auto [id, f] = m_mediaQueue.front();
    m_mediaQueue.pop_front();
    m_api.post(S("/api/media-metadata"), QJsonObject{{S("link"), *f.link}, {S("magnetId"), id}, {S("path"), f.path}},
               [this, id = id, path = f.path](const ApiResult &r) {
                   const QString key = QString::number(id) + QLatin1Char('|') + path;
                   if (r.ok() && r.json.value(S("metadata")).isObject())
                       m_mediaIndex.put(key, QString::fromUtf8(QJsonDocument(r.json.value(S("metadata")).toObject()).toJson(QJsonDocument::Compact)));
                   else if (r.error.kind == ApiError::Kind::Http && r.error.code >= 500)
                       m_mediaIndex.put(key, QString::fromUtf8(QJsonDocument(QJsonObject{{S("failedAt"), now()}}).toJson(QJsonDocument::Compact)));
                   m_mediaBusy = false;
                   bump();
                   runMediaQueue();
               });
}

// ---- прогресс ----
static std::optional<WatchInfo> parseWatchInfoVal(const std::optional<QString> &v) {
    if (!v) return std::nullopt;
    const QStringList p = v->split(QLatin1Char('|'));
    if (p.size() < 3) return std::nullopt;
    bool ok = false;
    const qint64 pos = p[0].toLongLong(&ok);
    if (!ok) return std::nullopt;
    return WatchInfo{pos, p[1].toLongLong(), p[2].toLongLong()};
}

std::optional<WatchInfo> Library::watchInfo(const QString &key, const std::optional<QString> &cid) const {
    if (const auto direct = parseWatchInfoVal(m_watch.get(key))) return direct;

    const qsizetype bar = key.indexOf(QLatin1Char('|'));
    const qint64 magId = bar > 0 ? key.left(bar).toLongLong() : 0;
    const QString path = bar >= 0 ? key.mid(bar + 1) : QString();
    const auto targetEp = path.isEmpty() ? std::nullopt : core::episodePair(path);

    std::optional<QString> effCid = (cid && !cid->isEmpty()) ? cid : std::nullopt;
    if (!effCid && magId > 0) {
        if (const LibraryItem *it = item(magId)) {
            if (const auto ai = it->m.acceptedAi(); ai && ai->canonicalId && !ai->canonicalId->isEmpty()) effCid = ai->canonicalId;
        }
    }

    std::optional<WatchInfo> best;
    // 1. Поиск среди других версий (groupVersions) того же фильма/сериала
    if (magId > 0) {
        const auto versions = versionsOf(magId);
        for (const auto &ver : versions) {
            if (ver.m.id == magId) continue;
            if (ver.playable.isMovie()) {
                if (targetEp) continue;
                const auto w = parseWatchInfoVal(m_watch.get(watchKey(ver.m.id, ver.playable.file)));
                if (w && (!best || w->at > best->at)) best = w;
            } else {
                for (const auto &s : ver.playable.seasons) {
                    for (const auto &e : s.items) {
                        if (targetEp && core::episodePair(e.file.path) == targetEp) {
                            const auto w = parseWatchInfoVal(m_watch.get(watchKey(ver.m.id, e.file)));
                            if (w && (!best || w->at > best->at)) best = w;
                        }
                    }
                }
            }
        }
    }

    // 2. Поиск по canonicalId (cid) в m_watch (в т.ч. пришедшее с других устройств)
    if (effCid && !effCid->isEmpty()) {
        const QHash<QString, QString> all = m_watch.all();
        for (auto it = all.cbegin(); it != all.cend(); ++it) {
            if (it.key() == key) continue;
            const QStringList p = it.value().split(QLatin1Char('|'));
            if (p.size() < 4 || p[3] != *effCid) continue;
            const QString kPath = after(it.key(), QLatin1Char('|'));
            if (targetEp != core::episodePair(kPath)) continue;
            bool ok = false;
            const qint64 pos = p[0].toLongLong(&ok);
            if (!ok) continue;
            const WatchInfo w{pos, p[1].toLongLong(), p[2].toLongLong()};
            if (!best || w.at > best->at) best = w;
        }
    }
    return best;
}

void Library::noteWatch(const QString &key, qint64 pos, qint64 len, const std::optional<QString> &cid) {
    const qint64 at = QDateTime::currentMSecsSinceEpoch();
    std::optional<QString> effCid = (cid && !cid->isEmpty()) ? cid : std::nullopt;
    if (!effCid) {
        const qint64 magId = before(key, QLatin1Char('|')).toLongLong();
        if (magId > 0) {
            if (const LibraryItem *it = item(magId)) {
                if (const auto ai = it->m.acceptedAi(); ai && ai->canonicalId && !ai->canonicalId->isEmpty()) effCid = ai->canonicalId;
            }
        }
    }
    const qint64 safeLen = std::max<qint64>(len, 0);
    const QString val = (effCid && !effCid->isEmpty())
                            ? QStringLiteral("%1|%2|%3|%4").arg(pos).arg(safeLen).arg(at).arg(*effCid)
                            : QStringLiteral("%1|%2|%3").arg(pos).arg(safeLen).arg(at);
    m_watch.put(key, val);
    m_sync.noteWatch(key, pos, safeLen, at, effCid);
}

std::optional<std::pair<MFile, WatchInfo>> Library::lastWatched(const Magnet &m) const {
    std::optional<std::pair<MFile, WatchInfo>> best;
    for (const MFile &f : m.files) {
        if (f.isFolder) continue;
        const auto w = watchInfo(watchKey(m.id, f));
        if (!w) continue;
        if (!best || w->at > best->second.at) best = std::make_pair(f, *w);
    }
    return best;
}

QSet<qint64> Library::watchedSince(qint64 since) const {
    QSet<qint64> ids;
    const QHash<QString, QString> all = m_watch.all();
    for (auto it = all.cbegin(); it != all.cend(); ++it) {
        // ключ «magnetId|путь», значение «pos|len|at»
        const qsizetype bar = it.key().indexOf(QLatin1Char('|'));
        if (bar <= 0) continue;
        bool ok = false;
        const qint64 id = QStringView(it.key()).left(bar).toLongLong(&ok);
        if (ok && it.value().section(QLatin1Char('|'), 2, 2).toLongLong() >= since) ids.insert(id);
    }
    return ids;
}

qint64 Library::continueHiddenAt(qint64 id) const { return m_hidden.get(QString::number(id)).value_or(QString()).toLongLong(); }

void Library::hideContinue(const QVariantList &ids) {
    const QString t = QString::number(now());
    for (const auto &v : ids) m_hidden.put(QString::number(v.toLongLong()), t);
    bump();
}

std::optional<QString> Library::lastEpisode(qint64 id) const { return m_episodes.get(QString::number(id)); }
void Library::saveEpisode(qint64 id, const QString &path) { m_episodes.put(QString::number(id), path); }

bool Library::hasFile(qint64 magnetId, const QString &path) const {
    for (const LibraryItem &it : m_items) {
        if (it.m.id != magnetId) continue;
        if (it.playable.isMovie()) return it.playable.file.path == path;
        for (const Season &s : it.playable.seasons)
            for (const Episode &e : s.items)
                if (e.file.path == path) return true;
        return false;
    }
    return false;
}

bool Library::hasMatchingFilm(const QString &key, const QString &canonicalId) const {
    const qint64 magnetId = key.section(QLatin1Char('|'), 0, 0).toLongLong();
    const QString path = key.section(QLatin1Char('|'), 1);
    if (magnetId > 0 && hasFile(magnetId, path)) return true;
    if (!canonicalId.isEmpty()) {
        for (const LibraryItem &it : m_items) {
            if (it.m.aiTitle && it.m.aiTitle->accepted() && it.m.aiTitle->canonicalId == canonicalId) return true;
        }
    }
    return false;
}

int Library::magnetStatusCode(qint64 magnetId) const {
    for (const LibraryItem &it : m_items)
        if (it.m.id == magnetId) return it.m.statusCode;
    return -1;
}

QString Library::magnetStatus(qint64 magnetId) const {
    for (const LibraryItem &it : m_items)
        if (it.m.id == magnetId) return it.m.status;
    return QString();
}

} // namespace suslik::app
