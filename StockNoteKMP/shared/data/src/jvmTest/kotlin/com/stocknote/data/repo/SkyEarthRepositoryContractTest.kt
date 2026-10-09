package com.stocknote.data.repo

import app.cash.sqldelight.db.SqlDriver
import com.stocknote.core.io.SkySeedItem
import com.stocknote.core.model.SkyAttitude
import com.stocknote.core.model.SkyDomain
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.platform.createEncryptedDriver
import com.stocknote.data.platform.todayIso
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **看天看地契约测试**（SE-4，技术说明书 §9）。
 *
 * 钉死四条关键行为：
 *  1. 改分 = factor 快照与 judgement 历史**两处同步**（同一事务）；
 *  2. **同自然日多次改只留 1 条**（值是最后一次）；跨日保留 2 条；
 *  3. **删条目级联删历史**（无孤儿行）—— 反向验证：去掉级联这里会红；
 *  4. 排序变更持久化；种子导入追加不覆盖、带档位写历史。
 *
 * ⚠️ 用 JVM `JdbcSqliteDriver`（内存库、不加密），schema 由测试自建。
 */
class SkyEarthRepositoryContractTest {

    private fun newRepo(): Pair<SkyEarthRepository, StockNoteDb> {
        val driver: SqlDriver = createEncryptedDriver("sky-earth-test", ByteArray(0))
        StockNoteDb.Schema.create(driver)
        val db = StockNoteDb(driver)
        return SkyEarthRepository(db) to db
    }

    private val sky = SkyDomain.SKY
    private val skyScope = SkyEarthRepository.SKY_SCOPE

    @Test
    fun 改分两处同步() = runBlocking {
        val (repo, _) = newRepo()
        val id = repo.addFactor(sky, skyScope, "美联储降息路径")

        repo.setScore(id, SkyAttitude.BULLISH)

        val f = repo.factors(sky, skyScope).single()
        assertEquals(SkyAttitude.BULLISH, f.score)
        assertNotNull(f.judgedAt)
        val history = repo.judgements(id)
        assertEquals(1, history.size)
        assertEquals(SkyAttitude.BULLISH, history[0].score)
        assertEquals(todayIso(), history[0].day)
    }

    @Test
    fun 同日改3次只留1条且是最后一次() = runBlocking {
        val (repo, _) = newRepo()
        val id = repo.addFactor(sky, skyScope, "A股估值位置")

        repo.setScore(id, SkyAttitude.BEARISH)
        repo.setScore(id, SkyAttitude.NEUTRAL)
        repo.setScore(id, SkyAttitude.BULLISH)

        val history = repo.judgements(id)
        assertEquals(1, history.size, "同自然日应只留最后一次")
        assertEquals(SkyAttitude.BULLISH, history[0].score)
        assertEquals(SkyAttitude.BULLISH, repo.factors(sky, skyScope).single().score)
    }

    @Test
    fun 跨日保留历史两条() = runBlocking {
        val (repo, db) = newRepo()
        val id = repo.addFactor(sky, skyScope, "人民币汇率")
        // 手工插一条「更早一天」的历史：repo 的 day 取自 todayIso()，没法注入假日期，
        // 这里直接构造跨日场景来验证「同日覆盖、跨日新增」的另一半。
        db.skyEarthQueries.upsertJudgement("skj-old", id, "2026-01-01", -1L, 1L)

        repo.setScore(id, SkyAttitude.BULLISH)

        val history = repo.judgements(id)
        assertEquals(2, history.size)
        assertEquals(SkyAttitude.BEARISH, history[0].score, "时间升序：更早那条在前")
        assertEquals(SkyAttitude.BULLISH, history[1].score)
    }

    @Test
    fun 删除条目级联删除历史_无孤儿() = runBlocking {
        val (repo, db) = newRepo()
        val id = repo.addFactor(sky, skyScope, "地产销售")
        repo.setScore(id, SkyAttitude.BEARISH)
        assertEquals(1, repo.judgements(id).size)

        repo.removeFactor(id)

        assertTrue(repo.factors(sky, skyScope).isEmpty())
        assertTrue(repo.judgements(id).isEmpty(), "删条目后不该留下孤儿 judgement")
        assertEquals(0, db.skyEarthQueries.selectJudgements(id).executeAsList().size)
    }

    @Test
    fun 删除条目不影响其它条目历史() = runBlocking {
        val (repo, _) = newRepo()
        val a = repo.addFactor(sky, skyScope, "甲")
        val b = repo.addFactor(sky, skyScope, "乙")
        repo.setScore(a, SkyAttitude.BEARISH)
        repo.setScore(b, SkyAttitude.BULLISH)

        repo.removeFactor(a)

        assertEquals(1, repo.factors(sky, skyScope).size)
        assertEquals(1, repo.judgements(b).size, "级联删除不能误伤别的条目")
    }

    @Test
    fun 排序变更持久化() = runBlocking {
        val (repo, _) = newRepo()
        val a = repo.addFactor(sky, skyScope, "甲")
        val b = repo.addFactor(sky, skyScope, "乙")
        val c = repo.addFactor(sky, skyScope, "丙")
        assertEquals(listOf("甲", "乙", "丙"), repo.factors(sky, skyScope).map { it.title })

        repo.reorder(listOf(c, a, b))

        assertEquals(listOf("丙", "甲", "乙"), repo.factors(sky, skyScope).map { it.title })
    }

    @Test
    fun 两组清单互不影响且新增追加到组尾() = runBlocking {
        val (repo, _) = newRepo()
        repo.addFactor(sky, skyScope, "天1")
        repo.addFactor(sky, skyScope, "天2")
        repo.addFactor(SkyDomain.EARTH, "hk00700", "地1")

        assertEquals(2, repo.countGroup(sky, skyScope))
        assertEquals(1, repo.countGroup(SkyDomain.EARTH, "hk00700"))
        assertEquals(0, repo.countGroup(SkyDomain.EARTH, "sh600519"), "换标的 = 另一张空清单")

        repo.addFactor(sky, skyScope, "天3")
        assertEquals("天3", repo.factors(sky, skyScope).last().title, "新增应追加到组尾")
    }

    @Test
    fun 清除判断回到未判断且不写历史() = runBlocking {
        val (repo, _) = newRepo()
        val id = repo.addFactor(sky, skyScope, "出口增速")
        repo.setScore(id, SkyAttitude.BEARISH)

        repo.setScore(id, null)

        val f = repo.factors(sky, skyScope).single()
        assertNull(f.score)
        assertNull(f.judgedAt)
        // 历史只增不清：已留档的那条仍在（一期 UI 无清除入口，行为仅作契约）
        assertEquals(1, repo.judgements(id).size)
    }

    @Test
    fun 种子导入_带档位写历史_未识别档位不写() = runBlocking {
        val (repo, _) = newRepo()
        val items = listOf(
            SkySeedItem("游戏 pipeline 与版号", SkyAttitude.BULLISH, "版号常态化"),
            SkySeedItem("AI 云资本开支", null, "资本开支高企"),
        )

        val n = repo.importSeeds(SkyDomain.EARTH, "hk00700", items)

        assertEquals(2, n)
        val list = repo.factors(SkyDomain.EARTH, "hk00700")
        assertEquals(listOf("游戏 pipeline 与版号", "AI 云资本开支"), list.map { it.title })
        assertEquals(SkyAttitude.BULLISH, list[0].score)
        assertNull(list[1].score, "未识别档位 = 未判断，不猜")
        assertEquals(1, repo.judgements(list[0].id).size, "带档位导入 = 首次判断，要留档")
        assertEquals(0, repo.judgements(list[1].id).size)
    }

    @Test
    fun 导入追加在现有条目之后不覆盖() = runBlocking {
        val (repo, _) = newRepo()
        repo.addFactor(sky, skyScope, "原有")

        repo.importSeeds(sky, skyScope, listOf(SkySeedItem("新1", SkyAttitude.NEUTRAL, null)))

        assertEquals(listOf("原有", "新1"), repo.factors(sky, skyScope).map { it.title })
    }

    @Test
    fun 复制条目只带标题不带打分与依据() = runBlocking {
        val (repo, _) = newRepo()
        val idA = repo.addFactor(SkyDomain.EARTH, "hk00700", "游戏流水", note = "依据A")
        repo.setScore(idA, SkyAttitude.BULLISH)

        val n = repo.copyTitlesFrom(SkyDomain.EARTH, "hk00700", "sh600519")

        assertEquals(1, n)
        val copied = repo.factors(SkyDomain.EARTH, "sh600519").single()
        assertEquals("游戏流水", copied.title)
        assertNull(copied.score)
        assertNull(copied.note)
        assertNull(copied.judgedAt)
    }

    @Test
    fun 模块配置读写与默认值() = runBlocking {
        val (repo, _) = newRepo()
        assertEquals("hk00700", repo.currentEarthSymbol())
        assertEquals("sh000300", repo.skyBenchmark())

        repo.setCurrentEarthSymbol("sh600519")
        repo.setSkyBenchmark("sh000001")

        assertEquals("sh600519", repo.currentEarthSymbol())
        assertEquals("sh000001", repo.skyBenchmark())
    }

    @Test
    fun 改标题与依据不动判断() = runBlocking {
        val (repo, _) = newRepo()
        val id = repo.addFactor(sky, skyScope, "旧标题")
        repo.setScore(id, SkyAttitude.BULLISH)

        repo.renameFactor(id, "新标题", "新依据")

        val f = repo.factors(sky, skyScope).single()
        assertEquals("新标题", f.title)
        assertEquals("新依据", f.note)
        assertEquals(SkyAttitude.BULLISH, f.score, "改标题不该动判断")
        assertEquals(1, repo.judgements(id).size)
    }
}
