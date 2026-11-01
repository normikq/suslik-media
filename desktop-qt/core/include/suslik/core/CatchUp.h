#pragma once
#include "suslik/core/BurstProxy.h"

#include <QDateTime>
#include <functional>

/// «Копим запас» — те же правила, что в Kotlin-версии (shared/.../core/CatchUp.kt). На сети медленнее фильма запас
/// кончается, и плеер встаёт на несколько секунд каждые 15–30 с; после такой остановки аппаратный декодер часто
/// показывает застывшую или битую картинку. Вместо этого, когда запас пуст, BurstProxy::hold держит фильм, пока не
/// скачается ~30 с (catchUpTargetMb, не дольше MAX_MS): для плеера это одна длинная буферизация. Паузу плееру не ставим.
///
/// Приложение сообщает, что знает (свои условия в maybeStart, тихое время после открытия / перемотки в quiet), и раз
/// в секунду спрашивает progress, пока active; end говорит, перезапустить ли декодеры на текущем месте.
namespace suslik::core {

class CatchUp {
public:
    static constexpr qint64 MAX_MS = 45000;
    /// Столько после прошлого раза новый не начинается.
    static constexpr qint64 AFTER_MS = 20000;

    using Clock = std::function<qint64()>;
    CatchUp(BurstProxy &proxy, std::function<void(const QString &)> log, Clock now = [] { return QDateTime::currentMSecsSinceEpoch(); })
        : m_proxy(proxy), m_log(std::move(log)), m_now(std::move(now)) {}

    /// Фильм держится сейчас.
    bool active() const { return m_active; }
    /// Когда начался текущий (или последний) раз, по часам now.
    qint64 startedAt() const { return m_startedAt; }

    /// Не копить ms (открыли, перемотали): плеер всё равно наполняет свой буфер.
    void quiet(qint64 ms) { m_notBefore = std::max(m_notBefore, m_now() + ms); }

    /// Начинает держать фильм, когда сеть медленнее него и запас (почти) пуст. canStart — условия приложения
    /// (играет, не пауза, перемотки нет). true — начал.
    bool maybeStart(const QString &url, bool canStart);

    /// Пока active: сколько собрано, 0..99 %, или −1, когда хватит (или прошло MAX_MS): end с перезапуском.
    int progress(const QString &url) const;

    /// Отпускает фильм. С restart (запас есть) — true, когда плееру стоит перемотать на текущее место: декодер,
    /// отставший после долгой буферизации, начинает чисто. Только если позади хранится ≥ 8 с: прыжок попадает на
    /// последний ключевой кадр, на несколько секунд назад.
    bool end(const QString &url, bool restart);

private:
    BurstProxy &m_proxy;
    std::function<void(const QString &)> m_log;
    Clock m_now;
    bool m_active = false;
    qint64 m_startedAt = 0;
    qint64 m_notBefore = 0;
};

} // namespace suslik::core
