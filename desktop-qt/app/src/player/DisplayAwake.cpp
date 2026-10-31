#include "DisplayAwake.h"
#include "PlayerLog.h"

#include <QTimer>

#ifdef Q_OS_WIN
#include <windows.h>
#endif

namespace suslik::app::DisplayAwake {

#ifdef Q_OS_WIN
namespace {
HANDLE request = nullptr;
bool on = false;
QTimer *pulse = nullptr;

void ensureRequest() {
    if (request) return;
    REASON_CONTEXT ctx{};
    ctx.Version = POWER_REQUEST_CONTEXT_VERSION;
    ctx.Flags = POWER_REQUEST_CONTEXT_SIMPLE_STRING;
    static wchar_t reason[] = L"Suslik Media: воспроизведение видео";
    ctx.Reason.SimpleReasonString = reason;
    request = PowerCreateRequest(&ctx);
    if (request == INVALID_HANDLE_VALUE) request = nullptr;
}
} // namespace

void set(bool awake) {
    if (awake == on) return;
    on = awake;
    ensureRequest();
    if (awake) {
        if (request) {
            PowerSetRequest(request, PowerRequestDisplayRequired);
            PowerSetRequest(request, PowerRequestSystemRequired);
        }
        SetThreadExecutionState(ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED);
        if (!pulse) {
            pulse = new QTimer();
            pulse->setInterval(30000);
            QObject::connect(pulse, &QTimer::timeout, [] { SetThreadExecutionState(ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED); });
        }
        pulse->start();
    } else {
        if (request) {
            PowerClearRequest(request, PowerRequestDisplayRequired);
            PowerClearRequest(request, PowerRequestSystemRequired);
        }
        SetThreadExecutionState(ES_CONTINUOUS);
        if (pulse) pulse->stop();
    }
    PlayerLog::write(QStringLiteral("[power] display awake: %1").arg(awake ? QStringLiteral("ON") : QStringLiteral("OFF")));
}
#else
void set(bool) {}
#endif

} // namespace suslik::app::DisplayAwake
