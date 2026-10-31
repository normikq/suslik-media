//! Папка данных: `%APPDATA%\SuslikMedia` на Windows (отладочная сборка — `SuslikMediaDebug`), `~/.suslikmedia` в
//! других системах. Та же, что у Kotlin-версии (`AppDir` в `Api.kt`): вход, прогресс и настройки общие.

use std::path::PathBuf;
use std::sync::OnceLock;

pub fn dir() -> &'static PathBuf {
    static DIR: OnceLock<PathBuf> = OnceLock::new();
    DIR.get_or_init(|| {
        // SUSLIK_APPDIR задают только тесты: иначе они писали бы во вход и прогресс владельца.
        let dir = if let Some(o) = std::env::var_os("SUSLIK_APPDIR").filter(|o| !o.is_empty()) {
            PathBuf::from(o)
        } else {
            let name = if cfg!(feature = "diag") { "SuslikMediaDebug" } else { "SuslikMedia" };
            match std::env::var_os("APPDATA") {
                Some(a) => PathBuf::from(a).join(name),
                None => home().join(format!(".{}", name.to_lowercase())),
            }
        };
        let _ = std::fs::create_dir_all(&dir);
        dir
    })
}

fn home() -> PathBuf {
    std::env::var_os("USERPROFILE").or_else(|| std::env::var_os("HOME")).map(PathBuf::from).unwrap_or_default()
}

pub fn file(name: &str) -> PathBuf {
    dir().join(name)
}
