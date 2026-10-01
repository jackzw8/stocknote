package com.stocknote.core.calc

import kotlin.math.pow

/**
 * 图表坐标换算。
 *
 * 刻意放在领域层（而不是 UI 层）的原因：这是纯数学，**放这里就能被单测覆盖**。
 * 自绘图表最容易出的 bug 就是坐标算错、边界值溢出画布，
 * 而这类 bug 在 Compose 里只能靠肉眼看——所以把数学抽出来测。
 *
 * 所有函数返回的是「相对画布左上角」的像素坐标，与具体平台无关。
 */
object ChartMath {

    /** 值域区间 */
    data class Range(val min: Double, val max: Double) {
        val span: Double get() = max - min
    }

    /**
     * 计算值域，并在上下各留 [padRatio] 比例的留白，避免曲线贴边或被裁掉。
     * 全等值序列会被撑成一个非零区间，否则后面除零。
     */
    fun rangeOf(values: List<Double>, padRatio: Double = 0.05): Range {
        if (values.isEmpty()) return Range(0.0, 1.0)
        val lo = values.min()
        val hi = values.max()
        if (hi - lo < 1e-12) {
            val pad = if (kotlin.math.abs(hi) < 1e-12) 1.0 else kotlin.math.abs(hi) * 0.05
            return Range(lo - pad, hi + pad)
        }
        val pad = (hi - lo) * padRatio
        return Range(lo - pad, hi + pad)
    }

    /**
     * 把数值映射为 y 像素。注意屏幕坐标系 y 向下，所以最大值映射到 topPad（靠上）。
     *
     * @param topPad    画布顶部内边距（像素）
     * @param bottomPad 画布底部内边距（像素）
     */
    fun yFor(value: Double, range: Range, height: Float, topPad: Float, bottomPad: Float): Float {
        val usable = (height - topPad - bottomPad).coerceAtLeast(1f)
        if (range.span < 1e-12) return topPad + usable / 2f
        val t = (value - range.min) / range.span
        return (topPad + usable * (1.0 - t)).toFloat()
    }

    /**
     * 把索引映射为 x 像素。
     * 只有 1 个点时居中；否则首点贴左内边距、末点贴右内边距。
     */
    fun xFor(index: Int, count: Int, width: Float, leftPad: Float, rightPad: Float): Float {
        val usable = (width - leftPad - rightPad).coerceAtLeast(1f)
        if (count <= 1) return leftPad + usable / 2f
        val i = index.coerceIn(0, count - 1)
        return (leftPad + usable * i / (count - 1).toFloat())
    }

    /** 取「好看」的刻度值：把 [step] 向上归整到 1/2/5 × 10^n */
    fun niceStep(rawStep: Double): Double {
        if (rawStep <= 0.0) return 1.0
        val exp = kotlin.math.floor(kotlin.math.log10(rawStep))
        val base = 10.0.pow(exp)
        val norm = rawStep / base
        val nice = when {
            norm <= 1.0 -> 1.0
            norm <= 2.0 -> 2.0
            norm <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * base
    }

    /** 生成覆盖 [range] 的刻度线数值集合，最多 [maxTicks] 条。 */
    fun ticks(range: Range, maxTicks: Int = 4): List<Double> {
        if (maxTicks < 2) return listOf(range.min, range.max)
        val raw = range.span / (maxTicks - 1)
        val step = niceStep(raw)
        if (step <= 0.0) return listOf(range.min, range.max)
        val start = kotlin.math.ceil(range.min / step) * step
        val out = ArrayList<Double>()
        var v = start
        var guard = 0
        while (v <= range.max + 1e-9 && guard < 64) {
            out += v
            v += step
            guard++
        }
        return if (out.isEmpty()) listOf(range.min, range.max) else out
    }
}
