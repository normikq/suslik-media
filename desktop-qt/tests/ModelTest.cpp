// Перенос android/core/src/test/.../ModelTest.kt.
#include "TestUtil.h"

using namespace suslik::core;
using namespace testutil;

class ModelTest : public QObject {
    Q_OBJECT

    static QJsonObject aiLibrary(const QString &status) {
        QByteArray text = "{\n"
"          \"aiTitleReadyAt\": 1700000000000,\n"
"          \"magnets\": [\n"
"            { \"id\": 1, \"name\": \"Тачки 2 (2011) BDRip\", \"statusCode\": 4,\n"
"              \"files\": [ { \"name\": \"Cars.2.mkv\", \"path\": \"Cars.2.mkv\", \"type\": \"file\", \"size\": 4000, \"sourceLink\": \"https://host/1\" } ] },\n"
"            { \"id\": 2, \"name\": \"Cars.Two.2011.720p\", \"statusCode\": 4, \"canAiTitle\": true,\n"
"              \"aiTitle\": { \"base\": \" Тачки 2 \", \"season\": null, \"source\": \"ai\", \"model\": \"gemini-3.5-flash-lite\", \"at\": 5, \"status\": \"STATUS\" },\n"
"              \"files\": [ { \"name\": \"Cars2.mkv\", \"path\": \"Cars2.mkv\", \"type\": \"file\", \"size\": 2000, \"sourceLink\": \"https://host/2\" } ] }\n"
"          ]\n"
"        }";
        return json(text.replace("STATUS", status.toUtf8()));
    }

    static MFile aiFile(int i, const QString &path, qint64 mb) {
        return MFile{i, afterLastSlash(path), path, false, mb * 1024 * 1024, S("https://host/%1").arg(i)};
    }

    static Magnet aiMagnet(const QList<MFile> &files, std::optional<QString> kind, std::optional<int> season, const QString &status = S("accepted")) {
        Magnet m = magnet(1, S("Show"), S("Ready"), 4, 0, 0, files);
        AiTitle ai;
        ai.base = S("Show"); ai.season = season; ai.model = S("m"); ai.at = 1; ai.status = status; ai.kind = kind;
        m.aiTitle = ai;
        return m;
    }

    static std::optional<QString> firstTitle(const Playable &p) { return p.seasons.value(0).items.value(0).title; }

private slots:
    // 1. parseLibrary и Magnet
    void parseLibraryEmptyOrInvalidJson() {
        QVERIFY(parseLibrary(QJsonObject()).isEmpty());
        QVERIFY(parseLibrary(json("{\"magnets\": []}")).isEmpty());
        QVERIFY(parseLibrary(json("{\"magnets\": \"not-an-array\"}")).isEmpty());
    }

    void parseLibraryValidMagnetsAndFiles() {
        const auto list = parseLibrary(json("{\n"
"          \"magnets\": [\n"
"            { \"id\": 101, \"name\": \"Interstellar.2014.1080p\", \"status\": \"Downloading\", \"statusCode\": 2, \"size\": 10000000, \"downloaded\": 5000000,\n"
"              \"canRemove\": false, \"favorite\": true, \"mine\": true,\n"
"              \"files\": [\n"
"                { \"name\": \"Interstellar.mkv\", \"path\": \"Interstellar/Interstellar.mkv\", \"type\": \"file\", \"size\": 9500000, \"sourceLink\": \"https://alldebrid.com/f/xyz\" },\n"
"                { \"name\": \"Subs\", \"path\": \"Interstellar/Subs\", \"type\": \"folder\", \"size\": 0, \"sourceLink\": \"\" },\n"
"                { \"name\": \"insecure.srt\", \"path\": \"Interstellar/Subs/insecure.srt\", \"type\": \"file\", \"size\": 5000, \"sourceLink\": \"http://insecure.test/file.srt\" }\n"
"              ] },\n"
"            { \"id\": 102, \"name\": \"\", \"status\": \"\", \"statusCode\": 4 }\n"
"          ]\n"
"        }"));
        QCOMPARE(list.size(), 2);
        const Magnet &m1 = list[0];
        QCOMPARE(m1.id, 101);
        QCOMPARE(m1.name, S("Interstellar.2014.1080p"));
        QCOMPARE(m1.status, S("Downloading"));
        QCOMPARE(m1.statusCode, 2);
        QCOMPARE(m1.size, 10000000);
        QCOMPARE(m1.downloaded, 5000000);
        QVERIFY(!m1.canRemove);
        QVERIFY(m1.favorite);
        QVERIFY(m1.mine);
        QVERIFY(!m1.ready());
        QVERIFY(m1.downloading());
        QVERIFY(!m1.failed());
        QVERIFY(qAbs(m1.progress() - 0.5f) < 0.0001f);
        QCOMPARE(m1.files.size(), 3);
        QCOMPARE(m1.files[0].index, 0);
        QCOMPARE(m1.files[0].name, S("Interstellar.mkv"));
        QCOMPARE(m1.files[0].path, S("Interstellar/Interstellar.mkv"));
        QVERIFY(!m1.files[0].isFolder);
        QCOMPARE(m1.files[0].link.value(), S("https://alldebrid.com/f/xyz"));
        QVERIFY(m1.files[1].isFolder);
        QVERIFY(!m1.files[1].link);
        QVERIFY(!m1.files[2].link); // http — не принимается
        const Magnet &m2 = list[1];
        QCOMPARE(m2.name, S("Magnet 102"));
        QVERIFY(m2.ready());
        QVERIFY(!m2.downloading());
        QCOMPARE(m2.progress(), 0.f);
        QVERIFY(m2.canRemove);
        QVERIFY(!m2.favorite);
        QVERIFY(!m2.mine);
    }

    void magnetStatesAndProgress() {
        const Magnet ready = magnet(1, S("Ready"), S("Ready"), 4, 100, 100, {});
        QVERIFY(ready.ready() && !ready.downloading() && !ready.failed());
        QCOMPARE(ready.progress(), 1.f);
        const Magnet failed = magnet(2, S("Failed"), S("Error"), 5, 100, 10, {});
        QVERIFY(!failed.ready() && !failed.downloading() && failed.failed());
        QCOMPARE(magnet(3, S("Zero"), S("Init"), 1, 0, 0, {}).progress(), 0.f);
        QCOMPARE(magnet(4, S("Over"), S("Downloading"), 2, 100, 150, {}).progress(), 1.f);
    }

    // 2. cleanTitle
    void cleanTitleStripsReleaseTagsAndYears() {
        QCOMPARE(cleanTitle(S("Inception.2010.1080p.BluRay.x264-SPARKS.mkv")), S("Inception"));
        QCOMPARE(cleanTitle(S("Breaking.Bad.S01.1080p.NF.WEB-DL.DDP5.1.H.264-NTb")), S("Breaking Bad S01"));
        QCOMPARE(cleanTitle(S("Dune.Part.Two.2024.2160p.UHD.Remux.HEVC.DV.HDR10+.mkv")), S("Dune Part Two"));
        QCOMPARE(cleanTitle(S("The Matrix (1999) [BDRip 720p]")), S("The Matrix"));
        QCOMPARE(cleanTitle(S("Movie_Title_2021_Proper_Repack")), S("Movie Title"));
        QCOMPARE(cleanTitle(S("Severance.S01E01.480p.WEBRip.x264.mp4")), S("Severance S01E01"));
    }

    void cleanTitleEdgeCases() {
        QCOMPARE(cleanTitle(S("")), S("Untitled"));
        QCOMPARE(cleanTitle(S("   ")), S("Untitled"));
        QVERIFY(!cleanTitle(S("1080p")).isEmpty());
    }

    // 3. formatBytes и statusRu
    void formatBytesFormatting() {
        QCOMPARE(formatBytes(0), S("—"));
        QCOMPARE(formatBytes(-100), S("—"));
        QCOMPARE(formatBytes(512), S("512 B"));
        QCOMPARE(formatBytes(1024), S("1.0 KB"));
        QCOMPARE(formatBytes(1536), S("1.5 KB"));
        QCOMPARE(formatBytes(10LL * 1024 * 1024), S("10.0 MB"));
        QCOMPARE(formatBytes(qint64(4.2 * 1024 * 1024 * 1024)), S("4.2 GB"));
        QCOMPARE(formatBytes(qint64(1.5 * 1024 * 1024 * 1024 * 1024)), S("1.5 TB"));
    }

    void statusRuMapping() {
        auto m = [](int code, const QString &status) { return magnet(1, S("Test"), status, code, 0, 0, {}); };
        QCOMPARE(statusRu(m(4, S("Uploading"))), S("Готово"));
        QCOMPARE(statusRu(m(2, S("Downloading"))), S("Загрузка"));
        QCOMPARE(statusRu(m(2, S("downloading"))), S("Загрузка"));
        QCOMPARE(statusRu(m(1, S(""))), S("Обработка"));
        QCOMPARE(statusRu(m(1, S("   "))), S("Обработка"));
        QCOMPARE(statusRu(m(5, S(""))), S("Ошибка"));
        QCOMPARE(statusRu(m(5, S("Bad Torrent"))), S("Bad Torrent"));
        QCOMPARE(statusRu(m(1, S("Queued"))), S("Queued"));
    }

    // 4. mainVideo и что играть
    void mainVideoSelectsLargestAndIgnoresIso() {
        const Magnet m = magnet(1, S("Film"), S("Готово"), 4, 0, 0, {file(0, S("Film.sample.mkv"), 50), file(1, S("Film.iso"), 8000), file(2, S("Film.mkv"), 4000)});
        QCOMPARE(mainVideo(m)->name, S("Film.mkv"));
        QCOMPARE(mainVideo(magnet(2, S("Iso"), S("Готово"), 4, 0, 0, {file(0, S("Disc.iso"), 4000)}))->name, S("Disc.iso"));
        QVERIFY(!mainVideo(magnet(3, S("Doc"), S("Готово"), 4, 0, 0, {file(0, S("readme.txt"), 1)})));
    }

    void discImageByNameOrMirrorUrl() {
        QVERIFY(isDiscImage(S("Film/BD/Disc.ISO")));
        QVERIFY(isDiscImage(S("https://host/api/file/proxy/Disc.iso?link=x&exp=1&sig=y")));
        QVERIFY(!isDiscImage(S("Film.mkv")));
        QVERIFY(!isDiscImage(S("https://host/api/file/proxy/Film.mkv?link=https%3A%2F%2Fa%2FDisc.iso")));
        QVERIFY(!isDiscImage(S("Film.iso.mkv")));
    }

    void buildPlayableSingleMovie() {
        const auto p = buildPlayable(magnet(1, S("Avatar"), S("Готово"), 4, 0, 0, {file(0, S("Avatar.sample.mkv"), 30), file(1, S("Avatar.mkv"), 8000)}));
        QVERIFY(p.isMovie());
        QCOMPARE(p.file.name, S("Avatar.mkv"));
    }

    void buildPlayableSeriesByDirectoryStructure() {
        const auto p = buildPlayable(magnet(1, S("Breaking Bad"), S("Готово"), 4, 0, 0, {
            file(0, S("Breaking Bad/Season 1/ep01.mkv")), file(1, S("Breaking Bad/Season 1/ep02.mkv")),
            file(2, S("Breaking Bad/Season 1/ep10.mkv")), file(3, S("Breaking Bad/Season 2/ep01.mkv"))}));
        QVERIFY(p.isSeries());
        QCOMPARE(p.seasons.size(), 2);
        const Season &s1 = p.seasons[0];
        QCOMPARE(s1.season, 1);
        QCOMPARE(s1.items.size(), 3);
        QCOMPARE(s1.items[0].file.name, S("ep01.mkv"));
        QCOMPARE(s1.items[0].episode, 1);
        QCOMPARE(s1.items[1].file.name, S("ep02.mkv"));
        QCOMPARE(s1.items[1].episode, 2);
        QCOMPARE(s1.items[2].file.name, S("ep10.mkv"));
        QCOMPARE(s1.items[2].episode, 3);
        QCOMPARE(p.seasons[1].season, 2);
        QCOMPARE(p.seasons[1].items.size(), 1);
        QCOMPARE(p.seasons[1].items[0].file.name, S("ep01.mkv"));
    }

    void buildPlayableSeriesByRussianSeasonDirectory() {
        const auto p = buildPlayable(magnet(1, S("Чернобыль"), S("Готово"), 4, 0, 0, {file(0, S("Чернобыль/Сезон 1/01.mkv")), file(1, S("Чернобыль/Сезон 1/02.mkv"))}));
        QVERIFY(p.isSeries());
        QCOMPARE(p.seasons.size(), 1);
        QCOMPARE(p.seasons[0].season, 1);
        QCOMPARE(p.seasons[0].items.size(), 2);
    }

    void buildPlayableSeriesByFilenamePattern() {
        const auto p = buildPlayable(magnet(1, S("Sherlock"), S("Готово"), 4, 0, 0, {
            file(0, S("Sherlock.S01E01.mkv"), 1500), file(1, S("Sherlock.S01E02.mkv"), 1500), file(2, S("Sherlock.1x03.mkv"), 1500)}));
        QVERIFY(p.isSeries());
        QCOMPARE(p.seasons.size(), 1);
        QCOMPARE(p.seasons[0].items.size(), 3);
        for (int i = 0; i < 3; ++i) QCOMPARE(p.seasons[0].items[i].episode, i + 1);
    }

    void buildPlayableIgnoresSampleFiles() {
        const auto p = buildPlayable(magnet(1, S("Movie with samples"), S("Готово"), 4, 0, 0, {
            file(0, S("sample/sample.mkv"), 50), file(1, S("movie.sample.avi"), 50), file(2, S("movie.mkv"), 2000)}));
        QVERIFY(p.isMovie());
        QCOMPARE(p.file.name, S("movie.mkv"));
    }

    void buildPlayableFilmCollection() {
        const auto p = buildPlayable(magnet(1, S("Lord of the Rings Trilogy"), S("Готово"), 4, 0, 0, {
            file(0, S("1.The.Fellowship.of.the.Ring.2001.1080p.mkv"), 8000), file(1, S("2.The.Two.Towers.2002.1080p.mkv"), 8200),
            file(2, S("3.The.Return.of.the.King.2003.1080p.mkv"), 9000), file(3, S("Extras/featurette.mkv"), 500)}));
        QVERIFY(p.isSeries());
        QCOMPARE(p.seasons.size(), 1);
        const auto &films = p.seasons[0].items;
        QCOMPARE(films.size(), 3);
        QCOMPARE(films[0].title.value(), S("1 The Fellowship of the Ring"));
        QCOMPARE(films[1].title.value(), S("2 The Two Towers"));
        QCOMPARE(films[2].title.value(), S("3 The Return of the King"));
    }

    void buildPlayableBlurayDirectoriesNotTreatedAsCollection() {
        const auto p = buildPlayable(magnet(1, S("Blu-ray Disc"), S("Готово"), 4, 0, 0, {file(0, S("BDMV/STREAM/00001.m2ts"), 20000), file(1, S("BDMV/STREAM/00002.m2ts"), 15000)}));
        QVERIFY(p.isMovie());
        QCOMPARE(p.file.name, S("00001.m2ts"));
    }

    void buildPlayableNoVideosReturnsNone() {
        QVERIFY(buildPlayable(magnet(1, S("Music Album"), S("Готово"), 4, 0, 0, {file(0, S("song1.flac"), 30), file(1, S("song2.flac"), 30)})).isNone());
    }

    // 5. episodeLabel
    void episodeLabelFormatting() {
        const MFile f = file(0, S("ep.mkv"));
        const Season s1{1, {Episode{1, 2, f, std::nullopt}}};
        QCOMPARE(episodeLabel(S("Breaking Bad"), s1, s1.items[0]), S("Breaking Bad · S01E02"));
        const Season s0{0, {Episode{0, 1, f, std::nullopt}}};
        QCOMPARE(episodeLabel(S("Doctor Who"), s0, s0.items[0]), S("Doctor Who · Спец.E01"));
        const Season sc{1, {Episode{1, 1, f, S("Alien 1")}}};
        QCOMPARE(episodeLabel(S("Alien Collection"), sc, sc.items[0]), S("Alien 1"));
    }

    // 6. субтитры и озвучки
    void subtitlesMatchedByNameAndFolder() {
        const MFile vid1 = file(0, S("Series/S01/Episode.01.1080p.mkv")), vid2 = file(1, S("Series/S01/Episode.02.1080p.mkv"));
        const MFile sub1Rus = file(2, S("Series/S01/Episode.01.1080p.rus.srt")), sub1Eng = file(3, S("Series/S01/Episode.01.1080p.eng.ass"));
        const MFile sub2Rus = file(4, S("Series/S01/Subs/Episode.02.1080p.srt")), orphan = file(5, S("Series/S01/other.srt"));
        const Magnet m = magnet(1, S("Series"), S("Готово"), 4, 0, 0, {vid1, vid2, sub1Rus, sub1Eng, sub2Rus, orphan});
        QCOMPARE(subtitlesFor(m, vid1), (QList<MFile>{sub1Rus, sub1Eng}));
        QCOMPARE(subtitlesFor(m, vid2), (QList<MFile>{sub2Rus}));
    }

    void subtitlesForSingleVideoAcceptsAnyNearOrInTorrent() {
        const MFile vid = file(0, S("Movie/Movie.mkv"));
        const Magnet m = magnet(1, S("Movie"), S("Готово"), 4, 0, 0, {vid, file(1, S("Movie/Subs/rus.srt")), file(2, S("Movie/eng.vtt"))});
        QCOMPARE(subtitlesFor(m, vid).size(), 2);
    }

    void externalAudioForMatchedByName() {
        const MFile vid1 = file(0, S("Anime/Ep01.mkv")), vid2 = file(1, S("Anime/Ep02.mkv"));
        const MFile aud1 = file(2, S("Anime/Audio/Ep01.rus.mka")), aud2 = file(3, S("Anime/Audio/Ep02.rus.mka"));
        const Magnet m = magnet(1, S("Anime"), S("Готово"), 4, 0, 0, {vid1, vid2, aud1, aud2});
        QCOMPARE(externalAudioFor(m, vid1), (QList<MFile>{aud1}));
        QCOMPARE(externalAudioFor(m, vid2), (QList<MFile>{aud2}));
    }

    void episodeOneDoesNotTakeEpisodeTensFiles() {
        const MFile e1 = file(0, S("Show/Show.S01E1.mkv")), e10 = file(1, S("Show/Show.S01E10.mkv"));
        const MFile sub1 = file(2, S("Show/Show.S01E1.rus.srt")), sub10 = file(3, S("Show/Show.S01E10.rus.srt"));
        const MFile aud1 = file(4, S("Show/Show.S01E1_rus.mka")), aud10 = file(5, S("Show/Show.S01E10.mka"));
        const Magnet m = magnet(1, S("Show"), S("Готово"), 4, 0, 0, {e1, e10, sub1, sub10, aud1, aud10});
        QCOMPARE(subtitlesFor(m, e1), (QList<MFile>{sub1}));
        QCOMPARE(subtitlesFor(m, e10), (QList<MFile>{sub10}));
        QCOMPARE(externalAudioFor(m, e1), (QList<MFile>{aud1}));
        QCOMPARE(externalAudioFor(m, e10), (QList<MFile>{aud10}));
    }

    void externalAudioSingleVideoFallback() {
        const MFile vid = file(0, S("Film/Film.mkv")), dub = file(1, S("Film/Dub.ac3"));
        QCOMPARE(externalAudioFor(magnet(1, S("Film"), S("Готово"), 4, 0, 0, {vid, dub}), vid), (QList<MFile>{dub}));
    }

    // 7. WatchInfo и watchKey
    void watchKeyConstruction() { QCOMPARE(watchKey(42, file(5, S("folder/movie.mkv"))), S("42|folder/movie.mkv")); }

    void watchInfoProgressAndWatchedThreshold() {
        const WatchInfo w1{50000, 100000, 123456789};
        QVERIFY(qAbs(w1.fraction() - 0.5f) < 0.0001f);
        QVERIFY(!w1.watched());
        QVERIFY((WatchInfo{85000, 100000, 123456789}).watched());
        QVERIFY((WatchInfo{99000, 100000, 123456789}).watched());
        const WatchInfo zero{0, 0, 0};
        QCOMPARE(zero.fraction(), 0.f);
        QVERIFY(!zero.watched());
    }

    // 8. prepareItems
    void prepareItemsComputesTileData() {
        const auto items = prepareItems({magnet(10, S("Inception.2010.1080p"), S("Готово"), 4, 5000, 5000, {file(0, S("Inception.mkv"), 4000)})});
        QCOMPARE(items.size(), 1);
        const LibraryItem &item = items[0];
        QCOMPARE(item.baseTitle, S("Inception"));
        QVERIFY(item.playable.isMovie());
        QVERIFY(item.posterQueries.contains(S("Inception")));
        QVERIFY(item.searchText.contains(S("inception")));
        QVERIFY(item.searchText.contains(S("inception.mkv")));
    }

    // Название от ИИ: разбирается, для группировки — только принятое
    void parseLibraryAiTitle() {
        const QJsonObject j = aiLibrary(S("suggested"));
        const auto list = parseLibrary(j);
        QVERIFY(!list[0].aiTitle);
        QVERIFY(!list[0].canAiTitle);
        AiTitle expected;
        expected.base = S("Тачки 2"); expected.model = S("gemini-3.5-flash-lite"); expected.at = 5; expected.status = S("suggested");
        QVERIFY(list[1].aiTitle == expected);
        QVERIFY(list[1].canAiTitle);
        QVERIFY(!list[1].aiTitle->accepted());
        QCOMPARE(aiTitleReadyAt(j), 1700000000000LL);
        QCOMPARE(aiTitleReadyAt(QJsonObject()), 0LL);
        const auto season = parseLibrary(json("{\"magnets\":[{\"id\":3,\"name\":\"S\",\"aiTitle\":{\"base\":\"Тед Лассо\",\"season\":2,\"status\":\"accepted\"}}]}"));
        QCOMPARE(season[0].aiTitle->season.value(), 2);
        QVERIFY(season[0].aiTitle->accepted());
        QVERIFY(!parseLibrary(json("{\"magnets\":[{\"id\":4,\"name\":\"S\",\"aiTitle\":{\"base\":\"X\",\"status\":\"weird\"}}]}"))[0].aiTitle);
    }

    void groupVersionsUsesOnlyAcceptedAiTitle() {
        const auto accepted = prepareItems(parseLibrary(aiLibrary(S("accepted"))));
        QCOMPARE(accepted[1].baseTitle, S("Тачки 2"));
        const auto [shown, versions] = groupVersions(accepted);
        QCOMPARE(ids(shown), (QList<qint64>{1}));
        auto v = ids(versions.value(1));
        std::sort(v.begin(), v.end());
        QCOMPARE(v, (QList<qint64>{1, 2}));
        for (const QString &status : {S("suggested"), S("rejected"), S("none")}) {
            const auto items = prepareItems(parseLibrary(aiLibrary(status)));
            QCOMPARE(items[1].baseTitle, cleanTitle(items[1].m.name));
            QCOMPARE(ids(groupVersions(items).first), (QList<qint64>{1, 2}));
        }
    }

    void acceptedAiTitleNamesTheTileAndItsPoster() {
        const auto items = prepareItems(parseLibrary(aiLibrary(S("accepted"))));
        QCOMPARE(items[1].baseTitle, S("Тачки 2"));
        QCOMPARE(items[1].posterQueries.first(), S("Тачки 2"));
        const LibraryItem suggested = prepareItems(parseLibrary(aiLibrary(S("suggested"))))[1];
        QVERIFY(suggested.baseTitle != S("Тачки 2") || suggested.m.name.contains(S("Тачки")));
        AiTitle ai = *suggested.m.aiTitle;
        ai.status = S("accepted");
        const LibraryItem accepted = suggested.withAiTitle(ai);
        QCOMPARE(accepted.baseTitle, S("Тачки 2"));
        QCOMPARE(accepted.posterQueries.first(), S("Тачки 2"));
        ai.status = S("rejected");
        const LibraryItem rejected = accepted.withAiTitle(ai);
        QCOMPARE(rejected.baseTitle, cleanTitle(suggested.m.name));
        QVERIFY(rejected.playable == suggested.playable);
    }

    void acceptedAiKindAndSeasonChangeWhatPlays() {
        const QList<MFile> films{aiFile(0, S("Show/Show A.mkv"), 1500), aiFile(1, S("Show/Show B.mkv"), 1500), aiFile(2, S("Show/Show C.mkv"), 1500)};
        QVERIFY(firstTitle(buildPlayable(aiMagnet(films, std::nullopt, std::nullopt, S("none")))));
        const auto series = buildPlayable(aiMagnet(films, S("series"), 3));
        QVERIFY(series.isSeries());
        QCOMPARE(series.seasons.size(), 1);
        QCOMPARE(series.seasons[0].season, 3);
        QList<int> eps;
        for (const auto &e : series.seasons[0].items) {
            eps << e.episode;
            QVERIFY(!e.title && e.season == 3);
        }
        QCOMPARE(eps, (QList<int>{1, 2, 3}));
        QVERIFY(firstTitle(buildPlayable(aiMagnet(films, S("series"), 3, S("suggested")))));

        const QList<MFile> epFiles{aiFile(0, S("S/Show.S01E01.mkv"), 500), aiFile(1, S("S/Show.S01E02.mkv"), 500)};
        const auto relabeled = buildPlayable(aiMagnet(epFiles, std::nullopt, 2));
        QCOMPARE(relabeled.seasons.size(), 1);
        for (const auto &e : relabeled.seasons[0].items) QCOMPARE(e.season, 2);
        QVERIFY(firstTitle(buildPlayable(aiMagnet(epFiles, S("collection"), std::nullopt))));

        const auto movie = buildPlayable(aiMagnet({aiFile(0, S("Film.mkv"), 4000), aiFile(1, S("Film part2.mkv"), 3900)}, S("movie"), std::nullopt));
        QVERIFY(movie.isMovie());
        QCOMPARE(movie.file.path, S("Film.mkv"));

        const auto two = buildPlayable(aiMagnet({aiFile(0, S("Season 1/a.mkv"), 500), aiFile(1, S("Season 2/b.mkv"), 500)}, S("series"), 5));
        QCOMPARE(two.seasons.size(), 2);
        QCOMPARE(two.seasons[0].season, 1);
        QCOMPARE(two.seasons[1].season, 2);

        const LibraryItem item = prepareItems({aiMagnet(films, S("series"), 3, S("suggested"))}).first();
        QVERIFY(firstTitle(item.playable));
        AiTitle ai = *item.m.aiTitle;
        ai.status = S("accepted");
        const LibraryItem acc = item.withAiTitle(ai);
        QCOMPARE(acc.playable.seasons.size(), 1);
        QCOMPARE(acc.playable.seasons[0].season, 3);
        QVERIFY(!firstTitle(acc.playable));
    }

    void parseAiTitleKindAndStale() {
        const QJsonObject a = json("{\"base\":\"X\",\"season\":null,\"kind\":\"collection\",\"status\":\"suggested\",\"stale\":true}");
        const auto t = parseAiTitle(&a);
        QCOMPARE(t->kind.value(), S("collection"));
        QVERIFY(t->stale);
        const QJsonObject b = json("{\"base\":\"X\",\"kind\":\"trilogy\",\"status\":\"suggested\"}");
        QVERIFY(!parseAiTitle(&b)->kind);
    }

    void acceptedAiOriginalTitleWithYearIsTheFirstPosterQuery() {
        const auto item = prepareItems(parseLibrary(json("{\"magnets\":[{\"id\":1,\"name\":\"Дюна (2021) BDRip\",\"statusCode\":4,\n"
"            \"files\":[{\"name\":\"Dune.2021.mkv\",\"path\":\"Dune.2021.mkv\",\"type\":\"file\",\"size\":4000,\"sourceLink\":\"https://host/1\"}],\n"
"            \"aiTitle\":{\"base\":\"Дюна\",\"season\":null,\"kind\":\"movie\",\"original\":\"Dune\",\"year\":2021,\"status\":\"suggested\"}}]}"))).first();
        QCOMPARE(item.m.aiTitle->original.value(), S("Dune"));
        QCOMPARE(item.m.aiTitle->year.value(), 2021);
        QVERIFY(!item.posterQueries.contains(S("Dune (2021)")));
        AiTitle ai = *item.m.aiTitle;
        ai.status = S("accepted");
        QCOMPARE(item.withAiTitle(ai).posterQueries.mid(0, 2), (QStringList{S("Dune (2021)"), S("Дюна")}));
        const QJsonObject n = json("{\"base\":\"X\",\"original\":null,\"year\":null,\"status\":\"accepted\"}");
        const auto none = parseAiTitle(&n);
        QVERIFY(!none->original);
        QVERIFY(!none->posterQuery());
    }

    void collectionDetectionAndPosterQueries() {
        auto mf = [](int i, const QString &name, std::optional<QString> link) { return MFile{i, name, name, false, 2000000000LL, link}; };
        const MFile f1 = mf(1, S("Harry Potter and the Sorcerers Stone (2001).mkv"), S("http://link1"));
        const MFile f2 = mf(2, S("Harry Potter and the Chamber of Secrets (2002).mkv"), S("http://link2"));
        const MFile f3 = mf(3, S("Harry Potter and the Prisoner of Azkaban (2004).mkv"), S("http://link3"));
        const Magnet hp = magnet(10, S("Гарри Поттер: Трилогия / Harry Potter: Trilogy (2001-2004) BDRip"), S(""), 4, 6000000000LL, 6000000000LL, {f1, f2, f3}, false);
        const LibraryItem item = prepareItems({hp}).first();
        QVERIFY(isCollection(item.playable, &hp));
        QVERIFY(collectionKind(3, hp.name) == CollectionKind::Trilogy);
        QCOMPARE(filmCountRu(3), S("3 фильма"));
        const auto q1 = collectionFilePosterQueries(hp, f1, item.baseTitle);
        QVERIFY(q1.contains(S("Harry Potter and the Sorcerers Stone (2001)")));
        QVERIFY(q1.contains(S("Harry Potter and the Sorcerers Stone")));
        QCOMPARE(continueItemPosterQueries(item, f1), q1);

        const MFile lotrFile = mf(1, S("01. Братство кольца (2001).mkv"), S("http://link"));
        const Magnet lotr = magnet(11, S("Властелин колец: Трилогия (2001-2003)"), S(""), 4, 6000000000LL, 6000000000LL, {lotrFile}, false);
        const auto lq = collectionFilePosterQueries(lotr, lotrFile, S("Властелин колец"));
        QVERIFY(lq.contains(S("Братство кольца (2001)")));
        QVERIFY(lq.contains(S("Властелин колец: Братство кольца (2001)")));
        QCOMPARE(collectionFileTitle(lotr, lotrFile, S("Властелин колец")), S("Властелин колец: Братство кольца"));

        const MFile hp2 = mf(1, S("2 Гарри Поттер и Тайная комната.mkv"), S("http://link"));
        const Magnet hpm = magnet(12, S("Гарри Поттер: Коллекция 8 фильмов / Harry Potter (2001-2011)"), S(""), 4, 16000000000LL, 16000000000LL, {hp2}, false);
        QCOMPARE(collectionFileTitle(hpm, hp2, hpm.name), S("Гарри Поттер и Тайная комната"));
        const auto hq = collectionFilePosterQueries(hpm, hp2, hpm.name);
        QVERIFY(hq.contains(S("Гарри Поттер и Тайная комната")));
        QVERIFY(hq.contains(S("Harry Potter")));

        QCOMPARE(cleanCollectionFilmTitle(S("1 Гарри Поттер и философский камень (2001).mkv")), S("Гарри Поттер и философский камень"));
        QCOMPARE(cleanCollectionFilmTitle(S("1. 1 Гарри Поттер и философский камень")), S("Гарри Поттер и философский камень"));
        QCOMPARE(cleanCollectionFilmTitle(S("Фильм 2 - Гарри Поттер и Тайная комната")), S("Гарри Поттер и Тайная комната"));
        QCOMPARE(cleanCollectionFilmTitle(S("300.mkv")), S("300"));
    }

    void tileTitleIsAiThenTmdbRussianThenRelease() {
        for (const QString &status : {S("suggested"), S("rejected"), S("none")}) {
            const LibraryItem item = prepareItems(parseLibrary(aiLibrary(status)))[1];
            const QString q = item.posterQueries.first().toLower();
            QCOMPARE(tileTitle(item, [&](const QString &x) -> std::optional<QString> { if (x == q) return S("Русское"); return std::nullopt; }), S("Русское"));
            QCOMPARE(tileTitle(item, [](const QString &) -> std::optional<QString> { return S(""); }), item.baseTitle);
        }
        const LibraryItem accepted = prepareItems(parseLibrary(aiLibrary(S("accepted"))))[1];
        QCOMPARE(tileTitle(accepted, [](const QString &x) -> std::optional<QString> { return x == S("тачки 2") ? S("Тачки 2 (TMDB)") : S("Другое"); }), S("Тачки 2"));
    }

    void smartQualityScoreAndQualityScore() {
        QCOMPARE(qualityScore(S("Movie.2160p.mkv")), 4);
        QCOMPARE(qualityScore(S("Movie.1080p.mkv")), 3);
        QCOMPARE(qualityScore(S("Movie.720p.mkv")), 2);
        QCOMPARE(qualityScore(S("Movie.480p.mkv")), 1);
        QCOMPARE(qualityScore(S("Movie.DVDRip.mkv")), 1);
        QCOMPARE(qualityScore(S("Movie.Unknown.mkv")), 0);

        QCOMPARE(smartQualityScore(S("Dune.2021.2160p.UHD.Remux.DV.TrueHD.Atmos.mkv")), 505);
        QCOMPARE(smartQualityScore(S("Movie.1080p.BDRemux.10-bit.DTS-HD.MA.mkv")), 365);
        QCOMPARE(smartQualityScore(S("Film.1080p.BDRip.AC3.5.1.mkv")), 325);
        QCOMPARE(smartQualityScore(S("Series.S01E01.720p.WEB-DL.DDP5.1.mkv")), 230);
        QCOMPARE(smartQualityScore(S("Movie.2160p.HDR10+.DTS:X.BDRip.mkv")), 485);
        QCOMPARE(smartQualityScore(S("Old.Show.480p.mkv")), 100);
        QCOMPARE(smartQualityScore(S("network.webcam.4kilo.dtswiss.mkv")), 100);
        QCOMPARE(smartQualityScore(S("Movie_1080p_x264_DTS5.1.mkv")), 320);
        QCOMPARE(smartQualityScore(S("Film.1080p.BluRay.dts.x264.mkv")), 335);
        QCOMPARE(smartQualityScore(S("Atmosphere.2020.720p.WEB.mkv")), 210);
        QCOMPARE(smartQualityScore(S("Film.1920x1080.HDRip.mkv")), 300);
    }
};

QTEST_APPLESS_MAIN(ModelTest)
#include "ModelTest.moc"
