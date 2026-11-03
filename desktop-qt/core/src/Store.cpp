#include "suslik/core/Store.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QMetaObject>
#include <QSaveFile>
#include <QTimer>
#include <map>
#include <memory>

namespace suslik::core {

namespace {
QString &dirRef() { static QString d; return d; }
QMutex &registryLock() { static QMutex m; return m; }
std::map<QString, std::unique_ptr<Store>> &registry() { static std::map<QString, std::unique_ptr<Store>> r; return r; }

bool isWhite(char16_t c) { return c == ' ' || c == '\t' || c == '\f'; }
} // namespace

void Store::setDirectory(const QString &dir) {
    dirRef() = dir;
    QDir().mkpath(dir);
}

QString Store::directory() { return dirRef(); }

Store &Store::of(const QString &name) {
    QMutexLocker l(&registryLock());
    auto &r = registry();
    auto it = r.find(name);
    if (it == r.end()) it = r.emplace(name, std::make_unique<Store>(name)).first;
    return *it->second;
}

void Store::flushAll() {
    QMutexLocker l(&registryLock());
    for (auto &[_, s] : registry()) s->flush();
}

void Store::resetForTests() {
    QMutexLocker l(&registryLock());
    registry().clear();
}

Store::Store(const QString &name)
    : m_file(QDir(dirRef()).filePath(name + QStringLiteral(".properties"))), m_bak(m_file + QStringLiteral(".bak")) {
    load();
}

Store::~Store() { flush(); }

void Store::load() {
    for (const QString &src : {m_file, m_bak}) {
        QFile f(src);
        if (!f.open(QIODevice::ReadOnly)) continue;
        const QByteArray bytes = f.readAll();
        // 18.10: после выключения питания файлы были из нулей — нули уходили в заголовок Cookie
        if (bytes.isEmpty() || bytes.contains('\0')) continue;
        QHash<QString, QString> v = parseProperties(bytes);
        for (auto it = v.begin(); it != v.end();) {
            const QString both = it.key() + it.value();
            // мусор — управляющие знаки, кроме пробельных: \t, \n, \r пишутся экранированными и бывают в значениях
            const bool control = std::any_of(both.begin(), both.end(), [](QChar c) {
                return c.unicode() < ' ' && c != QLatin1Char('\t') && c != QLatin1Char('\n') && c != QLatin1Char('\r');
            });
            it = control ? v.erase(it) : std::next(it);
        }
        m_values = v;
        return;
    }
}

std::optional<QString> Store::get(const QString &key) const {
    QMutexLocker l(&m_lock);
    const auto it = m_values.constFind(key);
    return it == m_values.cend() ? std::nullopt : std::optional<QString>(*it);
}

void Store::put(const QString &key, const std::optional<QString> &value) {
    {
        QMutexLocker l(&m_lock);
        if (value) m_values.insert(key, *value);
        else m_values.remove(key);
    }
    scheduleSave();
}

QStringList Store::keys() const {
    QMutexLocker l(&m_lock);
    return m_values.keys();
}

QHash<QString, QString> Store::all() const {
    QMutexLocker l(&m_lock);
    return m_values;
}

void Store::scheduleSave() {
    {
        QMutexLocker l(&m_lock);
        if (m_pending) return;
        m_pending = true;
    }
    // Запись в фоне и пачкой: плеер сохраняет место раз в 10 с, переписывать файл каждый раз незачем
    if (auto *app = QCoreApplication::instance())
        QMetaObject::invokeMethod(app, [this] { QTimer::singleShot(400, QCoreApplication::instance(), [this] { flush(); }); }, Qt::QueuedConnection);
    else
        flush();
}

void Store::flush() {
    QMutexLocker fileLock(&m_fileLock);
    QHash<QString, QString> copy;
    {
        QMutexLocker l(&m_lock);
        if (!m_pending) return;
        m_pending = false;
        copy = m_values;
    }
    QDir().mkpath(QFileInfo(m_file).absolutePath());
    // Новый файл пишется через QSaveFile (временный файл, fsync, замена именем): рабочий файл на месте, пока новый
    // не готов. Прежний становится .bak копией, а не переименованием — не открылся QSaveFile, файл не пропадает.
    QSaveFile out(m_file);
    bool ok = out.open(QIODevice::WriteOnly);
    if (ok) ok = out.write(writeProperties(copy)) >= 0;
    if (ok && QFile::exists(m_file)) {
        QFile::remove(m_bak);
        QFile::copy(m_file, m_bak);
    }
    if (ok) ok = out.commit();
    else out.cancelWriting();
    if (ok) return;
    // Не записалось (файл занят антивирусом, нет места): данные пока только в памяти — повторить позже.
    {
        QMutexLocker l(&m_lock);
        if (m_pending) return; // уже есть новая запись в очереди
        m_pending = true;
    }
    if (auto *app = QCoreApplication::instance())
        QMetaObject::invokeMethod(app, [this] { QTimer::singleShot(5000, QCoreApplication::instance(), [this] { flush(); }); }, Qt::QueuedConnection);
}

QHash<QString, QString> Store::parseProperties(const QByteArray &bytes) {
    // Properties.load(InputStream) читает ISO-8859-1, остальное — через \uXXXX
    const QString text = QString::fromLatin1(bytes);
    QHash<QString, QString> out;
    qsizetype i = 0;
    const qsizetype n = text.size();
    while (i < n) {
        // Логическая строка: продолжения через нечётное число «\» в конце
        QString line;
        bool first = true;
        while (i < n) {
            while (i < n && isWhite(text[i].unicode())) ++i; // начальные пробелы (и у продолжения)
            const qsizetype start = i;
            while (i < n && text[i] != QLatin1Char('\n') && text[i] != QLatin1Char('\r')) ++i;
            QString part = text.mid(start, i - start);
            if (i < n && text[i] == QLatin1Char('\r')) ++i;
            if (i < n && text[i] == QLatin1Char('\n')) ++i;
            if (first && (part.isEmpty() || part.startsWith(QLatin1Char('#')) || part.startsWith(QLatin1Char('!')))) { line.clear(); break; }
            first = false;
            qsizetype slashes = 0;
            for (qsizetype k = part.size() - 1; k >= 0 && part[k] == QLatin1Char('\\'); --k) ++slashes;
            if (slashes % 2 == 1) {
                part.chop(1);
                line += part;
                continue;
            }
            line += part;
            break;
        }
        if (line.isEmpty()) continue;
        // Ключ — до первого неэкранированного «=», «:» или пробела
        qsizetype k = 0;
        bool esc = false;
        for (; k < line.size(); ++k) {
            const QChar c = line[k];
            if (esc) { esc = false; continue; }
            if (c == QLatin1Char('\\')) { esc = true; continue; }
            if (c == QLatin1Char('=') || c == QLatin1Char(':') || isWhite(c.unicode())) break;
        }
        const QString rawKey = line.left(k);
        qsizetype v = k;
        while (v < line.size() && isWhite(line[v].unicode())) ++v;
        if (v < line.size() && (line[v] == QLatin1Char('=') || line[v] == QLatin1Char(':'))) ++v;
        while (v < line.size() && isWhite(line[v].unicode())) ++v;
        auto unescape = [](const QString &s) {
            QString r;
            r.reserve(s.size());
            for (qsizetype j = 0; j < s.size(); ++j) {
                QChar c = s[j];
                if (c != QLatin1Char('\\') || j + 1 >= s.size()) { r += c; continue; }
                c = s[++j];
                switch (c.unicode()) {
                case 't': r += QLatin1Char('\t'); break;
                case 'n': r += QLatin1Char('\n'); break;
                case 'r': r += QLatin1Char('\r'); break;
                case 'f': r += QLatin1Char('\f'); break;
                case 'u': {
                    bool ok = false;
                    const QString digits = s.mid(j + 1, 4);
                    const ushort code = digits.toUShort(&ok, 16);
                    if (ok && digits.size() == 4) { r += QChar(code); j += 4; }
                    else r += QLatin1Char('u');
                    break;
                }
                default: r += c;
                }
            }
            return r;
        };
        out.insert(unescape(rawKey), unescape(line.mid(v)));
    }
    return out;
}

static void escapeInto(QByteArray &out, const QString &s, bool isKey) {
    static const char hex[] = "0123456789ABCDEF";
    for (qsizetype j = 0; j < s.size(); ++j) {
        const char16_t c = s[j].unicode();
        switch (c) {
        case ' ': if (j == 0 || isKey) out += '\\'; out += ' '; break;
        case '\t': out += "\\t"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\f': out += "\\f"; break;
        case '\\': case '=': case ':': case '#': case '!': out += '\\'; out += char(c); break;
        default:
            if (c < 0x20 || c > 0x7e) {
                out += "\\u";
                out += hex[(c >> 12) & 0xF]; out += hex[(c >> 8) & 0xF]; out += hex[(c >> 4) & 0xF]; out += hex[c & 0xF];
            } else {
                out += char(c);
            }
        }
    }
}

QByteArray Store::writeProperties(const QHash<QString, QString> &values) {
    QByteArray out;
    QStringList keys = values.keys();
    keys.sort();
    for (const QString &k : keys) {
        escapeInto(out, k, true);
        out += '=';
        escapeInto(out, values.value(k), false);
        out += '\n';
    }
    return out;
}

} // namespace suslik::core
