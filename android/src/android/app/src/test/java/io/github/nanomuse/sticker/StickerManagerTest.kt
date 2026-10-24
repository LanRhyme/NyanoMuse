package io.github.nanomuse.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerManagerTest {

    @Test
    fun testHasMemeTag() {
        assertTrue(StickerManager.hasMemeTag("<nm:meme id=\"001\"/>"))
        assertTrue(StickerManager.hasMemeTag("<nm:meme id='042' />"))
        assertTrue(StickerManager.hasMemeTag("确实！<nm:meme id=\"005\"/>哈哈"))
        assertTrue(StickerManager.hasMemeTag("[表情: 猫猫举爪]"))
        assertTrue(StickerManager.hasMemeTag("[meme:001]"))

        assertFalse(StickerManager.hasMemeTag("普通的文字对话"))
        assertFalse(StickerManager.hasMemeTag("<nm:bubble/>"))
        assertFalse(StickerManager.hasMemeTag("<nm:mood feel=\"happy\"/>"))
    }

    @Test
    fun testFindMemeMatch() {
        val match1 = StickerManager.findMemeMatch("<nm:meme id=\"001\"/>")
        assertNotNull(match1)
        val q1 = match1!!.groupValues.drop(1).firstOrNull { it.isNotBlank() }
        assertEquals("001", q1)

        val match2 = StickerManager.findMemeMatch("太喵啦！<nm:bubble/><nm:meme id=\"003\"/>")
        assertNotNull(match2)
        val q2 = match2!!.groupValues.drop(1).firstOrNull { it.isNotBlank() }
        assertEquals("003", q2)

        val match3 = StickerManager.findMemeMatch("[表情: 给花花]")
        assertNotNull(match3)
        val q3 = match3!!.groupValues.drop(1).firstOrNull { it.isNotBlank() }
        assertEquals("给花花", q3)
    }
}
