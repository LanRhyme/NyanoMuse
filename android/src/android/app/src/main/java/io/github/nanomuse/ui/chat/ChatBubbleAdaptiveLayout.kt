package io.github.nanomuse.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Telegram-style double checkmark indicating delivered / read status.
 */
@Composable
fun TelegramDoubleCheck(
    modifier: Modifier = Modifier,
    tint: Color,
) {
    Box(
        modifier = modifier.size(width = 15.dp, height = 11.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(11.dp)
                .offset(x = 0.dp),
        )
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(11.dp)
                .offset(x = 4.dp),
        )
    }
}

/**
 * Adaptive bubble layout that places content and timestamp on the same line (inline)
 * for single-line / short messages, eliminating awkward blank space, and places
 * the timestamp on an independent row at the bottom right for multi-line content.
 */
@Composable
fun ChatBubbleAdaptiveLayout(
    modifier: Modifier = Modifier,
    isSingleLineCandidate: Boolean,
    gap: Dp = 6.dp,
    bottomSpacing: Dp = 0.dp,
    content: @Composable () -> Unit,
    timestamp: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            content()
            timestamp()
        },
    ) { measurables, constraints ->
        if (measurables.isEmpty()) {
            return@Layout layout(0, 0) {}
        }
        if (measurables.size == 1) {
            val placeable = measurables[0].measure(constraints)
            return@Layout layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, 0)
            }
        }
        val gapPx = gap.roundToPx()
        val bottomSpacingPx = bottomSpacing.roundToPx()

        val timePlaceable = measurables[1].measure(
            constraints.copy(minWidth = 0, minHeight = 0),
        )

        val contentPlaceable = measurables[0].measure(
            constraints.copy(minWidth = 0, minHeight = 0),
        )

        if (timePlaceable.width == 0 && timePlaceable.height == 0) {
            return@Layout layout(contentPlaceable.width, contentPlaceable.height) {
                contentPlaceable.placeRelative(0, 0)
            }
        }

        // Fast horizontal check: if it cannot fit horizontally with the timestamp,
        // it cannot be a single-line message inline.
        val fitsHorizontal = isSingleLineCandidate &&
            (contentPlaceable.width + gapPx + timePlaceable.width <= constraints.maxWidth)

        // Single line check:
        // When content has multiple lines, FirstBaseline != LastBaseline.
        // When content has exactly one line, FirstBaseline == LastBaseline.
        // This is computed in O(1) from the already-measured placeable, eliminating secondary intrinsic passes.
        val isSingleLine = if (fitsHorizontal) {
            val contentFirstBaseline = contentPlaceable[FirstBaseline]
            val contentLastBaseline = contentPlaceable[LastBaseline]
            if (contentFirstBaseline != AlignmentLine.Unspecified && contentLastBaseline != AlignmentLine.Unspecified) {
                contentFirstBaseline == contentLastBaseline
            } else {
                val singleLineHeight = measurables[0].minIntrinsicHeight(Int.MAX_VALUE)
                contentPlaceable.height <= singleLineHeight + 2
            }
        } else {
            false
        }

        if (isSingleLine) {
            val totalWidth = contentPlaceable.width + gapPx + timePlaceable.width
            val contentBaseline = contentPlaceable[FirstBaseline]
            val timeBaseline = timePlaceable[FirstBaseline]

            val (contentY, timeY, totalHeight) = if (
                contentBaseline != AlignmentLine.Unspecified &&
                timeBaseline != AlignmentLine.Unspecified
            ) {
                val maxBaseline = maxOf(contentBaseline, timeBaseline)
                val cY = maxBaseline - contentBaseline
                val tY = maxBaseline - timeBaseline
                val h = maxOf(cY + contentPlaceable.height, tY + timePlaceable.height)
                Triple(cY, tY, h)
            } else {
                val h = maxOf(contentPlaceable.height, timePlaceable.height)
                Triple(h - contentPlaceable.height, h - timePlaceable.height, h)
            }

            layout(totalWidth, totalHeight) {
                contentPlaceable.placeRelative(0, contentY)
                val timeX = contentPlaceable.width + gapPx
                timePlaceable.placeRelative(timeX, timeY)
            }
        } else {
            val totalWidth = maxOf(contentPlaceable.width, timePlaceable.width)
            val totalHeight = contentPlaceable.height + bottomSpacingPx + timePlaceable.height
            layout(totalWidth, totalHeight) {
                contentPlaceable.placeRelative(0, 0)
                val timeX = totalWidth - timePlaceable.width
                val timeY = contentPlaceable.height + bottomSpacingPx
                timePlaceable.placeRelative(timeX, timeY)
            }
        }
    }
}
