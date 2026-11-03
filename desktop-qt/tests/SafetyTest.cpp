#include "suslik/core/Safety.h"
#include "../core/src/QtWait.h"

#include <QAbstractEventDispatcher>
#include <QTest>
#include <thread>

using namespace suslik::core;
#define S QStringLiteral

class SafetyTest : public QObject {
    Q_OBJECT
private slots:
    void sha256IsRequired() {
        const QString h = S("0fc3d4a64453fc65b801a687cede8196899788a81a4cd082a9ea477fc418b10f");
        QVERIFY(Safety::sha256Matches(h, h));
        QVERIFY(Safety::sha256Matches(h.toUpper(), h));
        QVERIFY(!Safety::sha256Matches(QString(), h));            // сервер не дал суммы — не ставим
        QVERIFY(!Safety::sha256Matches(QString(), QString()));
        QVERIFY(!Safety::sha256Matches(S("abc"), S("abc")));       // не SHA-256
        QVERIFY(!Safety::sha256Matches(h, QString(h).replace(0, 1, S("1"))));
    }

    void windowsArgQuoting() {
        QCOMPARE(Safety::quoteWindowsArg(S("--headless=new")), S("--headless=new"));
        QCOMPARE(Safety::quoteWindowsArg(S("C:\\dir\\")), S("C:\\dir\\"));                 // без пробелов — как есть
        QCOMPARE(Safety::quoteWindowsArg(QString()), S("\"\""));
        QCOMPARE(Safety::quoteWindowsArg(S("a b")), S("\"a b\""));
        // завершающий «\» перед закрывающей кавычкой удваивается
        QCOMPARE(Safety::quoteWindowsArg(S("--user-data-dir=C:\\My Profile\\")), S("\"--user-data-dir=C:\\My Profile\\\\\""));
        QCOMPARE(Safety::quoteWindowsArg(S("C:\\Program Files\\App"), true), S("\"C:\\Program Files\\App\""));
        QCOMPARE(Safety::quoteWindowsArg(S("C:\\"), true), S("\"C:\\\\\""));
        // кавычка внутри: «\"», слэши перед ней удваиваются
        QCOMPARE(Safety::quoteWindowsArg(S("say \"hi\"")), S("\"say \\\"hi\\\"\""));
        QCOMPARE(Safety::quoteWindowsArg(S("a\\\"b")), S("\"a\\\\\\\"b\""));
        QCOMPARE(Safety::quoteWindowsArg(S("a\\b c")), S("\"a\\b c\""));                    // слэши не у кавычки — как есть
    }

    void rateLimit() {
        Safety::RateLimit l(2, 1000);
        QVERIFY(l.allow(0));
        QVERIFY(l.allow(10));
        QVERIFY(!l.allow(500));
        QVERIFY(l.allow(1000));  // первое вышло из окна
        QVERIFY(!l.allow(1005));
        QVERIFY(l.allow(1010));
    }

    void ensureDispatcherCreatesOne() {
        // поток не через QThread: диспетчер заводит конструктор QEventLoop, exec() не нужен
        bool has = false;
        std::thread t([&has] {
            suslik::core::detail::ensureDispatcher();
            has = QAbstractEventDispatcher::instance() != nullptr;
        });
        t.join();
        QVERIFY(has);
    }
};

QTEST_GUILESS_MAIN(SafetyTest)
#include "SafetyTest.moc"
