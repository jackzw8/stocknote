package com.stocknote.data.repo

import com.stocknote.data.db.StockNoteDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **情绪标签域 Repository**（阶段 2：实现已搬迁，2026-09-29，第 9 批）。
 *
 * 这是拆分里**最干净的一块**：只读写 `app_setting` 表的一条 key（`custom_emotions`），
 * 与账本（交易/持仓/现金）毫无关系，也不依赖任何其它 Repository。
 *
 * 存储形态：**CSV 字符串**（`"恐惧,贪婪"`），保序 —— 因为 UI 是列表，顺序即用户看到的顺序。
 *
 * ⚠️ 预设 5 个（[presetEmotions]）不落库，是常量；库里只存用户**自定义**的那部分。
 * `allEmotions` = 预设 + 自定义去重。
 *
 * 约束（与 UI 提示一致）：单个名称 **≤ 6 字**、自定义最多 **10 个**、与预设重名则忽略。
 */
class EmotionRepository internal constructor(
    private val db: StockNoteDb,
) {
    /** 预设情绪（固定 5 个，供 UI 与校验共用）。 */
    val presetEmotions: List<String> get() = PRESET_EMOTIONS

    /** 用户自定义情绪：存 app_setting（key=`custom_emotions`，CSV 保序）。 */
    suspend fun customEmotions(): List<String> = withContext(Dispatchers.Default) {
        db.settingQueries.selectByKey(EMOTION_KEY).executeAsOneOrNull()
            ?.setting_value
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    /** 全部可选情绪 = 预设 5 个 + 自定义（去重保序）。 */
    suspend fun allEmotions(): List<String> = withContext(Dispatchers.Default) {
        (PRESET_EMOTIONS + customEmotions()).distinct()
    }

    /** 追加自定义情绪（重复忽略）；限长 6 字、最多 10 个。 */
    suspend fun addCustomEmotion(name: String): List<String> = withContext(Dispatchers.Default) {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "情绪名称不能为空" }
        require(trimmed.length <= 6) { "情绪名称最多 6 个字" }
        val current = customEmotions().toMutableList()
        if (trimmed !in PRESET_EMOTIONS && trimmed !in current) {
            require(current.size < 10) { "自定义情绪最多 10 个" }
            current += trimmed
        }
        db.settingQueries.upsert(EMOTION_KEY, current.joinToString(","))
        current
    }

    /** 删除一个自定义情绪，返回剩余列表。 */
    suspend fun removeCustomEmotion(name: String): List<String> = withContext(Dispatchers.Default) {
        val current = customEmotions().filterNot { it == name }
        db.settingQueries.upsert(EMOTION_KEY, current.joinToString(","))
        current
    }

    private companion object {
        const val EMOTION_KEY = "custom_emotions"

        val PRESET_EMOTIONS = listOf("恐惧", "贪婪", "冷静", "犹豫", "自信")
    }
}
