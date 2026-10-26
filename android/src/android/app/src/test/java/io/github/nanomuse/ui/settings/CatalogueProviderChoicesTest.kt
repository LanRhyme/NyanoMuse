package io.github.nanomuse.ui.settings

import io.github.nanomuse.cloud.CatalogueProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "More providers" section of the add-a-provider screen: the rest of the own-key
 * catalogue, so OpenCode Zen and OpenCode Go are added where every other provider is. What
 * is left out matters as much as what is in: the vendors the protocol rows already cover,
 * the servers one runs oneself, and the blank "any endpoint" row.
 */
class CatalogueProviderChoicesTest {

    private fun vendor(
        id: String,
        name: String = id,
        baseUrl: String = "https://example.com/v1",
        regions: List<String> = listOf("global"),
        auth: List<String> = listOf("key"),
        capabilities: List<String> = listOf("chat"),
    ) = CatalogueProvider(
        id = id,
        name = name,
        nameZh = name,
        protocol = "openai",
        baseUrl = baseUrl,
        baseUrlGlobal = null,
        keyUrl = "https://example.com/keys",
        keyUrlGlobal = null,
        keyHint = null,
        auth = auth,
        authCapabilities = emptyMap(),
        regions = regions.toSet(),
        capabilities = capabilities.toSet(),
        userCapabilities = false,
        defaults = emptyMap(),
        note = "A note.",
        noteZh = "一句说明。",
        verified = "2026-10",
    )

    private val catalogue = listOf(
        vendor("bailian", regions = listOf("cn")),
        vendor("openai"),
        vendor("openrouter"),
        vendor("opencode-zen", baseUrl = "https://opencode.ai/zen/v1"),
        vendor("opencode-go", baseUrl = "https://opencode.ai/zen/go/v1"),
        vendor("deepseek", regions = listOf("cn", "global")),
        vendor("ollama", baseUrl = "http://127.0.0.1:11434/v1", auth = listOf("none")),
        vendor("custom", baseUrl = ""),
        vendor("signin-only", auth = listOf("oauth-chatgpt")),
    )

    private val covered = setOf("openai", "anthropic", "gemini", "xai", "moonshot", "openrouter")

    @Test
    fun `opencode is offered from the mainland and from elsewhere`() {
        for (mainland in listOf(true, false)) {
            val ids = catalogueChoices(catalogue, mainland, covered).map { it.id }
            assertTrue("opencode-zen for mainland=$mainland", ids.contains("opencode-zen"))
            assertTrue("opencode-go for mainland=$mainland", ids.contains("opencode-go"))
        }
    }

    @Test
    fun `the protocol rows' vendors, the local servers and the blank entry are left out`() {
        val ids = catalogueChoices(catalogue, true, covered).map { it.id }.toSet()
        for (id in listOf("openai", "openrouter", "ollama", "custom", "signin-only")) {
            assertFalse(id, ids.contains(id))
        }
    }

    @Test
    fun `the region's own vendors come first, then the rest`() {
        val mainland = catalogueChoices(catalogue, true, covered).map { it.id }
        assertEquals("bailian", mainland.first())
        val global = catalogueChoices(catalogue, false, covered).map { it.id }
        assertFalse(global.first() == "bailian")
        assertTrue(global.contains("bailian"))
    }

    @Test
    fun `a vendor with no endpoint is not offered`() {
        val ids = catalogueChoices(catalogue + vendor("blank", baseUrl = ""), true, covered).map { it.id }
        assertFalse(ids.contains("blank"))
    }
}
