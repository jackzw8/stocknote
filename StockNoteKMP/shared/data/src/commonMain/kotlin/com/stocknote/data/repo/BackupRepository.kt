package com.stocknote.data.repo

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.stocknote.core.csv.SqlLiteral
import com.stocknote.data.backup.BackupCrypto
import com.stocknote.data.backup.BackupSchema
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.platform.todayIso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * **加密备份 / 恢复域 Repository**（阶段 2：实现已搬迁，2026-09-29，第 9 批）。
 *
 * 自成体系：逐表 dump 成 SQL → 打包 JSON（含 schemaVersion + checksum）→
 * 按 REQ-SEC-03 加密（PBKDF2 + AES/GCM）。恢复则是反过来的全量覆盖。
 *
 * ⚠️ **原类不持有本类**（本类要 `repo.invalidateCurveCache()`，反向持有会循环）——
 * 调用方（设置页）直接从容器拿。
 *
 * ## 覆盖清单的单一来源
 * 表与列清单在 [BackupSchema]（`backup/BackupSchema.kt`），那边有 `BackupCoverageTest`
 * 逐张表对着 `*.sq` 校验，**漏表会直接红**。本类只消费它，不自己维护清单。
 *
 * ## ⚠️ 两条"防护"是被真机事故教出来的，别删
 *  1. **导出**：账本为空 → 报错，**绝不生成空备份**（空备份一旦被恢复会把当前账本清空）；
 *  2. **恢复**：备份无数据行 → 拒绝执行（否则 DELETE 全表后 0 条 INSERT，账本被清空）。
 *
 * ## ⚠️ 恢复 = 全量覆盖（语义要点）
 *  - 执行前先删所有备份内的表；**另外必须清 `trade_photo`** ——
 *    它不在备份清单里（单图 ≤2MB 不进备份），但恢复后不能残留旧账本的截图
 *    （交易 id 撞上就会把旧截图挂到别人的交易上，M7 修复）；
 *  - `ROLLBACK` 必须**兜底 `runCatching`** —— 否则会抛"cannot rollback - no transaction is active"，
 *    把原始错误替换掉，用户看不到真正原因（M9 修复）。
 */
class BackupRepository internal constructor(
    private val driver: SqlDriver,
    /** ⚠️ 仅用于恢复成功后失效资产曲线缓存（本类不能反向依赖原类之外的东西）。 */
    private val repo: PortfolioRepository,
) {
    private val backupColumns get() = BackupSchema.columns

    private val backupTables get() = BackupSchema.tables

    /**
     * 生成加密备份**文本**（SAF 保存到用户选的位置用）—— 老周 2026-09-17。
     *
     * 实现：逐表 `SELECT *` 生成 `INSERT OR REPLACE` 语句（通用、不依赖逐表 DTO），
     * 打包 JSON（含 schemaVersion + checksum）后按 REQ-SEC-03 加密（PBKDF2 + AES/GCM）。
     */
    suspend fun buildEncryptedBackup(password: String): String = withContext(Dispatchers.Default) {
        val sb = StringBuilder()
        backupTables.forEach { table ->
            val columns = backupColumns.getValue(table)
            val colList = columns.joinToString(",")
            // executeQuery 直接返回 mapper 包装的 List<String>（SQLDelight 2.x 自动 unwrap QueryResult）
            val rows: List<String> = driver.executeQuery(
                null,
                "SELECT $colList FROM $table",
                { cursor ->
                    val list = mutableListOf<String>()
                    while (cursor.next().value) {
                        val vals = columns.indices.map { idx ->
                            val s = cursor.getString(idx)
                            if (s == null) "NULL" else sqlLiteral(s)
                        }
                        list.add("(" + vals.joinToString(",") + ")")
                    }
                    QueryResult.Value(list)
                },
                0,
            ).value
            rows.forEach { row ->
                sb.append("INSERT OR REPLACE INTO $table ($colList) VALUES ").append(row).append(";\n")
            }
        }
        val sqlDump = sb.toString()
        // 导出防护：账本是空的就报错，绝不生成"空备份"——
        // 空备份一旦被恢复，会把当前账本清空（本次真机事故的根因之一）
        if (sqlDump.isBlank()) {
            error("账本没有任何数据，无需备份")
        }
        // ⚠️ 不能用 `java.security.MessageDigest`（JVM 专有 → iOS 编译失败），
        // 也不能用 `Charsets.UTF_8`（同为 JVM 专有）→ 统一走 core 的纯 Kotlin 实现。
        val checksum = com.stocknote.core.io.Sha256.hex(sqlDump.encodeToByteArray())
        val payload = buildJsonObject {
            put("app", JsonPrimitive("StockNote"))
            // M12 修复（2026-09-27）：写**真实** schema 版本 —— 此前硬编码 6，与实际严重不符
            // （备份格式版本应当可追溯，将来做"跨版本恢复"时要用它判断兼容性）
            put("schemaVersion", JsonPrimitive(StockNoteDb.Schema.version))
            put("exportedAt", JsonPrimitive(todayIso()))
            put("checksum", JsonPrimitive(checksum))
            put("data", JsonPrimitive(sqlDump))
        }.toString()

        BackupCrypto.encrypt(payload, password)
    }

    /**
     * 把字符串转成**单行**的 SQL 字面量（H1 修复，2026-09-27）。
     *
     * 具体转义规则见 [SqlLiteral]（已抽到 core 以便单测 ——
     * 备注里的裸换行会把一条 INSERT 切成两半，让**整份备份无法恢复**，而 checksum 仍会通过）。
     */
    private fun sqlLiteral(s: String): String = SqlLiteral.of(s)

    /**
     * 从加密备份**文本**恢复（SAF 选文件用）—— 老周 2026-09-17。
     * @return 执行的语句条数
     */
    suspend fun restoreFromEncoded(encoded: String, password: String): Int =
        withContext(Dispatchers.Default) {
            val payload = BackupCrypto.decrypt(encoded, password)
            val json = Json.parseToJsonElement(payload).let { it as JsonObject }
            require((json["app"] as? JsonPrimitive)?.content == "StockNote") {
                "不是 StockNote 备份文件"
            }
            val data = (json["data"] as? JsonPrimitive)?.content
                ?: error("备份缺少数据段")
            val checksum = (json["checksum"] as? JsonPrimitive)?.content
            // ⚠️ 2026-09-30：此处是**漏改的一半**（导出侧已换、恢复侧没换）——
            // `java.security.MessageDigest` / `Charsets` / `"%02x".format` 全是 JVM 专有，
            // iOS 编译直接失败。校验必须与导出侧**同一算法**，故同样走 core 的纯 Kotlin Sha256。
            val actual = com.stocknote.core.io.Sha256.hex(data.encodeToByteArray())
            require(checksum == actual) { "校验和不一致：文件已损坏或被修改" }

            // ⚠️ M8 修复（2026-09-28）：**读 `schemaVersion` 并给明确提示**。
            // 此前这个字段只写不读 —— 把「高版本 App 导出的备份」恢复到低版本时，
            // 执行到新列 / 新表会抛 `no such column: xxx`，整份恢复回滚，
            // 用户只看到底层 SQL 报错，根本不知道原因是"版本不匹配"。
            // 现在提前拦下并说清楚，且**不动当前账本**（校验在事务之前）。
            val backupSchema = (json["schemaVersion"] as? JsonPrimitive)?.content?.toLongOrNull()
            val currentSchema = StockNoteDb.Schema.version
            if (backupSchema != null && backupSchema > currentSchema) {
                error(
                    "这份备份来自**更新版本**的 App（备份 Schema v$backupSchema，当前 App 支持 v$currentSchema）。" +
                        "请先升级 App 再恢复 —— 直接恢复会因缺少新版本的列/表而失败。当前账本未受影响。",
                )
            }

            val statements = data.lines().filter { it.isNotBlank() }
            // 恢复防护：空备份拒绝执行——否则 DELETE 全表后 0 条 INSERT，账本被清空（本次真机事故根因）
            if (statements.isEmpty()) {
                error("备份文件不含任何数据，已取消恢复（当前账本未受影响）")
            }
            // 外观检查（H1 修复配套，2026-09-27）：dump 保证"一条 INSERT 占一行"。
            // 若出现"半截 SQL"（旧版备份里备注/复盘含裸换行），给人话提示而不是底层 SQL 报错。
            val badLine = statements.indexOfFirst { !it.trimStart().startsWith("INSERT ") }
            if (badLine >= 0) {
                error(
                    "备份文件格式异常（第 ${badLine + 1} 行不是 INSERT 语句）：该备份可能由旧版本导出、" +
                        "且其中含换行的备注内容，已无法恢复。当前账本未受影响。",
                )
            }
            driver.execute(null, "BEGIN", 0)
            try {
                backupTables.forEach { table ->
                    driver.execute(null, "DELETE FROM $table", 0)
                }
                // ⚠️ M7 修复（2026-09-28）：`trade_photo` **不在备份清单里，但必须清**。
                // 「恢复 = 全量覆盖」的语义要求：恢复后不残留任何属于旧账本的数据。
                // 此前漏了它 → 旧截图留在库里，而新账本的交易 id 一旦撞上（id 生成含随机段，
                // 概率低但备份往返完全可能命中）就会**把旧截图挂到别人的交易上**。
                // 截图不参与备份（REQ：单图 ≤2MB 不进备份），恢复时清空是唯一正确做法。
                runCatching { driver.execute(null, "DELETE FROM trade_photo", 0) }
                statements.forEach { sql ->
                    driver.execute(null, sql, 0)
                }
                driver.execute(null, "COMMIT", 0)
            } catch (t: Throwable) {
                // ⚠️ M9 修复（2026-09-28）：ROLLBACK 必须**兜底**。`BEGIN` 在 try 之外，
                // 若在 BEGIN 之后、第一条语句之前就失败（或 SQLite 已因错误自动回滚），
                // 裸发 ROLLBACK 会抛 "cannot rollback - no transaction is active"，
                // **把原始错误替换掉** —— 用户看到的是回滚失败，而不是真正的原因。
                runCatching { driver.execute(null, "ROLLBACK", 0) }
                throw IllegalStateException("恢复失败已回滚：${t.message}", t)
            }
            repo.invalidateCurveCache()
            statements.size
        }
}
