package com.stocknote.data.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/**
 * 备份加解密（Android 实现）：PBKDF2 → AES/GCM。
 * GCM 自带完整性认证：密码错或文件被改，解密直接抛 AEADBadTagException。
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
            // ⚠️ 轻微-11 修复（2026-09-28）：**用完即清**口令副本 —— PBEKeySpec 会把 char[] 留在内存，
            // GC 之前可通过堆转储读到口令。生成密钥后立即 clear。
            spec.clearPassword()
        }
        // ⚠️ 轻微-11 的另一半（KDF 参数进文件头）**刻意未做**：当前 KDF 全 App 只有一套配置
        //（ITERATIONS/KEY_BITS 唯一），文件头 `SNBK1|` 不含参数也能解析；把参数写进头需要
        // 「新头 SNBK2 + 兼容解析旧 SNBK1」两端一起动，属于加密格式演进，需专项处理。
    }

    actual fun encrypt(plainText: String, password: String): String {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val packed = salt + iv + cipherText
        return HEADER + Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    actual fun decrypt(encoded: String, password: String): String {
        require(encoded.startsWith(HEADER)) { "不是 StockNote 备份文件" }
        val packed = Base64.decode(encoded.removePrefix(HEADER), Base64.NO_WRAP)
        require(packed.size > SALT_LEN + IV_LEN) { "备份文件已损坏" }
        val salt = packed.copyOfRange(0, SALT_LEN)
        val iv = packed.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val cipherText = packed.copyOfRange(SALT_LEN + IV_LEN, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }
}
