package com.stocknote.data.security

import kotlin.random.Random
import platform.Foundation.NSUserDefaults

/**
 * iOS 实现 —— ⚠️ **M0 临时占位，必须在 M1 换成 Keychain，否则 iOS 版不可交付**。
 *
 * 当前把口令存在 NSUserDefaults 里（明文）。
 * 之所以暂时接受：iOS 目前只是「自用 / TestFlight、且本机无 macOS 无法构建」的旁支，
 * Android 交付链路完全不受影响；而把 Keychain 的 CFDictionary / SecItem 互操作
 * 先写在这里又无法编译验证，只会留下一堆看着像真的假代码。
 *
 * M1 替换要点（在 macOS 上执行）：
 *  - Security 框架：SecItemAdd / SecItemCopyMatching / SecItemDelete
 *  - kSecClass = kSecClassGenericPassword，kSecAttrService = "com.stocknote.app"
 *  - kSecAttrAccessible = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
 *  - 从 CFDataRef 取字节：CFBridgingRelease(...) as NSData，再转 ByteArray
 */
class IosSecureKeyStore : SecureKeyStore {

    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getOrCreateDatabasePassphrase(): ByteArray {
        defaults.stringForKey(SecureKeyStore.DATABASE_PASSPHRASE_KEY)?.let { encoded ->
            decode(encoded)?.let { return it }
        }
        val fresh = ByteArray(PASSPHRASE_BYTES)
        Random.nextBytes(fresh)
        defaults.setObject(encode(fresh), forKey = SecureKeyStore.DATABASE_PASSPHRASE_KEY)
        return fresh
    }

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }

    // 明文存储下的「编码」只是避免二进制口令直接进 plist，不构成任何安全保护
    private fun encode(bytes: ByteArray): String =
        bytes.joinToString("") { b -> ((b.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    private fun decode(hex: String): ByteArray? = runCatching {
        require(hex.length % 2 == 0) { "十六进制长度非法" }
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }.getOrNull()

    private companion object {
        const val PASSPHRASE_BYTES = 32
    }
}
