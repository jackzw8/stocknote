package com.stocknote.core.calc

/**
 * 净值曲线构建。
 *
 * 采用**时间加权**口径（TWR）：把外部资金进出的影响剥离，只看「同样的钱被管理得怎么样」。
 * 单日收益率 r_i = (MV_i - MV_(i-1) - netFlow_i) / (MV_(i-1) + netFlow_i)
 * 净值       NAV_i = NAV_(i-1) * (1 + r_i)，NAV_0 = 1.0
 *
 * 这样曲线不会因为某天一次性大额入金就被拉平，符合复盘的实际需求。
 */
object SeriesBuilder {

    /**
     * @param marketValues 逐日总资产（现金 + 持仓市值），升序
     * @param netFlows     逐日净外部流入（入金为正、出金为负），长度需与 marketValues 一致；
     *                     若为 null 表示期间无资金进出，此时净值与总资产成正比
     */
    fun timeWeightedNetValue(
        marketValues: List<Double>,
        netFlows: List<Double>? = null,
    ): List<Double> {
        if (marketValues.isEmpty()) return emptyList()
        val flows = netFlows ?: List(marketValues.size) { 0.0 }
        require(flows.size == marketValues.size) {
            "netFlows 长度(${flows.size})必须与 marketValues(${marketValues.size})一致"
        }

        val nav = ArrayList<Double>(marketValues.size)
        nav += 1.0

        var prevMv = marketValues.first()
        for (i in 1 until marketValues.size) {
            val mv = marketValues[i]
            val flow = flows[i]
            val denominator = prevMv + flow
            val r = if (denominator > 1e-9) (mv - prevMv - flow) / denominator else 0.0
            nav += nav[i - 1] * (1.0 + r)
            prevMv = mv
        }
        return nav
    }

    /**
     * 归一化：把任意正值序列缩放到首点 = 1.0。
     * 用于「与基准对比」这类展示——因为起点相同才能看出相对强弱。
     */
    fun normalize(values: List<Double>): List<Double> {
        val first = values.firstOrNull() ?: return emptyList()
        if (first == 0.0) return values
        return values.map { it / first }
    }

    /** 简单移动平均。窗口不足处填 null，交给上层决定是否断线绘制。 */
    fun movingAverage(values: List<Double>, window: Int): List<Double?> {
        require(window > 0) { "window 必须为正数" }
        if (values.isEmpty()) return emptyList()
        val result = ArrayList<Double?>(values.size)
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= window) sum -= values[i - window]
            result += if (i >= window - 1) sum / window else null
        }
        return result
    }
}
