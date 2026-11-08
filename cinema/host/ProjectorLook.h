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

/// Проходы `//!HOOK MAIN` -> `//!HOOK OUTPUT`: считать на кадре буфера (1920×1080), а не на кадре фильма (4K —
/// в 4 раза больше точек). GRAINLIGHT задаёт смещения в долях высоты кадра, вид от этого не меняется. 08.11: «кино
/// 2000-х» на 4K отнимал у игры треть кадров в секунду, экран запаздывал за камерой («плавал»).
QByteArray hookAtOutput(QByteArray src);

/// Шейдер «в зале» (scene/in-world.glsl): свои #define для HDR-фильма. mpv уже сжал его света (tone-mapping
/// bt.2446a), второе сжатие делает картинку тусклой и блёклой: плечо выше, цвет в светах почти не уходит,
/// насыщенность чуть выше, свечение слабее. Для обычного (SDR) фильма — пусто, значения файла.
QList<QPair<QByteArray, QByteArray>> sceneShaderDefines(bool hdr);

/// Звук плёнки (GRAINLIGHT, audio-presets.lua): 0 — как есть, 1 — оптическая дорожка 35 мм, 2 — винил.
/// Строка для af mpv (с меткой @film) или пусто.
int filmSoundCount();
QString filmSoundTitle(int i);
QByteArray filmSoundFilter(int i);

} // namespace suslik::cinema
