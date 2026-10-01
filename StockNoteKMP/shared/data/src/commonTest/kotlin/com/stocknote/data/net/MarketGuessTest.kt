package com.stocknote.data.net

import com.stocknote.core.model.Market
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 标的代码 → 市场 推断单测（[MarketGuess.of]）。
 *
 * 回归重点（2026-09-20 老周报 bug）：
 * CSV 导出的「标的代码」列是**带前缀**形态（`sh511660`，长度 9），
 * 旧实现却要求 `length == 6` 才判 ETF，导致导出后再导入时
 * **ETF 被静默建成 A 股**（跑进 A 股筛选 Tab、占比图变蓝）。
 *
 * 真实场景：全新安装 + 备份还原 / 换设备迁移时，标的在库中不存在 → 走新建路径 → 触发本函数。
 */
class MarketGuessTest {

    // ---------------------------------------------------------- 主要回归

    @Test
    fun `带前缀的沪市ETF识别为ETF（导出的真实形态）`() {
        assertEquals(Market.ETF, MarketGuess.of("sh511660"))
        assertEquals(Market.ETF, MarketGuess.of("sh510300"))
        assertEquals(Market.ETF, MarketGuess.of("sh588000"))
    }

    @Test
    fun `带前缀的深市ETF识别为ETF`() {
        assertEquals(Market.ETF, MarketGuess.of("sz159915"))
        assertEquals(Market.ETF, MarketGuess.of("sz159919"))
    }

    @Test
    fun `裸代码ETF同样识别`() {
        assertEquals(Market.ETF, MarketGuess.of("511660"))
        assertEquals(Market.ETF, MarketGuess.of("510300"))
        assertEquals(Market.ETF, MarketGuess.of("159915"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(Market.ETF, MarketGuess.of("SH511660"))
        assertEquals(Market.A_SHARE, MarketGuess.of("SH600519"))
        assertEquals(Market.HK, MarketGuess.of("HK00700"))
    }

    // ---------------------------------------------------------- 不能被误判成 ETF

    @Test
    fun `可转债不能被误判成ETF（旧实现深市1开头过宽）`() {
        // 深市可转债 123xxx / 127xxx / 128xxx
        assertEquals(Market.A_SHARE, MarketGuess.of("123456"))
        assertEquals(Market.A_SHARE, MarketGuess.of("127007"))
        assertEquals(Market.A_SHARE, MarketGuess.of("128036"))
        // 沪市可转债 110xxx / 113xxx
        assertEquals(Market.A_SHARE, MarketGuess.of("110059"))
        assertEquals(Market.A_SHARE, MarketGuess.of("113050"))
        // 带前缀形态同样不能中招
        assertEquals(Market.A_SHARE, MarketGuess.of("sz123456"))
        assertEquals(Market.A_SHARE, MarketGuess.of("sh113050"))
    }

    @Test
    fun `国债与深市主板不能被误判成ETF`() {
        assertEquals(Market.A_SHARE, MarketGuess.of("100303"))  // 国债
        assertEquals(Market.A_SHARE, MarketGuess.of("000001"))  // 平安银行
        assertEquals(Market.A_SHARE, MarketGuess.of("002594"))  // 比亚迪
        assertEquals(Market.A_SHARE, MarketGuess.of("300750"))  // 宁德时代
    }

    @Test
    fun `普通股票识别为A股`() {
        assertEquals(Market.A_SHARE, MarketGuess.of("sh600519"))
        assertEquals(Market.A_SHARE, MarketGuess.of("600519"))
        assertEquals(Market.A_SHARE, MarketGuess.of("sz000001"))
    }

    // ---------------------------------------------------------- 港美股

    @Test
    fun `港美股按前缀识别`() {
        assertEquals(Market.HK, MarketGuess.of("hk00700"))
        assertEquals(Market.HK, MarketGuess.of("hk09988"))
        assertEquals(Market.US, MarketGuess.of("usAAPL.OQ"))
    }

    @Test
    fun `港美股判定优先于剥前缀`() {
        // "hk..." 不能被当成 A 股前缀，也不能因为长度不是 6 就落 A 股
        assertEquals(Market.HK, MarketGuess.of("hk00700"))
        assertEquals(Market.US, MarketGuess.of("usBABA.N"))
    }

    // ---------------------------------------------------------- 场外基金（老周 2026-09-20）

    @Test
    fun `of前缀识别为场外基金`() {
        assertEquals(Market.FUND, MarketGuess.of("of000001"))
        assertEquals(Market.FUND, MarketGuess.of("of519066"))
        assertEquals(Market.FUND, MarketGuess.of("OF110022"))  // 大小写不敏感
        assertEquals(Market.FUND, MarketGuess.of("  of000001  "))
    }

    @Test
    fun `场外基金与同码股票必须分得开`() {
        // 000001 既是「华夏成长混合」（场外基金）也是「平安银行」（A股）——
        // 所以场外基金**必须带 of 前缀**，裸 6 位码只能按 A股 处理（无法分辨）
        assertEquals(Market.A_SHARE, MarketGuess.of("000001"))
        assertEquals(Market.A_SHARE, MarketGuess.of("sz000001"))
        assertEquals(Market.FUND, MarketGuess.of("of000001"))
    }

    // ---------------------------------------------------------- 边界

    @Test
    fun `空串与非法形态不崩，归A股兜底`() {
        assertEquals(Market.A_SHARE, MarketGuess.of(""))
        assertEquals(Market.A_SHARE, MarketGuess.of("   "))
        assertEquals(Market.A_SHARE, MarketGuess.of("abc"))
        assertEquals(Market.A_SHARE, MarketGuess.of("51166"))    // 5 位
        assertEquals(Market.A_SHARE, MarketGuess.of("5116600"))  // 7 位
        assertEquals(Market.A_SHARE, MarketGuess.of("5a1660"))   // 含非数字
    }

    @Test
    fun `北交所剥前缀后归A股（App无独立北交所档）`() {
        assertEquals(Market.A_SHARE, MarketGuess.of("bj430047"))
        assertEquals(Market.A_SHARE, MarketGuess.of("430047"))
    }

    @Test
    fun `前后空格被忽略`() {
        assertEquals(Market.ETF, MarketGuess.of("  sh511660  "))
        assertEquals(Market.A_SHARE, MarketGuess.of(" 600519 "))
    }
}
