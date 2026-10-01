package com.stocknote.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom

/**
 * Android 实现：Android Keystore（AES-256-GCM）。
 *
 * 安全模型：
 *  - 密钥本身由 Keystore 生成并持有，**私钥材料永远不进入 App 进程内存、不落盘**；
 *  - App 只拿到一个句柄，加解密在 TEE / StrongBox 内完成；
 *  - 落盘的只有「口令密文」，root 拿到 files 目录也解不开（除非能攻破硬件密钥库）。
 *
 * 失败策略：解密失败时**直接抛错，绝不自动重建口令**。
 * 因为一旦重建，已有账本就永久不可读——宁可让用户看到明确报错。
 */
class AndroidSecureKeyStore(context: Context) : SecureKeyStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getOrCreateDatabasePassphrase(): ByteArray {
        val stored = prefs.getString(SecureKeyStore.DATABASE_PASSPHRASE_KEY, null)
        if (stored != null) {
            decrypt(stored)?.let { return it }
            error(
                "数据库口令存在但无法解密（Keystore 密钥可能已被清除或设备凭据变更）。" +
                    "为避免已有账本被不可逆地覆盖，这里拒绝自动重建口令。"
            )
        }
        val fresh = ByteArray(PASSPHRASE_BYTES)
        // 口令随机数必须用密码学安全源（老周 2026-09-23 安全审查 P4）：
        // kotlin.random.Random 是可预测的 PRNG，不该用来生成密钥材料。
        SecureRandom().nextBytes(fresh)
        prefs.edit()
            .putString(SecureKeyStore.DATABASE_PASSPHRASE_KEY, encrypt(fresh))
            .apply()
        return fresh
    }

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, encrypt(value.toByteArray(Charsets.UTF_8))).apply()
    }

    override fun getString(key: String): String? =
        prefs.getString(key, null)?.let { decrypt(it)?.toString(Charsets.UTF_8) }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain)
        // 存储格式： [ivLen(1B)][iv][cipherText]，iv 长度不写死，兼容 Keystore 后续调整
        val blob = ByteArray(1 + iv.size + cipherText.size)
        blob[0] = iv.size.toByte()
        iv.copyInto(blob, 1)
        cipherText.copyInto(blob, 1 + iv.size)
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray? = runCatching {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        require(blob.size > 2) { "密文长度非法" }
        val ivLen = blob[0].toInt()
        require(ivLen in 1..32 && blob.size > 1 + ivLen) { "IV 长度非法: $ivLen" }
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val cipherText = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(cipherText)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 刻意不要求用户认证：本 App 的威胁模型是「设备被翻看/丢失」，不是「抵御有物理权限的攻击者」
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "stocknote_db_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PASSPHRASE_BYTES = 32
        const val PREFS_NAME = "stocknote_secure_store"
    }
}
