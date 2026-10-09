package com.stocknote.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.data.AppContainer
import com.stocknote.feature.nav.AppNav
import com.stocknote.feature.nav.AppRoute
import com.stocknote.feature.state.AppDisplaySettings
import com.stocknote.feature.state.PlanHolder
import com.stocknote.feature.state.Screen
import com.stocknote.feature.state.rememberAnalysisHolder
import com.stocknote.feature.state.rememberAppStateHolder
import com.stocknote.feature.state.rememberFlashNewsHolder
import com.stocknote.feature.state.rememberHoldingsDetailFilterHolder
import com.stocknote.feature.state.rememberNewsHolder
import com.stocknote.feature.state.rememberPlanEditHolder
import com.stocknote.feature.state.rememberPlanHolder
import com.stocknote.feature.state.rememberSecurityDetailHolder
import com.stocknote.feature.state.rememberTradeFormHolder
import com.stocknote.feature.theme.StockNoteColors
import com.stocknote.feature.theme.StockNoteTheme
import com.stocknote.feature.ui.ExploreScreen
import com.stocknote.feature.ui.FinanceScreen
import com.stocknote.feature.ui.FlashNewsScreen
import com.stocknote.feature.ui.CninfoSyncScreen
import com.stocknote.feature.ui.RiskScanScreen
import com.stocknote.feature.ui.NewsDetailScreen
import com.stocknote.feature.ui.NewsListScreen
import com.stocknote.feature.ui.AboutScreen
import com.stocknote.feature.ui.AnalysisChartScreen
import com.stocknote.feature.ui.CashFlowScreen
import com.stocknote.feature.ui.DataManageScreen
import com.stocknote.feature.ui.DiaryScreen
import com.stocknote.feature.ui.DividendAlertHost
import com.stocknote.feature.ui.DividendScreen
import com.stocknote.feature.ui.FxRateScreen
import com.stocknote.feature.ui.HoldingsDetailScreen
import com.stocknote.feature.ui.HoldingsScreen
import com.stocknote.feature.ui.PlanEditScreen
import com.stocknote.feature.ui.PlanScreen
import com.stocknote.feature.ui.PlaneShooterScreen
import com.stocknote.feature.ui.ProtoTabBar
import com.stocknote.feature.ui.PnlCalendarScreen
import com.stocknote.feature.ui.ReviewFormScreen
import com.stocknote.feature.ui.ReturnAnalysisScreen
import com.stocknote.feature.ui.SecurityDetailScreen
import com.stocknote.feature.ui.SettingsScreen
import com.stocknote.feature.ui.SkyEarthScreen
import com.stocknote.feature.ui.SkyFactorDetailScreen
import com.stocknote.feature.ui.SkySeedImportScreen
import com.stocknote.feature.ui.StatisticScreen
import com.stocknote.feature.ui.TagManageScreen
import com.stocknote.feature.ui.TradeFormScreen
import com.stocknote.feature.ui.WatchlistAddScreen
import com.stocknote.feature.ui.WatchlistScreen
import com.stocknote.feature.ui.rememberCashFlowHolder
import com.stocknote.feature.ui.rememberDiaryHolder
import com.stocknote.feature.ui.rememberDividendHolder
import com.stocknote.feature.ui.rememberReviewFormHolder
import com.stocknote.feature.ui.rememberSettingsHolder
import com.stocknote.feature.ui.rememberTagManageHolder
import com.stocknote.feature.ui.rememberWatchAddHolder
import com.stocknote.feature.ui.rememberWatchlistHolder

/**
 * 应用根组件 —— **Android 与 iOS 共用这一份 UI**。
 * 页面结构严格对照 `界面原型-新版/`：4 个底部 tab（统计 / 持仓 / 记一笔 / 分析）。
 */
@Composable
fun App(
    container: AppContainer,
    /**
     * 版本号：**由各平台外壳传入**（单一来源）—— 显示在「关于」页，并写进导出运行日志的头部。
     * Android = `BuildConfig.VERSION_NAME/VERSION_CODE`；iOS = `Info.plist`；桌面 = `:desktopApp` 常量。
     */
    versionLabel: String = "",
    /**
     * 该平台的最低系统要求（老周 2026-10-02）：此前「关于」页把 `"Android 7.0+"` **写死在 commonMain**，
     * iOS 装出来也显示成 Android —— 所以改成由外壳传入，Android 直接吃默认值。
     */
    platformLabel: String = "Android 7.0+",
    /**
     * **桌面外壳模式**（老周 2026-09-30）：隐藏底部导航栏。
     *
     * 桌面版把 5 个页面做成**窗口菜单栏**（见 `:desktopApp` 的 `MenuBar`）——
     * 手机上"底部 tab + 左右滑动"是对的，桌面上那条 66dp 的 tab 栏既占地方、
     * 也不符合桌面操作习惯（鼠标点顶部菜单）。Android/iOS 保持 false。
     */
    desktopShell: Boolean = false,
    /**
     * 由外壳**外部创建并传入**的 holder。
     *
     * 为什么需要：菜单栏必须能调 `holder.select(页面)` 才能切页，而 holder 原本
     * 只在 [App] 内部 remember（外面拿不到）。传 null 时按原逻辑内部创建 —— Android/iOS 不受影响。
     */
    shellHolder: com.stocknote.feature.state.AppStateHolder? = null,
) {
    StockNoteTheme {

        // ---- 隐藏盈亏模式（REQ-VIEW-09，老周 2026-09-30）----
        // 把设置里的开关同步给 core 的**展示层**格式化函数（Format.money / quantity / percent… 统一遮罩成 •••）。
        // ⚠️ 刻意在**组合期直接赋值**，不用 SideEffect / LaunchedEffect：那两者要等当前帧之后才执行，
        //    切换瞬间会漏出一帧真实数字（演示时恰恰会被看到）。这里同时**读取**该 state 建立了订阅 ——
        //    开关一变 → 本组件重组 → 赋值 → 子树在**同一帧**就读到遮罩态。
        val privacyOn by AppDisplaySettings.privacyMode.collectAsState()
        com.stocknote.core.format.Format.privacyMasked = privacyOn

        val holder = shellHolder ?: rememberAppStateHolder(container)
        val state by holder.state.collectAsState()
        val navStack by AppNav.stack.collectAsState()

        // 计划列表 holder 提升到顶层（老周 2026-09-19）：
        // 标的详情页的「🎯 查看计划」要给它预置搜索词与排序，所以不能只在 PlanScreen 内部 remember。
        val planHolder = rememberPlanHolder(container.portfolio, container.plan, container.quote, container.security)

        // ⚠️ 修 bug（2026-09-28，老周报）：资讯 holder **必须提升到顶层**。
        // 此前在 `is AppRoute.NewsList ->` 分支里 remember，进资讯详情（切到 NewsDetail 分支）
        // 时该分支组合被销毁、holder 连同 symbol 一起丢 → 返回时 holder 重建，
        // bootstrap() 又选回"自选第一只"，表现为「返回后标的换了、不是真正的上一页」。
        val newsHolder = rememberNewsHolder(container)

        // 持仓明细页的筛选 / 排序 / 搜索 / 页码也提升到顶层（老周 2026-10-04）：
        // 该页是二级路由，点某行看详情再返回时，页面组合会被重建 —— 状态放页面里就会丢。
        val holdingsDetailHolder = rememberHoldingsDetailFilterHolder()

        // 7×24 快讯（老周 2026-10-04）：提升到顶层，返回探索页再进来时列表还在、不必重拉
        val flashNewsHolder = rememberFlashNewsHolder(container)

        // 看天看地（老周 2026-10-09）：同样提升到顶层 —— 进种子导入页 / 关注点详情再返回时，
        // 清单、分数与编辑状态不能丢（NewsHolder 当年就是在页面分支里 remember 踩的坑）。
        val skyEarthHolder = com.stocknote.feature.state.rememberSkyEarthHolder(container)

        // 设置类 holder 同样提升到顶层（老周 2026-09-22）：
        // 「设置 / 数据管理 / 汇率管理」现在是三个独立页面，共用同一份状态与加载逻辑。
        val settingsHolder = rememberSettingsHolder(container.portfolio, container.settings, container.plan, container.csv, container.trade, container.backup)

        var dataVersion by remember { mutableIntStateOf(0) }

        LaunchedEffect(Unit) { holder.load() }
        // 启动图数据就绪信号（老周 2026-09-17，改）：**首屏快照 + 最大回撤都出来**才算就绪
        // —— 老周要求"等最大回撤也展示后再关启动图"。曲线算不出来（失败/无数据）时也要放行，
        //    否则启动图会卡到 5 秒上限；用 equityLoading 结束 && (maxDrawdown 有值 || equityError 有值) 判定。
        LaunchedEffect(Unit) {
            snapshotFlow {
                Triple(
                    state.loading,
                    state.equityLoading,
                    state.maxDrawdown != null || state.equityError != null,
                )
            }.collect { (firstLoading, curveLoading, curveDone) ->
                if (!firstLoading && !curveLoading && curveDone) {
                    com.stocknote.feature.state.AppDisplaySettings.markDataReady()
                }
            }
        }
    // 资产曲线（M4 真值）后台异步算——不阻塞统计页首屏；
    // dataVersion 变化 = 账本有增删改（记一笔/出入金/分红），此时强制重算
    LaunchedEffect(dataVersion) { holder.loadEquity(force = dataVersion > 0) }
    // 启动检测「未登记分红送配」（半自动：只提示，用户确认后才写账本）—— 老周 2026-09-16
    LaunchedEffect(dataVersion) { holder.checkDividends() }

        val top = navStack.lastOrNull()

        // ---- 资产曲线（组合总资产）+ 沪深300 基准 ----
        // 老周 2026-09-19：「图表中心」整页作废，曲线迁进页面；
        // 老周 2026-10-08：再从分析页**迁到「持仓」页顶部**（分析页改作「看天看地」），
        // 所以拉取门控跟着改成「持仓页可见」。
        // 仅当该页可见时才拉取，避免无谓网络请求；曲线走 holder 缓存（equityCurveCached），
        // 与统计页回撤/热力图/收益分析共用同一次拉取。
        val equityCardVisible = when (top) {
            null -> state.screen == Screen.HOLDINGS
            is AppRoute.Tabs -> top.screen == Screen.HOLDINGS
            else -> false
        }
        var equityCurveResult by remember {
            mutableStateOf<com.stocknote.data.repo.PortfolioRepository.EquityCurveResult?>(null)
        }
        var benchSeries by remember { mutableStateOf<List<Pair<String, Double>>?>(null) }
        LaunchedEffect(equityCardVisible, dataVersion) {
            if (!equityCardVisible) return@LaunchedEffect
            equityCurveResult = runCatching { container.portfolio.equityCurveCached() }.getOrNull()
            benchSeries = runCatching {
                // 与收益率分析页同一份基准数据，同样加自动重试（老周 2026-09-24）：
                // 偶发失败会让资产曲线的沪深300对比线整条消失，且不重试就一直是空的
                var rows = emptyList<com.stocknote.data.net.QuoteClient.Candle>()
                for (attempt in 0 until 3) {
                    rows = runCatching { container.quoteClient.fetchClosesWithDates("sh000300", 400) }
                        .getOrDefault(emptyList())
                    if (rows.size >= 5) break
                    kotlinx.coroutines.delay(400L * (attempt + 1))
                }
                rows.map { it.date to it.close }
            }.getOrNull()
        }

        fun refresh() {
            dataVersion++
            // 先用缓存快速出结果，再联网补行情二次刷新 ——
            // 否则新记的标的没有行情，持仓页会显示「成本总价」+ 浮动盈亏 0（老周 2026-09-18 报）
            holder.load(refreshQuotes = false, backgroundQuotesAfter = true)
        }

        // ⚠️ 隐藏盈亏模式必须让**整棵 UI 重建**（老周 2026-09-30 真机实测发现漏遮）：
        // `Format.privacyMasked` 是普通全局变量、**不是 Compose 状态** —— 没读过
        // `AppDisplaySettings.privacyMode` 的 Composable（例如统计页的 MetricCell）在重组时
        // 会被 Compose 的**跳过优化**略过，于是出现"Hero 卡遮住了、指标卡还露着真金额"。
        // 用 key(privacyOn) 把 UI 主体挂到开关上：开关一变 → 子树全部重新组合 → 每个 Format.* 都读新状态。
        // ⚠️ holders / state 都声明在 key **之外**，重建不会丢数据与账本状态（页面内滚动位置会重置，可接受）。
        // ---- 全局「待登记分红」弹窗（老周 2026-09-30）----
        // ⚠️ 刻意放在**页面之外**：此前它挂在持仓页，而 App 默认落「探索」页、
        //    统计页刷新时也不在持仓页 → 检测到了却弹不出来。放这里任何页面都能弹。
        DividendAlertHost(
            state = state,
            onRegister = { items -> holder.registerDividends(items) { refresh() } },
        )

        androidx.compose.runtime.key(privacyOn) {
        when (val route = top) {
            // 新建 / 编辑交易计划（REQ-PLAN-01）：全屏表单页**不带底部导航**（同记一笔）
            is AppRoute.PlanForm -> {
                val planHolder = rememberPlanEditHolder(container.portfolio, container.plan, container.settings, container.tag, container.watch, container.security)
                LaunchedEffect(route.planId) { planHolder.start(route.planId) }
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    PlanEditScreen(
                        holder = planHolder,
                        // 保存/删除后回列表：dataVersion++ 让计划列表页重新加载
                        onDone = { dataVersion++; AppNav.pop() },
                        onBack = { AppNav.pop() },
                    )
                }
            }

            is AppRoute.About -> {
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    AboutScreen(versionLabel = versionLabel, platformLabel = platformLabel)
                }
            }

is AppRoute.CashFlow -> {
                val cfHolder = rememberCashFlowHolder(container.portfolio, container.cash)
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    CashFlowScreen(
                        holder = cfHolder,
                        onDone = {
                            dataVersion++
                            holder.load(refreshQuotes = false, backgroundQuotesAfter = true)
                        },
                    )
                }
            }

            is AppRoute.DividendForm -> {
                val divHolder = rememberDividendHolder(container.portfolio, route.securityId)
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    DividendScreen(
                        holder = divHolder,
                        onDone = {
                            dataVersion++
                            holder.load(refreshQuotes = false, backgroundQuotesAfter = true)
                        },
                    )
                }
            }

            is AppRoute.Watchlist -> {
                val wlHolder = rememberWatchlistHolder(container.portfolio, container.watch, container.security)
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    WatchlistScreen(
                        holder = wlHolder,
                        onOpenSecurity = { AppNav.push(AppRoute.SecurityDetail(it)) },
                        onAdd = { AppNav.push(AppRoute.WatchlistAdd) },
                    )
                }
            }

            is AppRoute.WatchlistAdd -> {
                val addHolder = rememberWatchAddHolder(container.portfolio, container.watch, container.security)
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    WatchlistAddScreen(holder = addHolder)
                }
            }

            is AppRoute.Diary -> {
                val diaryHolder = rememberDiaryHolder(container.portfolio, container.review, container.trade, container.security)
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    DiaryScreen(
                        holder = diaryHolder,
                        onOpenSecurity = { AppNav.push(AppRoute.SecurityDetail(it)) },
                        onWriteReview = { AppNav.push(AppRoute.ReviewForm(null)) },
                        onEditReview = { AppNav.push(AppRoute.ReviewForm(it)) },
                    )
                }
            }

            is AppRoute.ReviewForm -> {
                val reviewHolder = rememberReviewFormHolder(container.portfolio, container.review, container.trade)
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    ReviewFormScreen(holder = reviewHolder, preset = route.preset)
                }
            }

            is AppRoute.TagManage -> {
                val tagHolder = rememberTagManageHolder(container.portfolio, container.tag, container.trade)
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    TagManageScreen(holder = tagHolder)
                }
            }

            is AppRoute.Settings -> {
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    // onLedgerChanged：现金等价物名单变化会改持仓/现金口径 → 让外层重算（老周 2026-09-20）
                    SettingsScreen(holder = settingsHolder, onLedgerChanged = { refresh() })
                }
            }

            // 数据管理 / 汇率管理：老周 2026-09-22 从设置页拆出的两个独立页（共用 settingsHolder）
            is AppRoute.DataManage -> {
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    DataManageScreen(
                        holder = settingsHolder,
                        onBack = { AppNav.pop() },
                        // 导出的运行日志头部要带 App 版本 —— 排查时「用户装的是哪个版本」是关键信息
                        //（老周这次在应用宝里装到的就是旧版本，靠版本号一眼可辨）
                        versionLabel = versionLabel,
                    )
                }
            }

            is AppRoute.FxRate -> {
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    FxRateScreen(holder = settingsHolder, onBack = { AppNav.pop() })
                }
            }

            is AppRoute.PnlCalendar -> {
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    PnlCalendarScreen(
                        points = state.equityPoints,
                        cashFlowsByDate = state.equityDailyCashFlows,
                        // 标的名称（老周 2026-10-01）：明细要按名字列，而 pnlBySecurity 里只有 id
                        securityNames = state.equitySecurityNames,
                        // 点某天时按需查当天流水（交易/出入金/分红）—— 不在打开页面就全表查一遍
                        loadDayDetail = { date -> container.portfolio.dayDetail(date) },
                        // 没选中某天时的「当月明细」要带当月流水汇总（老周 2026-10-08），同样按需查
                        loadMonthDetail = { month -> container.portfolio.monthDetail(month) },
                    )
                }
            }

            is AppRoute.ReturnAnalysis -> {
                // 基准（沪深300）用指数代码拉区间涨跌，取不到时传 null（页面如实显示 —）
                var bench by remember { mutableStateOf<Double?>(null) }
                var stats by remember { mutableStateOf<com.stocknote.data.repo.PortfolioRepository.ReturnStats?>(null) }
                // ⚠️ key 里带上 dataVersion（老周 2026-09-24）：原来只看 equityPoints.size，
                // 曲线点数不变时下拉刷新/重进都不会重拉基准，一次失败就永久显示「—」。
                LaunchedEffect(state.equityPoints.size, dataVersion) {
                    stats = runCatching { container.portfolio.returnStats() }.getOrNull()
                    bench = runCatching {
                        val p = state.equityPoints
                        if (p.size >= 2) {
                            // ⚠️ 自动重试（老周 2026-09-24 反馈「沪深300 基准取不到值了」）：
                            // 首次进页面时行情/曲线/基准同时抢网络，基准偶发失败 → 原来直接置 null 且不再重试，
                            // 表现为时好时坏（多数时候能取到，偶尔一直显示「—」）。重试 3 次、退避递增。
                            var closes = emptyList<com.stocknote.data.net.QuoteClient.Candle>()
                            for (attempt in 0 until 3) {
                                closes = runCatching { container.quoteClient.fetchClosesWithDates("sh000300", 400) }
                                    .getOrDefault(emptyList())
                                if (closes.size >= 5) break
                                kotlinx.coroutines.delay(400L * (attempt + 1))
                            }
                            val first = p.first().date
                            val last = p.last().date
                            val a = closes.filter { it.date <= first }.lastOrNull()?.close
                            val b = closes.filter { it.date <= last }.lastOrNull()?.close
                            if (a != null && b != null && a > 0) (b - a) / a else null
                        } else null
                    }.getOrNull()
                }
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    ReturnAnalysisScreen(
                        points = state.equityPoints,
                        stats = stats,
                        benchmarkChange = bench,
                    )
                }
            }

            // ---- 个股资讯（老周 2026-09-24）：探索 →「个股资讯」→「资讯详情」 ----
            is AppRoute.NewsList -> {
                // ⚠️ 修 bug（2026-09-28）：直接使用**顶层**的 newsHolder —— 此前在这里局部
                // remember，进详情（切到 NewsDetail 分支）时组合被销毁，holder 连同 symbol
                // 一起丢；返回时 holder 重建、bootstrap() 选回"自选第一只"，
                // 表现为「返回后标的换了、不是真正的上一页」。
                NewsListScreen(
                    holder = newsHolder,
                    repo = container.portfolio,
                    watch = container.watch,
                    quotes = container.quoteClient,
                    security = container.security,
                    onBack = { AppNav.pop() },
                    onOpenDetail = { item ->
                        // 详情页也要能加星（老周 2026-09-30）：收藏主键是 id，而路由只带 title/url
                        // → push 前把整个条目暂存在 holder 上（holder 已提升到顶层，跨路由不丢）
                        newsHolder.setDetail(item)
                        // detailUrl 统一处理：研报的 http → https（否则 Android 报 CLEARTEXT 错），
                        // 公告 url 为空时用 id 拼腾讯详情模板（老周 2026-09-24）
                        AppNav.push(
                            AppRoute.NewsDetail(
                                item.title,
                                com.stocknote.data.net.NewsSource.detailUrl(item),
                            ),
                        )
                    },
                )
            }

            is AppRoute.NewsDetail -> {
                // 详情页收藏（老周 2026-09-30）：条目来自列表页暂存的 detailItem，
                // 收藏态与列表页共用同一份 favoriteIds；进页面先刷一次保证不虚
                val favScope = rememberCoroutineScope()
                val newsItem = newsHolder.detailItem
                LaunchedEffect(newsItem) { newsHolder.refreshFavorites() }
                NewsDetailScreen(
                    title = route.title,
                    url = route.url,
                    favorited = newsItem != null && newsItem.id in newsHolder.favoriteIds,
                    onToggleFavorite = newsItem?.let { item ->
                        { favScope.launch { newsHolder.toggleFavorite(item) } }
                    },
                    onBack = { AppNav.pop() },
                )
            }

            // ---- 巨潮诉讼/担保同步（老周 2026-09-24）：设置 →「诉讼担保查询」手工拉取存本地 ----
            is AppRoute.CninfoSync -> {
                CninfoSyncScreen(
                    repo = container.portfolio,
                    cninfo = container.cninfo,
                    quotes = container.quoteClient,
                    onBack = { AppNav.pop() },
                )
            }

            // ---- 个股风险扫雷（老周 2026-09-24）：探索 →「个股扫雷」（4 维度 24 项）----
            is AppRoute.RiskScan -> {
                val riskHolder = remember {
                    com.stocknote.feature.state.RiskScanHolder(container.portfolio, container.watch, container.cninfo, container.quoteClient, container.security)
                }
                RiskScanScreen(
                    holder = riskHolder,
                    repo = container.portfolio,
                    watch = container.watch,
                    quotes = container.quoteClient,
                    security = container.security,
                    onBack = { AppNav.pop() },
                )
            }

            // ---- 7×24 快讯（老周 2026-10-04）：探索 →「7×24 快讯」（财联社电报）----
            is AppRoute.FlashNews -> {
                FlashNewsScreen(
                    holder = flashNewsHolder,
                    onBack = { AppNav.pop() },
                )
            }

            // ---- 星际战机（老周 2026-10-04）：探索 →「星际战机」（内嵌 H5 canvas 小游戏，离线）----
            is AppRoute.PlaneShooter -> {
                PlaneShooterScreen(onBack = { AppNav.pop() })
            }

            // ---- 分析图表（老周 2026-10-08）：统计分析页「📊 分析图表」→ 绩效类四块（二级页）----
            is AppRoute.AnalysisChart -> {
                AnalysisChartScreen(
                    holder = rememberAnalysisHolder(container.portfolio, container.cash),
                    refreshKey = dataVersion,
                    onBack = { AppNav.pop() },
                )
            }

            // ---- 看天看地 · 种子导入（老周 2026-10-09）：复制提示词 → 粘贴 → 预览 → 导入 ----
            is AppRoute.SkySeedImport -> {
                SkySeedImportScreen(
                    holder = skyEarthHolder,
                    initialDomain = route.domain,
                    onBack = { AppNav.pop() },
                )
            }

            // ---- 看天看地 · 关注点详情（老周 2026-10-09）：判断阶梯线 × 同期基准走势 ----
            is AppRoute.SkyFactorDetail -> {
                SkyFactorDetailScreen(
                    holder = skyEarthHolder,
                    factorId = route.factorId,
                    repo = container.skyEarth,
                    quoteClient = container.quoteClient,
                    onBack = { AppNav.pop() },
                )
            }

            // ---- 个股财务数据（老周 2026-09-24）：探索 →「个股财务」（东财 F10）----
            is AppRoute.Finance -> {
                val financeHolder = remember {
                    com.stocknote.feature.state.FinanceHolder(container.portfolio, container.watch, container.quoteClient, container.security)
                }
                FinanceScreen(
                    holder = financeHolder,
                    repo = container.portfolio,
                    watch = container.watch,
                    quotes = container.quoteClient,
                    security = container.security,
                    onBack = { AppNav.pop() },
                )
            }

            is AppRoute.TradeForm -> {
                val formHolder = rememberTradeFormHolder(container.portfolio, container.trade, container.fx, container.quote, container.settings, container.tag, container.watch, container.emotion, container.security)
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    TradeFormScreen(
                        holder = formHolder,
                        securityId = route.securityId,
                        editTxId = route.editTxId,
                        onSaved = { refresh(); AppNav.pop() },
                    )
                }
            }

            is AppRoute.SecurityDetail -> {
                val detailHolder = rememberSecurityDetailHolder(container.portfolio, container.trade, container.security)
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            SecurityDetailScreen(
                                holder = detailHolder,
                                securityId = route.securityId,
                                refreshKey = dataVersion,
                                onEdit = { sid, txId -> AppNav.push(AppRoute.TradeForm(sid, txId)) },
                                onAddNew = { sid -> AppNav.push(AppRoute.TradeForm(sid, null)) },
                                onAddDividend = { sid -> AppNav.push(AppRoute.DividendForm(sid)) },
                                // 「🎯 查看计划」→ 计划 tab（老周 2026-09-19）：
                                // 按该标的**名称**预置搜索 + 排序切「距达成最近」，再清栈回一级页并切 tab。
                                // 顺序不能反：先预置（此时 PlanHolder 还在内存里），再切页。
                                onViewPlan = { securityName ->
                                    planHolder.applyQuickFilter(
                                        keyword = securityName,
                                        sort = PlanHolder.Sort.NEAREST,
                                    )
                                    holder.select(Screen.PLAN)
                                    AppNav.popToTabs()
                                },
                                onChanged = { refresh() },
                            )
                        }
                        // 二级页无底部导航（与原型一致）
                    }
                }
            }

            // ---- 持仓明细（老周 2026-10-02）：持仓页「持仓 TOP5 › 全部」进入的二级页 ----
            is AppRoute.HoldingsDetail -> {
                Box(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
                    HoldingsDetailScreen(
                        state = state,
                        holder = holdingsDetailHolder,
                        onOpenSecurity = { AppNav.push(AppRoute.SecurityDetail(it)) },
                        onBack = { AppNav.pop() },
                    )
                }
            }

            null -> MainTabs(
                state = state,
                holder = holder,
                container = container,
                planHolder = planHolder,
                skyEarthHolder = skyEarthHolder,
                dataVersion = dataVersion,
                equityCurveResult = equityCurveResult,
                benchSeries = benchSeries,
                onTab = { label ->
                    // 修复：切 tab 时清空导航栈——否则从统计页「全部›」等入口
                    // push 的 Tabs 路由会卡住栈顶，导致其他 tab 点不动
                    AppNav.popToTabs()
                    holder.select(Screen.entries.first { it.label == label })
                },
                showBottomBar = !desktopShell,
            )

            is AppRoute.Tabs -> MainTabs(
                state = state,
                holder = holder,
                container = container,
                planHolder = planHolder,
                skyEarthHolder = skyEarthHolder,
                dataVersion = dataVersion,
                equityCurveResult = equityCurveResult,
                benchSeries = benchSeries,
                onTab = { label -> holder.select(Screen.entries.first { it.label == label }) },
                showBottomBar = !desktopShell,
            )
        }
        }   // key(privacyOn)：隐藏盈亏模式切换时整棵 UI 重建，见上方说明
    }
}

/**
 * 五个主页面（统计分析 / 我的持仓 / 探索 / 交易计划 / 看天看地）——**左右滑动切换**（老周 2026-09-21）。
 *
 * 用 [HorizontalPager] 承载，底部 tab 与滑动**双向同步**：
 *  - 滑动落定 → `holder.select(该页)`；
 *  - 外部切页（如底部 tab / 「🎯 查看计划」）→ 动画滚到对应页。
 *
 * `beyondViewportPageCount = 3` = 四个页面**都留在组合里**：否则滑走的页面会被销毁，
 * 回来时 `remember` 的筛选 / 排序 / 搜索词全部复位（体验像"被重置了"）。
 * 页内都是 LazyColumn，只组合可视行，常驻四页的内存开销可忽略。
 */
@Composable
private fun MainTabs(
    state: com.stocknote.feature.state.AppUiState,
    holder: com.stocknote.feature.state.AppStateHolder,
    container: AppContainer,
    planHolder: PlanHolder,
    /** 看天看地 holder（App 顶层 remember，见 skyEarthHolder 的说明）。 */
    skyEarthHolder: com.stocknote.feature.state.SkyEarthHolder,
    dataVersion: Int,
    equityCurveResult: com.stocknote.data.repo.PortfolioRepository.EquityCurveResult?,
    benchSeries: List<Pair<String, Double>>?,
    onTab: (String) -> Unit,
    /**
     * 是否渲染底部导航栏（老周 2026-09-30）：桌面版传 false ——
     * 那 5 个页面改由**窗口菜单栏**切换，页面本身（HorizontalPager）完全不变。
     */
    showBottomBar: Boolean = true,
) {
    val pages = Screen.entries
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = state.screen.ordinal.coerceIn(0, pages.lastIndex),
        pageCount = { pages.size },
    )

    // ⚠️ 程序发起的翻页（点底部 tab / 统计页跳转）要打标记：
    // 多页动画途中 currentPage 会**先经过中间页**（0→2 途中先变 1），
    // 若此时把中间页写回 state.screen，下面的正向 effect 会因 key 变化重启并
    // **取消在飞的动画**，页面就停在中间页
    // （真机 2026-09-22：统计页点「计划」落在持仓页，凡跨页跳转皆如此）。
    // 手势拖动/甩动不带此标记，照常反向同步 tab 高亮。
    var programmaticScroll by remember { mutableStateOf(false) }

    // 隐藏盈亏（REQ-VIEW-09）落库用：MainTabs 是独立 Composable，拿不到 App 那边的 settingsHolder，
    // 但 `container` 是参数、里面有同一份 SettingsRepository。
    val privacyScope = rememberCoroutineScope()

    // 滑动 → 切 tab（currentPage 落定后才变，不会在拖动途中反复触发；
    // 程序动画途中的中间页跳变被上方标记拦下，不同步）
    LaunchedEffect(pagerState.currentPage) {
        if (programmaticScroll) return@LaunchedEffect
        pages.getOrNull(pagerState.currentPage)?.let { target ->
            if (target != state.screen) holder.select(target)
        }
    }
    // 外部切 tab → 滚到对应页
    LaunchedEffect(state.screen) {
        val idx = state.screen.ordinal
        // ⚠️ 除了「页码不同」，还要处理「页码相同但停在两页中间」：
        // 动画被打断（锁屏/截图/重组）后 currentPage 已落到目标页、offsetFraction 却非 0，
        // 旧条件直接跳过 → 翻页器永远卡在两页中间（真机 2026-09-21 实测复现）。
        if (pagerState.currentPage != idx || pagerState.currentPageOffsetFraction != 0f) {
            programmaticScroll = true
            try {
                pagerState.animateScrollToPage(idx)
            } finally {
                // 动画完成/被取消（用户中途伸手接管、锁屏）都要摘标记，
                // 否则之后的手势滑动再也不反向同步 tab
                programmaticScroll = false
            }
        }
    }

    Scaffold(containerColor = StockNoteColors.Background) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 3,
            ) { page ->
                when (pages[page]) {
                    Screen.STATISTIC -> StatisticScreen(
                        state = state,
                        onRefresh = { holder.load(refreshQuotes = true) },
                        onRefreshAll = { holder.refreshAll() },
                        onDismissRefreshMessage = { holder.dismissRefreshMessage() },
                        onOpenSecurity = { AppNav.push(AppRoute.SecurityDetail(it)) },
                        // 👁 切换全局「隐藏盈亏」（老周 2026-09-30）：
                        // 先改内存态（App 根部据此设置 Format.privacyMasked → **同一帧**全部页面变遮罩），
                        // 再落库（重启后仍是隐私态）。设置页里的开关走 SettingsHolder，两边最终一致。
                        onTogglePrivacy = {
                            val next = !AppDisplaySettings.privacyMode.value
                            AppDisplaySettings.setPrivacyMode(next)
                            privacyScope.launch { container.settings.setPrivacyMode(next) }
                        },
                    )

                    // 资产曲线卡在**本页顶部**（老周 2026-10-08 从分析页迁来）
                    Screen.HOLDINGS -> HoldingsScreen(
                        state = state,
                        onOpenSecurity = { AppNav.push(AppRoute.SecurityDetail(it)) },
                        equityCurve = equityCurveResult,
                        benchmark = benchSeries,
                    )

                    Screen.EXPLORE -> ExploreScreen(
                        onOpenNews = { AppNav.push(AppRoute.NewsList) },
                        onOpenFinance = { AppNav.push(AppRoute.Finance) },
                        onOpenRiskScan = { AppNav.push(AppRoute.RiskScan) },
                        onOpenFlashNews = { AppNav.push(AppRoute.FlashNews) },
                        onOpenGame = { AppNav.push(AppRoute.PlaneShooter) },
                    )

                    Screen.PLAN -> PlanScreen(
                        holder = planHolder,
                        refreshKey = dataVersion,
                        onNewPlan = { AppNav.push(AppRoute.PlanForm(null)) },
                        onEditPlan = { id -> AppNav.push(AppRoute.PlanForm(id)) },
                    )

                    // 看天看地（老周 2026-10-09，SE-5）：主页面 + 两个二级页（种子导入 / 关注点详情）
                    Screen.ANALYSIS -> SkyEarthScreen(
                        holder = skyEarthHolder,
                        onOpenDetail = { id -> AppNav.push(AppRoute.SkyFactorDetail(id)) },
                        onOpenImport = { domain -> AppNav.push(AppRoute.SkySeedImport(domain)) },
                    )
                }
            }
            // 底部导航：桌面外壳模式下不渲染（改由窗口菜单切页，见 desktopApp 的 MenuBar）
            if (showBottomBar) ProtoTabBar(selected = state.screen.label, onTab = onTab)
        }
    }
}

/**
 * 启动失败时的兜底界面。刻意不静默吞异常：
 * 「加密库没起来」这类硬失败如果白屏/闪退，会让人误以为是 UI 问题。
 */
@Composable
fun AppStartupError(message: String) {
    StockNoteTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(StockNoteColors.Background)
                .padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Text("启动失败", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = StockNoteColors.Up)
            Spacer(Modifier.height(10.dp))
            Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "常见原因：SQLCipher 未正确加载、Keystore 口令解密失败、" +
                    "或本地数据库文件被其他进程占用。",
                fontSize = 12.sp,
                color = StockNoteColors.TextTertiary,
            )
        }
    }
}
