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
    /// Окно развёрнуто на весь экран (не полный экран плеера).
    Q_PROPERTY(bool maximized READ maximized NOTIFY changed)
    /// Развёрнутое окно Windows заходит за край экрана: на столько опускается содержимое (заголовок, страницы).
    Q_PROPERTY(qreal topInset READ topInset NOTIFY changed)
    /// Размер окна меняет QML (края TitleBar.qml): окно без системной рамки вне Windows.
    Q_PROPERTY(bool edgeResize READ edgeResize NOTIFY changed)
    /// Кнопки окна видны (в плеере гаснут вместе с панелями): только тогда кнопка «Развернуть» — системная (Snap Layouts).
    Q_PROPERTY(bool captionButtons READ captionButtons WRITE setCaptionButtons NOTIFY captionButtonsChanged)
    /// Windows 11: курсор или нажатие на «Развернуть» (её события идут системе — показать панель макетов Snap Layouts).
    Q_PROPERTY(bool maxHovered READ maxHovered NOTIFY maxButtonChanged)
    Q_PROPERTY(bool maxPressed READ maxPressed NOTIFY maxButtonChanged)

public:
    explicit WindowMode(QObject *parent = nullptr);
    ~WindowMode() override;
    /// Окно приложения: системная полоса заголовка убирается (её место — TitleBar.qml), тёмная тема рамки.
    void setWindow(QWindow *w);
    /// Windows: мини-плеер при растягивании за край держит пропорции фильма (WM_SIZING), без чёрных полос;
    /// обычное окно — верхний край тянет размер (WM_NCHITTEST), полоса заголовка своя.
    bool nativeEventFilter(const QByteArray &type, void *message, qintptr *result) override;

protected:
    /// HWND окна запоминается при создании его платформенной части и забывается при уничтожении (смена флагов).
    bool eventFilter(QObject *obj, QEvent *ev) override;

public:

    bool fullscreen() const { return m_full; }
    bool mini() const { return m_mini; }
    bool onTop() const { return m_onTop; }
    bool maximized() const { return m_maximized; }
    qreal topInset() const { return m_topInset; }
    bool edgeResize() const;
    bool captionButtons() const { return m_captionButtons; }
    void setCaptionButtons(bool on) { if (on != m_captionButtons) { m_captionButtons = on; emit captionButtonsChanged(); } }
    bool maxHovered() const { return m_maxHover; }
    bool maxPressed() const { return m_maxPress; }

    Q_INVOKABLE void setFullscreen(bool on);
    Q_INVOKABLE void toggleFullscreen() { if (m_mini) setMini(false); else setFullscreen(!m_full); }
    /// aspect — ширина к высоте фильма.
    Q_INVOKABLE void setMini(bool on, double aspect = 16.0 / 9.0);
    Q_INVOKABLE void setOnTop(bool on);
    /// Мини-плеер: подогнать высоту под новый формат фильма.
    Q_INVOKABLE void fitMini(double aspect);
    /// Мини-плеер: тянуть окно мышью (системное перемещение) и менять размер с края.
    /// grabY — где схвачен заголовок (y в окне): развёрнутое окно сначала восстанавливается под курсором, потом едет.
    Q_INVOKABLE void startMove(qreal grabY = -1);
    Q_INVOKABLE void startResize(int edges);
    /// Мини-плеер: растягивание за угол своими силами — размер всегда по пропорциям фильма, противоположный угол
    /// стоит на месте. beginResize — при нажатии, resizeStep — при каждом движении мыши (позиция курсора — экранная).
    Q_INVOKABLE void beginResize(int edges);
    Q_INVOKABLE void resizeStep();
    /// Кнопку отпустили: окно плавно доезжает до последнего размера.
    Q_INVOKABLE void endResize();
    /// Плеер закрыт: обычное окно (без полного экрана и мини).
    Q_INVOKABLE void reset();
    /// Свернуть окно в панель задач (развёрнутое остаётся развёрнутым при возврате).
    Q_INVOKABLE void minimize();
    /// Кнопка «Развернуть / Восстановить» и двойной щелчок по заголовку.
    Q_INVOKABLE void toggleMaximized();
    /// Правая кнопка по заголовку: системное меню окна (x, y — в окне).
    Q_INVOKABLE void systemMenu(qreal x, qreal y);

signals:
    void changed();
    void captionButtonsChanged(); // отдельно от changed: TitleBar.visible зависит от mini/fullscreen, а сам пишет captionButtons — общий сигнал давал Binding loop
    void maxButtonChanged();

private:
    void frameless(bool on);
    void updateState();
    void scheduleState();
    void setMaxButton(bool hover, bool press);
    void fly(const QRect &to);
    void roundCorners(bool on);
    void applyResize();
    /// Windows: смена рамки окна на время перерисовки прячется (DWM cloak), иначе на миг видна белая
    /// «классическая» рамка. change — сразу, after — когда окно снова видно (кадр без рамки уже нарисован).
    void cloakWhile(const std::function<void()> &change, const std::function<void()> &after = {});
    int m_cloakGen = 0;

    QPointer<QWindow> m_w;
    quintptr m_hwnd = 0;   ///< HWND окна (0 — ещё не создано): фильтр сообщений сверяет с ним, без winId() на каждое
    bool m_full = false, m_mini = false, m_onTop = false, m_topBeforeMini = false;
    bool m_maximized = false;
    bool m_captionButtons = true, m_maxHover = false, m_maxPress = false;
    QTimer *m_stateTimer = nullptr;
    qreal m_topInset = 0;
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
