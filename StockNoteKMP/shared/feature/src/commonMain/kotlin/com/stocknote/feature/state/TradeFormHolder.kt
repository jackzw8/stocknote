package com.stocknote.feature.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.stocknote.core.calc.TradeForms
import com.stocknote.core.format.Format
import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.Security
import com.stocknote.core.model.TradeSide
import com.stocknote.data.net.QuoteClient
import com.stocknote.data.platform.todayIso
import com.stocknote.data.repo.FxRepository
import com.stocknote.data.repo.SecurityRepository
import com.stocknote.data.repo.TagRepository
import com.stocknote.data.repo.WatchlistRepository
import com.stocknote.data.repo.EmotionRepository
import com.stocknote.data.repo.PortfolioRepository
import com.stocknote.data.repo.SettingsRepository
import com.stocknote.data.repo.QuoteRepository
import com.stocknote.data.repo.TradeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 记一笔 / 编辑 的表单状态。
 *
 * 两条入口：
 *  - 新增：可先搜本地标的，没有就手动新增（symbol + 名称 + 市场 + 币种）
 *  - 编辑：securityId + editTxId 都给定，标的名**只读**（REQ-ACC-09：security_id 不可改）
 */
class TradeFormHolder(
    /** 尚未拆出独立域的：标的详情/搜索等，仍走原仓储。 */
    private val repo: PortfolioRepository,
    /** **设置域**：整手校验开关（老周 2026-09-28）。 */
    private val settings: SettingsRepository,
    /** **交易域**：增改删、截图、删除预览、持仓数量。 */
    private val trade: TradeRepository,
    /** **汇率域**：成交日汇率。 */
    private val fx: FxRepository,
    /** **行情域**：缓存行情与联网刷新。 */
    private val quote: QuoteRepository,
    /** **标签域**（第 4 批）：标签选项与新增。 */
    private val tag: TagRepository,
    /** **自选域**（第 5 批）：最近用过的标的。 */
    private val watch: WatchlistRepository,
    /** **情绪标签域**（第 9 批）：可选情绪与自定义增删。 */
    private val emotion: EmotionRepository,
    /** **标的域**（第 11 批）。 */
    private val security: SecurityRepository,
    private val scope: CoroutineScope,
) {

    data class UiState(
        val editTxId: String? = null,
        val securityId: String? = null,
        val securityLabel: String = "",
        /**
         * 已选标的的市场（老周 2026-09-20）。
         * 「不计入统计与分析」开关**只对基金显示**，而开关的判断需要知道标的市场 ——
         * 之前只存了 newMarket（新建标的用），已选标的市场是缺的。
         */
        val selectedMarket: Market? = null,
        /**
         * 已选标的的**每手股数**（老周 2026-09-28）。港股为真实值（腾讯接口取回，如 500），
         * 未取到或非港股时为 null → 由 `LotRule` 按市场兜底。用于数量输入的提示与整手校验。
         */
        val selectedLotSize: Double? = null,
        /** 标的名只读：编辑态、或已从列表带入预填态 */
        val locked: Boolean = false,
        val side: TradeSide = TradeSide.BUY,
        val quantity: String = "",
        /**
         * 「整手校验」开关（老周 2026-09-28，设置里配、**缺省开**）。
         * 由界面在进入时调 [refreshLotCheck] 从加密 KV 读入；关掉后数量不再做整手约束。
         */
        val lotCheckEnabled: Boolean = true,
        val price: String = "",
        /**
         * 手续费率（%），原型 03 口径；入库金额 = 价×量×费率/100（TradeForms.feeAmountOf）。
         * **单位＝万分位**（老周 2026-09-16 定稿）：填 2.5 即万分之 2.5。
         * 缺省按市场：A股/其它 = 2.5（万分之2.5）；港股 = 3（万分之3）。
         * 选定标的后会按标的市场重设（见 pick）。
         */
        val feeRate: String = "2.5",
        val date: String = "",
        /**
         * 成交日汇率（**原币 → CNY**，REQ-ACC-15 路径 1，老周 2026-09-19）。
         * 只对非本位币标的（港股 / 美股）显示与使用；A 股恒为空（无需折算）。
         * 录入时按交易日期**自动带出市场汇率**，允许手改成券商结算价。
         */
        val fxRate: String = "",
        /** 成交日汇率拉取中（UI 显示提示，不阻塞保存） */
        val fxRateLoading: Boolean = false,
        val note: String = "",
        val keyword: String = "",
        val candidates: List<Security> = emptyList(),
        /** 联网搜索结果（REQ-TOOL-01）；为空表示无结果或离线 */
        val onlineHits: List<QuoteClient.SymbolHit> = emptyList(),
        val searchingOnline: Boolean = false,
        val searching: Boolean = false,
        /** 候选列表是「最近使用」而不是搜索结果（老周 2026-09-21：空关键词点搜索） */
        val recentMode: Boolean = false,
        val showNewSecurity: Boolean = false,
        val newSymbol: String = "",
        val newName: String = "",
        val newMarket: Market = Market.A_SHARE,
        val newCurrency: Currency = Currency.CNY,
        /**
         * **不计入统计与分析**（老周 2026-09-20，为场外基金而加）。
         *
         * true = 完全账外（纯备忘）：买入不扣现金、卖出不加现金、市值不进总资产，
         * 只在「账外备忘」区看它自己的份额 / 成本 / 净值 / 浮盈。
         * 新建**基金**标的时缺省为 true（老周定：默认不计入）。
         */
        val excludeFromStats: Boolean = false,
        /** 当前表单展示币种（随选定标的/新建市场变化）：港股 HKD，其余 CNY（老周 2026-09-16） */
        val formCurrency: Currency = Currency.CNY,
        /** 情绪（REQ-NOTE-02） */
        val emotion: String? = null,
        /** 情绪选项 = 预设 + 自定义（A9） */
        val emotions: List<String> = emptyList(),
        val customEmotions: List<String> = emptyList(),
        val showEmotionInput: Boolean = false,
        val emotionError: String? = null,
        /** 卖出时该标的的当前可卖数量（老周 2026-09-16）；null = 未知（未选标的/加载中） */
        val maxSellable: Double? = null,
        /**
         * 编辑态：本笔**入库时的方向**（老周 2026-09-29）。
         * 卖出才占「可卖额度」，计算上限时要看**原方向**而不是界面上当前的 [side]
         * （用户可能把卖出改成买入，或反过来）。
         */
        val originSide: TradeSide? = null,
        /**
         * 编辑态：本笔**入库时的数量**（老周 2026-09-29）。上限的保底值，见 [sellableLimitOf]。
         */
        val originQuantity: Double? = null,
        /** 执行评分 1-5（REQ-NOTE-05），0 = 未评 */
        val score: Int = 0,
        /** 策略标签多选（REQ-NOTE-04） */
        val tags: List<String> = emptyList(),
        /** 可选标签全集（表单内快捷新建后动态更新） */
        val tagOptions: List<String> = emptyList(),
        /** 表单内快捷新建标签弹窗 */
        val showTagCreate: Boolean = false,
        /** 编辑态回填：质量评级不在表单里改（日记页标记），保存时原样带回避免被抹掉 */
        val originQuality: com.stocknote.core.model.TradeQuality? = null,
        /** 交易截图（REQ-ACC-11，老周 2026-09-17）：已压缩 JPEG 字节；null = 未添加 */
        val photo: ByteArray? = null,
        /** 预览位图（由平台桥解码；解码失败为 null，UI 退化为文字提示） */
        val photoBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
        val photoBusy: Boolean = false,
        /** 编辑态：用户主动移除了原有截图（保存时删库里的图） */
        val photoRemoved: Boolean = false,
        val issues: List<TradeForms.Issue> = emptyList(),
        val warning: String? = null,
        val saving: Boolean = false,
        val saved: Boolean = false,
        val error: String? = null,
        /** 保存失败次数：每次校验失败 / 异常 +1，UI 据此弹「无法保存」弹窗（老周 2026-09-18） */
        val saveFailTick: Int = 0,
        /**
         * 删除预告（编辑态专用）。
         * 老周 2026-09-19：删除入口由详情页左滑移入**编辑页底部**（与交易计划一致），
         * 但 REQ-ACC-09 的「先预告重算结果、用户确认才动手」口径保持不变。
         */
        val deletePreview: com.stocknote.core.model.DeletePreview? = null,
        val deleting: Boolean = false,
        /** 删除完成（UI 据此返回上一页） */
        val deleted: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState(date = todayIso()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * ⚠️ M5 修复（2026-09-28）：异步读取的**竞态防护（请求序号）**。
     *
     * 症状：快速切标的或快速改日期时，**先发的请求可能后完成**，把界面覆盖成旧值 ——
     *  - 「当前最多可卖 N」显示成**上一只标的**的持仓（用户会据此填错数量）；
     *  - 成交日汇率显示成**上一个日期**的汇率（保存时按错汇率折算成本）。
     *
     * 做法：每次发起递增对应序号，异步结果回来时比对；不等于当前序号说明已被更新的请求取代，
     * **直接丢弃结果**（loading 标记也一并交给新请求去改，避免过期结果把转圈关掉）。
     */
    private var maxSellableSeq = 0
    private var fxRateSeq = 0

    fun start(securityId: String?, editTxId: String?) {
        scope.launch {
            val base = UiState(date = todayIso(), tagOptions = loadTagOptions())
            val next = when {
                editTxId != null -> {
                    // 编辑态：回填已有值，标的名锁定
                    val detail = securityId?.let { repo.loadSecurityDetail(it) }
                    val tx = detail?.transactions?.firstOrNull { it.id == editTxId }
                    if (detail == null || tx == null) {
                        base.copy(error = "找不到要编辑的交易")
                    } else {
                        base.copy(
                            editTxId = editTxId,
                            // 原方向 / 原数量：「最多可卖」与保存时的超卖截断都要靠它们还原额度
                            originSide = tx.side,
                            originQuantity = tx.quantity,
                            securityId = detail.security.id,
                            securityLabel = "${detail.security.name} (${detail.security.symbol})",
                            selectedMarket = detail.security.market,
                            selectedLotSize = detail.security.lotSize,
                            locked = true,
                            side = tx.side,
                            // ⚠️ H1 修复（2026-09-28）：回填一律 `group = false` —— 数量框是**数字输入**，
                            // 带千分位（"1,000"）会让保存被判「必须是数字」而完全存不了。
                            quantity = Format.quantity(tx.quantity, group = false),
                            price = tx.price.toString(),
                            // 由已存金额反算费率%（编辑改价后保存会按新价×量重算金额，差值在 0.01 元级）
                            // ⚠️ H1 修复：反算失败返回 null → **留空让用户填**，绝不静默写 0（否则手续费被清零）
                            feeRate = TradeForms.feeRateOf(
                                tx.price.toString(),
                                Format.quantity(tx.quantity, group = false),
                                tx.fee,
                            ).orEmpty(),
                            date = tx.tradeDate,
                            note = tx.note.orEmpty(),
                            emotion = tx.emotion,
                            score = tx.score ?: 0,
                            tags = tx.tags,
                            originQuality = tx.quality,
                            // 展示币种必须跟着标的走（老周 2026-09-19 报：编辑港股交易时
                            // 成交价旁仍写 CNY）。此前只有「选标的」路径设了 formCurrency，
                            // 编辑态与预填态都漏了。
                            formCurrency = defaultCurrencyOf(detail.security.market),
                            // 已记录的成交日汇率原样回填（REQ-ACC-15）；为空则 start() 末尾再尝试带出
                            fxRate = tx.fxRate?.let { r -> trimFxRate(r) } ?: "",
                            // 「不计入统计」是**标的级**开关，编辑任一笔都要回填真实值，
                            // 否则保存时会按缺省值把它改掉（老周 2026-09-20）
                            excludeFromStats = detail.security.excludeFromStats,
                        )
                    }
                }

                securityId != null -> {
                    // 预填态：从标的历史交易页点「新增一笔」进来，现价带出来省一次输入
                    val detail = repo.loadSecurityDetail(securityId)
                    if (detail == null) {
                        base.copy(error = "找不到标的")
                    } else {
                        base.copy(
                            securityId = detail.security.id,
                            securityLabel = "${detail.security.name} (${detail.security.symbol})",
                            selectedMarket = detail.security.market,
                            selectedLotSize = detail.security.lotSize,
                            locked = true,
                            price = detail.position.marketPrice?.toString().orEmpty(),
                            // 与「选标的」路径同口径：费率缺省按市场（A股/其它 2.5、港股 3）——
                            // 老周 2026-09-16：此前详情页进来的预填态漏了这个初始化
                            feeRate = defaultFeeRateOf(detail.security.market),
                            // 同上：预填态也要带对展示币种（老周 2026-09-19）
                            formCurrency = defaultCurrencyOf(detail.security.market),
                            // 同上：预填态回填真实开关（老周 2026-09-20）
                            excludeFromStats = detail.security.excludeFromStats,
                        )
                    }
                }

                else -> base
            }
            // ⚠️ M6 修复（2026-09-28）：原来这里是整段 `_state.value = when {...}`，
            // 会**抹掉并发写入的字段** —— `refreshLotCheck()` 写的 `lotCheckEnabled`、
            // `loadEmotions()` 写的 `emotions` 都是独立协程，谁先完成都可能被这一句覆盖回默认值
            // （表现：整手校验开关"自己变回开"、情绪列表变空）。
            // 改成合并写入：只覆盖 start() 负责的字段，其余沿用当前值。
            _state.update { prev ->
                next.copy(
                    lotCheckEnabled = prev.lotCheckEnabled,
                    emotions = prev.emotions,
                )
            }
            // 预填态/编辑态同样要刷「最多可卖」（老周 2026-09-16 补齐）
            refreshMaxSellable()
            // 非本位币标的：按交易日期带出成交日汇率（REQ-ACC-15 路径 1）——
            // 编辑态若已有记录会被同值覆盖（无害）；没有记录的旧数据则补上当日的
            refreshFxRate()
        }
    }

    // ---- 字段输入 ----

    fun setSide(side: TradeSide) = _state.update { it.copy(side = side) }
        .also { if (side == TradeSide.SELL) refreshMaxSellable() }
    fun setQuantity(v: String) = _state.update { it.copy(quantity = v) }

    /**
     * 从设置（加密 KV）读入「整手校验」开关（老周 2026-09-28）。
     * 界面进入时调一次即可 —— 用户改设置后重新进页面会拿到新值。
     */
    fun refreshLotCheck() {
        scope.launch {
            val enabled = runCatching { settings.lotCheckEnabled() }.getOrDefault(true)
            _state.update { it.copy(lotCheckEnabled = enabled) }
        }
    }
    fun setPrice(v: String) = _state.update { it.copy(price = v) }
    fun setFeeRate(v: String) = _state.update { it.copy(feeRate = v) }

    /**
     * 刷新「当前可卖数量」：用与持仓完全相同的重放口径（replay + dividends）。
     * 卖出时显示「最多可卖 N 股」，输入超过 N 即时提示（老周 2026-09-16）。
     */
    fun refreshMaxSellable() {
        val s = _state.value
        val sid = s.securityId ?: return
        // ⚠️ M5：序号防护 —— 期间切了标的就丢弃这次结果
        val mySeq = ++maxSellableSeq
        scope.launch {
            val qty = runCatching { sellableLimitOf(s, sid) }.getOrNull()
            if (mySeq != maxSellableSeq) return@launch
            _state.update { it.copy(maxSellable = qty) }
        }
    }

    /**
     * 卖出数量的**可填上限**（老周 2026-09-29）。
     *
     * ⚠️ 编辑态必须**剔除本笔**再算（与保存时 `TradeRepository.truncateSell` 同口径）：
     * 典型场景 —— 买入 1000 后又卖出 1000（清仓），当前持仓已是 0；
     * 回头只改这笔记的备注，若不剔除本笔，上限 = 0 → 界面红字「超出可卖数量」，
     * **连改个备注都做不了**（老周报的正是这个）。
     * 剔除本笔后持仓＝「如果这笔记还没卖时能卖多少」，才是编辑时真正的上限。
     *
     * 保底：剔除后算出 0 而本笔原本就是卖出（典型：卖出日期早于买入这种异常账，
     * 按日重放算不出持仓）时，至少允许保持**原数量**不变，否则一样改不动备注。
     */
    private suspend fun sellableLimitOf(s: UiState, securityId: String): Double {
        val excludingSelf = trade.positionQuantityOf(
            securityId = securityId,
            // 新增态没有"本笔"，传 null 即整本账的当前持仓
            excludeTxId = s.editTxId,
        )
        val keepAtLeast = if (s.originSide == TradeSide.SELL) s.originQuantity ?: 0.0 else 0.0
        return kotlin.math.max(excludingSelf, keepAtLeast)
    }

    /** 按市场取缺省费率（单位：万分之）：港股 3，A股/其它 2.5。 */
    /**
     * 手续费率缺省（万分之值）：**从设置读取**（老周 2026-09-18：A 股缺省 2.5、港股缺省 5，
     * 设置页「手续费率」可改）。美股沿用 A 股的缺省值。
     */
    fun defaultFeeRateOf(market: Market): String =
        if (market == Market.HK) AppDisplaySettings.feeRateHk.value
        else AppDisplaySettings.feeRateAShare.value

    /** 手续费率清零（老周 2026-09-16：有些券商免佣） */
    fun clearFeeRate() = _state.update { it.copy(feeRate = "0") }

    /**
     * 本笔的**印花税**（原币金额，REQ-ACC-17 老周 2026-09-30）。
     *
     * 规则（见 `core/calc/StampDuty`）：A 股**买入不收、卖出万分之 5**；
     * 港股**买卖各千分之 1**；ETF / 场外基金 / 美股不收。税率取自设置（缺省即法定值）。
     *
     * ⚠️ 界面上**只展示、不可修改**（法定税率）；保存时调**同一个函数**，
     * 保证"屏幕上看到的 = 存进库里的"。
     */
    fun stampDutyOf(): Double {
        val s = _state.value
        val market = s.selectedMarket ?: return 0.0
        val price = s.price.trim().toDoubleOrNull() ?: return 0.0
        val qty = s.quantity.trim().toDoubleOrNull() ?: return 0.0
        return com.stocknote.core.calc.StampDuty.dutyOf(
            market = market,
            isBuy = s.side == TradeSide.BUY,
            price = price,
            quantity = qty,
            aShareRate = AppDisplaySettings.stampDutyAShare.value.toDoubleOrNull()
                ?: com.stocknote.core.calc.StampDuty.A_SHARE_SELL_RATE,
            hkRate = AppDisplaySettings.stampDutyHk.value.toDoubleOrNull()
                ?: com.stocknote.core.calc.StampDuty.HK_RATE,
        )
    }
    /** 改日期 → 重新带出该日的成交汇率（REQ-ACC-15：汇率是按交易日期取的） */
    fun setDate(v: String) {
        _state.update { it.copy(date = v) }
        refreshFxRate()
    }

    fun setFxRate(v: String) = _state.update { it.copy(fxRate = v) }

    /**
     * 按「交易日期 + 标的币种」自动带出**成交日汇率**（REQ-ACC-15 路径 1，老周 2026-09-19）。
     *
     * - 本位币（A股）标的：直接清空，不显示该字段；
     * - 拉不到（离线 / 该日无数据）：**保持用户已填的值**，不覆盖、不阻塞保存，由用户手填。
     */
    fun refreshFxRate() {
        val s = _state.value
        if (s.formCurrency == Currency.CNY) {
            _state.update { it.copy(fxRate = "") }
            return
        }
        if (s.securityId == null) return
        // ⚠️ M5：序号防护 —— 期间改了日期/币种就丢弃这次结果（否则旧汇率覆盖新汇率）
        val mySeq = ++fxRateSeq
        scope.launch {
            _state.update { it.copy(fxRateLoading = true) }
            val rate = runCatching { fx.rateOn(s.date, s.formCurrency) }.getOrNull()
            if (mySeq != fxRateSeq) return@launch
            _state.update {
                it.copy(
                    fxRateLoading = false,
                    fxRate = rate?.let { r -> trimFxRate(r) } ?: it.fxRate,
                )
            }
        }
    }

    /**
     * 成交日汇率：**4 位小数**（老周 2026-09-21 定口径，与 fx_rate 表一致）。
     * 表单里手填 6 位也会被收敛成 4 位 —— 屏幕上看到的数字 = 真正参与折算的数字。
     */
    fun parseFxRate(v: String): Double? =
        v.trim().toDoubleOrNull()?.let { com.stocknote.core.model.FxTable.roundRate(it) }

    /** 汇率显示成 4 位小数（0.853600 → "0.8536"），避免 0.8535999999 这种 */
    private fun trimFxRate(v: Double): String {
        // ⚠️ M3 修复（2026-09-28）：改用 Locale 无关的 Format.fixedPlain
        val s = com.stocknote.core.format.Format.fixedPlain(v, 4).trimEnd('0').trimEnd('.')
        return s.ifEmpty { "0" }
    }

    /** 备注上限 500 字（小B 定稿 2026-09-14）：超出直接截断，UI 显示计数 */
    fun setNote(v: String) = _state.update { it.copy(note = v.take(NOTE_MAX_LEN)) }
    fun setEmotion(v: String?) = _state.update { it.copy(emotion = v) }

    // ---- 情绪自定义（A9）----

    /** 加载情绪选项（预设 + 自定义）；进入表单时调用一次。 */
    fun loadEmotions() {
        scope.launch {
            val all = runCatching { emotion.allEmotions() }.getOrDefault(emotion.presetEmotions)
            val custom = runCatching { emotion.customEmotions() }.getOrDefault(emptyList())
            _state.update { it.copy(emotions = all, customEmotions = custom) }
        }
    }

    fun toggleEmotionInput() = _state.update {
        it.copy(showEmotionInput = !it.showEmotionInput, emotionError = null)
    }

    fun addEmotion(name: String) {
        scope.launch {
            runCatching { emotion.addCustomEmotion(name) }
                .onSuccess { custom ->
                    val all = runCatching { emotion.allEmotions() }.getOrDefault(emotion.presetEmotions + custom)
                    _state.update {
                        it.copy(
                            emotions = all,
                            customEmotions = custom,
                            showEmotionInput = false,
                            emotion = name.trim(),
                            emotionError = null,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(emotionError = e.message ?: "添加失败") }
                }
        }
    }

    /** 自定义情绪可删除（预设 5 个不可删）。 */
    fun removeEmotion(name: String) {
        scope.launch {
            val custom = runCatching { emotion.removeCustomEmotion(name) }.getOrDefault(emptyList())
            val all = runCatching { emotion.allEmotions() }.getOrDefault(emotion.presetEmotions + custom)
            _state.update {
                it.copy(
                    emotions = all,
                    customEmotions = custom,
                    emotion = if (it.emotion == name) null else it.emotion,
                )
            }
        }
    }
    fun setScore(v: Int) = _state.update { it.copy(score = if (it.score == v) 0 else v) }
    fun toggleTag(v: String) = _state.update {
        it.copy(tags = if (v in it.tags) it.tags - v else it.tags + v)
    }
    /** 改关键词：一开始打字就退出「最近用过」展示态（老周 2026-09-21），免得列表标题对不上 */
    fun setKeyword(v: String) = _state.update { it.copy(keyword = v, recentMode = false) }
    fun setNewSymbol(v: String) = _state.update { it.copy(newSymbol = v) }
    fun setNewName(v: String) = _state.update { it.copy(newName = v) }
    /** 切市场：币种跟着走；**基金缺省「不计入统计」**（老周 2026-09-20），其它市场不动用户的勾选。 */
    fun setNewMarket(v: Market) = _state.update {
        it.copy(
            newMarket = v,
            newCurrency = defaultCurrencyOf(v),
            formCurrency = defaultCurrencyOf(v),
            excludeFromStats = if (v == Market.FUND) true else it.excludeFromStats,
        )
    }

    /** 切换「不计入统计与分析」（仅基金市场可见）。 */
    fun setExcludeFromStats(v: Boolean) = _state.update { it.copy(excludeFromStats = v) }
    fun setNewCurrency(v: Currency) = _state.update { it.copy(newCurrency = v) }
    fun toggleNewSecurity() = _state.update { it.copy(showNewSecurity = !it.showNewSecurity) }

    // ---- 表单内快捷新建标签（小B 定稿 2026-09-14） ----

    fun toggleTagCreate() = _state.update { it.copy(showTagCreate = !it.showTagCreate) }

    /** 新建并自动勾选；重名时仅勾选已有标签。 */
    fun createTagAndSelect(nameRaw: String) {
        val name = nameRaw.trim()
        if (name.isEmpty()) return
        scope.launch {
            runCatching { tag.add(name) }
            _state.update {
                val options = (it.tagOptions + name).distinct()
                it.copy(
                    tagOptions = options,
                    tags = if (name in it.tags) it.tags else it.tags + name,
                    showTagCreate = false,
                )
            }
        }
    }

    private suspend fun loadTagOptions(): List<String> =
        runCatching { tag.all().map { it.name } }.getOrElse { DEFAULT_TAGS }

    fun search() {
        val kw = _state.value.keyword
        // 关键词为空时点「搜索」= 看**最近用过的标的**（老周 2026-09-21）：
        // 常记的那几只不用每次手打代码，点一下就出来；账本还空则回退自选。
        if (kw.isBlank()) {
            scope.launch {
                _state.update { it.copy(searching = true, searchingOnline = false, recentMode = true) }
                val recent = runCatching { watch.recentSecurities() }.getOrDefault(emptyList())
                _state.update { it.copy(searching = false, candidates = recent, onlineHits = emptyList()) }
            }
            return
        }
        scope.launch {
            _state.update { it.copy(searching = true, searchingOnline = true, recentMode = false) }
            val local = security.searchSecurities(kw)
            _state.update { it.copy(searching = false, candidates = local) }
            // 联网搜索后置：本地结果先出（满足 <400ms 可感目标），网络慢也不拖界面
            val online = runCatching { security.searchOnline(kw) }.getOrDefault(emptyList())
            _state.update { it.copy(searchingOnline = false, onlineHits = online) }
        }
    }

    /** 选中联网候选：先建标的（按 symbol 去重复用），再带入表单。 */
    fun pickOnline(hit: QuoteClient.SymbolHit) {
        scope.launch {
            val market = when (hit.marketLabel) {
                "ETF" -> Market.ETF
                "基金" -> Market.FUND
                "港股" -> Market.HK
                "美股" -> Market.US
                else -> Market.A_SHARE
            }
            val currency = Currency.entries.firstOrNull { it.code == hit.currencyCode } ?: Currency.CNY
            val security = security.findOrCreateSecurity(
                symbol = hit.symbol,
                name = hit.name,
                market = market,
                currency = currency,
                // 场外基金缺省「不计入统计」（老周 2026-09-20）；已存在的标的按原开关原样返回
                excludeFromStats = market == Market.FUND,
            )
            pick(security)
        }
    }

    fun pick(security: Security) = _state.update {
        it.copy(
            securityId = security.id,
            securityLabel = "${security.name} (${security.symbol})",
            selectedMarket = security.market,
            selectedLotSize = security.lotSize,
            locked = true,
            showNewSecurity = false,
            // 按市场给手续费率缺省值 + 展示币种（A股/其它 0.025；港股 0.03 且用 HKD）—— 老周 2026-09-16
            feeRate = defaultFeeRateOf(security.market),
            formCurrency = defaultCurrencyOf(security.market),
            // 回填该标的**真实的**统计开关（标的级属性，不能按表单缺省值猜）—— 老周 2026-09-20
            excludeFromStats = security.excludeFromStats,
        )
    }
    // 选中后顺带拉一次实时价填进「成交价」+ 刷新可卖数量 + 带出成交日汇率
        .also { fetchMarketPriceIntoForm(security); refreshMaxSellable(); refreshFxRate() }

    /**
     * 取标的最新成交价写入「成交价」输入框。
     * 顺序：本地行情缓存 → 联网拉取；拿不到保持现状（用户手填），不报错打断录入。
     */
    fun fetchMarketPriceIntoForm(security: Security) {
        scope.launch {
            // 先读本地缓存；拿不到再联网拉一次（缓存落库后下次即时）
            val cached = runCatching { quote.cached(security.symbol) }.getOrNull()
            val price = cached?.price
                ?: runCatching { quote.refresh(security.symbol) }.getOrNull()?.price
            if (price != null && price > 0) {
                _state.update { if (it.price.isBlank()) it.copy(price = trimPrice(price)) else it }
            }
        }
    }

    /** 价格去掉多余小数（最多 3 位），避免 1277.9599999 这种 */
    private fun trimPrice(v: Double): String {
        // ⚠️ M3 修复（2026-09-28）：改用 Locale 无关的 Format.fixedPlain
        val s = com.stocknote.core.format.Format.fixedPlain(v, 3).trimEnd('0').trimEnd('.')
        return s.ifEmpty { "0" }
    }

    fun createAndPick() {
        val s = _state.value
        if (s.newSymbol.isBlank() || s.newName.isBlank()) {
            _state.update { it.copy(issues = listOf(TradeForms.Issue("newSecurity", "请填写代码与名称"))) }
            return
        }
        scope.launch {
            val security = security.findOrCreateSecurity(
                symbol = s.newSymbol,
                name = s.newName,
                market = s.newMarket,
                currency = s.newCurrency,
                excludeFromStats = s.excludeFromStats,
            )
            pick(security)
        }
    }

    /**
     * 保存。校验失败不动库；成功后落 saved 态，
     * 导航（refresh+pop）由 UI 层在主线程执行（见 TradeFormScreen 的 LaunchedEffect）。
     */
    /** 选一张截图（系统文件选择器 → 自动压缩为 JPEG）—— 老周 2026-09-17 */
    fun pickPhoto() {
        if (_state.value.photoBusy) return
        _state.update { it.copy(photoBusy = true) }
        scope.launch {
            val bytes = runCatching {
                com.stocknote.feature.state.FileBridgeHolder.require().pickImage()
            }.getOrNull()
            _state.update {
                if (bytes == null) {
                    it.copy(photoBusy = false, error = null)
                } else {
                    it.copy(
                        photo = bytes,
                        photoBitmap = com.stocknote.feature.state.FileBridgeHolder.impl?.decodeImage(bytes),
                        photoBusy = false,
                        photoRemoved = false,
                    )
                }
            }
        }
    }

    /** 移除已选/已有截图（保存时生效） */
    fun removePhoto() =
        _state.update { it.copy(photo = null, photoBitmap = null, photoRemoved = true) }

    /** 编辑态载入已有截图（供 UVer 预览；失败静默） */
    fun loadExistingPhoto() {
        val txId = _state.value.editTxId ?: return
        scope.launch {
            val bytes = runCatching { trade.tradePhotoOf(txId) }.getOrNull()
            if (bytes != null) {
                _state.update {
                    it.copy(
                        photo = bytes,
                        photoBitmap = com.stocknote.feature.state.FileBridgeHolder.impl?.decodeImage(bytes),
                    )
                }
            }
        }
    }

    fun save() {
        val s = _state.value
        val all = buildList {
            addAll(
                TradeForms.validate(
                    s.side.name,
                    s.quantity,
                    s.price,
                    s.date,
                    s.note,
                    s.feeRate,
                    // ⚠️ M4 修复（2026-09-28）：场外基金按**份额**申赎，允许小数数量；
                    // 其余市场仍要求整数（A股/ETF/港股的整手约束另由 LotRule 负责）。
                    allowFractional = s.selectedMarket == com.stocknote.core.model.Market.FUND,
                ),
            )
            if (s.securityId == null) add(TradeForms.Issue("security", "请先选择标的"))
            // 整手校验（老周 2026-09-28）：A股/ETF/港股必须 100 的整数倍；美股/场外基金不受限。
            // 开关关掉时 LotRule.check 直接放行（缺省开）。
            val qtyValue = s.quantity.trim().toDoubleOrNull()
            val market = s.selectedMarket
            if (qtyValue != null && market != null) {
                com.stocknote.core.calc.LotRule.check(qtyValue, market, s.selectedLotSize, s.lotCheckEnabled)
                    ?.let { add(TradeForms.Issue("quantity", it)) }
            }
        }
        if (all.isNotEmpty()) {
            _state.update { it.copy(issues = all, saveFailTick = it.saveFailTick + 1) }
            return
        }

        scope.launch {
            _state.update { it.copy(saving = true, issues = emptyList(), error = null) }
            try {
                val qty = s.quantity.trim().toDouble()
                val price = s.price.trim().toDouble()
                // 费率% -> 金额入库（口径不变：trade.fee 列存金额）；校验已保证非空时是合法数字
                val fee = TradeForms.feeAmountOf(s.price, s.quantity, s.feeRate) ?: 0.0
                val targetSecurityId = s.securityId!!

                if (s.editTxId != null) {
                    trade.updateTransaction(
                        txId = s.editTxId,
                        side = s.side,
                        quantity = qty,
                        price = price,
                        fee = fee,
                        tradeDate = s.date.trim(),
                        note = s.note,
                        emotion = s.emotion,
                        score = s.score.takeIf { it > 0 },
                        tags = s.tags,
                        quality = s.originQuality,
                        // 成交日汇率（REQ-ACC-15）：A股为空 → 存 NULL（折算回退最新汇率）
                        fxRate = parseFxRate(s.fxRate),
                        // 印花税（REQ-ACC-17）：与界面展示同源（只读，法定税率）
                        stampDuty = stampDutyOf(),
                    )
                } else {
                    val newTxId = trade.addTransaction(
                        securityId = targetSecurityId,
                        side = s.side,
                        quantity = qty,
                        price = price,
                        fee = fee,
                        tradeDate = s.date.trim(),
                        note = s.note,
                        emotion = s.emotion,
                        score = s.score.takeIf { it > 0 },
                        tags = s.tags,
                        fxRate = parseFxRate(s.fxRate),
                        // 印花税（REQ-ACC-17）：同编辑态，与界面展示同源
                        stampDuty = stampDutyOf(),
                    )
                    // 截图入库（REQ-ACC-11）：新增态在拿到 id 后写
                    s.photo?.let { trade.saveTradePhoto(newTxId, "image/jpeg", it) }
                }
                // 「不计入统计与分析」是**标的级**开关：保存时同步落库（老周 2026-09-20）。
                // 放在交易入库之后、且只写这一个字段（不动别的标的属性）。
                runCatching { security.setSecurityExcludeFromStats(targetSecurityId, s.excludeFromStats) }

                // 编辑态：替换 / 移除截图
                s.editTxId?.let { id ->
                    when {
                        s.photo != null -> trade.saveTradePhoto(id, "image/jpeg", s.photo)
                        s.photoRemoved -> trade.deleteTradePhoto(id)
                    }
                }

                // 超卖提示在保存后给出（重放会按实际持仓截断，不阻断保存）。
                // ⚠️ 上限与 [sellableLimitOf] **同源**（编辑态剔除本笔）：
                // 此前直接读保存后的持仓，改一笔"清仓卖出"的备注会被误报
                // 「当前仅持有 0 股，将按实际持仓截断」——其实一笔都没截断（老周 2026-09-29 报）。
                val holding = runCatching { sellableLimitOf(s, targetSecurityId) }.getOrNull()
                    ?: repo.loadSecurityDetail(targetSecurityId)?.position?.quantity
                    ?: 0.0
                val warn = TradeForms.overSellHint(s.side.name, qty, holding)

                // 统一落 saved 态；导航（refresh+pop）由 UI 层 LaunchedEffect 在主线程执行：
                // 无警示 → 自动返回；有警示 → 停留展示「将按实际持仓截断」再手动返回。
                _state.update { it.copy(saving = false, saved = true, warning = warn) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        saving = false,
                        error = "保存失败：${t.message ?: t::class.simpleName}",
                        saveFailTick = it.saveFailTick + 1,
                    )
                }
            }
        }
    }

    // ------------------------------------------------- 删除（编辑态专用，老周 2026-09-19）
    //
    // 入口由详情页「左滑 → 🗑」移到**编辑页底部按钮**（与交易计划的操作方式统一）。
    // ⚠️ 但 REQ-ACC-09 的口径不变：**先算删除预告让用户看清重算结果，确认后才真正删库**。
    // 预告与实际删除共用同一套重放（TradeForms.previewAfterDelete），因此两者必然一致。

    /** 请求删除：先算预告（重放前后差异），非空则弹确认弹窗 */
    fun requestDelete() {
        val s = _state.value
        val txId = s.editTxId ?: return
        val sid = s.securityId ?: return
        scope.launch {
            val preview = runCatching { trade.previewAfterDelete(sid, txId) }.getOrNull()
            _state.update {
                if (preview == null) {
                    it.copy(
                        error = "找不到该笔交易，无法预览重算结果",
                        saveFailTick = it.saveFailTick + 1,
                    )
                } else {
                    it.copy(deletePreview = preview)
                }
            }
        }
    }

    fun cancelDelete() = _state.update { it.copy(deletePreview = null) }

    /** 确认删除：落库后置 deleted，导航由 UI 层 LaunchedEffect 执行 */
    fun confirmDelete() {
        val txId = _state.value.editTxId ?: return
        scope.launch {
            _state.update { it.copy(deleting = true) }
            val err = runCatching { trade.deleteTransaction(txId) }.exceptionOrNull()
            _state.update {
                if (err != null) {
                    it.copy(
                        deleting = false,
                        error = "删除失败：${err.message ?: err::class.simpleName}",
                        saveFailTick = it.saveFailTick + 1,
                    )
                } else {
                    it.copy(deleting = false, deletePreview = null, deleted = true)
                }
            }
        }
    }

    private companion object {
        /** 备注字数上限（500 中文字，小B 定稿） */
        const val NOTE_MAX_LEN = 500

        /** 内置策略标签（与迁移 3.sqm / DemoData 同源；管理页改名后以此兜底） */
        val DEFAULT_TAGS = listOf("价值投资", "突破", "网格", "趋势", "波段", "打板")

        fun defaultCurrencyOf(market: Market): Currency = when (market) {
            Market.HK -> Currency.HKD
            Market.US -> Currency.USD
            else -> Currency.CNY
        }
    }
}

@Composable
/**
 * ⚠️ 2026-09-28 拆分：4 个依赖 —— `repo`（标的/情绪/标签/设置）+ `trade` + `fx` + `quote`。
 */
fun rememberTradeFormHolder(
    repo: PortfolioRepository,
    trade: TradeRepository,
    fx: FxRepository,
    quote: QuoteRepository,
    settings: SettingsRepository,
    tag: TagRepository,
    watch: WatchlistRepository,
    emotion: EmotionRepository,
    security: SecurityRepository,
): TradeFormHolder {
    val scope = rememberCoroutineScope()
    // ⚠️ 顺序必须与构造一致（settings 紧随 repo，tag 紧随 quote）
    return remember(repo, settings, trade, fx, quote, tag, watch, emotion, security) {
        TradeFormHolder(repo, settings, trade, fx, quote, tag, watch, emotion, security, scope)
    }
}
