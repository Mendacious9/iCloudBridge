import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 发布签名配置从 keystore/keystore.properties 读取（该文件不入版本库）。
 * 文件不存在时 release 构建退回未签名——不影响日常 debug 开发，
 * 但要出装机包必须补齐，否则 `assembleRelease` 产出的 APK 装不上。
 */
val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

/**
 * 构建日期写进 BuildConfig：设置里的「关于 APP → 更新日期」直接读它。
 * 手写常量一定会过期，而 PackageManager 的 lastUpdateTime 会随重装变化，
 * 都不如构建时间准确。
 */
val buildDate: String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/**
 * 仅用于真机自测：`-PdebugSignWithRelease=true` 时给 debug 包套上发布签名。
 *
 * 发布包和 debug 包签名不同，覆盖安装必须先卸载，而卸载会丢掉已登录的 iCloud 会话
 * （又要走一遍 2FA）。打开这个开关就能把 debug 包直接盖到手机上验证手势、不再重新登录。
 * 默认关闭，不做任何事——正式包永远只由 release 变体产出。
 */
val debugSignWithRelease = providers.gradleProperty("debugSignWithRelease")
    .getOrElse("false")
    .toBoolean()

android {
    namespace = "cn.yunqiao.icloud.app"
    compileSdk = 35
    // 不指定 buildToolsVersion：交给 AGP 8.9 用它的默认版本（35.0.0）。
    // 写死 34.0.0 会让别人机器上多下一套 SDK，还可能和 compileSdk 35 的资源编译对不上。

    defaultConfig {
        applicationId = "cn.yunqiao.icloud.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 只出 v2/v3。v1（JAR signing）是 Android 7 以下才需要的方案，
                // 本应用 minSdk = 26，AGP 也不会为它生成 v1 —— 别在这儿写
                // enableV1Signing = true，那是个不生效的开关，验证时会误导人。
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false      // 反射 + kotlinx.serialization 全量保留，先求稳定
            isShrinkResources = false
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ""
            // 自测用，见文件顶部的 debugSignWithRelease 说明
            if (debugSignWithRelease && hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        // 关闭的默认值：AGP 8 起 buildConfig 默认不生成，而「关于 APP」要读版本号与构建日期
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }
}

dependencies {
    implementation(project(":core:icloud"))
    // 会话加密落盘要把 SessionState / StoredCookie 序列化成 JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 生物识别解锁：BiometricPrompt 需要 FragmentActivity 宿主
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 转码链路必须在真机/模拟器上跑：MediaCodec 是硬件相关实现，纯 JVM 单测测不到
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
