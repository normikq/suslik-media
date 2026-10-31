//! Журнал плеера `player.log` в папке данных — тот же файл и формат строк, что у `PlayerLog.kt`: запись в фоне,
//! больше 2 МБ (у отладочной сборки 20 МБ) — старый уходит в `player.old.log`.

use crate::app_dir;
use std::io::Write;
use std::sync::mpsc::{channel, Sender};
use std::sync::{Mutex, OnceLock};

fn writer() -> &'static Mutex<Sender<String>> {
    static W: OnceLock<Mutex<Sender<String>>> = OnceLock::new();
    W.get_or_init(|| {
        let (tx, rx) = channel::<String>();
        let _ = std::thread::Builder::new().name("player-log".into()).spawn(move || {
            let file = app_dir::file("player.log");
            let limit = if cfg!(feature = "diag") { 20_000_000 } else { 2_000_000 };
            for line in rx {
                if std::fs::metadata(&file).map(|m| m.len()).unwrap_or(0) > limit {
                    let old = app_dir::file("player.old.log");
                    let _ = std::fs::remove_file(&old);
                    let _ = std::fs::rename(&file, &old);
                }
                if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(&file) {
                    let _ = f.write_all(line.as_bytes());
                }
            }
        });
        Mutex::new(tx)
    })
}

pub fn write(msg: &str) {
    let line = format!("{}  {msg}\n", chrono::Local::now().format("%m-%d %H:%M:%S%.3f"));
    if cfg!(debug_assertions) {
        eprint!("{line}");
    }
    let _ = writer().lock().unwrap_or_else(|e| e.into_inner()).send(line);
}
