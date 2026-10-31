#pragma once
#include <QObject>
#include <QVariantMap>

class QWindow;

/// Помощник интерфейса для QML: строки (tx), открыть ссылку, размеры файлов, буфер обмена, окно.
namespace suslik::app {

class Ui : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString version READ version CONSTANT)
    Q_PROPERTY(bool diag READ diag CONSTANT)
    Q_PROPERTY(bool siteConfigured READ siteConfigured CONSTANT)
public:
    Ui(const QString &version, bool siteConfigured, QObject *parent = nullptr)
        : QObject(parent), m_version(version), m_site(siteConfigured) {}

    QString version() const { return m_version; }
    bool diag() const;
    bool siteConfigured() const { return m_site; }

    /// Ui.tx("player.next", {title: name}) ← «Следующая: {title}».
    Q_INVOKABLE QString tx(const QString &key, const QVariantMap &params = {}) const;
    /// «8,4 ГБ», «512 МБ».
    Q_INVOKABLE QString size(qint64 bytes) const;
    Q_INVOKABLE void openUrl(const QString &url) const;
    Q_INVOKABLE void copy(const QString &text) const;
    Q_INVOKABLE QString paste() const;
    /// Хранилище "ui" (вид, свёрнутая панель и др.) для QML.
    Q_INVOKABLE QString setting(const QString &key, const QString &def = {}) const;
    Q_INVOKABLE void setSetting(const QString &key, const QString &value) const;
    /// Окно выйти вперёд (второй запуск).
    Q_INVOKABLE void raise(QWindow *w) const;
    Q_INVOKABLE void styleTitleBar(QWindow *w) const;
    /// Переменная окружения (проверка вёрстки: SM_OPEN открывает окно сразу после запуска).
    Q_INVOKABLE QString env(const QString &name) const { return qEnvironmentVariable(name.toUtf8().constData()); }

private:
    QString m_version;
    bool m_site;
};

} // namespace suslik::app
