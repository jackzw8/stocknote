package com.stocknote.feature.state

import com.stocknote.core.io.SkySeedItem
import com.stocknote.core.io.SkySeedParser
import com.stocknote.core.io.SkySeedResult
import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.SkyAttitude
import com.stocknote.core.model.SkyDomain
import com.stocknote.core.model.SkyLevel
import com.stocknote.data.AppContainer
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.platform.nowEpochMs
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **看天看地 Holder 行为测试**（SE-5，技术说明书 §9）。
 *
 * 钉住四条：
 *  1. 改档位 → 分数/档位**立刻重算**（派生不存 state，防"忘了重算"）；
 *  2. 30 天过期条数（未判断的不计）；
 *  3. 种子导入条数与上限拦截；
 *  4. **失败路径必须写 `error`**（P1-35 的规矩：不许点了没反应）。
 *
 * ⚠️ 用真内存库 + 真 `AppContainer`（JVM 侧未设系统属性 = 内存库；schema 由测试自建）；
 * 失败路径用 **DROP TABLE** 制造（照 HolderFailureFeedbackTest 的做法，不改生产代码）。
 */
class SkyEarthHolderTest {

    private fun newEnv(brokenTable: String? = null): Pair<SkyEarthHolder, AppContainer> {
        val container = AppContainer()
        StockNoteDb.Schema.create(container.driver)
        if (brokenTable != null) {
            container.driver.execute(null, "DROP TABLE $brokenTable", 0)
        }
        val holder = SkyEarthHolder(container.skyEarth, container.watch, container.security)
        return holder to container
    }

    @Test
    fun 改档位后分数立刻重算(): Unit = runBlocking {
        val (holder, container) = newEnv()
        try {
            holder.load()
            val a = holder.addFactor(SkyDomain.SKY, "甲", null)
            val b = holder.addFactor(SkyDomain.SKY, "乙", null)
            assertNull(a, "新增应成功")
            assertNull(b)
            assertEquals(2, holder.sky.size)
            assertNull(holder.skyScore, "全未判断 → 分数是 null，不是 0")
            assertEquals(SkyLevel.UNJUDGED, holder.skyLevel)

            holder.setScore(holder.sky[0].id, SkyAttitude.BULLISH)
            holder.setScore(holder.sky[1].id, SkyAttitude.BULLISH)
            assertEquals(100, holder.skyScore, "两条全乐观 → +100")
            assertEquals(SkyLevel.VERY_BULLISH, holder.skyLevel)

            // 第 1 条改悲观：位次权重 1/3 vs 1/4 → (0.25−0.3333)/0.58333 ≈ −14
            holder.setScore(holder.sky[0].id, SkyAttitude.BEARISH)
            assertEquals(-14, holder.skyScore)
            assertEquals(SkyLevel.NEUTRAL, holder.skyLevel)
            assertEquals(2, holder.skyJudged)
        } finally {
            container.close()
        }
    }

    @Test
    fun 三十天过期条数_未判断不计(): Unit = runBlocking {
        val (holder, container) = newEnv()
        try {
            holder.load()
            holder.addFactor(SkyDomain.SKY, "甲", null)
            holder.addFactor(SkyDomain.SKY, "乙", null)
            holder.addFactor(SkyDomain.SKY, "丙（从不判断）", null)
            holder.load()

            holder.setScore(holder.sky[0].id, SkyAttitude.NEUTRAL)
            assertEquals(0, holder.staleCount, "刚判断的不该算过期")

            // 把第 2 条的 judged_at 手工改到 40 天前
            val old = nowEpochMs() - 40L * 86_400_000L
            container.driver.execute(
                null,
                "UPDATE sky_factor SET judged_at = $old WHERE id = '${holder.sky[1].id}'",
                0,
            )
            holder.load()
            assertEquals(1, holder.staleCount, "40 天前判断的算 1 条；从未判断的不计入")
        } finally {
            container.close()
        }
    }

    @Test
    fun 导入种子条数与上限拦截(): Unit = runBlocking {
        val (holder, container) = newEnv()
        try {
            holder.load()
            val n = holder.importSeeds(
                SkyDomain.EARTH,
                listOf(
                    SkySeedItem("游戏流水", SkyAttitude.BULLISH, "版号常态化"),
                    SkySeedItem("回购节奏", null, "未识别档位也能导入"),
                ),
            )
            assertEquals(2, n)
            assertEquals(2, holder.earth.size)
            assertEquals(1, holder.earthJudged, "只有带档位的那条算已判断")

            // 上限拦截：看地填满 5 条后再导入 → 0 条 + error 有值
            repeat(3) { holder.addFactor(SkyDomain.EARTH, "补${it}", null) }
            assertEquals(5, holder.earth.size)
            val n2 = holder.importSeeds(SkyDomain.EARTH, listOf(SkySeedItem("多余", SkyAttitude.NEUTRAL, null)))
            assertEquals(0, n2)
            assertNotNull(holder.error, "超上限必须给人话提示，不许静默")
        } finally {
            container.close()
        }
    }

    @Test
    fun 新增超上限被拦并给出原因(): Unit = runBlocking {
        val (holder, container) = newEnv()
        try {
            holder.load()
            repeat(SkyDomain.EARTH.maxCount) { holder.addFactor(SkyDomain.EARTH, "第${it + 1}条", null) }
            val err = holder.addFactor(SkyDomain.EARTH, "第 6 条", null)
            assertNotNull(err, "超上限时 addFactor 应返回失败原因")
            assertTrue(err.contains("最多"), "原因里要有上限说明：$err")
            assertEquals(SkyDomain.EARTH.maxCount, holder.earth.size)
        } finally {
            container.close()
        }
    }

    @Test
    fun 多段种子_天地两组各自入库(): Unit = runBlocking {
        // H-1（审核报告 2026-10-09）端到端：老周真机文件是「看天 10 条 + 看地 5 条」一份文本，
        // 解析出两段后逐段导入 —— 天组进天组、地组进地组，合计 15 条。
        val (holder, container) = newEnv()
        try {
            holder.load()
            val parsed = SkySeedParser.parse(REAL_SEEDSKY_SAMPLE)
            val ok = parsed as SkySeedResult.Ok
            assertEquals(2, ok.segments.size)

            var total = 0
            for (seg in ok.segments) {
                total += holder.importSeeds(seg.domain, seg.items)
            }
            assertEquals(15, total, "10 条天 + 5 条地都要进库")
            assertEquals(10, holder.sky.size)
            assertEquals(5, holder.earth.size)
            assertEquals("AI资本开支强度", holder.earth.first().title)
            assertEquals(SkyAttitude.BEARISH, holder.earth.first().score)
        } finally {
            container.close()
        }
    }

    @Test
    fun 天组已满时_地组仍能导入且天组不重复进(): Unit = runBlocking {
        // 老周真机现状：天组 10 条已满、地组空 —— 一份文本两段，
        // 天段因容量失败**不能挡住**地段（导入页循环里对失败段 continue，不停下）。
        val (holder, container) = newEnv()
        try {
            holder.load()
            repeat(10) { holder.addFactor(SkyDomain.SKY, "已满$it", null) }
            val ok = SkySeedParser.parse(REAL_SEEDSKY_SAMPLE) as SkySeedResult.Ok

            var total = 0
            for (seg in ok.segments) {
                val n = holder.importSeeds(seg.domain, seg.items) // 模拟"两段都全勾"
                if (n == 0) continue
                total += n
            }
            assertEquals(5, total, "天段失败不该挡住地段的 5 条")
            assertEquals(10, holder.sky.size, "天组保持原 10 条，不重复进")
            assertEquals(5, holder.earth.size)
            assertNotNull(holder.error, "天段没导进去要有原因留在 error 上（回主页会弹出来）")
        } finally {
            container.close()
        }
    }

    @Test
    fun 切换标的清单跟着切换且旧清单保留(): Unit = runBlocking {
        val (holder, container) = newEnv()
        try {
            holder.load()
            holder.addFactor(SkyDomain.EARTH, "腾讯的关注点", null)
            assertEquals(1, holder.earth.size)

            holder.switchSymbol("sh600519")
            assertEquals(0, holder.earth.size, "新标的的清单是空的")
            holder.addFactor(SkyDomain.EARTH, "茅台的关注点", null)
            assertEquals(1, holder.earth.size)

            holder.switchSymbol("hk00700")
            assertEquals(1, holder.earth.size, "切回来旧清单还在")
            assertEquals("腾讯的关注点", holder.earth.first().title)
        } finally {
            container.close()
        }
    }

    @Test
    fun 自选标的进入切换列表(): Unit = runBlocking {
        // 老周 2026-10-09 报「切换标的说没有、实际自选里有」：
        // 根因是页面漏调 load()（已修），这里把 Holder 侧的数据链也钉住 ——
        // 自选（watch_item.security_id）必须能按 id 关联到标的表并给出 symbol/name。
        val (holder, container) = newEnv()
        try {
            val sec = container.security.findOrCreateSecurity(
                symbol = "sh600519",
                name = "贵州茅台",
                market = Market.A_SHARE,
                currency = Currency.CNY,
            )
            container.watch.add(sec.id)
            holder.load()
            assertEquals(1, holder.watchSymbols.size, "自选里的标的必须出现在切换列表")
            assertEquals("sh600519" to "贵州茅台", holder.watchSymbols.first())
        } finally {
            container.close()
        }
    }

    @Test
    fun 失败路径必须写error(): Unit = runBlocking {
        val (holder, container) = newEnv(brokenTable = "sky_factor")
        try {
            holder.load()
            assertNotNull(holder.error, "读清单失败 → error 必须有值（不许静默）")

            holder.dismissError()
            assertNull(holder.error)

            holder.setScore("whatever", SkyAttitude.BULLISH)
            assertNotNull(holder.error, "写判断失败 → error 必须有值")
        } finally {
            container.close()
        }
    }

    private companion object {
        /**
         * **老周 2026-10-09 真机文件（`SEEDSKY.txt`）逐字复刻**：看天 10 条 + 看地 5 条装在一个文本里。
         * 用它当多段（H-1）回归的锚点 —— 以后谁再把 parse 改回"只认第一个头行"，这套用例就会红。
         * （解析器侧同一份样本见 `SkySeedParserTest.双段文件_看天加看地_各归各段且无垃圾条目`。）
         */
        private val REAL_SEEDSKY_SAMPLE = listOf(
            "SEED:SKY",
            "1|美联储加息压制估值|悲观|9月加息至3.75-4%，10年美债破5%创07年新高",
            "2|中美关税休战延长|乐观|关税暂停延至2027年1月，300亿对等降税达成",
            "3|稳增长政策再加码|乐观|9·29央行下调PSL利率25bp、再贷款扩容7000亿",
            "4|高油价推升PPI|悲观|布伦特破100美元，9月PPI同比或升至4.5%",
            "5|内需地产继续探底|悲观|8月社零增0.4%，9月50城新房成交同比-23%",
            "6|人民币升值引外资|乐观|在岸人民币升破6.70，创2022年7月以来新高",
            "7|A股港股估值安全垫|乐观|沪深300风险溢价近十年76%分位，恒指PE10.7倍",
            "8|南向资金逆势抄底|乐观|9月净买入608亿港元，恒指当月跌3.73%仍加仓",
            "9|增量资金边际转弱|悲观|9月A股日均成交环比-18.8%，权益新发同比-64%",
            "10|三季报结构分化|中性|已披露预告预增占81%，但整体盈利增速或回落",
            "# 信息截至 2026-10-09",
            "",
            "",
            "SEED:EARTH|腾讯控股|00700",
            "1|AI资本开支强度|悲观|H1投资净流出860亿港元，长期借款升至2448亿",
            "2|回购节奏|悲观|日均回购由1月6.36亿降至10月1.0亿港元",
            "3|三季报业绩|中性|H1收入4619.7亿、净利1313.9亿，均增约16%",
            "4|AI应用变现|乐观|8月HY4 Preview开源，WorkBuddy待验证货币化",
            "5|南向与估值位置|乐观|南向持股11.91%季增2308万股，PE仅14.08",
            "# 信息截至 2026-10-09",
        ).joinToString("\n")
    }
}
