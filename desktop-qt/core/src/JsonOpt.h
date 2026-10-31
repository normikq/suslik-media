#pragma once
// Чтение JSON как в org.json (optString / optLong / optBoolean / isNull) — чтобы перенос из shared/ вёл себя так же.
#include <QJsonObject>
#include <QJsonValue>
#include <QString>

namespace suslik::core::json {

/// Строка; число — его запись; null — "null" (как optString); нет ключа — пусто.
inline QString optString(const QJsonObject &o, const QString &key) {
    const QJsonValue v = o.value(key);
    switch (v.type()) {
    case QJsonValue::String: return v.toString();
    case QJsonValue::Double: {
        const double d = v.toDouble();
        return d == double(qint64(d)) ? QString::number(qint64(d)) : QString::number(d);
    }
    case QJsonValue::Bool: return v.toBool() ? QStringLiteral("true") : QStringLiteral("false");
    case QJsonValue::Null: return QStringLiteral("null");
    default: return QString();
    }
}

inline qint64 optLong(const QJsonObject &o, const QString &key, qint64 def = 0) {
    const QJsonValue v = o.value(key);
    if (v.isDouble()) return qint64(v.toDouble());
    if (v.isString()) {
        bool ok = false;
        const double d = v.toString().trimmed().toDouble(&ok);
        if (ok) return qint64(d);
    }
    return def;
}

inline bool optBool(const QJsonObject &o, const QString &key, bool def = false) {
    const QJsonValue v = o.value(key);
    if (v.isBool()) return v.toBool();
    if (v.isString()) {
        if (v.toString().compare(QLatin1String("true"), Qt::CaseInsensitive) == 0) return true;
        if (v.toString().compare(QLatin1String("false"), Qt::CaseInsensitive) == 0) return false;
    }
    return def;
}

/// Ключ есть и не null (org.json: !isNull).
inline bool hasValue(const QJsonObject &o, const QString &key) { return o.contains(key) && !o.value(key).isNull(); }

/// Значение без null: null или нет ключа — пусто.
inline QString str(const QJsonObject &o, const QString &key) { return hasValue(o, key) ? optString(o, key) : QString(); }

} // namespace suslik::core::json
