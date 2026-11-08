#include "SceneLight.h"

#include <QFile>
#include <QRegularExpression>
#include <QSet>
#include <QtMath>
#include <cmath>
#include <QtTest>

using namespace suslik::cinema;

class SceneLightTest : public QObject {
    Q_OBJECT
private slots:
    /// Однородное серое поле: замер даёт ненулевое количество точек, линейный цвет и яркость pow(128/255, 2.2).
    void uniformGray() {
        QImage img(32, 32, QImage::Format_RGB32);
        img.fill(QColor(128, 128, 128));

        const SceneSample s = measureScene(img, QRect());
        QVERIFY(s.pixels > 0);

        const double expected = std::pow(128.0 / 255.0, 2.2);
        QVERIFY(qAbs(s.lin.x() - expected) < 1e-4);
        QVERIFY(qAbs(s.luma - expected) < 1e-4);
    }

    /// Область экрана исключается из замера: белый прямоугольник внутри exclude отбрасывается, считаются только оставшиеся точки.
    void excludeSkipsScreen() {
        QImage img(32, 32, QImage::Format_RGB32);
        img.fill(Qt::black);

        const QRect exclude(8, 8, 16, 16);
        for (int y = exclude.top(); y <= exclude.bottom(); ++y) {
            for (int x = exclude.left(); x <= exclude.right(); ++x) {
                img.setPixel(x, y, qRgb(255, 255, 255));
            }
        }

        const SceneSample s = measureScene(img, exclude);
        QVERIFY(qAbs(s.luma - 0.0) < 1e-6);
        QCOMPARE(s.pixels, (1024 - 256) * 9 / 10);
    }

    /// Яркие выбросы отбрасываются: 10 % самых ярких точек (HUD, лампы) не влияют на среднюю яркость сцены.
    void brightOutliersDropped() {
        QImage img(20, 20, QImage::Format_RGB32);
        img.fill(QColor(40, 40, 40));

        // Первые 40 пикселей (строки 0 и 1 целиком) делаем белыми
        for (int y = 0; y < 2; ++y) {
            for (int x = 0; x < 20; ++x) {
                img.setPixel(x, y, qRgb(255, 255, 255));
            }
        }

        const SceneSample s = measureScene(img, QRect());
        const double expected = std::pow(40.0 / 255.0, 2.2);
        QVERIFY(qAbs(s.luma - expected) < 1e-4);
    }

    /// Слишком малое число точек вне исключённой зоны и пустая картинка признаются негодным замером.
    void tooFewPixels() {
        QImage smallImg(3, 5, QImage::Format_RGB32); // 15 точек: меньше 16
        smallImg.fill(Qt::gray);
        const SceneSample sSmall = measureScene(smallImg, QRect());
        QVERIFY(!sSmall.valid());

        QImage nullImg;
        const SceneSample sNull = measureScene(nullImg, QRect());
        QVERIFY(!sNull.valid());
    }

    /// Экспоненциальное сглаживание к новому замеру и корректная обработка негодных отсчётов.
    void smoothing() {
        SceneSample prev;
        prev.lin = QVector3D(0.0f, 0.0f, 0.0f);
        prev.pixels = 1;

        SceneSample next;
        next.lin = QVector3D(1.0f, 1.0f, 1.0f);
        next.pixels = 1;

        const SceneSample s = smoothScene(prev, next, 1.0, 1.0);
        const double expected = 1.0 - std::exp(-1.0);
        QVERIFY(qAbs(s.lin.x() - expected) < 1e-4);

        SceneSample invalidSample;
        invalidSample.pixels = 0;

        const SceneSample resInvalidNext = smoothScene(prev, invalidSample, 1.0, 1.0);
        QCOMPARE(resInvalidNext.pixels, prev.pixels);
        QVERIFY(qAbs(resInvalidNext.lin.x() - prev.lin.x()) < 1e-6);

        const SceneSample resInvalidPrev = smoothScene(invalidSample, next, 1.0, 1.0);
        QCOMPARE(resInvalidPrev.pixels, next.pixels);
        QVERIFY(qAbs(resInvalidPrev.lin.x() - next.lin.x()) < 1e-6);
    }

    /// Настройка кадра для тёмной и светлой сцены, а также монотонность затемнения и отсвета при росте яркости.
    void lookDarkVsBright() {
        SceneSample dark;
        dark.lin = QVector3D(0.002f, 0.002f, 0.002f);
        dark.luma = 0.002;
        dark.pixels = 100;

        const SceneLook lookDark = sceneLookFor(dark);
        QVERIFY(qAbs(lookDark.dim - 0.84) < 1e-4);
        QVERIFY(qAbs(lookDark.glow - 0.26) < 1e-4);
        QVERIFY(qAbs(lookDark.liftAmount - 0.05) < 1e-4);

        SceneSample bright;
        bright.lin = QVector3D(0.5f, 0.5f, 0.5f);
        bright.luma = 0.5;
        bright.pixels = 100;

        const SceneLook lookBright = sceneLookFor(bright);
        QVERIFY(qAbs(lookBright.dim - 1.0) < 1e-4);
        QVERIFY(qAbs(lookBright.glow - 0.08) < 1e-4);
        QVERIFY(qAbs(lookBright.liftAmount - 0.12) < 1e-4);

        const double lumas[] = {0.0, 0.01, 0.05, 0.1, 0.2};
        double prevDim = -1.0;
        double prevGlow = 2.0;
        for (double lumaVal : lumas) {
            SceneSample s;
            s.lin = QVector3D(static_cast<float>(lumaVal), static_cast<float>(lumaVal), static_cast<float>(lumaVal));
            s.luma = lumaVal;
            s.pixels = 100;

            const SceneLook look = sceneLookFor(s);
            QVERIFY(look.dim >= prevDim);
            QVERIFY(look.glow <= prevGlow);

            prevDim = look.dim;
            prevGlow = look.glow;
        }
    }

    /// Цвет подсветки полотна повторяет оттенок освещения зала и переводится в гамму.
    void liftColorFollowsTint() {
        SceneSample s;
        s.lin = QVector3D(0.01f, 0.03f, 0.03f);
        s.luma = 0.02;
        s.pixels = 100;

        const SceneLook look = sceneLookFor(s);
        QVERIFY(look.lift.greenF() > look.lift.redF());
        QVERIFY(look.lift.blueF() > look.lift.redF());

        const double expectedRed = std::pow(0.03, 1.0 / 2.2);
        QVERIFY(qAbs(look.lift.redF() - expectedRed) < 1e-3);
    }

    /// Поведение по умолчанию соответствует тёмному залу; негодный замер возвращает настройки зала по умолчанию.
    void defaultIsDarkHall() {
        const SceneSample def = defaultScene();
        QVERIFY(def.valid());
        QVERIFY(def.luma < 0.03);

        const SceneLook lookDefault = sceneLookFor(def);
        const SceneLook lookInvalid = sceneLookFor(SceneSample{});

        QVERIFY(qAbs(lookInvalid.dim - lookDefault.dim) < 1e-6);
        QVERIFY(qAbs(lookInvalid.liftAmount - lookDefault.liftAmount) < 1e-6);
        QVERIFY(qAbs(lookInvalid.glow - lookDefault.glow) < 1e-6);
        QCOMPARE(lookInvalid.lift, lookDefault.lift);
    }

    /// Таблица ползунков: размер, уникальность id, пределы и значение по умолчанию, поиск по ключу.
    void knobsTable() {
        const auto &knobs = sceneKnobs();
        QCOMPARE(knobs.size(), 10);
        QSet<QString> ids;
        for (const auto &k : knobs) {
            const QString id = QString::fromLatin1(k.id);
            QVERIFY(!ids.contains(id));
            ids.insert(id);
            QVERIFY(k.min < k.max);
            QVERIFY(k.min <= k.def && k.def <= k.max);
        }
        const auto *exposure = sceneKnob(QStringLiteral("exposure"));
        QVERIFY(exposure != nullptr);
        QVERIFY(exposure->title != nullptr && exposure->title[0] != '\0');
        QVERIFY(sceneKnob(QStringLiteral("nope")) == nullptr);
    }

    /// Пределы ползунка, не число, неизвестный ключ; tuningValue без значения и за пределами.
    void knobClamp() {
        QVERIFY(qAbs(sceneKnobClamp(QStringLiteral("exposure"), 5.0) - 1.4) < 1e-9);
        QVERIFY(qAbs(sceneKnobClamp(QStringLiteral("exposure"), 0.1) - 0.6) < 1e-9);
        QVERIFY(qAbs(sceneKnobClamp(QStringLiteral("exposure"), std::nan("")) - 1.0) < 1e-9);
        QVERIFY(qAbs(sceneKnobClamp(QStringLiteral("nope"), 3.0) - 0.0) < 1e-9);
        QVERIFY(qAbs(tuningValue({}, "bloom") - 1.0) < 1e-9);
        QVERIFY(qAbs(tuningValue(SceneTuning{{QStringLiteral("bloom"), 9.0}}, "bloom") - 3.0) < 1e-9);
    }

    /// #define для шейдера: только ползунки с define, три знака после запятой, подстройка хоста не попадает.
    void userDefines() {
        const auto defs = sceneUserDefines({});
        QCOMPARE(defs.size(), 6);
        QCOMPARE(defs.first(), qMakePair(QByteArray("USER_EXPOSURE"), QByteArray("1.000")));
        QVERIFY(defs.contains(qMakePair(QByteArray("USER_SHOULDER"), QByteArray("0.000"))));
        const SceneTuning custom{{QStringLiteral("satur"), 1.234}, {QStringLiteral("adapt"), 2.0}};
        const auto customDefs = sceneUserDefines(custom);
        QVERIFY(customDefs.contains(qMakePair(QByteArray("USER_SATUR"), QByteArray("1.234"))));
        for (const auto &p : customDefs) QVERIFY(!p.first.contains("ADAPT"));
    }

    /// Ползунки подстройки под зал: adapt — глубина затемнения, lift — свет зала, spill — отсвет.
    void tunedLookAppliesKnobs() {
        SceneLook base;
        base.dim = 0.84;
        base.liftAmount = 0.1;
        base.glow = 0.2;
        const auto defLook = tunedLook(base, {});
        QVERIFY(qAbs(defLook.dim - base.dim) < 1e-9);
        QVERIFY(qAbs(defLook.liftAmount - base.liftAmount) < 1e-9);
        QVERIFY(qAbs(defLook.glow - base.glow) < 1e-9);
        const SceneTuning t1{{QStringLiteral("adapt"), 0.0}, {QStringLiteral("lift"), 2.0}, {QStringLiteral("spill"), 3.0}};
        const auto look1 = tunedLook(base, t1);
        QVERIFY(qAbs(look1.dim - 1.0) < 1e-9);
        QVERIFY(qAbs(look1.liftAmount - 0.2) < 1e-9);
        QVERIFY(qAbs(look1.glow - 0.6) < 1e-9);
        const auto look2 = tunedLook(base, SceneTuning{{QStringLiteral("adapt"), 2.0}});
        QVERIFY(qAbs(look2.dim - 0.68) < 1e-9);
    }

    /// Пульт (R.LOOK в remote.lua) и хост — одни и те же ключи, пределы и значения по умолчанию.
    void knobsMatchRemoteLua() {
        QFile file(QString(SM_MOD_DIR) + QStringLiteral("/remote.lua"));
        QVERIFY2(file.open(QIODevice::ReadOnly | QIODevice::Text), "remote.lua не открыть");
        const QString content = QString::fromUtf8(file.readAll());
        for (const auto &k : sceneKnobs()) {
            const QString id = QString::fromLatin1(k.id);
            const QRegularExpression re(
                QStringLiteral("id = \"%1\", title = \"[^\"]*\", min = ([-0-9.]+), max = ([-0-9.]+), def = ([-0-9.]+)")
                    .arg(id));
            const auto match = re.match(content);
            QVERIFY2(match.hasMatch(), qPrintable(id));
            QVERIFY2(qAbs(match.captured(1).toDouble() - k.min) < 1e-9, qPrintable(id));
            QVERIFY2(qAbs(match.captured(2).toDouble() - k.max) < 1e-9, qPrintable(id));
            QVERIFY2(qAbs(match.captured(3).toDouble() - k.def) < 1e-9, qPrintable(id));
        }
    }
};

QTEST_APPLESS_MAIN(SceneLightTest)

#include "SceneLightTest.moc"
