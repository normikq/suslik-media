#pragma once
#include <QString>
#include <QStringList>

/// Звук игры во время фильма (Windows Core Audio): приглушаются только сессии процесса игры на всех устройствах вывода,
/// другие программы не трогаются. Сессии, которые игрок приглушил сам, не трогаем.
///
/// Какие сессии приглушили мы — пишется в game-mute.json. Windows запоминает приглушение программы, и сессия игры
/// после перезапуска та же (тот же идентификатор). Поэтому сессия, которой не было, когда звук возвращали (игру
/// закрыли во время фильма, хост упал), остаётся «нашей» в файле, и звук ей возвращается, как только игра снова
/// запущена (restorePending), — иначе новая игра шла бы без звука, а хост считал бы, что её приглушил игрок.
namespace suslik::cinema {

class GameAudio {
public:
    /// Читает из файла сессии, которым звук ещё не вернули.
    explicit GameAudio(QString exeName = QStringLiteral("Cyberpunk2077.exe"));
    ~GameAudio();

    /// Сколько сессий изменено (-1 — Core Audio недоступен).
    int mute();
    /// Вернуть звук сессиям, которые приглушили мы; не найденные (игра закрыта) ждут её запуска.
    int unmute();
    /// Сейчас приглушено нами (идёт фильм).
    bool muted() const { return m_active; }
    /// Есть сессии из прошлого, которым звук ещё не вернули.
    bool hasPending() const { return !m_pending.isEmpty(); }
    /// Вернуть звук таким сессиям, если игра снова запущена. Сколько возвращено.
    int restorePending();

    /// При запуске хоста: прошлый сеанс не снял приглушение (падение, игру закрыли во время фильма) — снять.
    static int recoverAfterCrash(const QString &exeName = QStringLiteral("Cyberpunk2077.exe"));
    /// Снять приглушение со всех сессий игры, чьё бы оно ни было (--unmute-game). -1 — Core Audio недоступен.
    static int unmuteAll(const QString &exeName = QStringLiteral("Cyberpunk2077.exe"));

private:
    void saveState() const;

    QString m_exe;
    bool m_active = false;
    QStringList m_ours;    // приглушены нами сейчас
    QStringList m_pending; // приглушены нами раньше, звук ещё не вернули (игры не было)
};

} // namespace suslik::cinema
