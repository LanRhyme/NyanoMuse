package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.ExecutionMode
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject

object FileWriteTool {
    const val NAME = "file_write"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Write content to a file on the Android filesystem. In Root mode, full unrestricted write access across all files and directories (/sdcard, /data, /system, etc.) is supported. Faster than shell_execute for writing files. Creates the file if it doesn't exist. Use append mode to add to existing files.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Create Python statistics script', 'Write configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute path to write (e.g. /sdcard/Download/test.txt or /data/adb/modules/sample.prop)"),
            "content" to AgentToolParam("string", "The text content to write to the file"),
            "append" to AgentToolParam("boolean", "If true, append to existing file instead of overwriting (default: false)"),
            "create_dirs" to AgentToolParam("boolean", "If true, create parent directories if they don't exist (default: false)"),
        ),
        required = listOf("tool_title", "path", "content"),
        propertyOrdering = listOf("tool_title", "path", "content", "append", "create_dirs"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "").trim()
            val content = args.optString("content", "")
            val append = args.optBoolean("append", false)
            val createDirs = args.optBoolean("create_dirs", false)
            val toolTitle = args.optString("tool_title", NAME)

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
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

            // Validate UTF-8
            try {
                content.toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                return ToolExecutionResult("Error: Content is not valid UTF-8", false, toolTitle = toolTitle)
            }

            val parent = file.parentFile
            if (parent != null && (createDirs || !parent.exists())) {
                if (!parent.exists() && !parent.mkdirs() && isRootMode) {
                    mkdirWithRoot(parent.absolutePath)
                }
            }

            try {
                if (file.canWrite() || (!file.exists() && parent != null && parent.canWrite())) {
                    if (append) {
                        file.appendText(content)
                    } else {
                        file.writeText(content)
                    }
                } else if (isRootMode) {
                    writeWithRoot(file.absolutePath, content, append)
                } else {
                    if (append) file.appendText(content) else file.writeText(content)
                }
            } catch (e: Exception) {
                if (isRootMode) {
                    writeWithRoot(file.absolutePath, content, append)
                } else {
                    throw e
                }
            }

            val bytes = if (file.canRead()) file.length() else if (isRootMode) getLengthWithRoot(file.absolutePath) else content.toByteArray(Charsets.UTF_8).size.toLong()
            ToolExecutionResult("Wrote to $path ($bytes bytes)", true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error writing file: ${e.message}", false)
        }
    }

    private fun mkdirWithRoot(absolutePath: String) {
        try {
            val process = ProcessBuilder("su", "-c", "mkdir -p \"$absolutePath\"").start()
            process.waitFor()
        } catch (_: Exception) {}
    }

    private fun getLengthWithRoot(absolutePath: String): Long {
        return try {
            val p = ProcessBuilder("su", "-c", "stat -c %s \"$absolutePath\" 2>/dev/null || wc -c < \"$absolutePath\"").start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun writeWithRoot(absolutePath: String, content: String, append: Boolean) {
        val redirect = if (append) ">>" else ">"
        val process = ProcessBuilder("su", "-c", "cat $redirect \"$absolutePath\"").start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
        process.waitFor()
        if (process.exitValue() != 0) {
            val err = process.errorStream.bufferedReader().readText()
            throw RuntimeException(err.ifBlank { "Root write failed (exit code ${process.exitValue()})" })
        }
    }
}
