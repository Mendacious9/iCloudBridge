# 构建与发版

主 README 只讲「能做什么、怎么装怎么用」，这一份是给想自己编译或发版的人看的。

## 环境

- **JDK 21** — Kotlin 2.0.x 与 AGP 8.9 **不支持 JDK 25**（新版 Android Studio 自带的
  JBR 就是 25）。`gradle.properties` 里刻意没有写死 JDK 路径，请用 `JAVA_HOME` 指定，
  或在 *Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK*
  里选 21。
- Android SDK，platform 35。

## 编译

```bash
# 1. 告诉 Gradle 你的 SDK 在哪（该文件不入版本库）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2. Debug APK
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# 3. 协议层单元测试
./gradlew :core:icloud:test

# 4. 装到已连接的真机
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 发布签名

release 包从 `keystore/keystore.properties` 读签名配置，该文件不入版本库。
先复制模板再填：

```bash
cp keystore/keystore.properties.example keystore/keystore.properties
./gradlew :app:assembleRelease
```

没有这个文件时 `assembleRelease` 仍会执行，但产出的是**未签名** APK，装不上。

release 与 debug 用的是不同密钥，不能互相 `install -r` 覆盖；而卸载会清掉已保存的
会话（又要重走一遍 2FA）。真机上调试界面时，可以给 debug 包套发布签名：

```bash
./gradlew :app:assembleDebug -PdebugSignWithRelease=true
```

## 发版与线上更新

本应用不上任何商店，所以更新是自己做的：下载新 APK → 校验 → 交给系统安装器。
全程不涉及 Play 的 In-App Updates。

### 发一个版本的流程

1. 版本号**只改一处** —— `app/build.gradle.kts`：

   ```kotlin
   val appVersionCode = 7          // 每次发版必须递增
   val appVersionName = "1.0.5"
   ```

2. 构建并生成清单：

   ```bash
   ./gradlew :app:writeUpdateManifest
   # → app/build/outputs/apk/release/app-release.apk
   # → app/build/outputs/update.json   （versionCode、sha256、体积）
   ```

3. 编辑 `update.json`：把 `apkUrl` 里的 `<owner>/<repo>` 换成你的仓库，填好
   `changelog`；如果这一版必须强制更新，就设置 `minSupportedVersionCode`。
   然后建一个 `v1.0.5` 的 GitHub Release，把 `app-release.apk` 和 `update.json`
   **两个都**作为资源上传。

App 会请求 `GET /repos/{owner}/{repo}/releases/latest`，找到 `update.json` 资源再读它。
任何一环失败（连不上 GitHub、没有 release、没有该资源）都会退回到
`UpdateConfig.MANIFEST_URL` 配的静态地址。两个源都在
`app/src/main/kotlin/cn/yunqiao/icloud/app/update/UpdateManifest.kt` 里配。

### 清单格式

```json
{
  "versionCode": 7,
  "versionName": "1.0.5",
  "apkUrl": "https://github.com/you/iCloudBridge/releases/download/v1.0.5/app-release.apk",
  "sha256": "<64 位十六进制>",
  "sizeBytes": 18930122,
  "minSupportedVersionCode": 6,
  "changelog": { "en": "Faster albums.", "zh-CN": "相册加载更快。" },
  "publishedAt": "2026-11-11"
}
```

参与比较的只有 `versionCode`，`versionName` 只用于显示。`minSupportedVersionCode`
是强制更新的下界：低于它的版本关不掉更新对话框，必须升级。

### 安装前的四道校验

任何一条不通过都会中止安装：

1. **SHA-256** 与清单一致（清单里给了才校验）；
2. **签名证书**必须和手机上已装的一致 —— 这是真正的防线，伪造的包签不出你的证书；
3. **包名**必须一致；
4. APK 里的 **versionCode** 必须等于清单里的值。

下载的包放在应用 cache 里，校验失败立即删除。

## 工程结构

```
icloud-bridge/
├── core/icloud/          纯 Kotlin 协议层 —— 不依赖 Android，可直接在 JVM 上跑
│   └── src/main/kotlin/cn/yunqiao/icloud/
│       ├── srp/          SRP-6a 认证（RFC 5054，2048-bit）
│       ├── auth/         登录、双重认证、会话建立
│       ├── http/         OkHttp 底座 + Cookie 持久化
│       ├── photos/       照片库查询（CloudKit Web Services）
│       ├── drive/        云盘目录与下载
│       ├── store/        会话存储（文件 / 内存）
│       └── tools/        VerifyMain：用真实会话跑全链路联调
├── app/                  Android 客户端（Jetpack Compose，单 Activity）
└── poc/                  M0 阶段的 Python 协议验证，留作可读的参考实现
```

## 测试

```bash
./gradlew :core:icloud:test        # SRP 向量 + 协议解析，纯 JVM
./gradlew :app:testDebugUnitTest   # 更新清单校验
```

仪器测试（会话加密、媒体转码）需要 API 26 以上的设备或模拟器：

```bash
./gradlew :app:connectedAndroidTest
```

## 贡献

1. `core/icloud` 保持零 Android 依赖，它是可移植层。
2. 改协议解析时补单测，`core/icloud/src/test/` 里有 CloudKit 记录形状的样例。
3. **不要提交任何真实的 Apple ID、会话文件、Cookie jar 或签名文件。**
4. 五套 `values*/strings.xml` 同步维护。
