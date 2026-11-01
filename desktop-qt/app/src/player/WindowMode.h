#pragma once
#include <QAbstractNativeEventFilter>
#include <QElapsedTimer>
#include <QObject>
#include <QPointer>
#include <QRect>
#include <QSize>

#include <QWindow>
#include <functional>
class QVariantAnimation;
class QTimer;

/// Полный экран, мини-плеер и «поверх окон» (WindowMode.kt) средствами Qt, без Win32.
/// Полный экран — окно без рамки во весь монитор и на 1 пиксель выше него: Windows не переводит такое окно в
/// эксклюзивный режим (DirectFlip), и панели плеера поверх видео не мигают. Мини-плеер — окно без рамки в правом
/// нижнем углу, по пропорциям фильма, поверх окон; уезжает туда за 320 мс.
namespace suslik::app {

class WindowMode : public QObject, public QAbstractNativeEventFilter {
    Q_OBJECT
    Q_PROPERTY(bool fullscreen READ fullscreen NOTIFY changed)
    Q_PROPERTY(bool mini READ mini NOTIFY changed)
    Q_PROPERTY(bool onTop READ onTop NOTIFY changed)

public:
    explicit WindowMode(QObject *parent = nullptr);
    ~WindowMode() override;
    void setWindow(QWindow *w) { m_w = w; }
    /// Windows: мини-плеер при растягивании за край держит пропорции фильма (WM_SIZING), без чёрных полос.
    bool nativeEventFilter(const QByteArray &type, void *message, qintptr *result) override;

    bool fullscreen() const { return m_full; }
    bool mini() const { return m_mini; }
    bool onTop() const { return m_onTop; }

    Q_INVOKABLE void setFullscreen(bool on);
    Q_INVOKABLE void toggleFullscreen() { if (m_mini) setMini(false); else setFullscreen(!m_full); }
    /// aspect — ширина к высоте фильма.
    Q_INVOKABLE void setMini(bool on, double aspect = 16.0 / 9.0);
    Q_INVOKABLE void setOnTop(bool on);
    /// Мини-плеер: подогнать высоту под новый формат фильма.
    Q_INVOKABLE void fitMini(double aspect);
    /// Мини-плеер: тянуть окно мышью (системное перемещение) и менять размер с края.
    Q_INVOKABLE void startMove();
    Q_INVOKABLE void startResize(int edges);
    /// Мини-плеер: растягивание за угол своими силами — размер всегда по пропорциям фильма, противоположный угол
    /// стоит на месте. beginResize — при нажатии, resizeStep — при каждом движении мыши (позиция курсора — экранная).
    Q_INVOKABLE void beginResize(int edges);
    Q_INVOKABLE void resizeStep();
    /// Кнопку отпустили: окно плавно доезжает до последнего размера.
    Q_INVOKABLE void endResize();
    /// Плеер закрыт: обычное окно (без полного экрана и мини).
    Q_INVOKABLE void reset();

signals:
    void changed();

private:
    void frameless(bool on);
    void fly(const QRect &to);
    void roundCorners(bool on);
    void applyResize();
    /// Windows: смена рамки окна на время перерисовки прячется (DWM cloak), иначе на миг видна белая
    /// «классическая» рамка. change — сразу, after — когда окно снова видно (кадр без рамки уже нарисован).
    void cloakWhile(const std::function<void()> &change, const std::function<void()> &after = {});
    int m_cloakGen = 0;

    QPointer<QWindow> m_w;
    bool m_full = false, m_mini = false, m_onTop = false, m_topBeforeMini = false;
    QRect m_before;
    bool m_maximizedBefore = false;
    QSize m_minBefore;
    double m_aspect = 16.0 / 9.0;
    int m_resizeEdges = 0;
    QRect m_resizeFrom;
    QPoint m_resizeCursor;
    QVariantAnimation *m_anim = nullptr;
    QTimer *m_smooth = nullptr;
    QElapsedTimer m_resizeTimer;
    qint64 m_lastResizeMs = 0;
    double m_curW = 0, m_targetW = 0;
};

} // namespace suslik::app
