package com.stocknote.data.net

import com.stocknote.core.model.Market

/**
 * 由**标的代码**推断市场（CSV 导入建标时用）。
 *
 * 口径与联网搜索候选（[QuoteClient.parseSearch]）保持一致：沪市 `5` 开头 / 深市 `15·16·18`
 * 开头 → ETF。
 *
 * ⚠️ 三个必须踩住的坑（2026-09-20 老周报 bug 修复）：
 *
 *  1. **入参可能带市场前缀**。CSV 导出的「标的代码」列写的是 `sec.symbol`，形如 `sh511660`
 *     （长度 9），而不是裸 6 位。旧实现要求 `symbol.length == 6` 才判 ETF，
 *     于是带前缀的 ETF 全部**静默落进 else 被建成 A 股**。
 *  2. **深市不能只判 `1` 开头**。深市可转债 `123xxx` / `127xxx`、国债 `10xxxx`
 *     都会命中 `startsWith("1")`，被误判成 ETF。收紧为 `15/16/18` 三个基金段。
 *  3. **大小写**。手工填的 CSV 可能是 `SH600519`，统一转小写后再匹配。
 *
 * 注意本函数**只决定 `market` 标签**，不推断币种（CSV 导入的币种以文件里的币种列为准）。
 * 判定错误只影响展示分组（持仓页筛选 Tab / 占比图色段），不参与任何金额计算。
 */
internal object MarketGuess {

    /**
     * @param symbol 标的代码：可带 `sh` / `sz` / `bj` / `hk` / `us` 前缀，也可以是裸代码
     */
    fun of(symbol: String): Market {
        val s = symbol.trim().lowercase()
        if (s.isEmpty()) return Market.A_SHARE

        // 场外基金：本 App 用 `of` 前缀与 A股/场内基金区分（of000001 vs 平安银行 000001 同码不同物）
        // （老周 2026-09-20 加：场外基金也要能记账）
        if (s.startsWith("of")) return Market.FUND

        // 港股 / 美股识别与前缀无关，先判（它们的前缀不会被剥掉）
        if (s.startsWith("hk")) return Market.HK
        if (s.startsWith("us")) return Market.US

        // 剥掉 A 股市场前缀（导出形态 sh511660 / sz159915 / bj430047）
        val bare = if (s.startsWith("sh") || s.startsWith("sz") || s.startsWith("bj")) {
            s.substring(2)
        } else {
            s
        }

        // 只有 6 位数字才是 A 股 / 场内基金的合法代码形态，其余原样归 A 股
        if (bare.length != 6 || bare.any { !it.isDigit() }) return Market.A_SHARE

        return when {
            // 沪市场内基金：51/52/56/58 段 ETF，50 段为老封闭式基金；均以 5 开头
            bare.startsWith("5") -> Market.ETF
            // 深市场内基金：15 段 ETF/LOF、16 段 LOF、18 段封闭式基金
            // ⚠️ 不能放宽到整个 "1"，否则 123xxx 可转债、10xxxx 国债会被误判
            bare.startsWith("15") || bare.startsWith("16") || bare.startsWith("18") -> Market.ETF
            else -> Market.A_SHARE
        }
    }
}
