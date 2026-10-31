//! Экраны окна: вход, боковая панель, медиатека (`LibraryView.kt`, `Sidebar.kt`).

pub mod add_view;
pub mod library_view;
pub mod login;
pub mod search_view;
pub mod sidebar;

/// Разделы боковой панели (`Tab` у Kotlin-версии).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Tab {
    Library,
    Mine,
    Search,
    Add,
}
