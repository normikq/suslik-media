#pragma once
#include "ReleasePick.h"

#include <QElapsedTimer>
#include <QJsonArray>
#include <QJsonObject>
#include <QObject>
#include <QPointer>
#include <QString>
#include <QTimer>
#include <functional>
#include <optional>

namespace suslik::core {
class ApiClient;
class CloudSync;
}
namespace suslik::app {
class Library;
class MpvEngine;
}

/// Этап 1 (docs/CYBERPUNK.md): вход по коду устройства, медиатека, выбор релиза, зеркало и прогресс — поверх
/// Library, CloudSync и Reserve (BurstProxy) из ПК-версии, без своих копий. Команды — ключами запуска (этап 1)
/// или с телефона VAO (этап 3, RemoteLink: remote(), status(), library()).
namespace suslik::cinema {

/// Что играть (ключ --play и уточнения к нему).
struct PlayArgs {
    QString query;          ///< id раздачи или часть названия
    QString quality;        ///< «4K», «1080p» или подпись релиза; пусто — как выбрал пользователь, иначе лучший
    qint64 release = 0;     ///< id раздачи-версии (из --list), 0 — по качеству
    int season = -1;        ///< сериал: номер сезона (−1 — последняя просмотренная серия)
    int episode = -1;       ///< номер серии (у сборника — фильма, с 1)
    bool fromStart = false; ///< не продолжать с сохранённого места
};

/// Строка в журнал player.log и в консоль, из которой запущен хост.
void say(const QString &s);

class CinemaSession : public QObject {
    Q_OBJECT
public:
    CinemaSession(core::ApiClient &api, core::CloudSync &sync, app::Library &lib, QObject *parent = nullptr);

    /// --login: код устройства в консоль и журнал, ждём подтверждения на сайте. Потом done(0).
    void login();
    /// --list [фильтр]: плитки медиатеки и их версии. Потом done(0).
    void list(const QString &filter);
    /// --play: найти фильм и релиз, получить адрес зеркала; ready(адрес через BurstProxy, место) или done(код ошибки).
    void play(const PlayArgs &args);

    /// Плеер открыл файл: связать с ним прогресс (раз в 10 с, при паузе, перемотке и выходе).
    void attach(app::MpvEngine &mpv);
    /// Пульт (клавиши хоста, позже — телефон VAO): seek± (10 с), jump± (10 мин), audio, sub (следующая дорожка), stats.
    void command(const QString &cmd);
    /// Сохранить место сейчас и отправить на сервер (выход).
    void flush();

    // ---- пульт VAO ----
    /// Режим пульта: ошибки не завершают хост, а уходят на телефон; страницу входа не открываем в браузере.
    void startRemote();
    /// Команда с телефона (RemoteLink): login, logout, refresh, play, toggle, seek, seekBy, audio, sub, volume, stop, opt, quit.
    void remote(const QJsonObject &cmd);
    /// Состояние для телефона: вход, что играет, место, дорожки, настройки.
    QJsonObject status() const;
    /// Медиатека для телефона: плитки, сезоны, релизы.
    QJsonArray library() const;
    // ---- комната «Вместе» (CinemaParty) ----
    /// Ключ просмотра играющего файла (magnetId|путь); пусто — ничего не играет.
    QString playingKey() const { return m_key; }
    QString playingTitle() const { return m_title; }
    std::optional<QString> canonicalId() const { return m_cid; }
    std::optional<QString> edition() const { return m_edition; }
    /// Варианты качества фильма (релизы плитки) и играющий — для комнаты.
    const QList<core::PartyVariant> &variants() const { return m_variants; }
    const core::PartyVariant &variant() const { return m_variant; }
    /// Открыть этот файл медиатеки с места startMs (гость идёт за ведущим). false — файла в медиатеке нет.
    bool playKey(const QString &key, qint64 startMs);
    /// Остановить фильм (место сохраняется).
    void stop();
    /// Перемотка от комнаты (движок синхронизации) и метки для него.
    void roomSeek(qint64 ms);
    qint64 seekStamp() const { return m_seekStamp; }
    /// Перемотка ещё идёт (движок комнаты ждёт её, как PartySync у ПК ждёт syncBusy).
    bool seeking() const;
    qint64 syncTimeMs() const;
    /// Пауза и перемотка заперты (гость комнаты): причина или пусто.
    void setControlLock(std::function<QString()> why) { m_lock = std::move(why); }
    /// Свои команды пульта (комната) и свои поля состояния.
    void setExtraCommands(std::function<bool(const QString &, const QJsonObject &)> f) { m_extra = std::move(f); }
    void setExtraStatus(std::function<void(QJsonObject &)> f) { m_extraStatus = std::move(f); }
    /// Сообщение на телефон и в журнал.
    void notify(const QString &text) { note(text); }

    /// Настройки пульта (хранилище settings): muteGame — глушить игру во время фильма, pauseWithGame — пауза с игрой.
    bool option(const QString &key) const;
    /// 3D-звук: 0 — выкл, 1 — стерео, 2 — объёмный, 3 — колонки (muffle — «за стеной глуше», option).
    int audio3d() const;
    /// Эффект проектора (ProjectorLook, 0 — выкл) и звук плёнки (0 — как есть).
    int projector() const;
    int filmSound() const;

signals:
    void ready(const QString &url, qint64 startMs);
    void done(int code);
    void statusChanged();
    void libraryChanged();
    /// Пульт: остановить фильм / выйти / поменялась настройка.
    void stopRequested();
    void quitRequested();
    void optionChanged(const QString &key);
    /// Фильм открыт (адрес готов) / остановлен.
    void opened(const QString &key);
    void stopped();

private:
    /// Сессия проверена и медиатека свежая (или сохранённая копия без сети): потом then(), иначе ошибка.
    void whenLibrary(std::function<void()> then);
    QList<std::pair<core::LibraryItem, QList<core::LibraryItem>>> tiles() const;
    std::optional<core::MFile> fileIn(const core::LibraryItem &item, const PlayArgs &args, int *season, int *episode) const;
    void openChoice(const core::LibraryItem &tile, const QList<Choice> &choices, int pick, const PlayArgs &args,
                    std::optional<qint64> startAt);
    void noteSeek(qint64 toMs);
    QList<Choice> choicesFor(const core::LibraryItem &tile, const QList<core::LibraryItem> &versions, const PlayArgs &args) const;
    void savePos();
    /// Сообщение человеку: в консоль, журнал и на телефон.
    void note(const QString &text);
    /// Не вышло: в режиме ключей — выход с кодом, в режиме пульта — сообщение на телефон.
    void fail(int code);

    core::ApiClient &m_api;
    core::CloudSync &m_sync;
    app::Library &m_lib;
    QPointer<app::MpvEngine> m_mpv;
    QTimer m_saveTimer;
    QString m_key;     ///< ключ прогресса играющего файла (magnetId|путь)
    QString m_tileKey; ///< тот же фильм у показанной плитки, если играет другая версия
    std::optional<QString> m_cid;
    QString m_mirror;  ///< подписанный адрес зеркала (BurstProxy запоминает длину по нему)
    qint64 m_lastSaved = -1;
    qint64 m_startMs = 0;
    QElapsedTimer m_openedAt;
    bool m_userSeeked = false;
    bool m_remote = false;
    bool m_libStarted = false;
    QString m_busy;    ///< «Готовлю …» — пока ищем релиз и адрес
    QString m_msg;     ///< последнее сообщение (номер — m_msgN: телефон показывает новое)
    int m_msgN = 0;
    QString m_title, m_release;
    int m_volume = 100;
    QList<core::PartyVariant> m_variants;
    core::PartyVariant m_variant;
    std::optional<QString> m_edition;
    qint64 m_seekStamp = 0, m_seekTarget = 0;
    bool m_seeking = false;
    QElapsedTimer m_seekAt;
    std::function<QString()> m_lock;
    std::function<bool(const QString &, const QJsonObject &)> m_extra;
    std::function<void(QJsonObject &)> m_extraStatus;
};

} // namespace suslik::cinema
