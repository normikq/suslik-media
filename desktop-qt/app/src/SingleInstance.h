#pragma once
#include <QObject>

class QLocalServer;
class QLockFile;

/// Одна копия приложения: второй запуск не открывает второе окно, а просит работающее выйти вперёд и завершается.
/// Замок — файл в папке данных (отпускается системой и после сбоя), связь — локальный сокет (только этот компьютер).
namespace suslik::app {

class SingleInstance : public QObject {
    Q_OBJECT
public:
    explicit SingleInstance(QObject *parent = nullptr);
    ~SingleInstance() override;
    /// true — эта копия единственная (работаем дальше); false — другая уже открыта и ей сказано показаться
    /// (и передана ссылка [link] — suslik://room/…, если запуск был по ней).
    bool acquire(const QString &link = QString());
signals:
    /// Кто-то запустил приложение ещё раз: показать окно.
    void showRequested();
    /// Второй запуск по ссылке suslik://… (кнопка «Открыть в приложении» в Discord Activity).
    void linkRequested(const QString &link);
private:
    QLockFile *m_lock = nullptr;
    QLocalServer *m_server = nullptr;
};

} // namespace suslik::app
