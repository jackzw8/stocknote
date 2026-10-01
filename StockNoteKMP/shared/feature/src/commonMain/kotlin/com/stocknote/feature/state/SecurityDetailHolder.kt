package com.stocknote.feature.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.stocknote.core.model.SecurityDetail
import com.stocknote.data.repo.PortfolioRepository
import com.stocknote.data.repo.SecurityRepository
import com.stocknote.data.repo.TradeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 标的历史交易页（REQ-VIEW-10）的状态。
 *
 * 本页**只读 + 跳转**：点某行 → 进入编辑页（老周 2026-09-19 取消左滑方案）。
 * 删除也统一在**编辑页底部**（先删除预告、确认后落库，REQ-ACC-09 口径不变），
 * 因此这里不再持有任何删除状态。
 *
 * 例外：**利润转增资本（REQ-ACC-16）** 不能走记一笔表单（它不是成交），
 * 所以它的新增与撤销都在本页弹窗里完成，动作落在本 Holder 上。
 */
class SecurityDetailHolder(
    private val repo: PortfolioRepository,
    /** ⚠️ 2026-09-29 拆分（第 8 批）：交易域（删除一笔交易）。 */
    private val trade: TradeRepository,
    /** **标的域**（第 11 批）。 */
    private val security: SecurityRepository,
    private val scope: CoroutineScope,
) {

    data class UiState(
        val securityId: String = "",
        val loading: Boolean = true,
        val detail: SecurityDetail? = null,
        val error: String? = null,
        /** 转增/撤销进行中：用于禁用按钮，避免重复提交 */
        val busy: Boolean = false,
        /** 操作结果提示（成功与失败共用；非空即弹窗）；确认关闭后置回 null */
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load(securityId: String) {
        _state.update { it.copy(securityId = securityId, loading = true, error = null) }
        scope.launch {
            try {
                val detail = repo.loadSecurityDetail(securityId)
                _state.update {
                    if (detail == null) {
                        it.copy(loading = false, error = "找不到该标的")
                    } else {
                        it.copy(loading = false, detail = detail)
                    }
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(loading = false, error = "加载失败：${t.message ?: t::class.simpleName}")
                }
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    /**
     * **一键清空该标的的全部流水**（老周 2026-09-30）：交易（含备注 / 截图）+ 分红送股。
     *
     * ⚠️ 标的**本身保留**（持仓自然归零，可重新记一笔）；交易当初对现金的影响
     * 由仓储在**同一事务**内按各笔成交日汇率回冲。
     * 调用方（UI）必须先过**两次确认**再调本方法。
     */
    fun clearAllTrades(onDone: (Int) -> Unit) {
        val sid = _state.value.securityId
        if (sid.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        scope.launch {
            try {
                val n = trade.clearSecurityLedger(sid)
                val detail = repo.loadSecurityDetail(sid)
                _state.update {
                    it.copy(
                        busy = false,
                        detail = detail ?: it.detail,
                        message = "已清空 $n 笔交易与全部分红送股；现金已同步回冲，标的本身保留。",
                    )
                }
                onDone(n)
            } catch (t: Throwable) {
                _state.update {
                    it.copy(busy = false, message = "清空失败：${t.message ?: t::class.simpleName}")
                }
            }
        }
    }

    /**
     * 切换「不计入统计与分析」（场外基金备忘账，老周 2026-09-20）。
     *
     * 这是**账务口径**开关：开启后买入不扣现金、卖出不加现金，市值不进总资产 / 资产曲线 / 收益率。
     * 切完立刻重载本页（份额与盈亏不受影响，但页面上的口径提示要跟着变）；
     * ⚠️ 调用方（UI）负责提示「已扣的历史现金不会自动回退」。
     */
    fun setExcludeFromStats(exclude: Boolean) {
        val sid = _state.value.securityId
        if (sid.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        scope.launch {
            try {
                security.setSecurityExcludeFromStats(sid, exclude)
                val detail = repo.loadSecurityDetail(sid)
                _state.update {
                    it.copy(
                        busy = false,
                        detail = detail ?: it.detail,
                        message = if (exclude) {
                            "已改为「不计入统计与分析」：此后买卖不再联动现金，市值不计入总资产。"
                        } else {
                            "已改为「计入统计与分析」：与股票同口径参与现金、总资产与收益率统计。"
                        },
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(busy = false, message = "切换失败：${t.message ?: t::class.simpleName}")
                }
            }
        }
    }

    /**
     * 利润转增资本（REQ-ACC-16）：把 [amount] 折入该标本金（抬高成本价）。
     *
     * 不做任何现金联动（仓储层的 `addCapitalize` 只写一条 CAPITALIZE 流水），
     * 因此总资产 / 可用现金 / XIRR 都不变，只有该股的成本与盈亏构成变化。
     *
     * @param successMessage 由 UI 组装（它才拿得到币种符号），失败文案统一 `转增失败：…`
     */
    fun capitalize(
        securityId: String,
        amount: Double,
        /** 转增日期（老周 2026-09-24）：汇率按该日折算（repo 内部走 fxRateOn，与记一笔同口径） */
        tradeDate: String,
        successMessage: String,
        onDone: () -> Unit,
    ) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        scope.launch {
            try {
                repo.addCapitalize(securityId = securityId, amount = amount, tradeDate = tradeDate)
                _state.update { it.copy(busy = false, message = successMessage) }
                onDone()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(busy = false, message = "转增失败：${t.message ?: t::class.simpleName}")
                }
            }
        }
    }

    /**
     * 撤销一笔转增资本 —— 直接删掉那条记录即可（它就在重放流里，
     * 删后重放自然回到转增前口径，REQ-ACC-16「删除即撤销」）。
     */
    fun revokeCapitalize(
        securityId: String,
        txId: String,
        successMessage: String,
        onDone: () -> Unit,
    ) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        scope.launch {
            try {
                trade.deleteTransaction(txId)
                _state.update { it.copy(busy = false, message = successMessage) }
                onDone()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(busy = false, message = "撤销失败：${t.message ?: t::class.simpleName}")
                }
            }
        }
    }
}

@Composable
fun rememberSecurityDetailHolder(
    repo: PortfolioRepository,
    trade: TradeRepository,
    security: SecurityRepository,
): SecurityDetailHolder {
    val scope = rememberCoroutineScope()
    return remember(repo, trade) { SecurityDetailHolder(repo, trade, security, scope) }
}
