// ============================================================
//  Suslik Cinema — «в зале»: вписать кадр фильма в картинку игры.
//
//  Оверлей рисуется поверх уже готового кадра игры, поэтому её тонмаппинг,
//  свет и постобработка на фильм не действуют: он выглядит ярче, контрастнее,
//  резче и «чернее» всего вокруг. Этот проход делает с кадром то, что сделала бы
//  игра с настоящим полотном в зале:
//   - экспозиция: полотно отражает свет, а не светится как монитор;
//   - мягкое плечо светов (тонмаппинг) вместо жёсткого белого клипа;
//   - светлые места чуть теряют насыщенность, как у тонмаппера игры;
//   - свечение ярких мест (bloom), как у света в самой игре;
//   - фактура матового полотна — неподвижная, очень слабая;
//   - лёгкая мягкость, как у TAA/DLSS игры, и чуть шума.
//  Ставится после эффекта проектора (Projector::install), выключается с пульта («Вписать в сцену»).
//  Для HDR-фильмов mpv уже сжал света (tone-mapping=bt.2446a), Projector подставляет другие значения
//  (sceneShaderDefines в ProjectorLook): плечо выше, цвет в светах почти не уходит.
//  Подстройку под свет игры (затемнение, свет зала, отсвет) делают SceneProbe и Main.qml поверх mpv.
//  Значения — #define ниже.
// ============================================================

//!HOOK OUTPUT
//!BIND HOOKED
//!DESC suslik in-world (экспозиция, плечо, свечение, полотно)

#define EXPOSURE    0.90    // постоянная часть яркости (1 — как в плеере); от света игры — ещё SceneProbe
#define SHOULDER    0.60    // с какой яркости (линейный свет) мягко сжимаются света
#define WHITE_DESAT 0.20    // насколько света теряют цвет в плече
#define SATUR       1.00    // общая насыщенность
#define BLACK       0.002   // малый постоянный подъём чёрного (линейный); свет зала по замеру — SceneProbe
#define SOFTEN      0.12    // смешивание с размытым соседством (0 — резко; уменьшение на экране и так смягчает)
#define BLOOM       0.14    // сила свечения ярких мест
#define BLOOM_THR   0.55    // с какой яркости (линейный свет) места светятся
#define BLOOM_R     0.010   // радиус свечения (доля высоты кадра)
#define FABRIC      0.010   // фактура полотна (0 — гладкое)
#define NOISE       0.006   // шум под зерно игры

// Подстройка с телефона (вкладка «Картинка», SceneKnob в SceneLight.cpp): подставляет Projector поверх значений выше
#define USER_EXPOSURE 1.0   // множитель EXPOSURE
#define USER_SATUR    1.0   // множитель SATUR
#define USER_SHOULDER 0.0   // сдвиг SHOULDER (+ — света ярче, меньше сжатия)
#define USER_BLOOM    1.0   // множитель BLOOM
#define USER_SOFTEN   1.0   // множитель SOFTEN
#define USER_FABRIC   1.0   // множитель FABRIC

#define K_EXPOSURE (EXPOSURE * USER_EXPOSURE)
#define K_SATUR    (SATUR * USER_SATUR)
#define K_SHOULDER clamp(SHOULDER + USER_SHOULDER, 0.2, 0.95)
#define K_BLOOM    (BLOOM * USER_BLOOM)
#define K_SOFTEN   clamp(SOFTEN * USER_SOFTEN, 0.0, 0.8)
#define K_FABRIC   (FABRIC * USER_FABRIC)

vec3 toLin(vec3 c) { return pow(max(c, 0.0), vec3(2.2)); }
vec3 toGam(vec3 c) { return pow(max(c, 0.0), vec3(1.0 / 2.2)); }
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

float hash(vec2 p, float t) {
    vec3 q = fract(vec3(p.xyx) * 0.1031 + t * 0.6180339);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

// плавный неподвижный шум клетками по 3 точки — переплетение ткани полотна
float fabric(vec2 pix) {
    vec2 g = pix / 3.0;
    vec2 i = floor(g), f = fract(g);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i, 0.0), b = hash(i + vec2(1.0, 0.0), 0.0);
    float c = hash(i + vec2(0.0, 1.0), 0.0), d = hash(i + vec2(1.0, 1.0), 0.0);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y) - 0.5;
}

// яркая часть точки (линейный свет) для свечения
vec3 bright(vec2 off) {
    vec3 l = toLin(HOOKED_texOff(off).rgb);
    return l * max(luma(l) - BLOOM_THR, 0.0) / max(luma(l), 1e-4);
}

vec4 hook() {
    vec4 c0 = HOOKED_tex(HOOKED_pos);
    vec3 nb = (HOOKED_texOff(vec2( 1.0,  0.0)).rgb + HOOKED_texOff(vec2(-1.0,  0.0)).rgb
             + HOOKED_texOff(vec2( 0.0,  1.0)).rgb + HOOKED_texOff(vec2( 0.0, -1.0)).rgb) * 0.25;
    vec3 l = toLin(mix(c0.rgb, nb, K_SOFTEN));

    // свечение: два кольца по 8 точек (радиус r и 2,2r), яркая часть
    if (K_BLOOM > 0.0) {
        float r = HOOKED_size.y * BLOOM_R;
        vec3 b = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float a = float(i) * 0.785398 + 0.39;
            vec2 d = vec2(cos(a), sin(a));
            b += bright(d * r) * 0.6 + bright(d * r * 2.2) * 0.4;
        }
        l += b / 8.0 * K_BLOOM;
    }

    l *= K_EXPOSURE;
    // плечо: до SHOULDER — как есть, выше — асимптотически к 1 (без клипа и «пересвета»)
    float y = luma(l);
    float sh = K_SHOULDER;
    float room = 1.0 - sh;
    float over = max(y - sh, 0.0);
    float yt = y > sh ? sh + room * over / (over + room) : y;
    l *= yt / max(y, 1e-5);
    // в плече цвет немного уходит в белое, как у тонмаппера игры
    l = mix(l, vec3(yt), clamp(over / room, 0.0, 1.0) * WHITE_DESAT);
    l = max(mix(vec3(yt), l, K_SATUR), 0.0);
    l = min(l, vec3(1.0));
    l = l + BLACK * (1.0 - l);

    vec2 pix = HOOKED_pos * HOOKED_size;
    l *= 1.0 + fabric(pix) * 2.0 * K_FABRIC;
    vec3 g = toGam(l);
    g += (hash(pix, float(frame)) - 0.5) * NOISE;
    return vec4(clamp(g, 0.0, 1.0), c0.a);
}
