// Перенос android/core/.../core/party: PartyModels.kt, PartyProtocol.kt, PartySyncEngine.kt.
#include "suslik/core/Party.h"
#include "suslik/core/Model.h"

#include <QRegularExpression>
#include <QUrl>

#include "JsonOpt.h"

#include <QCryptographicHash>
#include <QJsonArray>
#include <QJsonDocument>
#include <QUuid>
#include <cmath>
#include <cstdlib>

namespace suslik::core {

#define S QStringLiteral

namespace {
std::optional<QString> nonBlank(const QString &s) { return s.trimmed().isEmpty() ? std::nullopt : std::optional<QString>(s); }
double optDouble(const QJsonObject &o, const QString &k, double def) {
    const QJsonValue v = o.value(k);
    if (v.isDouble()) return v.toDouble();
    if (v.isString()) { bool ok = false; const double d = v.toString().toDouble(&ok); if (ok) return d; }
    return def;
}
} // namespace

// ---- часы и дрейф ----

void PartyClock::addSample(qint64 localSend, qint64 localRecv, qint64 serverTime) {
    const qint64 rtt = std::max<qint64>(0, localRecv - localSend);
    m_samples.append({localRecv, rtt, serverTime - (localSend + rtt / 2)});
    m_samples.removeIf([&](const Sample &x) { return localRecv - x.at > WINDOW_MS || x.at > localRecv; });
    const Sample *best = nullptr;
    for (const Sample &x : m_samples)
        if (!best || x.rtt < best->rtt) best = &x;
    if (!best) return;
    m_bestRtt = best->rtt;
    m_offset = best->offset;
}

void PartyClock::reset() {
    m_samples.clear();
    m_offset = 0;
    m_bestRtt = std::numeric_limits<qint64>::max();
}

DriftAction DriftController::evaluate(float localPosSec, float targetPosSec) {
    const float drift = localPosSec - targetPosSec;
    if (std::abs(drift) < TOLERANCE_SEC) return {};
    if (drift > HARD_SEEK_THRESHOLD_SEC || drift < -HARD_SEEK_THRESHOLD_SEC) return {DriftAction::Kind::HardSeek, 1.f, targetPosSec};
    if (drift > 0.f) return {DriftAction::Kind::SlowDown, 0.95f, 0.f};
    return {DriftAction::Kind::SpeedUp, 1.05f, 0.f};
}

// ---- данные комнаты ----

qint64 PartyFileRef::adId() const {
    bool ok = false;
    const qint64 v = key.section(QLatin1Char('|'), 0, 0).toLongLong(&ok);
    return ok ? v : 0;
}

bool PartyFileRef::matches(const QString &fileKey, const QString &otherCanonicalId) const {
    if (fileKey.isEmpty()) return false;
    if (fileKey == key) return true;
    for (const auto &v : variants) {
        if (v.key == fileKey) return true;
    }
    if (canonicalId && !canonicalId->isEmpty() && !otherCanonicalId.isEmpty() && *canonicalId == otherCanonicalId) {
        return true;
    }
    return false;
}

qint64 PartyState::targetMs(qint64 serverNowMs) const {
    const double base = pos * 1000.0;
    if (!playing || waiting) return qint64(base);
    const qint64 elapsed = std::max<qint64>(0, serverNowMs - anchorServerMs);
    return qint64(base + double(elapsed) * rate);
}

bool PartyRoom::isHost(const std::optional<QString> &myUserId, const QString &myKey) const {
    return myUserId && hostId && *myUserId == *hostId && (*hostId != SHARED_USER || !hostKey || *hostKey == myKey);
}

bool PartyRoom::isCreator(const PartyMember &m) const {
    return hostId && m.userId == *hostId && (*hostId != SHARED_USER || !hostKey || m.key == *hostKey);
}

bool PartyRoom::othersBuffering(const QString &myKey) const {
    for (const auto &m : members)
        if (m.buffering && m.key != myKey) return true;
    return false;
}

PartyRoom PartyRoom::putFile(const PartyFileRef &f) const {
    PartyRoom r = *this;
    r.file = f;
    r.pendingFileKey = f.key;
    return r;
}

PartyRoom PartyRoom::fileRejected() const {
    PartyRoom r = *this;
    r.pendingFileKey.reset();
    return r;
}

PartyRoom PartyRoom::clearFile() const {
    PartyRoom r = *this;
    r.file.reset();
    r.pendingFileKey = NO_FILE;
    return r;
}

PartyRoom PartyRoom::merge(const RoomUpdate &u) const {
    PartyRoom r = *this;
    if (pendingFileKey && (u.file ? u.file->key : NO_FILE) != *pendingFileKey) {
        // Старый ответ: участники и ведущий — свежие, фильм и время — ещё прежние
        if (u.id) r.id = u.id;
        r.hostId = u.hostId; r.hostKey = u.hostKey; r.hostName = u.hostName; r.hostOnly = u.hostOnly;
        if (u.members) r.members = *u.members;
        return r;
    }
    if (u.id) r.id = u.id;
    r.hostId = u.hostId; r.hostKey = u.hostKey; r.hostName = u.hostName; r.hostOnly = u.hostOnly;
    r.file = u.file;
    if (u.state && u.state->seq >= state.seq) r.state = *u.state;
    if (u.members) r.members = *u.members;
    r.pendingFileKey.reset();
    return r;
}

PartyRoom PartyRoom::reduce(const PartyEvent &e) const {
    switch (e.kind) {
    case PartyEvent::Kind::Init: return e.room ? merge(*e.room) : *this;
    case PartyEvent::Kind::StateChanged: return e.room ? merge(*e.room) : *this;
    case PartyEvent::Kind::MemberJoined: {
        PartyRoom r = *this;
        r.members.removeIf([&](const PartyMember &m) { return m.key == e.member.key; });
        r.members.append(e.member);
        return r;
    }
    case PartyEvent::Kind::RefreshLibrary: return *this;   // комнату не меняет: только «перечитай медиатеку»
    case PartyEvent::Kind::MemberLeft: {
        PartyRoom r = *this;
        r.members.removeIf([&](const PartyMember &m) { return m.key == e.key; });
        return r;
    }
    }
    return *this;
}

// ---- протокол ----

QString PartyProtocol::newClientId(const QString &prefix) {
    return prefix + QLatin1Char('_') + QUuid::createUuid().toString(QUuid::Id128).left(12);
}

QString PartyProtocol::clientKey(const QString &clientId) {
    return QString::fromLatin1(QCryptographicHash::hash(clientId.toUtf8(), QCryptographicHash::Sha256).toHex().left(16));
}

QString PartyProtocol::normalizeCode(const QString &code) {
    QString out;
    for (const QChar c : code.trimmed().toLower())
        if (c.isLetterOrNumber()) out += c;
    return out;
}

QString PartyProtocol::roomFromLink(const QString &link) {
    const QString t = link.trimmed();
    static const QString prefix = QStringLiteral("suslik://room/");
    if (!t.startsWith(prefix, Qt::CaseInsensitive)) return {};
    QString rest = t.mid(prefix.size());
    const qsizetype cut = rest.indexOf(QRegularExpression(QStringLiteral("[/?#]")));
    if (cut >= 0) rest.truncate(cut);
    const QString code = normalizeCode(QUrl::fromPercentEncoding(rest.toUtf8()));
    return code.size() <= 24 ? code : QString();
}

QJsonObject PartyProtocol::createBody(const QString &clientId) { return {{S("controls"), S("all")}, {S("client_id"), clientId}}; }
QJsonObject PartyProtocol::joinBody(const QString &clientId) { return {{S("client_id"), clientId}}; }
QJsonObject PartyProtocol::leaveBody(const QString &clientId) { return {{S("client_id"), clientId}}; }
QJsonObject PartyProtocol::play(qint64 posMs) { return {{S("type"), S("play")}, {S("pos"), double(posMs) / 1000.0}}; }
QJsonObject PartyProtocol::pause(qint64 posMs) { return {{S("type"), S("pause")}, {S("pos"), double(posMs) / 1000.0}}; }
QJsonObject PartyProtocol::seek(qint64 posMs) { return {{S("type"), S("seek")}, {S("pos"), double(posMs) / 1000.0}}; }
QJsonObject PartyProtocol::buffering(bool loading, const QString &variantKey) {
    QJsonObject obj{{S("type"), S("buffering")}, {S("buffering"), loading}};
    if (!variantKey.isEmpty()) obj.insert(S("variant_key"), variantKey);
    return obj;
}
QJsonObject PartyProtocol::variantChange(const QString &variantKey) {
    QJsonObject obj{{S("type"), S("variant_change")}};
    if (!variantKey.isEmpty()) obj.insert(S("variant_key"), variantKey);
    return obj;
}
QJsonObject PartyProtocol::changeFile(const PartyFileRef &f) { return {{S("type"), S("change_file")}, {S("file"), fileJson(f)}}; }
QJsonObject PartyProtocol::closeFile() { return {{S("type"), S("change_file")}, {S("file"), QJsonValue::Null}}; }
QJsonObject PartyProtocol::withClient(QJsonObject body, const QString &clientId) { body.insert(S("client_id"), clientId); return body; }
QJsonObject PartyProtocol::fileJson(const PartyFileRef &f) {
    QJsonObject obj{{S("key"), f.key}, {S("name"), f.title}, {S("ad_id"), double(f.adId())}};
    if (f.canonicalId) obj.insert(S("canonical_id"), *f.canonicalId);
    if (f.edition) obj.insert(S("edition"), *f.edition);
    if (!f.variants.isEmpty()) {
        QJsonArray arr;
        for (const auto &v : f.variants) {
            QJsonObject vo{{S("key"), v.key}, {S("label"), v.label}, {S("quality"), v.quality},
                           {S("audio"), v.audio}, {S("bitrate"), v.bitrate}, {S("offset_ms"), double(v.offsetMs)},
                           {S("score"), double(v.score)}};
            QJsonArray fa;
            for (const auto &flag : v.hardwareFlags) fa.append(flag);
            vo.insert(S("hardware_flags"), fa);
            arr.append(vo);
        }
        obj.insert(S("variants"), arr);
    }
    return obj;
}

PartyVariant parsePartyVariant(const QJsonObject &vo) {
    PartyVariant v;
    v.key = json::str(vo, S("key"));
    v.label = json::str(vo, S("label"));
    v.quality = json::str(vo, S("resolution"));
    if (v.quality.isEmpty()) v.quality = json::str(vo, S("quality"));
    v.audio = json::str(vo, S("audio_tech"));
    if (v.audio.isEmpty()) v.audio = json::str(vo, S("audio"));
    if (v.audio.isEmpty()) v.audio = S("Stereo");
    v.bitrate = optDouble(vo, S("bitrate_mbps"), optDouble(vo, S("bitrate"), 0.0));
    v.offsetMs = json::optLong(vo, S("offset_ms"));
    v.score = int(optDouble(vo, S("score"), 0));
    const QJsonValue flags = vo.value(S("hardware_flags"));
    if (flags.isArray()) {
        for (const auto &fl : flags.toArray()) v.hardwareFlags << fl.toString();
    }
    return v;
}

std::optional<PartyFileRef> PartyProtocol::parseFileRef(const QJsonObject &f) {
    const QString key = json::str(f, S("key"));
    if (key.trimmed().isEmpty()) return std::nullopt;
    PartyFileRef ref;
    ref.key = key;
    ref.title = json::str(f, S("name"));
    ref.canonicalId = nonBlank(json::str(f, S("canonical_id")));
    ref.edition = nonBlank(json::str(f, S("edition")));
    const QJsonValue vars = f.value(S("variants"));
    if (vars.isArray()) {
        for (const auto &val : vars.toArray()) {
            if (!val.isObject()) continue;
            const QJsonObject vo = val.toObject();
            PartyVariant v = parsePartyVariant(vo);
            if (v.key.isEmpty()) continue;
            ref.variants << v;
        }
    }
    return ref;
}

std::optional<RoomUpdate> PartyProtocol::roomOf(const QJsonObject &res) {
    const QJsonValue v = res.value(S("room"));
    if (!v.isObject()) return std::nullopt;
    return parseRoom(v.toObject());
}

std::optional<QString> PartyProtocol::hostIdOf(const QJsonObject &res) {
    const QJsonValue v = res.value(S("room"));
    return v.isObject() ? nonBlank(json::str(v.toObject(), S("host_id"))) : std::nullopt;
}

std::optional<QString> PartyProtocol::memberIdOf(const QJsonObject &res) {
    const QJsonValue v = res.value(S("member"));
    return v.isObject() ? nonBlank(json::str(v.toObject(), S("id"))) : std::nullopt;
}

RoomUpdate PartyProtocol::parseRoom(const QJsonObject &room) {
    RoomUpdate u;
    u.id = nonBlank(json::str(room, S("id")));
    u.hostId = nonBlank(json::str(room, S("host_id")));
    u.hostKey = nonBlank(json::str(room, S("host_key")));
    u.hostName = json::str(room, S("host_name"));
    u.hostOnly = json::str(room, S("controls")) == S("host");
    const QJsonValue fv = room.value(S("file"));
    if (fv.isObject()) {
        u.file = parseFileRef(fv.toObject());
    }
    const QJsonValue sv = room.value(S("state"));
    if (sv.isObject()) {
        const QJsonObject s = sv.toObject();
        u.state = PartyState{json::optBool(s, S("playing")), optDouble(s, S("pos"), 0), optDouble(s, S("rate"), 1),
                             json::optLong(s, S("anchor_server_ms")), json::optLong(s, S("seq")), json::optBool(s, S("waiting"))};
        u.serverTime = json::optLong(s, S("server_time"));
    }
    const QJsonValue mv = room.value(S("members"));
    if (mv.isArray()) {
        QList<PartyMember> list;
        for (const auto &m : mv.toArray())
            if (m.isObject()) list << parseMember(m.toObject());
        u.members = list;
    }
    return u;
}

PartyMember PartyProtocol::parseMember(const QJsonObject &m) {
    PartyMember p;
    p.key = json::str(m, S("key"));
    p.userId = json::str(m, S("id"));
    p.name = json::str(m, S("name"));
    if (p.name.trimmed().isEmpty()) p.name = S("Гость");
    p.buffering = json::optBool(m, S("buffering"));
    const QString avatar = json::str(m, S("avatar"));
    if (avatar.startsWith(S("https://"))) p.avatar = avatar;
    const QString vk = json::str(m, S("variant_key"));
    if (!vk.isEmpty()) p.variantKey = vk;
    return p;
}

std::optional<PartyEvent> PartyProtocol::parseEventLine(const QString &line) {
    if (!line.startsWith(S("data:"))) return std::nullopt;
    return parseEvent(line.mid(5).trimmed());
}

std::optional<RemoteTrackerRequest> PartyProtocol::remoteRequestOf(const QString &line) {
    if (!line.startsWith(S("data:"))) return std::nullopt;
    const auto doc = QJsonDocument::fromJson(line.mid(5).trimmed().toUtf8());
    if (!doc.isObject() || json::str(doc.object(), S("event")) != S("rutracker_request")) return std::nullopt;
    const QJsonObject d = doc.object().value(S("data")).toObject();
    RemoteTrackerRequest r{json::str(d, S("request_id")), json::str(d, S("kind")), json::str(d, S("query")).trimmed(),
                           json::str(d, S("topic_id")), json::str(d, S("title")), json::str(d, S("target")), json::str(d, S("by"))};
    if (r.id.isEmpty()) return std::nullopt;
    if (r.kind == S("search") && !r.query.isEmpty()) return r;
    if (r.kind == S("add") && !r.topicId.isEmpty() && !r.target.isEmpty()) return r;
    return std::nullopt;
}

QString PartyProtocol::remoteReplyPath(const QString &roomId) {
    return S("/api/rooms/") + QString::fromLatin1(QUrl::toPercentEncoding(roomId)) + S("/rutracker/reply");
}

std::optional<PartyEvent> PartyProtocol::parseEvent(const QString &text) {
    QJsonParseError err{};
    const auto doc = QJsonDocument::fromJson(text.toUtf8(), &err);
    if (err.error != QJsonParseError::NoError || !doc.isObject()) return std::nullopt;
    const QJsonObject msg = doc.object();
    const QString event = json::str(msg, S("event"));
    const QJsonObject data = msg.value(S("data")).toObject();
    PartyEvent e;
    if (event == S("init")) {
        e.kind = PartyEvent::Kind::Init;
        const QJsonValue member = msg.value(S("member"));
        if (member.isObject()) e.myUserId = nonBlank(json::str(member.toObject(), S("id")));
        if (msg.value(S("room")).isObject()) e.room = parseRoom(msg.value(S("room")).toObject());
        return e;
    }
    if (event == S("state_changed")) {
        if (!msg.value(S("data")).isObject()) return std::nullopt;
        e.kind = PartyEvent::Kind::StateChanged;
        e.room = parseRoom(data);
        return e;
    }
    if (event == S("member_joined")) {
        if (!data.value(S("member")).isObject()) return std::nullopt;
        e.kind = PartyEvent::Kind::MemberJoined;
        e.member = parseMember(data.value(S("member")).toObject());
        return e;
    }
    if (event == S("member_left")) {
        const auto key = nonBlank(json::str(data, S("key")));
        if (!key) return std::nullopt;
        e.kind = PartyEvent::Kind::MemberLeft;
        e.key = *key;
        return e;
    }
    // ведущий сменил фильм, которого у гостя ещё нет в медиатеке: перечитать её (файла может и не быть)
    if (event == S("refresh_library")) {
        e.kind = PartyEvent::Kind::RefreshLibrary;
        const QJsonValue f = data.value(S("file"));
        if (f.isObject()) e.file = parseFileRef(f.toObject());
        return e;
    }
    return std::nullopt;
}

// ---- синхронизация плеера ----

PartySyncEngine::PartySyncEngine(PartyLink &link, std::function<void(const QString &)> log, std::function<void(const PartyNotice &)> notice)
    : m_link(link), m_log(std::move(log)), m_notice(std::move(notice)) {}

void PartySyncEngine::idle(SyncPlayer &player) {
    if (!m_seenSeek) m_seenSeek = player.seekStamp();
    m_drift = 1.f;
    if (m_engineRate && *m_engineRate != player.userRate()) player.setRate(player.userRate());
    m_engineRate.reset();
    m_lastSeq = -1; m_pendingAt = 0; m_stalledSince = 0; m_lastEngineMs = std::numeric_limits<qint64>::min();
    m_applied.reset(); m_waitSince = 0; m_waitNoticed = false;
    m_link.reportBuffering(false);
}

void PartySyncEngine::tick(SyncPlayer &player, const QString &key, qint64 now) {
    if (!m_seenSeek) m_seenSeek = player.seekStamp();
    if (player.busy()) return; // полосу тянут: место отправим после отпускания
    // В фильме комнаты все идут со скоростью комнаты: своя скорость плеера (1,25× из настроек) уводила бы его вперёд,
    // а выравнивание дрейфа при ней не работало
    if (!m_engineRate) applyRate(player, 1.f);
    const qint64 t = player.timeMs();
    const bool p = player.paused();
    m_lastTimeMs = t;

    // Пользователь перемотал здесь: комната идёт туда же. Ловим по метке перемотки плеера, а не по скачку времени.
    const qint64 stamp = player.seekStamp();
    if (stamp != *m_seenSeek) {
        m_seenSeek = stamp;
        const qint64 expected = m_prevP ? m_prevT : m_prevT + qint64(double(now - m_prevAt) * m_engineRate.value_or(player.userRate()));
        if (m_lastSeq >= 0 && std::llabs(t - expected) > 1500 && m_link.canSeek()) { // смена дорожки тоже ставит метку: без скачка
            log(S("[party] local seek at %1").arg(t));
            restoreRate(player);
            if (p) m_link.pause(t); else m_link.play(t);
            m_lastSeq = m_link.state().seq + 1;
            // Новое место грузится секунды: «грузимся» (комната ждёт, а не убегает) и не гонимся за ней
            m_pendingAt = now; m_pendingTo = t; m_pendingT = -1;
            m_ownUntil = now + OWN_MS;
            m_prevT = t; m_prevP = p; m_prevAt = now;
            m_link.reportBuffering(!p);
            return;
        }
    }

    // Наша перемотка к комнате: готова, когда движок снова идёт вперёд (далеко от цели — через 3 с)
    if (m_pendingAt > 0) {
        const qint64 e = player.engineMs();
        const qint64 d = e - m_pendingT;
        const bool moved = m_pendingT >= 0 && d >= 100 && d <= 2000 && player.running() &&
                           (std::llabs(e - m_pendingTo) < 5000 || now - m_pendingAt > 3000);
        if (moved || p || now - m_pendingAt > 30000) {
            log(S("[party] seek done in %1 ms").arg(now - m_pendingAt));
            m_pendingAt = 0; m_ownUntil = now + OWN_MS;
        } else {
            m_pendingT = e;
        }
    }
    // Сами грузимся, хотя должны играть: через 1 с комната ждёт и нас. Стоящее время без паузы — тоже загрузка.
    const qint64 e = player.engineMs();
    if (p || e != m_lastEngineMs) { m_lastEngineMs = e; m_engineMovedAt = now; }
    const bool frozen = now - m_engineMovedAt >= FROZEN_MS;
    if (!p && m_pendingAt == 0 && (!player.running() || frozen))
        m_stalledSince = m_stalledSince > 0 ? m_stalledSince : (frozen ? m_engineMovedAt : now);
    else
        m_stalledSince = 0;
    const bool loading = m_pendingAt > 0 || (m_stalledSince > 0 && now - m_stalledSince > 1000);
    m_link.reportBuffering(loading && m_link.state().playing);

    // 1. Я только что поставил этот фильм в комнату: комната стартует с моего места
    if (m_link.takeStartedByMe(key)) {
        if (p) m_link.pause(t); else m_link.play(t);
        m_lastSeq = m_link.state().seq + 1; m_ownUntil = now + OWN_MS;
        if (!p && !player.running()) { m_pendingAt = now; m_pendingTo = t; m_pendingT = -1; }
        m_prevT = t; m_prevP = p; m_prevAt = now;
        return;
    }

    // 2. Пауза / пуск здесь: сообщаем комнате сразу
    if (m_lastSeq >= 0 && !loading && p != m_prevP) {
        if (m_link.canControl()) {
            log(S("[party] local %1 at %2").arg(p ? S("pause") : S("play")).arg(t));
            if (p) m_link.pause(t); else m_link.play(t);
            m_lastSeq = m_link.state().seq + 1; // наше изменение вернётся: это не новость
            m_ownUntil = now + OWN_MS;
            m_prevT = t; m_prevP = p; m_prevAt = now;
            return;
        }
        // Гость: пауза только у него; пуск возвращает его к месту ведущего
        m_localHold = p;
        log(p ? S("[party] guest pause (local only)") : S("[party] guest goes on: back to the room"));
        if (!p) m_lastSeq = -1;
    }

    // 3. Следуем за комнатой
    const PartyState s = m_link.state();
    const qint64 target = m_link.targetMs();
    const bool othersLoading = m_link.othersBuffering();
    const bool wantPaused = !s.playing || (s.waiting && !loading && othersLoading) || m_localHold;
    qint64 nt = t;
    bool np = p;
    if (s.playing && s.waiting && othersLoading) {
        if (m_waitSince == 0) m_waitSince = now;
        else if (!m_waitNoticed && now - m_waitSince >= WAIT_NOTICE_MS) {
            m_waitNoticed = true;
            if (m_notice) m_notice({PartyNotice::Kind::Waiting, 0});
        }
    } else {
        m_waitSince = 0; m_waitNoticed = false;
    }
    if (m_lastSeq < 0 || s.seq > m_lastSeq) {
        const std::optional<PartyState> before = m_lastSeq >= 0 ? m_applied : std::nullopt;
        m_applied = s;
        if (before && !m_link.canControl())
            if (auto n = noticeOf(*before, s); n && m_notice) m_notice(*n);
        m_lastSeq = s.seq;
        restoreRate(player);
        const bool alreadyGoing = m_pendingAt > 0 && std::llabs(m_pendingTo - target) < 1000;
        const bool needSeek = std::llabs(t - target) > 1000 && !alreadyGoing;
        // Комната играет, но ждёт других, а нам ещё перематываться: грузим без паузы
        const bool pauseNow = needSeek && s.playing && !m_localHold ? false : wantPaused;
        if (p != pauseNow && !(loading && !pauseNow)) { player.applyPaused(pauseNow); np = pauseNow; }
        if (needSeek) nt = seekRoom(player, target, now);
        m_ownUntil = now + OWN_MS;
        log(S("[party] apply seq=%1 playing=%2 waiting=%3 target=%4 local=%5")
                .arg(s.seq).arg(s.playing ? S("true") : S("false")).arg(s.waiting ? S("true") : S("false")).arg(target).arg(t));
    } else if (s.playing && s.waiting && !loading && !p && othersLoading) {
        // Готовы, но комната ждёт кого-то ещё: стоим здесь
        player.applyPaused(true); np = true; m_ownUntil = now + 1000;
        log(S("[party] ready, waiting for the others at %1").arg(t));
    } else if (s.playing && !s.waiting && !p && !loading && now > m_ownUntil && player.running()) {
        const DriftAction a = DriftController::evaluate(float(t) / 1000.f, float(target) / 1000.f);
        switch (a.kind) {
        case DriftAction::Kind::None: restoreRate(player); break;
        case DriftAction::Kind::SlowDown:
        case DriftAction::Kind::SpeedUp:
            if (m_drift != a.rate) applyRate(player, a.rate);
            break;
        case DriftAction::Kind::HardSeek:
            restoreRate(player); nt = seekRoom(player, target, now); m_ownUntil = now + OWN_MS;
            log(S("[party] drift seek local=%1 target=%2").arg(t).arg(target));
            break;
        }
    }
    m_prevT = nt; m_prevP = np; m_prevAt = now;
}

std::optional<PartyNotice> PartySyncEngine::noticeOf(const PartyState &before, const PartyState &s) const {
    const qint64 ran = before.playing && !before.waiting
                           ? qint64(double(std::max<qint64>(0, s.anchorServerMs - before.anchorServerMs)) * before.rate) : 0;
    const qint64 expected = qint64(before.pos * 1000) + ran;
    const qint64 pos = qint64(s.pos * 1000);
    if (std::llabs(pos - expected) > SEEK_NOTICE_MS) return PartyNotice{PartyNotice::Kind::Seeked, pos};
    if (before.playing && !s.playing) return PartyNotice{PartyNotice::Kind::Paused, 0};
    if (!before.playing && s.playing) return PartyNotice{PartyNotice::Kind::Resumed, 0};
    return std::nullopt;
}

// Скорость движка: скорость комнаты × подстройка дрейфа d (0,95–1,05)
void PartySyncEngine::applyRate(SyncPlayer &player, float d) {
    m_drift = d;
    const double room = m_link.state().rate;
    const float r = float(room >= 0.25 && room <= 4.0 ? room : 1.0) * d;
    if (r != m_engineRate.value_or(player.userRate())) player.setRate(r);
    m_engineRate = r;
}

qint64 PartySyncEngine::seekRoom(SyncPlayer &player, qint64 target, qint64 now) {
    player.seekTo(target);
    m_pendingAt = now; m_pendingTo = target; m_pendingT = -1;
    m_seenSeek = player.seekStamp();
    return target;
}

PartyVariant buildPartyVariant(const LibraryItem &item, const MFile &file, const std::optional<PartyVariant> &serverProfile, int width, int height) {
    if (serverProfile.has_value() && !serverProfile->key.isEmpty() && serverProfile->key == watchKey(item.m.id, file)) {
        PartyVariant v = *serverProfile;
        if (v.quality.isEmpty() && (width > 0 || height > 0)) {
            if (width >= 3000 || height >= 1600) v.quality = QStringLiteral("4K");
            else if (width >= 1600 || height >= 900) v.quality = QStringLiteral("1080p");
            else if (width >= 1100 || height >= 600) v.quality = QStringLiteral("720p");
            else v.quality = QStringLiteral("480p");
            if (!v.label.startsWith(v.quality)) {
                v.label = v.quality + QStringLiteral(" · ") + v.label;
            }
        }
        return v;
    }
    const QString combined = (item.m.name + QLatin1Char(' ') + file.name).trimmed();
    const QString l = combined.toLower();

    QString quality;
    auto match = [&](const char *pat) {
        return l.contains(QRegularExpression(QString::fromUtf8(pat), QRegularExpression::CaseInsensitiveOption));
    };
    if (match(R"((?:^|[^a-z0-9])(?:2160p|4k|uhd)(?![a-z])|(?:^|\D)3840(?!\d))")) quality = QStringLiteral("4K");
    else if (match(R"((?:^|\D)1080[pi](?![a-z])|(?:^|\D)1920(?!\d))")) quality = QStringLiteral("1080p");
    else if (match(R"((?:^|\D)720p(?![a-z])|(?:^|\D)1280(?!\d))")) quality = QStringLiteral("720p");
    else if (match(R"((?:^|\D)(?:480|576)[pi](?![a-z])|(?:^|[^a-z])dvd)")) quality = QStringLiteral("480p");
    else if (width > 0 || height > 0) {
        if (width >= 3000 || height >= 1600) quality = QStringLiteral("4K");
        else if (width >= 1600 || height >= 900) quality = QStringLiteral("1080p");
        else if (width >= 1100 || height >= 600) quality = QStringLiteral("720p");
        else quality = QStringLiteral("480p");
    }

    QString audio = QStringLiteral("Stereo");
    if (match(R"(atmos(?![a-z]))")) audio = QStringLiteral("TrueHD Atmos");
    else if (match(R"(truehd)")) audio = QStringLiteral("TrueHD");
    else if (match(R"(dts[:._-]?x(?![a-z0-9]))")) audio = QStringLiteral("DTS:X");
    else if (match(R"(dts[\s._-]?hd)")) audio = QStringLiteral("DTS-HD MA");
    else if (match(R"((?:^|[^a-z])dts(?![a-z]))")) audio = QStringLiteral("DTS 5.1");
    else if (match(R"(dd\+|(?:^|[^a-z])ddp(?![a-z])|(?:^|[^a-z])e[\s._-]?ac[\s._-]?3(?!\d)|dolby\s*digital\s*plus)")) audio = QStringLiteral("DD+");
    else if (match(R"((?:^|[^a-z])ac[\s._-]?3(?!\d)|(?:^|[^a-z])dd[\s._-]?5\.1|(?:^|[^\d.])5\.1(?!\d))")) audio = QStringLiteral("AC3 5.1");

    QStringList hwFlags;
    if (match(R"(dolby[\s._-]?vision|dovi|(?:^|[^a-z])dv(?![a-z]))")) hwFlags << QStringLiteral("dv");
    if (match(R"(hdr10\+|hdr10plus|hdr10|(?:^|[^a-z])hdr(?![a-z]))")) hwFlags << QStringLiteral("hdr");
    if (match(R"((?:^|[^a-z])dts(?![a-z]))")) hwFlags << QStringLiteral("dts");

    int score = smartQualityScore(combined);
    if (!match(R"((?:^|[^a-z0-9])(?:2160p|4k|uhd)(?![a-z])|(?:^|\D)3840(?!\d))")
        && !match(R"((?:^|\D)1080[pi](?![a-z])|(?:^|\D)1920(?!\d))")
        && !match(R"((?:^|\D)720p(?![a-z])|(?:^|\D)1280(?!\d))")
        && (width > 0 || height > 0)) {
        if (quality == QStringLiteral("4K")) score += 300;
        else if (quality == QStringLiteral("1080p")) score += 200;
        else if (quality == QStringLiteral("720p")) score += 100;
    }
    const qint64 sizeBytes = file.size > 0 ? file.size : item.m.size;
    const bool isSeries = item.playable.isSeries();
    const double estDuration = isSeries ? 2700.0 : 6600.0;
    double bitrate = sizeBytes > 0 ? std::round((sizeBytes * 8.0) / (estDuration * 1000000.0) * 10.0) / 10.0 : 0.0;
    bool hasExactBitrate = false;
    if (serverProfile.has_value() && serverProfile->bitrate > 0) {
        bitrate = serverProfile->bitrate;
        hasExactBitrate = true;
    }

    QStringList parts;
    if (!quality.isEmpty()) parts << quality;
    if (hwFlags.contains(QStringLiteral("dv"))) parts << QStringLiteral("Dolby Vision");
    else if (hwFlags.contains(QStringLiteral("hdr"))) parts << QStringLiteral("HDR");
    if (match(R"(remux)")) parts << QStringLiteral("BDRemux");
    else if (match(R"(bdrip|brrip|blu[\s._-]?ray)")) parts << QStringLiteral("BDRip");
    else if (match(R"(web(?:[\s._-]?(?:dl|rip))?)")) parts << QStringLiteral("WEB-DL");
    if (audio != QStringLiteral("Stereo")) parts << audio;

    QString label = parts.join(QStringLiteral(" · "));
    if (hasExactBitrate) {
        if (!label.isEmpty()) label += QLatin1Char(' ');
        label += QStringLiteral("(%1 Мбит/с)").arg(QString::number(bitrate, 'f', 1));
    } else if (sizeBytes > 0) {
        if (!label.isEmpty()) label += QLatin1Char(' ');
        label += QStringLiteral("(%1)").arg(formatBytes(sizeBytes));
    }

    PartyVariant v;
    v.key = watchKey(item.m.id, file);
    v.label = label;
    v.quality = quality;
    v.audio = audio;
    v.bitrate = bitrate;
    v.offsetMs = 0;
    v.score = score;
    v.hardwareFlags = hwFlags;
    return v;
}

} // namespace suslik::core
