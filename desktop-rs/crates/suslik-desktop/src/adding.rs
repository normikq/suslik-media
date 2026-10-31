//! Добавленная раздача и её путь до медиатеки (`Added`, `follow`, `rejectIfNoVideo` из `AddView.kt`): опрос AllDebrid,
//! пока не будет готова, проверка, что в ней есть видео, и обновление медиатеки.

use crate::api::Api;
use crate::library::{now_ms, Library};
use crate::strings::tr;
use serde_json::Value;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use suslik_core::json::{opt_array, opt_bool, opt_i32, opt_i64, opt_str};
use suslik_core::torrent;

#[derive(Debug, Clone)]
pub struct Added {
    pub name: String,
    pub note: String,
    pub ok: bool,
    /// 0..1 во время загрузки, `None` — неизвестно.
    pub progress: Option<f32>,
    pub done: bool,
}

impl Added {
    pub fn new(name: &str) -> Self {
        Self { name: name.into(), note: tr("add.otpravlyayu", &[]), ok: true, progress: None, done: false }
    }
}

/// Строка «Добавлено сейчас», которую меняет фоновый поток.
pub type Row = Arc<Mutex<Added>>;

pub fn set(row: &Row, lib: &Library, f: impl FnOnce(&mut Added)) {
    f(&mut row.lock().unwrap());
    lib.repaint_now();
}

/// Раздача без видео (игра, музыка, программа): текст отказа, иначе `None`. Список файлов AllDebrid отдаёт только
/// готовой раздаче, поэтому проверка — в момент готовности. Такую раздачу просим удалить.
fn reject_if_no_video(api: &Api, id: i64) -> Option<String> {
    let files = api.get(&format!("/api/magnets/{id}/files")).ok()?;
    let names: Vec<String> = opt_array(&files, "files")?.iter().map(|f| opt_str(f, "name")).collect();
    let rejected = torrent::rejection_for_paths(&names)?;
    let removed = api.post(&format!("/api/magnets/{id}/cancel"), None).is_ok();
    Some(if removed { rejected } else { rejected + &tr("add.rejected_cleanup_note", &[]) })
}

/// Ответ сервера на добавление → опрос до готовности (сначала каждые 4 с, через минуту — каждые 15 с).
pub fn follow(lib: &Library, row: &Row, res: &Value) {
    let api = lib.api();
    let id = opt_i64(res, "id", 0);
    let ready = |note: String| {
        let rejected = reject_if_no_video(api, id);
        set(row, lib, |a| {
            match rejected {
                Some(r) => {
                    a.note = r;
                    a.ok = false;
                }
                None => {
                    a.note = note;
                    a.progress = Some(1.0);
                }
            }
            a.done = true;
        });
        lib.refresh();
    };
    if opt_bool(res, "ready", false) {
        ready(tr("add.gotovo_uzhe_v_biblioteke", &[]));
        return;
    }
    set(row, lib, |a| a.note = tr("add.dobavleno_alldebrid_kachaet", &[]));
    let started = now_ms();
    for _ in 0..120 {
        std::thread::sleep(Duration::from_millis(if now_ms() - started < 60_000 { 4000 } else { 15000 }));
        let Ok(st) = api.get(&format!("/api/magnets/{id}")) else { continue };
        let (total, got, code) =
            (opt_i64(&st, "size", 0), opt_i64(&st, "downloaded", 0), opt_i32(&st, "statusCode", 0));
        if code == 4 || opt_bool(&st, "ready", false) {
            ready(tr("add.gotovo_fayly_v_biblioteke", &[]));
            return;
        }
        if code >= 5 {
            set(row, lib, |a| {
                a.note = tr("add.oshibka_p", &[("p", &opt_str(&st, "status"))]);
                a.ok = false;
            });
            return;
        }
        let status = opt_str(&st, "status");
        set(row, lib, |a| {
            a.progress = (total > 0).then(|| (got as f32 / total as f32).clamp(0.0, 1.0));
            a.note = if status.trim().is_empty() { tr("add.obrabotka", &[]) } else { status };
            if total > 0 {
                a.note += &format!(" · {}%", got * 100 / total);
            }
        });
    }
}
