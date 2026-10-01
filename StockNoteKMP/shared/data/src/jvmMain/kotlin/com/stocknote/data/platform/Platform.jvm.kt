package com.stocknote.data.platform

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.security.SecureKeyStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import java.io.File
import java.util.Base64
import java.util.Properties

/**
 * JVM 侧平台实现 —— 两种运行形态（老周 2026-09-30 扩展出第二种）：
 *
 *  1. **测试**（默认）：`stocknote.db.path` 未设置 → 内存库 + 内存 KeyStore。
 *     每个用例一个全新空库、schema 自建，要的是**确定性**。
 *  2. **桌面版**（Windows/macOS 应用）：`:desktopApp` 外壳在启动时设置
 *     [DB_PATH_PROPERTY] / [KEY_PATH_PROPERTY] → 文件库 + 文件 KeyStore，数据落在用户目录。
 *
 * ⚠️ **桌面驱动不加密**（本轮如实记录）：SQLCipher 官方只有 Android 构件
 * （`net.zetetic:sqlcipher-android` 是 AAR），桌面 JVM 没有配套 JDBC 实现；硬上要
 * 自引原生库 + 自己写 JDBC 封装，风险大于收益。所以桌面是**明文 SQLite 文件** ——
 * 这与手机端「句口令绝不明文落盘」的口径**不同**，属于已知差距，不是疏漏。
 * 将来要加密，只需替换本函数（其余各层不用动）。
 *
 * 时间相关三个函数与 Android 保持一致（都用 `java.time`）。
 */

/** 数据库文件路径（桌面壳设置）。未设置 = 测试模式（内存库）。 */
const val DB_PATH_PROPERTY = "stocknote.db.path"

/** 密钥/KV 文件路径（桌面壳设置）。未设置 = 测试模式（内存 KeyStore）。 */
const val KEY_PATH_PROPERTY = "stocknote.key.path"

actual fun createEncryptedDriver(databaseName: String, passphrase: ByteArray): SqlDriver {
    val path = System.getProperty(DB_PATH_PROPERTY)
    if (path.isNullOrBlank()) {
        // ⚠️ 测试分支：passphrase 被刻意忽略（不加密），且每次调用都是**全新的空库** ——
        // schema 由测试自行 `StockNoteDb.Schema.create(driver)` 建。
        return JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    }

    val file = File(path)
    file.parentFile?.mkdirs()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
    // 首次启动建表；已有库要跳过 —— Schema.create 重复执行会因表已存在而抛错
    if (isFreshDatabase(driver)) StockNoteDb.Schema.create(driver)
    return driver
}

/** `trade` 表在不在 —— 判断「全新库」还是「已有库」的唯一依据。 */
private fun isFreshDatabase(driver: SqlDriver): Boolean =
    driver.executeQuery(
        identifier = null,
        sql = "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='trade'",
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
        parameters = 0,
    ).value == 0L

/**
 * 桌面 KeyStore：口令与 KV 落在用户目录下的一个 properties 文件里。
 *
 * ⚠️ **与 Android 的差距必须说清楚**：Android 把口令交给 TEE/StrongBox 保护的 Keystore；
 * 桌面这里只是「随机生成 32 字节 → 写进用户目录文件」，**没有任何硬件保护**
 * （Windows 可上 DPAPI，但那要引 JNA，本轮没做）。
 * 又因为桌面库当前是明文，这份口令实际不构成防线 —— **别把桌面版当成已加密**。
 */
actual fun createSecureKeyStore(): SecureKeyStore {
    val path = System.getProperty(KEY_PATH_PROPERTY)
    if (path.isNullOrBlank()) return InMemorySecureKeyStore()
    return FileSecureKeyStore(File(path))
}

/** 文件 KeyStore（桌面）：口令 + KV 持久化，进程重启后仍在。 */
internal class FileSecureKeyStore(private val file: File) : SecureKeyStore {

    private val props = Properties().apply {
        if (file.exists()) runCatching { file.inputStream().use { load(it) } }
    }

    @Synchronized
    override fun getOrCreateDatabasePassphrase(): ByteArray {
        val existing = props.getProperty(SecureKeyStore.DATABASE_PASSPHRASE_KEY)
        if (existing != null) {
            runCatching { Base64.getDecoder().decode(existing) }.getOrNull()?.let { return it }
        }
        val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        props.setProperty(
            SecureKeyStore.DATABASE_PASSPHRASE_KEY,
            Base64.getEncoder().encodeToString(fresh),
        )
        persist()
        return fresh
    }

    @Synchronized
    override fun putString(key: String, value: String) {
        props.setProperty(key, value)
        persist()
    }

    @Synchronized
    override fun getString(key: String): String? = props.getProperty(key)

    @Synchronized
    override fun remove(key: String) {
        props.remove(key)
        persist()
    }

    /** 写盘失败不抛（磁盘满 / 权限问题不该让整个应用挂掉），下次操作会再试。 */
    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, "StockNote desktop keystore") }
        }
    }
}

/**
 * 内存 KeyStore：口令固定、KV 存在内存 Map 里。
 * 进程结束即消失，不落盘 —— 测试要的是**确定性**而非安全性。
 */
internal class InMemorySecureKeyStore : SecureKeyStore {
    private val map = mutableMapOf<String, String>()
    private val passphrase = ByteArray(32) { (it + 1).toByte() }

    override fun getOrCreateDatabasePassphrase(): ByteArray = passphrase.copyOf()

    override fun putString(key: String, value: String) {
        map[key] = value
    }

    override fun getString(key: String): String? = map[key]

    override fun remove(key: String) {
        map.remove(key)
    }
}

/**
 * 与 Android 保持同一套配置（超时 5s/8s + 只对 403/429/5xx 抛异常），
 * 这样测试里若用到网络路径，行为与真机一致。
 */
actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 8_000
        socketTimeoutMillis = 8_000
    }
    HttpResponseValidator {
        validateResponse { response ->
            val code = response.status.value
            if (code == 403 || code == 429 || code >= 500) {
                throw ResponseException(response, "HTTP $code")
            }
        }
    }
}

actual fun todayIso(): String = java.time.LocalDate.now().toString()

actual fun nowEpochMs(): Long = System.currentTimeMillis()

actual fun formatLocalDateTime(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

/** 桌面端（JVM）：系统名 / 版本 / 架构 —— 排查桌面版网络与路径问题时有用。 */
actual fun platformInfo(): String =
    System.getProperty("os.name").orEmpty() + " " +
        System.getProperty("os.version").orEmpty() + "（" +
        System.getProperty("os.arch").orEmpty() + "）"

/** 桌面端：直接进控制台（开发时本来就在看，不需要额外的日志框架）。 */
actual fun nativeLog(message: String) {
    println(message)
}
