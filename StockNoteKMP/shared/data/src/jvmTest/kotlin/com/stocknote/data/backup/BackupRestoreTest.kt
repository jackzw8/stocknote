package com.stocknote.data.backup

import app.cash.sqldelight.db.SqlDriver
import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.TradeSide
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.net.QuoteClient
import com.stocknote.data.platform.createEncryptedDriver
import com.stocknote.data.platform.createHttpClient
import com.stocknote.data.platform.createSecureKeyStore
import com.stocknote.data.repo.BackupRepository
import com.stocknote.data.repo.CashRepository
import com.stocknote.data.repo.PortfolioRepository
import com.stocknote.data.repo.SecurityRepository
import com.stocknote.data.repo.TradeRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **备份「加解密 / 序列化 / 恢复」的真库契约测试**（P1-4，老周 2026-10-02）。
 *
 * ## 为什么必须补
 * 此前这条链只有 [BackupCoverageTest] 那种**读源码的覆盖护栏**（表/列名单对不对），
 * 加解密往返、真库导出→恢复、失败路径**零用例** —— 而它出错 = **用户账本丢失**。
 * 两次收尾评估都点名了这一项。
 *
 * ## 三个必测场景（P1-4 验收）
 *  1. 加密 → 解密**往返一致**（含真库导出 → 新库恢复后数据一致）；
 *  2. **口令错误**：解密失败，且**不破坏当前账本**；
 *  3. **版本不匹配**（高版本备份 → 低版本 App）：给出明确提示、**不落库**。
 *
 * ⚠️ 用 JVM `JdbcSqliteDriver`（内存库、不加密），schema 由测试自建；
 * 分不清 sqlcipher 的问题（那要看 [createEncryptedDriver] 的实现）。
 * ⚠️ `BackupCrypto` 走的是 **jvmMain 的 actual**，它与 Android 侧算法一致（PBKDF2 + AES/GCM）。
 */
class BackupRestoreTest {

    private companion object {
        const val PWD = "correct-horse-battery"
    }

    /** 一个独立的内存库 + 仓储 + 备份仓储（每个用例一份，互不干扰）。 */
    private class Env(
        val repo: PortfolioRepository,
        val db: StockNoteDb,
        val driver: SqlDriver,
        val backup: BackupRepository,
    ) {
        fun tradeCount(): Int = db.tradeQueries.selectAll().executeAsList().size
        fun securityNames(): List<String> =
            db.securityQueries.selectAll().executeAsList().map { it.name }
    }

    private fun newEnv(name: String): Env {
        val driver: SqlDriver = createEncryptedDriver(name, ByteArray(0))
        StockNoteDb.Schema.create(driver)
        val db = StockNoteDb(driver)
        val repo = PortfolioRepository(
            db = db,
            quoteClient = QuoteClient(createHttpClient()),
            driver = driver,
            cash = CashRepository(db),
            keyStore = createSecureKeyStore(),
        )
        return Env(repo, db, driver, BackupRepository(driver, repo))
    }

    private fun Env.security() = SecurityRepository(db, repo, QuoteClient(createHttpClient()))

    private fun Env.trades() =
        TradeRepository(db, repo, CashRepository(db), SecurityRepository(db, repo, QuoteClient(createHttpClient())))

    /** 造一份**有内容**的账本：1 个账户 + 入金 10 万 + 1 个标的 + 1 笔买入。 */
    private suspend fun Env.seed() {
        val accountId = "acc-backup"
        db.accountQueries.upsert(accountId, "备份测试账户", "CNY", 0.0)
        CashRepository(db).addCashFlow(accountId, "2026-08-31", true, 100_000.0, Currency.CNY, 1.0)
        val sec = security().findOrCreateSecurity("sh600519", "贵州茅台", Market.A_SHARE, Currency.CNY)
        trades().addTransaction(sec.id, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")
    }

    // ================================================================ 1. 加解密往返一致

    @Test
    fun `加密解密往返一致_含中文与特殊字符`() {
        val text = "StockNote 备份 · 中文/emoji 🎁\n第二行 \"引号\" 与 \\ 反斜杠 与 <标签>"
        val encoded = BackupCrypto.encrypt(text, PWD)

        assertTrue(encoded.startsWith("SNBK1|"), "文件头必须是 SNBK1|（换算法会让历史备份全部失效）")
        assertEquals(text, BackupCrypto.decrypt(encoded, PWD))
    }

    @Test
    fun `随机盐与IV让同一明文两次密文不同_但都能解回原文`() {
        // 若盐/IV 被写成固定值，同一口令+同一明文会产生相同密文（可被离线比对）——
        // 这条断言就是防这个回归。
        val text = "同一段明文"
        val a = BackupCrypto.encrypt(text, PWD)
        val b = BackupCrypto.encrypt(text, PWD)
        assertTrue(a != b, "每次加密都应使用新的随机盐/IV")
        assertEquals(text, BackupCrypto.decrypt(a, PWD))
        assertEquals(text, BackupCrypto.decrypt(b, PWD))
    }

    @Test
    fun `密文被篡改必须解密失败_GCM认证标签兜底`() {
        val encoded = BackupCrypto.encrypt("敏感账本内容", PWD)
        // 改掉 Base64 尾部一个字符 —— checksum 是包在明文里的，这里靠的是 GCM 认证标签
        val tampered = encoded.dropLast(1) + if (encoded.last() == 'A') 'B' else 'A'
        assertFailsWith<GeneralSecurityException> { BackupCrypto.decrypt(tampered, PWD) }
    }

    @Test
    fun `不是备份文件必须明确报错`() {
        val err = assertFailsWith<IllegalArgumentException> { BackupCrypto.decrypt("随便一段文本", PWD) }
        assertTrue(err.message?.contains("不是 StockNote 备份文件") == true)
    }

    @Test
    fun `口令错误必须解密失败`() {
        val encoded = BackupCrypto.encrypt("账本", "right-password")
        assertFailsWith<GeneralSecurityException> { BackupCrypto.decrypt(encoded, "wrong-password") }
    }

    // ================================================================ 2. 真库导出 → 恢复

    @Test
    fun `备份恢复往返数据一致`() = runBlocking {
        val src = newEnv("backup-rt-src")
        src.seed()
        val srcTrades = src.tradeCount()
        val srcNames = src.securityNames()
        val srcCash = CashRepository(src.db).availableCash()
        assertTrue(srcTrades > 0 && srcNames.isNotEmpty(), "前置：源库必须先有数据")

        val encoded = src.backup.buildEncryptedBackup(PWD)

        val dst = newEnv("backup-rt-dst")
        assertTrue(dst.tradeCount() == 0, "前置：目标库应是空库")

        val statements = dst.backup.restoreFromEncoded(encoded, PWD)

        assertTrue(statements > 0, "应真的执行了若干条 INSERT")
        assertEquals(srcTrades, dst.tradeCount(), "交易条数应一致")
        assertEquals(srcNames, dst.securityNames(), "标的应一致（含名称，验证序列化没错位）")
        assertEquals(srcCash, CashRepository(dst.db).availableCash(), 0.001, "可用现金应一致")
    }

    @Test
    fun `恢复是全量覆盖_目标库原有数据必须被清掉`() = runBlocking {
        // 「恢复 = 全量覆盖」是这套语义的根：若只 INSERT 不 DELETE，
        // 别人的账本会与本账本**混在一起**（且数字看着还挺合理，极难发现）。
        val src = newEnv("backup-ovr-src")
        src.seed()

        val dst = newEnv("backup-ovr-dst")
        dst.seed()
        val bogus = dst.security().findOrCreateSecurity("sz000001", "目标库独有标的", Market.A_SHARE, Currency.CNY)
        assertEquals(2, dst.securityNames().size, "前置：目标库有 2 个标的")

        dst.backup.restoreFromEncoded(src.backup.buildEncryptedBackup(PWD), PWD)

        assertEquals(1, dst.securityNames().size, "多余的标的应被清掉（= 全量覆盖）")
        assertTrue("目标库独有标的" !in dst.securityNames())
        assertTrue(
            dst.db.securityQueries.selectAll().executeAsList().none { it.id == bogus.id },
            "目标库原有标的（id=${bogus.id}）必须连行带 id 一起消失",
        )
    }

    // ================================================================ 3. 失败路径：不能破坏当前账本

    @Test
    fun `口令错误时恢复失败且不破坏当前账本`() = runBlocking {
        val src = newEnv("backup-pwd-src")
        src.seed()
        val encoded = src.backup.buildEncryptedBackup(PWD)

        val dst = newEnv("backup-pwd-dst")
        dst.seed()
        val beforeTrades = dst.tradeCount()
        val beforeNames = dst.securityNames()
        val beforeCash = CashRepository(dst.db).availableCash()
        assertTrue(beforeTrades > 0)

        assertFailsWith<GeneralSecurityException> { dst.backup.restoreFromEncoded(encoded, "wrong-password") }

        // ⚠️ 关键：失败必须是「什么都没发生」—— DELETE 语句连发都没发出去
        assertEquals(beforeTrades, dst.tradeCount(), "口令错误时不能动当前账本")
        assertEquals(beforeNames, dst.securityNames())
        assertEquals(beforeCash, CashRepository(dst.db).availableCash(), 0.001)
    }

    @Test
    fun `校验和不一致时恢复失败且不破坏当前账本`() = runBlocking {
        // 备份文件被改动（无论有意还是传输损坏）→ 必须在事务之前拦下
        val src = newEnv("backup-sum-src")
        src.seed()
        val payload = BackupCrypto.decrypt(src.backup.buildEncryptedBackup(PWD), PWD)
        val broken = payload.replace("\"checksum\":\"", "\"checksum\":\"0")   // 让 checksum 对不上
        val tampered = BackupCrypto.encrypt(broken, PWD)

        val dst = newEnv("backup-sum-dst")
        dst.seed()
        val before = dst.tradeCount()

        val err = assertFailsWith<IllegalArgumentException> { dst.backup.restoreFromEncoded(tampered, PWD) }
        assertTrue(err.message?.contains("校验和不一致") == true, "应给人话提示：${err.message}")
        assertEquals(before, dst.tradeCount(), "校验失败时不能动当前账本")
    }

    @Test
    fun `高版本备份必须明确拒绝且不破坏当前账本`() = runBlocking {
        val src = newEnv("backup-ver-src")
        src.seed()
        // 取一份**合法**备份，只把 payload 里的 schemaVersion 改成「当前 + 1」：
        // data 与 checksum 都保持有效 → 能走到「版本检查」那一步（这正是要验的分支）。
        val payload = BackupCrypto.decrypt(src.backup.buildEncryptedBackup(PWD), PWD)
        val root = Json.parseToJsonElement(payload).jsonObject
        val bumped = JsonObject(
            root + ("schemaVersion" to JsonPrimitive(StockNoteDb.Schema.version + 1)),
        ).toString()
        val higher = BackupCrypto.encrypt(bumped, PWD)

        val dst = newEnv("backup-ver-dst")
        dst.seed()
        val beforeTrades = dst.tradeCount()
        val beforeCash = CashRepository(dst.db).availableCash()

        val err = assertFailsWith<IllegalStateException> { dst.backup.restoreFromEncoded(higher, PWD) }

        // 必须是**人话**提示（说清"备份比 App 新"），而不是底层 `no such column: xxx`
        assertTrue(
            err.message?.contains("更新版本") == true,
            "应提示是版本不匹配、而不是底层 SQL 报错：${err.message}",
        )
        assertEquals(beforeTrades, dst.tradeCount(), "版本不匹配时不能动当前账本")
        assertEquals(beforeCash, CashRepository(dst.db).availableCash(), 0.001)
    }

    // ================================================================ 4. 导出侧防护

    @Test
    fun `空账本导出必须报错_绝不生成空备份`() = runBlocking {
        // 空备份一旦被恢复 = DELETE 全表 + 0 条 INSERT = 账本被清空（真机事故根因之一）
        val env = newEnv("backup-empty")
        val err = assertFailsWith<IllegalStateException> { env.backup.buildEncryptedBackup(PWD) }
        assertTrue(err.message?.contains("没有任何数据") == true, "应明确报错：${err.message}")
    }

    @Test
    fun `空数据备份必须拒绝恢复且不破坏当前账本`() = runBlocking {
        // 构造一份「格式合法、checksum 正确，但 data 全是空行」的备份 —— 等价于空备份
        val data = "\n\n"
        val checksum = com.stocknote.core.io.Sha256.hex(data.encodeToByteArray())
        val body = """{"app":"StockNote","schemaVersion":${StockNoteDb.Schema.version},""" +
            """"checksum":"$checksum","data":"\n\n"}"""
        val encoded = BackupCrypto.encrypt(body, PWD)

        val dst = newEnv("backup-emptydata")
        dst.seed()
        val before = dst.tradeCount()

        val err = assertFailsWith<IllegalStateException> { dst.backup.restoreFromEncoded(encoded, PWD) }
        assertTrue(err.message?.contains("不含任何数据") == true, "应明确拒绝：${err.message}")
        assertEquals(before, dst.tradeCount(), "拒绝时不能动当前账本")
    }
}
