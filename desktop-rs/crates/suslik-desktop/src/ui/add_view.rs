//! «Добавить раздачу» (`AddView.kt`, режим «Фильм или сериал»): magnet-ссылки (можно несколько через пробел) и файлы
//! `.torrent` — кнопкой или перетаскиванием в окно. Озвучка к фильму из медиатеки переедет вместе с плеером.

use crate::adding::{self, Added, Row};
use crate::library::Library;
use crate::strings::tr;
use crate::theme;
use eframe::egui::{self, Color32, RichText};
use serde_json::json;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use suslik_core::{rx, torrent, url_codec};

const OK: Color32 = Color32::from_rgb(0x6E, 0xE7, 0xA8);
const MAX_TORRENT: u64 = 1024 * 1024;

#[derive(Default)]
pub struct AddView {
    magnet: String,
    status: Option<(String, bool)>,
    added: Vec<Row>,
}

impl AddView {
    fn push(&mut self, name: &str) -> Row {
        let row: Row = Arc::new(Mutex::new(Added::new(name)));
        self.added.insert(0, row.clone());
        row
    }

    fn submit_magnet(&mut self, lib: &Library) {
        let links: Vec<String> =
            self.magnet.split_whitespace().filter(|l| l.starts_with("magnet:?")).map(String::from).collect();
        if links.is_empty() {
            self.status = Some((tr("add.vstavte_ssylku_kotoraya_nachin", &[]), false));
            return;
        }
        self.magnet.clear();
        self.status = None;
        for link in links {
            let dn = suslik_core::jrx::caps(rx!(r"[?&]dn=([^&]+)"), &link)
                .and_then(|c| url_codec::decode(suslik_core::jrx::group(&c, 1)))
                .unwrap_or_else(|| tr("add.magnet_ssylka", &[]));
            let row = self.push(&dn);
            // имя уже говорит, что это не видео (.exe, .zip…) — не отправляем
            if let Some(reason) = torrent::rejection_for_magnet_name(&dn) {
                adding::set(&row, lib, |a| {
                    a.note = reason;
                    a.ok = false;
                });
                continue;
            }
            let lib = lib.clone();
            std::thread::spawn(move || match lib.api().post("/api/magnets", Some(&json!({ "magnet": link }))) {
                Ok(r) => adding::follow(&lib, &row, &r),
                Err(e) => adding::set(&row, &lib, |a| {
                    a.note = e.describe();
                    a.ok = false;
                }),
            });
        }
    }

    /// `.torrent` из диалога или перетащенные: загружаются сразу.
    fn take_torrents(&mut self, lib: &Library, files: Vec<PathBuf>) {
        let picked: Vec<PathBuf> =
            files.into_iter().filter(|f| f.extension().is_some_and(|e| e.eq_ignore_ascii_case("torrent"))).collect();
        if picked.is_empty() {
            self.status = Some((tr("add.vyberite_fayl_torrent", &[]), false));
            return;
        }
        let size = |f: &PathBuf| std::fs::metadata(f).map(|m| m.len()).unwrap_or(0);
        let too_big: Vec<String> = picked.iter().filter(|f| size(f) > MAX_TORRENT).map(|f| file_name(f)).collect();
        self.status = (!too_big.is_empty()).then(|| (tr("add.bolshe_mb_p", &[("p", &too_big.join(", "))]), false));
        for f in picked.into_iter().filter(|f| size(f) <= MAX_TORRENT) {
            let name = file_name(&f);
            let row = self.push(name.strip_suffix(".torrent").unwrap_or(&name));
            let lib = lib.clone();
            std::thread::spawn(move || {
                let fail = |note: String| {
                    adding::set(&row, &lib, |a| {
                        a.note = note;
                        a.ok = false;
                    })
                };
                let Ok(bytes) = std::fs::read(&f) else { return fail(tr("add.ne_udalos_prochitat_fayl", &[])) };
                if bytes.len() as u64 > MAX_TORRENT {
                    return fail(tr("add.fayl_bolshe_mb", &[]));
                }
                // в раздаче нет видео — не отправляем
                if let Some(r) = torrent::rejection_for_torrent(&bytes) {
                    return fail(r);
                }
                match lib.api().post_torrent(bytes, &name) {
                    Ok(r) => adding::follow(&lib, &row, &r),
                    Err(e) => fail(e.describe()),
                }
            });
        }
    }

    pub fn show(&mut self, ui: &mut egui::Ui, lib: &Library) {
        let dropped: Vec<PathBuf> =
            ui.ctx().input(|i| i.raw.dropped_files.iter().filter_map(|f| f.path.clone()).collect());
        if !dropped.is_empty() {
            self.take_torrents(lib, dropped);
        }
        egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
            egui::Frame::none().inner_margin(28.0).show(ui, |ui| {
                ui.label(RichText::new(tr("add.dobavit_razdachu", &[])).font(theme::heading(22.0)));
                ui.label(RichText::new(tr("add.magnet_ssylka_ili_fayl", &[])).color(theme::MUTED));
                ui.add_space(14.0);
                ui.label(RichText::new(tr("add.film_tekst", &[])).size(12.5).color(theme::MUTED));
                ui.add_space(8.0);
                ui.horizontal(|ui| {
                    let field = ui.add(
                        egui::TextEdit::singleline(&mut self.magnet)
                            .hint_text(tr("audio.magnet_ili_torrent", &[]))
                            .desired_width(ui.available_width() - 260.0),
                    );
                    let enter = field.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
                    if ui.add(egui::Button::new(tr("add.dobavit", &[])).min_size(egui::vec2(100.0, 30.0))).clicked()
                        || enter
                    {
                        self.submit_magnet(lib);
                    }
                    if let Some(files) = pick_torrents(ui) {
                        self.take_torrents(lib, files);
                    }
                });
                if let Some((s, ok)) = &self.status {
                    ui.add_space(10.0);
                    ui.label(RichText::new(s).color(if *ok { OK } else { theme::BAD }));
                }
                if self.added.is_empty() {
                    ui.add_space(22.0);
                    ui.label(RichText::new(tr("add.skachannoe_poyavitsya_vo_vklad", &[])).color(theme::MUTED));
                    return;
                }
                ui.add_space(24.0);
                ui.label(RichText::new(tr("add.dobavleno_seychas", &[])).font(theme::heading(16.0)));
                ui.add_space(8.0);
                for row in &self.added {
                    let a = row.lock().unwrap().clone();
                    added_row(ui, &a);
                }
            });
        });
    }
}

fn file_name(p: &std::path::Path) -> String {
    p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()
}

fn added_row(ui: &mut egui::Ui, a: &Added) {
    egui::Frame::none().fill(theme::BOOTH).rounding(12.0).inner_margin(14.0).stroke((1.0, theme::LINE)).show(
        ui,
        |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                ui.add(egui::Label::new(RichText::new(&a.name).strong()).truncate());
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let color = if !a.ok {
                        theme::BAD
                    } else if a.done {
                        OK
                    } else {
                        Color32::WHITE
                    };
                    ui.label(RichText::new(&a.note).color(color));
                });
            });
            if let (Some(p), false) = (a.progress, a.done) {
                ui.add_space(8.0);
                ui.add(egui::ProgressBar::new(p).desired_height(5.0));
            }
        },
    );
    ui.add_space(6.0);
}

/// «Выбрать файл .torrent»: обычный диалог Windows (можно несколько).
#[cfg(windows)]
fn pick_torrents(ui: &mut egui::Ui) -> Option<Vec<PathBuf>> {
    if !ui.button(tr("add.vybrat_fayl_torrent", &[])).clicked() {
        return None;
    }
    rfd::FileDialog::new().set_title(tr("add.vyberite_torrent", &[])).add_filter("torrent", &["torrent"]).pick_files()
}

/// Не в Windows (сборка для проверок): только перетаскивание.
#[cfg(not(windows))]
fn pick_torrents(_ui: &mut egui::Ui) -> Option<Vec<PathBuf>> {
    None
}
