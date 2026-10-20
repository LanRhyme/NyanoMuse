package io.github.nanomuse.memory

/**
 * CJK bigram and lowercase word tokenizer for full-text search indexing.
 * 100% synchronized with Desktop TypeScript implementation in src/memory-search.ts.
 */
object MemoryTokenizer {
    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FA5) || (code in 0x3400..0x4DBF) || (code in 0xF900..0xFAFF)
    }

    private fun isNonCjkWordChar(c: Char): Boolean {
        return !isCjk(c) && (Character.isLetterOrDigit(c) || c == '_')
    }

    private fun isNonCjkTokenChar(c: Char): Boolean {
        return isNonCjkWordChar(c) || c == '-' || c == '.'
    }

    fun tokenizeForSearch(text: String): String {
        if (text.isEmpty()) return ""
        val clean = text.lowercase()
        val tokens = mutableListOf<String>()

        var i = 0
        while (i < clean.length) {
            val char = clean[i]

            // CJK character processing -> generate bigrams
            if (isCjk(char)) {
                val nextChar = if (i + 1 < clean.length) clean[i + 1] else null
                if (nextChar != null && isCjk(nextChar)) {
                    tokens.add("$char$nextChar")
                } else if (i == 0 || !isCjk(clean[i - 1])) {
                    // Isolated single CJK character
                    tokens.add(char.toString())
                }
                i++
                continue
            }

            // Non-CJK Unicode Letter / Alphanumeric word processing
            if (isNonCjkWordChar(char)) {
                val sb = StringBuilder()
                while (i < clean.length && isNonCjkTokenChar(clean[i])) {
                    sb.append(clean[i])
                    i++
                }
                val trimmed = sb.toString().trim('.', '-')
                if (trimmed.isNotEmpty()) {
                    tokens.add(trimmed)
                }
                continue
            }

            // Whitespace and punctuation
            i++
        }

        return tokens.joinToString(" ")
    }

    fun buildFtsQuery(query: String): String {
        val tokenized = tokenizeForSearch(query)
        val tokens = tokenized.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
    }
}
