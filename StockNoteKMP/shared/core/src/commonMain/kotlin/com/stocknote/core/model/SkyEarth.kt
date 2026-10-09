package com.stocknote.core.model

/**
 * 看天看地（SKY-EARTH）领域模型 —— 需求说明书 FR-SE-01~FR-SE-10 / 技术说明书 §3.1。
 *
 * 本模块记的是**「我此刻怎么看市场」的主观决策快照**：一张关注点清单 + 三档判断 + 历史留档。
 * 与账本域（Transaction / Position / 盈亏 / XIRR）**零耦合**（FR-SE-13 之外不碰任何既有口径）。
 *
 * ⚠️ **未判断 ≠ 中性**（全模块最重要的一条口径）：
 * [SkyFactor.score] == null 表示「还没给过态度」，与 0（中性）语义完全不同，
 * 且**不参与总分合成**（见 `SkyEarthScore.scoreOf` 的「只在已判集合内归一化」）。
 */

/**
 * 分组（FR-SE-01）：天组 = 宏观（上限 10 条）；地组 = 个股（上限 5 条）。
 * 两组各有各的清单、各自的分数、各自的情景图，组与组之间互不影响。
 */
enum class SkyDomain(val raw: String, val label: String, val maxCount: Int) {
    SKY("SKY", "看天", 10),
    EARTH("EARTH", "看地", 5),
    ;

    companion object {
        /** 宽松解析（大小写不敏感）；未知返回 null（不猜）。 */
        fun ofRaw(raw: String): SkyDomain? =
            entries.firstOrNull { it.raw.equals(raw.trim(), ignoreCase = true) }
    }
}

/** 三档判断值（FR-SE-03）：悲观 −1 / 中性 0 / 乐观 +1。UI 上是哭脸 / 平脸 / 笑脸。 */
enum class SkyAttitude(val value: Int, val label: String) {
    BEARISH(-1, "悲观"),
    NEUTRAL(0, "中性"),
    BULLISH(1, "乐观"),
    ;

    companion object {
        /** 由库值还原；null / 越界 → null（= 未判断，不猜）。 */
        fun ofValue(value: Int?): SkyAttitude? = entries.firstOrNull { it.value == value }
    }
}

/**
 * 一条专注点（factor）—— 本模块的唯一实体。
 *
 * 字段口径与 `sky_factor` 表一一对应（技术说明书 §3.1）。
 */
data class SkyFactor(
    val id: String,
    val domain: SkyDomain,
    /** 分组键：天组固定 ""；地组 = 标的代码（如 `hk00700`），换标的 = 换清单（FR-SE-10）。 */
    val scopeKey: String,
    /** 标题，≤ 30 字。 */
    val title: String,
    /** 组内排序键，越小越靠前；**排序即重要程度**（第 1 条最重，见权重口径）。 */
    val sortOrder: Long,
    /** 判断值；**null = 未判断**（≠ 中性），不参与总分。 */
    val score: SkyAttitude? = null,
    /** 依据备注，≤ 200 字（FR-SE-04）。 */
    val note: String? = null,
    /** 最后一次判断时间的 epoch 毫秒；null = 从未判断（也**不算过期**，见 FR-SE-07）。 */
    val judgedAt: Long? = null,
    /** 建条目时间的 epoch 毫秒。 */
    val createdAt: Long,
)

/**
 * 判断历史留档（FR-SE-08）—— 只增不清的事实流，模块长期价值的地基。
 *
 * ⚠️ 同 factor 同自然日只留最后一次（实现手段：`UNIQUE(factor_id, day)` + INSERT OR REPLACE，
 * 别改成普通插入）。
 */
data class SkyJudgement(
    val id: String,
    val factorId: String,
    /** 自然日 `YYYY-MM-DD`（本地时区，来自 `todayIso()`）。 */
    val day: String,
    val score: SkyAttitude,
    /** 判断时间的 epoch 毫秒。 */
    val judgedAt: Long,
)

/**
 * 总分五档（FR-SE-05）。阈值边界归属由 `SkyEarthScore.levelOf` 钉死并单测覆盖。
 *
 * [UNJUDGED] 对应「全组一条都没判断」——UI 显示「—」与灰底问号态，**不是 0 分**。
 */
enum class SkyLevel(val label: String) {
    UNJUDGED("待判断"),
    VERY_BEARISH("很悲观"),
    BEARISH("偏悲观"),
    NEUTRAL("中性"),
    BULLISH("偏乐观"),
    VERY_BULLISH("很乐观"),
}
