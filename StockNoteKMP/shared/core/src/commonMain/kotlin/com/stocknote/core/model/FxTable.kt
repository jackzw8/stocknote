package com.stocknote.core.model

/**
 * 汇率换算表。
 *
 * M0 阶段使用固定汇率（与界面原型口径一致：HKD→CNY 0.92、USD→CNY 7.2）。
 * 正式的 FxRate 表（复合主键 currency + effective_date，支持历史快照）在 M4 接入，
 * 届时本对象退化为「找不到当日汇率时的兜底」。
 */
object FxTable {

    // ⚠️ 2026-09-17 调整（老周实测"港股现金扣减仍不对"的根因）：
    // 兜底值只在「fx_rate 表拉不到该币种」时生效。原 HKD 0.92 / USD 7.2 都偏在区间边缘，
    // 一旦兜底生效就会让外币交易的本位币金额明显偏大（港股多扣约 7%）。
    // 实测参考（腾讯外汇 whHKDCNY / whUSDCNY）：HKD ≈ 0.8546、USD ≈ 6.7071。
    const val HKD_TO_CNY: Double = 0.90
    const val USD_TO_CNY: Double = 7.1

    /**
     * 汇率统一保留 **4 位小数**（老周 2026-09-21 定口径）。
     *
     * 一切写库的路径（手动录入 / 启动自动更新 / 手动全量刷新 / 成交日汇率回填）都要先过这个函数，
     * 保证 **看到的 = 存进去的 = 参与计算的**：
     * 网络源常常给到 6~8 位（0.85362499），不收敛的话同一笔在详情页和列表会差几分钱，
     * 而且用户照着屏幕上 4 位手改后又会变成另一个值。
     */
    fun roundRate(rate: Double): Double =
        if (rate.isNaN() || rate.isInfinite()) rate
        else kotlin.math.round(rate * 10_000.0) / 10_000.0

    fun rate(from: Currency, to: Currency): Double = when {
        from == to -> 1.0
        from == Currency.HKD && to == Currency.CNY -> HKD_TO_CNY
        from == Currency.USD && to == Currency.CNY -> USD_TO_CNY
        from == Currency.CNY && to == Currency.HKD -> 1.0 / HKD_TO_CNY
        from == Currency.CNY && to == Currency.USD -> 1.0 / USD_TO_CNY
        from == Currency.HKD && to == Currency.USD -> HKD_TO_CNY / USD_TO_CNY
        from == Currency.USD && to == Currency.HKD -> USD_TO_CNY / HKD_TO_CNY
        else -> 1.0
    }

    fun convert(amount: Double, from: Currency, to: Currency): Double = amount * rate(from, to)

    /**
     * 带 FxRate 表的换算（M4 数据真值）。rates = 币种 code -> 兑 CNY 汇率（来自 fx_rate 表最新生效值）。
     * 某币种查不到时退回固定汇率兜底——宁可略偏，不出 NaN。
     */
    fun convertWith(amount: Double, from: Currency, to: Currency, rates: Map<String, Double>): Double {
        if (from == to) return amount
        val fromRate = if (from == Currency.CNY) 1.0
        else rates[from.code] ?: rate(from, Currency.CNY)
        val toRate = if (to == Currency.CNY) 1.0
        else rates[to.code] ?: rate(to, Currency.CNY)
        return amount * fromRate / toRate
    }

    // ---------------------------------------------------------------- 逐日汇率数据源（2026-10-01）

    /**
     * 币种 → **腾讯外汇日 K** 的行情符号 + 合理性中枢值；`null` = 该币种没有外汇行情。
     *
     * ## ⚠️⚠️ 这里推翻了此前一个错误前提（2026-10-01 老周对账）
     * `PortfolioRepository.equityCurve` 原先的注释写着：
     * > 「外币持仓市值按 fx_rate 表最新汇率折算 CNY（**历史逐日汇率无免费数据源**，
     * >   用最新汇率近似历史——在汇率波动平缓期误差可控）」
     *
     * **这个前提是错的**：腾讯外汇日 K（`whHKDCNY` / `whUSDCNY`）与股票 K 线走的是
     * **同一个** `ifzq.gtimg.cn/appstock/app/fqkline/get` 接口，**逐日收盘汇率完全拿得到**
     * （实测 400 根 ≈ 18 个月、2000 根 ≈ 8 年；⚠️ 3000 根会被接口拒绝、整批返回空）。
     *
     * 后果有多大：曲线只有「手工录入的那几条汇率」（老周真机实测：**只有最近 3 天**），
     * 于是 `EquityCurve.rateOn` 对「昨天」和「今天」返回**同一个值** → **汇率波动被整段抵消**，
     * 港股当日盈亏整整少掉那一块 —— 与券商差约 1%
     * （真机对账：腾讯 1600 股 9-01，本应用 −15,853.95 vs 券商 −15,997.91，差 143.96）。
     *
     * ⚠️ 符号**必须带 `wh` 前缀**：`HKDCNY` / `hkHKDCNY` / `usdCNY` 都是**无效**代码，
     * 接口会回 `v_pv_none_match="1"`，宽松正则还能从中抠出个 "1" 当成汇率写进库
     *（2026-09-16 真机踩过：1 HKD 被当成 ¥1.00）。
     *
     * @return 第二项是**合理性中枢值**，供 [isSaneRate] 做 ±40% 过滤（挡残缺/远古数据）。
     */
    fun fxQuoteOf(currency: Currency): Pair<String, Double>? = when (currency) {
        Currency.HKD -> "whHKDCNY" to 0.8
        Currency.USD -> "whUSDCNY" to 6.0
        else -> null
    }

    /** 汇率是否落在合理区间（±40%，以 [center] 为中枢）—— 挡残数据，宁可回退也不用可疑值。 */
    fun isSaneRate(rate: Double, center: Double): Boolean =
        rate.isFinite() && rate > center * 0.6 && rate < center * 1.4

    /**
     * 合并两路**逐日汇率**序列（各自按日期升序）：[primary]（腾讯外汇日 K）优先，
     * [fallback]（`fx_rate` 表手工录入）补齐它没有的日期。
     *
     * **为什么要两路并存**：
     *  - 日 K **连续覆盖**，但可能拉不到（离线 / 接口异常 / 被限流）；
     *  - 手工录入**精确可信**，但用户通常只录最近几天（老周实测：只有 3 条）。
     *
     * 合并后 `EquityCurve.rateOn` 的二分查找才有连续的逐日汇率可用 ——
     * **此前只有手工那几条，昨天与今天取到同一个汇率，汇率波动被整段抹掉**（本次修复的核心）。
     * 同一天两者都有时**以日 K 为准**（它是市场真实收盘价，手工值可能录错或只录了几天）。
     */
    fun mergeRateSeries(
        primary: List<Pair<String, Double>>,
        fallback: List<Pair<String, Double>>,
    ): List<Pair<String, Double>> {
        if (primary.isEmpty()) return fallback.sortedBy { it.first }
        if (fallback.isEmpty()) return primary.sortedBy { it.first }
        val byDate = HashMap<String, Double>(fallback.size + primary.size)
        fallback.forEach { (d, r) -> byDate[d] = r }
        primary.forEach { (d, r) -> byDate[d] = r } // 主源覆盖同日的手工值
        return byDate.entries.sortedBy { it.key }.map { it.key to it.value }
    }
}
