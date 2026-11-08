#include "RemoteLink.h"

#include "PlayerLog.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QJsonDocument>

namespace suslik::cinema {

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
qint64 nowSec() { return QDateTime::currentSecsSinceEpoch(); }
} // namespace

RemoteLink::RemoteLink(QObject *parent) : QObject(parent) {
    m_poll.setInterval(150);
    connect(&m_poll, &QTimer::timeout, this, &RemoteLink::poll);
    m_beat.setInterval(1000);
    connect(&m_beat, &QTimer::timeout, this, [this] { m_dirty = true; writeStatus(); });
}

void RemoteLink::setModDir(const QString &dir) {
    if (dir == m_dir) return;
    m_dir = dir;
    m_sid.clear();
    m_lastN = -1;
    if (m_dir.isEmpty() || !QDir(m_dir).exists()) {
        m_poll.stop();
        m_beat.stop();
        return;
    }
    // helper мода запускает хост по этому пути
    QFile f(QDir(m_dir).filePath(S("host.txt")));
    if (f.open(QIODevice::WriteOnly | QIODevice::Truncate))
        f.write(QDir::toNativeSeparators(QCoreApplication::applicationFilePath()).toUtf8() + "\n");
    app::PlayerLog::write(S("[remote] пульт VAO: ") + m_dir);
    m_poll.start();
    m_beat.start();
    m_dirty = true;
    writeStatus();
    if (!m_library.isEmpty()) setLibrary(m_library);
}

void RemoteLink::setStatus(const QJsonObject &status) {
    if (status == m_status) return;
    m_status = status;
    m_dirty = true;
    writeStatus();
}

void RemoteLink::setLibrary(const QJsonArray &items) {
    if (items == m_library && m_libRev > 0) return;
    m_library = items;
    ++m_libRev;
    if (!m_dir.isEmpty()) {
        const QByteArray body = QJsonDocument(QJsonObject{{S("seq"), m_libRev}, {S("items"), items}}).toJson(QJsonDocument::Compact);
        writeInPlace(S("library.json"), body, m_libRev);
    }
    m_dirty = true;
    writeStatus();
}

void RemoteLink::writeStatus() {
    if (m_dir.isEmpty() || !m_dirty) return;
    m_dirty = false;
    QJsonObject o = m_status;
    o.insert(S("seq"), ++m_seq);
    o.insert(S("time"), nowSec());
    o.insert(S("libRev"), m_libRev);
    writeInPlace(S("host.json"), QJsonDocument(o).toJson(QJsonDocument::Compact), m_seq);
}

// На месте, а не через переименование (как state.json у мода): мод читает файл в каждом опросе, и заменить
// открытый файл на Windows нельзя. Целость — по "end" в конце: он равен "seq" в начале, только если файл дописан.
void RemoteLink::writeInPlace(const QString &name, const QByteArray &body, qint64 seq) {
    QByteArray b = body;
    if (b.endsWith('}')) b.chop(1);
    b += ",\"end\":" + QByteArray::number(seq) + "}\n";
    QFile f(QDir(m_dir).filePath(name));
    if (f.open(QIODevice::WriteOnly | QIODevice::Truncate)) f.write(b);
}

QList<QJsonObject> RemoteLink::takeNew(const QJsonObject &file, qint64 now) {
    QList<QJsonObject> out;
    const QString sid = file.value(S("sid")).toString();
    const QJsonArray cmds = file.value(S("cmds")).toArray();
    const bool first = m_sid.isEmpty() || sid != m_sid; // запуск хоста или перезапуск мода (новая сессия)
    m_sid = sid;
    if (first) m_lastN = -1;
    qint64 maxN = m_lastN;
    for (const QJsonValue &v : cmds) {
        const QJsonObject c = v.toObject();
        const qint64 n = c.value(S("n")).toInteger(-1);
        if (n <= m_lastN) continue;
        maxN = std::max(maxN, n);
        const qint64 at = c.value(S("at")).toInteger(0);
        // устаревшие команды (хост был закрыт, когда их дали) не выполняем: телефон ждёт ответа не дольше
        if (now - at > (first ? 15 : 30)) continue;
        out << c;
    }
    m_lastN = maxN;
    return out;
}

void RemoteLink::poll() {
    QFile f(QDir(m_dir).filePath(S("remote.json")));
    if (!f.open(QIODevice::ReadOnly)) return;
    const QJsonDocument doc = QJsonDocument::fromJson(f.readAll());
    f.close();
    if (!doc.isObject()) return; // мод пишет файл прямо сейчас
    for (const QJsonObject &c : takeNew(doc.object(), nowSec())) {
        app::PlayerLog::write(S("[remote] ") + QString::fromUtf8(QJsonDocument(c).toJson(QJsonDocument::Compact)));
        emit command(c);
    }
}

} // namespace suslik::cinema
