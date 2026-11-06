#pragma once
#include <QString>
#include <QStringList>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <utility>
#include <vector>

/// «Экономная загрузка»: маленький HTTP-сервер на 127.0.0.1 между плеером и зеркалом. Перенос android/core/.../core/BurstProxy.kt.
///
/// Плеер читает поток понемногу всё время, и сеть не отдыхает весь фильм. Здесь фильм качается рывками: сколько сеть
/// даст, до ~1,5–2 минут вперёд (в файле-кольце на диске), потом соединение закрывается до тех пор, пока запас не
/// подойдёт к концу. Плеер читает с диска и ничего не замечает. Важнее на ПК: плеер говорит только с localhost, оборванное
/// или зависшее соединение с зеркалом его не держит (загрузчик переподключается), озвучка и видео читаются с диска
/// с одной скоростью.
///
/// Перемотка дешёвая: запрос далеко от скачанного открывает новое «окно» в том месте (короткие пробы — индекс MKV
/// в конце файла — без рывков). Диск на фильм ограничен: все его окна и озвучки вместе не больше capBytes (trimToBudget).
///
/// Потоки: сервер, сторож зависаний и по потоку на соединение плеера и на загрузчик окна (как пул Kotlin-версии).
/// Сокеты и файлы трогает только их поток, отмена — флагами.
namespace suslik::core {

/// Сбой запроса к зеркалу (IOException Kotlin-версии).
struct IoError : std::runtime_error {
    enum class Kind {
        Io,      ///< обычный сбой: повтор с паузой
        Timeout, ///< таймаут соединения: повтор почти сразу
        Offline  ///< сети нет (адрес не найден, соединение отклонено): не расходует попытки
    };
    Kind kind;
    explicit IoError(const QString &message, Kind k = Kind::Io) : std::runtime_error(message.toStdString()), kind(k) {}
    QString message() const { return QString::fromStdString(what()); }
};

/// Отменяет запрос, который ещё соединяется или ждёт ответа (зависшее рукопожатие TLS). Потокобезопасна.
class Cancel {
public:
    /// Что прерывает запрос (их может быть несколько); если отмена уже была — вызывается сразу.
    void attach(std::function<void()> action);
    void cancel();
    bool cancelled() const;
private:
    mutable std::mutex m_mutex;
    std::vector<std::function<void()>> m_actions;
    bool m_cancelled = false;
};

/// Ответ зеркала. code(), header(), read() и close() — из потока, открывшего запрос; abort() — из любого (сторож, смена сети).
class UpstreamResponse {
public:
    virtual ~UpstreamResponse() = default;
    virtual int code() const = 0;
    /// По имени, без учёта регистра; пусто, если заголовка нет.
    virtual std::optional<QString> header(const QString &name) const = 0;
    /// До max байт тела; -1 — тело кончилось. Сбой или abort() — IoError.
    virtual qint64 read(char *buf, qint64 max) = 0;
    /// Ответ больше не нужен (дочитанный — соединение остаётся для следующего запроса). Поток-владелец.
    virtual void close() = 0;
    /// Прервать передачу: ждущий read() выходит с IoError. Любой поток.
    virtual void abort() = 0;
};

/// Как качается диапазон: байты from..to (включительно; to < 0 — до конца файла). Сбой — IoError.
using Upstream = std::function<std::shared_ptr<UpstreamResponse>(const QString &url, qint64 from, qint64 to, Cancel &cancel)>;

class BurstProxy {
public:
    BurstProxy();
    /// Останавливает сервер и загрузчики, удаляет файлы окон.
    ~BurstProxy();
    BurstProxy(const BurstProxy &) = delete;
    BurstProxy &operator=(const BurstProxy &) = delete;

    // ---- настройки (можно менять на ходу) ----
    /// Сколько диска может занять фильм (все окна и озвучки), из «Настроек»; 0 — выключено (плеер читает напрямую).
    void setCapBytes(qint64 v);
    qint64 capBytes() const;
    /// Свежий подписанный адрес того же файла (блокирующий запрос к серверу; пусто — не вышло). Для истёкшей подписи.
    void setRefreshUrl(std::function<std::optional<QString>(const QString &)> f);
    /// Сбросить соединения в пуле (они от сети, которой нет, или зависли).
    void setResetConnections(std::function<void()> f);
    /// Запрос без ответа / без данных дольше этого отменяется и повторяется на новом соединении.
    void setStallLimits(qint64 headerStallMs, qint64 bodyStallMs);
    /// Журнал (журнал плеера).
    void setLog(std::function<void(const QString &)> f);
    /// Сверх бюджета окно, прочитанное за столько мс до последнего чтения фильма, ещё остаётся (тесты: 0).
    void setRecentMs(qint64 ms);
    /// Каждая порция, скачанная с зеркала (счётчик трафика приложения).
    void setOnBytes(std::function<void(qint64)> f);

    /// Один раз: папка запаса (остатки прошлого запуска удаляются) и как качать.
    void init(const QString &directory, Upstream up);
    /// Папка задана (плеер открывался): её файлы заняты, см. dropRetired.
    bool initialized() const;

    /// Адрес для плеера: локальный прокси или сам url, когда прокси нельзя (не https, мало места, сервер не поднялся).
    QString wrap(const QString &url);
    /// Файлы открываемого фильма (видео первым, потом озвучки и субтитры): все прочие потоки уходят сразу.
    /// Пустой список (плеер закрыт): скачанное остаётся на диске до открытия другого фильма; хранится один закрытый.
    void keepOnly(const QStringList &urls);
    /// Плеер закрыт: скачанное хранится до другого фильма (см. keepOnly).
    void clear() { keepOnly({}); }
    /// Закрытые фильмы, чей файл keep больше не принимает (ушли из медиатеки): удаляются. Сколько.
    int forgetRetired(const std::function<bool(const QString &link)> &keep);
    /// «Очистить кэш»: все закрытые фильмы уходят (играющий остаётся). Сколько.
    int dropRetired() { return forgetRetired([](const QString &) { return false; }); }
    /// Байт на диске в папке запаса (пустая — своя).
    qint64 diskBytes(const QString &directory = {}) const;
    /// Всё выбросить (чистый перезапуск, выход).
    void dropAll();

    /// Сеть сменилась (Wi-Fi ↔ мобильная, VPN): запросы в пути и соединения пула — от старой; всё повторить сразу.
    void networkChanged();
    /// Сети нет совсем: запросы останавливаются до её возвращения.
    void networkLost();
    bool isNetworkOnline() const;
    /// Экран выключен / приложение скрыто: ничего не качается до onScreenOn.
    void onScreenOff();
    void onScreenOn();

    /// Плеер знает длину фильма: средний битрейт (размер ÷ длина) задаёт рывки с первой секунды.
    void setDuration(const QString &url, qint64 ms);
    /// Сколько вперёд качает играющая озвучка/субтитры (second) и когда начинает снова (first): ~2 мин её самой
    /// по её битрейту — total байт на длину фильма filmMs; длина неизвестна — 4 МБ пробы. См. BurstProxy.kt sideReserve.
    static std::pair<qint64, qint64> sideReserve(qint64 total, qint64 filmMs, qint64 cap);
    /// Фильм читается через запас прямо сейчас.
    bool isActive(const QString &url) const;
    /// МБ скачано впереди плеера в читаемом окне; пусто — не через запас.
    std::optional<double> aheadMb(const QString &url) const;
    /// Мс фильма, скачанные впереди места чтения.
    qint64 aheadMs(const QString &url, qint64 durationMs) const;
    /// Средний битрейт фильма, Мбит/с; пусто, пока длина не известна.
    std::optional<double> bitrateMbit(const QString &url) const;
    /// МБ, скачанные с зеркала с запуска.
    double downloadedMb() const;
    qint64 downloaded() const;
    /// Кусок потока прямо с зеркала, мимо окон: байты from … from+len−1 и размер файла (−1 — неизвестен). До 7 с.
    std::optional<std::pair<QByteArray, qint64>> readRange(const QString &url, qint64 from, int len);
    /// Сеть даёт меньше, чем нужно фильму (с запасом 15 %).
    bool slowerThanFilm(const QString &url) const;
    /// Сколько МБ копить в паузе «копим запас»: ~30 с фильма, не больше половины запаса.
    double catchUpTargetMb(const QString &url) const;
    /// Секунд фильма, хранящихся сразу позади места чтения.
    double keptBehindSec(const QString &url) const;
    /// Копить запас, не отдавая плееру больше (не дольше ms; 0 — отпустить).
    void hold(const QString &url, qint64 ms);
    /// Состояние запаса одной строкой (отладочная сборка).
    QString snapshot() const;

    struct Impl;
private:
    std::shared_ptr<Impl> d;
};

} // namespace suslik::core
