package io.github.nanomuse.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Adaptive bubble layout that places content and timestamp on the same line (inline)
 * for single-line / short messages, eliminating awkward blank space, and places
 * the timestamp on an independent row at the bottom right for multi-line content.
 */
@Composable
fun ChatBubbleAdaptiveLayout(
    modifier: Modifier = Modifier,
    isSingleLineCandidate: Boolean,
    gap: Dp = 8.dp,
    bottomSpacing: Dp = 2.dp,
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

        // Single line check:
        // Must be a candidate (no newlines/complex blocks), fits horizontally with time and gap,
        // and height must not exceed the single-line intrinsic height.
        val singleLineHeight = if (isSingleLineCandidate) {
            measurables[0].minIntrinsicHeight(Int.MAX_VALUE)
        } else {
            0
        }

        val isSingleLine = isSingleLineCandidate &&
            (contentPlaceable.height <= singleLineHeight + 2) &&
            (contentPlaceable.width + gapPx + timePlaceable.width <= constraints.maxWidth)

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
