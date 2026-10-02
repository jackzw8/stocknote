@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package com.stocknote.data.security

import kotlinx.cinterop.UInt8Var
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSString
import platform.Foundation.NSUserDefaults
import platform.Security.SecCopyErrorMessageString
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecRandomDefault
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus

/**
 * iOS 实现 —— **Keychain（Security 框架）**。
 *
 * ✅ 2026-10-02（P0-2）：**已从 M0 的 NSUserDefaults 明文占位换成 Keychain**。
 * 旧实现把 32 字节口令以十六进制写进 `NSUserDefaults`（plist 明文），等于「本地加密」在 iOS 上是空话；
 * 这正是本项被列为 **P0（对外发布前必做）** 的原因。
 *
 * ## 存储模型
 * - 每个键 = 一个 `kSecClassGenericPassword` 条目：`kSecAttrService = "com.stocknote.app"`、
 *   `kSecAttrAccount = 键名`；值以字符串形式放进 `kSecValueData`。
 * - `kSecAttrAccessible = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`：
 *   设备**首次解锁后**才可读（App 冷启动在后台也能开库），且**不随 iCloud/换机迁移** ——
 *   与 Android 侧「密钥由本机硬件持有、不出本机」的口径一致。
 * - ⚠️ 这里与 Android 的实现路径**有意不同**：Android Keystore 只能存密钥句柄、存不了任意数据，
 *   所以那边是「Keystore 里的 AES 密钥 → 加解密随机口令 → 密文落盘」；
 *   而 Keychain 本身就能加密存储任意数据，直接把口令放进去即可，**不必**再自造一层 AES（自造只会更弱）。
 *
 * ## 失败策略（与 Android 完全一致）
 * **读不到 / 格式坏了就抛错，绝不自动重建口令** —— 一旦重建，已有账本永久不可读。
 *
 * ## 编译注意
 * 本文件在 Windows 上不会被编译（iOS target 由 `stocknote.ios` 开关门控），只能靠 CI 验证。
 * 因此这里刻意**只用纯 CoreFoundation 的 C API**（`CFDictionaryCreateMutable` + `CFDictionarySetValue`
 * + `CFBridgingRetain/Release`），不走 `NSMutableDictionary` —— 少一层 toll-free bridging 的不确定性；
 * 唯一的例外是最后把取回的 `CFTypeRef` 转成 Kotlin `String`（`CFBridgingRelease` + `as NSString`）。
 */
class IosSecureKeyStore : SecureKeyStore {

    init {
        purgeLegacyPlaintextStore()
    }

    override fun getOrCreateDatabasePassphrase(): ByteArray {
        readValue(SecureKeyStore.DATABASE_PASSPHRASE_KEY)?.let { stored ->
            stored.toHexBytesOrNull()?.let { return it }
            error(
                "Keychain 里的数据库口令格式非法。为避免已有账本被不可逆地覆盖，这里拒绝自动重建口令。",
            )
        }
        val fresh = randomPassphrase()
        putValue(SecureKeyStore.DATABASE_PASSPHRASE_KEY, fresh.toHexString())
        return fresh
    }

    override fun putString(key: String, value: String) = putValue(key, value)

    override fun getString(key: String): String? = readValue(key)

    override fun remove(key: String) = removeValue(key)

    // ---- Keychain 读 / 写 / 删 ----

    private fun readValue(account: String): String? = withAccount(account) { cfAccount, cfService ->
        withDictionary(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to cfService,
            kSecAttrAccount to cfAccount,
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        ) { query ->
            memScoped {
                val out = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, out.ptr)
                if (status != errSecSuccess && status != errSecItemNotFound) failWith(status)
                if (status == errSecItemNotFound) {
                    null
                } else {
                    (CFBridgingRelease(out.value) as? NSString)?.toKotlinString()
                }
            }
        }
    }

    private fun putValue(account: String, value: String) =
        withAccount(account) { cfAccount, cfService ->
            val cfValue = CFBridgingRetain(value.toNSString())
            try {
                val added = withDictionary(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfService,
                    kSecAttrAccount to cfAccount,
                    kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                    kSecValueData to cfValue,
                ) { SecItemAdd(it, null) }

                when (added) {
                    errSecSuccess -> Unit
                    // 已存在 → 只改 value。**刻意不用 delete + add**：中途失败会让口令消失，账本永久不可读。
                    errSecDuplicateItem -> {
                        val updated = withDictionary(
                            kSecClass to kSecClassGenericPassword,
                            kSecAttrService to cfService,
                            kSecAttrAccount to cfAccount,
                        ) { query ->
                            withDictionary(kSecValueData to cfValue) { attributes ->
                                SecItemUpdate(query, attributes)
                            }
                        }
                        if (updated != errSecSuccess) failWith(updated)
                    }
                    else -> failWith(added)
                }
            } finally {
                CFBridgingRelease(cfValue)
            }
        }

    private fun removeValue(account: String) = withAccount(account) { cfAccount, cfService ->
        val status = withDictionary(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to cfService,
            kSecAttrAccount to cfAccount,
        ) { query -> SecItemDelete(query) }
        // 本来就没有 = 目标已达成，不算失败
        if (status != errSecSuccess && status != errSecItemNotFound) failWith(status)
    }

    /**
     * 构造 CFDictionary 并把「键名 / 服务名的桥接引用」的生命周期管好。
     *
     * `CFBridgingRetain` 是 **+1**，用完必须 `CFBridgingRelease` 回来，否则每次读写都漏一个 NSString。
     * 这里把 account 与 service 统一在 `withAccount` 里成对保留、在 `finally` 里成对释放，
     * 调用方看到的就只是普通的 Kotlin `String`。
     */
    private inline fun <R> withAccount(
        account: String,
        block: (cfAccount: CFTypeRef?, cfService: CFTypeRef?) -> R,
    ): R {
        val cfAccount = CFBridgingRetain(account.toNSString())
        val cfService = CFBridgingRetain(SERVICE_NAME.toNSString())
        try {
            return block(cfAccount, cfService)
        } finally {
            CFBridgingRelease(cfAccount)
            CFBridgingRelease(cfService)
        }
    }

    /**
     * 搭一个 CFDictionary（使用完即释放）。
     *
     * ⚠️ 传 `null` 作为 key/value 回调 = **不 retain**，所以键值必须在本次调用期间一直活着
     *（上面的 `withAccount` / `cfValue` 就是为此）。键是常量指针，做等值查找没问题。
     */
    private fun <R> withDictionary(
        vararg items: Pair<CFStringRef?, CFTypeRef?>,
        block: (CFDictionaryRef?) -> R,
    ): R {
        val dict = CFDictionaryCreateMutable(kCFAllocatorDefault, items.size.convert(), null, null)
        try {
            for ((key, value) in items) CFDictionarySetValue(dict, key, value)
            return block(dict)
        } finally {
            CFRelease(dict)
        }
    }

    /** 口令随机数必须用**密码学安全**源（与 Android 侧 `SecureRandom` 对称；`kotlin.random.Random` 可预测）。 */
    private fun randomPassphrase(): ByteArray {
        val bytes = ByteArray(PASSPHRASE_BYTES)
        val status = bytes.usePinned { pinned ->
            SecRandomCopyBytes(kSecRandomDefault, PASSPHRASE_BYTES.convert(), pinned.addressOf(0).reinterpret<UInt8Var>())
        }
        if (status != errSecSuccess) failWith(status)
        return bytes
    }

    /** 统一报错（带上系统给的人话原因，排查时不用去查 OSStatus 表）。 */
    private fun failWith(status: OSStatus): Nothing {
        val reason = (CFBridgingRelease(SecCopyErrorMessageString(status, null)) as? NSString)?.toKotlinString()
        error("Keychain 操作失败（OSStatus=$status" + (reason?.let { "，$it" } ?: "") + "）")
    }

    /**
     * 抹掉 M0 时期留在 `NSUserDefaults` 里的**明文口令**。
     *
     * 留着它等于「口令仍然明文落盘」，与新实现的目标直接矛盾。升级后第一次启动就会清掉；
     * 抹掉不会影响任何东西 —— 那时口令早已换到 Keychain，旧口令对应的还是那个明文库（已被改名留档）。
     */
    private fun purgeLegacyPlaintextStore() {
        runCatching {
            NSUserDefaults.standardUserDefaults
                .removeObjectForKey(SecureKeyStore.DATABASE_PASSPHRASE_KEY)
        }
    }

    private companion object {
        const val PASSPHRASE_BYTES = 32

        /** 与 `PRODUCT_BUNDLE_IDENTIFIER` 保持一致 —— 换 App 标识时这里的旧条目就取不到了。 */
        const val SERVICE_NAME = "com.stocknote.app"
    }
}

// ---- 十六进制与字符串桥接的小工具（`Platform.ios.kt` 拼 `PRAGMA key` 时也用它）----

/** 字节 → 小写十六进制。 */
internal fun ByteArray.toHexString(): String =
    joinToString("") { b -> ((b.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

/** 十六进制 → 字节；长度非法或含非十六进制字符时返回 `null`（调用方据此判断"格式坏了"）。 */
internal fun String.toHexBytesOrNull(): ByteArray? = runCatching {
    require(length % 2 == 0) { "长度必须是偶数" }
    require(all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "含非十六进制字符" }
    ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}.getOrNull()

/**
 * Kotlin `String` → `NSString`。
 *
 * ⚠️ 这两处强转在 K/N 里是**真实存在**的桥（`String` 与 `NSString` 双向映射），
 * 但编译器会报 `CAST_NEVER_SUCCEEDS` 警告 —— 是本项目已知的"看着像错、其实必需"的写法。
 */
@Suppress("CAST_NEVER_SUCCEEDS")
private fun String.toNSString(): NSString = this as NSString

@Suppress("CAST_NEVER_SUCCEEDS")
private fun NSString.toKotlinString(): String = this as String

