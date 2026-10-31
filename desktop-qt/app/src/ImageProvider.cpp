#include "ImageProvider.h"
#include "Library.h"

#include <QBuffer>
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

class Response : public QQuickImageResponse {
public:
    QQuickTextureFactory *textureFactory() const override { return QQuickTextureFactory::textureFactoryForImage(m_image); }
    QString errorString() const override { return m_error; }

    /// Раскодировать в пуле потоков (большие кадры баннера не тормозят интерфейс), потом finished() в главном.
    /// glow — свечение баннера: маленькая размытая копия с прозрачными краями (растягивается в QML, размытие даром).
    void decode(const QByteArray &bytes, QSize want, bool glow) {
        if (bytes.isEmpty()) { m_error = QStringLiteral("no image"); emit finished(); return; }
        QPointer<Response> self(this);
        QThreadPool::globalInstance()->start([self, bytes, want, glow] {
            QBuffer buf;
            buf.setData(bytes);
            QImageReader r(&buf);
            r.setAutoTransform(true);
            const QSize full = r.size();
            if (glow && full.isValid()) r.setScaledSize(full.scaled(GLOW_W, GLOW_W * 4, Qt::KeepAspectRatio));
            else if (want.isValid() && full.isValid() && (full.width() > want.width() || full.height() > want.height())) {
                QSize s = full.scaled(want.width() > 0 ? want.width() : full.width(), want.height() > 0 ? want.height() : full.height(),
                                      Qt::KeepAspectRatioByExpanding);
                if (s.width() > full.width() || s.height() > full.height()) s = full;
                r.setScaledSize(s);
            }
            QImage img = r.read();
            if (glow && !img.isNull()) img = glowOf(img);
            QMetaObject::invokeMethod(qApp, [self, img] {
                if (!self) return;
                self->m_image = img;
                if (img.isNull()) self->m_error = QStringLiteral("bad image");
                emit self->finished();
            }, Qt::QueuedConnection);
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
    // Library — объект главного потока: просим там.
    QMetaObject::invokeMethod(&m_lib, [this, guard, path, requestedSize, glow] {
        m_lib.image(path, [guard, requestedSize, glow](const QByteArray &bytes) {
            if (guard) guard->decode(bytes, requestedSize, glow);
        });
    }, Qt::QueuedConnection);
    return resp;
}

} // namespace suslik::app
