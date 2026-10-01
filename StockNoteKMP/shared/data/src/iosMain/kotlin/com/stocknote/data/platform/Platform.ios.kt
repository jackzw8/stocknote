// ⚠️ 2026-10-01：本文件的**时间函数改用 POSIX**（`time` / `gettimeofday` / `localtime_r`），
// 不再碰 Foundation 的 `NSDate` / `NSTimeZone` —— 原因见下方 `todayIso` 的注释。
// cinterop 这些 API 在 Kotlin 2.x 需要显式 opt-in。
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.stocknote.data.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.stocknote.core.calc.CivilDate
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.security.IosSecureKeyStore
import com.stocknote.data.security.SecureKeyStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.gettimeofday
import platform.posix.localtime_r
import platform.posix.time
import platform.posix.timeval
import platform.posix.tm

/**
 * iOS 侧平台实现。
 *
 * ⚠️ 本文件在 Windows 上不会被编译（iOS target 由 `stocknote.ios` 开关门控），
 * 首次在 macOS 上构建时应视为「待验证代码」，必须逐条过一遍。
 *
 * M0 遗留项（阻塞 iOS 可交付，不阻塞 Android）：
 *   1. 加密数据库：需在 Podfile 引入 SQLCipher 并在打开连接后调用 sqlite3_key。
 *      当前为 NativeSqliteDriver（**未加密**），仅用于把 iOS 侧链路跑通。
 *   2. 密钥存取：见 IosSecureKeyStore 的说明。
 */
actual fun createEncryptedDriver(databaseName: String, passphrase: ByteArray): SqlDriver {
    // TODO(M1/macOS)：接入 SQLCipher
    //   pod 'SQLCipher'
    //   打开连接后执行 PRAGMA key = '<passphrase>'
    //   或用 SQLDelight 的加密驱动封装替换此处实现
    return NativeSqliteDriver(StockNoteDb.Schema, databaseName)
}

actual fun createSecureKeyStore(): SecureKeyStore = IosSecureKeyStore()

actual fun createHttpClient(): HttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 8_000
        socketTimeoutMillis = 8_000
    }
    // ⚠️ H6 修复（2026-09-28）：见 Android 侧同处注释 —— 非 2xx 会被静默当"无数据"渲染。
    // 这里同样只对 403 / 429 / 5xx 抛异常（3xx 与其它码放行）。
    HttpResponseValidator {
        validateResponse { response ->
            val code = response.status.value
            if (code == 403 || code == 429 || code >= 500) {
                throw ResponseException(response, "HTTP $code")
            }
        }
    }
}

/**
 * 当天日期（**设备本地时区**），`yyyy-MM-dd`。
 *
 * ⚠️⚠️ 2026-10-01 **第三次修**：此前用 Foundation（`NSISO8601DateFormatter` + `NSTimeZone.localTimeZone`），
 * 但 K/N 里 `NSDate.timeIntervalSince1970`、`NSTimeZone.localTimeZone`、
 * `NSDate.dateWithTimeIntervalSince1970` 这些**都是需要 import 的顶层扩展**，不 import 就报
 * `Unresolved reference`（CI 实测 3 轮）。与其去赌那些扩展的导出名，**改用 POSIX**：
 * `time` + `localtime_r` 本身就是「按设备本地时区解析」，比绕 Foundation 更直接、也没有导出名不确定性。
 *
 * ⚠️ 历史坑仍然有效（H5 修复 2026-09-28）：日期串**必须**是 `yyyy-MM-dd` 十位。
 * 曾因 formatter 漏了 `NSISO8601DateFormatWithDashSeparatorInDate` 而输出 `20260928`（8 位），
 * 而全项目按 ISO 串比较/截取（默认交易日期、目标价 endTime、巨潮 edate、扫雷 daysAgo、分红日期比较），
 * 8 位串会让这些比较**静默错乱**（不崩、不报错，只是结果不对），`.take(10)` 也拿不到 10 位。
 * 现在由 [CivilDate.isoOf] 直接产出该格式，不再依赖 formatter 选项。
 */
actual fun todayIso(): String = memScoped {
    val epoch = alloc<LongVar>()
    time(epoch.ptr)
    val ti = alloc<tm>()
    localtime_r(epoch.ptr, ti.ptr)
    CivilDate.isoOf(ti.tm_year + 1900, ti.tm_mon + 1, ti.tm_mday)
}

/**
 * 当前 epoch 毫秒。
 *
 * ⚠️ 刻意用 `gettimeofday` 而不是 `time()`：**必须保住毫秒精度** ——
 * 备份文件名里用 `nowEpochMs() % 100_000` 做区分，秒精度会让它每 100 秒就重复一次。
 */
actual fun nowEpochMs(): Long = memScoped {
    val tv = alloc<timeval>()
    gettimeofday(tv.ptr, null)
    tv.tv_sec * 1000L + tv.tv_usec / 1000
}

/** epoch 毫秒 → 设备本地时区的 `yyyy-MM-dd HH:mm`（`localtime_r` 即本地时区口径）。 */
actual fun formatLocalDateTime(epochMs: Long): String = memScoped {
    val epoch = alloc<LongVar>()
    epoch.value = epochMs / 1000
    val ti = alloc<tm>()
    localtime_r(epoch.ptr, ti.ptr)
    CivilDate.isoOf(ti.tm_year + 1900, ti.tm_mon + 1, ti.tm_mday) + " " +
        ti.tm_hour.toString().padStart(2, '0') + ":" +
        ti.tm_min.toString().padStart(2, '0')
}

/**
 * ⚠️ 刻意只返回 "iOS"：要拿系统版本得碰 `NSProcessInfo.processInfo` / `UIDevice.currentDevice`
 * 这类**类属性**，而 K/N 里它们是需要 `import platform.Foundation.xxx` 的顶层扩展 ——
 * 本文件 2026-10-01 刚在这上面踩过一次坑（详见 todayIso 上方注释）。
 * 日志头部少这一项不影响排查，等 iOS 端真正跑起来再补。
 */
actual fun platformInfo(): String = "iOS"

/**
 * iOS 日志：**双写** —— ① `NSLog` 进系统日志；② 追加到沙盒 `Documents/stocknote.log`。
 *
 * ## ⚠️⚠️ 为什么不能写 `NSLog("%@", message)`（2026-10-01 真机实测，血的教训）
 * 这么写会**当场崩溃**，而且是崩在**自己的日志调用**上 —— 主线程堆栈：
 * ```
 * objc_opt_respondsToSelector
 * __CFSTRING_IS_CALLING_OUT_TO_AN_OBJECT_FORMAT_ARGUMENT_WITH_CONTEXT__
 * __CFStringAppendFormatCore 到 _CFStringCreateWithFormatAndArgumentsAux2
 * _os_log_impl_dynamic 到 _NSLogv 到 NSLog      ← 就是这里
 * ```
 * 原因：**K/N 把 Kotlin `String` 塞进 C vararg 时不会桥接成 `NSString`**，
 * `%@` 拿到的是一个非法对象指针，系统对它 `respondsToSelector` 就 trap（`EXC_BREAKPOINT`）。
 * K/N 调 C 可变参数函数本身是公认的薄弱点（需 C 封装层才可靠）。
 *
 * ⇒ **正确写法**：`NSLog` **只传一个参数**（形参声明类型就是 `String`，K/N 会正确桥接为
 * `NSString`），并把正文里的 `%` 转义成 `%%`（否则被当格式符，乱码或崩）。
 *
 * ## 为什么要落盘
 * 爱思助手的「实时日志」**抓不到第三方 App 的输出**（2026-10-01 实测：抓回的 700 行里
 * 全是 `SpringBoard`/`cameracaptured` 这类系统进程，**一行 StockNote 都没有**）。
 * 所以必须同时写文件，再通过「爱思 → 应用 → 文件」或 iPad 的「文件」App 取出来。
 * 路径用 `getenv("HOME")` 拼（`platform.posix`，零导出名风险），
 * 文件系统 API 也用 POSIX（`fopen`/`fputs`），**不碰 Foundation 的那堆导出名**。
 */
actual fun nativeLog(message: String) {
    // ① 系统日志：单参数调用（不要用 "%@" 加参数的 vararg 形式，见上方注释）
    platform.Foundation.NSLog(message.replace("%", "%%"))

    // ② 落盘：失败也不能让日志本身把 App 弄崩，故整体 runCatching
    runCatching {
        val home = platform.posix.getenv("HOME")?.toKString() ?: return
        val path = "$home/Documents/stocknote.log"
        val file = platform.posix.fopen(path, "a") ?: return
        try {
            platform.posix.fputs(message.replace("\n", " "), file)
            platform.posix.fputs("\n", file)
            platform.posix.fflush(file)
        } finally {
            platform.posix.fclose(file)
        }
    }
}
