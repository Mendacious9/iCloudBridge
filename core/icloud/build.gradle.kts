plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // api 而非 implementation：ICloudHttp 的公开方法签名里出现了 OkHttpClient / Response，
    // 消费方（:app）必须能看到这些类型，否则编译期报 "Cannot access class okhttp3.Response"
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

/**
 * M1 联调入口：用 PoC 阶段保存的会话跑一遍 Kotlin 全链路（不落任何新文件到用户目录）。
 *   ./gradlew :core:icloud:verifyLive --args="poc/poc_session you@example.com"
 */
tasks.register<JavaExec>("verifyLive") {
    group = "verification"
    description = "用已有 iCloud 会话验证 Kotlin 协议实现"
    mainClass.set("cn.yunqiao.icloud.tools.VerifyMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}
