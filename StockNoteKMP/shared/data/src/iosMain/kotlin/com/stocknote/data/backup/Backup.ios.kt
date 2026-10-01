package com.stocknote.data.backup

/**
 * iOS 占位（iOS 三件套被 macOS 卡着，未接入）。
 * 接入时用 CommonCrypto/AES-GCM 实现同签名；文件落盘位置一律走 iOS 侧的文件桥
 * （与 Android 的 SAF 对应，见 FileBridge），不再有「固定目录」这一层。
 */
actual object BackupCrypto {
    actual fun encrypt(plainText: String, password: String): String =
        error("iOS 备份加密待接入（macOS 环境就绪后实现）")

    actual fun decrypt(encoded: String, password: String): String =
        error("iOS 备份解密待接入（macOS 环境就绪后实现）")
}
