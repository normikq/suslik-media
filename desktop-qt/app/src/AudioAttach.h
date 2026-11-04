#pragma once
#include "suslik/core/CustomAudio.h"

#include "Library.h"

#include <QObject>
#include <QUrl>
#include <QVariantList>
#include <functional>

/// «Добавить» → «Озвучка» (AudioAttachView.kt, CustomAudio.kt): раздачи только с озвучками (.mka), проверка на сервере,
/// таблица «файл → фильм/серия» с задержкой, предпросмотр в плеере, «Привязать к библиотеке». Сервер: docs/CUSTOM-AUDIO-API.md.
/// Раздачи загружаются в AllDebrid сразу (для предпросмотра); не привязанные удаляются, когда ушли с вкладки «Добавить»
/// (или при следующем запуске, если приложение закрыли раньше).
namespace suslik::app {


/// Внешние озвучки с сервера: сеть, кэш дорожек, подобранные задержки, раздачи на удаление.
class AudioDesk {
public:
    /// Ошибка проверки сервера: code (FORBIDDEN_FILES, AMBIGUOUS, …) и весь ответ.
    struct Problem {
        QString code, message;
        QJsonObject body;
    };
    using Fail = std::function<void(const Problem &)>;

    static void tracks(Library &lib, qint64 magnetId, bool fresh, std::function<void(QList<core::CustomTrack>)> done);
    static void check(Library &lib, qint64 audioId, std::optional<qint64> movieId, std::function<void(core::CustomCheck)> ok, Fail fail);
    static void checkTorrent(Library &lib, const QByteArray &bytes, std::optional<qint64> movieId, std::function<void(core::CustomCheck)> ok, Fail fail);
    static void attach(Library &lib, qint64 audioId, std::optional<qint64> movieId, std::function<void(QList<QJsonObject>)> ok, Fail fail);
    static void saveOffset(Library &lib, const QString &trackId, qint64 ms, std::function<void(bool)> done);

    static void rememberOffset(qint64 audioId, const QString &audioPath, qint64 ms);
    static std::optional<qint64> knownOffset(qint64 audioId, const QString &audioPath);
    static void markPending(qint64 id);
    static void donePending(qint64 id);
    /// Удалить из AllDebrid не привязанные раздачи (в фоне; ошибка — останутся до следующего раза).
    static void dropUnattached(Library &lib, const QList<qint64> &ids);
    /// Один раз за запуск: то, что осталось с прошлого раза.
    static void cleanupPending(Library &lib);
    static QString describe(const Problem &p);
};

class AudioAttachModel : public QObject {
    Q_OBJECT
    Q_PROPERTY(Library *library READ library WRITE setLibrary)
    /// [{key, name, torrent, busy, note, noteOk, valid (-1/0/1), done, candidates: [{id, name}], movieTitle, moviePoster,
    ///   rows: [{audio, videoTitle, video, voice, offset}], canCheck, ready}]
    Q_PROPERTY(QVariantList sources READ sources NOTIFY changed)
    Q_PROPERTY(QString note READ note NOTIFY changed)
    /// Сколько раздач готовы к привязке (кнопка «Привязать все»).
    Q_PROPERTY(int readyCount READ readyCount NOTIFY readyChanged)

public:
    explicit AudioAttachModel(QObject *parent = nullptr) : QObject(parent) {}
    ~AudioAttachModel() override;
    Library *library() const { return m_lib; }
    void setLibrary(Library *l) { m_lib = l; }
    QVariantList sources() const;
    QString note() const { return m_note; }
    int readyCount() const;

    Q_INVOKABLE void addInput(const QString &text);
    Q_INVOKABLE void addTorrents(const QList<QUrl> &files);
    Q_INVOKABLE void check(qint64 key);
    Q_INVOKABLE void chooseMovie(qint64 key, qint64 movieId);
    Q_INVOKABLE void remove(qint64 key);
    Q_INVOKABLE void attach(qint64 key);
    Q_INVOKABLE void attachAll();
    /// Задержка строки, как её ввели (проверка — при привязке); готовность пересчитывается без пересборки таблицы.
    Q_INVOKABLE void setOffset(qint64 key, int row, const QString &text);
    Q_INVOKABLE bool offsetValid(const QString &text) const { return parseOffset(text).has_value(); }
    Q_INVOKABLE bool isReady(qint64 key) const;
    Q_INVOKABLE void applyAll(qint64 key, const QString &text);
    /// ▶ у строки: фильм с этой озвучкой в плеере (появится на шаге 3).
    Q_INVOKABLE void preview(qint64 key, int row);
    /// Ушли с вкладки «Добавить»: не привязанные раздачи удаляются.
    Q_INVOKABLE void leave();
    Q_INVOKABLE void setNote(const QString &n) { m_note = n; emit changed(); }

signals:
    void changed();
    void readyChanged();
    /// Предпросмотр: ссылка видео, ссылка озвучки, задержка, название.
    void previewRequested(const QString &video, const QString &audio, qint64 offsetMs, const QString &title);

private:
    struct Row {
        core::CustomPair pair;
        qint64 initial = 0;
        QString offset;
    };
    struct Source {
        qint64 key = 0;
        QString magnet, torrent, name;
        bool busy = false;
        QString note;
        bool noteOk = true;
        int valid = -1;
        std::optional<qint64> audioId, movieId;
        QList<std::pair<qint64, QString>> candidates;
        QList<qint64> targetIds;
        QStringList targetNames;
        bool done = false, existed = false;
        QList<Row> rows;
        bool ready() const;
    };
    static std::optional<qint64> parseOffset(const QString &text);
    Source *find(qint64 key);
    void add(const QString &magnet, const QString &torrent);
    void say(qint64 key, const QString &text, bool ok);
    void failed(qint64 key, const AudioDesk::Problem &p);
    void afterUpload(qint64 key, qint64 id, bool readyNow);
    void waitReady(qint64 key, qint64 id, int round);
    void runCheck(qint64 key);

    Library *m_lib = nullptr;
    QList<Source> m_sources;
    QString m_note;
    qint64 m_next = 1;
};

} // namespace suslik::app
