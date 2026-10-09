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

/**
 * **看天看地 · 情绪轴配色**（SKY-EARTH，老周 2026-10-09）—— 本模块**唯一配色来源**
 * （情景图 / 三档表情 / 五档文字都从这里取，别散在 UI 里）。
 *
 * ⚠️ 刻意**不复用行情色**：[StockNoteColors.Up]（涨红）/[StockNoteColors.Down]（跌绿）
 * 是行情语义、[StockNoteColors.Danger] 是危险操作色 —— 情绪轴的绿若被读成「今天涨了」就是误导
 * （需求 FR-SE-06 明令）。轴走：灰蓝（悲观）→ 中性灰 → 暖橙（偏乐观）→ 绿（很乐观）。
 */
object SkyEarthPalette {

    // ---- 五档档位主色（表情选中底色 / 图上文案）----

    val VeryBearish = Color(0xFF475467)
    val Bearish = Color(0xFF667085)
    val Neutral = Color(0xFF98A2B3)

    /** ⚠️ 偏乐观是**暖橙**（4 档），很乐观才是绿（5 档）——别把两档都画成绿。 */
    val Bullish = Color(0xFFF79009)
    val VeryBullish = Color(0xFF12B76A)

    // ---- 三档表情：未选中态 ----

    val FaceIdleBg = Color(0xFFEEF1F6)
    val FaceIdleInk = Color(0xFFC6CDD8)

    // ---- 情景图 · 天空主题（原型 23b 天组五档渐变端点）----

    val SkyStormTop = Color(0xFF344054)
    val SkyStormBottom = Color(0xFF667085)
    val SkyCloudyTop = Color(0xFF667085)
    val SkyCloudyBottom = Color(0xFF98A2B3)
    val SkyGreyTop = Color(0xFFD0D5DD)
    val SkyGreyBottom = Color(0xFFEAECF0)
    val SkyWarmTop = Color(0xFFFDE3A7)
    val SkyWarmBottom = Color(0xFFFDB022)
    val SkyClearTop = Color(0xFFA6F4C5)
    val SkyClearBottom = Color(0xFF4EC9A0)

    /** 太阳（外晕 / 内盘 / 很乐观的小内核）。 */
    val SunOuter = Color(0xFFFFF6D6)
    val SunInner = Color(0xFFFFE9A3)
    val SunCore = Color(0xFFFFE070)

    /** 云（白色，透明度由档位定）、雨丝、远山分层。 */
    val Cloud = Color(0xFFFFFFFF)
    val Rain = Color(0xFF98A2B3)
    val HillStorm = Color(0xFF3E4C5A)
    val HillCloudy = Color(0xFF5A6675)
    val HillGrey = Color(0xFF98A2B3)
    val HillWarm = Color(0xFF8A6A3A)
    val HillClear = Color(0xFF2E8F63)

    // ---- 情景图 · 地面主题（原型 23b 地组五档）----

    val SoilDry = Color(0xFF6E6A62)
    val SoilPale = Color(0xFFA9A59C)
    val SoilLight = Color(0xFFD6D3CD)
    val SoilWet = Color(0xFFC9BFA6)
    val SoilRich = Color(0xFF8A6E42)

    val WaterLow = Color(0xFF8FA0A8)
    val WaterMid = Color(0xFF9FB0B8)
    val WaterHigh = Color(0xFF6FA8C4)
    val WaterDeep = Color(0xFF3E8FC0)

    val CropSparse = Color(0xFF7E8C4A)
    val CropMid = Color(0xFF5F7A32)
    val CropRich = Color(0xFF2F7A32)

    /** 干裂纹（很悲观）。 */
    val Crack = Color(0xFF4A463F)
}

/** 五档 → 档位主色（图上文字 / 详情页当前档位徽标）。未判断走中性灰。 */
fun skyLevelColor(level: com.stocknote.core.model.SkyLevel): Color = when (level) {
    com.stocknote.core.model.SkyLevel.UNJUDGED -> SkyEarthPalette.Neutral
    com.stocknote.core.model.SkyLevel.VERY_BEARISH -> SkyEarthPalette.VeryBearish
    com.stocknote.core.model.SkyLevel.BEARISH -> SkyEarthPalette.Bearish
    com.stocknote.core.model.SkyLevel.NEUTRAL -> SkyEarthPalette.Neutral
    com.stocknote.core.model.SkyLevel.BULLISH -> SkyEarthPalette.Bullish
    com.stocknote.core.model.SkyLevel.VERY_BULLISH -> SkyEarthPalette.VeryBullish
}
