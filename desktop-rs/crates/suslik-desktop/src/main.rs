//! Suslik Media для ПК на Rust: каркас окна. Пока только «Что нового» и проверка адреса сервера;
//! вход, медиатека и плеер mpv переезжают сюда по шагам из `docs/RUST-PORT.md`.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

// Клиент, хранилище и тексты нужны целиком начиная с этапа 4 (вход и медиатека); пока окно берёт из них только сеанс.
#[allow(dead_code)]
mod api;
mod app_dir;
#[allow(dead_code)]
mod store;
#[allow(dead_code)]
mod strings;
mod theme;

use std::sync::{Arc, Mutex};

use eframe::egui;
use suslik_core::{auth, changelog, site_address};

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
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("Suslik Media")
            .with_inner_size([1280.0, 800.0])
            .with_min_inner_size([MIN_W, MIN_H]),
        ..Default::default()
    };
    eframe::run_native("Suslik Media", options, Box::new(|cc| Ok(Box::new(App::new(cc)))))
}

/// Проверка сеанса на старте: тот же `/api/session` с cookie и токеном устройства из `%APPDATA%\SuslikMedia`.
enum Session {
    Checking,
    Ready(auth::SessionInfo),
    Failed(String),
}

struct App {
    site: String,
    releases: Vec<changelog::Release>,
    session: Arc<Mutex<Session>>,
}

impl App {
    fn new(cc: &eframe::CreationContext<'_>) -> Self {
        theme::apply(&cc.egui_ctx);
        let all = changelog::parse(CHANGELOG);
        let site = site_address::normalize(SITE_URL);
        let session = Arc::new(Mutex::new(Session::Checking));
        if !site.is_empty() {
            let (slot, ctx, base) = (session.clone(), cc.egui_ctx.clone(), site.clone());
            std::thread::spawn(move || {
                let api = api::Api::new(base);
                let s = match api.get(auth::SESSION_PATH) {
                    Ok(v) => Session::Ready(auth::SessionInfo::parse(&v)),
                    Err(e) => Session::Failed(e.describe()),
                };
                *slot.lock().unwrap() = s;
                ctx.request_repaint();
            });
        }
        Self { site, releases: changelog::up_to(&all, VERSION, 3), session }
    }

    fn session_line(&self, ui: &mut egui::Ui) {
        match &*self.session.lock().unwrap() {
            Session::Checking => {
                ui.label(egui::RichText::new("Проверяем вход…").color(theme::MUTED));
            }
            Session::Ready(info) => match info.user.as_ref().filter(|_| info.authenticated) {
                Some(u) => {
                    ui.label(egui::RichText::new(&u.name).color(theme::TEXT));
                }
                None => {
                    ui.label(egui::RichText::new("Вход не выполнен").color(theme::MUTED));
                }
            },
            Session::Failed(e) => {
                ui.colored_label(theme::BAD, e);
            }
        }
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        egui::TopBottomPanel::top("header").show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.heading("Suslik Media");
                ui.label(egui::RichText::new(format!("{VERSION} · Rust")).color(theme::MUTED));
                if !self.site.is_empty() {
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| self.session_line(ui));
                }
            });
            if self.site.is_empty() {
                ui.colored_label(theme::BAD, "Не задан адрес сервера: соберите с переменной SITE_URL.");
            }
        });
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading("Что нового");
            egui::ScrollArea::vertical().show(ui, |ui| {
                for r in &self.releases {
                    ui.add_space(8.0);
                    ui.label(egui::RichText::new(&r.title).strong().color(theme::LAMP));
                    for item in &r.items {
                        ui.label(format!("• {item}"));
                    }
                }
            });
        });
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        store::Store::flush_all();
    }
}
