#pragma once
#include <QObject>
#include <QVariantList>
#include <QVariantMap>
#include <functional>
#include <optional>

namespace suslik::core {
class ApiClient;
}

/// Обновления со своего сервера и «Что нового» (Updater.kt, WhatsNew.kt). Проверка после входа; новая версия — дышащая
/// кнопка в боковой панели; нажали — установщик качается (размер и SHA-256 сверяются), запускается, приложение закрывается.
namespace suslik::app {

class Updater : public QObject {
    Q_OBJECT
    /// {version, size, beta, replace} или пусто — новее нет.
    Q_PROPERTY(QVariantMap available READ available NOTIFY availableChanged)
    /// 0…1 пока качается, −1 — не качается.
    Q_PROPERTY(double progress READ progress NOTIFY stateChanged)
    Q_PROPERTY(QString error READ error NOTIFY stateChanged)
    /// «Что нового» после обновления: [{version, title, items}] или пусто.
    Q_PROPERTY(QVariantList whatsNew READ whatsNew NOTIFY whatsNewChanged)
    Q_PROPERTY(QString current READ current CONSTANT)

public:
    struct Info {
        QString version, notes, url, file, sha256;
        qint64 size = 0;
        bool beta = false, replace = false;
    };

    Updater(core::ApiClient &api, const QString &current, QObject *parent = nullptr);

    QVariantMap available() const;
    double progress() const { return m_progress; }
    QString error() const { return m_error; }
    QVariantList whatsNew() const { return m_whatsNew; }
    QString current() const { return m_current; }

    /// После входа: есть ли новее для этого канала (отладочная сборка себя не обновляет).
    Q_INVOKABLE void check();
    /// Самая новая версия на сервере для канала этого компьютера, новее или нет (nullopt — нет ответа).
    void latest(std::function<void(std::optional<Info>)> done);
    void setAvailable(const std::optional<Info> &info);
    /// Скачать, проверить и поставить; приложение закроется.
    Q_INVOKABLE void install();
    /// Окно «Что нового» — один раз на версию.
    Q_INVOKABLE void loadWhatsNew();
    Q_INVOKABLE void whatsNewSeen();

    /// Папка скачанного установщика и сколько он занимает (для «Очистить кэш»).
    static QString tempDir();
    static qint64 tempBytes();
    static void cleanup();
    /// После обновления — кэши прошлой версии долой (прогресс, вход и настройки остаются). До открытия хранилищ.
    static void clearCachesOnUpdate(const QString &current);

signals:
    void availableChanged();
    void stateChanged();
    void whatsNewChanged();

private:
    void installAndExit(const QString &file, bool replace);
    bool needsReinstall(const QString &remote) const;

    core::ApiClient &m_api;
    QString m_current;
    std::optional<Info> m_available;
    double m_progress = -1;
    QString m_error;
    QVariantList m_whatsNew;
};

} // namespace suslik::app
