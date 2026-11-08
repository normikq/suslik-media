#pragma once
#include "Mpv.h"

#include <QObject>
#include <atomic>
#include <QVariantList>
#include <QVariantMap>

/// Видеодвижок на libmpv (MpvEngine.kt): ядро mpv, его события в главном потоке (будильник mpv ставит их в очередь),
/// дорожки, перемотка, звук. Кадры рисует MpvVideo в сцене окна (vo=libmpv, render API).
namespace suslik::app {

class MpvEngine : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool available READ available NOTIFY stateChanged)
    Q_PROPERTY(bool loaded READ loaded NOTIFY stateChanged)
    Q_PROPERTY(bool paused READ paused NOTIFY stateChanged)
    /// Ядро ждёт данных (кэш пуст): показываем «Загрузка N %».
    Q_PROPERTY(bool buffering READ buffering NOTIFY stateChanged)
    Q_PROPERTY(int bufferPercent READ bufferPercent NOTIFY stateChanged)
    /// Кадры идут: открыт, не пауза, не ждём кэш.
    Q_PROPERTY(bool playing READ playing NOTIFY stateChanged)
    Q_PROPERTY(qint64 timeMs READ timeMs NOTIFY timeChanged)
    Q_PROPERTY(qint64 lengthMs READ lengthMs NOTIFY lengthChanged)
    Q_PROPERTY(int videoWidth READ videoWidth NOTIFY videoSizeChanged)
    Q_PROPERTY(int videoHeight READ videoHeight NOTIFY videoSizeChanged)
    /// [{id, title, lang, forced, selected, label}]
    Q_PROPERTY(QVariantList audioTracks READ audioTracks NOTIFY tracksChanged)
    Q_PROPERTY(QVariantList subTracks READ subTracks NOTIFY tracksChanged)
    /// [{timeMs, title}]
    Q_PROPERTY(QVariantList chapters READ chapters NOTIFY tracksChanged)
    /// Сколько внешних дорожек ещё добавляется.
    Q_PROPERTY(int pendingAudio READ pendingAudio NOTIFY tracksChanged)
    Q_PROPERTY(int pendingSubs READ pendingSubs NOTIFY tracksChanged)

public:
    struct SideTrack {
        QString target, title, lang;
    };

    explicit MpvEngine(QObject *parent = nullptr);
    ~MpvEngine() override;

    bool available() const { return mpv::api() != nullptr; }
    bool loaded() const { return m_loaded; }
    bool paused() const { return m_pause; }
    bool buffering() const { return m_cacheWait; }
    int bufferPercent() const { return m_cachePercent; }
    bool playing() const { return m_loaded && !m_pause && !m_cacheWait; }
    qint64 timeMs() const { return qint64(m_time * 1000); }
    qint64 lengthMs() const { return qint64(m_length * 1000); }
    int videoWidth() const { return m_videoW; }
    int videoHeight() const { return m_videoH; }
    QVariantList audioTracks() const { return m_audio; }
    QVariantList subTracks() const { return m_subs; }
    QVariantList chapters() const { return m_chapters; }
    int pendingAudio() const { return m_pendingAudio; }
    int pendingSubs() const { return m_pendingSubs; }

    /// Ядро (создаётся при первом обращении). Пустой — libmpv нет или она не запустилась.
    mpv::Handle handle();
    /// Ядро, если оно уже есть (без создания): вывод кадров берёт его из потока отрисовки
    mpv::Handle current() const { return m_h; }

    /// Открыть url с позиции startMs. Внешние субтитры (уже скачанные) и озвучки добавляются, когда файл открыт.
    void open(const QString &url, qint64 startMs, bool nightMode, const QString &audioLang, const QString &subLang,
              const QList<SideTrack> &audio, const QList<SideTrack> &subs);
    /// Субтитры, скачанные позже открытия: добавляются сразу (или по открытию файла).
    void addSubtitles(const QList<SideTrack> &subs);
    Q_INVOKABLE void setPaused(bool paused);
    Q_INVOKABLE void togglePause() { setPaused(!m_pause); }
    Q_INVOKABLE void stop();
    Q_INVOKABLE void seekTo(qint64 ms);
    Q_INVOKABLE void seekToFraction(double f);
    Q_INVOKABLE void setRate(double rate);
    /// 0…150.
    Q_INVOKABLE void setVolume(int percent);
    Q_INVOKABLE void setMute(bool mute);
    Q_INVOKABLE void setAudioDelayMs(qint64 ms);
    Q_INVOKABLE void setSubDelayMs(qint64 ms);
    Q_INVOKABLE void setSubScale(int percent);
    Q_INVOKABLE void setNightMode(bool on);
    Q_INVOKABLE void setPanScan(double val);
    /// false — растянуть картинку на всю область вывода без сохранения пропорций (mpv keepaspect).
    Q_INVOKABLE void setKeepAspect(bool on);
    Q_INVOKABLE void setAudioTrack(int id);
    Q_INVOKABLE void setSubTrack(int id);
    Q_INVOKABLE void removeAudioTrack(int id);
    /// Перезапуск декодера и вывода при застывшей картинке (render context остаётся).
    Q_INVOKABLE void restartVideoOutput();
    /// Кадр в файл (заставка при переоткрытии, картинка для SMTC). false — не вышло.
    bool screenshot(const QString &file);
    /// Сводка для F8 и журнала: кодеки, кадры, потери.
    Q_INVOKABLE QString statsLine();
    QString property(const QString &name);
    double fps();
    /// Настоящий ход звука (audio-pts), для сторожа.
    double audioPts();

    /// Остановить и отпустить ядро (вывод кадров отпустит его следом).
    void release();

signals:
    void stateChanged();
    void timeChanged();
    void lengthChanged();
    void videoSizeChanged();
    void tracksChanged();
    void opened();
    void finished();
    void failed(const QString &message);
    void seekDone();

private:
    void drain();
    void onEvent(const mpv_event &ev);
    void onProperty(const mpv_event_property &p);
    void set(const char *name, const QString &value);
    void command(const QStringList &args);
    void commandAsync(quint64 tag, const QStringList &args);
    void addSideTracks();
    void readTracks();
    void updateVideoSize();

    mpv::Handle m_h;
    bool m_failedStart = false;
    bool m_loaded = false, m_pause = false, m_cacheWait = false, m_playingSent = false;
    int m_cachePercent = 100;
    double m_time = 0, m_length = 0;
    qint64 m_lastTimeSignal = 0;
    int m_videoW = 0, m_videoH = 0;
    QVariantList m_audio, m_subs, m_chapters;
    int m_pendingAudio = 0, m_pendingSubs = 0;
    QString m_openUrl;
    quint64 m_openGen = 0;
    QList<SideTrack> m_sideAudio, m_sideSubs;
    /// drain() уже в очереди: из потока mpv (wakeup) — не ставить второй.
    std::atomic<bool> m_drainQueued{false};
};

} // namespace suslik::app
