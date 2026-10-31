#pragma once
#include <QObject>

class QNetworkAccessManager;
class QNetworkReply;
class QFile;
class QCryptographicHash;

/// libmpv-2.dll (~120 МБ) не входит в программу (MpvInstall.kt): её кладёт установщик, а если её нет — программа
/// докачивает её с нашего сервера (путь и SHA-256 — libmpv.properties прежней версии) в папку данных mpv\.
namespace suslik::app {

class MpvInstall : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool ready READ ready NOTIFY changed)
    /// 0…1, пока качается; -1 — не качается.
    Q_PROPERTY(double progress READ progress NOTIFY changed)
    /// Текст ошибки последней загрузки.
    Q_PROPERTY(QString error READ error NOTIFY changed)
    Q_PROPERTY(QString path READ path NOTIFY changed)

public:
    MpvInstall(const QString &site, QObject *parent = nullptr);

    bool ready() const;
    double progress() const { return m_progress; }
    QString error() const { return m_error; }
    QString path() const;

    /// Проверить и, если библиотеки нет, скачать (повторный вызов после ошибки пробует снова).
    Q_INVOKABLE void start();

signals:
    void changed();
    /// Загрузка кончилась: ok — библиотека на месте.
    void finished(bool ok);

private:
    void fail(const QString &message, const QString &log);

    QString m_url, m_sha;
    double m_progress = -1;
    QString m_error;
    QNetworkAccessManager *m_nam = nullptr;
    QNetworkReply *m_reply = nullptr;
    QFile *m_part = nullptr;
    QCryptographicHash *m_hash = nullptr;
};

} // namespace suslik::app
