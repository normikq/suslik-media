#pragma once
#include "PlayRequest.h"

#include "Library.h"

#include <QObject>
#include <QVariantList>

/// Окно «Смотреть» (PlayDialog.kt): фильм, сериал (сезон и серия) или сборник (фильм из списка); с места или с начала;
/// ссылки зеркала, озвучки, субтитры и очередь готовятся до того, как кнопка включится.
namespace suslik::app {


class PlayModel : public QObject {
    Q_OBJECT
    Q_PROPERTY(Library *library READ library WRITE setLibrary)
    Q_PROPERTY(qint64 magnetId READ magnetId NOTIFY changed)
    /// 0 — фильм, 1 — сериал, 2 — сборник.
    Q_PROPERTY(int kind READ kind NOTIFY changed)
    Q_PROPERTY(QString heading READ heading NOTIFY changed)
    Q_PROPERTY(QString currentTitle READ currentTitle NOTIFY changed)
    Q_PROPERTY(QString poster READ poster NOTIFY changed)
    Q_PROPERTY(QStringList chips READ chips NOTIFY changed)
    Q_PROPERTY(QString rating READ rating NOTIFY changed)
    Q_PROPERTY(QString overview READ overview NOTIFY changed)
    Q_PROPERTY(QString collectionLine READ collectionLine NOTIFY changed)
    Q_PROPERTY(QString collectionHint READ collectionHint NOTIFY changed)
    Q_PROPERTY(QVariantList films READ films NOTIFY changed)
    Q_PROPERTY(int filmIndex READ filmIndex WRITE setFilmIndex NOTIFY changed)
    Q_PROPERTY(QStringList seasons READ seasons NOTIFY changed)
    Q_PROPERTY(int seasonIndex READ seasonIndex WRITE setSeasonIndex NOTIFY changed)
    Q_PROPERTY(QString seasonValue READ seasonValue NOTIFY changed)
    Q_PROPERTY(QStringList episodes READ episodes NOTIFY changed)
    Q_PROPERTY(int episodeIndex READ episodeIndex WRITE setEpisodeIndex NOTIFY changed)
    Q_PROPERTY(QString episodeValue READ episodeValue NOTIFY changed)
    /// «С 1:02:15» (пусто — места нет: смотреть с начала).
    Q_PROPERTY(QString resumeLabel READ resumeLabel NOTIFY changed)
    Q_PROPERTY(double resumeFraction READ resumeFraction NOTIFY changed)
    Q_PROPERTY(bool fromStart READ fromStart WRITE setFromStart NOTIFY changed)
    Q_PROPERTY(QString status READ status NOTIFY changed)
    Q_PROPERTY(bool loading READ loading NOTIFY changed)
    Q_PROPERTY(bool canWatch READ canWatch NOTIFY changed)
    Q_PROPERTY(bool mine READ mine NOTIFY changed)
    Q_PROPERTY(bool favorite READ favorite NOTIFY changed)

public:
    explicit PlayModel(QObject *parent = nullptr);

    Library *library() const { return m_lib; }
    void setLibrary(Library *l);
    qint64 magnetId() const { return m_id; }
    int kind() const { return m_collection ? 2 : m_series ? 1 : 0; }
    QString heading() const;
    QString currentTitle() const;
    QString poster() const;
    QStringList chips() const;
    QString rating() const;
    QString overview() const;
    QString collectionLine() const;
    QString collectionHint() const;
    QVariantList films() const;
    int filmIndex() const { return m_film; }
    void setFilmIndex(int i);
    QStringList seasons() const;
    int seasonIndex() const { return m_season; }
    void setSeasonIndex(int i);
    QString seasonValue() const;
    QStringList episodes() const;
    int episodeIndex() const { return m_episode; }
    void setEpisodeIndex(int i);
    QString episodeValue() const;
    QString resumeLabel() const;
    double resumeFraction() const;
    bool fromStart() const { return m_fromStart; }
    void setFromStart(bool v) { if (m_fromStart != v) { m_fromStart = v; emit changed(); } }
    QString status() const;
    bool loading() const { return m_loading; }
    bool canWatch() const { return !m_loading && m_mirror.has_value(); }
    bool mine() const;
    bool favorite() const;

    /// Открыть: раздача и файл (из плитки, баннера, выбора версии).
    Q_INVOKABLE void load(qint64 magnetId, const QString &path);
    Q_INVOKABLE void toggleMine();
    Q_INVOKABLE void toggleFavorite();
    /// «Смотреть»: собирает запрос плеера и отдаёт его (playRequested).
    Q_INVOKABLE void play();

    const PlayRequest &request() const { return m_request; }

signals:
    void changed();
    void playRequested();

private:
    const core::LibraryItem *item() const;
    core::MFile currentFile() const;
    QList<core::Episode> filmList() const;
    std::pair<QString, QString> filmInfo(const core::Episode &ep, int index) const;
    QList<core::MFile> queueFiles() const;
    Presence presenceOf(const core::MFile &f) const;
    std::optional<MarkerQuery> markerQueryOf(const core::MFile &f) const;
    std::optional<core::WatchInfo> watch() const;
    void fetch();

    Library *m_lib = nullptr;
    qint64 m_id = 0;
    QString m_startPath;
    bool m_series = false, m_collection = false;
    int m_film = 0, m_season = 0, m_episode = 0;
    bool m_fromStart = false;
    bool m_loading = true;
    QString m_status;
    std::optional<QString> m_mirror, m_playlist;
    QStringList m_subs, m_audio;
    QHash<QString, QString> m_sidePaths;
    int m_gen = 0;
    PlayRequest m_request;
};

} // namespace suslik::app
