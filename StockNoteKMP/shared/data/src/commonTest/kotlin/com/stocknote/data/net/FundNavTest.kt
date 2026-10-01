package com.stocknote.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 基金历史净值解析单测（老周 2026-09-20）。
 *
 * 场外基金没有交易所行情，「浮盈」完全靠每日**单位净值** —— 这个解析错了，
 * 场外基金的持仓盈亏就整条是错的，因此按接口真实形态做夹具。
 */
class FundNavTest {

    /** 天天基金 f10/lsjz 实测返回（510300 沪深300ETF）。 */
    private val navJson = """
    {"Data":{"LSJZList":[
      {"FSRQ":"2026-09-18","DWJZ":"1.3330","LJJZ":"2.1450","JZZZL":"2.70","SGZT":"开放申购","SHZT":"开放赎回"},
      {"FSRQ":"2026-09-17","DWJZ":"1.2980","LJJZ":"2.1100","JZZZL":"-0.46","SGZT":"开放申购","SHZT":"开放赎回"},
      {"FSRQ":"2026-09-16","DWJZ":"1.3040","LJJZ":"2.1160","JZZZL":"","SGZT":"开放申购","SHZT":"开放赎回"}
    ],"FundType":"ETF"},"ErrCode":0,"ErrMsg":null,"TotalCount":3021,"PageIndex":1,"PageSize":20}
    """.trimIndent()

    @Test
    fun `净值-解析出日期_单位净值_涨跌幅`() {
        val list = QuoteClient.parseFundNav(navJson)
        assertEquals(3, list.size)
        val first = list.first()
        assertEquals("2026-09-18", first.date)
        assertEquals(1.3330, first.nav, 1e-9)
        assertEquals(2.70, first.growthPct!!, 1e-9)
        // 接口按日期降序返回，升序由调用方负责 → 这里只断言原序未被改动
        assertEquals("2026-09-16", list.last().date)
    }

    @Test
    fun `净值-涨跌幅为空时容忍_不拖垮整条记录`() {
        val list = QuoteClient.parseFundNav(navJson)
        val zh = list.first { it.date == "2026-09-16" }
        assertEquals(1.3040, zh.nav, 1e-9)
        assertEquals(null, zh.growthPct)
    }

    @Test
    fun `净值-缺字段或非法值跳过该条`() {
        val json = """
        {"Data":{"LSJZList":[
          {"FSRQ":"2026-09-18","DWJZ":"1.3330"},
          {"FSRQ":"2026-09-17","DWJZ":""},
          {"FSRQ":"2026-09-16","DWJZ":"0"},
          {"FSRQ":"bad","DWJZ":"1.1"},
          {"FSRQ":"2026-09-15","DWJZ":"1.2000"}
        ]}}
        """.trimIndent()
        val list = QuoteClient.parseFundNav(json)
        assertEquals(2, list.size, "空净值/0/非法日期要跳过")
        assertEquals("2026-09-18", list[0].date)
        assertEquals("2026-09-15", list[1].date)
    }

    @Test
    fun `净值-不存在的基金代码返回空列表_不抛异常`() {
        assertEquals(0, QuoteClient.parseFundNav("""{"Data":{"LSJZList":[]},"ErrCode":0}""").size)
        assertEquals(0, QuoteClient.parseFundNav("""{"ErrCode":-999,"ErrMsg":"参数错误"}""").size)
        assertEquals(0, QuoteClient.parseFundNav("not-json").size)
    }

    @Test
    fun `净值-带时分秒的日期截断为10位`() {
        val json = """{"Data":{"LSJZList":[{"FSRQ":"2026-09-18 00:00:00","DWJZ":"1.5"}]}}"""
        val list = QuoteClient.parseFundNav(json)
        assertEquals(1, list.size)
        assertTrue(list.single().date == "2026-09-18")
    }
}
