package io.github.nanomuse.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection
import io.github.nanomuse.cloud.CatalogueProvider
import io.github.nanomuse.cloud.ProviderCatalogue
import io.github.nanomuse.cloud.Region

/**
 * "Add a provider" gets the rest of the own-key catalogue: OpenCode Zen and OpenCode Go,
 * 阿里云百炼, DeepSeek, GLM, SiliconFlow, Volcengine, MiniMax, Groq, Mistral, Standard Compute.
 * Each row opens the same form the allowance card's deep link opens
 * ([io.github.nanomuse.cloud.OwnKeyPresets.template]) with the vendor's name, public endpoint
 * and key page filled in, so a person who wants OpenCode finds it where every other provider
 * is, not only after the allowance runs out.
 *
 * The vendors the screen's own protocol rows already cover are left out: OpenAI, Anthropic,
 * Gemini, xAI, Kimi and OpenRouter are picked by protocol there.
 */
internal fun catalogueChoices(
    list: List<CatalogueProvider>,
    mainland: Boolean,
    excluded: Set<String>,
): List<CatalogueProvider> {
    val skip = excluded + ProviderCatalogue.CUSTOM
    // `ordered` already drops the servers one runs oneself and the blank "any endpoint" entry
    return ProviderCatalogue.ordered(list, mainland)
        .filter { it.id !in skip && it.baseUrl.isNotBlank() && it.takesKey }
}

/** The section itself: a header and one row per vendor, the region's first as the card orders them. */
@Composable
fun CatalogueProviderSection(
    excluded: Set<String>,
    onPick: (CatalogueProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val chinese = ProviderCatalogue.chinese(context)
    val mainland = Region.mainland(context)
    val choices = remember(mainland, excluded) {
        catalogueChoices(ProviderCatalogue.load(context), mainland, excluded)
    }
    if (choices.isEmpty()) return
    SettingsSection(header = stringResource(R.string.nm_add_provider_more), modifier = modifier) {
        choices.forEachIndexed { index, vendor ->
            SettingsRow(
                title = vendor.displayName(chinese),
                subtitle = vendor.note(chinese).lineSequence().firstOrNull()?.take(110),
                icon = Icons.Default.Cloud,
                onClick = { onPick(vendor) },
                showDivider = index < choices.lastIndex,
            )
        }
    }
}
