package com.moneytracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.moneytracker.data.Store

/**
 * 七套主题，名字和观感跟电脑版一一对应：
 *   light     浅色     干净的默认配色，白天用最舒服
 *   dark      深色     夜间记账不刺眼
 *   green     护眼绿   低饱和绿底，长时间看着不累
 *   midnight  午夜蓝   深蓝夜色，偏冷
 *   warm      暖阳     米黄暖调，像纸一样
 *   sakura    樱花粉   粉嫩一点，也可以很认真
 *   contrast  高对比   黑底亮黄，视力不好也能看清
 *
 * 换主题走 Store.data.settings.theme，改完调 Store.save()，
 * 这里会因为 Store.version 变化而重组，整个 app 一起换色。
 */

// ---------- 强调色预设（设置页里那八个） ----------
data class AccentOption(val name: String, val hex: String)

val accentOptions = listOf(
    AccentOption("天蓝", "#4A90D9"),
    AccentOption("青绿", "#12A594"),
    AccentOption("紫罗兰", "#7B61C9"),
    AccentOption("橘橙", "#E8843C"),
    AccentOption("玫红", "#D9467A"),
    AccentOption("正红", "#D64545"),
    AccentOption("石墨", "#5A6270"),
    AccentOption("琥珀", "#E9B949")
)

fun parseHex(hex: String): Color? {
    val h = hex.trim().removePrefix("#")
    if (h.length != 6) return null
    val v = h.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or v)
}

private data class Palette(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val error: Color,
    val onError: Color,
    val isDark: Boolean,
    val expense: Color,
    val income: Color
)

private fun paletteOf(themeId: String): Palette = when (themeId) {

    "dark" -> Palette(
        primary = Color(0xFF6FA8DC), onPrimary = Color(0xFF0B1620),
        primaryContainer = Color(0xFF24435F), onPrimaryContainer = Color(0xFFD6E7F7),
        secondary = Color(0xFF9BB0C4), secondaryContainer = Color(0xFF2B3742),
        onSecondaryContainer = Color(0xFFD3DDE6),
        background = Color(0xFF12141A), onBackground = Color(0xFFE4E7EC),
        surface = Color(0xFF1A1D24), onSurface = Color(0xFFE4E7EC),
        surfaceVariant = Color(0xFF262A33), onSurfaceVariant = Color(0xFFAAB2BF),
        outline = Color(0xFF3C424D),
        error = Color(0xFFFF8A80), onError = Color(0xFF4A0F0B),
        isDark = true,
        expense = Color(0xFFFF8A80), income = Color(0xFF6FD08C)
    )

    "green" -> Palette(
        primary = Color(0xFF3F7D53), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFC8E6CE), onPrimaryContainer = Color(0xFF10301C),
        secondary = Color(0xFF5C6B58), secondaryContainer = Color(0xFFDCE8DA),
        onSecondaryContainer = Color(0xFF273024),
        background = Color(0xFFF1F6EE), onBackground = Color(0xFF1B211A),
        surface = Color(0xFFF8FBF6), onSurface = Color(0xFF1B211A),
        surfaceVariant = Color(0xFFE2EBDD), onSurfaceVariant = Color(0xFF495245),
        outline = Color(0xFFB6C2B1),
        error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
        isDark = false,
        expense = Color(0xFFC0392B), income = Color(0xFF2E7D46)
    )

    "midnight" -> Palette(
        primary = Color(0xFF7FB3F0), onPrimary = Color(0xFF07182B),
        primaryContainer = Color(0xFF1E3A5F), onPrimaryContainer = Color(0xFFD3E4FF),
        secondary = Color(0xFF93A6C4), secondaryContainer = Color(0xFF243247),
        onSecondaryContainer = Color(0xFFD4DFF0),
        background = Color(0xFF0C1626), onBackground = Color(0xFFDDE4F0),
        surface = Color(0xFF13203A), onSurface = Color(0xFFDDE4F0),
        surfaceVariant = Color(0xFF1D2C48), onSurfaceVariant = Color(0xFFA6B4CC),
        outline = Color(0xFF33456280),
        error = Color(0xFFFF9A94), onError = Color(0xFF47090A),
        isDark = true,
        expense = Color(0xFFFF9A94), income = Color(0xFF7FD6A6)
    )

    "warm" -> Palette(
        primary = Color(0xFFB07D3A), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFF3DFC0), onPrimaryContainer = Color(0xFF3E2A0E),
        secondary = Color(0xFF7C6A52), secondaryContainer = Color(0xFFEFE3D0),
        onSecondaryContainer = Color(0xFF33291A),
        background = Color(0xFFFBF5E9), onBackground = Color(0xFF2A2318),
        surface = Color(0xFFFFFBF3), onSurface = Color(0xFF2A2318),
        surfaceVariant = Color(0xFFF0E6D3), onSurfaceVariant = Color(0xFF5A5040),
        outline = Color(0xFFC9BBA2),
        error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
        isDark = false,
        expense = Color(0xFFC0392B), income = Color(0xFF2E7D46)
    )

    "sakura" -> Palette(
        primary = Color(0xFFC96B8E), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFBDCE6), onPrimaryContainer = Color(0xFF44101F),
        secondary = Color(0xFF8A6B78), secondaryContainer = Color(0xFFF6E2EA),
        onSecondaryContainer = Color(0xFF35222A),
        background = Color(0xFFFDF4F7), onBackground = Color(0xFF2B1E23),
        surface = Color(0xFFFFF8FA), onSurface = Color(0xFF2B1E23),
        surfaceVariant = Color(0xFFF4E2E8), onSurfaceVariant = Color(0xFF5C4A51),
        outline = Color(0xFFD9BEC8),
        error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
        isDark = false,
        expense = Color(0xFFC0392B), income = Color(0xFF2E7D46)
    )

    "contrast" -> Palette(
        primary = Color(0xFFFFD400), onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF3A3000), onPrimaryContainer = Color(0xFFFFE766),
        secondary = Color(0xFFFFD400), secondaryContainer = Color(0xFF2A2A2A),
        onSecondaryContainer = Color(0xFFFFD400),
        background = Color(0xFF000000), onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF0A0A0A), onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF1E1E1E), onSurfaceVariant = Color(0xFFEDEDED),
        outline = Color(0xFF8A8A8A),
        error = Color(0xFFFF5252), onError = Color(0xFF000000),
        isDark = true,
        expense = Color(0xFFFF6B6B), income = Color(0xFF5BE86B)
    )

    // 默认浅色
    else -> Palette(
        primary = Color(0xFF3D7EBF), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD3E4F7), onPrimaryContainer = Color(0xFF0C2942),
        secondary = Color(0xFF5A6675), secondaryContainer = Color(0xFFE1E7EF),
        onSecondaryContainer = Color(0xFF232B35),
        background = Color(0xFFF7F8FA), onBackground = Color(0xFF1A1D21),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1A1D21),
        surfaceVariant = Color(0xFFEDF0F4), onSurfaceVariant = Color(0xFF4C5560),
        outline = Color(0xFFC4CBD4),
        error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
        isDark = false,
        expense = Color(0xFFC0392B), income = Color(0xFF2E7D46)
    )
}

// ---------- 界面各处要用的额外颜色 ----------
data class ExtraColors(
    val expense: Color,
    val income: Color,
    val isDark: Boolean
)

val LocalExtraColors = staticCompositionLocalOf {
    ExtraColors(Color(0xFFC0392B), Color(0xFF2E7D46), false)
}

/** 分类占比图那类地方用的固定色板，跟主题无关 */
val chartPalette = listOf(
    Color(0xFF4A90D9), Color(0xFFE8843C), Color(0xFF12A594), Color(0xFFD9467A),
    Color(0xFF7B61C9), Color(0xFFE9B949), Color(0xFF3F7D53), Color(0xFFC96B8E),
    Color(0xFF5A6270), Color(0xFF2E9BC9)
)

@Composable
fun MoneyTrackerTheme(content: @Composable () -> Unit) {
    // 订阅 Store.version。少了这一行，设置页改完主题/强调色/字号之后，
    // 失效的只是 AppRoot 的 scope，MoneyTrackerTheme 参数没变会被跳过，
    // 色板不会重算，用户点了主题要等别的原因引起重组才会变色。
    val tick = Store.version.collectAsState().value
    val settings = remember(tick) { Store.data.settings }
    val p = paletteOf(settings.theme)

    // 强调色：填了就盖掉主题自带的主色
    val accent = parseHex(settings.accent)
    val scheme = if (p.isDark) {
        darkColorScheme(
            primary = accent ?: p.primary,
            onPrimary = if (accent != null) Color.White else p.onPrimary,
            primaryContainer = p.primaryContainer,
            onPrimaryContainer = p.onPrimaryContainer,
            secondary = p.secondary,
            secondaryContainer = p.secondaryContainer,
            onSecondaryContainer = p.onSecondaryContainer,
            background = p.background,
            onBackground = p.onBackground,
            surface = p.surface,
            onSurface = p.onSurface,
            surfaceVariant = p.surfaceVariant,
            onSurfaceVariant = p.onSurfaceVariant,
            outline = p.outline,
            error = p.error,
            onError = p.onError
        )
    } else {
        lightColorScheme(
            primary = accent ?: p.primary,
            onPrimary = if (accent != null) Color.White else p.onPrimary,
            primaryContainer = p.primaryContainer,
            onPrimaryContainer = p.onPrimaryContainer,
            secondary = p.secondary,
            secondaryContainer = p.secondaryContainer,
            onSecondaryContainer = p.onSecondaryContainer,
            background = p.background,
            onBackground = p.onBackground,
            surface = p.surface,
            onSurface = p.onSurface,
            surfaceVariant = p.surfaceVariant,
            onSurfaceVariant = p.onSurfaceVariant,
            outline = p.outline,
            error = p.error,
            onError = p.onError
        )
    }

    val extra = ExtraColors(
        // 不勾「支出红收入绿」就都按主题色来，跟电脑版一样
        expense = if (settings.redExpense) p.expense else scheme.primary,
        income = if (settings.redExpense) p.income else scheme.primary,
        isDark = p.isDark
    )

    // 字号缩放：85% ~ 125%
    val scale = (settings.fontScale.coerceIn(85, 125)) / 100f
    fun s(size: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontSize = (size * scale).sp, fontWeight = weight)

    val typography = Typography(
        displayLarge = s(52, FontWeight.SemiBold),
        displayMedium = s(40, FontWeight.SemiBold),
        displaySmall = s(32, FontWeight.SemiBold),
        headlineLarge = s(28, FontWeight.SemiBold),
        headlineMedium = s(24, FontWeight.SemiBold),
        headlineSmall = s(20, FontWeight.SemiBold),
        titleLarge = s(19, FontWeight.Medium),
        titleMedium = s(16, FontWeight.Medium),
        titleSmall = s(14, FontWeight.Medium),
        bodyLarge = s(16),
        bodyMedium = s(14),
        bodySmall = s(12),
        labelLarge = s(14, FontWeight.Medium),
        labelMedium = s(12, FontWeight.Medium),
        labelSmall = s(11, FontWeight.Medium)
    )

    CompositionLocalProvider(LocalExtraColors provides extra) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            content = content
        )
    }
}

/** 主题 id 和显示名的对照，设置页直接用它列表 */
val themeOptions = listOf(
    "light" to "浅色",
    "dark" to "深色",
    "green" to "护眼绿",
    "midnight" to "午夜蓝",
    "warm" to "暖阳",
    "sakura" to "樱花粉",
    "contrast" to "高对比"
)