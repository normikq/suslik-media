#pragma once
#include "MpvEngine.h"
#include "../PlayRequest.h"

#include "suslik/core/CatchUp.h"
#include "suslik/core/CustomAudio.h"
#include "suslik/core/PlaybackSupervisor.h"

#include <QHash>
#include <QSet>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QVariantList>
#include <functional>
#include <memory>

class QNetworkAccessManager;

/// Плеер (PlayerView.kt без вёрстки): очередь серий, открытие через запас на диске, прогресс в медиатеке и облаке,
/// выбор дорожек (запомненный, умные субтитры), задержки, скорость, ночной звук, заставка и титры, карточка
/// следующей серии, сторож воспроизведения и «копим запас». Вёрстка — PlayerView.qml, кадры — MpvVideo.
namespace suslik::app {

class Library;
class PlayModel;

class PlayerModel : public QObject, private core::PlaybackSupervisor::Actions {
    Q_OBJECT
    Q_PROPERTY(Library *library READ library WRITE setLibrary)
    Q_PROPERTY(suslik::app::MpvEngine *engine READ engine CONSTANT)
    Q_PROPERTY(bool open READ isOpen NOTIFY openChanged)
    Q_PROPERTY(bool closing READ closing NOTIFY openChanged)
    Q_PROPERTY(QString title READ title NOTIFY entryChanged)
    Q_PROPERTY(QString heading READ heading NOTIFY entryChanged)
    Q_PROPERTY(QString poster READ poster NOTIFY entryChanged)
    Q_PROPERTY(int index READ index NOTIFY entryChanged)
    Q_PROPERTY(qint64 magnetId READ magnetId NOTIFY entryChanged)
    Q_PROPERTY(int queueSize READ queueSize NOTIFY entryChanged)
    Q_PROPERTY(QString prevTitle READ prevTitle NOTIFY entryChanged)
    Q_PROPERTY(QString nextEntryTitle READ nextEntryTitle NOTIFY entryChanged)
    /// «Следующая серия: S01E02» для карточки на титрах.
    Q_PROPERTY(QString nextTitle READ nextTitle NOTIFY entryChanged)
    Q_PROPERTY(bool started READ started NOTIFY stateChanged)
    Q_PROPERTY(bool playing READ playing NOTIFY stateChanged)
    Q_PROPERTY(bool paused READ paused NOTIFY stateChanged)
    Q_PROPERTY(QString status READ status NOTIFY statusChanged)
    Q_PROPERTY(qint64 time READ time NOTIFY timeChanged)
    Q_PROPERTY(qint64 length READ length NOTIFY timeChanged)
    Q_PROPERTY(qint64 bufferedMs READ bufferedMs NOTIFY bufferedChanged)
    Q_PROPERTY(int volume READ volume NOTIFY soundChanged)
    Q_PROPERTY(bool muted READ muted NOTIFY soundChanged)
    Q_PROPERTY(qint64 volumeWheelAt READ volumeWheelAt NOTIFY soundChanged)
    Q_PROPERTY(double rate READ rate NOTIFY optionsChanged)
    Q_PROPERTY(int subScale READ subScale NOTIFY optionsChanged)
    Q_PROPERTY(qint64 subDelay READ subDelay NOTIFY optionsChanged)
    Q_PROPERTY(qint64 audioDelay READ audioDelay NOTIFY optionsChanged)
    Q_PROPERTY(bool nightMode READ nightMode NOTIFY optionsChanged)
    Q_PROPERTY(bool crop219 READ crop219 NOTIFY optionsChanged)
    /// [{timeMs, title}]
    Q_PROPERTY(QVariantList chapters READ chapters NOTIFY markersChanged)
    Q_PROPERTY(int chapterIndex READ chapterIndex NOTIFY timeChanged)
    /// Заставка и титры на полосе: [начало, конец] в мс или пусто.
    Q_PROPERTY(QVariantList introRange READ introRange NOTIFY markersChanged)
    Q_PROPERTY(QVariantList creditsRange READ creditsRange NOTIFY markersChanged)
    Q_PROPERTY(bool inIntro READ inIntro NOTIFY timeChanged)
    /// Обратный отсчёт до следующей серии (-1 — карточки нет).
    Q_PROPERTY(int nextIn READ nextIn NOTIFY nextChanged)
    Q_PROPERTY(bool infoOpen READ infoOpen NOTIFY infoChanged)
    Q_PROPERTY(QString infoText READ infoText NOTIFY infoChanged)
    /// Сколько субтитров ещё скачивается (скелеты в меню).
    Q_PROPERTY(int subsFetching READ subsFetching NOTIFY tracksExtraChanged)
    /// Под звуковыми дорожками: «Запомнить задержку для всех (…)» и «Удалить озвучку» (пусто — кнопки нет).
    Q_PROPERTY(QString saveDelayText READ saveDelayText NOTIFY tracksExtraChanged)
    Q_PROPERTY(QString deleteTrackText READ deleteTrackText NOTIFY tracksExtraChanged)
    /// Гость комнаты «Вместе» с её фильмом: перемотку, паузу (пока её держит ведущий) и серии выбирает ведущий.
    Q_PROPERTY(bool partyGuest READ partyGuest NOTIFY partyChanged)
    /// Плеер показывает фильм комнаты (ведущий или гость): скорость — комнаты, своя не действует.
    Q_PROPERTY(bool partyFilm READ partyFilm NOTIFY partyChanged)
    Q_PROPERTY(bool playLocked READ playLocked NOTIFY partyChanged)
    Q_PROPERTY(QVariantList qualityVariants READ qualityVariants NOTIFY qualityVariantsChanged)
    Q_PROPERTY(QString activeVariantKey READ activeVariantKey NOTIFY activeVariantKeyChanged)
    Q_PROPERTY(QString hostVariantKey READ hostVariantKey NOTIFY hostVariantKeyChanged)
    Q_PROPERTY(bool switchingVariant READ switchingVariant NOTIFY switchingVariantChanged)
    Q_PROPERTY(QString suggestedDowngradeKey READ suggestedDowngradeKey NOTIFY suggestedDowngradeChanged)
    Q_PROPERTY(QString suggestedDowngradeLabel READ suggestedDowngradeLabel NOTIFY suggestedDowngradeChanged)

public:
    explicit PlayerModel(QObject *parent = nullptr);
    ~PlayerModel() override;

    Library *library() const { return m_lib; }
    void setLibrary(Library *l) { m_lib = l; }
    MpvEngine *engine() const { return m_engine; }
    bool isOpen() const { return m_open; }
    bool closing() const { return m_closing; }
    QString title() const { return m_title; }
    QString heading() const;
    QString poster() const { return m_poster; }
    int index() const { return m_index; }
    qint64 magnetId() const;
    int queueSize() const { return int(m_queue.size()); }
    QString prevTitle() const;
    QString nextEntryTitle() const;
    QString nextTitle() const;
    bool started() const { return m_started; }
    bool playing() const { return m_playing; }
    bool paused() const { return m_pausedAt > 0; }
    QString status() const { return m_status; }
    qint64 time() const { return m_time; }
    qint64 length() const { return m_length; }
    qint64 bufferedMs() const { return m_bufferedMs; }
    int volume() const { return m_volume; }
    bool muted() const { return m_muted; }
    qint64 volumeWheelAt() const { return m_volumeWheelAt; }
    double rate() const { return m_rate; }
    int subScale() const { return m_subScale; }
    qint64 subDelay() const { return m_subDelay; }
    qint64 audioDelay() const { return m_audioDelay; }
    bool nightMode() const { return m_night; }
    bool crop219() const { return m_crop219; }
    QVariantList chapters() const;
    int chapterIndex() const { return chapterAt(m_time); }
    QVariantList introRange() const;
    QVariantList creditsRange() const;
    bool inIntro() const { return introNow().has_value(); }
    int nextIn() const { return m_nextIn; }
    bool infoOpen() const { return m_infoOpen; }
    QString infoText() const { return m_infoText; }
    int subsFetching() const { return m_subsFetching; }
    QString saveDelayText() const;
    QString deleteTrackText() const;
    QVariantList qualityVariants() const;
    QString activeVariantKey() const { return m_activeVariantKey; }
    QString hostVariantKey() const;
    bool switchingVariant() const { return m_switchingVariant; }
    QString suggestedDowngradeKey() const { return m_suggestedDowngradeKey; }
    QString suggestedDowngradeLabel() const { return m_suggestedDowngradeLabel; }
    QList<core::PartyVariant> requestVariants() const { return m_req.variants; }

    Q_INVOKABLE void switchVariant(const QString &variantKey);
    Q_INVOKABLE void acceptSuggestedDowngrade();
    Q_INVOKABLE void dismissSuggestedDowngrade();

    /// Открыть то, что собрало окно «Смотреть».
    Q_INVOKABLE void start(suslik::app::PlayModel *from);
    /// Предпросмотр внешней озвучки (вкладка «Добавить»): видео с этой озвучкой и её задержкой.
    Q_INVOKABLE void startPreview(const QString &video, const QString &audio, qint64 offsetMs, const QString &title);
    /// Закрыть (место сохраняется).
    Q_INVOKABLE void close();
    /// Завершить закрытие (после анимации вылета)
    Q_INVOKABLE void finishClose();
    /// Ещё раз открыть с того же места (видеодвижок докачан, сеть вернулась).
    Q_INVOKABLE void retry();

    Q_INVOKABLE void togglePause();
    Q_INVOKABLE void seekBy(qint64 ms);
    Q_INVOKABLE void seekTo(qint64 ms);
    Q_INVOKABLE void playEntry(int i);
    Q_INVOKABLE void playNext();
    Q_INVOKABLE void cancelNext();
    Q_INVOKABLE void skipIntro() { skipIntroImpl(false); }
    Q_INVOKABLE void jumpChapter(int delta);
    Q_INVOKABLE void selectAudio(int id, const QString &name);
    Q_INVOKABLE void selectSub(int id, const QString &name);
    Q_INVOKABLE void setVolume(int v);
    Q_INVOKABLE void wheelVolume(int steps);
    Q_INVOKABLE void stepVolume(int delta);
    Q_INVOKABLE void toggleMute();
    Q_INVOKABLE void setSpeed(double r);
    Q_INVOKABLE void stepSpeed(int d);
    Q_INVOKABLE void setSubScaleValue(int v);
    Q_INVOKABLE void shiftSubs(qint64 ms);
    Q_INVOKABLE void shiftAudio(qint64 ms);
    Q_INVOKABLE void toggleNight();
    Q_INVOKABLE void toggleCrop219();
    Q_INVOKABLE void setCrop219(bool on);
    Q_INVOKABLE void toggleInfo();
    Q_INVOKABLE void saveDelay();
    Q_INVOKABLE void deleteTrack();
    /// Метка 📍 в журнале (F8).
    Q_INVOKABLE void mark();
    /// Окно сменило вид (полный экран, мини): сторож проверит кадры.
    Q_INVOKABLE void surfaceChanged() { m_supervisor.surfaceChanged(); }
    /// Перетаскивают ползунок: пока тянут, время с полосы.
    Q_INVOKABLE void setScrubbing(bool on) { m_scrubbing = on; }
    Q_INVOKABLE QString clock(qint64 ms) const;
    Q_INVOKABLE QString rateText(double r) const;

    // ---- совместный просмотр (party/PartySync) ----
    bool partyGuest() const;
    bool partyFilm() const;
    /// Гость, а ведущий держит паузу (или комната ждёт загрузку): снять её гость не может.
    bool playLocked() const;
    QString currentWatchKey() const { return watchKey(); }
    QString activeWatchKey() const { return !m_activeVariantKey.isEmpty() ? m_activeVariantKey : watchKey(); }
    QString canonicalId() const { return m_canonicalId; }
    std::optional<QString> edition() const;
    int findEpisodeIndex(const core::PartyFileRef &f) const;
    /// Открыт ли этот файл в очереди плеера (гость комнаты: ведущий перешёл к серии, которая уже в ней).
    Q_INVOKABLE bool hasWatchKey(const QString &key) const { return m_open && !m_closing && m_req.watchKeys.contains(key); }
    QStringList watchKeys() const { return m_req.watchKeys; }
    /// Как фильм зовётся в комнате: подпись из медиатеки или название.
    QString entryLabel() const;
    /// Где картинка (сразу после перемотки mpv ещё показывает старое место — тогда цель полосы).
    qint64 syncTimeMs() const;
    bool syncBusy() const { return m_scrubbing || m_pendingSeek.has_value(); }
    qint64 engineMs() const;
    qint64 seekStamp() const { return m_lastSeekAt; }
    bool running() const;
    void roomSetPaused(bool paused);
    void roomSeekTo(qint64 t);
    void roomPlayEntry(int i);
    /// Скорость самого движка (подстройка дрейфа); своя скорость плеера — rate().
    void engineRate(double r);
    void showNotice(const QString &text) { flash(text); }

    /// Для SMTC и Discord: строка 1 и 2.
    std::pair<QString, QString> mediaTitles() const;
    Presence presence() const;
    QStringList posterQueries() const { return m_req.posterQueries; }

signals:
    void openChanged();
    void entryChanged();
    void stateChanged();
    void statusChanged();
    void timeChanged();
    void bufferedChanged();
    void soundChanged();
    void optionsChanged();
    void markersChanged();
    void nextChanged();
    void infoChanged();
    void tracksExtraChanged();
    void partyChanged();
    void qualityVariantsChanged();
    void activeVariantKeyChanged();
    void hostVariantKeyChanged();
    void switchingVariantChanged();
    void suggestedDowngradeChanged();
    /// Короткая надпись поверх (громкость, скорость…).
    void flashed(const QString &text);
    /// Плеер закрылся (окно убирает экран плеера).
    void closed();
    /// Выбор после перемотки: внешний мир (SMTC) обновляет полосу.
    void seeked();

private:
    struct Entry {
        QString title, url;
    };
    struct Sides {
        QStringList audio, subs;
    };

    // PlaybackSupervisor::Actions
    void restartVideo() override;
    void fixSound() override;
    void reopen(bool fresh) override;
    void notice(const QString &text) override { flash(text); }
    void log(const QString &message) override;
    void suggestDowngrade(double currentBitrateMbps) override;

    void begin();
    void loadPlaylist();
    void sidecars(int i, std::function<void(const Sides &)> done);
    void open(const QString &url, qint64 startAt, const Sides &sides);
    void reopenAt(qint64 t);
    void openWithCustom(quint64 gen, const QString &url, qint64 startAt, const Sides &sides, const QList<core::CustomTrack> &custom);
    void fetchSubtitles(quint64 gen, const QStringList &urls, const QString &videoUrl);
    void doSeek(qint64 t);
    void savePos(qint64 t, qint64 len, int i = -1);
    qint64 savedPos(int i) const;
    QString watchKey(int i = -1) const;
    QString titleKey() const;
    QString seriesKey() const;
    std::optional<QString> pref(const QString &kind) const;
    QString prefLanguage(const QString &kind) const;
    void putUi(const QString &key, const std::optional<QString> &v);
    void flash(const QString &text);
    void setStatus(const QString &s);
    void applyPrefs();
    void applySmartSubs(int audioId = -1);
    void applyCustomDelay(const QString &name);
    void ensureAudio();
    void reseekHere();
    void evPlayingNow();
    void evBuffering(int percent);
    void evTime(qint64 t);
    void evFinished();
    void evError(const QString &message);
    void onEngineState();
    void tick();
    void superviseTick();
    void refreshInfo();
    void fetchMarkers();
    void checkCredits();
    void warmNext();
    int chapterAt(qint64 t) const;
    core::MediaMarkers markers() const;
    std::optional<core::TimeRange> introNow() const;
    void skipIntroImpl(bool autoSkip);
    QString sideFileName(const QString &url) const;
    std::pair<QString, QString> sideName(const QString &sideUrl, const QString &videoUrl) const;
    QStringList preferFirst(const QStringList &urls, const std::optional<QString> &saved, const QString &videoUrl) const;
    void rebuildVariantsForEntry(int i);

    QPointer<Library> m_lib;
    MpvEngine *m_engine;
    core::PlaybackSupervisor m_supervisor;
    std::unique_ptr<core::CatchUp> m_catchUp;

    PlayRequest m_req;
    std::optional<core::CustomTrack> m_preview;
    QList<core::CustomTrack> m_variantCustom;
    QString m_poster;
    QList<Entry> m_queue;
    QHash<int, Sides> m_sidecars;
    QHash<int, QList<std::function<void(const Sides &)>>> m_sidecarWaiters;
    QHash<QString, QString> m_sidePaths;
    QHash<QString, std::pair<QString, QString>> m_sideTitles;
    int m_index = 0;
    QString m_title, m_currentUrl;
    QString m_seriesTitle;
    quint64 m_gen = 0;

    bool m_open = false, m_closing = false, m_started = false, m_playing = false, m_switching = false;
    qint64 m_pausedAt = 0;
    bool m_muted = false;
    int m_volume = 100;
    qint64 m_volumeWheelAt = 0;
    double m_rate = 1;
    int m_subScale = 100;
    bool m_night = false;
    bool m_crop219 = false;
    qint64 m_audioDelay = 0, m_subDelay = 0;
    qint64 m_time = 0, m_length = 0, m_lastGood = 0, m_bufferedMs = 0;
    QString m_status;
    int m_retries = 0;
    bool m_prefsApplied = false;
    qint64 m_prefsUntil = 0;
    qint64 m_lastSeekAt = 0, m_openedAt = 0;
    qint64 m_bufferingSince = 0;
    int m_bufferingShown = 0;
    bool m_scrubbing = false;
    bool m_byRoom = false;   ///< действие пришло от комнаты «Вместе», а не от человека
    std::optional<qint64> m_pendingSeek;
    std::optional<core::MediaMarkers> m_serverMarkers;
    bool m_markersAsked = false;
    int m_nextIn = -1;
    qint64 m_nextFrom = -1;
    bool m_nextDismissed = false;
    std::optional<core::TimeRange> m_introAutoDone;
    bool m_infoOpen = false;
    QString m_infoText;
    bool m_catchingUp = false;
    QString m_catchUpStatus;
    QList<core::CustomTrack> m_custom;
    std::optional<core::CustomTrack> m_activeCustom;
    bool m_endCut = false;
    QString m_deleteArmed;
    QString m_activeVariantKey;
    QString m_canonicalId;
    bool m_switchingVariant = false;
    QString m_suggestedDowngradeKey;
    QString m_suggestedDowngradeLabel;
    int m_subsFetching = 0;
    /// Внешние субтитры: один менеджер сети на плеер (соединения и TLS-сессии переиспользуются между сериями).
    QNetworkAccessManager *m_subsNam = nullptr;
    QSet<int> m_warmed;
    int m_trackSwitchGen = 0;

    QTimer m_tickTimer, m_superTimer, m_bufTimer, m_infoTimer, m_seekTimer, m_flashTimer, m_prefsTimer, m_ensureAudioTimer,
        m_autoSkipTimer, m_statsTimer, m_trackWatch, m_closeTimer;
    QString m_flashText;
    int m_trackWatchLeft = 0;
    int m_trackWatchShown = 0;
};

} // namespace suslik::app
