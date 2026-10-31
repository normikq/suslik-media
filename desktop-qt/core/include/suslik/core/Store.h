#pragma once
#include <QHash>
#include <QMutex>
#include <QString>
#include <QStringList>
#include <optional>

/// Маленькое хранилище «ключ — значение» на диске (постеры, прогресс, настройки, cookie): файл `<имя>.properties`
/// в формате java.util.Properties — тот же, что у Kotlin-версии, чтобы её данные можно было перенести.
/// Запись отложена (~400 мс) и атомарна: данные на диске раньше, чем у файла новое имя; прежний остаётся как .bak.
/// Испорченный файл (нули после выключения питания) — читается .bak. Одно хранилище на файл (of()).
namespace suslik::core {

class Store {
public:
    /// Папка данных: задаётся один раз при запуске (тесты — своя временная).
    static void setDirectory(const QString &dir);
    static QString directory();
    static Store &of(const QString &name);
    /// Записать всё отложенное (выход из приложения).
    static void flushAll();
    /// Забыть все открытые хранилища (тесты).
    static void resetForTests();

    std::optional<QString> get(const QString &key) const;
    void put(const QString &key, const std::optional<QString> &value);
    QStringList keys() const;
    void flush();

    // Формат Properties: разбор и запись (открыто для тестов).
    static QHash<QString, QString> parseProperties(const QByteArray &bytes);
    static QByteArray writeProperties(const QHash<QString, QString> &values);

    explicit Store(const QString &name);
    ~Store();

private:
    void scheduleSave();
    void load();

    QString m_file;
    QString m_bak;
    mutable QMutex m_lock;
    QMutex m_fileLock;
    QHash<QString, QString> m_values;
    bool m_pending = false;
};

} // namespace suslik::core
