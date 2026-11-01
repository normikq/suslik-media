#include "Ui.h"
#include "TitleBar.h"
#include "AppDir.h"

#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"

#include <QClipboard>
#include <QDesktopServices>
#include <QDir>
#include <QGuiApplication>
#include <QLocale>
#include <QUrl>
#include <QWindow>
#include <QTimer>
#include <QQmlApplicationEngine>
#include <QPixmapCache>

#ifdef Q_OS_WIN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <psapi.h>
#endif

namespace suslik::app {

using namespace suslik::core;

bool Ui::diag() const {
#ifdef SM_DIAG
    return true;
#else
    return false;
#endif
}

QString Ui::appDir() const { return QDir::toNativeSeparators(AppDir::dir()); }

QString Ui::tx(const QString &key, const QVariantMap &params) const {
    QString s = Strings::get(key);
    for (auto it = params.cbegin(); it != params.cend(); ++it)
        s.replace(QLatin1Char('{') + it.key() + QLatin1Char('}'), it.value().toString());
    return s;
}

QString Ui::size(qint64 bytes) const {
    static const QLocale ru(QLocale::Russian);
    const double gb = double(bytes) / (1LL << 30);
    if (gb >= 1) return ru.toString(gb, 'f', 1) + QStringLiteral(" ГБ");
    const double mb = double(bytes) / (1LL << 20);
    if (mb >= 1) return ru.toString(mb, 'f', 0) + QStringLiteral(" МБ");
    return ru.toString(double(bytes) / 1024, 'f', 0) + QStringLiteral(" КБ");
}

void Ui::openUrl(const QString &url) const { QDesktopServices::openUrl(QUrl(url)); }
void Ui::copy(const QString &text) const { QGuiApplication::clipboard()->setText(text); }
QString Ui::paste() const { return QGuiApplication::clipboard()->text(); }

QString Ui::setting(const QString &key, const QString &def) const {
    return Store::of(QStringLiteral("ui")).get(key).value_or(def);
}
void Ui::setSetting(const QString &key, const QString &value) const { Store::of(QStringLiteral("ui")).put(key, value); }

void Ui::raise(QWindow *w) const {
    if (!w) return;
    if (w->windowStates() & Qt::WindowMinimized) w->setWindowStates(w->windowStates() & ~Qt::WindowMinimized);
    w->show();
    w->raise();
    w->requestActivate();
}

void Ui::styleTitleBar(QWindow *w) const { TitleBar::style(w); }

void Ui::noteImageBytes(qint64 bytes) {
    m_imageBytes += bytes;
    if (m_imageBytes >= IMAGE_TRIM_BYTES) trimMemory();
}

void Ui::trimMemory() {
    m_imageBytes = 0;
    if (m_engine) {
        m_engine->collectGarbage();
    }
    QPixmapCache::clear();
#ifdef Q_OS_WIN
    QTimer::singleShot(250, [] {
        EmptyWorkingSet(GetCurrentProcess());
    });
#endif
}

} // namespace suslik::app
