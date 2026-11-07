#pragma once
#include <QString>
#include <QStringList>

/// Звук игры во время фильма (Windows Core Audio): приглушаются только сессии процесса игры на всех устройствах вывода,
/// другие программы не трогаются. Какие сессии приглушили мы — пишется в файл: если хост упал, при следующем запуске
/// recoverAfterCrash() возвращает звук. Сессии, которые игрок приглушил сам, не трогаем.
namespace suslik::cinema {

class GameAudio {
public:
    explicit GameAudio(QString exeName = QStringLiteral("Cyberpunk2077.exe"));
    ~GameAudio();

    /// Сколько сессий изменено (-1 — Core Audio недоступен).
    int mute();
    /// Вернуть звук сессиям, которые приглушили мы.
    int unmute();
    bool muted() const { return !m_ours.isEmpty(); }

    /// Прошлый сеанс не снял приглушение (падение) — снять. Сколько сессий восстановлено.
    static int recoverAfterCrash(const QString &exeName = QStringLiteral("Cyberpunk2077.exe"));

private:
    void saveState() const;

    QString m_exe;
    QStringList m_ours; // идентификаторы сессий, приглушённых нами
};

} // namespace suslik::cinema
