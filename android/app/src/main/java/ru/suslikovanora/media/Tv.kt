package ru.suslikovanora.media

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** Android TV (set-top box, TV with a remote): no touch, everything by the D-pad, OK and Back. */
object Tv {
    /** Known after the first [isTv] (SuslikApp asks at start). */
    @Volatile var cached: Boolean? = null
        private set

    fun isTv(context: Context): Boolean = cached ?: (
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        ).also { cached = it }
}
