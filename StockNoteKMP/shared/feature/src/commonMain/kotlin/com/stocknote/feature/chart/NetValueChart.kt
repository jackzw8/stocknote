package com.stocknote.feature.chart

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.core.calc.ChartMath
import com.stocknote.core.format.Format
import com.stocknote.feature.theme.StockNoteColors

/**
 * 净值 / 价格曲线 —— **Compose Canvas 自绘，不使用任何三方图表库**。
 *
 * 这是本方案里一个刻意的技术决策（见技术说明书 2.0 第 3.5 节）：
 *  - KMP 生态中成熟的图表库很少，引入就等于给自己埋「只在一个平台能编译」的坑；
 *  - 自绘天然跨端，Android 与 iOS 像素完全一致；
 *  - 坐标换算全部走 [ChartMath] 的纯函数，可被单测覆盖
 *    —— 这是任何三方库都给不了的东西。
 *
 * 代价是要自己处理刻度与手势，所以把「数学」和「绘制」严格分开。
 */
@Composable
fun NetValueChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    lineColor: Color = StockNoteColors.Brand,
    /** 基线值（如净值 1.0）；画出虚线参考位 */
    baseline: Double? = null,
    /**
     * 第二组序列（REQ-VIEW-07「可叠加基准曲线」）：基准指数。
     * 会按**同一区间涨跌幅**归一化到与主序列相同的起点后叠加，
     * 只比"形状"不比"金额"（基准是点数，不是钱）。
     */
    compareValues: List<Double>? = null,
    compareColor: Color = Color(0xFF9AA4B2),
    /**
     * X 轴刻度文案（按等距位置显示，如 2025-09 / 2026-01 / 2026-05 / 2026-09）。
     * 不传则只画 Y 轴刻度（原型 charts.html 的 .axis 对齐）。
     */
    xLabels: List<String>? = null,
    /**
     * Y 轴刻度文案。
     * 默认按数值大小自适应金额格式 —— 以前默认用 Format.percent，那是净值口径；
     * 画价格时会变成 "130000.00%" 这种荒谬标签（实机录屏暴露过）。
     */
    labelFormatter: (Double) -> String = { v ->
        val a = kotlin.math.abs(v)
        Format.money(v, decimals = when {
            a >= 1000 -> 0
            a >= 10 -> 1
            else -> 2
        })
    },
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember {
        TextStyle(fontSize = 10.sp, color = StockNoteColors.TextSecondary)
    }

    val safeValues = remember(values) { if (values.isEmpty()) listOf(1.0) else values }

    // 基准归一化：把 compare 序列按"同区间涨跌幅"缩放到主序列的起点值上，
    // 只比形状不比金额（基准是指数点数，不是钱）。
    val compareNorm = remember(values, compareValues) {
        val cv = compareValues
        if (cv != null && cv.size >= 2 && values.isNotEmpty()) {
            val c0 = cv.first()
            if (c0 > 1e-9) {
                val v0 = values.first()
                cv.map { v0 * (it / c0) }
            } else null
        } else null
    }

    val range = remember(safeValues, compareNorm) {
        val all = safeValues + (compareNorm ?: emptyList())
        ChartMath.rangeOf(all)
    }
    val ticks = remember(range) { ChartMath.ticks(range, maxTicks = 4) }
    // Y 轴刻度标签里最宽的一个（像素）。左边距按它算，否则长标签会被画布左边缘裁掉
    // ——老周 2026-09-23 真机反馈：「¥1,000,000」的 ¥ 不见了（固定 48dp 装不下）。
    val labelMaxWidth = remember(ticks, labelStyle) {
        ticks.maxOfOrNull { measurer.measure(labelFormatter(it), labelStyle).size.width }
            ?.toFloat() ?: 0f
    }

    Canvas(modifier) {
        // 左边距 = max(48dp 基准留白, 最长标签宽 + 8dp)，保证任何金额都不会被裁
        val leftPad = maxOf(48.dp.toPx(), labelMaxWidth + 8.dp.toPx())
        val rightPad = 10.dp.toPx()
        val topPad = 12.dp.toPx()
        val bottomPad = if (xLabels != null) 26.dp.toPx() else 14.dp.toPx()
        val w = size.width
        val h = size.height

        // 网格线 + Y 轴刻度
        ticks.forEach { t ->
            val y = ChartMath.yFor(t, range, h, topPad, bottomPad)
            drawLine(
                color = StockNoteColors.Divider,
                start = Offset(leftPad, y),
                end = Offset(w - rightPad, y),
                strokeWidth = 1f,
            )
            val layout = measurer.measure(labelFormatter(t), labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = leftPad - 6.dp.toPx() - layout.size.width,
                    y = y - layout.size.height / 2f,
                ),
            )
        }

        // 基线参考
        if (baseline != null && baseline in range.min..range.max) {
            val by = ChartMath.yFor(baseline, range, h, topPad, bottomPad)
            drawLine(
                color = StockNoteColors.TextTertiary,
                start = Offset(leftPad, by),
                end = Offset(w - rightPad, by),
                strokeWidth = 1f,
                pathEffect = androidx.compose.ui.graphics.PathEffect
                    .dashPathEffect(floatArrayOf(6f, 6f), 0f),
            )
        }

        // 基准叠加线（REQ-VIEW-07「可叠加基准曲线」）：灰虚线，先画在主曲线下层
        if (compareNorm != null && compareNorm.size >= 2) {
            val cmp = Path()
            compareNorm.forEachIndexed { i, v ->
                val x = ChartMath.xFor(i, compareNorm.size, w, leftPad, rightPad)
                val y = ChartMath.yFor(v, range, h, topPad, bottomPad)
                if (i == 0) cmp.moveTo(x, y) else cmp.lineTo(x, y)
            }
            drawPath(
                path = cmp,
                color = compareColor,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = androidx.compose.ui.graphics.PathEffect
                        .dashPathEffect(floatArrayOf(8f, 6f), 0f),
                ),
            )
        }

        if (safeValues.size == 1) {
            val y = ChartMath.yFor(safeValues[0], range, h, topPad, bottomPad)
            drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(leftPad, y))
            return@Canvas
        }

        // 曲线
        val line = Path()
        safeValues.forEachIndexed { i, v ->
            val x = ChartMath.xFor(i, safeValues.size, w, leftPad, rightPad)
            val y = ChartMath.yFor(v, range, h, topPad, bottomPad)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }

        // 曲线下投影，让趋势更易读（不用纯色块，避免遮住网格）
        val area = Path().apply {
            addPath(line)
            lineTo(ChartMath.xFor(safeValues.lastIndex, safeValues.size, w, leftPad, rightPad), h - bottomPad)
            lineTo(ChartMath.xFor(0, safeValues.size, w, leftPad, rightPad), h - bottomPad)
            close()
        }
        drawPath(
            path = area,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.18f), lineColor.copy(alpha = 0.01f)),
                startY = topPad,
                endY = h - bottomPad,
            ),
        )
        drawPath(
            path = line,
            color = lineColor,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        // 末点高亮
        val lastX = ChartMath.xFor(safeValues.lastIndex, safeValues.size, w, leftPad, rightPad)
        val lastY = ChartMath.yFor(safeValues.last(), range, h, topPad, bottomPad)
        drawCircle(color = lineColor.copy(alpha = 0.18f), radius = 7.dp.toPx(), center = Offset(lastX, lastY))
        drawCircle(color = lineColor, radius = 3.5.dp.toPx(), center = Offset(lastX, lastY))
        drawCircle(color = Color.White, radius = 1.5.dp.toPx(), center = Offset(lastX, lastY))

        // X 轴日期刻度：等距显示（原型 charts.html 的 .axis：首/1/3、1/3、2/3、末）
        val xl = xLabels
        if (xl != null && xl.isNotEmpty()) {
            val n = xl.size
            xl.forEachIndexed { i, label ->
                val t = if (n == 1) 0f else i.toFloat() / (n - 1).toFloat()
                val x = leftPad + (w - leftPad - rightPad) * t
                val layout = measurer.measure(label, labelStyle)
                var drawX = x - layout.size.width / 2f
                // 首尾刻度不越界
                if (drawX < 2f) drawX = 2f
                if (drawX + layout.size.width > w - 2f) drawX = w - 2f - layout.size.width
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(drawX, h - 16.dp.toPx()),
                )
            }
        }
    }
}
