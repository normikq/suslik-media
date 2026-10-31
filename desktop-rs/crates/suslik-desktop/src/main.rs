//! Suslik Media для ПК на Rust. Сейчас: вход, боковая панель, медиатека, поиск по трекерам и плеер mpv с запасом на
//! диске; остальное переезжает сюда по шагам из `docs/RUST-PORT.md`.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod adding;
mod api;
mod app_dir;
mod catalog;
mod library;
mod player;
mod player_log;
mod reserve;
mod store;
mod strings;
mod theme;
mod ui;

use eframe::egui;
use library::Library;
use strings::tr;
use suslik_core::{changelog, site_address};
use ui::{add_view::AddView, library_view::LibraryView, login::LoginView, search_view::SearchView, sidebar, Tab};

/// Версия приложения — из `desktop-rs/Cargo.toml` (одна на всю рабочую область).
const VERSION: &str = env!("CARGO_PKG_VERSION");
/// Адрес сервера в репозиторий не пишем: сборка берёт его из переменной `SITE_URL` (как `-Dsuslik.site` у Kotlin-версии).
const SITE_URL: Option<&str> = option_env!("SITE_URL");
/// Тот же changelog, что у Kotlin-версии: тексты не дублируем, пока обе живут рядом.
const CHANGELOG: &str = include_str!("../../../../desktop/src/main/resources/changelog.json");

/// Наименьшее обычное окно — как `MIN_W`/`MIN_H` в `Main.kt`.
const MIN_W: f32 = 1005.0;
const MIN_H: f32 = 685.0;

fn main() -> eframe::Result {
    let title = if cfg!(feature = "diag") { "Suslik Debug" } else { "Suslik Media" };
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title(title)
            .with_inner_size([1280.0, 860.0])
            .with_min_inner_size([MIN_W, MIN_H]),
        ..Default::default()
    };
    eframe::run_native(title, options, Box::new(|cc| Ok(Box::new(App::new(cc)))))
}

struct App {
    lib: Option<Library>,
    nav: sidebar::Nav,
    counts: sidebar::CountsCache,
    login: LoginView,
    library: LibraryView,
    mine: LibraryView,
    search: SearchView,
    add: AddView,
    /// «Что нового» — один раз после обновления (тот же `whatsnew.properties`, что у Kotlin-версии).
    whats_new: Option<Vec<changelog::Release>>,
    /// Открытый плеер (вместо всего окна) и текстура видео, которая живёт между фильмами.
    player: Option<player::view::Player>,
    surface: Option<player::video::Surface>,
    player_error: Option<String>,
    ctx: egui::Context,
}

impl App {
    fn new(cc: &eframe::CreationContext<'_>) -> Self {
        theme::apply(&cc.egui_ctx);
        let site = site_address::normalize(SITE_URL);
        let lib = (!site.is_empty()).then(|| Library::new(cc.egui_ctx.clone(), site.clone()));
        if let Some(l) = &lib {
            l.start();
        }
        let seen = store::Store::of("whatsnew");
        let last = seen.get("version");
        let all = changelog::parse(CHANGELOG);
        let notes = changelog::up_to(&all, VERSION, 3);
        let whats_new = if last.as_deref() == Some(VERSION) {
            None
        } else if !changelog::should_show(&notes, last.as_deref()) {
            seen.put("version", Some(VERSION));
            None
        } else {
            Some(notes)
        };
        Self {
            lib,
            nav: sidebar::Nav::default(),
            counts: sidebar::CountsCache::default(),
            login: LoginView::default(),
            library: LibraryView::new(false),
            mine: LibraryView::new(true),
            search: SearchView::default(),
            add: AddView::default(),
            whats_new,
            player: None,
            surface: None,
            player_error: None,
            ctx: cc.egui_ctx.clone(),
        }
    }

    fn play(&mut self, req: Option<player::view::PlayRequest>, lib: &Library, ctx: &egui::Context) {
        let Some(req) = req else { return };
        match player::view::Player::new(req, lib, ctx) {
            Ok(p) => self.player = Some(p),
            Err(e) => {
                player_log::write(&format!("player: {e}"));
                self.player_error = Some(e);
            }
        }
    }

    fn player_error(&mut self, ctx: &egui::Context) {
        let Some(e) = self.player_error.clone() else { return };
        let mut open = true;
        let mut ok = false;
        egui::Window::new(tr("player.ne_udalos_vosproizvesti_potok", &[]))
            .collapsible(false)
            .resizable(false)
            .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
            .open(&mut open)
            .show(ctx, |ui| {
                ui.label(e);
                ok = ui.button("OK").clicked();
            });
        if ok || !open {
            self.player_error = None;
        }
    }

    fn whats_new(&mut self, ctx: &egui::Context) {
        let Some(releases) = &self.whats_new else { return };
        let mut open = true;
        let mut done = false;
        egui::Window::new("Что нового")
            .collapsible(false)
            .resizable(false)
            .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
            .open(&mut open)
            .show(ctx, |ui| {
                egui::ScrollArea::vertical().max_height(420.0).show(ui, |ui| {
                    for r in releases {
                        ui.add_space(6.0);
                        ui.label(egui::RichText::new(&r.title).strong().color(theme::LAMP));
                        for item in &r.items {
                            ui.label(format!("• {item}"));
                        }
                    }
                });
                ui.add_space(8.0);
                done = ui.button(tr("library.zakryt", &[])).clicked();
            });
        if done || !open {
            store::Store::of("whatsnew").put("version", Some(VERSION));
            self.whats_new = None;
        }
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, frame: &mut eframe::Frame) {
        let Some(lib) = self.lib.clone() else {
            egui::CentralPanel::default().show(ctx, |ui| {
                ui.heading("Suslik Media");
                ui.colored_label(theme::BAD, "Не задан адрес сервера: соберите с переменной SITE_URL.");
            });
            return;
        };
        let auth = lib.state().authenticated;
        if auth == Some(false) {
            egui::CentralPanel::default().show(ctx, |ui| {
                ui.horizontal(|ui| {
                    ui.add_space(32.0);
                    self.login.show(ui, &lib);
                });
            });
            return;
        }
        if let Some(p) = self.player.as_mut() {
            if !p.show(ctx, frame, &lib, &mut self.surface) {
                self.player = None;
            }
            return;
        }
        egui::SidePanel::left("nav")
            .exact_width(236.0)
            .resizable(false)
            .frame(egui::Frame::none().fill(theme::BOOTH).inner_margin(egui::Margin::symmetric(14.0, 0.0)))
            .show(ctx, |ui| match sidebar::show(ui, &mut self.nav, &lib, &mut self.counts) {
                sidebar::SidebarAction::OpenedLibrary => lib.refresh(),
                sidebar::SidebarAction::Logout => lib.logout(),
                sidebar::SidebarAction::None => {}
            });
        let mut req = None;
        egui::CentralPanel::default().frame(egui::Frame::none().fill(theme::NIGHT)).show(ctx, |ui| {
            match self.nav.tab {
                Tab::Library => req = self.library.show(ui, &lib, self.nav.category_of(false)),
                Tab::Mine => req = self.mine.show(ui, &lib, self.nav.category_of(true)),
                Tab::Search => self.search.show(ui, &lib),
                Tab::Add => self.add.show(ui, &lib),
            }
        });
        self.play(req, &lib, ctx);
        self.player_error(ctx);
        if auth == Some(true) {
            self.whats_new(ctx);
        }
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        // render context освобождается, пока жив GL-контекст окна (иначе mpv роняет процесс)
        if let (Some(mut p), Some(lib)) = (self.player.take(), self.lib.clone()) {
            p.close(&self.ctx, &lib);
        }
        suslik_core::burst_proxy::drop_all();
        store::Store::flush_all();
    }
}
