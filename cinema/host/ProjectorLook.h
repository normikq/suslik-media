#pragma once
#include <QByteArray>
#include <QList>
#include <QPair>
#include <QString>

/// Эффект кинопроектора: шейдеры mpv GRAINLIGHT (github.com/claudiofabian-sudo/grainlight, MIT, файлы и лицензия —
/// cinema/host/grainlight/). Плёночное зерно, дрожание кадра в фильмовом канале, мерцание лампы, кривая контраста,
/// тёплый свет, ореол вокруг светлого, виньетка, пыль, волоски, метки смены части. Пресеты 1–4 — GRAINLIGHT как есть,
/// 5–6 — свои, из тех же шейдеров с другими #define. Здесь только таблица и подстановка (без mpv и окна, тест
/// ProjectorLookTest); ставит шейдер в mpv — Projector.
namespace suslik::cinema {

struct ProjectorPreset {
    const char *id;     ///< в настройках и host.json
    const char *title;  ///< на телефоне и в журнале
    const char *base;   ///< файл GRAINLIGHT (ресурс :/grainlight/…), пусто — эффекта нет
    QList<QPair<QByteArray, QByteArray>> defines; ///< свои значения #define поверх base
};

/// 0 — выкл, 1 «Тонкий», 2 «Кино 2000-х», 3 «Старая копия», 4 «Сбалансированный», 5 «Немое кино», 6 «Домашний проектор».
const QList<ProjectorPreset> &projectorPresets();
int projectorCount();
/// Номер пресета в допустимых пределах (иначе 0 — выкл).
int projectorClamp(int i);

/// Заменить значения #define NAME в тексте шейдера (каждое вхождение; комментарий после значения сохраняется).
/// Имени нет в тексте — текст не меняется.
QByteArray patchDefines(QByteArray src, const QList<QPair<QByteArray, QByteArray>> &defines);

/// Звук плёнки (GRAINLIGHT, audio-presets.lua): 0 — как есть, 1 — оптическая дорожка 35 мм, 2 — винил.
/// Строка для af mpv (с меткой @film) или пусто.
int filmSoundCount();
QString filmSoundTitle(int i);
QByteArray filmSoundFilter(int i);

} // namespace suslik::cinema
