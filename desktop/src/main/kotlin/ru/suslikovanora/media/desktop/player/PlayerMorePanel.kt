package ru.suslikovanora.media.desktop.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.suslikovanora.media.desktop.ui.Onest
import ru.suslikovanora.media.desktop.tr
import ru.suslikovanora.media.desktop.signed

/** "⚙ Ещё": speed, subtitles, night mode, window modes, info and chapters — inline in the bar (popups would hide behind the video). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MorePanel(
    rates: List<Float>,
    rate: Float,
    onRate: (Float) -> Unit,
    subScale: Int,
    onSubScale: (Int) -> Unit,
    subDelay: Long,
    onSubDelay: (Long) -> Unit,
    night: Boolean,
    onNight: () -> Unit,
    onTop: Boolean,
    onOnTop: () -> Unit,
    mini: Boolean,
    onMini: () -> Unit,
    info: Boolean,
    onInfo: () -> Unit,
    chapters: List<Pair<Long, String>>,
    current: Int,
    onChapter: (Int) -> Unit
) {
    @Composable
    fun Label(t: String) = Text(
        t,
        color = Color.White.copy(alpha = 0.6f),
        fontFamily = Onest,
        fontSize = 12.5.sp,
        modifier = Modifier.width(92.dp)
    )

    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(tr("player.skorost"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rates.forEach { r ->
                    PlayerChip(fmtRate(r), kotlin.math.abs(r - rate) < 0.01f) { onRate(r) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(tr("player.subtitry"))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(75, 100, 125, 150, 200).forEach { v ->
                    PlayerChip("$v%", v == subScale) { if (v != subScale) onSubScale(v) }
                }
                Spacer(Modifier.width(10.dp))
                PlayerChip(tr("player.s"), false) { onSubDelay(-100L) }
                Text(
                    tr("player.p_subdelay_ms", "ms" to signed(subDelay)),
                    color = Color.White,
                    fontFamily = Onest,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 7.dp)
                )
                PlayerChip(tr("player.s_2"), false) { onSubDelay(100L) }
                if (subDelay != 0L) PlayerChip(tr("player.sbros"), false) { onSubDelay(-subDelay) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(tr("player.rezhimy"))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PlayerChip(tr("player.nochnoy_zvuk"), night) { onNight() }
                PlayerChip(tr("player.poverh_okon_t"), onTop) { onOnTop() }
                PlayerChip(tr("player.mini_pleer_o"), mini) { onMini() }
                PlayerChip(tr("player.informaciya_i"), info) { onInfo() }
            }
        }
        if (chapters.size > 1) Row(verticalAlignment = Alignment.Top) {
            Label(tr("player.glavy"))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                chapters.take(40).forEachIndexed { i, (_, name) ->
                    PlayerChip("${i + 1}. ${name.ifBlank { tr("player.glava") }.take(28)}", i == current) { onChapter(i) }
                }
            }
        }
        Text(
            tr("player.razmer_subtitrov_i_nochnoy"),
            color = Color.White.copy(alpha = 0.45f),
            fontFamily = Onest,
            fontSize = 11.5.sp
        )
    }
}
