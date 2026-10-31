//! Страница медиатеки (`LibraryView.kt`): поиск по названию, сортировка, «Только готовые», сетка постеров по страницам
//! (три ряда), плитка с избранным и «Моими фильмами», версии одного фильма в одной плитке.
//!
//! Плеер переезжает на этапе 6: пока по нажатию на плитку открывается карточка с тем, что будет играть.

use crate::catalog::{self, item_category, item_specs, item_type_label, item_year, versions_word, Category};
use crate::library::{LibState, Library, StatusKind};
use crate::strings::tr;
use crate::theme;
use eframe::egui::{self, Color32, Pos2, Rect, RichText, Sense, Vec2};
use std::collections::HashMap;
use std::sync::Arc;
use suslik_core::model::{
    episode_label, format_bytes, group_versions, sort_versions, status_ru, version_line, watch_key, LibraryItem, MFile,
    Playable,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SortBy {
    Name,
    Newest,
    Size,
    Favorite,
}

impl SortBy {
    const ALL: [SortBy; 4] = [SortBy::Name, SortBy::Newest, SortBy::Size, SortBy::Favorite];
    fn label(self) -> String {
        tr(
            match self {
                SortBy::Name => "library.nazvanie",
                SortBy::Newest => "library.snachala_novye",
                SortBy::Size => "library.razmer",
                SortBy::Favorite => "library.izbrannoe",
            },
            &[],
        )
    }
}

/// Плитки страницы после поиска, фильтров, сортировки и слияния версий; пересчитываются, только когда что-то из этого
/// изменилось.
#[derive(PartialEq, Clone)]
struct ShownKey {
    items: usize,
    query: String,
    category: Category,
    sort: SortBy,
    only_ready: bool,
    titles: u64,
    posters: u64,
    anim: u64,
}

struct Shown {
    key: ShownKey,
    tiles: Vec<LibraryItem>,
    versions: HashMap<i64, Vec<LibraryItem>>,
}

pub struct LibraryView {
    mine: bool,
    query: String,
    sort: SortBy,
    only_ready: bool,
    page: usize,
    shown: Option<Shown>,
    /// Карточка «что будет играть» и выбор версии.
    open: Option<LibraryItem>,
    pick: Option<Vec<LibraryItem>>,
}

/// Ряд плиток: постеры 180 px, 14 px между ними, по 36 px полей; страница — три ряда.
const TILE_W: f32 = 180.0;
const GAP: f32 = 14.0;
const PAGE_ROWS: usize = 3;

impl LibraryView {
    pub fn new(mine: bool) -> Self {
        Self {
            mine,
            query: String::new(),
            sort: SortBy::Name,
            only_ready: false,
            page: 0,
            shown: None,
            open: None,
            pick: None,
        }
    }

    fn update_shown(&mut self, st: &LibState, category: Category) {
        let key = ShownKey {
            items: Arc::as_ptr(&st.items) as usize,
            query: self.query.trim().to_lowercase(),
            category,
            sort: self.sort,
            only_ready: self.only_ready,
            titles: st.title_version,
            posters: st.poster_version,
            anim: st.anim_version,
        };
        if self.shown.as_ref().is_some_and(|s| s.key == key) {
            return;
        }
        if self.shown.as_ref().is_some_and(|s| {
            (&s.key.query, s.key.category, s.key.sort, s.key.only_ready)
                != (&key.query, key.category, key.sort, key.only_ready)
        }) {
            self.page = 0; // новый поиск, категория или сортировка — снова с первой страницы
        }
        let anim = |k: &str| st.is_animation(k);
        let q = &key.query;
        let mut list: Vec<(String, &LibraryItem)> = st
            .items
            .iter()
            .filter(|i| !self.mine || i.m.mine)
            // русское название TMDB тоже ищется: «дюна» находит «Dune.2021.2160p»
            .map(|i| (st.title_of(i), i))
            .filter(|(t, i)| q.is_empty() || i.search_text.to_lowercase().contains(q) || t.to_lowercase().contains(q))
            .filter(|(_, i)| !self.only_ready || i.m.ready())
            .filter(|(_, i)| category == Category::All || item_category(i, &anim) == category)
            .collect();
        match self.sort {
            SortBy::Name => list.sort_by(|a, b| {
                b.1.m.favorite.cmp(&a.1.m.favorite).then_with(|| a.0.to_lowercase().cmp(&b.0.to_lowercase()))
            }),
            SortBy::Newest => list.sort_by(|a, b| b.1.m.favorite.cmp(&a.1.m.favorite).then(b.1.m.id.cmp(&a.1.m.id))),
            SortBy::Size => list.sort_by(|a, b| b.1.m.favorite.cmp(&a.1.m.favorite).then(b.1.m.size.cmp(&a.1.m.size))),
            SortBy::Favorite => list.sort_by_key(|i| std::cmp::Reverse(i.1.m.favorite)),
        }
        let filtered: Vec<LibraryItem> = list.into_iter().map(|(_, i)| i.clone()).collect();
        // Одна плитка на фильм, добавленный несколько раз (тот же постер): плитка с версиями спросит, какую включить.
        let (tiles, versions) = group_versions(&filtered, |i| st.poster_path_of(i));
        self.shown = Some(Shown { key, tiles, versions });
    }

    pub fn show(&mut self, ui: &mut egui::Ui, lib: &Library, category: Category) {
        {
            let st = lib.state();
            self.update_shown(&st, category);
        }
        let shown = self.shown.as_ref().unwrap();
        let width = ui.available_width() - 72.0;
        let columns = (((width + GAP) / (TILE_W + GAP)).floor() as usize).max(1);
        let page_size = columns * PAGE_ROWS;
        let pages = shown.tiles.len().div_ceil(page_size).max(1);
        let page = self.page.min(pages - 1);
        let tiles: Vec<LibraryItem> = shown.tiles.iter().skip(page * page_size).take(page_size).cloned().collect();
        let versions = shown.versions.clone();
        let total = shown.tiles.len();
        let mut new_page = None;
        let mut open: Option<LibraryItem> = None;

        egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
            egui::Frame::none().inner_margin(egui::Margin { left: 36.0, right: 36.0, top: 28.0, bottom: 40.0 }).show(
                ui,
                |ui| {
                    self.header(ui, lib, total);
                    ui.add_space(16.0);
                    self.filters(ui);
                    ui.add_space(14.0);
                    if self.mine && total == 0 && self.query.trim().is_empty() {
                        ui.add_space(30.0);
                        ui.label(RichText::new(tr("library.izbrannoe_pusto", &[])).size(18.0));
                        ui.label(RichText::new(tr("library.izbrannoe_pusto_podskazka", &[])).color(theme::MUTED));
                    }
                    let tile_w = ((width - GAP * (columns as f32 - 1.0)) / columns as f32).max(120.0);
                    for row in tiles.chunks(columns) {
                        ui.horizontal(|ui| {
                            ui.spacing_mut().item_spacing.x = GAP;
                            for item in row {
                                let vs = versions.get(&item.m.id).map(Vec::as_slice).unwrap_or(&[]);
                                if let Some(clicked) = tile(ui, lib, item, vs, self.mine, tile_w) {
                                    open = Some(clicked);
                                }
                            }
                        });
                        ui.add_space(20.0);
                    }
                    if pages > 1 {
                        new_page = page_bar(ui, page, pages);
                    }
                },
            );
        });
        if let Some(p) = new_page {
            self.page = p;
        }
        if let Some(item) = open {
            match versions.get(&item.m.id) {
                Some(vs) if vs.len() > 1 => self.pick = Some(sort_versions(vs)),
                _ => self.open = Some(item),
            }
        }
        self.version_picker(ui.ctx(), lib);
        self.play_card(ui.ctx(), lib);
    }

    fn header(&mut self, ui: &mut egui::Ui, lib: &Library, total: usize) {
        let (loading, status, kind, notice) = {
            let mut st = lib.state();
            (st.loading, st.status.clone(), st.status_kind, st.notice.take())
        };
        ui.horizontal(|ui| {
            let title = tr(if self.mine { "library.moi_filmy" } else { "library.moya_biblioteka_alldebrid" }, &[]);
            ui.label(RichText::new(title).font(theme::heading(24.0)));
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let label = if loading { "…".to_string() } else { tr("library.obnovit", &[]) };
                if ui.add_enabled(!loading, egui::Button::new(label).min_size(egui::vec2(96.0, 32.0))).clicked() {
                    lib.refresh();
                }
            });
        });
        if self.mine {
            ui.label(RichText::new(tr("library.moi_filmy_podskazka", &[])).color(theme::MUTED));
        }
        ui.add_space(12.0);
        ui.horizontal(|ui| {
            ui.add(theme::field(&mut self.query).hint_text(tr("library.poisk_po_nazvaniyu", &[])).desired_width(360.0));
            ui.label(RichText::new(count_films(total)).color(theme::MUTED));
        });
        let (text, color) = match notice {
            Some(n) => (n, theme::BAD),
            None => (status, if kind == StatusKind::Error { theme::BAD } else { theme::MUTED }),
        };
        ui.label(RichText::new(text).size(12.5).color(color));
    }

    fn filters(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            egui::ComboBox::from_id_salt(("sort", self.mine))
                .selected_text(tr("library.sortirovka_label", &[("label", &self.sort.label())]).replace(" ▾", ""))
                .show_ui(ui, |ui| {
                    for s in SortBy::ALL {
                        ui.selectable_value(&mut self.sort, s, s.label());
                    }
                });
            ui.checkbox(&mut self.only_ready, tr("library.tolko_gotovye", &[]));
        });
    }

    /// «Какую версию включить?»: по строке на раздачу, с качеством и размером.
    fn version_picker(&mut self, ctx: &egui::Context, lib: &Library) {
        let Some(vs) = self.pick.clone() else { return };
        let mut keep = true;
        let mut chosen = None;
        let title = vs.first().map(|v| lib.state().title_of(v)).unwrap_or_default();
        egui::Window::new(title).collapsible(false).resizable(false).open(&mut keep).show(ctx, |ui| {
            for v in &vs {
                let line = version_line(v, false);
                if ui
                    .add(egui::Button::new(format!("{line}\n{}", v.m.name)).min_size(egui::vec2(420.0, 40.0)))
                    .clicked()
                {
                    chosen = Some(v.clone());
                }
            }
        });
        if chosen.is_some() || !keep {
            self.pick = None;
        }
        if chosen.is_some() {
            self.open = chosen;
        }
    }

    /// Пока плеера нет: что будет играть и откуда продолжить.
    fn play_card(&mut self, ctx: &egui::Context, lib: &Library) {
        let Some(item) = self.open.clone() else { return };
        let mut keep = true;
        let title = lib.state().title_of(&item);
        egui::Window::new(title).collapsible(false).resizable(false).open(&mut keep).default_width(460.0).show(
            ctx,
            |ui| {
                let m = &item.m;
                ui.label(RichText::new(&m.name).color(theme::MUTED).size(12.0));
                ui.add_space(8.0);
                let file: Option<(MFile, String)> = match &item.playable {
                    Playable::Movie(f) => Some((f.clone(), item.base_title.clone())),
                    Playable::Series(seasons) => {
                        let saved = lib.last_watched(m).map(|w| w.0.path).or_else(|| lib.last_episode(m.id));
                        let s = seasons
                            .iter()
                            .find(|s| s.items.iter().any(|e| Some(&e.file.path) == saved.as_ref()))
                            .or(seasons.first());
                        s.and_then(|s| {
                            let e =
                                s.items.iter().find(|e| Some(&e.file.path) == saved.as_ref()).or(s.items.first())?;
                            Some((e.file.clone(), episode_label(&item.base_title, s, e)))
                        })
                    }
                    Playable::None => None,
                };
                match file {
                    Some((f, label)) => {
                        ui.label(RichText::new(label).strong());
                        ui.label(format!("{} · {}", f.name, format_bytes(f.size)));
                        lib.want_media_line(m.id, &f);
                        if let Some(line) = lib.cached_media_line(m.id, &f) {
                            ui.label(RichText::new(line).color(theme::MUTED));
                        }
                        if let Some(w) = lib.watch_info(&watch_key(m.id, &f)) {
                            if w.fraction() > 0.01 && !w.watched() {
                                ui.label(format!(
                                    "▶ {} / {}",
                                    suslik_core::clock_text(w.pos),
                                    suslik_core::clock_text(w.len)
                                ));
                            }
                        }
                    }
                    None => {
                        ui.label(status_ru(m));
                    }
                }
                ui.add_space(10.0);
                ui.label(
                    RichText::new("Плеер переезжает в Rust-версию на этапе 6. Пока смотреть — в обычной ПК-версии.")
                        .color(theme::LAMP),
                );
            },
        );
        if !keep {
            self.open = None;
        }
    }
}

fn count_films(n: usize) -> String {
    let c = n.to_string();
    let key = match (n % 10, n % 100) {
        (1, r) if r != 11 => "library.count_film",
        (2..=4, r) if !(12..=14).contains(&r) => "library.count_filma",
        _ => "library.count_filmov",
    };
    tr(key, &[("count", &c)])
}

/// «‹ 1 2 3 … 9 ›» под постерами.
fn page_bar(ui: &mut egui::Ui, page: usize, count: usize) -> Option<usize> {
    let mut go = None;
    ui.horizontal(|ui| {
        if ui.add_enabled(page > 0, egui::Button::new("‹")).clicked() {
            go = Some(page - 1);
        }
        let mut last_shown = None;
        for i in 0..count {
            let near = i == 0 || i + 1 == count || i.abs_diff(page) <= 2;
            if !near {
                if last_shown.is_some_and(|l: usize| l + 1 == i) {
                    ui.label("…");
                }
                continue;
            }
            last_shown = Some(i);
            if ui.selectable_label(i == page, (i + 1).to_string()).clicked() {
                go = Some(i);
            }
        }
        if ui.add_enabled(page + 1 < count, egui::Button::new("›")).clicked() {
            go = Some(page + 1);
        }
    });
    go
}

const BOOKMARK: Color32 = Color32::from_rgb(0x7F, 0xB2, 0xFF);
const HEART: Color32 = Color32::from_rgb(0xFF, 0x6B, 0x8A);

/// Плитка-постер. Возвращает раздачу, если на неё нажали.
fn tile(
    ui: &mut egui::Ui,
    lib: &Library,
    item: &LibraryItem,
    versions: &[LibraryItem],
    in_mine: bool,
    w: f32,
) -> Option<LibraryItem> {
    let m = &item.m;
    let size = Vec2::new(w, w * 1.5);
    let can_play = m.ready() && !matches!(item.playable, Playable::None);
    let (rect, resp) = ui.allocate_exact_size(size, if can_play { Sense::click() } else { Sense::hover() });
    let resp = if can_play { resp } else { ui.interact(rect, resp.id, Sense::click()) };
    let hovered = resp.hovered();
    let p = ui.painter_at(rect);
    let rounding = 14.0;
    p.rect_filled(rect, rounding, Color32::from_rgb(0x13, 0x16, 0x1F));
    if let Some(tex) = lib.poster(&item.poster_queries) {
        // как ContentScale.Crop: заполнить плитку, лишнее обрезать
        let [tw, th] = tex.size().map(|v| v as f32);
        let (sx, sy) = (size.x / tw, size.y / th);
        let s = sx.max(sy);
        let (uw, uh) = (size.x / (tw * s), size.y / (th * s));
        let uv = Rect::from_min_size(Pos2::new((1.0 - uw) / 2.0, (1.0 - uh) / 2.0), Vec2::new(uw, uh));
        egui::Image::from_texture(&tex).uv(uv).rounding(rounding).paint_at(ui, rect);
    } else {
        p.text(rect.center(), egui::Align2::CENTER_CENTER, "🎬", egui::FontId::proportional(28.0), theme::LINE);
    }
    let edge = if hovered { Color32::from_rgb(0x5A, 0x66, 0x80) } else { Color32::from_rgb(0x20, 0x24, 0x30) };
    p.rect_stroke(rect, rounding, (1.0, edge));

    let st = lib.state();
    let group: Vec<&LibraryItem> = if versions.is_empty() { vec![item] } else { versions.iter().collect() };
    let is_mine = group.iter().any(|v| v.m.mine);
    let is_fav = group.iter().any(|v| v.m.favorite);
    let ids: Vec<i64> = group.iter().map(|v| v.m.id).collect();

    // Метки и число версий — справа сверху.
    let mut tags = Vec::new();
    for v in &group {
        tags.extend(st.tags_of(v));
    }
    let mut y = rect.top() + 8.0;
    let mut badge = |text: &str, color: Color32| {
        let galley = p.layout_no_wrap(text.to_string(), egui::FontId::proportional(11.5), color);
        let r = Rect::from_min_size(
            Pos2::new(rect.right() - 8.0 - galley.size().x - 16.0, y),
            Vec2::new(galley.size().x + 16.0, 22.0),
        );
        p.rect_filled(r, 8.0, Color32::from_black_alpha(190));
        p.rect_stroke(r, 8.0, (1.0, color.gamma_multiply(0.7)));
        p.galley(r.center() - galley.size() / 2.0, galley, color);
        y += 26.0;
    };
    if versions.len() > 1 {
        badge(&versions_word(versions.len()), Color32::WHITE);
    }
    for t in catalog::collapse_tags(&tags) {
        let c = match t {
            "Dolby Vision" => Color32::from_rgb(0xB7, 0x94, 0xF6),
            "HDR" => Color32::from_rgb(0xFF, 0xC8, 0x57),
            _ => Color32::from_rgb(0x7D, 0xD3, 0xFC),
        };
        badge(t, c);
    }

    // Подпись снизу: название, а при наведении (и всегда у неготовых) — год, вид, статус и характеристики.
    let anim = |k: &str| st.is_animation(k);
    let details = hovered || !m.ready();
    let mut lines: Vec<(String, f32, Color32)> = vec![(st.title_of(item), 13.5, Color32::WHITE)];
    if details {
        lines.push((
            format!("{} • {}", item_year(item), item_type_label(item, &anim)),
            11.5,
            Color32::from_rgb(0x9B, 0xA4, 0xB5),
        ));
        let status = if m.ready() {
            tr("library.gotovo", &[])
        } else if m.downloading() {
            format!("{}% • {}", (m.progress() * 100.0) as i32, status_ru(m))
        } else {
            status_ru(m)
        };
        lines.push((status, 10.5, Color32::WHITE));
        let media = match &item.playable {
            Playable::Movie(f) if m.ready() => lib.cached_media_line(m.id, f),
            _ => None,
        };
        let (a, b) = item_specs(item, media.as_deref());
        lines.push((a, 11.0, Color32::from_rgb(0xC0, 0xC7, 0xD5)));
        lines.push((b, 10.5, Color32::from_rgb(0x7A, 0x82, 0x94)));
    }
    drop(st);
    let galleys: Vec<_> = lines
        .into_iter()
        .map(|(t, s, c)| {
            let mut job = egui::text::LayoutJob::simple(t, egui::FontId::proportional(s), c, w - 24.0);
            job.wrap.max_rows = 2;
            p.layout_job(job)
        })
        .collect();
    let text_h: f32 = galleys.iter().map(|g| g.size().y + 2.0).sum();
    let band = Rect::from_min_max(Pos2::new(rect.left(), rect.bottom() - text_h - 22.0), rect.max);
    p.rect_filled(
        band,
        egui::Rounding { nw: 0.0, ne: 0.0, sw: rounding, se: rounding },
        Color32::from_rgba_unmultiplied(0x0B, 0x0E, 0x14, 225),
    );
    let mut ty = band.top() + 11.0;
    for g in galleys {
        let h = g.size().y;
        p.galley(Pos2::new(rect.left() + 12.0, ty), g, Color32::WHITE);
        ty += h + 2.0;
    }

    // Прогресс просмотра (или загрузки) — полоска по нижнему краю.
    let frac = match &item.playable {
        Playable::Movie(f) if m.ready() => lib
            .watch_info(&watch_key(m.id, f))
            .filter(|w| w.fraction() > 0.01)
            .map(|w| if w.watched() { 1.0 } else { w.fraction() }),
        _ if m.downloading() && m.size > 0 => Some(m.progress()),
        _ => None,
    };
    if let Some(f) = frac {
        let bar = Rect::from_min_max(
            Pos2::new(rect.left() + 6.0, rect.bottom() - 4.0),
            Pos2::new(rect.right() - 6.0, rect.bottom() - 1.0),
        );
        p.rect_filled(bar, 1.5, Color32::from_black_alpha(90));
        p.rect_filled(
            Rect::from_min_size(bar.min, Vec2::new(bar.width() * f, bar.height())),
            1.5,
            Color32::from_rgb(0x38, 0xBD, 0xF8),
        );
    }

    if hovered && can_play {
        p.circle_filled(rect.center(), 30.0, Color32::from_white_alpha(40));
        p.text(rect.center(), egui::Align2::CENTER_CENTER, "▶", egui::FontId::proportional(30.0), Color32::WHITE);
        ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
    }

    // Уголки: «Мои фильмы» (закладка) и избранное (сердце). Включённые видны всегда, остальные — при наведении.
    let mut corner_clicked = false;
    for (i, (on, glyph, color, mine)) in
        [(is_mine, "🔖", BOOKMARK, true), (is_fav, "❤", HEART, false)].into_iter().enumerate()
    {
        if !(on || hovered) {
            continue;
        }
        let c = Pos2::new(rect.left() + 22.0 + i as f32 * 36.0, rect.top() + 22.0);
        let r = Rect::from_center_size(c, Vec2::splat(30.0));
        let b = ui.interact(r, resp.id.with(i), Sense::click());
        p.circle_filled(c, 15.0, Color32::from_black_alpha(if b.hovered() { 220 } else { 150 }));
        p.text(
            c,
            egui::Align2::CENTER_CENTER,
            glyph,
            egui::FontId::proportional(14.0),
            if on { color } else { Color32::WHITE },
        );
        if b.clicked() {
            corner_clicked = true;
            lib.set_flag(ids.clone(), mine, !on);
        }
    }

    // Правая кнопка: «Мои фильмы», избранное, удаление.
    resp.context_menu(|ui| {
        if ui.button(tr(if is_mine { "library.iz_moih_filmov" } else { "library.v_moi_filmy" }, &[])).clicked() {
            lib.set_flag(ids.clone(), true, !is_mine);
            ui.close_menu();
        }
        if ui.button(tr(if is_fav { "library.iz_izbrannogo" } else { "library.v_izbrannoe" }, &[])).clicked() {
            lib.set_flag(ids.clone(), false, !is_fav);
            ui.close_menu();
        }
        // «Удалить» стирает раздачу из AllDebrid у всех: в «Моих фильмах» его нет (своя незаконченная загрузка — отменить можно)
        if m.can_remove && (!in_mine || m.downloading()) {
            let label = tr(if m.downloading() { "library.otmenit" } else { "library.udalit" }, &[]);
            if ui.button(RichText::new(label).color(theme::BAD)).clicked() {
                lib.remove(m);
                ui.close_menu();
            }
        }
    });

    (can_play && resp.clicked() && !corner_clicked).then(|| item.clone())
}
