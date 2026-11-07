#include "ScreenQuad.h"
#include "AppDir.h"

#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>

namespace suslik::cinema {

namespace {
QString file() { return app::AppDir::file(QStringLiteral("screens.json")); }
} // namespace

ScreenQuad::ScreenQuad(QObject *parent) : QObject(parent) {
    reset();
}

QVariantList ScreenQuad::corners() const {
    QVariantList l;
    for (const QPointF &p : m_frac) l << p;
    return l;
}

void ScreenQuad::setViewSize(const QSizeF &s) {
    if (s == m_view) return;
    m_view = s;
    recompute();
}

bool ScreenQuad::setCorner(int i, double fx, double fy) {
    if (i < 0 || i > 3) return false;
    Quad q = m_frac;
    q[i] = {qBound(-0.5, fx, 1.5), qBound(-0.5, fy, 1.5)};
    if (!isConvex(q)) return false;
    m_frac = q;
    recompute();
    return true;
}

bool ScreenQuad::setCornersPx(const Quad &px) {
    if (m_view.isEmpty() || !isConvex(px)) return false;
    for (int i = 0; i < 4; ++i) m_frac[i] = {px[i].x() / m_view.width(), px[i].y() / m_view.height()};
    recompute();
    return true;
}

void ScreenQuad::reset() {
    m_frac = {QPointF(0.30, 0.28), QPointF(0.68, 0.22), QPointF(0.70, 0.66), QPointF(0.28, 0.62)};
    recompute();
}

bool ScreenQuad::save() const {
    QJsonArray arr;
    for (const QPointF &p : m_frac) arr.append(QJsonArray{p.x(), p.y()});
    QSaveFile f(file());
    if (!f.open(QIODevice::WriteOnly)) return false;
    f.write(QJsonDocument(QJsonObject{{QStringLiteral("corners"), arr}}).toJson());
    return f.commit();
}

bool ScreenQuad::load() {
    QFile f(file());
    if (!f.open(QIODevice::ReadOnly)) return false;
    const QJsonArray arr = QJsonDocument::fromJson(f.readAll()).object().value(QStringLiteral("corners")).toArray();
    if (arr.size() != 4) return false;
    Quad q;
    for (int i = 0; i < 4; ++i) q[i] = {arr[i].toArray().at(0).toDouble(), arr[i].toArray().at(1).toDouble()};
    if (!isConvex(q)) return false;
    m_frac = q;
    recompute();
    return true;
}

void ScreenQuad::recompute() {
    if (!m_view.isEmpty()) {
        Quad px;
        for (int i = 0; i < 4; ++i) px[i] = {m_frac[i].x() * m_view.width(), m_frac[i].y() * m_view.height()};
        m_valid = rectToQuad(sourceWidth(), sourceHeight(), px, m_matrix);
    } else {
        m_valid = false;
    }
    emit changed();
}

} // namespace suslik::cinema
