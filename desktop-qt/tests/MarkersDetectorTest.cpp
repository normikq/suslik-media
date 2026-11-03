// Перенос android/core/src/test/.../MarkersDetectorTest.kt.
#include "suslik/core/MarkersDetector.h"
#include "TestUtil.h"

using namespace suslik::core;
using namespace suslik::core::MarkersDetector;
using namespace testutil;

namespace {
constexpr qint64 len = 45 * 60000LL;
Track track(int id, std::optional<QString> title, std::optional<QString> lang, bool forced = false) { return Track{id, title, lang, forced}; }
const std::optional<QString> none = std::nullopt;
} // namespace

class MarkersDetectorTest : public QObject {
    Q_OBJECT
    const QList<Track> subs{track(1, S("Полные"), S("rus")), track(2, S("Форсированные"), S("rus"), true), track(3, S("Full"), S("eng"))};
private slots:
    void introAndCreditsFromChapters() {
        const auto m = fromChapters({{0, S("Пролог")}, {90000, S("Заставка")}, {180000, S("Эпизод")}, {len - 120000, S("Титры")}, {len - 30000, S("Preview")}}, len);
        QVERIFY(m.intro == (TimeRange{90000, 180000}));
        QVERIFY(m.credits == (TimeRange{len - 120000, len}));
        QCOMPARE(nextOfferAt(m, len), std::optional<qint64>(len - 120000));
    }

    void englishAndShortNames() {
        const auto m = fromChapters({{0, S("Chapter 1")}, {60000, S("OP")}, {150000, S("Part A")}, {len - 90000, S("ED 2")}}, len);
        QVERIFY(m.intro == (TimeRange{60000, 150000}));
        QCOMPARE(m.credits->startMs, len - 90000);
        QVERIFY(fromChapters({{0, S("Edward")}, {60000, S("Opera")}, {len - 60000, S("Openly")}}, len).isEmpty());
    }

    void introWithoutNextChapterIsCapped() {
        const auto m = fromChapters({{0, S("Начало")}, {30000, S("Opening Credits")}}, len);
        QVERIFY(!m.credits);
        QVERIFY(!m.intro);
        QVERIFY(fromChapters({{0, S("Intro")}}, len).intro == (TimeRange{0, INTRO_MAX_MS}));
    }

    void creditsInTheFirstHalfAreIgnored() { QVERIFY(!fromChapters({{0, S("Credits")}, {60000, S("Story")}}, len).credits); }

    void serverFillsWhatChaptersLack() {
        MediaMarkers file;
        file.intro = TimeRange{10000, 100000};
        const QJsonObject j = json("{\"intro\":{\"startMs\":1,\"endMs\":2},\"credits\":{\"startMs\":2600000,\"endMs\":2690000}}");
        const auto m = merge(file, parseServer(&j), len);
        QVERIFY(m.intro == (TimeRange{10000, 100000}));
        QVERIFY(m.credits == (TimeRange{2600000, len}));
        MediaMarkers late;
        late.credits = TimeRange{len + 1, len + 9};
        QVERIFY(!merge(MediaMarkers{}, late, len).credits);
    }

    void serverPathEncodes() {
        QCOMPARE(serverPath(S("Frieren"), S("Фрирен"), std::nullopt, 3, 1420500).value(),
                 S("/api/markers?title=Frieren&alt=%D0%A4%D1%80%D0%B8%D1%80%D0%B5%D0%BD&season=1&episode=3&duration=1420"));
        QCOMPARE(serverPath(S("Dune (2021)"), none, 1, std::nullopt, 0).value(), S("/api/markers?title=Dune+%282021%29"));
        QVERIFY(!serverPath(S(" "), none, 1, 2, 0));
        QCOMPARE(serverPath(S("Фрирен / Frieren"), none, 1, 2, 0).value(), S("/api/markers?title=%D0%A4%D1%80%D0%B8%D1%80%D0%B5%D0%BD+%2F+Frieren&alt=%D0%A4%D1%80%D0%B8%D1%80%D0%B5%D0%BD&season=1&episode=2"));
    }

    void introAtSkipsTheLastSeconds() {
        MediaMarkers m;
        m.intro = TimeRange{60000, 150000};
        QVERIFY(introAt(m, 61000) == m.intro);
        QVERIFY(!introAt(m, 149000));
        QVERIFY(!introAt(m, 10000));
    }

    void filmOfACollection() {
        QCOMPARE(filmTitle(S("2 Harry Potter and the Chamber of Secrets (2002).mkv")).value(), S("Harry Potter and the Chamber of Secrets (2002)"));
        QVERIFY(!filmTitle(S("01.mkv")));
    }

    void russianSpeechTakesForcedSubtitles() {
        QVERIFY(pickSubtitle(track(1, S("Дубляж"), S("rus")), subs) == SubChoice::pick(2));
        QVERIFY(pickSubtitle(track(1, S("MVO LostFilm"), none), subs) == SubChoice::pick(2));
        QVERIFY(pickSubtitle(track(1, none, S("rus")), {subs[0], subs[2]}) == SubChoice::off());
        QVERIFY(pickSubtitle(track(1, S("Кубик в Кубе"), none), {track(6, S("Полные"), none), track(7, S("Надписи"), none)}) == SubChoice::pick(7));
    }

    void foreignSpeechTakesFullRussianSubtitles() {
        QVERIFY(pickSubtitle(track(5, S("Original"), S("eng")), subs) == SubChoice::pick(1));
        QVERIFY(pickSubtitle(track(5, none, S("jpn")), subs) == SubChoice::pick(1));
        QVERIFY(pickSubtitle(track(5, S("Оригинал"), none), {track(8, S("Russian SDH"), S("rus")), track(9, S("Полные (Rus)"), S("rus"))}) == SubChoice::pick(9));
        QVERIFY(pickSubtitle(track(5, none, S("eng")), {track(3, S("Full"), S("eng"))}) == SubChoice::keep());
    }

    void unknownSpeechChangesNothing() {
        QVERIFY(pickSubtitle(track(1, S("Track 1"), none), subs) == SubChoice::keep());
        QVERIFY(pickSubtitle(std::nullopt, subs) == SubChoice::keep());
    }

    void cyrillicCaseInsensitive() {
        QVERIFY(isIntroTitle(S("ЗАСТАВКА")));
        QVERIFY(isCreditsTitle(S("Финальные ТИТРЫ")));
    }
};

QTEST_APPLESS_MAIN(MarkersDetectorTest)
#include "MarkersDetectorTest.moc"
