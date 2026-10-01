# iosApp —— iOS 外壳（待 macOS 验证）

## 当前状态：**未验证**

构建机是 Windows，而 Kotlin/Native **只能在 macOS 上**链接 Apple 平台产物。
所以本目录下的代码目前全部属于「写好了但没编译过」。这是 M0 的已知边界，
不影响 Android 交付。

同时，`gradle.properties` 里的 `stocknote.ios` 开关在 Windows 上会让 iOS 目标**不注册**，
从而保证 Android 构建永远不被 iOS 拖累。到了 macOS 上它会自动变成 `true`，无需手工改。

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

### 2. 三个 iOS 待补点（按优先级）

| # | 位置 | 待办 | 影响 |
|---|------|------|------|
| 1 | `shared/data/.../iosMain/.../Platform.ios.kt` | 接 SQLCipher：Podfile 加 `pod 'SQLCipher'`，打开连接后 `PRAGMA key`。当前是 `NativeSqliteDriver`，**库未加密** | 阻塞 iOS 可交付 |
| 2 | `shared/data/.../iosMain/.../IosSecureKeyStore.kt` | 用 Keychain（`SecItemAdd` / `SecItemCopyMatching`）替换 NSUserDefaults 占位 | 阻塞 iOS 可交付 |
| 3 | `shared/feature/.../iosMain/.../MainViewController.kt` | 首次编译验证（`ComposeUIViewController` 接法） | 阻塞 iOS 可启动 |

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
| `iosApp/Info.plist` | 应用元信息（版本 2.0.0 / Bundle 2，与 Android 对齐） |
| `project.yml` | XcodeGen 工程描述（未验证） |

## 与 Android 的对称性

| | Android | iOS |
|---|---|---|
| 入口 | `MainActivity`（~50 行） | `StockNoteApp.swift`（~30 行） |
| 上下文注入 | `StockNoteApplication` 装 `AndroidPlatformContext` | 无需（iOS 无对应概念） |
| 密钥库 | Android Keystore | 待接 Keychain |
| 数据库驱动 | SQLCipher（已接） | 待接 SQLCipher |
| 网络引擎 | OkHttp | Darwin |
| UI | 共享 | 共享（同一份 Compose 代码） |
