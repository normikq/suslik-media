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
    // Кнопки, поля и меню — скруглённые, как у Kotlin-версии (12 dp у кнопок и полей, 14 у окон).
    let r = egui::Rounding::same(RADIUS);
    for w in [
        &mut v.widgets.noninteractive,
        &mut v.widgets.inactive,
        &mut v.widgets.hovered,
        &mut v.widgets.active,
        &mut v.widgets.open,
    ] {
        w.rounding = r;
    }
    v.widgets.inactive.weak_bg_fill = FILM;
    v.widgets.inactive.bg_fill = FILM;
    v.widgets.inactive.bg_stroke = egui::Stroke::new(1.0, LINE);
    v.widgets.hovered.weak_bg_fill = egui::Color32::from_rgb(0x22, 0x27, 0x34);
    v.widgets.hovered.bg_stroke = egui::Stroke::new(1.0, egui::Color32::from_rgb(0x3E, 0x47, 0x5C));
    v.window_rounding = egui::Rounding::same(14.0);
    v.menu_rounding = egui::Rounding::same(RADIUS);
    ctx.set_visuals(v);
    ctx.style_mut(|s| {
        s.spacing.button_padding = egui::vec2(14.0, 7.0);
        s.spacing.interact_size.y = 30.0;
    });
}

/// Скругление кнопок и полей ввода.
pub const RADIUS: f32 = 10.0;

/// Поле ввода в стиле приложения: скруглённое, с отступами внутри.
pub fn field(text: &mut String) -> egui::TextEdit<'_> {
    egui::TextEdit::singleline(text).margin(egui::vec2(12.0, 8.0))
}
