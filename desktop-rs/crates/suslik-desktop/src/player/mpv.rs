//! Минимальная привязка к libmpv (client.h и render.h), как `MpvLib.kt`. Библиотека грузится при первом обращении:
//! `libmpv-2.dll` рядом с приложением, в `mpv\` рядом, в `%APPDATA%\SuslikMedia\mpv\`, в `SUSLIK_MPV_DIR` или из PATH
//! (на Linux для разработки — `libmpv.so.2`). Пока библиотеки нет, каждый вызов ищет её снова: её могут докачать.

use libloading::Library;
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

pub const FORMAT_FLAG: c_int = 3;
pub const FORMAT_INT64: c_int = 4;
pub const FORMAT_DOUBLE: c_int = 5;

pub const EV_SHUTDOWN: c_int = 1;
pub const EV_END_FILE: c_int = 7;
pub const EV_FILE_LOADED: c_int = 8;
pub const EV_PLAYBACK_RESTART: c_int = 21;
pub const EV_PROPERTY_CHANGE: c_int = 22;

pub const END_EOF: c_int = 0;
pub const END_ERROR: c_int = 4;

pub const RENDER_PARAM_API_TYPE: c_int = 1;
pub const RENDER_PARAM_OPENGL_INIT_PARAMS: c_int = 2;
pub const RENDER_PARAM_OPENGL_FBO: c_int = 3;
pub const RENDER_PARAM_FLIP_Y: c_int = 4;
pub const RENDER_PARAM_BLOCK_FOR_TARGET_TIME: c_int = 12;
pub const RENDER_UPDATE_FRAME: u64 = 1;

pub type Handle = *mut c_void;
pub type RenderCtx = *mut c_void;
pub type UpdateCallback = unsafe extern "C" fn(*mut c_void);
pub type GetProcAddress = unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_void;

#[repr(C)]
pub struct Event {
    pub event_id: c_int,
    pub error: c_int,
    pub reply_userdata: u64,
    pub data: *mut c_void,
}

#[repr(C)]
pub struct EventProperty {
    pub name: *const c_char,
    pub format: c_int,
    pub data: *mut c_void,
}

#[repr(C)]
pub struct EventEndFile {
    pub reason: c_int,
    pub error: c_int,
}

#[repr(C)]
pub struct RenderParam {
    pub kind: c_int,
    pub data: *mut c_void,
}

#[repr(C)]
pub struct OpenGlInitParams {
    pub get_proc_address: GetProcAddress,
    pub get_proc_address_ctx: *mut c_void,
}

#[repr(C)]
pub struct OpenGlFbo {
    pub fbo: c_int,
    pub w: c_int,
    pub h: c_int,
    pub internal_format: c_int,
}

/// Функции libmpv. Указатели живут, пока жива `_lib` (её не выгружаем до выхода).
pub struct Api {
    _lib: Library,
    pub file: PathBuf,
    pub create: unsafe extern "C" fn() -> Handle,
    pub initialize: unsafe extern "C" fn(Handle) -> c_int,
    pub terminate_destroy: unsafe extern "C" fn(Handle),
    pub set_option_string: unsafe extern "C" fn(Handle, *const c_char, *const c_char) -> c_int,
    pub set_property_string: unsafe extern "C" fn(Handle, *const c_char, *const c_char) -> c_int,
    pub get_property_string: unsafe extern "C" fn(Handle, *const c_char) -> *mut c_char,
    pub free: unsafe extern "C" fn(*mut c_void),
    pub command: unsafe extern "C" fn(Handle, *mut *const c_char) -> c_int,
    pub observe_property: unsafe extern "C" fn(Handle, u64, *const c_char, c_int) -> c_int,
    pub wait_event: unsafe extern "C" fn(Handle, f64) -> *mut Event,
    pub wakeup: unsafe extern "C" fn(Handle),
    pub error_string: unsafe extern "C" fn(c_int) -> *const c_char,
    pub render_context_create: unsafe extern "C" fn(*mut RenderCtx, Handle, *mut RenderParam) -> c_int,
    pub render_context_set_update_callback: unsafe extern "C" fn(RenderCtx, Option<UpdateCallback>, *mut c_void),
    pub render_context_update: unsafe extern "C" fn(RenderCtx) -> u64,
    pub render_context_render: unsafe extern "C" fn(RenderCtx, *mut RenderParam) -> c_int,
    pub render_context_free: unsafe extern "C" fn(RenderCtx),
}

unsafe impl Send for Api {}
unsafe impl Sync for Api {}

#[cfg(windows)]
const NAMES: &[&str] = &["libmpv-2.dll", "mpv-2.dll"];
#[cfg(not(windows))]
const NAMES: &[&str] = &["libmpv.so.2", "libmpv.so"];

/// Папки, где ищем библиотеку (по порядку).
fn dirs() -> Vec<PathBuf> {
    let mut d = Vec::new();
    if let Some(exe_dir) = std::env::current_exe().ok().and_then(|e| e.parent().map(|p| p.to_path_buf())) {
        d.push(exe_dir.join("resources"));
        d.push(exe_dir.clone());
        d.push(exe_dir.join("mpv"));
    }
    d.push(crate::app_dir::dir().join("mpv"));
    if let Some(e) = std::env::var_os("SUSLIK_MPV_DIR") {
        d.push(PathBuf::from(e));
    }
    d
}

fn load() -> Option<Api> {
    let mut tries: Vec<PathBuf> = Vec::new();
    for dir in dirs() {
        for n in NAMES {
            let f = dir.join(n);
            if f.is_file() {
                tries.push(f);
            }
        }
    }
    // из PATH / системных папок — по имени
    tries.extend(NAMES.iter().map(PathBuf::from));
    for f in tries {
        // Безопасность: библиотеку берём из папок приложения или системы, как Kotlin-версия.
        let Ok(lib) = (unsafe { Library::new(&f) }) else { continue };
        if let Some(api) = unsafe { bind(lib, f) } {
            return Some(api);
        }
    }
    None
}

unsafe fn bind(lib: Library, file: PathBuf) -> Option<Api> {
    macro_rules! sym {
        ($n:literal) => {
            *lib.get(concat!($n, "\0").as_bytes()).ok()?
        };
    }
    Some(Api {
        file,
        create: sym!("mpv_create"),
        initialize: sym!("mpv_initialize"),
        terminate_destroy: sym!("mpv_terminate_destroy"),
        set_option_string: sym!("mpv_set_option_string"),
        set_property_string: sym!("mpv_set_property_string"),
        get_property_string: sym!("mpv_get_property_string"),
        free: sym!("mpv_free"),
        command: sym!("mpv_command"),
        observe_property: sym!("mpv_observe_property"),
        wait_event: sym!("mpv_wait_event"),
        wakeup: sym!("mpv_wakeup"),
        error_string: sym!("mpv_error_string"),
        render_context_create: sym!("mpv_render_context_create"),
        render_context_set_update_callback: sym!("mpv_render_context_set_update_callback"),
        render_context_update: sym!("mpv_render_context_update"),
        render_context_render: sym!("mpv_render_context_render"),
        render_context_free: sym!("mpv_render_context_free"),
        _lib: lib,
    })
}

static API: Mutex<Option<Arc<Api>>> = Mutex::new(None);

/// libmpv, если найдена.
pub fn api() -> Option<Arc<Api>> {
    let mut g = API.lock().unwrap_or_else(|e| e.into_inner());
    if g.is_none() {
        *g = load().map(Arc::new);
    }
    g.clone()
}

pub fn cstr(s: &str) -> CString {
    CString::new(s.replace('\0', "")).unwrap_or_default()
}

impl Api {
    pub fn error_text(&self, code: c_int) -> String {
        unsafe {
            let p = (self.error_string)(code);
            if p.is_null() {
                format!("mpv error {code}")
            } else {
                CStr::from_ptr(p).to_string_lossy().into_owned()
            }
        }
    }

    pub fn set_option(&self, h: Handle, name: &str, value: &str) -> c_int {
        let (n, v) = (cstr(name), cstr(value));
        unsafe { (self.set_option_string)(h, n.as_ptr(), v.as_ptr()) }
    }

    pub fn set_property(&self, h: Handle, name: &str, value: &str) -> c_int {
        let (n, v) = (cstr(name), cstr(value));
        unsafe { (self.set_property_string)(h, n.as_ptr(), v.as_ptr()) }
    }

    pub fn get_property(&self, h: Handle, name: &str) -> Option<String> {
        let n = cstr(name);
        unsafe {
            let p = (self.get_property_string)(h, n.as_ptr());
            if p.is_null() {
                return None;
            }
            let s = CStr::from_ptr(p).to_string_lossy().into_owned();
            (self.free)(p as *mut c_void);
            Some(s)
        }
    }

    pub fn run(&self, h: Handle, args: &[&str]) -> c_int {
        let owned: Vec<CString> = args.iter().map(|a| cstr(a)).collect();
        let mut ptrs: Vec<*const c_char> = owned.iter().map(|c| c.as_ptr()).collect();
        ptrs.push(std::ptr::null());
        unsafe { (self.command)(h, ptrs.as_mut_ptr()) }
    }

    pub fn observe(&self, h: Handle, id: u64, name: &str, format: c_int) {
        let n = cstr(name);
        unsafe {
            (self.observe_property)(h, id, n.as_ptr(), format);
        }
    }
}
