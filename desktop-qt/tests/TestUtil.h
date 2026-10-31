#pragma once
// Общие помощники тестов модели: те же короткие конструкторы, что в ModelTest.kt / SeriesGroupingTest.kt.
#include "suslik/core/Model.h"

#include <QJsonDocument>
#include <QTest>

#define S QStringLiteral

namespace testutil {
using namespace suslik::core;

inline QString afterLastSlash(const QString &p) { return p.mid(p.lastIndexOf(QLatin1Char('/')) + 1); }

inline MFile file(int idx, const QString &path, qint64 sizeMb = 1000, std::optional<QString> link = std::nullopt) {
    return MFile{idx, afterLastSlash(path), path, false, sizeMb * 1024 * 1024, link ? link : std::optional<QString>(S("https://host/f/%1").arg(idx))};
}

inline Magnet magnet(qint64 id, const QString &name, const QString &status, int code, qint64 size, qint64 downloaded,
                     const QList<MFile> &files, bool canRemove = true) {
    Magnet m;
    m.id = id; m.name = name; m.status = status; m.statusCode = code; m.size = size; m.downloaded = downloaded;
    m.files = files; m.canRemove = canRemove;
    return m;
}

inline QJsonObject json(const QByteArray &text) {
    QJsonParseError err{};
    const auto doc = QJsonDocument::fromJson(text, &err);
    if (err.error != QJsonParseError::NoError) qFatal("bad test JSON: %s", qPrintable(err.errorString()));
    return doc.object();
}

inline QList<qint64> ids(const QList<LibraryItem> &l) {
    QList<qint64> out;
    for (const auto &i : l) out << i.m.id;
    return out;
}
} // namespace testutil
