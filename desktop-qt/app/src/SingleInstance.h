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
    /// true — эта копия единственная (работаем дальше); false — другая уже открыта и ей сказано показаться.
    bool acquire();
signals:
    /// Кто-то запустил приложение ещё раз: показать окно.
    void showRequested();
private:
    QLockFile *m_lock = nullptr;
    QLocalServer *m_server = nullptr;
};

} // namespace suslik::app
