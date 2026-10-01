package com.stocknote.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.feature.theme.StockNoteColors
import com.stocknote.feature.theme.marketAccent
import com.stocknote.feature.theme.marketAccentSoft

/**
 * 「界面原型-新版」组件库 —— 逐条对应 styles.css 的类名。
 *
 * ⚠️ 这一份是 UI 基线。此前 M0/M1 的界面是我自己发明的，用户指出后重写。
 * 改样式请对着 `界面原型-新版/styles.css` 改，不要凭感觉。
 */

/** 渐变主按钮：对应 .btn.primary（linear-gradient(135deg, brand, brand-2)） */
fun brandGradient(): Brush = Brush.linearGradient(listOf(Color(0xFF2E6BE6), Color(0xFF5B8BF5)))

/** Hero 卡背景：对应 .hero 的 linear-gradient(140deg,#3C6FE8 0%,#2E6BE6 46%,#2559C9 100%) */
fun heroGradient(): Brush = Brush.linearGradient(
    listOf(Color(0xFF3C6FE8), Color(0xFF2E6BE6), Color(0xFF2559C9))
)

/** 区块：对应 .sec + .sec-h（标题 14.5/800 + 右侧「更多 ›」链接） */
@Composable
fun Sec(
    title: String,
    modifier: Modifier = Modifier,
    more: String? = null,
    onMore: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(horizontal = 2.dp).padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = pageSp(14f), fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            if (more != null && onMore != null) {
                Text(
                    text = more,
                    fontSize = pageSp(12f),
                    fontWeight = FontWeight.SemiBold,
                    color = StockNoteColors.Brand,
                    modifier = Modifier.clickable { onMore() },
                )
            }
        }
        content()
    }
}

/** 白卡片：对应 .card（圆角 16 + 柔和阴影） */
@Composable
fun CardBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(StockNoteColors.Surface),
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

/** 持仓行：对应 .row（40×40 圆角12 头像方块 + 名称/市场tag + 明细 + 右侧价格与涨跌 + ›） */
/**
 * @param avatarTinted 旧开关：无 [market] 时才生效（亏损 → 红字浅红底）。
 * @param market 传了就按**市场**给图标上色（老周 2026-09-21）；null 时退回 [avatarTinted] 的老样子。
 */
@Composable
fun StockRow(
    avatar: String,
    name: String,
    marketTag: String,
    detail: String,
    /** 明细第二行（可选）：字号变大后一行放不下，拆两行展示；null = 单行（自动折行最多 2 行） */
    detail2: String? = null,
    priceText: String,
    pnlText: String,
    pnlPositive: Boolean,
    avatarTinted: Boolean = false,
    market: com.stocknote.core.model.Market? = null,
    onClick: (() -> Unit)? = null,
) {
    val avatarInk = if (market != null) marketAccent(market)
    else if (avatarTinted) StockNoteColors.Up else StockNoteColors.BrandDark
    val avatarBg = if (market != null) marketAccentSoft(market)
    else if (avatarTinted) Color(0xFFFDF0EE) else Color(0xFFEAF1FE)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(avatarBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = avatar,
                fontSize = pageSp(15f),
                fontWeight = FontWeight.ExtraBold,
                color = avatarInk,
            )
        }
        Spacer(Modifier.width(12.dp))
        // 纵向四行（老周 2026-09-16：名称与市值分两行，大字体也能显示全）：
        // ① 名称 + 市场标签 ② 市值 ③ 盈亏 ④ 明细（数量/成本/现价）
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    fontSize = pageSp(14f),
                    fontWeight = FontWeight.Bold,
                    color = StockNoteColors.TextPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFFEAF1FE)),
                ) {
                    Text(
                        marketTag,
                        fontSize = pageSp(10f),
                        fontWeight = FontWeight.Bold,
                        color = StockNoteColors.BrandDark,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                priceText,
                fontSize = pageSp(15f),
                fontWeight = FontWeight.ExtraBold,
                color = StockNoteColors.TextPrimary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                pnlText,
                fontSize = pageSp(12.5f),
                fontWeight = FontWeight.Bold,
                color = if (pnlPositive) StockNoteColors.Up else StockNoteColors.Down,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                detail,
                // 老周 2026-09-21：明细行（"60股 · 成本 … · 现 …"）此前走 fixedSp（视觉恒定）显得过小，
                // 改成与 MetricCell 的 label 同档（scaleSp），统计页放大系数对它同样生效。
                fontSize = pageSp(11f),
                color = StockNoteColors.TextTertiary,
                // 传了 detail2 = 明细拆两行展示，本行只放「数量 · 成本」；没传则保持旧行为（最多折 2 行）
                maxLines = if (detail2 == null) 2 else 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            // 明细第二行（老周 2026-09-21：字号变大后一行放不下，拆两行 —— 第 1 行「数量 · 成本」，
            // 第 2 行「现价/净值（· 占比）」）。没传 detail2 的调用方保持旧行为（自动折行，最多 2 行）。
            if (detail2 != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    detail2,
                    fontSize = pageSp(11f),
                    color = StockNoteColors.TextTertiary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text("›", fontSize = pageSp(14f), color = StockNoteColors.TextTertiary)
    }
}

/** 提示条：对应 .note.blue */
@Composable
fun NoteBar(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xFFEAF1FE)),
    ) {
        Text(
            text = text,
            fontSize = pageSp(12f),
            color = Color(0xFF20488F),
            lineHeight = pageSp(18f),
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** 页脚说明：对应 .foot */
@Composable
fun ProtoFoot(text: String) {
    Text(
        text = text,
        fontSize = pageSp(10f),
        color = StockNoteColors.TextTertiary,
        lineHeight = pageSp(16f),
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
        textAlign = TextAlign.Center,
    )
}

/** 主按钮：对应 .btn.primary */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(brandGradient())
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = pageSp(15f), fontWeight = FontWeight.ExtraBold, color = Color.White)
    }
}

/** 幽灵按钮：对应 .btn.ghost */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = pageSp(15f), fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary)
    }
}

/**
 * 危险操作按钮（老周 2026-09-30）：白底**红字**，尺寸与 [GhostButton] 一致。
 *
 * 用于「一键清空」这类**不可撤销**的操作 —— 一律配 [DangerConfirmDialog]（两次确认），
 * 不要直接执行。
 */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 高度：比普通按钮高一点（老周 2026-09-30），看着更"重"、也更防误点。 */
    height: Dp = 56.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        // ⚠️ 用 [StockNoteColors.Danger]（真红），**不要**用 Down —— Down 是"跌"的绿，
        // 老周真机一眼看出「怎么都是绿字」。
        Text(text, fontSize = pageSp(15f), fontWeight = FontWeight.ExtraBold, color = StockNoteColors.Danger)
    }
}

/**
 * 危险操作的**两次确认**弹窗（老周 2026-09-30 要求"要有 2 次提醒"）。
 *
 * 第 1 次：说清**做什么**（[message]），按钮「继续」；
 * 第 2 次：说清**不可撤销的后果**（[warning]，红字），按钮是具体动词（如「清空」）；
 * 两次都点确认才走 [onConfirm]，任何时候可以「我再想想」退出。
 */
@Composable
fun DangerConfirmDialog(
    title: String,
    message: String,
    warning: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
) {
    var step by remember { mutableStateOf(1) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = StockNoteColors.Surface,
        title = {
            Text(
                if (step == 1) title else "再次确认",
                fontSize = pageSp(16f),
                fontWeight = FontWeight.Bold,
                // ⚠️ 一律用 Danger（真红）——Down 是"跌"的绿，不能当危险色
                color = if (step == 1) StockNoteColors.TextPrimary else StockNoteColors.Danger,
            )
        },
        text = {
            Text(
                if (step == 1) message else warning,
                fontSize = pageSp(13f),
                lineHeight = pageSp(20f),
                color = if (step == 1) StockNoteColors.TextSecondary else StockNoteColors.Danger,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (step == 1) step = 2 else onConfirm() }, enabled = !busy) {
                Text(
                    if (step == 1) "继续" else confirmLabel,
                    fontSize = pageSp(14f),
                    fontWeight = FontWeight.Bold,
                    color = StockNoteColors.Danger,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(if (step == 2) "我再想想" else "取消", fontSize = pageSp(14f))
            }
        },
    )
}

/** 指标卡：对应 .mgrid > .mg（weight 由调用处的 Row 提供） */
@Composable
fun MetricCell(
    label: String,
    value: String,
    sub: String? = null,
    modifier: Modifier = Modifier,
    valueColor: Color = StockNoteColors.TextPrimary,
    /**
     * 数值**同一行**的灰色小字后缀（老周 2026-09-27：持仓市值后面要带持仓占比）。
     * ⚠️ 金额本身已占 11 字符，加后缀更容易溢出 —— 故后缀用小字号，且整行仍 maxLines=1 兜底。
     */
    valueSuffix: String? = null,
    /**
     * 右上角图标（老周 2026-09-30）：把「📅 盈亏日历」这类入口挂在指标卡上。
     * null 时与普通指标卡完全一致。
     */
    trailingGlyph: String? = null,
    /** 整卡点击（配 [trailingGlyph] 使用）；null = 不可点 */
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(15.dp))
            .background(StockNoteColors.Surface)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 15.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = pageSp(11f),
                color = StockNoteColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            if (trailingGlyph != null) {
                Text(trailingGlyph, fontSize = pageSp(14f))
            }
        }
        Spacer(Modifier.height(7.dp))
        // 2026-09-17 B 方案：金额统一 2 位小数后文本变长。
        // 19sp 下「¥196,960.00」实测被 ellipsis 截成「¥196,960.…」（真机 Redmi K30 + 1.1 字体缩放），
        // 降到 16sp 保证 11 字符完整显示，并加 maxLines 兜底。
        // ⚠️ 放大字号时这里最容易溢出（11 字符 + 卡片宽度固定），看效果时留意是否被截断。
        // 老周 2026-09-27：数值后可带一个灰色小字后缀（如持仓占比），与数值**同一行**。
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value, fontSize = pageSp(16f), fontWeight = FontWeight.ExtraBold, color = valueColor,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (valueSuffix != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    valueSuffix,
                    fontSize = pageSp(11.5f),
                    fontWeight = FontWeight.Bold,
                    color = StockNoteColors.TextTertiary,
                    maxLines = 1,
                )
            }
        }
        if (sub != null) {
            Spacer(Modifier.height(5.dp))
            // 老周 2026-09-21：副标题（"7 只标的" / "含现金等价物"…）此前走 fixedSp（视觉恒定），
            // 在大字体档位下比 label 还小，被嫌太小 → 改成与 label **同一档字号**。
            Text(
                sub,
                fontSize = pageSp(11f),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = StockNoteColors.TextTertiary)
        }
    }
}

/** 底部导航：对应 .tabbar（4 项，中间「记一笔」凸起 46×46 渐变圆角方块） */
@Composable
fun ProtoTabBar(
    selected: String,
    onTab: (String) -> Unit,
) {
    // navigationBarsPadding：手势条会盖住 tab 文案（Redmi K30 实测被裁）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFFFFF))
            .navigationBarsPadding()
            .height(66.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabItem(Modifier.weight(1f), "📈", "统计", selected == "统计") { onTab("统计") }
        TabItem(Modifier.weight(1f), "💼", "持仓", selected == "持仓") { onTab("持仓") }
        // 🧭 探索（老周 2026-09-24）：4 项 → 5 项，插在正中间
        TabItem(Modifier.weight(1f), "🧭", "探索", selected == "探索") { onTab("探索") }
        // 🎯 交易计划（REQ-PLAN-01，老周 2026-09-19）：3 项 → 4 项
        TabItem(Modifier.weight(1f), "🎯", "计划", selected == "计划") { onTab("计划") }
        TabItem(Modifier.weight(1f), "📊", "分析", selected == "分析") { onTab("分析") }
    }
}

@Composable
private fun TabItem(
    modifier: Modifier = Modifier,
    icon: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, fontSize = 20.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) StockNoteColors.Brand else StockNoteColors.TextTertiary,
        )
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = pageSp(13f), color = StockNoteColors.TextTertiary)
    }
}

/** 错误条（保留原 ErrorBanner 语义，样式对齐 .note） */
@Composable
fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xFFFDF0EE)),
    ) {
        Text(
            message,
            fontSize = pageSp(12f),
            color = StockNoteColors.Up,
            lineHeight = pageSp(18f),
            modifier = Modifier.padding(12.dp),
        )
        Text(
            "重试 ›",
            fontSize = pageSp(12f),
            fontWeight = FontWeight.Bold,
            color = StockNoteColors.Up,
            modifier = Modifier
                .clickable { onRetry() }
                .padding(start = 12.dp, bottom = 12.dp),
        )
    }
}

/**
 * 当前字体缩放档位（技术方案-字体大小设置 · 方案 C 务实版）。
 *
 * 用途：**次要文案反向补偿** —— 大字号档下正文放大，但页头欢迎语、行内副信息这类
 * "辅助文字"若同步放大会把布局撑爆。做法是这些文字用 `(基准sp / scale).sp`，
 * 视觉大小恒定、不占额外空间；主信息（金额/名称）照常放大。
 */
val LocalAppFontScale = androidx.compose.runtime.compositionLocalOf { 1.0f }

/**
 * **页面级字号缩放开关**（老周 2026-09-21）：
 * 需要整页放大的页面用
 * `CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) { ...页内容... }`
 * 包住整页（含弹窗），页内所有组件字号（[pageSp]）随之放大；
 * 未包的页面默认 1f，**完全不受影响**。
 *
 * 与「字体大小设置」（`LocalAppFontScale`，全局、用户自选）是两套独立机制，互不干扰。
 * ⚠️ 2026-09-21 决定：页面字号不再使用视觉恒定机制（旧名 fixedSp）——它会让"最后一行"
 * 比同卡片的 label 还小（老周两次反馈的"字太小"都源于此），该机制已整体移除。
 */
val LocalPageTextScale = androidx.compose.runtime.compositionLocalOf { 1f }

/** 页面希望的整体放大系数（统计页先行试验 1.2，现已推广到各主页/表单页）。 */
internal const val PAGE_TEXT_SCALE = 1.2f

/** 组件内统一用它取字号：基础字号 × 页面缩放系数。 */
@androidx.compose.runtime.Composable
fun pageSp(baseSp: Float) = (baseSp * LocalPageTextScale.current).sp
