package io.github.nanomuse.ui.sticker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.openminis.app.ui.chat.FlatChatItem
import java.io.File

/**
 * Modern borderless meme/sticker bubble for chat conversations.
 * Supports static and animated GIF stickers seamlessly.
 */
@Composable
internal fun MemeBubble(
    item: FlatChatItem.NanoMuseMeme,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(File(item.filePath))
                .crossfade(true)
                .build(),
            contentDescription = item.name.ifBlank { "Meme Sticker" },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .wrapContentWidth()
                .sizeIn(minWidth = 80.dp, minHeight = 80.dp, maxWidth = 160.dp, maxHeight = 160.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = onTap != null) { onTap?.invoke() }
        )
    }
}
