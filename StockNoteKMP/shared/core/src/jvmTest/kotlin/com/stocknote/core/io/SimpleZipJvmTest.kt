package com.stocknote.core.io

import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 只在 JVM 跑的**真解压**验证：用 JDK 自带的 `java.util.zip` 解我们手写的 zip。
 * 这条测试比"看字节签名"有用得多 —— 中央目录偏移、CRC、长度任一写错，
 * JDK 的解压器都会直接报错或读出乱码。
 */
class SimpleZipJvmTest {

    @Test
    fun jdkCanUnzipWhatWeWrite() {
        val files = linkedMapOf(
            "stocknote-template-trades.csv" to "日期,标的代码\r\n2026-09-16,sh600519\r\n",
            "stocknote-template-cashflows.csv" to "日期,类型,金额,币种,备注\r\n2026-09-16,存入,10000,CNY,\r\n",
            "stocknote-template-plans.csv" to "标的代码,方向,目标价\r\nsh600519,买入,1800\r\n",
        )
        val zip = SimpleZip.of(files.map { SimpleZip.Entry(it.key, it.value) })

        val got = LinkedHashMap<String, String>()
        ZipInputStream(zip.inputStream()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                got[e.name] = zin.readBytes().toString(Charsets.UTF_8)
                e = zin.nextEntry
            }
        }
        assertEquals(files.keys.toList(), got.keys.toList())
        files.forEach { (k, v) -> assertEquals(v, got[k], "文件 $k 内容应一致") }
        assertTrue(got.size == 3)
    }
}
