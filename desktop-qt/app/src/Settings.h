#pragma once
#include <QObject>
#include <QVariantList>
#include <optional>

namespace suslik::core {
class ApiClient;
}

/// Настройки воспроизведения и приложения (Settings.kt: PlaybackSettings). Хранилище «settings» — то же, что у старой
/// версии (переносится при первом запуске). Плеер читает их при каждом открытии фильма.
namespace suslik::app {

class Updater;

namespace PlaybackSettings {
int burstMb();
int cacheMs();
bool customAudioOn();
bool autoSkipIntro();
bool offerNextOnCredits();
int nextCountdown();
bool smartSubs();
bool discordOn();
QString betaCode();
bool betaOn();
/// Код, который уходит с запросами (X-Beta-Code): только пока выбран бета-канал.
std::optional<QString> sentBetaCode();
bool betaChannel();
} // namespace PlaybackSettings

/// То же для окна «Настройки» (контекстное свойство prefs).
class Prefs : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantList burstPresets READ burstPresets CONSTANT)
    Q_PROPERTY(QVariantList cachePresets READ cachePresets CONSTANT)
    Q_PROPERTY(QVariantList countdownChoices READ countdownChoices CONSTANT)
    Q_PROPERTY(int burstMb READ burstMb WRITE setBurstMb NOTIFY changed)
    Q_PROPERTY(int cacheMs READ cacheMs WRITE setCacheMs NOTIFY changed)
    Q_PROPERTY(bool customAudioOn READ customAudioOn WRITE setCustomAudioOn NOTIFY changed)
    Q_PROPERTY(bool autoSkipIntro READ autoSkipIntro WRITE setAutoSkipIntro NOTIFY changed)
    Q_PROPERTY(bool offerNextOnCredits READ offerNextOnCredits WRITE setOfferNextOnCredits NOTIFY changed)
    Q_PROPERTY(int nextCountdown READ nextCountdown WRITE setNextCountdown NOTIFY changed)
    Q_PROPERTY(bool smartSubs READ smartSubs WRITE setSmartSubs NOTIFY changed)
    Q_PROPERTY(bool discordOn READ discordOn WRITE setDiscordOn NOTIFY changed)
    Q_PROPERTY(QString betaCode READ betaCode NOTIFY changed)
    /// Выбран бета-канал и код сохранён.
    Q_PROPERTY(bool beta READ beta NOTIFY changed)
    /// «Память»: пусто — ещё считаем.
    Q_PROPERTY(QString storageText READ storageText NOTIFY storageChanged)
    Q_PROPERTY(bool clearing READ clearing NOTIFY storageChanged)
    /// Строка под «Каналом обновлений» и под «Памятью».
    Q_PROPERTY(QString channelState READ channelState NOTIFY channelStateChanged)
    Q_PROPERTY(QString storageState READ storageState NOTIFY storageChanged)

public:
    Prefs(core::ApiClient &api, Updater &updater, QObject *parent = nullptr);

    QVariantList burstPresets() const;
    QVariantList cachePresets() const;
    QVariantList countdownChoices() const;
    int burstMb() const { return PlaybackSettings::burstMb(); }
    void setBurstMb(int v);
    int cacheMs() const { return PlaybackSettings::cacheMs(); }
    void setCacheMs(int v);
    bool customAudioOn() const { return PlaybackSettings::customAudioOn(); }
    void setCustomAudioOn(bool on);
    bool autoSkipIntro() const { return PlaybackSettings::autoSkipIntro(); }
    void setAutoSkipIntro(bool on);
    bool offerNextOnCredits() const { return PlaybackSettings::offerNextOnCredits(); }
    void setOfferNextOnCredits(bool on);
    int nextCountdown() const { return PlaybackSettings::nextCountdown(); }
    void setNextCountdown(int v);
    bool smartSubs() const { return PlaybackSettings::smartSubs(); }
    void setSmartSubs(bool on);
    bool discordOn() const { return PlaybackSettings::discordOn(); }
    void setDiscordOn(bool on);
    QString betaCode() const { return PlaybackSettings::betaCode(); }
    bool beta() const { return PlaybackSettings::betaOn() && !PlaybackSettings::betaCode().isEmpty(); }
    QString storageText() const { return m_storageText; }
    QString storageState() const { return m_storageState; }
    bool clearing() const { return m_clearing; }
    QString channelState() const { return m_channelState; }

    /// Окно открылось: посчитать, сколько занимает кэш.
    Q_INVOKABLE void measureStorage();
    Q_INVOKABLE void clearCache();
    /// «Релиз» / «Бета»: переключение сразу проверяет сервер.
    Q_INVOKABLE void pickChannel(bool beta);
    Q_INVOKABLE void saveBetaCode(const QString &code);
    Q_INVOKABLE void checkUpdates();
    Q_INVOKABLE void resetState();

signals:
    void changed();
    void storageChanged();
    void channelStateChanged();

private:
    void setChannelState(const QString &s) { m_channelState = s; emit channelStateChanged(); }
    core::ApiClient &m_api;
    Updater &m_updater;
    QString m_storageText, m_storageState, m_channelState;
    qint64 m_lastTotal = -1;
    bool m_clearing = false;
};

/// Папка запаса фильмов (BurstProxy) — своя у новой версии, чтобы не мешать старой.
QString reserveDir();

} // namespace suslik::app
