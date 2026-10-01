package com.stocknote.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **行情解析契约测试**（老周 2026-10-01，配「东财备源」一起加）。
 *
 * ## 为什么要它
 * 起因：腾讯当天把 `web.ifzq` 的 K 线接口**整站下线**（一律 HTTP 501），资产曲线当场"无数据"，
 * 于是给 [QuoteClient] 补了东财 `push2his` 作为备源。
 *
 * ⚠️ 备源最危险的失败方式不是"报错"，而是**解析口径错**：曲线能画出来、但数字不对，
 * 比直接失败更难发现。所以这里用**真实响应样本**把口径钉死：
 *
 *  1. 东财 `klines` 是**逗号分隔的字符串数组**（不是腾讯那种 JSON 数组），字段序为
 *     `日期,开,收,高,低,量,额,振幅` —— **收盘价在 index 2**；
 *  2. **两个源解析同一段行情必须逐日一致** —— 这是"回退后口径不变"的底线；
 *  3. secid 映射规则（沪 `1.` / 深·北 `0.` / 港 `116.`；美股与场外基金没有备源）；
 *  4. 脏响应要**静默跳过坏行**，而不是整段失败（行情是外部依赖，不能让接口改版把 App 带崩）。
 *
 * ⚠️ 样本是 2026-10-01 用 curl 抓的**真实响应**（东财 `secid=1.000300&klt=101&fqt=1&lmt=3`；
 * 腾讯同标的同三日），两者的开/收/高/低/量已逐位核对过。**纯函数、不联网。**
 *
 * ⚠️ 方法名一律用反引号：Kotlin 标识符**不能含空格**，名字里带空格必须这样写。
 */
class QuoteClientParsingTest {

    /** 东财真实响应（沪深300，2026-09-28 ~ 09-30）。 */
    private val eastmoneySample = """
        {"rc":0,"rt":17,"svr":183642696,"lt":1,"full":0,"dlmkts":"","dsc":"0","data":{"code":"000300","market":1,"name":"沪深300","decimal":2,"dktotal":5282,"preKPrice":4439.14,"klines":["2026-09-28,4423.82,4340.76,4423.82,4323.57,171277373,428620471197.90,2.26","2026-09-29,4335.81,4345.21,4359.30,4324.53,148129466,334950785525.60,0.80","2026-09-30,4356.80,4357.62,4368.61,4341.88,162949626,356491548621.40,0.62"]}}
    """.trimIndent()

    /** 腾讯真实响应（同标的、同三日），走 `data.<symbol>.day`。 */
    private val tencentSample = """
        {"code":0,"msg":"","data":{"sh000300":{"day":[["2026-09-28","4423.820","4340.760","4423.820","4323.570","171277373.000"],["2026-09-29","4335.810","4345.210","4359.300","4324.530","148129466.000"],["2026-09-30","4356.800","4357.620","4368.610","4341.880","162949626.000"]],"qt":{"sh000300":["1"]},"prec":"4544.590","version":"16"}}}
    """.trimIndent()

    /**
     * **最重要的一条**：两个源解析同一段行情，结果必须一致。
     * 只要这条成立，"腾讯挂了自动切东财"就不会让曲线口径发生变化。
     */
    @Test
    fun `东财与腾讯解析同一段行情必须一致`() {
        val em = QuoteClient.parseEastmoneyCandles(eastmoneySample)
        val tx = QuoteClient.parseCandles("sh000300", tencentSample)

        assertEquals(3, em.size, "东财应解析出 3 条")
        assertEquals(tx.size, em.size, "两个源条数应一致")
        assertEquals(tx.map { it.date }, em.map { it.date }, "日期序列应一致")
        // 收盘价：腾讯写 4340.760、东财写 4340.76 —— 同值不同书写，按数值比
        tx.zip(em).forEach { (a, b) ->
            assertEquals(a.close, b.close, 1e-9, "收盘价应一致（${a.date}）")
        }
    }

    /** 东财行是「字符串 + 逗号分隔」，别退化成按 JSON 数组解析（那是腾讯的格式）。 */
    @Test
    fun `东财取到的是 index2 收盘价而不是开盘价`() {
        val em = QuoteClient.parseEastmoneyCandles(eastmoneySample)
        // 09-28：开 4423.82 / 收 4340.76 —— 若误取 index1 就会得到开盘价，此断言会立刻失败
        assertEquals(4340.76, em.first().close, 1e-9, "取到的应是收盘价（index 2）")
        assertEquals("2026-09-28", em.first().date)
        assertEquals(4357.62, em.last().close, 1e-9)
    }

    @Test
    fun `secid 映射覆盖沪深北港不含美股与场外基金`() {
        assertEquals("1.600519", QuoteClient.eastmoneyKlineSecid("sh600519"))
        assertEquals("0.002027", QuoteClient.eastmoneyKlineSecid("sz002027"))
        assertEquals("0.830799", QuoteClient.eastmoneyKlineSecid("bj830799"))
        assertEquals("116.00700", QuoteClient.eastmoneyKlineSecid("hk00700"))
        assertEquals("1.000300", QuoteClient.eastmoneyKlineSecid("sh000300"), "指数同样适用")
        // 美股没有备源（东财要按 105./106./107. 前缀探测交易所，本实现刻意不做）
        assertNull(QuoteClient.eastmoneyKlineSecid("usAAPL"))
        // 场外基金走的是天天基金净值接口（fetchFundNav），不经 K 线
        assertNull(QuoteClient.eastmoneyKlineSecid("of000001"))
    }

    /** 脏响应不能把 App 带崩：结构不对返回空列表，坏行跳过、好行照常解析。 */
    @Test
    fun `东财脏响应返回空列表并跳过坏行`() {
        assertTrue(QuoteClient.parseEastmoneyCandles("").isEmpty())
        assertTrue(QuoteClient.parseEastmoneyCandles("{}").isEmpty())
        assertTrue(QuoteClient.parseEastmoneyCandles("""{"data":{}}""").isEmpty())
        assertTrue(QuoteClient.parseEastmoneyCandles("""{"data":{"klines":[]}}""").isEmpty())

        val partial = QuoteClient.parseEastmoneyCandles(
            """{"data":{"klines":["2026-09-30,4356.80,4357.62,4368.61,4341.88","bad-row","2026-09-29,4335.81,4345.21,4359.30,4324.53"]}}""",
        )
        assertEquals(2, partial.size, "坏行应被跳过，其余照常解析")
        assertEquals(listOf("2026-09-30", "2026-09-29"), partial.map { it.date })
    }
}
