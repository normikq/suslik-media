package ru.suslikovanora.media.desktop.trackers

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.suslikovanora.media.desktop.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.suslikovanora.media.desktop.ui.Chip
import ru.suslikovanora.media.desktop.ui.CloseCrossIcon
import ru.suslikovanora.media.desktop.ui.Field
import ru.suslikovanora.media.desktop.ui.LampButton
import ru.suslikovanora.media.desktop.ui.Onest
import ru.suslikovanora.media.desktop.ui.OutlineButton
import ru.suslikovanora.media.desktop.ui.Sm
import ru.suslikovanora.media.desktop.ui.Unbounded
import ru.suslikovanora.media.desktop.ui.hoverClick
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackerSettingsDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Main dialog tab: 0 = RuTracker (ПК), 1 = Выбор трекеров
    var activeTab by remember { mutableStateOf(0) }

    // RuTracker states
    var rtEnabled by remember { mutableStateOf(RuTrackerTracker.enabled) }
    var rtUrl by remember { mutableStateOf(RuTrackerTracker.url) }
    var rtCookie by remember { mutableStateOf(RuTrackerTracker.cookie) }

    // Tracker toggles for Tab 2
    var trackerRefresh by remember { mutableStateOf(0) }

    // Detection
    var browsers by remember { mutableStateOf<List<DetectedBrowser>>(emptyList()) }
    var selectedBrowser by remember { mutableStateOf<DetectedBrowser?>(null) }
    var browserStatus by remember { mutableStateOf<Pair<Boolean?, String>?>(null) }
    var testStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val detected = BrowserDetector.detect()
            browsers = detected
            val pref = EdgeBrowserBridge.preferredBrowserType
            val source = RuTrackerTracker.cookieSource
            selectedBrowser = detected.firstOrNull { it.type == source }
                ?: detected.firstOrNull { it.type == pref } ?: detected.firstOrNull()
        }
    }

    fun saveAll() {
        RuTrackerTracker.enabled = rtEnabled
        RuTrackerTracker.url = rtUrl
        RuTrackerTracker.cookie = rtCookie
        scope.launch(Dispatchers.IO) {
            ru.suslikovanora.media.desktop.Store.flushAll()
        }
    }

    fun dismissDialog() {
        saveAll()
        onDismiss()
        EdgeBrowserBridge.stopAsync()
    }

    val enabledSourcesCount = remember(trackerRefresh, rtEnabled) {
        TrackerSettings.SOURCES.count { TrackerSettings.isEnabled(it.id) }
    }

    Box(
        Modifier.fillMaxSize().background(Sm.scrim).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            dismissDialog()
        },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .background(Sm.booth)
                .border(1.dp, Sm.line, RoundedCornerShape(20.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {} // Prevent click-through
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header with Close button
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("trackers.title"), fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Sm.text)
                    Text(
                        tr("trackers.subtitle"),
                        fontFamily = Onest,
                        fontSize = 13.5.sp,
                        color = Sm.muted,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).hoverClick(remember { MutableInteractionSource() }) {
                        dismissDialog()
                    }.padding(6.dp)
                ) {
                    CloseCrossIcon(color = Sm.muted, size = 16.dp)
                }
            }

            Spacer(Modifier.height(18.dp))

            // Main Tabs: Tab 0 = RuTracker (ПК), Tab 1 = Выбор трекеров
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(
                    label = tr("search.rutracker_pc") + if (rtEnabled && rtCookie.isNotBlank()) " ✓" else "",
                    on = activeTab == 0
                ) {
                    activeTab = 0
                }
                Chip(
                    label = tr("trackers.selection_title", "enabled" to enabledSourcesCount, "total" to TrackerSettings.SOURCES.size),
                    on = activeTab == 1
                ) {
                    activeTab = 1
                }
            }

            Spacer(Modifier.height(18.dp))

            if (activeTab == 0) {
                // ================= TAB 1: RuTracker (ПК) =================
                // Enabled Toggle
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(
                        label = if (rtEnabled) tr("trackers.direct_search_enabled") else tr("trackers.direct_search_disabled"),
                        on = rtEnabled
                    ) {
                        rtEnabled = !rtEnabled
                        TrackerSettings.setEnabled("rutracker", rtEnabled)
                        trackerRefresh++
                    }
                    if (rtEnabled && rtCookie.isBlank()) {
                        Text(tr("trackers.need_cookies"), color = Sm.lamp, fontFamily = Onest, fontSize = 12.5.sp)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Tracker URL / Mirror
                Text(tr("trackers.address_label"), color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Field(rtUrl, { rtUrl = it }, tr("trackers.address_hint"))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(tr("trackers.address_main"), rtUrl.contains("rutracker.org")) {
                        rtUrl = "https://rutracker.org"
                    }
                    Chip(tr("trackers.address_mirror"), rtUrl.contains("rutracker.net")) {
                        rtUrl = "https://rutracker.net"
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Browser Auto-detection section
                Text(tr("trackers.autodetect_title"), color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text(
                    tr("trackers.autodetect_desc"),
                    color = Sm.muted,
                    fontFamily = Onest,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                )

                if (browsers.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        browsers.forEach { b ->
                            Chip(b.title, selectedBrowser == b) {
                                selectedBrowser = b
                                browserStatus = null
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    val b = selectedBrowser
                    val isChromium = b?.type?.isChromium == true
                    val browserName = b?.type?.displayName ?: tr("trackers.browser_fallback")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlineButton(
                            text = if (busy) {
                                if (isChromium) tr("trackers.waiting_login", "browser" to browserName) else tr("trackers.reading")
                            } else {
                                if (isChromium) tr("trackers.btn_login_via", "browser" to browserName)
                                else tr("trackers.btn_take_from", "browser" to browserName)
                            }
                        ) {
                            val target = selectedBrowser ?: return@OutlineButton
                            busy = true
                            browserStatus = null
                            scope.launch {
                                if (target.type.isChromium && EdgeBrowserBridge.isAvailable(target.type)) {
                                    val res = EdgeBrowserBridge.loginAndCaptureCookies(target.type, rtUrl) { msg ->
                                        browserStatus = null to msg
                                    }
                                    busy = false
                                    res.onSuccess {
                                        rtCookie = it
                                        rtEnabled = true
                                        RuTrackerTracker.cookie = it
                                        RuTrackerTracker.enabled = true
                                        EdgeBrowserBridge.preferredBrowserType = target.type
                                        RuTrackerTracker.cookieSource = target.type
                                        ru.suslikovanora.media.desktop.Store.flushAll()
                                        browserStatus = true to tr("trackers.cookies_saved", "browser" to browserName)
                                        trackerRefresh++
                                    }.onFailure {
                                        browserStatus = false to (it.message ?: tr("trackers.cookies_save_failed", "browser" to browserName))
                                    }
                                } else {
                                    val res = withContext(Dispatchers.IO) {
                                        BrowserDetector.extractCookies(target, "rutracker")
                                    }
                                    busy = false
                                    res.onSuccess {
                                        rtCookie = it
                                        rtEnabled = true
                                        RuTrackerTracker.cookie = it
                                        RuTrackerTracker.enabled = true
                                        RuTrackerTracker.cookieSource = target.type
                                        ru.suslikovanora.media.desktop.Store.flushAll()
                                        browserStatus = true to tr("trackers.cookies_saved", "browser" to browserName)
                                        trackerRefresh++
                                    }.onFailure {
                                        browserStatus = false to (it.message ?: tr("trackers.cookies_extract_failed"))
                                    }
                                }
                            }
                        }

                        browserStatus?.let { (ok, msg) ->
                            val color = when (ok) {
                                true -> Sm.ok
                                false -> Sm.bad
                                else -> Sm.lamp
                            }
                            Text(msg, color = color, fontFamily = Onest, fontSize = 12.5.sp)
                        }
                    }

                    if (isChromium) {
                        Text(
                            tr("trackers.v20_hint", "browser" to browserName),
                            color = Sm.muted,
                            fontFamily = Onest,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    } else if (selectedBrowser?.type == BrowserType.FIREFOX) {
                        Text(
                            tr("trackers.firefox_hint"),
                            color = Sm.muted,
                            fontFamily = Onest,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                } else {
                    Text(tr("trackers.scanning_browsers"), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
                }

                Spacer(Modifier.height(16.dp))

                // Cookie input
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("trackers.session_cookie_label"), color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text(
                        tr("trackers.btn_paste"),
                        color = Sm.accent,
                        fontFamily = Onest,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).hoverClick(remember { MutableInteractionSource() }) {
                            val clip = runCatching {
                                Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
                            }.getOrNull()
                            if (!clip.isNullOrBlank()) {
                                rtCookie = clip.trim()
                                rtEnabled = true
                                RuTrackerTracker.cookie = rtCookie
                                RuTrackerTracker.enabled = true
                                RuTrackerTracker.cookieSource = null
                                ru.suslikovanora.media.desktop.Store.flushAll()
                                trackerRefresh++
                            }
                        }.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Field(rtCookie, { rtCookie = it; RuTrackerTracker.cookieSource = null },"bb_session=...; bb_data=...; cf_clearance=...")

                Spacer(Modifier.height(12.dp))

                // Test connection
                var testingConnection by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlineButton(
                        text = if (testingConnection) tr("trackers.checking_via", "browser" to EdgeBrowserBridge.activeBrowserName) else tr("trackers.btn_check_connection")
                    ) {
                        if (testingConnection) return@OutlineButton
                        saveAll()
                        testingConnection = true
                        testStatus = null
                        scope.launch {
                            val res = RuTrackerTracker.testConnection()
                            testStatus = res
                            testingConnection = false
                        }
                    }

                    testStatus?.let { (ok, msg) ->
                        Text(msg, color = if (ok) Sm.ok else Sm.bad, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    }
                }

            } else {
                // ================= TAB 2: Выбор трекеров =================
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        tr("trackers.sources_title"),
                        fontFamily = Onest,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = Sm.text
                    )
                    Text(
                        tr("trackers.sources_desc"),
                        fontFamily = Onest,
                        fontSize = 13.sp,
                        color = Sm.muted,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )

                    // Quick action buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                        Chip(tr("trackers.btn_enable_all"), on = enabledSourcesCount == TrackerSettings.SOURCES.size) {
                            TrackerSettings.setAll(true)
                            rtEnabled = true
                            trackerRefresh++
                        }
                        Chip(tr("trackers.btn_only_ru"), on = false) {
                            TrackerSettings.setOnlyRussian()
                            rtEnabled = true
                            trackerRefresh++
                        }
                    }

                    // List of trackers
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrackerSettings.SOURCES.forEach { source ->
                            val on = remember(trackerRefresh, source.id, rtEnabled) {
                                TrackerSettings.isEnabled(source.id)
                            }

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (on) Color(0xFF161A24) else Color(0xFF11131A))
                                    .border(1.dp, if (on) Color(0xFF283144) else Sm.line, RoundedCornerShape(12.dp))
                                    .clickable {
                                        val next = !on
                                        TrackerSettings.setEnabled(source.id, next)
                                        if (source.id == "rutracker") rtEnabled = next
                                        trackerRefresh++
                                    }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Tag badge
                                Text(
                                    source.tag,
                                    fontFamily = Onest,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp,
                                    color = if (on) Sm.accent else Sm.muted,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (on) Sm.accent.copy(alpha = 0.15f) else Color(0xFF1B1E28))
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                )

                                Spacer(Modifier.width(12.dp))

                                Column(Modifier.weight(1f)) {
                                    Text(
                                        source.name,
                                        fontFamily = Onest,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = if (on) Sm.text else Sm.muted
                                    )
                                    Text(
                                        source.description,
                                        fontFamily = Onest,
                                        fontSize = 12.sp,
                                        color = Sm.muted,
                                        modifier = Modifier.padding(top = 1.dp)
                                    )
                                }

                                Spacer(Modifier.width(10.dp))

                                // Toggle button
                                Chip(
                                    label = if (on) tr("trackers.status_on") else tr("trackers.status_off"),
                                    on = on
                                ) {
                                    val next = !on
                                    TrackerSettings.setEnabled(source.id, next)
                                    if (source.id == "rutracker") rtEnabled = next
                                    trackerRefresh++
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Bottom action
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                LampButton(tr("trackers.btn_save_close")) {
                    dismissDialog()
                }
            }
        }
    }
}
