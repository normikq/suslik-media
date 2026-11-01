#include "ImageProvider.h"
#include "Library.h"

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

constexpr int GLOW_W = 320;

inline double smoothstep(double edge0, double edge1, double x) {
    const double t = std::clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

inline double rampSmooth(double t, double edge) {
    if (t < edge) return smoothstep(0.0, edge, t);
    if (t > 1.0 - edge) return smoothstep(1.0, 1.0 - edge, t);
    return 1.0;
}

/// Мягкое свечение баннера: края плавно тают в фон (сверху/снизу 30 %, по бокам 12 %) и размываются 3 проходами
QImage glowOf(QImage src) {
    const int w = src.width(), h = src.height();
    if (w <= 0 || h <= 0) return src;

    src = src.convertToFormat(QImage::Format_ARGB32);
    QImage cur(w, h, QImage::Format_ARGB32_Premultiplied);
    for (int y = 0; y < h; ++y) {
        const auto *in = reinterpret_cast<const QRgb *>(src.constScanLine(y));
        auto *o = reinterpret_cast<QRgb *>(cur.scanLine(y));
        const double ay = rampSmooth((y + 0.5) / h, 0.30);
        for (int x = 0; x < w; ++x) {
            const double a = std::clamp(ay * rampSmooth((x + 0.5) / w, 0.12), 0.0, 1.0);
            o[x] = qPremultiply(qRgba(qRed(in[x]), qGreen(in[x]), qBlue(in[x]), int(a * 255)));
        }
    }

    constexpr int RAD = 4;
    constexpr int DIAM = 2 * RAD + 1;
    QImage tmp(w, h, QImage::Format_ARGB32_Premultiplied);

    for (int pass = 0; pass < 3; ++pass) {
        // Горизонтальный проход
        for (int y = 0; y < h; ++y) {
            const auto *inLine = reinterpret_cast<const QRgb *>(cur.constScanLine(y));
            auto *outLine = reinterpret_cast<QRgb *>(tmp.scanLine(y));
            int sumA = 0, sumR = 0, sumG = 0, sumB = 0;
            for (int dx = -RAD; dx <= RAD; ++dx) {
                const int xx = std::clamp(dx, 0, w - 1);
                const QRgb c = inLine[xx];
                sumA += qAlpha(c); sumR += qRed(c); sumG += qGreen(c); sumB += qBlue(c);
            }
            outLine[0] = qRgba(sumR / DIAM, sumG / DIAM, sumB / DIAM, sumA / DIAM);
            for (int x = 1; x < w; ++x) {
                const int addX = std::clamp(x + RAD, 0, w - 1);
                const int subX = std::clamp(x - RAD - 1, 0, w - 1);
                const QRgb cAdd = inLine[addX];
                const QRgb cSub = inLine[subX];
                sumA += qAlpha(cAdd) - qAlpha(cSub);
                sumR += qRed(cAdd) - qRed(cSub);
                sumG += qGreen(cAdd) - qGreen(cSub);
                sumB += qBlue(cAdd) - qBlue(cSub);
                outLine[x] = qRgba(sumR / DIAM, sumG / DIAM, sumB / DIAM, sumA / DIAM);
            }
        }
        // Вертикальный проход
        for (int x = 0; x < w; ++x) {
            int sumA = 0, sumR = 0, sumG = 0, sumB = 0;
            for (int dy = -RAD; dy <= RAD; ++dy) {
                const int yy = std::clamp(dy, 0, h - 1);
                const QRgb c = reinterpret_cast<const QRgb *>(tmp.constScanLine(yy))[x];
                sumA += qAlpha(c); sumR += qRed(c); sumG += qGreen(c); sumB += qBlue(c);
            }
            reinterpret_cast<QRgb *>(cur.scanLine(0))[x] = qRgba(sumR / DIAM, sumG / DIAM, sumB / DIAM, sumA / DIAM);
            for (int y = 1; y < h; ++y) {
                const int addY = std::clamp(y + RAD, 0, h - 1);
                const int subY = std::clamp(y - RAD - 1, 0, h - 1);
                const QRgb cAdd = reinterpret_cast<const QRgb *>(tmp.constScanLine(addY))[x];
                const QRgb cSub = reinterpret_cast<const QRgb *>(tmp.constScanLine(subY))[x];
                sumA += qAlpha(cAdd) - qAlpha(cSub);
                sumR += qRed(cAdd) - qRed(cSub);
                sumG += qGreen(cAdd) - qGreen(cSub);
                sumB += qBlue(cAdd) - qBlue(cSub);
                reinterpret_cast<QRgb *>(cur.scanLine(y))[x] = qRgba(sumR / DIAM, sumG / DIAM, sumB / DIAM, sumA / DIAM);
            }
        }
    }
    return cur;
}

class Response : public QQuickImageResponse {
public:
    QQuickTextureFactory *textureFactory() const override { return QQuickTextureFactory::textureFactoryForImage(m_image); }
    QString errorString() const override { return m_error; }

    /// Раскодировать в пуле потоков (большие кадры баннера не тормозят интерфейс), потом finished() в главном.
    /// glow — свечение баннера: размытая копия с прозрачными краями (растягивается в QML, размытие даром).
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
            else if (full.isValid() && full.width() > 0 && full.height() > 0 && (want.width() > 0 || want.height() > 0)) {
                QSize s;
                if (want.width() > 0 && want.height() > 0) s = full.scaled(want, Qt::KeepAspectRatioByExpanding);
                else if (want.width() > 0) s = QSize(want.width(), std::max(1, int(qint64(full.height()) * want.width() / full.width())));
                else s = QSize(std::max(1, int(qint64(full.width()) * want.height() / full.height())), want.height());
                if (s.width() < full.width() && s.height() < full.height()) r.setScaledSize(s); // только уменьшать
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
    // Уже на диске — читается здесь же, в потоке пула (не в главном: чтение файла не тормозит интерфейс).
    // Нет — Library (объект главного потока) скачает.
    QThreadPool::globalInstance()->start([this, guard, path, requestedSize, glow] {
        QFile f(Library::posterFile(path));
        if (f.open(QIODevice::ReadOnly)) {
            const QByteArray b = f.readAll();
            f.close();
            f.setFileTime(QDateTime::currentDateTime(), QFileDevice::FileModificationTime); // показан — недавно нужен
            if (!b.isEmpty()) {
                QMetaObject::invokeMethod(qApp, [guard, b, requestedSize, glow] { if (guard) guard->decode(b, requestedSize, glow); },
                                          Qt::QueuedConnection);
                return;
            }
        }
        QMetaObject::invokeMethod(&m_lib, [this, guard, path, requestedSize, glow] {
            m_lib.image(path, [guard, requestedSize, glow](const QByteArray &bytes) {
                if (guard) guard->decode(bytes, requestedSize, glow);
            });
        }, Qt::QueuedConnection);
    });
    return resp;
}

} // namespace suslik::app