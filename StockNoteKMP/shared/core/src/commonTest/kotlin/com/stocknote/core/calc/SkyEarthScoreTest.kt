package com.stocknote.core.calc

import com.stocknote.core.model.SkyAttitude
import com.stocknote.core.model.SkyDomain
import com.stocknote.core.model.SkyFactor
import com.stocknote.core.model.SkyLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 看天看地算分纯函数测试（FR-SE-05 / FR-SE-07 / 技术说明书 §4.2~§4.4）。
 *
 * 覆盖：§4.2 全部向量（含「地组只判第 5 条 = −100」反向用例）、
 * 档位边界（−60/−59/0/19/20/59/60）、过期（29/30/31 天、未判断）。
 *
 * 反向验证（故意改错必须变红）：
 *  1. `scoreOf` 空集合返回 0 而不是 null → 「全未判断返回null」变红；
 *  2. 归一化改成全局（含未判条目）→ 「地组只判第5条」由 −100 变 −13；
 *  3. 位次改用「已判集合内相对位次」→ 「清单位次参与归一化」由 −9 变 −14。
 */
class SkyEarthScoreTest {

    private fun factor(rank: Int, attitude: SkyAttitude?, domain: SkyDomain): SkyFactor =
        SkyFactor(
            id = "f$rank",
            domain = domain,
            scopeKey = if (domain == SkyDomain.SKY) "" else "hk00700",
            title = "关注点 $rank",
            sortOrder = rank.toLong(),
            score = attitude,
            judgedAt = if (attitude != null) 1_700_000_000_000L else null,
            createdAt = 1_700_000_000_000L,
        )

    private fun sky(attitudes: List<SkyAttitude?>): List<SkyFactor> =
        attitudes.mapIndexed { i, a -> factor(i + 1, a, SkyDomain.SKY) }

    private fun earth(attitudes: List<SkyAttitude?>): List<SkyFactor> =
        attitudes.mapIndexed { i, a -> factor(i + 1, a, SkyDomain.EARTH) }

    // ---- §4.2 合成向量 ----

    @Test
    fun 天组10条全乐观为100() {
        assertEquals(100, SkyEarthScore.scoreOf(sky(List(10) { SkyAttitude.BULLISH })))
    }

    @Test
    fun 天组10条全悲观为负100() {
        assertEquals(-100, SkyEarthScore.scoreOf(sky(List(10) { SkyAttitude.BEARISH })))
    }

    @Test
    fun 天组10条全中性为0() {
        assertEquals(0, SkyEarthScore.scoreOf(sky(List(10) { SkyAttitude.NEUTRAL })))
    }

    @Test
    fun 全未判断返回null而不是0() {
        assertNull(SkyEarthScore.scoreOf(sky(List(10) { null })))
        assertNull(SkyEarthScore.scoreOf(emptyList()))
    }

    @Test
    fun 天组第1条悲观其余9条乐观约58() {
        val attitudes = List(10) { if (it == 0) SkyAttitude.BEARISH else SkyAttitude.BULLISH }
        assertEquals(58, SkyEarthScore.scoreOf(sky(attitudes)))
    }

    @Test
    fun 天组第10条悲观其余9条乐观约90() {
        val attitudes = List(10) { if (it == 9) SkyAttitude.BEARISH else SkyAttitude.BULLISH }
        assertEquals(90, SkyEarthScore.scoreOf(sky(attitudes)))
    }

    @Test
    fun 地组第1条悲观其余4条乐观约39() {
        val attitudes = List(5) { if (it == 0) SkyAttitude.BEARISH else SkyAttitude.BULLISH }
        assertEquals(39, SkyEarthScore.scoreOf(earth(attitudes)))
    }

    @Test
    fun 地组只判第5条悲观为负100() {
        // 归一化只在已判集合内做 —— 若改成全局归一化，这里会变成 −13
        val attitudes = listOf(null, null, null, null, SkyAttitude.BEARISH)
        assertEquals(-100, SkyEarthScore.scoreOf(earth(attitudes)))
    }

    @Test
    fun 只判一条乐观同样满权为100() {
        val attitudes = listOf(SkyAttitude.BULLISH, null, null)
        assertEquals(100, SkyEarthScore.scoreOf(sky(attitudes)))
    }

    @Test
    fun 位次权重第一条影响大于第十条() {
        // 验收 #4：同样方向下，第 1 条改判断引起的分数变化 > 第 10 条
        val first = sky(List(10) { if (it == 0) SkyAttitude.BULLISH else SkyAttitude.NEUTRAL })
        val tenth = sky(List(10) { if (it == 9) SkyAttitude.BULLISH else SkyAttitude.NEUTRAL })
        assertEquals(21, SkyEarthScore.scoreOf(first))
        assertEquals(5, SkyEarthScore.scoreOf(tenth))
        assertTrue(SkyEarthScore.scoreOf(first)!! > SkyEarthScore.scoreOf(tenth)!!)
    }

    @Test
    fun 清单位次参与归一化() {
        // 判第 3 条悲观 + 第 4 条乐观（其余未判）：
        // 用清单内位次（1/5、1/6）→ −9；若误用已判集合内相对位次（1/3、1/4）→ −14
        val attitudes = listOf(null, null, SkyAttitude.BEARISH, SkyAttitude.BULLISH, null)
        assertEquals(-9, SkyEarthScore.scoreOf(sky(attitudes + List(5) { null })))
    }

    @Test
    fun 原始权重口径() {
        assertEquals(1.0 / 3.0, SkyEarthScore.rawWeight(1), 1e-12)
        assertEquals(1.0 / 12.0, SkyEarthScore.rawWeight(10), 1e-12)
    }

    // ---- §4.3 档位边界（边界闭合）----

    @Test
    fun 档位映射边界() {
        assertEquals(SkyLevel.UNJUDGED, SkyEarthScore.levelOf(null))
        assertEquals(SkyLevel.VERY_BEARISH, SkyEarthScore.levelOf(-100))
        assertEquals(SkyLevel.VERY_BEARISH, SkyEarthScore.levelOf(-60))
        assertEquals(SkyLevel.BEARISH, SkyEarthScore.levelOf(-59))
        assertEquals(SkyLevel.BEARISH, SkyEarthScore.levelOf(-20))
        assertEquals(SkyLevel.NEUTRAL, SkyEarthScore.levelOf(-19))
        assertEquals(SkyLevel.NEUTRAL, SkyEarthScore.levelOf(0))
        assertEquals(SkyLevel.NEUTRAL, SkyEarthScore.levelOf(19))
        assertEquals(SkyLevel.BULLISH, SkyEarthScore.levelOf(20))
        assertEquals(SkyLevel.BULLISH, SkyEarthScore.levelOf(59))
        assertEquals(SkyLevel.VERY_BULLISH, SkyEarthScore.levelOf(60))
        assertEquals(SkyLevel.VERY_BULLISH, SkyEarthScore.levelOf(100))
    }

    // ---- §4.4 过期判定 ----

    @Test
    fun 从未判断不算过期() {
        val now = 1_800_000_000_000L
        assertFalse(SkyEarthScore.isStale(null, now))
    }

    @Test
    fun 过期阈值29与30与31天() {
        val now = 1_800_000_000_000L
        val day = 86_400_000L
        assertFalse(SkyEarthScore.isStale(now - 29 * day, now))
        // 恰好 30 天不算过期（规则是「距今 > 30 天」）
        assertFalse(SkyEarthScore.isStale(now - 30 * day, now))
        assertTrue(SkyEarthScore.isStale(now - 30 * day - 1, now))
        assertTrue(SkyEarthScore.isStale(now - 31 * day, now))
    }
}
