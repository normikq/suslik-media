#include "RoomScreen.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QtTest>

using namespace suslik::cinema;

class RoomScreenTest : public QObject {
    Q_OBJECT
private slots:
    /// Полный JSON экрана из screens.json: проверяем все ключи и значения в объекте.
    void calibToScreen() {
        const QByteArray input =
            "{\"version\":1,\"c\":[-1032.5,1520.25,7.5],\"n\":[0,-1,0],\"w\":4.2,\"h\":2.3625,"
            "\"sr\":0.1,\"su\":-0.2,\"sn\":0.05,\"lock\":true,\"ar\":1.7778,\"fit\":\"fill\",\"occl\":true,\"od\":0.6}";
        const QJsonObject o = screenFromCalib(input);
        const QStringList expectedKeys = {"ar", "c", "fit", "h", "n", "sn", "sr", "su", "w"};
        QStringList keys = o.keys();
        keys.sort();
        QCOMPARE(keys, expectedKeys);
        const QJsonArray expectedC = {-1032.5, 1520.25, 7.5};
        QCOMPARE(o.value("c").toArray(), expectedC);
        QCOMPARE(o.value("w").toDouble(), 4.2);
        QCOMPARE(o.value("fit").toString(), QStringLiteral("fill"));
        QCOMPARE(o.value("sn").toDouble(), 0.05);
    }

    /// Старая калибровка без поля ar: lock = true даёт 16/9, lock = false даёт 0.
    void oldCalibWithoutAr() {
        const QByteArray withLock =
            "{\"c\":[0,0,0],\"n\":[0,1,0],\"w\":1,\"h\":1,\"lock\":true}";
        const QJsonObject oLock = screenFromCalib(withLock);
        QVERIFY(qAbs(oLock.value("ar").toDouble() - (16.0 / 9.0)) < 1e-9);

        const QByteArray withoutLock =
            "{\"c\":[0,0,0],\"n\":[0,1,0],\"w\":1,\"h\":1,\"lock\":false}";
        const QJsonObject oNoLock = screenFromCalib(withoutLock);
        QCOMPARE(oNoLock.value("ar").toDouble(), 0.0);
    }

    /// Некорректные входные данные: пусто, не JSON, битые векторы, неположительные размеры.
    void badCalib() {
        QVERIFY(screenFromCalib(QByteArray()).isEmpty());
        QVERIFY(screenFromCalib(QByteArray("{}")).isEmpty());
        QVERIFY(screenFromCalib("{\"c\":[1,2],\"n\":[0,1,0],\"w\":1,\"h\":1}").isEmpty());
        QVERIFY(screenFromCalib("{\"c\":[0,0,0],\"n\":[0,\"1\",0],\"w\":1,\"h\":1}").isEmpty());
        QVERIFY(screenFromCalib("{\"c\":[0,0,0],\"n\":[0,1,0],\"w\":0,\"h\":1}").isEmpty());
        QVERIFY(screenFromCalib("{\"c\":[0,0,0],\"n\":[0,1,0],\"w\":1,\"h\":-1}").isEmpty());
        QVERIFY(screenFromCalib("abc").isEmpty());
    }

    /// Неизвестный режим fit сбрасывается в «fit», отсутствующие сдвиги sr/su/sn по умолчанию 0.
    void unknownFit() {
        const QByteArray input =
            "{\"c\":[0,0,0],\"n\":[0,1,0],\"w\":2,\"h\":1,\"fit\":\"zoom\"}";
        const QJsonObject o = screenFromCalib(input);
        QCOMPARE(o.value("fit").toString(), QStringLiteral("fit"));
        QCOMPARE(o.value("sr").toDouble(), 0.0);
    }

    /// Формирование room-screen.json и чтение обратно: содержит id, парсится Lua-паттерном, данные совпадают.
    void roomFileRoundTrip() {
        const QByteArray input =
            "{\"version\":1,\"c\":[-1032.5,1520.25,7.5],\"n\":[0,-1,0],\"w\":4.2,\"h\":2.3625,"
            "\"sr\":0.1,\"su\":-0.2,\"sn\":0.05,\"lock\":true,\"ar\":1.7778,\"fit\":\"fill\",\"occl\":true,\"od\":0.6}";
        const QJsonObject screen = screenFromCalib(input);
        const QByteArray f = roomScreenFile(screen, 1794146054000);
        QVERIFY(f.endsWith('\n'));
        QVERIFY(f.contains("\"id\":1794146054000"));

        const QRegularExpression re("\"c\":\\[([^,]+),([^,]+),([^\\]]+)\\]");
        QVERIFY(re.match(QString::fromUtf8(f)).hasMatch());

        QJsonObject parsed = QJsonDocument::fromJson(f).object();
        parsed.remove("id");
        QCOMPARE(parsed, screen);
    }

    /// Отказ формирования файла для недопустимых параметров экрана.
    void roomFileRejectsBad() {
        QVERIFY(roomScreenFile(QJsonObject(), 1).isEmpty());

        QJsonObject badScreen;
        badScreen.insert("c", QJsonArray{0, 0, 0});
        badScreen.insert("n", QJsonArray{0, 1, 0});
        badScreen.insert("w", 0);
        badScreen.insert("h", 1);
        QVERIFY(roomScreenFile(badScreen, 1).isEmpty());
    }
};

QTEST_APPLESS_MAIN(RoomScreenTest)
#include "RoomScreenTest.moc"
