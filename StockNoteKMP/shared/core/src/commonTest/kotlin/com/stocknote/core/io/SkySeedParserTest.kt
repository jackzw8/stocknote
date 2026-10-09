package com.stocknote.core.io

import com.stocknote.core.model.SkyAttitude
import com.stocknote.core.model.SkyDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 种子清单解析器测试（FR-SE-11 / 技术说明书 §5.4 全部容错场景）。
 *
 * 边界原则：解析器只解析、不截断（上限由预览页处理），不吃掉有效数据、也不猜档位。
 */
class SkySeedParserTest {

    private fun ok(text: String): SkySeedResult.Ok {
        val r = SkySeedParser.parse(text)
        assertIs<SkySeedResult.Ok>(r, "期望解析成功，实际：$r")
        return r
    }

    @Test
    fun 标准纯文本_看天() {
        val r = ok(
            """
            SEED:SKY
            1|美联储降息路径与美元流动性|中性|9月降25bp，点阵图分歧仍然较大
            2|A股估值位置|乐观|沪深300 PE 处于近十年中位数偏下
            # 信息截至 2026-10-08
            """.trimIndent(),
        )
        assertEquals(SkyDomain.SKY, r.domain)
        assertNull(r.symbol)
        assertEquals(2, r.items.size)
        assertEquals("美联储降息路径与美元流动性", r.items[0].title)
        assertEquals(SkyAttitude.NEUTRAL, r.items[0].attitude)
        assertEquals("9月降25bp，点阵图分歧仍然较大", r.items[0].note)
        assertEquals(SkyAttitude.BULLISH, r.items[1].attitude)
        assertEquals("2026-10-08", r.infoDate)
        assertEquals(0, r.skippedLines)
    }

    @Test
    fun 标准纯文本_看地_头行带名称与代码() {
        val r = ok(
            """
            SEED:EARTH|腾讯控股|00700
            1|游戏 pipeline 与版号|乐观|版号常态化，储备新游多于去年同期
            2|AI 云资本开支|中性|资本开支高企但变现尚未在报表体现
            """.trimIndent(),
        )
        assertEquals(SkyDomain.EARTH, r.domain)
        assertEquals("腾讯控股", r.name)
        assertEquals("00700", r.symbol)
        assertEquals(2, r.items.size)
    }

    @Test
    fun markdown表格仍可解析_表头与分隔行被忽略() {
        val r = ok(
            """
            SEED:SKY
            | 序号 | 标题 | 档位 | 理由 |
            |---|---|---|---|
            | 1 | 美联储降息 | 中性 | 分歧大 |
            | 2 | 人民币汇率 | 悲观 | 贬值压力 |
            """.trimIndent(),
        )
        assertEquals(2, r.items.size)
        assertEquals("美联储降息", r.items[0].title)
        assertEquals(SkyAttitude.NEUTRAL, r.items[0].attitude)
        assertEquals("人民币汇率", r.items[1].title)
        assertEquals(SkyAttitude.BEARISH, r.items[1].attitude)
    }

    @Test
    fun 列表前缀混合形式() {
        val r = ok(
            """
            SEED:SKY
            - 1 | 美联储降息 | 乐观 | 理由A
            * 2 | 人民币汇率 | 悲观 | 理由B
            3. 地产销售 | 中性 | 理由C
            4、出口增速 | 乐观 | 理由D
            """.trimIndent(),
        )
        assertEquals(4, r.items.size)
        assertEquals("美联储降息", r.items[0].title)
        assertEquals("人民币汇率", r.items[1].title)
        assertEquals("地产销售", r.items[2].title)
        assertEquals("出口增速", r.items[3].title)
        assertEquals(SkyAttitude.BULLISH, r.items[3].attitude)
    }

    @Test
    fun 寒暄与免责声明被忽略_不丢条目() {
        val r = ok(
            """
            好的，根据您的要求，我为您整理了以下清单：
            SEED:SKY
            1|美联储降息路径|中性|点阵图分歧较大
            2|A股估值位置|乐观|处于中位数偏下

            以上内容仅供参考，不构成投资建议。
            """.trimIndent(),
        )
        assertEquals(2, r.items.size)
        // 头行之前的内容丢弃；末尾免责声明算噪声行
        assertEquals(1, r.skippedLines)
    }

    @Test
    fun 代码块围栏被剥离() {
        val r = ok(
            "```text\nSEED:SKY\n1|美联储降息路径|中性|点阵图分歧\n2|A股估值|乐观|中位数偏下\n```",
        )
        assertEquals(2, r.items.size)
    }

    @Test
    fun 全角竖线同等对待() {
        val r = ok(
            """
            SEED:SKY
            1｜美联储降息路径｜悲观｜点阵图偏鹰
            """.trimIndent(),
        )
        assertEquals(1, r.items.size)
        assertEquals("美联储降息路径", r.items[0].title)
        assertEquals(SkyAttitude.BEARISH, r.items[0].attitude)
        assertEquals("点阵图偏鹰", r.items[0].note)
    }

    @Test
    fun 档位同义词映射() {
        assertEquals(SkyAttitude.BEARISH, SkySeedParser.attitudeOf("看空"))
        assertEquals(SkyAttitude.BEARISH, SkySeedParser.attitudeOf("偏悲观"))
        assertEquals(SkyAttitude.BEARISH, SkySeedParser.attitudeOf("负面"))
        assertEquals(SkyAttitude.BEARISH, SkySeedParser.attitudeOf("bearish"))
        assertEquals(SkyAttitude.NEUTRAL, SkySeedParser.attitudeOf("观望"))
        assertEquals(SkyAttitude.BULLISH, SkySeedParser.attitudeOf("偏乐观"))
        assertEquals(SkyAttitude.BULLISH, SkySeedParser.attitudeOf("看多"))
        assertEquals(SkyAttitude.BULLISH, SkySeedParser.attitudeOf("bullish"))
        assertNull(SkySeedParser.attitudeOf("值得关注"))
        assertNull(SkySeedParser.attitudeOf(""))
    }

    @Test
    fun 档位不识别_条目保留标未判断_内容不丢() {
        val r = ok(
            """
            SEED:SKY
            1|美联储降息路径|值得关注|点阵图分歧较大
            """.trimIndent(),
        )
        assertEquals(1, r.items.size)
        assertNull(r.items[0].attitude)
        // 识别不了的「档位」段并入理由，不丢内容
        assertEquals("值得关注|点阵图分歧较大", r.items[0].note)
    }

    @Test
    fun 理由缺失允许() {
        val r = ok(
            """
            SEED:SKY
            1|美联储降息路径|乐观
            """.trimIndent(),
        )
        assertEquals(1, r.items.size)
        assertNull(r.items[0].note)
    }

    @Test
    fun 序号重复或断号_按出现顺序() {
        val r = ok(
            """
            SEED:SKY
            1|甲|乐观|a
            1|乙|悲观|b
            5|丙|中性|c
            """.trimIndent(),
        )
        assertEquals(listOf("甲", "乙", "丙"), r.items.map { it.title })
    }

    @Test
    fun 标题超30字截断并标记() {
        val long = "甲".repeat(31)
        val r = ok("SEED:SKY\n1|$long|乐观|理由")
        assertEquals(30, r.items[0].title.length)
        assertTrue(r.items[0].titleTruncated)
    }

    @Test
    fun 超过上限的条目照样解析_由上层截断() {
        val body = (1..15).joinToString("\n") { "$it|关注点$it|乐观|理由$it" }
        val r = ok("SEED:SKY\n$body")
        assertEquals(15, r.items.size)
    }

    @Test
    fun 混入无结构行被忽略且计入噪声() {
        val r = ok(
            """
            SEED:SKY
            1|美联储降息路径|乐观|理由A
            这里是一段 AI 的解释性文字
            2|A股估值|中性|理由B
            """.trimIndent(),
        )
        assertEquals(2, r.items.size)
        assertEquals(1, r.skippedLines)
    }

    @Test
    fun BOM与CRLF与大小写不敏感() {
        val r = ok("\uFEFFseed:sky\r\n1|美联储降息路径|中性|理由\r\n")
        assertEquals(SkyDomain.SKY, r.domain)
        assertEquals(1, r.items.size)
    }

    @Test
    fun 空输入与无头行给不出结果() {
        assertIs<SkySeedResult.Invalid>(SkySeedParser.parse(""))
        assertIs<SkySeedResult.Invalid>(SkySeedParser.parse("   \n\n  "))
        val noHeader = SkySeedParser.parse("1|美联储降息路径|乐观|理由")
        assertIs<SkySeedResult.Invalid>(noHeader)
        assertTrue(noHeader.reason.contains("SEED:"))
    }

    // ==================== 多段（H-1） ====================

    /**
     * **老周 2026-10-09 真机上的真实文件**（`SEEDSKY.txt`）：看天 10 条 + 看地 5 条装在一个文本里。
     *
     * 修前这段文本会解析出 16 条：第 11 条是垃圾（标题 `SEED:EARTH`、依据 `腾讯控股|00700`），
     * 后面 5 条地组条目被当天组条目 —— 天组容量截断后地组永远进不去。
     */
    @Test
    fun 双段文件_看天加看地_各归各段且无垃圾条目() {
        val r = ok(
            """
            SEED:SKY
            1|美联储加息压制估值|悲观|9月加息至3.75-4%，10年美债破5%创07年新高
            2|中美关税休战延长|乐观|关税暂停延至2027年1月，300亿对等降税达成
            3|稳增长政策再加码|乐观|9·29央行下调PSL利率25bp、再贷款扩容7000亿
            4|高油价推升PPI|悲观|布伦特破100美元，9月PPI同比或升至4.5%
            5|内需地产继续探底|悲观|8月社零增0.4%，9月50城新房成交同比-23%
            6|人民币升值引外资|乐观|在岸人民币升破6.70，创2022年7月以来新高
            7|A股港股估值安全垫|乐观|沪深300风险溢价近十年76%分位，恒指PE10.7倍
            8|南向资金逆势抄底|乐观|9月净买入608亿港元，恒指当月跌3.73%仍加仓
            9|增量资金边际转弱|悲观|9月A股日均成交环比-18.8%，权益新发同比-64%
            10|三季报结构分化|中性|已披露预告预增占81%，但整体盈利增速或回落
            # 信息截至 2026-10-09


            SEED:EARTH|腾讯控股|00700
            1|AI资本开支强度|悲观|H1投资净流出860亿港元，长期借款升至2448亿
            2|回购节奏|悲观|日均回购由1月6.36亿降至10月1.0亿港元
            3|三季报业绩|中性|H1收入4619.7亿、净利1313.9亿，均增约16%
            4|AI应用变现|乐观|8月HY4 Preview开源，WorkBuddy待验证货币化
            5|南向与估值位置|乐观|南向持股11.91%季增2308万股，PE仅14.08
            # 信息截至 2026-10-09
            """.trimIndent(),
        )
        assertEquals(2, r.segments.size, "一份文本两段：天 + 地")

        val sky = r.segments[0]
        assertEquals(SkyDomain.SKY, sky.domain)
        assertEquals(10, sky.items.size)
        assertEquals("2026-10-09", sky.infoDate)
        assertEquals(0, sky.skippedLines)
        // 修前会在这里多出「标题 = SEED:EARTH」的垃圾条目
        assertTrue(sky.items.none { it.title.startsWith("SEED") }, "天组不得混入段头行垃圾：${sky.items.map { it.title }}")

        val earth = r.segments[1]
        assertEquals(SkyDomain.EARTH, earth.domain)
        assertEquals("腾讯控股", earth.name)
        assertEquals("00700", earth.symbol)
        assertEquals(5, earth.items.size)
        assertEquals("AI资本开支强度", earth.items[0].title)
        assertEquals(SkyAttitude.BEARISH, earth.items[0].attitude)
        assertEquals("2026-10-09", earth.infoDate)
    }

    @Test
    fun 三段混合_同域两段也各自成段() {
        val r = ok(
            """
            SEED:SKY
            1|甲|乐观|a
            SEED:EARTH|贵州茅台|600519
            1|乙|中性|b
            SEED:SKY
            1|丙|悲观|c
            """.trimIndent(),
        )
        assertEquals(3, r.segments.size)
        assertEquals(SkyDomain.SKY, r.segments[0].domain)
        assertEquals(SkyDomain.EARTH, r.segments[1].domain)
        assertEquals(SkyDomain.SKY, r.segments[2].domain)
        assertEquals(listOf("甲"), r.segments[0].items.map { it.title })
        assertEquals(listOf("乙"), r.segments[1].items.map { it.title })
        assertEquals(listOf("丙"), r.segments[2].items.map { it.title })
        assertEquals("贵州茅台", r.segments[1].name)
        assertEquals("600519", r.segments[1].symbol)
    }

    @Test
    fun 单段文本仍是单段_便捷属性等于第一段() {
        val r = ok("SEED:SKY\n1|甲|乐观|a\n# 信息截至 2026-10-09")
        assertEquals(1, r.segments.size)
        assertEquals(r.segments[0], r.primary)
        assertEquals(SkyDomain.SKY, r.domain)
        assertEquals(1, r.items.size)
        assertEquals("2026-10-09", r.infoDate)
    }
}
