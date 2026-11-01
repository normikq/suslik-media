#include "TrackerSettingsModel.h"
#include "RuTrackerClient.h"
#include "../player/PlayerLog.h"

#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"
#include "suslik/core/Trackers.h"

#include <QDir>
#include <QFile>
#include <QPointer>
#include <QTemporaryDir>
#include <QTimer>

namespace suslik::app {

using namespace suslik::core;

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }

/// cookies.sqlite Firefox: копия (запущенный Firefox держит файл) и её журнал WAL, затем поиск cookie в байтах.
std::optional<QString> firefoxCookies(const QString &file, QString *error) {
    if (!QFile::exists(file)) { *error = S("Файл cookies.sqlite не найден"); return std::nullopt; }
    QTemporaryDir tmp;
    QByteArray bytes;
    const QString copy = tmp.filePath(S("cookies.sqlite"));
    if (tmp.isValid() && QFile::copy(file, copy)) {
        QFile f(copy);
        if (f.open(QIODevice::ReadOnly)) bytes = f.readAll();
        const QString wal = file + S("-wal");
        if (QFile::exists(wal) && QFile::copy(wal, copy + S("-wal"))) {
            QFile w(copy + S("-wal"));
            if (w.open(QIODevice::ReadOnly)) bytes += w.readAll();
        }
    } else {
        QFile f(file);
        if (f.open(QIODevice::ReadOnly)) bytes = f.readAll();
    }
    return RuTracker::extractFirefoxCookies(bytes, S("rutracker"), error);
}
} // namespace

TrackerSettingsModel::TrackerSettingsModel(QObject *parent) : QObject(parent) {
    m_rtEnabled = RuTracker::enabled();
    m_rtUrl = RuTracker::url();
    m_rtCookie = RuTracker::cookie();
}

void TrackerSettingsModel::setRtEnabled(bool on) {
    m_rtEnabled = on;
    TrackerSettings::setEnabled(S("rutracker"), on);
    emit changed();
}

void TrackerSettingsModel::setRtCookie(const QString &c) {
    m_rtCookie = c;
    RuTracker::setCookieSource({});
    emit changed();
}

QVariantList TrackerSettingsModel::browsers() const {
    QVariantList out;
    for (const auto &b : m_browsers) out << b.title();
    return out;
}

QString TrackerSettingsModel::browserName() const {
    if (m_selected < 0 || m_selected >= m_browsers.size()) return tx(S("trackers.browser_fallback"));
    return Browsers::displayName(m_browsers[m_selected].type);
}

bool TrackerSettingsModel::selectedChromium() const {
    return m_selected >= 0 && m_selected < m_browsers.size() && Browsers::isChromium(m_browsers[m_selected].type);
}

bool TrackerSettingsModel::selectedFirefox() const {
    return m_selected >= 0 && m_selected < m_browsers.size() && m_browsers[m_selected].type == BrowserType::FIREFOX;
}

QVariantList TrackerSettingsModel::sources() const {
    QVariantList out;
    for (const auto &s : TrackerSettings::sources())
        out << QVariantMap{{S("id"), s.id}, {S("name"), s.name}, {S("tag"), tx(s.tagKey)}, {S("desc"), tx(s.descKey)},
                           {S("on"), TrackerSettings::isEnabled(s.id)}};
    return out;
}

int TrackerSettingsModel::enabledCount() const { return TrackerSettings::enabledCount(); }
int TrackerSettingsModel::sourceCount() const { return int(TrackerSettings::sources().size()); }

void TrackerSettingsModel::scan() {
    QPointer<TrackerSettingsModel> self(this);
    QTimer::singleShot(0, this, [self] {
        if (!self) return;
        self->m_browsers = detectBrowsers();
        self->m_scanned = true;
        const auto source = Browsers::fromKey(RuTracker::cookieSource());
        const BrowserType pref = BrowserBridge::preferredType();
        int pick = -1;
        for (int i = 0; source && i < self->m_browsers.size() && pick < 0; ++i)
            if (self->m_browsers[i].type == *source) pick = i;
        for (int i = 0; i < self->m_browsers.size() && pick < 0; ++i)
            if (self->m_browsers[i].type == pref) pick = i;
        if (pick < 0 && !self->m_browsers.isEmpty()) pick = 0;
        self->m_selected = pick;
        emit self->changed();
    });
}

void TrackerSettingsModel::captured(BrowserType t, const QString &cookie) {
    m_rtCookie = cookie;
    m_rtEnabled = true;
    RuTracker::setCookie(cookie);
    RuTracker::setEnabled(true);
    RuTracker::setCookieSource(Browsers::key(t));
    Store::flushAll();
    m_browserStatus = tx(S("trackers.cookies_saved"), {{"browser", Browsers::displayName(t)}});
    m_browserStatusKind = 1;
}

void TrackerSettingsModel::capture() {
    if (m_busy || m_selected < 0 || m_selected >= m_browsers.size()) return;
    const DetectedBrowser target = m_browsers[m_selected];
    const QString name = Browsers::displayName(target.type);
    PlayerLog::write(S("[trackers] TrackerSettingsModel::capture() selected=%1, browser=%2").arg(m_selected).arg(name));
    m_busy = true;
    m_browserStatus.clear();
    emit changed();
    QPointer<TrackerSettingsModel> self(this);
    if (Browsers::isChromium(target.type) && BrowserBridge::isAvailable(target.type)) {
        BrowserBridge::instance().loginAndCapture(target.type, m_rtUrl, this,
            [self](const QString &msg) {
                if (!self) return;
                self->m_browserStatus = msg;
                self->m_browserStatusKind = 2;
                emit self->changed();
            },
            [self, target, name](bool ok, const QString &result) {
                if (!self) return;
                PlayerLog::write(S("[trackers] TrackerSettingsModel::capture result: ok=%1, %2").arg(ok)
                                     .arg(ok ? S("cookie %1 chars").arg(result.size()) : result)); // cookie в журнал не пишем
                self->m_busy = false;
                if (ok) {
                    BrowserBridge::setPreferredType(target.type);
                    self->captured(target.type, result);
                } else {
                    self->m_browserStatus = result.isEmpty() ? tx(S("trackers.cookies_save_failed"), {{"browser", name}}) : result;
                    self->m_browserStatusKind = 0;
                }
                emit self->changed();
            });
        return;
    }
    if (target.type == BrowserType::FIREFOX) {
        QString err;
        const auto c = firefoxCookies(target.cookieFile, &err);
        m_busy = false;
        if (c) captured(target.type, *c);
        else {
            m_browserStatus = err.isEmpty() ? tx(S("trackers.cookies_extract_failed")) : err;
            m_browserStatusKind = 0;
        }
        emit changed();
        return;
    }
    // Chromium без программы: сессия, уже сохранённая в своём профиле
    BrowserBridge::instance().existingCookies(target.type, S("https://rutracker.org"), this, [self, target, name](std::optional<QString> c) {
        if (!self) return;
        self->m_busy = false;
        if (c && !c->trimmed().isEmpty()) self->captured(target.type, *c);
        else {
            self->m_browserStatus = S("В профиле %1 нет активной сессии: нажмите «🔑 Войти через %1» выше").arg(name);
            self->m_browserStatusKind = 0;
        }
        emit self->changed();
    });
}

void TrackerSettingsModel::paste(const QString &clip) {
    const QString c = clip.trimmed();
    if (c.isEmpty()) return;
    m_rtCookie = c;
    m_rtEnabled = true;
    RuTracker::setCookie(c);
    RuTracker::setEnabled(true);
    RuTracker::setCookieSource({});
    Store::flushAll();
    emit changed();
}

void TrackerSettingsModel::saveAll() {
    RuTracker::setEnabled(m_rtEnabled);
    RuTracker::setUrl(m_rtUrl);
    RuTracker::setCookie(m_rtCookie);
    Store::flushAll();
}

void TrackerSettingsModel::test() {
    if (m_testing) return;
    saveAll();
    m_testing = true;
    m_testStatus.clear();
    emit changed();
    QPointer<TrackerSettingsModel> self(this);
    rutracker::testConnection(this, [self](bool ok, const QString &msg) {
        if (!self) return;
        self->m_testing = false;
        self->m_testOk = ok;
        self->m_testStatus = msg;
        self->m_rtCookie = RuTracker::cookie(); // браузер мог обновить cookie
        emit self->changed();
    });
}

void TrackerSettingsModel::toggleSource(const QString &id) {
    const bool next = !TrackerSettings::isEnabled(id);
    TrackerSettings::setEnabled(id, next);
    if (id == S("rutracker")) m_rtEnabled = next;
    emit changed();
}

void TrackerSettingsModel::enableAll() {
    TrackerSettings::setAll(true);
    m_rtEnabled = true;
    emit changed();
}

void TrackerSettingsModel::onlyRussian() {
    TrackerSettings::setOnlyRussian();
    m_rtEnabled = true;
    emit changed();
}

void TrackerSettingsModel::close() {
    saveAll();
    BrowserBridge::instance().stopAsync();
}

} // namespace suslik::app
