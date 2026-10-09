package com.stocknote.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.core.calc.CivilDate
import com.stocknote.core.format.Format
import com.stocknote.feature.nav.AppNav
import com.stocknote.feature.theme.StockNoteColors
import kotlin.math.absoluteValue

/**
 * 盈亏日历热力图（REQ-VIEW-06）。
 *
 * 数据来源：资产曲线的**逐日差分**（今日总资产 − 昨日总资产），
 * 即"当天的钱多了还是少了"，与统计页「当日盈亏」语义递进
 * （后者只看持仓的 现价−昨收，本页看整个组合的净资产变化）。
 *
 * ⚠️ 老周 2026-09-29 修口径：**当日出入金必须剔除** —— 入金只是把本金搬进账户，不是赚的钱。
 * 真机实测：9-01 入金 19,000，剔除前日历显示 +2,212，剔除后与券商当日盈亏 −16,986 口径一致。
 * 剔除逻辑在 [com.stocknote.core.calc.EquityCurve.dailyPnl]（纯函数，可单测）。
 *
 *  tháng 切换：默认展示曲线最后一个月，可左右切月。
 */
@Composable
fun PnlCalendarScreen(
    points: List<com.stocknote.core.calc.EquityCurve.Point>,
    /** 逐日出入金净额（date -> 本位币，存入为正），从差分里剔除。 */
    cashFlowsByDate: Map<String, Double> = emptyMap(),
    /**
     * securityId -> 标的名称（老周 2026-10-01）。
     * 点某天的明细要按标的列盈亏，而 `Point.pnlBySecurity` 里只有 id，得靠它转成名字。
     */
    securityNames: Map<String, String> = emptyMap(),
    /**
     * 按需加载某天的**流水**（交易 / 出入金 / 分红）—— 老周 2026-10-01。
     * null = 不提供该能力（明细里就只显示「各标的盈亏」那一段）。
     */
    loadDayDetail: (suspend (String) -> com.stocknote.data.repo.PortfolioRepository.DayDetail)? = null,
    /**
     * 按需加载**某个月**的流水（老周 2026-10-08）—— 「当月明细」里的**流水汇总**用它。
     * 入参是 `yyyy-MM`。null = 当月明细里就不显示流水汇总那一段。
     */
    loadMonthDetail: (suspend (String) -> com.stocknote.data.repo.PortfolioRepository.DayDetail)? = null,
    modifier: Modifier = Modifier,
) {
    // 逐日盈亏：date -> delta（**已剔除出入金**）
    val dailyPnl = remember(points, cashFlowsByDate) {
        com.stocknote.core.calc.EquityCurve.dailyPnl(points, cashFlowsByDate)
    }

    // 可选月份列表（从数据里取，升序）—— ⚠️ 必须排在「选中某天」**之前**：
    // 下面 `selectedDate` 的 remember key 要用到 `month`。
    val months = remember(dailyPnl) {
        dailyPnl.keys.map { it.take(7) }.distinct().sorted()
    }
    var monthIndex by remember(months) { mutableStateOf(months.lastIndex.coerceAtLeast(0)) }
    val month = months.getOrNull(monthIndex).orEmpty()

    // ---- 选中某天 → 在日历下方展开明细（老周 2026-10-01）----
    // ⚠️ key 里**必须带 `month`**（老周 2026-10-08 报「点上一月 / 下一月，明细没跟着变」）：
    //    原来只 key 了 `points`，切月时选中的那天会一直留着 —— 而那天已经不在当前日历上了，
    //    明细区就一直挂着**旧月份**那一天的卡片。key 上 `month` 后，切月即收起、自动回落到当月明细。
    // `points` 一并 key：刷新数据后同样收起，避免挂着一个已经不存在的日期。
    var selectedDate by remember(points, month) { mutableStateOf<String?>(null) }
    var dayDetail by remember(selectedDate) {
        mutableStateOf<com.stocknote.data.repo.PortfolioRepository.DayDetail?>(null)
    }
    var detailLoading by remember(selectedDate) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(selectedDate) {
        val d = selectedDate
        if (d == null || loadDayDetail == null) {
            dayDetail = null
            return@LaunchedEffect
        }
        detailLoading = true
        dayDetail = runCatching { loadDayDetail(d) }.getOrNull()
        detailLoading = false
    }

    val monthDays = remember(month, dailyPnl) {
        // ⚠️ 老周 2026-09-29 修：此前这里还 `&& dayOfWeek(it) < 5` 把**周末剔掉**，
        // 于是周末发生的现金变动被月汇总**漏掉**（实测 9-05 周六分红 +300 凭空消失），
        // 与 DayCell 注释「仍计入月度合计」自相矛盾。
        // 现在月汇总取**当月全部日期**；周末只是格子不显示金额（仍显示「休」），数据不再丢。
        // （周末休市、市值顺延不变，delta 通常为 0，不会污染「涨跌天数」计数。）
        dailyPnl.filterKeys { it.startsWith(month) }
    }
    val maxAbs = remember(monthDays) { monthDays.values.maxOfOrNull { it.absoluteValue } ?: 1.0 }

    // ---- 当月明细的聚合（老周 2026-10-08：没选中某天时，明细区缺省显示**当月**）----
    // ① 各标的当月累计盈亏：把当月每个曲线点的 pnlBySecurity 逐日累加
    //  （口径与单日完全一致：纯价格与汇率波动，**不含分红** —— 分红没有标的归属，见 DayDetailCard 注释）
    val monthPnlBySecurity = remember(month, points) {
        val acc = LinkedHashMap<String, Double>()
        points.forEach { p ->
            if (!p.date.startsWith(month)) return@forEach
            p.pnlBySecurity.forEach { (id, v) -> acc[id] = (acc[id] ?: 0.0) + v }
        }
        acc
    }
    // ② 当月出入金净额：`cashFlowsByDate` 本来就是逐日的，按月前缀汇总即可
    val monthCashFlow = remember(month, cashFlowsByDate) {
        cashFlowsByDate.filterKeys { it.startsWith(month) }.values.sum()
    }
    // ③ 各标的**当月涨跌幅**（老周 2026-10-08）：口径 = 当月各日日涨跌幅连乘 ∏(1+日涨跌) − 1，
    //  等价于「上月末收盘 → 本月末收盘」。实现在 `EquityCurve.monthlyChangePct`（纯函数、带单测）。
    val monthChgBySecurity = remember(month, points) {
        com.stocknote.core.calc.EquityCurve.monthlyChangePct(points, month)
    }
    // ④ 当月流水汇总（老周 2026-10-08）：按月查一次（`monthDetail` 与 `dayDetail` 同一套过滤）
    var monthFlowDetail by remember(month) {
        mutableStateOf<com.stocknote.data.repo.PortfolioRepository.DayDetail?>(null)
    }
    var monthFlowLoading by remember(month) { mutableStateOf(false) }
    // ⚠️ 这里**不跟 selectedDate 走**（老周 2026-10-08 加了「有交易的日子在日历上打标记」）：
    // 标记在任何选中状态下都得在，所以按月查一次、常驻。查的是本地库，代价很小。
    androidx.compose.runtime.LaunchedEffect(month) {
        if (loadMonthDetail == null) return@LaunchedEffect
        monthFlowLoading = true
        monthFlowDetail = runCatching { loadMonthDetail(month) }.getOrNull()
        monthFlowLoading = false
    }
    // 当月**有交易的日子**（买 / 卖 / 转增资本）→ 日历格右上角打点
    val tradeDates = remember(monthFlowDetail) {
        monthFlowDetail?.trades?.map { it.tradeDate }?.toSet().orEmpty()
    }

    // 注：此处曾用 TextMeasurer 实测字符宽来自适应字号，真机两轮验证都不可靠
    //（测量拿到的宽度与实际渲染不一致 → 数字照样被裁）。最终方案（老周 2026-09-23 定）：
    // **字号固定不缩小，金额与日期拉开、超长金额折两行**，详见 DayCell。

    LazyColumn(
        modifier = modifier.fillMaxSize().background(StockNoteColors.Background),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TopBar(title = "盈亏日历") { AppNav.pop() }
            }
        }

        if (points.size < 2 || months.isEmpty()) {
            item {
                SectionCard(title = "暂无数据") {
                    Text(
                        "需要先有资产曲线（至少 2 个估值点）才能算每日盈亏。\n" +
                            "请确认网络可用后，到「持仓」页顶部的资产曲线加载一次。",
                        fontSize = 13.sp,
                        color = StockNoteColors.TextTertiary,
                    )
                }
            }
            return@LazyColumn
        }

        // ---- 月汇总 ----
        item {
            val monthTotal = monthDays.values.sum()
            CardBox {
                Row(Modifier.fillMaxWidth().padding(15.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("当月盈亏", fontSize = 12.sp, color = StockNoteColors.TextSecondary)
                        Text(
                            Format.moneySigned(monthTotal),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (monthTotal >= 0) StockNoteColors.Up else StockNoteColors.Down,
                        )
                    }
                    val winDays = monthDays.values.count { it > 0 }
                    val loseDays = monthDays.values.count { it < 0 }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("盈亏天数", fontSize = 12.sp, color = StockNoteColors.TextSecondary)
                        Text(
                            "$winDays 涨 / $loseDays 跌",
                            fontSize = 13.sp,
                            color = StockNoteColors.TextPrimary,
                        )
                    }
                }
            }
        }

        // ---- 日历网格 ----
        item {
            SectionCard(title = "$month 每日盈亏") {
                val today = remember { com.stocknote.data.platform.todayIso() }
                // 月份切换（老周 2026-09-23：原来单独占一张带「盈亏日历」标题的卡，和顶部栏标题重复，
                // 现在并入日历卡顶部；「本月 N 天」改直白说法「已记录 N 天」）
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "‹ 上一月",
                        fontSize = 13.sp,
                        color = if (monthIndex > 0) StockNoteColors.Brand else StockNoteColors.TextTertiary,
                        modifier = Modifier
                            .clickable(enabled = monthIndex > 0) { monthIndex-- }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                    )
                    Text(
                        "已记录 ${monthDays.size} 天",
                        fontSize = 12.sp,
                        color = StockNoteColors.TextSecondary,
                    )
                    Text(
                        "下一月 ›",
                        fontSize = 13.sp,
                        color = if (monthIndex < months.lastIndex) StockNoteColors.Brand else StockNoteColors.TextTertiary,
                        modifier = Modifier
                            .clickable(enabled = monthIndex < months.lastIndex) { monthIndex++ }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                    )
                }
                // 星期表头（老周 2026-09-23：改成**周日起始**，与原型图一致）
                Row(Modifier.fillMaxWidth()) {
                    listOf("日", "一", "二", "三", "四", "五", "六").forEach { w ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(w, fontSize = 11.sp, color = StockNoteColors.TextTertiary)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))

                // 计算该月 1 号落在哪一列（周日=0）与当月天数 → 渲染**整月**网格
                val firstDay = if (month.length >= 7) "$month-01" else null
                val lead = remember(firstDay) {
                    // CivilDate.dayOfWeek: 0=周一…6=周日 → 转成「周日=0」的表头偏移
                    if (firstDay == null) 0 else (CivilDate.dayOfWeek(firstDay) + 1) % 7
                }
                val daysInMonth = remember(firstDay) {
                    if (firstDay == null) 0
                    else CivilDate.daysInMonth(
                        CivilDate.parseIso(firstDay).year,
                        CivilDate.parseIso(firstDay).month,
                    )
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    modifier = Modifier.fillMaxWidth()
                        // 行数按向上取整算（原公式 (n/7)+1 在整除时会多出一整行空白）
                        .height((((daysInMonth + lead + 6) / 7) * 56).dp),
                    userScrollEnabled = false,
                ) {
                    items(lead) {
                        Box(Modifier.padding(2.dp).height(52.dp))
                    }
                    items(daysInMonth) { i ->
                        val dayNum = i + 1
                        // ⚠️ 不用 `"%02d".format(...)` —— String.format 是 JVM 专有（iOS 编译失败）
                        val date = "$month-" + dayNum.toString().padStart(2, '0')
                        val pnl = monthDays[date]
                        DayCell(
                            day = dayNum.toString(),
                            pnl = pnl,
                            // 周末（周六=5 / 周日=6）休市：无盈亏时显示「休」
                            isWeekend = CivilDate.dayOfWeek(date) >= 5,
                            isToday = date == today,
                            intensity = if (pnl != null && maxAbs > 0) {
                                (pnl.absoluteValue / maxAbs).toFloat().coerceIn(0f, 1f)
                            } else 0f,
                            selected = date == selectedDate,
                            // 这天有买卖交易 → 右上角打点（老周 2026-10-08）
                            hasTrade = date in tradeDates,
                            // 再点同一天 = 收起（老周 2026-10-01）
                            onClick = { selectedDate = if (selectedDate == date) null else date },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "格子底色与数字 = 当日盈亏（红涨绿跌，**已剔除出入金**，含浮盈浮亏与现金分红，金额取整）；" +
                        "实心格 = 今天；白底「休」= 周末休市（该日盈亏仍计入当月合计）；白底无字 = 暂无数据；" +
                        "**右上角圆点 = 这天有买卖交易**；" +
                        "**点格子 → 下方看这天的明细**；不点格子时，下方默认显示**当月明细**。",
                    fontSize = 11.sp,
                    color = StockNoteColors.TextTertiary,
                )
            }
        }

        // ---- 明细区（老周 2026-10-01 起：点某天看那天 / 2026-10-08 起：不点就缺省看**当月**）----
        val sel = selectedDate
        item {
            if (sel != null) {
                val selPoint = points.firstOrNull { it.date == sel }
                DayDetailCard(
                    date = sel,
                    pnl = dailyPnl[sel],
                    pnlBySecurity = selPoint?.pnlBySecurity ?: emptyMap(),
                    // 各标的当日涨跌幅（老周 2026-10-03）：(今收 − 昨收) / 昨收，随曲线一起算好
                    chgBySecurity = selPoint?.chgPctBySecurity ?: emptyMap(),
                    securityNames = securityNames,
                    detail = dayDetail,
                    loading = detailLoading,
                    // 收起 = 回到"当月明细"（不再是把明细整段藏起来）
                    onClose = { selectedDate = null },
                )
            } else {
                MonthDetailCard(
                    month = month,
                    pnl = monthDays.values.sum(),
                    cashFlow = monthCashFlow,
                    pnlBySecurity = monthPnlBySecurity,
                    // 各标的当月涨跌幅（日涨跌连乘）—— 老周 2026-10-08
                    chgBySecurity = monthChgBySecurity,
                    securityNames = securityNames,
                    dayCount = monthDays.size,
                    // 当月流水汇总（与「每日流水」同一套数据，只是按月过滤）
                    detail = monthFlowDetail,
                    loading = monthFlowLoading,
                )
            }
        }

        item { ProtoFoot("口径：逐日总资产差分 − 当日出入金（含浮盈浮亏与现金分红；入金不计收益），来自资产曲线") }
    }
}

/**
 * 日历格（老周 2026-09-23 按原型图重做）：
 *  - 有盈亏：**浅色底 + 同色系深色字**（涨红 / 跌绿），金额完整 2 位小数（+23481.34 / -16986.17）；
 *  - 今天：**实心色底 + 白字**（涨红/跌绿），一眼能定位；
 *  - 周末无数据：灰底 + 灰日期 +「休」；
 *  - 其余无数据（未来日期）：更浅的灰底 + 灰日期，不显示金额；
 *  - **这天有买卖交易：右上角一个品牌色圆点**（老周 2026-10-08）。
 * 底色深浅随盈亏绝对值相对当月最大波动微调（保留信息量，但不做重色块）。
 *
 * 布局（老周 2026-09-23 真机反馈后定）：**日期固定贴顶部，金额/「休」居格子下方**；
 * 金额**取整（四舍五入）、不带正负号**；**周末一律白底 +「休」**、不显示金额
 *（周末也可能有真实现金变动，例如除权日落在周末的分红、或周末入金 —— 那些不在日历上显示，
 *  但仍计入月度合计，数据不丢）。
 */
@Composable
private fun DayCell(
    day: String,
    pnl: Double?,
    isWeekend: Boolean,
    isToday: Boolean,
    intensity: Float,
    /** 是否被选中（老周 2026-10-01：点某天 → 日历下方展开这天的明细） */
    selected: Boolean,
    /** 这天是否有买卖交易（老周 2026-10-08：有则在右上角打点） */
    hasTrade: Boolean,
    onClick: () -> Unit,
) {
    // 周末：**一律白底 + 「休」**，即使当天确有现金变动（如除权日落在周末的分红）也不显示金额
    //（老周 2026-09-23：休息日直接白底）。
    // 另外金额**取整（四舍五入）、不带正负号**（老周 2026-09-23）；绝对值 <0.5 视为无盈亏。
    val amt: Double? = if (isWeekend) null else pnl?.takeIf { it.absoluteValue >= 0.5 }
    val showMoney = amt != null
    val ink = when {
        amt == null -> StockNoteColors.TextTertiary
        amt >= 0 -> StockNoteColors.Up
        else -> StockNoteColors.Down
    }
    val bg = when {
        isToday && showMoney -> ink                                   // 今天：实心色底
        showMoney -> ink.copy(alpha = 0.10f + 0.10f * intensity)       // 有盈亏：浅底色，深浅随幅度
        else -> Color.White                                           // 周末 / 无数据 → 白底
    }
    val textColor = when {
        isToday && showMoney -> Color.White
        isToday -> StockNoteColors.Brand        // 今天（周末或无数据）用品牌色日期标出
        else -> ink
    }
    val mainText = when {
        amt != null -> Format.plain(kotlin.math.abs(amt), 0)
        isWeekend -> "休"
        else -> null
    }
    Box(
        modifier = Modifier
            // 内边距 2dp → 1dp：格子变宽，缓解长金额被裁（实测 -5513.47 这类会缺末尾字符）
            .padding(1.dp)
            .height(52.dp)
            .background(bg, RoundedCornerShape(8.dp))
            // 选中态：描一圈品牌色边框（老周 2026-10-01）
            .then(
                if (selected) {
                    Modifier.border(2.dp, StockNoteColors.Brand, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    ) {
        // 日期固定贴顶、不参与居中 → 所有格子的日期落在同一条水平线上
        Text(
            day,
            fontSize = 11.sp,
            fontWeight = if (isToday || pnl != null) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
        )
        if (mainText != null) {
            // 金额（老周 2026-09-23 定的规则）：
            //  1) **往下移**：底部对齐、与顶部日期拉开距离（原来居中时两行挨在一起看不清）；
            //  2) 字号**固定不缩小**（不再做自适应缩字）；
            //  3) 太长就**折两行**：数字串本身没有断点，在中间插一个零宽空格当折行点——
            //     放得下时它不占位、不会折；放不下才折成两行。
            // 金额现在是**纯整数**（取整 + 无正负号），4~6 位一行放得下；
            // 仍每 4 位插一个零宽空格作断点，应对 7 位以上的极端金额（放不下时折两行，放得下不占位）。
            val display = mainText.replace(Regex("(\\d{4})(?=\\d)"), "$1\u200B")
            Text(
                display,
                fontSize = 8.5.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 3.dp),
            )
        }
        // 有买卖交易的标记（老周 2026-10-08）：右上角一个小圆点。
        // 为什么用点不用字：格子只有 ~48dp 宽、还要塞日期与金额，加文字必然挤到金额；
        // 点在右上角也不碰日期（日期是 TopCenter），涨红/跌绿/白底几种底色上都看得见。
        if (hasTrade) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .size(5.dp)
                    .background(StockNoteColors.Brand, CircleShape),
            )
        }
    }
}

/**
 * 选中日的**明细卡**（老周 2026-10-01）—— 分两段：
 *
 * ① **各标的盈亏**：来自 `EquityCurve.Point.pnlBySecurity`，口径是
 *    `市值(今日) − 市值(昨日) + 当日该标的现金净流入` = **纯价格与汇率波动**。
 *    ⚠️ **不含现金分红** —— 分红在数据里只有「除权日 + 金额」、没有标的归属，
 *    拆不进任何一只票，所以它出现在下面第 ② 段（但它确实计入「当日盈亏」总额，
 *    两边相差的就是分红，这是**设计如此**，不是对不上账）。
 *
 * ② **当日流水**：买卖 / 出入金 / 分红 —— 回答「钱为什么动了」，与盈亏本身是两个视角。
 */
@Composable
private fun DayDetailCard(
    date: String,
    pnl: Double?,
    pnlBySecurity: Map<String, Double>,
    /** 各标的当日涨跌幅（比率，如 0.0123 = +1.23%）；缺失则不显示括号。 */
    chgBySecurity: Map<String, Double> = emptyMap(),
    securityNames: Map<String, String>,
    detail: com.stocknote.data.repo.PortfolioRepository.DayDetail?,
    loading: Boolean,
    onClose: () -> Unit,
) {
    SectionCard(title = "$date 明细") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("当日盈亏", fontSize = 12.sp, color = StockNoteColors.TextSecondary)
            Text(
                "收起 ✕",
                fontSize = 12.sp,
                color = StockNoteColors.Brand,
                modifier = Modifier.clickable(onClick = onClose).padding(4.dp),
            )
        }
        Text(
            if (pnl == null) "—" else Format.moneySigned(pnl),
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = when {
                pnl == null -> StockNoteColors.TextTertiary
                pnl >= 0 -> StockNoteColors.Up
                else -> StockNoteColors.Down
            },
        )

        // ---- ① 各标的盈亏 ----
        Spacer(Modifier.height(14.dp))
        Text("各标的盈亏", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
        Text(
            "纯价格与汇率波动（不含分红，分红见下方流水）；% = 该标的当日涨跌幅",
            fontSize = 11.sp,
            color = StockNoteColors.TextTertiary,
        )
        Spacer(Modifier.height(6.dp))
        if (pnlBySecurity.isEmpty()) {
            Text(
                "这天没有持仓盈亏（可能没有持仓，或当日无行情）",
                fontSize = 12.sp,
                color = StockNoteColors.TextTertiary,
            )
        } else {
            // 从盈到亏排序（老周 2026-10-03）：原按**绝对值**降序会把「今天最大的亏损」排在最前，
            // 现改为按**净额**降序 —— 赚得最多的在最上、亏得最多的在最下。
            // 每只标的后面的 % 是**该股当日涨跌幅**（不是占当日盈亏的比例，老周 2026-10-03 改）。
            pnlBySecurity.entries
                .sortedByDescending { it.value }
                .forEach { (id, v) ->
                    val chg = chgBySecurity[id]
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            securityNames[id] ?: id,
                            fontSize = 13.sp,
                            color = StockNoteColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            buildString {
                                append(Format.moneySigned(v))
                                if (chg != null) {
                                    append("  ")
                                    append(Format.percent(chg, decimals = 2))
                                }
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (v >= 0) StockNoteColors.Up else StockNoteColors.Down,
                        )
                    }
                }
        }

        // ---- ② 当日流水 ----
        Spacer(Modifier.height(16.dp))
        Text("当日流水", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        when {
            loading -> Text("读取中…", fontSize = 12.sp, color = StockNoteColors.TextTertiary)

            detail == null -> Text(
                "流水读取失败（明细数据未接上）",
                fontSize = 12.sp,
                color = StockNoteColors.TextTertiary,
            )

            detail.isEmpty -> Text(
                "这天没有买卖、出入金或分红",
                fontSize = 12.sp,
                color = StockNoteColors.TextTertiary,
            )

            else -> {
                detail.trades.forEach { t ->
                    val side = when (t.side) {
                        com.stocknote.core.model.TradeSide.BUY -> "买入"
                        com.stocknote.core.model.TradeSide.SELL -> "卖出"
                        com.stocknote.core.model.TradeSide.CAPITALIZE -> "转增资本"
                    }
                    DetailRow(
                        label = securityNames[t.securityId] ?: t.securityId,
                        value = "$side ${Format.plain(t.quantity, 0)} 股 @ ${Format.plain(t.price, 3)}",
                    )
                }
                detail.cashFlows.forEach { f ->
                    // 正 = 存入、负 = 取出；外币出入金把原币金额一起显示
                    val kind = if (f.amountBase >= 0) "入金" else "出金"
                    val orig = if (f.currencyCode == "CNY") {
                        ""
                    } else {
                        "（${Format.plain(f.amountOrig, 2)} ${f.currencyCode}）"
                    }
                    DetailRow(
                        label = kind,
                        // ⚠️ 必须是 `kotlin.math.abs` —— 这里是 **commonMain**，
                        // 而 `Math` 是 **JVM 专属**（`java.lang.Math`）：
                        // Android/JVM 上它是隐式导入、编得过去，**iOS(K/N) 上直接 Unresolved reference**。
                        // 2026-10-01 run `36839514419` 就是被这一行卡死在 compileKotlinIosArm64
                        // （本地 Android 全绿、Windows 又编不了 iOS → 只有 CI 能发现）。
                        value = Format.moneySigned(kotlin.math.abs(f.amountBase)) + orig,
                    )
                }
                detail.dividends.forEach { d ->
                    val name = securityNames[d.securityId] ?: d.securityId
                    val text = when {
                        d.type == "CASH" -> "现金分红 ${Format.moneySigned(d.amountBase)}"
                        d.type == "BONUS" -> "送股 ${Format.plain(d.bonusShares, 0)} 股"
                        d.type == "RIGHTS" -> "配股 ${Format.plain(d.rightsShares, 0)} 股"
                        else -> d.type
                    }
                    DetailRow(label = name, value = text)
                }
            }
        }
    }
}

/**
 * **当月明细卡**（老周 2026-10-08）—— 日历里**没有选中某天**时的缺省明细。
 *
 * 与 [DayDetailCard] 占**同一个位置**，二者互斥：选中某天看那天的、否则看当月
 *（所以点「收起 ✕」就是回到当月，而不是把明细整段藏起来）。
 *
 * 内容：当月盈亏合计 / 各标的当月累计盈亏（带**当月涨跌幅**）/ **当月流水汇总**。
 * 逐笔流水仍按天看（点日历里的某一天），这里只给汇总。
 *
 * ⚠️ 流水汇总走的是 `monthDetail`（按 `yyyy-MM` 前缀过滤），**不是**把 30 天的 `dayDetail` 拉一遍 ——
 * 后者每次都是全表过滤再筛日期，为一个月拉 30 次不划算。
 */
@Composable
private fun MonthDetailCard(
    month: String,
    /** 当月盈亏合计（**已剔除出入金**，与日历格子同源） */
    pnl: Double,
    /** 当月出入金净额（存入为正）。只是显示出来让口径可见，不参与 [pnl] 计算。 */
    cashFlow: Double,
    /** 各标的当月累计盈亏（securityId -> 金额） */
    pnlBySecurity: Map<String, Double>,
    /** 各标的**当月涨跌幅**（securityId -> 比率，如 0.0123 = +1.23%）；缺失则不显示 */
    chgBySecurity: Map<String, Double> = emptyMap(),
    securityNames: Map<String, String>,
    /** 当月有记录的天数 */
    dayCount: Int,
    /** 当月流水（按月过滤，与单日同一套数据）；null = 未接上或读取失败 */
    detail: com.stocknote.data.repo.PortfolioRepository.DayDetail?,
    loading: Boolean,
) {
    SectionCard(title = "$month 当月明细") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("当月盈亏", fontSize = 12.sp, color = StockNoteColors.TextSecondary)
            Text("未选中某天时的缺省明细", fontSize = 11.sp, color = StockNoteColors.TextTertiary)
        }
        Text(
            Format.moneySigned(pnl),
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (pnl >= 0) StockNoteColors.Up else StockNoteColors.Down,
        )
        Text(
            // 没有出入金的月份就不提这一句，免得"剔除 +0.00"看着像噪音
            if (cashFlow.absoluteValue >= 0.01) {
                "已剔除当月出入金净额 ${Format.moneySigned(cashFlow)}（入金只是搬本金，不算收益）· 当月有记录 $dayCount 天"
            } else {
                "当月无出入金 · 当月有记录 $dayCount 天"
            },
            fontSize = 11.sp,
            color = StockNoteColors.TextTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )

        // ---- 各标的当月累计盈亏 ----
        Spacer(Modifier.height(14.dp))
        Text("各标的盈亏（当月累计）", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
        Text(
            "逐日价格与汇率波动累加（不含分红）；% = 该标的当月涨跌幅",
            fontSize = 11.sp,
            color = StockNoteColors.TextTertiary,
        )
        Spacer(Modifier.height(6.dp))
        if (pnlBySecurity.isEmpty()) {
            Text(
                "当月没有持仓盈亏记录（可能没有持仓，或当月无行情）",
                fontSize = 12.sp,
                color = StockNoteColors.TextTertiary,
            )
        } else {
            // 与单日明细同一套排序与配色：按**净额**从盈到亏（赚得最多的在最上）
            pnlBySecurity.entries
                .sortedByDescending { it.value }
                .forEach { (id, v) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            securityNames[id] ?: id,
                            fontSize = 13.sp,
                            color = StockNoteColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            buildString {
                                append(Format.moneySigned(v))
                                // 该标的的**当月涨跌幅**（老周 2026-10-08）：口径与单日一致，只是按日连乘到整月
                                val chg = chgBySecurity[id]
                                if (chg != null) {
                                    append("  ")
                                    append(Format.percent(chg, decimals = 2))
                                }
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (v >= 0) StockNoteColors.Up else StockNoteColors.Down,
                        )
                    }
                }
        }

        // ---- ② 当月流水汇总（老周 2026-10-08）----
        Spacer(Modifier.height(16.dp))
        Text("当月流水汇总", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
        Text(
            "按月汇总；要看逐笔，点日历里的某一天",
            fontSize = 11.sp,
            color = StockNoteColors.TextTertiary,
        )
        Spacer(Modifier.height(6.dp))
        when {
            loading -> Text("读取中…", fontSize = 12.sp, color = StockNoteColors.TextTertiary)

            detail == null -> Text(
                "流水读取失败（明细数据未接上）",
                fontSize = 12.sp,
                color = StockNoteColors.TextTertiary,
            )

            detail.isEmpty -> Text(
                "当月没有买卖、出入金或分红",
                fontSize = 12.sp,
                color = StockNoteColors.TextTertiary,
            )

            else -> {
                // 交易：只报**笔数与只数**，不报金额 —— 买卖可能跨币种（HKD/USD 原币），
                // 原币金额直接相加是错的；折算本位币又要再引一套汇率口径，不值得。
                if (detail.trades.isNotEmpty()) {
                    val buy = detail.trades.count { it.side == com.stocknote.core.model.TradeSide.BUY }
                    val sell = detail.trades.count { it.side == com.stocknote.core.model.TradeSide.SELL }
                    val cap = detail.trades.count { it.side == com.stocknote.core.model.TradeSide.CAPITALIZE }
                    DetailRow(
                        label = "交易",
                        value = buildString {
                            append("${detail.trades.size} 笔")
                            if (buy > 0) append(" · 买 $buy")
                            if (sell > 0) append(" · 卖 $sell")
                            if (cap > 0) append(" · 转增 $cap")
                            append(" · 涉及 ${detail.trades.map { it.securityId }.distinct().size} 只")
                        },
                    )
                }
                if (detail.cashFlows.isNotEmpty()) {
                    val inflow = detail.cashFlows.filter { it.amountBase >= 0 }.sumOf { it.amountBase }
                    val outflow = detail.cashFlows.filter { it.amountBase < 0 }.sumOf { -it.amountBase }
                    // ⚠️ 拆成两行：合成一句（入/出/净额）后 value 太长，会把 DetailRow 的
                    // label 挤成一字一行（2026-10-08 真机踩到）。净额不在这里重复 ——
                    // 卡片副标题已经写了「已剔除当月出入金净额 …」。
                    DetailRow(label = "入金", value = Format.money(inflow))
                    DetailRow(label = "出金", value = Format.money(outflow))
                }
                val cashDiv = detail.dividends.filter { it.type == "CASH" }
                if (cashDiv.isNotEmpty()) {
                    DetailRow(
                        label = "现金分红",
                        value = "${Format.moneySigned(cashDiv.sumOf { it.amountBase })}（${cashDiv.size} 笔）",
                    )
                }
                val bonusShares = detail.dividends.sumOf { it.bonusShares }
                val rightsShares = detail.dividends.sumOf { it.rightsShares }
                if (bonusShares > 0 || rightsShares > 0) {
                    DetailRow(
                        label = "送股 / 配股",
                        value = "送股 ${Format.plain(bonusShares, 0)} 股 · 配股 ${Format.plain(rightsShares, 0)} 股",
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "点日历里任意一天 → 看那天的各标的盈亏与当日流水",
            fontSize = 11.sp,
            color = StockNoteColors.TextTertiary,
        )
    }
}

/** 明细里的一行：左侧名目、右侧内容（右对齐，长内容自动换行）。 */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = StockNoteColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            fontSize = 13.sp,
            color = StockNoteColors.TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}
