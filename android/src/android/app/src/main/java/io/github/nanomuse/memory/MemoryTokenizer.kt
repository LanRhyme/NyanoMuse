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

    val EQUIVALENT_SYNONYMS = listOf(
        listOf("电脑", "pc", "计算机", "台式机", "主机", "工作站"),
        listOf("笔记本", "laptop", "macbook", "thinkpad"),
        listOf("平板", "tablet", "pad", "ipad", "平板电脑"),
        listOf("手机", "phone", "移动端", "smartphone"),
        listOf("操作系统", "os", "系统"),
        listOf("桌面环境", "窗口管理器", "合成器", "wm", "compositor"),
        listOf("包管理器", "packagemanager", "包管理工具"),
        listOf("编辑器", "开发工具", "ide", "editor", "代码编辑器"),
        listOf("热饮", "饮品", "饮料", "喝的"),
        listOf("饮食", "食物", "餐", "吃的"),
        listOf("偏好", "喜好", "偏爱", "倾向"),
        listOf("习惯", "日常习惯", "生活习惯"),
        listOf("住址", "所在地", "居住地", "现居"),
        listOf("姓名", "名字", "称呼", "叫我", "昵称", "name"),
        listOf("代码", "编程", "开发", "写代码", "coding"),
        listOf("工作", "职业", "身份", "岗位", "上班", "公司", "work", "job")
    )

    val HYPERNYM_EXPANSIONS = mapOf(
        "热饮" to listOf("拿铁", "咖啡", "美式", "茶", "红茶", "绿茶", "乌龙茶", "奶茶", "可乐", "水"),
        "饮品" to listOf("拿铁", "咖啡", "美式", "茶", "红茶", "绿茶", "乌龙茶", "奶茶", "可乐", "水"),
        "饮料" to listOf("拿铁", "咖啡", "美式", "茶", "红茶", "绿茶", "乌龙茶", "奶茶", "可乐", "水"),
        "喝" to listOf("拿铁", "咖啡", "美式", "茶", "红茶", "绿茶", "乌龙茶", "奶茶", "可乐", "水"),
        "饮食" to listOf("菜", "饭", "面", "早饭", "午饭", "晚饭", "早餐", "午餐", "晚餐", "零食", "甜品"),
        "食物" to listOf("菜", "饭", "面", "早饭", "午饭", "晚饭", "早餐", "午餐", "晚餐", "零食", "甜品"),
        "吃" to listOf("菜", "饭", "面", "早饭", "午饭", "晚饭", "早餐", "午餐", "晚餐", "零食", "甜品"),
        "操作系统" to listOf("linux", "arch", "cachyos", "windows", "macos", "android", "coloros", "hyperos", "ios"),
        "系统" to listOf("linux", "arch", "cachyos", "windows", "macos", "android", "coloros", "hyperos", "ios"),
        "窗口管理器" to listOf("niri", "hyprland", "sway", "wayland", "gnome", "kde", "x11"),
        "合成器" to listOf("niri", "hyprland", "sway", "wayland"),
        "编辑器" to listOf("neovim", "nvim", "vscode", "cursor", "zed", "vim"),
        "包管理器" to listOf("pacman", "paru", "aur", "pnpm", "npm", "yarn", "cargo", "pip")
    )

    private val EQUIVALENTS_MAP = mutableMapOf<String, List<String>>().apply {
        for (cluster in EQUIVALENT_SYNONYMS) {
            for (term in cluster) {
                put(term.lowercase(), cluster)
            }
        }
    }

    fun getSynonyms(term: String): List<String> {
        val lower = term.lowercase()
        val eq = EQUIVALENTS_MAP[lower].orEmpty()
        val hyp = HYPERNYM_EXPANSIONS[lower].orEmpty()
        if (eq.isEmpty() && hyp.isEmpty()) return emptyList()
        return (eq + hyp).distinct()
    }

    fun buildFtsQuery(query: String): String {
        val tokenized = tokenizeForSearch(query)
        val tokens = tokenized.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""

        val querySet = LinkedHashSet<String>(tokens)
        for (token in tokens) {
            val syns = getSynonyms(token)
            for (s in syns.take(12)) {
                val synTokenized = tokenizeForSearch(s)
                for (st in synTokenized.split(Regex("\\s+"))) {
                    if (st.isNotEmpty()) querySet.add(st)
                }
            }
        }

        val rawWords = query.lowercase().split(Regex("[\\s,，.。!！?？]+")).filter { it.isNotEmpty() }
        for (w in rawWords) {
            val syns = getSynonyms(w)
            for (s in syns.take(12)) {
                val synTokenized = tokenizeForSearch(s)
                for (st in synTokenized.split(Regex("\\s+"))) {
                    if (st.isNotEmpty()) querySet.add(st)
                }
            }
        }

        return querySet.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
    }
}
