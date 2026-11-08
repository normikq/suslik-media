#include "ProjectorLook.h"

#include <QFile>
#include <QRegularExpression>
#include <QSet>
#include <QString>
#include <QtTest>

using namespace suslik::cinema;

namespace {
/// Прочитать шейдер из каталога GRAINLIGHT по имени файла.
QByteArray readShader(const char *name) {
    QFile file(QString(SM_GRAINLIGHT_DIR) + '/' + name);
    if (!file.open(QIODevice::ReadOnly)) {
        return {};
    }
    return file.readAll();
}
} // namespace

class ProjectorLookTest : public QObject {
    Q_OBJECT
private slots:
    /// Таблица пресетов: количество, выключенный пресет, уникальность id, ограничение индекса.
    void presetsTable() {
        QCOMPARE(projectorCount(), 7);

        const auto &presets = projectorPresets();
        QCOMPARE(presets.size(), 7);

        // Пресет 0 «off»: пустая база и пустые переопределения
        QCOMPARE(presets[0].id, "off");
        QVERIFY(QByteArray(presets[0].base).isEmpty());
        QVERIFY(presets[0].defines.isEmpty());

        // Все id уникальны
        QSet<QString> ids;
        for (const auto &p : presets) {
            const QString id = QString::fromUtf8(p.id);
            QVERIFY(!ids.contains(id));
            ids.insert(id);
        }

        // Ограничение индекса пресета
        QCOMPARE(projectorClamp(-1), 0);
        QCOMPARE(projectorClamp(7), 0);
        QCOMPARE(projectorClamp(3), 3);
    }

    /// Базовые шейдеры для всех активных пресетов существуют и содержат главные хуки mpv.
    void baseShadersExist() {
        for (const auto &p : projectorPresets()) {
            if (p.base && p.base[0] != '\0') {
                const QByteArray shader = readShader(p.base);
                QVERIFY(!shader.isEmpty());
                QVERIFY(shader.contains("//!HOOK MAIN"));
                QVERIFY(shader.contains("//!HOOK OUTPUT"));
            }
        }
    }

    /// Переопределения #define попадают в существующие строки базового шейдера и корректно подставляются.
    void overridesHitRealDefines() {
        for (const auto &p : projectorPresets()) {
            if (p.defines.isEmpty()) {
                continue;
            }
            const QByteArray base = readShader(p.base);
            QVERIFY(!base.isEmpty());

            const QString baseStr = QString::fromUtf8(base);
            for (const auto &[name, value] : p.defines) {
                const QRegularExpression reDefine("#define[ \t]+" + QString::fromUtf8(name) + "[ \t]");
                QVERIFY(baseStr.contains(reDefine));
            }

            const QByteArray patched = patchDefines(base, p.defines);
            QVERIFY(patched != base);

            const QString patchedStr = QString::fromUtf8(patched);
            for (const auto &[name, value] : p.defines) {
                const QString pat = "#define[ \t]+" + QRegularExpression::escape(QString::fromUtf8(name)) +
                                    "[ \t]+" + QRegularExpression::escape(QString::fromUtf8(value)) + "([^0-9]|$)";
                const QRegularExpression rePatched(pat);
                QVERIFY(patchedStr.contains(rePatched));
            }
        }
    }

    /// Подстановка сохраняет комментарии и пробелы, не трогает префиксы других макросов и неизвестные имена.
    void patchKeepsComments() {
        const QByteArray src = "#define GRAIN       0.052   // intensidad\n#define GRAIN_SIZE  2.2\n";
        const QByteArray expected = "#define GRAIN       0.090   // intensidad\n#define GRAIN_SIZE  2.2\n";
        QCOMPARE(patchDefines(src, {{"GRAIN", "0.090"}}), expected);

        // Неизвестное имя оставляет текст без изменений
        QCOMPARE(patchDefines(src, {{"UNKNOWN", "1.0"}}), src);
    }

    /// Фильтры звука плёнки: количество, пустой для 0, lavfi-строки для 1 и 2.
    void filmSound() {
        QCOMPARE(filmSoundCount(), 3);
        QVERIFY(filmSoundFilter(0).isEmpty());

        const QByteArray f1 = filmSoundFilter(1);
        const QByteArray f2 = filmSoundFilter(2);

        QVERIFY(f1.startsWith("@film:lavfi=[") && f1.endsWith(']'));
        QVERIFY(f2.startsWith("@film:lavfi=[") && f2.endsWith(']'));

        QVERIFY(f1.contains("highpass=f=55"));
        QVERIFY(f2.contains("vibrato=f=0.55"));
    }
};

QTEST_GUILESS_MAIN(ProjectorLookTest)
#include "ProjectorLookTest.moc"
