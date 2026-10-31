#include "MpvInstall.h"
#include "Mpv.h"
#include "PlayerLog.h"
#include "../AppDir.h"

#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"

#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QNetworkAccessManager>
#include <QNetworkReply>

namespace suslik::app {

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
QString dir() { return QDir(AppDir::dir()).filePath(S("mpv")); }
} // namespace

MpvInstall::MpvInstall(const QString &site, QObject *parent) : QObject(parent) {
    QFile f(S(":/res/libmpv.properties"));
    const auto props = f.open(QIODevice::ReadOnly) ? core::Store::parseProperties(f.readAll()) : QHash<QString, QString>();
    const QString path = props.value(S("path"));
    if (!site.isEmpty() && !path.isEmpty()) m_url = site + path;
    m_sha = props.value(S("dllSha256")).toLower();
}

bool MpvInstall::ready() const { return mpv::api() != nullptr; }

QString MpvInstall::path() const { return ready() ? mpv::libraryFile() : QString(); }

void MpvInstall::start() {
    if (m_reply) return;
    if (ready() || m_url.isEmpty()) { emit finished(ready()); return; }
    QDir().mkpath(dir());
    m_error.clear();
    m_progress = 0;
    emit changed();
    PlayerLog::write(S("libmpv: downloading %1").arg(m_url));
    if (!m_nam) m_nam = new QNetworkAccessManager(this);
    m_part = new QFile(QDir(dir()).filePath(mpv::libraryName() + S(".part")), this);
    if (!m_part->open(QIODevice::WriteOnly)) { fail(core::tx(S("app.mpv_server_nedostupen")), S("cannot write %1").arg(m_part->fileName())); return; }
    m_hash = new QCryptographicHash(QCryptographicHash::Sha256);
    QNetworkRequest req{QUrl(m_url)};
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    m_reply = m_nam->get(req);
    connect(m_reply, &QNetworkReply::readyRead, this, [this] {
        const QByteArray b = m_reply->readAll();
        m_part->write(b);
        m_hash->addData(b);
    });
    connect(m_reply, &QNetworkReply::downloadProgress, this, [this](qint64 got, qint64 total) {
        const double p = total > 0 ? double(got) / double(total) : 0;
        if (int(p * 100) != int(m_progress * 100)) { m_progress = p; emit changed(); }
    });
    connect(m_reply, &QNetworkReply::finished, this, [this] {
        QNetworkReply *r = m_reply;
        m_reply = nullptr;
        r->deleteLater();
        const int code = r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (r->error() != QNetworkReply::NoError || code != 200) {
            if (code > 0 && code != 200) fail(core::tx(S("app.mpv_oshibka_servera"), {{"code", QString::number(code)}}), S("HTTP %1").arg(code));
            else fail(core::tx(S("app.mpv_server_nedostupen")), r->errorString());
            return;
        }
        m_part->write(r->readAll());
        m_part->close();
        const QString hex = QString::fromLatin1(m_hash->result().toHex());
        if (!m_sha.isEmpty() && hex != m_sha) { fail(core::tx(S("app.mpv_fayl_povrezhden")), S("checksum mismatch")); return; }
        const QString target = QDir(dir()).filePath(mpv::libraryName());
        QFile::remove(target);
        if (!m_part->rename(target)) { fail(core::tx(S("app.mpv_server_nedostupen")), S("cannot place %1").arg(target)); return; }
        QFile ver(QDir(dir()).filePath(S("libmpv.version")));
        if (ver.open(QIODevice::WriteOnly)) ver.write(m_sha.toLatin1());
        delete m_hash; m_hash = nullptr;
        m_part->deleteLater(); m_part = nullptr;
        m_progress = -1;
        mpv::rescan();
        PlayerLog::write(S("libmpv: installed %1").arg(target));
        emit changed();
        emit finished(ready());
    });
}

void MpvInstall::cancel() {
    if (!m_reply) return;
    QNetworkReply *r = m_reply;
    m_reply = nullptr;
    r->disconnect(this);
    r->abort();
    r->deleteLater();
    if (m_part) { m_part->close(); m_part->remove(); m_part->deleteLater(); m_part = nullptr; }
    delete m_hash; m_hash = nullptr;
    m_progress = -1;
    PlayerLog::write(S("libmpv: download cancelled (quit)"));
}

void MpvInstall::fail(const QString &message, const QString &log) {
    PlayerLog::write(S("libmpv: download from %1 failed: %2").arg(m_url, log));
    if (m_part) { m_part->close(); m_part->remove(); m_part->deleteLater(); m_part = nullptr; }
    delete m_hash; m_hash = nullptr;
    m_progress = -1;
    m_error = message;
    emit changed();
    emit finished(false);
}

} // namespace suslik::app
