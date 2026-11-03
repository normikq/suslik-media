#include "Party.h"
#include "../player/PlayerLog.h"
#include "../trackers/RuTrackerClient.h"

#include "suslik/core/ApiClient.h"
#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"
#include "suslik/core/TimeText.h"
#include "suslik/core/Trackers.h"

#include <QDateTime>
#include <QElapsedTimer>
#include <QJsonArray>
#include <QPointer>
#include <QRegularExpression>
#include <QNetworkAccessManager>
#include <QNetworkReply>

namespace suslik::app {

using namespace suslik::core;

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
// Монотонные часы для комнаты: перевод системного времени (NTP) не сдвигает место комнаты
qint64 nowMs() {
    static QElapsedTimer t = [] { QElapsedTimer x; x.start(); return x; }();
    return t.elapsed();
}
void log(const QString &s) { PlayerLog::write(S("[party] ") + s); }

// Пропуск этого ПК в комнатах, один и тот же после перезапуска: создатель комнаты при общем пароле остаётся её ведущим,
// а вход в ту же комнату не упирается в «уже подключён с другого устройства», пока сервер не заметил обрыв
QString savedClientId() {
    Store &st = Store::of(S("party"));
    static const QRegularExpression ok(S("^pc_[0-9a-f]{12}$"));
    if (const auto v = st.get(S("client_id")); v && ok.match(*v).hasMatch()) return *v;
    const QString id = PartyProtocol::newClientId(S("pc"));
    st.put(S("client_id"), id);
    return id;
}
} // namespace

Party::Party(ApiClient &api, QObject *parent)
    : QObject(parent), m_api(api), m_clientId(savedClientId()), m_myKey(PartyProtocol::clientKey(m_clientId)) {
    s_instance = this;
    // Пока в комнате — точка для часов раз в минуту: гость сам ничего не шлёт, а часы за часы просмотра уплывают
    m_clockTimer.setInterval(int(PartyClock::RESAMPLE_MS));
    connect(&m_clockTimer, &QTimer::timeout, this, [this] {
        if (!m_roomId) { m_clockTimer.stop(); return; }
        timedGet(PartyProtocol::roomPath(*m_roomId), [](const ApiResult &) {});
    });
}

Party::~Party() {
    if (s_instance == this) s_instance = nullptr;
    stopStream();
}

QVariantList Party::members() const {
    QVariantList out;
    for (const PartyMember &m : m_room.members) {
        // Имя из Discord может быть справа налево или с управляющими знаками: тогда вся строка разворачивалась и
        // прижималась вправо. Строка — всегда слева направо (LRM), имя — отдельным островком (FSI…PDI), без переносов.
        QString name = m.name;
        for (QChar &c : name)
            if (c == u'\n' || c == u'\r' || c == u'\t') c = u' ';
        const QString label = QStringLiteral("\u200E\u2068") + name.trimmed() + QStringLiteral("\u2069") +
                              (m.key == m_myKey ? tx(S("party.vy")) : QString()) +
                              (m_room.isCreator(m) ? tx(S("party.sozdatel")) : QString()) +
                              (m.buffering ? tx(S("party.zagruzhaetsya")) : QString());
        out << QVariantMap{{S("name"), m.name}, {S("label"), label}, {S("avatar"), m.avatar.value_or(QString())}};
    }
    return out;
}

QString Party::hostWho() const {
    return m_room.hostName.trimmed().isEmpty() ? tx(S("party.sozdatel_komnaty")) : m_room.hostName;
}

qint64 Party::targetMs() const { return m_room.state.targetMs(m_clock.serverNow(nowMs())); }

QString Party::stateLine() const {
    const PartyState &s = m_room.state;
    QString text;
    if (!s.playing) text = tx(S("party.na_pauze"));
    else if (s.waiting) {
        QStringList names;
        for (const auto &m : m_room.members)
            if (m.buffering) names << m.name;
        text = tx(S("party.zhdyom")) + (names.isEmpty() ? QString() : tx(S("party.u_kogo"), {{"names", names.join(S(", "))}}));
    } else text = tx(S("party.idyot"));
    return text + S(" · ") + clockText(targetMs());
}

bool Party::takeStartedByMe(const QString &key) {
    if (m_startedByMe != key) return false;
    m_startedByMe.reset();
    return true;
}

// ---- запросы с часами ----

void Party::sample(qint64 t0, const ApiResult &r) {
    if (!r.ok()) return;
    const auto u = PartyProtocol::roomOf(r.json);
    if (u && u->serverTime > 0) m_clock.addSample(t0, nowMs(), u->serverTime);
}

void Party::timedGet(const QString &path, Done done) {
    const qint64 t0 = nowMs();
    QPointer<Party> self(this);
    m_api.get(path, [self, t0, done](const ApiResult &r) {
        if (!self) return;
        self->sample(t0, r);
        done(r);
    });
}

void Party::timedPost(const QString &path, const QJsonObject &body, Done done) {
    const qint64 t0 = nowMs();
    QPointer<Party> self(this);
    m_api.post(path, body, [self, t0, done](const ApiResult &r) {
        if (!self) return;
        self->sample(t0, r);
        done(r);
    });
}

// ---- войти, выйти ----

void Party::finishRun(const QString &error) {
    m_busy = false;
    m_error = error;
    emit changed();
}

void Party::create() {
    if (m_busy) return;
    m_busy = true;
    m_error.clear();
    emit changed();
    timedPost(PartyProtocol::roomsPath(), PartyProtocol::createBody(m_clientId), [this](const ApiResult &r) {
        if (!r.ok()) { finishRun(ApiClient::describe(r.error)); return; }
        const auto u = PartyProtocol::roomOf(r.json);
        if (!u || !u->id) { finishRun(tx(S("party.server_ne_prislal_komnatu"))); return; }
        m_myUserId = PartyProtocol::hostIdOf(r.json); // создатель — ведущий
        enter(*u);
        finishRun({});
    });
}

void Party::join(const QString &code) { joinThen(code, {}); }

void Party::joinThen(const QString &code, std::function<void()> after) {
    if (m_busy) return;
    const QString id = PartyProtocol::normalizeCode(code);
    if (id.isEmpty()) { m_error = tx(S("party.vvedite_kod")); emit changed(); return; }
    m_busy = true;
    m_error.clear();
    emit changed();
    timedPost(PartyProtocol::joinPath(id), PartyProtocol::joinBody(m_clientId), [this, after](const ApiResult &r) {
        if (!r.ok()) { finishRun(ApiClient::describe(r.error)); return; }
        const auto u = PartyProtocol::roomOf(r.json);
        if (!u || !u->id) { finishRun(tx(S("party.server_ne_prislal_komnatu"))); return; }
        if (const auto m = PartyProtocol::memberIdOf(r.json)) m_myUserId = m;
        enter(*u);
        finishRun({});
        if (after) after();
    });
}

void Party::openLink(const QString &link) {
    const QString code = PartyProtocol::roomFromLink(link);
    if (code.isEmpty() || m_busy) return;
    log(S("link to room %1").arg(code));
    // Гость по ссылке не ждёт «Смотреть вместе»: фильм ведущего откроется сам (PartyFollow)
    auto opened = [this] {
        m_followed.clear();
        emit changed();
        emit roomLinkOpened();
    };
    if (m_roomId && *m_roomId == code) { opened(); return; }
    if (!m_roomId) { joinThen(code, opened); return; }
    // Сначала выйти из прежней комнаты (сервер не пустит одно устройство в две), потом войти
    const QString old = *m_roomId;
    stopStream();
    m_roomId.reset();
    m_startedByMe.reset();
    m_followed.clear();
    m_room = PartyRoom();
    m_reportedBuffering = false;
    m_outbox.clear();
    emit changed();
    QPointer<Party> self(this);
    m_api.post(PartyProtocol::leavePath(old), PartyProtocol::leaveBody(m_clientId), [self, code, opened](const ApiResult &) {
        if (self) self->joinThen(code, opened);
    });
}

void Party::enter(const RoomUpdate &u) {
    stopStream();
    m_notice.clear();
    m_clock.reset();
    m_reportedBuffering = false;
    m_outbox.clear();
    // Свежий снимок: seq другой комнаты не должен прятать состояние этой
    m_room = PartyRoom().merge(u);
    // Вход по коду не включает фильм, который уже в комнате: его откроет «Смотреть вместе»; следующий ведущего — сам
    m_followed = m_room.file ? m_room.file->key : QString();
    const QString id = *u.id;
    m_roomId = id;
    log(S("in room %1 (%2)").arg(id, isHost() ? S("host") : S("guest")));
    emit changed();
    // Ещё несколько точек для часов: побеждает самый короткий круг
    QPointer<Party> self(this);
    auto more = std::make_shared<std::function<void(int)>>();
    *more = [self, id, more](int left) {
        if (!self || self->m_roomId != id) return;
        if (left == 0) { self->startStream(id); self->m_clockTimer.start(); return; }
        self->timedGet(PartyProtocol::roomPath(id), [more, left](const ApiResult &) { (*more)(left - 1); });
    };
    (*more)(3);
}

void Party::leave() {
    if (!m_roomId) return;
    const QString id = *m_roomId;
    stopStream();
    m_roomId.reset();
    m_startedByMe.reset();
    m_followed.clear();
    m_room = PartyRoom();
    m_reportedBuffering = false;
    m_outbox.clear();
    log(S("left room %1").arg(id));
    emit changed();
    m_api.post(PartyProtocol::leavePath(id), PartyProtocol::leaveBody(m_clientId), [](const ApiResult &) {});
}

// ---- фильм и действия ----

void Party::putFilm(const PartyFileRef &f) {
    if (!inRoom() || !isHost() || (m_room.file && m_room.file->key == f.key)) return;
    setRoom(m_room.putFile(f));
    m_startedByMe = f.key;
    action(PartyProtocol::changeFile(f));
}

void Party::closeIfNobodyWatches() {
    QPointer<Party> self(this);
    QTimer::singleShot(1500, this, [self] {
        if (!self || self->watching != 0 || !self->inRoom() || !self->isHost() || !self->m_room.file) return;
        log(S("host closed the film: the room has none now"));
        self->setRoom(self->m_room.clearFile());
        self->action(PartyProtocol::closeFile());
    });
}

void Party::reportBuffering(bool loading) {
    if (!m_roomId || loading == m_reportedBuffering) return;
    m_reportedBuffering = loading;
    log(loading ? S("loading: the room waits") : S("ready"));
    m_outbox << std::make_pair(*m_roomId, PartyProtocol::withClient(PartyProtocol::buffering(loading), m_clientId));
    pump();
}

void Party::action(const QJsonObject &body) {
    if (!m_roomId || !canControl()) return;
    m_outbox << std::make_pair(*m_roomId, PartyProtocol::withClient(body, m_clientId));
    pump();
}

/// По одному и по порядку: «поставить фильм» должно дойти до сервера раньше «пуск отсюда».
void Party::pump() {
    if (m_sending || m_outbox.isEmpty()) return;
    m_sending = true;
    const auto [id, body] = m_outbox.takeFirst();
    timedPost(PartyProtocol::actionPath(id), body, [this, id, body](const ApiResult &r) {
        m_sending = false;
        if (r.ok()) {
            // своё изменение приходит сразу (поток может опоздать)
            if (m_roomId == id)
                if (const auto u = PartyProtocol::roomOf(r.json)) setRoom(m_room.merge(*u));
        } else {
            log(S("action %1 failed: %2").arg(body.value(S("type")).toString(), ApiClient::describe(r.error)));
            if (body.value(S("type")).toString() == S("change_file") && m_roomId == id) setRoom(m_room.fileRejected());
        }
        pump();
    });
}

// ---- поток событий ----

void Party::startStream(const QString &id) {
    stopStream();
    const int gen = ++m_streamGen;
    QNetworkRequest req = m_api.request(PartyProtocol::eventsPath(id, m_clientId));
    req.setRawHeader("Accept", "text/event-stream");
    // Без сжатия: сжатый ответ держит события, пока не заполнится буфер
    req.setRawHeader("Accept-Encoding", "identity");
    req.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);
    // сервер шлёт «: ping» каждые 20 с: тишина минуту — соединение оборвалось
    req.setTransferTimeout(60000);
    QNetworkReply *reply = m_api.network()->get(req);
    m_stream = reply;
    m_streamBuf.clear();
    auto status = [reply] { return reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt(); };
    connect(reply, &QNetworkReply::readyRead, this, [this, reply, gen, status] {
        if (gen != m_streamGen) return;
        if (status() < 200 || status() > 299) return;
        if (!m_connected) {
            m_connected = true;
            m_failures = 0;
            emit changed();
        }
        m_streamBuf += reply->readAll();
        int nl;
        while ((nl = m_streamBuf.indexOf('\n')) >= 0) {
            const QString line = QString::fromUtf8(m_streamBuf.left(nl)).trimmed();
            m_streamBuf.remove(0, nl + 1);
            if (const auto e = PartyProtocol::parseEventLine(line)) onEvent(*e);
            else if (const auto rq = PartyProtocol::remoteRequestOf(line)) onRemoteRequest(*rq);
        }
    });
    connect(reply, &QNetworkReply::finished, this, [this, reply, id, gen, status] {
        reply->deleteLater();
        if (gen != m_streamGen || m_roomId != id) return;
        m_stream = nullptr;
        const int code = status();
        if (m_connected) { m_connected = false; emit changed(); }
        if (code == 404) {
            log(S("room %1 is gone").arg(id));
            leave();
            m_notice = tx(S("party.komnata_zakryta"));
            emit changed();
            return;
        }
        // Вход больше не действует (вышли, доступ отозван): повторять бессмысленно
        if (code == 401 || code == 403) {
            log(S("stream: HTTP %1, leaving the room").arg(code));
            leave();
            m_notice = tx(S("party.vhod_bolshe_ne_deystvuet"));
            emit changed();
            return;
        }
        log(S("stream: %1").arg(code > 0 ? S("HTTP %1").arg(code) : reply->errorString()));
        retryStream(id);
    });
}

void Party::retryStream(const QString &id) {
    const int delay = int(std::min<qint64>(qint64(1000) << std::min(m_failures, 4), 15000));
    ++m_failures;
    QPointer<Party> self(this);
    QTimer::singleShot(delay, this, [self, id] {
        if (self && self->m_roomId == id && !self->m_stream) self->startStream(id);
    });
}

void Party::stopStream() {
    ++m_streamGen;
    if (m_stream) {
        QNetworkReply *r = m_stream;
        m_stream = nullptr;
        r->abort();
        r->deleteLater();
    }
    if (m_connected) { m_connected = false; emit changed(); }
}

void Party::onRemoteRequest(const RemoteTrackerRequest &rq) {
    if (!m_roomId) return;
    const QString path = PartyProtocol::remoteReplyPath(*m_roomId);
    QPointer<Party> self(this);
    auto send = [self, path, clientId = m_clientId, id = rq.id](QJsonObject body) {
        if (!self) return;
        body.insert(S("client_id"), clientId);
        body.insert(S("request_id"), id);
        self->m_api.post(path, body, [](const ApiResult &) {});
    };
    if (rq.kind == S("search")) {
        if (!RuTracker::isReady() || !TrackerSettings::isEnabled(S("rutracker"))) {
            send({{S("error"), S("На этом ПК RuTracker не настроен (Настройки → Трекеры).")}});
            return;
        }
        log(S("RuTracker search for the Activity (%1)").arg(rq.by));
        rutracker::search(rq.query, this, [send](const QList<SearchHit> &hits, const QString &error) {
            if (!error.isEmpty()) { send({{S("error"), error}}); return; }
            QJsonArray arr;
            for (const auto &h : hits) {
                if (ReleaseParser::isNonVideo(h.title) || arr.size() >= 100) continue;
                QJsonObject o{{S("title"), h.title}, {S("size"), double(h.size)}, {S("seeders"), h.seeders},
                              {S("leechers"), h.leechers}, {S("date"), h.date}, {S("kind"), h.kind},
                              {S("badges"), QJsonArray::fromStringList(h.release.badges())}};
                if (h.topicId) o.insert(S("topic_id"), *h.topicId);
                if (h.magnet) o.insert(S("magnet"), *h.magnet);
                arr.append(o);
            }
            send({{S("hits"), arr}});
        });
        return;
    }
    // «add»: раздачу нашёл этот ПК, у сервера её .torrent нет — качаем со своими cookie и кладём в AllDebrid
    if (rq.kind != S("add") || rq.target != m_myKey) return;
    log(S("RuTracker topic %1 for the Activity (%2)").arg(rq.topicId, rq.by));
    rutracker::downloadTorrent(rq.topicId, this, [self, send, rq](const QByteArray &bytes) {
        if (!self) return;
        if (bytes.isEmpty()) { send({{S("error"), S("ПК не смог скачать .torrent с RuTracker.")}}); return; }
        static const QRegularExpression bad(S("[^a-zA-Z0-9а-яА-ЯёЁ ._-]"));
        QString name = ReleaseParser::cleanReleaseTitle(rq.title).trimmed();
        if (name.isEmpty()) name = S("rutracker-") + rq.topicId;
        name = name.replace(bad, S("_")).left(80) + S(".torrent");
        self->m_api.postTorrent(bytes, name, [send](const ApiResult &r) {
            if (!r.ok()) { send({{S("error"), ApiClient::describe(r.error)}}); return; }
            send({{S("id"), double(r.json.value(S("id")).toInteger())}, {S("ready"), r.json.value(S("ready")).toBool()}});
        });
    });
}

void Party::onEvent(const PartyEvent &e) {
    if (e.kind == PartyEvent::Kind::Init && e.myUserId) m_myUserId = e.myUserId;
    if (e.kind == PartyEvent::Kind::RefreshLibrary) {
        emit refreshLibraryRequested();
        return;
    }
    setRoom(m_room.reduce(e));
}

} // namespace suslik::app
