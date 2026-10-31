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

namespace suslik::app {

namespace {

class Response : public QQuickImageResponse {
public:
    QQuickTextureFactory *textureFactory() const override { return QQuickTextureFactory::textureFactoryForImage(m_image); }
    QString errorString() const override { return m_error; }

    /// Раскодировать в пуле потоков (большие кадры баннера не тормозят интерфейс), потом finished() в главном.
    void decode(const QByteArray &bytes, QSize want) {
        if (bytes.isEmpty()) { m_error = QStringLiteral("no image"); emit finished(); return; }
        QPointer<Response> self(this);
        QThreadPool::globalInstance()->start([self, bytes, want] {
            QBuffer buf;
            buf.setData(bytes);
            QImageReader r(&buf);
            r.setAutoTransform(true);
            const QSize full = r.size();
            if (want.isValid() && full.isValid() && (full.width() > want.width() || full.height() > want.height())) {
                QSize s = full.scaled(want.width() > 0 ? want.width() : full.width(), want.height() > 0 ? want.height() : full.height(),
                                      Qt::KeepAspectRatioByExpanding);
                if (s.width() > full.width() || s.height() > full.height()) s = full;
                r.setScaledSize(s);
            }
            QImage img = r.read();
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
    if (!path.startsWith(QLatin1Char('/'))) path.prepend(QLatin1Char('/'));
    // Library — объект главного потока: просим там.
    QMetaObject::invokeMethod(&m_lib, [this, guard, path, requestedSize] {
        m_lib.image(path, [guard, requestedSize](const QByteArray &bytes) {
            if (guard) guard->decode(bytes, requestedSize);
        });
    }, Qt::QueuedConnection);
    return resp;
}

} // namespace suslik::app
