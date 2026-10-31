//! Поиск раздач: разбор названия (`ReleaseParser`), находка (`SearchHit`) и фильтр. Повторяет `core/search/`.

mod filter;
mod hit;
mod parser;
mod release;

pub use filter::*;
pub use hit::*;
pub use parser::*;
pub use release::*;

/// Длина строки так, как её считает Kotlin (UTF-16): пороги в правилах заданы в этих единицах.
pub(crate) fn kt_len(s: &str) -> usize {
    s.encode_utf16().count()
}
