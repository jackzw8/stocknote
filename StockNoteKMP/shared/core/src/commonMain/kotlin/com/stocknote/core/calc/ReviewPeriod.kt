package com.stocknote.core.calc

/**
 * 复盘周期（REQ-NOTE-06）的纯函数。字符串进、字符串出，两端共享、可单测。
 *
 * period 存储格式：月 "2026-08"；季 "2026Q3"。
 */
object ReviewPeriod {

    const val TYPE_MONTH = "MONTH"
    const val TYPE_QUARTER = "QUARTER"

    /** "2026-08-14" → "2026-08"。非法输入返回 null。 */
    fun monthOf(isoDate: String): String? {
        val m = Regex("""^(\d{4})-(\d{2})""").find(isoDate.trim()) ?: return null
        val month = m.groupValues[2].toInt()
        return if (month in 1..12) "${m.groupValues[1]}-${m.groupValues[2]}" else null
    }

    /** "2026-08-14" → "2026Q3"。非法输入返回 null。 */
    fun quarterOf(isoDate: String): String? {
        val m = Regex("""^(\d{4})-(\d{2})""").find(isoDate.trim()) ?: return null
        val month = m.groupValues[2].toInt()
        if (month !in 1..12) return null
        val q = (month - 1) / 3 + 1
        return "${m.groupValues[1]}Q$q"
    }

    /** "2026-08" → "2026年8月"；"2026Q3" → "2026年第3季度"。 */
    fun display(period: String): String {
        val q = Regex("""^(\d{4})Q([1-4])$""").find(period)
        if (q != null) return "${q.groupValues[1]}年第${q.groupValues[2].toInt()}季度"
        val m = Regex("""^(\d{4})-(\d{2})$""").find(period)
        if (m != null) return "${m.groupValues[1]}年${m.groupValues[2].toInt()}月"
        return period
    }

    /**
     * 某个 period 覆盖的日期区间（闭区间，ISO）。
     * 月 → "2026-08-01"~"2026-08-31"；季 → "2026-07-01"~"2026-09-30"。
     * 用于关联该时间段的绩效（月内盈亏 / 胜率 / 笔数）。
     */
    fun range(period: String): Pair<String, String>? {
        val q = Regex("""^(\d{4})Q([1-4])$""").find(period)
        if (q != null) {
            val year = q.groupValues[1]
            val quarters = mapOf(1 to "01-01~03-31", 2 to "04-01~06-30", 3 to "07-01~09-30", 4 to "10-01~12-31")
            val (start, end) = quarters[q.groupValues[2].toInt()]!!.split("~")
            return "$year-$start" to "$year-$end"
        }
        val m = Regex("""^(\d{4})-(\d{2})$""").find(period) ?: return null
        val year = m.groupValues[1]
        val month = m.groupValues[2].toInt()
        if (month !in 1..12) return null
        val lastDay = when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            else -> if (isLeap(year.toInt())) 29 else 28
        }
        return "$year-${m.groupValues[2]}-01" to "$year-${m.groupValues[2]}-$lastDay"
    }

    private fun isLeap(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
}
