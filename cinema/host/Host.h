#pragma once
#include <QObject>
#include <QString>

namespace suslik::cinema {

/// Состояние хоста для QML каркаса.
class Host : public QObject {
    Q_OBJECT
    /// Открыт фильм (иначе на экране тестовая картинка).
    Q_PROPERTY(bool hasFile READ hasFile CONSTANT)
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
    QString version() const { return QStringLiteral(SM_VERSION); }

signals:
    void hiddenChanged();

private:
    bool m_hasFile;
    bool m_hidden = false;
};

} // namespace suslik::cinema
