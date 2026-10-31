// Перенос shared/src/test/.../SeriesGroupingTest.kt (SeriesGroupingTest и AiTitleSearchGroupingTest).
#include "TestUtil.h"

#include <QRandomGenerator>

using namespace suslik::core;
using namespace testutil;

namespace {
constexpr qint64 MB = 1024LL * 1024;

Magnet mag(const QString &name, const QList<std::pair<QString, qint64>> &files) {
    Magnet m = magnet(1, name, S("Ready"), 4, 0, 0, {});
    for (qsizetype i = 0; i < files.size(); ++i) {
        m.files << MFile{int(i), afterLastSlash(files[i].first), files[i].first, false, files[i].second, S("https://x/%1").arg(i)};
        m.size += files[i].second;
    }
    return m;
}

LibraryItem one(const Magnet &m) { return prepareItems({m}).first(); }

QList<MFile> seasonFiles(int season, qint64 sizeMb) {
    QList<MFile> out;
    for (int i = 1; i <= 10; ++i)
        out << MFile{i, S("Cyberpunk Edgerunners S0%1E%2.mkv").arg(season).arg(i), S("Season %1/E%2.mkv").arg(season).arg(i), false, sizeMb * MB, std::nullopt};
    return out;
}

LibraryItem single(qint64 id, const QString &name, qint64 sizeMb, const QList<MFile> &files) {
    return one(magnet(id, name, S("Ready"), 4, sizeMb * MB, 0, files));
}

MFile plain(const QString &name, qint64 sizeMb) { return MFile{1, name, name, false, sizeMb * MB, std::nullopt}; }

QList<int> seasonsOf(const Playable &p) {
    QList<int> out;
    for (const auto &s : p.seasons) out << s.season;
    return out;
}

AiTitle acceptedDune() {
    AiTitle a;
    a.base = S("Дюна"); a.model = S("m"); a.at = 1; a.status = S("accepted"); a.kind = S("movie"); a.original = S("Dune"); a.year = 2021;
    return a;
}

Magnet movie(qint64 id, const QString &name, std::optional<AiTitle> ai) {
    Magnet m = magnet(id, name, S("Ready"), 4, 4000 * MB, 0, {MFile{0, name + S(".mkv"), name + S(".mkv"), false, 4000 * MB, S("https://x/%1").arg(id)}});
    m.aiTitle = ai;
    return m;
}

AiTitle ai(const QString &base, std::optional<int> season, const QString &kind) {
    AiTitle a;
    a.base = base; a.season = season; a.model = S("m"); a.at = 1; a.status = S("accepted"); a.kind = kind;
    return a;
}
} // namespace

class SeriesGroupingTest : public QObject {
    Q_OBJECT
private slots:
    void seasonMarksDoNotSplitTheTitle() {
        const QString a = titleKey(cleanTitle(S("Breaking.Bad.S01.1080p.BluRay")));
        QCOMPARE(a, S("breaking bad"));
        QCOMPARE(titleKey(cleanTitle(S("Breaking Bad Season 2 1080p"))), a);
        QCOMPARE(titleKey(cleanTitle(S("Breaking Bad [1-3 сезон] 1080p"))), a);
        QCOMPARE(titleKey(cleanTitle(S("Breaking Bad Complete 720p"))), a);
    }

    void differentShowsKeepDifferentKeys() { QVERIFY(titleKey(S("Cyberpunk Edgerunners")) != titleKey(S("Cyberpunk 2077"))); }

    void numberedEpisodesAreASeriesNotACollection() {
        QList<std::pair<QString, qint64>> files;
        for (int i = 1; i <= 10; ++i) files << std::pair{S("Show/Show - %1 [1080p].mkv").arg(i, 2, 10, QLatin1Char('0')), 800 * MB};
        const auto p = buildPlayable(mag(S("Show [WEB-DL 1080p]"), files));
        QVERIFY(p.isSeries());
        QCOMPARE(p.seasons.size(), 1);
        QCOMPARE(p.seasons[0].items.size(), 10);
        QCOMPARE(p.seasons[0].items.first().episode, 1);
        QVERIFY(!p.seasons[0].items.first().title);
    }

    void seasonFromTheReleaseNameWhenFilesDoNotSayIt() {
        QCOMPARE(nameSeason(S("Show.S02.1080p")), std::optional<int>(2));
        QCOMPARE(nameSeason(S("Сериал / Сезон: 2 / Серии: 1-10 из 10")), std::optional<int>(2));
        QCOMPARE(nameSeason(S("Сериал (2-й сезон) [2024]")), std::optional<int>(2));
        QVERIFY(!nameSeason(S("Show S01-S03")));
        QVERIFY(!nameSeason(S("Сериал [1-3 сезон]")));
        QVERIFY(!nameSeason(S("Сериал (2 сезона)")));
        QVERIFY(!nameSeason(S("Cyberpunk 2077")));
        QList<std::pair<QString, qint64>> f1, f2, f3;
        for (int i = 1; i <= 3; ++i) {
            f1 << std::pair{S("Show.S02.1080p/Show.E0%1.mkv").arg(i), 800 * MB};
            f2 << std::pair{S("Сериал/Сериал - 0%1 [1080p].mkv").arg(i), 800 * MB};
        }
        for (int i = 1; i <= 2; ++i) f3 << std::pair{S("Show/Show.S01E0%1.mkv").arg(i), 800 * MB};
        const auto ep = buildPlayable(mag(S("Show.S02.1080p"), f1));
        QVERIFY(ep.isSeries());
        QCOMPARE(seasonsOf(ep), (QList<int>{2}));
        const auto num = buildPlayable(mag(S("Сериал (2 сезон) WEB-DL"), f2));
        QVERIFY(num.isSeries());
        QCOMPARE(seasonsOf(num), (QList<int>{2}));
        QCOMPARE(num.seasons[0].items.first().season, 2);
        const auto own = buildPlayable(mag(S("Show S02"), f3)); // свой сезон файлов важнее названия
        QVERIFY(own.isSeries());
        QCOMPARE(seasonsOf(own), (QList<int>{1}));
    }

    void watchedSeasonIsTheOneHoldingTheFile() {
        QList<std::pair<QString, qint64>> files;
        for (int s = 1; s <= 2; ++s)
            for (int e = 1; e <= 3; ++e) files << std::pair{S("Show/Season %1/E0%2.mkv").arg(s).arg(e), 800 * MB};
        const LibraryItem show = one(mag(S("Show"), files));
        QCOMPARE(watchedSeason(show, S("Show/Season 2/E02.mkv"))->season, 2);
        QCOMPARE(watchedSeason(show, S("Show/Season 2/E02.mkv"))->items.size(), 3);
        QVERIFY(!watchedSeason(show, S("Show/none.mkv")));
        const LibraryItem trilogy = one(mag(S("Trilogy"), {{S("Film 1 (1999).mkv"), 4000 * MB}, {S("Film 2 (2003).mkv"), 4200 * MB}}));
        QVERIFY(!watchedSeason(trilogy, S("Film 1 (1999).mkv")));
    }

    void trilogyStaysACollection() {
        const auto p = buildPlayable(mag(S("Trilogy"), {{S("Film 1 (1999).mkv"), 4000 * MB}, {S("Film 2 (2003).mkv"), 4200 * MB}, {S("Film 3 (2003).mkv"), 4100 * MB}}));
        QVERIFY(p.isSeries());
        QCOMPARE(p.seasons[0].items.first().title.value(), S("Film 1"));
    }

    void versionsSortBySeasonChronologicallyAndQualityAndSize() {
        const auto s2Large = single(1, S("Cyberpunk Edgerunners 2 [2160p WEB-DL DV HDR10]"), 26200, seasonFiles(2, 2620));
        const auto s1Large = single(2, S("Cyberpunk - Edgerunners [2022] [WEB-DL] [Dolby Vision] [1080p] [RU...]"), 26000, seasonFiles(1, 2600));
        const auto s2Small = single(3, S("Cyberpunk Edgerunners 2 [2160p WEB-DL DV HDR10]"), 24800, seasonFiles(2, 2480));
        const auto s1Small = single(4, S("[Deadmau- RAWS] Cyberpunk.Edgerunners.S01.x264.2022.BDRip.1080..."), 16200, seasonFiles(1, 1620));
        const QList<LibraryItem> unsorted{s2Large, s1Large, s2Small, s1Small};
        QCOMPARE(ids(sortVersions(unsorted)), (QList<qint64>{2, 4, 1, 3}));
        const auto sections = versionSections(unsorted);
        QCOMPARE(sections.size(), 2);
        QCOMPARE(sections[0].title.value(), S("Сезон 1"));
        QCOMPARE(ids(sections[0].items), (QList<qint64>{2, 4}));
        QCOMPARE(sections[1].title.value(), S("Сезон 2"));
        QCOMPARE(ids(sections[1].items), (QList<qint64>{1, 3}));
    }

    void movieTrilogySortsByYearAndPart() {
        const auto part3 = single(3, S("The Return of the King (2003) 4K Remux"), 45000, {plain(S("The Return of the King (2003).mkv"), 45000)});
        const auto part1Hd = single(1, S("The Fellowship of the Ring (2001) 1080p"), 15000, {plain(S("The Fellowship of the Ring (2001).mkv"), 15000)});
        const auto part2 = single(2, S("The Two Towers (2002) 4K Remux"), 40000, {plain(S("The Two Towers (2002).mkv"), 40000)});
        const auto part1Uhd = single(4, S("The Fellowship of the Ring (2001) 4K Remux"), 42000, {plain(S("The Fellowship of the Ring (2001).mkv"), 42000)});
        const QList<LibraryItem> unsorted{part3, part1Hd, part2, part1Uhd};
        QCOMPARE(ids(sortVersions(unsorted)), (QList<qint64>{4, 1, 2, 3}));
        const auto sections = versionSections(unsorted);
        QCOMPARE(sections.size(), 3);
        QCOMPARE(sections[0].title.value(), S("2001 год"));
        QCOMPARE(ids(sections[0].items), (QList<qint64>{4, 1}));
        QCOMPARE(sections[1].title.value(), S("2002 год"));
        QCOMPARE(ids(sections[1].items), (QList<qint64>{2}));
        QCOMPARE(sections[2].title.value(), S("2003 год"));
        QCOMPARE(ids(sections[2].items), (QList<qint64>{3}));
    }

    void singleSeasonOrMovieHasNullSectionTitle() {
        const auto v1 = single(1, S("Inception (2010) 1080p"), 10000, {plain(S("Inception.mkv"), 10000)});
        const auto v2 = single(2, S("Inception (2010) 4K"), 30000, {plain(S("Inception.mkv"), 30000)});
        const auto sections = versionSections({v1, v2});
        QCOMPARE(sections.size(), 1);
        QVERIFY(!sections.first().title);
        QCOMPARE(ids(sections.first().items), (QList<qint64>{2, 1}));
    }

    // ---- AiTitleSearchGroupingTest ----

    void searchSeesTheAcceptedTitleOnly() {
        const LibraryItem item = one(movie(1, S("Sousou.no.Frieren"), acceptedDune()));
        QVERIFY(item.searchText.contains(S("дюна")));
        QVERIFY(item.searchText.contains(S("dune")));
        AiTitle sug = acceptedDune();
        sug.status = S("suggested");
        const LibraryItem suggested = one(movie(1, S("Sousou.no.Frieren"), sug));
        QVERIFY(!suggested.searchText.contains(S("дюна")));
        QVERIFY(suggested.withAiTitle(acceptedDune()).searchText.contains(S("дюна")));
    }

    void aiYearJoinsReleasesWithoutYearInName() {
        const auto items = prepareItems({movie(1, S("Dune.2021.1080p"), acceptedDune()), movie(2, S("Dune.BDRip"), acceptedDune())});
        const auto [shown, versions] = groupVersions(items);
        QCOMPARE(shown.size(), 1);
        QCOMPARE(versions.value(1).size(), 2);
    }

    void russianOnlyCollectionWordsWork() {
        const Magnet lotr = mag(S("Властелин колец: Трилогия (2001-2003)"), {{S("LOTR/Братство кольца (2001).mkv"), 4000 * MB},
                                                                              {S("LOTR/Две крепости (2002).mkv"), 4000 * MB},
                                                                              {S("LOTR/Возвращение короля (2003).mkv"), 4000 * MB}});
        const LibraryItem item = one(lotr);
        QVERIFY(isCollection(item.playable, &item.m));
        QVERIFY(collectionKind(3, lotr.name) == CollectionKind::Trilogy);
        QVERIFY(collectionKind(3, S("Дилогия + бонус")) == CollectionKind::Dilogy);
        QVERIFY(collectionKind(8, S("Гарри Поттер: Коллекция 8 фильмов")) == CollectionKind::Collection);
        QCOMPARE(versionLine(item, false), S("Кинотрилогия • 3 фильма"));
        const Playable serial = Playable::series({Season{1, {Episode{1, 1, lotr.files[0], std::nullopt}, Episode{1, 2, lotr.files[1], std::nullopt}}}});
        auto renamed = [&](const QString &n) { Magnet m = lotr; m.name = n; return m; };
        const Magnet a = renamed(S("Пила все части")), b = renamed(S("Матрица 3 в 1")), c = renamed(S("Шоу: трилогия, 2 сезон"));
        QVERIFY(isCollection(serial, &a));
        QVERIFY(isCollection(serial, &b));
        QVERIFY(!isCollection(serial, &c));
    }

    void aShowCalledSagaOrCollectionStaysAShow() {
        QList<std::pair<QString, qint64>> files;
        for (int i = 1; i <= 24; ++i) files << std::pair{S("Vinland/Vinland Saga - %1 [1080p].mkv").arg(i, 2, 10, QLatin1Char('0')), 800 * MB};
        for (const QString &name : {S("Сага о Винланде / Vinland Saga [1080p]"), S("Cowboy Bebop Complete Collection [BD 1080p]")}) {
            const Magnet m = mag(name, files);
            const auto p = buildPlayable(m);
            QVERIFY2(p.isSeries(), qPrintable(name));
            QCOMPARE(p.seasons.size(), 1);
            QCOMPARE(p.seasons[0].items.size(), 24);
            QVERIFY(!p.seasons[0].items.first().title);
            QVERIFY(!isCollection(p, &m));
        }
        QList<std::pair<QString, qint64>> mi;
        for (int i = 0; i < 6; ++i) mi << std::pair{S("MI/Mission %1 (%2).mkv").arg(i + 1).arg(1996 + i * 4), 4000 * MB};
        const auto p = buildPlayable(mag(S("Миссия невыполнима: коллекция"), mi));
        QVERIFY(p.isSeries());
        QVERIFY(p.seasons[0].items.first().title);
    }

    void acceptedAiKindDecidesWhetherItIsACollection() {
        const Magnet m = mag(S("Властелин колец: Трилогия"), {{S("A (2001).mkv"), 4000 * MB}, {S("B (2002).mkv"), 4000 * MB}});
        const Playable series = Playable::series({Season{1, {Episode{1, 1, m.files[0], std::nullopt}, Episode{1, 2, m.files[1], std::nullopt}}}});
        QVERIFY(isCollection(series, &m));
        Magnet asSeries = m;
        asSeries.aiTitle = ai(S("Шоу"), 1, S("series"));
        QVERIFY(!isCollection(series, &asSeries));
        Magnet asCollection = m;
        asCollection.name = S("Шоу");
        asCollection.aiTitle = ai(S("Шоу"), std::nullopt, S("collection"));
        QVERIFY(isCollection(series, &asCollection));
    }

    void romanNumeralsElsewhereAreNotParts() {
        QVERIFY(!itemPart(one(mag(S("X-Men (2000) 1080p"), {{S("X-Men.2000.mkv"), 4000 * MB}}))));
        QCOMPARE(itemPart(one(mag(S("Дюна: Часть II (2024)"), {{S("Dune2.mkv"), 4000 * MB}}))), std::optional<int>(2));
        QVERIFY(!itemPart(one(mag(S("8 фильмов"), {{S("a.mkv"), MB}}))));
        LibraryItem second = one(mag(S("Тачки 2 (2011)"), {{S("Cars2.mkv"), 4000 * MB}}));
        second.baseTitle = S("Тачки");
        QCOMPARE(itemPart(second), std::optional<int>(2));
    }

    void sortingManyMixedVersionsKeepsATotalOrder() {
        const QStringList names{S("Film Part 2"), S("Film (2000)"), S("Film Part 1 (2005)"), S("Film"), S("Film Part 3 (1999)"), S("Film (2010) 4K")};
        QList<LibraryItem> items;
        for (int i = 0; i < 64; ++i) {
            Magnet m = mag(names[i % names.size()], {{S("f%1.mkv").arg(i), (1000LL + (i * 37) % 11) * MB}});
            m.id = i;
            items << one(m);
        }
        QRandomGenerator rng(7);
        for (qsizetype i = items.size() - 1; i > 0; --i) items.swapItemsAt(i, rng.bounded(int(i + 1)));
        const auto sorted = sortVersions(items);
        QCOMPARE(sorted.size(), 64);
        QList<LibraryItem> reversed(sorted.rbegin(), sorted.rend());
        QCOMPARE(ids(sortVersions(reversed)), ids(sorted));
        QCOMPARE(itemPart(sorted.first()), std::optional<int>(1));
    }
};

QTEST_APPLESS_MAIN(SeriesGroupingTest)
#include "SeriesGroupingTest.moc"
