//! Экран входа (`LoginView`): «Войти через Discord» по коду устройства или общий пароль.

use crate::library::Library;
use crate::strings::tr;
use crate::theme;
use eframe::egui::{self, RichText};

#[derive(Default)]
pub struct LoginView {
    password: String,
}

impl LoginView {
    pub fn show(&mut self, ui: &mut egui::Ui, lib: &Library) {
        let (pairing, busy, error) = {
            let st = lib.state();
            (st.pairing.clone(), st.login_busy, st.login_error.clone())
        };
        ui.vertical(|ui| {
            ui.add_space(64.0);
            ui.set_max_width(560.0);
            ui.label(RichText::new(tr("library.vse_vashi_ssylki_v", &[])).color(theme::MUTED));
            ui.label(RichText::new(tr("library.naydite_razblokiruyte_smotrite", &[])).font(theme::heading(34.0)));
            ui.add_space(24.0);
            egui::Frame::none().fill(theme::BOOTH).rounding(16.0).inner_margin(20.0).stroke((1.0, theme::LINE)).show(
                ui,
                |ui| {
                    ui.set_width(ui.available_width());
                    if let Some(p) = pairing {
                        ui.label(RichText::new(tr("login.vhod_cherez_discord", &[])).font(theme::heading(20.0)));
                        ui.label(RichText::new(tr("login.podtverdite_v_brauzere", &[])).color(theme::MUTED));
                        ui.add_space(12.0);
                        egui::Frame::none().fill(theme::FILM).rounding(12.0).inner_margin(16.0).show(ui, |ui| {
                            ui.set_width(ui.available_width());
                            ui.vertical_centered(|ui| ui.label(RichText::new(&p.user_code).font(theme::heading(30.0))));
                        });
                        ui.add_space(12.0);
                        ui.horizontal(|ui| {
                            if ui.button(tr("login.otkryt_v_brauzere", &[])).clicked() {
                                ui.ctx().open_url(egui::OpenUrl::new_tab(&p.url));
                            }
                            if ui.button(tr("login.otmena", &[])).clicked() {
                                lib.cancel_device_auth();
                            }
                        });
                    } else {
                        ui.label(RichText::new(tr("library.voyti", &[])).font(theme::heading(20.0)));
                        ui.add_space(10.0);
                        let discord =
                            egui::Button::new(RichText::new(tr("login.voyti_cherez_discord", &[])).color(theme::NIGHT))
                                .fill(theme::LAMP)
                                .min_size(egui::vec2(ui.available_width(), 36.0));
                        if ui.add_enabled(!busy, discord).clicked() {
                            lib.start_device_auth();
                        }
                        ui.add_space(14.0);
                        ui.vertical_centered(|ui| {
                            ui.label(RichText::new(tr("login.ili_po_parolyu", &[])).color(theme::MUTED).size(12.0))
                        });
                        ui.add_space(10.0);
                        let field = ui.add(
                            theme::field(&mut self.password)
                                .password(true)
                                .hint_text(tr("library.parol_dostupa", &[]))
                                .desired_width(f32::INFINITY),
                        );
                        let enter = field.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
                        ui.add_space(10.0);
                        let label = tr(if busy { "library.vypolnyaetsya_vhod" } else { "library.voyti" }, &[]);
                        let button = egui::Button::new(label).min_size(egui::vec2(ui.available_width(), 34.0));
                        if (ui.add_enabled(!busy, button).clicked() || enter) && !self.password.trim().is_empty() {
                            lib.login(std::mem::take(&mut self.password));
                        }
                    }
                    if let Some(e) = error {
                        ui.add_space(10.0);
                        ui.colored_label(theme::BAD, e);
                    }
                },
            );
        });
    }
}
