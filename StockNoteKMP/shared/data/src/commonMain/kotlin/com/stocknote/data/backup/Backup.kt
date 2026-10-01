package com.stocknote.data.backup

/**
 * 备份文件与加解密的平台隔离点（REQ-SEC-03，自 M5 提前至 M4）。
 *
 * 文件策略（老周 2026-09-23 清理旧路径）：
 *  - 备份与 CSV 的**落盘位置全部由用户通过 SAF（系统文件选择器）自选**，见 FileBridge；
 *  - 原先「固定写公共 Documents/StockNote/」的 BackupFileStore 已删除，原因：
 *    ① Android 10+ 直写公共目录会失败（scoped storage）；
 *    ② 明文 CSV 落到公共目录 = 把账本暴露给其它 App。
 *
 * 加密策略（REQ-SEC-03）：
 *  - 密钥：PBKDF2WithHmacSHA256(密码, 盐, 120_000 次, 256bit)；
 *  - 加密：AES/GCM/NoPadding（随机 12 字节 IV，认证标签自带防篡改）；
 *  - 文件头 `SNBK1|` + Base64(盐|IV|密文)。
 *  - 完整性由 GCM 认证标签保证，另在 JSON payload 内含 schemaVersion 与 checksum。
 */
expect object BackupCrypto {
    fun encrypt(plainText: String, password: String): String
    fun decrypt(encoded: String, password: String): String
}
