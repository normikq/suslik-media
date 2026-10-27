package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.desktop.ui.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * What the AI suggested for the torrents of one tile (versions, seasons): a row per torrent, ticked = take the AI's
 * title (accepted), unticked = keep the regexes' one (rejected). Also a plain message (cooldown, error, «не смог»).
 */
@Composable
fun AiTitleDialog(panel: Library.AiPanel, lib: Library, onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(260, easing = Sm.ease))
    val scope = rememberCoroutineScope()
    val rows = panel.ids.mapNotNull { id -> lib.items.firstOrNull { it.m.id == id } }.filter { it.m.aiTitle != null }
    // suggested and accepted start ticked: «Сохранить» right away accepts what the AI found
    val ticks = remember(panel) { mutableStateMapOf<Long, Boolean>().apply { rows.forEach { put(it.m.id, it.m.aiTitle!!.status != "rejected") } } }
    var saving by remember { mutableStateOf(false) }
    val editable = rows.filter { it.m.canAiTitle && it.m.aiTitle!!.status != "none" }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = a }.background(Sm.scrim)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.graphicsLayer { translationY = (1f - a) * 24f; scaleX = 0.97f + 0.03f * a; scaleY = scaleX }
                .widthIn(max = 620.dp).padding(24.dp).glassSheet(RoundedCornerShape(22.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .padding(22.dp)
        ) {
            Text(tr("library.ai_okno"), color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 20.sp)
            if (panel.message != null) {
                Text(panel.message, color = Sm.muted, fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
            }
            if (rows.isNotEmpty()) {
                if (editable.isNotEmpty()) Text(tr("library.ai_okno_pod"), color = Sm.muted, fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { item ->
                        val ai = item.m.aiTitle!!
                        val canTick = item.m.canAiTitle && ai.status != "none"
                        Row(
                            Modifier.fillMaxWidth().background(Sm.glassFill, RoundedCornerShape(14.dp)).border(1.dp, Sm.glassEdge, RoundedCornerShape(14.dp))
                                .clickable(enabled = canTick && !saving) { ticks[item.m.id] = !(ticks[item.m.id] ?: false) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (canTick) {
                                Checkbox(
                                    checked = ticks[item.m.id] ?: false, enabled = !saving,
                                    onCheckedChange = { ticks[item.m.id] = it },
                                    colors = CheckboxDefaults.colors(checkedColor = Sm.accent, uncheckedColor = Sm.muted)
                                )
                            } else Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (ai.status == "none") tr("library.ai_ne_smog") else aiLabel(ai),
                                    color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp
                                )
                                Text(item.m.name, color = Sm.muted, fontFamily = Onest, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                OutlineButton(if (editable.isEmpty()) tr("library.zakryt") else tr("library.ai_pozzhe")) { onDismiss() }
                if (editable.isNotEmpty()) {
                    LampButton(tr("library.ai_sohranit"), enabled = !saving) {
                        saving = true
                        scope.launch {
                            lib.saveAi(editable.associate { it.m.id to (ticks[it.m.id] ?: false) })
                            saving = false
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}
