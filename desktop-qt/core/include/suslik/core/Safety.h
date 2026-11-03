#pragma once
#include <QList>
#include <QString>

/// Проверки перед запуском чужого кода и внешних процессов: сумма скачанного файла, аргументы командной строки Windows.
namespace suslik::core::Safety {

/// Ожидаемая сумма — ровно 64 шестнадцатеричных знака и совпадает с посчитанной (регистр не важен).
/// Пустая или кривая ожидаемая сумма — «не совпала»: без эталона файл не запускаем и не загружаем.
bool sha256Matches(const QString &expected, const QString &actualHex);

/// Один аргумент командной строки Windows по правилам CommandLineToArgvW (ими же читают msiexec и Chromium).
/// Обратные слэши перед кавычкой и в конце удваиваются: иначе «"C:\Папка\"» съедает закрывающую кавычку
/// и склеивает все следующие аргументы. always = false — без кавычек, если в аргументе нет пробелов и кавычек.
QString quoteWindowsArg(const QString &arg, bool always = false);

/// Не больше max событий за windowMs (скользящее окно). Запросы участников комнаты к RuTracker на этом ПК.
class RateLimit {
public:
    RateLimit(int max, qint64 windowMs) : m_max(max), m_window(windowMs) {}
    /// Можно ли ещё одно событие в момент now; да — оно засчитывается.
    bool allow(qint64 now);

private:
    int m_max;
    qint64 m_window;
    QList<qint64> m_stamps;
};

} // namespace suslik::core::Safety
