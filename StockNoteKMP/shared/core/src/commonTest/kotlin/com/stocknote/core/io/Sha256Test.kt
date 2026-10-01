package com.stocknote.core.io

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 纯 Kotlin SHA-256 的正确性（老周 2026-09-30）。
 *
 * **为什么必须测**：它替掉了原来的 `java.security.MessageDigest`（JVM 专有，iOS 编译不过），
 * 而它的输出会被**写进备份文件**、恢复时逐字节比对 —— 一旦算错，
 * 表现是"备份文件全部校验失败"（用户以为备份坏了），而不是编译错误。
 *
 * 用 FIPS 180-4 / NIST 的标准测试向量钉死。
 */
class Sha256Test {

    @Test
    fun 标准向量_空串() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(ByteArray(0)),
        )
    }

    @Test
    fun 标准向量_abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".encodeToByteArray()),
        )
    }

    @Test
    fun 标准向量_数字串() {
        // 与 SimpleZipTest 用同一个串（那边测 CRC32），方便对照
        assertEquals(
            "15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225",
            Sha256.hex("123456789".encodeToByteArray()),
        )
    }

    @Test
    fun 跨多块与填充边界() {
        // 55 / 56 / 64 字节是**填充分支的边界**（56 会多补一整块），各钉一条
        assertEquals(
            "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318",
            Sha256.hex("a".repeat(55).encodeToByteArray()),
        )
        assertEquals(
            "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a",
            Sha256.hex("a".repeat(56).encodeToByteArray()),
        )
        assertEquals(
            "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb",
            Sha256.hex("a".repeat(64).encodeToByteArray()),
        )
    }

    @Test
    fun 中文与多字节内容() {
        // 备份内容是 JSON（含中文），必须按 UTF-8 字节算（18 字节）
        assertEquals(
            "787fd2df154f8cf9a8ae643f76e3ada6bb141b89b39adc73dfe2ea5023e66661",
            Sha256.hex("股票交易笔记".encodeToByteArray()),
        )
    }
}
