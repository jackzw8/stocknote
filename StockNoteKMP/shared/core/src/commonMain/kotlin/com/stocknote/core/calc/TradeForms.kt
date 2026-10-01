package com.stocknote.core.calc

import com.stocknote.core.format.Format
import com.stocknote.core.model.DeletePreview
import com.stocknote.core.model.Security
import com.stocknote.core.model.Transaction

/**
 * 记一笔表单的校验与删除预告。
 *
 * 校验放在领域层而不是 UI 层，理由同 ChartMath / Format：
 * 纯函数、两端共享、可单测。UI 只负责把错误文案摆到对应字段旁边。
 */
object TradeForms {

    /**
     * 佣金下限（老周 2026-09-19）：**不足 5 元按 5 元计**（券商通行规则）。
     *
     * 边界（本版实现取的默认口径，如需调整请说）：
     *  - **免佣不动**：费率填 0 表示免佣，手续费就是 0，不受下限约束；
     *  - **按币种单位**：A 股＝元、港股＝港元（跟随该笔交易的币种），不做跨币种换算；
     *  - **不追溯历史**：只影响新录入 / 再次编辑的交易（库里的 fee 列存的是金额）。
     */
    const val MIN_FEE = 5.0

    /** 套用佣金下限：费率 > 0 且不足 [MIN_FEE] → 取 [MIN_FEE]；费率 = 0（免佣）原样返回。 */
    fun applyMinFee(rawFee: Double, rate: Double): Double =
        if (rate > 0.0 && rawFee < MIN_FEE) MIN_FEE else rawFee

    /** 单条字段级错误。field 与表单字段一一对应，UI 据此决定红字落在哪。 */
    data class Issue(val field: String, val message: String)

    fun validate(
        side: String,
        quantityText: String,
        priceText: String,
        dateText: String,
        noteText: String = "",
        feeText: String = "",
        /**
         * 是否允许**小数数量**（M4，2026-09-28）。
         * 场外基金按份额申赎（如 `1234.5678` 份）传 true；A股/ETF/港股/美股传 false（默认）。
         */
        allowFractional: Boolean = false,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()

        if (side != "BUY" && side != "SELL") {
            issues += Issue("side", "方向必须是买入或卖出")
        }

        val qty = quantityText.trim().toDoubleOrNull()
        when {
            quantityText.isBlank() -> issues += Issue("quantity", "请填写数量")
            qty == null -> issues += Issue("quantity", "数量必须是数字")
            // ⚠️ M2 修复（2026-09-28）：NaN / Infinity 必须**显式**拦下 ——
            // `NaN <= 0.0` 与 `Infinity <= 0.0` 都是 **false**，只判 `<= 0` 会直接放行；
            // 而一条 NaN/Inf 经 `sumOf` 会扩散污染总资产 / 曲线 / XIRR，**且删不掉**。
            !qty.isFinite() -> issues += Issue("quantity", "数量必须是有效数字")
            qty <= 0.0 -> issues += Issue("quantity", "数量必须大于 0")
            // ⚠️ M4 修复（2026-09-28）：整数要求**按市场**放宽 ——
            // 场外基金按**份额**申赎（`1234.5678` 份合法），此前无条件要求整数会把基金份额录入拦死；
            // 而回填又会被截成 3 位小数 → 份额被静默改写。仅当 allowFractional=false 时才要求整数。
            !allowFractional && qty != kotlin.math.floor(qty) ->
                issues += Issue("quantity", "A股与ETF按整数股录入")
        }

        val price = priceText.trim().toDoubleOrNull()
        when {
            priceText.isBlank() -> issues += Issue("price", "请填写价格")
            price == null -> issues += Issue("price", "价格必须是数字")
            // ⚠️ M2 修复（2026-09-28）：同数量，NaN/Inf 必须显式拦下
            !price.isFinite() -> issues += Issue("price", "价格必须是有效数字")
            price <= 0.0 -> issues += Issue("price", "价格必须大于 0")
        }

        // 手续费率（老周 2026-09-16 定稿）：
        //  ① 单位 = **万分位**：填 2.5 表示万分之 2.5；② **不可为空**（0 合法=免佣）。
        // 入库口径不变：fee 列存金额 = 价×量×费率/10000（见 feeAmountOf）。
        val fee = feeText.trim().toDoubleOrNull()
        when {
            feeText.isBlank() -> issues += Issue("fee", "请填写手续费率（免佣请填 0）")
            fee == null -> issues += Issue("fee", "手续费率必须是数字（单位：万分之）")
            fee < 0.0 -> issues += Issue("fee", "手续费率不能为负")
        }

        // 日期合法性交给 CivilDate：它已经把 2026-02-30、2026/01/01 这类都拦下来了。
        // 直接用 parseIso 的异常文案，让「格式错误 / 月份越界 / 日期越界」精确区分（V-13/V-14）。
        if (dateText.isBlank()) {
            issues += Issue("date", "请填写交易日期")
        } else {
            runCatching { CivilDate.parseIso(dateText) }
                .onFailure { issues += Issue("date", it.message ?: "日期格式应为 yyyy-MM-dd") }
        }

        // 笔记必填（REQ-NOTE-01，P0）：理由/逻辑/盘面是复盘的根，不填不能保存
        if (noteText.isBlank()) {
            issues += Issue("note", "请写下当时的理由或盘面（复盘的根，不能留空）")
        }

        // 卖出方向额外提醒：超卖不阻断（重放会截断），但要让用户知道
        if (side == "SELL" && qty != null && qty > 0.0) {
            // 这里拿不到当前持仓，由 UI 层把持仓数量传进来再追加一条提示（见 overSellHint）
        }
        return issues
    }

    /**
     * 超卖提示（不阻断，符合 REQ-ACC-09：违反给警示、不拦截保存）。
     * @return null 表示没有超卖问题
     */
    fun overSellHint(side: String, sellQuantity: Double, currentHolding: Double): String? {
        if (side != "SELL") return null
        return if (sellQuantity > currentHolding + 1e-9) {
            "当前仅持有 ${Format.quantity(currentHolding)} 股，卖出数量将按实际持仓截断"
        } else {
            null
        }
    }

    /**
     * 手续费金额（原型 03 口径）：费率（万分位）× 价格 × 数量 / 10000，
     * 再套用**佣金下限**（不足 5 元按 5 元；免佣的 0 不受限，见 [applyMinFee]）。
     * [rateText] 为界面上输入的费率字符串；非法（空/非数字/负数）返回 null。
     * 入库仍存金额（trade.fee 列口径不变），只是输入方式改为费率。
     */
    fun feeAmountOf(priceText: String, quantityText: String, rateText: String): Double? {
        val price = priceText.trim().toDoubleOrNull() ?: return null
        val qty = quantityText.trim().toDoubleOrNull() ?: return null
        val rate = rateText.trim().toDoubleOrNull() ?: return null
        if (rate < 0.0) return null
        // 费率单位＝**万分位**（老周 2026-09-16 定稿）：填 2.5 表示万分之 2.5
        return applyMinFee(price * qty * rate / 10000.0, rate)
    }

    /**
     * 编辑回填：由已存金额反算费率%（保留 4 位小数），供费率输入框显示。
     * 价×量为 0 时返回 "0"。
     */
    /**
     * 由「已存金额 + 价 + 量」反算费率（万分位），用于编辑态回填。
     *
     * ⚠️ **H1 修复（2026-09-28）**：入参解析失败时返回 **`null`**（此前是**静默 `return "0"`**）。
     * 原因：调用方传的是**已格式化**的数量串，一旦带千分位（`"1,000"`）解析必然失败，
     * 返回 `"0"` 会让用户一保存就把该笔**手续费写成 0**（总价、成本价、盈亏全跟着错）。
     * 现在返回 null，由调用方决定是留空让用户填、还是提示。
     */
    fun feeRateOf(priceText: String, quantityText: String, feeAmount: Double): String? {
        val price = priceText.trim().toDoubleOrNull() ?: return null
        val qty = quantityText.trim().toDoubleOrNull() ?: return null
        val base = price * qty
        if (base <= 1e-9) return null
        // 反算到万分位（与输入口径一致）
        val rate = feeAmount / base * 10000.0
        val rounded = kotlin.math.round(rate * 10000.0) / 10000.0
        // 整数费率不带小数点（2.0 -> "2"）
        return if (rounded == kotlin.math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }

    /**
     * 总价（只读联动，原型 03）：买入 = 价×量 + 手续费；卖出 = 价×量 − 手续费。
     * 手续费与 [feeAmountOf] **同源**（含佣金下限：不足 5 元按 5 元），
     * 否则总价会比实际收付少算那部分差额。任一输入非法返回 null（UI 不显示）。
     */
    fun totalAmountOf(
        side: String,
        priceText: String,
        quantityText: String,
        rateText: String,
        /**
         * 印花税金额（原币，REQ-ACC-17）：由 `StampDuty.dutyOf` 算好后传进来，
         * 与手续费一起构成实际收付 —— 买入 = 价×量+费+税、卖出 = 价×量−费−税。
         */
        stampDuty: Double = 0.0,
    ): Double? {
        val price = priceText.trim().toDoubleOrNull() ?: return null
        val qty = quantityText.trim().toDoubleOrNull() ?: return null
        if (price <= 0.0 || qty <= 0.0) return null
        // 费率单位＝万分位（与 feeAmountOf 一致）
        val rate = rateText.trim().toDoubleOrNull() ?: 0.0
        val base = price * qty
        val fee = applyMinFee(base * rate / 10000.0, rate)
        return if (side == "SELL") base - fee - stampDuty else base + fee + stampDuty
    }

    /**
     * 删除预告：对「剔除这一笔」的流水集合做一次重放。
     * 与实际删除走的是同一个 [PositionCalculator.replay]，所以预告必然等于实况。
     */
    fun previewAfterDelete(
        security: Security,
        transactions: List<Transaction>,
        deleteId: String,
        marketPrice: Double?,
        /**
         * 分红送股记录。必须与真实持仓传入**同一份**，否则预告的成本是"纯流水口径"、
         * 页面是"含分红调整口径"，两者对不上（有分红的标的差约 0.5 元/股，
         * 用户会以为删错了）。口径见技术说明书 5.2：现金分红降成本、送股摊薄、配股加成本。
         */
        dividends: List<com.stocknote.core.model.DividendRecord> = emptyList(),
    ): DeletePreview {
        val target = transactions.firstOrNull { it.id == deleteId }
            ?: error("找不到待删除的交易: $deleteId")
        val before = PositionCalculator.replay(security, transactions, marketPrice, dividends)
        val after = PositionCalculator.replay(
            security,
            transactions.filterNot { it.id == deleteId },
            marketPrice,
            dividends,
        )
        return DeletePreview(
            before = before,
            after = after,
            removedQuantity = target.quantity,
            removedPrice = target.price,
        )
    }
}
