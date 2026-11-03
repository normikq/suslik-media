#pragma once
#include <QByteArray>
#include <QList>
#include <QSet>
#include <QString>
#include <QStringList>
#include <optional>

/// Проверка «в раздаче есть видео» до отправки на сервер: список файлов .torrent (bencode) и имя магнета.
/// Перенос android/core/.../torrent/TorrentInspector.kt.
namespace suslik::core::TorrentInspector {

inline constexpr int MAX_TORRENT_FILE_SIZE = 10 * 1024 * 1024;

struct FileInfo {
    QString path;
    qint64 size = 0;
    QString extension() const;
    bool isVideo() const;
};

struct Inspection {
    QList<FileInfo> files;
    qint64 totalSize = 0;
    qint64 videoSize = 0;
    bool hasVideo = false;
    int videoCount = 0;
    QSet<QString> nonVideoExtensions;
    bool isParsedSuccessfully = true;
};

bool isVideoFileName(const QString &name);
bool hasVideoFiles(const QList<FileInfo> &files);
/// Текст отказа или пусто, если всё в порядке либо судить не по чему (пустой список).
std::optional<QString> rejectionForPaths(const QStringList &paths);
std::optional<QString> rejectionForTorrent(const QByteArray &bytes);
/// Магнет: до готовности известно только имя dn — отказ, если у него расширение не видео.
std::optional<QString> rejectionForMagnetName(const QString &dn);
Inspection inspect(const QByteArray &bytes);
QList<FileInfo> extractFiles(const QByteArray &bytes);

} // namespace suslik::core::TorrentInspector
