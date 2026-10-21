package io.github.nanomuse.ui.chat

import com.openminis.app.tools.AgentTools
import com.openminis.app.ui.chat.ChatViewModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionCardTest {

    @Test
    fun `parseFromToolArgs handles standard questions array`() {
        val json = """
            {
              "questions": [
                {
                  "question": "Which architecture do you prefer?",
                  "options": ["(Recommended) MVVM", "MVI", "Clean Architecture"],
                  "is_multi_select": false
                }
              ]
            }
        """.trimIndent()

        val parsed = QuestionCardParser.parseFromToolArgs(json)
        assertNotNull(parsed)
        assertEquals("Which architecture do you prefer?", parsed?.question)
        assertEquals(3, parsed?.options?.size)
        assertEquals(false, parsed?.isMultiSelect)
    }

    @Test
    fun `parseFromToolArgs handles stringified questions array`() {
        val json = """
            {
              "questions": "[{\"question\":\"Which city?\",\"options\":[\"Beijing\",\"Shanghai\"]}]"
            }
        """.trimIndent()

        val parsed = QuestionCardParser.parseFromToolArgs(json)
        assertNotNull(parsed)
        assertEquals("Which city?", parsed?.question)
        assertEquals(listOf("Beijing", "Shanghai"), parsed?.options)
    }

    @Test
    fun `parseFromToolArgs handles stringified single question object`() {
        val json = """
            {
              "questions": "{\"question\":\"Are you sure?\",\"options\":[\"Yes\",\"No\"]}"
            }
        """.trimIndent()

        val parsed = QuestionCardParser.parseFromToolArgs(json)
        assertNotNull(parsed)
        assertEquals("Are you sure?", parsed?.question)
        assertEquals(listOf("Yes", "No"), parsed?.options)
    }

    @Test
    fun `parseFromToolArgs handles direct root question and options`() {
        val json = """
            {
              "question": "Choose an option",
              "options": ["A", "B", "C"]
            }
        """.trimIndent()

        val parsed = QuestionCardParser.parseFromToolArgs(json)
        assertNotNull(parsed)
        assertEquals("Choose an option", parsed?.question)
        assertEquals(listOf("A", "B", "C"), parsed?.options)
    }

    @Test
    fun `parseFromToolArgs handles prompt and choices aliases`() {
        val json = """
            {
              "prompt": "Select target platform",
              "choices": ["Android", "Desktop", "Web"]
            }
        """.trimIndent()

        val parsed = QuestionCardParser.parseFromToolArgs(json)
        assertNotNull(parsed)
        assertEquals("Select target platform", parsed?.question)
        assertEquals(listOf("Android", "Desktop", "Web"), parsed?.options)
    }

    @Test
    fun `parseFromToolArgs handles object options with text and descriptions`() {
        val json = """
            {
              "question": "Choose a mode",
              "options": [
                {"label": "Fast", "description": "Quick execution"},
                {"text": "Safe"}
              ]
            }
        """.trimIndent()

        val parsed = QuestionCardParser.parseFromToolArgs(json)
        assertNotNull(parsed)
        assertEquals("Choose a mode", parsed?.question)
        assertEquals(listOf("Fast - Quick execution", "Safe"), parsed?.options)
    }

    @Test
    fun `parseFromToolArgs handles markdown code fences`() {
        val json = "```json\n{\"question\": \"Continue?\", \"options\": [\"Yes\", \"No\"]}\n```"

        val parsed = QuestionCardParser.parseFromToolArgs(json)
        assertNotNull(parsed)
        assertEquals("Continue?", parsed?.question)
        assertEquals(listOf("Yes", "No"), parsed?.options)
    }

    @Test
    fun `parseFromToolArgs provides fallback options if options are empty`() {
        val json = """{"question": "Do you want to proceed?"}"""

        val parsed = QuestionCardParser.parseFromToolArgs(json)
        assertNotNull(parsed)
        assertEquals("Do you want to proceed?", parsed?.question)
        assertTrue(parsed?.options?.isNotEmpty() == true)
    }

    @Test
    fun `extractQuestion extracts question from tag`() {
        val text = "Please see this:\n<question>{\"question\":\"Confirm change?\",\"options\":[\"OK\",\"Cancel\"]}</question>\nThanks"

        val (cleaned, parsed) = QuestionCardParser.extractQuestion(text)
        assertNotNull(parsed)
        assertEquals("Confirm change?", parsed?.question)
        assertEquals(listOf("OK", "Cancel"), parsed?.options)
        assertTrue(cleaned.contains("Please see this:"))
        assertTrue(cleaned.contains("Thanks"))
    }

    @Test
    fun `preflight allows ask_user_question with direct question parameter`() {
        val tool = AgentTools.askUserQuestionDefinition()
        val error = ChatViewModel.preflightValidateToolCallImpl(
            name = "ask_user_question",
            args = JSONObject("""{"question": "Proceed?", "options": ["Yes", "No"]}"""),
            tools = listOf(tool),
        )
        assertNull(error)
    }

    @Test
    fun `parseFromToolArgs detects multi select flags`() {
        val jsonSnake = """
            {
              "question": "Select features",
              "options": ["A", "B", "C"],
              "is_multi_select": true
            }
        """.trimIndent()
        assertEquals(true, QuestionCardParser.parseFromToolArgs(jsonSnake)?.isMultiSelect)

        val jsonCamel = """
            {
              "questions": [
                {
                  "question": "Select features",
                  "options": ["A", "B"],
                  "multiSelect": true
                }
              ]
            }
        """.trimIndent()
        assertEquals(true, QuestionCardParser.parseFromToolArgs(jsonCamel)?.isMultiSelect)
    }

    @Test
    fun `extractQuestion handles questions array and code fence inside question tag`() {
        val text = "Preamble\n<question>\n```json\n{\"questions\": [{\"question\": \"Deploy where?\", \"options\": [\"Cloud\", \"Edge\"]}]}\n```\n</question>\nPostscript"
        val (cleaned, parsed) = QuestionCardParser.extractQuestion(text)
        assertNotNull(parsed)
        assertEquals("Deploy where?", parsed?.question)
        assertEquals(listOf("Cloud", "Edge"), parsed?.options)
        assertTrue(cleaned.contains("Preamble"))
        assertTrue(cleaned.contains("Postscript"))
    }
}

