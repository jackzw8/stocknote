# iosApp —— iOS 外壳

## 当前状态（2026-10-02）

**已跑通**：由 GitHub Actions 的 macOS runner 出**未签名 IPA**（`.github/workflows/ios.yml`，
手动触发），老周自签后装 iPad Pro 11" 3 代（iOS 26.5）可正常启动使用。

⚠️ **本机（Windows）编不了 iOS**：Kotlin/Native 只能在 macOS 上链接 Apple 平台产物，
所以 iOS 侧代码在本地**永远拿不到编译反馈**，只能靠 CI。`gradle.properties` 的
`stocknote.ios` 开关在 Windows 上会让 iOS 目标**不注册**，保证 Android 构建不被拖累；
到 macOS 上它会自动变成 `true`，无需手工改。

### 安全项（P0）已完成 —— 2026-10-02
1. **数据库加密**：`Platform.ios.kt` 走 `NativeSqliteDriver(onConfiguration = …)` 塞
   `DatabaseConfiguration.Encryption(key = 口令十六进制)`，由 `co.touchlab:sqliter` 执行
   `PRAGMA key`；native 库来自 `project.yml` 里的官方 SwiftPM 包
   `https://github.com/sqlcipher/SQLCipher.swift`（**4.17.0，与 Android 侧同版本**）。
   ⚠️ 因此 `OTHER_LDFLAGS` 里的 `-lsqlite3` **已经删掉** —— 系统 sqlite3 与 SQLCipher
   同时在场会让 `PRAGMA key` 静默失效（仍写明文）。
2. **口令存取**：`IosSecureKeyStore` 用 Keychain（`SecItemAdd/CopyMatching/Update/Delete` +
   `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`），不再是 `NSUserDefaults` 明文。
3. **图标**：`iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`（1024 全尺寸单图，
   Xcode 14+ 形式），配合 `ASSETCATALOG_COMPILER_APPICON_NAME` + `Info.plist` 的
   `CFBundleIconName`。图标源图复用 `项目文档/界面原型-2.0/appicon/appicon_1024.png`，
   去掉圆角（iOS 自己会切圆角，且 App 图标不允许有 alpha 通道）。

### ⚠️ 升级注意（明文库 → 加密库）
接入加密前，iOS 上落的是**明文** `stocknote.db`。首次装上加密版本时：
- 老库（明文）会被自动改名留档为 `stocknote.db.plaintext.bak`（**数据不删**），
  随后新建加密库 —— 也就是**账本内容不会自动迁移**（iOS 一直只跑演示数据，影响可忽略）。
- 想取回旧数据：用带 SQLCipher 的 DB Browser 打开该 `.bak` 文件。
- 位置：`<沙盒>/Library/Application Support/databases/`（**不是 Documents** —— 见 sqliter
  的 `DatabaseFileContext.kt`）。

---

## 首次在 macOS 上开工

### 0. 前置

- macOS + Xcode（命令行工具已 `xcode-select --install`）
- JDK 17
- 一个可用的 Gradle 环境（工程自带 `gradlew`，或复用命令行 Gradle）

### 1. 先只编共享层，不要一上来就开 Xcode

```bash
cd StockNoteKMP
./gradlew :shared:core:jvmTest          # 核心算法单测（与平台无关）
./gradlew :shared:feature:linkDebugFrameworkIosSimulatorArm64
```

第二步如果失败，错误一定出在 `iosMain` 里那三个 actual 实现上（见下）。先把它们解决，
再去动 Xcode 工程——否则 Xcode 的报错会把这些信息淹掉。

### 2. iOS 侧曾经的三个待补点（均已处理）

| # | 位置 | 状态 |
|---|------|------|
| 1 | `shared/data/.../iosMain/.../Platform.ios.kt` | ✅ 2026-10-02 接 SQLCipher（SwiftPM 包 + `onConfiguration` 的 `encryptionConfig`），并加了 `PRAGMA cipher_version` 启动自检 |
| 2 | `shared/data/.../iosMain/.../IosSecureKeyStore.kt` | ✅ 2026-10-02 换成 Keychain |
| 3 | `shared/feature/.../iosMain/.../MainViewController.kt` | ✅ 早已跑通（`ComposeUIViewController` + 未捕获异常弹屏取证） |

### 3. 生成 Xcode 工程

```bash
brew install xcodegen
cd iosApp
xcodegen generate
open StockNoteApp.xcodeproj
```

`project.yml` 里已经配好了两件关键的事：

- `FRAMEWORK_SEARCH_PATHS` 指向 Gradle 的 framework 产物目录
- 一个 pre-build script：`./gradlew :shared:feature:embedAndSignAppleFrameworkForXcode`

如果不使用 XcodeGen，也可以手工建工程，但**必须**自己加上第 2 条那个 build phase，
否则 `import SharedUI` 会找不到模块。

### 4. 本次交付预期

- 首次 `linkDebugFrameworkIosSimulatorArm64` 会下载 Kotlin/Native 工具链（约 1~2 GB），
  第一次会比较久，属正常现象。
- 只有 Apple 平台才需要 Kotlin/Native；Android 构建完全不碰它。

---

## 文件说明

| 文件 | 作用 |
|------|------|
| `iosApp/StockNoteApp.swift` | SwiftUI 入口 + `UIViewControllerRepresentable` 包装 |
| `iosApp/Info.plist` | 应用元信息（版本 1.1.8 / 58，与 Android 对齐） |
| `iosApp/Assets.xcassets/` | App 图标（`AppIcon.appiconset/AppIcon-1024.png`，1024 全尺寸单图） |
| `project.yml` | XcodeGen 工程描述（含 SQLCipher 的 SwiftPM 依赖；已随 CI 验证） |

## 与 Android 的对称性

| | Android | iOS |
|---|---|---|
| 入口 | `MainActivity`（~50 行） | `StockNoteApp.swift`（~30 行） |
| 上下文注入 | `StockNoteApplication` 装 `AndroidPlatformContext` | 无需（iOS 无对应概念） |
| 密钥库 | Android Keystore | Keychain（`IosSecureKeyStore`，2026-10-02） |
| 数据库驱动 | SQLCipher（`SupportOpenHelperFactory`） | SQLCipher（SwiftPM 包 + `encryptionConfig`，2026-10-02） |
| 网络引擎 | OkHttp | Darwin |
| UI | 共享 | 共享（同一份 Compose 代码） |
