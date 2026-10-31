#include "suslik/core/PlaybackSupervisor.h"

#include <QDateTime>
#include <algorithm>
#include <climits>

namespace suslik::core {

PlaybackSupervisor::PlaybackSupervisor(Actions &actions, Clock now, std::function<qint64()> stuckLimitMs)
    : m_actions(actions),
      m_now(now ? std::move(now) : Clock([] { return QDateTime::currentMSecsSinceEpoch(); })),
      m_stuckLimit(stuckLimitMs ? std::move(stuckLimitMs) : std::function<qint64()>([] { return qint64(12000); })) {}

void PlaybackSupervisor::opening(const QString &episodeKey) {
    if (episodeKey != m_episode) {
        m_episode = episodeKey;
        m_videoFixes = m_soundFixes = m_reopens = m_afterBufferFixes = 0;
    }
    m_bufferedAt = -1;
    m_picturesMoved = 0; // новый поток: неподвижная обложка не должна сойти за фильм, потому что прошлый двигался
    enter(State::Opening, 8000);
}

void PlaybackSupervisor::buffering(float percent) {
    if (m_state == State::Paused || m_state == State::Ended) return;
    if (percent < 100.f) {
        if (m_state != State::Opening) enter(State::Buffering, 4000);
    } else if (m_state == State::Buffering) {
        m_bufferedAt = m_now();
        enter(State::Playing, 2000);
    } else if (m_state == State::Opening) {
        enter(State::Playing, 4000);
    }
}

void PlaybackSupervisor::playing() {
    if (m_state != State::Paused && m_state != State::Playing) enter(State::Playing, 4000);
}
void PlaybackSupervisor::paused() { enter(State::Paused); }
void PlaybackSupervisor::resumed() { enter(State::Playing, 5000); }
void PlaybackSupervisor::seeked() {
    if (m_state != State::Paused) enter(State::Playing, 5000);
    else resetMeasurements();
}
void PlaybackSupervisor::ended() { enter(State::Ended); }
void PlaybackSupervisor::closed() { enter(State::Idle); }
void PlaybackSupervisor::surfaceChanged() { m_surfaceCheck = std::pair{m_now() + 1500, m_last ? m_last->pictures : qint64(-1)}; }

void PlaybackSupervisor::enter(State s, qint64 grace) {
    m_state = s;
    if (grace > 0) m_graceUntil = std::max(m_graceUntil, m_now() + grace);
    resetMeasurements();
}

void PlaybackSupervisor::resetMeasurements() {
    m_last.reset();
    m_silentFor = m_frozenFor = m_stuckFor = 0;
    m_choppyTicks = 0;
}

bool PlaybackSupervisor::act(const QString &reason) {
    const qint64 t = m_now();
    if (t - m_lastAction < 8000) return false; // никогда два восстановления подряд
    m_lastAction = t;
    m_actions.log(QStringLiteral("supervisor: %1 → action (video %2, sound %3, reopen %4)").arg(reason).arg(m_videoFixes).arg(m_soundFixes).arg(m_reopens));
    return true;
}

void PlaybackSupervisor::afterAct(qint64 t) {
    m_state = State::Recovering;
    m_graceUntil = t + 6000;
    resetMeasurements();
}

void PlaybackSupervisor::tick(const Stats &s) {
    const qint64 t = m_now();
    const std::optional<Stats> prev = m_last;
    const qint64 prevAt = m_lastAt;
    m_last = s;
    m_lastAt = t;
    if (m_state == State::Recovering && t >= m_graceUntil) m_state = State::Playing;
    if (!prev) return;
    if (s.pictures > prev->pictures && prev->pictures > 0) ++m_picturesMoved;

    // Сразу после смены поверхности — один быстрый взгляд (и во время передышки после неё)
    if (m_surfaceCheck && t >= m_surfaceCheck->first) {
        m_surfaceCheck.reset();
        if (m_state == State::Playing && s.videoTracks > 0 && s.pictures > 0 && s.pictures == prev->pictures && s.timeMs > prev->timeMs &&
            m_videoFixes < 3) {
            if (act(QStringLiteral("frozen picture after the video area changed"))) {
                ++m_videoFixes;
                m_actions.restartVideo();
                afterAct(t);
            }
            return;
        }
    }

    if (m_state != State::Playing || t < m_graceUntil) {
        m_silentFor = m_frozenFor = m_stuckFor = 0;
        m_choppyTicks = 0;
        return;
    }
    const qint64 wall = std::max<qint64>(t - prevAt, 1);
    const qint64 clock = s.timeMs - prev->timeMs;

    auto reopen = [&](const QString &reason, const QString &notice, std::optional<bool> fresh) {
        if (!act(reason)) return;
        ++m_reopens;
        if (!notice.isEmpty()) m_actions.notice(notice);
        m_actions.reopen(fresh.value_or(m_reopens >= 2));
        afterAct(t);
    };
    auto restartVideo = [&](const QString &reason, int &budget) {
        if (!act(reason)) return;
        ++budget;
        m_actions.restartVideo();
        afterAct(t);
    };

    // 1. Стоят часы: «играет», а время не идёт (и буферизации плеер не сообщает)
    if (clock <= 50) {
        m_stuckFor += wall;
        if (m_stuckFor >= m_stuckLimit() && m_reopens < 3) reopen(QStringLiteral("clock stuck for %1 s").arg(m_stuckFor / 1000), {}, std::nullopt);
        return;
    }
    m_stuckFor = 0;
    if (clock < wall / 3) return; // еле идёт (догоняет после заминки): не время судить

    // 2. Застывшая картинка: часы идут, нового кадра нет, а раньше картинка двигалась
    if (s.videoTracks > 0 && s.pictures > 0 && m_picturesMoved >= 3) {
        m_frozenFor = s.pictures == prev->pictures ? m_frozenFor + clock : 0;
        if (m_frozenFor >= 3000) {
            // Сразу после буферизации — известная беда плеера, не сломанный поток: новый декодер помогает всегда
            if (m_bufferedAt >= 0 && t - m_bufferedAt < 30000 && m_afterBufferFixes < 20) {
                restartVideo(QStringLiteral("frozen picture after buffering"), m_afterBufferFixes);
                return;
            }
            if (m_videoFixes < 2) { restartVideo(QStringLiteral("frozen picture"), m_videoFixes); return; }
            if (m_reopens < 3) { reopen(QStringLiteral("frozen picture again"), {}, std::nullopt); return; }
        }
    }

    // 3. Дёрганая картинка: кадров гораздо меньше, чем в фильме
    if (s.videoTracks > 0 && s.expectedFps >= 15 && prev->pictures > 0 && m_picturesMoved >= 3) {
        const double shown = double(s.pictures - prev->pictures) * 1000.0 / double(clock);
        m_choppyTicks = shown < s.expectedFps * 0.45 ? m_choppyTicks + 1 : 0;
        if (m_choppyTicks >= 3) {
            const QString msg = QStringLiteral("choppy picture: %1 of %2 fps").arg(shown, 0, 'f', 1).arg(s.expectedFps, 0, 'f', 1);
            if (m_videoFixes < 2) { restartVideo(msg, m_videoFixes); return; }
            if (m_reopens < 3) { reopen(msg, QStringLiteral("Восстанавливаем картинку…"), true); return; }
        }
    }

    // 4. Немой фильм: настоящая дорожка, звук не выключен, часы идут, до колонок ничего не доходит
    if (s.audioTracks > 0 && !s.userMuted && (s.audioDecoded > 0 || s.pictures > 0)) {
        if (!s.audioSelected) {
            if (m_soundFixes < 3 && act(QStringLiteral("no audio track selected"))) {
                ++m_soundFixes;
                m_actions.fixSound();
                afterAct(t);
            }
            return;
        }
        m_silentFor = s.audioPlayed == prev->audioPlayed ? m_silentFor + clock : 0;
        if (m_silentFor >= 5000) {
            if (m_soundFixes < 1) {
                if (act(QStringLiteral("silent for %1 s").arg(m_silentFor / 1000))) {
                    ++m_soundFixes;
                    m_actions.fixSound();
                    afterAct(t);
                }
                return;
            }
            if (m_reopens < 3) {
                if (act(QStringLiteral("silent again"))) {
                    ++m_soundFixes;
                    ++m_reopens;
                    m_actions.notice(QStringLiteral("Восстанавливаем звук…"));
                    m_actions.reopen(false);
                    afterAct(t);
                }
                return;
            }
        }
    }
}

} // namespace suslik::core
