#pragma once
#include <QJsonObject>
#include <QList>
#include <QString>
#include <functional>
#include <optional>
#include <limits>

/// Совместный просмотр: данные комнаты, протокол сервера (`/api/rooms`), часы и синхронизация плеера.
/// Перенос android/core/.../core/party (PartyModels.kt, PartyProtocol.kt, PartySyncEngine.kt) — без сети: сеть у приложения.
namespace suslik::core {

/// Часы комнаты: смещение локального времени относительно сервера по самому короткому кругу запроса среди точек
/// за последние WINDOW_MS (старые забываются: уплывшие за долгий просмотр часы догоняются новыми точками).
class PartyClock {
public:
    void addSample(qint64 localSend, qint64 localRecv, qint64 serverTime);
    qint64 serverNow(qint64 localNow) const { return localNow + m_offset; }
    qint64 offsetMs() const { return m_offset; }
    qint64 bestRttMs() const { return m_bestRtt; }
    void reset();

    static constexpr qint64 WINDOW_MS = 10 * 60 * 1000;
    /// Раз во столько приложение, пока в комнате, берёт ещё одну точку (GET комнаты).
    static constexpr qint64 RESAMPLE_MS = 60 * 1000;

private:
    struct Sample { qint64 at, rtt, offset; };
    QList<Sample> m_samples;
    qint64 m_offset = 0;
    qint64 m_bestRtt = std::numeric_limits<qint64>::max();
};

/// Подстройка рассинхрона: < 0,3 с — ничего, до 2 с — скорость 0,95 / 1,05, больше — перемотка.
struct DriftAction {
    enum class Kind { None, SlowDown, SpeedUp, HardSeek };
    Kind kind = Kind::None;
    float rate = 1.f;
    float targetSec = 0.f;
    bool operator==(const DriftAction &o) const = default;
};
struct DriftController {
    static constexpr float TOLERANCE_SEC = 0.3f;
    static constexpr float HARD_SEEK_THRESHOLD_SEC = 2.0f;
    static DriftAction evaluate(float localPosSec, float targetPosSec);
};

/// Участник. key — отпечаток его client_id (сам client_id — пропуск устройства, другим не показывается).
struct PartyMember {
    QString key, userId, name;
    bool buffering = false;
    std::optional<QString> avatar;
    std::optional<QString> variantKey;
    /// Длина раздачи, которую участник смотрит (мс): по ней гости на других раздачах сдвигают время.
    std::optional<qint64> durationMs;
    bool operator==(const PartyMember &o) const = default;
};

/// Раздачи одного фильма разной длины: лишнее обычно в начале (заставка релиз-группы), поэтому место на своей
/// раздаче = место ведущего + сдвиг, сдвиг = своя длина − длина ведущего. Меньше 1,5 с — хвост кадров, больше 2 мин —
/// другой монтаж: тогда 0. Те же числа у сервера (rooms.js) и Kotlin (PartyProtocol.kt).
inline constexpr qint64 TIMELINE_SHIFT_MIN_MS = 1500;
inline constexpr qint64 TIMELINE_SHIFT_MAX_MS = 120 * 1000;
qint64 timelineShiftMs(qint64 myDurationMs, qint64 hostDurationMs);

/// Подстройка времени между раздачами, сохранённая гостем (сервер: services/variant-offsets.js): место на to = место
/// на from + offsetMs.
struct VariantOffset {
    QString from, to;
    qint64 offsetMs = 0;
    bool operator==(const VariantOffset &o) const = default;
};
/// Сдвиг своей раздачи myKey относительно раздачи ведущего hostKey: сохранённая подстройка пары, иначе разница длин.
qint64 roomShiftMs(const QList<VariantOffset> &saved, const QString &hostKey, qint64 hostDurationMs,
                   const QString &myKey, qint64 myDurationMs);

struct PartyVariant {
    QString key;
    QString label;
    QString quality = QStringLiteral("1080p");
    QString audio = QStringLiteral("Stereo");
    double bitrate = 0.0;
    qint64 offsetMs = 0;
    int score = 0;
    QStringList hardwareFlags;
    bool operator==(const PartyVariant &o) const = default;
};

struct LibraryItem;
struct MFile;
PartyVariant parsePartyVariant(const QJsonObject &obj);
PartyVariant buildPartyVariant(const LibraryItem &item, const MFile &file, const std::optional<PartyVariant> &serverProfile = std::nullopt, int width = 0, int height = 0);

/// Фильм комнаты: ключ просмотра `magnetId|путь` (тот же, что у прогресса в медиатеке), canonical_id и варианты.
struct PartyFileRef {
    QString key, title;
    std::optional<QString> canonicalId;
    std::optional<QString> edition;
    QList<PartyVariant> variants;

    qint64 adId() const;
    bool matches(const QString &fileKey, const QString &otherCanonicalId = QString()) const;
    /// Индекс фильма комнаты в списке ключей просмотра плеера или -1: свой ключ, затем варианты, затем та же серия (S/E)
    /// той же работы. Те же правила, что у Kotlin `resolveIndex` и JS; sameWorkHint — вызывающий знает, что работа та же.
    int resolveIndex(const QStringList &keys, const QString &myCanonicalId, bool sameWorkHint = false) const;
    bool operator==(const PartyFileRef &o) const = default;
};

/// Где комната: pos (с) в момент anchorServerMs по часам сервера, скорость rate.
/// waiting: кто-то грузится — время комнаты стоит, пока все не готовы.
struct PartyState {
    bool playing = false;
    double pos = 0;
    double rate = 1;
    qint64 anchorServerMs = 0;
    qint64 seq = 0;
    bool waiting = false;
    /// Где комната сейчас, мс, при часах сервера serverNowMs.
    qint64 targetMs(qint64 serverNowMs) const;
    bool operator==(const PartyState &o) const = default;
};

/// Комната из JSON сервера. Поля, которых нет, — пустые: merge их не трогает.
struct RoomUpdate {
    std::optional<QString> id, hostId, hostKey;
    QString hostName;
    bool hostOnly = false;
    std::optional<PartyFileRef> file;
    std::optional<PartyState> state;
    std::optional<QList<PartyMember>> members;
    std::optional<QList<VariantOffset>> variantOffsets;
    /// state.server_time: ещё одна точка для часов. 0 — нет.
    qint64 serverTime = 0;
};

/// Событие потока комнаты (`/api/rooms/<id>/events`).
struct PartyEvent {
    enum class Kind { Init, StateChanged, MemberJoined, MemberLeft, RefreshLibrary };
    Kind kind = Kind::Init;
    std::optional<QString> myUserId;   ///< Init
    std::optional<RoomUpdate> room;    ///< Init (может не быть), StateChanged
    PartyMember member;                ///< MemberJoined
    QString key;                       ///< MemberLeft
    std::optional<PartyFileRef> file;  ///< RefreshLibrary
};

/// Discord Activity просит ПК комнаты поискать на RuTracker («search») или добавить найденную раздачу без magnet
/// («add», только ПК с отпечатком target). Событие потока rutracker_request (website/src/services/pc-search.js).
struct RemoteTrackerRequest {
    QString id, kind, query, topicId, title, target, by;
};

/// Приглушение фильма, когда говорят в Discord (website: services/voice-duck.js): своя настройка аккаунта.
/// level — насколько тише, %: 10 — громкость × 0,9, 100 — тишина.
struct VoiceDuck {
    bool enabled = true;
    int level = 10;
    bool operator==(const VoiceDuck &o) const = default;
};

/// Что о приглушении сказал поток комнаты: кто говорит (voice_changed, room.voice в init и state_changed)
/// и/или своя настройка (my_voice_duck в init, settings_changed с for_user).
struct VoiceUpdate {
    std::optional<QStringList> speakers;
    std::optional<VoiceDuck> duck;
    /// settings_changed: чья настройка (чужую пропускаем). Пусто — своя (init).
    std::optional<QString> forUser;
};

/// Приглушение в плеере: настройка и кто говорит. Сам говорящий свой фильм не глушит.
struct VoiceState {
    VoiceDuck duck;
    QStringList speakers;
    /// Применить событие; false — ничего не поменялось (или настройка чужая).
    bool apply(const VoiceUpdate &u, const std::optional<QString> &myUserId);
    /// Множитель громкости: 1 — как есть, 0,9 — на 10 % тише, 0 — тишина.
    double factor(const std::optional<QString> &myUserId) const;
};

/// Снимок комнаты, как его видит приложение. Меняется только через merge и reduce.
struct PartyRoom {
    static inline const QString SHARED_USER = QStringLiteral("shared");
    /// pendingFileKey закрытого фильма (у настоящего фильма ключ не пустой).
    static inline const QString NO_FILE = QString();

    std::optional<QString> id, hostId;
    /// Устройство создателя: при общем пароле у всех id "shared", ведущего отличает устройство.
    std::optional<QString> hostKey;
    QString hostName;
    bool hostOnly = false;
    std::optional<PartyFileRef> file;
    PartyState state;
    QList<PartyMember> members;
    QList<VariantOffset> variantOffsets;
    /// Ведущий поставил этот фильм, сервер ещё не подтвердил: ответы на прежние действия со старым фильмом его не возвращают.
    std::optional<QString> pendingFileKey;

    bool isHost(const std::optional<QString> &myUserId, const QString &myKey) const;
    bool isHost(const PartyMember &m) const { return isCreator(m); }
    bool isCreator(const PartyMember &m) const;
    bool othersBuffering(const QString &myKey) const;
    PartyRoom putFile(const PartyFileRef &f) const;
    PartyRoom fileRejected() const;
    PartyRoom clearFile() const;
    PartyRoom merge(const RoomUpdate &u) const;
    PartyRoom reduce(const PartyEvent &e) const;
};

class PartyProtocol {
public:
    static QString roomsPath() { return QStringLiteral("/api/rooms"); }
    static QString roomPath(const QString &id) { return roomsPath() + QLatin1Char('/') + id; }
    static QString joinPath(const QString &id) { return roomPath(id) + QStringLiteral("/join"); }
    static QString leavePath(const QString &id) { return roomPath(id) + QStringLiteral("/leave"); }
    static QString actionPath(const QString &id) { return roomPath(id) + QStringLiteral("/action"); }
    /// Поток событий. Без Accept-Encoding: сжатый ответ держит события, пока не заполнится буфер.
    static QString eventsPath(const QString &id, const QString &clientId) { return roomPath(id) + QStringLiteral("/events?client_id=") + clientId; }

    /// Новый client_id устройства: prefix — платформа ("pc", "an", "tv").
    static QString newClientId(const QString &prefix);
    /// Как сервер показывает устройство другим: sha-256 от client_id, 16 hex.
    static QString clientKey(const QString &clientId);
    /// Код комнаты как его ввёл человек: только буквы и цифры, в нижнем регистре.
    static QString normalizeCode(const QString &code);
    /// Код комнаты из ссылки suslik://room/<код> (кнопка «Открыть в приложении» в Discord Activity); не та ссылка — пусто.
    static QString roomFromLink(const QString &link);

    static QJsonObject createBody(const QString &clientId);
    static QJsonObject joinBody(const QString &clientId);
    static QJsonObject leaveBody(const QString &clientId);
    static QJsonObject play(qint64 posMs);
    static QJsonObject pause(qint64 posMs);
    static QJsonObject seek(qint64 posMs);
    static QJsonObject buffering(bool loading);
    /// Свой вариант качества; durationMs > 0 — длина раздачи, variant — описание (своей раздачи нет у ведущего).
    static QJsonObject variantChange(const QString &variantKey, qint64 durationMs = 0, const PartyVariant *variant = nullptr);
    static QJsonObject variantJson(const PartyVariant &v);
    /// Гость подстроил время: место на variantKey = место на hostVariantKey + offsetMs (запоминается для пары).
    static QJsonObject variantOffset(const QString &hostVariantKey, const QString &variantKey, qint64 offsetMs);
    static QJsonObject changeFile(const PartyFileRef &f);
    /// Ведущий закрыл фильм: у гостей плеер тоже закрывается.
    static QJsonObject closeFile();
    static QJsonObject withClient(QJsonObject body, const QString &clientId);
    static QJsonObject fileJson(const PartyFileRef &f);

    static std::optional<PartyFileRef> parseFileRef(const QJsonObject &f);
    static std::optional<RoomUpdate> roomOf(const QJsonObject &res);
    static std::optional<QString> hostIdOf(const QJsonObject &res);
    static std::optional<QString> memberIdOf(const QJsonObject &res);
    static RoomUpdate parseRoom(const QJsonObject &room);
    static PartyMember parseMember(const QJsonObject &m);
    /// Строка потока SSE: не `data:` или незнакомое событие — пусто.
    static std::optional<PartyEvent> parseEventLine(const QString &line);
    static std::optional<PartyEvent> parseEvent(const QString &text);
    /// Строка потока с rutracker_request — запрос; иначе (и без id, с незнакомым kind) — пусто.
    static std::optional<RemoteTrackerRequest> remoteRequestOf(const QString &line);
    /// Путь ответа ПК на такой запрос.
    static QString remoteReplyPath(const QString &roomId);
    /// Строка потока с тем, что касается приглушения (voice_changed, settings_changed, init, state_changed);
    /// иначе — пусто.
    static std::optional<VoiceUpdate> voiceOf(const QString &line);
    static VoiceDuck parseVoiceDuck(const QJsonObject &o);
};

/// Плеер, которым управляет комната.
class SyncPlayer {
public:
    virtual ~SyncPlayer() = default;
    /// Где картинка (сразу после перемотки — куда она идёт).
    virtual qint64 timeMs() const = 0;
    /// Пользователь тянет полосу времени: ждём, пока отпустит.
    virtual bool busy() const = 0;
    /// Место самого движка как есть.
    virtual qint64 engineMs() const = 0;
    /// Метка последней перемотки (пользователя или нашей): меняется при каждой.
    virtual qint64 seekStamp() const = 0;
    virtual bool paused() const = 0;
    /// Кадры идут: не открывается и не буферизуется.
    virtual bool running() const = 0;
    /// Скорость, выбранная в плеере. В фильме комнаты не действует: там идёт скорость комнаты.
    virtual float userRate() const = 0;
    virtual void applyPaused(bool paused) = 0;
    virtual void seekTo(qint64 ms) = 0;
    /// Скорость движка (скорость комнаты и подстройка дрейфа); вне комнаты возвращаем userRate().
    virtual void setRate(float rate) = 0;
};

/// Комната для PartySyncEngine: состояние и команды серверу.
class PartyLink {
public:
    virtual ~PartyLink() = default;
    virtual PartyState state() const = 0;
    /// Пауза, пуск и перемотка всей комнаты — только ведущий.
    virtual bool canControl() const = 0;
    virtual bool canSeek() const = 0;
    virtual qint64 targetMs() const = 0;
    virtual bool othersBuffering() const = 0;
    /// Я только что поставил фильм key в комнату: true один раз, комната стартует с моего места.
    virtual bool takeStartedByMe(const QString &key) = 0;
    virtual void play(qint64 posMs) = 0;
    virtual void pause(qint64 posMs) = 0;
    virtual void seek(qint64 posMs) = 0;
    virtual void reportBuffering(bool loading) = 0;
};

/// Подсказка в плеере: что сделал ведущий (гостю) и кого ждёт комната.
struct PartyNotice {
    enum class Kind { Paused, Resumed, Seeked, Waiting };
    Kind kind = Kind::Paused;
    qint64 posMs = 0;
    bool operator==(const PartyNotice &o) const = default;
};

/// Держит плеер в фильме комнаты: следует за пуском, паузой и перемоткой комнаты и отправляет свои. Малый дрейф
/// выравнивает скоростью (0,95–1,05×), большой — перемоткой. Без потоков: приложение зовёт tick каждые TICK_MS,
/// пока плеер показывает фильм комнаты, и idle — пока нет. Грузящийся плеер сообщает об этом, и комната его ждёт.
class PartySyncEngine {
public:
    static constexpr qint64 TICK_MS = 400;
    static constexpr qint64 OWN_MS = 2500;
    static constexpr qint64 FROZEN_MS = 1000;
    static constexpr qint64 WAIT_NOTICE_MS = 2000;
    static constexpr qint64 SEEK_NOTICE_MS = 3000;

    explicit PartySyncEngine(PartyLink &link, std::function<void(const QString &)> log = {},
                             std::function<void(const PartyNotice &)> notice = {});

    void idle(SyncPlayer &player);
    void tick(SyncPlayer &player, const QString &key, qint64 now);
    qint64 lastTimeMs() const { return m_lastTimeMs; }

private:
    std::optional<PartyNotice> noticeOf(const PartyState &before, const PartyState &s) const;
    void restoreRate(SyncPlayer &player) { applyRate(player, 1.f); }
    void applyRate(SyncPlayer &player, float d);
    qint64 seekRoom(SyncPlayer &player, qint64 target, qint64 now);
    void log(const QString &s) const { if (m_log) m_log(s); }

    PartyLink &m_link;
    std::function<void(const QString &)> m_log;
    std::function<void(const PartyNotice &)> m_notice;
    qint64 m_lastSeq = -1;
    qint64 m_ownUntil = 0;
    qint64 m_prevT = 0;
    bool m_prevP = true;
    qint64 m_prevAt = 0;
    float m_drift = 1.f;
    std::optional<float> m_engineRate; ///< что стоит у движка по нашей команде; нет — своя скорость плеера
    qint64 m_pendingAt = 0, m_pendingTo = 0, m_pendingT = -1;
    qint64 m_stalledSince = 0;
    qint64 m_lastEngineMs = std::numeric_limits<qint64>::min();
    qint64 m_engineMovedAt = 0;
    bool m_localHold = false;
    std::optional<qint64> m_seenSeek;
    std::optional<PartyState> m_applied;
    qint64 m_waitSince = 0;
    bool m_waitNoticed = false;
    qint64 m_lastTimeMs = 0;
};

} // namespace suslik::core
