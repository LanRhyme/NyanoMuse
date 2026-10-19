package com.openminis.app.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

enum class ThemePalette(val id: String, val label: String, val description: String) {
    MORANDI("morandi", "莫兰迪", "低饱和温润暖灰与鼠尾草色，极简护眼"),
    MATERIAL_YOU("material_you", "Material You", "跟随 Android 系统壁纸动态取色 (Android 12+)"),
    OLED_BLACK("oled_black", "OLED 纯黑", "极致纯黑背景，深度省电与沉浸对比"),
    SAKURA_PINK("sakura_pink", "猫猫暖樱", "甜美樱粉与奶油白，萌系猫猫专属"),
    DEFAULT_BLUE("default_blue", "经典蓝", "官方原生经典冷蓝配色");

    companion object {
        fun fromId(id: String?): ThemePalette =
            values().firstOrNull { it.id == id } ?: MORANDI
    }
}

object ThemePalettes {

    // ── 莫兰迪 (Morandi) ──────────────────────────────────────────────────────────
    private val MorandiPrimaryLight = Color(0xFF6A7F7A)
    private val MorandiOnPrimaryLight = Color(0xFFFFFFFF)
    private val MorandiPrimaryContainerLight = Color(0xFFE0E7E4)
    private val MorandiOnPrimaryContainerLight = Color(0xFF22302D)
    private val MorandiSecondaryLight = Color(0xFF767F86)
    private val MorandiBgLight = Color(0xFFF3F1EC)
    private val MorandiCardLight = Color(0xFFFAF9F6)
    private val MorandiCardElevatedLight = Color(0xFFFFFFFF)
    private val MorandiOutlineLight = Color(0xFFD5D2CA)

    private val MorandiPrimaryDark = Color(0xFF9EABA7)
    private val MorandiOnPrimaryDark = Color(0xFF1A2624)
    private val MorandiPrimaryContainerDark = Color(0xFF33403D)
    private val MorandiOnPrimaryContainerDark = Color(0xFFDFE6E3)
    private val MorandiSecondaryDark = Color(0xFFAAB2B8)
    private val MorandiBgDark = Color(0xFF1E2124)
    private val MorandiCardDark = Color(0xFF292D32)
    private val MorandiCardElevatedDark = Color(0xFF34393F)
    private val MorandiOutlineDark = Color(0xFF454B52)

    val MorandiLightColorScheme: ColorScheme = lightColorScheme(
        primary = MorandiPrimaryLight,
        onPrimary = MorandiOnPrimaryLight,
        primaryContainer = MorandiPrimaryContainerLight,
        onPrimaryContainer = MorandiOnPrimaryContainerLight,
        secondary = MorandiSecondaryLight,
        onSecondary = Color.White,
        background = MorandiBgLight,
        onBackground = Color(0xFF2C2B29),
        surface = MorandiBgLight,
        onSurface = Color(0xFF2C2B29),
        surfaceVariant = MorandiCardLight,
        onSurfaceVariant = Color(0xFF55524D),
        surfaceContainerLowest = MorandiBgLight,
        surfaceContainerLow = MorandiCardLight,
        surfaceContainer = MorandiCardLight,
        surfaceContainerHigh = MorandiCardElevatedLight,
        surfaceContainerHighest = MorandiCardElevatedLight,
        outline = MorandiOutlineLight,
        outlineVariant = MorandiOutlineLight,
    )

    val MorandiDarkColorScheme: ColorScheme = darkColorScheme(
        primary = MorandiPrimaryDark,
        onPrimary = MorandiOnPrimaryDark,
        primaryContainer = MorandiPrimaryContainerDark,
        onPrimaryContainer = MorandiOnPrimaryContainerDark,
        secondary = MorandiSecondaryDark,
        onSecondary = Color(0xFFE4E4E6),
        background = MorandiBgDark,
        onBackground = Color(0xFFE4E4E6),
        surface = MorandiBgDark,
        onSurface = Color(0xFFE4E4E6),
        surfaceVariant = MorandiCardDark,
        onSurfaceVariant = Color(0xFFB0B3B8),
        surfaceContainerLowest = MorandiBgDark,
        surfaceContainerLow = MorandiCardDark,
        surfaceContainer = MorandiCardDark,
        surfaceContainerHigh = MorandiCardElevatedDark,
        surfaceContainerHighest = MorandiCardElevatedDark,
        outline = MorandiOutlineDark,
        outlineVariant = MorandiOutlineDark,
    )

    val MorandiLightChatPalette: ChatPalette = LightChatPalette.copy(
        background = MorandiBgLight,
        secondaryBg = MorandiCardLight,
        inputBg = MorandiCardElevatedLight,
        inputIconBg = MorandiCardLight,
        userBubble = Color(0xFF4E5E59),
        onUserBubble = Color.White,
        assistantBubble = MorandiCardLight,
        toolBg = MorandiCardLight,
        toolCapsuleBg = MorandiCardLight,
        inlineCodeText = MorandiPrimaryLight,
        link = MorandiPrimaryLight,
        thinking = MorandiPrimaryLight,
        fabAccent = MorandiPrimaryLight,
    )

    val MorandiDarkChatPalette: ChatPalette = DarkChatPalette.copy(
        background = MorandiBgDark,
        secondaryBg = MorandiCardDark,
        inputBg = MorandiCardDark,
        inputIconBg = MorandiCardElevatedDark,
        userBubble = Color(0xFF384541),
        onUserBubble = Color(0xFFE4E4E6),
        assistantBubble = MorandiCardDark,
        toolBg = MorandiCardElevatedDark,
        toolCapsuleBg = MorandiCardDark,
        inlineCodeText = MorandiPrimaryDark,
        link = MorandiPrimaryDark,
        thinking = MorandiPrimaryDark,
        fabAccent = MorandiPrimaryDark,
    )

    // ── 猫猫暖樱 (Sakura Pink) ──────────────────────────────────────────────────
    private val SakuraPrimaryLight = Color(0xFFD66885)
    private val SakuraPrimaryDark = Color(0xFFF29BB1)

    val SakuraLightColorScheme: ColorScheme = lightColorScheme(
        primary = SakuraPrimaryLight,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFECF0),
        onPrimaryContainer = Color(0xFF4A1020),
        secondary = Color(0xFF8B6C74),
        onSecondary = Color.White,
        background = Color(0xFFFFF7F9),
        onBackground = Color(0xFF2D2326),
        surface = Color(0xFFFFF7F9),
        onSurface = Color(0xFF2D2326),
        surfaceVariant = Color(0xFFFFFFFF),
        onSurfaceVariant = Color(0xFF5A484C),
        surfaceContainerLowest = Color(0xFFFFF7F9),
        surfaceContainerLow = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFFFFFFF),
        surfaceContainerHigh = Color(0xFFFFF0F3),
        surfaceContainerHighest = Color(0xFFFFF0F3),
        outline = Color(0xFFE8D3D8),
        outlineVariant = Color(0xFFE8D3D8),
    )

    val SakuraDarkColorScheme: ColorScheme = darkColorScheme(
        primary = SakuraPrimaryDark,
        onPrimary = Color.White,
        primaryContainer = Color(0xFF5A1C2C),
        onPrimaryContainer = Color(0xFFFFD9E2),
        secondary = Color(0xFFD4B2BA),
        onSecondary = Color(0xFFEDE0E3),
        background = Color(0xFF1F171A),
        onBackground = Color(0xFFEDE0E3),
        surface = Color(0xFF1F171A),
        onSurface = Color(0xFFEDE0E3),
        surfaceVariant = Color(0xFF2E2327),
        onSurfaceVariant = Color(0xFFBFB0B4),
        surfaceContainerLowest = Color(0xFF1F171A),
        surfaceContainerLow = Color(0xFF2E2327),
        surfaceContainer = Color(0xFF2E2327),
        surfaceContainerHigh = Color(0xFF3B2E33),
        surfaceContainerHighest = Color(0xFF3B2E33),
        outline = Color(0xFF564449),
        outlineVariant = Color(0xFF564449),
    )

    val SakuraLightChatPalette: ChatPalette = LightChatPalette.copy(
        background = Color(0xFFFFF7F9),
        secondaryBg = Color(0xFFFFF0F3),
        inputBg = Color.White,
        inputIconBg = Color(0xFFFFF0F3),
        userBubble = SakuraPrimaryLight,
        onUserBubble = Color.White,
        assistantBubble = Color(0xFFFFF0F3),
        toolBg = Color(0xFFFFF0F3),
        toolCapsuleBg = Color(0xFFFFE8ED),
        inlineCodeText = SakuraPrimaryLight,
        link = SakuraPrimaryLight,
        thinking = SakuraPrimaryLight,
        fabAccent = SakuraPrimaryLight,
    )

    val SakuraDarkChatPalette: ChatPalette = DarkChatPalette.copy(
        background = Color(0xFF1F171A),
        secondaryBg = Color(0xFF2E2327),
        inputBg = Color(0xFF2E2327),
        inputIconBg = Color(0xFF3B2E33),
        userBubble = Color(0xFF4A232E),
        onUserBubble = Color(0xFFFFD9E2),
        assistantBubble = Color(0xFF2E2327),
        toolBg = Color(0xFF3B2E33),
        toolCapsuleBg = Color(0xFF2E2327),
        inlineCodeText = SakuraPrimaryDark,
        link = SakuraPrimaryDark,
        thinking = SakuraPrimaryDark,
        fabAccent = SakuraPrimaryDark,
    )

    // ── OLED 纯黑 (OLED Pure Black) ─────────────────────────────────────────────
    val OledDarkColorScheme: ColorScheme = darkColorScheme(
        primary = Color(0xFFFFFFFF),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF262626),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFB3B3B3),
        onSecondary = Color(0xFFFFFFFF),
        background = Color(0xFF000000),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF141414),
        onSurfaceVariant = Color(0xFFB3B3B3),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF141414),
        surfaceContainer = Color(0xFF141414),
        surfaceContainerHigh = Color(0xFF222222),
        surfaceContainerHighest = Color(0xFF2A2A2A),
        outline = Color(0xFF383838),
        outlineVariant = Color(0xFF383838),
    )

    val OledDarkChatPalette: ChatPalette = DarkChatPalette.copy(
        background = Color(0xFF000000),
        secondaryBg = Color(0xFF121212),
        inputBg = Color(0xFF141414),
        inputIconBg = Color(0xFF202020),
        userBubble = Color(0xFF262626),
        onUserBubble = Color(0xFFFFFFFF),
        assistantBubble = Color(0xFF141414),
        toolBg = Color(0xFF1A1A1A),
        toolCapsuleBg = Color(0xFF161616),
        codeBlockBg = Color(0xFF121212),
        inlineCodeBg = Color(0xFF202020),
        inlineCodeText = Color(0xFFE0E0E0),
        link = Color(0xFF64B5F6),
        thinking = Color(0xFF90CAF9),
        fabAccent = Color(0xFFFFFFFF),
    )

    /** 根据当前选择的调色盘与亮暗模式，解析对应的 ColorScheme */
    fun resolveColorScheme(palette: ThemePalette, darkTheme: Boolean, context: Context): ColorScheme {
        return when (palette) {
            ThemePalette.MORANDI -> if (darkTheme) MorandiDarkColorScheme else MorandiLightColorScheme
            ThemePalette.SAKURA_PINK -> if (darkTheme) SakuraDarkColorScheme else SakuraLightColorScheme
            ThemePalette.OLED_BLACK -> if (darkTheme) OledDarkColorScheme else LightColorScheme
            ThemePalette.MATERIAL_YOU -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                } else {
                    if (darkTheme) DarkColorScheme else LightColorScheme
                }
            }
            ThemePalette.DEFAULT_BLUE -> if (darkTheme) DarkColorScheme else LightColorScheme
        }
    }

    /** 根据当前选择的调色盘与亮暗模式，解析对应的 ChatPalette */
    fun resolveChatPalette(palette: ThemePalette, darkTheme: Boolean, context: Context): ChatPalette {
        return when (palette) {
            ThemePalette.MORANDI -> if (darkTheme) MorandiDarkChatPalette else MorandiLightChatPalette
            ThemePalette.SAKURA_PINK -> if (darkTheme) SakuraDarkChatPalette else SakuraLightChatPalette
            ThemePalette.OLED_BLACK -> if (darkTheme) OledDarkChatPalette else LightChatPalette
            ThemePalette.MATERIAL_YOU -> {
                val cs = resolveColorScheme(palette, darkTheme, context)
                if (darkTheme) {
                    DarkChatPalette.copy(
                        userBubble = cs.primaryContainer,
                        onUserBubble = cs.onPrimaryContainer,
                        link = cs.primary,
                        thinking = cs.primary,
                        fabAccent = cs.primary,
                    )
                } else {
                    LightChatPalette.copy(
                        userBubble = cs.primary,
                        onUserBubble = cs.onPrimary,
                        link = cs.primary,
                        thinking = cs.primary,
                        fabAccent = cs.primary,
                    )
                }
            }
            ThemePalette.DEFAULT_BLUE -> if (darkTheme) DarkChatPalette else LightChatPalette
        }
    }
}
