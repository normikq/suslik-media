#pragma once
#include <QQuickAsyncImageProvider>

namespace suslik::app {
class Library;

/// image://suslik/<путь сервера> — постеры, кадры и логотипы с диска или сервера (Library::image).
/// Картинка уменьшается до запрошенного sourceSize при раскодировании: в памяти не держим больше, чем видно.
class ImageProvider : public QQuickAsyncImageProvider {
public:
    explicit ImageProvider(Library &lib) : m_lib(lib) {}
    QQuickImageResponse *requestImageResponse(const QString &id, const QSize &requestedSize) override;
private:
    Library &m_lib;
};

} // namespace suslik::app
