package com.stocknote.core.calc

import com.stocknote.core.format.Format
import com.stocknote.core.model.Market
import com.stocknote.core.model.Security

/**
 * **每手股数规则 + 整手校验**（老周 2026-09-28）。
 *
 * 规则（老周定「全市场都校验」，按各市场自己的规则落地）：
 *  - **A股 / ETF**：`100` —— 一手 100 股/份；
 *  - **港股**：**真实每手**（⚠️ 不固定：腾讯控股 100 / 小米 200 / 中国移动 500 / 建设银行 1000），
 *    自腾讯行情 `qt.gtimg.cn/q=hkXXXXX` 的**第 61 个 `~` 分隔字段**取回并存在 `security.lot_size`；
 *    取不到时才退回 100 兜底（老周 2026-09-28 指出该字段 —— 此前我以为拿不到，是我错了）；
 *  - **美股**：`1` —— 没有"手"的概念，1 股即可成交 → 不做整数倍约束；
 *  - **场外基金**：`1` —— 按份额申赎 → 同理不约束。
 *
 * ⚠️ 与老周原始选择（"全市场都校验" + "100 的整数倍"）的**唯一偏差点**是美股/场外基金：
 * 一律按 100 会**误拦美股正常买单**（美股 1 股起），所以这里按"各市场自己的规则"处理。
 */
object LotRule {

    /** 该市场「一手」的单位数；`1` = 无整手约束。 */
    fun lotSizeOf(market: Market): Double = when (market) {
        Market.A_SHARE, Market.ETF, Market.HK -> 100.0
        Market.US, Market.FUND -> 1.0
    }

    /**
     * **按标的**取每手（老周 2026-09-28）：优先用该标的自己存的**真实值**
     * （港股从腾讯行情第 61 字段取回，如 100 / 200 / 500 / 1000），没有才按市场规则兜底。
     *
     * ⚠️ 港股的真实每手差异很大，只用市场默认的 100 会**误判整手** —— 所以有真实值就必须用它。
     */
    fun lotSizeOf(security: Security): Double =
        security.lotSize?.takeIf { it > 0 } ?: lotSizeOf(security.market)

    /**
     * 数量输入框旁的提示文案，如 `100 股/手`。
     * 无整手约束的市场（美股/场外基金）返回 null —— 不显示提示，免得误导。
     */
    fun hintOf(market: Market): String? {
        val lot = lotSizeOf(market)
        return if (lot <= 1.0) null else "${lot.toLong()}${market.quantityUnit}/手"
    }

    /** 按市场 + **显式每手**（界面持有标的值但没建 Security 对象时用；lotSize 为 null 则按市场兜底）。 */
    fun hintOf(market: Market, lotSize: Double?): String? {
        val lot = lotSize?.takeIf { it > 0 } ?: lotSizeOf(market)
        return if (lot <= 1.0) null else "${lot.toLong()}${market.quantityUnit}/手"
    }

    /** 按标的的提示文案（用真实每手，如港股 500股/手）；无整手约束返回 null。 */
    fun hintOf(security: Security): String? {
        val lot = lotSizeOf(security)
        return if (lot <= 1.0) null else "${lot.toLong()}${security.market.quantityUnit}/手"
    }

    /** 按市场的整手校验（用市场默认每手）。 */
    fun check(quantity: Double, market: Market, enabled: Boolean): String? =
        check(quantity, market, lotSizeOf(market), enabled)

    /** 按标的的整手校验（**优先真实每手**）。 */
    fun check(quantity: Double, security: Security, enabled: Boolean): String? =
        check(quantity, security.market, lotSizeOf(security), enabled)

    /** 按市场 + **显式每手**的整手校验（界面用；lotSize 为 null 则按市场兜底）。 */
    fun check(quantity: Double, market: Market, lotSize: Double?, enabled: Boolean): String? =
        check(quantity, market, lotSize?.takeIf { it > 0 } ?: lotSizeOf(market), enabled)

    /**
     * 整手校验：返回**错误文案**（null = 通过）。
     *
     * @param enabled 设置里的「整手校验」开关（老周：**缺省开**）。关掉则一律放行。
     */
    private fun check(quantity: Double, market: Market, lot: Double, enabled: Boolean): String? {
        if (!enabled) return null
        if (lot <= 1.0) return null
        if (quantity <= 0) return null          // 非正数由既有的「数量非法」校验负责
        val lotLong = lot.toLong()
        // 浮点余数容差：100.0000001 这种录入不该被误判
        val remainder = quantity % lot
        val offLot = remainder > 1e-6 && remainder < lot - 1e-6
        if (!offLot) return null
        return if (quantity < lot) {
            // 零星股（不足一整手）
            "不足一手：${market.label}每手 $lotLong${market.quantityUnit}，零星股请核对" +
                "（可在「设置」里关闭整手校验）"
        } else {
            "应为 $lotLong${market.quantityUnit}的整数倍：${market.label}每手 $lotLong" +
                "${market.quantityUnit}（当前 ${trimQty(quantity)}）"
        }
    }

    private fun trimQty(v: Double): String {
        val s = Format.fixedPlain(v, 2).trimEnd('0').trimEnd('.')
        return s.ifEmpty { "0" }
    }
}
