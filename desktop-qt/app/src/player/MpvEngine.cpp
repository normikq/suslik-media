#include "MpvEngine.h"
#include "../AppDir.h"
#include "../Settings.h"

#include "suslik/core/Strings.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QLocale>

namespace suslik::app {

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
QString num(double v) { return QString::number(v, 'f', 3); }

/// Ночной звук: компрессор (тише взрывы, громче голоса).
const char *kNightFilter = "lavfi=[acompressor=threshold=0.063:ratio=6:attack=25:release=100:knee=2:makeup=3]";

/// mpv_node → QVariant (списки дорожек, глав).
QVariant fromNode(const mpv_node &n) {
    switch (n.format) {
    case MPV_FORMAT_STRING: return QString::fromUtf8(n.u.string);
    case MPV_FORMAT_FLAG: return bool(n.u.flag);
    case MPV_FORMAT_INT64: return qint64(n.u.int64);
    case MPV_FORMAT_DOUBLE: return n.u.double_;
    case MPV_FORMAT_NODE_ARRAY: {
        QVariantList l;
        for (int i = 0; i < n.u.list->num; ++i) l << fromNode(n.u.list->values[i]);
        return l;
    }
    case MPV_FORMAT_NODE_MAP: {
        QVariantMap m;
        for (int i = 0; i < n.u.list->num; ++i) m.insert(QString::fromUtf8(n.u.list->keys[i]), fromNode(n.u.list->values[i]));
        return m;
    }
    default: return {};
    }
}

constexpr quint64 TAG_SUB = 1, TAG_AUDIO = 2;
} // namespace

MpvEngine::MpvEngine(QObject *parent) : QObject(parent) {}

MpvEngine::~MpvEngine() { release(); }

mpv::Handle MpvEngine::handle() {
    if (m_h || m_failedStart) return m_h;
    const mpv::Api *a = mpv::api();
    if (!a) return {}; // библиотеку могут докачать: спросим снова
    mpv_handle *p = a->create();
    if (!p) { m_failedStart = true; return {}; }
    const auto opt = [a, p](const char *n, const QString &v) { a->set_option_string(p, n, v.toUtf8().constData()); };
    opt("vo", S("libmpv"));
    // SUSLIK_MPV_HWDEC — только для проверки (no, nvdec-copy…), по умолчанию auto-safe
    const QString hw = qEnvironmentVariable("SUSLIK_MPV_HWDEC");
    opt("hwdec", hw.isEmpty() ? S("auto-safe") : hw);
#ifdef SM_DIAG
    // журнал mpv (в нём адреса с подписью) — только в отладочной сборке
    opt("log-file", AppDir::file(S("mpv.log")));
    opt("msg-level", S("all=v"));
#else
    if (qEnvironmentVariableIsSet("SUSLIK_MPV_LOG")) opt("log-file", AppDir::file(S("mpv.log")));
    opt("msg-level", S("all=warn"));
#endif
    opt("load-scripts", S("no"));
    opt("ytdl", S("no"));
    opt("keep-open", S("yes")); // в конце остаёмся на последнем кадре, «конец» сообщаем сами
    opt("idle", S("yes"));
    for (const char *n : {"osc", "input-default-bindings", "input-vo-keyboard", "input-cursor", "cursor-autohide", "terminal",
                          "sub-auto", "audio-file-auto", "demuxer-mkv-subtitle-preroll"})
        opt(n, S("no"));
    opt("osd-level", S("0"));
    opt("cache", S("yes"));
    opt("cache-pause", S("yes"));
    opt("cache-pause-initial", S("yes"));
    opt("cache-pause-wait", S("1"));
    // без отката назад при смене субтитров и перемотке: иначе большие запросы диапазонов назад по HTTP
    opt("demuxer-mkv-subtitle-preroll", S("no"));
    opt("demuxer-mkv-subtitle-preroll-secs", S("1"));
    // Ключевые кадры позади — в памяти (смена дорожки и короткая перемотка без сети); долгий запас — на диске (BurstProxy).
    opt("demuxer-max-bytes", S("96MiB"));
    opt("demuxer-max-back-bytes", S("24MiB"));
    opt("gapless-audio", S("yes"));
    opt("audio-stream-silence", S("yes"));
    opt("network-timeout", S("30"));
    opt("stream-lavf-o", S("reconnect=1,reconnect_streamed=1,reconnect_delay_max=5"));
    opt("audio-client-name", S("Suslik Media"));
    opt("volume-max", S("150"));
    if (const int rc = a->initialize(p); rc < 0) {
        a->terminate_destroy(p);
        m_failedStart = true;
        return {};
    }
    a->observe_property(p, 1, "time-pos", MPV_FORMAT_DOUBLE);
    a->observe_property(p, 2, "duration", MPV_FORMAT_DOUBLE);
    a->observe_property(p, 3, "pause", MPV_FORMAT_FLAG);
    a->observe_property(p, 5, "paused-for-cache", MPV_FORMAT_FLAG);
    a->observe_property(p, 6, "eof-reached", MPV_FORMAT_FLAG);
    a->observe_property(p, 7, "cache-buffering-state", MPV_FORMAT_INT64);
    a->observe_property(p, 8, "track-list", MPV_FORMAT_NONE);
    a->observe_property(p, 9, "video-params/dw", MPV_FORMAT_INT64);
    a->observe_property(p, 10, "chapter-list", MPV_FORMAT_NONE);
    // Последний отпустивший (движок или вывод кадров) закрывает ядро.
    m_h = mpv::Handle(p, [a](mpv_handle *h) { a->terminate_destroy(h); });
    a->set_wakeup_callback(p, [](void *ctx) {
        auto *self = static_cast<MpvEngine *>(ctx);
        QMetaObject::invokeMethod(self, [self] { self->drain(); }, Qt::QueuedConnection);
    }, this);
    return m_h;
}

void MpvEngine::release() {
    if (!m_h) return;
    const mpv::Api *a = mpv::api();
    a->set_wakeup_callback(m_h.get(), nullptr, nullptr);
    a->command(m_h.get(), std::array<const char *, 2>{"stop", nullptr}.data());
    m_h.reset();
}

void MpvEngine::drain() {
    if (!m_h) return;
    const mpv::Api *a = mpv::api();
    // Не больше сотни событий за раз: окно остаётся отзывчивым, остальное — следующим кругом.
    for (int i = 0; i < 100 && m_h; ++i) {
        mpv_event *ev = a->wait_event(m_h.get(), 0);
        if (ev->event_id == MPV_EVENT_NONE) return;
        onEvent(*ev);
    }
    if (m_h) QMetaObject::invokeMethod(this, [this] { drain(); }, Qt::QueuedConnection);
}

void MpvEngine::onEvent(const mpv_event &ev) {
    switch (ev.event_id) {
    case MPV_EVENT_FILE_LOADED: {
        m_loaded = true;
        addSideTracks();
        readTracks();
        updateVideoSize();
        emit opened();
        emit stateChanged();
        break;
    }
    case MPV_EVENT_PLAYBACK_RESTART:
        emit seekDone();
        emit stateChanged();
        break;
    case MPV_EVENT_PROPERTY_CHANGE:
        onProperty(*static_cast<mpv_event_property *>(ev.data));
        break;
    case MPV_EVENT_COMMAND_REPLY: {
        // озвучка или субтитры добавлены (или не вышло): этого открытия — меньше ждём
        if ((ev.reply_userdata >> 8) != m_openGen) break;
        if ((ev.reply_userdata & 0xff) == TAG_SUB) m_pendingSubs = std::max(0, m_pendingSubs - 1);
        else if ((ev.reply_userdata & 0xff) == TAG_AUDIO) m_pendingAudio = std::max(0, m_pendingAudio - 1);
        readTracks();
        break;
    }
    case MPV_EVENT_END_FILE: {
        const auto *e = static_cast<mpv_event_end_file *>(ev.data);
        if (e->reason == MPV_END_FILE_REASON_EOF) emit finished();
        else if (e->reason == MPV_END_FILE_REASON_ERROR) emit failed(QString::fromUtf8(mpv::api()->error_string(e->error)));
        break;
    }
    default: break;
    }
}

void MpvEngine::onProperty(const mpv_event_property &p) {
    const QByteArray name(p.name);
    const bool has = p.data != nullptr;
    if (name == "time-pos" && has && p.format == MPV_FORMAT_DOUBLE) {
        // До открытия нового файла time-pos ещё от прошлого (события в очереди перед loadfile): не берём.
        if (!m_loaded) return;
        m_time = *static_cast<double *>(p.data);
        if (!m_playingSent && m_time > 0) { m_playingSent = true; emit stateChanged(); }
        const qint64 now = QDateTime::currentMSecsSinceEpoch();
        if (now - m_lastTimeSignal >= 200) { m_lastTimeSignal = now; emit timeChanged(); }
    } else if (name == "duration" && has && p.format == MPV_FORMAT_DOUBLE) {
        const double d = *static_cast<double *>(p.data);
        if (d > 0) { m_length = d; emit lengthChanged(); }
    } else if (name == "pause" && has) {
        m_pause = *static_cast<int *>(p.data) != 0;
        emit stateChanged();
    } else if (name == "paused-for-cache" && has) {
        m_cacheWait = *static_cast<int *>(p.data) != 0;
        if (!m_cacheWait) m_cachePercent = 100;
        emit stateChanged();
    } else if (name == "cache-buffering-state" && has && p.format == MPV_FORMAT_INT64) {
        m_cachePercent = int(std::clamp<qint64>(*static_cast<qint64 *>(p.data), 0, 100));
        if (m_cacheWait) emit stateChanged();
    } else if (name == "eof-reached" && has) {
        if (*static_cast<int *>(p.data) != 0 && m_loaded) emit finished();
    } else if (name == "track-list" || name == "chapter-list") {
        readTracks();
    } else if (name == "video-params/dw") {
        updateVideoSize();
    }
}

void MpvEngine::readTracks() {
    if (!m_h) return;
    const mpv::Api *a = mpv::api();
    mpv_node n;
    QVariantList audio, subs, chapters;
    if (a->get_property(m_h.get(), "track-list", MPV_FORMAT_NODE, &n) >= 0) {
        for (const QVariant &v : fromNode(n).toList()) {
            const QVariantMap t = v.toMap();
            const QString type = t.value(S("type")).toString();
            if (type != S("audio") && type != S("sub")) continue;
            const QString title = t.value(S("title")).toString(), lang = t.value(S("lang")).toString();
            QString label = title;
            if (!lang.isEmpty()) label += (label.isEmpty() ? QString() : S(" ")) + S("[%1]").arg(lang);
            if (label.isEmpty()) label = S("%1 #%2").arg(type).arg(t.value(S("id")).toInt());
            QVariantMap row{{S("id"), t.value(S("id")).toInt()}, {S("title"), title}, {S("lang"), lang},
                            {S("forced"), t.value(S("forced")).toBool()}, {S("selected"), t.value(S("selected")).toBool()},
                            {S("external"), t.value(S("external")).toBool()}, {S("label"), label}};
            (type == S("audio") ? audio : subs) << row;
        }
        a->free_node_contents(&n);
    }
    if (a->get_property(m_h.get(), "chapter-list", MPV_FORMAT_NODE, &n) >= 0) {
        int i = 0;
        for (const QVariant &v : fromNode(n).toList()) {
            const QVariantMap c = v.toMap();
            ++i;
            const QString title = c.value(S("title")).toString();
            chapters << QVariantMap{{S("timeMs"), qint64(c.value(S("time")).toDouble() * 1000)},
                                    {S("title"), title.isEmpty() ? core::Strings::get(S("player.glava_n")).replace(S("{n}"), QString::number(i)) : title}};
        }
        a->free_node_contents(&n);
    }
    m_audio = audio;
    m_subs = subs;
    m_chapters = chapters;
    emit tracksChanged();
}

void MpvEngine::updateVideoSize() {
    const int w = property(S("video-params/dw")).toInt(), h = property(S("video-params/dh")).toInt(); // размер на экране (с учётом пропорций пикселя)
    if (w != m_videoW || h != m_videoH) { m_videoW = w; m_videoH = h; emit videoSizeChanged(); }
}

QString MpvEngine::property(const QString &name) {
    if (!m_h) return {};
    const mpv::Api *a = mpv::api();
    char *s = a->get_property_string(m_h.get(), name.toUtf8().constData());
    if (!s) return {};
    const QString out = QString::fromUtf8(s);
    a->free(s);
    return out;
}

void MpvEngine::set(const char *name, const QString &value) {
    if (!handle()) return;
    mpv::api()->set_property_string(m_h.get(), name, value.toUtf8().constData());
}

void MpvEngine::command(const QStringList &args) {
    if (!handle()) return;
    QList<QByteArray> bytes;
    for (const QString &s : args) bytes << s.toUtf8();
    std::vector<const char *> argv;
    for (const QByteArray &b : bytes) argv.push_back(b.constData());
    argv.push_back(nullptr);
    mpv::api()->command(m_h.get(), argv.data());
}

void MpvEngine::commandAsync(quint64 tag, const QStringList &args) {
    if (!handle()) return;
    QList<QByteArray> bytes;
    for (const QString &s : args) bytes << s.toUtf8();
    std::vector<const char *> argv;
    for (const QByteArray &b : bytes) argv.push_back(b.constData());
    argv.push_back(nullptr);
    mpv::api()->command_async(m_h.get(), tag, argv.data());
}

void MpvEngine::open(const QString &url, qint64 startMs, bool nightMode, const QString &audioLang, const QString &subLang,
                     const QList<SideTrack> &audio, const QList<SideTrack> &subs) {
    if (!handle()) { emit stateChanged(); emit failed(core::Strings::get(S("app.mpv_ne_skachan"))); return; }
    m_loaded = false;
    m_playingSent = false;
    m_time = m_length = 0;
    m_cacheWait = false;
    m_cachePercent = 0;
    ++m_openGen;
    m_openUrl = url;
    m_sideAudio = audio;
    m_sideSubs = subs;
    m_pendingAudio = int(audio.size());
    m_pendingSubs = int(subs.size());
    // Буфер mpv — из настроек («Буфер», как в Kotlin-версии): без этих двух mpv копит, сколько влезет в demuxer-max-bytes
    const QString secs = num(std::max(1.0, PlaybackSettings::cacheMs() / 1000.0));
    set("cache-secs", secs);
    set("demuxer-readahead-secs", secs);
    set("pause", S("no"));
    set("start", startMs > 0 ? num(startMs / 1000.0) : S("0"));
    set("alang", audioLang);
    set("slang", subLang);
    set("af", nightMode ? S(kNightFilter) : QString());
    emit stateChanged();
    emit timeChanged();
    emit lengthChanged();
    command({S("loadfile"), url, S("replace")});
}

void MpvEngine::addSideTracks() {
    // Сначала субтитры (уже на диске, мгновенно), потом озвучки. Асинхронно: mpv открывает каждый файл сам,
    // а события и окно не ждут.
    const quint64 gen = m_openGen << 8;
    for (const SideTrack &s : std::as_const(m_sideSubs)) {
        QStringList args{S("sub-add"), s.target, S("auto"), s.title};
        if (!s.lang.isEmpty()) args << s.lang;
        commandAsync(gen | TAG_SUB, args);
    }
    for (const SideTrack &s : std::as_const(m_sideAudio)) {
        QStringList args{S("audio-add"), s.target, S("auto")};
        if (!s.title.isEmpty()) { args << s.title; if (!s.lang.isEmpty()) args << s.lang; }
        commandAsync(gen | TAG_AUDIO, args);
    }
    m_sideAudio.clear();
    m_sideSubs.clear();
}

void MpvEngine::addSubtitles(const QList<SideTrack> &subs) {
    if (subs.isEmpty()) { m_pendingSubs = 0; emit tracksChanged(); return; }
    m_pendingSubs = int(subs.size());
    if (!m_loaded) { m_sideSubs = subs; emit tracksChanged(); return; }
    const quint64 gen = m_openGen << 8;
    for (const SideTrack &s : subs) {
        QStringList args{S("sub-add"), s.target, S("auto"), s.title};
        if (!s.lang.isEmpty()) args << s.lang;
        commandAsync(gen | TAG_SUB, args);
    }
    emit tracksChanged();
}

void MpvEngine::setPaused(bool paused) {
    m_pause = paused;
    set("pause", paused ? S("yes") : S("no"));
    emit stateChanged();
}
void MpvEngine::stop() {
    if (m_h) command({S("stop")});
    // сеанс кончился: прежние время, дорожки, главы и размер кадра больше не действуют
    ++m_openGen;
    m_loaded = m_playingSent = m_cacheWait = false;
    m_time = m_length = 0;
    m_cachePercent = 0;
    m_videoW = m_videoH = 0;
    m_audio.clear(); m_subs.clear(); m_chapters.clear();
    m_pendingAudio = m_pendingSubs = 0;
    m_sideAudio.clear(); m_sideSubs.clear();
    emit stateChanged();
    emit timeChanged();
    emit lengthChanged();
    emit tracksChanged();
    emit videoSizeChanged();
}
void MpvEngine::seekTo(qint64 ms) {
    m_cachePercent = 0;
    command({S("seek"), num(ms / 1000.0), S("absolute")});
}
void MpvEngine::seekToFraction(double f) {
    m_cachePercent = 0;
    command({S("seek"), num(f * 100), S("absolute-percent")});
}
void MpvEngine::setRate(double rate) { set("speed", QString::number(rate)); }
void MpvEngine::setVolume(int percent) { set("volume", QString::number(std::clamp(percent, 0, 150))); }
void MpvEngine::setMute(bool mute) { set("mute", mute ? S("yes") : S("no")); }
void MpvEngine::setAudioDelayMs(qint64 ms) { set("audio-delay", num(ms / 1000.0)); }
void MpvEngine::setSubDelayMs(qint64 ms) { set("sub-delay", num(ms / 1000.0)); }
void MpvEngine::setSubScale(int percent) { set("sub-scale", QString::number(percent / 100.0)); }
void MpvEngine::setNightMode(bool on) { set("af", on ? S(kNightFilter) : QString()); }
void MpvEngine::setAudioTrack(int id) { set("aid", id < 0 ? S("no") : QString::number(id)); }
void MpvEngine::setSubTrack(int id) { set("sid", id < 0 ? S("no") : QString::number(id)); }
void MpvEngine::removeAudioTrack(int id) { if (id >= 0) command({S("audio-remove"), QString::number(id)}); }

void MpvEngine::restartVideoOutput() {
    const QString vid = property(S("vid"));
    set("vid", S("no"));
    set("vid", vid.isEmpty() ? S("auto") : vid);
    command({S("seek"), S("0"), S("relative"), S("exact")});
}

bool MpvEngine::screenshot(const QString &file) {
    if (!m_h || !m_loaded) return false;
    QFile::remove(file);
    command({S("screenshot-to-file"), file, S("video")});
    return QFileInfo(file).size() > 0;
}

double MpvEngine::fps() { return property(S("container-fps")).toDouble(); }
double MpvEngine::audioPts() { return property(S("audio-pts")).toDouble(); }

QString MpvEngine::statsLine() {
    const auto g = [this](const char *n) { const QString v = property(S(n)); return v.isEmpty() ? S("?") : v; };
    QString fmt = property(S("video-params/hw-pixelformat"));
    if (fmt.isEmpty()) fmt = g("video-params/pixelformat");
    return S("[mpv] %1/%2s video=%3 hw=%4 %5x%6 fmt=%7 %8/%9 audio=%10 drop=%11 vo-drop=%12 cache=%13s")
        .arg(QString::number(m_time, 'f', 1)).arg(int(m_length)).arg(g("video-codec"), g("hwdec-current"), g("video-params/w"),
                                                                       g("video-params/h"), fmt, g("video-params/gamma"),
                                                                       g("video-params/primaries"))
        .arg(g("audio-codec-name"), g("frame-drop-count"), g("vo-delayed-frame-count"), g("demuxer-cache-duration"));
}

} // namespace suslik::app
