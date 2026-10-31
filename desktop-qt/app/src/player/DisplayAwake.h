#pragma once

/// Экран не гаснет и компьютер не засыпает, пока идёт фильм (DisplayAwake.kt): запрос питания Windows
/// «Suslik Media: воспроизведение видео» (виден в powercfg /requests) и пульс SetThreadExecutionState раз в 30 с.
namespace suslik::app::DisplayAwake {

void set(bool awake);

} // namespace suslik::app::DisplayAwake
