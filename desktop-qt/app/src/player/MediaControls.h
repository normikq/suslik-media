#pragma once
#include <QObject>
#include <memory>

class QWindow;

/// Системные медиа-кнопки Windows (SMTC, MediaControls.kt): плеер виден в окне громкости и Media Flyout, работают
/// клавиши мультимедиа. Название, состояние, полоса времени, «следующая/предыдущая», картинка. C++/WinRT,
/// ISystemMediaTransportControlsInterop::GetForWindow; кнопки приходят из потоков WinRT и передаются в окно.
namespace suslik::app {

class MediaControls : public QObject {
    Q_OBJECT
public:
    explicit MediaControls(QObject *parent = nullptr);
    ~MediaControls() override;

    void attach(QWindow *w);
    void setMetadata(const QString &title, const QString &subtitle);
    /// Картинка (файл JPEG/PNG); ставится, только когда сменилась.
    void setThumbnail(const QString &file);
    void setPlaying(bool playing);
    void setTimeline(qint64 posMs, qint64 lenMs);
    void setCapabilities(bool next, bool prev);
    /// Плеер закрыт: статус Stopped, всё убрать, кнопки выключить.
    void stop();

Q_SIGNALS:
    void togglePause();
    void next();
    void previous();
    void seek(qint64 ms);

private:
    struct Impl;
    std::unique_ptr<Impl> d;
};

} // namespace suslik::app
