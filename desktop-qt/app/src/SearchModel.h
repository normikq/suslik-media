#pragma once
#include "Library.h"

#include "suslik/core/Search.h"

#include <QHash>
#include <QSet>
#include <QObject>
#include <QVariantList>

#include <functional>
#include <optional>

/// «Поиск раздач» (SearchView.kt): запрос к серверу и, если включён, к RuTracker с этого ПК; выдача сервера показывается
/// сразу, RuTracker дописывается, когда ответит. Фильтры, сортировка и страницы — core::SearchFilterCriteria.
/// «＋ В библиотеку» — раздача уходит в AllDebrid, её состояние пишется под строкой до готовности.
/// «Подбор по настроению» (aiMode): запрос уходит на сервер (/api/search/ai-discovery, Gemini + TMDB), фильмы
/// сверяются с медиатекой — что уже есть, можно сразу смотреть, остальное — найти раздачу одним нажатием.
namespace suslik::app {

class SearchModel : public QObject {
    Q_OBJECT
    Q_PROPERTY(Library *library READ library WRITE setLibrary)
    Q_PROPERTY(bool aiMode READ aiMode WRITE setAiMode NOTIFY aiChanged)
    Q_PROPERTY(bool aiBusy READ aiBusy NOTIFY aiChanged)
    Q_PROPERTY(QString aiStatus READ aiStatus NOTIFY aiChanged)
    Q_PROPERTY(bool aiStatusOk READ aiStatusOk NOTIFY aiChanged)
    Q_PROPERTY(QString aiQuery READ aiQuery NOTIFY aiChanged)
    Q_PROPERTY(QVariantList aiHits READ aiHits NOTIFY aiChanged)
    Q_PROPERTY(int aiInLibrary READ aiInLibrary NOTIFY aiChanged)
    Q_PROPERTY(bool busy READ busy NOTIFY changed)
    Q_PROPERTY(bool ruSearching READ ruSearching NOTIFY changed)
    Q_PROPERTY(QString status READ status NOTIFY changed)
    Q_PROPERTY(bool statusOk READ statusOk NOTIFY changed)
    Q_PROPERTY(QVariantList providers READ providers NOTIFY changed)
    Q_PROPERTY(int hitCount READ hitCount NOTIFY changed)
    Q_PROPERTY(bool localActive READ localActive NOTIFY changed)

    Q_PROPERTY(QString kind READ kind WRITE setKind NOTIFY changed)
    Q_PROPERTY(int preset READ preset WRITE setPreset NOTIFY changed)
    Q_PROPERTY(int resolution READ resolution WRITE setResolution NOTIFY changed)   ///< -1 — любое
    Q_PROPERTY(int sort READ sort WRITE setSort NOTIFY changed)
    Q_PROPERTY(bool onlySeeded READ onlySeeded WRITE setOnlySeeded NOTIFY changed)
    Q_PROPERTY(bool onlyRu READ onlyRu WRITE setOnlyRu NOTIFY changed)
    Q_PROPERTY(QString refine READ refine WRITE setRefine NOTIFY changed)

    Q_PROPERTY(int page READ page WRITE setPage NOTIFY changed)
    Q_PROPERTY(int pageSize READ pageSize WRITE setPageSize NOTIFY changed)
    Q_PROPERTY(int totalPages READ totalPages NOTIFY changed)
    Q_PROPERTY(int shownCount READ shownCount NOTIFY changed)
    Q_PROPERTY(int fromIndex READ fromIndex NOTIFY changed)
    Q_PROPERTY(int toIndex READ toIndex NOTIFY changed)
    Q_PROPERTY(QVariantList pageRange READ pageRange NOTIFY changed)
    Q_PROPERTY(QVariantList rows READ rows NOTIFY changed)
    Q_PROPERTY(QVariantList presets READ presets CONSTANT)

public:
    explicit SearchModel(QObject *parent = nullptr) : QObject(parent) {}

    Library *library() const { return m_lib; }
    void setLibrary(Library *l);
    bool aiMode() const { return m_aiMode; }
    void setAiMode(bool on) { if (m_aiMode != on) { m_aiMode = on; emit aiChanged(); } }
    bool aiBusy() const { return m_aiBusy; }
    QString aiStatus() const { return m_aiStatus; }
    bool aiStatusOk() const { return m_aiStatusOk; }
    QString aiQuery() const { return m_aiQuery; }
    QVariantList aiHits() const { return m_aiHits; }
    int aiInLibrary() const;
    bool busy() const { return m_busy; }
    bool ruSearching() const { return m_ruSearching; }
    QString status() const { return m_status; }
    bool statusOk() const { return m_statusOk; }
    QVariantList providers() const;
    int hitCount() const { return int(m_hits.size()); }
    bool localActive() const;

    QString kind() const { return m_criteria.kind; }
    void setKind(const QString &k) { m_criteria.kind = k; refilter(true); }
    int preset() const { return int(m_criteria.preset); }
    void setPreset(int p) { m_criteria.preset = core::FilterPreset(p); refilter(true); }
    int resolution() const { return m_criteria.resolution ? int(*m_criteria.resolution) : -1; }
    void setResolution(int r);
    int sort() const { return int(m_criteria.sort); }
    void setSort(int s) { m_criteria.sort = core::SearchSort(s); refilter(true); }
    bool onlySeeded() const { return m_criteria.onlySeeded; }
    void setOnlySeeded(bool on) { m_criteria.onlySeeded = on; refilter(true); }
    bool onlyRu() const { return m_criteria.onlyRu; }
    void setOnlyRu(bool on) { m_criteria.onlyRu = on; refilter(true); }
    QString refine() const { return m_criteria.refine; }
    void setRefine(const QString &r) { m_criteria.refine = r; refilter(true); }

    int page() const { return m_page; }
    void setPage(int p) { m_page = p; emit changed(); }
    int pageSize() const { return m_pageSize; }
    void setPageSize(int s) { m_pageSize = s; m_page = 1; emit changed(); }
    int totalPages() const { return std::max<int>(1, (int(m_shown.size()) + m_pageSize - 1) / m_pageSize); }
    int shownCount() const { return int(m_shown.size()); }
    int fromIndex() const { return m_shown.isEmpty() ? 0 : (safePage() - 1) * m_pageSize; }
    int toIndex() const { return std::min<int>(fromIndex() + m_pageSize, int(m_shown.size())); }
    QVariantList pageRange() const;
    QVariantList rows() const;
    QVariantList presets() const;

    Q_INVOKABLE void run(const QString &query);
    Q_INVOKABLE void add(const QString &hitId);
    /// Подбор по настроению. fresh — не из памяти сервера и другие фильмы («Кинорулетка», «Ещё варианты»).
    Q_INVOKABLE void runAi(const QString &query, bool fresh = false);

    /// Раздача медиатеки для сверки с подборкой: нормализованные названия, имя раздачи, год, файл постера TMDB.
    struct LibraryKeys {
        qint64 id = 0;
        QSet<QString> names;
        QString text; ///< « имя раздачи » с пробелами по краям — поиск целых слов
        std::optional<int> year;
        QString posterFile;
    };
    static QList<LibraryKeys> libraryKeys(const QList<LibraryItem> &items, const std::function<QString(const LibraryItem &)> &titleOf,
                                          const std::function<std::optional<QString>(const LibraryItem &)> &posterOf);
    /// Сверка фильма подборки: тот же постер TMDB; или то же название (русское, оригинальное, из раздачи) и год ±1;
    /// или название целыми словами в имени раздачи и тот же год. Id раздачи или пусто.
    static std::optional<qint64> matchLibrary(const QList<LibraryKeys> &lib, const QVariantMap &film);
    /// Что искать на трекерах по «Найти раздачу»: русский фильм — русским названием, остальные — оригиналом.
    static QString trackerTitle(const QString &title, const QString &original, const QString &lang);
    /// Нормализация для сверки названий: строчные, «ё» → «е», только буквы и цифры через пробел.
    static QString normTitle(const QString &s);
    /// Окно «Трекеры» закрыто: включённые источники могли поменяться.
    Q_INVOKABLE void trackersChanged() { refilter(false); }
    /// Выход из вкладки поиска — фоновый браузер больше не нужен
    Q_INVOKABLE void leave();

    /// Страницы со вставками «…» (-1): для 10 страниц и пятой — 1, …, 4, 5, 6, …, 10 (Pagination.kt).
    static QList<int> computePageRange(int current, int total);

signals:
    void changed();
    void aiChanged();

private:
    int safePage() const { return std::clamp(m_page, 1, totalPages()); }
    void refilter(bool resetPage);
    void publish(bool ruPending, const std::optional<std::pair<QList<core::SearchHit>, QString>> &ru);
    void setNote(const QString &id, const QString &text, bool ok);
    void follow(const QString &hitId, qint64 id, bool readyNow, int round = 0, qint64 started = 0);
    void ready(const QString &hitId, qint64 id, const QString &note);
    /// force — новый ответ сервера; без него (медиатека изменилась) карточки пересоздаются, только если что-то поменялось.
    void rematch(bool force);

    Library *m_lib = nullptr;
    core::SearchFilterCriteria m_criteria;
    QList<core::SearchHit> m_hits, m_shown;
    QList<core::SearchHit> m_serverHits;
    QList<core::SearchProviderInfo> m_providers, m_serverProviders;
    bool m_serverFailed = false;
    QString m_serverError;
    bool m_hasRu = false;
    qint64 m_t0 = 0;
    QHash<QString, std::pair<QString, bool>> m_notes;
    bool m_busy = false, m_ruSearching = false;
    QString m_status;
    bool m_statusOk = true;
    int m_page = 1, m_pageSize = 25;
    int m_gen = 0;

    bool m_aiMode = false, m_aiBusy = false, m_aiStatusOk = true;
    QString m_aiStatus, m_aiQuery;
    QVariantList m_aiRaw;  ///< ответ сервера, без сверки
    QVariantList m_aiHits; ///< со сверкой: inLibrary, magnetId, path
    int m_aiGen = 0;
};

} // namespace suslik::app
