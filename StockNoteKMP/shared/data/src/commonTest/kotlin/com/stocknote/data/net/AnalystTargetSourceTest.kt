package com.stocknote.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 分析师目标价解析与「去极值平均」（老周 2026-09-21 口径）。
 *
 * 夹具都是**真实响应片段**（2026-09-21 抓的东财 600519 → 40 份研报，其中 5 份带目标价）：
 * 自造数据会漏掉真实接口的坑（`indvAimPriceT` 是 10 位小数的字符串、空值是 `""` 不是 null、
 * 大部分研报压根不填目标价）。
 */
class AnalystTargetSourceTest {

    /** 真实响应片段：5 份带目标价的研报（日期倒序，与接口一致）。 */
    private val realFixture = """
    {"hits":40,"size":10,"data":[
      {"title":"x","publishDate":"2026-08-21 00:00:00.000","orgSName":"西南证券","sRatingName":"买入","indvAimPriceT":"","indvAimPriceL":""},
      {"publishDate":"2026-08-18 00:00:00.000","orgSName":"群益证券","sRatingName":"区间操作(Tranding Buy)","indvAimPriceT":"1430.0000000000","indvAimPriceL":"1430.0000000000"},
      {"publishDate":"2026-07-20 00:00:00.000","orgSName":"群益证券","sRatingName":"区间操作(Tranding Buy)","indvAimPriceT":"1430.0000000000","indvAimPriceL":"1430.0000000000"},
      {"publishDate":"2026-04-28 00:00:00.000","orgSName":"群益证券","sRatingName":"区间操作(Tranding Buy)","indvAimPriceT":"1525.0000000000","indvAimPriceL":"1525.0000000000"},
      {"publishDate":"2026-04-01 00:00:00.000","orgSName":"东兴证券","sRatingName":"强烈推荐","indvAimPriceT":"1716.9500000000","indvAimPriceL":"1716.9500000000"},
      {"publishDate":"2026-03-23 00:00:00.000","orgSName":"国金证券","sRatingName":"买入","indvAimPriceT":"1773.0000000000","indvAimPriceL":"1773.0000000000"}
    ]}
    """.trimIndent()

    @Test
    fun 只保留填了目标价的研报() {
        val reports = AnalystTargetSource.parse(realFixture)
        // 6 条里只有 5 条填了目标价（第一条是空字符串，必须被跳过而不是当成 0）
        assertEquals(5, reports.size)
        assertTrue(reports.none { it.price <= 0.0 })
        assertEquals("2026-08-18", reports.first().dateIso)
        assertEquals(1430.0, reports.first().price)
        assertEquals("群益证券", reports.first().org)
        assertEquals("买入", reports.last().rating)
    }

    @Test
    fun 目标价区间取中点() {
        // 研报给区间时取中点：只取一端会系统性偏高/偏低
        assertEquals(1500.0, AnalystTargetSource.priceOf("1600", "1400"))
        // 上下限相同 = 单点值
        assertEquals(1430.0, AnalystTargetSource.priceOf("1430", "1430"))
        // 只给一边就用那一边；都没有 / 非正数 → null
        assertEquals(1500.0, AnalystTargetSource.priceOf("1500", ""))
        assertEquals(1400.0, AnalystTargetSource.priceOf("", "1400"))
        assertNull(AnalystTargetSource.priceOf("", ""))
        assertNull(AnalystTargetSource.priceOf("0", "0"))
    }

    @Test
    fun 去掉最大最小后取平均() {
        val all = AnalystTargetSource.parse(realFixture)
        // 全部 5 条（2026-03-21 起）：1430 / 1430 / 1525 / 1716.95 / 1773
        val c = AnalystTargetSource.consensus(all, fromIso = "2026-03-21")!!
        assertEquals(5, c.count)
        // 去掉最低 1430 与最高 1773 → (1430 + 1525 + 1716.95) / 3 = 1557.3166…
        assertEquals((1430.0 + 1525.0 + 1716.95) / 3, c.mean, 1e-9)
        // 原始均值（对照）不被改写
        assertEquals((1430.0 + 1430.0 + 1525.0 + 1716.95 + 1773.0) / 5, c.plainMean, 1e-9)
        assertEquals(1430.0, c.droppedLow!!.price)
        assertEquals(1773.0, c.droppedHigh!!.price)
        assertEquals(1430.0, c.min)
        assertEquals(1773.0, c.max)
    }

    @Test
    fun 时间窗只保留近6个月() {
        val all = AnalystTargetSource.parse(realFixture)
        // 从 2026-04-01 起：剩下 1716.95 / 1525 / 1430 / 1430 四条 → 去掉最低 1430 与最高 1716.95
        val c = AnalystTargetSource.consensus(all, fromIso = "2026-04-01")!!
        assertEquals(4, c.count)
        assertEquals((1430.0 + 1525.0) / 2, c.mean, 1e-9)
    }

    @Test
    fun 样本不足三份时不去极值() {
        // 2026-07-01 之后只剩两份（07-20 与 08-18，都是 1430）
        val two = AnalystTargetSource.parse(realFixture).filter { it.dateIso >= "2026-07-01" }
        val c2 = AnalystTargetSource.consensus(two, fromIso = "2026-07-01")!!
        assertEquals(2, c2.count)
        assertEquals(1430.0, c2.mean, 1e-9)
        assertNull(c2.droppedLow)   // 去掉就没样本了，所以不去
        assertNull(c2.droppedHigh)

        val one = AnalystTargetSource.parse(realFixture).filter { it.dateIso == "2026-04-01" }
        val c1 = AnalystTargetSource.consensus(one, fromIso = "2026-04-01")!!
        assertEquals(1, c1.count)
        assertEquals(1716.95, c1.mean, 1e-9)
    }

    @Test
    fun 没有样本返回null() {
        assertNull(AnalystTargetSource.consensus(emptyList(), fromIso = "2026-03-21"))
        // 全在时间窗之外
        val all = AnalystTargetSource.parse(realFixture)
        assertNull(AnalystTargetSource.consensus(all, fromIso = "2026-09-01"))
    }

    @Test
    fun 只认A股代码_别把场外基金当成股票() {
        assertEquals("600519", AnalystTargetSource.aShareCodeOf("sh600519"))
        assertEquals("000001", AnalystTargetSource.aShareCodeOf("sz000001"))
        assertEquals("430047", AnalystTargetSource.aShareCodeOf("bj430047"))
        // ⚠️ 场外基金 `of000001`（华夏成长混合）绝不能落成 000001 平安银行
        assertNull(AnalystTargetSource.aShareCodeOf("of000001"))
        assertNull(AnalystTargetSource.aShareCodeOf("hk00700"))
        assertNull(AnalystTargetSource.aShareCodeOf("usAAPL"))
        assertNull(AnalystTargetSource.aShareCodeOf("600519"))   // 没前缀也拒
    }

    @Test
    fun 坏数据不抛异常() {
        assertEquals(0, AnalystTargetSource.parse("").size)
        assertEquals(0, AnalystTargetSource.parse("not json").size)
        assertEquals(0, AnalystTargetSource.parse("""{"result":null}""").size)
        // 缺 date 的行被跳过
        assertEquals(0, AnalystTargetSource.parse("""{"data":[{"indvAimPriceT":"100"}]}""").size)
    }

    @Test
    fun 最近6个月的起点按自然月回退() {
        assertEquals("2026-03-21", AnalystTargetSource.monthsAgoIso("2026-09-21", 6))
        assertEquals("2025-09-21", AnalystTargetSource.monthsAgoIso("2026-03-21", 6))
        // 日不足时收到当月最后一天（2/31 → 2/28）
        assertEquals("2026-02-28", AnalystTargetSource.monthsAgoIso("2026-08-31", 6))
        // 跨年
        assertEquals("2025-12-15", AnalystTargetSource.monthsAgoIso("2026-06-15", 6))
    }
}
