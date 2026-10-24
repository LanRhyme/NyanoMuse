package io.github.nanomuse.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which rows of an OpenMinis chat are the conversation the other devices see. */
class TranscriptTest {
    private val chats = MemoryChats()

    private fun items(sessionId: String) = Transcript.items(chats.rows.getValue(sessionId).sortedBy { it.createdAt })

    @Test fun `a plain turn is the question and the answer`() {
        chats.addSession("s")
        chats.user("s", "What time is it?")
        chats.assistant("s", "Half past three.")
        val out = items("s")
        assertEquals(listOf("user" to "What time is it?", "assistant" to "Half past three."), out.map { it.role to it.text })
    }

    @Test fun `tool steps are skipped and only the final reply goes`() {
        chats.addSession("s")
        chats.user("s", "list my downloads")
        chats.assistantTool("s", "Let me look.")
        chats.toolResult("s")
        chats.assistantTool("s", "")
        chats.toolResult("s")
        chats.assistant("s", "Three files: a, b, c.")
        val out = items("s")
        assertEquals(listOf("user" to "list my downloads", "assistant" to "Three files: a, b, c."), out.map { it.role to it.text })
    }

    @Test fun `a turn still running or stopped mid-way sends the question only`() {
        chats.addSession("s")
        chats.user("s", "do the thing")
        chats.assistantTool("s", "On it.")
        chats.toolResult("s")
        assertEquals(listOf("user"), items("s").map { it.role })
        // the next question closes the turn: its last text, if any, goes along
        chats.user("s", "never mind")
        chats.assistant("s", "Stopped.")
        assertEquals(listOf("user" to "do the thing", "assistant" to "On it.", "user" to "never mind", "assistant" to "Stopped."), items("s").map { it.role to it.text })
    }

    @Test fun `pictures ride as names, system reminders are stripped`() {
        chats.addSession("s")
        val id = chats.userWithPicture("s", "<system-reminder>nudge</system-reminder>what is this?", "photo.png")
        chats.assistant("s", "A cat.")
        val out = items("s")
        assertEquals(id, out[0].messageId)
        assertEquals("what is this?", out[0].text)
        assertEquals(listOf(Attachment("photo.png", "image/png", 0)), out[0].attachments)
        assertTrue(out[1].attachments.isEmpty())
    }

    @Test fun `the continue nudge is not a message of the person`() {
        chats.addSession("s")
        chats.user("s", "go")
        chats.assistantTool("s", "")
        chats.user("s", "The user stopped the previous response. Continue.")
        chats.assistant("s", "Done.")
        assertEquals(listOf("user" to "go", "assistant" to "Done."), items("s").map { it.role to it.text })
    }

    @Test fun `pulled parts are one text part with the attachment lines`() {
        val arr = org.json.JSONArray(Transcript.partsJson("hello", listOf("[Attachment a.pdf]")))
        assertEquals(1, arr.length())
        assertEquals("text", arr.getJSONObject(0).getString("type"))
        assertEquals("hello\n\n[Attachment a.pdf]", arr.getJSONObject(0).getString("value"))
        val plain = org.json.JSONArray(Transcript.partsJson("hello", emptyList()))
        assertEquals("hello", plain.getJSONObject(0).getString("value"))
    }

    @Test fun `assistant question tool call is included as question tag and not dropped while pending`() {
        chats.addSession("s_q")
        chats.user("s_q", "Help me choose an architecture")
        val qJson = """{"question":"Which do you prefer?","options":["MVVM","MVI"]}"""
        chats.assistantQuestion("s_q", "Sure, let's decide:", qJson)

        val out = items("s_q")
        assertEquals(2, out.size)
        assertEquals("user", out[0].role)
        assertEquals("Help me choose an architecture", out[0].text)
        assertEquals("assistant", out[1].role)
        assertTrue(out[1].text.contains("<question>$qJson</question>"))
    }

    @Test fun `the bubble delimiter the phone renders with is not sent to another device`() {
        // The delimiter stays in the local DB so the split survives a reload and so the
        // model reads its own tagged turns back — but iOS renders it as literal text, so
        // it comes off on the way out.
        chats.addSession("s_b")
        chats.user("s_b", "在吗")
        chats.assistant("s_b", "在呀<nm:bubble/>怎么啦")
        val out = items("s_b")
        assertEquals(listOf("user" to "在吗", "assistant" to "在呀\n\n怎么啦"), out.map { it.role to it.text })
    }

    @Test fun `assistant question tool call answered retains both question and user answer in transcript`() {
        chats.addSession("s_qa")
        chats.user("s_qa", "Help me choose an architecture")
        val qJson = """{"question":"Which do you prefer?","options":["MVVM","MVI"]}"""
        chats.assistantQuestion("s_qa", "Sure, let's decide:", qJson)
        chats.questionResult("s_qa", "MVVM")
        chats.assistant("s_qa", "Great choice, MVVM it is.")

        val out = items("s_qa")
        assertEquals(4, out.size)
        assertEquals("user" to "Help me choose an architecture", out[0].role to out[0].text)
        assertEquals("assistant", out[1].role)
        assertTrue(out[1].text.contains("<question>$qJson</question>"))
        assertEquals("user" to "MVVM", out[2].role to out[2].text)
        assertEquals("assistant" to "Great choice, MVVM it is.", out[3].role to out[3].text)
    }
}
