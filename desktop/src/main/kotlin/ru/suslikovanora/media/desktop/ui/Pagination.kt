package ru.suslikovanora.media.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object PaginationHelper {
    /**
     * Generates a clean list of page numbers and ellipsis markers (-1).
     * For example, for total = 10 and current = 5: [1, -1, 4, 5, 6, -1, 10].
     */
    fun computePageRange(current: Int, total: Int): List<Int> {
        if (total <= 7) return (1..total).toList()
        return when {
            current <= 4 -> listOf(1, 2, 3, 4, 5, -1, total)
            current >= total - 3 -> listOf(1, -1, total - 4, total - 3, total - 2, total - 1, total)
            else -> listOf(1, -1, current - 1, current, current + 1, -1, total)
        }
    }
}

@Composable
fun PageButton(
    label: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val scale = if (enabled) hoverScale(hovered, pressed, 1.03f) else 1f

    val bg by animateColorAsState(
        when {
            selected -> Color(0xFF282E3C)
            !enabled -> Color.Transparent
            hovered -> Color(0xFF1F2432)
            else -> Color(0xFF161A24)
        },
        tween(180)
    )
    val border by animateColorAsState(
        when {
            selected -> Sm.accent.copy(alpha = 0.85f)
            !enabled -> Color.Transparent
            hovered -> Sm.line2
            else -> Sm.line
        },
        tween(180)
    )
    val fg by animateColorAsState(
        when {
            selected -> Sm.accent
            !enabled -> Color(0xFF4A5265)
            hovered -> Color.White
            else -> Color(0xFFC0C7D5)
        },
        tween(180)
    )

    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.hoverClick(src, onClick = onClick) else Modifier)
            .heightIn(min = 34.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = fg,
            fontFamily = Onest,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp
        )
    }
}

/**
 * Modern pagination bar with page tabs, previous/next buttons and release count summary.
 */
@Composable
fun PaginationBar(
    currentPage: Int,
    totalPages: Int,
    totalItems: Int,
    fromIndex: Int,
    toIndex: Int,
    pageSize: Int = 25,
    onPageSizeChange: ((Int) -> Unit)? = null,
    onPageSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (totalPages <= 1 && totalItems <= 0) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF141720))
            .border(1.dp, Color(0xFF242A38), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left info & page size switch
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Раздачи ${fromIndex + 1}–$toIndex из $totalItems",
                color = Sm.muted,
                fontFamily = Onest,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )

            if (onPageSizeChange != null && totalItems > 25) {
                Text("·", color = Sm.line2, fontFamily = Onest, fontSize = 13.sp)
                Text("Показывать по:", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
                for (s in listOf(25, 50)) {
                    val isCur = pageSize == s
                    Text(
                        text = "$s",
                        color = if (isCur) Sm.accent else Sm.muted,
                        fontFamily = Onest,
                        fontWeight = if (isCur) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.5.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isCur) Color(0xFF282E3C) else Color.Transparent)
                            .then(if (!isCur) Modifier.clickable { onPageSizeChange(s) } else Modifier)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // Right side: Tabs with page numbers
        if (totalPages > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PageButton(
                    label = "‹ Назад",
                    enabled = currentPage > 1,
                    selected = false,
                    onClick = { onPageSelect(currentPage - 1) }
                )

                val pageNumbers = remember(currentPage, totalPages) {
                    PaginationHelper.computePageRange(currentPage, totalPages)
                }

                for (p in pageNumbers) {
                    if (p == -1) {
                        Text(
                            "…",
                            color = Sm.muted,
                            fontFamily = Onest,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 3.dp)
                        )
                    } else {
                        PageButton(
                            label = p.toString(),
                            enabled = true,
                            selected = p == currentPage,
                            onClick = { onPageSelect(p) }
                        )
                    }
                }

                PageButton(
                    label = "Вперед ›",
                    enabled = currentPage < totalPages,
                    selected = false,
                    onClick = { onPageSelect(currentPage + 1) }
                )
            }
        }
    }
}
