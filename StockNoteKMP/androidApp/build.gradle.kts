// 注意：必须显式 import。在 Gradle .kts 里 `java` 会被 JavaPluginExtension 扩展遮蔽，
// 直接写 java.util.Properties 会报 "Unresolved reference: util"。
// ⚠️ 2026-10-01：我给 debug 时间串写 java.time.LocalDateTime 时**又踩了同一个坑**
//（尽管这行注释就在眼前）—— 教训：写 java.* 之前先看这里。
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

// release 签名：从项目根的 keystore.properties 读取（不存在则 release 不签名，不影响 debug 构建）
val keystorePropsFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropsFile.exists()

android {
    namespace = "com.stocknote.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.stocknote.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // 版本号由老周指定（2026-10-01：1.1.8 ——
        // ① **运行日志**：内存环形缓冲 + 「设置 → 数据管理 → 导出运行日志」，并把行情取数失败、
        //    资产曲线拉取失败等原本被静默吞掉的错误写进日志（排查「拉不到数据」不用再靠猜）；
        // ② **行情双源**：腾讯把 K 线域名 `web.ifzq` 整站下线，改回 `ifzq` 恢复；
        //    并新增**东财 K 线备源**（腾讯拿不到足够 K 线时自动回退，不再整页空白）、配套解析契约测试；
        // ③ **盈亏日历明细**：点某天 → 日历下方展开当日明细（各标的盈亏 + 当日买卖/出入金/分红流水）；
        // ④ iOS 侧同步修复若干（不影响本 APK，同仓库一起发布）；
        // ⑤ **港股当日盈亏接上逐日汇率**（2026-10-01 晚，老周对账）：曲线此前只从 fx_rate 表
        //    取逐日汇率，而用户手工只录了最近 3 天 → 「昨天」与「今天」命中同一条
        //    → 汇率波动被**整段抵消**，港股盈亏与券商差约 1%
        //    （真机对账：腾讯 9-01 本应用 −15,853.95 vs 券商 −15,997.91）。
        //    现主源改为**腾讯外汇日 K**（whHKDCNY / whUSDCNY），落 daily_close、与收盘价同库同机制。
        //
        // ⚠️ 2026-10-01 晚：**升 code、不升 name** ——
        // 本轮只改了代码、没有对外发布新版本，但 debug 包必须能分辨新旧
        //（老周问「你打的 debug 包版本有变吗」）。「关于」页显示的正是
        // `VERSION_NAME (VERSION_CODE)`（见 MainActivity），所以升 code 就够：
        // **1.1.8 (57) → 1.1.8 (58)**，一眼可辨。
        // 版本名仍保持 1.1.8，避免与已发布的 交付/StockNote-release-1.1.8.apk 对不上。
        // ⚠️ 全屏截图/关于页若显示仍是旧的，先确认装的是这个包。
        // versionCode 必须单调递增（56 → 57 → 58），否则实机覆盖安装会失败
        versionCode = 58
        versionName = "1.1.8"

        // 演示数据：**默认不播种**（老周 2026-09-17：debug 版也不要种子数据，
        // 以便真实复现「全新空账本」场景）。需要演示数据时用 -PseedDemo=true 构建。
        val seedDemo = (project.findProperty("seedDemo") as String?)?.toBoolean() ?: false
        buildConfigField("boolean", "SEED_DEMO_DATA", seedDemo.toString())

        // 真机 UI 自动化（androidTest）：UiAutomator 驱动
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ⚠️ 刻意**不加** abiFilters 去掉 x86 / x86_64：
        // 应用宝 PC 版的安卓 VM 是 x86 架构，去掉后本机的装机验证链路直接断掉。
        // 体积优化（省约 4MB）留给正式发布时用 productFlavors 或 AAB 分发解决。
    }

    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                val props = Properties().apply {
                    keystorePropsFile.inputStream().use { load(it) }
                }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    // ⚠️ 2026-10-01（老周要求）：**debug 包的版本名追加构建时间（到分钟）** ——
    // 多次安装的 debug 包能一眼分辨是哪一次打的（上一轮他问「debug 包版本有变吗」的后续）。
    // 显示效果（「设置 → 关于」）：`1.1.8-dbg.20261001-1941 (58)`。
    // ⚠️ 取**构建机本地时间**，每次构建都变 —— 变了才能分辨，这正是目的；
    // 代价是 versionName 变化会让 manifest 重打，debug 构建基本不享受增量，可接受。
    // ⚠️ **release 完全不受影响**（老周：发布包的版本号还是按他说的来）。
    val debugStamp: String = LocalDateTime.now()
        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 老周要求：debug 版本名追加构建时间（到分钟），见上面 debugStamp 的说明。
            versionNameSuffix = "-dbg.$debugStamp"
        }
        release {
            // M0/M1 阶段先不开 R8：Compose + kotlinx.serialization + Ktor 需要一整套 keep 规则，
            // 等业务稳定后再专门做一次混淆验证，避免"为了压缩体积引入一堆运行时崩溃"。
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        // release 版不播种演示数据需要读 BuildConfig.DEBUG（AGP 8 起默认不生成）
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":shared:feature"))
    implementation(libs.androidx.activity.compose)

    // 真机 UI 自动化测试（老周 2026-09-16 要求执行 9/14 的方案）
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
