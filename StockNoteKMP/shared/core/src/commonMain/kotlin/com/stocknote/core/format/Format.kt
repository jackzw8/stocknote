package com.stocknote.core.format

/**
 * 数字格式化。放在领域层而不是 UI 层，理由和 ChartMath 一样：纯函数、可单测。
 * 共享层没有 java.text / NSNumberFormatter，两端要一致就只能自己写。
 */
object Format {

    /**
     * **隐私遮罩开关**（REQ-VIEW-09 「隐藏盈亏模式」，老周 2026-09-30）。
     *
     * 打开后，**展示用**的格式化函数（[money] / [moneySigned] / [plain] / [quantity]（仅展示态）/
     * [percent] / [wan]）会把数字与正负号换成 `•`，保留货币符 / 千分位 / 小数点 / `%` 等结构字符 ——
     * 观感等价于原型 `隐私.html` 里的 `filter: blur(6px)`，但**不依赖 blur API**（API 31+ 才有，
     * 低版本会静默失效、真机就泄漏了）。
     *
     * ⚠️ **边界（改动前必读）**：
     *  - [fixedPlain] / [fixed] / [groupDigits] **刻意不遮罩** —— CSV 导出、备份、写库走的是这一批，
     *    否则"开了隐私模式再导出"会导出一堆 `••••`；
     *  - [quantity] 只在 `group = true`（展示）时遮罩，`group = false` 是**回填输入框**用的，
     *    遮了会让编辑交易时数量框变成 `•••`（保存直接失败）。
     *
     * 由 feature 层在 App 根部按 `AppDisplaySettings.privacyMode` 写入（同一帧内生效）。
     */
    var privacyMasked: Boolean = false

    /** 遮罩：数字与正负号 → `•`；其它字符（¥ / , / . / % / HK$…）原样保留。 */
    private fun mask(text: String): String =
        text.map { c -> if (c.isDigit() || c == '+' || c == '-') '•' else c }.joinToString("")

    /** 展示出口：隐私模式下统一走这里。 */
    private fun out(text: String): String = if (privacyMasked) mask(text) else text

    /** 金额：¥1,286,540.00；负号为 -¥1,200.50（符号在外、货币符在内） */
    fun money(value: Double, symbol: String = "¥", decimals: Int = 2): String = out(
        (if (value < 0) "-" else "") + symbol + fixed(kotlin.math.abs(value), decimals, group = true),
    )

    /** 带符号金额：+¥4,300.00 / -¥1,200.00 —— 表格里用得多 */
    fun moneySigned(value: Double, symbol: String = "¥", decimals: Int = 2): String = out(
        (if (value > 0) "+" else if (value < 0) "-" else "") + symbol + fixed(kotlin.math.abs(value), decimals, group = true),
    )

    /**
     * 朴素定点数：**无货币符、无千分位**，负号保留，如 `16986.17` / `-23481.34`。
     * 用于窄格子（盈亏日历）——带千分位的金额在小格里会溢出。
     */
    fun plain(value: Double, decimals: Int = 2): String = out(
        (if (value < 0) "-" else "") + fixed(kotlin.math.abs(value), decimals, group = false),
    )

    /**
     * 数量：整数不带小数点，小数最多 [maxDecimals] 位且不补零。
     *
     * ⚠️ [group] 默认 `true`（整数走千分位，如 `1,000`，**只适合展示**）。
     * **回填到输入框时必须传 `false`** —— 带逗号的串不是合法数字输入：
     * 2026-09-28 的 H1 就是编辑一笔 ≥1000 股的交易时回填了 `1,000`，
     * 导致 ① 保存被判「数量必须是数字」完全存不了；② `feeRateOf` 解析失败退回 `0` → **手续费被清零**。
     */
    fun quantity(value: Double, maxDecimals: Int = 2, group: Boolean = true): String {
        // ⚠️ 隐私遮罩（REQ-VIEW-09）**只作用于展示态**（group = true）；
        // group = false 是"回填输入框"用的，遮了会让编辑交易时数量框变成 `•••`，
        // 保存直接被判「数量必须是数字」—— 那样隐私模式就成了功能破坏。
        if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) {
            val digits = kotlin.math.floor(kotlin.math.abs(value)).toLong().toString()
            val shown = if (group) groupDigits(digits) else digits
            val text = if (value < 0) "-$shown" else shown
            return if (group) out(text) else text
        }
        val raw = fixed(value, maxDecimals, group = false)
        val trimmed = if (raw.contains('.')) raw.trimEnd('0').trimEnd('.') else raw
        return if (group) out(trimmed) else trimmed
    }

    /** 百分比：+9.72% / —（null 表示不可计算） */
    fun percent(ratio: Double?, decimals: Int = 2, signed: Boolean = true): String {
        if (ratio == null || ratio.isNaN() || ratio.isInfinite()) return "—"
        val pct = ratio * 100.0
        val sign = if (signed) (if (pct > 0) "+" else if (pct < 0) "-" else "") else ""
        return out(sign + fixed(kotlin.math.abs(pct), decimals, group = false) + "%")
    }

    /** 世界标准时间戳 → yyyy-MM-dd HH:mm（避免在两端引入各自的日期格式化实现） */
    fun epochMillisToDateTime(epochMillis: Long): String {
        val totalSeconds = epochMillis / 1000
        val days = kotlin.math.floor(totalSeconds / 86400.0).toLong()
        var secOfDay = totalSeconds - days * 86400
        if (secOfDay < 0) secOfDay += 86400
        val (y, m, d) = civilFromDays(days)
        val hh = (secOfDay / 3600).toString().padStart(2, '0')
        val mm = ((secOfDay % 3600) / 60).toString().padStart(2, '0')
        return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')} $hh:$mm"
    }

    // ---- internals ----

    /**
     * **纯手写的定点格式化**（不含 `String.format`，因此**不受设备 Locale 影响**）—— M7 修复（2026-09-27）。
     *
     * 为什么需要它：`"%.2f".format(v)` 走默认 Locale，在小数分隔符为**逗号**的地区会输出 `168092,01`，
     * 而 CSV 导入端用 `toDoubleOrNull()` 解析 → **整行被静默跳过**（导出再导入 = 自毁）。
     * 所以 CSV 的数值列统一走这里；`commonMain` 里没有 `java.util.Locale`，也正好不能靠它绕过。
     */
    /**
     * **以「万」为单位的紧凑金额**（老周 2026-09-28：资产曲线 Y 轴、交易计划金额合计用）。
     *
     * 例：`1347649.36` → `134.8万`（decimals = 1）；`8500` → `0.9万`。
     * ⚠️ 内部用 [fixedPlain]（纯手写），**不受设备 Locale 影响** —— 与 M7 修的是同一类问题。
     */
    fun wan(value: Double, decimals: Int = 1): String = out(fixedPlain(value / 10000.0, decimals) + "万")

    fun fixedPlain(value: Double, decimals: Int = 2): String = fixed(value, decimals, group = false)

    private fun fixed(value: Double, decimals: Int, group: Boolean): String {
        val negative = value < 0
        val abs = kotlin.math.abs(value)
        var factor = 1L
        repeat(decimals) { factor *= 10 }
        val scaled = kotlin.math.round(abs * factor).toLong()
        val intPart = (scaled / factor).toString()
        val fracPart = if (decimals > 0) (scaled % factor).toString().padStart(decimals, '0') else ""
        val head = if (group) groupDigits(intPart) else intPart
        val body = if (decimals > 0) "$head.$fracPart" else head
        return if (negative) "-$body" else body
    }

    private fun groupDigits(intPart: String): String {
        if (intPart.length <= 3) return intPart
        val sb = StringBuilder()
        val first = intPart.length % 3
        var idx = 0
        if (first != 0) {
            sb.append(intPart, 0, first)
            idx = first
        }
        while (idx < intPart.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(intPart, idx, idx + 3)
            idx += 3
        }
        return sb.toString()
    }

    /** Howard Hinnant 的 civil_from_days，与 CivilDate.toEpochDay 互逆 */
    private fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
    }
}
