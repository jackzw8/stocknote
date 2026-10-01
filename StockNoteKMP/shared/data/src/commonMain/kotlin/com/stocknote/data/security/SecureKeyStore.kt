package com.stocknote.data.security

/**
 * 安全密钥存取。
 *
 * Android → Android Keystore（密钥不落盘、由 TEE/StrongBox 保护）
 * iOS     → Keychain
 *
 * 关键原则：**数据库口令本身绝不明文落盘**。
 * 流程是「Keystore 里生成/持有一把 AES 密钥 → 用它加解密随机生成的口令 → 密文才落盘」。
 */
interface SecureKeyStore {

    /** 取出数据库口令；不存在则生成一个 32 字节随机口令并加密后持久化。 */
    fun getOrCreateDatabasePassphrase(): ByteArray

    fun putString(key: String, value: String)

    fun getString(key: String): String?

    fun remove(key: String)

    companion object {
        const val DATABASE_PASSPHRASE_KEY = "db_passphrase"
    }
}
