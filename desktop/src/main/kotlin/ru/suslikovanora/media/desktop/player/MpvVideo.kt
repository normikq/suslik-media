package ru.suslikovanora.media.desktop.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import com.sun.jna.CallbackReference
import com.sun.jna.CallbackThreadInitializer
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import ru.suslikovanora.media.desktop.PlayerLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/*
 * Видео mpv внутри сцены Compose (одно окно, без холста и окон-оверлеев).
 *
 * Skiko рисует окно через OpenGL (-Dskiko.renderApi=OPENGL) на потоке интерфейса и не отпускает свой контекст.
 * mpv (vo=libmpv, render API) рисует кадр в нашу текстуру в ЭТОМ же контексте, тоже на потоке интерфейса;
 * Skia копирует её на видеокарте в свою картинку, а Compose рисует картинку под панелями. Процессор кадр не трогает,
 * аппаратное декодирование остаётся. Колбэк mpv только будит отрисовку (invokeLater), рисуем не из него.
 */

/** Минимум OpenGL/WGL: opengl32.dll даёт GL 1.1, остальное (кадровые буферы) — через wglGetProcAddress. */
internal object Gl {
    interface Lib : Library {
        fun wglGetProcAddress(name: String): Pointer?
        fun wglMakeCurrent(hdc: Pointer?, hglrc: Pointer?): Boolean
        fun wglGetCurrentContext(): Pointer?
        fun glGenTextures(n: Int, textures: IntArray)
        fun glDeleteTextures(n: Int, textures: IntArray)
        fun glBindTexture(target: Int, texture: Int)
        fun glTexParameteri(target: Int, pname: Int, param: Int)
        fun glTexImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, pixels: Pointer?)
        fun glGetError(): Int
        fun glGetIntegerv(pname: Int, out: IntArray)
        fun glPixelStorei(pname: Int, param: Int)
        fun glDisable(cap: Int)
        fun glColorMask(r: Byte, g: Byte, b: Byte, a: Byte)
    }

    const val TEXTURE_2D = 0x0DE1
    const val RGBA8 = 0x8058
    const val RGBA = 0x1908
    const val UNSIGNED_BYTE = 0x1401
    const val TEXTURE_MIN_FILTER = 0x2801
    const val TEXTURE_MAG_FILTER = 0x2800
    const val TEXTURE_WRAP_S = 0x2802
    const val TEXTURE_WRAP_T = 0x2803
    const val LINEAR = 0x2601
    const val CLAMP_TO_EDGE = 0x812F
    const val FRAMEBUFFER = 0x8D40
    const val COLOR_ATTACHMENT0 = 0x8CE0
    const val FRAMEBUFFER_COMPLETE = 0x8CD5

    val lib: Lib? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) null
        else runCatching { Native.load("opengl32", Lib::class.java) }.getOrNull()
    }
    private val opengl32: NativeLibrary? by lazy { runCatching { NativeLibrary.getInstance("opengl32") }.getOrNull() }

    /** Адрес функции GL: расширения и GL > 1.1 из wglGetProcAddress, остальное — экспорт opengl32.dll. */
    fun proc(name: String): Pointer? {
        val p = lib?.wglGetProcAddress(name)
        val v = if (p == null) 0L else Pointer.nativeValue(p)
        if (v !in -1L..3L) return p // 0, 1, 2, 3 и -1 — «нет функции» у разных драйверов
        return runCatching { opengl32?.getFunction(name) }.getOrNull()
    }

    private val fns = HashMap<String, Function>()
    private fun fn(name: String): Function = fns.getOrPut(name) { Function.getFunction(proc(name) ?: error("OpenGL: нет $name")) }

    fun genFramebuffer(): Int = IntArray(1).also { fn("glGenFramebuffers").invokeVoid(arrayOf(1, it)) }[0]
    fun deleteFramebuffer(id: Int) = fn("glDeleteFramebuffers").invokeVoid(arrayOf(1, intArrayOf(id)))
    fun bindFramebuffer(id: Int) = fn("glBindFramebuffer").invokeVoid(arrayOf(FRAMEBUFFER, id))
    fun framebufferTexture(tex: Int) = fn("glFramebufferTexture2D").invokeVoid(arrayOf(FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, tex, 0))
    fun framebufferStatus(): Int = fn("glCheckFramebufferStatus").invokeInt(arrayOf(FRAMEBUFFER))

    /**
     * Состояние GL по умолчанию перед кадром mpv. Skia оставляет свои объекты-сэмплеры на текстурных блоках, буфер
     * распаковки и параметры glPixelStore; mpv на них не рассчитывает: его текстуры (кадр 10 бит, таблица дизеринга,
     * HDR) читались с чужими настройками — чёрный кадр с точками и мусор поверх картинки у HEVC и AV1.
     */
    fun defaultsForMpv() {
        val g = lib ?: return
        val units = IntArray(1).also { g.glGetIntegerv(0x8B4D /* MAX_COMBINED_TEXTURE_IMAGE_UNITS */, it) }[0].coerceIn(1, 64)
        runCatching { val bindSampler = fn("glBindSampler"); for (i in 0 until units) bindSampler.invokeVoid(arrayOf(i, 0)) }
        runCatching {
            val bindBuffer = fn("glBindBuffer")
            // PIXEL_PACK, PIXEL_UNPACK, ARRAY, ELEMENT_ARRAY, UNIFORM, SHADER_STORAGE, DRAW_INDIRECT
            for (t in intArrayOf(0x88EB, 0x88EC, 0x8892, 0x8893, 0x8A11, 0x90D2, 0x8F3F)) bindBuffer.invokeVoid(arrayOf(t, 0))
        }
        runCatching { fn("glBindVertexArray").invokeVoid(arrayOf(0)) }
        runCatching { fn("glUseProgram").invokeVoid(arrayOf(0)) }
        runCatching { fn("glActiveTexture").invokeVoid(arrayOf(0x84C0 /* TEXTURE0 */)) }
        // UNPACK/PACK: ROW_LENGTH, SKIP_ROWS, SKIP_PIXELS = 0, ALIGNMENT = 4
        for (p in intArrayOf(0x0CF2, 0x0CF3, 0x0CF4, 0x0D02, 0x0D03, 0x0D04)) g.glPixelStorei(p, 0)
        g.glPixelStorei(0x0CF5, 4); g.glPixelStorei(0x0D05, 4)
        // SCISSOR, STENCIL, DEPTH, CULL_FACE, BLEND, FRAMEBUFFER_SRGB, DITHER, MULTISAMPLE
        for (c in intArrayOf(0x0C11, 0x0B90, 0x0B71, 0x0B44, 0x0BE2, 0x8DB9, 0x0BD0, 0x809D)) g.glDisable(c)
        g.glColorMask(1, 1, 1, 1)
        for (i in 0 until 16) if (g.glGetError() == 0) break // чужие ошибки mpv иначе припишет себе
    }
}

/**
 * OpenGL-контекст, которым Skiko рисует окно: устройство (HDC), контекст (HGLRC) и DirectContext Skia.
 * Skiko их не публикует, берём из его внутренних полей (Skiko 0.8.x, WindowsOpenGLRedrawer); при другой версии
 * или другом способе отрисовки [find] вернёт null, и плеер останется на VLC.
 */
class SkikoGl private constructor(
    private val layer: SkiaLayer,
    private val redrawer: Any,
    private val hdc: Pointer,
    private val hglrc: Pointer,
    val context: DirectContext
) {
    /** Окно всё ещё рисуется тем же контекстом (Skiko пересоздаёт его, например, при пересоздании окна). */
    val valid: Boolean get() = runCatching { redrawerOf(layer) === redrawer }.getOrDefault(false)

    /** Делает контекст Skiko текущим на этом потоке (поток интерфейса). */
    fun makeCurrent(): Boolean {
        val gl = Gl.lib ?: return false
        if (gl.wglGetCurrentContext() == hglrc) return true
        return gl.wglMakeCurrent(hdc, hglrc)
    }

    companion object {
        private fun field(obj: Any, name: String): Any? {
            var k: Class<*>? = obj.javaClass
            while (k != null) {
                runCatching { return k.getDeclaredField(name).apply { isAccessible = true }.get(obj) }
                k = k.superclass
            }
            return null
        }

        private fun redrawerOf(layer: SkiaLayer): Any? =
            SkiaLayer::class.java.declaredMethods.firstOrNull { it.name.startsWith("getRedrawer") && it.parameterCount == 0 }
                ?.apply { isAccessible = true }?.invoke(layer)

        private fun skiaLayer(c: java.awt.Component): SkiaLayer? {
            if (c is SkiaLayer) return c
            if (c is java.awt.Container) for (child in c.components) skiaLayer(child)?.let { return it }
            return null
        }

        /** Поток интерфейса, после первой отрисовки окна. */
        fun find(window: java.awt.Window?): SkikoGl? {
            if (window == null || Gl.lib == null) return null
            return runCatching {
                val layer = skiaLayer(window) ?: return null
                if (layer.renderApi != GraphicsApi.OPENGL) {
                    PlayerLog.write("[video] Skiko draws with ${layer.renderApi}, not OpenGL: no in-window mpv")
                    return null
                }
                val redrawer = redrawerOf(layer) ?: return null
                val hdc = (field(redrawer, "device") as? Long)?.takeIf { it != 0L } ?: return null
                val hglrc = (field(redrawer, "context") as? Long)?.takeIf { it != 0L } ?: return null
                val handler = field(redrawer, "contextHandler") ?: return null
                val ctx = field(handler, "context") as? DirectContext ?: return null
                SkikoGl(layer, redrawer, Pointer(hdc), Pointer(hglrc), ctx)
            }.onFailure { PlayerLog.write("[video] no Skiko OpenGL context: $it") }.getOrNull()
        }
    }
}

/**
 * Вывод кадров mpv в сцену Compose. Всё, кроме [schedule] и [awaitFreed], — только на потоке интерфейса.
 * Последний кадр — [image] (картинка Skia на видеокарте); [frame] меняется с каждым новым кадром.
 */
class MpvVideoOut(private val gl: SkikoGl) {
    val frame = mutableStateOf(0L)
    var image: Image? = null
        private set

    private var api: MpvApi? = null
    private var ctx: Pointer? = null
    private var tex = 0
    private var fbo = 0
    private var texW = 0
    private var texH = 0
    private var surface: Surface? = null
    private var wantW = 0
    private var wantH = 0
    private var forceNext = false
    private var frames = 0L
    @Volatile private var disposed = false
    private val posted = AtomicBoolean(false)
    private val freed = CountDownLatch(1)

    // Колбэки и память параметров живут столько же, сколько вывод: mpv держит на них указатели.
    private val onUpdate = MpvUpdateCallback { schedule() }
    private val getProc = MpvGetProcAddress { _, name -> name?.getString(0, "US-ASCII")?.let { Gl.proc(it) } }
    private val fboParam = Memory(16)
    private val zero = Memory(4).apply { setInt(0, 0) }
    private val renderParams = params(
        Mpv.RENDER_PARAM_OPENGL_FBO to fboParam,
        Mpv.RENDER_PARAM_FLIP_Y to zero,               // строка 0 — верх кадра: Skia берёт буфер как TOP_LEFT
        Mpv.RENDER_PARAM_BLOCK_FOR_TARGET_TIME to zero // поток интерфейса не ждёт времени кадра
    )

    /**
     * Конец нашей работы с GL: привязан снова буфер окна (0), Skia забывает своё закешированное состояние.
     * Skiko при изменении размера окна берёт под окно тот буфер, что привязан сейчас: оставленный буфер mpv
     * уводил всю отрисовку окна в текстуру видео (чёрный экран после ресайза).
     */
    private fun leaveGl() {
        runCatching { Gl.bindFramebuffer(0) }
        gl.context.resetGLAll()
    }

    private fun params(vararg p: Pair<Int, Pointer?>): Memory {
        val m = Memory(16L * (p.size + 1))
        m.clear()
        p.forEachIndexed { i, (type, data) -> m.setInt(16L * i, type); m.setPointer(16L * i + 8, data) }
        return m
    }

    /** Создаёт render context mpv в контексте Skiko. До первого loadfile: без него vo=libmpv не откроется. */
    fun start(api: MpvApi, mpv: Pointer): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        if (disposed || ctx != null) return ctx != null
        if (!gl.makeCurrent()) { PlayerLog.write("[video] wglMakeCurrent failed"); return false }
        val apiType = Memory(8).apply { clear(); setString(0, "opengl", "US-ASCII") }
        val init = Memory(24).apply { clear(); setPointer(0, CallbackReference.getFunctionPointer(getProc)) }
        val ref = PointerByReference()
        Gl.defaultsForMpv()
        val rc = api.mpv_render_context_create(ref, mpv, params(Mpv.RENDER_PARAM_API_TYPE to apiType, Mpv.RENDER_PARAM_OPENGL_INIT_PARAMS to init))
        leaveGl()
        if (rc < 0 || ref.value == null) {
            PlayerLog.write("[video] mpv_render_context_create: ${api.mpv_error_string(rc)}")
            return false
        }
        this.api = api
        ctx = ref.value
        Native.setCallbackThreadInitializer(onUpdate, CallbackThreadInitializer(true, false, "mpv-video-update"))
        api.mpv_render_context_set_update_callback(ref.value, onUpdate, null)
        PlayerLog.write("[video] mpv draws inside the window (OpenGL, one surface)")
        if (ru.suslikovanora.media.desktop.Diag.on) stallWatch.start()
        return true
    }

    // Suslik Debug: сторож потока интерфейса — пока идёт видео, замирания окна дольше 100 мс пишутся в журнал
    private val stallWatch = Thread({
        var logged = 0
        while (!disposed) {
            val posted = System.nanoTime()
            val done = CountDownLatch(1)
            SwingUtilities.invokeLater { done.countDown() }
            done.await(5, TimeUnit.SECONDS)
            val ms = (System.nanoTime() - posted) / 1_000_000
            if (ms > 100 && !disposed && logged++ < 200) PlayerLog.write("[video] UI thread stalled $ms ms (frame $frames)")
            Thread.sleep(50)
        }
    }, "video-ui-watch").apply { isDaemon = true }

    /** Из любого потока: отрисовать на потоке интерфейса (несколько вызовов подряд сливаются в один). */
    fun schedule() {
        if (disposed) return
        if (posted.compareAndSet(false, true)) SwingUtilities.invokeLater { posted.set(false); render() }
    }

    /** Размер области видео в пикселях. mpv сам вписывает кадр и рисует субтитры по всей области. */
    fun resize(w: Int, h: Int) {
        if (w == wantW && h == wantH) return
        wantW = w; wantH = h
        forceNext = true
        schedule()
    }

    private fun render() {
        val c = ctx ?: return
        val a = api ?: return
        if (disposed) return
        if (!gl.valid) {
            PlayerLog.write("[video] Skiko re-created its OpenGL context: video stopped")
            disposed = true
            return
        }
        if (!gl.makeCurrent()) return
        // update() заодно выполняет запросы ядра к выводу (снимок кадра и т. п.), поэтому зовём его на каждый сигнал
        Gl.defaultsForMpv()
        val flags = a.mpv_render_context_update(c)
        val force = forceNext
        forceNext = false
        if (flags and Mpv.RENDER_UPDATE_FRAME == 0L && !force) { leaveGl(); return }
        val w = wantW.coerceAtLeast(16)
        val h = wantH.coerceAtLeast(16)
        if (!ensureTarget(w, h)) { leaveGl(); return }
        fboParam.setInt(0, fbo); fboParam.setInt(4, w); fboParam.setInt(8, h); fboParam.setInt(12, Gl.RGBA8)
        // mpv пишет в буфер мимо Skia: без этого Skia считает его неизменным и отдаёт прошлый снимок
        // (был один и тот же кадр — первый после изменения размера, часто чёрный)
        surface?.notifyContentWillChange(ContentChangeMode.RETAIN)
        gl.context.resetGLAll()
        Gl.defaultsForMpv()
        val rc = a.mpv_render_context_render(c, renderParams)
        leaveGl() // mpv менял состояние GL, которое Skia держит у себя в кеше
        if (rc < 0) { PlayerLog.write("[video] render: ${a.mpv_error_string(rc)}"); return }
        val snap = runCatching { surface?.makeImageSnapshot() }
            .onFailure { if (frames == 0L) PlayerLog.write("[video] snapshot failed: $it") }.getOrNull()
        gl.context.flush()
        leaveGl()
        if (snap == null) return
        image?.close()
        image = snap
        frames++
        frame.value = frames
        if (frames == 1L || frames == 300L) PlayerLog.write("[video] frame $frames shown (${w}x$h)")
        if (dumpDir != null) dump(snap)
    }

    private fun ensureTarget(w: Int, h: Int): Boolean {
        if (tex != 0 && texW == w && texH == h) return true
        releaseTarget()
        val g = Gl.lib ?: return false
        return runCatching {
            val ids = IntArray(1)
            g.glGenTextures(1, ids)
            tex = ids[0]
            g.glBindTexture(Gl.TEXTURE_2D, tex)
            g.glTexParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_MIN_FILTER, Gl.LINEAR)
            g.glTexParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_MAG_FILTER, Gl.LINEAR)
            g.glTexParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_WRAP_S, Gl.CLAMP_TO_EDGE)
            g.glTexParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_WRAP_T, Gl.CLAMP_TO_EDGE)
            g.glTexImage2D(Gl.TEXTURE_2D, 0, Gl.RGBA8, w, h, 0, Gl.RGBA, Gl.UNSIGNED_BYTE, null)
            fbo = Gl.genFramebuffer()
            Gl.bindFramebuffer(fbo)
            Gl.framebufferTexture(tex)
            val status = Gl.framebufferStatus()
            Gl.bindFramebuffer(0)
            gl.context.resetGLAll()
            if (status != Gl.FRAMEBUFFER_COMPLETE) error("framebuffer status 0x${status.toString(16)}")
            val rt = BackendRenderTarget.makeGL(w, h, 0, 0, fbo, FramebufferFormat.GR_GL_RGBA8)
            surface = Surface.makeFromBackendRenderTarget(gl.context, rt, SurfaceOrigin.TOP_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB)
                ?: error("Skia cannot wrap the framebuffer")
            texW = w; texH = h
            true
        }.onFailure { PlayerLog.write("[video] target ${w}x$h: $it"); releaseTarget() }.getOrDefault(false)
    }

    private fun releaseTarget() {
        surface?.close(); surface = null
        if (fbo != 0) runCatching { Gl.deleteFramebuffer(fbo) }
        if (tex != 0) runCatching { Gl.lib?.glDeleteTextures(1, intArrayOf(tex)) }
        fbo = 0; tex = 0; texW = 0; texH = 0
        leaveGl()
    }

    // Отладка: SUSLIK_VIDEO_DUMP=1 — раз в 4 с кадр, который получает Compose, пишется в %TEMP%\suslik-video\*.png
    private val dumpDir: java.io.File? = System.getenv("SUSLIK_VIDEO_DUMP")?.takeIf { it.isNotBlank() && it != "0" }
        ?.let { java.io.File(System.getProperty("java.io.tmpdir"), "suslik-video").apply { mkdirs() } }
    private var lastDump = 0L

    private fun dump(img: Image) {
        val now = System.currentTimeMillis()
        if (now - lastDump < 4000) return
        lastDump = now
        runCatching {
            val bmp = org.jetbrains.skia.Bitmap()
            bmp.allocPixels(org.jetbrains.skia.ImageInfo.makeN32Premul(img.width, img.height))
            val ok = img.readPixels(gl.context, bmp, 0, 0)
            leaveGl()
            val f = java.io.File(dumpDir, "frame-%06d.png".format(frames))
            Thread({ // сжатие PNG — не на потоке интерфейса (иначе окно замирает)
                runCatching { f.writeBytes((Image.makeFromBitmap(bmp).encodeToData() ?: error("encode")).bytes) }
                PlayerLog.write("[video] dump ${f.name} ${img.width}x${img.height} read=$ok")
            }, "video-dump").apply { isDaemon = true; start() }
        }.onFailure { PlayerLog.write("[video] dump failed: $it") }
    }

    /** Поток интерфейса: освобождает render context (до mpv_terminate_destroy, см. [MpvEngine.release]). */
    fun dispose() {
        if (freed.count == 0L) return
        val wasValid = !disposed || gl.valid
        disposed = true
        try {
            val c = ctx
            val a = api
            ctx = null
            if (wasValid && gl.makeCurrent()) {
                if (c != null && a != null) {
                    a.mpv_render_context_set_update_callback(c, null, null)
                    a.mpv_render_context_free(c)
                }
                releaseTarget() // и буфер окна снова привязан
            }
            image?.close(); image = null
        } catch (e: Throwable) {
            PlayerLog.write("[video] dispose: $e")
        } finally {
            freed.countDown()
        }
    }

    /** Из потока движка: дождаться [dispose]. */
    fun awaitFreed(ms: Long): Boolean = freed.await(ms, TimeUnit.MILLISECONDS)
}

/** Видео mpv как обычный слой Compose: панели рисуются поверх него в той же сцене. */
@Composable
fun MpvVideoSurface(out: MpvVideoOut, modifier: Modifier = Modifier) {
    Canvas(modifier.onSizeChanged { out.resize(it.width, it.height) }) {
        out.frame.value // новый кадр → перерисовка без перекомпоновки
        val img = out.image ?: return@Canvas
        drawIntoCanvas { it.nativeCanvas.drawImageRect(img, Rect.makeWH(size.width, size.height)) }
    }
}
