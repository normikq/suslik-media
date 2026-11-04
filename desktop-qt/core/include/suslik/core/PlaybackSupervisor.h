#pragma once
#include <QString>
#include <functional>
#include <optional>
#include <climits>
#include <utility>

/// Одно место, которое решает, когда воспроизведение нездорово и что с этим делать. Плеер только сообщает события
/// и замеры (tick раз в ~2 с); проверка разрешена, лишь пока фильм действительно идёт, по одному действию за раз,
/// сначала мягкие (перезапуск видео, выбор звука), потом переоткрытие; бюджет на серию. Перенос PlaybackSupervisor.kt.
///
///     Idle → Opening → Buffering ⇄ Playing ⇄ Paused
///                         ↑           ↓
///                         └─ Recovering (одно действие, потом передышка)
namespace suslik::core {

class PlaybackSupervisor {
public:
    enum class State { Idle, Opening, Buffering, Playing, Paused, Recovering, Ended };

    /// Что может плеер. Вызовы приходят из tick() и событий, в потоке вызывающего.
    class Actions {
    public:
        virtual ~Actions() = default;
        /// Новый декодер и вывод видео в текущем месте (застывшая / дёрганая картинка).
        virtual void restartVideo() = 0;
        /// Выбрана настоящая звуковая дорожка, вывод не заглушён.
        virtual void fixSound() = 0;
        /// Открыть фильм заново в текущем месте; fresh — заодно выбросить локальный запас.
        virtual void reopen(bool fresh) = 0;
        /// Короткое сообщение зрителю («Восстанавливаем звук…»).
        virtual void notice(const QString &text) = 0;
        virtual void log(const QString &message) = 0;
        /// Подсказка даунгрейда качества при частой буферизации
        virtual void suggestDowngrade(double currentBitrateMbps) { (void)currentBitrateMbps; }
    };

    /// Замеры плеера. Счётчики — нарастающие для текущего потока.
    struct Stats {
        qint64 timeMs = 0;       ///< часы фильма
        qint64 audioPlayed = 0;  ///< сыгранные звуковые буферы
        qint64 audioDecoded = 0; ///< декодированные звуковые блоки
        qint64 pictures = 0;     ///< показанные кадры
        double expectedFps = 0;  ///< частота кадров фильма (0 — неизвестна)
        int audioTracks = 0;     ///< настоящие звуковые дорожки
        bool audioSelected = false;
        int videoTracks = 0;
        bool userMuted = false;  ///< звук выключен пользователем: тишина ожидаема
    };

    using Clock = std::function<qint64()>;

    /// stuckLimitMs — сколько терпеть стоящие часы до переоткрытия (дольше, когда переподключается локальный запас).
    explicit PlaybackSupervisor(Actions &actions, Clock now = {}, std::function<qint64()> stuckLimitMs = {});

    State state() const { return m_state; }

    void setBitrateMbps(double mbps) { m_bitrateMbps = mbps; }
    double bitrateMbps() const { return m_bitrateMbps; }

    // ---- события ----
    /// Поток начинает открываться. Новый episodeKey обнуляет бюджеты.
    void opening(const QString &episodeKey);
    void buffering(float percent);
    void playing();
    void paused();
    void resumed();
    /// Любой прыжок (перемотка, глава, смена дорожки с перемоткой): плеер пересобирает буфер.
    void seeked();
    void ended();
    void closed();
    /// Размер окна / полный экран / новая поверхность: вскоре проверить, что картинка идёт.
    void surfaceChanged();

    void tick(const Stats &s);

private:
    void enter(State s, qint64 grace = 0);
    void resetMeasurements();
    /// false — действие не разрешено (только что было другое).
    bool act(const QString &reason);
    void afterAct(qint64 t);

    Actions &m_actions;
    Clock m_now;
    std::function<qint64()> m_stuckLimit;
    State m_state = State::Idle;
    QString m_episode;
    qint64 m_graceUntil = 0;
    int m_videoFixes = 0, m_soundFixes = 0, m_reopens = 0, m_afterBufferFixes = 0;
    qint64 m_bufferedAt = -1;
    qint64 m_lastAction = LLONG_MIN / 2;
    std::optional<Stats> m_last;
    qint64 m_lastAt = 0;
    int m_picturesMoved = 0;
    qint64 m_silentFor = 0, m_frozenFor = 0, m_stuckFor = 0;
    int m_choppyTicks = 0;
    std::optional<std::pair<qint64, qint64>> m_surfaceCheck; ///< (когда, кадров до)
    struct BufferSpan { qint64 start = 0; qint64 durationMs = 0; };
    double m_bitrateMbps = 0.0;
    qint64 m_bufferingStart = 0;
    std::vector<BufferSpan> m_recentBuffering;
    qint64 m_lastDowngradeSuggested = LLONG_MIN / 2;
};

} // namespace suslik::core
