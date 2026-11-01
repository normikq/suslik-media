#include "ImageProvider.h"
#include "Library.h"
#include "PosterCache.h"

#include <QBuffer>
#include <QDateTime>
#include <QFile>
#include <QCoreApplication>
#include <QImageReader>
#include <QMetaObject>
#include <QPointer>
#include <QQuickTextureFactory>
#include <QThreadPool>
#include <QRunnable>
#include <algorithm>

namespace suslik::app {

namespace {

constexpr int GLOW_W = 96;
/// Размер не задан — не больше этого по длинной стороне (кадр 4K не займёт десятки мегабайт).
constexpr int MAX_SIDE = 1920;

/// Размытие (три прохода «коробкой» 3×3) и края, тающие в фон: сверху и снизу по 30 %, по бокам по 12 % (как у прежней версии).
QImage glowOf(QImage src) {
    QImage img = src.convertToFormat(QImage::Format_ARGB32);
    const int w = img.width(), h = img.height();
    for (int pass = 0; pass < 3; ++pass) {
        QImage out(w, h, QImage::Format_ARGB32);
        for (int y = 0; y < h; ++y) {
            auto *o = reinterpret_cast<QRgb *>(out.scanLine(y));
            for (int x = 0; x < w; ++x) {
                int r = 0, g = 0, b = 0, n = 0;
                for (int dy = -1; dy <= 1; ++dy) {
                    const int yy = std::clamp(y + dy, 0, h - 1);
                    const auto *line = reinterpret_cast<const QRgb *>(img.constScanLine(yy));
                    for (int dx = -1; dx <= 1; ++dx) {
                        const QRgb c = line[std::clamp(x + dx, 0, w - 1)];
                        r += qRed(c); g += qGreen(c); b += qBlue(c); ++n;
                    }
                }
                o[x] = qRgb(r / n, g / n, b / n);
            }
        }
        img = out;
    }
    auto ramp = [](double t, double edge) { return t < edge ? t / edge : t > 1 - edge ? (1 - t) / edge : 1.0; };
    QImage res(w, h, QImage::Format_ARGB32_Premultiplied);
    for (int y = 0; y < h; ++y) {
        const auto *in = reinterpret_cast<const QRgb *>(img.constScanLine(y));
        auto *o = reinterpret_cast<QRgb *>(res.scanLine(y));
        const double ay = ramp((y + 0.5) / h, 0.3);
        for (int x = 0; x < w; ++x) {
            const double a = std::clamp(ay * ramp((x + 0.5) / w, 0.12), 0.0, 1.0);
            o[x] = qPremultiply(qRgba(qRed(in[x]), qGreen(in[x]), qBlue(in[x]), int(a * 255)));
        }
    }
    return res;
}

/// Раскодировать и масштабировать картинку, положить в кэш.
QImage decodeImage(const QByteArray &bytes, QSize want, bool glow, const QString &key) {
    if (bytes.isEmpty()) return {};
    QBuffer buf;
    buf.setData(bytes);
    QImageReader r(&buf);
    r.setAutoTransform(true);
    const QSize full = r.size();
    if (glow && full.isValid()) r.setScaledSize(full.scaled(GLOW_W, GLOW_W * 4, Qt::KeepAspectRatio));
    else if (full.isValid() && full.width() > 0 && full.height() > 0 && (want.width() > 0 || want.height() > 0)) {
        // задана одна сторона (баннер: только ширина) — вторая по пропорциям. Раньше вторая бралась от полного
        // кадра, и KeepAspectRatioByExpanding оставлял 4K-кадр как есть (33 МБ в памяти вместо 8)
        QSize s;
        if (want.width() > 0 && want.height() > 0) s = full.scaled(want, Qt::KeepAspectRatioByExpanding);
        else if (want.width() > 0) s = QSize(want.width(), std::max(1, int(qint64(full.height()) * want.width() / full.width())));
        else s = QSize(std::max(1, int(qint64(full.width()) * want.height() / full.height())), want.height());
        if (s.width() < full.width() && s.height() < full.height()) r.setScaledSize(s); // только уменьшать
    } else if (full.isValid() && std::max(full.width(), full.height()) > MAX_SIDE) {
        r.setScaledSize(full.scaled(MAX_SIDE, MAX_SIDE, Qt::KeepAspectRatio));
    }
    QImage img = r.read();
    if (glow && !img.isNull()) img = glowOf(img);
    if (!img.isNull()) PosterCache::instance().put(key, img);
    return img;
}

class Response : public QQuickImageResponse {
public:
    QQuickTextureFactory *textureFactory() const override { return QQuickTextureFactory::textureFactoryForImage(m_image); }
    QString errorString() const override { return m_error; }

    /// Готовая картинка из кэша: finished() — следующим ходом главного потока (QML ещё не подписался).
    void ready(const QImage &img) {
        m_image = img;
        QPointer<Response> self(this);
        QMetaObject::invokeMethod(qApp, [self] { if (self) emit self->finished(); }, Qt::QueuedConnection);
    }

    /// Готовая картинка из потока пула — в главный поток. guard проверяется только там: QPointer не потокобезопасен,
    /// а QML может отменить и удалить запрос (плитка ушла) прямо во время раскодирования.
    static void finishLater(const QPointer<Response> &guard, const QImage &img) {
        QMetaObject::invokeMethod(qApp, [guard, img] {
            if (!guard) return;
            guard->m_image = img;
            if (img.isNull()) guard->m_error = QStringLiteral("bad image");
            emit guard->finished();
        }, Qt::QueuedConnection);
    }

    /// Раскодировать в пуле потоков (когда байты скачаны из сети в главном потоке).
    void decodeAsync(const QByteArray &bytes, QSize want, bool glow, const QString &key) {
        if (bytes.isEmpty()) { m_error = QStringLiteral("no image"); emit finished(); return; }
        QPointer<Response> self(this);
        QThreadPool::globalInstance()->start([self, bytes, want, glow, key] {
            finishLater(self, decodeImage(bytes, want, glow, key));
        });
    }

    QImage m_image;
    QString m_error;
};

} // namespace

QQuickImageResponse *ImageProvider::requestImageResponse(const QString &id, const QSize &requestedSize) {
    auto *resp = new Response;
    QPointer<Response> guard(resp);
    QString path = id;
    bool glow = false;
    if (path.startsWith(QLatin1String("glow:"))) { glow = true; path = path.mid(5); }
    if (!path.startsWith(QLatin1Char('/'))) path.prepend(QLatin1Char('/'));
    const QString key = QStringLiteral("%1#%2x%3%4").arg(path).arg(requestedSize.width()).arg(requestedSize.height())
                            .arg(glow ? QStringLiteral("g") : QString());
    if (auto img = PosterCache::instance().get(key)) {
        resp->ready(*img);
        return resp;
    }
    // Уже на диске — читается и раскодируется сразу здесь же, в потоке пула (без переключений в главный поток).
    // Нет — Library (объект главного потока) скачает.
    QThreadPool::globalInstance()->start([this, guard, path, requestedSize, glow, key] {
        QFile f(Library::posterFile(path));
        if (f.open(QIODevice::ReadOnly)) {
            const QByteArray b = f.readAll();
            f.close();
            f.setFileTime(QDateTime::currentDateTime(), QFileDevice::FileModificationTime); // показан — недавно нужен
            if (!b.isEmpty()) {
                Response::finishLater(guard, decodeImage(b, requestedSize, glow, key));
                return;
            }
        }
        QMetaObject::invokeMethod(&m_lib, [this, guard, path, requestedSize, glow, key] {
            m_lib.image(path, [guard, requestedSize, glow, key](const QByteArray &bytes) {
                if (guard) guard->decodeAsync(bytes, requestedSize, glow, key);
            });
        }, Qt::QueuedConnection);
    });
    return resp;
}

} // namespace suslik::app
