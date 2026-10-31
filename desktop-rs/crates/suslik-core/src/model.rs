//! Медиатека: торренты, их файлы и что из них играть (фильм, сериал, сборник), слияние версий.
//! Повторяет `shared/.../core/Model.kt` (те же правила, что на сайте: buildPlaylist / parseEpisode / escapeTitle).

use crate::jrx::{caps, find, find_all, group, has, replace};
use crate::json::{self, opt_bool, opt_i32, opt_i64, opt_str};
use crate::rx;
use serde_json::Value;
use std::cmp::Ordering;
use std::collections::HashMap;

/// Запись дерева файлов торрента, развёрнутого сервером (папки тоже, чтобы номера совпадали с сервером).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MFile {
    pub index: i32,
    pub name: String,
    pub path: String,
    pub is_folder: bool,
    pub size: i64,
    pub link: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Magnet {
    pub id: i64,
    pub name: String,
    pub status: String,
    pub status_code: i32,
    pub size: i64,
    pub downloaded: i64,
    pub files: Vec<MFile>,
    pub can_remove: bool,
    /// На этом устройстве, показывается первым в медиатеке.
    pub favorite: bool,
    /// В «Моих фильмах» владельца (сервер: services/shelf.js).
    pub mine: bool,
    /// Предложение ИИ сервера (services/aititle.js).
    pub ai_title: Option<AiTitle>,
    /// Этот пользователь добавил готовый торрент (или админ): может спросить ИИ и принять / отклонить.
    pub can_ai_title: bool,
}

impl Magnet {
    pub fn new(id: i64, name: &str, status_code: i32, files: Vec<MFile>) -> Self {
        Self {
            id,
            name: name.to_string(),
            status: String::new(),
            status_code,
            size: 0,
            downloaded: 0,
            files,
            can_remove: true,
            favorite: false,
            mine: false,
            ai_title: None,
            can_ai_title: false,
        }
    }
    pub fn ready(&self) -> bool {
        self.status_code == 4
    }
    pub fn downloading(&self) -> bool {
        self.status_code < 4
    }
    pub fn failed(&self) -> bool {
        self.status_code >= 5
    }
    pub fn progress(&self) -> f32 {
        if self.size > 0 {
            (self.downloaded as f32 / self.size as f32).clamp(0.0, 1.0)
        } else {
            0.0
        }
    }
}

/// Название и сезон, которые предложил ИИ сервера. `status`: "suggested", "accepted", "rejected" или "none".
/// Для слияния берётся только принятое; основной путь — по-прежнему регулярные выражения.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct AiTitle {
    pub base: String,
    pub season: Option<i32>,
    pub model: String,
    pub at: i64,
    pub status: String,
    /// "movie" | "series" | "collection".
    pub kind: Option<String>,
    /// Ответ старой версии сервера: можно спросить снова.
    pub stale: bool,
    /// Оригинальное название (английское, если не латиницей) — для постера TMDB.
    pub original: Option<String>,
    pub year: Option<i32>,
}

impl AiTitle {
    pub fn accepted(&self) -> bool {
        self.status == "accepted" && !self.base.trim().is_empty()
    }
    /// «Dune (2021)»: сервер берёт результат TMDB этого года. `None` без оригинального названия.
    pub fn poster_query(&self) -> Option<String> {
        let o = self.original.as_deref().filter(|o| !o.trim().is_empty())?;
        Some(match self.year {
            Some(y) => format!("{o} ({y})"),
            None => o.to_string(),
        })
    }
}

fn accepted_ai(m: &Magnet) -> Option<&AiTitle> {
    m.ai_title.as_ref().filter(|a| a.accepted())
}

const AI_STATUSES: [&str; 4] = ["suggested", "accepted", "rejected", "none"];
const AI_KINDS: [&str; 3] = ["movie", "series", "collection"];

pub fn parse_ai_title(a: Option<&Value>) -> Option<AiTitle> {
    let a = a?;
    let status = opt_str(a, "status");
    if !AI_STATUSES.contains(&status.as_str()) {
        return None;
    }
    let kind = opt_str(a, "kind");
    let original = opt_str(a, "original").trim().to_string();
    Some(AiTitle {
        base: opt_str(a, "base").trim().to_string(),
        season: json::has(a, "season").then(|| opt_i32(a, "season", 0)),
        model: opt_str(a, "model"),
        at: opt_i64(a, "at", 0),
        status,
        kind: AI_KINDS.contains(&kind.as_str()).then_some(kind),
        stale: opt_bool(a, "stale", false),
        original: (!original.is_empty() && json::has(a, "original")).then_some(original),
        year: if json::has(a, "year") {
            Some(opt_i32(a, "year", 0)).filter(|y| (1880..=2100).contains(y))
        } else {
            None
        },
    })
}

/// Когда этому пользователю снова можно спросить ИИ (мс сервера, 0 — сейчас): `aiTitleReadyAt` в /api/library.
pub fn ai_title_ready_at(v: &Value) -> i64 {
    opt_i64(v, "aiTitleReadyAt", 0)
}

pub fn parse_library(v: &Value) -> Vec<Magnet> {
    let Some(arr) = json::opt_array(v, "magnets") else { return Vec::new() };
    let mut out = Vec::with_capacity(arr.len());
    for m in arr.iter().filter(|m| m.is_object()) {
        let mut files = Vec::new();
        if let Some(fa) = json::opt_array(m, "files") {
            for (j, f) in fa.iter().enumerate() {
                if !f.is_object() {
                    continue;
                }
                let link = opt_str(f, "sourceLink");
                files.push(MFile {
                    index: j as i32,
                    name: opt_str(f, "name"),
                    path: opt_str(f, "path"),
                    is_folder: opt_str(f, "type") == "folder",
                    size: opt_i64(f, "size", 0),
                    link: link.starts_with("https://").then_some(link),
                });
            }
        }
        let id = opt_i64(m, "id", 0);
        let name = opt_str(m, "name");
        out.push(Magnet {
            id,
            name: if name.trim().is_empty() { format!("Magnet {id}") } else { name },
            status: opt_str(m, "status"),
            status_code: opt_i32(m, "statusCode", 0),
            size: opt_i64(m, "size", 0),
            downloaded: opt_i64(m, "downloaded", 0),
            files,
            can_remove: opt_bool(m, "canRemove", true),
            favorite: opt_bool(m, "favorite", false),
            mine: opt_bool(m, "mine", false),
            ai_title: parse_ai_title(json::opt_object(m, "aiTitle")),
            can_ai_title: opt_bool(m, "canAiTitle", false),
        });
    }
    out
}

/// Отрезок видео, `start_ms`..`end_ms`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TimeRange {
    pub start_ms: i64,
    pub end_ms: i64,
}

impl TimeRange {
    pub fn contains(&self, t: i64) -> bool {
        t >= self.start_ms && t < self.end_ms
    }
    pub fn length_ms(&self) -> i64 {
        self.end_ms - self.start_ms
    }
}

/// Где заставка и финальные титры видео.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct MediaMarkers {
    pub intro: Option<TimeRange>,
    /// До конца видео.
    pub credits: Option<TimeRange>,
}

impl MediaMarkers {
    pub fn is_empty(&self) -> bool {
        self.intro.is_none() && self.credits.is_none()
    }
}

// ---- те же правила, что у сайта -------------------------------------------------------------

const VIDEO_EXT: &str = r"\.(mkv|mp4|avi|mov|webm|m4v|m2ts|ts|iso)$";
const SAMPLE: &str = r"(^|[/._ -])sample([/._ -]|$)";
const DIR_SEASON: &str = r"(?:^|/)(?:season|сезон)[ ._-]?(\d{1,2})(?:/|$)";
const DIR_S: &str = r"(?:^|/)S(\d{1,2})(?:/|$)";
const ANY_SEASON: &str = r"(?:season|сезон)[ ._-]?(\d{1,2})";
const SXXEYY: &str = r"S(\d{1,2})[ ._-]?E(\d{1,3})";
const NXNN: &str = r"(?:^|[^\d])(\d{1,2})x(\d{2,3})(?!\d)";
const EP_WORD: &str = r"(?:^|[^a-z0-9а-я])(?:e|ep|episode|серия)[ ._-]?(\d{1,3})(?!\d)";
const LEAD_NUM: &str = r"^\s*(\d{1,3})(?=[ ._-])";
const NAME_SEASON_RANGE: &str = r"(?:season|сезон)[a-zа-яё]*[ ._:]*\d{1,2}[ ._]*[-–][ ._]*\d|\d[ ._]*[-–][ ._]*\d{1,2}[ ._]*(?:-?[a-zа-яё]{1,2}[ ._]*)?(?:season|сезон)|(?:^|[^a-zа-яё0-9])s\d{1,2}[ ._]*[-–][ ._]*s?\d";
const NAME_SEASON: &str = r"(?:^|[^a-zа-яё0-9])s(\d{1,2})(?=e\d|[^a-zа-яё0-9]|$)|(?:season|сезон)[ ._:]*(\d{1,2})(?!\d)|(?:^|\D)(\d{1,2})[ ._]*(?:-?(?:й|ый|ий|st|nd|rd|th))?[ ._]*(?:season|сезон)(?![a-zа-яё0-9])";
const DOTS: &str = r"[._]+";
const BRACKETS: &str = r"[\[\](){}]";
const SPACES: &str = r"\s+";

macro_rules! r {
    (VIDEO_EXT) => {
        rx!(VIDEO_EXT, ic)
    };
    (SAMPLE) => {
        rx!(SAMPLE, ic)
    };
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Episode {
    pub season: i32,
    pub episode: i32,
    pub file: MFile,
    /// Название фильма сборника.
    pub title: Option<String>,
}

impl Episode {
    fn new(season: i32, episode: i32, file: &MFile) -> Self {
        Self { season, episode, file: file.clone(), title: None }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Season {
    pub season: i32,
    pub items: Vec<Episode>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Playable {
    Movie(MFile),
    Series(Vec<Season>),
    None,
}

impl Playable {
    pub fn seasons(&self) -> Option<&Vec<Season>> {
        match self {
            Playable::Series(s) => Some(s),
            _ => None,
        }
    }
    fn first_title(&self) -> Option<&str> {
        self.seasons()?.first()?.items.first()?.title.as_deref()
    }
}

fn basename(path: &str) -> String {
    let p = path.replace('\\', "/");
    p.rsplit('/').next().unwrap_or("").to_string()
}

fn after_last(s: &str, c: char) -> &str {
    s.rsplit_once(c).map(|(_, b)| b).unwrap_or(s)
}

fn before_last<'a>(s: &'a str, c: char, missing: &'a str) -> &'a str {
    s.rsplit_once(c).map(|(a, _)| a).unwrap_or(missing)
}

fn blank(s: &str) -> bool {
    s.trim().is_empty()
}

fn if_blank(s: String, f: impl FnOnce() -> String) -> String {
    if blank(&s) {
        f()
    } else {
        s
    }
}

fn contains_ci(hay: &str, needle: &str) -> bool {
    hay.to_lowercase().contains(&needle.to_lowercase())
}

fn is_video(f: &MFile) -> bool {
    !f.is_folder && has(r!(VIDEO_EXT), &f.path)
}

fn is_playable_video(f: &MFile) -> bool {
    is_video(f) && !has(r!(SAMPLE), &f.path)
}

/// Естественный порядок: «Episode 2» раньше «Episode 10» (как localeCompare с numeric: true).
pub fn natural_cmp(a: &str, b: &str) -> Ordering {
    fn tokens(s: &str) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        let mut last_digit: Option<bool> = None;
        for c in s.to_lowercase().chars() {
            let d = c.is_ascii_digit();
            if last_digit == Some(d) {
                out.last_mut().unwrap().push(c);
            } else {
                out.push(c.to_string());
                last_digit = Some(d);
            }
        }
        out
    }
    let (ra, rb) = (tokens(a), tokens(b));
    for (x, y) in ra.iter().zip(rb.iter()) {
        let xd = x.starts_with(|c: char| c.is_ascii_digit());
        let yd = y.starts_with(|c: char| c.is_ascii_digit());
        let c = if xd && yd {
            let (nx, ny) = (x.trim_start_matches('0'), y.trim_start_matches('0'));
            nx.len().cmp(&ny.len()).then_with(|| nx.cmp(ny))
        } else {
            x.cmp(y)
        };
        if c != Ordering::Equal {
            return c;
        }
    }
    ra.len().cmp(&rb.len())
}

fn sort_natural(files: &mut [MFile]) {
    files.sort_by(|a, b| natural_cmp(&basename(&a.path), &basename(&b.path)));
}

fn num(s: &str) -> i32 {
    s.parse().unwrap_or(0)
}

fn season_of(path: &str) -> Option<i32> {
    let p = path.replace('\\', "/");
    let c = caps(rx!(DIR_SEASON, ic), &p).or_else(|| caps(rx!(DIR_S, ic), &p))?;
    Some(num(group(&c, 1)))
}

/// Сезон из названия раздачи, если оно называет ровно один (диапазоны «S01-S03», «Сезон: 1-3» не считаются).
pub fn name_season(name: &str) -> Option<i32> {
    if has(rx!(NAME_SEASON_RANGE, ic), name) {
        return None;
    }
    let mut nums: Vec<i32> = Vec::new();
    for c in crate::jrx::captures_all(rx!(NAME_SEASON, ic), name) {
        if let Some(n) = (1..c.len()).map(|i| group(&c, i)).find(|g| !g.is_empty()) {
            let n = num(n);
            if !nums.contains(&n) {
                nums.push(n);
            }
        }
    }
    match nums.as_slice() {
        [n] if *n > 0 => Some(*n),
        _ => None,
    }
}

fn parse_episode(path: &str, default_season: i32) -> Option<(i32, i32)> {
    let p = path.replace('\\', "/");
    let name = after_last(&p, '/');
    let folder_season = caps(rx!(ANY_SEASON, ic), &p).or_else(|| caps(rx!(DIR_S, ic), &p)).map(|c| num(group(&c, 1)));
    if let Some(c) = caps(rx!(SXXEYY, ic), name).or_else(|| caps(rx!(SXXEYY, ic), &p)) {
        return Some((num(group(&c, 1)), num(group(&c, 2))));
    }
    if let Some(c) = caps(rx!(NXNN, ic), name) {
        return Some((num(group(&c, 1)), num(group(&c, 2))));
    }
    if let Some(c) = caps(rx!(EP_WORD, ic), name) {
        return Some((folder_season.unwrap_or(default_season), num(group(&c, 1))));
    }
    if let Some(fs) = folder_season {
        if let Some(c) = caps(rx!(LEAD_NUM), name) {
            return Some((fs, num(group(&c, 1))));
        }
    }
    None
}

/// Первый из наибольших (как `maxByOrNull`).
fn first_max_by<T>(items: impl IntoIterator<Item = T>, key: impl Fn(&T) -> i64) -> Option<T> {
    let mut best: Option<(i64, T)> = None;
    for it in items {
        let k = key(&it);
        if best.as_ref().is_none_or(|(bk, _)| k > *bk) {
            best = Some((k, it));
        }
    }
    best.map(|(_, t)| t)
}

pub fn main_video(m: &Magnet) -> Option<MFile> {
    let vids: Vec<&MFile> = m.files.iter().filter(|f| is_video(f)).collect();
    let real: Vec<&MFile> = vids.iter().copied().filter(|f| !f.path.to_lowercase().ends_with(".iso")).collect();
    let pick = if real.is_empty() { vids } else { real };
    first_max_by(pick, |f| f.size).cloned()
}

/// Что играть: по файлам, затем вид и сезон, которые владелец принял от ИИ ([`apply_ai_title`]).
pub fn build_playable(m: &Magnet) -> Playable {
    apply_ai_title(playable_by_files(m), m)
}

// Границы слов по буквам и цифрам любого алфавита.
macro_rules! nb {
    ($body:literal) => {
        concat!(r"(?<![\p{L}\p{N}])", $body, r"(?![\p{L}\p{N}])")
    };
}

const COLLECTION_STRONG_BODY: &str = r"(?:трилоги[яи]|дилоги[яи]|тетралоги[яи]|квадр[ио]логи[яи]|пенталоги[яи]|гексалоги[яи]|франшиз[аеы]|киносаг[аеиы]|trilogy|duology|dilogy|tetralogy|quadrilogy|franchise|\d{1,2}\s*[-–—]?\s*(?:в|in)\s*1|все\s+части|все\s+фильмы|\d{1,2}\s+фильм(?:а|ов))";
const COLLECTION_WEAK_BODY: &str =
    r"(?:коллекци[яи]|сборник|антологи[яи]|саг[аеиы]|boxset|box[- ]set|collection|anthology|saga)";
const NB: &str = r"(?<![\p{L}\p{N}])";
const NA: &str = r"(?![\p{L}\p{N}])";

fn collection_strong() -> &'static fancy_regex::Regex {
    static R: std::sync::LazyLock<fancy_regex::Regex> =
        std::sync::LazyLock::new(|| crate::jrx::compile(&format!("{NB}{COLLECTION_STRONG_BODY}{NA}"), true));
    &R
}
fn collection_weak() -> &'static fancy_regex::Regex {
    static R: std::sync::LazyLock<fancy_regex::Regex> =
        std::sync::LazyLock::new(|| crate::jrx::compile(&format!("{NB}{COLLECTION_WEAK_BODY}{NA}"), true));
    &R
}
fn collection_words() -> &'static fancy_regex::Regex {
    static R: std::sync::LazyLock<fancy_regex::Regex> = std::sync::LazyLock::new(|| {
        crate::jrx::compile(&format!("{NB}{COLLECTION_STRONG_BODY}{NA}|{NB}{COLLECTION_WEAK_BODY}{NA}"), true)
    });
    &R
}

const SERIES_HINT: &str = r"(?<![\p{L}\p{N}])(?:сезон\p{L}*|season\p{L}*|серии|серия|series|episodes?|эпизод\p{L}*|s\d{1,2}|ova|ona)(?![\p{L}\p{N}])";
const YEAR_IN_NAME: &str = r"\b(19\d{2}|20\d{2})\b";

/// У каждого файла год в имени, и годы разные: фильмы франшизы, а не серии.
fn films_by_year(files: &[&MFile]) -> bool {
    let mut years: Vec<String> = Vec::new();
    for f in files {
        let Some(y) = find(rx!(YEAR_IN_NAME), &basename(&f.path)).map(String::from) else { return false };
        if !years.contains(&y) {
            years.push(y);
        }
    }
    years.len() >= 2
}

fn named_collection(m: &Magnet, files: &[&MFile]) -> bool {
    !has(rx!(SERIES_HINT, ic), &m.name)
        && (has(collection_strong(), &m.name) || (has(collection_weak(), &m.name) && films_by_year(files)))
}

/// Набор фильмов, который играется списком: владелец принял «сборник» от ИИ («сериал» / «фильм» исключают),
/// у фильмов есть названия ([`collection_of`]), или так говорит название раздачи.
pub fn is_collection(p: &Playable, m: Option<&Magnet>) -> bool {
    if let Some(kind) = m.and_then(accepted_ai).and_then(|a| a.kind.as_deref()) {
        return kind == "collection";
    }
    if !matches!(p, Playable::Series(_)) {
        return false;
    }
    if p.first_title().is_some() {
        return true;
    }
    m.is_some_and(|m| has(collection_strong(), &m.name) && !has(rx!(SERIES_HINT, ic), &m.name))
}

/// Какой набор фильмов; `tr_key` — строка в strings/ru.json приложений.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CollectionKind {
    Trilogy,
    Dilogy,
    Tetralogy,
    Compilation,
    Anthology,
    Collection,
    Franchise,
}

impl CollectionKind {
    pub fn label(self) -> &'static str {
        match self {
            Self::Trilogy => "Кинотрилогия",
            Self::Dilogy => "Дилогия",
            Self::Tetralogy => "Кинотетралогия",
            Self::Compilation => "Сборник",
            Self::Anthology => "Антология",
            Self::Collection => "Коллекция",
            Self::Franchise => "Кинофраншиза",
        }
    }
    pub fn tr_key(self) -> &'static str {
        match self {
            Self::Trilogy => "library.kinotrilogiya",
            Self::Dilogy => "library.dilogiya",
            Self::Tetralogy => "library.kinotetralogiya",
            Self::Compilation => "library.sbornik",
            Self::Anthology => "library.antologiya",
            Self::Collection => "library.kollektsiya",
            Self::Franchise => "library.kinofranshiza",
        }
    }
}

/// Сначала слова названия («Дилогия» с тремя файлами — всё равно дилогия), потом число фильмов.
pub fn collection_kind(film_count: usize, name: &str) -> CollectionKind {
    let is = |p: &'static fancy_regex::Regex| has(p, name);
    if is(rx!(nb!(r"(?:трилоги[яи]|trilogy)"), ic)) {
        CollectionKind::Trilogy
    } else if is(rx!(nb!(r"(?:дилоги[яи]|duology|dilogy)"), ic)) {
        CollectionKind::Dilogy
    } else if is(rx!(nb!(r"(?:квадр[ио]логи[яи]|тетралоги[яи]|quadrilogy|tetralogy)"), ic)) {
        CollectionKind::Tetralogy
    } else if film_count == 2 {
        CollectionKind::Dilogy
    } else if film_count == 3 {
        CollectionKind::Trilogy
    } else if film_count == 4 {
        CollectionKind::Tetralogy
    } else if is(rx!(nb!(r"сборник"), ic)) {
        CollectionKind::Compilation
    } else if is(rx!(nb!(r"(?:антологи[яи]|anthology)"), ic)) {
        CollectionKind::Anthology
    } else if is(rx!(nb!(r"(?:коллекци[яи]|collection|boxset|box[- ]set)"), ic)) {
        CollectionKind::Collection
    } else {
        CollectionKind::Franchise
    }
}

/// Русское множественное: `plural_ru(3, "фильм", "фильма", "фильмов")` = «фильма».
pub fn plural_ru<'a>(n: i64, one: &'a str, few: &'a str, many: &'a str) -> &'a str {
    if n % 10 == 1 && n % 100 != 11 {
        one
    } else if (2..=4).contains(&(n % 10)) && !(12..=14).contains(&(n % 100)) {
        few
    } else {
        many
    }
}

pub fn film_count_ru(count: usize) -> String {
    format!("{count} {}", plural_ru(count as i64, "фильм", "фильма", "фильмов"))
}

fn episodes_ru(n: usize) -> String {
    format!("{n} {}", plural_ru(n as i64, "серия", "серии", "серий"))
}

/// Фильмы сборника в порядке показа (для остального — пусто).
pub fn collection_films(item: &LibraryItem) -> Vec<Episode> {
    if !is_collection(&item.playable, Some(&item.m)) {
        return Vec::new();
    }
    item.playable.seasons().map(|s| s.iter().flat_map(|s| s.items.clone()).collect()).unwrap_or_default()
}

pub const TRACK_PREFIX: &str =
    r"^\s*(?:\[[^\]]*\]\s*|\([^\)]*\)\s*|(?:фильм|серия|часть|part|movie|vol|cd|диск)?\s*\d{1,3}[\s._-]*)+";
const EXT: &str = r"\.[a-z0-9]{2,5}$";

pub fn clean_collection_film_title(raw: &str) -> String {
    let without_ext = replace(rx!(EXT, ic), raw, "");
    let stripped = replace(rx!(TRACK_PREFIX, ic), &without_ext, "").trim().to_string();
    let cleaned = clean_title(if blank(&stripped) { &without_ext } else { &stripped });
    if_blank(cleaned, || clean_title(raw))
}

const FILM_COUNT_NOISE: &str = nb!(r"\d+\s*(?:фильм\p{L}*|сери\p{L}*|част\p{L}*)");

/// Собственные названия сборника («Гарри Поттер», «Harry Potter») без «Трилогия», «8 фильмов» и части после «:».
fn collection_bases(base_title: &str) -> Vec<String> {
    replace(collection_words(), base_title, " ")
        .split('/')
        .map(|it| {
            let t = clean_title(it);
            let t = t.split(':').next().unwrap_or("");
            let t = replace(rx!(FILM_COUNT_NOISE, ic), t, "");
            replace(rx!(SPACES), &t, " ").trim().to_string()
        })
        .filter(|s| !blank(s))
        .collect()
}

fn collection_base(base_title: &str) -> String {
    collection_bases(base_title).into_iter().next().unwrap_or_default()
}

fn file_raw_name(file: &MFile) -> String {
    if blank(&file.name) {
        after_last(&file.path, '/').to_string()
    } else {
        file.name.clone()
    }
}

pub fn collection_file_title(m: &Magnet, file: &MFile, base_title: Option<&str>) -> String {
    let own_base;
    let base_title = match base_title {
        Some(b) => b,
        None => {
            own_base = clean_title(&m.name);
            &own_base
        }
    };
    let raw = file_raw_name(file);
    let cleaned = clean_collection_film_title(&raw);
    let core = collection_base(base_title);
    if !blank(&cleaned) && !blank(&core) && !contains_ci(&cleaned, &core) {
        format!("{core}: {cleaned}")
    } else if !blank(&cleaned) {
        cleaned
    } else {
        clean_title(&raw)
    }
}

const MAX_FILM_QUERIES: usize = 6;

fn push_unique(out: &mut Vec<String>, s: String) {
    if !out.contains(&s) {
        out.push(s);
    }
}

/// Запросы постера для одного фильма сборника, лучшие сначала и не больше шести.
pub fn collection_file_poster_queries(m: &Magnet, file: &MFile, base_title: Option<&str>) -> Vec<String> {
    let own_base = clean_title(&m.name);
    let base_title = base_title.unwrap_or(&own_base);
    let raw = file_raw_name(file);
    let without_ext = replace(rx!(EXT, ic), &raw, "");
    let year = find(rx!(YEAR_IN_NAME), &without_ext).or_else(|| find(rx!(YEAR_IN_NAME), &file.path)).map(String::from);
    let stripped = replace(rx!(TRACK_PREFIX, ic), &without_ext, "").trim().to_string();
    let film = clean_title(if blank(&stripped) { &without_ext } else { &stripped })
        .split('/')
        .map(str::trim)
        .find(|s| !blank(s))
        .unwrap_or("")
        .to_string();
    let base = collection_base(base_title);
    let with_year = |q: &str| match &year {
        Some(y) => format!("{q} ({y})"),
        None => q.to_string(),
    };
    let mut out = Vec::new();
    if !blank(&film) {
        push_unique(&mut out, with_year(&film));
        push_unique(&mut out, film.clone());
    }
    if base.chars().count() >= 2 && !blank(&film) && !contains_ci(&film, &base) {
        push_unique(&mut out, with_year(&format!("{base}: {film}")));
        push_unique(&mut out, format!("{base}: {film}"));
    }
    for b in collection_bases(base_title).into_iter().filter(|b| b.chars().count() >= 2) {
        push_unique(&mut out, b);
    }
    push_unique(&mut out, clean_title(&m.name));
    out.truncate(MAX_FILM_QUERIES);
    out
}

pub fn continue_item_poster_queries(item: &LibraryItem, file: &MFile) -> Vec<String> {
    if is_collection(&item.playable, Some(&item.m)) {
        return collection_file_poster_queries(&item.m, file, Some(&item.base_title));
    }
    item.poster_queries.clone()
}

pub fn continue_item_title(item: &LibraryItem, file: &MFile) -> String {
    if is_collection(&item.playable, Some(&item.m)) {
        return collection_file_title(&item.m, file, Some(&item.base_title));
    }
    item.base_title.clone()
}

const EXTRAS: &str =
    r"(?:^|/)(?:extras?|bonus|featurettes?|behind[ ._-]?the[ ._-]?scenes|trailers?|доп[^/]*|бонус[^/]*|допы)(?:/|$)";
const BLURAY_DIR: &str = r"(?:^|/)(?:BDMV|STREAM|VIDEO_TS)(?:/|$)";

fn not_extras<'a>(vids: &[&'a MFile]) -> Vec<&'a MFile> {
    vids.iter().copied().filter(|f| !has(rx!(EXTRAS, ic), &f.path)).collect()
}

fn titled_films(files: Vec<MFile>) -> Vec<Season> {
    let items = files
        .iter()
        .enumerate()
        .map(|(i, f)| {
            let t = if_blank(clean_title(&basename(&f.path)), || format!("Фильм {}", i + 1));
            Episode { season: 1, episode: i as i32 + 1, file: f.clone(), title: Some(t) }
        })
        .collect();
    vec![Season { season: 1, items }]
}

/// Вид и сезон, принятые владельцем от ИИ, поверх того, что говорят файлы.
pub fn apply_ai_title(p: Playable, m: &Magnet) -> Playable {
    let Some(ai) = accepted_ai(m) else { return p };
    let vids: Vec<&MFile> = m.files.iter().filter(|f| is_playable_video(f)).collect();
    let main = {
        let n = not_extras(&vids);
        if n.is_empty() {
            vids.clone()
        } else {
            n
        }
    };
    let sorted = || {
        let mut v: Vec<MFile> = main.iter().map(|f| (*f).clone()).collect();
        sort_natural(&mut v);
        v
    };
    let mut out = p.clone();
    match ai.kind.as_deref() {
        Some("movie") => {
            if matches!(p, Playable::Series(_)) {
                if let Some(f) = main_video(m) {
                    out = Playable::Movie(f);
                }
            }
        }
        Some("series") => {
            if (matches!(p, Playable::Movie(_)) || is_collection(&p, None)) && main.len() >= 2 {
                let s = ai.season.filter(|s| *s > 0).unwrap_or(1);
                let items = sorted().iter().enumerate().map(|(i, f)| Episode::new(s, i as i32 + 1, f)).collect();
                out = Playable::Series(vec![Season { season: s, items }]);
            }
        }
        Some("collection") => {
            let plain_series = matches!(p, Playable::Series(_)) && !is_collection(&p, None);
            if (matches!(p, Playable::Movie(_)) || plain_series) && main.len() >= 2 {
                out = Playable::Series(titled_films(sorted()));
            }
        }
        _ => {}
    }
    if let (Some(s), Playable::Series(seasons)) = (ai.season, &out) {
        if s > 0 && seasons.len() == 1 && !is_collection(&out, None) && seasons[0].season != s {
            let items = seasons[0].items.iter().map(|e| Episode { season: s, ..e.clone() }).collect();
            out = Playable::Series(vec![Season { season: s, items }]);
        }
    }
    out
}

fn playable_by_files(m: &Magnet) -> Playable {
    let vids: Vec<&MFile> = m.files.iter().filter(|f| is_playable_video(f)).collect();
    // Сначала папки: многие раздачи называют серии без SxxEyy.
    let mut by_season: Vec<(i32, Vec<MFile>)> = Vec::new();
    for f in &vids {
        let Some(s) = season_of(&f.path) else { continue };
        match by_season.iter_mut().find(|(k, _)| *k == s) {
            Some((_, v)) => v.push((*f).clone()),
            None => by_season.push((s, vec![(*f).clone()])),
        }
    }
    if by_season.len() >= 2 || (by_season.len() == 1 && vids.len() > 1) {
        by_season.sort_by_key(|(k, _)| *k);
        let seasons = by_season
            .into_iter()
            .map(|(s, mut files)| {
                sort_natural(&mut files);
                Season {
                    season: s,
                    items: files.iter().enumerate().map(|(i, f)| Episode::new(s, i as i32 + 1, f)).collect(),
                }
            })
            .collect();
        return Playable::Series(seasons);
    }
    // Файлы без своей отметки сезона получают сезон из названия раздачи.
    let named = name_season(&m.name).unwrap_or(1);
    let eps: Vec<Episode> =
        vids.iter().filter_map(|f| parse_episode(&f.path, named).map(|(s, e)| Episode::new(s, e, f))).collect();
    if eps.len() >= 2 {
        let mut groups: Vec<(i32, Vec<Episode>)> = Vec::new();
        for e in eps {
            match groups.iter_mut().find(|(k, _)| *k == e.season) {
                Some((_, v)) => v.push(e),
                None => groups.push((e.season, vec![e])),
            }
        }
        groups.sort_by_key(|(k, _)| *k);
        let seasons = groups
            .into_iter()
            .map(|(s, mut items)| {
                items.sort_by(|a, b| a.episode.cmp(&b.episode).then_with(|| a.file.path.cmp(&b.file.path)));
                Season { season: s, items }
            })
            .collect();
        return Playable::Series(seasons);
    }
    if let Some(p) = numbered_episodes_of(m, &vids, named) {
        return p;
    }
    if let Some(p) = collection_of(&vids) {
        return p;
    }
    main_video(m).map(Playable::Movie).unwrap_or(Playable::None)
}

const NUM_NOISE: &str = r"(?:2160|1080|720|480|576)[pi]|[xh][ .]?26[45]|\d+(?:[.,]\d+)?\s?(?:fps|kbps|mbps|bit|gb|mb)|(?<!\d)(?:19|20)\d{2}(?!\d)|(?<!\d)[1-9]\.[01](?!\d)";
const NUM: &str = r"(?<!\d)\d{1,3}(?!\d)";

/// Файлы, которые различаются только одним числом («Show - 01», «Show - 02»…), — серии, если раздача так
/// называется (сезон / серии в названии) или похожа на сезон: пять и больше файлов похожего размера подряд.
fn numbered_episodes_of(m: &Magnet, vids: &[&MFile], season: i32) -> Option<Playable> {
    if vids.len() < 2 || vids.iter().any(|f| has(rx!(BLURAY_DIR, ic), &f.path)) {
        return None;
    }
    let main = not_extras(vids);
    if main.len() < 2 || named_collection(m, &main) {
        return None;
    }
    // Имя без шума и числа в нём: (начало, конец, значение).
    type Parsed = (String, Vec<(usize, usize, i32)>);
    let parsed: Vec<Parsed> = main
        .iter()
        .map(|f| {
            let b = replace(rx!(EXT, ic), &basename(&f.path), "");
            let b = replace(rx!(DOTS), &b, " ");
            let b = replace(rx!(NUM_NOISE, ic), &b, " ");
            let ms = find_all(rx!(NUM), &b).iter().map(|m| (m.start(), m.end(), num(m.as_str()))).collect();
            (b, ms)
        })
        .collect();
    let slots = parsed.iter().map(|(_, ms)| ms.len()).min().unwrap_or(0);
    for slot in 0..slots {
        let nums: Vec<i32> = parsed.iter().map(|(_, ms)| ms[slot].2).collect();
        let mut uniq = nums.clone();
        uniq.sort();
        uniq.dedup();
        if uniq.len() != nums.len() {
            continue;
        }
        let first_template = {
            let (b, ms) = &parsed[0];
            format!("{}#{}", &b[..ms[slot].0], &b[ms[slot].1..])
        };
        if parsed.iter().any(|(b, ms)| format!("{}#{}", &b[..ms[slot].0], &b[ms[slot].1..]) != first_template) {
            continue;
        }
        let consecutive = uniq.windows(2).all(|w| w[1] - w[0] == 1);
        let sizes: Vec<i64> = main.iter().map(|f| f.size).collect();
        let similar = sizes.iter().max() <= sizes.iter().min().map(|m| m * 2).as_ref();
        let looks_like_season = main.len() >= 5 && consecutive && similar;
        if !has(rx!(SERIES_HINT, ic), &m.name) && !looks_like_season {
            continue;
        }
        let mut eps: Vec<Episode> = main.iter().enumerate().map(|(i, f)| Episode::new(season, nums[i], f)).collect();
        eps.sort_by_key(|e| e.episode);
        return Some(Playable::Series(vec![Season { season, items: eps }]));
    }
    None
}

/// Несколько полнометражных файлов (каждый не меньше 1/4 самого большого и 300 МБ) вне папок Blu-ray/DVD.
fn collection_of(vids: &[&MFile]) -> Option<Playable> {
    if vids.len() < 2 || vids.iter().any(|f| has(rx!(BLURAY_DIR, ic), &f.path)) {
        return None;
    }
    let main = not_extras(vids);
    let largest = main.iter().map(|f| f.size).max()?;
    let mut films: Vec<MFile> =
        main.iter().filter(|f| f.size >= 300 * 1024 * 1024 && f.size >= largest / 4).map(|f| (*f).clone()).collect();
    if films.len() < 2 {
        return None;
    }
    sort_natural(&mut films);
    Some(Playable::Series(titled_films(films)))
}

const TAGS: &str = r"\b(2160p|1080p|720p|480p|4k|8k|web[- ]?dl|web[- ]?rip|bluray|blu[- ]?ray|bdrip|bdremux|remux|hdr10?\+?|dv|dovi|hevc|h\.?265|x265|h\.?264|x264|aac|dts|ddp?5?\.?1|proper|repack|extended|limited|internal|amzn|nf|dsnp|hmax|yts|rarbg)\b.*$";
const YEAR_TAIL: &str = r"\b(19|20)\d{2}\b.*$";

/// Название раздачи → простое название для поиска постера (как escapeTitle на сайте).
pub fn clean_title(value: &str) -> String {
    let v = if blank(value) { "Untitled" } else { value };
    let s = replace(rx!(EXT, ic), v, "");
    let s = replace(rx!(DOTS), &s, " ");
    let s = replace(rx!(TAGS, ic), &s, "");
    let s = replace(rx!(YEAR_TAIL), &s, " ");
    let s = replace(rx!(BRACKETS), &s, " ");
    let s = replace(rx!(SPACES), &s, " ").trim().to_string();
    if_blank(s, || replace(rx!(DOTS), value, " ").trim().to_string())
}

const SEASON_NOISE: &str = r"(?<![\p{L}\p{N}])(?:\d{1,2}(?:\s*[-–]\s*\d{1,2})?\s*(?:сезон\p{L}*|season\p{L}*)|(?:сезон\p{L}*|season\p{L}*|серии|серия|series|episodes?)\s*\d{1,3}(?:\s*[-–]\s*\d{1,3})?|s\d{1,2}(?:\s*[-–]?\s*[se]\d{1,3})?|complete|полный|полностью|(?:сезон\p{L}*|season\p{L}*|серии|episodes))(?![\p{L}\p{N}])";
const NON_WORD: &str = r"[^\p{L}\p{N}]+";

/// Ключ «того же названия»: простое название без отметок сезона и серий.
pub fn title_key(base_title: &str) -> String {
    let plain = base_title.to_lowercase().replace('ё', "е");
    let key = replace(rx!(NON_WORD), &replace(rx!(SEASON_NOISE, ic), &plain, " "), " ").trim().to_string();
    if_blank(key, || replace(rx!(NON_WORD), &plain, " ").trim().to_string())
}

pub fn format_bytes(value: i64) -> String {
    if value <= 0 {
        return "—".into();
    }
    const UNITS: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
    let (mut v, mut i) = (value as f64, 0);
    while v >= 1024.0 && i < UNITS.len() - 1 {
        v /= 1024.0;
        i += 1;
    }
    if i == 0 {
        format!("{value} B")
    } else {
        format!("{v:.1} {}", UNITS[i])
    }
}

pub fn status_ru(m: &Magnet) -> String {
    if m.ready() {
        "Готово".into()
    } else if m.failed() {
        if blank(&m.status) {
            "Ошибка".into()
        } else {
            m.status.clone()
        }
    } else if m.status.eq_ignore_ascii_case("Downloading") {
        "Загрузка".into()
    } else if blank(&m.status) {
        "Обработка".into()
    } else {
        m.status.clone()
    }
}

/// Подпись под плиткой: принятое название ИИ, иначе русское название TMDB по запросу постера, иначе название раздачи.
pub fn tile_title(item: &LibraryItem, ru_title_of: impl Fn(&str) -> Option<String>) -> String {
    if item.m.ai_title.as_ref().is_some_and(|a| a.accepted()) || is_collection(&item.playable, Some(&item.m)) {
        return item.base_title.clone();
    }
    item.poster_queries
        .iter()
        .find_map(|q| ru_title_of(&q.to_lowercase()).filter(|t| !blank(t)))
        .unwrap_or_else(|| item.base_title.clone())
}

pub fn episode_label(base: &str, s: &Season, e: &Episode) -> String {
    if let Some(t) = &e.title {
        return t.clone();
    }
    let season = if s.season == 0 { "Спец.".to_string() } else { format!("S{:02}", s.season) };
    format!("{base} · {season}E{:02}", e.episode)
}

/// Сезон сериала, в котором лежит `path` (баннер «Продолжить просмотр»). `None` для фильма и сборника.
pub fn watched_season<'a>(item: &'a LibraryItem, path: &str) -> Option<&'a Season> {
    let seasons = item.playable.seasons()?;
    if is_collection(&item.playable, Some(&item.m)) {
        return None;
    }
    seasons.iter().find(|s| s.items.iter().any(|e| e.file.path == path))
}

/// Всё, что нужно плитке: считается один раз на загрузку медиатеки.
#[derive(Debug, Clone, PartialEq)]
pub struct LibraryItem {
    pub m: Magnet,
    pub playable: Playable,
    pub base_title: String,
    pub poster_queries: Vec<String>,
    pub search_text: String,
}

impl LibraryItem {
    pub fn of(m: Magnet) -> Self {
        let base = title_of(&m);
        Self {
            playable: build_playable(&m),
            poster_queries: poster_queries_of(&m, &base),
            search_text: search_text_of(&m),
            base_title: base,
            m,
        }
    }

    /// То же с изменённым ответом ИИ: название и запросы постера следуют за ним.
    pub fn with_ai_title(&self, ai: Option<AiTitle>) -> Self {
        let mut m = self.m.clone();
        m.ai_title = ai;
        Self::of(m)
    }
}

fn search_text_of(m: &Magnet) -> String {
    let mut s = m.name.clone();
    for f in &m.files {
        s.push('\n');
        s.push_str(&f.path);
    }
    if let Some(ai) = accepted_ai(m) {
        s.push('\n');
        s.push_str(&ai.base);
        s.push('\n');
        s.push_str(ai.original.as_deref().unwrap_or(""));
    }
    s.to_lowercase()
}

fn title_of(m: &Magnet) -> String {
    accepted_ai(m).map(|a| a.base.clone()).unwrap_or_else(|| clean_title(&m.name))
}

fn poster_queries_of(m: &Magnet, base: &str) -> Vec<String> {
    let mut out = Vec::new();
    if let Some(q) = accepted_ai(m).and_then(|a| a.poster_query()) {
        push_unique(&mut out, q);
    }
    push_unique(&mut out, base.to_string());
    if let Some(f) = main_video(m) {
        push_unique(&mut out, clean_title(&file_raw_name(&f)));
    }
    out
}

pub fn prepare_items(list: Vec<Magnet>) -> Vec<LibraryItem> {
    list.into_iter().map(LibraryItem::of).collect()
}

/// «Сезоны 1–3 · 24 серии» для строки сериала («3 фильма» для сборника).
pub fn series_line(seasons: &[Season]) -> String {
    let nums: Vec<i32> = seasons.iter().map(|s| s.season).filter(|s| *s > 0).collect();
    let eps: usize = seasons.iter().map(|s| s.items.len()).sum();
    if seasons.first().and_then(|s| s.items.first()).is_some_and(|e| e.title.is_some()) {
        return film_count_ru(eps);
    }
    let range = match nums.as_slice() {
        [] => String::new(),
        [n] => format!("Сезон {n} · "),
        _ => format!("Сезоны {}–{} · ", nums.iter().min().unwrap(), nums.iter().max().unwrap()),
    };
    format!("{range}{}", episodes_ru(eps))
}

const YEAR_BRACKETED: &str = r"[(\[{](19\d{2}|20\d{2})[)\]}]";
const SEASON_RANGE_REGEX: &str = r"(?:season|сезон)[s\p{L}]*[\s._-]*(\d{1,2})[\s._-]*[-–][\s._-]*(\d{1,2})|(\d{1,2})[\s._-]*[-–][\s._-]*(\d{1,2})[\s._-]*(?:season|сезон)[s\p{L}]*";
const PART_EXPLICIT: &str = nb!(r"(?:часть|ч\.|фильм|film|part|pt\.?|глава|chapter)\s*(\d{1,2}|[ivx]{1,4})");
const PART_ORDINAL: &str = nb!(r"(\d{1,2})\s*[-–]?\s*(?:я|ая|ья|ей|й|ый|ой)?\s*(?:часть|фильм)");

/// Текущий год + 2 (как `LATEST_YEAR` в Kotlin).
fn latest_year() -> i32 {
    static Y: std::sync::LazyLock<i32> = std::sync::LazyLock::new(|| current_year() + 2);
    *Y
}

/// Год по UTC из системных часов (алгоритм civil_from_days).
pub fn current_year() -> i32 {
    let days = crate::mirror_link::now_sec().div_euclid(86_400);
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    (yoe + era * 400 + i64::from(month <= 2)) as i32
}

#[derive(Debug, Clone, PartialEq)]
pub struct VersionSection {
    pub title: Option<String>,
    pub items: Vec<LibraryItem>,
}

/// Год выпуска (1900..следующий год + 1) из текста или имени файла: сначала «(2001)», потом отдельный год.
pub fn extract_year(name: &str) -> Option<i32> {
    if let Some(c) = caps(rx!(YEAR_BRACKETED), name) {
        let y = num(group(&c, 1));
        if y <= latest_year() {
            return Some(y);
        }
    }
    crate::jrx::captures_all(rx!(YEAR_IN_NAME), name).iter().map(|c| num(group(c, 1))).find(|y| *y <= latest_year())
}

pub fn release_year(item: &LibraryItem) -> Option<i32> {
    if let Some(y) = accepted_ai(&item.m).and_then(|a| a.year) {
        return Some(y);
    }
    extract_year(&item.m.name)
        .or_else(|| extract_year(&item.base_title))
        .or_else(|| item.m.files.iter().find_map(|f| extract_year(if blank(&f.name) { &f.path } else { &f.name })))
}

fn parse_roman(s: &str) -> Option<i32> {
    let n = match s.to_uppercase().as_str() {
        "I" => 1,
        "II" => 2,
        "III" => 3,
        "IV" => 4,
        "V" => 5,
        "VI" => 6,
        "VII" => 7,
        "VIII" => 8,
        "IX" => 9,
        "X" => 10,
        _ => return None,
    };
    Some(n)
}

fn part_num(raw: &str) -> Option<i32> {
    raw.parse().ok().or_else(|| parse_roman(raw))
}

/// Явный номер части или фильма: «Part 2», «Часть 3», «2-я часть» или число / римская цифра сразу после названия.
pub fn item_part(item: &LibraryItem) -> Option<i32> {
    let text = &item.m.name;
    if let Some(n) = caps(rx!(PART_EXPLICIT, ic), text).and_then(|c| part_num(group(&c, 1))) {
        return Some(n);
    }
    if let Some(n) = caps(rx!(PART_ORDINAL, ic), text).and_then(|c| group(&c, 1).parse().ok()) {
        return Some(n);
    }
    if !blank(&item.base_title) {
        let p = format!(r"{NB}{}\s+(\d{{1,2}}|[IVX]{{1,4}}){NA}", fancy_regex::escape(&item.base_title));
        let r = crate::jrx::compile(&p, true);
        if let Some(n) = caps(&r, text).and_then(|c| part_num(group(&c, 1))) {
            return Some(n);
        }
    }
    None
}

/// Качество: 4K (4), 1080p (3), 720p (2), SD (1), прочее (0).
pub fn quality_score(name: &str) -> i32 {
    let l = name.to_lowercase();
    let any = |xs: &[&str]| xs.iter().any(|x| l.contains(x));
    if any(&["2160p", "4k", "3840"]) {
        4
    } else if any(&["1080p", "1080i", "1920"]) {
        3
    } else if any(&["720p", "1280"]) {
        2
    } else if any(&["480p", "576p", "dvd"]) {
        1
    } else {
        0
    }
}

/// Сезоны версии: (первый, последний) или `None`, если это не сериал.
pub fn item_seasons(item: &LibraryItem) -> Option<(i32, i32)> {
    if let Some(s) = accepted_ai(&item.m).and_then(|a| a.season) {
        return Some((s, s));
    }
    if let Some(seasons) = item.playable.seasons() {
        if item.playable.first_title().is_some() {
            return None; // сборник фильмов, а не сериал
        }
        let nums: Vec<i32> = seasons.iter().map(|s| s.season).collect();
        if let (Some(a), Some(b)) = (nums.iter().min(), nums.iter().max()) {
            return Some((*a, *b));
        }
    }
    if let Some(c) = caps(rx!(SEASON_RANGE_REGEX, ic), &item.m.name) {
        let pick = |a: usize, b: usize| {
            let g = group(&c, a);
            (if g.is_empty() { group(&c, b) } else { g }).parse::<i32>().ok()
        };
        if let (Some(min), Some(max)) = (pick(1, 3), pick(2, 4)) {
            return Some((min, max));
        }
    }
    if let Some(c) = caps(rx!(ANY_SEASON, ic), &item.m.name).or_else(|| caps(rx!(SXXEYY, ic), &item.m.name)) {
        if let Ok(s) = group(&c, 1).parse() {
            return Some((s, s));
        }
    }
    if let Playable::Movie(file) = &item.playable {
        if let Some(s) = season_of(&file.path) {
            return Some((s, s));
        }
        if let Some((s, _)) = parse_episode(&file.path, 1) {
            return Some((s, s));
        }
    }
    None
}

/// По чему версия сортируется и делится на разделы: считается один раз на версию.
struct VersionKey<'a> {
    item: &'a LibraryItem,
    seasons: Option<(i32, i32)>,
    part: Option<i32>,
    year: Option<i32>,
    quality: i32,
}

impl<'a> VersionKey<'a> {
    fn new(item: &'a LibraryItem) -> Self {
        Self {
            item,
            seasons: item_seasons(item),
            part: item_part(item),
            year: release_year(item),
            quality: quality_score(&item.m.name),
        }
    }

    /// Один ключ, сравниваемый поле за полем, — порядок полный.
    fn order(&self) -> (i64, i64, i64, i64, i64, i64, &str, i64) {
        const MAX: i64 = i32::MAX as i64;
        (
            self.seasons.map(|(a, _)| if a == 0 { MAX - 1 } else { a as i64 }).unwrap_or(MAX),
            self.seasons.map(|(a, b)| (b - a) as i64).unwrap_or(0),
            self.part.map(i64::from).unwrap_or(MAX),
            self.year.map(i64::from).unwrap_or(MAX),
            -(self.quality as i64),
            -self.item.m.size,
            &self.item.m.name,
            self.item.m.id,
        )
    }
}

fn sorted_keys(versions: &[LibraryItem]) -> Vec<VersionKey<'_>> {
    let mut keys: Vec<VersionKey> = versions.iter().map(VersionKey::new).collect();
    keys.sort_by(|a, b| a.order().cmp(&b.order()));
    keys
}

/// Сериалы по сезону (спецвыпуски в конце), фильмы и трилогии по части и году, затем лучшее качество и больший размер.
pub fn sort_versions(versions: &[LibraryItem]) -> Vec<LibraryItem> {
    if versions.len() <= 1 {
        return versions.to_vec();
    }
    sorted_keys(versions).into_iter().map(|k| k.item.clone()).collect()
}

/// Отсортированные версии по разделам («Сезон 1» или «2001 год»). Всё одно — один раздел без заголовка.
pub fn version_sections(versions: &[LibraryItem]) -> Vec<VersionSection> {
    if versions.is_empty() {
        return Vec::new();
    }
    let keys = sorted_keys(versions);
    let sorted: Vec<LibraryItem> = keys.iter().map(|k| k.item.clone()).collect();
    let one = |sorted: Vec<LibraryItem>| vec![VersionSection { title: None, items: sorted }];
    let titles: Vec<String> = if keys.iter().any(|k| k.seasons.is_some()) {
        keys.iter()
            .map(|k| match k.seasons {
                None => "Дополнительно".to_string(),
                Some((0, _)) => "Спецвыпуски".to_string(),
                Some((a, b)) if a == b => format!("Сезон {a}"),
                Some((a, b)) => format!("Сезоны {a}–{b}"),
            })
            .collect()
    } else {
        let distinct = |v: Vec<i32>| {
            let mut v = v;
            v.sort();
            v.dedup();
            v.len()
        };
        if distinct(keys.iter().filter_map(|k| k.year).collect()) <= 1
            && distinct(keys.iter().filter_map(|k| k.part).collect()) <= 1
        {
            return one(sorted);
        }
        keys.iter()
            .map(|k| match (k.year, k.part) {
                (Some(y), Some(p)) => format!("{y} год · Часть {p}"),
                (Some(y), None) => format!("{y} год"),
                (None, Some(p)) => format!("Часть {p}"),
                (None, None) => "Другая версия".to_string(),
            })
            .collect()
    };
    let mut groups: Vec<(String, Vec<LibraryItem>)> = Vec::new();
    for (t, it) in titles.into_iter().zip(sorted.iter()) {
        match groups.iter_mut().find(|(k, _)| *k == t) {
            Some((_, v)) => v.push(it.clone()),
            None => groups.push((t, vec![it.clone()])),
        }
    }
    if groups.len() <= 1 {
        return one(sorted);
    }
    groups.into_iter().map(|(t, items)| VersionSection { title: Some(t), items }).collect()
}

/// Первая строка в выборе версии, без качества: «Кинотрилогия • 3 фильма», «Сезоны 1–3 · 24 серии», «2021 год».
pub fn version_line(v: &LibraryItem, in_section: bool) -> String {
    let films = collection_films(v);
    if !films.is_empty() {
        return format!("{} • {}", collection_kind(films.len(), &v.m.name).label(), film_count_ru(films.len()));
    }
    if let Some(seasons) = v.playable.seasons() {
        if !in_section {
            return series_line(seasons);
        }
        let eps = episodes_ru(seasons.iter().map(|s| s.items.len()).sum());
        return match item_seasons(v) {
            Some((a, b)) if a != b => format!("Сезоны {a}–{b} · {eps}"),
            _ => eps,
        };
    }
    if in_section {
        return String::new();
    }
    release_year(v).map(|y| format!("{y} год")).unwrap_or_default()
}

/// Один фильм или сериал, добавленный не раз: одна плитка на все версии. Сливаются только готовые; фильмы с
/// фильмами, сериалы (и сборники) с сериалами: по одному постеру (если `poster_of` его знает), иначе по названию
/// без отметок сезона (фильм — ещё и по году). Возвращает показанные плитки и id первой → все версии (по порядку).
pub fn group_versions(
    list: &[LibraryItem],
    poster_of: impl Fn(&LibraryItem) -> Option<String>,
) -> (Vec<LibraryItem>, HashMap<i64, Vec<LibraryItem>>) {
    let mut by_key: HashMap<String, usize> = HashMap::new();
    let mut groups: Vec<Vec<LibraryItem>> = Vec::new();
    let mut shown = Vec::with_capacity(list.len());
    for it in list {
        let base = &it.base_title;
        if matches!(it.playable, Playable::None) || !it.m.ready() || blank(base) {
            shown.push(it.clone());
            continue;
        }
        let kind = if matches!(it.playable, Playable::Movie(_)) { "m" } else { "s" };
        let mut keys = Vec::new();
        if let Some(p) = poster_of(it) {
            keys.push(format!("{kind}|poster|{p}"));
        }
        keys.push(if kind == "m" {
            let year = accepted_ai(&it.m)
                .and_then(|a| a.year)
                .map(|y| y.to_string())
                .or_else(|| find(rx!(YEAR_IN_NAME), &it.m.name).map(String::from))
                .unwrap_or_default();
            format!("m|{}|{year}", title_key(base))
        } else {
            format!("s|{}", title_key(base))
        });
        match keys.iter().find_map(|k| by_key.get(k).copied()) {
            None => {
                groups.push(vec![it.clone()]);
                shown.push(it.clone());
                for k in keys {
                    by_key.insert(k, groups.len() - 1);
                }
            }
            Some(g) => {
                groups[g].push(it.clone());
                for k in keys {
                    by_key.entry(k).or_insert(g);
                }
            }
        }
    }
    let versions = groups.iter().filter(|g| g.len() > 1).map(|g| (g[0].m.id, sort_versions(g))).collect();
    (shown, versions)
}

/// `name` (в нижнем регистре, без папки) — это `base` плюс хвост: «show.s01e01.rus.srt» для «show.s01e01»,
/// но не «show.s01e10.srt».
fn same_base(name: &str, base: &str) -> bool {
    name.starts_with(base) && name[base.len()..].chars().next().is_none_or(|c| !c.is_alphanumeric())
}

const SUB_EXT: &str = r"\.(srt|ass|ssa|vtt|sub)$";
const AUDIO_EXT: &str = r"\.(mka|ac3|eac3|dts|aac|flac|mp3|m4a|opus|thd|wav)$";

fn video_base(video: &MFile) -> String {
    let name = basename(&video.path);
    before_last(&name, '.', &name).to_lowercase()
}

fn playable_video_count(m: &Magnet) -> usize {
    m.files.iter().filter(|f| is_playable_video(f)).count()
}

/// Субтитры видео: сначала с тем же именем, иначе из той же папки или её «Subs»; у раздачи с одним видео — любые.
pub fn subtitles_for(m: &Magnet, video: &MFile) -> Vec<MFile> {
    let subs: Vec<&MFile> =
        m.files.iter().filter(|f| !f.is_folder && f.link.is_some() && has(rx!(SUB_EXT, ic), &f.path)).collect();
    if subs.is_empty() {
        return Vec::new();
    }
    let v_path = video.path.replace('\\', "/");
    let v_dir = before_last(&v_path, '/', "");
    let v_base = video_base(video);
    let take = |v: Vec<&MFile>| v.into_iter().take(8).cloned().collect::<Vec<_>>();
    let by_name: Vec<&MFile> =
        subs.iter().copied().filter(|f| same_base(&basename(&f.path).to_lowercase(), &v_base)).collect();
    if !by_name.is_empty() {
        return take(by_name);
    }
    if playable_video_count(m) <= 1 {
        return take(subs);
    }
    let near = subs
        .into_iter()
        .filter(|f| {
            let p = f.path.replace('\\', "/");
            let d = before_last(&p, '/', "");
            d == v_dir || (d.starts_with(&format!("{v_dir}/")) && after_last(d, '/').to_lowercase().starts_with("sub"))
        })
        .collect();
    take(near)
}

pub fn watch_key(magnet_id: i64, file: &MFile) -> String {
    format!("{magnet_id}|{}", file.path)
}

/// Сохраняет плеер: место, длина, когда.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WatchInfo {
    pub pos: i64,
    pub len: i64,
    pub at: i64,
}

impl WatchInfo {
    pub fn fraction(&self) -> f32 {
        if self.len > 0 {
            (self.pos as f32 / self.len as f32).clamp(0.0, 1.0)
        } else {
            0.0
        }
    }
    pub fn watched(&self) -> bool {
        self.len > 0 && self.pos >= self.len - 15_000
    }
}

/// Внешние озвучки («RUS Sound/Show.S01E02.mka»): сначала с тем же именем; у раздачи с одним видео — любые.
pub fn external_audio_for(m: &Magnet, video: &MFile) -> Vec<MFile> {
    let audio: Vec<&MFile> =
        m.files.iter().filter(|f| !f.is_folder && f.link.is_some() && has(rx!(AUDIO_EXT, ic), &f.path)).collect();
    if audio.is_empty() {
        return Vec::new();
    }
    let v_base = video_base(video);
    let by_name: Vec<MFile> = audio
        .iter()
        .filter(|f| same_base(&basename(&f.path).to_lowercase(), &v_base))
        .take(8)
        .map(|f| (*f).clone())
        .collect();
    if !by_name.is_empty() {
        return by_name;
    }
    if playable_video_count(m) <= 1 {
        audio.into_iter().take(8).cloned().collect()
    } else {
        Vec::new()
    }
}

#[cfg(test)]
#[path = "model_tests.rs"]
mod tests;
