#pragma once
#include <QAbstractNativeEventFilter>
#include <QObject>
#include <QRect>
#include <QString>

class QWindow;

/// Окно-оверлей поверх игры (Windows): прозрачное, всегда сверху, без кнопки на панели задач.
/// В обычном режиме пропускает клики и не берёт фокус (игра остаётся активной); в режиме калибровки — принимает мышь.
namespace suslik::cinema {

class Overlay : public QObject, public QAbstractNativeEventFilter {
    Q_OBJECT
    Q_PROPERTY(bool calibrating READ calibrating WRITE setCalibrating NOTIFY calibratingChanged)
    Q_PROPERTY(bool gameFound READ gameFound NOTIFY gameChanged)

public:
    /// gameExe — процесс игры, за клиентской областью окна которого ходит оверлей (пусто — весь основной экран).
    explicit Overlay(QString gameExe, QObject *parent = nullptr);
    /// Окно сцены (после загрузки QML): стили оверлея и слежение за окном игры.
    void attach(QWindow *window);
    ~Overlay() override;

    bool calibrating() const { return m_calibrating; }
    void setCalibrating(bool on);
    bool gameFound() const { return m_gameFound; }

    /// Глобальные клавиши каркаса (фокус у игры): Ctrl+Alt+C калибровка, Ctrl+Alt+P пауза, Ctrl+Alt+H скрыть,
    /// Ctrl+Alt+Q выход.
    void registerHotkeys();

    bool nativeEventFilter(const QByteArray &type, void *message, qintptr *result) override;

signals:
    void calibratingChanged();
    void gameChanged();
    void hotkey(const QString &name);

private:
    void applyStyles();
    void followGame();

    QWindow *m_window = nullptr;
    QString m_gameExe;
    bool m_calibrating = false;
    bool m_gameFound = false;
    QRect m_lastRect;
    bool m_hotkeys = false;
};

} // namespace suslik::cinema
