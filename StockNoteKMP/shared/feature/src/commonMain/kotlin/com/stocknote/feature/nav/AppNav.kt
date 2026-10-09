package com.stocknote.feature.nav

import com.stocknote.feature.state.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 路由。
 *
 * M1 仍用手写路由而不是 Navigation 库：页面数量还少，而引入导航库会同时带来
 * 「返回栈与状态恢复」两个新问题。等页面结构定型（M2）再换，成本可控。
 *
 * 做成单例 object 的原因：Android 的系统返回键只能由 Activity（androidApp）拦截，
 * 而返回栈状态在共享层 —— 单例让两端都能读写同一个栈，不需要为它打通一条
 * 跨模块回调。
 */
sealed interface AppRoute {

    /** 底部导航的三个一级页 */
    data class Tabs(val screen: Screen) : AppRoute

    /** 标的历史交易页（REQ-VIEW-10） */
    data class SecurityDetail(val securityId: String) : AppRoute

    /**
     * 记一笔 / 编辑。
     * @param securityId 预填或锁定的标的；null 表示从「选择标的」开始
     * @param editTxId   非空表示编辑态（此时标的名只读）
     */
    data class TradeForm(val securityId: String?, val editTxId: String?) : AppRoute

    /** 关于本应用（REQ-TOOL-04，P0） */
    data object About : AppRoute

    /** 新建 / 编辑交易计划（REQ-PLAN-01）；planId 非空 = 编辑态。表单页**不带底部导航** */
    data class PlanForm(val planId: String?) : AppRoute

    /** 出入金登记（REQ-ACC-03，从持仓页现金区进入） */
    data object CashFlow : AppRoute

    /** 分红送股登记（REQ-ACC-04，从标的历史交易页进入） */
    data class DividendForm(val securityId: String) : AppRoute

    /** 自选管理（REQ-TOOL-02，原型 13/13b；M3.2 入口暂放统计页） */
    data object Watchlist : AppRoute

    /** 添加自选 · 搜索多选（原型 13c） */
    data object WatchlistAdd : AppRoute

    /**
     * 持仓明细（老周 2026-10-02）。
     *
     * 从持仓页「持仓 TOP5 › 全部」进入的**二级页**（不带底部导航）。
     * 完整持仓列表 + 筛选 / 排序 / 查询都在这里，每页 10 条分页显示；
     * 原挂在持仓页的 🔍 查询入口也已随之迁入。
     */
    data object HoldingsDetail : AppRoute

    /** 交易日记（原型 07：日记列表 + 月度复盘区） */
    data object Diary : AppRoute

    /** 写/编辑复盘（REQ-NOTE-06）；preset 非空为编辑态 */
    data class ReviewForm(val preset: com.stocknote.core.model.Review?) : AppRoute

    /** 标签管理（REQ-NOTE-04 配套） */
    data object TagManage : AppRoute

    /** 巨潮诉讼/担保数据同步页（老周 2026-09-24）：手工拉全市场数据 → 存本地 → 个股扫雷只读本地 */
    data object CninfoSync : AppRoute

    /** 设置页（原型 09；标签管理/关于入口在此，老周反馈 2026-09-14） */
    data object Settings : AppRoute

    /** 数据管理（CSV 导入导出 + 加密备份恢复）；老周 2026-09-22 从设置页拆出成独立页 */
    data object DataManage : AppRoute

    /** 汇率管理（fx_rate 数据真值）；老周 2026-09-22 从设置页拆出成独立页 */
    data object FxRate : AppRoute

    /** 盈亏日历热力图（REQ-VIEW-06，M4 收尾） */
    data object PnlCalendar : AppRoute

    /** 收益率分析页（REQ-VIEW-07 P1，M4 收尾） */
    data object ReturnAnalysis : AppRoute

    /** 个股资讯列表（老周 2026-09-24）：探索页 →「个股资讯」，含选标的与分类切换 */
    data object NewsList : AppRoute

    /** 资讯详情（老周 2026-09-24）：正文是腾讯 H5 单页应用，用 WebView 打开 */
    data class NewsDetail(val title: String, val url: String) : AppRoute

    /** 个股财务数据（老周 2026-09-24）：探索页 →「个股财务」，数据来自东财 F10 */
    data object Finance : AppRoute

    /**
     * **7×24 快讯**（老周 2026-10-04）：探索页 →「7×24 快讯」。
     * 数据来自**财联社电报**（非官方接口，`sign = MD5(SHA1(...))`，见 `FlashNewsSource`）。
     */
    data object FlashNews : AppRoute

    /**
     * **星际战机**（老周 2026-10-04）：探索页 →「星际战机」。
     * 内嵌的 H5 canvas 小游戏（`PlaneShooterHtml`），**完全离线**、不联网。
     */
    data object PlaneShooter : AppRoute

    /**
     * **分析图表**二级页（老周 2026-10-08）：统计分析页 →「📊 分析图表」。
     *
     * 原来这四块（绩效总览 / 连胜·连亏 / 按策略分析 / 盈亏归因）挂在「分析」tab 上；
     * 那个 tab 改成「看天看地」空白页后，它们整体搬来这里、降级成二级页。
     */
    data object AnalysisChart : AppRoute

    /**
     * **看天看地 · 种子清单导入**（老周 2026-10-09，SE-7）：复制提示词 → 粘贴 AI 返回 → 预览 → 导入。
     *
     * [domain] 是入口建议的组；最终导入组由粘贴文本的 `SEED:` 头行决定（界面会跟着切）。
     */
    data class SkySeedImport(val domain: com.stocknote.core.model.SkyDomain) : AppRoute

    /** **看天看地 · 关注点详情**（老周 2026-10-09，SE-8）：判断阶梯线 × 同期基准走势。 */
    data class SkyFactorDetail(val factorId: String) : AppRoute

    /** 个股风险扫雷（老周 2026-09-24）：探索页 →「个股扫雷」，规则见《东财F10个股风险扫雷技术方案》 */
    data object RiskScan : AppRoute
}

object AppNav {

    private val _stack = MutableStateFlow<List<AppRoute>>(emptyList())
    val stack: StateFlow<List<AppRoute>> = _stack.asStateFlow()

    val current: AppRoute
        get() = _stack.value.lastOrNull() ?: AppRoute.Tabs(Screen.STATISTIC)

    val canPop: Boolean get() = _stack.value.isNotEmpty()

    fun push(route: AppRoute) {
        _stack.value = _stack.value + route
    }

    fun pop() {
        if (_stack.value.isNotEmpty()) {
            _stack.value = _stack.value.dropLast(1)
        }
    }

    /** 回到底部导航（保存成功后用，避免回栈里残留中间页） */
    fun popToTabs() {
        _stack.value = emptyList()
    }
}
