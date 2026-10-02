import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlinSerialization)
}

val iosEnabled: Boolean = when (providers.gradleProperty("stocknote.ios").orNull?.lowercase()) {
    "true" -> true
    "false" -> false
    else -> System.getProperty("os.name").startsWith("Mac")
}

kotlin {
    jvmToolchain(17)

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // ⚠️ 2026-09-29：新增 JVM target —— **只为测试**。
    // data 层此前完全没有行为测试（它对 PortfolioRepository 只有静态检查类），
    // 而账本数字错了很难发现（H2 跨币种混加 / H3 送股不进曲线都是实例）。
    // 加 jvm target 后可以用 JdbcSqliteDriver 建**真库**跑契约测试。
    // 代价：CommonMain 的 7 个 expect 需要补 jvm actual（见 jvmMain）。
    jvm()

    if (iosEnabled) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            // api 而非 implementation：仓储的公开签名里带 core 的领域类型，必须向上传递
            api(project(":shared:core"))
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // L12 修复（2026-09-27）：BackupCoverageTest 用 `java.io.File` 读源码目录，
        // 留在 commonTest 会让 **iOS 目标编译失败**（`java.io` 在 Kotlin/Native 不存在），
        // 故移到 androidUnitTest —— 这里补上同样的测试依赖。
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.ktor.client.okhttp)
            // SQLCipher：加密 SQLite。注意这是 zetetic 的新构件名（老的是 android-database-sqlcipher）
            implementation(libs.sqlcipher.android)
        }

        // ⚠️ JVM 侧：**仅为测试服务**（见上面 jvm() 的说明）。
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            // JVM 上复用 okhttp 引擎（libs 里已有，无需新依赖）
            implementation(libs.ktor.client.okhttp)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            // P1-3（2026-10-02）：分红「错误体」用例要构造 **HTTP 200 + 指定响应体**，
            // 只能靠 MockEngine（真接口触发不了限流/错误体）。版本跟 ktor 一致，**仅测试**、不进产物。
            implementation(libs.ktor.client.mock)
        }
    }
}

// 用 matching/configureEach 而不是直接写 iosMain.dependencies：
// 保证在未声明 iOS 目标的宿主（Windows）上构建脚本依然成立。
//
// ⚠️⚠️ 2026-09-30 修复（首次 iOS 打包 `Unresolved reference 'driver' / 'darwin'` 的**根因**）：
// **必须写成 `kotlin.sourceSets`**。裸 `sourceSets` 解析到的是 Java/AGP 注册的那一个
//（它里面只有 main / test），于是 `matching { it.name == "iosMain" }` **永远匹配 0 个元素**，
// `configureEach` 从不触发 → native-driver / ktor-darwin **压根没进 iOS 编译 classpath**。
// 症状极其隐蔽：依赖"看起来声明了"、Gradle 配置阶段也不报错，只在真正编译 iOS 时才炸。
kotlin.sourceSets.matching { it.name == "iosMain" }.configureEach {
    dependencies {
        implementation(libs.sqldelight.native.driver)
        implementation(libs.ktor.client.darwin)
    }
}

sqldelight {
    databases {
        create("StockNoteDb") {
            packageName.set("com.stocknote.data.db")
            // M0 不做迁移；表结构定型后在此登记 schema 版本与 migration
        }
    }
}

android {
    namespace = "com.stocknote.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
