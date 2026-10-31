//! Боковая панель (`Sidebar.kt`): разделы, «Коллекция» с числом фильмов в каждой категории, профиль и выход.

use super::Tab;
use crate::catalog::{item_category, Category};
use crate::library::Library;
use crate::strings::tr;
use crate::theme;
use eframe::egui::{self, RichText};
use std::collections::HashMap;

/// Что выбрала панель: раздел и категория страницы медиатеки (у «Главной» и «Моей медиатеки» — своя).
pub struct Nav {
    pub tab: Tab,
    pub category: Category,
    pub mine_category: Category,
}

impl Default for Nav {
    fn default() -> Self {
        Self { tab: Tab::Library, category: Category::All, mine_category: Category::All }
    }
}

impl Nav {
    pub fn category_of(&self, mine: bool) -> Category {
        if mine {
            self.mine_category
        } else {
            self.category
        }
    }
}

pub enum SidebarAction {
    None,
    /// Перешли на страницу медиатеки: список стоит обновить.
    OpenedLibrary,
    Logout,
}

fn nav_item(
    ui: &mut egui::Ui,
    label: &str,
    active: bool,
    enabled: bool,
    note: Option<&str>,
    count: Option<usize>,
) -> bool {
    let h = if note.is_some() { 48.0 } else { 40.0 };
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(ui.available_width(), h), egui::Sense::click());
    let hovered = resp.hovered() && enabled;
    let fill = if active {
        theme::ACCENT.gamma_multiply(0.78)
    } else if hovered {
        egui::Color32::from_white_alpha(15)
    } else {
        egui::Color32::TRANSPARENT
    };
    let p = ui.painter();
    p.rect_filled(rect, 12.0, fill);
    let fg = if !enabled {
        egui::Color32::from_rgb(0x5B, 0x62, 0x72)
    } else if active || hovered {
        egui::Color32::WHITE
    } else {
        egui::Color32::from_rgb(0xCB, 0xD2, 0xE0)
    };
    let y = if note.is_some() { rect.top() + 16.0 } else { rect.center().y };
    p.text(egui::pos2(rect.left() + 15.0, y), egui::Align2::LEFT_CENTER, label, egui::FontId::proportional(14.0), fg);
    if let Some(n) = note {
        let c = if active { egui::Color32::from_white_alpha(200) } else { egui::Color32::from_rgb(0x5B, 0x62, 0x72) };
        p.text(
            egui::pos2(rect.left() + 15.0, rect.top() + 34.0),
            egui::Align2::LEFT_CENTER,
            n,
            egui::FontId::proportional(10.5),
            c,
        );
    }
    if let Some(c) = count {
        p.text(
            egui::pos2(rect.right() - 10.0, rect.center().y),
            egui::Align2::RIGHT_CENTER,
            c.to_string(),
            egui::FontId::proportional(12.0),
            theme::MUTED,
        );
    }
    if enabled && resp.hovered() {
        ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
    }
    enabled && resp.clicked()
}

fn section(ui: &mut egui::Ui, title: &str) {
    ui.add_space(14.0);
    ui.label(RichText::new(title).size(11.0).color(theme::MUTED));
    ui.add_space(4.0);
}

pub fn show(ui: &mut egui::Ui, nav: &mut Nav, lib: &Library, counts_cache: &mut CountsCache) -> SidebarAction {
    let mut action = SidebarAction::None;
    ui.add_space(18.0);
    ui.horizontal(|ui| {
        ui.add_space(8.0);
        ui.label(RichText::new("Suslik Media").font(theme::heading(16.0)));
    });
    ui.add_space(18.0);
    let in_lib = nav.tab == Tab::Library;
    let in_mine = nav.tab == Tab::Mine;
    let current = nav.category_of(in_mine);
    if nav_item(ui, &tr("sidebar.glavnaya", &[]), in_lib && current == Category::All, true, None, None) {
        nav.tab = Tab::Library;
        nav.category = Category::All;
        action = SidebarAction::OpenedLibrary;
    }
    if nav_item(ui, &tr("sidebar.moya_mediateka", &[]), in_mine && current == Category::All, true, None, None) {
        nav.tab = Tab::Mine;
        nav.mine_category = Category::All;
        action = SidebarAction::OpenedLibrary;
    }
    if nav_item(ui, &tr("search.poisk_razdach", &[]), nav.tab == Tab::Search, true, None, None) {
        nav.tab = Tab::Search;
    }
    if nav_item(ui, &tr("search.magnet_torrent", &[]), nav.tab == Tab::Add, true, None, None) {
        nav.tab = Tab::Add;
    }
    // «Вместе» переезжает на этапе 7
    nav_item(ui, &tr("sidebar.sovmestnyy_prosmotr", &[]), false, false, Some(&tr("sidebar.skoro", &[])), None);

    section(ui, &tr("sidebar.kollektsiya", &[]));
    let counts = counts_cache.get(lib, in_mine);
    for c in Category::ALL.into_iter().filter(|c| *c != Category::All) {
        let active = (in_lib || in_mine) && current == c;
        if nav_item(ui, &c.label(), active, true, None, Some(counts.get(&c).copied().unwrap_or(0))) {
            if in_mine {
                nav.mine_category = c;
            } else {
                nav.tab = Tab::Library;
                nav.category = c;
            }
            action = SidebarAction::OpenedLibrary;
        }
    }
    section(ui, &tr("sidebar.servis", &[]));
    // «Настройки» — вместе с плеером (этап 6)
    nav_item(ui, &tr("sidebar.nastroyki", &[]), false, false, Some(&tr("sidebar.skoro", &[])), None);

    ui.with_layout(egui::Layout::bottom_up(egui::Align::LEFT), |ui| {
        ui.add_space(16.0);
        let out = egui::Button::new(tr("library.vyyti", &[])).min_size(egui::vec2(ui.available_width(), 32.0));
        if ui.add(out).clicked() {
            action = SidebarAction::Logout;
        }
        ui.add_space(8.0);
        let profile = lib.state().profile.clone();
        ui.label(RichText::new(profile.note).size(11.5).color(theme::MUTED));
        ui.label(RichText::new(profile.name).strong());
    });
    action
}

/// Число фильмов в категориях: пересчёт, только когда меняются список или жанры.
#[derive(Default)]
pub struct CountsCache {
    key: Option<(usize, u64, bool)>,
    counts: HashMap<Category, usize>,
}

impl CountsCache {
    fn get(&mut self, lib: &Library, mine: bool) -> HashMap<Category, usize> {
        let st = lib.state();
        let key = (std::sync::Arc::as_ptr(&st.items) as usize, st.anim_version, mine);
        if self.key != Some(key) {
            let anim = |k: &str| st.is_animation(k);
            let mut counts = HashMap::new();
            for item in st.items.iter().filter(|i| !mine || i.m.mine) {
                *counts.entry(item_category(item, &anim)).or_insert(0) += 1;
            }
            self.counts = counts;
            self.key = Some(key);
        }
        self.counts.clone()
    }
}
