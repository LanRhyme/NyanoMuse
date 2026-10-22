package io.github.nanomuse.memory

data class ExtractedMemoryItem(
    val content: String,
    val category: String,
    val importance: Float
)

object MemoryExtractor {
    private val TRANSIENT_PATTERNS = listOf(
        Regex("^(?:帮我|请帮我|给我|替我|麻烦)"),
        Regex("^(?:打开|启动|运行|搜索|查询|删除|修改|重命名|复制|移动|写一段|看一下|解释一下|总结一下)"),
        Regex("(?:今天|刚才|这会儿|这次|这篇|这个文件|这段代码|当前会话|现在几点)"),
        Regex("(?:吗|呢|吧|？|\\?)$")
    )

    /**
     * A value that reads like a phone number, an e-mail address, an id or a card number is
     * not a fact to keep: passive extraction writes it to every device of the account and
     * into the prompt, without the person asking for it. Short digits (Pixel 8, HarmonyOS 4)
     * still pass.
     */
    private fun looksPrivate(line: String): Boolean {
        if (line.contains("@")) return true
        if (Regex("\\d{7,}").containsMatchIn(line)) return true
        return false
    }

    fun extractPassiveMemories(text: String): List<ExtractedMemoryItem> {
        val cleaned = io.github.nanomuse.chat.BubbleTimeFormatter.stripUserPromptTimestamp(text)
        val trimmed = cleaned.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("/") || trimmed.startsWith("!") || trimmed.startsWith("```")) return emptyList()
        if (trimmed.length > 800) return emptyList()

        val sentences = trimmed
            .split(Regex("[。\\n!！;；,，]+"))
            .map { it.trim() }
            .filter { it.length in 3..120 }

        val results = mutableListOf<ExtractedMemoryItem>()
        fun keep(content: String, category: String, importance: Float) {
            if (looksPrivate(content)) return
            results.add(ExtractedMemoryItem(content, category, importance))
        }

        for (s in sentences) {
            if (TRANSIENT_PATTERNS.any { it.containsMatchIn(s) }) continue

            // 1. Explicit Remember prefix
            val explicitMatch = Regex("^(?:记住|请记住|以后记住|记录一下)\\s*[:：,，]?\\s*(.+)$", RegexOption.IGNORE_CASE).find(s)
            if (explicitMatch != null && explicitMatch.groupValues[1].length >= 3) {
                keep(explicitMatch.groupValues[1].trim(), "preference", 0.8f)
                continue
            }

            // 2. Hardware / OS / Tools
            val zhDeviceMatch = Regex("^(?:我(?:的)?(?:当前|现在|主力)?(?:电脑|手机|平板)?)*(手机|平板|电脑|笔记本|操作系统|系统|窗口管理器|合成器|桌面环境|编辑器|IDE|包管理器)(?:是|换成了|换成|使用|为|用)\\s*(.+)$", RegexOption.IGNORE_CASE).find(s)
            if (zhDeviceMatch != null && zhDeviceMatch.groupValues[1].isNotEmpty() && zhDeviceMatch.groupValues[2].length >= 2) {
                val dev = zhDeviceMatch.groupValues[1]
                val v = zhDeviceMatch.groupValues[2].trim()
                keep("${dev}使用 $v", "device", 0.85f)
                continue
            }

            val enDeviceMatch = Regex("^my\\s+(phone|tablet|laptop|pc|desktop|os|editor|ide|package manager)\\s+is\\s+(.+)$", RegexOption.IGNORE_CASE).find(s)
            if (enDeviceMatch != null && enDeviceMatch.groupValues[1].isNotEmpty() && enDeviceMatch.groupValues[2].isNotEmpty()) {
                keep("${enDeviceMatch.groupValues[1]} is ${enDeviceMatch.groupValues[2].trim()}", "device", 0.85f)
                continue
            }

            // 3. Residence / Location
            val zhLocationMatch = Regex("^我(?:现在|目前|已经|最近|刚)?(?:搬到了|搬到|定居在|定居到|住在|居住在|常驻在)\\s*(.+)$", RegexOption.IGNORE_CASE).find(s)
            if (zhLocationMatch != null && zhLocationMatch.groupValues[1].length >= 2) {
                val loc = zhLocationMatch.groupValues[1].replace(Regex("[，,].*$"), "").trim()
                keep("现居 $loc", "profile", 0.8f)
                continue
            }

            val enLocationMatch = Regex("^i\\s+(?:now\\s+)?(?:live in|moved to|relocated to)\\s+(.+)$", RegexOption.IGNORE_CASE).find(s)
            if (enLocationMatch != null && enLocationMatch.groupValues[1].isNotEmpty()) {
                keep("Lives in ${enLocationMatch.groupValues[1].trim()}", "profile", 0.8f)
                continue
            }

            // 4. Name / Preferred addressing
            val nameMatch = Regex("^(?:叫我|称呼我为|我的名字是)\\s*[:：]?\\s*(.+)$", RegexOption.IGNORE_CASE).find(s)
            if (nameMatch != null && nameMatch.groupValues[1].length in 1..20) {
                keep("Call them: ${nameMatch.groupValues[1].trim()}", "profile", 0.9f)
                continue
            }

            // 5. Allergies & Health constraints
            val allergyMatch = Regex("^我(?:对)?(.+?)(?:严重)?过敏$", RegexOption.IGNORE_CASE).find(s)
            if (allergyMatch != null && allergyMatch.groupValues[1].isNotEmpty()) {
                keep("健康约束：对${allergyMatch.groupValues[1].trim()}过敏", "habit", 0.9f)
                continue
            }

            // 6. Preferences & Habits
            val zhPrefMatch = Regex("^我(?:平时|日常|通常|一般)?(?:最喜欢|挺喜欢|特别喜欢|很喜欢|偏好|偏爱|喜欢)\\s*(.+)$", RegexOption.IGNORE_CASE).find(s)
            if (zhPrefMatch != null && zhPrefMatch.groupValues[1].length >= 2) {
                keep("偏好：喜欢${zhPrefMatch.groupValues[1].trim()}", "preference", 0.7f)
                continue
            }

            val zhDislikeMatch = Regex("^我(?:平时|日常|通常|一般)?(?:最讨厌|特别讨厌|很讨厌|反感|排斥|不喜欢|不爱)\\s*(.+)$", RegexOption.IGNORE_CASE).find(s)
            if (zhDislikeMatch != null && zhDislikeMatch.groupValues[1].length >= 2) {
                keep("偏好：不喜欢${zhDislikeMatch.groupValues[1].trim()}", "preference", 0.7f)
                continue
            }

            val zhHabitMatch = Regex("^我(?:平时|日常|通常|一般)?(?:习惯用|习惯|只用|只喝|不喝|戒了)\\s*(.+)$", RegexOption.IGNORE_CASE).find(s)
            if (zhHabitMatch != null && zhHabitMatch.groupValues[1].length >= 2) {
                keep("习惯：$s", "habit", 0.7f)
                continue
            }

            val enPrefMatch = Regex("^i\\s+(?:usually\\s+|always\\s+)?(prefer|like|love|dislike|hate)\\s+(.+)$", RegexOption.IGNORE_CASE).find(s)
            if (enPrefMatch != null && enPrefMatch.groupValues[1].isNotEmpty() && enPrefMatch.groupValues[2].isNotEmpty()) {
                keep("Preference: ${enPrefMatch.groupValues[1]} ${enPrefMatch.groupValues[2].trim()}", "preference", 0.7f)
                continue
            }

            // 7. Communication guidelines
            val guidelineMatch = Regex("^(?:(?:以后|今后)(?:(?:跟我)?(?:回答|说话)时)?|(?:跟我)?(?:回答|说话)时)(?:请|千万|务必|一律)?(不要|别|必须|记得|默认)\\s*(.+)$", RegexOption.IGNORE_CASE).find(s)
            if (guidelineMatch != null && guidelineMatch.groupValues[1].isNotEmpty() && guidelineMatch.groupValues[2].isNotEmpty()) {
                keep("沟通规范：$s", "preference", 0.85f)
                continue
            }
        }

        return results
    }
}
