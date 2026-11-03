#pragma once
#include <QEventLoop>
#include <QObject>
#include <QTimer>

/// Блокирующее ожидание поверх событий Qt. Потоки BurstProxy пишутся «сверху вниз», как в Kotlin-версии, а сокеты
/// и запросы Qt живут событиями: waitFor крутит события своего потока до сигнала или до таймаута. На Windows это
/// надёжнее waitForReadyRead/waitForBytesWritten (документация Qt: «may fail randomly on Windows»).
namespace suslik::core::detail {

/// До одного из сигналов sigs объекта o или до ms. Работает и в потоках, запущенных не через QThread:
/// QEventLoop заводит им диспетчер событий.
template <class Obj, class... Sig>
void waitFor(Obj *o, int ms, Sig... sigs) {
    QEventLoop loop;
    QTimer timer;
    timer.setSingleShot(true);
    QObject::connect(&timer, &QTimer::timeout, &loop, &QEventLoop::quit);
    (QObject::connect(o, sigs, &loop, &QEventLoop::quit), ...);
    timer.start(ms);
    loop.exec();
}

/// Диспетчер событий потоку — до создания в нём сокетов (иначе QSocketNotifier не работает).
/// Работает сам конструктор QEventLoop: он вызывает QThreadData::ensureEventDispatcher() (qeventloop.cpp),
/// exec() для этого не нужен.
inline void ensureDispatcher() { QEventLoop warm; }

} // namespace suslik::core::detail
