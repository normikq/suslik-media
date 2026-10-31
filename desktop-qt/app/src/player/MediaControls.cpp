// C++/WinRT и ключевые слова Qt (signals, emit) не дружат: в этом файле — Q_SIGNALS/Q_EMIT.
#define QT_NO_KEYWORDS
#include "MediaControls.h"
#include "PlayerLog.h"

#include <QCoreApplication>
#include <QPointer>
#include <chrono>
#include <QWindow>

#if defined(Q_OS_WIN) && __has_include(<winrt/Windows.Media.h>)
#define SM_SMTC 1
#include <windows.h>
#include <systemmediatransportcontrolsinterop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/Windows.Storage.h>
#include <winrt/Windows.Storage.Streams.h>
#include <QThreadPool>
#endif

namespace suslik::app {

#ifdef SM_SMTC
namespace wm = winrt::Windows::Media;

struct MediaControls::Impl {
    wm::SystemMediaTransportControls smtc{nullptr};
    winrt::event_token buttonToken{}, seekToken{};
    QString title, subtitle, thumb;
    bool playing = false, next = false, prev = false;
    qint64 pos = 0, len = 0, lastTimelineSec = -1;
    int thumbGen = 0;
};

MediaControls::MediaControls(QObject *parent) : QObject(parent), d(std::make_unique<Impl>()) {}

MediaControls::~MediaControls() { stop(); }

void MediaControls::attach(QWindow *w) {
    if (d->smtc || !w) return;
    try {
        winrt::init_apartment(winrt::apartment_type::single_threaded);
    } catch (...) {} // уже STA (окно Qt)
    try {
        auto interop = winrt::get_activation_factory<wm::SystemMediaTransportControls, ISystemMediaTransportControlsInterop>();
        wm::SystemMediaTransportControls smtc{nullptr};
        winrt::check_hresult(interop->GetForWindow(reinterpret_cast<HWND>(w->winId()), winrt::guid_of<wm::SystemMediaTransportControls>(),
                                                   winrt::put_abi(smtc)));
        d->smtc = smtc;
        QPointer<MediaControls> self(this);
        d->buttonToken = smtc.ButtonPressed([self](const wm::SystemMediaTransportControls &, const wm::SystemMediaTransportControlsButtonPressedEventArgs &e) {
            const auto b = e.Button();
            QMetaObject::invokeMethod(qApp, [self, b] {
                if (!self) return;
                // Play и Pause — оба переключают: Flyout с одной кнопкой шлёт противоположное тому, что видел
                if (b == wm::SystemMediaTransportControlsButton::Play || b == wm::SystemMediaTransportControlsButton::Pause) Q_EMIT self->togglePause();
                else if (b == wm::SystemMediaTransportControlsButton::Next) Q_EMIT self->next();
                else if (b == wm::SystemMediaTransportControlsButton::Previous) Q_EMIT self->previous();
            }, Qt::QueuedConnection);
        });
        d->seekToken = smtc.PlaybackPositionChangeRequested([self](const wm::SystemMediaTransportControls &, const wm::PlaybackPositionChangeRequestedEventArgs &e) {
            const qint64 ms = std::chrono::duration_cast<std::chrono::milliseconds>(e.RequestedPlaybackPosition()).count();
            QMetaObject::invokeMethod(qApp, [self, ms] { if (self) Q_EMIT self->seek(ms); }, Qt::QueuedConnection);
        });
        PlayerLog::write(QStringLiteral("[smtc] attached"));
    } catch (const winrt::hresult_error &e) {
        PlayerLog::write(QStringLiteral("[smtc] attach failed: %1").arg(QString::fromWCharArray(e.message().c_str())));
        d->smtc = nullptr;
    }
}

void MediaControls::setMetadata(const QString &title, const QString &subtitle) {
    d->title = title;
    d->subtitle = subtitle;
    if (!d->smtc) return;
    try {
        d->smtc.IsEnabled(true);
        d->smtc.IsPlayEnabled(true);
        d->smtc.IsPauseEnabled(true);
        auto u = d->smtc.DisplayUpdater();
        u.Type(wm::MediaPlaybackType::Video);
        u.VideoProperties().Title(winrt::hstring(title.toStdWString()));
        u.VideoProperties().Subtitle(winrt::hstring(subtitle.toStdWString()));
        u.Update();
    } catch (...) {}
}

void MediaControls::setThumbnail(const QString &file) {
    if (!d->smtc || file.isEmpty() || file == d->thumb) return;
    d->thumb = file;
    const int gen = ++d->thumbGen;
    QPointer<MediaControls> self(this);
    // StorageFile открывается асинхронно: в фоне (там можно ждать), картинка ставится в окне.
    QThreadPool::globalInstance()->start([self, file, gen] {
        try {
            winrt::init_apartment(winrt::apartment_type::multi_threaded);
        } catch (...) {}
        try {
            auto f = winrt::Windows::Storage::StorageFile::GetFileFromPathAsync(winrt::hstring(file.toStdWString())).get();
            auto ref = winrt::Windows::Storage::Streams::RandomAccessStreamReference::CreateFromFile(f);
            QMetaObject::invokeMethod(qApp, [self, ref, gen, file] {
                if (!self || !self->d->smtc || self->d->thumbGen != gen) return;
                try {
                    auto u = self->d->smtc.DisplayUpdater();
                    u.Thumbnail(ref);
                    u.Update();
                    PlayerLog::write(QStringLiteral("[smtc] thumbnail %1 set").arg(file));
                } catch (...) {}
            }, Qt::QueuedConnection);
        } catch (const winrt::hresult_error &e) {
            PlayerLog::write(QStringLiteral("[smtc] thumbnail failed: %1").arg(QString::fromWCharArray(e.message().c_str())));
        }
    });
}

void MediaControls::setPlaying(bool playing) {
    d->playing = playing;
    if (!d->smtc) return;
    try {
        d->smtc.PlaybackStatus(playing ? wm::MediaPlaybackStatus::Playing : wm::MediaPlaybackStatus::Paused);
    } catch (...) {}
}

void MediaControls::setTimeline(qint64 posMs, qint64 lenMs) {
    if (!d->smtc || lenMs <= 0) return;
    try {
        wm::SystemMediaTransportControlsTimelineProperties t;
        t.StartTime(std::chrono::milliseconds(0));
        t.MinSeekTime(std::chrono::milliseconds(0));
        t.Position(std::chrono::milliseconds(posMs));
        t.MaxSeekTime(std::chrono::milliseconds(lenMs));
        t.EndTime(std::chrono::milliseconds(lenMs));
        d->smtc.UpdateTimelineProperties(t);
    } catch (...) {}
}

void MediaControls::setCapabilities(bool next, bool prev) {
    if (!d->smtc) return;
    try {
        d->smtc.IsNextEnabled(next);
        d->smtc.IsPreviousEnabled(prev);
    } catch (...) {}
}

void MediaControls::stop() {
    if (!d || !d->smtc) return;
    try {
        d->smtc.PlaybackStatus(wm::MediaPlaybackStatus::Stopped);
        d->smtc.DisplayUpdater().ClearAll();
        d->smtc.DisplayUpdater().Update();
        d->smtc.IsEnabled(false);
    } catch (...) {}
    d->thumb.clear();
}

#else

struct MediaControls::Impl {};
MediaControls::MediaControls(QObject *parent) : QObject(parent), d(std::make_unique<Impl>()) {}
MediaControls::~MediaControls() = default;
void MediaControls::attach(QWindow *) {}
void MediaControls::setMetadata(const QString &, const QString &) {}
void MediaControls::setThumbnail(const QString &) {}
void MediaControls::setPlaying(bool) {}
void MediaControls::setTimeline(qint64, qint64) {}
void MediaControls::setCapabilities(bool, bool) {}
void MediaControls::stop() {}

#endif

} // namespace suslik::app
