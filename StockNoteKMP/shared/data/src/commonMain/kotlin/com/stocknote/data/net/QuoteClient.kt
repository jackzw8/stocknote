package com.stocknote.data.net

import com.stocknote.core.model.Quote
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import com.stocknote.core.model.Market
import com.stocknote.data.platform.nowEpochMs
import com.stocknote.data.platform.todayIso
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * 行情客户端（腾讯行情，UTF-8 JSON 接口）。
 *
 * 为什么选这个接口而不是 `qt.gtimg.cn/q=xxx`：
 * 后者返回 **GBK** 编码的纯文本，而 GBK 解码在 Android / iOS 上要各写一套（iOS 需要 CFString 编码转换）。
 * 本接口是标准 JSON（UTF-8），两端一套解析代码即可，直接干掉一个平台隔离点。
 *
 * 解析刻意写成「防御式」：字段缺失、层级变化都不抛异常，只返回 null。
 * 理由：行情是外部依赖，绝不能让行情接口改版导致整个 App 崩溃——上层会降级到手动价。
 */
class QuoteClient(
    private val http: HttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun fetch(symbol: String, dayCount: Int = 30): Quote? {
        // 场外基金（`of` 前缀）：腾讯 K 线接口根本没有它（拿不到会返回残数据 / 空），
        // 必须走天天基金**单位净值**。放在最前面分流，后续 fetchMany / refreshQuote /
        // 标的详情全都自动跟着对（老周 2026-09-20）。
        if (isFundSymbol(symbol)) return fetchFundQuote(symbol)
        val raw = fetchRaw(symbol, dayCount) ?: return null
        // parseQuote 刻意保持纯函数（可单测、不含时钟）；抓取时刻在这里打戳
        return parseQuote(raw.first, raw.second)?.copy(updatedAtEpochMs = nowEpochMs())
    }

    /**
     * 取**港股每手股数**（老周 2026-09-28）。
     *
     * 来源：`https://qt.gtimg.cn/q=hk00700` 返回的 `~` 分隔串，**第 61 个字段**即每手股数
     * （实测：腾讯控股 100 / 小米集团 200 / 中国移动 500 / 建设银行 1000 —— 港股每手**不固定**，
     * 所以必须取真实值，不能像 A股那样固定按 100 卡）。
     *
     * ⚠️ 只对**港股**有意义：A股/ETF 恒 100、美股 1 股、场外基金按份额
     * → 非港股直接返回 null，**不浪费一次网络请求**。
     */
    suspend fun fetchLotSize(symbol: String, market: Market): Long? {
        if (market != Market.HK) return null
        return runCatching {
            val body = http.get("$FX_ENDPOINT$symbol").bodyAsText()
            // 形如：`v_hk00700="100~腾讯控股~00700~...";` → 取引号内内容，按 `~` 切，第 61 项即每手
            val payload = body.substringAfter("=\"", "").substringBeforeLast("\"")
            payload.split("~").getOrNull(60)?.trim()?.toLongOrNull()
        }.getOrNull()?.takeIf { it > 0 }
    }

    /** 场外基金符号判定：本 App 用 `of` 前缀（如 `of000001`），与 A股 000001 区分开。 */
    internal fun isFundSymbol(symbol: String): Boolean =
        symbol.trim().startsWith("of", ignoreCase = true)

    /**
     * 场外基金行情 = **最新单位净值**（天天基金）。
     *
     * 基金一天只有一个净值（当日 15:00 后公布），所以「现价 / 昨收」就是最近两个净值，
     * 这样复用现有的 [Quote] 通道即可让基金也有当日涨跌与浮盈。
     */
    suspend fun fetchFundQuote(symbol: String): Quote? {
        val navs = fetchFundNav(symbol, days = 2)
        val latest = navs.firstOrNull() ?: return null
        return Quote(
            symbol = symbol.trim(),
            price = latest.nav,
            prevClose = navs.getOrNull(1)?.nav,
            source = FUND_SOURCE,
            updatedAtEpochMs = nowEpochMs(),
            // 净值日期（FSRQ）就是这条"行情"的交易日：当日净值 15:00 后才公布，
            // 白天拿到的是昨天的净值 —— 报价日期守门（P3-36）会让它当日盈亏计 0，与日历一致
            quoteDate = latest.date,
        )
    }

    /**
     * 并行拉取，单个失败不影响整体（返回的 Map 里只会缺少失败项）。
     * ⚠️ 2026-09-18 审查修复（老周拍板）：此前逐个串行，每个标的都要下载一次 30 天 K 线，
     * 标的一多，快照刷新 / 手动刷新就明显变慢。
     */
    suspend fun fetchMany(symbols: List<String>): Map<String, Quote> =
        kotlinx.coroutines.coroutineScope {
            symbols.map { s -> async { s to fetch(s) } }
                .awaitAll()
                .mapNotNull { (s, q) -> q?.let { s to it } }
                .toMap()
        }

    /**
     * 美股符号的接口候选。腾讯 K 线接口要求美股带交易所后缀：
     * 实测 `usAAPL` 只回 2 行（2011 年残数据），`usAAPL.OQ` 才返回完整 K 线。
     * 历史建档的 symbol 可能是无后缀形态（无法就地迁移），故这里做候选探测：
     * `.OQ` 纳斯达克 / `.N` 纽交所 / `.A` 美交所，最后兜底原样。
     */
    private fun apiSymbolCandidates(symbol: String): List<String> {
        val s = symbol.trim()
        if (!s.startsWith("us", ignoreCase = true) || s.contains('.')) return listOf(s)
        return listOf("$s.OQ", "$s.N", "$s.A", s)
    }

    /**
     * 取回 (实际可用符号, 响应文本)：逐个候选请求，取第一个能解析出足够 K 线的候选。
     * 全部失败返回 null。A股/港股只有一个候选，行为与原实现一致（一次请求）。
     *
     * ⚠️ **只服务 [fetch] 的实时快照** —— 它还需要从同一份响应里读 `qt` 字段（实时价/涨跌幅），
     * 所以必须拿到**原始文本**，不能换成归一化后的 [Candle]。
     * 历史 K 线一律走 [fetchCandles]（带东财回退）：2026-10-01 腾讯挂的是 **K 线接口**，
     * 而 `qt.gtimg.cn` 的实时行情一直正常，故这条链路不需要备源。
     */
    private suspend fun fetchRaw(symbol: String, days: Int): Pair<String, String>? {
        val candidates = apiSymbolCandidates(symbol)
        for (cand in candidates) {
            val text = runCatching {
                http.get(endpoint) { parameter("param", "$cand,day,,,$days,qfq") }.bodyAsText()
            }.onFailure { err ->
                com.stocknote.data.log.SnLog.e(
                    "NET",
                    "行情快照请求失败 symbol=$cand days=$days endpoint=$endpoint",
                    err,
                )
            }.getOrNull() ?: continue
            val got = parseCloses(cand, text).size
            if (got >= MIN_CANDLES) return cand to text
            com.stocknote.data.log.SnLog.w(
                "NET",
                "行情快照K线不足 symbol=$cand 解析到 $got 条（要求 >= $MIN_CANDLES）",
            )
        }
        com.stocknote.data.log.SnLog.w("NET", "行情快照全部候选失败 symbol=$symbol 候选=$candidates")
        return null
    }

    /**
     * 历史日 K：**腾讯为主，失败自动回退东财**（老周 2026-10-01 要求）。
     *
     * ## 为什么必须加回退
     * 此前行情**只有腾讯一个源**。2026-10-01 腾讯把 `web.ifzq.gtimg.cn` 的 `fqkline`
     * 接口**整站下线**（一律 HTTP 501），用户侧的资产曲线当场变成「取不到任何历史收盘价」，
     * 而且完全看不出原因（详见当天的运行日志实战）。多一个源，主源抽风就不会让整条链路瘫掉。
     *
     * ## 归一化
     * 两个源各自解析后**归一成同一份 [Candle] 列表**（升序），调用方无需知道数据来自谁 ——
     * 这也是把原来的 `fetchRaw(): Pair<符号, 原始文本>` 换掉的原因：
     * 两家 JSON 结构完全不同（腾讯 `data.<sym>.qfqday`／东财 `data.klines` 字符串数组），
     * 交出原始文本就等于把"选源"这件事泄漏给了调用方。
     */
    private suspend fun fetchCandles(symbol: String, days: Int): List<Candle> {
        val tencent = fetchCandlesFromTencent(symbol, days)
        if (tencent.size >= MIN_CANDLES) return tencent
        if (tencent.isNotEmpty()) {
            com.stocknote.data.log.SnLog.w(
                "NET",
                "腾讯源只取到 ${tencent.size} 条（要求 >= $MIN_CANDLES）symbol=$symbol，改用东财",
            )
        }
        com.stocknote.data.log.SnLog.w("NET", "回退东财源 symbol=$symbol days=$days")
        return fetchCandlesFromEastmoney(symbol, days)
    }

    /**
     * 主源：腾讯 K 线。逐个候选符号请求，取第一个能解析出足够 K 线的
     * （美股需交易所后缀，见 [apiSymbolCandidates]）；全失败返回空列表，交给备源。
     */
    private suspend fun fetchCandlesFromTencent(symbol: String, days: Int): List<Candle> {
        val candidates = apiSymbolCandidates(symbol)
        for (cand in candidates) {
            val text = runCatching {
                http.get(endpoint) { parameter("param", "$cand,day,,,$days,qfq") }.bodyAsText()
            }
                // ⚠️ 原来这里是 `getOrNull() ?: continue` —— 网络失败被**完全吞掉**：
                // 用户只看到「行情接口未取到任何历史收盘价，请检查网络后重试」，
                // 却分不清是「网络不通」「接口改版」还是「这只标的没有 K 线」（老周 2026-10-01 反馈）。
                // 现在如实记进运行日志，可在「设置 → 数据管理 → 导出运行日志」里看到。
                .onFailure { err ->
                    com.stocknote.data.log.SnLog.e(
                        "NET",
                        "腾讯K线请求失败 symbol=$cand days=$days endpoint=$endpoint",
                        err,
                    )
                }
                .getOrNull() ?: continue
            val candles = parseCandles(cand, text)
            if (candles.size >= MIN_CANDLES) return candles
            com.stocknote.data.log.SnLog.w(
                "NET",
                "腾讯K线数据不足 symbol=$cand 解析到 ${candles.size} 条" +
                    "（要求 >= $MIN_CANDLES），响应 ${text.length} 字节",
            )
        }
        com.stocknote.data.log.SnLog.w("NET", "腾讯K线全部候选失败 symbol=$symbol 候选=$candidates")
        return emptyList()
    }

    /**
     * 备源：东财日 K（`push2his`，实测 A股/港股/指数均可用）。
     *
     * ⚠️ **只覆盖 A股 / 港股**（含指数）。美股在东财要按 `105./106./107.` 前缀探测交易所，
     * 这里没做 —— `usXXX` 会直接返回空列表（`eastmoneyKlineSecid` 返回 null），
     * 行为退化成"只有腾讯时"的样子，不会比现状更差。
     * 场外基金走的是另一个接口（[fetchFundNav]），不经这里。
     */
    private suspend fun fetchCandlesFromEastmoney(symbol: String, days: Int): List<Candle> {
        val secid = eastmoneyKlineSecid(symbol)
        if (secid == null) {
            com.stocknote.data.log.SnLog.w("NET", "东财无对应 secid，放弃回退 symbol=$symbol")
            return emptyList()
        }
        return runCatching {
            val text = http.get(EM_KLINE_ENDPOINT) {
                parameter("secid", secid)
                parameter("fields1", "f1,f2,f3,f4,f5,f6")
                parameter("fields2", "f51,f52,f53,f54,f55,f56,f57,f58")
                parameter("klt", "101")        // 101 = 日 K
                parameter("fqt", "1")          // 1 = 前复权（与腾讯的 qfq 口径一致）
                parameter("end", "20500101")   // 取到未来，确保拿满最近 days 根
                parameter("lmt", days.toString())
            }.bodyAsText()
            val candles = parseEastmoneyCandles(text)
            com.stocknote.data.log.SnLog.i(
                "NET",
                "东财K线成功 symbol=$symbol secid=$secid 取到 ${candles.size} 条",
            )
            candles
        }.onFailure { err ->
            com.stocknote.data.log.SnLog.e("NET", "东财K线请求失败 symbol=$symbol secid=$secid", err)
        }.getOrDefault(emptyList())
    }

    /**
     * 标的实时搜索（REQ-TOOL-01 / REQ-ACC-01）。
     *
     * 选型（技术说明书 8.2 / 风险 R4）：东方财富 suggest 接口 —— **UTF-8 JSON**，
     * 和行情接口一样避开 GBK，两端共用一套解析。
     * 失败返回空列表：搜索是外部依赖，绝不能因为它把表单卡死；本地搜索仍然可用。
     */
    suspend fun searchOnline(keyword: String, count: Int = 10): List<SymbolHit> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        return runCatching {
            val text: String = http.get(SEARCH_ENDPOINT) {
                parameter("input", kw)
                parameter("type", "14")
                parameter("token", SEARCH_TOKEN)
                parameter("count", count)
            }.bodyAsText()
            parseSearch(text)
        }.getOrDefault(emptyList())
    }

    /**
     * 取外币兑人民币汇率（1 单位外币 = ? CNY）。
     *
     * ⚠️ 2026-09-16 修复：此前用 `qt.gtimg.cn/q=HKDCNY` —— 该代码**无效**，
     * 接口返回 `v_pv_none_match="1"`，宽松正则会从中抠出 "1" 并当成汇率写入（真机实测 1 HKD = ¥1.00）。
     * 现改用**外汇专用代码** `whHKDCNY` / `whUSDCNY`，格式：
     *   `v_whHKDCNY="310~港元人民币~HKDCNY~0.8549~0~20260916141013~..."`
     * 取「~」分割后的**第 4 个字段**（index 3）= 当前汇率；解析不出或数值不合理返回 null
     *（调用方保留旧值，绝不写入可疑数据）。
     */
    suspend fun fetchFxRate(currency: String): Double? {
        val code = when (currency.uppercase()) {
            "USD" -> "whUSDCNY" to 6.0
            "HKD" -> "whHKDCNY" to 0.8
            else -> return null
        }
        return runCatching {
            val text: String = http.get(FX_ENDPOINT) {
                parameter("q", code.first)
            }.bodyAsText()
            parseFxRate(text, code.first, code.second)
        }.getOrNull()
    }

    /**
     * 取**指定日期**的汇率（原币 → CNY），用于交易录入时回填「成交日汇率」
     * （REQ-ACC-15 路径 1，老周 2026-09-19）。
     *
     * 数据源与行情 K 线**同源**（腾讯外汇日 K：whHKDCNY / whUSDCNY），因此不需要新增接口。
     * 口径：取该日**收盘**汇率；当日无数据（周末/休市）→ 回退到之前最近的一个交易日。
     *
     * @return null = 币种不支持 / 拉不到 → 调用方留空由用户手填，不阻塞保存
     */
    suspend fun fetchFxRateOn(currency: String, dateIso: String, days: Int = FX_ON_DEFAULT_DAYS): Double? {
        val (symbol, sanity) = when (currency.uppercase()) {
            "USD" -> "whUSDCNY" to 6.0
            "HKD" -> "whHKDCNY" to 0.8
            else -> return null
        }
        // ⚠️ **窗口分档回退**（老周 2026-09-29 实测腾讯外汇日 K）：
        //  - 400 根只覆盖约 18 个月 → 更早的交易会取不到（表单留空，用户只能手填）；
        //  - 2000 根实测能覆盖到 2018-07（约 8 年）；
        //  - 但 **不能一味放大**：3000 根会被接口拒绝、**整批返回空**（比 400 还差）。
        // 所以先按小窗口拉（快、够用），没命中再按大窗口补拉一次。
        for (window in fxOnWindows(days)) {
            val candles = runCatching { fetchClosesWithDates(symbol, window) }.getOrNull() ?: continue
            // 命中：当日收盘，或之前最近一个交易日
            val hit = candles.lastOrNull { it.date == dateIso }
                ?: candles.lastOrNull { it.date < dateIso }
            val rate = hit?.close ?: continue
            // ±40% 合理性校验（与 parseFxRate 同口径），挡住残数据
            if (rate > sanity * 0.6 && rate < sanity * 1.4) return rate
        }
        return null
    }

    // ---------------------------------------------------------------- 基金分红 / 净值（老周 2026-09-20）

    /**
     * 分红拉取结果（老周 2026-09-30）。
     *
     * [ok] 用于区分「**取数失败**（网络 / 接口异常 / 代码无法查询）」与「**确认没有分红**」——
     * 此前两者都返回空列表，界面上完全一样，用户看到的就是"怎么没反应"（老周真机反馈）。
     */
    data class DividendFetch(val ok: Boolean, val items: List<DividendSource.Dividend>) {
        companion object {
            /** 取数失败：网络不可达 / 接口异常 / 代码格式无法查询 → 结果**不可当"没有分红"**用。 */
            val FAILED = DividendFetch(false, emptyList())

            /** 取数成功；[items] 为空 = 确认该标的没有分红。 */
            fun ok(items: List<DividendSource.Dividend>) = DividendFetch(true, items)
        }
    }

    /**
     * 拉**基金**（含 ETF / 场外基金）分红送配（天天基金 F10 页，**税前**，老周 2026-09-20）。
     *
     * ⚠️ 不能复用 [fetchCnDividends]：东财 `RPT_SHAREBONUS_DET` 是股票报表，
     * 对 ETF 代码实测返回「返回数据为空」→ ETF 分红此前永远检不出来。
     *
     * @param symbol App 侧代码（sh510300 / of000001）→ 天天基金只认 6 位数字
     */
    suspend fun fetchCnFundDividends(symbol: String): DividendFetch {
        val code = symbol.filter { it.isDigit() }
        if (code.length != 6) return DividendFetch.FAILED
        return runCatching {
            val text: String = http.get(CN_FUND_DIVIDEND_ENDPOINT + code + ".html") {
                // 天天基金会对无 Referer 的直连做拦截（实测），必须带上
                header("Referer", "https://fund.eastmoney.com/")
            }.bodyAsText()
            // ⚠️ P1-3（2026-10-02）：先判"拿到的到底是不是那个分红页"。
            // 拦截页/异常页照样是 HTTP 200，此前会被解析成空列表 = "确认无分红"（静默漏检）。
            if (!DividendSource.cnFundDividendPageOk(text)) DividendFetch.FAILED
            else DividendFetch.ok(DividendSource.parseCnFundDividend(text, symbol))
        }.getOrElse { DividendFetch.FAILED }
    }

    /**
     * 拉基金**单位净值**（天天基金历史净值接口，老周 2026-09-20）。
     *
     * 返回按日期**降序**（接口原序）；调用方如需升序自行反转。
     * 场外基金没有交易所行情，只能取每日净值 —— 这是「场外基金能算浮盈」的前提。
     *
     * @param days 取最近多少个交易日（接口按页取，1 页 ≈ 20 条）
     */
    suspend fun fetchFundNav(
        symbol: String,
        days: Int = 30,
    ): List<FundNav> {
        val code = symbol.filter { it.isDigit() }
        if (code.length != 6) return emptyList()
        val size = (days.coerceAtLeast(1) + 19) / 20 * 20
        return runCatching {
            val text: String = http.get(FUND_NAV_ENDPOINT) {
                parameter("fundCode", code)
                parameter("pageIndex", "1")
                parameter("pageSize", size.toString())
                header("Referer", "https://fundf10.eastmoney.com/")
            }.bodyAsText()
            parseFundNav(text)
        }.getOrDefault(emptyList())
    }

    /** 一条基金净值（[date] 升序由调用方保证；[nav] = 单位净值） */
    data class FundNav(val date: String, val nav: Double, val growthPct: Double?)

    /**
     * 解析基金历史净值 JSON（**纯函数**，便于单测）—— 已归入 companion（与 parseSearch 同级）。
     * 形态：`{"Data":{"LSJZList":[{"FSRQ":"2026-09-18","DWJZ":"1.3330","JZZZL":"2.70"},...]}}`
     */
    internal fun parseFundNav(text: String): List<FundNav> = Companion.parseFundNav(text)

    // ---------------------------------------------------------------- 分红送配（股票，老周 2026-09-16）

    /**
     * 拉 A股**股票**分红送配（东财数据中心，**税前**）。
     *
     * ⚠️ 只适用于股票 —— ETF / 场外基金请走 [fetchCnFundDividends]。
     * @param symbol App 侧代码（sh600519）→ 东财只认 6 位数字
     */
    suspend fun fetchCnDividends(symbol: String): DividendFetch {
        val code = symbol.filter { it.isDigit() }
        if (code.length != 6) return DividendFetch.FAILED
        return runCatching {
            val text: String = http.get(CN_DIVIDEND_ENDPOINT) {
                parameter("reportName", "RPT_SHAREBONUS_DET")
                parameter("columns", "ALL")
                parameter("filter", "(SECURITY_CODE=\"$code\")")
                parameter("pageSize", "50")
                parameter("sortColumns", "EX_DIVIDEND_DATE")
                parameter("sortTypes", "-1")
            }.bodyAsText()
            // ⚠️ P1-3（2026-10-02）：东财「限流 / 参数错」也是 HTTP 200，错误体解析出来就是空列表
            // → 与"确认无分红"同形。判据是响应里的 `code`（9201 = 确实没数据；其它非 0 = 失败），
            // 详见 [DividendSource.eastmoneyOk]。
            if (!DividendSource.eastmoneyOk(text)) DividendFetch.FAILED
            else DividendFetch.ok(DividendSource.parseCnBonus(text, symbol))
        }.getOrElse { DividendFetch.FAILED }
    }

    /**
     * 拉港股派息（腾讯港股 K 线自带字段，**税后**）。
     *
     * ⚠️ **根数 = 分红能查到的历史长度**（老周 2026-09-24）：港股分红不是独立接口，
     * 而是附在日K里的 `cqr`/`paixiri` 字段，所以取多少根 K 线就只能查到多久的分红。
     * 实测（2026-09-24，hk00700）：800 根 → 最早 2023-06-29（仅 3.2 年，此前老周反馈
     * 「港股分红只查到近 3 年」就是这个）；改 **1200 根 ≈ 4.8 年**。
     * 腾讯接口的硬边界：900/1000/1200 正常，**1600 与 2000 会被静默砍成 640 根**，
     * 3000 直接 `{"code":1,"msg":"bad params"}` → 所以 1200 是「不改成分段翻页」方案下的安全上限。
     *
     * @param symbol App 侧代码（hk00700）—— 腾讯直接认这个格式
     */
    suspend fun fetchHkDividends(symbol: String): DividendFetch {
        if (!symbol.startsWith("hk")) return DividendFetch.FAILED
        return runCatching {
            val text: String = http.get(HK_DIVIDEND_ENDPOINT) {
                parameter("param", "$symbol,day,,,1200,qfq")
            }.bodyAsText()
            // ⚠️ P1-3（2026-10-02）：腾讯参数错是 `{"code":1,"msg":"bad params"}`（HTTP 200）、
            // 代码无效则 `day` 为空数组 —— 两者都不能当成"确认无分红"。
            if (!DividendSource.tencentKlineOk(text, symbol)) DividendFetch.FAILED
            else DividendFetch.ok(DividendSource.parseHkBonus(text, symbol))
        }.getOrElse { DividendFetch.FAILED }
    }

    /**
     * 拉**美股**分红派息（东财 F10 `RPT_USF10_INFO_DIVIDEND`，**税前票面**，老周 2026-09-30）。
     *
     * ⚠️ 美股**不能走港股那条路**：实测腾讯 `usfqkline` 的 K 线行只有 6 项、**没有分红字段**
     * （港股 `hkfqkline` 才有 `cqr`/`FHcontent`）→ 只能改用东财 F10 的美元股息报表。
     *
     * ⚠️ 东财的 `SECUCODE` 形如 `AAPL.O`（后缀 O=纳斯达克 / N=纽交所 / A=美交所），
     * 而本 App 的 symbol 多为无后缀的 `usAAPL` → 按后缀**依次探测**，取第一个有数据的。
     *
     * @param symbol App 侧代码（`usAAPL` / `usAAPL.OQ`）
     */
    suspend fun fetchUsDividends(symbol: String): DividendFetch {
        val (code, suffixes) = usSecuCode(symbol) ?: return DividendFetch.FAILED
        var reached = false
        for (suffix in suffixes) {
            val fetched = runCatching {
                val text: String = http.get(US_F10_ENDPOINT) {
                    parameter("reportName", "RPT_USF10_INFO_DIVIDEND")
                    parameter("columns", US_DIVIDEND_COLUMNS)
                    parameter("filter", "(SECUCODE=\"$code.$suffix\")")
                    parameter("pageNumber", "1")
                    parameter("pageSize", "200")
                    parameter("sortTypes", "-1")
                    parameter("sortColumns", "EX_DIVIDEND_DATE")
                    parameter("source", "SECURITIES")
                    parameter("client", "PC")
                }.bodyAsText()
                // ⚠️ P1-3（2026-10-02）：**必须把「信封合法性」和「解析结果」分开带出来**。
                // 此前只取解析结果、失败即 `continue`，于是「限流/参数错的错误体」会被当成
                // "这个交易所没有该标的"，三个后缀都试完 → reached=true → 误判成「确认无分红」。
                // 实测：交易所猜错（AAPL.N）东财返回 `code:9201 + 返回数据为空`（= 真的没这个代码的数据），
                // 只有 `code` 非 0 且非 9201 的错误体才算"没请求成功"。
                DividendSource.eastmoneyOk(text) to DividendSource.parseUsBonus(text, symbol)
            }.getOrNull() ?: continue
            if (!fetched.first) continue
            reached = true
            if (fetched.second.isNotEmpty()) return DividendFetch.ok(fetched.second)
        }
        // 请求过但都没数据 = 确认无分红；一次都没请求成功 = 失败（供上层区分展示）
        return if (reached) DividendFetch.ok(emptyList()) else DividendFetch.FAILED
    }

    // ---------------------------------------------------------------- 个股资讯（腾讯自选股，老周 2026-09-24）

    /**
     * 拉某标的的**个股资讯 / 公告 / 研报**（腾讯自选股接口，三类同源）。
     *
     * 老周 2026-09-24 实测通的接口；比此前"按市场分源"的东财方案省事（A股/港股同一入口）。
     * 参数与坑见 [NewsSource] 的类注释（重点是 **`n` 与 `page` 必须成对**、每页条数参数名是 `n`）。
     *
     * @param symbol App 侧代码（`sh600519` / `sz000001` / `hk00700`）—— 与接口格式一致，无需转换
     * @param kind 资讯分类（0 公告 / 1 研报 / 2 资讯）
     * @param page 页码，从 1 开始
     * @param n    每页条数（默认 20）
     * @return 解析后的一页；网络失败或接口报错时返回**空页**（页面如实显示"暂无"）
     */
    suspend fun fetchStockNews(
        symbol: String,
        kind: NewsSource.Kind,
        page: Int = 1,
        n: Int = 20,
    ): NewsSource.Page = runCatching {
        val text: String = http.get(NEWS_ENDPOINT) {
            // 接口要求带 UA / Referer，缺了可能被拒
            header("User-Agent", "Mozilla/5.0")
            header("Referer", "https://stockapp.finance.qq.com/")
            parameter("type", kind.code)
            parameter("symbol", symbol)
            parameter("page", page)
            parameter("n", n)
        }.bodyAsText()
        NewsSource.parse(text)
    }.getOrDefault(NewsSource.Page(emptyList(), 0, 0))

    /**
     * 拉**财联社 7×24 快讯**（老周 2026-10-04，依据《财联社 7x24 快讯接口技术文档 v1.0》）。
     *
     * 签名与字段说明见 [FlashNewsSource]（**sign = MD5(SHA1(参数字典序拼接))**）。
     * ⚠️ 必须带**浏览器 UA + `Referer: https://www.cls.cn/telegraph`**，否则接口 404 / 报签名错误。
     * ⚠️ `rn` 由 [FlashNewsSource.safeRn] **夹到 1..50** —— 实测 51 起接口返回空列表且 `errno=0`
     *（静默失败，页面上看就是"突然没有快讯"）。
     *
     * @param rn 每批条数（接口参数名是 `rn`）；"加载更多"就是把它加大再重取（不是游标翻页，
     *           原因见 [FlashNewsSource] 类注释）
     * @return 一页快讯；**网络失败 / 接口报错返回 null**（调用方据此显示「加载失败 + 重试」，
     *         与"接口正常但没数据"的空页区分开 —— 这是 P1-35「别静默失败」的同一条原则）
     */
    suspend fun fetchFlashNews(rn: Int = FlashNewsSource.DEFAULT_RN): FlashNewsSource.Page? {
        val params = FlashNewsSource.params(rn)
        return runCatching {
            val text: String = http.get(FLASH_NEWS_ENDPOINT) {
                header("User-Agent", FlashNewsSource.USER_AGENT)
                header("Referer", FlashNewsSource.REFERER)
                header("Accept", "application/json, text/plain, */*")
                params.forEach { (k, v) -> parameter(k, v) }
                parameter("sign", FlashNewsSource.sign(params))
            }.bodyAsText()
            FlashNewsSource.parse(text)
        }.getOrElse { null }
    }

    // ---------------------------------------------------------------- F10 财务数据（老周 2026-09-24）

    /**
     * 拉某标的的**报告期财务指标**（东方财富 F10，依据《东方财富F10接口技术文档》）。
     *
     * 按市场走不同数据集（文档 §3.2，两市场**字段名不同**，归一化在 [FinanceSource] 里做）：
     *  - A股：老网关 + `type=RPT_F10_FINANCE_MAINFINADATA` + `sty=APP_F10_MAINFINADATA`
     *  - 港股：v1 网关 + `reportName=RPT_HKF10_FN_MAININDICATOR`
     *
     * @param symbol App 侧代码（sh600519 / sz000001 / hk00700）
     * @param periods 取最近多少期（默认 6）
     * @return 报告期列表（倒序）；不支持的市场（美股/场外基金）或网络失败时返回**空列表**
     */
    suspend fun fetchFinance(symbol: String, periods: Int = 6): List<FinanceSource.Report> {
        val mapped = FinanceSource.secuCode(symbol) ?: return emptyList()
        val code = mapped.first
        val kind = mapped.second
        return runCatching {
            val text: String = if (kind == FinanceSource.Kind.A) {
                http.get(A_F10_ENDPOINT) {
                    header("User-Agent", "Mozilla/5.0")
                    parameter("type", "RPT_F10_FINANCE_MAINFINADATA")
                    parameter("sty", "APP_F10_MAINFINADATA")
                    parameter("filter", "(SECUCODE=\"$code\")")
                    parameter("p", 1)
                    parameter("ps", periods)
                    parameter("sr", -1)
                    parameter("st", "REPORT_DATE")
                    parameter("source", "HSF10")
                    parameter("client", "PC")
                }.bodyAsText()
            } else {
                http.get(HK_F10_ENDPOINT) {
                    header("User-Agent", "Mozilla/5.0")
                    parameter("reportName", "RPT_HKF10_FN_MAININDICATOR")
                    parameter("columns", "ALL")
                    parameter("filter", "(SECUCODE=\"$code\")")
                    parameter("pageNumber", 1)
                    parameter("pageSize", periods)
                    parameter("sortTypes", -1)
                    parameter("sortColumns", "REPORT_DATE")
                    parameter("source", "F10")
                    parameter("client", "PC")
                }.bodyAsText()
            }
            FinanceSource.parse(text, kind)
        }.getOrDefault(emptyList())
    }

    /**
     * 取**当前估值**：PE(TTM) / PB / 股息率（老周 2026-09-24）。
     *
     * 为什么单独取：**A股的 F10 数据集不含这三项**（港股的 F10 里有），老周要求**从东财行情接口补**。
     *
     * 接口：`push2.eastmoney.com/api/qt/stock/get?secid=1.600519&fields=…`
     * ⚠️ 返回值**统一放大 100 倍**（f164=1899 表示 PE 18.99）→ 解析时除 100。
     * 实测（2026-09-24 贵州茅台）：f162 PE(动) 17.37 · **f164 PE(TTM) 18.99** · **f167 PB 6.15** ·
     * **f171 股息率 2.00%** · f173 ROE 16.75%（与 F10 的 ROEJQ 一致 → 佐证字段含义）。
     *
     * 只覆盖 **A股**（沪 `1.` / 深·北 `0.`）；其它市场返回空表（港股 F10 里已带这些指标）。
     */
    suspend fun fetchValuation(symbol: String): Map<String, Double> {
        val secid = eastmoneySecid(symbol) ?: return emptyMap()
        // ① 主源：腾讯行情（老周 2026-09-24 定的顺序：**先腾讯**——qt.gtimg.cn 是本项目一直在用的域名、
        //    实测不会像东财 push2 那样按 IP 限流，且三项数值与东财一致）
        val byQt = runCatching {
            val text: String = http.get(TENCENT_QUOTE_ENDPOINT + symbol) {
                header("User-Agent", "Mozilla/5.0")
                header("Referer", "https://gu.qq.com/")
            }.bodyAsText()
            parseTencentValuation(text)
        }.getOrDefault(emptyMap())
        if (byQt.isNotEmpty()) {
            println("[SN_FIN] valuation(qt) " + symbol + " = " + byQt)
            return byQt
        }
        // ② 兜底：东财 push2 行情
        val byEm = runCatching {
            val text: String = http.get(VALUATION_ENDPOINT) {
                header("User-Agent", "Mozilla/5.0")
                header("Referer", "https://quote.eastmoney.com/")
                parameter("secid", secid)
                parameter("fields", "f57,f58,f164,f167,f171")
            }.bodyAsText()
            parseValuation(text)
        }.getOrDefault(emptyMap())
        println("[SN_FIN] valuation(em) " + symbol + " = " + byEm)
        return byEm
    }

    // ---------------------------------------------------------------- 个股风险扫雷（老周 2026-09-24）

    /**
     * 个股风险扫雷取数：抓取并组装 [com.stocknote.core.calc.RiskScanner.Input]。
     *
     * 数据源（移植自 `项目文档/saolei.py`）：
     *  ① **F10「操盘必读」**（核心；一次请求拿 20 个模块）→ 名称/公告标题/最新财务指标/估值/股东户数/
     *     大宗交易/龙虎榜/融资余额；
     *  ② **资产负债表**（商誉 / 审计意见 / 货币资金 / 短期借款 / 归母净资产 / 应收同比）；
     *  ③ **数据中心**（股权质押 / 限售解禁 / 高管增减持）；
     *  ④ **腾讯行情**（取最新价；⚠️ 不用东财 push2，它与扫雷用的 F10 同域易被一起限流）。
     *
     * ⚠️ **巨潮资讯（诉讼 / 担保）本期未接入** —— 它需要 `Accept-Enckey` 动态鉴权（AES-128-CBC）
     * 且返回**全市场**记录（体积大、每次全量拉取不经济）→ 这两项在界面显示「无记录」。
     * ⚠️ **除 ① 外的来源失败即跳过**（对应指标留空 → 界面显示「—」），**不阻塞整体扫描**。
     *
     * @return 扫描输入；**非 A 股**（美股/港股/场外基金）或①失败时返回 null，由 UI 如实提示。
     */
    suspend fun fetchRiskScan(symbol: String): com.stocknote.core.calc.RiskScanner.Input? {
        val sec = riskSecOf(symbol) ?: return null
        val bare = sec.substring(2)
        val raw = RiskScanSource.Raw(symbol)
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

        // ⚠️ M16 修复（2026-09-28）：扫雷要串行发 6~7 个请求（其中多个是百条级响应），
        // 全局超时只有 8 秒、且**没有任何取消点** —— 弱网下 30 秒以上，用户退出了协程还在跑。
        // `ensureActive()` 让协程在**每个请求之间**响应取消（用户离开页面即尽快停止）。
        kotlinx.coroutines.currentCoroutineContext().ensureActive()

        // ① F10 操盘必读（核心，失败即整体中止）
        val f10Text = runCatching {
            http.get("$F10_WEB_BASE/OperationsRequired/PageAjax") {
                header("User-Agent", ua)
                parameter("code", sec)
            }.bodyAsText()
        }.getOrNull() ?: return null
        RiskScanSource.fillFromF10(raw, f10Text, symbol)
        val totalShare = RiskScanSource.totalShareOf(f10Text)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()

        // ② 资产负债表（两段式：先报告期 → 再数据；companyType=4 通用，失败跳过）
        runCatching {
            val datesText = http.get("$F10_WEB_BASE/NewFinanceAnalysis/zcfzbDateAjaxNew") {
                header("User-Agent", ua)
                parameter("companyType", "4")
                parameter("reportDateType", "0")
                parameter("code", sec)
            }.bodyAsText()
            val dates = Regex("\"REPORT_DATE\":\"([0-9-]{10})").findAll(datesText)
                .map { it.groupValues[1] }.take(4).joinToString(",")
            if (dates.isNotEmpty()) {
                val balText = http.get("$F10_WEB_BASE/NewFinanceAnalysis/zcfzbAjaxNew") {
                    header("User-Agent", ua)
                    parameter("companyType", "4")
                    parameter("reportDateType", "0")
                    parameter("reportType", "1")
                    parameter("dates", dates)
                    parameter("code", sec)
                }.bodyAsText()
                RiskScanSource.fillFromBalance(raw, balText)
            }
        }

        // ③ 数据中心：股权质押 / 限售解禁 / 高管增减持（各自独立，失败互不影响）
        runCatching { RiskScanSource.fillFromPledge(raw, dcFetch(bare, "RPTA_APP_ACCUMDETAILS", "NOTICE_DATE", ua)) }
        runCatching { RiskScanSource.fillFromUnlock(raw, dcFetch(bare, "RPT_LIFT_STAGE", "FREE_DATE", ua)) }
        runCatching {
            RiskScanSource.fillFromHolderChange(
                raw, dcFetch(bare, "RPT_EXECUTIVE_HOLD_DETAILS", "CHANGE_DATE", ua), totalShare,
            )
        }

        // ④ 最新价（腾讯行情字段 [3]；拿不到就留空）
        raw.price = runCatching {
            val t = http.get(TENCENT_QUOTE_ENDPOINT + symbol) {
                header("User-Agent", ua)
                header("Referer", "https://gu.qq.com/")
            }.bodyAsText()
            t.substringAfter("=\"", "").substringBeforeLast("\"").split("~").getOrNull(3)?.toDoubleOrNull()
        }.getOrNull()

        // P2-6：扫雷/巨潮的取数结果收进运行日志（此前只 println，用户「导出运行日志」里看不到）
        com.stocknote.data.log.SnLog.i(
            "SN_SCAN",
            "risk $symbol price=${raw.price} debt=${raw.debtRatio} pe=${raw.marketCap}",
        )
        return raw.toInput()
    }

    /** 数据中心通用取数（返回原始 JSON 文本，解析交给 [RiskScanSource]） */
    private suspend fun dcFetch(code: String, report: String, sortCol: String, ua: String): String =
        runCatching {
            http.get(DC_WEB_URL) {
                header("User-Agent", ua)
                parameter("reportName", report)
                parameter("columns", "ALL")
                parameter("pageSize", "200")
                parameter("pageNumber", "1")
                parameter("source", "WEB")
                parameter("client", "WEB")
                parameter("filter", "(SECURITY_CODE=\"$code\")")
                parameter("sortColumns", sortCol)
                parameter("sortTypes", "-1")
            }.bodyAsText()
        }.getOrDefault("")

    // ---------------------------------------------------------------- 巨潮资讯（诉讼 / 担保，手工同步）

    /**
     * 拉取巨潮**专题统计**（诉讼 / 担保）的**全市场**记录（老周 2026-09-24）。
     *
     * ⚠️ 两个特点决定了它**不做成"每次扫雷都拉"**，而是由「设置 → 诉讼担保查询」**手工同步一次**：
     *  ① 需要**动态鉴权头** `Accept-Enckey` = Base64(AES-128-CBC(当前秒级时间戳, key=iv="1234567887654321"))；
     *  ② 返回的是**全市场**记录（几千只票，体积大、耗流量）。
     * 同步结果落到本地表 `cninfo_risk`，扫雷时只查本地。
     *
     * @return `Pair(诉讼表, 担保表)`，元素为 `代码 → (次数, 数值)`（诉讼数值=金额万元 / 担保数值=占净资产%）
     */
    suspend fun fetchCninfoRisk(): Pair<Map<String, Pair<Int, Double?>>, Map<String, Pair<Int, Double?>>> {
        suspend fun one(url: String): Map<String, Pair<Int, Double?>> = runCatching {
            val enckey = com.stocknote.core.calc.Aes128.base64(
                com.stocknote.core.calc.Aes128.cbcEncrypt(
                    "1234567887654321".encodeToByteArray(),
                    "1234567887654321".encodeToByteArray(),
                    (nowEpochMs() / 1000).toString().encodeToByteArray(),
                ),
            )
            val text = http.post(url) {
                header("Accept", "*/*")
                header("Accept-Enckey", enckey)
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                header("Referer", "https://webapi.cninfo.com.cn/")
                header("Origin", "https://webapi.cninfo.com.cn")
                parameter("sdate", "2024-01-01")
                parameter("edate", todayIso())
                parameter("market", "")
                // ⚠️ H6 修复（2026-09-28）：巨潮返回的是**全市场**记录（几千只票、体积大），
                // 而全局超时只有 8 秒 —— 手工同步几乎必然超时，`cninfo_risk` 落成空表，
                // 扫雷页于是**恒显示"无诉讼担保"**，用户会以为标的没风险。这里单独放宽到 60 秒。
                timeout { requestTimeoutMillis = 60_000 }
            }.bodyAsText()
            RiskScanSource.parseCninfoRecords(text)
        }.getOrDefault(emptyMap())
        val sue = one(CNINFO_SUE_URL)
        val gte = one(CNINFO_GTE_URL)
        com.stocknote.data.log.SnLog.i("SN_SCAN", "cninfo sue=${sue.size} gte=${gte.size}")
        return sue to gte
    }

    // ---------------------------------------------------------------- 港股风险扫雷（老周 2026-09-24 补）

    /**
     * 港股风险扫雷取数（方案 v1.2 §2.5）。
     *
     * 数据源：① 东财**港股 F10** 主要指标 `RPT_HKF10_FN_MAININDICATOR`；
     * ② 港股资产负债表 `RPT_HKF10_FN_BALANCE_PC`；③ **腾讯自选股港股公告**（`type=0`，关键词扫标题）；
     * ④ **南向资金** `RPT_MUTUAL_HOLD_DET`；⑤ **回购** `RPT_HK_BUYBACK`；⑥ 腾讯行情取最新价。
     * ⚠️ 除 ① 外全部"失败即跳过"（对应项显示「—」），**不阻塞整体扫描**。
     *
     * @return 港股扫描输入；非港股或①失败时返回 null
     */
    suspend fun fetchHkRiskScan(symbol: String): com.stocknote.core.calc.RiskScanner.HkInput? {
        val code5 = hkBareCode(symbol) ?: return null
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

        /** 港股 F10 / 数据中心通用取数（同一网关 datacenter.eastmoney.com） */
        suspend fun dc(report: String, sortCol: String, pageSize: String = "3"): String = runCatching {
            http.get(HK_F10_ENDPOINT) {
                header("User-Agent", ua)
                parameter("reportName", report)
                parameter("columns", "ALL")
                parameter("pageNumber", "1")
                parameter("pageSize", pageSize)
                parameter("sortTypes", "-1")
                parameter("sortColumns", sortCol)
                parameter("source", "F10")
                parameter("client", "PC")
                parameter("filter", "(SECUCODE=\"$code5.HK\")(DATE_TYPE_CODE=\"001\")")
            }.bodyAsText()
        }.getOrDefault("")

        /** 数据中心（web 网关，用于南向/回购） */
        suspend fun dcWeb(report: String, sortCol: String, filter: String, pageSize: String = "100"): String =
            runCatching {
                http.get(DC_WEB_URL) {
                    header("User-Agent", ua)
                    parameter("reportName", report)
                    parameter("columns", "ALL")
                    parameter("pageSize", pageSize)
                    parameter("pageNumber", "1")
                    parameter("source", "WEB")
                    parameter("client", "WEB")
                    parameter("sortColumns", sortCol)
                    parameter("sortTypes", "-1")
                    parameter("filter", filter)
                }.bodyAsText()
            }.getOrDefault("")

        // ① 主源：港股 F10 主要指标（失败即中止）
        val mainText = dc("RPT_HKF10_FN_MAININDICATOR", "STD_REPORT_DATE")
        if (mainText.isEmpty()) return null
        // ② 资产负债表
        val balText = dc("RPT_HKF10_FN_BALANCE_PC", "REPORT_DATE", pageSize = "500")
        // ③ 公告标题（复用资讯接口，type=0 公告）
        val titles = runCatching { fetchStockNews(symbol, NewsSource.Kind.ANNOUNCEMENT, page = 1, n = 30) }
            .getOrNull()?.items?.map { it.title } ?: emptyList()
        // ④ 南向资金
        val southText = dcWeb("RPT_MUTUAL_HOLD_DET", "HOLD_DATE", "(SECURITY_CODE=\"$code5\")", pageSize = "80")
        // ⑤ 回购
        val bbText = dcWeb("RPT_HK_BUYBACK", "TRADE_DATE", "(SECURITY_CODE=\"$code5\")")
        // ⑥ 最新价（腾讯行情）
        val price = runCatching {
            val t = http.get(TENCENT_QUOTE_ENDPOINT + symbol) {
                header("User-Agent", ua)
                header("Referer", "https://gu.qq.com/")
            }.bodyAsText()
            t.substringAfter("=\"", "").substringBeforeLast("\"").split("~").getOrNull(3)?.toDoubleOrNull()
        }.getOrNull()

        val input = RiskScanSource.buildHkInput(
            symbol = symbol,
            mainText = mainText,
            balanceText = balText,
            announcements = titles,
            southboundText = southText,
            buybackText = bbText,
            price = price,
        )
        com.stocknote.data.log.SnLog.i(
            "SN_SCAN",
            "hk $symbol price=$price debt=${input.debtRatio} roe=${input.roe}",
        )
        return input
    }

    // ---------------------------------------------------------------- 分析师目标价（老周 2026-09-21）

    /**
     * 拉某标的**从 [fromIso] 起（含）**的研报目标价（东财研报库）。
     *
     * 时间窗由调用方算好传进来：6 个月去极值口径与「近 90 天均价」口径共用这一个方法
     *（时间串由 [AnalystTargetSource.monthsAgoIso] 一类工具生成）。
     *
     * ⚠️ 只有 A股有数据：港股/美股/基金代码传过去返回空列表（该接口是 A股研报库），
     * 调用方据此显示「该市场暂不支持」。返回的是**逐条样本**，平均在
     * [AnalystTargetSource.consensus] 里做（纯函数、可单测）。
     *
     * @param symbol App 侧代码（sh600519 / 600519 都行，这里只取数字部分）
     */
    suspend fun fetchAnalystTargets(symbol: String, fromIso: String): List<AnalystTargetSource.Report> {
        // ⚠️ 只认 `sh/sz/bj` + 6 位数字（A股）。
        //   绝不能用「取数字部分」的宽松判断：场外基金代码是 `of000001`，
        //   宽松匹配会拿「华夏成长混合」的代码去查**平安银行**的研报目标价（老账本踩过同类坑）。
        val code = AnalystTargetSource.aShareCodeOf(symbol) ?: return emptyList()
        val today = com.stocknote.data.platform.todayIso()
        return runCatching {
            val text: String = http.get(ANALYST_REPORT_ENDPOINT) {
                header("Referer", "https://data.eastmoney.com/report/stock.jshtml")
                parameter("industryCode", "*")
                parameter("pageSize", "100")
                parameter("industry", "*")
                parameter("rating", "*")
                parameter("ratingChange", "*")
                parameter("beginTime", fromIso)
                parameter("endTime", today)
                parameter("pageNo", "1")
                parameter("qType", "0")
                parameter("code", code)
                parameter("p", "1")
                parameter("pageNum", "1")
                parameter("pageNumber", "1")
            }.bodyAsText()
            AnalystTargetSource.parse(text)
        }.getOrDefault(emptyList())
    }

    /**
     * 拉**机构评级聚合**（东财数据中心，A股）：评级机构家数 + 机构目标价区间。
     *
     * 与 [fetchAnalystTargets] 互补 —— 那个是逐条研报（准但稀疏），这个是聚合（覆盖率高但只有区间）。
     * 非 A股 / 无数据返回 null。
     */
    suspend fun fetchAnalystRatingProfile(symbol: String): AnalystRatingSource.Profile? {
        val code = AnalystTargetSource.aShareCodeOf(symbol) ?: return null
        return runCatching {
            val text: String = http.get(CN_RATING_ENDPOINT) {
                parameter("reportName", "RPT_WEB_RESPREDICT")
                parameter("columns", "ALL")
                parameter("filter", "(SECURITY_CODE=\"$code\")")
                parameter("pageNumber", "1")
                parameter("pageSize", "1")
                parameter("source", "WEB")
                parameter("client", "WEB")
            }.bodyAsText()
            AnalystRatingSource.parse(text)
        }.getOrNull()
    }

    /**
     * 纯函数：解析腾讯外汇返回体。
     * 必须包含对应币种特征（HKDCNY/USDCNY）且能取到合理数值，否则返回 null。
     * @param sanity 参考值（HKD≈0.8 / USD≈6.0），用于 ±40% 合理性校验
     */
    internal fun parseFxRate(text: String, code: String, sanity: Double): Double? {
        if (code !in text) return null                    // 来源校验：不是我们要的那个币种
        val body = text.substringAfter("\"", "").substringBeforeLast("\"")
        val parts = body.split("~")
        if (parts.size < 4) return null
        val v = parts[3].trim().toDoubleOrNull() ?: return null
        // 合理性校验：偏离参考值 40% 以上视为异常，宁可不写
        return if (v > sanity * 0.6 && v < sanity * 1.4) v else null
    }

    /** 搜索候选项。symbol 已按本 App 规范（sh/sz/hk/us 前缀）。 */
    data class SymbolHit(
        val symbol: String,
        val name: String,
        val marketLabel: String,
        val currencyCode: String,
    )

    /**
     * 纯函数，便于单独测试（不依赖网络）——已归入 companion（与 parseQuote/parseCloses 同级）。
     * 解析防御式：任何字段缺失/结构变化都跳过该条，不抛异常。
     */
    internal fun parseSearch(text: String): List<SymbolHit> = Companion.parseSearch(text)

    /** 历史日 K（带日期）：腾讯 K 线 JSON 的日期与收盘配对输出，逐日估值用。 */
    data class Candle(val date: String, val close: Double)

    /**
     * 取近 [days] 个交易日的收盘价序列（升序）。
     *
     * ⚠️ 2026-10-01：原注释那句「不需要再引入第二个行情源」**已经作废** ——
     * 腾讯当天把 `web.ifzq` 的 K 线接口整站下线，资产曲线直接无数据。
     * 现在统一由 [fetchCandles] 调度「腾讯为主、东财为备」，本方法不再关心数据来自谁。
     */
    suspend fun fetchClosesWithDates(symbol: String, days: Int = 120): List<Candle> =
        fetchCandles(symbol, days)

    suspend fun fetchCloses(symbol: String, days: Int = 60): List<Double> =
        fetchCandles(symbol, days).map { it.close }

    companion object {
        /**
         * 腾讯 K 线（前复权）—— **注意域名**。
         *
         * ⚠️⚠️ 2026-10-01 实测：`web.ifzq.gtimg.cn` 下的 `fqkline` 接口**整站返回 HTTP 501**
         *   —— 所有标的（沪深港）、所有参数组合、带不带 UA、逗号编不编码，**一律 501**，与本机网络无关。
         *   这正是老周反馈「资产曲线取不到任何历史收盘价」的**真因**
         *   （运行日志里几十条 `K线请求失败 ... HTTP 501` 一眼可见）。
         *
         *   ✅ 同一路径换到 **`ifzq.gtimg.cn`（去掉 `web.`）** 立即恢复：
         *   实测 A股/港股/指数均 200，且返回结构与字段名（个股 `qfqday`、指数 `day`）
         *   **与旧接口完全一致**，[parseCloses] / [parseCandles] 等解析器无需任何改动。
         */
        const val DEFAULT_ENDPOINT = "https://ifzq.gtimg.cn/appstock/app/fqkline/get"

        /**
         * 东财日 K（**备源**，`push2his`）—— 2026-10-01 加。
         *
         * 起因：腾讯当天把 `web.ifzq` 的 K 线接口整站下线（一律 501），资产曲线当场"无数据"，
         * 而行情此前**只有这一个源**。补一个源后，主源抽风不会再让整条链路瘫掉。
         * 实测 A股 / 港股 / 指数均可用；参数见 [fetchCandlesFromEastmoney]。
         */
        const val EM_KLINE_ENDPOINT = "https://push2his.eastmoney.com/api/qt/stock/kline/get"

        const val SOURCE = "tencent"
        /** 基金净值的来源标记（quote.source 列，用于区分「交易所行情」与「基金净值」）。 */
        const val FUND_SOURCE = "eastmoney-fund"

        /** 判定「该候选符号取到了有效 K 线」的最小行数；低于此视为残数据（如美股旧格式只回 2 行）。 */
        private const val MIN_CANDLES = 5

        /**
         * 「成交日汇率」回填的**默认窗口**（天）：约 18 个月，绝大多数录入场景够用（一次请求最快）。
         */
        const val FX_ON_DEFAULT_DAYS = 400

        /**
         * 老日期**补拉窗口**（天）：2018-07 实测可得（约 8 年）。
         *
         * ⚠️ 上限别再加：`3000` 实测被接口拒绝、返回空列表 → 比 400 还差。
         * 分档策略见 [fxOnWindows]。
         */
        const val FX_ON_WIDE_DAYS = 2000

        /**
         * 汇率回填的窗口档位：**先小后大**（去重、升序）。
         * 传入的 [days] 也算一档，便于调用方自定义起点（如曲线要更长窗口）。
         */
        internal fun fxOnWindows(days: Int): List<Int> =
            listOf(days, FX_ON_WIDE_DAYS).filter { it > 0 }.distinct().sorted()

        /** 标的实时搜索（东方财富 suggest，UTF-8 JSON）。 */
        const val SEARCH_ENDPOINT = "https://searchapi.eastmoney.com/api/suggest/get"
        /** A股分红送配（东财数据中心，红送配明细报表）。⚠️ 只认股票，ETF/基金走下面那个。 */
        const val CN_DIVIDEND_ENDPOINT = "https://datacenter-web.eastmoney.com/api/data/v1/get"

        /** 基金（含 ETF / 场外基金）分红送配页（天天基金 F10，服务端渲染 HTML）。用法：前缀 + 6位代码 + ".html"。 */
        const val CN_FUND_DIVIDEND_ENDPOINT = "https://fundf10.eastmoney.com/fhsp_"

        /** 基金历史单位净值（天天基金 f10/lsjz，JSON）。 */
        const val FUND_NAV_ENDPOINT = "https://api.fund.eastmoney.com/f10/lsjz"

        /**
         * 港股 K 线（含分红字段 cqr/paixiri/FHcontent）。
         *
         * ⚠️ 2026-10-01：与 [DEFAULT_ENDPOINT] **同步改用 `ifzq.gtimg.cn`（去掉 `web.`）**。
         *   旧域名此刻尚可用，但腾讯明显正在下线 `web.` 那一套（`fqkline` 已整站 501），
         *   一并对齐可避免港股分红下次再断；新域名实测返回结构与 cqr / FHcontent 字段完全一致。
         */
        const val HK_DIVIDEND_ENDPOINT = "https://ifzq.gtimg.cn/appstock/app/hkfqkline/get"

        /**
         * 东财 F10 —— **美股**分红派息（`RPT_USF10_INFO_DIVIDEND`，老周 2026-09-30 实测）。
         * 与 [HK_F10_ENDPOINT] 同一网关，只是 `reportName` 不同。
         */
        const val US_F10_ENDPOINT = "https://datacenter.eastmoney.com/securities/api/data/v1/get"

        /** 美股分红报表要取的列（东财要求显式列出；实测可用组合）。 */
        private const val US_DIVIDEND_COLUMNS =
            "SECUCODE,SECURITY_CODE,SECURITY_NAME_ABBR,NOTICE_DATE,ASSIGN_TYPE,PLAN_EXPLAIN," +
                "EQUITY_RECORD_DATE,BONUS_PAY_DATE,EX_DIVIDEND_DATE,ASSIGN_PERIOD"

        /** 外汇接口（离岸/在岸人民币报价）。 */
        const val FX_ENDPOINT = "https://qt.gtimg.cn/q="

        /**
         * 个股资讯 / 公告 / 研报（腾讯自选股，老周 2026-09-24 实测）。
         * 参数：type(0公告/1研报/2资讯) + symbol + page + n；Headers 需带 UA 与 Referer。
         */
        const val NEWS_ENDPOINT = "https://proxy.finance.qq.com/ifzqgtimg/appstock/news/info/search"

        /**
         * 财联社 **7×24 快讯**（老周 2026-10-04，依据《财联社 7x24 快讯接口技术文档 v1.0》）。
         * 需要 `sign`（MD5∘SHA1）与 Referer，见 [FlashNewsSource]。
         */
        const val FLASH_NEWS_ENDPOINT = "https://www.cls.cn/v1/roll/get_roll_list"

        /** 东财 F10 —— A股：老网关 + `type` + `sty`（《东方财富F10接口技术文档》§3.2）。 */
        const val A_F10_ENDPOINT = "https://datacenter.eastmoney.com/securities/api/data/get"

        /** 东财 F10 —— 港股：v1 网关 + `reportName`（同上文档 §3.2）。 */
        const val HK_F10_ENDPOINT = "https://datacenter.eastmoney.com/securities/api/data/v1/get"

        /** 东财行情（单只查询）：取现价 / 估值（PE、PB、股息率）。 */
        const val VALUATION_ENDPOINT = "https://push2.eastmoney.com/api/qt/stock/get"

        /** 腾讯行情（`q=` 后直接跟代码，无查询串）：估值兜底用，见 [parseTencentValuation]。 */
        const val TENCENT_QUOTE_ENDPOINT = "https://qt.gtimg.cn/q="

        /** 东财 F10 网页版接口基址（操盘必读 / 资产负债表）。 */
        const val F10_WEB_BASE = "https://emweb.securities.eastmoney.com/PC_HSF10"

        /** 东财数据中心（股权质押 / 限售解禁 / 高管增减持）。 */
        const val DC_WEB_URL = "https://datacenter-web.eastmoney.com/api/data/v1/get"

        /** 巨潮资讯 · 公司诉讼专题统计（需动态鉴权头 Accept-Enckey）。 */
        const val CNINFO_SUE_URL = "https://webapi.cninfo.com.cn/api/sysapi/p_sysapi1055"

        /** 巨潮资讯 · 对外担保专题统计（同上鉴权）。 */
        const val CNINFO_GTE_URL = "https://webapi.cninfo.com.cn/api/sysapi/p_sysapi1054"

        /**
         * App 侧代码 → 东财 F10 的 **sec**（大写市场前缀，如 `SH600519` / `SZ000001` / `BJ830799`）。
         * 仅 A 股（沪/深/北）；其它市场返回 null（扫雷只覆盖 A 股）。
         */
        fun riskSecOf(symbol: String): String? {
            val s = symbol.trim().lowercase()
            return when {
                s.startsWith("sh") && s.length >= 8 -> "SH" + s.substring(2)
                s.startsWith("sz") && s.length >= 8 -> "SZ" + s.substring(2)
                s.startsWith("bj") && s.length >= 8 -> "BJ" + s.substring(2)
                else -> null
            }
        }

        /**
         * App 侧代码 → 港股 5 位代码（`hk00700` → `00700`）；非港股返回 null。
         * 与 [riskSecOf] 并列放在 companion 里，供 UI 侧**判断市场**（个股扫雷/A股或港股分支）。
         */
        fun hkBareCode(symbol: String): String? {
            val s = symbol.trim().lowercase()
            return if (s.startsWith("hk") && s.length >= 6) s.substring(2).padStart(5, '0') else null
        }

        /**
         * App 侧代码 → 东财美股的 `(裸代码大写, 交易所后缀候选)`；非美股返回 null。
         *
         * 后缀映射：`O` 纳斯达克 / `N` 纽交所 / `A` 美交所（东财用**单字母**，
         * 与腾讯行情的 `.OQ/.N/.A` 不同 —— 别直接拿行情后缀去查）。
         * 无后缀时按 O → N → A **依次探测**（老周 2026-09-30：多数美股在纳斯达克/纽交所）。
         */
        internal fun usSecuCode(symbol: String): Pair<String, List<String>>? {
            val s = symbol.trim()
            if (!s.startsWith("us", ignoreCase = true)) return null
            val body = s.substring(2)
            if (body.isEmpty()) return null
            val dot = body.lastIndexOf('.')
            if (dot > 0) {
                val code = body.substring(0, dot).uppercase()
                val suffix = when (body.substring(dot + 1).uppercase()) {
                    "OQ", "O" -> "O"
                    "N", "NY" -> "N"
                    "A", "AM" -> "A"
                    else -> null
                }
                if (code.isNotEmpty() && suffix != null) return code to listOf(suffix)
            }
            return body.uppercase() to listOf("O", "N", "A")
        }

        /**
         * App 侧代码 → 东财行情 **secid**（沪 `1.` / 深 `0.` / 北 `0.`）。
         * 只认 A股三个市场；港股/美股/场外基金返回 null（估值补齐只对 A股 有意义）。
         */
        internal fun eastmoneySecid(symbol: String): String? {
            val s = symbol.trim().lowercase()
            return when {
                s.startsWith("sh") && s.length >= 8 -> "1." + s.substring(2)
                s.startsWith("sz") && s.length >= 8 -> "0." + s.substring(2)
                s.startsWith("bj") && s.length >= 8 -> "0." + s.substring(2)
                else -> null
            }
        }

        /**
         * App 侧代码 → 东财 **K 线** secid（沪 `1.` / 深 `0.` / 北 `0.` / 港 `116.`）。
         *
         * ⚠️ 与上面 [eastmoneySecid] **故意分开**：那个是给「A股估值补齐」用的（港股/美股返回 null
         * 是有意为之的语义），而 K 线备源需要港股 —— 若直接给它加 `hk` 分支，会顺带改变估值链路的行为。
         * 美股暂不支持（东财要按 `105./106./107.` 探测交易所），返回 null 即"该标的没有备源"。
         */
        internal fun eastmoneyKlineSecid(symbol: String): String? {
            val s = symbol.trim().lowercase()
            return when {
                // A股 / 指数：2 位市场前缀 + **6 位**代码（sh600519 / sh000300 / bj830799）
                s.startsWith("sh") && s.length >= 8 -> "1." + s.substring(2)
                s.startsWith("sz") && s.length >= 8 -> "0." + s.substring(2)
                s.startsWith("bj") && s.length >= 8 -> "0." + s.substring(2)
                // ⚠️ 港股是 **5 位**代码（hk00700）→ 总长 **7**，**别照抄 A股的 8**！
                // 这里曾写成 `>= 8`，导致港股永远匹配不上、静默失去备源（被
                // QuoteClientParsingTest 当场抓出）。
                s.startsWith("hk") && s.length >= 7 -> "116." + s.substring(2)
                else -> null
            }
        }

        /**
         * 解析**东财日 K**（备源）。
         *
         * 响应形如 `{"data":{"code":"000300","klines":["2026-09-23,开,收,高,低,量,额,...", ...]}}` ——
         * 每行是**逗号分隔的字符串**（不是 JSON 数组），与腾讯的 `qfqday` 结构完全不同，故单独一个解析函数。
         * 字段序：0=日期 1=开 2=**收** 3=高 4=低，与腾讯行 `row[0]=日期 / row[2]=收` 对齐。
         * 防御式：字段缺失或解析失败就跳过该行，不抛异常（行情是外部依赖，不能让接口改版把 App 带崩）。
         */
        internal fun parseEastmoneyCandles(text: String): List<Candle> {
            val root = obj(text) ?: return emptyList()
            val data = root["data"]?.let { it as? JsonObject } ?: return emptyList()
            val rows = data["klines"]?.let { it as? JsonArray } ?: return emptyList()
            return rows.mapNotNull { row ->
                val line = (row as? JsonPrimitive)?.content ?: return@mapNotNull null
                val parts = line.split(',')
                val date = parts.getOrNull(0)?.trim() ?: return@mapNotNull null
                val close = parts.getOrNull(2)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                if (date.length >= 10) Candle(date.take(10), close) else null
            }
        }

        /**
         * 解析**腾讯行情**（主源，`qt.gtimg.cn/q=sh600519`，`~` 分隔的字段数组）。
         *
         * 实测（2026-09-24 贵州茅台）：
         *  - `[3]` 现价 1237.00 · `[31]/[32]` 涨跌额/幅 · `[38]` 换手率
         *  - `[39]` **PE(TTM) 18.99** · `[46]` **PB 6.15** · `[52]/[53]` PE 动/静
         *  - **`[64]` 股息率 4.21(%)** ← ⚠️ **不是 `[43]`**！`[43]` 实测是 2.00，与老周给的
         *    真实股息率（4.21%）不符，**已纠正**（老周 2026-09-24 指出）
         *
         * ⚠️ 与东财两点不同：
         *  1. 腾讯这里**没有放大 100 倍**（18.99 就是 18.99）→ **不要除 100**；
         *  2. 返回是 **GBK** 编码，`bodyAsText()` 会把中文名解成乱码 —— 但**我们只取数字字段，不受影响**。
         */
        internal fun parseTencentValuation(text: String): Map<String, Double> {
            val body = text.substringAfter("=\"", "").substringBeforeLast("\"")
            if (body.isEmpty()) return emptyMap()
            val p = body.split("~")
            fun at(i: Int): Double? = p.getOrNull(i)?.trim()?.toDoubleOrNull()?.takeIf { it != 0.0 }
            val out = LinkedHashMap<String, Double>()
            at(39)?.let { out["pe"] = it }
            at(46)?.let { out["pb"] = it }
            at(64)?.let { out["dividend_rate"] = it }
            return out
        }

        /**
         * 解析估值：`f164`→PE(TTM)、`f167`→PB、`f171`→股息率(%)。
         * ⚠️ 三个都要 **÷100**（东财放大了 100 倍）；缺失或为 0 的**不放进去**（UI 显示「—」，不编造）。
         */
        internal fun parseValuation(text: String): Map<String, Double> {
            val root = obj(text) ?: return emptyMap()
            val d = root["data"] as? JsonObject ?: return emptyMap()
            fun scaled(field: String): Double? =
                (d[field] as? JsonPrimitive)?.doubleOrNull?.takeIf { it != 0.0 }?.div(100.0)
            val out = LinkedHashMap<String, Double>()
            scaled("f164")?.let { out["pe"] = it }
            scaled("f167")?.let { out["pb"] = it }
            scaled("f171")?.let { out["dividend_rate"] = it }
            return out
        }

        /**
         * 个股研报列表（东财，JSON）—— 分析师目标价的来源（老周 2026-09-21）。
         * 逐条含 `publishDate` 与 `indvAimPriceT/L`（目标价上限/下限），**只有 A股有数据**。
         */
        const val ANALYST_REPORT_ENDPOINT = "https://reportapi.eastmoney.com/report/list"

        /**
         * 机构评级聚合（东财数据中心 `RPT_WEB_RESPREDICT`）：评级家数 + 目标价区间。
         * 与分红用同一个 datacenter 网关，只是 `reportName` 不同。
         */
        const val CN_RATING_ENDPOINT = "https://datacenter-web.eastmoney.com/api/data/v1/get"

        const val SEARCH_TOKEN = "D43BF722C8E33BDC906FB84D85E326E8"

        /**
         * 解析基金历史净值 JSON（**纯函数**，便于单测）。
         *
         * 形态：`{"Data":{"LSJZList":[{"FSRQ":"2026-09-18","DWJZ":"1.3330","JZZZL":"2.70"},...]}}`。
         * 缺字段 / 结构变化一律跳过该条，不抛异常（与 [parseSearch] 同风格），
         * 因为基金净值错了会**静默**把场外基金的浮盈算错，宁可少一条也不要脏数据。
         */
        internal fun parseFundNav(text: String): List<FundNav> {
            val root = obj(text) ?: return emptyList()
            val list = ((root["Data"] as? JsonObject)?.get("LSJZList") as? JsonArray) ?: return emptyList()
            return list.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                fun str(k: String): String =
                    (o[k] as? JsonPrimitive)?.content?.takeIf { it != "null" } ?: ""
                val date = str("FSRQ").take(10)
                val nav = str("DWJZ").toDoubleOrNull()
                if (date.length != 10 || nav == null || nav <= 0.0) return@mapNotNull null
                FundNav(date = date, nav = nav, growthPct = str("JZZZL").toDoubleOrNull())
            }
        }

        /**
         * 由**裸代码**给出 App 的市场标签 —— 直接复用 [MarketGuess]，不另开一套规则。
         *
         * 2026-09-20 修：此前深市写的是 `code.startsWith("1") → ETF`，
         * 于是深市**可转债 `123xxx` / 国债 `10xxxx`** 在联网搜索里被标成 ETF
         * （与 CSV 导入建标那个 bug 同源）。现收紧为 15/16/18 段。
         */
        private fun marketLabelOf(code: String): String = when (MarketGuess.of(code)) {
            Market.ETF -> "ETF"
            Market.FUND -> "基金"
            else -> "A股"
        }

        /** 纯函数，便于单独测试（不依赖网络）。 */
        internal fun parseQuote(symbol: String, text: String): Quote? {
            val root = obj(text) ?: return null
            val data = root["data"]?.let { it as? JsonObject } ?: return null
            val node = data[symbol]?.let { it as? JsonObject } ?: return null

            var price: Double? = null
            var prevClose: Double? = null

            // K 线行（qfqday 优先，day 兜底）：既给「取价兜底」用，也给「报价日期」兜底用
            val rows = (node["qfqday"] ?: node["day"])?.let { it as? JsonArray }

            // 1) 实时报价字段 qt：[0]=市场 [1]=名称 [2]=代码 [3]=当前价 [4]=昨收
            val qt = node["qt"]?.let { it as? JsonObject }?.get(symbol)?.let { it as? JsonArray }
            if (qt != null && qt.size > 4) {
                price = num(qt[3])
                prevClose = num(qt[4])
            }

            // 2) 兜底：用最近两根 K 线的收盘价
            if (price == null) {
                val closes = rows?.mapNotNull { row ->
                    (row as? JsonArray)?.getOrNull(2)?.let { num(it) }
                }
                if (!closes.isNullOrEmpty()) {
                    price = closes.last()
                    if (prevClose == null) prevClose = closes.getOrNull(closes.size - 2)
                }
            }

            // 3) 报价日期（P3-36，2026-10-02）：qt[30] = 报价时间（**交易所本地** yyyyMMddHHmmss）。
            //    实测 2026-10-02（国庆休市）请求贵州茅台返回 "20260930161458" —— 休市时行情
            //    快照停在最后一个交易日。loadSnapshot 靠它把「报价日期 ≠ 今天」的标的从
            //    **当日盈亏**里剔除（市值照算）。qt 没给时间时兜底用最后一根 K 线的日期
            //    （同一语义：K 线最后一根就是最后交易日）。防御式：格式不对就放弃，不猜。
            val quoteDate = qt?.getOrNull(30)?.let { it as? JsonPrimitive }?.content
                ?.takeIf { t -> t.length >= 8 && t.take(8).all(Char::isDigit) }
                ?.let { t -> t.substring(0, 4) + "-" + t.substring(4, 6) + "-" + t.substring(6, 8) }
                ?: rows?.lastOrNull()?.let { row -> (row as? JsonArray)?.getOrNull(0) }
                    ?.let { it as? JsonPrimitive }?.content
                    ?.takeIf { d -> d.length == 10 && d.substring(0, 4).all(Char::isDigit) }

            val p = price ?: return null
            if (p <= 0.0) return null
            return Quote(symbol = symbol, price = p, prevClose = prevClose, source = SOURCE, quoteDate = quoteDate)
        }

        private fun obj(text: String): JsonObject? =
            runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject

        private fun num(e: JsonElement?): Double? =
            (e as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()

        /** K 线行格式：[日期, 开, 收, 高, 低, 量] */
        internal fun parseCloses(symbol: String, text: String): List<Double> {
            val root = obj(text) ?: return emptyList()
            val data = root["data"]?.let { it as? JsonObject } ?: return emptyList()
            val node = data[symbol]?.let { it as? JsonObject } ?: return emptyList()
            val rows = (node["qfqday"] ?: node["day"])?.let { it as? JsonArray } ?: return emptyList()
            return rows.mapNotNull { row -> (row as? JsonArray)?.getOrNull(2)?.let { num(it) } }
        }

        /** K 线行解析：日期（row[0]，形如 2026-09-12）+ 收盘（row[2]）。防御式，缺字段跳过。 */
        internal fun parseCandles(symbol: String, text: String): List<Candle> {
            val root = obj(text) ?: return emptyList()
            val data = root["data"]?.let { it as? JsonObject } ?: return emptyList()
            val node = data[symbol]?.let { it as? JsonObject } ?: return emptyList()
            val rows = (node["qfqday"] ?: node["day"])?.let { it as? JsonArray } ?: return emptyList()
            return rows.mapNotNull { row ->
                row as? JsonArray ?: return@mapNotNull null
                val date = (row.getOrNull(0) as? JsonPrimitive)?.content ?: return@mapNotNull null
                val close = row.getOrNull(2)?.let { num(it) } ?: return@mapNotNull null
                if (date.length >= 10) Candle(date.take(10), close) else null
            }
        }

        /**
         * 标的搜索结果解析。纯函数，便于单独测试（不依赖网络）。
         * 解析防御式：任何字段缺失/结构变化都跳过该条，不抛异常。
         *
         * ⚠️ MarketType 是**数字枚举**（2026-09-14 真机实测，2026-09-20 网络复核）：
         *   1=沪（沪A/沪基金/指数） 2=深（深A/深基金） 5=港股 7=美股 **6=场外基金（OTCFUND）**；
         *   字母形态 "SH"/"SZ"/"HK"/"US" 仅作兼容保留。指数不进入记账，直接跳过。
         */
        internal fun parseSearch(text: String): List<SymbolHit> {
            val root = obj(text) ?: return emptyList()
            val table = root["QuotationCodeTable"]?.let { it as? JsonObject } ?: return emptyList()
            val data = table["Data"]?.let { it as? JsonArray } ?: return emptyList()

            return data.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                fun str(k: String): String? = (o[k] as? JsonPrimitive)?.content
                val code = str("Code") ?: return@mapNotNull null
                val name = str("Name") ?: return@mapNotNull null
                val mkt = (str("MarketType") ?: "").uppercase()
                val typeName = str("SecurityTypeName") ?: ""
                val classify = (str("Classify") ?: "").uppercase()
                if (typeName.contains("指数")) return@mapNotNull null

                // 映射到本 App 的市场/币种/代码规范
                val hit = when (mkt) {
                    // 场内（沪深交易所）
                    "1", "SH" -> SymbolHit("sh$code", name, marketLabelOf(code), "CNY")
                    "2", "SZ" -> SymbolHit("sz$code", name, marketLabelOf(code), "CNY")

                    // 场外基金（老周 2026-09-20 加）：东财 MarketType="6" / Classify="OTCFUND"。
                    // ⚠️ 此前没有这一支 → 场外基金**联网根本搜不出来**，只能手填。
                    // 代码用 `of` 前缀，因为场外基金代码与股票**同码不同物**
                    // （000001 既是华夏成长混合，也是平安银行）。
                    "6", "OF", "OTCFUND" ->
                        if (classify == "OTCFUND" || typeName == "基金") {
                            SymbolHit("of$code", name, "基金", "CNY")
                        } else {
                            null
                        }

                    "5", "HK" -> SymbolHit("hk$code", name, "港股", "HKD")
                    "7", "US" -> SymbolHit("us$code", name, "美股", "USD")
                    else -> null
                }
                hit
            }.filter { it.symbol.isNotBlank() }
        }
    }
}
