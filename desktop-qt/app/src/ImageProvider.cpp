#include "ImageProvider.h"
#include "Library.h"
#include "PosterCache.h"

#include <QBuffer>
#include <QDateTime>
#include <QFile>
#include <QCoreApplication>
#include <QImageReader>
#include <QMetaObject>
#include <QMutex>
#include <QQuickTextureFactory>
#include <QThreadPool>
#include <QRunnable>
#include <algorithm>
#include <memory>
#include <vector>

namespace suslik::app {

namespace {

constexpr int GLOW_W = 96;
/// Размер не задан — не больше этого по длинной стороне (кадр 4K не займёт десятки мегабайт).
constexpr int MAX_SIDE = 1920;

/// Размытие (три прохода «коробкой» 3×3) и края, тающие в фон: сверху и снизу по 30 %, по бокам по 12 % (как у прежней версии).
QImage glowOf(QImage src) {
    QImage img = src.convertToFormat(QImage::Format_ARGB32);
    const int w = img.width(), h = img.height();
    // «Коробка» 3×3 раскладывается на два прохода по 3: суммы по строке, затем по столбцу и одно деление на 9 —
    // результат тот же до бита, а работы втрое меньше (края — повтор крайнего пикселя, как раньше).
    std::vector<int> rows(size_t(w) * h * 3);
    for (int pass = 0; pass < 3; ++pass) {
        for (int y = 0; y < h; ++y) {
            const auto *line = reinterpret_cast<const QRgb *>(img.constScanLine(y));
            int *row = rows.data() + size_t(y) * w * 3;
            for (int x = 0; x < w; ++x) {
                const QRgb a = line[std::max(x - 1, 0)], c = line[x], b = line[std::min(x + 1, w - 1)];
                row[x * 3] = qRed(a) + qRed(c) + qRed(b);
                row[x * 3 + 1] = qGreen(a) + qGreen(c) + qGreen(b);
                row[x * 3 + 2] = qBlue(a) + qBlue(c) + qBlue(b);
            }
        }
        QImage out(w, h, QImage::Format_ARGB32);
        for (int y = 0; y < h; ++y) {
            const int *up = rows.data() + size_t(std::max(y - 1, 0)) * w * 3;
            const int *mid = rows.data() + size_t(y) * w * 3;
            const int *down = rows.data() + size_t(std::min(y + 1, h - 1)) * w * 3;
            auto *o = reinterpret_cast<QRgb *>(out.scanLine(y));
            for (int x = 0; x < w; ++x) {
                const int k = x * 3;
                o[x] = qRgb((up[k] + mid[k] + down[k]) / 9, (up[k + 1] + mid[k + 1] + down[k + 1]) / 9,
                            (up[k + 2] + mid[k + 2] + down[k + 2]) / 9);
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

/// Запрос живёт в потоке загрузчика картинок Qt Quick и может быть удалён им в любой момент (плитка ушла), а картинку
/// отдают поток пула или главный поток. Поэтому не QPointer (он не потокобезопасен): общее «жив ли» под мьютексом,
/// которое деструктор гасит под тем же мьютексом. finished() Qt разрешает слать из любого потока.
struct ResponseState {
    QMutex lock;
    bool alive = true;
};

class Response : public QQuickImageResponse {
public:
    struct Handle {
        std::shared_ptr<ResponseState> state;
        Response *response;
    };

    Response() : m_state(std::make_shared<ResponseState>()) {}
    ~Response() override {
        QMutexLocker l(&m_state->lock);
        m_state->alive = false;
    }

    QQuickTextureFactory *textureFactory() const override {
        QMutexLocker l(&m_state->lock);
        return QQuickTextureFactory::textureFactoryForImage(m_image);
    }
    QString errorString() const override {
        QMutexLocker l(&m_state->lock);
        return m_error;
    }

    Handle handle() { return {m_state, this}; }

    /// Отдать картинку, если запрос ещё жив (из любого потока).
    static void deliver(const Handle &h, const QImage &img, const QString &error = {}) {
        QMutexLocker l(&h.state->lock);
        if (!h.state->alive) return;
        h.response->m_image = img;
        h.response->m_error = !error.isEmpty() ? error : img.isNull() ? QStringLiteral("bad image") : QString();
        emit h.response->finished(); // получатели в потоке загрузчика: вызов уходит очередью, под мьютексом ничего не выполняется
    }

    /// Готовая картинка из кэша: finished() — следующим ходом (QML ещё не подписался).
    void ready(const QImage &img) {
        QMetaObject::invokeMethod(qApp, [h = handle(), img] { deliver(h, img); }, Qt::QueuedConnection);
    }

    /// Раскодировать в пуле потоков (когда байты скачаны из сети в главном потоке).
    static void decodeAsync(const Handle &h, const QByteArray &bytes, QSize want, bool glow, const QString &key) {
        if (bytes.isEmpty()) { deliver(h, {}, QStringLiteral("no image")); return; }
        QThreadPool::globalInstance()->start([h, bytes, want, glow, key] { deliver(h, decodeImage(bytes, want, glow, key)); });
    }

private:
    std::shared_ptr<ResponseState> m_state;
    QImage m_image;
    QString m_error;
};

} // namespace

QQuickImageResponse *ImageProvider::requestImageResponse(const QString &id, const QSize &requestedSize) {
    auto *resp = new Response;
    const Response::Handle h = resp->handle();
    QString path = id;
    bool glow = false, temp = false;
    if (path.startsWith(QLatin1String("tmp:"))) { temp = true; path = path.mid(4); }
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
    QThreadPool::globalInstance()->start([this, h, path, requestedSize, glow, key, temp] {
        QFile f(Library::posterFile(path));
        if (f.open(QIODevice::ReadOnly)) {
            const QByteArray b = f.readAll();
            f.close();
            f.setFileTime(QDateTime::currentDateTime(), QFileDevice::FileModificationTime); // показан — недавно нужен
            if (!b.isEmpty()) {
                Response::deliver(h, decodeImage(b, requestedSize, glow, key));
                return;
            }
        }
        QMetaObject::invokeMethod(&m_lib, [this, h, path, requestedSize, glow, key, temp] {
            m_lib.image(path, [h, requestedSize, glow, key](const QByteArray &bytes) {
                Response::decodeAsync(h, bytes, requestedSize, glow, key);
            }, !temp);
        }, Qt::QueuedConnection);
    });
    return resp;
}

} // namespace suslik::app
