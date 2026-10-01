package com.stocknote.data

import app.cash.sqldelight.db.SqlDriver
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.net.QuoteClient
import com.stocknote.data.platform.createEncryptedDriver
import com.stocknote.data.platform.createHttpClient
import com.stocknote.data.platform.createSecureKeyStore
import com.stocknote.data.repo.BackupRepository
import com.stocknote.data.repo.CashRepository
import com.stocknote.data.repo.SecurityRepository
import com.stocknote.data.repo.EmotionRepository
import com.stocknote.data.repo.CninfoRepository
import com.stocknote.data.repo.CsvRepository
import com.stocknote.data.repo.FxRepository
import com.stocknote.data.repo.NewsFavoriteRepository
import com.stocknote.data.repo.PlanRepository
import com.stocknote.data.repo.PortfolioRepository
import com.stocknote.data.repo.QuoteRepository
import com.stocknote.data.repo.ReviewRepository
import com.stocknote.data.repo.SettingsRepository
import com.stocknote.data.repo.TagRepository
import com.stocknote.data.repo.WatchlistRepository
import com.stocknote.data.repo.TradeRepository
import com.stocknote.data.security.SecureKeyStore
import com.stocknote.data.seed.DemoData
import io.ktor.client.HttpClient

/**
 * 依赖容器。
 *
 * M0 用手工装配而不是 Koin：真实依赖目前只有 4 个，引入 DI 框架只会增加一层
 * 排查成本，而现在最需要的是「链路能跑通」。等模块数量上来（M2 之后）再换 Koin，
 * 接口形状已经按可替换的方式设计（全部是构造注入）。
 */
class AppContainer(databaseName: String = DEFAULT_DB_NAME) {

    val keyStore: SecureKeyStore = createSecureKeyStore()

    val driver: SqlDriver =
        createEncryptedDriver(databaseName, keyStore.getOrCreateDatabasePassphrase())

    val db: StockNoteDb = StockNoteDb(driver)

    private val http: HttpClient = createHttpClient()

    val quoteClient: QuoteClient = QuoteClient(http)

    // ⚠️ 第 7 批（2026-09-29）：现金域**必须先建** —— portfolio 反过来依赖它。
    // ⚠️ 方向是单向的：CashRepository 不依赖任何 Repository（自给自足），否则会三向循环。
    val cash: CashRepository = CashRepository(db)

    val portfolio: PortfolioRepository = PortfolioRepository(db, quoteClient, driver, cash, keyStore)

    // ⚠️ 第 11 批：标的域（本地 CRUD / 搜索 / 删除 / 统计开关）。
    // ⚠️ 建在 portfolio 之后（写操作后要调它的 invalidateCurveCache）、plan / trade 之前
    //（它们的 CSV 导入建档要用 findOrCreateSecurity）。
    val security: SecurityRepository = SecurityRepository(db, portfolio, quoteClient)

    // ⚠️ 2026-09-28 拆分（阶段 1：委托式）：按业务域暴露 Repository，让调用方可以逐步
    // 依赖到域接口上。当前它们**只转发**给 portfolio（行为零变化），实现仍留在原类；
    // 阶段 2 再把代码逐个搬过来 —— 那时调用方已依赖域 Repository，搬动对它们无感。
    // 之所以不一次性搬：原类 117 个方法 / 189 处调用，而 data 层**没有针对它的行为测试**，
    // 一次性重构无法验证。
    val fx: FxRepository = FxRepository(portfolio)
    // ⚠️ 计划域的**实现已搬进 PlanRepository**（阶段 2），故它需要 db；
    // 第二个参数仅用于尚未迁移的计划 CSV，搬完即可去掉。
    val plan: PlanRepository = PlanRepository(db, portfolio, security)
    // ⚠️ 第 6 批：交易域的 9 个方法（3 查询 + 4 截图 + 2 质量评级）实现已搬进，
    // 故需要 db；仍转发的 5 个（增改删/持仓/删除预览）继续走 portfolio。
    val trade: TradeRepository = TradeRepository(db, portfolio, cash, security)
    // ⚠️ 历史收盘价（2 个方法）的实现已搬进 QuoteRepository，故需要 db。
    val quote: QuoteRepository = QuoteRepository(db, portfolio)

    // ⚠️ 设置域：**实现已搬进 SettingsRepository**（阶段 2）。选它是因为它在原类内部零自用
    // （叶子域），搬走不牵动任何逻辑 —— 与汇率域（被内部 10+ 处依赖）形成对照。
    val settings: SettingsRepository = SettingsRepository(db, keyStore)

    // ⚠️ 两个叶子域（原类内部零自用，grep 计数全为 1）：巨潮风险快照 与 资讯收藏。
    val cninfo: CninfoRepository = CninfoRepository(db)
    val news: NewsFavoriteRepository = NewsFavoriteRepository(db)

    // ⚠️ 第 4 批（2026-09-29）：复盘域 与 标签域，同为叶子域。
    val review: ReviewRepository = ReviewRepository(db)
    val tag: TagRepository = TagRepository(db)

    // ⚠️ 第 5 批（2026-09-29）：自选域（10 个方法，本批最大收益）。
    val watch: WatchlistRepository = WatchlistRepository(db)

    // ⚠️ 第 6 批：CSV 域的**生成侧**（4 个方法纯读拼串）实现已搬进，只需 db。
    // 导入侧（applyTradesCsv / applyCashFlowsCsv）仍在 PortfolioRepository。
    val csv: CsvRepository = CsvRepository(db)

    // ⚠️ 第 9 批：情绪标签域（零依赖，只读写 app_setting 的一条 key）。
    val emotion: EmotionRepository = EmotionRepository(db)

    // ⚠️ 第 9 批：备份/恢复域。需要 driver（逐表 dump/restore）；
    // 第二个参数用于恢复成功后失效资产曲线缓存 —— 方向是单向的（原类不持有本类）。
    val backup: BackupRepository = BackupRepository(driver, portfolio)

    fun seedDemoDataIfEmpty() = DemoData.seedIfEmpty(db)

    fun close() {
        runCatching { http.close() }
        runCatching { driver.close() }
    }

    companion object {
        const val DEFAULT_DB_NAME = "stocknote.db"
    }
}
