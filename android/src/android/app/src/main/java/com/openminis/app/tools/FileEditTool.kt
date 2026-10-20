package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.ExecutionMode
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject

object FileEditTool {
    const val NAME = "file_edit"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Make targeted edits to an existing file using exact string replacement on the Android filesystem. In Root mode, full access to all files across the system (/sdcard, /data, /system, etc.) is supported. ALWAYS use file_read first to see the current file contents before editing. Prefer file_edit over file_write when modifying existing files — only the changed part needs to be specified. The old_string must match exactly one location in the file (including whitespace/indentation), unless replace_all is true.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Fix typo in Python script', 'Update config value'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute path to the file to edit (e.g. /sdcard/Download/script.py or /data/adb/modules/sample.prop)"),
            "old_string" to AgentToolParam("string", "The exact text to find in the file. Must match precisely including whitespace and indentation. Must be unique in the file unless replace_all is true."),
            "new_string" to AgentToolParam("string", "The replacement text. Use empty string to delete old_string."),
            "replace_all" to AgentToolParam("boolean", "If true, replace ALL occurrences of old_string (default: false)"),
        ),
        required = listOf("tool_title", "path", "old_string", "new_string"),
        propertyOrdering = listOf("tool_title", "path", "old_string", "new_string", "replace_all"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "").trim()
            val oldString = args.optString("old_string", "")
            val newString = args.optString("new_string", "")
            val replaceAll = args.optBoolean("replace_all", false)
            val toolTitle = args.optString("tool_title", NAME)

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }
            if (oldString.isEmpty()) {
                return ToolExecutionResult("Error: 'old_string' is required and cannot be empty", false, toolTitle = toolTitle)
            }

            val isRootMode = ExecutionMode.getMode(context) == ExecutionMode.ROOT

            // In non-root mode, guard against read-only mounts
            if (!isRootMode && PRootKernel.isLinuxPathUnderReadOnlyMount(path)) {
                return ToolExecutionResult(
                    "Error: $path is inside a read-only mounted folder and cannot be modified. " +
                        "Toggle writability in Settings → Mount External Folders if this is a mistake.",
                    false, toolTitle = toolTitle,
                )
            }

            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            val exists = file.exists() || (isRootMode && checkExistsWithRoot(file.absolutePath))
            if (!exists) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }

            val content = try {
                if (file.canRead()) file.readText() else if (isRootMode) readTextWithRoot(file.absolutePath) else file.readText()
            } catch (e: Exception) {
                if (isRootMode) {
                    readTextWithRoot(file.absolutePath)
                } else {
                    throw e
                }
            }

            // Count occurrences
            var count = 0
            var searchFrom = 0
            while (true) {
                val idx = content.indexOf(oldString, searchFrom)
                if (idx < 0) break
                count++
                searchFrom = idx + oldString.length
            }

            // Fallback: line-ending normalization (\r\n vs \n)
            var actualOldString = oldString
            var actualContent = content
            if (count == 0 && content.contains("\r\n") && !oldString.contains("\r\n")) {
                val normalizedContent = content.replace("\r\n", "\n")
                if (normalizedContent.contains(oldString)) {
                    actualContent = normalizedContent
                    count = 0
                    searchFrom = 0
                    while (true) {
                        val idx = actualContent.indexOf(oldString, searchFrom)
                        if (idx < 0) break
                        count++
                        searchFrom = idx + oldString.length
                    }
                }
            }

            if (count == 0) {
                return ToolExecutionResult("Error: old_string not found in $path", false, toolTitle = toolTitle)
            }

            if (count > 1 && !replaceAll) {
                return ToolExecutionResult(
                    "Error: old_string found $count times in $path. Use replace_all=true to replace all occurrences, " +
                        "or provide a more specific old_string that matches exactly once.",
                    false, toolTitle = toolTitle
                )
            }

            val newContent = if (replaceAll) {
                actualContent.replace(actualOldString, newString)
            } else {
                actualContent.replaceFirst(actualOldString, newString)
            }

            try {
                if (file.canWrite()) {
                    file.writeText(newContent)
                } else if (isRootMode) {
                    writeWithRoot(file.absolutePath, newContent)
                } else {
                    file.writeText(newContent)
                }
            } catch (e: Exception) {
                if (isRootMode) {
                    writeWithRoot(file.absolutePath, newContent)
                } else {
                    throw e
                }
            }

            val replacements = if (replaceAll) count else 1
            ToolExecutionResult(
                "Edited $path ($replacements replacement(s), ${newContent.length} bytes)",
                true, toolTitle = toolTitle
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error editing file: ${e.message}", false)
        }
    }

    private fun checkExistsWithRoot(absolutePath: String): Boolean {
        return try {
            val p = ProcessBuilder("su", "-c", "test -e \"$absolutePath\"").start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun readTextWithRoot(absolutePath: String): String {
        val process = ProcessBuilder("su", "-c", "cat \"$absolutePath\"").start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        process.waitFor()
        if (process.exitValue() != 0 && output.isBlank()) {
            val err = process.errorStream.bufferedReader().readText()
            throw RuntimeException(err.ifBlank { "Root read failed (exit code ${process.exitValue()})" })
        }
        return output
    }

    private fun writeWithRoot(absolutePath: String, content: String) {
        val process = ProcessBuilder("su", "-c", "cat > \"$absolutePath\"").start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
        process.waitFor()
        if (process.exitValue() != 0) {
            val err = process.errorStream.bufferedReader().readText()
            throw RuntimeException(err.ifBlank { "Root write failed (exit code ${process.exitValue()})" })
        }
    }
}
