package com.openminis.app.tools

import com.openminis.app.data.repository.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool definitions and execution for memory_write and memory_get.
 * Schema matches iOS AgentToolDefinition exactly.
 */
object MemoryTools {

    // -- Tool Definitions (Anthropic format) --

    fun memoryWriteToolDefinition(): JSONObject {
        val properties = JSONObject().apply {
            put("tool_title", JSONObject().apply {
                put("type", "string")
                put("description", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Save user preference for Python', 'Note today's project context'). Use the same language as the user.")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "The memory content to write. Use concise Markdown with a short heading (## Topic) and context about what was done/learned.")
            })
            // nanoMuse: the memory this entry supersedes, so the store archives the old one
            put("replaces_id", JSONObject().apply {
                put("type", "string")
                put("description", "Optional memory ID (e.g. 'mem_...') that this entry updates, contradicts, or supersedes. If provided, the old memory is automatically archived to prevent contradictions.")
            })
        }

        return JSONObject().apply {
            put("name", "memory_write")
            put("description", "Write a memory entry to today's daily log (YYYY-MM-DD.md). Memories persist across all sessions. Each entry is prepended with a timestamp. Save: user preferences, recurring patterns, key facts, project conventions, reusable knowledge. If updating or superseding an existing memory, pass its ID in replaces_id. Avoid saving passwords, API keys, tokens, or secrets unless the user explicitly confirms after being warned. Keep entries concise and general-purpose. GLOBAL.md is read-only (user-maintained via Settings).")
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", JSONArray().apply {
                    put("tool_title")
                    put("content")
                })
            })
        }
    }

    fun memoryGetToolDefinition(): JSONObject {
        val properties = JSONObject().apply {
            put("tool_title", JSONObject().apply {
                put("type", "string")
                put("description", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Recall user preferences', 'Search past notes'). Use the same language as the user.")
            })
            put("scope", JSONObject().apply {
                put("type", "string")
                put("description", "Memory scope to search: 'daily' for daily logs only, 'all' for daily logs + GLOBAL.md.")
                put("enum", JSONArray().apply {
                    put("daily")
                    put("all")
                })
            })
            put("keywords", JSONObject().apply {
                put("type", "string")
                put("description", "Space-separated keywords for fuzzy matching (e.g. 'python preference' or 'API key setup'). All keywords must appear in a line or its surrounding context for a match. Leave empty to return full memory files.")
            })
        }

        return JSONObject().apply {
            put("name", "memory_get")
            put("description", "Retrieve memories from persistent storage. Supports keyword-based fuzzy search across memory files. Returns matching lines with surrounding context. Use this to recall previous knowledge, user preferences, or past notes.")
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", JSONArray().apply {
                    put("tool_title")
                })
            })
        }
    }

    // -- OpenAI Function Calling format --

    fun memoryWriteOpenAIDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "memory_write")
                put("description", memoryWriteToolDefinition().getString("description"))
                put("parameters", memoryWriteToolDefinition().getJSONObject("input_schema"))
            })
        }
    }

    fun memoryGetOpenAIDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "memory_get")
                put("description", memoryGetToolDefinition().getString("description"))
                put("parameters", memoryGetToolDefinition().getJSONObject("input_schema"))
            })
        }
    }

    // -- Execution --

    data class ToolResult(
        val output: String,
        val success: Boolean,
        val toolTitle: String = "",
    )

    fun executeMemoryWrite(inputJson: String, repository: MemoryRepository): ToolResult {
        return try {
            val obj = JSONObject(inputJson)
            val content = obj.optString("content", "")
            val toolTitle = obj.optString("tool_title", "memory_write")
            val replacesId = obj.optString("replaces_id", "").takeIf { it.isNotBlank() }

            if (content.isBlank()) {
                ToolResult("Error: Missing required 'content' parameter", false, toolTitle)
            } else {
                // nanoMuse: the conflict resolution lives in the structured store
                val result = repository.writeMemory(content, replacesId)
                val success = result.startsWith("Memory saved")
                ToolResult(result, success, toolTitle)
            }
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }

    fun executeMemoryGet(inputJson: String, repository: MemoryRepository): ToolResult {
        return try {
            val obj = JSONObject(inputJson)
            val keywords = obj.optString("keywords", "")
            val scope = obj.optString("scope", "all")
            val toolTitle = obj.optString("tool_title", "memory_get")

            val result = repository.getMemory(keywords, scope)
            ToolResult(result, true, toolTitle)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }
}
