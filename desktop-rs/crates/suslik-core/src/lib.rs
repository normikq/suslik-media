//! Общая логика Suslik Media для ПК-версии на Rust.
//!
//! Перенос модуля `shared/` (Kotlin) по частям: каждый файл здесь повторяет одноимённый объект
//! из `shared/src/main/kotlin/ru/suslikovanora/media/core/`, с теми же правилами и тестами.
//! Пока обе версии живут рядом, правила надо менять в обеих.

pub mod changelog;
pub mod jrx;
mod json;
pub mod mirror_link;
pub mod model;
pub mod playback_presets;
pub mod search;
pub mod site_address;
pub mod time_text;
pub mod url_codec;
pub mod versions;

pub use time_text::clock_text;
