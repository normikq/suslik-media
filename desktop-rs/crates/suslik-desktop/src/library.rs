//! Медиатека: вход, список раздач, избранное и «Мои фильмы», постеры. Повторяет `Library.kt`: те же запросы к серверу
//! и те же файлы в папке данных (`library.json`, `posters.properties`, `poster_title`, `poster_anim`, `media_info`,
//! `watch`, картинки в `posters\`), так что Kotlin- и Rust-версии видят одно и то же.
//!
//! Сеть — в фоновых потоках; окно читает состояние под замком ([`Library::state`]) и перерисовывается по
//! `request_repaint`.

use crate::api::{Api, ApiError};
use crate::app_dir;
use crate::catalog;
use crate::store::Store;
use crate::strings::tr;
use eframe::egui;
use serde_json::{json, Value};
use sha1::{Digest, Sha1};
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use suslik_core::auth::{self, DevicePairing, DevicePoll, SessionInfo, UserProfile};
use suslik_core::json::{has, opt_bool, opt_f64, opt_i32, opt_i64, opt_object, opt_str};
use suslik_core::library_keep::LibraryKeep;
use suslik_core::model::{
    ai_title_ready_at, parse_library, prepare_items, tile_title, watch_key, LibraryItem, MFile, Magnet, WatchInfo,
};
use suslik_core::{rx, url_codec};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StatusKind {
    Info,
    Ok,
    Error,
}

/// Кто вошёл: общий пароль или пользователь Discord.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Profile {
    pub name: String,
    pub note: String,
    pub avatar_url: Option<String>,
}

impl Profile {
    pub fn shared() -> Self {
        Self { name: tr("sidebar.profil_imya", &[]), note: tr("sidebar.profil_vhod_po_parolyu", &[]), avatar_url: None }
    }

    fn of(u: Option<&UserProfile>) -> Self {
        match u {
            Some(u) if !u.is_shared() => Self {
                name: if u.name.trim().is_empty() { tr("profile.user", &[]) } else { u.name.clone() },
                note: tr(if u.is_admin() { "profile.admin" } else { "profile.member" }, &[]),
                avatar_url: u.avatar_url.clone(),
            },
            _ => Self::shared(),
        }
    }
}

pub struct LibState {
    pub items: Arc<Vec<LibraryItem>>,
    pub loading: bool,
    pub status: String,
    pub status_kind: StatusKind,
    /// `None` — ещё не знаем (проверка на старте или нет сети), `Some(false)` — нужен вход.
    pub authenticated: Option<bool>,
    pub profile: Profile,
    pub pairing: Option<DevicePairing>,
    pub login_busy: bool,
    pub login_error: Option<String>,
    /// Когда этому пользователю снова можно спросить ИИ (мс; 0 — сейчас).
    pub ai_ready_at: i64,
    /// Ошибка последнего действия с плиткой (избранное, удаление): показывается в строке состояния.
    pub notice: Option<String>,
    ru_titles: HashMap<String, String>,
    poster_paths: HashMap<String, String>,
    animation: HashMap<String, bool>,
    server_tags: HashMap<i64, Vec<String>>,
    /// Растут, когда меняются названия, постеры (слияние версий) и жанры: от них пересчитывается список на экране.
    pub title_version: u64,
    pub poster_version: u64,
    pub anim_version: u64,
}

impl LibState {
    /// Что плитка пишет под постером (русское название TMDB, если ИИ не предложил своё).
    pub fn title_of(&self, item: &LibraryItem) -> String {
        tile_title(item, |k| self.ru_titles.get(k).cloned())
    }

    pub fn poster_path_of(&self, item: &LibraryItem) -> Option<String> {
        item.poster_queries.iter().find_map(|q| self.poster_paths.get(&q.to_lowercase()).cloned())
    }

    pub fn is_animation(&self, key: &str) -> bool {
        self.animation.get(key) == Some(&true)
    }

    /// Dolby Vision / HDR / DTS, как их нашёл ffprobe на сервере (пока не ответил — по именам файлов).
    pub fn tags_of(&self, item: &LibraryItem) -> Vec<String> {
        self.server_tags.get(&item.m.id).cloned().unwrap_or_else(|| catalog::item_tags(item))
    }

    fn set_status(&mut self, text: String, kind: StatusKind) {
        self.status = text;
        self.status_kind = kind;
    }
}

struct Inner {
    api: Api,
    ctx: egui::Context,
    st: Mutex<LibState>,
    snapshot: std::path::PathBuf,
    library_etag: Arc<Store>,
    poster_index: Arc<Store>,
    anim_index: Arc<Store>,
    title_index: Arc<Store>,
    media_index: Arc<Store>,
    episodes: Arc<Store>,
    continue_hidden: Arc<Store>,
    watch: Arc<Store>,
    posters: Posters,
    media_busy: Mutex<HashSet<String>>,
    media_lock: Mutex<()>,
}

#[derive(Clone)]
pub struct Library(Arc<Inner>);

/// Картинок постеров на диске не больше 100 МБ; не показанная 7 дней удаляется (при показе скачается снова).
const POSTER_CAP: u64 = 100 << 20;
const POSTER_UNUSED_DAYS: u64 = 7;
const DAY_MS: i64 = 86_400_000;

pub fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

fn spawn(name: &str, f: impl FnOnce() + Send + 'static) {
    let _ = std::thread::Builder::new().name(name.into()).spawn(f);
}

impl Library {
    pub fn new(ctx: egui::Context, base: String) -> Self {
        let inner = Arc::new(Inner {
            api: Api::new(base),
            ctx,
            st: Mutex::new(LibState {
                items: Arc::new(Vec::new()),
                loading: false,
                status: tr("library.nazhmite_obnovit_chtoby_zagruz", &[]),
                status_kind: StatusKind::Info,
                authenticated: None,
                profile: Profile::shared(),
                pairing: None,
                login_busy: false,
                login_error: None,
                ai_ready_at: 0,
                notice: None,
                ru_titles: HashMap::new(),
                poster_paths: HashMap::new(),
                animation: HashMap::new(),
                server_tags: HashMap::new(),
                title_version: 0,
                poster_version: 0,
                anim_version: 0,
            }),
            snapshot: app_dir::file("library.json"),
            library_etag: Store::of("library_etag"),
            poster_index: Store::of("posters"),
            anim_index: Store::of("poster_anim"),
            title_index: Store::of("poster_title"),
            media_index: Store::of("media_info"),
            episodes: Store::of("last_episode"),
            continue_hidden: Store::of("continue_hidden"),
            watch: Store::of("watch"),
            posters: Posters::default(),
            media_busy: Mutex::new(HashSet::new()),
            media_lock: Mutex::new(()),
        });
        Posters::start_workers(&inner);
        Library(inner)
    }

    pub fn state(&self) -> MutexGuard<'_, LibState> {
        self.0.st.lock().unwrap()
    }

    pub fn api(&self) -> &Api {
        &self.0.api
    }

    /// Перерисовать окно (из фонового потока).
    pub fn repaint_now(&self) {
        self.0.ctx.request_repaint();
    }

    /// Старт: сохранённая копия списка сразу, потом проверка входа и свежий список.
    pub fn start(&self) {
        let me = self.clone();
        spawn("library-start", move || {
            me.restore_snapshot();
            match me.check_session() {
                Some(true) => me.refresh_now(),
                // нет сети: всё равно пробуем с сохранённой копией
                None if !me.state().items.is_empty() => me.refresh_now(),
                _ => {}
            }
            std::thread::sleep(Duration::from_secs(4));
            me.prune_poster_files();
        });
    }

    fn repaint(&self) {
        self.0.ctx.request_repaint();
    }

    fn publish(&self, list: Vec<Magnet>) {
        // Раздача только с озвучками .mka — внешняя озвучка к фильму: отдельной плиткой её не показываем.
        let list: Vec<Magnet> = list.into_iter().filter(|m| !is_voice_over_only(m)).collect();
        let items = prepare_items(list);
        self.state().items = Arc::new(items);
        self.repaint();
    }

    fn restore_snapshot(&self) {
        if !self.state().items.is_empty() {
            return;
        }
        let Ok(text) = std::fs::read_to_string(&self.0.snapshot) else { return };
        let Ok(v) = serde_json::from_str::<Value>(&text) else { return };
        self.publish(parse_library(&v));
        self.state().set_status(tr("library.pokazana_sohranennaya_kopiya_o", &[]), StatusKind::Info);
    }

    /// Профиль ставится раньше `authenticated`: от него закрывается экран входа.
    fn check_session(&self) -> Option<bool> {
        let res = self.0.api.get(auth::SESSION_PATH).ok()?;
        let s = SessionInfo::parse(&res);
        let mut st = self.state();
        st.profile = if s.authenticated { Profile::of(s.user.as_ref()) } else { Profile::shared() };
        st.authenticated = Some(s.authenticated);
        drop(st);
        self.repaint();
        Some(s.authenticated)
    }

    // ---- вход ----

    pub fn login(&self, password: String) {
        if self.begin_login() {
            let me = self.clone();
            spawn("login", move || {
                let r = me.0.api.post("/api/login", Some(&json!({ "password": password })));
                let err = match r {
                    Ok(_) => {
                        if me.check_session().is_none() {
                            me.state().authenticated = Some(true);
                        }
                        None
                    }
                    Err(e) => Some(e.describe()),
                };
                me.end_login(err);
                me.after_login();
            });
        }
    }

    fn begin_login(&self) -> bool {
        let mut st = self.state();
        if st.login_busy {
            return false;
        }
        st.login_busy = true;
        st.login_error = None;
        true
    }

    fn end_login(&self, err: Option<String>) {
        let mut st = self.state();
        st.login_busy = false;
        st.login_error = err;
        drop(st);
        self.repaint();
    }

    fn after_login(&self) {
        if self.state().authenticated == Some(true) {
            self.refresh_now();
        }
    }

    /// «Войти через Discord»: код устройства, страница подтверждения в браузере и опрос сервера каждые 2 с.
    pub fn start_device_auth(&self) {
        if !self.begin_login() {
            return;
        }
        let me = self.clone();
        spawn("device-auth", move || {
            let site = me.0.api.site();
            let res = me.0.api.post(auth::DEVICE_START, Some(&auth::start_body("Suslik Media Windows", &site)));
            let pairing = match res {
                Ok(v) => auth::parse_pairing(&v, &site).ok_or_else(|| tr("api.net_svyazi_s_serverom", &[])),
                Err(e) => Err(e.describe()),
            };
            match pairing {
                Err(e) => me.end_login(Some(e)),
                Ok(p) => {
                    me.0.ctx.open_url(egui::OpenUrl::new_tab(&p.url));
                    me.state().pairing = Some(p.clone());
                    me.end_login(None);
                    me.poll_device(p);
                }
            }
        });
    }

    fn poll_device(&self, p: DevicePairing) {
        loop {
            std::thread::sleep(Duration::from_secs(2));
            if self.state().pairing.as_ref() != Some(&p) {
                return; // отменили или начали заново
            }
            let r = match self.0.api.post(auth::DEVICE_POLL, Some(&auth::poll_body(&p))) {
                Ok(v) => auth::parse_poll(&v),
                // 410: код истёк (10 мин) — без этого экран ждал бы вечно
                Err(ApiError::Http { code, .. }) => auth::poll_failed(code),
                Err(_) => DevicePoll::Pending,
            };
            match r {
                DevicePoll::Pending => continue,
                DevicePoll::Expired => {
                    let mut st = self.state();
                    st.pairing = None;
                    st.login_error = Some(tr("login.vremya_ozhidaniya_isteklo", &[]));
                    drop(st);
                    self.repaint();
                    return;
                }
                DevicePoll::Authorized { token, user } => {
                    // Токен устройства живёт дольше cookie сеанса и уходит с каждым запросом.
                    if let Some(t) = token {
                        self.0.api.set_device_token(Some(&t));
                    }
                    let mut st = self.state();
                    st.profile = Profile::of(user.as_ref());
                    st.pairing = None;
                    st.authenticated = Some(true);
                    drop(st);
                    self.repaint();
                    self.refresh_now();
                    return;
                }
            }
        }
    }

    pub fn cancel_device_auth(&self) {
        self.state().pairing = None;
    }

    pub fn logout(&self) {
        let me = self.clone();
        spawn("logout", move || {
            let _ = me.0.api.post("/api/logout", None);
            me.0.api.set_device_token(None);
            {
                let mut st = me.state();
                st.authenticated = Some(false);
                st.items = Arc::new(Vec::new());
                st.profile = Profile::shared();
                st.pairing = None;
            }
            let _ = std::fs::remove_file(&me.0.snapshot);
            me.repaint();
        });
    }

    // ---- список ----

    pub fn refresh(&self) {
        let me = self.clone();
        spawn("library-refresh", move || me.refresh_now());
    }

    fn refresh_now(&self) {
        {
            let mut st = self.state();
            if st.loading {
                return;
            }
            st.loading = true;
            st.set_status(tr("library.zagruzka_torrentov_i_faylov", &[]), StatusKind::Info);
        }
        self.repaint();
        let changed = self.load_list();
        self.state().loading = false;
        self.repaint();
        if changed {
            let me = self.clone();
            spawn("library-classify", move || me.classify_animation());
            let me = self.clone();
            spawn("library-tags", move || me.refresh_tags());
        }
    }

    /// `true`, если пришёл новый список (а не «не изменился»).
    fn load_list(&self) -> bool {
        // Тот же ETag, что в прошлый раз: короткий 304 вместо всего списка.
        let known = self.0.library_etag.get("etag").filter(|_| !self.state().items.is_empty());
        match self.0.api.get_if_changed("/api/library", known.as_deref()) {
            Ok(res) if res.code == 304 => {
                let mut st = self.state();
                let n = st.items.len().to_string();
                st.set_status(tr("library.zagruzheno_size_torrent_ov", &[("size", &n)]), StatusKind::Ok);
                false
            }
            Ok(res) => {
                let json = res.json;
                let list = parse_library(&json);
                let n = list.len().to_string();
                self.state().ai_ready_at = ai_title_ready_at(&json);
                self.publish(list);
                let items = self.state().items.clone();
                self.clean_up(&items);
                let _ = std::fs::write(&self.0.snapshot, json.to_string());
                let stale = opt_bool(&json, "stale", false);
                self.0.library_etag.put("etag", if stale { None } else { res.etag.as_deref() });
                let mut st = self.state();
                if stale {
                    st.set_status(tr("library.alldebrid_ne_otvetil_pokazana", &[("size", &n)]), StatusKind::Info);
                } else {
                    st.set_status(tr("library.zagruzheno_size_torrent_ov", &[("size", &n)]), StatusKind::Ok);
                }
                true
            }
            Err(e) => {
                let mut st = self.state();
                if matches!(e, ApiError::Http { code: 401, .. }) {
                    st.authenticated = Some(false);
                }
                st.set_status(e.describe(), StatusKind::Error);
                false
            }
        }
    }

    /// Что оставили фильмы, ушедшие из медиатеки: постеры, строка о видео, прогресс, последняя серия, скрытые
    /// «Продолжить». (Запас на диске — с `BurstProxy`, этап 5.)
    fn clean_up(&self, list: &[LibraryItem]) {
        let keep = LibraryKeep::new(list);
        if !keep.usable() {
            return;
        }
        let path =
            |v: Option<String>| v.and_then(|v| v.split_once('|').map(|(_, p)| p.to_string())).filter(|p| !p.is_empty());
        let idx = &self.0.poster_index;
        let keys = idx.keys();
        let kept: HashSet<String> =
            keys.iter().filter(|k| keep.keeps_poster(k)).filter_map(|k| path(idx.get(k))).collect();
        let gone: Vec<String> = keys
            .iter()
            .filter(|k| !keep.keeps_poster(k))
            .filter_map(|k| path(idx.get(k)))
            .filter(|p| !kept.contains(p))
            .collect();
        let prune = |store: &Arc<Store>, keeps: &dyn Fn(&str) -> bool| {
            for k in store.keys() {
                if !keeps(&k) {
                    store.put(&k, None);
                }
            }
        };
        prune(&self.0.poster_index, &|k| keep.keeps_poster(k));
        prune(&self.0.title_index, &|k| keep.keeps_poster(k));
        for p in gone {
            let _ = std::fs::remove_file(poster_file(&p));
        }
        prune(&self.0.media_index, &|k| keep.keeps_key(k));
        prune(&self.0.watch, &|k| keep.keeps_key(k));
        prune(&self.0.episodes, &|k| keep.keeps_key(k));
        prune(&self.0.continue_hidden, &|k| keep.keeps_key(k));
        self.prune_poster_files();
    }

    /// Не показанные 7 дней картинки удаляются, и всего их не больше 100 МБ: первыми уходят давно не показанные.
    fn prune_poster_files(&self) {
        let Ok(dir) = std::fs::read_dir(app_dir::file("posters")) else { return };
        let old = SystemTime::now() - Duration::from_secs(POSTER_UNUSED_DAYS * 86_400);
        let mut files: Vec<(std::path::PathBuf, SystemTime, u64)> = Vec::new();
        for e in dir.flatten() {
            let Ok(meta) = e.metadata() else { continue };
            if !meta.is_file() {
                continue;
            }
            let at = meta.modified().unwrap_or(UNIX_EPOCH);
            if at < old {
                let _ = std::fs::remove_file(e.path());
            } else {
                files.push((e.path(), at, meta.len()));
            }
        }
        let mut total: u64 = files.iter().map(|f| f.2).sum();
        files.sort_by_key(|f| f.1);
        for (p, _, len) in files {
            if total <= POSTER_CAP {
                break;
            }
            total -= len;
            let _ = std::fs::remove_file(p);
        }
    }

    // ---- действия с плиткой ----

    fn update_items(&self, f: impl Fn(&LibraryItem) -> Option<LibraryItem>) -> Arc<Vec<LibraryItem>> {
        let mut st = self.state();
        let before = st.items.clone();
        st.items = Arc::new(before.iter().map(|i| f(i).unwrap_or_else(|| i.clone())).collect());
        before
    }

    /// Избранное (сервер хранит его по cookie устройства) или «Мои фильмы» для всех версий плитки: список меняется сразу,
    /// при ошибке возвращается.
    pub fn set_flag(&self, ids: Vec<i64>, mine: bool, on: bool) {
        let before = self.update_items(|i| {
            ids.contains(&i.m.id).then(|| {
                let mut c = i.clone();
                if mine {
                    c.m.mine = on;
                } else {
                    c.m.favorite = on;
                }
                c
            })
        });
        self.repaint();
        let me = self.clone();
        spawn("library-flag", move || {
            for id in ids {
                let r = if mine {
                    me.0.api.post(auth::SHELF_PATH, Some(&auth::shelf_body(id, on)))
                } else {
                    me.0.api.post("/api/favorites", Some(&json!({"id": id, "favorite": on})))
                };
                if let Err(e) = r {
                    let mut st = me.state();
                    st.items = before;
                    st.notice = Some(e.describe());
                    break;
                }
            }
            me.repaint();
        });
    }

    /// «Удалить» стирает раздачу из AllDebrid у всех; незаконченная загрузка отменяется.
    pub fn remove(&self, m: &Magnet) {
        let (id, path) = (m.id, format!("/api/magnets/{}/{}", m.id, if m.downloading() { "cancel" } else { "delete" }));
        let me = self.clone();
        spawn("library-remove", move || {
            match me.0.api.post(&path, None) {
                Ok(_) => {
                    let mut st = me.state();
                    st.items = Arc::new(st.items.iter().filter(|i| i.m.id != id).cloned().collect());
                }
                Err(e) => me.state().notice = Some(e.describe()),
            }
            me.repaint();
        });
    }

    // ---- прогресс (ключ «magnetId|path» → «pos|len|at», пишет плеер) ----

    pub fn watch_info(&self, key: &str) -> Option<WatchInfo> {
        let v = self.0.watch.get(key)?;
        let p: Vec<&str> = v.split('|').collect();
        if p.len() < 3 {
            return None;
        }
        Some(WatchInfo { pos: p[0].parse().ok()?, len: p[1].parse().unwrap_or(0), at: p[2].parse().unwrap_or(0) })
    }

    /// Место в фильме (как `savePos` в `PlayerView.kt`): меньше 5 с не пишется, за 15 с до конца — досмотрен.
    pub fn save_watch(&self, key: &str, t: i64, len: i64) {
        if t < 5_000 {
            return;
        }
        let done = len > 0 && t > len - 15_000;
        let pos = if done { len } else { t };
        self.0.watch.put(key, Some(&format!("{pos}|{}|{}", len.max(0), now_ms())));
    }

    /// Серия, которую открыли последней (карточка сериала предлагает её).
    pub fn save_episode(&self, id: i64, path: &str) {
        self.0.episodes.put(&id.to_string(), Some(path));
    }

    pub fn last_watched(&self, m: &Magnet) -> Option<(MFile, WatchInfo)> {
        let mut best: Option<(MFile, WatchInfo)> = None;
        for f in m.files.iter().filter(|f| !f.is_folder) {
            let Some(w) = self.watch_info(&watch_key(m.id, f)) else { continue };
            if best.as_ref().is_none_or(|b| w.at > b.1.at) {
                best = Some((f.clone(), w));
            }
        }
        best
    }

    pub fn last_episode(&self, id: i64) -> Option<String> {
        self.0.episodes.get(&id.to_string())
    }

    // ---- «1920×1080 · 12 Мбит/с» (ffprobe на сервере, по одному) ----

    pub fn cached_media_line(&self, id: i64, f: &MFile) -> Option<String> {
        let raw = self.0.media_index.get(&format!("{id}|{}", f.path))?;
        let j: Value = serde_json::from_str(&raw).ok()?;
        let failed_at = opt_i64(&j, "failedAt", 0);
        if failed_at > 0 {
            return (now_ms() - failed_at < 6 * 3_600_000).then(|| tr("library.razreshenie_n_d", &[]));
        }
        Some(format_media_info(&j))
    }

    /// Спросить сервер о файле, если ещё не знаем (ответ появится в [`Self::cached_media_line`]).
    pub fn want_media_line(&self, id: i64, f: &MFile) {
        let key = format!("{id}|{}", f.path);
        let Some(link) = f.link.clone() else { return };
        if self.0.media_index.get(&key).is_some() || !self.0.media_busy.lock().unwrap().insert(key.clone()) {
            return;
        }
        let (me, path) = (self.clone(), f.path.clone());
        spawn("media-line", move || {
            let _one = me.0.media_lock.lock().unwrap();
            let body = json!({"link": link, "magnetId": id, "path": path});
            match me.0.api.post("/api/media-metadata", Some(&body)) {
                Ok(v) => {
                    if let Some(meta) = opt_object(&v, "metadata") {
                        me.0.media_index.put(&key, Some(&meta.to_string()));
                    }
                }
                Err(ApiError::Http { code, .. }) if code >= 500 => {
                    me.0.media_index.put(&key, Some(&json!({"failedAt": now_ms()}).to_string()));
                }
                Err(_) => {}
            }
            me.0.media_busy.lock().unwrap().remove(&key);
            me.repaint();
        });
    }

    // ---- жанр, русское название и путь постера всех фильмов (не только видимых плиток) ----

    fn note_title(st: &mut LibState, key: &str, t: &str) {
        if t.is_empty() {
            if st.ru_titles.remove(key).is_some() {
                st.title_version += 1;
            }
        } else if st.ru_titles.insert(key.to_string(), t.to_string()).as_deref() != Some(t) {
            st.title_version += 1;
        }
    }

    fn note_poster(st: &mut LibState, key: &str, path: &str) {
        if st.poster_paths.get(key).map(String::as_str) != Some(path) {
            st.poster_paths.insert(key.to_string(), path.to_string());
            st.poster_version += 1;
        }
    }

    /// Ответ `/api/poster`: жанр TMDB (Animation), русское название и путь картинки — в память и на диск.
    fn record_answer(&self, key: &str, j: &Value) {
        let now = now_ms();
        let mut st = self.state();
        if has(j, "animation") {
            let a = opt_bool(j, "animation", false);
            self.0.anim_index.put(key, Some(&format!("{now}|{}", if a { 1 } else { 0 })));
            st.animation.insert(key.to_string(), a);
            st.anim_version += 1;
        }
        let t = if j.get("title_ru").is_some_and(|v| !v.is_null()) {
            opt_str(j, "title_ru").trim().to_string()
        } else {
            String::new()
        };
        self.0.title_index.put(key, Some(&format!("{now}|{t}")));
        Self::note_title(&mut st, key, &t);
    }

    /// Сначала из сохранённых ответов, потом спрашивает сервер о фильмах, которых ещё не знает (не больше 80 за раз).
    fn classify_animation(&self) {
        let items = self.state().items.clone();
        let now = now_ms();
        let mut todo: Vec<String> = Vec::new();
        {
            let mut st = self.state();
            let no_genre: &dyn Fn(&str) -> bool = &|_| false;
            for item in items.iter() {
                let need_genre = !(catalog::is_anime(item) || catalog::is_cartoon(item, no_genre));
                let (mut genre_known, mut poster_known, mut title_fresh) = (!need_genre, false, false);
                for q in &item.poster_queries {
                    let key = q.to_lowercase();
                    if let Some(v) = self.0.title_index.get(&key) {
                        let (at, t) = v.split_once('|').unwrap_or((&v, ""));
                        if !t.is_empty() {
                            Self::note_title(&mut st, &key, t);
                        }
                        let at = at.parse::<i64>().unwrap_or(0);
                        title_fresh |= now - at < if t.is_empty() { 3 } else { 30 } * DAY_MS;
                    }
                    if let Some(v) = self.0.anim_index.get(&key) {
                        let (at, a) = v.split_once('|').unwrap_or((&v, ""));
                        if now - at.parse::<i64>().unwrap_or(0) < 60 * DAY_MS {
                            st.animation.insert(key.clone(), a == "1");
                            genre_known = true;
                        }
                    }
                    if let Some(p) =
                        self.0.poster_index.get(&key).and_then(|v| v.split_once('|').map(|x| x.1.to_string()))
                    {
                        if p.starts_with("/api/poster/img/") {
                            Self::note_poster(&mut st, &key, &p);
                            poster_known = true;
                        }
                    }
                }
                if !genre_known || !poster_known || !title_fresh {
                    if let Some(q) = item.poster_queries.first() {
                        if !todo.contains(q) {
                            todo.push(q.clone());
                        }
                    }
                }
            }
            st.anim_version += 1;
        }
        self.repaint();
        for (n, q) in todo.into_iter().take(80).enumerate() {
            let _gate = self.0.posters.gate();
            if let Ok(j) = self.0.api.get(&format!("/api/poster?q={}", url_codec::encode(&q))) {
                let key = q.to_lowercase();
                let p = opt_str(&j, "poster");
                if p.starts_with("/api/poster/img/") {
                    Self::note_poster(&mut self.state(), &key, &p);
                }
                self.record_answer(&key, &j);
            }
            drop(_gate);
            if n % 5 == 4 {
                self.repaint();
            }
            std::thread::sleep(Duration::from_millis(120));
        }
        self.repaint();
    }

    /// Метки сервера; новые фильмы он разбирает в фоне по одному, поэтому спрашиваем снова, пока у него что-то осталось.
    fn refresh_tags(&self) {
        for _ in 0..40 {
            let Ok(j) = self.0.api.get("/api/tags") else { return };
            if let Some(tags) = j.get("tags").and_then(Value::as_object) {
                let mut st = self.state();
                for (k, a) in tags {
                    let (Ok(id), Some(a)) = (k.parse::<i64>(), a.as_array()) else { continue };
                    st.server_tags.insert(id, a.iter().filter_map(|t| t.as_str().map(String::from)).collect());
                }
            }
            self.repaint();
            if opt_i32(&j, "pending", 0) <= 0 {
                return;
            }
            std::thread::sleep(Duration::from_secs(30));
        }
    }

    // ---- постеры ----

    /// Картинка плитки, если уже готова; иначе ставит её в очередь (ответ — перерисовкой окна).
    pub fn poster(&self, queries: &[String]) -> Option<egui::TextureHandle> {
        self.0.posters.want(&self.0, queries)
    }

    fn poster_for(&self, query: &str) -> Result<Option<egui::ColorImage>, ()> {
        let key = query.to_lowercase();
        let stored = self.0.poster_index.get(&key);
        let (at, stored_path) = match &stored {
            Some(v) => {
                let (a, p) = v.split_once('|').unwrap_or((v, ""));
                (a.parse::<i64>().unwrap_or(0), Some(p.to_string()))
            }
            None => (0, None),
        };
        let fresh = stored.is_some()
            && now_ms() - at < if stored_path.as_deref().unwrap_or("").is_empty() { 3 } else { 30 } * DAY_MS;
        let path = if fresh {
            stored_path.filter(|p| !p.is_empty())
        } else {
            let j = self.0.api.get(&format!("/api/poster?q={}", url_codec::encode(query))).map_err(|_| ())?;
            let p = Some(opt_str(&j, "poster")).filter(|p| p.starts_with("/api/poster/img/"));
            self.record_answer(&key, &j);
            self.0.poster_index.put(&key, Some(&format!("{}|{}", now_ms(), p.as_deref().unwrap_or(""))));
            p
        };
        let Some(path) = path else { return Ok(None) };
        Self::note_poster(&mut self.state(), &key, &path);
        self.load_image(&path, 320)
    }

    fn load_image(&self, path: &str, max_w: u32) -> Result<Option<egui::ColorImage>, ()> {
        let f = poster_file(path);
        let bytes = match std::fs::read(&f) {
            // показан сейчас: файл считается недавно нужным (чистка удаляет давно не показанные)
            Ok(b) => {
                let _ = std::fs::File::options().append(true).open(&f).and_then(|h| h.set_modified(SystemTime::now()));
                b
            }
            Err(_) => match self.0.api.bytes(path) {
                Ok(Some(b)) => {
                    let _ = std::fs::write(&f, &b);
                    b
                }
                Ok(None) => return Ok(None),
                Err(_) => return Err(()),
            },
        };
        Ok(decode(&bytes, max_w))
    }
}

fn is_voice_over_only(m: &Magnet) -> bool {
    let files: Vec<&MFile> = m.files.iter().filter(|f| !f.is_folder).collect();
    !files.is_empty() && files.iter().all(|f| f.path.to_lowercase().ends_with(".mka"))
}

/// Файл картинки: SHA-1 пути на сервере, как у Kotlin-версии.
fn poster_file(path: &str) -> std::path::PathBuf {
    let dir = app_dir::file("posters");
    let _ = std::fs::create_dir_all(&dir);
    dir.join(poster_file_name(path))
}

fn poster_file_name(path: &str) -> String {
    let name: String = Sha1::digest(path.as_bytes()).iter().map(|b| format!("{b:02x}")).collect();
    format!("{name}.img")
}

/// Постер TMDB (500 px) в памяти занимает 1,5 МБ; в размер плитки (320 px) — около 0,6 МБ.
fn decode(bytes: &[u8], max_w: u32) -> Option<egui::ColorImage> {
    let img = image::load_from_memory(bytes).ok()?;
    let img = if img.width() > max_w {
        let h = (img.height() as u64 * max_w as u64 / img.width() as u64).max(1) as u32;
        img.resize_exact(max_w, h, image::imageops::FilterType::CatmullRom)
    } else {
        img
    };
    let rgba = img.to_rgba8();
    Some(egui::ColorImage::from_rgba_unmultiplied([rgba.width() as usize, rgba.height() as usize], rgba.as_raw()))
}

/// «1920×1080 · 12 Мбит/с · HEVC · 23.98 fps» (`formatMediaInfo`).
pub fn format_media_info(m: &Value) -> String {
    let mut parts: Vec<String> = Vec::new();
    let (w, h) = (opt_i32(m, "width", 0), opt_i32(m, "height", 0));
    if w > 0 && h > 0 {
        parts.push(format!("{w}×{h}"));
    }
    let br = opt_f64(m, "bitrateMbps", 0.0);
    parts.push(if br > 0.0 {
        plain_decimal(br, 1) + &tr("library.mbit_s", &[])
    } else {
        tr("library.bitreyt_n_d", &[])
    });
    let codec = opt_str(m, "codec");
    if !codec.trim().is_empty() {
        parts.push(codec.to_uppercase());
    }
    if let Some(c) = suslik_core::jrx::caps(rx!(r"^(\d+)/(\d+)$"), &opt_str(m, "frameRate")) {
        let a: f64 = suslik_core::jrx::group(&c, 1).parse().unwrap_or(0.0);
        let b: f64 = suslik_core::jrx::group(&c, 2).parse().unwrap_or(0.0);
        if b > 0.0 && a / b > 0.0 {
            parts.push(plain_decimal(a / b, 2) + " fps");
        }
    }
    parts.join(" · ")
}

/// `BigDecimal(v).setScale(n, HALF_UP).stripTrailingZeros().toPlainString()`.
fn plain_decimal(v: f64, scale: usize) -> String {
    let s = format!("{:.*}", scale, v);
    if s.contains('.') {
        s.trim_end_matches('0').trim_end_matches('.').to_string()
    } else {
        s
    }
}

// ---- очередь постеров: 4 потока, первыми — последние запрошенные (то, что на экране сейчас) ----

enum Slot {
    Queued(Instant),
    Done(Option<egui::TextureHandle>, Instant),
}

#[derive(Default)]
struct Posters {
    slots: Mutex<HashMap<String, Slot>>,
    queue: Mutex<Vec<Vec<String>>>,
    wake: Condvar,
    /// Не больше 4 запросов к `/api/poster` сразу (как `posterGate` у Kotlin).
    gate_count: Mutex<usize>,
    gate_free: Condvar,
}

struct Gate<'a>(&'a Posters);

impl Drop for Gate<'_> {
    fn drop(&mut self) {
        *self.0.gate_count.lock().unwrap() -= 1;
        self.0.gate_free.notify_one();
    }
}

/// Картинок в памяти не больше стольких (остальные снова прочитаются с диска).
const POSTERS_IN_MEMORY: usize = 72;

impl Posters {
    fn gate(&self) -> Gate<'_> {
        let mut n = self.gate_count.lock().unwrap();
        while *n >= 4 {
            n = self.gate_free.wait(n).unwrap();
        }
        *n += 1;
        Gate(self)
    }

    fn start_workers(inner: &Arc<Inner>) {
        for i in 0..4 {
            let lib = Library(inner.clone());
            spawn(&format!("poster-{i}"), move || loop {
                let queries = {
                    let mut q = lib.0.posters.queue.lock().unwrap();
                    loop {
                        if let Some(job) = q.pop() {
                            break job;
                        }
                        q = lib.0.posters.wake.wait(q).unwrap();
                    }
                };
                lib.0.posters.run(&lib, queries);
            });
        }
    }

    fn want(&self, inner: &Inner, queries: &[String]) -> Option<egui::TextureHandle> {
        if queries.is_empty() {
            return None;
        }
        let key = queries.join("\n").to_lowercase();
        let now = Instant::now();
        let mut slots = self.slots.lock().unwrap();
        match slots.get_mut(&key) {
            Some(Slot::Done(tex, used)) => {
                *used = now;
                return tex.clone();
            }
            Some(Slot::Queued(at)) => {
                *at = now;
                return None;
            }
            None => {}
        }
        slots.insert(key, Slot::Queued(now));
        drop(slots);
        let _ = inner;
        self.queue.lock().unwrap().push(queries.to_vec());
        self.wake.notify_one();
        None
    }

    fn run(&self, lib: &Library, queries: Vec<String>) {
        let key = queries.join("\n").to_lowercase();
        // Плитку давно не показывали (перелистнули страницу): не грузим, при показе попросит снова.
        if let Some(Slot::Queued(at)) = self.slots.lock().unwrap().get(&key) {
            if at.elapsed() > Duration::from_secs(3) {
                self.slots.lock().unwrap().remove(&key);
                return;
            }
        }
        let mut answer: Result<Option<egui::ColorImage>, ()> = Ok(None);
        for q in &queries {
            let _g = self.gate();
            match lib.poster_for(q) {
                Ok(Some(img)) => {
                    answer = Ok(Some(img));
                    break;
                }
                Ok(None) => {}
                Err(()) => answer = Err(()),
            }
        }
        let mut slots = self.slots.lock().unwrap();
        match answer {
            // нет сети: спросим снова при следующем показе
            Err(()) => {
                slots.remove(&key);
            }
            Ok(img) => {
                let tex = img.map(|i| lib.0.ctx.load_texture(&key, i, egui::TextureOptions::LINEAR));
                slots.insert(key, Slot::Done(tex, Instant::now()));
                let with_pictures = slots.values().filter(|s| matches!(s, Slot::Done(Some(_), _))).count();
                if with_pictures > POSTERS_IN_MEMORY {
                    let mut by_age: Vec<(String, Instant)> = slots
                        .iter()
                        .filter_map(|(k, s)| match s {
                            Slot::Done(Some(_), used) => Some((k.clone(), *used)),
                            _ => None,
                        })
                        .collect();
                    by_age.sort_by_key(|x| x.1);
                    for (k, _) in by_age.into_iter().take(with_pictures - POSTERS_IN_MEMORY) {
                        slots.remove(&k);
                    }
                }
            }
        }
        drop(slots);
        lib.repaint();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn media_info_line() {
        let m =
            json!({"width": 1920, "height": 1080, "bitrateMbps": 12.04, "codec": "hevc", "frameRate": "24000/1001"});
        assert_eq!(format_media_info(&m), format!("1920×1080 · 12{} · HEVC · 23.98 fps", tr("library.mbit_s", &[])));
        assert_eq!(format_media_info(&json!({})), tr("library.bitreyt_n_d", &[]));
    }

    #[test]
    fn poster_file_name_is_sha1_of_path() {
        // MessageDigest SHA-1 «abc» — как у Kotlin-версии, картинки на диске общие
        assert_eq!(poster_file_name("abc"), "a9993e364706816aba3e25717850c26c9cd0d89d.img");
    }
}
