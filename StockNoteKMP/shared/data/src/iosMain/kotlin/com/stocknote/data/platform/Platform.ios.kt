// ⚠️ 2026-10-01：本文件的**时间函数改用 POSIX**（`time` / `gettimeofday` / `localtime_r`），
// 不再碰 Foundation 的 `NSDate` / `NSTimeZone` —— 原因见下方 `todayIso` 的注释。
// cinterop 这些 API 在 Kotlin 2.x 需要显式 opt-in。
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.stocknote.data.platform

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.stocknote.core.calc.CivilDate
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.log.SnLog
import com.stocknote.data.security.IosSecureKeyStore
import com.stocknote.data.security.SecureKeyStore
import com.stocknote.data.security.toHexString
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
 * ✅ 2026-10-02（P0-1）：**数据库已接 SQLCipher**，不再是明文库。三件事配套：
 *   1. **加密**：`onConfiguration` 里塞 `DatabaseConfiguration.Encryption(key = 口令十六进制)`。
 *      SQLDelight 2.3.2 的 `NativeSqliteDriver` 提供
 *      `onConfiguration: (DatabaseConfiguration) -> DatabaseConfiguration`；而它依赖的
 *      `co.touchlab:sqliter-driver:1.3.3` 会拿这个 key 执行 `PRAGMA key = '<key>'`
 *      （native 侧源码 `DatabaseConnection.setCipherKey`，走 SQL 语句而非 `sqlite3_key()`）。
 *   2. **链接**：SQLCipher 由 Xcode 侧的 SPM 包提供（见 `iosApp/project.yml` 的 `packages:`），
 *      同时**删掉了原来的 `-lsqlite3`** —— sqliter 的 `sqlite3_*` 符号必须由 SQLCipher 提供。
 *      若两个提供者同时在场，哪个胜出取决于逐符号的链接顺序 → 可能**静默**写成明文库；
 *      宁可只留一个提供者，缺符号时让链接直接失败（CI 会红）。
 *   3. **自检**：开库后立刻查 `PRAGMA cipher_version` 并写进运行日志。万一没接上，
 *      「设置 → 数据管理 → 导出运行日志」里会有一条 ERROR，而不是用户以为"加密了"。
 *
 * ⚠️ 为什么口令先转十六进制：sqliter 拼 SQL 时**只把 `'` 转义成 `''`**（`escapeSql()`），
 * 反斜杠 / 双引号 / `x'...'` 这类形式都没处理，留着会让 `PRAGMA key` 的语义漂移。
 * 纯 `[0-9a-f]` 彻底绕开这个问题。
 *
 * 密钥存取见 IosSecureKeyStore（已改 Keychain）。
 */
actual fun createEncryptedDriver(databaseName: String, passphrase: ByteArray): SqlDriver {
    // ① 升级兜底：老版本 iOS 装的是**明文库**，拿口令去开它会报 "file is not a database"。
    //    先把明文库改名留档（旧数据不删），再建加密库。
    IosStartupTrace.stage("② 打开加密库（SQLCipher）…\n" + legacyDatabaseReport(databaseName))
    quarantineLegacyPlaintextDatabase(databaseName)

    // ② 加密打开
    val driver = NativeSqliteDriver(
        schema = StockNoteDb.Schema,
        name = databaseName,
        onConfiguration = { config ->
            config.copy(
                encryptionConfig = DatabaseConfiguration.Encryption(key = passphrase.toHexString()),
            )
        },
    )

    // ③ 自检：把「到底有没有在用 SQLCipher」写进运行日志，并回显到启动阶段页
    val cipherVersion = reportCipherVersion(driver)
    IosStartupTrace.stage("② 加密库已打开\ncipher_version = " + (cipherVersion ?: "（空 —— 未生效！）"))
    return driver
}

/**
 * 旧**明文**库兜底（P0-1，2026-10-02）。
 *
 * 背景：接入 SQLCipher 之前，iOS 上落的是**未加密**的 `stocknote.db`。加密驱动拿口令去开它，
 * 第一条语句就会失败（`file is not a database`），表现为**启动即崩**。
 *
 * 处理：只在「文件存在 **且** 头 16 字节是明文 SQLite 魔数」时把它改名成
 * `<名字>.plaintext.bak`（**不删**，用户数据仍可取回），随后照常新建加密库。
 *
 * ⚠️ 路径**扫多个候选**而不是写死一条：首选仍是 sqliter 源码的口径
 * （`NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, …)` + `databases` 子目录，
 * 见 sqliter `appleMain/DatabaseFileContext.kt`），但**万一推断错了**，明文库就不会被改名 →
 * 加密驱动拿口令去开它 → `file is not a database` → 启动即失败。
 * 而老周 2026-10-03 真机报的「白屏一会儿就退出」里，这一条正是首要嫌疑；
 * 多扫两个目录的代价是两次 `fopen`，换来的是"路径推断错也能自愈"。
 *
 * 用 POSIX 读文件头（`fopen`/`fgetc`/`rename`）而不是 Foundation：本文件在 Foundation 的
 * 导出名上踩过坑（见下方 `todayIso` 注释），而且 `getenv("HOME")` 这条路 `nativeLog` 已经在用。
 */
private fun quarantineLegacyPlaintextDatabase(databaseName: String) {
    val home = platform.posix.getenv("HOME")?.toKString() ?: return
    for (path in legacyDatabaseCandidates(home, databaseName)) {
        // 文件不存在（全新安装，或库里已经是密文）→ 跳过这个候选
        val file = platform.posix.fopen(path, "rb") ?: continue

        val looksLikePlaintextSqlite = try {
            var matched = true
            for (i in PLAINTEXT_SQLITE_HEADER.indices) {
                val c = platform.posix.fgetc(file)
                if (c < 0 || c != PLAINTEXT_SQLITE_HEADER[i].code) {
                    matched = false
                    break
                }
            }
            matched
        } finally {
            platform.posix.fclose(file)
        }
        // ⚠️ 不是明文库（比如已是密文，或口令对不上）→ **一律不动**，让加密驱动如实报错。
        //    自造"打不开就改名重建"会把"口令丢了"变成静默的数据消失 —— 那正是 P0-2 要防的事。
        if (!looksLikePlaintextSqlite) continue

        val backupPath = "$path.plaintext.bak"
        if (platform.posix.rename(path, backupPath) == 0) {
            SnLog.w(
                DB_LOG_TAG,
                "检测到未加密的旧数据库：$path 已改名留档为 *.plaintext.bak（数据未删除），" +
                    "本机将新建加密库；如需旧数据请用 DB Browser + SQLCipher 打开该文件。",
            )
            IosStartupTrace.stage(
                "② 发现旧明文库，已改名留档（数据未删）：${path.removePrefix("$home/")}",
            )
        } else {
            SnLog.e(DB_LOG_TAG, "旧明文数据库改名失败，加密库将无法打开。请手工删除或改名：$path")
            IosStartupTrace.stage("② 旧明文库改名失败：${path.removePrefix("$home/")}")
        }
    }
}

/**
 * 老版本明文库的**候选位置**（2026-10-03 加固，理由见 [quarantineLegacyPlaintextDatabase]）。
 *
 * 第一个是 sqliter 源码口径；后两个是"万一"——真机上一旦推断错，加密库就打不开了。
 */
private val LEGACY_DB_RELATIVE_DIRS = listOf(
    "Library/Application Support/databases",
    "Library/Application Support",
    "Documents",
)

private fun legacyDatabaseCandidates(home: String, databaseName: String): List<String> =
    LEGACY_DB_RELATIVE_DIRS.map { "$home/$it/$databaseName" }

/**
 * 给启动阶段页用：把候选库文件与"是否存在"列出来。
 *
 * 这一行信息在真机上很关键 —— 路径一旦推断错，截图就能直接告诉我们 sqliter 真正用的位置。
 */
private fun legacyDatabaseReport(databaseName: String): String {
    val home = platform.posix.getenv("HOME")?.toKString() ?: return "(取不到 HOME)"
    return legacyDatabaseCandidates(home, databaseName).joinToString("\n") { path ->
        val exists = platform.posix.fopen(path, "rb")?.let {
            platform.posix.fclose(it)
            true
        } ?: false
        (if (exists) "【存在】 " else "（无）　") + path.removePrefix("$home/")
    }
}

/**
 * 开库后立刻确认「真的在用 SQLCipher」。
 *
 * 判据用 `PRAGMA cipher_version`（SQLCipher 专有）：
 *  - 走 SQLCipher → 返回版本号（如 `4.17.0 community`）；
 *  - 走明文 SQLite → 该 pragma 不存在，**零行**，且没有任何报错（这正是最危险的情况）。
 *
 * 因此这里把「空」当**错误**记进运行日志：用户导出的日志里有这条，就能立刻定位
 * 「以为加密了、其实在写明文」。
 */
private fun reportCipherVersion(driver: SqlDriver): String? {
    val version = runCatching {
        driver.executeQuery(
            null,
            "PRAGMA cipher_version;",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            0,
        ).value
    }.getOrNull()

    if (version.isNullOrBlank()) {
        SnLog.e(
            DB_LOG_TAG,
            "SQLCipher 未生效：PRAGMA cipher_version 为空 —— 数据库会以**明文**写入，请检查 iOS 侧链接。",
        )
    } else {
        SnLog.i(DB_LOG_TAG, "SQLCipher 已生效：cipher_version=$version")
    }
    return version
}

/** 明文 SQLite 文件头（16 字节，含结尾的 `\0`）。 */
private const val PLAINTEXT_SQLITE_HEADER = "SQLite format 3\u0000"

private const val DB_LOG_TAG = "DB"

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
 * iOS 日志：**双写** —— ① `NSLog` 进系统日志；② 追加到沙盒 `Library/Caches/stocknote.log`
 *（⚠️ P1-22，2026-10-02：原来在 `Documents/`，会进整机备份，已挪走并加轮转）。
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
 * 路径用 `getenv("HOME")` 拼（`platform.posix`，零导出名风险），
 * 文件系统 API 也用 POSIX（`fopen`/`fputs`/`rename`），**不碰 Foundation 的那堆导出名**。
 *
 * ⚠️ 真机上**取日志的可用路径只有 App 内「设置 → 数据管理 → 导出运行日志」**
 *（那条路走内存里的 [SnLog]，**不读这个文件**）。「文件」App / 爱思 那条路实测取不出来
 *（自签会丢掉 Info.plist 的 `UIFileSharingEnabled`，见 MainViewController.kt 的注释）；
 * 所以这个文件基本上是**给崩溃排查留的兜底**，加上轮转与不进备份即可。
 *
 * ## P1-22（2026-10-02）改了什么
 *  1. **位置**：`Documents/` → **`Library/Caches/`**。`Documents` 默认进 iCloud/iTunes
 *     **整机备份**（每次备份都要传这个可涨到几十 MB 的文件），且 `UIFileSharingEnabled` 一旦生效，
 *     用户「文件」App 里就会多一个来历不明的文件；`Caches` **不参与备份**，被系统回收也无所谓。
 *  2. **轮转**：超过 [LOG_MAX_BYTES] 就整轮改名成 `.1`（见 [rotateLogIfTooLarge]），占用封顶约 2 MB。
 *  3. **迁移**：老版本留在 `Documents/` 的那个文件会被挪进 `Caches`（见 [migrateLegacyLogFile]），
 *     否则"文件不再位于 Documents"这条验收对老装机不成立、备份里也仍然带着它。
 *
 * ⚠️ 报告里还提过「按级别过滤（默认保留 i/w/e）」—— **本次没做**：级别只存在于
 * [SnLog] 传进来的那段文本里，要过滤就得改 `SnLog` 的公共接口（commonMain），
 * 属于另一件事；建议并进 P2-6（把所有 `println` / `nativeLog` 收拢进 `SnLog`）时一起做。
 */
actual fun nativeLog(message: String) {
    // ① 系统日志：单参数调用（不要用 "%@" 加参数的 vararg 形式，见上方注释）
    platform.Foundation.NSLog(message.replace("%", "%%"))

    // ② 落盘：失败也不能让日志本身把 App 弄崩，故整体 runCatching
    runCatching {
        val home = platform.posix.getenv("HOME")?.toKString() ?: return
        migrateLegacyLogFile(home)

        val path = "$home/$LOG_FILE_RELATIVE_PATH"
        rotateLogIfTooLarge(path)

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

/** 日志文件相对沙盒的位置（P1-22：`Library/Caches/` 不参与备份）。 */
private const val LOG_FILE_RELATIVE_PATH = "Library/Caches/stocknote.log"

/** 单个日志文件的上限（P1-22）。 */
private const val LOG_MAX_BYTES = 1L * 1024 * 1024

/**
 * `SEEK_END` 的字面值（POSIX 固定：`SEEK_SET`=0 / `SEEK_CUR`=1 / `SEEK_END`=2）。
 *
 * ⚠️ 为什么不用 `platform.posix.SEEK_END`：本文件在 Windows 上编不了，**每多一个名字就多一轮 CI**；
 * cinterop 对宏常量的导出并不总是齐，而这个值是 POSIX 写死的。同文件其它 POSIX 调用
 *（`fopen`/`fputs`/`fflush`/`fgetc`/`rename`）都是**已在 CI 上验证过**的。
 */
private const val POSIX_SEEK_END = 2

/**
 * 日志轮转（P1-22）：超过上限就把当前文件改名成 `<文件>.1`，下次写入从空文件开始。
 *
 * ## 为什么是「整轮改名」而不是报告里写的「保留尾部 256 KB」
 *  1. 效果更好：`.1` 是**上一整轮**的完整日志（排查时往往正需要"上次出事的上下文"），
 *     而不是从中间截断的半截；
 *  2. 只用 `fopen`/`fseek`/`ftell`/`rename` —— 截断尾部还要 `fread`/`fwrite` + pinned 内存，
 *     在这个只能靠 CI 验证的文件里，每多一个 API 就多一份风险；
 *  3. 占用依然有界：当前文件 ≤ 1 MB、`.1` ≤ 1 MB（`rename` 覆盖上一次的 `.1`）→ 封顶约 2 MB。
 *
 * ⚠️ 取大小用 `fseek(END)`+`ftell` 而不是 `stat`：`stat` 在 K/N 要用 `memScoped` 分配结构体，
 *    而这三个调用都是 stdio 的常规成员（`fopen`/`fputs` 已在本文件验证过）。
 * 失败一律静默返回 —— 轮转失败最多是文件大一点，绝不能让日志把 App 弄崩。
 */
private fun rotateLogIfTooLarge(path: String) {
    val size = platform.posix.fopen(path, "rb")?.let { file ->
        try {
            if (platform.posix.fseek(file, 0, POSIX_SEEK_END) != 0) return@let null
            platform.posix.ftell(file)
        } finally {
            platform.posix.fclose(file)
        }
    } ?: return
    if (size <= LOG_MAX_BYTES) return
    platform.posix.rename(path, "$path.1")
}

/**
 * 一次性迁移（P1-22）：把老版本留在 `Documents/` 的日志挪进 `Caches`。
 *
 * - 用 `.legacy` 后缀另存，**不删**（保留现场）；
 * - 每次 `nativeLog` 都试一次，成功后就再也不会命中（`fopen` 一个不存在的路径等于一次廉价系统调用）；
 * - 并存标志位只为省这点开销，**即使并发下重复执行也无害**（`rename` 幂等）。
 */
private var legacyLogMigrated = false

private fun migrateLegacyLogFile(home: String) {
    if (legacyLogMigrated) return
    legacyLogMigrated = true
    val legacyPath = "$home/Documents/stocknote.log"
    val probe = platform.posix.fopen(legacyPath, "rb") ?: return
    platform.posix.fclose(probe)
    platform.posix.rename(legacyPath, "$home/Library/Caches/stocknote.log.legacy")
}
