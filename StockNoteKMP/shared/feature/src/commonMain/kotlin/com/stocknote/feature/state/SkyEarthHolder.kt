package com.stocknote.feature.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.stocknote.core.calc.SkyEarthScore
import com.stocknote.core.io.SkySeedItem
import com.stocknote.core.io.SkySeedParser
import com.stocknote.core.model.SkyAttitude
import com.stocknote.core.model.SkyDomain
import com.stocknote.core.model.SkyFactor
import com.stocknote.core.model.SkyLevel
import com.stocknote.data.AppContainer
import com.stocknote.data.platform.formatLocalDateTime
import com.stocknote.data.platform.nowEpochMs
import com.stocknote.data.repo.SecurityRepository
import com.stocknote.data.repo.SkyEarthRepository
import com.stocknote.data.repo.WatchlistRepository

/**
 * **看天看地页的状态持有**（SE-5，老周 2026-10-09）。
 *
 * ⚠️ 必须 **提升到 App 顶层** remember（照抄 `rememberNewsHolder` 的做法）：
 * 进二级页（种子导入 / 关注点详情）时若 holder 在页面分支里，路由一切换组合就被销毁、
 * 状态连同清单一起丢 —— 这正是 NewsHolder 当初踩过的坑。
 *
 * 分数 / 档位 / 过期条数都是**派生**（core 纯函数现算，不存 state）：
 * 排序一变、档位一改，分数立刻跟着变，不存在"忘了重算"。
 *
 * ⚠️ 每个动作都 `runCatching` + 失败写 [error]（P1-35 定的规矩：**不许点了没反应**）。
 */
@Composable
fun rememberSkyEarthHolder(container: AppContainer): SkyEarthHolder =
    remember(container) {
        SkyEarthHolder(container.skyEarth, container.watch, container.security)
    }

class SkyEarthHolder(
    private val repo: SkyEarthRepository,
    private val watch: WatchlistRepository,
    private val security: SecurityRepository,
) {

    var loading by mutableStateOf(true)
        private set

    /** 天组清单（有序）。 */
    var sky by mutableStateOf<List<SkyFactor>>(emptyList())
        private set

    /** 地组清单（有序，属于 [earthSymbol]）。 */
    var earth by mutableStateOf<List<SkyFactor>>(emptyList())
        private set

    var earthSymbol by mutableStateOf(SkyEarthRepository.DEFAULT_EARTH_SYMBOL)
        private set

    var earthName by mutableStateOf("")
        private set

    var skyBenchmark by mutableStateOf(SkyEarthRepository.DEFAULT_SKY_BENCHMARK)
        private set

    /** 失败提示（非空即展示）；任何动作失败都必须写这里。 */
    var error by mutableStateOf<String?>(null)
        private set

    /** 自选标的（symbol to name）——「切换标的」弹窗的数据源。 */
    var watchSymbols by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set

    /** 地组里「有清单的标的」（不含当前）——「复制其他标的条目」弹窗的数据源。 */
    var earthScopes by mutableStateOf<List<String>>(emptyList())
        private set

    fun dismissError() {
        error = null
    }

    // ---- 派生（全部现算，不存 state）----

    val skyScore: Int? get() = SkyEarthScore.scoreOf(sky)
    val skyLevel: SkyLevel get() = SkyEarthScore.levelOf(skyScore)
    val earthScore: Int? get() = SkyEarthScore.scoreOf(earth)
    val earthLevel: SkyLevel get() = SkyEarthScore.levelOf(earthScore)

    val skyJudged: Int get() = sky.count { it.score != null }
    val earthJudged: Int get() = earth.count { it.score != null }

    /** 30 天未更新条数（两组合计；**从未判断不算**，FR-SE-07）。 */
    val staleCount: Int
        get() {
            val now = nowEpochMs()
            return (sky + earth).count { SkyEarthScore.isStale(it.judgedAt, now) }
        }

    /** 天组「更新于 yyyy-MM-dd」（取最近一次判断时间）；没有任何判断 → null。 */
    val skyUpdatedDate: String?
        get() = sky.mapNotNull { it.judgedAt }.maxOrNull()?.let { formatLocalDateTime(it).take(10) }

    val earthUpdatedDate: String?
        get() = earth.mapNotNull { it.judgedAt }.maxOrNull()?.let { formatLocalDateTime(it).take(10) }

    /** 某个 scopeKey 的显示名（复制源列表用）；查不到就返回 symbol 本身。 */
    suspend fun displayNameOf(symbol: String): String =
        runCatching { repo.securityNameOf(symbol) }.getOrNull()?.takeIf { it.isNotBlank() } ?: symbol

    // ---- 动作 ----

    suspend fun load() {
        loading = true
        error = null
        runCatching {
            sky = repo.factors(SkyDomain.SKY, SkyEarthRepository.SKY_SCOPE)
            earthSymbol = repo.currentEarthSymbol()
            earth = repo.factors(SkyDomain.EARTH, earthSymbol)
            earthName = repo.securityNameOf(earthSymbol).orEmpty()
            skyBenchmark = repo.skyBenchmark()
            watchSymbols = watchSecurities()
            earthScopes = repo.earthScopes().filter { it != earthSymbol }
        }.onFailure { error = humanError("加载", it) }
        loading = false
    }

    /**
     * **只刷新「切换标的」弹窗要用的两样**（自选列表 + 有清单的地组标的）——
     * 打开弹窗时调用（L-3，审核报告 2026-10-09）。
     *
     * 为什么不用整个 [load]：load 会把 [loading] 置 true 并重读清单（弹窗打开时并无必要），
     * 这里只补"用户可能刚去自选管理加过标的"这一个缺口。
     */
    suspend fun refreshWatchSymbols() {
        runCatching {
            watchSymbols = watchSecurities()
            earthScopes = repo.earthScopes().filter { it != earthSymbol }
        }.onFailure { error = humanError("读取自选", it) }
    }

    /** 点一下三档即写库（无保存按钮）；历史留档由仓储层同事务完成。 */
    suspend fun setScore(id: String, attitude: SkyAttitude?) {
        runCatching {
            repo.setScore(id, attitude)
            refreshLists()
        }.onFailure { error = humanError("保存判断", it) }
    }

    /** 新增关注点；返回 null = 成功，否则返回失败原因（UI 弹窗展示）。 */
    suspend fun addFactor(domain: SkyDomain, title: String, note: String?): String? {
        val t = title.trim()
        if (t.isEmpty()) return "标题不能为空"
        val current = if (domain == SkyDomain.SKY) sky.size else earth.size
        if (current >= domain.maxCount) return "${domain.label}最多 ${domain.maxCount} 条，请先删减"
        val result = runCatching {
            repo.addFactor(
                domain = domain,
                scopeKey = scopeOf(domain),
                title = t.take(SkySeedParser.TITLE_MAX),
                note = note?.trim()?.take(SkySeedParser.NOTE_MAX)?.ifEmpty { null },
            )
            refreshLists()
        }
        return result.fold(onSuccess = { null }, onFailure = { humanError("新增", it) })
    }

    /** 改名 / 改依据；返回 null = 成功，否则失败原因。 */
    suspend fun renameFactor(id: String, title: String, note: String?): String? {
        val t = title.trim()
        if (t.isEmpty()) return "标题不能为空"
        val result = runCatching {
            repo.renameFactor(
                id = id,
                title = t.take(SkySeedParser.TITLE_MAX),
                note = note?.trim()?.take(SkySeedParser.NOTE_MAX)?.ifEmpty { null },
            )
            refreshLists()
        }
        return result.fold(onSuccess = { null }, onFailure = { humanError("保存", it) })
    }

    /** 删除关注点（级联删历史，仓储层同事务完成）。 */
    suspend fun removeFactor(id: String) {
        runCatching {
            repo.removeFactor(id)
            refreshLists()
        }.onFailure { error = humanError("删除", it) }
    }

    /** 拖拽 / 上下移动后保存顺序（传该组的完整 id 序列）。 */
    suspend fun reorder(domain: SkyDomain, idsInOrder: List<String>) {
        runCatching {
            repo.reorder(idsInOrder)
            refreshLists()
        }.onFailure { error = humanError("保存排序", it) }
    }

    /** 切换地组标的（原有清单保留在库里，切回来还在）。 */
    suspend fun switchSymbol(symbol: String) {
        runCatching {
            repo.setCurrentEarthSymbol(symbol)
            earthSymbol = symbol
            earth = repo.factors(SkyDomain.EARTH, symbol)
            earthName = repo.securityNameOf(symbol).orEmpty()
            earthScopes = repo.earthScopes().filter { it != symbol }
        }.onFailure { error = humanError("切换标的", it) }
    }

    /** 从其他标的复制条目标题（不带打分与依据）；返回复制条数。 */
    suspend fun copyTitlesFrom(sourceScope: String): Int {
        val result = runCatching {
            repo.copyTitlesFrom(SkyDomain.EARTH, sourceScope, earthSymbol)
                .also { refreshLists() }
        }
        return result.getOrElse {
            error = humanError("复制条目", it)
            0
        }
    }

    /**
     * 导入种子（预览页确认后调用）；返回实际入库条数。
     *
     * 上限拦截（技术说明书 §5.4 边界原则）：超出的**不导入**并写 [error] 说明原因 ——
     * 预览页已默认不勾选超限条目，这里是双保险。
     */
    suspend fun importSeeds(domain: SkyDomain, items: List<SkySeedItem>): Int {
        if (items.isEmpty()) {
            error = "没有勾选任何条目"
            return 0
        }
        val current = if (domain == SkyDomain.SKY) sky.size else earth.size
        val capacity = (domain.maxCount - current).coerceAtLeast(0)
        if (capacity <= 0) {
            error = "${domain.label}已有 $current 条（上限 ${domain.maxCount}），请先删减"
            return 0
        }
        val accepted = items.take(capacity)
        val result = runCatching {
            repo.importSeeds(domain, scopeOf(domain), accepted)
                .also { refreshLists() }
        }
        return result.getOrElse {
            error = humanError("导入", it)
            0
        }
    }

    // ---- 内部 ----

    private fun scopeOf(domain: SkyDomain): String =
        if (domain == SkyDomain.SKY) SkyEarthRepository.SKY_SCOPE else earthSymbol

    private suspend fun refreshLists() {
        sky = repo.factors(SkyDomain.SKY, SkyEarthRepository.SKY_SCOPE)
        earth = repo.factors(SkyDomain.EARTH, earthSymbol)
        earthScopes = repo.earthScopes().filter { it != earthSymbol }
    }

    /**
     * 自选标的（带名称）。watch.all() 只有 securityId，需要按 id 关联标的表。
     *
     * ⚠️ 用 `associateBy` 一次建索引（L-4，审核报告 2026-10-09）：原来对每条自选都做一次
     * `firstOrNull` 全表扫描，O(n·m)；标的量级大了会无谓变慢。
     */
    private suspend fun watchSecurities(): List<Pair<String, String>> {
        val ids = watch.all().map { it.securityId }
        if (ids.isEmpty()) return emptyList()
        val byId = security.securities().associateBy { it.id }
        return ids.mapNotNull { byId[it] }.map { it.symbol to it.name }
    }

    private fun humanError(action: String, e: Throwable): String =
        "$action 失败：${e.message?.takeIf { it.isNotBlank() } ?: "未知错误"}"
}
