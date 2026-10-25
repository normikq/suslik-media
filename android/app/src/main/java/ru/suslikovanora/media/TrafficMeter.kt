package ru.suslikovanora.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import ru.suslikovanora.media.core.BurstProxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * «Трафик фильмов»: how much of the films the reserve (BurstProxy) has fetched this month, split by mobile data and
 * Wi-Fi. Only film data counts (posters and lists are small). Stored on the phone, shown in Settings.
 */
object TrafficMeter {
    private val pendingMobile = AtomicLong()
    private val pendingWifi = AtomicLong()
    @Volatile private var app: Context? = null
    @Volatile private var metered = false
    @Volatile private var checkedAt = 0L

    fun init(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        BurstProxy.onBytes = { n ->
            val total = if (isMetered(a)) pendingMobile.addAndGet(n) else pendingWifi.addAndGet(n)
            if (total > 16L shl 20) flush(a)
        }
    }

    /** Is the current network paid / limited (mobile data, a hotspot)? Asked at most every 3 s. */
    fun isMetered(ctx: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - checkedAt > 3000) {
            checkedAt = now
            metered = runCatching {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                cm.isActiveNetworkMetered || caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            }.getOrDefault(false)
        }
        return metered
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("traffic", Context.MODE_PRIVATE)
    private fun currentMonth() = SimpleDateFormat("yyyy-MM", Locale.ROOT).format(Date())

    @Synchronized fun flush(ctx: Context) {
        val m = pendingMobile.getAndSet(0)
        val w = pendingWifi.getAndSet(0)
        val p = prefs(ctx)
        val cur = currentMonth()
        if (p.getString("month", null) != cur) p.edit().putString("month", cur).putLong("mobile", 0L).putLong("wifi", 0L).apply()
        if (m == 0L && w == 0L) return
        p.edit().putLong("mobile", p.getLong("mobile", 0L) + m).putLong("wifi", p.getLong("wifi", 0L) + w).apply()
    }

    /** This month: (mobile data, Wi-Fi) in bytes. */
    @Synchronized fun month(ctx: Context): Pair<Long, Long> {
        flush(ctx)
        val p = prefs(ctx)
        return p.getLong("mobile", 0L) to p.getLong("wifi", 0L)
    }

    @Synchronized fun reset(ctx: Context) {
        pendingMobile.set(0); pendingWifi.set(0)
        prefs(ctx).edit().clear().apply()
    }

    fun format(bytes: Long): String =
        if (bytes >= 1L shl 30) tr("common.f_gb").format(bytes / 1073741824.0) else tr("common.d_mb").format(bytes shr 20)
}
