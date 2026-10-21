package io.github.nanomuse.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractorTest {

    @Test
    fun testExtractPassiveMemoriesCorrectlyExtractsPersonalTraitsAndGuidelines() {
        // 1. Hardware / Device declaration
        val dev1 = MemoryExtractor.extractPassiveMemories("我主力电脑操作系统换成了 Arch Linux，合成器用 Niri")
        assertTrue(dev1.isNotEmpty())
        assertEquals("device", dev1[0].category)
        assertTrue(dev1[0].content.contains("操作系统使用 Arch Linux") || dev1[0].content.contains("电脑使用"))

        // 2. Residence / Location
        val loc = MemoryExtractor.extractPassiveMemories("我最近搬到了杭州市西湖区定居")
        assertEquals(1, loc.size)
        assertEquals("profile", loc[0].category)
        assertTrue(loc[0].content.contains("现居 杭州市西湖区"))

        // 3. Name / Call me
        val name = MemoryExtractor.extractPassiveMemories("叫我小明就行啦")
        assertEquals(1, name.size)
        assertEquals("profile", name[0].category)
        assertTrue(name[0].content.contains("Call them: 小明"))

        // 4. Allergies
        val allergy = MemoryExtractor.extractPassiveMemories("我对坚果严重过敏")
        assertEquals(1, allergy.size)
        assertEquals("habit", allergy[0].category)
        assertTrue(allergy[0].content.contains("坚果过敏"))

        // 5. Food / Beverage Habits
        val drink = MemoryExtractor.extractPassiveMemories("我平时只喝无糖拿铁，不喝含糖饮料")
        assertTrue(drink.isNotEmpty())
        assertTrue(drink.any { it.content.contains("只喝无糖拿铁") || it.content.contains("不喝含糖饮料") })

        // 6. Standing Directive / Communication guideline
        val guideline = MemoryExtractor.extractPassiveMemories("以后回答时不要用表情符号，保持极简")
        assertTrue(guideline.isNotEmpty())
        assertTrue(guideline[0].content.contains("沟通规范"))

        // 7. Transient commands should NOT be extracted
        val transient1 = MemoryExtractor.extractPassiveMemories("帮我看一下这段代码怎么报错了？")
        assertEquals(0, transient1.size)

        val transient2 = MemoryExtractor.extractPassiveMemories("今天天气真不错，现在几点了？")
        assertEquals(0, transient2.size)

        val transient3 = MemoryExtractor.extractPassiveMemories("/goal 查一下机票价格")
        assertEquals(0, transient3.size)
    }

    @Test
    fun testConceptSynonymExpansionRetrievesAndRanksNonLiteralSemanticMatches() {
        val candidates = listOf(
            MemoryCandidate(
                id = "mem_drink",
                content = "最爱喝无糖生椰拿铁",
                searchText = MemoryTokenizer.tokenizeForSearch("最爱喝无糖生椰拿铁"),
                importance = 0.7f,
                updatedAt = 1000L
            ),
            MemoryCandidate(
                id = "mem_pc",
                content = "PC 操作系统为 Arch Linux，合成器为 Niri",
                searchText = MemoryTokenizer.tokenizeForSearch("PC 操作系统为 Arch Linux，合成器为 Niri"),
                importance = 0.8f,
                updatedAt = 1000L
            )
        )

        // 1. Semantic query "热饮" -> recalls "拿铁"
        val drinkFts = MemoryTokenizer.buildFtsQuery("想喝什么热饮")
        assertTrue(drinkFts.contains("拿铁") || drinkFts.contains("咖啡") || drinkFts.contains("热饮"))

        val drinkMatches = MemoryScorer.rankCandidates("想喝热饮", candidates)
        assertTrue("Semantic query '热饮' should recall '拿铁'", drinkMatches.isNotEmpty())
        assertEquals("mem_drink", drinkMatches[0].item.id)

        // 2. Semantic query "电脑系统" -> recalls "PC 操作系统"
        val pcMatches = MemoryScorer.rankCandidates("电脑系统配置", candidates)
        assertTrue("Semantic query '电脑' should recall 'PC'", pcMatches.isNotEmpty())
        assertEquals("mem_pc", pcMatches[0].item.id)
    }

    @Test
    fun testAValueThatReadsLikeAPhoneOrAMailIsNotKept() {
        // a phone number said in passing would travel to every device and into the prompt
        assertEquals(0, MemoryExtractor.extractPassiveMemories("我手机是 74955953457").size)
        assertEquals(0, MemoryExtractor.extractPassiveMemories("我电脑是 74955953457@qq.com").size)
        // a line that carries both keeps the harmless half
        val mixed = MemoryExtractor.extractPassiveMemories("我的邮箱是 lanrhyme@example.com，电脑是 MacBook Pro")
        assertEquals(1, mixed.size)
        assertEquals("电脑使用 MacBook Pro", mixed[0].content)
        // short digits are device names, not secrets
        assertEquals(1, MemoryExtractor.extractPassiveMemories("我手机是 Pixel 8").size)
        assertEquals(1, MemoryExtractor.extractPassiveMemories("我住在北京市海淀区 100871").size)
        assertEquals(1, MemoryExtractor.extractPassiveMemories("我电脑是 2008 年买的").size)
    }
}
