package io.github.nanomuse.ui.chat

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors
import org.json.JSONObject

data class QuestionCardData(
    val question: String,
    val options: List<String>,
    val isMultiSelect: Boolean = false,
)

object QuestionCardParser {
    private val regex = Regex("""<question>([\s\S]*?)(?:</question>|$)""", RegexOption.IGNORE_CASE)

    fun extractQuestion(rawText: String): Pair<String, QuestionCardData?> {
        val match = regex.find(rawText) ?: return Pair(rawText, null)
        val jsonStr = match.groupValues[1].trim()
        val data = try {
            val json = JSONObject(jsonStr)
            val question = json.optString("question", "").ifBlank {
                json.optString("title", "")
            }
            val optionsArray = json.optJSONArray("options")
            val options = mutableListOf<String>()
            if (optionsArray != null) {
                for (i in 0 until optionsArray.length()) {
                    options.add(optionsArray.optString(i, ""))
                }
            }
            if (question.isNotEmpty() && options.isNotEmpty()) {
                QuestionCardData(
                    question = question,
                    options = options,
                    isMultiSelect = json.optBoolean("is_multi_select", false),
                )
            } else null
        } catch (_: Exception) {
            null
        }

        if (data == null) return Pair(rawText, null)

        val before = rawText.substring(0, match.range.first).trim()
        val after = rawText.substring(minOf(match.range.last + 1, rawText.length)).trim()
        val cleanedText = listOf(before, after).filter { it.isNotEmpty() }.joinToString("\n\n")
        return Pair(cleanedText, data)
    }
}

@Composable
fun InteractiveQuestionCard(
    data: QuestionCardData,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ChatColors.secondaryBg.copy(alpha = 0.7f))
            .border(1.dp, ChatColors.sendButton.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Question Title Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.HelpOutline,
                contentDescription = null,
                tint = ChatColors.sendButton,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = data.question,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText,
                lineHeight = 20.sp,
            )
        }

        // Options List
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            data.options.forEach { option ->
                val isRecommended = option.contains("(Recommended)", ignoreCase = true) ||
                    option.contains("（推荐）") || option.startsWith("(推荐)")

                val cleanOptionText = option.replace(Regex("""^\((Recommended|推荐)\)\s*""", RegexOption.IGNORE_CASE), "")

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isRecommended) ChatColors.sendButton.copy(alpha = 0.15f)
                            else ChatColors.secondaryBg.copy(alpha = 0.8f),
                        )
                        .border(
                            width = if (isRecommended) 1.5.dp else 1.dp,
                            color = if (isRecommended) ChatColors.sendButton.copy(alpha = 0.8f) else ChatColors.secondaryText.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable { onOptionSelected(cleanOptionText) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    if (isRecommended) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(ChatColors.sendButton)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = "推荐",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(ChatColors.secondaryText.copy(alpha = 0.5f), CircleShape),
                        )
                    }

                    Text(
                        text = cleanOptionText,
                        fontSize = 13.5.sp,
                        fontWeight = if (isRecommended) FontWeight.Medium else FontWeight.Normal,
                        color = ChatColors.primaryText,
                        lineHeight = 18.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
