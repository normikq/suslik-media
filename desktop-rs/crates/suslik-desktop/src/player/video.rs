//! Вывод mpv в сцене окна egui (как `MpvVideo.kt`): mpv рисует кадр в нашу текстуру через render API в том же
//! GL-контексте, которым eframe рисует окно; картинка — обычное изображение egui под панелями плеера.
//!
//! Текстура и её кадровый буфер — одни на приложение (живут между фильмами); render context — свой у каждого ядра mpv.
//! Колбэк обновления из потоков mpv только будит окно; `mpv_render_context_update` и отрисовка — в потоке окна.
//! Перед mpv состояние GL приводится к значениям по умолчанию (egui оставляет включённые ножницы, смешивание и свой
//! VAO), после — кадровый буфер окна (0): egui рисует в него.

use super::mpv::{self, Api, Handle, RenderCtx};
use eframe::egui;
use eframe::glow::{self, HasContext};
use std::ffi::{c_char, c_int, c_void, CStr};
use std::sync::atomic::{AtomicBool, Ordering::SeqCst};
use std::sync::{Arc, OnceLock};

/// Текстура видео в egui и её кадровый буфер.
pub struct Surface {
    tex: glow::Texture,
    fbo: glow::Framebuffer,
    pub id: egui::TextureId,
    size: (i32, i32),
}

impl Surface {
    pub fn new(gl: &glow::Context, frame: &mut eframe::Frame) -> Option<Surface> {
        unsafe {
            let tex = gl.create_texture().ok()?;
            gl.bind_texture(glow::TEXTURE_2D, Some(tex));
            gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MIN_FILTER, glow::LINEAR as i32);
            gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MAG_FILTER, glow::LINEAR as i32);
            gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_WRAP_S, glow::CLAMP_TO_EDGE as i32);
            gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_WRAP_T, glow::CLAMP_TO_EDGE as i32);
            gl.bind_texture(glow::TEXTURE_2D, None);
            let fbo = gl.create_framebuffer().ok()?;
            let id = frame.register_native_glow_texture(tex);
            Some(Surface { tex, fbo, id, size: (0, 0) })
        }
    }

    fn resize(&mut self, gl: &glow::Context, w: i32, h: i32) {
        if self.size == (w, h) {
            return;
        }
        self.size = (w, h);
        unsafe {
            gl.bind_texture(glow::TEXTURE_2D, Some(self.tex));
            gl.tex_image_2d(glow::TEXTURE_2D, 0, glow::RGBA8 as i32, w, h, 0, glow::RGBA, glow::UNSIGNED_BYTE, None);
            gl.bind_texture(glow::TEXTURE_2D, None);
            gl.bind_framebuffer(glow::FRAMEBUFFER, Some(self.fbo));
            gl.framebuffer_texture_2d(glow::FRAMEBUFFER, glow::COLOR_ATTACHMENT0, glow::TEXTURE_2D, Some(self.tex), 0);
            gl.clear_color(0.0, 0.0, 0.0, 1.0);
            gl.clear(glow::COLOR_BUFFER_BIT);
            gl.bind_framebuffer(glow::FRAMEBUFFER, None);
        }
    }
}

struct Wake {
    dirty: AtomicBool,
    ctx: egui::Context,
}

unsafe extern "C" fn on_update(data: *mut c_void) {
    // потоки mpv: только будим окно, mpv отсюда не зовём
    let w = &*(data as *const Wake);
    w.dirty.store(true, SeqCst);
    w.ctx.request_repaint();
}

/// `get_proc_address` для mpv: функции GL того же контекста, что у окна.
unsafe extern "C" fn proc_address(_ctx: *mut c_void, name: *const c_char) -> *mut c_void {
    gl_proc(CStr::from_ptr(name))
}

#[cfg(windows)]
unsafe fn gl_proc(name: &CStr) -> *mut c_void {
    static LIB: OnceLock<Option<libloading::Library>> = OnceLock::new();
    let Some(lib) = LIB.get_or_init(|| libloading::Library::new("opengl32.dll").ok()) else {
        return std::ptr::null_mut();
    };
    type Wgl = unsafe extern "system" fn(*const c_char) -> *mut c_void;
    if let Ok(wgl) = lib.get::<Wgl>(b"wglGetProcAddress\0") {
        let p = wgl(name.as_ptr());
        // 0, 1, 2, 3, -1 — «нет» (для функций GL 1.1 их отдаёт сама opengl32.dll)
        if !matches!(p as isize, -1..=3) {
            return p;
        }
    }
    lib.get::<*mut c_void>(name.to_bytes_with_nul()).map(|s| *s).unwrap_or(std::ptr::null_mut())
}

#[cfg(not(windows))]
unsafe fn gl_proc(name: &CStr) -> *mut c_void {
    // Только для разработки на Linux. glutin в eframe на X11 берёт GLX; libEGL не трогаем, пока GLX отвечает:
    // указатели EGL при контексте GLX давали то кадр, то чёрный экран.
    static GLX: OnceLock<Option<libloading::Library>> = OnceLock::new();
    static EGL: OnceLock<Option<libloading::Library>> = OnceLock::new();
    type Get = unsafe extern "C" fn(*const c_char) -> *mut c_void;
    let libs =
        [(&GLX, "libGL.so.1", &b"glXGetProcAddressARB\0"[..]), (&EGL, "libEGL.so.1", &b"eglGetProcAddress\0"[..])];
    for (cell, file, sym) in libs {
        if let Some(l) = cell.get_or_init(|| libloading::Library::new(file).ok()) {
            if let Ok(f) = l.get::<Get>(sym) {
                let p = f(name.as_ptr());
                if !p.is_null() {
                    return p;
                }
            }
        }
    }
    std::ptr::null_mut()
}

/// Строка `GL_RENDERER` и признак программного OpenGL.
pub fn gl_renderer(gl: &glow::Context) -> (String, bool) {
    let r = unsafe { gl.get_parameter_string(glow::RENDERER) };
    let l = r.to_lowercase();
    let soft =
        ["llvmpipe", "softpipe", "swrast", "gdi generic", "microsoft basic render"].iter().any(|s| l.contains(s));
    (r, soft)
}

/// Render context одного ядра mpv.
pub struct VideoOut {
    api: Arc<Api>,
    ctx: RenderCtx,
    wake: *const Wake,
    pub has_frame: bool,
}

impl VideoOut {
    /// Поток окна, до первого `loadfile`.
    pub fn start(gl: &glow::Context, api: Arc<Api>, h: Handle, egui_ctx: egui::Context) -> Result<VideoOut, String> {
        // mpv при создании заводит свои текстуры и запоминает часть состояния: тоже с чистого листа
        unsafe { defaults_for_mpv(gl) };
        let mut init =
            mpv::OpenGlInitParams { get_proc_address: proc_address, get_proc_address_ctx: std::ptr::null_mut() };
        let api_type = c"opengl";
        let mut params = [
            mpv::RenderParam { kind: mpv::RENDER_PARAM_API_TYPE, data: api_type.as_ptr() as *mut c_void },
            mpv::RenderParam { kind: mpv::RENDER_PARAM_OPENGL_INIT_PARAMS, data: &mut init as *mut _ as *mut c_void },
            mpv::RenderParam { kind: 0, data: std::ptr::null_mut() },
        ];
        let mut ctx: RenderCtx = std::ptr::null_mut();
        let rc = unsafe { (api.render_context_create)(&mut ctx, h, params.as_mut_ptr()) };
        unsafe { gl.bind_framebuffer(glow::FRAMEBUFFER, None) };
        if rc < 0 || ctx.is_null() {
            return Err(format!("mpv_render_context_create: {}", api.error_text(rc)));
        }
        let wake = Arc::into_raw(Arc::new(Wake { dirty: AtomicBool::new(true), ctx: egui_ctx }));
        unsafe { (api.render_context_set_update_callback)(ctx, Some(on_update), wake as *mut c_void) };
        Ok(VideoOut { api, ctx, wake, has_frame: false })
    }

    /// Каждый кадр окна: если у mpv новый кадр (или изменился размер), он рисуется в текстуру `surface`.
    pub fn render(&mut self, gl: &glow::Context, surface: &mut Surface, w: i32, h: i32) {
        if w <= 0 || h <= 0 {
            return;
        }
        let resized = surface.size != (w, h);
        surface.resize(gl, w, h);
        let wake = unsafe { &*self.wake };
        if !wake.dirty.swap(false, SeqCst) && !resized {
            return;
        }
        let flags = unsafe { (self.api.render_context_update)(self.ctx) };
        if flags & mpv::RENDER_UPDATE_FRAME == 0 && !resized {
            return;
        }
        unsafe {
            defaults_for_mpv(gl);
            let fbo_id = surface.fbo.0.get() as c_int;
            let mut fbo = mpv::OpenGlFbo { fbo: fbo_id, w, h, internal_format: 0 };
            let mut flip: c_int = 0; // текстура egui и кадр mpv уже в одной ориентации
            let mut block: c_int = 0;
            let mut params = [
                mpv::RenderParam { kind: mpv::RENDER_PARAM_OPENGL_FBO, data: &mut fbo as *mut _ as *mut c_void },
                mpv::RenderParam { kind: mpv::RENDER_PARAM_FLIP_Y, data: &mut flip as *mut _ as *mut c_void },
                mpv::RenderParam {
                    kind: mpv::RENDER_PARAM_BLOCK_FOR_TARGET_TIME,
                    data: &mut block as *mut _ as *mut c_void,
                },
                mpv::RenderParam { kind: 0, data: std::ptr::null_mut() },
            ];
            (self.api.render_context_render)(self.ctx, params.as_mut_ptr());
            gl.bind_framebuffer(glow::FRAMEBUFFER, None);
        }
        self.has_frame = true;
    }

    /// Поток окна, до уничтожения ядра.
    pub fn free(self) {
        unsafe {
            (self.api.render_context_set_update_callback)(self.ctx, None, std::ptr::null_mut());
            (self.api.render_context_free)(self.ctx);
            drop(Arc::from_raw(self.wake));
        }
    }
}

/// Состояние GL по умолчанию перед вызовом mpv (как `defaultsForMpv` Kotlin-версии): egui оставляет свои VAO,
/// программу, буферы, ножницы и смешивание; mpv на них не рассчитывает — чёрный кадр или мусор поверх картинки.
unsafe fn defaults_for_mpv(gl: &glow::Context) {
    let units = gl.get_parameter_i32(glow::MAX_COMBINED_TEXTURE_IMAGE_UNITS).clamp(1, 64) as u32;
    for i in 0..units {
        gl.bind_sampler(i, None);
        gl.active_texture(glow::TEXTURE0 + i);
        gl.bind_texture(glow::TEXTURE_2D, None);
    }
    gl.active_texture(glow::TEXTURE0);
    for t in [
        glow::PIXEL_PACK_BUFFER,
        glow::PIXEL_UNPACK_BUFFER,
        glow::ARRAY_BUFFER,
        glow::ELEMENT_ARRAY_BUFFER,
        glow::UNIFORM_BUFFER,
    ] {
        gl.bind_buffer(t, None);
    }
    gl.bind_vertex_array(None);
    gl.use_program(None);
    for p in [
        glow::UNPACK_ROW_LENGTH,
        glow::UNPACK_SKIP_ROWS,
        glow::UNPACK_SKIP_PIXELS,
        glow::PACK_ROW_LENGTH,
        glow::PACK_SKIP_ROWS,
        glow::PACK_SKIP_PIXELS,
    ] {
        gl.pixel_store_i32(p, 0);
    }
    gl.pixel_store_i32(glow::UNPACK_ALIGNMENT, 4);
    gl.pixel_store_i32(glow::PACK_ALIGNMENT, 4);
    for cap in [
        glow::SCISSOR_TEST,
        glow::STENCIL_TEST,
        glow::DEPTH_TEST,
        glow::CULL_FACE,
        glow::BLEND,
        glow::FRAMEBUFFER_SRGB,
        glow::DITHER,
        glow::MULTISAMPLE,
    ] {
        gl.disable(cap);
    }
    gl.color_mask(true, true, true, true);
    for _ in 0..16 {
        if gl.get_error() == glow::NO_ERROR {
            break; // чужие ошибки mpv иначе припишет себе
        }
    }
}
