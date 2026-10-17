package ru.suslikovanora.media.desktop.player

import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import ru.suslikovanora.media.desktop.PlayerLog
import java.awt.Window
import java.io.File
import javax.swing.SwingUtilities

/**
 * Windows System Media Transport Controls (SMTC) integration.
 * Makes Windows and utilities like Media Flyout, ModernFlyouts, and the Windows 10/11 taskbar volume flyout
 * recognize Suslik Media as an active media player, displaying title, series/subtitle, thumbnail,
 * playback status, timeline, and allowing control via media keys and flyout buttons (Play, Pause, Next, Prev, Seek).
 */
object MediaControls {

    private interface CombaseApi : com.sun.jna.Library {
        fun RoInitialize(initType: Int): Int
        fun WindowsCreateString(source: CharArray, length: Int, hstring: PointerByReference): Int
        fun WindowsDeleteString(hstring: Pointer): Int
        fun RoGetActivationFactory(activatableClassId: Pointer, iid: Pointer, factory: PointerByReference): Int
        fun RoActivateInstance(activatableClassId: Pointer, instance: PointerByReference): Int
    }

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private val combase: CombaseApi? by lazy {
        if (!isWindows) null
        else runCatching {
            Native.load("combase", CombaseApi::class.java).also {
                // Initialize COM in MTA (RO_INIT_MULTITHREADED = 0) or STA (1)
                runCatching { it.RoInitialize(1) } // STA, on the smtc thread, which pumps messages
            }
        }.getOrNull()
    }

    private fun guid(s: String): Memory {
        val clean = s.replace("-", "")
        val mem = Memory(16)
        val d1 = clean.substring(0, 8).toLong(16).toInt()
        val d2 = clean.substring(8, 12).toInt(16).toShort()
        val d3 = clean.substring(12, 16).toInt(16).toShort()
        mem.setInt(0, d1)
        mem.setShort(4, d2)
        mem.setShort(6, d3)
        for (i in 0 until 8) {
            val b = clean.substring(16 + i * 2, 18 + i * 2).toInt(16).toByte()
            mem.setByte(8L + i, b)
        }
        return mem
    }

    private fun hstring(cb: CombaseApi, str: String): Pointer? {
        val ref = PointerByReference()
        val chars = str.toCharArray()
        val hr = cb.WindowsCreateString(chars, chars.size, ref)
        return if (hr == 0) ref.value else null
    }

    private fun vcall(pObj: Pointer, slot: Int, vararg args: Any?): Int {
        val pVtable = pObj.getPointer(0) ?: return -1
        val pMethod = pVtable.getPointer(slot.toLong() * Native.POINTER_SIZE) ?: return -1
        val func = Function.getFunction(pMethod, Function.ALT_CONVENTION)
        val allArgs = arrayOf<Any?>(pObj, *args)
        return (func.invoke(Int::class.java, allArgs) as Number).toInt()
    }

    // Callbacks to the player
    data class Callbacks(
        val onPlay: () -> Unit = {},
        val onPause: () -> Unit = {},
        val onNext: () -> Unit = {},
        val onPrevious: () -> Unit = {},
        val onSeek: (Long) -> Unit = {}
    )

    private var currentHwnd: Long = 0L
    private var pSmtc: Pointer? = null
    private var pSmtc2: Pointer? = null
    private var pUpdater: Pointer? = null

    private var buttonToken: Long = 0L
    private var posToken: Long = 0L

    // Strong references to COM callback objects and vtables to prevent garbage collection
    private var buttonQiCb: ComQiCallback? = null
    private var buttonAddRefCb: ComAddRefReleaseCallback? = null
    private var buttonReleaseCb: ComAddRefReleaseCallback? = null
    private var buttonInvokeCb: ComInvokeCallback? = null
    private var buttonVtableMem: Memory? = null
    private var buttonComObjMem: Memory? = null

    private var posQiCb: ComQiCallback? = null
    private var posAddRefCb: ComAddRefReleaseCallback? = null
    private var posReleaseCb: ComAddRefReleaseCallback? = null
    private var posInvokeCb: ComInvokeCallback? = null
    private var posVtableMem: Memory? = null
    private var posComObjMem: Memory? = null

    @Volatile private var activeCallbacks: Callbacks? = null

    // Cached states
    private var lastTitle: String = ""
    private var lastSubtitle: String = ""
    private var lastThumbnailPath: String = ""
    private var currentThumbnailPath: String = ""
    private var lastPositionMs: Long = -1L
    private var lastDurationMs: Long = -1L
    private var lastTimelineUpdateAt: Long = 0L

    private interface ComQiCallback : StdCallLibrary.StdCallCallback {
        fun invoke(thisPtr: Pointer?, riid: Pointer?, ppv: Pointer?): Int
    }
    private interface ComAddRefReleaseCallback : StdCallLibrary.StdCallCallback {
        fun invoke(thisPtr: Pointer?): Int
    }
    private interface ComInvokeCallback : StdCallLibrary.StdCallCallback {
        fun invoke(thisPtr: Pointer?, sender: Pointer?, args: Pointer?): Int
    }

    private val iunknownBytes by lazy { guid("00000000-0000-0000-c000-000000000046").getByteArray(0, 16) }
    private val agileBytes by lazy { guid("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90").getByteArray(0, 16) }
    private val buttonHandlerBytes by lazy { guid("0557e996-7b23-5bae-aa81-ea0d671143a4").getByteArray(0, 16) }
    private val posHandlerBytes by lazy { guid("44e34f15-bdc0-50a7-ace4-39e91fb753f1").getByteArray(0, 16) }

    @Synchronized
    private fun attachNow(hwnd: Pointer, callbacks: Callbacks) {
        val cb = combase ?: return
        val hwndVal = Pointer.nativeValue(hwnd)
        if (hwndVal == 0L) return

        if (currentHwnd == hwndVal && pSmtc != null) {
            this.activeCallbacks = callbacks
            // Already attached to this window, re-enable controls
            runCatching {
                vcall(pSmtc!!, 11, true) // put_IsEnabled(true)
                vcall(pSmtc!!, 13, true) // put_IsPlayEnabled(true)
                vcall(pSmtc!!, 17, true) // put_IsPauseEnabled(true)
                replayWanted()
            }
            return
        }

        cleanup()
        // After cleanup(), which clears them: set before it, the first film's buttons reached nobody.
        this.activeCallbacks = callbacks
        currentHwnd = hwndVal

        runCatching {
            val smtcHs = hstring(cb, "Windows.Media.SystemMediaTransportControls") ?: return
            val interopGuid = guid("ddb0472d-c911-4a1f-86d9-dc3d71a95f5a")
            val factoryRef = PointerByReference()
            val hrFactory = cb.RoGetActivationFactory(smtcHs, interopGuid, factoryRef)
            cb.WindowsDeleteString(smtcHs)
            if (hrFactory != 0 || factoryRef.value == null) {
                PlayerLog.write("[smtc] RoGetActivationFactory failed: 0x${Integer.toHexString(hrFactory)}")
                return
            }

            val smtcGuid = guid("99fa3ff4-1742-42a6-902e-087d41f965ec")
            val smtcRef = PointerByReference()
            val hrSmtc = vcall(factoryRef.value, 6, hwnd, smtcGuid, smtcRef)
            vcall(factoryRef.value, 2) // Release the factory: only GetForWindow was needed
            if (hrSmtc != 0 || smtcRef.value == null) {
                PlayerLog.write("[smtc] GetForWindow failed: 0x${Integer.toHexString(hrSmtc)}")
                return
            }
            val smtc = smtcRef.value
            pSmtc = smtc

            // Enable basic controls
            vcall(smtc, 11, true) // put_IsEnabled(true)
            vcall(smtc, 13, true) // put_IsPlayEnabled(true)
            vcall(smtc, 17, true) // put_IsPauseEnabled(true)
            vcall(smtc, 25, true) // put_IsPreviousEnabled(true)
            vcall(smtc, 27, true) // put_IsNextEnabled(true)

            // Get display updater
            val updaterRef = PointerByReference()
            if (vcall(smtc, 8, updaterRef) == 0) {
                pUpdater = updaterRef.value
            }

            // Register ButtonPressed event listener
            registerButtonListener(smtc)

            // QI for ISystemMediaTransportControls2 (timeline and seekbar scrub support)
            val smtc2Guid = guid("ea98d2f6-7f3c-4af2-a586-72889808efb1")
            val smtc2Ref = PointerByReference()
            if (vcall(smtc, 0, smtc2Guid, smtc2Ref) == 0 && smtc2Ref.value != null) {
                pSmtc2 = smtc2Ref.value
                registerPositionListener(pSmtc2!!)
            }

            PlayerLog.write("[smtc] attached to window HWND 0x${hwndVal.toString(16)}")
            replayWanted()
        }.onFailure {
            PlayerLog.write("[smtc] attach exception: ${it.message}")
        }
    }

    private fun registerButtonListener(smtc: Pointer) {
        val qi = object : ComQiCallback {
            override fun invoke(thisPtr: Pointer?, riid: Pointer?, ppv: Pointer?): Int {
                if (ppv == null || thisPtr == null) return 0x80070057.toInt() // E_INVALIDARG
                if (riid == null) {
                    ppv.setPointer(0, null)
                    return 0x80004003.toInt() // E_POINTER
                }
                return try {
                    val reqBytes = riid.getByteArray(0, 16)
                    if (reqBytes.contentEquals(iunknownBytes) ||
                        reqBytes.contentEquals(agileBytes) ||
                        reqBytes.contentEquals(buttonHandlerBytes)
                    ) {
                        ppv.setPointer(0, thisPtr)
                        0
                    } else {
                        ppv.setPointer(0, null)
                        0x80004002.toInt() // E_NOINTERFACE
                    }
                } catch (_: Throwable) {
                    ppv.setPointer(0, null)
                    0x80004002.toInt()
                }
            }
        }
        val addRef = object : ComAddRefReleaseCallback { override fun invoke(thisPtr: Pointer?) = 1 }
        val release = object : ComAddRefReleaseCallback { override fun invoke(thisPtr: Pointer?) = 1 }
        val invoke = object : ComInvokeCallback {
            override fun invoke(thisPtr: Pointer?, sender: Pointer?, args: Pointer?): Int {
                if (args == null) return 0
                try {
                    val btnMem = Memory(4)
                    if (vcall(args, 6, btnMem) == 0) {
                        val btn = btnMem.getInt(0)
                        PlayerLog.write("[smtc] button event: $btn")
                        SwingUtilities.invokeLater {
                            val cb = activeCallbacks ?: return@invokeLater
                            when (btn) {
                                0 -> cb.onPlay()
                                1 -> cb.onPause()
                                2 -> cb.onPause() // Stop -> Pause
                                6 -> cb.onNext()
                                7 -> cb.onPrevious()
                            }
                        }
                    }
                } catch (t: Throwable) {
                    PlayerLog.write("[smtc] button event exception: ${t.message}")
                }
                return 0
            }
        }

        buttonQiCb = qi
        buttonAddRefCb = addRef
        buttonReleaseCb = release
        buttonInvokeCb = invoke

        val vtable = Memory(Native.POINTER_SIZE.toLong() * 4)
        vtable.setPointer(0, CallbackReference.getFunctionPointer(qi))
        vtable.setPointer(Native.POINTER_SIZE.toLong() * 1, CallbackReference.getFunctionPointer(addRef))
        vtable.setPointer(Native.POINTER_SIZE.toLong() * 2, CallbackReference.getFunctionPointer(release))
        vtable.setPointer(Native.POINTER_SIZE.toLong() * 3, CallbackReference.getFunctionPointer(invoke))

        val comObj = Memory(Native.POINTER_SIZE.toLong())
        comObj.setPointer(0, vtable)

        val tokenMem = Memory(8)
        val hr = vcall(smtc, 32, comObj, tokenMem) // add_ButtonPressed
        if (hr == 0) {
            buttonToken = tokenMem.getLong(0)
            buttonVtableMem = vtable
            buttonComObjMem = comObj
        } else {
            PlayerLog.write("[smtc] add_ButtonPressed failed: 0x${Integer.toHexString(hr)}")
        }
    }

    private fun registerPositionListener(smtc2: Pointer) {
        val qi = object : ComQiCallback {
            override fun invoke(thisPtr: Pointer?, riid: Pointer?, ppv: Pointer?): Int {
                if (ppv == null || thisPtr == null) return 0x80070057.toInt() // E_INVALIDARG
                if (riid == null) {
                    ppv.setPointer(0, null)
                    return 0x80004003.toInt() // E_POINTER
                }
                return try {
                    val reqBytes = riid.getByteArray(0, 16)
                    if (reqBytes.contentEquals(iunknownBytes) ||
                        reqBytes.contentEquals(agileBytes) ||
                        reqBytes.contentEquals(posHandlerBytes)
                    ) {
                        ppv.setPointer(0, thisPtr)
                        0
                    } else {
                        ppv.setPointer(0, null)
                        0x80004002.toInt() // E_NOINTERFACE
                    }
                } catch (_: Throwable) {
                    ppv.setPointer(0, null)
                    0x80004002.toInt()
                }
            }
        }
        val addRef = object : ComAddRefReleaseCallback { override fun invoke(thisPtr: Pointer?) = 1 }
        val release = object : ComAddRefReleaseCallback { override fun invoke(thisPtr: Pointer?) = 1 }
        val invoke = object : ComInvokeCallback {
            override fun invoke(thisPtr: Pointer?, sender: Pointer?, args: Pointer?): Int {
                if (args == null) return 0
                try {
                    val posMem = Memory(8)
                    if (vcall(args, 6, posMem) == 0) {
                        val ticks = posMem.getLong(0)
                        val ms = ticks / 10000L
                        PlayerLog.write("[smtc] seek requested to ${ms}ms")
                        SwingUtilities.invokeLater {
                            activeCallbacks?.onSeek?.invoke(ms)
                        }
                    }
                } catch (t: Throwable) {
                    PlayerLog.write("[smtc] seek event exception: ${t.message}")
                }
                return 0
            }
        }

        posQiCb = qi
        posAddRefCb = addRef
        posReleaseCb = release
        posInvokeCb = invoke

        val vtable = Memory(Native.POINTER_SIZE.toLong() * 4)
        vtable.setPointer(0, CallbackReference.getFunctionPointer(qi))
        vtable.setPointer(Native.POINTER_SIZE.toLong() * 1, CallbackReference.getFunctionPointer(addRef))
        vtable.setPointer(Native.POINTER_SIZE.toLong() * 2, CallbackReference.getFunctionPointer(release))
        vtable.setPointer(Native.POINTER_SIZE.toLong() * 3, CallbackReference.getFunctionPointer(invoke))

        val comObj = Memory(Native.POINTER_SIZE.toLong())
        comObj.setPointer(0, vtable)

        val tokenMem = Memory(8)
        val hr = vcall(smtc2, 13, comObj, tokenMem) // add_PlaybackPositionChangeRequested
        if (hr == 0) {
            posToken = tokenMem.getLong(0)
            posVtableMem = vtable
            posComObjMem = comObj
        } else {
            PlayerLog.write("[smtc] add_PlaybackPositionChangeRequested failed: 0x${Integer.toHexString(hr)}")
        }
    }

    @Synchronized
    private fun updateMetadataNow(title: String, subtitle: String? = null, thumbnailFile: File? = null) {
        val updater = pUpdater ?: return
        val cb = combase ?: return
        val sub = subtitle ?: "Suslik Media"
        // No picture given (the next episode before its frame): the last one again, from a fresh stream (see below).
        val thumbFile = thumbnailFile?.takeIf { it.exists() } ?: lastThumbnailPath.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.exists() }
        val thumbPath = thumbFile?.absolutePath.orEmpty()

        if (title == lastTitle && sub == lastSubtitle && thumbPath == lastThumbnailPath) return
        val thumbChanged = thumbPath != currentThumbnailPath
        lastTitle = title
        lastSubtitle = sub
        lastThumbnailPath = thumbPath

        runCatching {
            // [7] put_Type(2) (Video)
            vcall(updater, 7, 2)

            // Populate VideoProperties
            val vpRef = PointerByReference()
            if (vcall(updater, 13, vpRef) == 0 && vpRef.value != null) {
                val vp = vpRef.value
                val hTitle = hstring(cb, title)
                val hSub = hstring(cb, sub)
                if (hTitle != null) { vcall(vp, 7, hTitle); cb.WindowsDeleteString(hTitle) }
                if (hSub != null) { vcall(vp, 9, hSub); cb.WindowsDeleteString(hSub) }
                vcall(vp, 2) // Release
            }

            // Populate MusicProperties (for flyout utilities that only inspect MusicProperties)
            val mpRef = PointerByReference()
            if (vcall(updater, 12, mpRef) == 0 && mpRef.value != null) {
                val mp = mpRef.value
                val hTitle = hstring(cb, title)
                val hArtist = hstring(cb, sub)
                if (hTitle != null) { vcall(mp, 7, hTitle); cb.WindowsDeleteString(hTitle) }
                if (hArtist != null) { vcall(mp, 11, hArtist); cb.WindowsDeleteString(hArtist) }
                vcall(mp, 2) // Release
            }

            // Thumbnail: update only if the thumbnail actually changed.
            // On episode changes of the same show (same poster), calling put_Thumbnail forces the Windows Shell
            // to re-read and re-decode the image across COM IPC, freezing the shell and cursor for ~0.5s.
            if (thumbChanged) {
                if (thumbFile != null) {
                    setThumbnail(cb, updater, thumbFile)
                } else {
                    vcall(updater, 11, null) // [11] put_Thumbnail(null)
                }
                currentThumbnailPath = thumbPath
            }

            // [17] Update()
            val t0 = System.currentTimeMillis()
            val hrUpdate = vcall(updater, 17)
            val dt = System.currentTimeMillis() - t0
            PlayerLog.write("[smtc] updateMetadataNow: Update() returned $hrUpdate in ${dt}ms (thumbChanged=$thumbChanged, title=$title)")
        }
    }

    private interface StreamApi : StdCallLibrary {
        fun SHCreateStreamOnFileW(path: com.sun.jna.WString, mode: Int, stream: PointerByReference): Int // shlwapi
    }
    private interface ShcoreApi : StdCallLibrary {
        fun CreateRandomAccessStreamOverStream(stream: Pointer, options: Int, riid: Pointer, ras: PointerByReference): Int
    }
    private val shlwapi: StreamApi? by lazy { runCatching { Native.load("shlwapi", StreamApi::class.java) }.getOrNull() }
    private val shcore: ShcoreApi? by lazy { runCatching { Native.load("shcore", ShcoreApi::class.java) }.getOrNull() }

    /**
     * The picture as a StorageFile (StorageFile.GetFileFromPathAsync → CreateFromFile): Windows then opens the file by
     * its path itself. Through a stream (below) the shell read the picture from this process in small pieces, call by
     * call, and waited on it at every Update() — the cursor froze for ~0.5 s at each episode of a show with a poster.
     */
    private fun setThumbnail(cb: CombaseApi, updater: Pointer, file: File) {
        val t0 = System.currentTimeMillis()
        val ok = runCatching { setThumbnailFile(cb, updater, file) }.onFailure { PlayerLog.write("[smtc] thumbnail file exception: ${it.message}") }.getOrDefault(false)
        if (ok) PlayerLog.write("[smtc] thumbnail ${file.name} set from file in ${System.currentTimeMillis() - t0} ms")
        else setThumbnailStream(cb, updater, file)
    }

    private fun setThumbnailFile(cb: CombaseApi, updater: Pointer, file: File): Boolean {
        val classHs = hstring(cb, "Windows.Storage.StorageFile") ?: return false
        val staticsRef = PointerByReference()
        val hrStatics = cb.RoGetActivationFactory(classHs, guid("5984c710-daf2-43c8-8bb4-a4d3eacfd03f"), staticsRef) // IStorageFileStatics
        cb.WindowsDeleteString(classHs)
        if (hrStatics != 0 || staticsRef.value == null) return false
        val pathHs = hstring(cb, file.absolutePath) ?: run { vcall(staticsRef.value, 2); return false }
        val opRef = PointerByReference()
        val hrOp = vcall(staticsRef.value, 6, pathHs, opRef) // [6] GetFileFromPathAsync (7 is GetFileFromApplicationUriAsync)
        cb.WindowsDeleteString(pathHs)
        vcall(staticsRef.value, 2)
        if (hrOp != 0 || opRef.value == null) { PlayerLog.write("[smtc] GetFileFromPathAsync failed 0x${Integer.toHexString(hrOp)}"); return false }
        val op = opRef.value
        try {
            if (!awaitAsync(op, 2_000L)) { PlayerLog.write("[smtc] GetFileFromPathAsync did not finish"); return false }
            val storageFile = PointerByReference()
            if (vcall(op, 8, storageFile) != 0 || storageFile.value == null) return false // [8] GetResults → IStorageFile
            try {
                val refHs = hstring(cb, "Windows.Storage.Streams.RandomAccessStreamReference") ?: return false
                val refStatics = PointerByReference()
                val hrRef = cb.RoGetActivationFactory(refHs, guid("857309dc-3fbf-4e7d-986f-ef3b1a07a964"), refStatics)
                cb.WindowsDeleteString(refHs)
                if (hrRef != 0 || refStatics.value == null) return false
                val streamRef = PointerByReference()
                val hrCreate = vcall(refStatics.value, 6, storageFile.value, streamRef) // [6] CreateFromFile
                vcall(refStatics.value, 2)
                if (hrCreate != 0 || streamRef.value == null) return false
                val hrPut = vcall(updater, 11, streamRef.value) // [11] put_Thumbnail
                vcall(streamRef.value, 2)
                return hrPut == 0
            } finally { vcall(storageFile.value, 2) }
        } finally { vcall(op, 2) }
    }

    /** Waits for a WinRT async operation on this (STA) thread, pumping messages meanwhile. True when completed. */
    private fun awaitAsync(op: Pointer, timeoutMs: Long): Boolean {
        val infoRef = PointerByReference()
        if (vcall(op, 0, guid("00000036-0000-0000-c000-000000000046"), infoRef) != 0 || infoRef.value == null) return false // IAsyncInfo
        val info = infoRef.value
        try {
            val status = Memory(4)
            val msg = Memory(48)
            val user = user32
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                if (vcall(info, 7, status) != 0) return false // [7] get_Status
                when (status.getInt(0)) { 0 -> {}; 1 -> return true; else -> return false } // Started, Completed, Canceled/Error
                if (System.currentTimeMillis() > deadline) return false
                if (user != null) {
                    while (user.PeekMessageW(msg, null, 0, 0, 1)) { user.TranslateMessage(msg); user.DispatchMessageW(msg) }
                    val ev = wake
                    if (ev != null) user.MsgWaitForMultipleObjects(1, arrayOf(ev), false, 5, 0x04FF) else Thread.sleep(5)
                } else Thread.sleep(5)
            }
        } finally { vcall(info, 2) }
    }

    /**
     * Fallback: the picture as a stream. RandomAccessStreamReference.CreateFromUri takes no file:/// URI in an app
     * outside the Store (only http, https, ms-appx). File → IStream → IRandomAccessStream → CreateFromStream.
     */
    private fun setThumbnailStream(cb: CombaseApi, updater: Pointer, file: File) {
        runCatching {
            val sh = shlwapi ?: return
            val sc = shcore ?: return
            val istream = PointerByReference()
            val hrFile = sh.SHCreateStreamOnFileW(com.sun.jna.WString(file.absolutePath), 0 /* STGM_READ */, istream)
            if (hrFile != 0 || istream.value == null) {
                PlayerLog.write("[smtc] thumbnail: SHCreateStreamOnFile failed 0x${Integer.toHexString(hrFile)}")
                return
            }
            val ras = PointerByReference()
            val hrRas = sc.CreateRandomAccessStreamOverStream(istream.value, 0 /* BSOS_DEFAULT */, guid("905a0fe1-bc53-11df-8c49-001e4fc686da"), ras)
            vcall(istream.value, 2) // Release: the random access stream holds its own reference
            if (hrRas != 0 || ras.value == null) {
                PlayerLog.write("[smtc] thumbnail: CreateRandomAccessStreamOverStream failed 0x${Integer.toHexString(hrRas)}")
                return
            }

            val streamHs = hstring(cb, "Windows.Storage.Streams.RandomAccessStreamReference") ?: return
            val streamStaticsGuid = guid("857309dc-3fbf-4e7d-986f-ef3b1a07a964")
            val streamStaticsRef = PointerByReference()
            val hrStreamStatics = cb.RoGetActivationFactory(streamHs, streamStaticsGuid, streamStaticsRef)
            cb.WindowsDeleteString(streamHs)
            if (hrStreamStatics != 0 || streamStaticsRef.value == null) { vcall(ras.value, 2); return }

            val streamRef = PointerByReference()
            // [8] CreateFromStream (6 CreateFromFile, 7 CreateFromUri)
            val hrCreateStream = vcall(streamStaticsRef.value, 8, ras.value, streamRef)
            vcall(ras.value, 2)
            vcall(streamStaticsRef.value, 2)
            if (hrCreateStream == 0 && streamRef.value != null) {
                // [11] put_Thumbnail(streamRef)
                val hrPut = vcall(updater, 11, streamRef.value)
                vcall(streamRef.value, 2)
                PlayerLog.write("[smtc] thumbnail ${file.name} ${if (hrPut == 0) "set" else "failed 0x${Integer.toHexString(hrPut)}"}")
            } else {
                PlayerLog.write("[smtc] thumbnail: CreateFromStream failed 0x${Integer.toHexString(hrCreateStream)}")
            }
        }.onFailure { PlayerLog.write("[smtc] thumbnail exception: ${it.message}") }
    }

    @Synchronized
    private fun updatePlaybackNow(playing: Boolean) {
        val smtc = pSmtc ?: return
        runCatching {
            // [7] put_PlaybackStatus(3 = Playing, 4 = Paused)
            vcall(smtc, 7, if (playing) 3 else 4)
        }
    }

    @Synchronized
    private fun updateCapabilitiesNow(hasNext: Boolean, hasPrev: Boolean) {
        val smtc = pSmtc ?: return
        runCatching {
            vcall(smtc, 25, hasPrev) // put_IsPreviousEnabled
            vcall(smtc, 27, hasNext) // put_IsNextEnabled
        }
    }

    @Synchronized
    private fun updateTimelineNow(positionMs: Long, durationMs: Long, force: Boolean = false) {
        val smtc2 = pSmtc2 ?: return
        val cb = combase ?: return
        if (durationMs <= 0L) return

        val now = System.currentTimeMillis()
        if (!force && lastDurationMs == durationMs && Math.abs(positionMs - lastPositionMs) < 600L && now - lastTimelineUpdateAt < 900L) {
            return
        }
        lastPositionMs = positionMs
        lastDurationMs = durationMs
        lastTimelineUpdateAt = now

        runCatching {
            val tlHs = hstring(cb, "Windows.Media.SystemMediaTransportControlsTimelineProperties") ?: return
            val tlRef = PointerByReference()
            val hrAct = cb.RoActivateInstance(tlHs, tlRef)
            cb.WindowsDeleteString(tlHs)
            if (hrAct != 0 || tlRef.value == null) return
            val tl = tlRef.value

            val startTicks = 0L
            val endTicks = durationMs.coerceAtLeast(0L) * 10000L
            val posTicks = positionMs.coerceIn(0L, durationMs) * 10000L

            vcall(tl, 7, startTicks)
            vcall(tl, 9, endTicks)
            vcall(tl, 11, startTicks)
            vcall(tl, 13, endTicks)
            vcall(tl, 15, posTicks)

            // [12] UpdateTimelineProperties
            vcall(smtc2, 12, tl)
            vcall(tl, 2) // Release: a new one every second otherwise piled up for the whole film
        }
    }

    // ---- public calls: all COM work runs on one STA thread of its own that pumps messages ----------------------
    // On the UI thread (an STA that pumps no Windows messages) the shell's reads of the thumbnail stream waited for
    // it, and switching an episode from the flyout froze the cursor for ~0.5 s. In an MTA the flyout got no poster
    // at all. So: an STA that pumps, and sleeps on an event in between (no wakeups while idle).
    private interface PumpApi : StdCallLibrary {
        fun PeekMessageW(msg: Pointer, hwnd: Pointer?, min: Int, max: Int, remove: Int): Boolean
        fun TranslateMessage(msg: Pointer): Boolean
        fun DispatchMessageW(msg: Pointer): Pointer?
        fun MsgWaitForMultipleObjects(count: Int, handles: Array<Pointer>, waitAll: Boolean, ms: Int, wakeMask: Int): Int
    }
    private interface EventApi : StdCallLibrary {
        fun CreateEventW(attrs: Pointer?, manualReset: Boolean, initialState: Boolean, name: Pointer?): Pointer?
        fun SetEvent(event: Pointer): Boolean
    }
    private val tasks = java.util.concurrent.LinkedBlockingQueue<() -> Unit>()
    private val wake: Pointer? by lazy { runCatching { Native.load("kernel32", EventApi::class.java).CreateEventW(null, false, false, null) }.getOrNull() }
    private val kernel: EventApi? by lazy { runCatching { Native.load("kernel32", EventApi::class.java) }.getOrNull() }
    private val user32: PumpApi? by lazy { runCatching { Native.load("user32", PumpApi::class.java) }.getOrNull() }
    private val pumpThread: Thread by lazy {
        Thread({
            val user = user32
            val ev = wake
            combase // RoInitialize(STA) on this thread
            val msg = Memory(48) // MSG on x64
            while (true) {
                while (true) { val t = tasks.poll() ?: break; runCatching(t).onFailure { PlayerLog.write("[smtc] ${it.javaClass.simpleName}: ${it.message}") } }
                if (user == null || ev == null) { runCatching { tasks.take()() }; continue } // no user32: just run tasks
                while (user.PeekMessageW(msg, null, 0, 0, 1 /* PM_REMOVE */)) { user.TranslateMessage(msg); user.DispatchMessageW(msg) }
                if (tasks.isEmpty()) user.MsgWaitForMultipleObjects(1, arrayOf(ev), false, -1 /* INFINITE */, 0x04FF /* QS_ALLINPUT */)
            }
        }, "smtc").apply { isDaemon = true; start() }
    }
    private fun post(block: () -> Unit) {
        if (!isWindows) return
        tasks.put(block)
        pumpThread
        wake?.let { w -> kernel?.SetEvent(w) }
    }

    /**
     * A file for the flyout's picture: %APPDATA%\…\smtc\<name>. Only the newest is kept (the others are deleted here;
     * one Windows still reads stays until next time), so the folder does not grow with every film.
     */
    fun thumbFile(name: String): File {
        val dir = ru.suslikovanora.media.desktop.AppDir.file("smtc").apply { mkdirs() }
        dir.listFiles()?.filter { it.name != name }?.forEach { runCatching { it.delete() } }
        runCatching { ru.suslikovanora.media.desktop.AppDir.file("smtc_thumb.jpg").delete() } // test builds before 2.3.5: one fixed file
        return dir.resolve(name)
    }

    fun attach(window: Window, callbacks: Callbacks) {
        if (!isWindows) return
        // The window handle is read here (AWT peer of the caller), the rest on the smtc thread.
        val hwnd = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return
        post { attachNow(hwnd, callbacks) }
    }
    // What the player asked for last. The first film's player can send its state before its window is there (attach
    // waits for it), and those calls found no SMTC: Windows had no "playing" status, and Pause stayed greyed out.
    // attachNow applies them again.
    @Volatile private var wantMeta: Triple<String, String?, File?>? = null
    @Volatile private var wantPlaying: Boolean? = null
    @Volatile private var wantCaps: Pair<Boolean, Boolean>? = null

    private fun replayWanted() {
        wantMeta?.let { (t, s, f) -> lastTitle = ""; currentThumbnailPath = ""; updateMetadataNow(t, s, f) }
        wantPlaying?.let { updatePlaybackNow(it) }
        wantCaps?.let { (n, p) -> updateCapabilitiesNow(n, p) }
    }

    fun updateMetadata(title: String, subtitle: String? = null, thumbnailFile: File? = null) {
        wantMeta = Triple(title, subtitle, thumbnailFile)
        post { updateMetadataNow(title, subtitle, thumbnailFile) }
    }
    fun updatePlayback(playing: Boolean) { wantPlaying = playing; post { updatePlaybackNow(playing) } }
    fun updateCapabilities(hasNext: Boolean, hasPrev: Boolean) { wantCaps = hasNext to hasPrev; post { updateCapabilitiesNow(hasNext, hasPrev) } }
    fun updateTimeline(positionMs: Long, durationMs: Long, force: Boolean = false) = post { updateTimelineNow(positionMs, durationMs, force) }
    fun stop() { forgetWanted(); post { stopNow() } }

    /** The player closed: its buttons go nowhere now (unless the next player already attached its own). */
    fun detach(callbacks: Callbacks) {
        forgetWanted() // the next player's own calls come after this (Compose disposes the old one first)
        post {
            if (activeCallbacks === callbacks) activeCallbacks = null
            stopNow()
        }
    }

    private fun forgetWanted() { wantMeta = null; wantPlaying = null; wantCaps = null }

    /** App exit: waits a little so the flyout does not keep a dead player. */
    fun release() {
        if (!isWindows) return
        val done = java.util.concurrent.CountDownLatch(1)
        post { try { releaseNow() } finally { done.countDown() } }
        runCatching { done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    @Synchronized
    private fun stopNow() {
        val smtc = pSmtc ?: return
        runCatching {
            // [7] put_PlaybackStatus(2 = Stopped)
            vcall(smtc, 7, 2)
            pUpdater?.let {
                vcall(it, 16) // ClearAll()
                vcall(it, 17) // Update()
            }
            // The player closed: no empty «Suslik Media» left in the flyout (attach turns it on again)
            vcall(smtc, 11, false) // put_IsEnabled(false)
        }
        lastTitle = ""
        lastSubtitle = ""
        lastThumbnailPath = ""
        currentThumbnailPath = ""
        lastPositionMs = -1L
        lastDurationMs = -1L
    }

    @Synchronized
    private fun cleanup() {
        runCatching {
            pSmtc?.let { smtc ->
                if (buttonToken != 0L) {
                    vcall(smtc, 33, buttonToken) // remove_ButtonPressed
                    buttonToken = 0L
                }
            }
            pSmtc2?.let { smtc2 ->
                if (posToken != 0L) {
                    vcall(smtc2, 14, posToken) // remove_PlaybackPositionChangeRequested
                    posToken = 0L
                }
            }
            // Our own references from GetForWindow, the QI and get_DisplayUpdater
            pUpdater?.let { vcall(it, 2) }
            pSmtc2?.let { vcall(it, 2) }
            pSmtc?.let { vcall(it, 2) }
        }
        buttonQiCb = null
        buttonAddRefCb = null
        buttonReleaseCb = null
        buttonInvokeCb = null
        buttonVtableMem = null
        buttonComObjMem = null

        posQiCb = null
        posAddRefCb = null
        posReleaseCb = null
        posInvokeCb = null
        posVtableMem = null
        posComObjMem = null

        pSmtc = null
        pSmtc2 = null
        pUpdater = null
        currentHwnd = 0L
        activeCallbacks = null
        currentThumbnailPath = ""
    }

    @Synchronized
    private fun releaseNow() {
        stopNow()
        cleanup()
    }
}
