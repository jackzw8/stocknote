package com.stocknote.data.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `todayIso()` 的**日期格式护栏**（H5 修复，2026-09-28）。
 *
 * 背景：iOS 侧实现（`Platform.ios.kt`）曾漏了 `NSISO8601DateFormatWithDashSeparatorInDate`，
 * 输出是 `20260928`（8 位）而不是 `2026-09-28`；而全项目都按 ISO 串比较/截取日期
 * （默认交易日期、目标价 `endTime`、巨潮 `edate`、扫雷 `daysAgo`、分红日期比较），
 * 8 位串会让这些比较**静默错乱** —— 不崩、不报错，只是结果不对。
 * 同时 `NSISO8601DateFormatter` 缺省 GMT，未显式设时区时中国时区 00:00–08:00 会拿到"昨天"。
 *
 * 这条断言把「必须是 `yyyy-MM-dd`（10 位带横线）」钉死，两个平台的实现都由它约束。
 *
 * ⚠️ 本文件位于 **androidUnitTest**：本机（Windows）只能跑 Android 实现（`java.time.LocalDate`）。
 * iOS 实现无法在此编译验证 —— 到 macOS 上首次编译时，请把这条断言同样跑一遍
 * （或把 `todayIso` 的"格式校验"抽到公共层，两平台都能测）。
 */
class TodayIsoTest {

    @Test
    fun 今日日期必须是十位带横线的ISO格式() {
        val today = todayIso()
        assertEquals(10, today.length, "应为 yyyy-MM-dd（10 位），实际得到：$today")
        assertEquals('-', today[4], "第 5 位必须是横线，实际得到：$today")
        assertEquals('-', today[7], "第 8 位必须是横线，实际得到：$today")
        assertTrue(
            today.filter { it != '-' }.all { it.isDigit() },
            "除横线外应全为数字，实际得到：$today",
        )
        val year = today.take(4).toIntOrNull()
        assertTrue(
            year != null && year in 2000..2100,
            "年份不合法（可能拿到 8 位串或空串）：$today",
        )
    }

    @Test
    fun 今日日期可以按ISO串比较与截取() {
        // 这是全项目依赖 todayIso() 的两个基本操作，格式一旦退化就会静默错乱
        val today = todayIso()
        // ① 与自身比较相等（区间判断的基础）
        assertTrue(today <= today, "自身比较应成立")
        // ② take(10) 必须仍是完整日期（8 位串会让 take(10) 返回残缺值）
        assertEquals(today, today.take(10), "take(10) 后应保持不变")
        // ③ 同年的 1 月 1 日必须**小于**今天（年初至今区间判断的基础）
        assertTrue("${today.take(4)}-01-01" <= today, "年初应不晚于今天")
    }
}
