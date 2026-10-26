package io.github.nanomuse.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionFamiliesTest {

    @Test fun `deepseek sees from v4_1 on, or when the name says so`() {
        assertTrue(VisionFamilies.looksLikeItSees("deepseek-v4.1-flash"))
        assertTrue(VisionFamilies.looksLikeItSees("deepseek/deepseek-v4.1-flash"))
        assertTrue(VisionFamilies.looksLikeItSees("deepseek-v4.2-pro"))
        assertTrue(VisionFamilies.looksLikeItSees("deepseek-v5"))
        assertTrue(VisionFamilies.looksLikeItSees("deepseek-vision"))
        assertTrue(VisionFamilies.looksLikeItSees("deepseek-ocr"))
        assertTrue(VisionFamilies.looksLikeItSees("deepseek-vl2"))
        assertFalse(VisionFamilies.looksLikeItSees("deepseek-v4-pro"))
        assertFalse(VisionFamilies.looksLikeItSees("deepseek-v4"))
        assertFalse(VisionFamilies.looksLikeItSees("deepseek-v3.2"))
        assertFalse(VisionFamilies.looksLikeItSees("deepseek-chat"))
        assertFalse(VisionFamilies.looksLikeItSees("deepseek-reasoner"))
        assertFalse(VisionFamilies.looksLikeItSees("deepseek-r1"))
    }

    @Test fun `the opencode models that take a picture are read as sighted`() {
        // OpenCode Zen and Go: minimax-m3 and kimi-k3 accept an image on the OpenAI shape
        // (checked against the vendor), while the earlier MiniMax M-series do not.
        assertTrue(VisionFamilies.looksLikeItSees("minimax-m3"))
        assertTrue(VisionFamilies.looksLikeItSees("kimi-k3"))
        assertTrue(VisionFamilies.looksLikeItSees("nemotron-3.5-super-vl-free"))
        assertFalse(VisionFamilies.looksLikeItSees("minimax-m2.5"))
        assertFalse(VisionFamilies.looksLikeItSees("minimax-m2.7"))
    }

    @Test fun `the hands default and the rest of the list still read as before`() {
        assertTrue(VisionFamilies.looksLikeItSees("qwen3.8-27b"))
        assertTrue(VisionFamilies.looksLikeItSees("qwen/qwen3.8-27b"))
        assertTrue(VisionFamilies.looksLikeItSees("qwen2.5-vl-7b"))
        assertTrue(VisionFamilies.looksLikeItSees("gpt-4o-mini"))
        assertTrue(VisionFamilies.looksLikeItSees("o3"))
        assertFalse(VisionFamilies.looksLikeItSees("qwen3-coder-plus"))
        assertFalse(VisionFamilies.looksLikeItSees("text-embedding-3-small"))
        assertFalse(VisionFamilies.looksLikeItSees("moonshot-v1-8k"))
    }
}
