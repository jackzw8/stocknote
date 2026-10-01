# StockNoteKMP —— 跨平台股票交易笔记

Kotlin Multiplatform + Compose Multiplatform 单仓库工程。
**一套 Kotlin 代码，Android 与 iOS 双端出包；UI 也是共享的**（不是只共享逻辑）。

- 业务定位：个人交易日记 + 盈亏追踪（轻记账、深复盘）
- 数据模型：**完全离线**、本地 SQLCipher 加密、无账号、无云端、无埋点
- 联网仅用于查询公开行情

---

## 当前进度：M0（可行性验证）已完成骨架

| 里程碑 | 状态 |
|--------|------|
| **M0 可行性验证**（加密库 + 行情 + 自绘图表 + 双端出包） | **骨架已落地，Android 已验证** |
| M1 共享内核完善 | 待开始 |
| M2 记账主流程 | 待开始 |

M0 的验收标准是「把跨平台方案里所有高风险点一次性暴露出来」，
因此这里刻意只做**一条打通的链路**，不做功能堆叠。

---

## 目录结构

```
StockNoteKMP/
├── shared/
│   ├── core/        纯 Kotlin 领域模型 + 算法，零平台依赖，100% 单测
│   ├── data/        SQLDelight + SQLCipher 加密存储 / Ktor 行情 / 平台隔离点
│   └── feature/     共享 Compose UI + 自绘图表 + 状态持有者
├── androidApp/      Android 外壳（~50 行）：Application + MainActivity + 资源
└── iosApp/          iOS 外壳（~30 行 Swift）：UIViewControllerRepresentable
```

依赖方向严格单向：`feature → data → core`。

---

## 版本矩阵（改任何一项都要重新核对）

| 组件 | 版本 | 为什么是这个版本 |
|------|------|------------------|
| Kotlin | 2.3.10 | SQLDelight 2.3.2 的 gradle-plugin 依赖 `kotlin-gradle-plugin:2.3.10`；CMP 1.11 的原生端最低要求也恰好是 2.3.10 |
| Compose Multiplatform | 1.11.1 | 官方文档当前版本；其底层 compose ui 1.11.x 的 `minCompileSdk = 35` |
| SQLDelight | 2.3.2 | 与 Kotlin 2.3.10 咬合（见上） |
| AGP | 8.6.1 | 本机 Gradle 为 8.7，AGP 8.6 是能满足该 Gradle 版本的最高一档；且 API 35 要求 AGP ≥ 8.6.0 |
| Gradle | 8.7 | 本机已有，无需额外下载 |
| compileSdk / targetSdk | 35 | `compose ui 1.11.x` 的 AAR 元数据要求 `minCompileSdk=35`（1.12.x 已跳到 37，故不用） |
| minSdk | 24 | 与上一版一致；自适应图标之外仍需传统 mipmap PNG |
| SQLCipher | `net.zetetic:sqlcipher-android:4.17.0` | 注意是新构件名；老的是 `android-database-sqlcipher`。**不可升到 4.18+**（其 AAR 要求 `minCompileSdk=37`，会卡死 compileSdk 35 的构建） |
| Ktor | 3.5.2 | Android 用 OkHttp、iOS 用 Darwin |
| 图表 | **无三方库** | Compose Canvas 自绘，见下文 |

> 全链路依赖都在阿里云镜像上有货（`maven.google.com` 在本机不可达，`settings.gradle.kts` 已把镜像置于官方仓库之前）。

---

## 构建

### ⚠️ 本机 bash 的 coreutils 不可用

`ls` / `grep` / `tail` / `dirname` 等一律 `command not found`，
所以 **不要用 `gradlew` 或 `gradle` 的 shell 脚本**（脚本内部要调 `uname`）。
一律用 **`gradle.bat`**。

### PowerShell（推荐）

```powershell
$env:JAVA_HOME='D:\AIwork\android-toolchain\jdk'
$env:ANDROID_HOME='D:\AIwork\android-toolchain\sdk'
$env:GRADLE_USER_HOME='D:\AIwork\android-toolchain\gradle-home'
Set-Location 'D:\AIwork\stocknote2\StockNoteKMP'

# 核心算法单测（快，无需 Android）
& 'D:\AIwork\android-toolchain\gradle-8.7\bin\gradle.bat' --console=plain :shared:core:jvmTest

# 打 debug APK
& 'D:\AIwork\android-toolchain\gradle-8.7\bin\gradle.bat' --console=plain :androidApp:assembleDebug
```

### 产物

```
androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

### release 签名

把 `keystore.properties` 放到工程根目录（与 `settings.gradle.kts` 同级）：

```properties
storeFile=../路径/xxx.keystore
storePassword=***
keyAlias=***
keyPassword=***
```

该文件不存在时 release 包不签名，**不影响 debug 构建**。

---

## iOS 目标开关

`gradle.properties` 里的 `stocknote.ios`：

- `auto`（默认）：宿主是 macOS 就启用 iOS 目标，Windows/Linux 自动关闭
- `true` / `false`：强制覆盖

**这是有意为之**：Windows 上无法为 Apple 平台编译 Kotlin/Native，
但 Android 交付绝不能被 iOS 拖累。详见 `iosApp/README.md`。

---

## 关键设计决策

| 决策 | 理由 |
|------|------|
| **图表用 Compose Canvas 自绘** | KMP 生态里成熟图表库极少，引入即带来「只在一个平台能编译」的风险；自绘像素两端一致，且坐标换算可抽纯函数单测 |
| **坐标换算放在 `shared:core`** | `ChartMath` 是纯数学，放领域层就能被单测覆盖。自绘图表最容易出的 bug 就是坐标算错、越界溢出，肉眼很难查 |
| **不引 kotlinx-datetime 进领域层** | 领域层只需要「差多少天」，自己实现 `CivilDate` 反而零依赖、两端行为绝对一致、100% 可测 |
| **数据层用 SQLDelight 而非 Room** | KMP 场景下 SQLDelight 更成熟；SQL 与既有表结构可照搬 |
| **行情用腾讯 ifzq JSON 接口** | 常用实时接口 `qt.gtimg.cn` 返回 **GBK**，两端各要一套解码；这个接口是 UTF-8 JSON，直接省掉一个平台隔离点 |
| **加密口令绝不明文落盘** | Keystore 持有 AES 密钥 → 加密随机口令 → 只有密文落盘 |
| **解密失败不自动重建口令** | 重建会让已有账本永久不可读，宁可给明确报错 |
| **交易为唯一事实源** | 持仓由全量重放推导；删改交易后不做增量修补，避免移动加权成本累积误差 |
| **M0 不用 DI 框架** | 真实依赖只有 4 个，引 Koin 只会增加排查成本；接口已按构造注入设计，可随时替换 |

## 平台隔离点 —— 全方案只有四处

| 隔离点 | Android | iOS |
|--------|---------|-----|
| 数据库驱动 | SQLCipher（已接） | 待接 |
| 密钥库 | Android Keystore（已接） | 待接 Keychain |
| 网络引擎 | OkHttp（已接） | Darwin |
| 导出与分享 | SAF | UIActivityViewController（M5） |

---

## 构建结果（M0 已验证）

| 校验项 | 结果 |
|--------|------|
| `:shared:core:jvmTest` | 33 个用例，0 失败 0 错误 |
| `:androidApp:assembleDebug` | BUILD SUCCESSFUL |
| debug APK | ≈ 20.4 MB（未开 R8 + 四个 ABI 的 libsqlcipher） |
| 包名 / 版本 | `com.stocknote.app` · 2.0.0 (2) |
| SDK 三值 | compileSdk 35 / minSdk 24 / targetSdk 35 |
| 签名 | apksigner 验证通过（v2 方案） |
| SQLCipher 原生库 | arm64-v8a / armeabi-v7a / x86 / x86_64 均已入包 |

---

## 已知边界（M0 明确不做的事）

1. **iOS 全链路未验证**：无 macOS，Kotlin/Native 无法链接 Apple 产物。
2. **iOS 的 SQLCipher 与 Keychain 是待办**：当前 iOS 侧库未加密、口令明文存 NSUserDefaults。\
   这两点是 **iOS 可交付的阻塞项**，但不影响 Android。
3. **无迁移机制**：表结构一旦改动要重建库（M2 补 schema 版本与 migration）。
4. **建库与种子数据跑在主线程**：十几行数据可忽略，正式版要移到后台。
5. **未开 R8**：Compose + kotlinx.serialization + Ktor 需要一整套 keep 规则，
   等业务稳定后专门做一次混淆验证，避免"为压体积引入运行时崩溃"。
6. **导航是手写状态机**：M0 只有三个页面，引入 Navigation 库属于过早优化。
