package com.stocknote.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 分红送配数据源解析单测（老周 2026-09-16）。
 * 用**真机实测过的原始返回片段**作为夹具，避免"自己编数据自己过"。
 */
class DividendSourceTest {

    // ---------------------------------------------------------------- A股（东财，税前）

    private val cnJson = """
    {"version":"x","result":{"pages":1,"data":[
      {"SECURITY_CODE":"600519","SECURITY_NAME_ABBR":"贵州茅台",
       "PRETAX_BONUS_RMB":280.2423,"BONUS_RATIO":null,"IT_RATIO":null,
       "EQUITY_RECORD_DATE":"2026-06-25 00:00:00","EX_DIVIDEND_DATE":"2026-06-26 00:00:00",
       "ASSIGN_PROGRESS":"实施分配","IMPL_PLAN_PROFILE":"10派280.2423元(含税)"},
      {"SECURITY_CODE":"600519","PRETAX_BONUS_RMB":100.0,
       "EQUITY_RECORD_DATE":"2027-06-25 00:00:00","EX_DIVIDEND_DATE":"2027-06-26 00:00:00",
       "ASSIGN_PROGRESS":"预案","IMPL_PLAN_PROFILE":"10派100元(预案)"}
    ]}}""".trimIndent()

    @Test
    fun `A股-只取实施分配_且每股口径正确`() {
        val list = DividendSource.parseCnBonus(cnJson, "sh600519")
        assertEquals(1, list.size, "预案应被过滤，只留实施分配")
        val d = list.first()
        assertEquals("2026-06-26", d.exDate)
        // 每10股 280.2423 元 → 每股 28.02423 元（税前）
        assertEquals(28.02423, d.cashPerShare, 1e-9)
        assertTrue(d.taxNote.contains("税前"))
    }

    @Test
    fun `A股-送股与转增按每10股折算成每股`() {
        val json = """
        {"result":{"data":[{"SECURITY_CODE":"600000","PRETAX_BONUS_RMB":50.0,
          "BONUS_RATIO":3.0,"IT_RATIO":2.0,
          "EX_DIVIDEND_DATE":"2026-06-26 00:00:00","ASSIGN_PROGRESS":"实施分配",
          "IMPL_PLAN_PROFILE":"10派50元送3股转2股"}]}}
        """.trimIndent()
        val d = DividendSource.parseCnBonus(json, "sh600000").single()
        assertEquals(5.0, d.cashPerShare, 1e-9)       // 每10股派50 → 每股5
        assertEquals(0.3, d.bonusPerShare, 1e-9)      // 送3股 → 每股0.3
        assertEquals(0.2, d.rightsPerShare, 1e-9)     // 转2股 → 每股0.2
        assertTrue(d.hasCash && d.hasBonus)
    }

    @Test
    fun `A股-坏数据不崩`() {
        assertEquals(0, DividendSource.parseCnBonus("not-json", "sh600519").size)
        assertEquals(0, DividendSource.parseCnBonus("""{"result":{"data":[]}}""", "sh600519").size)
    }

    // ---------------------------------------------------------------- 港股（腾讯，税后）

    private val hkJson = """
    {"code":0,"data":{"hk00700":{"qfqday":[
      ["2026-05-15","444.2","433.0","445.6","433.0","17387096.0",
       {"cqr":"2026-05-15","paixiri":"2026-06-01","FHcontent":"末期息5.3港元;","HGcontent":"回购22.90万股"},
       "0.190","759775.383"]
    ]}}}""".trimIndent()

    @Test
    fun `港股-税后口径_且忽略回购信息`() {
        val list = DividendSource.parseHkBonus(hkJson, "hk00700")
        assertEquals(1, list.size, "应只解析出分红，不把回购当分红")
        val d = list.first()
        assertEquals("2026-05-15", d.exDate)
        assertEquals("2026-06-01", d.payDate)
        // 票面 5.3 港元 → 税后 5.3 × 0.8 = 4.24
        assertEquals(5.3 * 0.8, d.cashPerShare, 1e-9)
        assertTrue(d.taxNote.contains("税后"))
        assertEquals("HKD", d.currency)
    }

    @Test
    fun `港股-人民币派息优先用相当于港元`() {
        // 建行实测片段：末期息0.206人民币;相当于0.22177484港元;
        val pairs = DividendSource.parseHkDividendText("末期息0.206人民币;相当于0.22177484港元;")
        assertEquals(1, pairs.size)
        assertEquals(0.22177484, pairs.first().first, 1e-9)
        assertEquals(DividendSource.HkCurrency.HKD, pairs.first().second)
    }

    @Test
    fun `港股-同日多笔合并_美元与特别息`() {
        // 汇丰实测片段：季度息 + 特别息，且都是美元
        val text = "第一季度息0.1美元;相当于0.780688港元;第一季度特别息0.21美元;相当于1.639445港元;"
        val pairs = DividendSource.parseHkDividendText(text)
        assertEquals(2, pairs.size)
        assertEquals(0.780688, pairs[0].first, 1e-9)
        assertEquals(1.639445, pairs[1].first, 1e-9)
        // 合计（接口给的是港元等值口径）
        assertEquals(0.780688 + 1.639445, pairs.sumOf { it.first }, 1e-9)
    }

    @Test
    fun `港股-无分红的K线不产出记录`() {
        val json = """
        {"data":{"hk00700":{"qfqday":[
          ["2026-09-03","444.2","433.0","445.6","433.0","17387096.0",
           {"cqr":"2026-09-03","FHcontent":"","HGcontent":"回购22.90万股"},"0.190","759"]
        ]}}}""".trimIndent()
        assertEquals(0, DividendSource.parseHkBonus(json, "hk00700").size)
    }

    @Test
    fun `港股-坏数据不崩`() {
        assertEquals(0, DividendSource.parseHkBonus("nope", "hk00700").size)
        assertEquals(0, DividendSource.parseHkDividendText("没有分红字样的文本").size)
    }

    // ---------------------------------------------------------------- 基金 / ETF（天天基金 F10，税前）

    /**
     * 沪深300ETF 510300 的**真实** F10 页片段（2026-09-20 curl 抓取，服务端渲染 HTML）。
     *
     * ⚠️ 夹具必须与真实页面同形：首格是 `2026年`（**带「年」字**）而不是 `2026`。
     * 第一版实现按「首格是 4 位数字」筛行，夹具也照着编成 `2026`，
     * 于是单测全绿、真机却一条都取不到 —— 这次直接把真实片段抄进来。
     *
     * 另外这份片段**故意保留了同页的「拆分折算」表**（`2012年 | 2012-05-11 | 份额折算 | 1:0.3709`），
     * 它是真页面上紧跟在分红表后的第二张表，用来验证解析不会把它当成分红行。
     */
    private val fundHtml = """
    <html><body>
    <div class="box">
      <h4 class="title">分红送配详情</h4>
      <table class="w650">
        <thead><tr><th class='first'>年份</th><th>权益登记日</th><th>除息日</th><th>每10份分红</th><th class='last'>分红发放日</th></tr></thead>
        <tbody>
          <tr><td>2026年</td><td>2026-01-16</td><td>2026-01-19</td><td>每10份派现金1.2300元</td><td>2026-01-27</td></tr>
          <tr><td>2024年</td><td>2024-12-16</td><td>2024-12-17</td><td>每10份派现金0.4500元</td><td>2024-12-24</td></tr>
        </tbody>
      </table>
      <h4 class="title">拆分详情</h4>
      <table class="w650">
        <thead><tr><th class='first'>年份</th><th>拆分折算日</th><th>拆分类型</th><th class='last'>拆分折算比例</th></tr></thead>
        <tbody><tr><td>2012年</td><td>2012-05-11</td><td>份额折算</td><td>1:0.3709</td></tr></tbody>
      </table>
    </div>
    </body></html>""".trimIndent()

    @Test
    fun `ETF-取到权益登记日与除息日_且只产出现金`() {
        val list = DividendSource.parseCnFundDividend(fundHtml, "sh510300")
        assertEquals(2, list.size, "拆分折算表不能被当成分红（第3格不是日期）")
        val d = list.first()
        // 升序 → 最早的一条在前
        assertEquals("2024-12-17", d.exDate)
        assertEquals("2024-12-16", d.recordDate)
        assertEquals("2024-12-24", d.payDate)
        assertEquals(0.045, d.cashPerShare, 1e-9)      // 每10份0.45 → 每份0.045
        // 基金没有送股/转增/配股
        assertEquals(0.0, d.bonusPerShare, 1e-9)
        assertEquals(0.0, d.rightsPerShare, 1e-9)
        assertTrue(d.hasCash && !d.hasBonus)
        assertEquals("CNY", d.currency)
        assertTrue(d.taxNote.contains("不代扣税"))
    }

    @Test
    fun `ETF-无分红信息时不产出记录`() {
        val html = """
        <h4>分红送配详情</h4>
        <table><tr><th class='first'>年份</th></tr>
        <tbody><tr><td align='center' colspan='5'>暂无分红信息!</td></tr></tbody></table>
        """.trimIndent()
        assertEquals(0, DividendSource.parseCnFundDividend(html, "sz159915").size)
    }

    @Test
    fun `ETF-只抓分红送配详情表_不误抓页面上其它表格`() {
        // 页面前面还有一张费率表，若不加锚点会被误当成分红行
        val html = """
        <table><tr><td>2025年</td><td>管理费率</td><td>0.50%</td><td>托管费率</td><td>0.10%</td></tr></table>
        <h4>分红送配详情</h4>
        <table><tr><td>2026年</td><td>2026-01-16</td><td>2026-01-19</td><td>每10份派现金1.2300元</td><td>2026-01-27</td></tr></table>
        """.trimIndent()
        val list = DividendSource.parseCnFundDividend(html, "sh510300")
        assertEquals(1, list.size)
        assertEquals("2026-01-19", list.single().exDate)
    }

    @Test
    fun `ETF-坏数据不崩`() {
        assertEquals(0, DividendSource.parseCnFundDividend("", "sh510300").size)
        assertEquals(0, DividendSource.parseCnFundDividend("<html>无表格</html>", "sh510300").size)
    }

    // ---------------------------------------------------------------- 美股（东财 F10，税前票面）

    /**
     * 苹果 `AAPL.O` 的**真实**东财 F10 片段（2026-09-30 curl 实测）。
     *
     * ⚠️ 钉住来源：腾讯美股 K 线 `usfqkline` 每根只有 6 项、**没有分红字段**（港股才有），
     * 所以美股必须走这份东财报表 —— 别再想着复用 [DividendSource.parseHkBonus]。
     */
    private val usJson = """
    {"version":"x","result":{"pages":1,"data":[
      {"SECUCODE":"AAPL.O","SECURITY_CODE":"AAPL","SECURITY_NAME_ABBR":"苹果",
       "NOTICE_DATE":"2026-07-30 00:00:00","ASSIGN_TYPE":"Cash",
       "PLAN_EXPLAIN":"每1股派0.27美元股息",
       "EQUITY_RECORD_DATE":"2026-08-10 00:00:00","BONUS_PAY_DATE":"2026-08-13 00:00:00",
       "EX_DIVIDEND_DATE":"2026-08-10 00:00:00","ASSIGN_PERIOD":"2026季度分配"},
      {"SECUCODE":"AAPL.O","SECURITY_CODE":"AAPL","ASSIGN_TYPE":"Split",
       "PLAN_EXPLAIN":"1拆4","EQUITY_RECORD_DATE":"2020-08-31 00:00:00",
       "EX_DIVIDEND_DATE":"2020-08-31 00:00:00"}
    ]}}""".trimIndent()

    @Test
    fun `美股-每股派息与美元币种_且过滤拆股`() {
        val list = DividendSource.parseUsBonus(usJson, "usAAPL")
        assertEquals(1, list.size, "拆股（ASSIGN_TYPE=Split）不应当成分红")
        val d = list.single()
        assertEquals("2026-08-10", d.exDate)
        assertEquals("2026-08-10", d.recordDate)
        assertEquals("2026-08-13", d.payDate)
        assertEquals(0.27, d.cashPerShare, 1e-9)
        assertEquals("USD", d.currency)
        assertTrue(d.hasCash && !d.hasBonus)
        assertTrue(d.taxNote.contains("税前"))
    }

    @Test
    fun `美股-无除权日时退回权益登记日`() {
        val json = """
        {"result":{"data":[{"ASSIGN_TYPE":"Cash","PLAN_EXPLAIN":"每1股派0.5美元股息",
          "EQUITY_RECORD_DATE":"2026-05-11 00:00:00","EX_DIVIDEND_DATE":null}]}}
        """.trimIndent()
        val d = DividendSource.parseUsBonus(json, "usMSFT").single()
        assertEquals("2026-05-11", d.exDate)
        assertEquals(0.5, d.cashPerShare, 1e-9)
    }

    @Test
    fun `美股-坏数据不崩`() {
        assertEquals(0, DividendSource.parseUsBonus("nope", "usAAPL").size)
        assertEquals(0, DividendSource.parseUsBonus("""{"result":{"data":[]}}""", "usAAPL").size)
        // 方案文本里没有「派X美元/港元/人民币」→ 不产出（宁缺勿造）
        assertEquals(
            0,
            DividendSource.parseUsBonus(
                """{"result":{"data":[{"ASSIGN_TYPE":"Cash","PLAN_EXPLAIN":"无金额"}]}}""",
                "usAAPL",
            ).size,
        )
    }

    // ---------------------------------------------------------------- 响应体合法性（P1-3，2026-10-02）

    /**
     * ⚠️ 下面几个样本是 **2026-10-02 用 curl 抓的真实响应**（HTTP 全部 200）：
     *  - 有数据：`code:0` + `success:true` + `result.data=[…]`
     *  - **无数据**：`code:9201` + `success:false` + `message:"返回数据为空"` + `result:null`
     *  - 参数错：`code:9501` + `message:"报表配置不存在,…"` + `result:null`
     *
     * 关键结论：**「确实没有数据」时 `result` 本来就是 `null`** ——
     * 判据只能是 `code`，不能把 `result:null` 一律当失败（否则没有分红历史的标的每次都误报取数失败）。
     */
    private val emDataJson =
        """{"version":"x","result":{"pages":1,"data":[{"PRETAX_BONUS_RMB":280.2423,"EX_DIVIDEND_DATE":"2026-06-26 00:00:00","ASSIGN_PROGRESS":"实施分配"}],"count":28},"success":true,"message":"ok","code":0}"""

    /** 无分红历史（实测 830799）/ 代码查不到（999999）/ 美股交易所猜错（AAPL.N）都是这一个形态。 */
    private val emEmptyJson =
        """{"version":null,"result":null,"success":false,"message":"返回数据为空","code":9201}"""

    /** 参数错（实测：reportName 拼错）→ 必须判失败，不能当成「没有分红」。 */
    private val emBadParamJson =
        """{"version":null,"result":null,"success":false,"message":"报表配置不存在,RPT_NOT_EXIST_XXX","code":9501}"""

    @Test
    fun `东财-有数据算成功`() {
        assertTrue(DividendSource.eastmoneyOk(emDataJson))
        assertEquals(1, DividendSource.parseCnBonus(emDataJson, "sh600519").size)
    }

    @Test
    fun `东财-返回数据为空算成功且无分红`() {
        // ⚠️ 最容易被"顺手统一"改错的一条：把 result:null 一律当失败
        // → 没有分红历史的标的每次扫描都会弹「取数失败，可能漏检」
        assertTrue(DividendSource.eastmoneyOk(emEmptyJson), "9201/返回数据为空 = 查询成功、确实没数据")
        assertEquals(0, DividendSource.parseCnBonus(emEmptyJson, "bj830799").size)
    }

    @Test
    fun `东财-明确错误码必须算失败`() {
        assertFalse(DividendSource.eastmoneyOk(emBadParamJson), "9501 报表配置错 = 取数失败（P1-3 的核心）")
        // 限流/WAF 之类返回的 `code:0 + result:null`（非 9201）同样不能当"确认无分红"
        assertFalse(DividendSource.eastmoneyOk("""{"result":null,"success":true,"message":"ok","code":0}"""))
        // 非 JSON（拦截页 HTML）与空响应
        assertFalse(DividendSource.eastmoneyOk("<html>403 Forbidden</html>"))
        assertFalse(DividendSource.eastmoneyOk(""))
        // result 里连 data 段都没有 = 结构不完整
        assertFalse(DividendSource.eastmoneyOk("""{"result":{"pages":0},"success":true,"code":0}"""))
    }

    @Test
    fun `东财-data为空数组算成功`() {
        // data: [] 是「查询成功但确实一条都没有」，不是失败（与 result:null 的区别见函数 KDoc）
        assertTrue(
            DividendSource.eastmoneyOk("""{"result":{"pages":0,"data":[],"count":0},"success":true,"code":0}"""),
        )
    }

    @Test
    fun `港股K线-有K线算成功_空K线与错误码算失败`() {
        assertTrue(
            DividendSource.tencentKlineOk(
                """{"code":0,"msg":"","data":{"hk00700":{"qfqday":[["2026-01-02","1","2","3","4","5"]]}}}""",
                "hk00700",
            ),
        )
        // 实测无效代码：code 仍为 0，但 day 是空数组、且没有 qfqday
        assertFalse(
            DividendSource.tencentKlineOk("""{"code":0,"msg":"","data":{"hk99999":{"day":[],"qt":{}}}}""", "hk99999"),
        )
        // 实测参数错
        assertFalse(DividendSource.tencentKlineOk("""{"code":1,"msg":"bad params"}""", "hk00700"))
        assertFalse(DividendSource.tencentKlineOk("nope", "hk00700"))
    }

    @Test
    fun `基金分红页-非分红页算失败`() {
        assertTrue(
            DividendSource.cnFundDividendPageOk(
                "<title>沪深300ETF华泰柏瑞(510300)基金分红送配 _ 基金档案 _ 天天基金网</title>",
            ),
        )
        assertFalse(DividendSource.cnFundDividendPageOk("<html><body>访问过于频繁，请稍后再试</body></html>"))
        assertFalse(DividendSource.cnFundDividendPageOk(""))
    }
}
