#pragma once
#include <QJsonObject>
#include <QList>
#include <QMutex>
#include <QString>
#include <list>
#include <optional>
#include <unordered_map>

/// Синхронизация просмотра по аккаунту Discord: прогресс файлов и выбранные озвучка/субтитры (сервер services/sync.js,
/// /api/sync). Без сети: приложение шлёт request() и отдаёт ответ в onResponse(). Перенос android/core/.../sync/WatchSync.kt.
namespace suslik::core {

/// Прогресс файла: ключ "magnetId|path" (watchKey).
struct WatchEntry {
    QString key;
    qint64 pos = 0;
    qint64 len = 0;
    qint64 at = 0;
    bool operator==(const WatchEntry &) const = default;
};

/// Выбор дорожек фильма ("m<magnetId>") или ANY. Пусто — поле не менялось. sidecar — файл озвучки рядом (только Android).
struct TrackEntry {
    QString key;
    std::optional<QString> audio;
    std::optional<QString> spu;
    std::optional<QString> sidecar;
    std::optional<QString> quality;
    std::optional<QString> cid;
    qint64 at = 0;
    bool operator==(const TrackEntry &) const = default;
};

/// Хранилище приложения: откуда брать своё и куда класть пришедшее с других устройств.
class SyncStore {
public:
    virtual ~SyncStore() = default;
    virtual std::optional<WatchEntry> watch(const QString &key) = 0;
    virtual void putWatch(const WatchEntry &e) = 0;
    /// Когда на этом устройстве выбрали дорожки key (0 — не выбирали или неизвестно).
    virtual qint64 trackAt(const QString &key) = 0;
    virtual void putTrack(const TrackEntry &e) = 0;
    virtual QList<WatchEntry> allWatch() = 0;
    virtual QList<TrackEntry> allTracks() = 0;
    /// "u_…|мс сервера|мс устройства": для кого, до какого места синхронизировано и до какого времени своё выгружено.
    virtual std::optional<QString> cursor() = 0;
    virtual void setCursor(const std::optional<QString> &c) = 0;
};

class WatchSync {
public:
    static constexpr const char *PATH = "/api/sync";
    static constexpr const char *ANY = "*";
    static constexpr int BATCH = 500; ///< столько записей одного вида сервер принимает за раз

    static QString filmKey(qint64 magnetId) { return QStringLiteral("m%1").arg(magnetId); }
    /// "m42" из "42|Season 1/E01.mkv"; пусто, если ключ не из библиотеки.
    static std::optional<QString> filmKeyOf(const std::optional<QString> &watchKey);
    /// Только вход через Discord: при общем пароле у всех один пользователь.
    static bool eligible(const std::optional<QString> &userId);

    /// Что ушло в запросе: после ответа убирается из очереди, если за это время не заменилось новым.
    struct Sent {
        QString user;
        QJsonObject body;
        QList<WatchEntry> watch;
        QList<TrackEntry> tracks;
        qint64 startedAt = 0;
        bool more = false; ///< остались записи сверх BATCH: после ответа сразу следующий запрос
    };

    explicit WatchSync(SyncStore &store) : m_store(store) {}

    void noteWatch(const WatchEntry &e);
    /// Поле, которое не меняли, — пусто (второй выбор дополняет первый, а не стирает).
    void noteTrack(const TrackEntry &e);
    bool hasPending() const;
    Sent request(const QString &user);
    /// Пришедшее новее своего ложится в хранилище. Возвращает, сколько записей поменялось.
    int onResponse(const Sent &sent, const QJsonObject &res);

private:
    /// Упорядоченная по вставке таблица (как LinkedHashMap).
    template <class T> class Ordered {
    public:
        void put(const QString &k, const T &v) { remove(k); m_list.push_back(v); m_index[k] = std::prev(m_list.end()); }
        void putIfAbsent(const QString &k, const T &v) { if (!m_index.count(k)) put(k, v); }
        void remove(const QString &k) {
            auto it = m_index.find(k);
            if (it == m_index.end()) return;
            m_list.erase(it->second);
            m_index.erase(it);
        }
        const T *get(const QString &k) const { auto it = m_index.find(k); return it == m_index.end() ? nullptr : &*it->second; }
        void clear() { m_list.clear(); m_index.clear(); }
        qsizetype size() const { return qsizetype(m_list.size()); }
        bool empty() const { return m_list.empty(); }
        QList<T> take(int n) const {
            QList<T> out;
            for (auto it = m_list.begin(); it != m_list.end() && out.size() < n; ++it) out << *it;
            return out;
        }
    private:
        struct Hash { size_t operator()(const QString &s) const { return qHash(s); } };
        std::list<T> m_list;
        std::unordered_map<QString, typename std::list<T>::iterator, Hash> m_index;
    };

    struct Cursor { qint64 since; qint64 mark; };
    Cursor cursor(const QString &user);

    SyncStore &m_store;
    mutable QMutex m_lock;
    Ordered<WatchEntry> m_watch;
    Ordered<TrackEntry> m_tracks;
    std::optional<QString> m_loaded; ///< для кого очередь собрана в этом процессе
};

} // namespace suslik::core
