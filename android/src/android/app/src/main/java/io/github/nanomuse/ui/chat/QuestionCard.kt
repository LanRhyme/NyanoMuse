package io.github.nanomuse.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors
import org.json.JSONArray
import org.json.JSONObject

data class QuestionCardData(
    val question: String,
    val options: List<String>,
    val isMultiSelect: Boolean = false,
)

object QuestionCardParser {
    private val regex = Regex("""<question>([\s\S]*?)(?:</question>|$)""", RegexOption.IGNORE_CASE)

    private fun cleanMarkdownCode(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.substringAfter("\n")
            if (text.endsWith("```")) {
                text = text.substringBeforeLast("```")
            }
            text = text.trim()
        }
        return text
    }

    private fun extractQuestionText(obj: JSONObject): String {
        val keys = listOf("question", "prompt", "text", "title", "header", "message", "query", "description", "content")
        for (k in keys) {
            val v = obj.optString(k, "").trim()
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun extractOptionsList(obj: JSONObject): List<String> {
        val keys = listOf("options", "choices", "items", "answers", "candidates", "alternatives")
        for (k in keys) {
            val arr = obj.optJSONArray(k)
            if (arr != null && arr.length() > 0) {
                return parseOptionsArray(arr)
            }
            val str = obj.optString(k, "").trim()
            if (str.isNotEmpty()) {
                val parsed = parseOptionsFromString(str)
                if (parsed.isNotEmpty()) return parsed
            }
        }
        return emptyList()
    }

    private fun parseOptionsFromString(raw: String): List<String> {
        val trimmed = cleanMarkdownCode(raw)
        if (trimmed.startsWith("[")) {
            try {
                val arr = JSONArray(trimmed)
                return parseOptionsArray(arr)
            } catch (_: Exception) {}
        }
        if (trimmed.contains("\n")) {
            return trimmed.split("\n")
                .map { it.replace(Regex("""^[-*•\d.]+\s*"""), "").trim() }
                .filter { it.isNotEmpty() }
        }
        if (trimmed.contains(",")) {
            return trimmed.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        return listOf(trimmed)
    }

    private fun parseOptionsArray(arr: JSONArray): List<String> {
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i) ?: continue
            when (item) {
                is JSONObject -> {
                    val label = listOf("label", "text", "option", "value", "title", "name", "content")
                        .map { item.optString(it, "").trim() }
                        .firstOrNull { it.isNotEmpty() } ?: ""
                    val desc = listOf("description", "desc", "detail", "sub")
                        .map { item.optString(it, "").trim() }
                        .firstOrNull { it.isNotEmpty() } ?: ""
                    if (label.isNotEmpty()) {
                        if (desc.isNotEmpty()) list.add("$label - $desc")
                        else list.add(label)
                    }
                }
                is String -> {
                    if (item.isNotBlank()) list.add(item.trim())
                }
                else -> {
                    val s = item.toString().trim()
                    if (s.isNotBlank()) list.add(s)
                }
            }
        }
        return list
    }

    private fun extractMultiSelect(obj: JSONObject): Boolean {
        return obj.optBoolean("is_multi_select", false) ||
            obj.optBoolean("multi_select", false) ||
            obj.optBoolean("isMultiSelect", false) ||
            obj.optBoolean("multiSelect", false) ||
            obj.optBoolean("multiple", false)
    }

    fun isAskQuestionTool(toolName: String): Boolean {
        val clean = toolName.lowercase().trim()
        return clean == "ask_user_question" || clean == "ask_question" || clean == "ask_user" || clean == "ask"
    }

    fun extractQuestion(rawText: String): Pair<String, QuestionCardData?> {
        val match = regex.find(rawText)
        if (match != null) {
            val data = parseFromToolArgs(match.groupValues[1])
            if (data != null) {
                val before = rawText.substring(0, match.range.first).trim()
                val after = rawText.substring(minOf(match.range.last + 1, rawText.length)).trim()
                val cleanedText = listOf(before, after).filter { it.isNotEmpty() }.joinToString("\n\n")
                return Pair(cleanedText, data)
            }
        }

        val trimmed = rawText.trim()
        if (trimmed.startsWith("```") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val data = parseFromToolArgs(trimmed)
            if (data != null && data.question.isNotBlank() && data.options.isNotEmpty()) {
                return Pair("", data)
            }
        }

        return Pair(rawText, null)
    }

    fun parseFromToolArgs(argsJson: String): QuestionCardData? {
        val clean = cleanMarkdownCode(argsJson)
        if (clean.isBlank()) return null
        return try {
            if (clean.startsWith("[")) {
                val arr = JSONArray(clean)
                if (arr.length() == 0) return null
                val first = arr.optJSONObject(0) ?: return null
                val question = extractQuestionText(first).ifEmpty { "请选择以下选项：" }
                var options = extractOptionsList(first)
                if (options.isEmpty()) options = listOf("(Recommended) 确认", "取消")
                return QuestionCardData(
                    question = question,
                    options = options,
                    isMultiSelect = extractMultiSelect(first),
                )
            }

            val root = JSONObject(clean)
            // 1. Check if 'questions' field exists as an array
            val qArray = root.optJSONArray("questions")
            var qObj = if (qArray != null && qArray.length() > 0) {
                qArray.optJSONObject(0)
            } else null

            // 2. If questions was passed as a string (due to AgentToolParam type: string)
            if (qObj == null && root.has("questions")) {
                val rawQuestions = root.optString("questions", "").trim()
                if (rawQuestions.isNotEmpty()) {
                    val cleanedInner = cleanMarkdownCode(rawQuestions)
                    if (cleanedInner.startsWith("[")) {
                        try {
                            val innerArr = JSONArray(cleanedInner)
                            if (innerArr.length() > 0) qObj = innerArr.optJSONObject(0)
                        } catch (_: Exception) {}
                    } else if (cleanedInner.startsWith("{")) {
                        try {
                            qObj = JSONObject(cleanedInner)
                        } catch (_: Exception) {}
                    }
                }
            }

            // 3. Fallback to root object itself
            if (qObj == null) {
                qObj = root
            }

            var question = extractQuestionText(qObj)
            if (question.isEmpty()) {
                question = extractQuestionText(root)
            }
            if (question.isEmpty()) {
                question = root.optString("tool_title", "").ifEmpty { "请选择以下选项：" }
            }

            var options = extractOptionsList(qObj)
            if (options.isEmpty()) {
                options = extractOptionsList(root)
            }
            if (options.isEmpty()) {
                options = listOf("(Recommended) 确认", "取消")
            }

            val isMulti = extractMultiSelect(qObj) || extractMultiSelect(root)
            QuestionCardData(
                question = question,
                options = options,
                isMultiSelect = isMulti,
            )
        } catch (_: Exception) {
            val fallbackQ = clean.take(120).trim()
            if (fallbackQ.isNotEmpty()) {
                QuestionCardData(
                    question = fallbackQ,
                    options = listOf("(Recommended) 确认", "取消"),
                )
            } else null
        }
    }
}

@Composable
fun InteractiveQuestionCard(
    data: QuestionCardData,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedAnswer: String? = null,
) {
    val accentColor = ChatColors.link
    val isAnswered = !selectedAnswer.isNullOrBlank()

    // Collapse automatically once answered, but allow user to toggle expansion
    var isExpanded by remember(selectedAnswer) {
        mutableStateOf(!isAnswered)
    }

    var selectedOptions by remember(data) {
        mutableStateOf<Set<String>>(emptySet())
    }

    val containerShape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(containerShape)
            .background(
                if (isAnswered && !isExpanded) ChatColors.toolCapsuleBg.copy(alpha = 0.5f)
                else ChatColors.secondaryBg.copy(alpha = 0.45f)
            )
            .border(
                width = 0.8.dp,
                color = if (!isAnswered) accentColor.copy(alpha = 0.35f) else ChatColors.toolBorder,
                shape = containerShape,
            )
            .animateContentSize(tween(250)),
    ) {
        if (isAnswered && !isExpanded) {
            // === Collapsed View: Compact Summary Pill ===
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = true }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Leading Check Badge
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(accentColor.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(13.dp),
                    )
                }

                // Question Title (Ellipsized)
                Text(
                    text = data.question,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = ChatColors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                // Selected Answer Chip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.14f))
                        .border(0.5.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = selectedAnswer.orEmpty(),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 240.dp),
                    )
                }

                // Expand Chevron
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand",
                    tint = ChatColors.secondaryText.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            // === Expanded View: Interactive Cards ===
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                // Header Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isAnswered) Modifier.clickable { isExpanded = false }
                            else Modifier
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(accentColor.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.HelpOutline,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(15.dp),
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = data.question,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ChatColors.primaryText,
                            lineHeight = 20.sp,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (data.isMultiSelect) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(accentColor.copy(alpha = 0.14f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "多选",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = accentColor,
                                )
                            }
                        }
                    }

                    if (isAnswered) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(ChatColors.toolCapsuleBg)
                                .border(0.5.dp, ChatColors.toolBorder, CircleShape)
                                .clickable { isExpanded = false }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = "收起",
                                fontSize = 11.sp,
                                color = ChatColors.secondaryText,
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "Collapse",
                                tint = ChatColors.secondaryText,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                // Options List
                Column(
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    data.options.forEach { option ->
                        val isRecommended = option.contains("(Recommended)", ignoreCase = true) ||
                            option.contains("（推荐）") || option.startsWith("(推荐)")

                        val cleanOptionText = option.replace(Regex("""^\((Recommended|推荐)\)\s*""", RegexOption.IGNORE_CASE), "").trim()

                        val isThisSelected = if (isAnswered) {
                            val sel = selectedAnswer.orEmpty()
                            val parts = sel.split(",", "，", "、", "\n").map { it.replace(Regex("""^\((Recommended|推荐)\)\s*""", RegexOption.IGNORE_CASE), "").trim() }.filter { it.isNotEmpty() }
                            parts.any { it.equals(cleanOptionText, ignoreCase = true) || it.contains(cleanOptionText, ignoreCase = true) || cleanOptionText.contains(it, ignoreCase = true) }
                        } else {
                            if (data.isMultiSelect) {
                                selectedOptions.contains(cleanOptionText)
                            } else false
                        }

                        val optionShape = RoundedCornerShape(11.dp)
                        val itemAlpha = if (!isAnswered || isThisSelected) 1f else 0.45f

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(optionShape)
                                .background(
                                    when {
                                        isThisSelected -> accentColor.copy(alpha = 0.16f)
                                        isRecommended && !isAnswered -> accentColor.copy(alpha = 0.08f)
                                        else -> ChatColors.toolCapsuleBg.copy(alpha = if (isAnswered) 0.35f else 0.65f)
                                    }
                                )
                                .border(
                                    width = if (isThisSelected) 1.2.dp else 0.6.dp,
                                    color = when {
                                        isThisSelected -> accentColor.copy(alpha = 0.8f)
                                        isRecommended && !isAnswered -> accentColor.copy(alpha = 0.3f)
                                        else -> ChatColors.toolBorder
                                    },
                                    shape = optionShape,
                                )
                                .then(
                                    if (!isAnswered) {
                                        Modifier.clickable {
                                            if (data.isMultiSelect) {
                                                selectedOptions = if (selectedOptions.contains(cleanOptionText)) {
                                                    selectedOptions - cleanOptionText
                                                } else {
                                                    selectedOptions + cleanOptionText
                                                }
                                            } else {
                                                onOptionSelected(cleanOptionText)
                                            }
                                        }
                                    } else Modifier
                                )
                                .padding(horizontal = 13.dp, vertical = 10.dp),
                        ) {
                            if (data.isMultiSelect) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(17.dp)
                                        .clip(RoundedCornerShape(4.5.dp))
                                        .background(if (isThisSelected) accentColor else Color.Transparent)
                                        .border(
                                            width = 1.3.dp,
                                            color = if (isThisSelected) accentColor else ChatColors.secondaryText.copy(alpha = 0.4f * itemAlpha),
                                            shape = RoundedCornerShape(4.5.dp),
                                        ),
                                ) {
                                    if (isThisSelected) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp),
                                        )
                                    }
                                }
                            } else {
                                if (isThisSelected) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .background(accentColor, CircleShape),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(11.dp),
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .border(
                                                width = 1.2.dp,
                                                color = ChatColors.secondaryText.copy(alpha = 0.35f * itemAlpha),
                                                shape = CircleShape,
                                            ),
                                    )
                                }
                            }

                            if (isRecommended && !isAnswered) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(accentColor.copy(alpha = 0.16f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = "推荐",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = accentColor,
                                    )
                                }
                            }

                            Text(
                                text = cleanOptionText,
                                fontSize = 13.5.sp,
                                fontWeight = if (isThisSelected) FontWeight.SemiBold else if (isRecommended && !isAnswered) FontWeight.Medium else FontWeight.Normal,
                                color = if (isThisSelected) accentColor else ChatColors.primaryText.copy(alpha = itemAlpha),
                                lineHeight = 18.5.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                if (data.isMultiSelect && !isAnswered) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "已选 ${selectedOptions.size} 项",
                            fontSize = 12.sp,
                            color = ChatColors.secondaryText,
                        )
                        Button(
                            onClick = {
                                if (selectedOptions.isNotEmpty()) {
                                    onOptionSelected(selectedOptions.joinToString(", "))
                                }
                            },
                            enabled = selectedOptions.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                disabledContainerColor = accentColor.copy(alpha = 0.25f),
                            ),
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 5.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text(
                                text = "确认提交",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selectedOptions.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
}
