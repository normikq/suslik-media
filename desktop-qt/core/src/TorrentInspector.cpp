#include "suslik/core/TorrentInspector.h"

#include <map>
#include <memory>
#include <vector>

namespace suslik::core::TorrentInspector {

namespace {
const QSet<QString> VIDEO{QStringLiteral("mkv"), QStringLiteral("mp4"), QStringLiteral("m4v"), QStringLiteral("avi"), QStringLiteral("mov"),
                          QStringLiteral("webm"), QStringLiteral("ts"), QStringLiteral("m2ts"), QStringLiteral("mts"), QStringLiteral("wmv"),
                          QStringLiteral("vob"), QStringLiteral("mpg"), QStringLiteral("mpeg"), QStringLiteral("flv"), QStringLiteral("3gp"),
                          QStringLiteral("ogv"), QStringLiteral("asf")};
const QSet<QString> NON_VIDEO{QStringLiteral("exe"), QStringLiteral("msi"), QStringLiteral("apk"), QStringLiteral("pkg"), QStringLiteral("deb"),
                              QStringLiteral("rpm"), QStringLiteral("bin"), QStringLiteral("cue"), QStringLiteral("dmg"), QStringLiteral("zip"),
                              QStringLiteral("rar"), QStringLiteral("7z"), QStringLiteral("tar"), QStringLiteral("gz"), QStringLiteral("cbr"),
                              QStringLiteral("cbz"), QStringLiteral("pdf"), QStringLiteral("epub"), QStringLiteral("fb2"), QStringLiteral("mp3"),
                              QStringLiteral("flac"), QStringLiteral("wav"), QStringLiteral("ape"), QStringLiteral("aac"), QStringLiteral("ogg"),
                              QStringLiteral("wma"), QStringLiteral("doc"), QStringLiteral("docx")};

QString extOf(const QString &name) {
    const auto dot = name.lastIndexOf(QLatin1Char('.'));
    return dot < 0 ? QString() : name.mid(dot + 1).toLower();
}

/// Значение bencode.
struct BValue {
    enum class Type { Int, Bytes, List, Dict } type = Type::Bytes;
    qint64 i = 0;
    QByteArray s;
    std::vector<BValue> list;
    std::map<QString, BValue> dict;
    const BValue *get(const char *key) const {
        auto it = dict.find(QString::fromLatin1(key));
        return it == dict.end() ? nullptr : &it->second;
    }
};

class Parser {
public:
    explicit Parser(const QByteArray &b) : m_b(b) {}
    bool failed = false;

    std::optional<BValue> parse(int depth = 0) {
        if (depth > 32) { failed = true; return std::nullopt; }
        if (m_pos >= m_b.size()) return std::nullopt;
        const char c = m_b[m_pos];
        if (c == 'i') return parseInt();
        if (c == 'l') return parseList(depth + 1);
        if (c == 'd') return parseDict(depth + 1);
        if (c >= '0' && c <= '9') return parseBytes();
        return std::nullopt;
    }

private:
    BValue parseInt() {
        ++m_pos;
        const qsizetype start = m_pos;
        while (m_pos < m_b.size() && m_b[m_pos] != 'e') ++m_pos;
        BValue v;
        v.type = BValue::Type::Int;
        bool ok = false;
        v.i = m_b.mid(start, m_pos - start).toLongLong(&ok);
        if (!ok) v.i = 0;
        ++m_pos;
        return v;
    }
    BValue parseBytes() {
        const qsizetype start = m_pos;
        while (m_pos < m_b.size() && m_b[m_pos] != ':') ++m_pos;
        bool ok = false;
        qsizetype len = m_b.mid(start, m_pos - start).toLongLong(&ok);
        if (!ok) len = 0;
        ++m_pos;
        len = std::max<qsizetype>(0, std::min(len, m_b.size() - m_pos));
        BValue v;
        v.s = m_b.mid(m_pos, len);
        m_pos += len;
        return v;
    }
    std::optional<BValue> parseList(int depth) {
        ++m_pos;
        BValue v;
        v.type = BValue::Type::List;
        bool closed = false;
        while (m_pos < m_b.size()) {
            if (m_b[m_pos] == 'e') { ++m_pos; closed = true; break; }
            auto item = parse(depth);
            if (!item || failed) break;
            v.list.push_back(std::move(*item));
        }
        if (!closed) { failed = true; return std::nullopt; }
        return v;
    }
    std::optional<BValue> parseDict(int depth) {
        ++m_pos;
        BValue v;
        v.type = BValue::Type::Dict;
        bool closed = false;
        while (m_pos < m_b.size()) {
            if (m_b[m_pos] == 'e') { ++m_pos; closed = true; break; }
            auto key = parse(depth);
            if (!key || failed || key->type != BValue::Type::Bytes) break;
            auto value = parse(depth);
            if (failed) break;
            v.dict[QString::fromUtf8(key->s)] = value ? std::move(*value) : BValue{};
        }
        if (!closed) { failed = true; return std::nullopt; }
        return v;
    }

    const QByteArray &m_b;
    qsizetype m_pos = 0;
};

std::optional<QList<FileInfo>> extractInternal(const QByteArray &bytes) {
    Parser p(bytes);
    const auto root = p.parse();
    if (p.failed || !root || root->type != BValue::Type::Dict) return std::nullopt;
    const BValue *info = root->get("info");
    if (!info || info->type != BValue::Type::Dict) return std::nullopt;
    QList<FileInfo> out;
    const BValue *files = info->get("files");
    if (files && files->type == BValue::Type::List) {
        for (const BValue &item : files->list) {
            if (item.type != BValue::Type::Dict) continue;
            const BValue *len = item.get("length");
            const qint64 length = len && len->type == BValue::Type::Int ? len->i : 0;
            const BValue *path = item.get("path.utf-8");
            if (!path || path->type != BValue::Type::List) path = item.get("path");
            if (!path || path->type != BValue::Type::List) continue;
            QStringList parts;
            for (const BValue &part : path->list)
                if (part.type == BValue::Type::Bytes) parts << QString::fromUtf8(part.s);
            const QString joined = parts.join(QLatin1Char('/'));
            if (!joined.trimmed().isEmpty()) out << FileInfo{joined, length};
        }
    } else {
        const BValue *name = info->get("name.utf-8");
        if (!name) name = info->get("name");
        const BValue *len = info->get("length");
        out << FileInfo{name && name->type == BValue::Type::Bytes ? QString::fromUtf8(name->s) : QStringLiteral("unknown"),
                        len && len->type == BValue::Type::Int ? len->i : 0};
    }
    return out;
}
} // namespace

QString FileInfo::extension() const { return extOf(path); }
bool FileInfo::isVideo() const { return VIDEO.contains(extension()); }

bool isVideoFileName(const QString &name) { return VIDEO.contains(extOf(name)); }

bool hasVideoFiles(const QList<FileInfo> &files) {
    return std::any_of(files.begin(), files.end(), [](const FileInfo &f) { return f.isVideo(); });
}

std::optional<QString> rejectionForPaths(const QStringList &paths) {
    if (paths.isEmpty() || std::any_of(paths.begin(), paths.end(), [](const QString &p) { return isVideoFileName(p); })) return std::nullopt;
    QStringList exts;
    for (const QString &p : paths) {
        const QString e = extOf(p.mid(p.lastIndexOf(QLatin1Char('/')) + 1));
        if (!e.trimmed().isEmpty() && e.size() <= 5 && !exts.contains(e)) exts << e;
    }
    QString note;
    if (!exts.isEmpty()) {
        QStringList shown;
        for (const QString &e : exts.mid(0, 4)) shown << QLatin1Char('.') + e;
        note = QStringLiteral(" (найдены: ") + shown.join(QStringLiteral(", ")) + QLatin1Char(')');
    }
    return QStringLiteral("Отклонено: в раздаче нет видеофайлов") + note;
}

std::optional<QString> rejectionForTorrent(const QByteArray &bytes) {
    QStringList paths;
    for (const FileInfo &f : inspect(bytes).files) paths << f.path;
    return rejectionForPaths(paths);
}

std::optional<QString> rejectionForMagnetName(const QString &dn) {
    const QString ext = extOf(dn);
    if (NON_VIDEO.contains(ext)) return QStringLiteral("Отклонено: не является видеофайлом (.%1)").arg(ext);
    return std::nullopt;
}

Inspection inspect(const QByteArray &bytes) {
    Inspection out;
    if (bytes.isEmpty() || bytes.size() > MAX_TORRENT_FILE_SIZE) { out.isParsedSuccessfully = false; return out; }
    const auto files = extractInternal(bytes);
    if (!files) { out.isParsedSuccessfully = false; return out; }
    out.files = *files;
    for (const FileInfo &f : *files) {
        out.totalSize += f.size;
        if (f.isVideo()) {
            out.videoSize += f.size;
            ++out.videoCount;
        } else if (!f.extension().trimmed().isEmpty()) {
            out.nonVideoExtensions.insert(f.extension());
        }
    }
    out.hasVideo = out.videoCount > 0;
    return out;
}

QList<FileInfo> extractFiles(const QByteArray &bytes) {
    if (bytes.isEmpty() || bytes.size() > MAX_TORRENT_FILE_SIZE) return {};
    return extractInternal(bytes).value_or(QList<FileInfo>{});
}

} // namespace suslik::core::TorrentInspector
