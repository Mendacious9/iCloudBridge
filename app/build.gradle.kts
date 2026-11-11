import java.io.FileInputStream
import java.security.MessageDigest
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

/**
 * 版本号集中在这里定义。
 * 不能只写在 defaultConfig 里 —— `writeUpdateManifest` 任务要读它生成 update.json，
 * 一旦两处各自维护，迟早出现「APK 是 7、清单写 6」这种更新永远推不下去的故障。
 */
val appVersionCode = 7
val appVersionName = "1.0.5"

android {
    namespace = "cn.yunqiao.icloud.app"
    compileSdk = 35
    // 不指定 buildToolsVersion：交给 AGP 8.9 用它的默认版本（35.0.0）。
    // 写死 34.0.0 会让别人机器上多下一套 SDK，还可能和 compileSdk 35 的资源编译对不上。

    defaultConfig {
        applicationId = "cn.yunqiao.icloud.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
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

    // 更新清单的解析、语言回退与强制更新判定都是纯逻辑，放在 JVM 单测里跑，不需要真机
    testImplementation("junit:junit:4.13.2")

    // 转码链路必须在真机/模拟器上跑：MediaCodec 是硬件相关实现，纯 JVM 单测测不到
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

/**
 * 生成线上更新清单 update.json。
 *
 *   ./gradlew :app:writeUpdateManifest
 *   → app/build/outputs/update.json
 *
 * 为什么要让脚本算 sha256：清单里的校验值和 APK 必须是同一份文件算出来的。
 * 手动拷一次哈希，改一次版本就漏改，最后的表现是「下载完提示校验失败」，
 * 而这个错误在发版时完全看不出来。
 *
 * 生成的清单里 apkUrl 是占位地址，发布前把 <owner>/<repo> 换成你的仓库，
 * 并把 update.json 与 APK 一起上传到同一个 GitHub Release。
 * minSupportedVersionCode 默认 0（不强制），要强制更新时改成目标下界。
 */
tasks.register("writeUpdateManifest") {
    group = "distribution"
    description = "生成 update.json（版本号 + sha256 + 体积），随 Release 一起上传"

    // 依赖 assembleRelease：清单必须描述真实的产物，不能拿旧 APK 的哈希
    dependsOn("assembleRelease")

    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    val outputFile = layout.buildDirectory.file("outputs/update.json")

    doLast {
        val apk = apkDir.get().asFile
            .listFiles()
            ?.firstOrNull { it.isFile && it.extension == "apk" }
            ?: error("没找到 release APK：先确认 assembleRelease 成功，且 keystore 配置齐全")

        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val sha256 = digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }

        val json = """
            {
              "versionCode": $appVersionCode,
              "versionName": "$appVersionName",
              "apkUrl": "https://github.com/<owner>/<repo>/releases/download/v$appVersionName/${apk.name}",
              "sha256": "$sha256",
              "sizeBytes": ${apk.length()},
              "minSupportedVersionCode": 0,
              "changelog": {
                "en": "Describe what changed in this version.",
                "zh-CN": "在这里写这个版本改了什么。"
              },
              "publishedAt": "${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}"
            }
        """.trimIndent() + "\n"

        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(json)
        }
        logger.lifecycle("update.json 已生成：${outputFile.get().asFile.absolutePath}")
        logger.lifecycle("sha256=$sha256  size=${apk.length()}  version=$appVersionName($appVersionCode)")
    }
}
