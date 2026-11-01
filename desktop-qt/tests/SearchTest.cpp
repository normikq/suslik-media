// Перенос ReleaseParserTest.kt и SearchFilterTest.kt (shared/src/test/.../search).
#include "suslik/core/Search.h"

#include <QTest>

using namespace suslik::core;
#define S QStringLiteral

namespace {
constexpr qint64 GB = 1024LL * 1024 * 1024;
bool between(float v, float a, float b) { return v >= a && v <= b; }
QStringList ids(const QList<SearchHit> &l) { QStringList o; for (const auto &h : l) o << h.id; return o; }
}

class SearchTest : public QObject {
    Q_OBJECT
private slots:
    // ---- ReleaseParserTest ----
    void parse4kDolbyVisionRemux() {
        const auto info = ReleaseParser::parse(S("Дюна: Часть вторая / Dune: Part Two (2024) BDRemux 2160p | HDR, Dolby Vision | DDP 5.1, TrueHD 7.1.Atmos | D"), 58 * GB, S("movie"));
        QCOMPARE(info.cleanTitle, S("Дюна: Часть вторая / Dune: Part Two (2024)"));
        QCOMPARE(info.year.value_or(0), 2024);
        QCOMPARE(info.resolution, Resolution::UHD_4K);
        QCOMPARE(info.ripType, RipType::REMUX);
        QCOMPARE(info.hdr, HdrType::DV);
        QCOMPARE(info.voice, VoiceType::DUB);
        QVERIFY(!info.isCam);
        QCOMPARE(info.bitrateTier, BitrateTier::HEAVY);
        QVERIFY(info.estimatedBitrateMbps > 50.f);
        QVERIFY(!info.isMobileFriendly());
        const auto b = info.badges();
        QVERIFY(b.contains(S("4K")));
        QVERIFY(b.contains(S("BDRemux")));
        QVERIFY(b.contains(S("Dolby Vision")));
        QVERIFY(b.contains(S("Дубляж")));
    }

    void parse1080pBDRipDubbed() {
        const auto info = ReleaseParser::parse(S("Интерстеллар / Interstellar (2014) BDRip 1080p | D, P, A | Лицензия"), 8 * GB, S("movie"));
        QCOMPARE(info.cleanTitle, S("Интерстеллар / Interstellar (2014)"));
        QCOMPARE(info.year.value_or(0), 2014);
        QCOMPARE(info.resolution, Resolution::FHD_1080P);
        QCOMPARE(info.ripType, RipType::BDRIP);
        QCOMPARE(info.voice, VoiceType::DUB);
        QVERIFY(!info.isCam);
        QCOMPARE(info.bitrateTier, BitrateTier::MEDIUM);
        QVERIFY(between(info.estimatedBitrateMbps, 9.f, 12.f));
    }

    void parseMobileFriendly720pWebDl() {
        const auto info = ReleaseParser::parse(S("Оппенгеймер / Oppenheimer (2023) WEB-DLRip 720p | D"), 2 * GB + 200LL * 1024 * 1024, S("movie"));
        QCOMPARE(info.cleanTitle, S("Оппенгеймер / Oppenheimer (2023)"));
        QCOMPARE(info.year.value_or(0), 2023);
        QCOMPARE(info.resolution, Resolution::HD_720P);
        QCOMPARE(info.ripType, RipType::WEBDL);
        QCOMPARE(info.voice, VoiceType::DUB);
        QVERIFY(!info.isCam);
        QCOMPARE(info.bitrateTier, BitrateTier::LIGHT);
        QVERIFY(between(info.estimatedBitrateMbps, 2.5f, 3.2f));
        QVERIFY(info.isMobileFriendly());
        QVERIFY(info.badges().contains(S("720p")));
        QVERIFY(info.badges().contains(S("WEB-DL")));
    }

    void parseHevcBDRip() {
        const auto info = ReleaseParser::parse(S("Мальчик и птица / The Boy and the Heron (2023) BDRip-HEVC 1080p | MVO"), 3 * GB, S("movie"));
        QCOMPARE(info.resolution, Resolution::FHD_1080P);
        QCOMPARE(info.codec, VideoCodec::HEVC);
        QCOMPARE(info.ripType, RipType::BDRIP);
        QCOMPARE(info.voice, VoiceType::MVO);
        QVERIFY(info.isMobileFriendly());
        QVERIFY(info.badges().contains(S("HEVC")));
    }

    void parseCamRipAndTelesync() {
        const auto i1 = ReleaseParser::parse(S("Гладиатор 2 / Gladiator II (2024) CAMRip | Звук с TS"), 1500000000LL, S("movie"));
        QVERIFY(i1.isCam);
        QCOMPARE(i1.ripType, RipType::CAM);
        QVERIFY(!i1.isMobileFriendly());
        const auto i2 = ReleaseParser::parse(S("Форсаж 10 / Fast X (2023) Telesync [TS]"), 1800000000LL, S("movie"));
        QVERIFY(i2.isCam);
        QCOMPARE(i2.ripType, RipType::CAM);
        QVERIFY(!i2.isMobileFriendly());
    }

    void parseSeriesWithEpisodeCount() {
        const auto i1 = ReleaseParser::parse(S("Одни из нас / The Last of Us (Сезон 1, серии 1-9 из 9) (2023) WEB-DL 1080p | Red Head Sound"), 18 * GB, S("series"));
        QCOMPARE(i1.episodesCount.value_or(0), 9);
        QCOMPARE(i1.resolution, Resolution::FHD_1080P);
        QCOMPARE(i1.ripType, RipType::WEBDL);
        QVERIFY(between(i1.estimatedBitrateMbps, 5.5f, 6.5f));

        const auto i2 = ReleaseParser::parse(S("Во все тяжкие / Breaking Bad [01-05x01-62] (2008-2013) BDRip 720p | LostFilm"), 50 * GB, S("series"));
        QCOMPARE(i2.episodesCount.value_or(0), 62);
        QCOMPARE(i2.resolution, Resolution::HD_720P);
        QVERIFY(between(i2.estimatedBitrateMbps, 2.0f, 2.8f));
        QCOMPARE(i2.bitrateTier, BitrateTier::LIGHT);
        QVERIFY(i2.isMobileFriendly());

        const auto i3 = ReleaseParser::parse(S("Игра престолов / Game of Thrones [01-08x01-73 из 73] (2011-2019) BDRip 1080p | LostFilm"), 120 * GB, S("series"));
        QCOMPARE(i3.episodesCount.value_or(0), 73);
        QCOMPARE(i3.resolution, Resolution::FHD_1080P);
    }

    void parseEnglishDottedTitle() {
        const auto info = ReleaseParser::parse(S("Dune.Part.Two.2024.2160p.UHD.BluRay.x265.TrueHD.7.1.Atmos.DV.HDR10-SWTYBLZ"), 40 * GB, S("movie"));
        QCOMPARE(info.cleanTitle, S("Dune Part Two 2024"));
        QCOMPARE(info.year.value_or(0), 2024);
        QCOMPARE(info.resolution, Resolution::UHD_4K);
        QCOMPARE(info.codec, VideoCodec::HEVC);
        QCOMPARE(info.ripType, RipType::BLURAY);
        QCOMPARE(info.hdr, HdrType::DV);
        QVERIFY(!info.isCam);
    }

    void parseResidentEvilCollection() {
        const auto info = ReleaseParser::parse(S("Обитель зла / Resident Evil (2002-2023) BDRip [H.265/1080p] [10-bit] [Коллекция]"), qint64(83.3 * GB), S("movie"));
        QVERIFY(info.isCollection);
        QCOMPARE(info.collectionCount.value_or(0), 7);
        QCOMPARE(info.resolution, Resolution::FHD_1080P);
        QCOMPARE(info.codec, VideoCodec::HEVC);
        QCOMPARE(info.ripType, RipType::BDRIP);
        QVERIFY(between(info.estimatedBitrateMbps, 14.f, 18.f));
        QVERIFY(info.badges().contains(S("Коллекция (7)")));
    }

    void parseTrilogyCollection() {
        const auto info = ReleaseParser::parse(S("Властелин колец: Трилогия / The Lord of the Rings: Trilogy (2001-2003) BDRip 1080p"), 36 * GB, S("movie"));
        QVERIFY(info.isCollection);
        QCOMPARE(info.collectionCount.value_or(0), 3);
        QCOMPARE(info.resolution, Resolution::FHD_1080P);
        QVERIFY(between(info.estimatedBitrateMbps, 14.f, 18.f));
    }

    void nonVideoDetection() {
        QVERIFY(ReleaseParser::isNonVideo(S("Marvel's Spider-Man 2: Digital Deluxe Edition [RUS text / RUS sound / EUR / v01.005.001 / Лицензия / FPKG]")));
        QVERIFY(ReleaseParser::isNonVideo(S("Cyberpunk 2077 [v 2.13] (2020) PC | RePack от FitGirl")));
        QVERIFY(ReleaseParser::isNonVideo(S("The Witcher 3: Wild Hunt - Complete Edition (2015) PC | GOG")));
        QVERIFY(ReleaseParser::isNonVideo(S("Spider-Man 2"), S("PS4")));
        QVERIFY(ReleaseParser::isNonVideo(S("Any title"), S("Игры для ПК")));
        QVERIFY(ReleaseParser::isNonVideo(S("Any title"), S("Программы и Дизайн")));
        QVERIFY(ReleaseParser::isNonVideo(S("Any title"), S("Аудиокниги")));
        QVERIFY(ReleaseParser::isNonVideo(S("Any title"), S("Музыка (lossless)")));
        QVERIFY(!ReleaseParser::isNonVideo(S("Человек-паук 2 / Spider-Man 2 (2004) BDRip 1080p | DUB"), S("Зарубежное кино (HD Video)")));
        QVERIFY(!ReleaseParser::isNonVideo(S("Spider-Man: No Way Home (2021) 2160p UHD Blu-Ray Remux | HDR | Dolby Vision"), S("Кино (4K UHD)")));
        QVERIFY(!ReleaseParser::isNonVideo(S("Игра престолов / Game of Thrones [S01-S08] (2011-2019) BDRip 1080p"), S("Зарубежные сериалы")));
        QVERIFY(!ReleaseParser::isNonVideo(S("Человек-паук / Spider-Man: The Animated Series (1994) [01-05x01-65] WEBRip 720p"), S("Мультсериалы")));
    }

    void oneGameWordDoesNotHideAFilm() {
        QVERIFY(!ReleaseParser::isNonVideo(S("Dune.Part.Two.2024.REPACK.1080p.WEB-DL.x264")));
        QVERIFY(!ReleaseParser::isNonVideo(S("Бэтмен против Супермена: На заре справедливости [Ultimate Edition] (2016) BDRip 1080p")));
        QVERIFY(!ReleaseParser::isNonVideo(S("Хоббит: Нежданное путешествие (2012) Gold Edition BDRemux 1080p")));
        QVERIFY(ReleaseParser::isNonVideo(S("Elden Ring (2022) PC | RePack")));
        QVERIFY(ReleaseParser::isNonVideo(S("Cyberpunk 2077 [v 2.13] (2020) PC | RePack от FitGirl")));
    }

    void trailerWordInsideATitleIsNotATrailer() {
        QVERIFY(!ReleaseParser::isTrailerOrTeaser(S("Парни из Трейлерного парка / Trailer Park Boys (2001) WEBRip 720p")));
        QVERIFY(!ReleaseParser::isNonVideo(S("Парни из Трейлерного парка / Trailer Park Boys (2001) WEBRip 720p")));
        QVERIFY(!ReleaseParser::isTrailerOrTeaser(S("Трейлерный парк (2019) WEB-DL 1080p")));
    }

    void trailerAndTeaserRejection() {
        for (const auto &t : {S("Человек-паук: Новый день [2026] тизер-трейлер"), S("Мстители: Секретные войны (2026) Тизер [1080p]"),
                              S("GTA 6 Official Trailer 1080p"), S("Бэтмен 2 (2026) Русский трейлер")}) {
            QVERIFY2(ReleaseParser::isTrailerOrTeaser(t), qPrintable(t));
            QVERIFY2(ReleaseParser::isNonVideo(t), qPrintable(t));
        }
        QVERIFY(!ReleaseParser::isTrailerOrTeaser(S("Новый Человек-паук / The Amazing Spider-Man (2012) BDRip 1080p")));
    }

    void cyrillicWordBoundaries() {
        const auto s1 = ReleaseParser::parse(S("Дом Дракона (Сезон 1) (2022) 1080p"), 10 * GB, S("movie"));
        QCOMPARE(s1.resolution, Resolution::FHD_1080P);
        QVERIFY(between(s1.estimatedBitrateMbps, 3.f, 4.f));
        QCOMPARE(ReleaseParser::parse(S("Слово пацана (серии 1-8) (2023) WEBRip 720p"), 4 * GB, S("series")).episodesCount.value_or(0), 8);
        QCOMPARE(ReleaseParser::parse(S("Чернобыль (1 из 5) (2019) BDRip 1080p"), 3 * GB, S("series")).episodesCount.value_or(0), 5);
        QCOMPARE(ReleaseParser::parse(S("Начало / Inception (2010) BDRip 1080p | Дубляж")).voice, VoiceType::DUB);
        QCOMPARE(ReleaseParser::parse(S("Матрица / The Matrix (1999) BDRip 1080p | многоголосый")).voice, VoiceType::MVO);
        QCOMPARE(ReleaseParser::parse(S("Бойцовский клуб / Fight Club (1999) BDRip 1080p | двухголосый")).voice, VoiceType::DVO);
        const auto tr = ReleaseParser::parse(S("Хоббит: Трилогия (2012-2014) BDRemux 1080p"));
        QVERIFY(tr.isCollection);
        QCOMPARE(tr.collectionCount.value_or(0), 3);
        const auto du = ReleaseParser::parse(S("Дюна: Дилогия (2021-2024) BDRip 1080p"));
        QVERIFY(du.isCollection);
        QCOMPARE(du.collectionCount.value_or(0), 2);
        const auto qd = ReleaseParser::parse(S("Джон Уик: Квадрология (2014-2023) BDRip 1080p"));
        QVERIFY(qd.isCollection);
        QCOMPARE(qd.collectionCount.value_or(0), 4);
    }

    void videoCategoryWhitelist() {
        QVERIFY(!ReleaseParser::isNonVideo(S("Linkin Park - Live in Texas (2003) 1080p"), S("Музыкальное видео")));
        QVERIFY(!ReleaseParser::isNonVideo(S("Queen - Rock Montreal (1981) BDRip"), S("Видеоклипы и Концерты")));
        QVERIFY(!ReleaseParser::isNonVideo(S("Война и мир (1965) BDRip"), S("Экранизации зарубежной классики")));
        QVERIFY(!ReleaseParser::isNonVideo(S("Преступление и наказание (2007) 1080p"), S("Экранизации книг")));
        QVERIFY(ReleaseParser::isNonVideo(S("Linkin Park - Meteora (2003) FLAC"), S("Музыка (lossless)")));
        QVERIFY(ReleaseParser::isNonVideo(S("Стивен Кинг - Сияние FB2, EPUB"), S("Книги и журналы")));
        QVERIFY(ReleaseParser::isNonVideo(S("Some Game (2020) PC"), S("Игры для PC: фильм-квесты")));
        QVERIFY(!ReleaseParser::isNonVideo(S("Silent Hill (2006) 1080p"), S("Фильмы по играм")));
    }

    // ---- SearchFilterTest ----
    void mobilePreset() {
        SearchFilterCriteria f; f.preset = FilterPreset::MOBILE;
        const auto r = ids(f.filter(all()));
        QVERIFY(r.contains(S("3")));
        QVERIFY(!r.contains(S("1")));
        QVERIFY(!r.contains(S("2")));
        QVERIFY(!r.contains(S("4")));
    }
    void onlySeeded() {
        SearchFilterCriteria f; f.onlySeeded = true;
        const auto r = ids(f.filter(all()));
        QVERIFY(!r.contains(S("5")));
        QCOMPARE(r.size(), 4);
    }
    void sortBySizeAscending() {
        SearchFilterCriteria f; f.sort = SearchSort::SIZE_ASC;
        QCOMPARE(ids(f.filter(all())), (QStringList{S("4"), S("3"), S("5"), S("2"), S("1")}));
    }
    void resolutionFilter() {
        SearchFilterCriteria f; f.resolution = Resolution::UHD_4K;
        QCOMPARE(ids(f.filter(all())), QStringList{S("1")});
    }
    void queryCleaner() {
        QCOMPARE(SearchQueryCleaner::clean(S("фильмы новый человек паук")), S("новый человек паук"));
        QCOMPARE(SearchQueryCleaner::clean(S("сериал во все тяжкие смотреть")), S("во все тяжкие"));
        QCOMPARE(SearchQueryCleaner::clean(S("кино интерстеллар скачать в хорошем качестве")), S("интерстеллар"));
        QCOMPARE(SearchQueryCleaner::clean(S("мультфильм король лев онлайн")), S("король лев"));
        QCOMPARE(SearchQueryCleaner::clean(S("фильмы")), S("фильмы"));
    }
    void relevanceSpiderMan() {
        const auto a = SearchHit::make(S("a1"), S("Новый Человек-паук / The Amazing Spider-Man (2012) BDRip 1080p | D"), 8 * GB, 50, 5, true, S("movie"), S("RuTracker"));
        const auto b = SearchHit::make(S("b1"), S("Человек-паук / Spider-Man (2002) BDRip 1080p | D"), 8 * GB, 200, 10, true, S("movie"), S("RuTracker"));
        const auto c = SearchHit::make(S("c1"), S("Человек-паук: Новый день [2026] тизер-трейлер"), 500000000LL, 300, 10, true, S("movie"), S("RuTracker"));
        SearchFilterCriteria f; f.query = S("новый человек паук");
        QCOMPARE(ids(f.filter({b, c, a})), (QStringList{S("a1"), S("b1")}));
    }
    void relevanceWithYear() {
        const auto y12 = SearchHit::make(S("asm1"), S("Новый Человек-паук / The Amazing Spider-Man (2012) BDRip 1080p | D"), 8 * GB, 30, 5, true, S("movie"), S("Rutor"));
        const auto y14 = SearchHit::make(S("asm2"), S("Новый Человек-паук: Высокое напряжение / The Amazing Spider-Man 2 (2014) BDRip 1080p | D"), 9 * GB, 150, 15, true, S("movie"), S("Rutor"));
        SearchFilterCriteria f; f.query = S("новый человек паук 2012");
        QCOMPARE(ids(f.filter({y14, y12})), (QStringList{S("asm1"), S("asm2")}));
    }

    // ---- Выдача сервера ----
    void hitFromJson() {
        const QJsonObject j{{S("id"), 77}, {S("title"), S("Дюна (2021) WEB-DL 1080p")}, {S("size"), 4.0 * GB}, {S("seeders"), 12},
                            {S("ru"), true}, {S("sources"), QJsonArray{S("Rutor"), S("NNM-Club")}}, {S("date"), S("2024-03-01T10:00:00Z")},
                            {S("magnet"), S("magnet:?xt=urn:btih:abc")}};
        const auto h = SearchHit::fromJson(j);
        QVERIFY(h);
        QCOMPARE(h->id, S("77"));
        QCOMPARE(h->source, S("Rutor + NNM-Club"));
        QCOMPARE(h->date, S("2024-03-01"));
        QCOMPARE(h->kind, S("movie"));
        QCOMPARE(h->magnet.value_or(QString()), S("magnet:?xt=urn:btih:abc"));
        QCOMPARE(h->release.resolution, Resolution::FHD_1080P);
        QVERIFY(!SearchHit::fromJson(QJsonObject{{S("title"), S("x")}}));
        const auto p = SearchProviderInfo::parse(QJsonArray{QJsonObject{{S("name"), S("Rutor")}, {S("ok"), true}, {S("count"), 3}}});
        QCOMPARE(p.size(), 1);
        QVERIFY(p[0].ok);
        QCOMPARE(p[0].count, 3);
    }

private:
    static QList<SearchHit> all() {
        return {
            SearchHit::make(S("1"), S("Dune 2 (2024) BDRemux 2160p | DV | TrueHD"), 60 * GB, 100, 10, true, S("movie"), S("Rutor"), S("2024-03-01")),
            SearchHit::make(S("2"), S("Dune 2 (2024) BDRip 1080p | D"), 8 * GB, 50, 5, true, S("movie"), S("Rutor"), S("2024-03-02")),
            SearchHit::make(S("3"), S("Dune 2 (2024) WEB-DL 720p | D | x265"), 2 * GB, 30, 2, true, S("movie"), S("Rutor"), S("2024-03-03")),
            SearchHit::make(S("4"), S("Dune 2 (2024) CAMRip | Экранка"), 1500000000LL, 20, 2, true, S("movie"), S("Rutor"), S("2024-03-01")),
            SearchHit::make(S("5"), S("Old Dune WEB-DL 1080p"), 4 * GB, 0, 0, false, S("movie"), S("TPB"), S("2020-01-01")),
        };
    }
};

QTEST_GUILESS_MAIN(SearchTest)
#include "SearchTest.moc"
