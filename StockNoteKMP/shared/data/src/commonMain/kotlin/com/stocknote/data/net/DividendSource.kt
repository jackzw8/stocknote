package com.stocknote.data.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 分红送配数据源（老周 2026-09-16 定稿：A股 + 港股一起做）。
 *
 * - **A股（股票）**：东方财富数据中心 `RPT_SHAREBONUS_DET`（每10股派息/送股/转增 + 登记日 + 除权日）
 *   → 口径 = **税前**（票面）
 * - **港股**：腾讯港股 K 线接口自带分红字段（`cqr` 除权日 / `paixiri` 派息日 / `FHcontent` 文本）
 *   → 口径 = **税后**（港股通红利税 20%，见 [HK_DIVIDEND_TAX]）
 * - **场内/场外基金（含 ETF）**：天天基金 F10 分红送配页（`<table>` 服务端渲染）
 *   → 口径 = **税前**
 *
 * ⚠️ **ETF 必须单独走基金接口**（老周 2026-09-20）：东财股票报表 `RPT_SHAREBONUS_DET`
 * 对 ETF 代码直接返回「返回数据为空」（真机实测 510300 / 511660 均为空），
 * 此前 ETF 被当成 A股 去查股票接口 → **永远检不出分红**。
 *
 * 解析全部是纯函数（[parseCnBonus] / [parseHkBonus] / [parseHkDividendText] /
 * [parseCnFundDividend]），便于单测。
 */
object DividendSource {

    /** 港股通红利税（内地个人投资者统一按 20% 代扣）。 */
    const val HK_DIVIDEND_TAX = 0.20

    /** 港股每股金额的币种 */
    enum class HkCurrency(val label: String) { HKD("港元"), CNY("人民币"), USD("美元") }

    /**
     * 一条分红送配（已归一化为「每股」口径）。
     *
     * @param exDate 除权除息日（yyyy-MM-dd）—— 登记分红时 App 的 ex_date 用这个
     * @param recordDate **权益登记日**（yyyy-MM-dd）。持有该记录才能在除权日拿到分红，
     *   因此「按登记日持仓数计算分红」比「除权日前一日」近似更准。
     *   三家源都能拿到：东财 `EQUITY_RECORD_DATE` / 天天基金「权益登记日」列；
     *   港股腾讯源没有该字段 → 保持 null，调用方退回除权日前一日近似。
     * @param payDate 派息日（港股/基金有；A股通常为空）
     * @param cashPerShare 每股现金分红（A股税前 / 港股税后 / 基金税前；本位币为标的币种）
     * @param bonusPerShare 每股送股（A股才有；港股红股极少）
     * @param rightsPerShare 每股转增（A股；港股/基金无）
     * @param note 方案文本（用于 UI 展示核对）
     */
    data class Dividend(
        val securitySymbol: String,
        val exDate: String,
        val recordDate: String? = null,
        val payDate: String? = null,
        val cashPerShare: Double = 0.0,
        val bonusPerShare: Double = 0.0,
        val rightsPerShare: Double = 0.0,
        val currency: String = "CNY",
        val taxNote: String = "",
        val note: String = "",
    ) {
        val hasCash: Boolean get() = cashPerShare > 1e-9
        val hasBonus: Boolean get() = bonusPerShare > 1e-9 || rightsPerShare > 1e-9
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---------------------------------------------------------------- 响应体合法性（P1-3，老周 2026-10-02）

    /**
     * 东财数据中心「查询成功但结果为空」的官方 `code`。
     * 实测（2026-10-02）：**没有分红历史的标的**（如 830799）= 9201；
     * **代码查不到**（999999）= 9201；**美股交易所猜错**（AAPL.N）= 9201 → 三者同形。
     */
    private const val EM_EMPTY_CODE = 9201

    /**
     * **东财数据中心（A股 `RPT_SHAREBONUS_DET` / 美股 `RPT_USF10_INFO_DIVIDEND`）响应能不能当查询结果用**。
     *
     * ## 为什么需要它（P1-3）
     * 此前 [QuoteClient.fetchCnDividends] 等只看「请求没抛异常」就当成功，于是**错误体**会被
     * 解析成空列表，与「确认没有分红」**完全同形** → 用户永远等不到提示（静默漏检）。
     *
     * ## ⚠️ 判据是 `code`，**不是** `result == null`（实测结论，推翻了原计划的假设）
     * 实测（curl，2026-10-02，HTTP 全部 200）：
     * ```
     * 有数据：{"result":{"pages":1,"data":[…],"count":28},"success":true,"message":"ok","code":0}
     * 无数据：{"version":null,"result":null,"success":false,"message":"返回数据为空","code":9201}
     * 参数错：{"version":null,"result":null,"success":false,"message":"报表配置不存在,…","code":9501}
     * ```
     * ⇒ **东财「确实没有数据」时 `result` 本来就是 `null`**。若把 `result:null` 一律判失败，
     * 则每一个「没有分红历史」的标的每次扫描都会报「取数失败，可能漏检」——
     * 从"静默漏检"变成"每次都误报"，对用户更糟。
     * 真正的分界是 `code`：`9201` = 查询成功、结果为空（可当"无分红"）；`0` = 有数据；
     * 其它（如 9501 报表配置错、限流/WAF 返回的非 0 码）= **明确失败**。
     *
     * ## 已知边界（如实记录，不赌）
     * 若东财限流**恰好**返回 `code:9201 + "返回数据为空"`，本判据无法与"真的没有数据"区分 ——
     * 但限流通常伴随 403（Ktor 会抛异常）或其它 code，这类都能被下面的规则拦下。
     * 另外 `code` 缺省时（退回结构判断）只要求 `result.data` 是数组：
     * **`data: []` 属于「成功且确实没分红」**，只有"连 data 段都没有"才算结构不完整。
     */
    fun eastmoneyOk(text: String): Boolean {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
        val code = (root["code"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (code == EM_EMPTY_CODE) return true
        if (code != null && code != 0) return false
        if ((root["success"] as? JsonPrimitive)?.content?.equals("false", ignoreCase = true) == true) return false
        return (root["result"] as? JsonObject)?.get("data") is JsonArray
    }

    /**
     * **腾讯港股 K 线响应是否真的拿到了 K 线**（P1-3）。
     *
     * 实测（2026-10-02，HTTP 200）：
     * ```
     * 有效代码：{"code":0,"msg":"","data":{"hk00700":{"qfqday":[…1200 根…]}}}
     * 无效代码：{"code":0,"msg":"","data":{"hk99999":{"day":[],"qt":{…}}}}   ← day 为空、无 qfqday
     * 参数错　：{"code":1,"msg":"bad params"}
     * ```
     * 判据：`code == 0`（缺省放行）且 `data.<symbol>` 存在、其 `qfqday`/`day` 是**非空**数组。
     * 有效的港股代码必然有 K 线 —— **拿不到 K 线就是取数失败**，不能当成"没有分红"
     * （注意区分：K 线有、只是行里没有 `cqr`/`FHcontent` 字段 = 真的没有分红）。
     */
    fun tencentKlineOk(text: String, symbol: String): Boolean {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
        val code = (root["code"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (code != null && code != 0) return false
        val node = (root["data"] as? JsonObject)?.get(symbol) as? JsonObject ?: return false
        val kline = (node["qfqday"] ?: node["day"]) as? JsonArray ?: return false
        return kline.isNotEmpty()
    }

    /**
     * **天天基金 F10 分红页是否是正常页面**（P1-3）。
     *
     * 该源是**服务端渲染 HTML**（URL 形如 `fundf10.eastmoney.com/fhsp_<代码>.html`），没有 JSON 信封、
     * 也没有错误码可判 —— 正常页面的标题/关键字必然含「分红送配」四个字，被拦截或返回异常页时不含。
     * 故用**这一条**当作"页面正常"的判据（宁可提示"可能漏检"，也不要静默漏检 —— P1-3 的取向）。
     *
     * ⚠️ 已知边界：**代码不存在时天天基金照样渲染一个正常页面**（实测 999999：「(999999)基金分红送配…」，
     * 正文无分红行）→ 这种情况会被判为「成功但无分红」。无法区分，如实记录。
     */
    fun cnFundDividendPageOk(html: String): Boolean = html.contains("分红送配")

    // ---------------------------------------------------------------- A股（东财，税前）

    /**
     * 解析东财分红送配返回（纯函数）。
     * 只保留 `ASSIGN_PROGRESS` 含「实施」的记录（预案/股东大会通过的不算真实发生）。
     */
    fun parseCnBonus(text: String, symbol: String): List<Dividend> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val arr = (root["result"] as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val progress = o.str("ASSIGN_PROGRESS")
            if (progress.isNotEmpty() && !progress.contains("实施")) return@mapNotNull null
            val exDate = o.str("EX_DIVIDEND_DATE").take(10)
            if (exDate.length != 10) return@mapNotNull null
            // 真实权益登记日：有它就不必用「除权日前一日」近似（2026-09-20 修准）
            val recordDate = o.str("EQUITY_RECORD_DATE").take(10).takeIf { it.length == 10 }
            // 东财：每 10 股派息（税前，元）/ 送股 / 转增
            val per10Cash = o.dbl("PRETAX_BONUS_RMB")
            val per10Bonus = o.dbl("BONUS_RATIO")
            val per10Rights = o.dbl("IT_RATIO")
            if (per10Cash <= 0 && per10Bonus <= 0 && per10Rights <= 0) return@mapNotNull null
            Dividend(
                securitySymbol = symbol,
                exDate = exDate,
                recordDate = recordDate,
                cashPerShare = per10Cash / 10.0,
                bonusPerShare = per10Bonus / 10.0,
                rightsPerShare = per10Rights / 10.0,
                currency = "CNY",
                taxNote = "税前（票面）",
                note = o.str("IMPL_PLAN_PROFILE"),
            )
        }
    }

    // ---------------------------------------------------------------- 基金（含 ETF，天天基金 F10）

    /**
     * 解析天天基金 F10「分红送配」页（**纯函数**，老周 2026-09-20）。
     *
     * 为什么不用股票接口：东财 `RPT_SHAREBONUS_DET` 是**股票**分红送配报表，
     * 对 ETF 代码（510300 / 511660）实测直接返回「返回数据为空」，
     * 所以 ETF 此前**永远检不出分红**。基金分红在天天基金的 F10 页里，
     * 且该页是**服务端渲染**的，直接解析 HTML 即可（无需额外 token）。
     *
     * 页面表格形态（实测 510300）：
     * ```
     * <tr><td>2026年</td><td>2026-01-16</td><td>2026-01-19</td>
     *     <td>每10份派现金1.2300元</td><td>2026-01-27</td></tr>
     * ```
     * 无分红时该行是 `<td align='center' colspan='5'>暂无分红信息!</td>` —— 靠
     * 「首格必须是 4 位年份」自然过滤掉。
     *
     * ⚠️ 基金分红与股票的关键区别（本函数因此只产出**现金**）：
     *
     *  - 基金/ETF **只有现金分红**，没有送股、转增、配股 → [bonusPerShare] / [rightsPerShare] 恒为 0；
     *  - 基金分红**不代扣红利税**（对个人投资者），票面即到账 → 口径同「税前」。
     */
    fun parseCnFundDividend(html: String, symbol: String): List<Dividend> {
        // 只取「分红送配详情」表，避免误抓页面上其它表格（如费率/规模）
        val anchor = html.indexOf("分红送配详情")
        val body = if (anchor >= 0) html.substring(anchor) else html

        val rowRe = Regex("""<tr[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
        val cellRe = Regex("""<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)

        return rowRe.findAll(body).mapNotNull { rowM ->
            val cells = cellRe.findAll(rowM.groupValues[1]).map { cleanCell(it.groupValues[1]) }.toList()
            if (cells.size < 5) return@mapNotNull null

            // ⚠️ 不要用「首格是 4 位年份」筛行：真实页面首格是 `2026年`（**带「年」字**），
            // 早先按 `^\d{4}$` 严格匹配会把**每一行都过滤掉**，函数静默返回空列表
            // （2026-09-20 单测当场抓到）。改为按**日期列**判定，顺带天然排除同页的
            // 「拆分折算」表（那行是 `2012年 | 2012-05-11 | 份额折算 | 1:0.3709`，
            // 第 3 格不是日期）。
            val recordDate = cells[1].take(10).takeIf { isIsoDate(it) }
            val exDate = cells[2].take(10)
            if (!isIsoDate(exDate)) return@mapNotNull null
            val per10 = PER_10_RE.find(cells[3])?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            if (per10 <= 0) return@mapNotNull null

            Dividend(
                securitySymbol = symbol,
                exDate = exDate,
                recordDate = recordDate,
                payDate = cells[4].take(10).takeIf { isIsoDate(it) },
                cashPerShare = per10 / 10.0,
                // 基金分红对个人投资者不代扣红利税 → 票面即到账
                currency = "CNY",
                taxNote = "基金分红（不代扣税）",
                note = cells[3],
            )
        }.toList().sortedBy { it.exDate }
    }

    /** 「每10份派现金1.2300元」→ 抓 1.2300 */
    private val PER_10_RE = Regex("""每\s*10\s*份[^0-9]*([0-9]+(?:\.[0-9]+)?)""")

    private fun isIsoDate(s: String): Boolean =
        s.length == 10 && s[4] == '-' && s[7] == '-' &&
            s.substring(0, 4).all { it.isDigit() } &&
            s.substring(5, 7).all { it.isDigit() } &&
            s.substring(8, 10).all { it.isDigit() }

    /**
     * ⚠️ M15 修复（2026-09-28）：正则**提为常量**。
     * 此前在函数体内 `Regex(...)` 逐次构造 —— 港股分红一次要解析 1200 根 K 线，
     * 循环内的两个（每笔分红 2~3 次）尤其浪费。`Regex` 构造虽有内部缓存，但跨平台实现
     * 不保证，提成常量最稳妥。
     */
    private val TAG_RE = Regex("""<[^>]*>""")
    private val SPACE_RE = Regex("""\s+""")
    private val HK_SPLIT_RE = Regex("(?=息)")
    private val HK_AMOUNT_RE = Regex("""息\s*([0-9]+(?:\.[0-9]+)?)\s*(港元|港币|人民币|美元)""")
    private val HK_EQUIV_RE = Regex("""相当于\s*([0-9]+(?:\.[0-9]+)?)\s*港元""")

    /** 去标签、去实体、压缩空白 —— HTML 取出来的是给人看的文本。 */
    private fun cleanCell(raw: String): String = raw
        .replace(TAG_RE, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(SPACE_RE, " ")
        .trim()

    // ---------------------------------------------------------------- 港股（腾讯，税后）

    /**
     * 解析腾讯港股 K 线里的分红信息（纯函数）。
     * 每条 K 线的第 7 个元素是对象：`{cqr, paixiri, FHcontent, HGcontent...}`；
     * `HGcontent`（回购）不是分红，忽略。
     *
     * 税后口径：票面 × (1 − 20%)，见 [HK_DIVIDEND_TAX]。
     */
    fun parseHkBonus(text: String, symbol: String): List<Dividend> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val data = root["data"] as? JsonObject ?: return emptyList()
        val node = data[symbol] as? JsonObject ?: return emptyList()
        val kline = (node["qfqday"] ?: node["day"]) as? JsonArray ?: return emptyList()

        val out = mutableListOf<Dividend>()
        kline.forEach { rowEl ->
            val row = rowEl as? JsonArray ?: return@forEach
            val info = row.getOrNull(6) as? JsonObject ?: return@forEach
            val cqr = info.str("cqr")
            val fh = info.str("FHcontent")
            if (cqr.isEmpty() || fh.isEmpty()) return@forEach
            val parsed = parseHkDividendText(fh)
            if (parsed.isEmpty()) return@forEach
            // 同日可能多笔（如 汇丰：季度息 + 特别息）→ 合并为一条，每股金额相加
            val totalHkd = parsed.sumOf { it.first }
            val gross = totalHkd
            val net = gross * (1 - HK_DIVIDEND_TAX)
            out += Dividend(
                securitySymbol = symbol,
                exDate = cqr.take(10),
                payDate = info.str("paixiri").take(10).ifEmpty { null },
                cashPerShare = net,
                currency = parsed.first().second.name,
                taxNote = "税后（已扣 ${(HK_DIVIDEND_TAX * 100).toInt()}% 红利税）",
                note = fh.trim().trimEnd(';'),
            )
        }
        return out.sortedBy { it.exDate }
    }

    /**
     * 解析港股分红文本（纯函数），返回 `[(每股金额, 币种)]`。
     *
     * 样例：
     *  - `末期息5.3港元;`
     *  - `末期息0.206人民币;相当于0.22177484港元;`
     *  - `第一季度息0.1美元;相当于0.780688港元;第一季度特别息0.21美元;相当于1.639445港元;`
     *
     * 规则：抓「…息<数字><币种>」；若其后紧跟「相当于<数字>港元」则**优先用港元等值**
     * （港股按港元记账，省一次折算）。
     */
    fun parseHkDividendText(text: String): List<Pair<Double, HkCurrency>> {
        val out = mutableListOf<Pair<Double, HkCurrency>>()
        // ⚠️ 两个坑（单测都抓到过）：
        //   1) 不能按 ';' 切段 —— `末期息0.206人民币;相当于0.22177484港元;` 中
        //      「相当于…港元」在后一段，切段后就找不到了；
        //   2) 不能用 `[^;]*` 做尾串 —— 同上原因。
        //   解法：以「息」字切笔（"相当于…"不含"息"，自然并入前一笔），再在**整笔文本**里找港元等值。
        val parts = text.split(HK_SPLIT_RE).filter { it.contains("息") }
        parts.forEach { seg ->
            val m = HK_AMOUNT_RE.find(seg)
                ?: return@forEach
            val amount = m.groupValues[1].toDoubleOrNull() ?: return@forEach
            val cur = when (m.groupValues[2]) {
                "港元", "港币" -> HkCurrency.HKD
                "人民币" -> HkCurrency.CNY
                else -> HkCurrency.USD
            }
            // 「相当于 X 港元」——若存在，转成港元口径（金额 = X），省一次折算
            if (cur != HkCurrency.HKD) {
                val eq = HK_EQUIV_RE.find(seg)
                val hkd = eq?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                if (hkd != null && hkd > 0) {
                    out += hkd to HkCurrency.HKD
                    return@forEach
                }
            }
            out += amount to cur
        }
        return out
    }

    // ---------------------------------------------------------------- 美股（东财 F10，税前票面）

    /**
     * 解析美股分红派息（东财 `RPT_USF10_INFO_DIVIDEND`，**纯函数**，老周 2026-09-30）。
     *
     * 为什么不能复用港股那条路（[parseHkBonus]）：**腾讯美股 K 线里没有分红字段** ——
     * 实测 `usfqkline` 每根只有「日期/开/收/高/低/量」6 项，而港股 `hkfqkline` 才带
     * `cqr`/`FHcontent`。所以美股只能另找东财 F10 的独立美元股息报表。
     *
     * 实测字段（AAPL.O）：`ASSIGN_TYPE`=`Cash` · `PLAN_EXPLAIN`=`每1股派0.27美元股息` ·
     * `EQUITY_RECORD_DATE` 权益登记日 · `BONUS_PAY_DATE` 派息日 · `EX_DIVIDEND_DATE` 除权日。
     *
     * 口径：票面**税前**（不扣美国预扣税 —— 各国/各券商代扣规则不一，App 不臆造税率）。
     * 只产出现金股息；拆股/并股（`ASSIGN_TYPE` 非 Cash）忽略。
     */
    fun parseUsBonus(text: String, symbol: String): List<Dividend> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val arr = (root["result"] as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val type = o.str("ASSIGN_TYPE")
            // 空值不拦（老数据可能缺字段），只明确排除非现金方案（Split 等不动账）
            if (type.isNotEmpty() && !type.contains("Cash", ignoreCase = true)) return@mapNotNull null
            val record = o.str("EQUITY_RECORD_DATE").take(10).takeIf { it.length == 10 }
            val exDate = o.str("EX_DIVIDEND_DATE").take(10).takeIf { it.length == 10 }
                ?: record ?: return@mapNotNull null
            val plan = o.str("PLAN_EXPLAIN")
            val m = US_PLAN_RE.find(plan) ?: return@mapNotNull null
            val amount = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            if (amount <= 0) return@mapNotNull null
            Dividend(
                securitySymbol = symbol,
                exDate = exDate,
                recordDate = record,
                payDate = o.str("BONUS_PAY_DATE").take(10).takeIf { it.length == 10 },
                cashPerShare = amount,
                currency = when (m.groupValues[2]) {
                    "港元" -> "HKD"
                    "人民币" -> "CNY"
                    else -> "USD"
                },
                taxNote = "税前（票面 · 未扣美国预扣税）",
                note = plan,
            )
        }.toList().sortedBy { it.exDate }
    }

    /** 「每1股派0.27美元股息」→ 抓 `0.27` + 币种 */
    private val US_PLAN_RE = Regex("""派\s*([0-9]+(?:\.[0-9]+)?)\s*(美元|港元|人民币)""")

    // ---------------------------------------------------------------- JSON 取值辅助

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it != "null" } ?: ""

    private fun JsonObject.dbl(key: String): Double =
        str(key).toDoubleOrNull() ?: 0.0
}
