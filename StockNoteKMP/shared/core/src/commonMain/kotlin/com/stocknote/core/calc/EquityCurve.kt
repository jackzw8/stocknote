package com.stocknote.core.calc

import com.stocknote.core.model.Transaction

/**
 * 资产曲线（REQ-VIEW-05）与最大回撤（REQ-ANA-01 真值）—— M4 数据真值的核心纯函数。
 *
 * 口径（技术说明书 5.2 + 5.12）：
 *   总资产(t) = 现金余额(t) + 持仓市值(t)
 *   现金余额(t) = **当前可用现金** − Σ(t 时点之后发生的现金流事件)
 *     —— 以「当前可用现金」为锚**反推历史**：起点现金 = 当前现金 − 全部事件，逐日正序累加，
 *        终点自然回到当前现金，从而与统计页「可用现金」口径**一致**。
 *        事件 = 交易买卖净额（买入 −价×量−费 / 卖出 +价×量−费）+ 出入金。
 *   持仓市值(t) = Σ 持仓份数(t) × 当日收盘(t) × 原币→本位币汇率；无收盘价的日期顺延最近一次收盘价
 *
 * 覆盖范围受限于历史收盘可得窗口（腾讯 K 线最多拉 N 天）；
 * 曲线起点 = max(首笔交易日, 收盘数据起点)。
 */
object EquityCurve {

    /** 一天的资产点。date = yyyy-MM-dd */
    data class Point(
        val date: String,
        val totalAsset: Double,
        /**
         * 当日**各标的的盈亏贡献**（本位币；key = securityId，无盈亏的标的不出现）。
         *
         * 口径（老周 2026-10-01，盈亏日历「点某天看明细」）：
         * ```
         * 盈亏(t) = 市值(t) − 市值(t−1) + 当日该标的现金净流入
         * ```
         * 现金净流入**买入为负**（付钱出去）、**卖出为正**（收回钱），
         * 因此它恰好抵消掉「买卖本身造成的市值变化」，剩下的就是**纯价格 + 汇率波动**。
         * 自校验：价格不动时纯买入 → `+买入额 − 买入额 = 0` ✓；
         *        价格涨 10% → `市值(t−1) × 10%` ✓；等价卖出 → `−市值(t−1) + 卖出所得 = 0` ✓。
         *
         * ⚠️ **不含现金分红**：分红在数据里只按「除权日 + 金额」记录、**没有挂标的**，
         * 拆不到某一只票上，所以归入「现金流水」那一段展示（但计入当日/当月总盈亏）。
         * 因此 `Σ pnlBySecurity = 当日盈亏 − 当日现金分红`。
         *
         * 默认空 Map，兼容既有调用方与单测。
         */
        val pnlBySecurity: Map<String, Double> = emptyMap(),
    )

    data class Drawdown(val maxDrawdown: Double, val peakDate: String, val troughDate: String)

    /**
     * 收盘价陈旧上限（自然日）。最近一条收盘价距目标日超过该值即视为「无有效行情」，
     * 不再顺延使用。用于挡住残缺/远古数据（例如某标的美股接口只回了 2011 年的残行）
     * 被顺延到当下参与估值。正常停牌一般不超过此天数。
     */
    // M17 修复（2026-09-27）：90 → **365** 天。
    // 此前停牌/停更超过 90 天的持仓**市值按 0 计**，曲线会出现断崖（报告 M17 指出的问题）。
    // 放宽到一年，覆盖绝大多数停牌情形；**仍保留上限**是为了挡住「远古残价被无限顺延」——
    // 注释里记录过美股 2011 残行的真实事故，那条防线不能拆。
    private const val MAX_STALE_DAYS = 365L

    /**
     * @param transactions 全部交易流水（现金等价物交易也计入——它们是持仓换现金）
     * @param cashFlows    出入金 (date, signedAmount)（存入正、取出负，折算本位币后）
     * @param currentCash  当前可用现金（锚点）。曲线各时点现金 = 当前现金 − 其后发生的事件；
     *                     故最后一点必等于当前现金，与统计页「可用现金」口径一致。
     * @param closesBySecurityId **securityId** -> [(date, close)]（按日期升序）。
     *        注意 key 必须是 securityId —— 与持仓时间线的 key 一致；用 symbol 会取不到价、市值恒为 0。
     * @return 按日期升序的资产曲线
     */
    /**
     * **送股 / 配股事件**（H3 修复，2026-09-28）：除权日 + 标的 + **股数增量**（正数 = 加股）。
     *
     * 为什么需要：`PositionCalculator.replay` 正确地把 BONUS 送股 / RIGHTS 配股计入了持仓，
     * 但资产曲线的持仓份数此前**只按交易流水累加** → 10 送 10 之后曲线仍按**原股数**估值，
     * 叠加除权日收盘价同步下调，表现为「总资产莫名其妙掉一半」，用户会当成数据丢失。
     * 股数规则与 `PositionCalculator.applyDividend` 保持一致（BONUS → +bonusShares；
     * RIGHTS → +quantity，且**都要求当时有持仓**）。
     */
    data class ShareEvent(val date: String, val securityId: String, val deltaQty: Double)

    fun build(
        transactions: List<Transaction>,
        cashFlows: List<Pair<String, Double>>,
        currentCash: Double,
        closesBySecurityId: Map<String, List<Pair<String, Double>>>,
        /** securityId -> 原币→本位币汇率；缺省 1.0（等同本位币）。外币持仓必须折算后再相加。 */
        rateToBase: Map<String, Double> = emptyMap(),
        /**
         * securityId -> **逐日**汇率序列 [(生效日, 汇率)]，按日期升序。
         *
         * ⚠️ 老周 2026-09-29（真机对账）：外币持仓的市值与交易现金流要按**当日**汇率折算 ——
         * 此前整条曲线统一用"最新汇率"，与券商"按当日汇率"口径对不上：
         * 实测腾讯 1600 股 2026-09-01，本应用 −15,850 vs 券商 −15,998，差 148 元（0.93%）。
         * 该日之前查不到汇率时**顺延用最早一条**，整条序列都为空才回退 [rateToBase]。
         */
        rateSeriesBySecurityId: Map<String, List<Pair<String, Double>>> = emptyMap(),
        /**
         * 现金分红事件 (date, amount)，REQ-ACC-04。
         * 分红是**现金流入**（账户收到钱），不计入持仓市值变化——
         * 除权除息日股价下跌已体现在收盘价里，若再扣一遍会重复计算。
         * 送股/转股（非现金）不改变现金，**改由 [shareEvents] 表达股数变化**。
         */
        dividends: List<Pair<String, Double>> = emptyList(),
        /** **送股 / 配股事件**（H3，2026-09-28）：改变持仓份数，必须进时间线，见 [ShareEvent]。 */
        shareEvents: List<ShareEvent> = emptyList(),
        /**
         * 只算**最近 N 天**（老周 2026-09-23：「资产曲线算最近一年的」→ 调用方传 365）。
         * null = 尽可能长（受历史收盘可得窗口限制）。
         *
         * 注意这是从**终点往回**截断输出区间，不改变算法：
         * 现金锚反推与全量事件流照旧，起点之前的事件由 [beforeStart] 一次性补回，
         * 因此曲线末点仍等于当前可用现金（与统计页口径一致）。
         */
        lookbackDays: Int? = null,
    ): List<Point> {
        // 1) 按时间顺序做一次「带截断」的重放，同时产出：逐日现金流事件 + 每日持仓快照。
        //    截断口径与 PositionCalculator.replay 完全一致（卖出超仓按实际持仓成交），
        //    否则会出现两个互相"抵消"的假象：卖出数量虚高 → 现金虚高、持仓变负 → 市值虚低。
        /**
         * 一笔现金事件。
         * @param securityId 该事件**归属的标的** —— 只有交易有；出入金与分红是组合级（null）。
         *   盈亏日历要按标的拆解当日盈亏，靠它把「买入付出去的钱」抵回对应标的，
         *   否则买入当天会被算成一大笔亏损。
         */
        data class CashEvent(val date: String, val amount: Double, val securityId: String? = null)
        data class Lot(val date: String, val qtyBySymbol: Map<String, Double>)

        val orderedTx = transactions.sortedWith(
            compareBy({ it.tradeDate }, { it.seq }, { it.id }),
        )
        val held = HashMap<String, Double>()
        val txCashEvents = mutableListOf<CashEvent>()
        val qtyByDate = LinkedHashMap<String, Map<String, Double>>()

        // ⚠️ 交易现金流必须**折本位币**再入事件流（2026-09-20 修复）。
        // 此前这里直接用 `price × qty`（原币）当人民币加：港股按面值多算、美股按面值少算，
        // 而 `currentCash`（锚点）与持仓市值都是**折算后**的 → 事件总和与锚点对不上，
        // 反推出的起点现金凭空多出一个偏差，整条曲线整体偏移。
        // 实测：港股 200×385 + 美股 60×305 两笔，事件总和比锚点高 97,001.61，
        // 曲线被压到 -6.4 万（总资产实际 +134 万）。与 marketValue 用同一个 rateToBase。
        /**
         * 某标的在**某一天**的「原币 → 本位币」汇率（[rateSeriesBySecurityId] 优先）。
         * 二分取 <= date 的最新一条；该日之前无记录则顺延到最早一条（汇率表往往只有一条最新值）。
         */
        fun rateOn(securityId: String, date: String): Double {
            val series = rateSeriesBySecurityId[securityId]
            if (!series.isNullOrEmpty()) {
                var lo = 0
                var hi = series.size - 1
                var best: Double? = null
                while (lo <= hi) {
                    val mid = (lo + hi) / 2
                    if (series[mid].first <= date) {
                        best = series[mid].second
                        lo = mid + 1
                    } else {
                        hi = mid - 1
                    }
                }
                return best ?: series.first().second
            }
            return rateToBase[securityId] ?: 1.0
        }

        fun toBase(securityId: String, amount: Double, date: String): Double =
            amount * rateOn(securityId, date)

        orderedTx.forEach { tx ->
            val cur = held[tx.securityId] ?: 0.0
            val next: Double
            val cashDelta: Double
            when (tx.side) {
                com.stocknote.core.model.TradeSide.BUY -> {
                    next = cur + tx.quantity
                    // 折算用**成交日**汇率（与市值同口径，见 rateSeriesBySecurityId 的说明）
                    // ⚠️ 印花税一并计入（REQ-ACC-17）：A 股买入为 0、港股买入要扣
                    cashDelta = toBase(tx.securityId, -(tx.price * tx.quantity + tx.fee + tx.stampDuty), tx.tradeDate)
                }

                com.stocknote.core.model.TradeSide.SELL -> {
                    val sellQty = if (tx.quantity > cur) cur else tx.quantity   // 超卖截断
                    next = cur - sellQty
                    // 同买入：按**成交日**汇率折算；印花税一并扣减（A 股卖出、港股双向都有）
                    cashDelta = toBase(tx.securityId, tx.price * sellQty - tx.fee - tx.stampDuty, tx.tradeDate)
                }

                com.stocknote.core.model.TradeSide.CAPITALIZE -> {
                    // 利润转增资本（REQ-ACC-16）：**不动股数、不动现金** → 资产曲线天然不受影响。
                    // 显式写出而不是靠 else 兜底：它既不是买也不是卖，一旦被当卖出处理
                    // 就会凭空产生一笔现金流入，曲线整条偏移（这正是要防的错）。
                    next = cur
                    cashDelta = 0.0
                }
            }
            held[tx.securityId] = next
            txCashEvents += CashEvent(tx.tradeDate, cashDelta, tx.securityId)
            qtyByDate[tx.tradeDate] = held.toMap()
        }

        // ⚠️ H3 修复（2026-09-28）：**送股 / 配股要进持仓时间线**。
        // 此前只按交易累加 → 10 送 10 后曲线仍按原股数估值 → 曲线市值腰斩。
        // 与重放同规则：只在**当时仍有持仓**时才生效（0 持仓不产生送股/配股）；
        // 同一天的送股排在当日交易之后（除权日在交易结算之后，差异仅限当日盘中，可接受）。
        shareEvents.sortedWith(compareBy({ it.date }, { it.securityId })).forEach { ev ->
            val cur = held[ev.securityId] ?: 0.0
            if (cur > 1e-9 && ev.deltaQty > 0.0) {
                held[ev.securityId] = cur + ev.deltaQty
                qtyByDate[ev.date] = held.toMap()
            }
        }

        val events = buildList {
            addAll(txCashEvents)
            cashFlows.forEach { (d, amt) -> add(CashEvent(d, amt)) }
            // 现金分红 = 现金流入（REQ-ACC-04）；与出入金同为「外部现金变动」
            dividends.forEach { (d, amt) -> add(CashEvent(d, amt)) }
        }.groupBy { it.date }.mapValues { (_, list) -> list.sumOf { it.amount } }

        // 逐日「各标的」的现金净流入（date -> securityId -> 金额；买入为负、卖出为正）。
        // 供下面按标的拆解当日盈亏用：盈亏 = 市值变化 + 当日现金净流入，后者正好抵消买卖本身。
        // ⚠️ 只取带 securityId 的（即交易）—— 出入金与分红是组合级，不参与单只标的拆解。
        val txCashBySecDate: Map<String, Map<String, Double>> = txCashEvents
            .filter { it.securityId != null }
            .groupBy { it.date }
            .mapValues { (_, list) ->
                list.groupBy({ it.securityId!! }, { it.amount }).mapValues { (_, a) -> a.sum() }
            }

        // 2) 持仓快照时间线：轴 = 全部事件日期；非交易事件日沿用上一快照
        // ⚠️ H3（2026-09-28）：**必须把 shareEvents 的日期也放进来** ——
        // 否则送股当天不在轴上，估值时会沿用送股前的快照（原股数）→ 曲线仍腰斩。
        val allDates = (
            transactions.map { it.tradeDate } +
                cashFlows.map { it.first } +
                dividends.map { it.first } +
                shareEvents.map { it.date }
            ).distinct().sorted()
        val qtyTimeline = mutableListOf<Lot>()
        var snapshotQty: Map<String, Double> = emptyMap()
        allDates.forEach { d ->
            qtyByDate[d]?.let { snapshotQty = it }
            qtyTimeline.add(Lot(d, snapshotQty))
        }

        // 3) 日期轴：起点 = max(最早交易/出入金日期, 最早收盘日期)——裁掉无持仓活动的空白期；
        //    终点 = 最晚收盘日期。连续自然日——估值曲线每日一点，无收盘价的日期顺延最近一次收盘价
        // 必须 sorted()：flatMap 的顺序取决于标的遍历顺序，不排序时 first()/last() 并非真正的最早/最晚；
        // 曾出现某标的美股残数据（2011 年）被当成「最晚收盘日」→ 区间倒挂 → 整条曲线为空。
        val closeDates = closesBySecurityId.values.flatMap { it.map { p -> p.first } }.distinct().sorted()
        val eventDates = allDates.distinct().sorted()
        if (eventDates.isEmpty() || closeDates.isEmpty()) return emptyList()
        val closeStart = closeDates.first()
        val eventStart = eventDates.first()
        // 起点取两者较大者（有交易但无收盘价时从收盘起点开始；有收盘但交易更晚时从交易起点开始）
        val start = if (eventStart > closeStart) eventStart else closeStart
        val end = closeDates.last()
        // 最近 N 天截断（老周 2026-09-23）：起点取「max(自然起点, 终点 − N 天)」。
        // 只裁输出区间——起点之前的事件仍在 events 里，由下面 beforeStart 一次性补回，
        // 因此曲线末点依旧等于当前可用现金，不会因截断而整体偏移。
        val effectiveStart =
            lookbackDays?.let { maxOf(start, CivilDate.minusDays(end, it)) } ?: start
        val startDay = CivilDate.toEpochDay(effectiveStart)
        val endDay = CivilDate.toEpochDay(end)
        val timeline = if (startDay > endDay) emptyList() else (startDay..endDay).map { day ->
            val ymd = CivilDate.fromEpochDay(day)
            CivilDate.isoOf(ymd.year, ymd.month, ymd.day)
        }
        if (timeline.isEmpty()) return emptyList()

        // 4) 收盘价顺延查找：每标的建一个 date->close 升序表，二分取 <= date 的最近值。
        //    key 为 securityId（与 qtyTimeline 一致）；并加陈旧度上限，避免顺延到远古残价。
        fun closeOn(securityId: String, date: String): Double? {
            val series = closesBySecurityId[securityId] ?: return null
            var lo = 0; var hi = series.size - 1
            var best: Double? = null
            var bestDate: String? = null
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (series[mid].first <= date) {
                    bestDate = series[mid].first; best = series[mid].second; lo = mid + 1
                } else hi = mid - 1
            }
            if (best == null || bestDate == null) return null
            return if (CivilDate.daysBetween(bestDate, date) <= MAX_STALE_DAYS) best else null
        }

        // 5) 逐点计算。现金以「当前可用现金」为锚反推：起点 = 当前现金 − 全部现金流事件，
        //    之后按日期正序累加，终点自然回到当前现金（与统计页口径一致）。
        //
        // ⚠️ 起点**之前**发生的事件必须一次性补回（2026-09-20 修复）：
        //    `events` 是全量的，但逐日累加只走 `timeline`（从 start 起）。
        //    早于 start 的事件（例如比收盘窗口更早的一笔入金）既不在起点、也永远不会被加上，
        //    于是末点回不到当前现金 —— 曲线整体少掉那笔金额。
        //    实测：一笔 2026-02-02 的 50 万入金早于 2026-02-11 的收盘起点，
        //    曲线末点 ¥842,209.98，比统计页总资产 ¥1,342,209.98 恰好少 50 万。
        //    ⚠️ 截断（lookbackDays）时必须用 **effectiveStart** 而不是 start 来做这件事：
        //    否则 start..effectiveStart 之间的事件既不在初值、也不在 timeline 循环里，
        //    会凭空丢失 → 末点对不上当前现金（与下面那段是同一类错）。
        val beforeStart = events.entries
            .filter { it.key < effectiveStart }
            .sumOf { it.value }
        // M18 修复（2026-09-27）：与 beforeStart **对称** —— 晚于终点 `end` 的事件也必须补回。
        // `end` 取的是**最晚收盘日**（`:148`），若某笔事件晚于它（盘中查看曲线、行情未更新、
        // 或录入了未来日期的交易），该事件既不在 beforeStart、也不会被下面的 timeline 循环累加，
        // 但它在 `Σ全部事件` 里被扣掉了 → `末点现金 = currentCash − 该事件` ≠ 当前可用现金，
        // 与「最后一点必等于当前现金、与统计页可用现金一致」的口径相违。
        val afterEnd = events.entries
            .filter { it.key > end }
            .sumOf { it.value }
        var cash = currentCash - events.values.sum() + beforeStart + afterEnd
        val points = mutableListOf<Point>()
        var idx = 0 // qtyTimeline 指针
        var lastTotal: Double? = null
        // 昨日各标的市值（本位币）—— 算「各标的当日盈亏」要拿它做差分
        var prevMvBySec: Map<String, Double> = emptyMap()
        timeline.forEach { d ->
            // 现金：加上该日（含）之前的所有事件增量
            events[d]?.let { cash += it }
            // 持仓：取 <= d 的最近份数
            while (idx < qtyTimeline.size && qtyTimeline[idx].date <= d) idx++
            val qtyMap = if (idx == 0) emptyMap() else qtyTimeline[idx - 1].qtyBySymbol
            var marketValue = 0.0
            // 顺带按标的留存市值（供盈亏日历「点某天看明细」拆解，老周 2026-10-01）
            val mvBySec = HashMap<String, Double>()
            qtyMap.forEach { (secId, q) ->
                val close = closeOn(secId, d)
                // 外币持仓（港股/美股）必须折算到本位币再相加，否则 HK$/US$ 会按面值当成人民币计入。
                // ⚠️ 用**当日**汇率（rateOn），与券商的逐日盈亏口径一致（老周 2026-09-29）。
                if (close != null) {
                    val v = q * close * rateOn(secId, d)
                    marketValue += v
                    mvBySec[secId] = v
                }
                // 无收盘价（如现金等价物无行情、或数据缺口）的持仓按 0 计，避免曲线跳变缺失
            }
            // 各标的当日盈亏 = 市值差分 + 当日该标的现金净流入（买入为负 → 抵消买入造成的市值增加）。
            // 详见 [Point.pnlBySecurity] 的口径说明与自校验。三者取并集，覆盖
            // 「今天新买入/今天清仓」这类只有单侧市值的标的。
            val pnlBySec = HashMap<String, Double>()
            val secIds = mvBySec.keys + prevMvBySec.keys + (txCashBySecDate[d]?.keys ?: emptySet())
            secIds.forEach { secId ->
                val delta = (mvBySec[secId] ?: 0.0) - (prevMvBySec[secId] ?: 0.0) +
                    (txCashBySecDate[d]?.get(secId) ?: 0.0)
                // 小于 0.005 元的视为噪声，不往明细里塞（避免一堆 ±0.00 的行）
                if (kotlin.math.abs(delta) >= 0.005) pnlBySec[secId] = delta
            }
            prevMvBySec = mvBySec
            val total = cash + marketValue
            points.add(Point(d, total, pnlBySec))
            lastTotal = total
        }
        return points
    }

    /**
     * **逐日盈亏**（盈亏日历 REQ-VIEW-06 的口径，老周 2026-09-29 修正）：
     *
     * `delta(t) = 总资产(t) − 总资产(t−1) − 当日出入金净额`
     *
     * ⚠️ **必须剔除出入金**：入金只是把本金搬进账户，不是赚的钱。
     * 真机实测（老周 2026-09-01 入金 19,000）：剔除前日历显示 **+2,212**，
     * 剔除后与券商当日盈亏 **−16,986** 口径一致 —— 差的正是那笔入金。
     *
     * 不剔除的其它项：
     *  - **现金分红**：真实收益，保留（与券商一致）；
     *  - **买卖**：现金减、市值增，净额为零，无需处理。
     *
     * @param cashFlowsByDate 当日**出入金净额**（折本位币，存入为正）；空 = 不剔除（兼容旧调用）。
     * @return date -> 当日盈亏，**按日期升序**（LinkedHashMap），第一个点无前值故不产出。
     */
    fun dailyPnl(
        points: List<Point>,
        cashFlowsByDate: Map<String, Double> = emptyMap(),
    ): LinkedHashMap<String, Double> {
        val map = LinkedHashMap<String, Double>()
        for (i in 1 until points.size) {
            val date = points[i].date
            map[date] = points[i].totalAsset - points[i - 1].totalAsset - (cashFlowsByDate[date] ?: 0.0)
        }
        return map
    }

    /** 展示粒度（REQ-VIEW-05：日/周/月/年） */
    enum class Granularity(val label: String) { DAY("日"), WEEK("周"), MONTH("月"), YEAR("年") }

    /**
     * 粒度聚合（REQ-VIEW-05）：按周/月/年取**区间内最后一个点**（期末值），
     * 与"资产曲线是时点序列"的语义一致（不能用均值，否则与快照对不上）。
     */
    fun resample(points: List<Point>, granularity: Granularity): List<Point> {
        if (granularity == Granularity.DAY || points.size < 2) return points
        val bucket = { date: String ->
            when (granularity) {
                Granularity.DAY -> date
                Granularity.WEEK -> {
                    // ISO 周：用 epochDay 周一为界
                    val ymd = CivilDate.parseIso(date)
                    val d = CivilDate.toEpochDay(ymd.year, ymd.month, ymd.day)
                    val weekStart = d - ((d + 3) % 7)
                    CivilDate.fromEpochDay(weekStart).let { CivilDate.isoOf(it.year, it.month, it.day) }
                }
                Granularity.MONTH -> date.take(7)
                Granularity.YEAR -> date.take(4)
            }
        }
        val out = LinkedHashMap<String, Point>()
        points.forEach { p ->
            val key = bucket(p.date)
            // 后写入覆盖前写入 → 保留区间内最后一个点
            out[key] = p
        }
        return out.values.toList()
    }

    /**
     * 最大回撤：max((峰值 − 谷值) / 峰值)，谷值在峰值之后。
     * 空曲线或全程无下跌返回 0。
     */
    fun maxDrawdown(points: List<Point>): Drawdown {
        if (points.size < 2) return Drawdown(0.0, "", "")
        var peak = points[0].totalAsset
        var peakDate = points[0].date
        var best = 0.0
        var bestPeak = peakDate
        var bestTrough = points[0].date
        for (p in points) {
            if (p.totalAsset > peak) {
                peak = p.totalAsset
                peakDate = p.date
            }
            val dd = if (peak > 0) (peak - p.totalAsset) / peak else 0.0
            if (dd > best) {
                best = dd
                bestPeak = peakDate
                bestTrough = p.date
            }
        }
        return Drawdown(best, bestPeak, bestTrough)
    }
}
