package com.stocknote.data.util

import kotlin.random.Random

/**
 * 本地主键生成。
 *
 * 共享层没有 java.util.UUID，也不该引入 UUID 依赖（iOS 侧要另写一套）。
 * 本 App 是**单机单用户**、无云端同步诉求，因此「随机 + 进程内自增」已足够避免碰撞，
 * 且 id 可读、便于排查。
 *
 * ⚠️ **M12 修复（2026-09-28）**：计数器改用 **`AtomicLong`**。此前是普通 `var counter` +
 * `counter += 1`（读-改-写三步，无锁无 `@Volatile`）—— 多线程并发取号可能读到同一个值
 * （CSV 导入、批量写、扫雷同步都可能与 UI 写入并发）→ 两条记录**拿到同一个 id** →
 * `upsert` 静默覆盖 → **丢一笔交易**。随机段虽能降低概率，但这类"丢数据"不该靠碰运气。
 */
@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
object Ids {
    // ⚠️ 必须用 Kotlin 的**跨平台**原子类型（`kotlin.concurrent.atomics.AtomicLong`），
    // **不能**用 `java.util.concurrent.atomic.AtomicLong` —— 后者在 commonMain 里 iOS 侧编不过。
    // 该 API 目前仍带 `@ExperimentalAtomicApi`，故在 object 上做 opt-in。
    private val counter = kotlin.concurrent.atomics.AtomicLong(0)

    fun next(prefix: String): String {
        val seq = counter.addAndFetch(1L)
        val rand = Random.nextLong().toULong().toString(16).padStart(16, '0')
        return "$prefix-$rand-${seq.toString(36)}"
    }
}
