package com.stocknote.data.log

import com.stocknote.data.platform.formatLocalDateTime
import com.stocknote.data.platform.nativeLog
import com.stocknote.data.platform.nowEpochMs
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * 运行日志（老周 2026-10-01 要求：出问题时能导出日志提交排查）。
 *
 * ## 为什么需要
 * 本 App 的疑难问题几乎都出在**网络取数**上（行情拉不到、接口改版、某只标的解析失败、
 * 备份/恢复失败），而此前这些失败要么被 `runCatching {}` 悄悄吞掉，要么散落成
 * `println("[SN_FIN] ...")` 只进 logcat —— **用户（和老周）拿不到**，
 * 只能靠「截图 + 猜」。这里把日志收进内存环形缓冲，再由「设置 → 数据管理」导出成文件。
 *
 * ## 设计要点
 * 1. **不会无限涨**：固定容量 [CAPACITY] 的数组 + 自增游标轮转，最多只占这么多条。
 * 2. **并发安全且无锁**：每个写入者从 [seq] 取到**唯一序号**、因而写**不同槽位**，
 *    既不用锁也不用复制列表（`AtomicReference<List>` 那种写法每次都要复制整个列表，太贵）。
 * 3. **时间戳到毫秒**：`formatLocalDateTime` 只到分钟，这里补上秒与毫秒
 *    （时区偏移都是整秒/整分，所以取模得到的秒、毫秒是准确的）。
 * 4. **不引第三方日志库**：Napier / Timber 都会拖依赖，而且它们也不负责「导出给用户看」这件事。
 *
 * ⚠️ 本类刻意放在 `data` 模块（而不是 `core`）：它要用 `nowEpochMs()` / `formatLocalDateTime()`，
 * 而这两个平台能力就在 `data` 里 —— 放 core 会造成依赖方向颠倒。
 */
@OptIn(ExperimentalAtomicApi::class)
object SnLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    class Entry(
        val epochMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val detail: String?,
    )

    /** 保留的最大条数（超出后覆盖最旧的）。2000 条足够覆盖「最近一次出问题」的现场。 */
    private const val CAPACITY = 2000

    private val slots = arrayOfNulls<Entry>(CAPACITY)

    /** 累计写入条数（从 1 开始）：既当序号，也用来算该写哪个槽。 */
    private val seq = AtomicLong(0)

    fun d(tag: String, message: String) = write(Level.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = write(Level.INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = write(Level.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = write(Level.ERROR, tag, message, error)

    /** 记录一次异常（语义比 `e()` 更直白，排查时按这个搜）。 */
    fun throwable(tag: String, message: String, error: Throwable) = write(Level.ERROR, tag, message, error)

    private fun write(level: Level, tag: String, message: String, error: Throwable?) {
        val n = seq.addAndFetch(1L)
        val detail = error?.let { runCatching { it.stackTraceToString() }.getOrNull() }
        // 每个写入者拿到的 n 互不相同 → 写的是不同槽位，无需加锁
        slots[((n - 1) % CAPACITY).toInt()] = Entry(nowEpochMs(), level, tag, message, detail)
        // 同时打一份到**系统日志**：Android 进 logcat、桌面进控制台、**iOS 进 syslog**。
        // ⚠️ 2026-10-01 修正：这里原来用 `println`（stdout）—— 在 iOS 上独立运行时
        //   stdout 会被丢弃，导致真机出问题时**一条日志都抓不到**（设备日志里一行 StockNote 都没有）。
        //   改用平台日志（iOS 侧是 `NSLog`）后才真正可见。堆栈不打，避免刷屏。
        nativeLog("SN/" + level.name + " [" + tag + "] " + message)
    }

    /** 当前缓冲里的条数。 */
    fun size(): Int {
        val total = seq.load()
        return if (total > CAPACITY) CAPACITY else total.toInt()
    }

    fun clear() {
        for (i in slots.indices) slots[i] = null
        seq.store(0)
    }

    /**
     * 导出为纯文本，**最新在最后**（顺着时间线读）。
     *
     * @param header 调用方补充的环境信息（App 版本、平台等）—— 由 UI 层传，
     *   避免 `data` 层反过来依赖外壳。
     */
    fun dump(header: String = ""): String = buildString {
        appendLine("# StockNote 运行日志")
        appendLine("# 导出时间：${stamp(nowEpochMs())}")
        if (header.isNotBlank()) header.trim().lineSequence().forEach { appendLine("# $it") }
        appendLine("# 共 ${size()} 条（上限 $CAPACITY 条，超出后丢弃最旧的）")
        appendLine()
        val total = seq.load()
        val from = if (total > CAPACITY) total - CAPACITY else 0L
        for (n in from until total) {
            val e = slots[(n % CAPACITY).toInt()] ?: continue
            append(stamp(e.epochMs)).append("  ")
            append(e.level.name.padEnd(5)).append(" [").append(e.tag).append("] ")
            appendLine(e.message)
            // 异常堆栈逐行缩进，便于从一堆日志里一眼看出边界
            e.detail?.lineSequence()?.forEach { appendLine("        " + it.trimEnd()) }
        }
    }

    /** `yyyy-MM-dd HH:mm:ss.SSS`（在 `formatLocalDateTime` 的分钟精度上补秒与毫秒）。 */
    private fun stamp(epochMs: Long): String {
        val sec = ((epochMs / 1000) % 60).toString().padStart(2, '0')
        val milli = (epochMs % 1000).toString().padStart(3, '0')
        return formatLocalDateTime(epochMs) + ":" + sec + "." + milli
    }
}
