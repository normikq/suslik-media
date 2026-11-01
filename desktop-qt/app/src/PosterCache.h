#pragma once
#include <QCache>
#include <QImage>
#include <QMutex>
#include <QMutexLocker>
#include <QString>
#include <optional>

namespace suslik::app {

/// Раскодированные картинки (постеры, кадры баннера, свечение) в памяти: ограниченный LRU-кэш.
/// Возврат на прежнюю страницу медиатеки или вкладку — без чтения с диска и раскодирования.
/// Вес записи — байты картинки; на рубеже MAX_BYTES вытесняются давно не нужные.
/// Ключ — путь, запрошенный размер и вид (свечение), см. ImageProvider.
class PosterCache {
public:
    static constexpr qsizetype MAX_BYTES = 32LL << 20; // ≈ 95 постеров 240×360

    static PosterCache &instance() {
        static PosterCache c;
        return c;
    }

    std::optional<QImage> get(const QString &key) {
        QMutexLocker lock(&m_mutex);
        if (const QImage *img = m_cache.object(key)) return *img;
        return std::nullopt;
    }

    void put(const QString &key, const QImage &img) {
        if (img.isNull()) return;
        QMutexLocker lock(&m_mutex);
        m_cache.insert(key, new QImage(img), img.sizeInBytes()); // больше MAX_BYTES — QCache не возьмёт
    }

    void clear() {
        QMutexLocker lock(&m_mutex);
        m_cache.clear();
    }

private:
    PosterCache() { m_cache.setMaxCost(MAX_BYTES); }
    QMutex m_mutex;
    QCache<QString, QImage> m_cache;
};

} // namespace suslik::app
