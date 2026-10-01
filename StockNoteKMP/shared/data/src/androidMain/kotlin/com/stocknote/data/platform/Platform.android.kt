package com.stocknote.data.platform

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.security.AndroidSecureKeyStore
import com.stocknote.data.security.SecureKeyStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * 应用级 Context 持有者。
 *
 * 共享层不能引用 android.content.Context，所以由 Android 外壳在 Application.onCreate 时注入。
 * 放的是 applicationContext，与进程同生命周期，不存在泄漏。
 */
object AndroidPlatformContext {

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun require(): Context = appContext
        ?: error("AndroidPlatformContext 未初始化：请在 Application.onCreate() 里调用 AndroidPlatformContext.install(this)")
}

/**
 * SQLCipher 加密 SQLite。口令来自 SecureKeyStore，绝不明文落盘。
 *
 * ⚠️ 必须先显式加载本地库。
 * `sqlcipher-android`（新构件）里**没有任何类会替我们调用 System.loadLibrary** ——
 * 已拆开 4.17.0 的 classes.jar 逐类验证：全库 0 处 loadLibrary 调用、也无旧版的
 * `SQLiteDatabase.loadLibs()`。漏掉这一行的症状是首次开库时抛
 *   UnsatisfiedLinkError: No implementation found for ... SQLiteConnection.nativeOpen(...)
 * 原因：类已加载，但含这些 JNI 符号的 libsqlcipher.so 根本没进进程。
 */
actual fun createEncryptedDriver(databaseName: String, passphrase: ByteArray): SqlDriver {
    // loadLibrary 在同一 ClassLoader 内是幂等的，重复调用安全
    System.loadLibrary("sqlcipher")

    return AndroidSqliteDriver(
        schema = StockNoteDb.Schema,
        context = AndroidPlatformContext.require(),
        name = databaseName,
        factory = SupportOpenHelperFactory(passphrase),
    )
}

actual fun createSecureKeyStore(): SecureKeyStore =
    AndroidSecureKeyStore(AndroidPlatformContext.require())

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        // 与技术说明书 8.2 的分级超时约定对齐：行情 5s 连接 / 8s 请求
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 8_000
        socketTimeoutMillis = 8_000
    }
    // ⚠️ H6 修复（2026-09-28）：此前 `expectSuccess = false` 且**没有任何响应校验** ——
    // 403 / 429 / 5xx 以及东财·腾讯常见的「反爬跳转 HTML」都会被当成**正常响应**，
    // 解析失败后一律降级为空列表 / null，用户看到的是「暂无资讯 / 无记录」而不是「查询失败」。
    // 那是比崩溃更危险的"静默错误数据"（例如巨潮同步失败 → 扫雷页恒显示"无诉讼担保"）。
    // 现在只对**明确的错误状态**抛异常（走各调用点既有的 runCatching 降级），
    // **3xx 与其它码仍放行**，以免误伤正常的重定向响应。
    HttpResponseValidator {
        validateResponse { response ->
            val code = response.status.value
            if (code == 403 || code == 429 || code >= 500) {
                throw ResponseException(response, "HTTP $code")
            }
        }
    }
}

actual fun todayIso(): String =
    java.time.LocalDate.now().toString()

actual fun nowEpochMs(): Long = System.currentTimeMillis()

actual fun formatLocalDateTime(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

/**
 * 设备与系统信息。⚠️ 这几个 Build 字段都是静态的，**不需要 Context**，
 * 因此不必把 Android 的 Context 透传进共享层（见本文件顶部说明的取舍）。
 */
actual fun platformInfo(): String =
    "Android " + android.os.Build.VERSION.RELEASE +
        "（SDK " + android.os.Build.VERSION.SDK_INT + "） " +
        android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL

/**
 * Android：走 logcat（tag 固定 `StockNote`，`adb logcat -s StockNote` 即可只看本应用）。
 */
actual fun nativeLog(message: String) {
    android.util.Log.i("StockNote", message)
}
