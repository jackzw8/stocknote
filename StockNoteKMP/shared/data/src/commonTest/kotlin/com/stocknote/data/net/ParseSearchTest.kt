package com.stocknote.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 东方财富 suggest 接口解析（parseSearch）单测。
 *
 * 样例取自 2026-09-14 真实接口响应。关键回归：**MarketType 是数字枚举**
 * （1=沪 2=深 5=港股 7=美股）——此前误以为是 "SH"/"SZ" 字母，导致联网搜索
 * 在真机上永远返回空列表。
 */
class ParseSearchTest {

    private fun response(vararg items: String): String = """
        {"Version":"6.2.8","QCode":0,"StatusCode":0,"Message":"成功","Data":null,
         "QuotationCodeTable":{"Data":[${items.joinToString(",")}],
         "Expansion":[],"TipWeight":[]}}
    """.trimIndent()

    @Test
    fun `数字枚举 - 沪A与深A`() {
        val json = response(
            """{"Code":"600519","Name":"贵州茅台","MarketType":"1","SecurityTypeName":"沪A"}""",
            """{"Code":"300750","Name":"宁德时代","MarketType":"2","SecurityTypeName":"深A"}""",
        )
        val hits = QuoteClient.parseSearch(json)
        assertEquals(2, hits.size)
        assertEquals(QuoteClient.SymbolHit("sh600519", "贵州茅台", "A股", "CNY"), hits[0])
        assertEquals(QuoteClient.SymbolHit("sz300750", "宁德时代", "A股", "CNY"), hits[1])
    }

    @Test
    fun `数字枚举 - 沪深基金映射为 ETF`() {
        val json = response(
            """{"Code":"510300","Name":"沪深300ETF华泰柏瑞","MarketType":"1","SecurityTypeName":"基金"}""",
            """{"Code":"159915","Name":"创业板ETF易方达","MarketType":"2","SecurityTypeName":"基金"}""",
        )
        val hits = QuoteClient.parseSearch(json)
        assertEquals(2, hits.size)
        assertEquals("sh510300", hits[0].symbol)
        assertEquals("ETF", hits[0].marketLabel)
        assertEquals("sz159915", hits[1].symbol)
        assertEquals("ETF", hits[1].marketLabel)
    }

    @Test
    fun `数字枚举 - 港股与美股`() {
        val json = response(
            """{"Code":"00700","Name":"腾讯控股","MarketType":"5","SecurityTypeName":"港股"}""",
            """{"Code":"AAPL","Name":"苹果","MarketType":"7","SecurityTypeName":"美股"}""",
        )
        val hits = QuoteClient.parseSearch(json)
        assertEquals(2, hits.size)
        assertEquals(QuoteClient.SymbolHit("hk00700", "腾讯控股", "港股", "HKD"), hits[0])
        assertEquals(QuoteClient.SymbolHit("usAAPL", "苹果", "美股", "USD"), hits[1])
    }

    @Test
    fun `指数被排除 - 搜索000001不应命中上证指数`() {
        val json = response(
            """{"Code":"000001","Name":"平安银行","MarketType":"2","SecurityTypeName":"深A"}""",
            """{"Code":"000001","Name":"上证指数","MarketType":"1","SecurityTypeName":"指数"}""",
        )
        val hits = QuoteClient.parseSearch(json)
        assertEquals(1, hits.size)
        assertEquals("sz000001", hits[0].symbol)
        assertTrue(hits.none { it.name.contains("指数") })
    }

    @Test
    fun `字母枚举兼容 - SH与HK仍然可解析`() {
        val json = response(
            """{"Code":"600519","Name":"贵州茅台","MarketType":"SH"}""",
            """{"Code":"00700","Name":"腾讯控股","MarketType":"HK"}""",
        )
        val hits = QuoteClient.parseSearch(json)
        assertEquals(2, hits.size)
        assertEquals("sh600519", hits[0].symbol)
        assertEquals("hk00700", hits[1].symbol)
    }

    @Test
    fun `场外基金被识别并加of前缀（老周 2026-09-20）`() {
        // 东财实测形态（type=14 搜 519066 / 110022）：MarketType="6"、Classify="OTCFUND"
        val json = response(
            """{"Code":"110022","Name":"易方达消费行业股票","MarketType":"6","Classify":"OTCFUND","SecurityTypeName":"基金"}""",
            """{"Code":"519066","Name":"汇添富蓝筹稳健混合A","MarketType":"6","Classify":"OTCFUND","SecurityTypeName":"基金"}""",
        )
        val hits = QuoteClient.parseSearch(json)
        assertEquals(2, hits.size)
        // ⚠️ 必须带 of 前缀：000001/110022 这类码与股票/可转债同形，裸码无法区分
        assertEquals(QuoteClient.SymbolHit("of110022", "易方达消费行业股票", "基金", "CNY"), hits[0])
        assertEquals("of519066", hits[1].symbol)
    }

    @Test
    fun `深市可转债不能被标成ETF（与CSV建标同源的宽度bug）`() {
        // 旧实现：MarketType=2 且 code 以 "1" 开头 → ETF，
        // 于是 123456（可转债）/ 100303（国债）在搜索里被标成 ETF。
        val json = response(
            """{"Code":"123456","Name":"某可转债","MarketType":"2","SecurityTypeName":"可转债"}""",
            """{"Code":"100303","Name":"某国债","MarketType":"1","SecurityTypeName":"国债"}""",
            """{"Code":"159915","Name":"创业板ETF","MarketType":"2","SecurityTypeName":"ETF"}""",
            """{"Code":"511660","Name":"货币ETF","MarketType":"1","SecurityTypeName":"ETF"}""",
        )
        val hits = QuoteClient.parseSearch(json)
        assertEquals(4, hits.size)
        assertEquals("A股", hits[0].marketLabel)   // 123456 可转债 → A股，不是 ETF
        assertEquals("A股", hits[1].marketLabel)   // 100303 国债 → A股
        assertEquals("ETF", hits[2].marketLabel)   // 159915 真 ETF
        assertEquals("ETF", hits[3].marketLabel)   // 511660 真 ETF
    }

    @Test
    fun `坏结构防御 - 非法JSON或缺字段返回空`() {
        assertTrue(QuoteClient.parseSearch("not a json").isEmpty())
        assertTrue(QuoteClient.parseSearch("""{"QuotationCodeTable":{"Data":[{}]}}""").isEmpty())
    }
}
