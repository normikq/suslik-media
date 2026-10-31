#pragma once
#include "Library.h"

#include <QObject>
#include <QUrl>
#include <QVariantList>

/// «Добавить» → «Фильм» (AddView.kt): magnet-ссылки и .torrent; каждая раздача — строка с живым состоянием AllDebrid
/// до готовности (тогда медиатека обновляется) или ошибки. Раздача без единого видео отклоняется.
namespace suslik::app {


class AddModel : public QObject {
    Q_OBJECT
    Q_PROPERTY(Library *library READ library WRITE setLibrary)
    Q_PROPERTY(QVariantList added READ added NOTIFY changed)
    Q_PROPERTY(QString status READ status NOTIFY changed)
    Q_PROPERTY(bool statusOk READ statusOk NOTIFY changed)

public:
    explicit AddModel(QObject *parent = nullptr) : QObject(parent) {}
    Library *library() const { return m_lib; }
    void setLibrary(Library *l) { m_lib = l; }
    QVariantList added() const;
    QString status() const { return m_status; }
    bool statusOk() const { return m_statusOk; }

    /// Ссылки из поля (можно несколько через пробел или перевод строки).
    Q_INVOKABLE void submit(const QString &text);
    /// .torrent из окна выбора или перетащенные: загружаются сразу.
    Q_INVOKABLE void addTorrents(const QList<QUrl> &files);
    Q_INVOKABLE void setStatus(const QString &s, bool ok) { m_status = s; m_statusOk = ok; emit changed(); }

signals:
    void changed();

private:
    struct Added {
        qint64 key = 0;
        QString name, note;
        bool ok = true, done = false;
        double progress = -1;
    };
    Added *find(qint64 key);
    qint64 push(const QString &name);
    void update(qint64 key, const std::function<void(Added &)> &f);
    void follow(qint64 key, qint64 id, bool readyNow, int round = 0, qint64 started = 0);
    void ready(qint64 key, qint64 id, const QString &note);

    Library *m_lib = nullptr;
    QList<Added> m_added;
    QString m_status;
    bool m_statusOk = true;
    qint64 m_next = 1;
};

/// Раздача без видео (игра, музыка, софт): текст отказа, иначе пусто. Список файлов AllDebrid отдаёт только готовой
/// раздаче — проверка в момент готовности; такую раздачу просим удалить, сервер может не дать (готовую удаляет админ).
void rejectIfNoVideo(Library &lib, qint64 id, std::function<void(std::optional<QString>)> done);

} // namespace suslik::app
