package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.ExecutionMode
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

object FileReadTool {
    const val NAME = "file_read"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a file from the Android filesystem. In Root mode, full unrestricted access to all files (/sdcard, /data, /system, etc.) is supported. Faster than shell_execute for reading files — no shell overhead. Returns file content with metadata. Rejects binary files.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Read Python script contents', 'Check system configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to read (e.g. /sdcard/Download/data.csv or /data/adb/modules/sample.prop)"),
            "offset" to AgentToolParam("integer", "1-based line number to start reading from (default: 1). Ignored when direction is 'tail'. If a previous read was truncated, its header ends with next_offset=N — pass that as offset to continue from where it stopped."),
            "lines" to AgentToolParam("integer", "Maximum number of lines to return (default: all lines up to max_length)"),
            "max_length" to AgentToolParam("integer", "Maximum character length of returned content (default: 15000)"),
            "direction" to AgentToolParam("string", "Read direction: 'head' (from start, default) or 'tail' (from end of file)"),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "offset", "lines", "direction", "max_length"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "").trim()
            val toolTitle = args.optString("tool_title", NAME)
            val offset = args.optInt("offset", 1).coerceAtLeast(1)
            val MAX_LENGTH_HARD_CAP = 80_000
            val maxLength = args.optInt("max_length", 15000).coerceAtMost(MAX_LENGTH_HARD_CAP)
            val direction = args.optString("direction", "head")

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            val isRootMode = ExecutionMode.getMode(context) == ExecutionMode.ROOT
            val exists = file.exists() || (isRootMode && checkExistsWithRoot(file.absolutePath))
            if (!exists) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }

            val isDirectory = file.isDirectory || (isRootMode && checkIsDirWithRoot(file.absolutePath))
            if (isDirectory) {
                return ToolExecutionResult(
                    "Error: Path is a directory: $path. Use shell_execute ('ls -la \"$path\"') to view directory contents.",
                    false, toolTitle = toolTitle,
                )
            }

            val size = if (file.canRead()) file.length() else if (isRootMode) getLengthWithRoot(file.absolutePath) else file.length()

            // Binary detection: check first 8192 bytes for null bytes
            val isBinary = if (file.canRead()) {
                try {
                    file.inputStream().use { input ->
                        val buf = ByteArray(minOf(8192, size.coerceAtLeast(1L).coerceAtMost(8192L).toInt()))
                        val read = input.read(buf)
                        if (read > 0) buf.take(read).any { it == 0.toByte() } else false
                    }
                } catch (_: Exception) {
                    false
                }
            } else if (isRootMode) {
                isBinaryWithRoot(file.absolutePath)
            } else {
                false
            }

            if (isBinary) {
                return ToolExecutionResult(
                    "[$path | $size bytes | binary file — cannot display contents]",
                    true, toolTitle = toolTitle,
                )
            }

            val requestedLines = if (args.has("lines")) args.optInt("lines") else null

            val allLines = try {
                if (file.canRead()) {
                    file.readLines()
                } else if (isRootMode) {
                    readLinesWithRoot(file.absolutePath, offset, requestedLines, direction)
                } else {
                    file.readLines()
                }
            } catch (e: Exception) {
                if (isRootMode) {
                    readLinesWithRoot(file.absolutePath, offset, requestedLines, direction)
                } else {
                    throw e
                }
            }
            val totalLines = allLines.size

            val selectedLines = if (file.canRead()) {
                if (direction == "tail") {
                    val count = requestedLines ?: totalLines
                    val start = (totalLines - count).coerceAtLeast(0)
                    allLines.subList(start, totalLines)
                } else {
                    val start = (offset - 1).coerceIn(0, totalLines)
                    val end = if (requestedLines != null) {
                        (start + requestedLines).coerceAtMost(totalLines)
                    } else {
                        totalLines
                    }
                    allLines.subList(start, end)
                }
            } else {
                allLines
            }

            val showStart = if (direction == "tail") {
                (totalLines - selectedLines.size) + 1
            } else {
                offset
            }
            val showEnd = showStart + selectedLines.size - 1

            var content = selectedLines.joinToString("\n")

            var effectiveStart = showStart
            var effectiveEnd = showEnd
            var nextOffset: Int? = null
            var wasTruncated = false
            if (content.length > maxLength) {
                wasTruncated = true
                if (direction == "tail") {
                    content = content.takeLast(maxLength)
                    val firstNewline = content.indexOf('\n')
                    if (firstNewline in 0 until content.length - 1) {
                        content = content.substring(firstNewline + 1)
                    }
                    effectiveStart = effectiveEnd - content.count { it == '\n' }
                } else {
                    content = content.take(maxLength)
                    val lastNewline = content.lastIndexOf('\n')
                    if (lastNewline > 0) content = content.substring(0, lastNewline)
                    effectiveEnd = showStart + content.count { it == '\n' }
                    if (effectiveEnd < totalLines) nextOffset = effectiveEnd + 1
                }
            }

            var header = "[$path | $size bytes | $totalLines lines | " +
                "showing $effectiveStart-$effectiveEnd of $totalLines"
            if (wasTruncated) {
                header += " | truncated at $maxLength chars"
                header += if (nextOffset != null) ", next_offset=$nextOffset"
                          else ", retry with a smaller lines value"
            }
            header += "]"
            ToolExecutionResult("$header\n$content", true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error reading file: ${e.message}", false)
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

    private fun checkIsDirWithRoot(absolutePath: String): Boolean {
        return try {
            val p = ProcessBuilder("su", "-c", "test -d \"$absolutePath\"").start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
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

    private fun isBinaryWithRoot(absolutePath: String): Boolean {
        return try {
            val p = ProcessBuilder("su", "-c", "head -c 8192 \"$absolutePath\"").start()
            val bytes = p.inputStream.readBytes()
            p.waitFor()
            bytes.any { it == 0.toByte() }
        } catch (_: Exception) {
            false
        }
    }

    private fun readLinesWithRoot(
        absolutePath: String,
        offset: Int = 1,
        lines: Int? = null,
        direction: String = "head",
    ): List<String> {
        val cmd = when {
            direction == "tail" && lines != null && lines > 0 -> "tail -n $lines \"$absolutePath\""
            direction == "head" && offset > 1 && lines != null && lines > 0 -> {
                val endLine = offset + lines - 1
                "sed -n '${offset},${endLine}p' \"$absolutePath\""
            }
            direction == "head" && offset == 1 && lines != null && lines > 0 -> "head -n $lines \"$absolutePath\""
            else -> "cat \"$absolutePath\""
        }
        val process = ProcessBuilder("su", "-c", cmd).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        if (process.exitValue() != 0 && output.isBlank()) {
            val err = process.errorStream.bufferedReader().readText()
            throw RuntimeException(err.ifBlank { "Root read failed (exit code ${process.exitValue()})" })
        }
        return output.lines()
    }
}
