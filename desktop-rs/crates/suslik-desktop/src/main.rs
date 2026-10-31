//! Suslik Media для ПК на Rust: каркас окна. Пока только «Что нового» и проверка адреса сервера;
//! вход, медиатека и плеер mpv переезжают сюда по шагам из `docs/RUST-PORT.md`.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod theme;

use eframe::egui;
use suslik_core::{changelog, site_address};

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

struct App {
    site: String,
    releases: Vec<changelog::Release>,
}

impl App {
    fn new(cc: &eframe::CreationContext<'_>) -> Self {
        theme::apply(&cc.egui_ctx);
        let all = changelog::parse(CHANGELOG);
        Self { site: site_address::normalize(SITE_URL), releases: changelog::up_to(&all, VERSION, 3) }
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        egui::TopBottomPanel::top("header").show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.heading("Suslik Media");
                ui.label(egui::RichText::new(format!("{VERSION} · Rust")).color(theme::MUTED));
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
}
