package com.stocknote.data.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份加解密（JVM 实现，2026-09-29）—— **仅为测试服务**。
 *
 * 与 Android 实现**算法完全一致**（PBKDF2WithHmacSHA256 + AES/GCM/NoPadding、
 * 120000 次迭代、文件头 `SNBK1|`），仅把 `android.util.Base64` 换成 `java.util.Base64`。
 *
 * ⚠️ 两者行为必须一致 —— 否则「Android 备份 → JVM 测试解密」这类跨端验证会假失败。
 * 本文件的存在也让"备份/恢复"这条链有机会被真数据库测试覆盖。
 */
actual object BackupCrypto {

    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val HEADER = "SNBK1|"

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            // ⚠️ 与 Android 侧同样的处理：生成密钥后立即清掉 char[] 副本
            spec.clearPassword()
        }
    }

    actual fun encrypt(plainText: String, password: String): String {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val packed = salt + iv + cipherText
        return HEADER + java.util.Base64.getEncoder().encodeToString(packed)
    }

    actual fun decrypt(encoded: String, password: String): String {
        require(encoded.startsWith(HEADER)) { "不是 StockNote 备份文件" }
        val packed = java.util.Base64.getDecoder().decode(encoded.removePrefix(HEADER))
        require(packed.size > SALT_LEN + IV_LEN) { "备份文件已损坏" }
        val salt = packed.copyOfRange(0, SALT_LEN)
        val iv = packed.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val cipherText = packed.copyOfRange(SALT_LEN + IV_LEN, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }
}
