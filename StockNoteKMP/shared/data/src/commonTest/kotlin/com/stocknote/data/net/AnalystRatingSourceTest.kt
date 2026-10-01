package com.stocknote.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 机构评级聚合（东财 `RPT_WEB_RESPREDICT`）与「两级取数」决策（老周 2026-09-21）。
 *
 * 夹具是**真实响应片段**（2026-09-21 抓的 600519 与 000001）：
 * 自造数据会漏掉真实接口的坑 —— 家数可能是 null、目标价可能只给一边、
 * 也会出现"整行都有 key 但值为 null"的形态。
 */
class AnalystRatingSourceTest {

    /** 真实响应：贵州茅台（45 家评级，目标价 1430 ~ 2030） */
    private val maotai = """
    {"version":"x","result":{"pages":1,"data":[{
      "SECUCODE":"600519.SH","SECURITY_CODE":"600519","SECURITY_NAME_ABBR":"贵州茅台",
      "RATING_ORG_NUM":45,"RATING_BUY_NUM":36,"RATING_ADD_NUM":9,"RATING_NEUTRAL_NUM":null,
      "RATING_REDUCE_NUM":null,"RATING_SALE_NUM":null,"RATING_LONG_NUM":45,
      "DEC_AIMPRICEMAX":2030,"DEC_AIMPRICEMIN":1430
    }]},"success":true}
    """.trimIndent()

    /** 真实响应：平安银行（21 家评级，目标价 13.21 ~ 15.1）—— 它的逐条研报目标价是 0 份，全靠这个源 */
    private val pingan = """
    {"result":{"pages":1,"data":[{
      "RATING_ORG_NUM":21,"RATING_BUY_NUM":10,"RATING_ADD_NUM":10,"RATING_NEUTRAL_NUM":null,
      "RATING_REDUCE_NUM":null,"RATING_SALE_NUM":null,
      "DEC_AIMPRICEMAX":15.1,"DEC_AIMPRICEMIN":13.21
    }]}}
    """.trimIndent()

    @Test
    fun 解析评级家数与目标价区间() {
        val p = AnalystRatingSource.parse(maotai)!!
        assertEquals(45, p.orgCount)
        assertEquals(36, p.buy)
        assertEquals(9, p.add)
        assertNull(p.neutral)
        assertTrue(p.hasRange)
        assertEquals(1430.0, p.aimMin)
        assertEquals(2030.0, p.aimMax)
        // 区间中点 = (1430+2030)/2（**不是**均价，只是兜底填值用）
        assertEquals(1730.0, p.midOfRange!!, 1e-9)
        assertEquals("45 家（买入 36 / 增持 9）", p.countText)
        assertEquals("1430.0 ~ 2030.0", p.rangeText)
    }

    @Test
    fun 只看区间与家数的边界() {
        val p = AnalystRatingSource.parse(pingan)!!
        assertEquals(21, p.orgCount)
        assertEquals(14.155, p.midOfRange!!, 1e-9)
        assertEquals("21 家（买入 10 / 增持 10）", p.countText)
    }

    @Test
    fun 没有有效数据返回null() {
        assertNull(AnalystRatingSource.parse(""))
        assertNull(AnalystRatingSource.parse("null"))
        assertNull(AnalystRatingSource.parse("""{"result":{"data":[]}}"""))
        // 整行为空（接口对不存在的代码会回一行全 null）→ 视为无数据
        assertNull(
            AnalystRatingSource.parse(
                """{"result":{"data":[{"RATING_ORG_NUM":null,"DEC_AIMPRICEMAX":null,"DEC_AIMPRICEMIN":null}]}}""",
            ),
        )
    }

    @Test
    fun 只有单边目标价时不给区间中点() {
        val oneSide = """{"result":{"data":[{"RATING_ORG_NUM":5,"DEC_AIMPRICEMAX":20,"DEC_AIMPRICEMIN":null}]}}"""
        val p = AnalystRatingSource.parse(oneSide)!!
        assertTrue(!p.hasRange)
        assertNull(p.midOfRange)
        assertNull(p.rangeText)
        assertEquals("5 家", p.countText)   // 没有分档家数时只写总数
    }

    @Test
    fun 两级取数_有逐条均值时优先用均值并带上机构对照() {
        val reports = AnalystTargetSource.parse(
            """{"data":[
              {"publishDate":"2026-08-18 00:00:00.000","orgSName":"群益证券","sRatingName":"买入","indvAimPriceT":"1430","indvAimPriceL":"1430"},
              {"publishDate":"2026-07-20 00:00:00.000","orgSName":"群益证券","sRatingName":"买入","indvAimPriceT":"1430","indvAimPriceL":"1430"},
              {"publishDate":"2026-04-01 00:00:00.000","orgSName":"东兴证券","sRatingName":"强烈推荐","indvAimPriceT":"1716.95","indvAimPriceL":"1716.95"}
            ]}""",
        )
        val c = AnalystTargetSource.consensus(reports, fromIso = "2026-03-21")!!
        val profile = AnalystRatingSource.parse(maotai)
        val s = AnalystTargetSource.suggest(c, profile)
        assertTrue(s is AnalystTargetSource.Suggestion.FromConsensus)
        // 去掉最低 1430 与最高 1716.95 后只剩 1430
        assertEquals(1430.0, s.price, 1e-9)
        // 老周要求：用 ① 取到时也要带上 ② 的对照信息
        assertEquals(45, s.profile!!.orgCount)
    }

    @Test
    fun 两级取数_逐条没样本时退回区间中点() {
        // 平安银行：逐条目标价 0 份 → 用机构区间中点
        val s = AnalystTargetSource.suggest(null, AnalystRatingSource.parse(pingan))
        assertTrue(s is AnalystTargetSource.Suggestion.FromRangeMid)
        assertEquals(14.155, s.price, 1e-9)
    }

    @Test
    fun 两级取数_两个源都没有() {
        val s = AnalystTargetSource.suggest(null, null)
        assertEquals(AnalystTargetSource.Suggestion.None, s)
        // 有评级但**没有区间** → 也不能填数（不能拿"家数"编一个目标价）
        val onlyCount = AnalystRatingSource.parse("""{"result":{"data":[{"RATING_ORG_NUM":5}]}}""")
        assertEquals(AnalystTargetSource.Suggestion.None, AnalystTargetSource.suggest(null, onlyCount))
    }
}
