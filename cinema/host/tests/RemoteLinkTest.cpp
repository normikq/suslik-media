#include "RemoteLink.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QtTest>

using suslik::cinema::RemoteLink;

namespace {
QJsonObject file(const QString &sid, std::initializer_list<std::pair<qint64, qint64>> cmds) {
    QJsonArray a;
    for (const auto &[n, at] : cmds) a.append(QJsonObject{{"n", n}, {"at", at}, {"op", "toggle"}});
    return QJsonObject{{"sid", sid}, {"cmds", a}};
}
QList<qint64> ns(const QList<QJsonObject> &l) {
    QList<qint64> out;
    for (const auto &c : l) out << c.value("n").toInteger();
    return out;
}
} // namespace

class RemoteLinkTest : public QObject {
    Q_OBJECT
private slots:
    void newCommandsOnce() {
        RemoteLink r;
        const qint64 now = 1000;
        QCOMPARE(ns(r.takeNew(file("a", {{1, now}, {2, now}}), now)), (QList<qint64>{1, 2}));
        // тот же файл ещё раз — ничего нового
        QVERIFY(r.takeNew(file("a", {{1, now}, {2, now}}), now).isEmpty());
        // мод дописал команду, старые остались в файле
        QCOMPARE(ns(r.takeNew(file("a", {{1, now}, {2, now}, {3, now + 1}}), now + 1)), (QList<qint64>{3}));
    }

    void staleCommandsSkipped() {
        RemoteLink r;
        // хост запустился позже: команды старше 15 с не выполняем (play часовой давности не нужен)
        QCOMPARE(ns(r.takeNew(file("a", {{1, 900}, {2, 990}}), 1000)), (QList<qint64>{2}));
        // в той же сессии — до 30 с (хост был занят)
        QCOMPARE(ns(r.takeNew(file("a", {{3, 975}}), 1000)), (QList<qint64>{3}));
        QVERIFY(r.takeNew(file("a", {{4, 960}}), 1000).isEmpty());
    }

    void newModSessionStartsOver() {
        RemoteLink r;
        r.takeNew(file("a", {{5, 1000}}), 1000);
        // игру перезапустили: мод снова считает с 1, другой sid
        QCOMPARE(ns(r.takeNew(file("b", {{1, 1010}}), 1010)), (QList<qint64>{1}));
    }

    void brokenFileIsHarmless() {
        RemoteLink r;
        QVERIFY(r.takeNew(QJsonObject{}, 1000).isEmpty());
        QVERIFY(r.takeNew(QJsonObject{{"sid", "a"}, {"cmds", "x"}}, 1000).isEmpty());
    }
};

QTEST_GUILESS_MAIN(RemoteLinkTest)
#include "RemoteLinkTest.moc"
