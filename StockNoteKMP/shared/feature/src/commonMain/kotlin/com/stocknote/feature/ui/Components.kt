package com.stocknote.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.feature.theme.StockNoteColors

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = StockNoteColors.Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    fontSize = pageSp(14f),
                    fontWeight = FontWeight.Medium,
                    color = StockNoteColors.TextSecondary,
                )
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

/** 指标块：小标题 + 大数字 */
@Composable
fun StatTile(
    label: String,
    value: String,
    valueColor: Color = StockNoteColors.TextPrimary,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, fontSize = pageSp(12f), color = StockNoteColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            fontSize = pageSp(18f),
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
        )
    }
}

/** 一行「标签左 / 数值右」，持仓列表与分析区复用它 */
@Composable
fun MetaRow(
    label: String,
    value: String,
    valueColor: Color = StockNoteColors.TextPrimary,
    labelColor: Color = StockNoteColors.TextSecondary,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // label 占据剩余宽度自然换行（老周 2026-09-16：长标签的括号部分落到第二行，
        // 金额保持右侧完整——修复大字档下金额被挤成碎片）
        Text(label, fontSize = pageSp(13f), color = labelColor, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = pageSp(13f),
            fontWeight = FontWeight.Medium,
            color = valueColor,
            maxLines = 1,
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** 涨跌色点 —— 列表里用形状而不是颜色单独承载信息，色盲用户也能分辨方向 */
@Composable
fun TrendDot(value: Double, modifier: Modifier = Modifier) {
    val color = when {
        value > 1e-9 -> StockNoteColors.Up
        value < -1e-9 -> StockNoteColors.Down
        else -> StockNoteColors.TextTertiary
    }
    Box(
        modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

/** 2 列指标网格（图表页区间统计在用） */
@Composable
fun KeyValueGrid(
    items: List<Triple<String, String, Color>>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEach { (label, value, color) ->
                    Box(Modifier.weight(1f)) {
                        StatTile(label = label, value = value, valueColor = color)
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}


/**
 * 保存 / 修改失败提示弹窗（老周 2026-09-18）。
 *
 * 背景：校验失败原本只在字段旁显示红字；当问题字段在滚动区外（或用户没留意）时，
 * 表现就是「点了保存按钮没反应」。现改为**每次失败都弹一个明确的窗口**，把问题一次列清。
 *
 * @param failTick 失败计数器：**每次**点保存失败都 +1。
 *   用计数器而不是 messages 当 key —— 否则连续两次同样的错误不会重新弹。
 * @param messages 要展示的问题列表（校验 issues 的 message，或 error 文案）
 * @param title 弹窗标题
 */
@Composable
fun SaveErrorDialog(
    failTick: Int,
    messages: List<String>,
    title: String = "无法保存",
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(failTick) { if (failTick > 0) visible = true }
    if (!visible) return
    AlertDialog(
        onDismissRequest = { visible = false },
        title = {
            Text(
                title,
                fontSize = pageSp(16f),
                fontWeight = FontWeight.Bold,
                color = StockNoteColors.TextPrimary,
            )
        },
        text = {
            Column {
                val list = messages.ifEmpty { listOf("请检查填写的内容后重试") }
                list.forEach { msg ->
                    Text(
                        "· $msg",
                        fontSize = pageSp(13f),
                        lineHeight = pageSp(19f),
                        color = StockNoteColors.TextPrimary,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { visible = false }) {
                Text("知道了", color = StockNoteColors.Brand, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color.White,
    )
}

/**
 * 通用信息弹窗（老周 2026-09-18）：告知类结果，如「刷新完成」。
 *
 * 与 [SaveErrorDialog] 的区别：这个由**调用方控制**（message 非空即显示），
 * 不需要失败计数器 —— 每次刷新都会把 message 置成新的内容。
 */
@Composable
fun InfoDialog(
    message: String?,
    title: String = "提示",
    onDismiss: () -> Unit,
) {
    if (message == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, fontSize = pageSp(16f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
        },
        text = {
            Text(message, fontSize = pageSp(13f), lineHeight = pageSp(20f), color = StockNoteColors.TextPrimary)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", color = StockNoteColors.Brand, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color.White,
    )
}
