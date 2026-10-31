//! Маленькое хранилище «ключ — значение» на диске (вход, cookie, прогресс, настройки). Повторяет `Store` из `Api.kt`
//! и пишет те же файлы `<имя>.properties` в формате Java, так что Kotlin- и Rust-версии видят одни данные.
//!
//! Один экземпляр на файл ([`Store::of`]). Запись — в фоне и с задержкой 400 мс (плеер сохраняет место каждые 10 с,
//! переписывать файл в потоке окна — заметный рывок). Новый файл сначала целиком ложится на диск (`sync`), и только
//! потом получает имя; прежний остаётся `.bak`. Повреждённый файл (пустой или с нулями после выключения питания,
//! 18.10) — читаем копию.

use crate::app_dir;
use std::collections::{BTreeMap, HashMap};
use std::io::Write;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;
use suslik_core::properties;

pub struct Store {
    file: PathBuf,
    bak: PathBuf,
    data: Mutex<BTreeMap<String, String>>,
    pending: Mutex<bool>,
    /// Держится всё время записи: две записи (фоновая и при выходе) не кладут старую копию поверх новой.
    file_lock: Mutex<()>,
}

fn registry() -> &'static Mutex<HashMap<String, Arc<Store>>> {
    static ALL: OnceLock<Mutex<HashMap<String, Arc<Store>>>> = OnceLock::new();
    ALL.get_or_init(Default::default)
}

impl Store {
    pub fn of(name: &str) -> Arc<Store> {
        registry()
            .lock()
            .unwrap()
            .entry(name.to_string())
            .or_insert_with(|| {
                let file = app_dir::file(&format!("{name}.properties"));
                let bak = app_dir::file(&format!("{name}.properties.bak"));
                Arc::new(Store {
                    data: Mutex::new(load(&file, &bak)),
                    file,
                    bak,
                    pending: Mutex::new(false),
                    file_lock: Mutex::new(()),
                })
            })
            .clone()
    }

    pub fn get(&self, k: &str) -> Option<String> {
        self.data.lock().unwrap().get(k).cloned()
    }

    pub fn put(self: &Arc<Self>, k: &str, v: Option<&str>) {
        {
            let mut d = self.data.lock().unwrap();
            match v {
                Some(v) => d.insert(k.to_string(), v.to_string()),
                None => d.remove(k),
            };
        }
        self.schedule_save();
    }

    pub fn keys(&self) -> Vec<String> {
        self.data.lock().unwrap().keys().cloned().collect()
    }

    pub fn entries(&self) -> BTreeMap<String, String> {
        self.data.lock().unwrap().clone()
    }

    fn schedule_save(self: &Arc<Self>) {
        let mut p = self.pending.lock().unwrap();
        if *p {
            return;
        }
        *p = true;
        let me = self.clone();
        std::thread::Builder::new()
            .name("store-writer".into())
            .spawn(move || {
                std::thread::sleep(Duration::from_millis(400));
                me.flush();
            })
            .ok();
    }

    pub fn flush(&self) {
        let _guard = self.file_lock.lock().unwrap();
        let copy = {
            let mut p = self.pending.lock().unwrap();
            if !*p {
                return;
            }
            *p = false;
            self.data.lock().unwrap().clone()
        };
        let _ = self.write(&copy);
    }

    fn write(&self, map: &BTreeMap<String, String>) -> std::io::Result<()> {
        let tmp = self.file.with_extension("properties.tmp");
        {
            let mut f = std::fs::File::create(&tmp)?;
            f.write_all(&properties::write(map))?;
            f.sync_all()?;
        }
        if self.file.exists() {
            std::fs::rename(&self.file, &self.bak)?;
        }
        std::fs::rename(&tmp, &self.file)
    }

    /// Всё на диск (при выходе из приложения).
    pub fn flush_all() {
        let all: Vec<Arc<Store>> = registry().lock().unwrap().values().cloned().collect();
        for s in all {
            s.flush();
        }
    }
}

/// Файл или копия перед последней записью, если файл повреждён. Запись с управляющими символами отбрасывается
/// (нули в заголовке Cookie ломали каждый запрос).
fn load(file: &PathBuf, bak: &PathBuf) -> BTreeMap<String, String> {
    for src in [file, bak] {
        let Ok(bytes) = std::fs::read(src) else { continue };
        if bytes.is_empty() || bytes.contains(&0) {
            continue;
        }
        let Ok(mut map) = properties::parse(&bytes) else { continue };
        map.retain(|k, v| !k.chars().chain(v.chars()).any(|c| c < ' '));
        return map;
    }
    BTreeMap::new()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn set_dir() -> PathBuf {
        static D: OnceLock<PathBuf> = OnceLock::new();
        D.get_or_init(|| {
            let d = std::env::temp_dir().join(format!("suslik-store-test-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&d);
            std::env::set_var("SUSLIK_APPDIR", &d);
            assert_eq!(app_dir::dir(), &d);
            d
        })
        .clone()
    }

    #[test]
    fn writes_java_properties_and_keeps_a_copy() {
        let d = set_dir();
        let s = Store::of("t_write");
        s.put("w:42|Сезон 1/E01.mkv", Some("1000"));
        s.flush();
        s.put("x", Some("y"));
        s.flush();
        let text = std::fs::read_to_string(d.join("t_write.properties")).unwrap();
        assert!(text.contains("w\\:42|\\u0421\\u0435\\u0437\\u043E\\u043D\\ 1/E01.mkv=1000"), "{text}");
        assert!(d.join("t_write.properties.bak").exists());
        assert!(!d.join("t_write.properties.tmp").exists());
    }

    #[test]
    fn damaged_file_falls_back_to_the_copy() {
        let d = set_dir();
        std::fs::write(d.join("t_bad.properties"), [0u8; 16]).unwrap();
        std::fs::write(d.join("t_bad.properties.bak"), "sid=abc\nbad=a\\u0000b\n").unwrap();
        let s = Store::of("t_bad");
        assert_eq!(s.get("sid").as_deref(), Some("abc"));
        assert_eq!(s.get("bad"), None);
    }
}
