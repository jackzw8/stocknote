// StockNoteKMP — 跨平台（KMP + Compose Multiplatform）工程
// 本机网络限制：maven.google.com 不可达，dl.google.com 可达。
// 因此 Google Maven 必须由阿里云镜像代为提供，且镜像必须排在官方仓库之前。
//
// ⚠️ 2026-10-01：**CI 与本机必须用两套仓库** —— 这是 iOS 打包在 CI 上"卡死/502"的真凶。
//   · 本机（国内）：走阿里云镜像（上面的原因，保持不变）。
//   · CI（GitHub macOS runner，在**境外**）：官方源又快又稳，而阿里云镜像**常 502 或长时间无响应**。
//   ⚠️ 关键：Gradle 遇到 5xx / 连接挂起**不会自动 fallback 到后面的仓库**，而是**直接失败** ——
//      在 CI 上表现为 `Received status code 502 from server: Bad Gateway`，
//      或长时间干等（看起来就像构建"卡死"，且此时取消指令都难送达）。
//   GitHub Actions 会设环境变量 `CI=true`，据此切换；本机不受任何影响。
//
// ⚠️ 坑（本地实测踩过）：`pluginManagement {}` / `dependencyResolutionManagement {}` 是**独立作用域**
//（Gradle 会把前者**单独提前编译**），**看不到 settings 脚本顶层的局部变量** ——
//  写成顶层 `val onCi = ...` 再在块内引用，会报 `Unresolved reference: onCi`。
//  所以这个判断必须**各自写在块内**。
pluginManagement {
    val onCi: Boolean = System.getenv("CI") == "true"
    repositories {
        if (!onCi) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // ⚠️ 同 pluginManagement：作用域独立，必须在本块内重新判断（见文件头的说明）。
    val onCi: Boolean = System.getenv("CI") == "true"
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!onCi) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "StockNoteKMP"

include(":shared:core")
include(":shared:data")
include(":shared:feature")
include(":androidApp")
// 桌面版（Compose Multiplatform Desktop → Windows exe，老周 2026-09-30）
include(":desktopApp")
