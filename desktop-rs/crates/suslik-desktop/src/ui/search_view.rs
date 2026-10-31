//! «Поиск раздач» (`SearchView.kt`): один запрос сразу по трекерам сервера, фильтры и пресеты, «В библиотеку».
//! Поиск RuTracker с самого ПК (через Edge) переедет позже — пока только трекеры сервера.

use crate::adding::{self, Added, Row};
use crate::library::{now_ms, Library};
use crate::store::Store;
use crate::strings::tr;
use crate::theme;
use eframe::egui::{self, Color32, RichText};
use serde_json::json;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use suslik_core::model::format_bytes;
use suslik_core::search::{
    clean_query, is_non_video, FilterPreset, Resolution, SearchFilterCriteria, SearchHit, SearchProviderInfo,
    SearchSort,
};
use suslik_core::url_codec;

const OK: Color32 = Color32::from_rgb(0x6E, 0xE7, 0xA8);

/// Какие трекеры включены — те же ключи `local_trackers.properties`, что у Kotlin-версии («Трекеры»).
fn tracker_on(id: &str) -> bool {
    let store = Store::of("local_trackers");
    if id == "rutracker" {
        store.get("rutracker.enabled").as_deref() == Some("true")
    } else {
        store.get(&format!("tracker.{id}.enabled")).as_deref() != Some("false")
    }
}

fn tracker_of(name: &str) -> Option<&'static str> {
    let s = name.to_lowercase();
    [
        ("rutor", "rutor"),
        ("nnm", "nnmclub"),
        ("tpb", "tpb"),
        ("pirate", "tpb"),
        ("nyaa", "nyaa"),
        ("rutracker", "rutracker"),
    ]
    .into_iter()
    .find(|(k, _)| s.contains(k))
    .map(|(_, id)| id)
}

fn provider_on(name: &str) -> bool {
    tracker_of(name).is_none_or(tracker_on)
}

/// Находка из нескольких источников («Rutor + NNM-Club») видна, если включён хоть один из них.
fn source_on(source: &str) -> bool {
    let s = source.to_lowercase();
    let ids: Vec<&str> = [
        ("rutor", "rutor"),
        ("nnm", "nnmclub"),
        ("tpb", "tpb"),
        ("pirate", "tpb"),
        ("nyaa", "nyaa"),
        ("rutracker", "rutracker"),
    ]
    .into_iter()
    .filter(|(k, _)| s.contains(k))
    .map(|(_, id)| id)
    .collect();
    ids.is_empty() || ids.into_iter().any(tracker_on)
}

#[derive(Default)]
struct Found {
    busy: bool,
    status: Option<(String, bool)>,
    hits: Vec<SearchHit>,
    providers: Vec<SearchProviderInfo>,
    active_query: String,
}

pub struct SearchView {
    query: String,
    found: Arc<Mutex<Found>>,
    criteria: SearchFilterCriteria,
    page: usize,
    page_size: usize,
    notes: HashMap<String, Row>,
}

impl Default for SearchView {
    fn default() -> Self {
        Self {
            query: String::new(),
            found: Default::default(),
            criteria: SearchFilterCriteria::default(),
            page: 0,
            page_size: 25,
            notes: HashMap::new(),
        }
    }
}

impl SearchView {
    fn run(&mut self, lib: &Library) {
        let raw = self.query.trim().to_string();
        let mut f = self.found.lock().unwrap();
        if raw.chars().count() < 2 || f.busy {
            f.status = Some((tr("search.vvedite_minimum_simvola", &[]), false));
            return;
        }
        let q = Some(clean_query(&raw)).filter(|q| !q.trim().is_empty()).unwrap_or(raw);
        f.busy = true;
        f.status = Some((tr("search.oprashivaem_trekery", &[]), true));
        f.active_query = q.clone();
        drop(f);
        self.page = 0;
        let (found, lib) = (self.found.clone(), lib.clone());
        std::thread::spawn(move || {
            let t0 = now_ms();
            let res = lib.api().get(&format!("/api/search?q={}", url_codec::encode(&q)));
            let mut f = found.lock().unwrap();
            f.busy = false;
            match res {
                Ok(j) => {
                    f.providers = SearchHit::parse_providers(j.get("providers"))
                        .into_iter()
                        .filter(|p| provider_on(&p.name))
                        .collect();
                    f.hits = SearchHit::parse_hits(j.get("results"))
                        .into_iter()
                        .filter(|h| !is_non_video(&h.title, "") && source_on(&h.source))
                        .collect();
                    let failed = f.providers.iter().filter(|p| !p.ok).count();
                    let sec = format!("{:.1}", (now_ms() - t0) as f64 / 1000.0).replace('.', ",");
                    f.status = Some(if f.hits.is_empty() {
                        (tr("search.nichego_ne_naydeno", &[]), false)
                    } else {
                        let mut s = tr("search.naydeno_p_za_p", &[("total", &f.hits.len().to_string()), ("sec", &sec)]);
                        if failed > 0 {
                            s += &tr("search.ne_otvetili_failed", &[("failed", &failed.to_string())]);
                        }
                        (s, true)
                    });
                }
                Err(e) => f.status = Some((e.describe(), false)),
            }
            drop(f);
            lib.repaint_now();
        });
    }

    fn add(&mut self, lib: &Library, h: &SearchHit) {
        let row: Row = Arc::new(Mutex::new(Added { note: tr("search.dobavlyayu", &[]), ..Added::new(&h.title) }));
        self.notes.insert(h.id.clone(), row.clone());
        let (lib, id) = (lib.clone(), h.id.clone());
        std::thread::spawn(move || match lib.api().post("/api/search/add", Some(&json!({ "id": id }))) {
            Ok(r) => adding::follow(&lib, &row, &r),
            Err(e) => adding::set(&row, &lib, |a| {
                a.note = e.describe();
                a.ok = false;
            }),
        });
    }

    pub fn show(&mut self, ui: &mut egui::Ui, lib: &Library) {
        egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
            egui::Frame::none().inner_margin(28.0).show(ui, |ui| {
                ui.label(RichText::new(tr("search.poisk_razdach", &[])).font(theme::heading(22.0)));
                ui.label(RichText::new(tr("search.odin_zapros_srazu_po", &[])).color(theme::MUTED));
                ui.add_space(12.0);
                let busy = self.found.lock().unwrap().busy;
                ui.horizontal(|ui| {
                    let field = ui.add(
                        egui::TextEdit::singleline(&mut self.query)
                            .hint_text(tr("search.nazvanie_filma_ili_seriala", &[]))
                            .desired_width(ui.available_width() - 110.0),
                    );
                    let enter = field.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
                    let label = tr(if busy { "search.ischem" } else { "search.nayti" }, &[]);
                    if ui.add_enabled(!busy, egui::Button::new(label).min_size(egui::vec2(96.0, 30.0))).clicked()
                        || enter
                    {
                        self.run(lib);
                    }
                });
                let (status, providers, hits, active) = {
                    let f = self.found.lock().unwrap();
                    (f.status.clone(), f.providers.clone(), f.hits.clone(), f.active_query.clone())
                };
                if !providers.is_empty() {
                    ui.add_space(8.0);
                    ui.horizontal_wrapped(|ui| {
                        for p in &providers {
                            let (text, color) = if p.ok {
                                (format!("✔ {} · {}", p.name, p.count), OK)
                            } else {
                                let err: String = p.error.chars().take(60).collect();
                                (
                                    if err.trim().is_empty() {
                                        format!("✖ {}", p.name)
                                    } else {
                                        format!("✖ {}: {err}", p.name)
                                    },
                                    theme::BAD,
                                )
                            };
                            ui.label(RichText::new(text).size(12.0).color(color));
                        }
                    });
                }
                if let Some((s, ok)) = status {
                    ui.add_space(6.0);
                    ui.label(RichText::new(s).color(if ok { OK } else { theme::BAD }));
                }
                if hits.is_empty() {
                    return;
                }
                let before = self.criteria.clone();
                self.filters(ui);
                self.criteria.query = active;
                if self.criteria != before {
                    self.page = 0;
                }
                let shown = self.criteria.filter(&hits);
                if hits.len() != shown.len() {
                    ui.label(
                        RichText::new(tr(
                            "search.skryto_filtrami_p",
                            &[("p", &(hits.len() - shown.len()).to_string())],
                        ))
                        .size(12.5)
                        .color(theme::MUTED),
                    );
                }
                ui.add_space(10.0);
                let pages = shown.len().div_ceil(self.page_size).max(1);
                self.page = self.page.min(pages - 1);
                for h in shown.iter().skip(self.page * self.page_size).take(self.page_size) {
                    self.hit_row(ui, lib, h);
                }
                if pages > 1 {
                    ui.add_space(8.0);
                    ui.horizontal(|ui| {
                        if ui.add_enabled(self.page > 0, egui::Button::new("‹")).clicked() {
                            self.page -= 1;
                        }
                        ui.label(format!("{} / {pages}", self.page + 1));
                        if ui.add_enabled(self.page + 1 < pages, egui::Button::new("›")).clicked() {
                            self.page += 1;
                        }
                    });
                }
            });
        });
    }

    fn filters(&mut self, ui: &mut egui::Ui) {
        let c = &mut self.criteria;
        ui.add_space(10.0);
        ui.horizontal_wrapped(|ui| {
            for (k, key) in [
                ("video", "search.filmy_i_serialy"),
                ("movie", "search.tolko_filmy"),
                ("cartoon", "search.tolko_multfilmy"),
                ("series", "search.tolko_serialy"),
                ("all", "search.vse"),
            ] {
                if ui.selectable_label(c.kind == k, tr(key, &[])).clicked() {
                    c.kind = k.into();
                }
            }
            ui.checkbox(&mut c.only_seeded, tr("search.tolko_s_sidami", &[]));
            ui.checkbox(&mut c.only_ru, tr("search.only_ru", &[]));
            ui.add(
                egui::TextEdit::singleline(&mut c.refine)
                    .hint_text(tr("search.utochnit_p_p_rus", &[]))
                    .desired_width(200.0),
            );
        });
        ui.horizontal_wrapped(|ui| {
            ui.label(RichText::new(tr("search.preset", &[])).color(theme::MUTED));
            for p in FilterPreset::ALL {
                ui.selectable_value(&mut c.preset, p, p.title()).on_hover_text(p.description());
            }
            ui.add_space(8.0);
            ui.label(RichText::new(tr("search.quality", &[])).color(theme::MUTED));
            ui.selectable_value(&mut c.resolution, None, tr("search.any_quality", &[]));
            for r in [Resolution::Uhd4k, Resolution::Fhd1080p, Resolution::Hd720p] {
                ui.selectable_value(&mut c.resolution, Some(r), r.label());
            }
            ui.add_space(8.0);
            ui.label(RichText::new(tr("search.sort", &[])).color(theme::MUTED));
            for (s, key) in [
                (SearchSort::Default, "search.sort_default"),
                (SearchSort::SizeAsc, "search.sort_size_asc"),
                (SearchSort::SeedsDesc, "search.sort_seeds_desc"),
            ] {
                ui.selectable_value(&mut c.sort, s, tr(key, &[]));
            }
        });
    }

    fn hit_row(&mut self, ui: &mut egui::Ui, lib: &Library, h: &SearchHit) {
        let note = self.notes.get(&h.id).map(|r| r.lock().unwrap().clone());
        let mut add = false;
        egui::Frame::none().fill(theme::BOOTH).rounding(12.0).inner_margin(14.0).show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                let right = 150.0;
                ui.vertical(|ui| {
                    ui.set_width(ui.available_width() - right);
                    let clean = &h.release.clean_title;
                    let title = if clean.trim().is_empty() { &h.title } else { clean };
                    ui.label(RichText::new(title).strong().size(15.0));
                    if !clean.trim().is_empty() && clean != &h.title {
                        ui.add(egui::Label::new(RichText::new(&h.title).size(12.0).color(theme::MUTED)).truncate());
                    }
                    let badges = h.release.badges();
                    if !badges.is_empty() {
                        ui.horizontal_wrapped(|ui| {
                            for b in badges {
                                egui::Frame::none()
                                    .fill(theme::FILM)
                                    .rounding(6.0)
                                    .inner_margin(egui::Margin::symmetric(7.0, 2.0))
                                    .show(ui, |ui| {
                                        ui.label(RichText::new(b).size(11.5).color(Color32::from_rgb(0xC7, 0xD0, 0xE0)))
                                    });
                            }
                        });
                    }
                    ui.horizontal_wrapped(|ui| {
                        if h.size > 0 {
                            ui.label(RichText::new(format_bytes(h.size)).strong());
                        }
                        ui.label(RichText::new(format!("↑ {}", h.seeders)).color(if h.seeders > 0 {
                            OK
                        } else {
                            theme::MUTED
                        }));
                        ui.label(RichText::new(format!("↓ {}", h.leechers)).color(theme::MUTED));
                        if h.ru {
                            ui.label(RichText::new("RU").strong().size(11.0));
                        }
                        let kind = match h.kind.as_str() {
                            "series" => Some("search.serial"),
                            "movie" => Some("search.film"),
                            "cartoon" => Some("search.multfilm"),
                            _ => None,
                        };
                        if let Some(k) = kind {
                            ui.label(RichText::new(tr(k, &[])).color(theme::MUTED));
                        }
                        ui.label(RichText::new(&h.source).color(theme::MUTED));
                        if !h.date.is_empty() {
                            ui.label(RichText::new(&h.date).color(theme::MUTED));
                        }
                    });
                    if let Some(a) = &note {
                        let color = if a.ok { OK } else { theme::BAD };
                        ui.label(RichText::new(&a.note).color(color));
                    }
                });
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let can = note.as_ref().is_none_or(|a| !a.ok);
                    let b = egui::Button::new(RichText::new(tr("search.v_biblioteku", &[])).color(theme::NIGHT))
                        .fill(theme::LAMP);
                    add = ui.add_enabled(can, b).clicked();
                });
            });
        });
        ui.add_space(4.0);
        if add {
            self.add(lib, h);
        }
    }
}
