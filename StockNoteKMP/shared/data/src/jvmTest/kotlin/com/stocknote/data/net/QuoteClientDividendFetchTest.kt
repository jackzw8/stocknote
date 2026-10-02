package com.stocknote.data.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.ResponseException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **分红拉取的「错误体也算失败」契约测试**（P1-3，老周 2026-10-02）。
 *
 * ## 为什么要它
 * 四个分红源的**错误响应也是 HTTP 200**（实测 2026-10-02）：
 *  - 东财：限流/参数错 → `{"result":null,"success":false,"message":"报表配置不存在,…","code":9501}`
 *  - 腾讯港股：参数错 → `{"code":1,"msg":"bad params"}`
 *  - 天天基金：拦截页（HTML，不含「分红送配」）
 *
 * 此前 [QuoteClient] 只看「请求没抛异常」就当成功 → 错误体解析成空列表，
 * 与「确认没有分红」**完全同形**，用户永远等不到提示（静默漏检）。
 *
 * ⚠️ 与「无数据」的区别是**本用例的重点**：东财「确实没有数据」也是 `result:null`，
 * 但带 `code:9201 + 返回数据为空` —— 那必须判成**成功且无分红**，
 * 否则每一个没有分红历史的标的每次扫描都会误报「取数失败」。
 *
 * ⚠️ 用 Ktor `MockEngine` 构造响应（真接口触发不了限流）；纯本地、不联网。
 */
class QuoteClientDividendFetchTest {

    // ---- 实测响应样本（2026-10-02，HTTP 全部 200）----

    /** 有数据：`code:0` + `success:true` + `result.data=[…]` */
    private val okCnJson =
        """{"version":"x","result":{"pages":1,"data":[{"SECURITY_CODE":"600519",
           "PRETAX_BONUS_RMB":280.2423,"EX_DIVIDEND_DATE":"2026-06-26 00:00:00",
           "EQUITY_RECORD_DATE":"2026-06-25 00:00:00","ASSIGN_PROGRESS":"实施分配",
           "IMPL_PLAN_PROFILE":"10派280.2423元(含税)"}],"count":1},"success":true,"message":"ok","code":0}"""
            .replace("\n", "").replace("  ", "")

    /** **无数据**（无分红历史 830799 / 代码查不到 999999 / 美股交易所猜错 AAPL.N 都是它）。 */
    private val emptyJson =
        """{"version":null,"result":null,"success":false,"message":"返回数据为空","code":9201}"""

    /** **参数错**（reportName 拼错）—— 必须判失败。 */
    private val badParamJson =
        """{"version":null,"result":null,"success":false,"message":"报表配置不存在,RPT_NOT_EXIST_XXX","code":9501}"""

    /** 腾讯参数错。 */
    private val hkBadParams = """{"code":1,"msg":"bad params"}"""

    /** 腾讯无效代码（code 仍为 0，但 K 线是空数组）。 */
    private val hkEmptyKline = """{"code":0,"msg":"","data":{"hk99999":{"day":[],"qt":{}}}}"""

    /** 天天基金被拦截时返回的页面（不含「分红送配」）。 */
    private val blockedHtml = "<html><body>访问过于频繁，请稍后再试</body></html>"

    private val fundPageHtml =
        "<title>沪深300ETF华泰柏瑞(510300)基金分红送配 _ 基金档案 _ 天天基金网</title>" +
            "<table><tr><td>2026年</td><td>2026-01-16</td><td>2026-01-19</td>" +
            "<td>每10份派现金1.2300元</td><td>2026-01-27</td></tr></table>"

    /** 构造一个「无论请求什么都返回同一段 body」的客户端（与 createHttpClient 同校验规则）。 */
    private fun clientReturning(body: String): HttpClient = HttpClient(MockEngine { _ ->
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }) {
        // 与生产 createHttpClient() 一致：只有 403/429/5xx 才抛异常 —— 这正是"错误体不漏"的前提
        HttpResponseValidator {
            validateResponse { response ->
                val code = response.status.value
                if (code == 403 || code == 429 || code >= 500) {
                    throw ResponseException(response, "HTTP $code")
                }
            }
        }
    }

    // ---------------------------------------------------------------- A股（东财）

    @Test
    fun `东财错误体即便HTTP200也必须ok为false`() = runBlocking {
        val client = QuoteClient(clientReturning(badParamJson))
        val r = client.fetchCnDividends("sh600519")
        assertFalse(r.ok, "HTTP 200 的错误体不能被当成「确认无分红」（P1-3 的核心）")
        assertTrue(r.items.isEmpty())
    }

    @Test
    fun `东财返回数据为空是成功且无分红`() = runBlocking {
        val client = QuoteClient(clientReturning(emptyJson))
        val r = client.fetchCnDividends("bj830799")
        assertTrue(r.ok, "9201/返回数据为空 = 查询成功、确实没数据，不能报「取数失败」")
        assertTrue(r.items.isEmpty())
    }

    @Test
    fun `东财有数据时ok为true且解析出条目`() = runBlocking {
        val client = QuoteClient(clientReturning(okCnJson))
        val r = client.fetchCnDividends("sh600519")
        assertTrue(r.ok)
        assertEquals(1, r.items.size)
        assertEquals("2026-06-26", r.items.single().exDate)
    }

    @Test
    fun `东财非JSON响应也必须ok为false`() = runBlocking {
        // 拦截页可能是 HTML（HTTP 200）
        val client = QuoteClient(clientReturning(blockedHtml))
        assertFalse(client.fetchCnDividends("sh600519").ok)
    }

    // ---------------------------------------------------------------- 港股（腾讯）

    @Test
    fun `港股参数错与空K线都不能当确认无分红`() = runBlocking {
        assertFalse(
            QuoteClient(clientReturning(hkBadParams)).fetchHkDividends("hk00700").ok,
            "腾讯 bad params 是取数失败",
        )
        assertFalse(
            QuoteClient(clientReturning(hkEmptyKline)).fetchHkDividends("hk99999").ok,
            "K 线为空 = 拿不到数据，不能当「没有分红」",
        )
    }

    @Test
    fun `港股有K线时ok为true`() = runBlocking {
        val body = """{"code":0,"msg":"","data":{"hk00700":{"qfqday":[
            ["2026-06-26","100","100","100","100","1",{"cqr":"2026-06-26","paixiri":"2026-07-10","FHcontent":"末期息5.3港元;"}]
            ]}}}""".replace("\n", "").replace("  ", "")
        val r = QuoteClient(clientReturning(body)).fetchHkDividends("hk00700")
        assertTrue(r.ok)
        assertEquals(1, r.items.size)
        assertEquals("HKD", r.items.single().currency)
    }

    // ---------------------------------------------------------------- 基金（天天基金 HTML）

    @Test
    fun `基金拦截页必须ok为false`() = runBlocking {
        assertFalse(
            QuoteClient(clientReturning(blockedHtml)).fetchCnFundDividends("sh510300").ok,
            "拿到的不是分红页时不能当「确认无分红」",
        )
    }

    @Test
    fun `基金正常页ok为true且解析出分红`() = runBlocking {
        val r = QuoteClient(clientReturning(fundPageHtml)).fetchCnFundDividends("sh510300")
        assertTrue(r.ok)
        assertEquals(1, r.items.size)
        assertEquals("2026-01-19", r.items.single().exDate)
    }

    // ---------------------------------------------------------------- 美股（东财，按后缀探测）

    @Test
    fun `美股三个后缀全是错误体时必须ok为false`() = runBlocking {
        // ⚠️ 回归点：改前「解析结果为空」会被当成"这个交易所没有该标的"，
        // 三个后缀试完 reached=true → ok(empty) = 「确认无分红」。错误体必须让它保持 FAILED。
        val r = QuoteClient(clientReturning(badParamJson)).fetchUsDividends("usAAPL")
        assertFalse(r.ok, "三个后缀都取数失败时必须报失败，而不是「确认无分红」")
    }

    @Test
    fun `美股一个后缀返回数据为空时仍算确认无分红`() = runBlocking {
        // 实测：交易所猜错（AAPL.N）返回的正是 9201/返回数据为空 → 可当"这个代码没有分红"，
        // 因此不能因为它把整次扫描判成失败。
        val r = QuoteClient(clientReturning(emptyJson)).fetchUsDividends("usAAPL")
        assertTrue(r.ok)
        assertTrue(r.items.isEmpty())
    }

    @Test
    fun `美股逐个后缀探测_第一个有数据即返回`() = runBlocking {
        // 纳斯达克（.O）有数据、纽交所（.N）为空 → 应取到 .O 那条
        val engine = MockEngine { request ->
            val filter = request.url.parameters["filter"].orEmpty()
            val body = if (filter.contains("AAPL.O")) {
                """{"result":{"pages":1,"data":[{"ASSIGN_TYPE":"Cash","PLAN_EXPLAIN":"每1股派0.27美元股息",
                   "EQUITY_RECORD_DATE":"2026-08-10 00:00:00","EX_DIVIDEND_DATE":"2026-08-10 00:00:00"}],"count":1},
                   "success":true,"message":"ok","code":0}""".replace("\n", "").replace("  ", "")
            } else {
                emptyJson
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val r = QuoteClient(HttpClient(engine)).fetchUsDividends("usAAPL")
        assertTrue(r.ok)
        assertEquals(1, r.items.size)
        assertEquals(0.27, r.items.single().cashPerShare, 1e-9)
    }
}
