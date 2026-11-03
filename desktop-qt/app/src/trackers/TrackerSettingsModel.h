#pragma once
#include "BrowserBridge.h"
#include "suslik/core/Trackers.h"

#include <QObject>
#include <QVariantList>

/// Окно «Трекеры» (TrackerSettingsDialog.kt): вкладка «RuTracker (ПК)» — включение, адрес, cookie из браузера
/// (вход в окне браузера или чтение профиля Firefox), проверка входа; вкладка «Выбор трекеров» — какие источники
/// показывать в выдаче. Изменения пишутся сразу, окно закрывается — фоновый браузер закрывается.
namespace suslik::app {

class TrackerSettingsModel : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool rtEnabled READ rtEnabled WRITE setRtEnabled NOTIFY changed)
    Q_PROPERTY(bool rtParty READ rtParty WRITE setRtParty NOTIFY changed)
    Q_PROPERTY(QString rtUrl READ rtUrl WRITE setRtUrl NOTIFY changed)
    Q_PROPERTY(QString rtCookie READ rtCookie WRITE setRtCookie NOTIFY changed)
    Q_PROPERTY(QVariantList browsers READ browsers NOTIFY changed)
    Q_PROPERTY(bool scanned READ scanned NOTIFY changed)
    Q_PROPERTY(int selected READ selected WRITE setSelected NOTIFY changed)
    Q_PROPERTY(QString browserName READ browserName NOTIFY changed)
    Q_PROPERTY(bool selectedChromium READ selectedChromium NOTIFY changed)
    Q_PROPERTY(bool selectedFirefox READ selectedFirefox NOTIFY changed)
    Q_PROPERTY(bool busy READ busy NOTIFY changed)
    Q_PROPERTY(QString browserStatus READ browserStatus NOTIFY changed)
    Q_PROPERTY(int browserStatusKind READ browserStatusKind NOTIFY changed) ///< 1 — ок, 0 — ошибка, 2 — ход дела
    Q_PROPERTY(bool testing READ testing NOTIFY changed)
    Q_PROPERTY(QString testStatus READ testStatus NOTIFY changed)
    Q_PROPERTY(bool testOk READ testOk NOTIFY changed)
    Q_PROPERTY(QString activeBrowserName READ activeBrowserName NOTIFY changed)
    Q_PROPERTY(QVariantList sources READ sources NOTIFY changed)
    Q_PROPERTY(int enabledCount READ enabledCount NOTIFY changed)
    Q_PROPERTY(int sourceCount READ sourceCount CONSTANT)

public:
    explicit TrackerSettingsModel(QObject *parent = nullptr);

    bool rtEnabled() const { return m_rtEnabled; }
    void setRtEnabled(bool on);
    /// Участники «Вместе» могут искать на RuTracker через этот ПК (RuTracker::partyAllowed).
    bool rtParty() const { return core::RuTracker::partyAllowed(); }
    void setRtParty(bool on) { core::RuTracker::setPartyAllowed(on); emit changed(); }
    QString rtUrl() const { return m_rtUrl; }
    void setRtUrl(const QString &u) { m_rtUrl = u; emit changed(); }
    QString rtCookie() const { return m_rtCookie; }
    /// Ввод руками: источник cookie забывается.
    void setRtCookie(const QString &c);
    QVariantList browsers() const;
    bool scanned() const { return m_scanned; }
    int selected() const { return m_selected; }
    void setSelected(int i) { m_selected = i; m_browserStatus.clear(); emit changed(); }
    QString browserName() const;
    bool selectedChromium() const;
    bool selectedFirefox() const;
    bool busy() const { return m_busy; }
    QString browserStatus() const { return m_browserStatus; }
    int browserStatusKind() const { return m_browserStatusKind; }
    bool testing() const { return m_testing; }
    QString testStatus() const { return m_testStatus; }
    bool testOk() const { return m_testOk; }
    QString activeBrowserName() const { return BrowserBridge::activeBrowserName(); }
    QVariantList sources() const;
    int enabledCount() const;
    int sourceCount() const;

    /// Найти браузеры (окно открылось).
    Q_INVOKABLE void scan();
    /// «Войти через …» (Chromium: окно браузера) или «Взять из …» (Firefox: cookie из профиля).
    Q_INVOKABLE void capture();
    /// «Вставить» cookie из буфера обмена.
    Q_INVOKABLE void paste(const QString &clip);
    Q_INVOKABLE void test();
    Q_INVOKABLE void toggleSource(const QString &id);
    Q_INVOKABLE void enableAll();
    Q_INVOKABLE void onlyRussian();
    /// Сохранить и закрыть фоновый браузер.
    Q_INVOKABLE void close();

signals:
    void changed();

private:
    void saveAll();
    void captured(BrowserType t, const QString &cookie);

    bool m_rtEnabled = false;
    QString m_rtUrl, m_rtCookie;
    QList<DetectedBrowser> m_browsers;
    bool m_scanned = false;
    int m_selected = -1;
    bool m_busy = false;
    QString m_browserStatus;
    int m_browserStatusKind = 2;
    bool m_testing = false;
    QString m_testStatus;
    bool m_testOk = false;
};

} // namespace suslik::app
