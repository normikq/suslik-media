#include "ProjectorLook.h"

#include <QRegularExpression>
#include <algorithm>

namespace suslik::cinema {

const QList<ProjectorPreset> &projectorPresets() {
    static const QList<ProjectorPreset> list = {
        {"off", "выкл", "", {}},
        {"subtle", "тонкий", "35mm-1-sutil.glsl", {}},
        {"cine2000", "кино 2000-х", "35mm-2-cine2000.glsl", {}},
        {"worn", "старая копия", "35mm-3-veterana.glsl", {}},
        {"balanced", "сбалансированный", "35mm-4-equilibrado.glsl", {}},
        // Свои: чёрно-белая затёртая копия с сильным мерцанием и тёплым (сепия) светом лампы
        {"silent", "немое кино", "35mm-3-veterana.glsl",
         {{"SATUR", "0.0"}, {"WARMTH", "0.090"}, {"FLICKER", "0.0120"}, {"CONTRAST", "0.36"}, {"FADE", "0.060"},
          {"VIGNETTE", "0.24"}, {"WEAVE_X", "0.00110"}, {"WEAVE_Y", "0.00150"}, {"JITTER", "0.00045"},
          {"GRAIN", "0.090"}, {"CUE_PERIOD", "0.0"}}},
        // Свет проектора без износа плёнки: чистая копия, мягкий ореол и виньетка, лёгкое мерцание лампы
        {"home", "домашний проектор", "35mm-1-sutil.glsl",
         {{"GRAIN", "0.014"}, {"VIGNETTE", "0.16"}, {"HALATION", "0.10"}, {"HAL_THR", "0.72"}, {"FLICKER", "0.0020"},
          {"WARMTH", "0.030"}, {"DUST_PROB", "0.0"}, {"CUE_PERIOD", "0.0"}}},
    };
    return list;
}

int projectorCount() { return int(projectorPresets().size()); }

int projectorClamp(int i) { return i >= 0 && i < projectorCount() ? i : 0; }

QByteArray patchDefines(QByteArray src, const QList<QPair<QByteArray, QByteArray>> &defines) {
    for (const auto &[name, value] : defines) {
        // #define NAME <значение>  // комментарий — меняется только значение
        const QRegularExpression re(QStringLiteral("(#define[ \\t]+%1[ \\t]+)[^ \\t\\r\\n/]+")
                                        .arg(QRegularExpression::escape(QString::fromLatin1(name))));
        QString s = QString::fromUtf8(src);
        s.replace(re, QStringLiteral("\\1") + QString::fromLatin1(value));
        src = s.toUtf8();
    }
    return src;
}

int filmSoundCount() { return 3; }

QString filmSoundTitle(int i) {
    switch (i) {
    case 1: return QStringLiteral("оптика 35 мм");
    case 2: return QStringLiteral("винил");
    default: return QStringLiteral("как есть");
    }
}

QByteArray filmSoundFilter(int i) {
    switch (i) {
    case 1: // полоса оптической дорожки, присутствие 2,2 кГц, мягкое сжатие (GRAINLIGHT «35MM ÓPTICO»)
        return "@film:lavfi=[highpass=f=55,lowpass=f=9500,equalizer=f=2200:t=q:w=1.0:g=3.5,"
               "equalizer=f=350:t=q:w=1.2:g=-2.5,"
               "acompressor=threshold=-16dB:ratio=2.2:attack=12:release=180:makeup=1.8,"
               "alimiter=limit=0.95:attack=3:release=60]";
    case 2: // тепло 90 Гц, детонация 0,55 Гц, лёгкий блеск (GRAINLIGHT «VINILO»)
        return "@film:lavfi=[highpass=f=28,lowpass=f=15500,equalizer=f=90:t=q:w=1.0:g=2.0,"
               "equalizer=f=3200:t=q:w=1.3:g=-1.2,vibrato=f=0.55:d=0.025,aexciter=amount=0.55:blend=0.25,"
               "alimiter=limit=0.97:attack=3:release=60]";
    default: return {};
    }
}

} // namespace suslik::cinema
