#pragma once
#include <QQuickAsyncImageProvider>
#include <functional>

namespace suslik::app {
class Library;

/// image://suslik/<путь сервера> — постеры, кадры и логотипы с диска или сервера (Library::image).
/// Картинка уменьшается до запрошенного sourceSize при раскодировании: в памяти не держим больше, чем видно.
class ImageProvider : public QQuickAsyncImageProvider {
public:
    /// decoded — в главном потоке, с объёмом каждой раскодированной картинки (рубеж очистки памяти, Ui::noteImageBytes).
    ImageProvider(Library &lib, std::function<void(qint64)> decoded) : m_lib(lib), m_decoded(std::move(decoded)) {}
    QQuickImageResponse *requestImageResponse(const QString &id, const QSize &requestedSize) override;
private:
    Library &m_lib;
    std::function<void(qint64)> m_decoded;
};

} // namespace suslik::app
