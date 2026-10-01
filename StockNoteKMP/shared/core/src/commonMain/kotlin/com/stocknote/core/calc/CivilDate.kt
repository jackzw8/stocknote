package com.stocknote.core.calc

/**
 * 纯 Kotlin 民用历日期工具。
 *
 * 领域层刻意不依赖 kotlinx-datetime / java.time：
 *  - 这条链路上只需要「日期差多少天」这一件事；
 *  - 零依赖 = 两端行为绝对一致，且 100% 可单测。
 *
 * 算法取自 Howard Hinnant 的 days_from_civil（proleptic Gregorian，以 1970-01-01 为原点）。
 */
object CivilDate {

    data class Ymd(val year: Int, val month: Int, val day: Int)

    /** 解析 yyyy-MM-dd。非法输入抛 [IllegalArgumentException]。 */
    fun parseIso(iso: String): Ymd {
        require(iso.length >= 10) { "日期格式应为 yyyy-MM-dd，收到: $iso" }
        // 必须显式校验分隔符：只按位置截取的话，"2026/01/01" 恰好能取到合法数字而被误判为有效
        require(iso[4] == '-' && iso[7] == '-') { "日期格式应为 yyyy-MM-dd，收到: $iso" }
        val y = iso.substring(0, 4).toIntOrNull()
        val m = iso.substring(5, 7).toIntOrNull()
        val d = iso.substring(8, 10).toIntOrNull()
        require(y != null && m != null && d != null) { "日期格式应为 yyyy-MM-dd，收到: $iso" }
        require(m in 1..12) { "月份越界: $iso" }
        require(d in 1..daysInMonth(y, m)) { "日期越界: $iso" }
        return Ymd(y, m, d)
    }

    fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> throw IllegalArgumentException("月份越界: $month")
    }

    /** 以 1970-01-01 为第 0 天的儒略日序号 */
    fun toEpochDay(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146097L + doe.toLong() - 719468L
    }

    /** [toEpochDay] 的反函数（Howard Hinnant civil_from_days）。日期选择控件回写用。 */
    fun fromEpochDay(z: Long): Ymd {
        val z0 = z + 719468
        val era = (if (z0 >= 0) z0 else z0 - 146096) / 146097
        val doe = z0 - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = mp + if (mp < 10) 3 else -9
        return Ymd((y + if (m <= 2) 1 else 0).toInt(), m.toInt(), d.toInt())
    }

    /** yyyy-MM-dd 的 UTC 零点毫秒（与 DatePicker 的 utcTimeMillis 口径一致）。非法输入返回 null。 */
    fun isoToUtcMillis(iso: String): Long? =
        runCatching {
            val p = parseIso(iso)
            toEpochDay(p.year, p.month, p.day) * 86_400_000L
        }.getOrNull()

    /**
     * 星期几：0=周一 … 6=周日（日历热力图表头顺序）。
     * 1970-01-01 是周四 → epochDay 0 对应周三(2)，故 +3 后取模。
     */
    fun dayOfWeek(iso: String): Int {
        val p = parseIso(iso)
        val d = toEpochDay(p.year, p.month, p.day)
        return ((d + 3) % 7).let { if (it < 0) it + 7 else it }.toInt()
    }

    /**
     * 星期几：0=周一 … 6=周日（日历热力图表头顺序）。
     */
    fun dayOfWeek(year: Int, month: Int, day: Int): Int {
        val d = toEpochDay(year, month, day)
        return ((d + 3) % 7).let { if (it < 0) it + 7 else it }.toInt()
    }

    /** UTC 零点毫秒 -> yyyy-MM-dd（[isoToUtcMillis] 的反函数）。 */
    fun utcMillisToIso(utcMillis: Long): String {
        val p = fromEpochDay(utcMillis / 86_400_000L)
        return isoOf(p.year, p.month, p.day)
    }

    fun toEpochDay(iso: String): Long = parseIso(iso).let { toEpochDay(it.year, it.month, it.day) }

    /** to - from，单位天。to 晚于 from 时为正。 */
    /**
     * 前一日（yyyy-MM-dd）。
     *
     * ⚠️ 2026-09-28：从 `PortfolioRepository` 的私有 `prevDayOf()` **上提到这里**
     * —— 纯日期运算不该留在上帝类里；提到 core 后也能在 jvmTest 里直接单测。
     * 用 CivilDate 做而非手写 `-1`，跨月/跨年/闰年才不会错。
     */
    fun prevIso(iso: String): String {
        val ymd = fromEpochDay(toEpochDay(iso) - 1)
        return isoOf(ymd.year, ymd.month, ymd.day)
    }

    fun daysBetween(fromIso: String, toIso: String): Long =
        toEpochDay(toIso) - toEpochDay(fromIso)

    /**
     * ISO 日期减去 [days] 天（可为 0），跨月 / 跨年 / 闰年自动处理。
     * 用于「只保留最近 N 天」这类保留策略的截止日计算（老周 2026-09-18）。
     */
    fun minusDays(iso: String, days: Int): String {
        val p = fromEpochDay(toEpochDay(iso) - days.toLong())
        return isoOf(p.year, p.month, p.day)
    }

    /** 年化天数基准，固定 365 天（与 XIRR 口径一致）。 */
    const val DAYS_PER_YEAR: Double = 365.0

    /**
     * 拼 `yyyy-MM-dd`。
     *
     * ⚠️ **不要用 `"%04d-%02d-%02d".format(...)`** —— `String.format` 是 **JVM 专有 API**，
     * Kotlin/Native（iOS）上不存在，会让 `:shared:core:compileKotlinIosArm64` **直接编译失败**
     *（2026-09-30 首次 iOS 打包实测：`Unresolved reference 'format'`）。
     * 补零一律走 [padStart]（Kotlin 标准库，跨平台可用）。
     */
    fun isoOf(year: Int, month: Int, day: Int): String =
        year.toString().padStart(4, '0') + "-" +
            month.toString().padStart(2, '0') + "-" +
            day.toString().padStart(2, '0')
}
