#include "ReleasePick.h"

#include <QtTest>

using namespace suslik;
using cinema::Choice;
using cinema::isMirrorUrl;
using cinema::pickVariant;

namespace {

Choice choice(qint64 id, const QString &quality, const QString &label, int score) {
    Choice c;
    c.version.m.id = id;
    c.variant.key = QStringLiteral("%1|film.mkv").arg(id);
    c.variant.quality = quality;
    c.variant.label = label;
    c.variant.score = score;
    return c;
}

/// Как после choicesFor: по счёту по убыванию.
QList<Choice> films() {
    return {choice(30, QStringLiteral("4K"), QStringLiteral("4K · Dolby Vision · BDRemux · TrueHD Atmos (58 ГБ)"), 530),
            choice(20, QStringLiteral("1080p"), QStringLiteral("1080p · BDRemux · DTS-HD MA (31 ГБ)"), 355),
            choice(10, QStringLiteral("1080p"), QStringLiteral("1080p · WEB-DL · DD+ (6 ГБ)"), 330)};
}

} // namespace

class ReleasePickTest : public QObject {
    Q_OBJECT
private slots:
    void emptyHasNothing() { QVERIFY(!pickVariant({}, 0, {}, {})); }

    void bestByDefault() { QCOMPARE(pickVariant(films(), 0, {}, {}), std::optional<int>(0)); }

    void explicitRelease() {
        QCOMPARE(pickVariant(films(), 10, {}, {}), std::optional<int>(2));
        QVERIFY(!pickVariant(films(), 99, {}, {}));
    }

    void explicitQualityIgnoresCase() {
        QCOMPARE(pickVariant(films(), 0, QStringLiteral("4k"), {}), std::optional<int>(0));
        // одно качество у двух раздач — лучшая из них
        QCOMPARE(pickVariant(films(), 0, QStringLiteral("1080P"), {}), std::optional<int>(1));
    }

    void explicitQualityByLabel() {
        QCOMPARE(pickVariant(films(), 0, QStringLiteral("web-dl"), {}), std::optional<int>(2));
        QVERIFY(!pickVariant(films(), 0, QStringLiteral("720p"), {}));
    }

    void savedQualityLikePcPlayer() {
        // PlayerModel: качество или подпись целиком, без учёта регистра не сравнивает
        QCOMPARE(pickVariant(films(), 0, {}, {QStringLiteral("1080p")}), std::optional<int>(1));
        QCOMPARE(pickVariant(films(), 0, {}, {QStringLiteral("1080p · WEB-DL · DD+ (6 ГБ)")}), std::optional<int>(2));
        // первое сохранённое, которого нет, пропускается
        QCOMPARE(pickVariant(films(), 0, {}, {QStringLiteral("720p"), QStringLiteral("4K")}), std::optional<int>(0));
        QCOMPARE(pickVariant(films(), 0, {}, {QStringLiteral("720p")}), std::optional<int>(0));
    }

    void explicitBeatsSaved() {
        QCOMPARE(pickVariant(films(), 0, QStringLiteral("4K"), {QStringLiteral("1080p")}), std::optional<int>(0));
        QCOMPARE(pickVariant(films(), 20, {}, {QStringLiteral("4K")}), std::optional<int>(1));
    }

    void mirrorOnly() {
        const QString base = QStringLiteral("https://media.example");
        QVERIFY(isMirrorUrl(QStringLiteral("https://media.example/api/file/proxy/Film.mkv?link=x&exp=1&sig=y"), base));
        // прямые ссылки AllDebrid и чужие сайты — никогда
        QVERIFY(!isMirrorUrl(QStringLiteral("https://abc.debrid.it/dl/xyz/Film.mkv"), base));
        QVERIFY(!isMirrorUrl(QStringLiteral("https://alldebrid.com/f/xyz"), base));
        QVERIFY(!isMirrorUrl(QStringLiteral("https://evil.example/api/file/proxy/Film.mkv"), base));
        // свой сервер, но не зеркало
        QVERIFY(!isMirrorUrl(QStringLiteral("https://media.example/api/file/mirror-url?link=x"), base));
        QVERIFY(!isMirrorUrl(QStringLiteral("https://media.example/media/"), base));
        // без шифрования — только локальный сервер
        QVERIFY(!isMirrorUrl(QStringLiteral("http://media.example/api/file/proxy/Film.mkv"), base));
        QVERIFY(isMirrorUrl(QStringLiteral("http://127.0.0.1:3000/api/file/proxy/Film.mkv"), QStringLiteral("http://127.0.0.1:3000")));
        QVERIFY(!isMirrorUrl(QStringLiteral("https://media.example/api/file/proxy/Film.mkv"), QString()));
    }

    void mirrorUnderServerPath() {
        const QString base = QStringLiteral("https://host.example/suslik");
        QVERIFY(isMirrorUrl(QStringLiteral("https://host.example/suslik/api/file/proxy/Film.mkv?sig=1"), base));
        QVERIFY(!isMirrorUrl(QStringLiteral("https://host.example/api/file/proxy/Film.mkv?sig=1"), base));
    }
};

QTEST_GUILESS_MAIN(ReleasePickTest)
#include "ReleasePickTest.moc"
