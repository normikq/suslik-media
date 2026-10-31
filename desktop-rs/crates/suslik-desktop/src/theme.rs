//! «Проекторная» — те же токены, что у сайта, Android и `desktop/.../ui/Theme.kt`.

use eframe::egui::{self, Color32};

pub const NIGHT: Color32 = Color32::from_rgb(0x09, 0x0B, 0x10);
pub const BOOTH: Color32 = Color32::from_rgb(0x11, 0x14, 0x1B);
pub const FILM: Color32 = Color32::from_rgb(0x18, 0x1C, 0x26);
pub const LINE: Color32 = Color32::from_rgb(0x25, 0x2A, 0x37);
pub const TEXT: Color32 = Color32::from_rgb(0xEE, 0xF0, 0xF5);
pub const MUTED: Color32 = Color32::from_rgb(0x8E, 0x96, 0xA8);
pub const LAMP: Color32 = Color32::from_rgb(0xFF, 0xB0, 0x2E);
pub const ACCENT: Color32 = Color32::from_rgb(0x25, 0x63, 0xEB);
pub const BAD: Color32 = Color32::from_rgb(0xFF, 0x9B, 0x8F);

/// Шрифты Kotlin-версии: Onest — текст, Unbounded — заголовки. Шрифты egui остаются запасными (значки, эмодзи).
const ONEST: &[u8] = include_bytes!("../../../../desktop/src/main/resources/fonts/onest_regular.ttf");
const UNBOUNDED: &[u8] = include_bytes!("../../../../desktop/src/main/resources/fonts/unbounded_medium.ttf");

/// Заголовок страницы шрифтом Unbounded.
pub fn heading(size: f32) -> egui::FontId {
    egui::FontId::new(size, egui::FontFamily::Name("unbounded".into()))
}

fn fonts(ctx: &egui::Context) {
    let mut f = egui::FontDefinitions::default();
    f.font_data.insert("onest".into(), egui::FontData::from_static(ONEST));
    f.font_data.insert("unbounded".into(), egui::FontData::from_static(UNBOUNDED));
    let fallback = f.families.get(&egui::FontFamily::Proportional).cloned().unwrap_or_default();
    f.families.entry(egui::FontFamily::Proportional).or_default().insert(0, "onest".into());
    let mut heading = vec!["unbounded".to_string(), "onest".to_string()];
    heading.extend(fallback);
    f.families.insert(egui::FontFamily::Name("unbounded".into()), heading);
    ctx.set_fonts(f);
}

pub fn apply(ctx: &egui::Context) {
    fonts(ctx);
    let mut v = egui::Visuals::dark();
    v.panel_fill = NIGHT;
    v.window_fill = BOOTH;
    v.extreme_bg_color = FILM;
    v.override_text_color = Some(TEXT);
    v.selection.bg_fill = ACCENT;
    v.widgets.noninteractive.bg_stroke.color = LINE;
    v.hyperlink_color = LAMP;
    ctx.set_visuals(v);
}
