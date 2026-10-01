package com.stocknote.feature.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 设计系统 —— 与 `界面原型-新版/styles.css` 同源，保证「原型 → 实现」不跑偏。
 *
 * 涨跌配色遵循中国习惯：**涨红跌绿**（与欧美相反）。
 */
object StockNoteColors {
    val Brand = Color(0xFF2E6BE6)
    val BrandDark = Color(0xFF1A4AB2)
    val Background = Color(0xFFF2F4F8)
    val Surface = Color(0xFFFFFFFF)

    /** 涨 —— 红 */
    val Up = Color(0xFFE23B3B)

    /** 跌 —— 绿 */
    val Down = Color(0xFF17A567)

    /**
     * **危险操作红**（老周 2026-09-30）：与 [Up]（涨红 E23B3B）**刻意分开** ——
     * 涨跌色是行情语义（涨红跌绿），危险操作是 UI 语义；混用会让"清空"按钮看起来像"涨"，
     * 而且**绝不能用 [Down]**：那是绿色（跌），老周真机一眼就看出"怎么都是绿字"。
     */
    val Danger = Color(0xFFD92D20)

    val TextPrimary = Color(0xFF1B1F27)
    val TextSecondary = Color(0xFF6B7280)
    val TextTertiary = Color(0xFF9AA1AE)
    val Divider = Color(0xFFE5E8EF)
    val Cash = Color(0xFF8B7BE8)
}

private val LightScheme = lightColorScheme(
    primary = StockNoteColors.Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE7FB),
    onPrimaryContainer = StockNoteColors.BrandDark,
    background = StockNoteColors.Background,
    onBackground = StockNoteColors.TextPrimary,
    surface = StockNoteColors.Surface,
    onSurface = StockNoteColors.TextPrimary,
    surfaceVariant = Color(0xFFF7F9FC),
    onSurfaceVariant = StockNoteColors.TextSecondary,
    outline = StockNoteColors.Divider,
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp),
)

@Composable
fun StockNoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightScheme,
        typography = AppTypography,
        content = content,
    )
}

/** 涨红跌绿：按数值正负给色，0 用次级文字色（不误导成"跌"）。 */
fun pnlColor(value: Double): Color = when {
    value > 1e-9 -> StockNoteColors.Up
    value < -1e-9 -> StockNoteColors.Down
    else -> StockNoteColors.TextSecondary
}

/**
 * 市场标识色（老周 2026-09-21 定）：一个市场一个色，
 * 持仓列表的股票图标、配置占比环形图 / 图例共用这一份，保证「颜色 ↔ 市场」只有一种映射。
 *
 * 注：[StockNoteColors.Up]（红）在列表里已经代表「亏损」，所以这里的红只用于美股，
 * 靠「它在图标方块里、且旁边有红绿盈亏数字」区分，不会误读。
 */
fun marketAccent(market: com.stocknote.core.model.Market?): Color = when (market) {
    com.stocknote.core.model.Market.A_SHARE -> Color(0xFF2E6BE6) // 蓝
    com.stocknote.core.model.Market.HK -> Color(0xFFF5A623)      // 橙
    com.stocknote.core.model.Market.US -> Color(0xFFE23B3B)      // 红
    com.stocknote.core.model.Market.ETF -> Color(0xFF8B5CF6)     // 紫
    com.stocknote.core.model.Market.FUND -> Color(0xFF0EA5B7)    // 青
    null -> StockNoteColors.TextSecondary
}

/** [marketAccent] 的浅底版（图标方块底色）：同一色相、12% 不透明度，不抢文字 */
fun marketAccentSoft(market: com.stocknote.core.model.Market?): Color =
    marketAccent(market).copy(alpha = 0.12f)
