#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"

#include <QFile>
#include <QTemporaryDir>
#include <QTest>

using namespace suslik::core;
#define S QStringLiteral

class StoreTest : public QObject {
    Q_OBJECT
    QTemporaryDir dir;
private slots:
    void init() {
        Store::resetForTests();
        Store::setDirectory(dir.path());
        for (const QString &f : QDir(dir.path()).entryList(QDir::Files)) QFile::remove(dir.filePath(f));
    }

    void propertiesRoundTrip() {
        const QHash<QString, QString> v{{S("1|Сериал/Сезон 1/E01.mkv"), S("120000|2400000|1700000000000")},
                                        {S("key with spaces=and:colons"), S(" leading space, #hash !bang \\slash")},
                                        {S("audio:m42"), S("Дубляж [Russian]\tтаб\nстрока")}};
        const QByteArray text = Store::writeProperties(v);
        for (char c : text) QVERIFY2(uchar(c) < 0x80, "only ASCII on disk, like java.util.Properties");
        QCOMPARE(Store::parseProperties(text), v);
    }

    void readsJavaPropertiesFile() {
        // Так пишет java.util.Properties.store() у Kotlin-версии
        const QByteArray java = "#Fri Oct 31 15:00:00 MSK 2026\n"
                                "audio\\:m42=\\u0414\\u0443\\u0431\\u043B\\u044F\\u0436\n"
                                "  ! комментарий\n"
                                "long=first \\\n    second\n"
                                "colon: value\n"
                                "spaced   value with spaces\n";
        const auto v = Store::parseProperties(java);
        QCOMPARE(v.value(S("audio:m42")), S("Дубляж"));
        QCOMPARE(v.value(S("long")), S("first second"));
        QCOMPARE(v.value(S("colon")), S("value"));
        QCOMPARE(v.value(S("spaced")), S("value with spaces"));
        QCOMPARE(v.size(), 4);
    }

    void savesAndReloads() {
        Store &s = Store::of(S("watch"));
        s.put(S("1|a.mkv"), S("10|20|30"));
        s.put(S("2|b.mkv"), S("x"));
        s.put(S("2|b.mkv"), std::nullopt);
        s.flush();
        Store::resetForTests();
        Store &again = Store::of(S("watch"));
        QCOMPARE(again.get(S("1|a.mkv")).value(), S("10|20|30"));
        QVERIFY(!again.get(S("2|b.mkv")));
        QCOMPARE(again.keys().size(), 1);
    }

    void damagedFileFallsBackToCopy() {
        Store &s = Store::of(S("cookies"));
        s.put(S("sid"), S("one"));
        s.flush();
        s.put(S("sid"), S("two"));
        s.flush(); // теперь cookies.properties.bak — с "one"
        Store::resetForTests();
        QFile f(dir.filePath(S("cookies.properties")));
        QVERIFY(f.open(QIODevice::WriteOnly | QIODevice::Truncate));
        f.write(QByteArray(64, '\0')); // после выключения питания
        f.close();
        QCOMPARE(Store::of(S("cookies")).get(S("sid")).value(), S("one"));
    }

    void controlCharactersAreDropped() {
        QFile f(dir.filePath(S("cookies.properties")));
        QVERIFY(f.open(QIODevice::WriteOnly));
        f.write("good=1\nbad=a\\u0001b\n");
        f.close();
        Store &s = Store::of(S("cookies"));
        QCOMPARE(s.get(S("good")).value(), S("1"));
        QVERIFY(!s.get(S("bad")));
    }

    void stringsWithParams() {
        Strings::load(R"j({"a":"Ошибка сервера ({code})","b":"Б"})j", R"j({"b":"B"})j");
        QCOMPARE(tx(S("a"), {{"code", S("502")}}), S("Ошибка сервера (502)"));
        QCOMPARE(tx(S("b")), S("B"));
        QCOMPARE(tx(S("missing.key")), S("missing.key"));
    }
};

QTEST_GUILESS_MAIN(StoreTest)
#include "StoreTest.moc"
