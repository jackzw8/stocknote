package com.stocknote.data.platform

import app.cash.sqldelight.db.SqlDriver
import com.stocknote.data.security.SecureKeyStore
import io.ktor.client.HttpClient

/**
 * 平台能力边界 —— 整个跨平台方案里**只有这几个地方需要各写一遍**。
 *
 * 设计取舍：这里用无参 expect 函数 + 平台侧「应用级上下文」注入，
 * 而不是把 Context 一路透传进共享代码。原因：共享层不该知道 Android 的 Context 存在；
 * 而应用级 Context 本来就是进程级单例，与 DI 容器的生命周期一致。
 * iOS 侧没有对应概念，直接忽略即可。
 */
expect fun createEncryptedDriver(databaseName: String, passphrase: ByteArray): SqlDriver

expect fun createSecureKeyStore(): SecureKeyStore

expect fun createHttpClient(): HttpClient

/**
 * 当天日期，ISO-8601（yyyy-MM-dd），用于「记一笔」表单的默认交易日期。
 *
 * 共享层刻意不引 kotlinx-datetime（理由见技术说明书 3.5：领域层只需要「差多少天」），
 * 但「今天是几号」必须问操作系统 —— 这是第 5 个、也是目前最后一个平台隔离点。
 */
expect fun todayIso(): String

/**
 * 当前时间的 epoch 毫秒，用于行情快照的「更新于」时间戳。
 * 共享层没有时钟 API，这是第 6 个平台隔离点。
 */
expect fun nowEpochMs(): Long

/**
 * epoch 毫秒 → 设备本地时区的「yyyy-MM-dd HH:mm」。
 * 修复「更新于」显示 UTC 的问题（共享层无法取本地时区），第 8 个平台隔离点。
 *
 * ⚠️ **格式契约（三端必须一致）**（L-1，审核报告 2026-10-09）：
 * 前 10 位固定是 `yyyy-MM-dd`、第 11 位是空格 —— 「看天看地」的「更新于 2026-10-09」
 * 靠 `take(10)` 取日期（`SkyEarthHolder.skyUpdatedDate` / `earthUpdatedDate`）。
 * **改格式串前先 grep 调用方**，否则 `take(10)` 会静默截出错日期。
 */
expect fun formatLocalDateTime(epochMs: Long): String

/**
 * 平台与系统信息（用于导出的运行日志头部）—— 老周 2026-10-01。
 *
 * 为什么需要：排查「行情拉不到」这类问题时，**设备型号与系统版本**往往就是关键线索
 * （老系统缺 TLS 支持、模拟器/虚拟机网络受限、厂商 ROM 限制后台联网等）。
 * 导出日志时一并带上，省一轮来回。
 */
expect fun platformInfo(): String

/**
 * 把一行文本写进**系统日志**（Android logcat / iOS 系统日志 / JVM 控制台）—— 老周 2026-10-01。
 *
 * ⚠️⚠️ **为什么非要专门开一个 expect**：`println` 写的是 **stdout**，而
 * **iOS 上 App 独立运行时 stdout 是被丢弃的**（只有接着 Xcode 调试才会转发进系统日志）。
 * 后果：2026-10-01 排查真机闪退时，老周抓回来的 12KB 设备日志里
 * **一行 StockNote 都没有** —— 我们的运行日志和 K/N 崩溃前那句
 * `Uncaught Kotlin exception` 全是 stdout，压根没进 syslog，
 * 等于「带了一套日志机制，在 iOS 上完全失灵」。
 *
 * 所以 iOS 侧必须走 **`NSLog()`**（它写的是系统日志，爱思「实时日志」/ Console.app 可见）。
 * 各平台：Android → `Log.i`；iOS → `NSLog`；JVM/桌面 → `println`（控制台本来就在看）。
 */
expect fun nativeLog(message: String)
