#include "AudioAttach.h"
#include "Library.h"

#include "suslik/core/ApiClient.h"
#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"
#include "suslik/core/UrlCoding.h"

#include <QDateTime>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QPointer>
#include <QRegularExpression>
#include <QSet>
#include <QTimer>

namespace suslik::app {

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
QHash<qint64, QList<CustomTrack>> &trackCache() { static QHash<qint64, QList<CustomTrack>> c; return c; }
QSet<qint64> &attachedSources() { static QSet<qint64> s; return s; } // к ним привязаны озвучки: не удалять
Store &offsets() { return Store::of(S("audio_offsets")); }
Store &pending() { return Store::of(S("audio_pending")); }

AudioDesk::Problem problemOf(const ApiResult &r) {
    const QString code = r.error.body.value(S("code")).toString();
    return {code, ApiClient::describe(r.error), r.error.body};
}
} // namespace

// ---- сеть и кэш ----
void AudioDesk::tracks(Library &lib, qint64 magnetId, bool fresh, std::function<void(QList<CustomTrack>)> done) {
    if (!fresh && trackCache().contains(magnetId)) { done(trackCache().value(magnetId)); return; }
    lib.api().get(CustomAudio::tracksPath(magnetId), [magnetId, done](const ApiResult &r) {
        if (!r.ok()) { done(trackCache().value(magnetId)); return; } // нет сети или старый сервер: фильм играет и без них
        const auto list = CustomAudio::parseTracks(r.json);
        for (const auto &t : list)
            if (t.audioMagnetId) attachedSources().insert(*t.audioMagnetId);
        trackCache().insert(magnetId, list);
        done(list);
    });
}

void AudioDesk::check(Library &lib, qint64 audioId, std::optional<qint64> movieId, std::function<void(CustomCheck)> ok, Fail fail) {
    lib.api().post(S(CustomAudio::CHECK_PATH), CustomAudio::checkBody(audioId, movieId), [ok, fail](const ApiResult &r) {
        if (!r.ok()) fail(problemOf(r)); else ok(CustomAudio::parseCheck(r.json));
    });
}

// Проверка по самому .torrent: сервер только разбирает его, в AllDebrid не загружает.
void AudioDesk::checkTorrent(Library &lib, const QByteArray &bytes, std::optional<qint64> movieId, std::function<void(CustomCheck)> ok, Fail fail) {
    QNetworkRequest req = lib.api().request(S(CustomAudio::CHECK_PATH) + (movieId ? S("?movieMagnetId=%1").arg(*movieId) : QString()));
    req.setHeader(QNetworkRequest::ContentTypeHeader, S("application/x-bittorrent"));
    lib.api().send(req, "POST", bytes, [ok, fail](const ApiResult &r) {
        if (!r.ok()) fail(problemOf(r)); else ok(CustomAudio::parseCheck(r.json));
    });
}

void AudioDesk::attach(Library &lib, qint64 audioId, std::optional<qint64> movieId, std::function<void(QList<QJsonObject>)> ok, Fail fail) {
    lib.api().post(S(CustomAudio::ATTACH_PATH), CustomAudio::checkBody(audioId, movieId), [audioId, ok, fail](const ApiResult &r) {
        if (!r.ok()) { fail(problemOf(r)); return; }
        trackCache().clear();
        attachedSources().insert(audioId);
        ok(CustomAudio::parseAttached(r.json));
    });
}

void AudioDesk::saveOffset(Library &lib, const QString &trackId, qint64 ms, std::function<void(bool)> done) {
    QNetworkRequest req = lib.api().request(CustomAudio::offsetPath(trackId));
    req.setHeader(QNetworkRequest::ContentTypeHeader, S("application/json"));
    lib.api().send(req, "PUT", QJsonDocument(CustomAudio::offsetBody(ms)).toJson(QJsonDocument::Compact), [done](const ApiResult &r) {
        trackCache().clear();
        done(r.ok());
    });
}

// Задержка, подобранная для файла озвучки: та же озвучка у другой раздачи фильма получает её сразу (23.10).
void AudioDesk::rememberOffset(qint64 audioId, const QString &audioPath, qint64 ms) {
    offsets().put(QString::number(audioId) + QLatin1Char('|') + audioPath, QString::number(ms));
}

std::optional<qint64> AudioDesk::knownOffset(qint64 audioId, const QString &audioPath) {
    bool ok = false;
    const qint64 v = offsets().get(QString::number(audioId) + QLatin1Char('|') + audioPath).value_or(QString()).toLongLong(&ok);
    if (ok) return v;
    for (const auto &list : trackCache())
        for (const auto &t : list)
            if (t.audioMagnetId == audioId && t.audioPath == audioPath) return t.offsetMs;
    return std::nullopt;
}

void AudioDesk::markPending(qint64 id) { pending().put(QString::number(id), QString::number(QDateTime::currentMSecsSinceEpoch())); }
void AudioDesk::donePending(qint64 id) { pending().put(QString::number(id), std::nullopt); }

void AudioDesk::dropUnattached(Library &lib, const QList<qint64> &ids) {
    ApiClient *api = &lib.api();
    for (qint64 id : ids) {
        if (attachedSources().contains(id)) { donePending(id); continue; } // привязана в другом месте: оставляем
        api->post(S("/api/magnets/%1/delete").arg(id), {}, [api, id](const ApiResult &r) {
            if (r.ok()) { donePending(id); return; }
            api->post(S("/api/magnets/%1/cancel").arg(id), {}, [id](const ApiResult &c) { if (c.ok()) donePending(id); });
        });
    }
}

void AudioDesk::cleanupPending(Library &lib) {
    static bool cleaned = false;
    if (cleaned) return;
    cleaned = true;
    QList<qint64> ids;
    for (const QString &k : pending().keys())
        if (bool ok = false; k.toLongLong(&ok), ok) ids << k.toLongLong();
    if (!ids.isEmpty()) dropUnattached(lib, ids);
}

QString AudioDesk::describe(const Problem &p) {
    const QJsonObject &b = p.body;
    if (p.code == QLatin1String("FORBIDDEN_FILES")) {
        const QJsonArray f = b.value(S("forbidden")).toArray();
        QStringList names;
        for (int i = 0; i < std::min<int>(int(f.size()), 5); ++i) names << f[i].toObject().value(S("path")).toString().section(QLatin1Char('/'), -1);
        return tx(S("audio.zapreshchennye_fayly"), {{"n", QString::number(f.size())}, {"files", names.join(S(", "))}}) +
               (b.value(S("removed")).toBool() ? S(" ") + tx(S("audio.razdacha_udalena")) : QString());
    }
    if (p.code == QLatin1String("EPISODE_MISMATCH")) {
        QStringList eps;
        for (const auto &v : b.value(S("episodes")).toArray()) eps << v.toVariant().toString();
        return tx(S("audio.net_seriy"), {{"list", eps.join(S(", "))}});
    }
    const QJsonArray all = b.value(S("errors")).toArray();
    if (all.size() > 1) {
        QStringList lines;
        for (const auto &v : all) {
            const QJsonObject o = v.toObject();
            QString m = o.value(S("message")).toString();
            if (m.trimmed().isEmpty()) m = o.value(S("error")).toString();
            lines << (m.trimmed().isEmpty() ? (v.isString() ? v.toString() : QString::fromUtf8(QJsonDocument(o).toJson(QJsonDocument::Compact))) : m);
        }
        return lines.join(QLatin1Char('\n'));
    }
    return p.message.isEmpty() ? p.code : p.message;
}

// ---- экран «Озвучка» ----
AudioAttachModel::~AudioAttachModel() = default;

std::optional<qint64> AudioAttachModel::parseOffset(const QString &text) {
    QString t = text.trimmed();
    t.replace(QChar(0x2212), QLatin1Char('-'));
    if (t.startsWith(QLatin1Char('+'))) t.remove(0, 1);
    bool ok = false;
    const qint64 v = t.toLongLong(&ok);
    if (!ok || v < -60000 || v > 60000) return std::nullopt;
    return v;
}

bool AudioAttachModel::Source::ready() const {
    if (busy || done || valid != 1 || !audioId || rows.isEmpty()) return false;
    for (const Row &r : rows)
        if (!parseOffset(r.offset)) return false;
    return true;
}

AudioAttachModel::Source *AudioAttachModel::find(qint64 key) {
    for (Source &s : m_sources)
        if (s.key == key) return &s;
    return nullptr;
}

int AudioAttachModel::readyCount() const {
    int n = 0;
    for (const Source &s : m_sources) n += s.ready() ? 1 : 0;
    return n;
}

bool AudioAttachModel::isReady(qint64 key) const {
    for (const Source &s : m_sources)
        if (s.key == key) return s.ready();
    return false;
}

QVariantList AudioAttachModel::sources() const {
    QVariantList out;
    for (const Source &s : m_sources) {
        QVariantList cands, rows;
        for (const auto &[id, name] : s.candidates.mid(0, 6)) cands << QVariantMap{{S("id"), id}, {S("name"), name}};
        const LibraryItem *movie = s.movieId && m_lib ? m_lib->item(*s.movieId) : nullptr;
        for (const Row &r : s.rows) {
            const CustomPair &p = r.pair;
            const QString ep = p.season && p.episode ? S("S%1E%2 · ").arg(*p.season, 2, 10, QLatin1Char('0')).arg(*p.episode, 2, 10, QLatin1Char('0')) : QString();
            rows << QVariantMap{{S("audio"), p.audioPath.section(QLatin1Char('/'), -1)},
                                {S("videoTitle"), movie ? movie->baseTitle : S("—")},
                                {S("video"), ep + p.videoPath.section(QLatin1Char('/'), -1)},
                                {S("voice"), p.voice.trimmed().isEmpty() ? S("—") : p.voice},
                                {S("offset"), r.offset}};
        }
        out << QVariantMap{{S("key"), s.key}, {S("name"), s.name}, {S("torrent"), !s.torrent.isEmpty()}, {S("busy"), s.busy},
                           {S("note"), s.note}, {S("noteOk"), s.noteOk}, {S("valid"), s.valid}, {S("done"), s.done},
                           {S("candidates"), cands}, {S("rows"), rows},
                           {S("targetCount"), int(s.targetIds.size())}, {S("targetNames"), s.targetNames.join(S(", "))},
                           {S("moviePoster"), movie ? const_cast<Library *>(m_lib)->posterPath(movie->posterQueries) : QString()},
                           {S("canCheck"), !s.busy && !s.done && s.valid != 1}};
    }
    return out;
}

void AudioAttachModel::say(qint64 key, const QString &text, bool ok) {
    if (Source *s = find(key)) { s->note = text; s->noteOk = ok; emit changed(); emit readyChanged(); }
}

void AudioAttachModel::addInput(const QString &text) {
    QStringList links;
    for (const QString &w : text.split(QRegularExpression(S("\\s+")), Qt::SkipEmptyParts))
        if (w.trimmed().startsWith(S("magnet:?"))) links << w.trimmed();
    if (links.isEmpty()) { setNote(tx(S("add.vstavte_ssylku_kotoraya_nachin"))); return; }
    for (const QString &l : links) add(l, QString());
}

void AudioAttachModel::addTorrents(const QList<QUrl> &files) {
    for (const QUrl &u : files) {
        const QString path = u.isLocalFile() ? u.toLocalFile() : u.toString();
        if (path.endsWith(S(".torrent"), Qt::CaseInsensitive)) add(QString(), path);
    }
}

void AudioAttachModel::add(const QString &magnet, const QString &torrent) {
    if (!torrent.isEmpty() && QFileInfo(torrent).size() > 1024 * 1024) { setNote(tx(S("add.fayl_bolshe_mb"))); return; }
    for (const Source &s : m_sources)
        if (!s.done && ((!magnet.isEmpty() && s.magnet == magnet) || (!torrent.isEmpty() && s.torrent == torrent))) return;
    Source s;
    s.key = m_next++;
    s.magnet = magnet;
    s.torrent = torrent;
    if (!torrent.isEmpty()) {
        s.name = QFileInfo(torrent).fileName();
        if (s.name.endsWith(S(".torrent"), Qt::CaseInsensitive)) s.name.chop(8);
    } else {
        static const QRegularExpression dnRe(S("[?&]dn=([^&]+)"));
        const auto m = dnRe.match(magnet);
        s.name = m.hasMatch() ? formDecode(m.captured(1)).value_or(tx(S("add.magnet_ssylka"))) : tx(S("add.magnet_ssylka"));
    }
    m_sources << s;
    m_note.clear();
    emit changed();
    check(s.key);
}

void AudioAttachModel::failed(qint64 key, const AudioDesk::Problem &p) {
    Source *s = find(key);
    if (!s) return;
    if (p.code == QLatin1String("FORBIDDEN_FILES")) {
        s->valid = 0;
        // лишние файлы: эту раздачу не привяжут — сразу убрать
        if (s->audioId) { if (!s->existed) AudioDesk::dropUnattached(*m_lib, {*s->audioId}); s->audioId.reset(); }
    }
    if (p.code == QLatin1String("AMBIGUOUS")) {
        s->candidates.clear();
        for (const auto &v : p.body.value(S("candidates")).toArray()) {
            const QJsonObject c = v.toObject();
            qint64 id = c.value(S("magnetId")).toInteger();
            if (id <= 0) id = c.value(S("id")).toInteger();
            if (id <= 0) continue;
            QString name = c.value(S("name")).toString();
            if (name.isEmpty()) name = c.value(S("title")).toString();
            if (name.isEmpty()) name = S("#%1").arg(id);
            s->candidates << std::make_pair(id, name);
        }
    }
    s->busy = false;
    say(key, AudioDesk::describe(p), false);
}

/// Загрузить раздачу в AllDebrid (если ещё нет), дождаться файлов и проверить на сервере.
void AudioAttachModel::check(qint64 key) {
    Source *s = find(key);
    if (!s || s->busy) return;
    s->busy = true; s->valid = -1; s->rows.clear(); s->candidates.clear();
    emit changed();
    if (s->audioId) { runCheck(key); return; }
    say(key, tx(S("add.otpravlyayu")), true);
    QPointer<AudioAttachModel> self(this);
    // что уже есть в AllDebrid до загрузки (список свежий: раздачу могли добавить с другого устройства)
    m_lib->refreshThen([self, key] {
        if (!self) return;
        Source *s = self->find(key);
        if (!s) return;
        auto upload = [self, key](const ApiResult &r) {
            if (!self) return;
            if (!r.ok()) { self->failed(key, problemOf(r)); return; }
            self->afterUpload(key, r.json.value(S("id")).toInteger(), r.json.value(S("ready")).toBool());
        };
        if (!s->torrent.isEmpty()) {
            QFile f(s->torrent);
            if (!f.open(QIODevice::ReadOnly)) { self->failed(key, {QString(), tx(S("add.vyberite_fayl_torrent")), {}}); return; }
            const QByteArray bytes = f.readAll();
            const QString name = QFileInfo(s->torrent).fileName();
            // сначала только разбор: раздачу с лишними файлами не загружаем вовсе
            AudioDesk::checkTorrent(*self->m_lib, bytes, s->movieId,
                [self, bytes, name, upload](const CustomCheck &) { if (self) self->m_lib->api().postTorrent(bytes, name, upload); },
                [self, key](const AudioDesk::Problem &p) { if (self) self->failed(key, p); });
        } else {
            self->m_lib->api().post(S("/api/magnets"), QJsonObject{{S("magnet"), s->magnet}}, upload);
        }
    });
}

void AudioAttachModel::afterUpload(qint64 key, qint64 id, bool readyNow) {
    Source *s = find(key);
    if (!s) return;
    s->audioId = id;
    // AllDebrid отдаёт ту же раздачу, если она уже добавлена (тот же хеш): такую при отмене не трогаем
    QSet<qint64> known;
    for (const auto &i : m_lib->items()) known.insert(i.m.id);
    for (const auto &v : m_lib->voiceOvers()) known.insert(v.id);
    bool other = false;
    for (const Source &o : m_sources)
        if (o.key != key && o.audioId == id) other = true;
    s->existed = known.contains(id) || other;
    if (!s->existed) AudioDesk::markPending(id);
    if (readyNow) runCheck(key);
    else waitReady(key, id, 0);
}

void AudioAttachModel::waitReady(qint64 key, qint64 id, int round) {
    if (round >= 120) { if (Source *s = find(key)) { s->busy = false; say(key, tx(S("audio.ne_dozhdalis")), false); } return; }
    QPointer<AudioAttachModel> self(this);
    m_lib->api().get(S("/api/magnets/%1").arg(id), [self, key, id, round](const ApiResult &r) {
        if (!self || !self->find(key)) return;
        if (r.ok()) {
            const int code = r.json.value(S("statusCode")).toInt();
            if (code == 4 || r.json.value(S("ready")).toBool()) { self->runCheck(key); return; }
            if (code >= 5) {
                self->find(key)->busy = false;
                self->say(key, tx(S("add.oshibka_p"), {{"p", r.json.value(S("status")).toString()}}), false);
                return;
            }
            const qint64 total = r.json.value(S("size")).toInteger(), got = r.json.value(S("downloaded")).toInteger();
            self->say(key, tx(S("audio.kachaet"), {{"p", total > 0 ? S("%1%").arg(got * 100 / total) : r.json.value(S("status")).toString()}}), true);
        }
        QTimer::singleShot(round < 15 ? 4000 : 15000, self, [self, key, id, round] { if (self) self->waitReady(key, id, round + 1); });
    });
}

void AudioAttachModel::runCheck(qint64 key) {
    Source *s = find(key);
    if (!s || !s->audioId) return;
    QPointer<AudioAttachModel> self(this);
    const qint64 aid = *s->audioId;
    AudioDesk::check(*m_lib, aid, s->movieId, [self, key, aid](const CustomCheck &c) {
        if (!self) return;
        Source *s = self->find(key);
        if (!s) return;
        s->valid = 1;
        s->targetIds = c.targetMagnetIds;
        s->targetNames = c.targetMovieNames;
        if (c.movieMagnetId) s->movieId = c.movieMagnetId;
        auto fill = [self, key, aid, c](const QList<CustomTrack> &bound) {
            if (!self) return;
            Source *s = self->find(key);
            if (!s) return;
            // уже привязанные к этому фильму дорожки: их задержка важнее всего (свежий список с сервера)
            for (const CustomPair &pair : c.pairs) {
                Row r;
                r.pair = pair;
                r.initial = pair.offsetMs.value_or(0);
                std::optional<qint64> known;
                for (const auto &t : bound)
                    if (t.audioMagnetId == aid && t.audioPath == pair.audioPath && t.videoPath == pair.videoPath) known = t.offsetMs;
                if (!known) known = AudioDesk::knownOffset(aid, pair.audioPath);
                if (known) r.initial = *known;
                r.offset = QString::number(r.initial);
                s->rows << r;
            }
            s->busy = false;
            self->say(key, c.pairs.isEmpty() ? tx(S("audio.net_par")) : tx(S("audio.naydeno_par"), {{"n", QString::number(c.pairs.size())}}), !c.pairs.isEmpty());
        };
        if (s->movieId) AudioDesk::tracks(*self->m_lib, *s->movieId, true, fill);
        else fill({});
    }, [self, key](const AudioDesk::Problem &p) { if (self) self->failed(key, p); });
}

void AudioAttachModel::chooseMovie(qint64 key, qint64 movieId) {
    if (Source *s = find(key)) { s->movieId = movieId; check(key); }
}

void AudioAttachModel::remove(qint64 key) {
    for (int i = 0; i < m_sources.size(); ++i)
        if (m_sources[i].key == key) {
            const Source s = m_sources.takeAt(i);
            if (!s.done && !s.existed && s.audioId) AudioDesk::dropUnattached(*m_lib, {*s.audioId});
            break;
        }
    emit changed();
    emit readyChanged();
}

void AudioAttachModel::setOffset(qint64 key, int row, const QString &text) {
    Source *s = find(key);
    if (!s || row < 0 || row >= s->rows.size()) return;
    s->rows[row].offset = text;
    emit readyChanged();
}

void AudioAttachModel::applyAll(qint64 key, const QString &text) {
    const auto v = parseOffset(text);
    Source *s = find(key);
    if (!s || !v) return;
    for (Row &r : s->rows) r.offset = QString::number(*v);
    emit changed();
    emit readyChanged();
}

void AudioAttachModel::attach(qint64 key) {
    Source *s = find(key);
    if (!s || !s->ready()) return;
    QHash<QString, qint64> wanted;
    QSet<QString> edited; // задержку поменяли руками: только её можно записать поверх уже привязанной дорожки
    for (const Row &r : s->rows) {
        const qint64 v = parseOffset(r.offset).value_or(0);
        wanted.insert(r.pair.audioPath, v);
        if (v != r.initial) edited.insert(r.pair.audioPath);
    }
    s->busy = true;
    emit changed();
    const qint64 aid = *s->audioId;
    QPointer<AudioAttachModel> self(this);
    AudioDesk::attach(*m_lib, aid, s->movieId, [self, key, aid, wanted, edited](const QList<QJsonObject> &tracks) {
        if (!self) return;
        // сервер берёт задержку из имени файла; поправленную в таблице сохраняем каждой дорожке отдельно
        auto left = std::make_shared<int>(1);
        auto failedN = std::make_shared<int>(0);
        const int n = int(tracks.size());
        auto finish = [self, key, aid, wanted, left, failedN, n] {
            if (--*left > 0 || !self) return;
            for (auto it = wanted.cbegin(); it != wanted.cend(); ++it) AudioDesk::rememberOffset(aid, it.key(), it.value());
            AudioDesk::donePending(aid);
            Source *s = self->find(key);
            if (!s) return;
            s->done = true;
            s->busy = false;
            if (*failedN > 0) self->say(key, tx(S("audio.zaderzhka_ne_sohranena"), {{"n", QString::number(*failedN)}}), false);
            else self->say(key, tx(S("audio.privyazano"), {{"n", QString::number(n)}}), true);
        };
        for (const QJsonObject &t : tracks) {
            const QString path = t.value(S("audioPath")).toString();
            if (!wanted.contains(path)) continue;
            // 23.10: повторная привязка вернула уже бывшие дорожки, и задержка по умолчанию записалась поверх подобранной
            if (t.value(S("existing")).toBool() && !edited.contains(path)) continue;
            const qint64 want = wanted.value(path);
            if (want == t.value(S("offsetMs")).toInteger()) continue;
            ++*left;
            AudioDesk::saveOffset(*self->m_lib, t.value(S("id")).toString(), want, [failedN, finish](bool ok) { if (!ok) ++*failedN; finish(); });
        }
        finish();
    }, [self, key](const AudioDesk::Problem &p) { if (self) self->failed(key, p); });
}

void AudioAttachModel::attachAll() {
    QList<qint64> keys;
    for (const Source &s : m_sources)
        if (s.ready()) keys << s.key;
    for (qint64 k : keys) attach(k);
}

void AudioAttachModel::preview(qint64 key, int row) {
    Source *s = find(key);
    if (!s || row < 0 || row >= s->rows.size()) return;
    const CustomPair pair = s->rows[row].pair;
    const qint64 offset = parseOffset(s->rows[row].offset).value_or(0);
    const LibraryItem *movie = s->movieId ? m_lib->item(*s->movieId) : nullptr;
    std::optional<MFile> video;
    if (movie)
        for (const MFile &f : movie->m.files)
            if (f.path == pair.videoPath) video = f;
    if (!movie || !video || !video->link) { say(key, tx(S("audio.net_video")), false); return; }
    const QString title = tx(S("audio.predprosmotr_t"), {{"t", movie->baseTitle}});
    QPointer<AudioAttachModel> self(this);
    const std::optional<qint64> aid = s->audioId;
    auto withAudio = [self, aid, pair]() -> std::optional<MFile> {
        if (!self) return std::nullopt;
        std::optional<MFile> audio;
        for (const Magnet &v : self->m_lib->voiceOvers())
            if (aid && v.id == *aid)
                for (const MFile &f : v.files)
                    if (f.path == pair.audioPath && f.link) audio = f;
        return audio;
    };
    auto go = [self, key, video, offset, title](const MFile &audio) {
        if (!self) return;
        ApiClient &api = self->m_lib->api();
        auto urls = std::make_shared<std::pair<QString, QString>>();
        auto left = std::make_shared<int>(2);
        auto done = [self, key, urls, left, offset, title] {
            if (--*left > 0 || !self) return;
            if (urls->first.isEmpty() || urls->second.isEmpty()) { self->say(key, tx(S("api.net_svyazi_s_serverom")), false); return; }
            emit self->previewRequested(urls->first, urls->second, offset, title);
        };
        auto mirror = [](const MFile &f) { return S("/api/file/mirror-url?link=") + formEncode(*f.link) + S("&name=") + formEncode(f.name); };
        api.get(mirror(*video), [urls, done](const ApiResult &r) { urls->first = r.json.value(S("url")).toString(); done(); });
        api.get(mirror(audio), [urls, done](const ApiResult &r) { urls->second = r.json.value(S("url")).toString(); done(); });
    };
    if (const auto a = withAudio()) { go(*a); return; }
    m_lib->refreshThen([self, key, withAudio, go] {
        if (!self) return;
        if (const auto a = withAudio()) go(*a);
        else self->say(key, tx(S("audio.net_fayla_ozvuchki")), false);
    });
}

void AudioAttachModel::leave() {
    QList<qint64> left;
    for (const Source &s : m_sources)
        if (!s.done && !s.existed && s.audioId) left << *s.audioId;
    m_sources.clear();
    emit changed();
    emit readyChanged();
    if (!left.isEmpty()) AudioDesk::dropUnattached(*m_lib, left);
}

} // namespace suslik::app
