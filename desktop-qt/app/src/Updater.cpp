#include "Updater.h"
#include "AppDir.h"
#include "Settings.h"

#include "suslik/core/ApiClient.h"
#include "suslik/core/Changelog.h"
#include "suslik/core/Safety.h"
#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"
#include "suslik/core/Versions.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QPointer>
#include <QProcess>
#include <QRegularExpression>
#include <QTimer>

#ifdef Q_OS_WIN
#include <windows.h>
#include <msi.h>
#endif

namespace suslik::app {

using namespace suslik::core;

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
QString tx(const QString &key, const QHash<QString, QString> &p = {}) {
    QString s = Strings::get(key);
    for (auto it = p.cbegin(); it != p.cend(); ++it) s.replace(QLatin1Char('{') + it.key() + QLatin1Char('}'), it.value());
    return s;
}
Store &prefs() { return Store::of(S("updates")); }
Store &seen() { return Store::of(S("whatsnew")); }

#ifdef Q_OS_WIN
/// UpgradeCode установщика Qt-версии (свой; Kotlin-версию при установке убирает установщик, см. docs/DESKTOP.md §8).
#ifdef SM_DIAG
constexpr const wchar_t *kUpgradeCode = L"{F79D44DC-CEC2-4619-824F-32180E6B7698}";
#else
constexpr const wchar_t *kUpgradeCode = L"{BFBB56B9-7E7F-4AF8-BA0B-C1BA1A054E70}";
#endif
#endif

qint64 folderBytes(const QString &path) {
    qint64 n = 0;
    QDir d(path);
    for (const QFileInfo &f : d.entryInfoList(QDir::Files | QDir::Dirs | QDir::NoDotAndDotDot | QDir::Hidden))
        n += f.isDir() ? folderBytes(f.filePath()) : f.size();
    return n;
}
} // namespace

Updater::Updater(ApiClient &api, const QString &current, QObject *parent) : QObject(parent), m_api(api), m_current(current) {
    QTimer::singleShot(2000, this, [this] { registerUninstaller(); });
}

QString Updater::uninstaller() {
#if defined(Q_OS_WIN) && !defined(SM_DIAG)
    // Только установленная копия: папка из архива сборки (без MSI) в «Установленные приложения» не попадает.
    wchar_t product[39] = {};
    if (MsiEnumRelatedProductsW(kUpgradeCode, 0, 0, product) != ERROR_SUCCESS) return {};
    const QString f = QDir(QCoreApplication::applicationDirPath()).filePath(S("SuslikUninstall.exe"));
    return QFile::exists(f) ? f : QString();
#else
    return {};
#endif
}

void Updater::registerUninstaller() {
    const QString exe = uninstaller();
    if (exe.isEmpty() || m_current == S("0")) return;
    const QString key = m_current + QLatin1Char('|') + QDir::toNativeSeparators(exe);
    if (Store::of(S("setup")).get(S("registered")) == key) return;
    auto *p = new QProcess(this);
    connect(p, &QProcess::finished, this, [p, key](int code, QProcess::ExitStatus status) {
        if (status == QProcess::NormalExit && code == 0) Store::of(S("setup")).put(S("registered"), key);
        p->deleteLater();
    });
    connect(p, &QProcess::errorOccurred, p, [p](QProcess::ProcessError e) { if (e == QProcess::FailedToStart) p->deleteLater(); });
    p->start(exe, {S("/register"), m_current});
}

QVariantMap Updater::available() const {
    if (!m_available) return {};
    return {{S("version"), m_available->version}, {S("size"), m_available->size}, {S("beta"), m_available->beta},
            {S("replace"), m_available->replace}};
}

void Updater::setAvailable(const std::optional<Info> &info) {
    m_available = info;
    emit availableChanged();
}

QString Updater::tempDir() { return QDir::temp().filePath(S("SuslikMediaQt-update")); }
qint64 Updater::tempBytes() { return folderBytes(tempDir()); }
void Updater::cleanup() { QDir(tempDir()).removeRecursively(); }

void Updater::latest(std::function<void(std::optional<Info>)> done) {
    // С 3.0.0 Qt-версия заменяет Kotlin-версию: та же платформа «desktop» на сервере, установщик там один
    // (desktop-qt.yml выкладывает «Suslik Media Setup-<V>.exe»; старая версия обновляется им же и удаляется, Work.cs).
    m_api.get(S("/api/app/update?platform=desktop"), [this, done](const ApiResult &r) {
        if (!r.ok() || !r.json.value(S("available")).toBool()) { done(std::nullopt); return; }
        Info i;
        i.version = r.json.value(S("version")).toString();
        i.size = qint64(r.json.value(S("size")).toDouble());
        i.notes = r.json.value(S("notes")).toString();
        i.url = r.json.value(S("url")).toString();
        i.file = r.json.value(S("file")).toString();
        i.beta = r.json.value(S("channel")).toString() == S("beta");
        i.sha256 = r.json.value(S("sha256")).toString().toLower();
        i.replace = !PlaybackSettings::betaChannel() && m_current != S("0") && Versions::compare(i.version, m_current) < 0;
        done(i);
    });
}

void Updater::check() {
#ifdef SM_DIAG
    return; // отладочная сборка — отдельное приложение, ставится с ПК
#endif
    cleanup();
    QPointer<Updater> self(this);
    latest([self](std::optional<Info> info) {
        if (!self || !info) return;
        prefs().put(S("checkedAt"), QString::number(QDateTime::currentMSecsSinceEpoch()));
        if (self->m_current == S("0")) return; // запуск без версии: не предлагать
        const auto v = UpdateChannel::verdict(info->version, self->m_current, !PlaybackSettings::betaChannel());
        if (!UpdateChannel::offers(v)) return;
        if (prefs().get(S("skipped")) == info->version) return;
        self->setAvailable(info);
    });
}

bool Updater::needsReinstall(const QString &remote) const {
    // Windows Installer сравнивает только три первых числа: 2.0.0.1 для него то же, что 2.0.0.
    const auto msi = [](const QString &v) {
        QStringList parts;
        auto it = QRegularExpression(S("\\d+")).globalMatch(v);
        while (it.hasNext() && parts.size() < 3) parts << it.next().captured();
        return parts.join(QLatin1Char('.'));
    };
    return !Versions::isNewer(msi(remote), msi(m_current));
}

void Updater::install() {
    if (!m_available || m_progress >= 0) return;
    const Info info = *m_available;
    m_error.clear();
    m_progress = 0;
    emit stateChanged();

    cleanup();
    QDir().mkpath(tempDir());
    QString ext = info.file.section(QLatin1Char('.'), -1).toLower();
    if (ext != S("msi") && ext != S("exe")) ext = S("msi");
    const QString path = QDir(tempDir()).filePath(S("Suslik-Media-%1.%2").arg(info.version, ext));
    auto *file = new QFile(path, this);
    if (!file->open(QIODevice::WriteOnly)) {
        delete file;
        m_progress = -1;
        m_error = tx(S("update.fayl_povrezhden"));
        emit stateChanged();
        return;
    }
    QNetworkRequest req = m_api.request(info.url);
    req.setTransferTimeout(30 * 60 * 1000);
    QNetworkReply *reply = m_api.network()->get(req);
    auto hash = std::make_shared<QCryptographicHash>(QCryptographicHash::Sha256);
    auto got = std::make_shared<qint64>(0);
    connect(reply, &QNetworkReply::readyRead, this, [reply, file, hash, got] {
        const QByteArray chunk = reply->readAll();
        file->write(chunk);
        hash->addData(chunk);
        *got += chunk.size();
    });
    connect(reply, &QNetworkReply::downloadProgress, this, [this, info](qint64 done, qint64 total) {
        if (total <= 0) total = info.size;
        const double p = total > 0 ? std::floor(double(done) * 100 / double(total)) / 100 : 0;
        if (p != m_progress) { m_progress = p; emit stateChanged(); }
    });
    connect(reply, &QNetworkReply::finished, this, [this, reply, file, hash, got, info, path] {
        reply->deleteLater();
        const QByteArray rest = reply->readAll();
        file->write(rest);
        hash->addData(rest);
        *got += rest.size();
        file->close();
        file->deleteLater();
        const int code = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const qint64 total = reply->header(QNetworkRequest::ContentLengthHeader).isValid()
                                 ? reply->header(QNetworkRequest::ContentLengthHeader).toLongLong() : info.size;
        QString err;
        if (reply->error() != QNetworkReply::NoError && code == 0) err = reply->errorString();
        else if (code != 200) err = tx(S("update.server_ne_otdal_fayl"), {{S("p"), QString::number(code)}});
        // Обрезанный, битый или подменённый файл не должен дойти до msiexec: размер и SHA-256 обязаны совпасть.
        // Без суммы от сервера не ставим вовсе (сервер отдаёт её с 2.x, routes/updates.js).
        else if ((total > 0 && *got != total) || !Safety::sha256Matches(info.sha256, QString::fromLatin1(hash->result().toHex())))
            err = tx(S("update.fayl_povrezhden"));
        if (!err.isEmpty()) {
            QFile::remove(path);
            m_progress = -1;
            m_error = err;
            emit stateChanged();
            return;
        }
        m_progress = 1;
        emit stateChanged();
        installAndExit(path, info.replace || needsReinstall(info.version));
    });
}

void Updater::installAndExit(const QString &file, bool replace) {
#ifdef Q_OS_WIN
    const auto q = [](QString v) { return v.replace(QLatin1Char('\''), S("''")); };
    const QString exe = QDir::toNativeSeparators(QCoreApplication::applicationFilePath());
    const QString dir = QDir::toNativeSeparators(QCoreApplication::applicationDirPath());
    QString script;
    if (file.endsWith(S(".msi"), Qt::CaseInsensitive)) {
        script += S("Wait-Process -Id %1 -Timeout 30 -ErrorAction SilentlyContinue\n").arg(QCoreApplication::applicationPid());
        // Ниже по числам (или равную по первым трём) Windows Installer поверх не ставит: сначала удалить установленную копию
        // этого же приложения — по его UpgradeCode, старую версию на Kotlin это не трогает. Данные в %APPDATA% остаются.
        if (replace) {
            wchar_t product[39] = {};
            for (DWORD i = 0; MsiEnumRelatedProductsW(kUpgradeCode, 0, i, product) == ERROR_SUCCESS; ++i)
                script += S("Start-Process msiexec -ArgumentList '/x %1 /passive /norestart' -Wait\n").arg(QString::fromWCharArray(product));
        }
        // Своя запись в «Установленных приложениях» у окна удаления (SuslikUninstall.exe): запись самого MSI прячем.
        const QString arp = uninstaller().isEmpty() ? QString() : S(" ARPSYSTEMCOMPONENT=1");
        script += S("$p = Start-Process msiexec -ArgumentList '/i %1 /passive /norestart INSTALLDIR=%2%3' -Wait -PassThru\n")
                      .arg(q(Safety::quoteWindowsArg(QDir::toNativeSeparators(file), true)), q(Safety::quoteWindowsArg(dir, true)), arp);
        script += S("if ($p.ExitCode -eq 0 -or $p.ExitCode -eq 3010) { Start-Process '%1' }\n").arg(q(exe));
        // В base64 (UTF-16LE): командной строке тогда не нужны кавычки.
        const QByteArray utf16(reinterpret_cast<const char *>(script.utf16()), script.size() * 2);
        QProcess::startDetached(S("powershell"), {S("-NoProfile"), S("-ExecutionPolicy"), S("Bypass"), S("-WindowStyle"), S("Hidden"),
                                                   S("-EncodedCommand"), QString::fromLatin1(utf16.toBase64())});
    } else {
        QProcess::startDetached(file, {S("/update")});
    }
    QTimer::singleShot(400, qApp, [] { QCoreApplication::quit(); });
#else
    Q_UNUSED(file)
    Q_UNUSED(replace)
    m_progress = -1;
    m_error = S("Только для Windows");
    emit stateChanged();
#endif
}

void Updater::loadWhatsNew() {
    if (m_current == S("0") || seen().get(S("version")) == m_current) return;
    QFile f(S(":/res/changelog.json"));
    const QList<Changelog::Release> list = f.open(QIODevice::ReadOnly) ? Changelog::upTo(Changelog::parse(f.readAll()), m_current)
                                                                        : QList<Changelog::Release>();
    // Нечего рассказать (мелкая правка без своих заметок, новейшие уже показывали).
    if (!Changelog::shouldShow(list, seen().get(S("version")))) { seen().put(S("version"), m_current); return; }
    m_whatsNew.clear();
    for (const auto &r : list) m_whatsNew << QVariantMap{{S("version"), r.version}, {S("title"), r.title}, {S("items"), r.items}};
    emit whatsNewChanged();
}

void Updater::whatsNewSeen() {
    seen().put(S("version"), m_current);
    m_whatsNew.clear();
    emit whatsNewChanged();
}

void Updater::clearCachesOnUpdate(const QString &current) {
    QFile marker(AppDir::file(S("cache_version.txt")));
    if (current == S("0")) return;
    if (marker.open(QIODevice::ReadOnly) && QString::fromUtf8(marker.readAll()).trimmed() == current) return;
    marker.close();
    QDir(reserveDir()).removeRecursively();
    QDir(AppDir::file(S("posters"))).removeRecursively();
    QDir(AppDir::file(S("smtc"))).removeRecursively();
    for (const char *n : {"posters", "poster_anim", "media_info", "library_etag"})
        for (const char *ext : {".properties", ".properties.bak"}) QFile::remove(AppDir::file(S(n) + S(ext)));
    QFile::remove(AppDir::file(S("library.json")));
    cleanup();
    if (marker.open(QIODevice::WriteOnly)) marker.write(current.toUtf8());
}

} // namespace suslik::app
