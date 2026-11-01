#include "suslik/core/CatchUp.h"

#include <algorithm>

namespace suslik::core {

bool CatchUp::maybeStart(const QString &url, bool canStart) {
    if (m_active || !canStart || m_now() < m_notBefore) return false;
    if (!m_proxy.isActive(url) || !m_proxy.slowerThanFilm(url) || m_proxy.aheadMb(url).value_or(99.0) >= 2.0) return false;
    m_active = true;
    m_startedAt = m_now();
    m_log(QString::asprintf("network slower than the film, reserve empty: holding the film to gather %.0f MB", m_proxy.catchUpTargetMb(url)));
    m_proxy.hold(url, MAX_MS + 5000);
    return true;
}

int CatchUp::progress(const QString &url) const {
    const double target = m_proxy.catchUpTargetMb(url);
    const double ahead = m_proxy.aheadMb(url).value_or(0.0);
    if (ahead >= target || m_now() - m_startedAt >= MAX_MS) return -1;
    return std::clamp(static_cast<int>(ahead * 100 / std::max(target, 1.0)), 0, 99);
}

bool CatchUp::end(const QString &url, bool restart) {
    if (!m_active) return false;
    m_active = false;
    quiet(AFTER_MS);
    m_proxy.hold(url, 0);
    m_log(QString::asprintf("reserve gathered: %.0f MB in %lld s", m_proxy.aheadMb(url).value_or(0.0),
                            static_cast<long long>((m_now() - m_startedAt) / 1000)));
    if (!restart) return false;
    const double behind = m_proxy.keptBehindSec(url);
    if (behind >= 8.0) return true;
    m_log(QString::asprintf("restart skipped: only %.1f s kept behind", behind));
    return false;
}

} // namespace suslik::core
