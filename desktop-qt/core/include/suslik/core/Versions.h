#pragma once
#include <QString>

/// Версии вида «1.9.5» / «2.0.0.1» (до четырёх чисел, прочий текст не учитывается) и канал обновлений.
/// Перенос android/core/.../core/Versions.kt.
namespace suslik::core {

namespace Versions {
bool isNewer(const QString &remote, const QString &local);
int compare(const QString &a, const QString &b);
} // namespace Versions

/// «Канал обновлений»: на релизном канале текущий релиз всегда побеждает (заменяет любую бету),
/// на бета-канале предлагается только более новая версия.
namespace UpdateChannel {
enum class Verdict { Offer, Replace, Installed, Older, Nothing };

/// remote пустой — проверить не удалось.
Verdict verdict(const QString &remote, const QString &installed, bool release);
bool offers(Verdict v);
QString message(Verdict v, const QString &remote, const QString &installed, bool beta);
} // namespace UpdateChannel

} // namespace suslik::core
