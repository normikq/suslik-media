#pragma once
#include <QObject>
#include <QString>

namespace suslik::cinema {

/// Состояние хоста для QML каркаса.
class Host : public QObject {
    Q_OBJECT
    /// Плеер на экране (иначе тестовая картинка). Ядро mpv рисует, только пока плеер на экране, поэтому в режиме
    /// пульта он там всегда, а без фильма экран прячет blank.
    Q_PROPERTY(bool hasFile READ hasFile CONSTANT)
    /// Пульт: фильм не выбран или остановлен — экран не показываем.
    Q_PROPERTY(bool blank READ blank WRITE setBlank NOTIFY blankChanged)
    /// Экран скрыт клавишей (позже — когда телевизор вне кадра).
    Q_PROPERTY(bool hidden READ hidden WRITE setHidden NOTIFY hiddenChanged)
    Q_PROPERTY(QString version READ version CONSTANT)

public:
    explicit Host(bool hasFile, QObject *parent = nullptr) : QObject(parent), m_hasFile(hasFile) {}

    bool hasFile() const { return m_hasFile; }
    bool hidden() const { return m_hidden; }
    void setHidden(bool h) {
        if (h == m_hidden) return;
        m_hidden = h;
        emit hiddenChanged();
    }
    bool blank() const { return m_blank; }
    void setBlank(bool b) {
        if (b == m_blank) return;
        m_blank = b;
        emit blankChanged();
    }
    QString version() const { return QStringLiteral(SM_VERSION); }

signals:
    void hiddenChanged();
    void blankChanged();

private:
    bool m_hasFile;
    bool m_hidden = false;
    bool m_blank = false;
};

} // namespace suslik::cinema
