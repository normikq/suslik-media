#pragma once
#include <QObject>
#include <QVariantMap>

class QWindow;
class QQmlApplicationEngine;

/// Помощник интерфейса для QML: строки (tx), открыть ссылку, размеры файлов, буфер обмена, окно.
namespace suslik::app {

class Ui : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString version READ version CONSTANT)
    Q_PROPERTY(bool diag READ diag CONSTANT)
    Q_PROPERTY(bool siteConfigured READ siteConfigured CONSTANT)
    /// Папка данных (журнал отладочной сборки и т. п.).
    Q_PROPERTY(QString appDir READ appDir CONSTANT)
public:
    Ui(const QString &version, bool siteConfigured, QObject *parent = nullptr)
        : QObject(parent), m_version(version), m_site(siteConfigured) {}

    void setEngine(QQmlApplicationEngine *e) { m_engine = e; }
    QString version() const { return m_version; }
    bool diag() const;
    bool siteConfigured() const { return m_site; }

    /// Ui.tx("player.next", {title: name}) ← «Следующая: {title}».
    QString appDir() const;
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
    /// Мягкий сброс: сборка мусора QML, очистка кэша компонентов и пиксмапов интерфейса (без сброса страниц ОС).
    Q_INVOKABLE void trimMemory() const;
    /// Полная выгрузка: сборка мусора + сброс физических страниц процесса в ОС (при сворачивании > 30 с).
    Q_INVOKABLE void unloadMemory() const;
    /// Кэш раскодированных постеров (PosterCache) — долой: при открытии плеера и в unloadMemory. Не на вкладках
    /// и страницах — иначе возврат на прошлую страницу снова читает и раскодирует постеры с диска.
    Q_INVOKABLE void dropPosterCache() const;
    /// Переменная окружения (проверка вёрстки: SM_OPEN открывает окно сразу после запуска).
    Q_INVOKABLE QString env(const QString &name) const { return qEnvironmentVariable(name.toUtf8().constData()); }

private:
    QString m_version;
    bool m_site;
    QQmlApplicationEngine *m_engine = nullptr;
};

} // namespace suslik::app
