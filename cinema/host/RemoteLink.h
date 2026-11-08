#pragma once
#include <QJsonArray>
#include <QJsonObject>
#include <QObject>
#include <QString>
#include <QTimer>

/// Пульт на телефоне VAO (этап 3, docs/CYBERPUNK.md): обмен с модом SuslikCinema файлами в его папке, как state.json.
///   remote.json  (мод → хост)  {"sid", "cmds": [{"n", "at", "op", …}]} — последние команды; хост выполняет новые по n;
///   host.json    (хост → мод)  {"seq", "time", …состояние…, "end"} — раз в секунду и при изменении; мод берёт файл,
///                               только если seq == end (файл пишется на месте, как state.json);
///   library.json (хост → мод)  {"rev", "items": […], "end"} — медиатека, при изменении (rev — в host.json);
///   host.txt     (хост → мод)  путь к suslik-cinema.exe: по нему helper мода запускает хост с телефона.
namespace suslik::cinema {

class RemoteLink : public QObject {
    Q_OBJECT
public:
    explicit RemoteLink(QObject *parent = nullptr);

    /// Папка мода (от GameLink). Пусто — пульта нет.
    void setModDir(const QString &dir);
    QString modDir() const { return m_dir; }

    /// Состояние для телефона: пишется, если изменилось (и раз в секунду — как признак жизни хоста).
    void setStatus(const QJsonObject &status);
    /// Медиатека для телефона: пишется сразу, rev растёт.
    void setLibrary(const QJsonArray &items);
    int libraryRev() const { return m_libRev; }

    /// Команда в разборе (открыто для тестов): новые по n, не старше 30 с; первый файл после запуска — только свежие.
    QList<QJsonObject> takeNew(const QJsonObject &file, qint64 nowSec);

signals:
    void command(const QJsonObject &cmd);

private:
    void poll();
    void writeStatus();
    void writeInPlace(const QString &name, const QByteArray &bodyWithoutEnd, qint64 seq);

    QString m_dir;
    QTimer m_poll, m_beat;
    QJsonObject m_status;
    bool m_dirty = true;
    qint64 m_seq = 0;
    int m_libRev = 0;
    QJsonArray m_library;
    QString m_sid;
    qint64 m_lastN = -1;
};

} // namespace suslik::cinema
