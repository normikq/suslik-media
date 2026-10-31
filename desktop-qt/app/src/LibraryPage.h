#pragma once
#include "Library.h"

#include <QObject>
#include <QVariantList>

/// Страница медиатеки («Главная» или «Моя медиатека»): поиск, категория, сортировка, вид, «только готовые», страницы;
/// плитки и баннер «Продолжить просмотр» готовыми данными для QML. Логика — LibraryView.kt.
namespace suslik::app {

class LibraryPage : public QObject {
    Q_OBJECT
    Q_PROPERTY(Library *library READ library WRITE setLibrary NOTIFY libraryChanged)
    Q_PROPERTY(bool mine READ mine WRITE setMine NOTIFY settingsChanged)
    Q_PROPERTY(QString query READ query WRITE setQuery NOTIFY settingsChanged)
    Q_PROPERTY(int category READ category WRITE setCategory NOTIFY settingsChanged)
    /// 0 — название, 1 — сначала новые, 2 — размер, 3 — избранное.
    Q_PROPERTY(int sortBy READ sortBy WRITE setSortBy NOTIFY settingsChanged)
    /// 0 — сетка, 1 — список.
    Q_PROPERTY(int viewMode READ viewMode WRITE setViewMode NOTIFY settingsChanged)
    Q_PROPERTY(bool onlyReady READ onlyReady WRITE setOnlyReady NOTIFY settingsChanged)
    /// Сколько постеров в ряд помещается (считает QML по ширине).
    Q_PROPERTY(int columns READ columns WRITE setColumns NOTIFY settingsChanged)
    Q_PROPERTY(int page READ page WRITE setPage NOTIFY resultChanged)
    Q_PROPERTY(int pageCount READ pageCount NOTIFY resultChanged)
    Q_PROPERTY(int total READ total NOTIFY resultChanged)
    Q_PROPERTY(bool emptyMine READ emptyMine NOTIFY resultChanged)
    Q_PROPERTY(QVariantList tiles READ tiles NOTIFY resultChanged)
    /// Номера страниц для «‹ 1 2 … 9 ›» (−1 — многоточие).
    Q_PROPERTY(QVariantList pageNumbers READ pageNumbers NOTIFY resultChanged)
    /// «Продолжить просмотр»: до 4 фильмов, первым — смотренный последним.
    Q_PROPERTY(QVariantList continueEntries READ continueEntries NOTIFY resultChanged)

public:
    explicit LibraryPage(QObject *parent = nullptr);

    Library *library() const { return m_lib; }
    void setLibrary(Library *l);
    bool mine() const { return m_mine; }
    void setMine(bool v) { if (m_mine != v) { m_mine = v; m_page = 0; changed(); } }
    QString query() const { return m_query; }
    void setQuery(const QString &v) { if (m_query != v) { m_query = v; m_page = 0; changed(); } }
    int category() const { return m_category; }
    void setCategory(int v) { if (m_category != v) { m_category = v; m_page = 0; changed(); } }
    int sortBy() const { return m_sortBy; }
    void setSortBy(int v) { if (m_sortBy != v) { m_sortBy = v; m_page = 0; changed(); } }
    int viewMode() const { return m_viewMode; }
    void setViewMode(int v) { if (m_viewMode != v) { m_viewMode = v; changed(); } }
    bool onlyReady() const { return m_onlyReady; }
    void setOnlyReady(bool v) { if (m_onlyReady != v) { m_onlyReady = v; m_page = 0; changed(); } }
    int columns() const { return m_columns; }
    void setColumns(int v) { v = std::max(1, v); if (m_columns != v) { m_columns = v; changed(); } }
    int page() const { return m_page; }
    void setPage(int v);
    int pageCount() const { return m_pageCount; }
    int total() const { return m_total; }
    bool emptyMine() const { return m_emptyMine; }
    QVariantList tiles() const { return m_tiles; }
    QVariantList pageNumbers() const { return m_pageNumbers; }
    QVariantList continueEntries() const { return m_continue; }

    /// Сезон на плитке сериала (из меню правой кнопки): плитка показывает и играет его.
    Q_INVOKABLE void chooseSeason(qint64 id, int seasonIndex);
    /// Нажали на плитку: версии на выбор (versionsRequested) или окно «Смотреть» (playRequested).
    Q_INVOKABLE void open(qint64 id);
    /// Баннер «Продолжить»: открыть этот файл.
    Q_INVOKABLE void openContinue(qint64 id, const QString &path);
    /// Действия над всей плиткой (все её версии).
    Q_INVOKABLE void setMineGroup(qint64 id, bool on);
    Q_INVOKABLE void setFavoriteGroup(qint64 id, bool on);
    Q_INVOKABLE void askAi(qint64 id);
    Q_INVOKABLE void showAi(qint64 id);

signals:
    void libraryChanged();
    void settingsChanged();
    void resultChanged();
    /// Версии одного фильма: какую включить.
    void versionsRequested(const QString &title, const QVariantList &ids);
    /// Окно «Смотреть»: раздача и файл.
    void playRequested(qint64 magnetId, const QString &path);

private:
    void changed();
    void rebuild();
    QVariantMap tileOf(const LibraryItem &item, const QList<LibraryItem> &versions) const;
    std::optional<MFile> currentFile(const LibraryItem &item) const;
    QList<LibraryItem> group(qint64 id) const;

    Library *m_lib = nullptr;
    bool m_mine = false;
    QString m_query;
    int m_category = 0, m_sortBy = 0, m_viewMode = 0, m_columns = 6, m_page = 0;
    bool m_onlyReady = false;
    int m_pageCount = 1, m_total = 0;
    bool m_emptyMine = false;
    bool m_queued = false;
    QVariantList m_tiles, m_pageNumbers, m_continue;
    QHash<qint64, QList<LibraryItem>> m_versionsOf;
    QHash<qint64, std::pair<int, int>> m_season; // id → (сезон, серия), выбранные в меню
};

/// «2014» из названия раздачи или файлов (как на плитке старой версии; без года — «2024»).
QString itemYear(const LibraryItem &item);
/// Строки характеристик плитки: «1080p • H265 • 23.98 fps» и «12 Мбит/с • 8,4 ГБ».
std::pair<QString, QString> itemSpecs(const LibraryItem &item, const std::optional<QString> &media);
/// Одна отметка для картинки и одна для звука.
QStringList collapseTags(const QStringList &all);

} // namespace suslik::app
